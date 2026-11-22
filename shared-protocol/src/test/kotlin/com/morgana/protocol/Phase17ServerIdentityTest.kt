package com.morgana.protocol

import com.morgana.protocol.tls.FileKnownHosts
import com.morgana.protocol.tls.Fingerprint
import com.morgana.protocol.tls.InMemoryKnownHosts
import com.morgana.protocol.tls.InviteCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase17ServerIdentityTest {

    private val fp = "ab".repeat(32)

    @Test
    fun `fingerprints normalize any common spelling and reject everything else`() {
        assertEquals(fp, Fingerprint.normalize(fp))
        assertEquals(fp, Fingerprint.normalize(fp.uppercase()))
        assertEquals(fp, Fingerprint.normalize(fp.chunked(2).joinToString(":")))
        assertEquals(fp, Fingerprint.normalize(" " + fp.chunked(4).joinToString(" ") + " "))
        assertNull(Fingerprint.normalize("ab".repeat(31)))
        assertNull(Fingerprint.normalize("zz".repeat(32)))
        assertNull(Fingerprint.normalize(""))
        assertEquals("AB:AB:AB", Fingerprint.display("ababab"))
    }

    @Test
    fun `invite codes round trip with host names, IPv4 and IPv6`() {
        for (host in listOf("play.example.com", "203.0.113.5", "2001:db8::1")) {
            val code = InviteCode(host, 7005, fp)
            assertEquals(code, InviteCode.parse(code.format()), host)
        }
        assertEquals("morgana://203.0.113.5:7001#fp=$fp", InviteCode("203.0.113.5", 7001, fp).format())
        assertEquals("morgana://[::1]:7001", InviteCode("::1", 7001).format())
    }

    @Test
    fun `plain addresses parse with the default port and no fingerprint`() {
        assertEquals(InviteCode("example.com", 7001), InviteCode.parse("example.com"))
        assertEquals(InviteCode("example.com", 9000), InviteCode.parse("  example.com:9000 "))
        assertEquals(InviteCode("::1", 7001), InviteCode.parse("[::1]"))
        assertEquals(InviteCode("::1", 8000), InviteCode.parse("[::1]:8000"))
        assertEquals(InviteCode("host", 7001, fp), InviteCode.parse("MORGANA://host#fp=${fp.uppercase()}"))
        assertEquals(InviteCode("host", 7001, fp), InviteCode.parse("morgana://host/#fp=" + fp.chunked(2).joinToString("%3A")))
    }

    @Test
    fun `malformed codes are rejected instead of guessed`() {
        for (bad in listOf("", "   ", "morgana://", ":7001", "host:0", "host:70000", "host:abc", "host name", "host#fp=123",
            "host#other=$fp", "morgana://host:7001#fp=", "[::1", "a/b")) {
            assertNull(InviteCode.parse(bad), "should reject '$bad'")
        }
    }

    @Test
    fun `known hosts remember, forget, and survive a restart`(@TempDir dir: Path) {
        val file = dir.resolve("nested/known_hosts.txt")
        val store = FileKnownHosts(file)
        assertNull(store.fingerprintFor("Example.com", 7001))
        store.remember("Example.com", 7001, fp)
        store.remember("other", 7002, "cd".repeat(32))
        assertEquals(fp, store.fingerprintFor("example.COM", 7001), "host names are case-insensitive")
        assertNull(store.fingerprintFor("example.com", 7002), "the port is part of the identity")

        val reloaded = FileKnownHosts(file)
        assertEquals(fp, reloaded.fingerprintFor("example.com", 7001))
        reloaded.forget("example.com", 7001)
        assertNull(FileKnownHosts(file).fingerprintFor("example.com", 7001))
        assertEquals("cd".repeat(32), FileKnownHosts(file).fingerprintFor("other", 7002))
    }

    @Test
    fun `corrupt lines in the known hosts file are ignored`(@TempDir dir: Path) {
        val file = dir.resolve("known_hosts.txt")
        Files.writeString(file, "garbage\nhost:1 nothex\nhost:2 $fp\n\none two three\n")
        val store = FileKnownHosts(file)
        assertEquals(fp, store.fingerprintFor("host", 2))
        assertNull(store.fingerprintFor("host", 1))
    }

    @Test
    fun `in-memory known hosts behave the same way`() {
        val store = InMemoryKnownHosts()
        store.remember("h", 1, fp)
        assertTrue(store.fingerprintFor("H", 1) == fp)
        store.forget("h", 1)
        assertNull(store.fingerprintFor("h", 1))
    }
}
