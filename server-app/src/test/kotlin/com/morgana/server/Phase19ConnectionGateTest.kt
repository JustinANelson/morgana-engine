package com.morgana.server

import com.morgana.persistence.AddressRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

class Phase19ConnectionGateTest {

    private var now = 1_000_000L
    private fun ip(text: String): InetAddress = AddressRange.literal(text)!!

    private fun gate(
        settings: ConnectionGateSettings = ConnectionGateSettings(),
        banFile: Path? = null
    ) = ConnectionGate(settings, { now }, banFile)

    private val minute = 60_000L

    // ---- caps --------------------------------------------------------------------------------------

    @Test
    fun `an address gets a fixed number of simultaneous connections and a closed one frees a slot`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 3))
        val a = ip("203.0.113.5")
        repeat(3) { assertEquals(GateVerdict.ALLOW, g.admit(a)) }
        assertEquals(GateVerdict.TOO_MANY_FROM_ADDRESS, g.admit(a))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("203.0.113.6")), "another address is independent")
        g.release(a)
        assertEquals(GateVerdict.ALLOW, g.admit(a))
        assertEquals(1L, g.refusedCounts()[GateVerdict.TOO_MANY_FROM_ADDRESS])
        assertTrue(g.bans().isEmpty(), "hitting the cap is not abuse, so it never leads to a ban")
    }

    @Test
    fun `a server-wide cap refuses everyone without penalizing them`() {
        val g = gate(ConnectionGateSettings(maxTotal = 4, maxPerIp = 10))
        repeat(4) { assertEquals(GateVerdict.ALLOW, g.admit(ip("198.51.100.$it"))) }
        assertEquals(GateVerdict.SERVER_FULL, g.admit(ip("198.51.100.99")))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("127.0.0.1")), "this machine can always connect")
        assertTrue(g.bans().isEmpty())
        // The loopback connection still occupies a socket, so room appears only once two connections have closed.
        g.release(ip("198.51.100.0"))
        assertEquals(GateVerdict.SERVER_FULL, g.admit(ip("198.51.100.99")))
        g.release(ip("127.0.0.1"))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("198.51.100.99")))
    }

    @Test
    fun `IPv6 clients are limited per 64-bit block, so rotating addresses does not help`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 2))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("2001:db8:1:2::1")))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("2001:db8:1:2:abcd::9")))
        assertEquals(GateVerdict.TOO_MANY_FROM_ADDRESS, g.admit(ip("2001:db8:1:2:ffff:ffff:ffff:ffff")))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("2001:db8:1:3::1")), "a different /64 is a different client")
        assertEquals("2001:db8:1:2:0:0:0:0/64", ConnectionGate.keyOf(ip("2001:db8:1:2::77")))
        assertEquals("203.0.113.9", ConnectionGate.keyOf(ip("::ffff:203.0.113.9")), "IPv4-mapped addresses count as IPv4")
    }

    @Test
    fun `loopback and allow-listed addresses are never limited, struck or banned`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 1, burst = 1, strikesToBan = 1, allow = listOf("10.1.0.0/16", "2001:db8:aaaa::/48").map { AddressRange.parse(it)!! }))
        for (addr in listOf("127.0.0.1", "::1", "10.1.200.7", "2001:db8:aaaa:5::1")) {
            repeat(50) { assertEquals(GateVerdict.ALLOW, g.admit(ip(addr)), addr) }
            repeat(10) { g.strike(ip(addr), "flood") }
            assertEquals(GateVerdict.ALLOW, g.admit(ip(addr)), "$addr still allowed after strikes")
        }
        assertNull(g.ban(ip("10.1.2.3"), 60, "manual"), "exempt addresses cannot be banned")
        assertEquals(GateVerdict.ALLOW, g.admit(ip("10.1.2.3")))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("10.2.0.1")), "outside the block is limited normally (first connection)")
        assertEquals(GateVerdict.TOO_MANY_FROM_ADDRESS, g.admit(ip("10.2.0.1")))
        assertTrue(g.bans().isEmpty())
    }

    // ---- connection rate and bans -----------------------------------------------------------------

    @Test
    fun `reconnecting in a loop is refused after a burst and then earns a temporary ban`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 100, burst = 5, newPerMinute = 30, strikesToBan = 3, banMinutes = 10))
        val a = ip("203.0.113.50")
        val banned = mutableListOf<String>()
        g.onBan = { banned += it }
        repeat(5) { assertEquals(GateVerdict.ALLOW, g.admit(a)); g.release(a) }
        assertEquals(GateVerdict.TOO_FAST, g.admit(a))
        assertEquals(GateVerdict.TOO_FAST, g.admit(a))
        assertTrue(banned.isEmpty())
        assertEquals(GateVerdict.TOO_FAST, g.admit(a), "third strike")
        assertEquals(listOf("203.0.113.50"), banned, "listeners are told so live connections can be closed")
        assertEquals(GateVerdict.BANNED, g.admit(a))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("203.0.113.51")), "neighbors are unaffected")

        now += 9 * minute
        assertEquals(GateVerdict.BANNED, g.admit(a))
        now += 2 * minute
        assertEquals(GateVerdict.ALLOW, g.admit(a), "the ban expired")
    }

    @Test
    fun `checking for a ban costs nothing, so page downloads on the browser port are never counted`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 100, burst = 2, newPerMinute = 1, strikesToBan = 1, banMinutes = 10))
        val a = ip("100.83.233.117")
        repeat(50) { assertFalse(g.isBanned(a)) }
        assertEquals(GateVerdict.ALLOW, g.admit(a), "isBanned used no tokens")
        assertEquals(GateVerdict.ALLOW, g.admit(a))
        assertEquals(GateVerdict.TOO_FAST, g.admit(a))
        assertTrue(g.isBanned(a))
        assertFalse(g.isBanned(ip("127.0.0.1")), "this machine is never banned")
        now += 11 * minute
        assertFalse(g.isBanned(a))
    }

    @Test
    fun `tokens refill over time so honest reconnects are fine`() {
        val g = gate(ConnectionGateSettings(burst = 3, newPerMinute = 60, maxPerIp = 100))
        val a = ip("203.0.113.60")
        repeat(3) { assertEquals(GateVerdict.ALLOW, g.admit(a)) }
        assertEquals(GateVerdict.TOO_FAST, g.admit(a))
        now += 1_000 // one token per second
        assertEquals(GateVerdict.ALLOW, g.admit(a))
    }

    @Test
    fun `strikes from elsewhere in the server lead to a ban, but old strikes are forgotten`() {
        val g = gate(ConnectionGateSettings(strikesToBan = 3))
        val a = ip("203.0.113.70")
        g.strike(a, "packet flood"); g.strike(a, "packet flood")
        now += ConnectionGate.STRIKE_WINDOW_MS + 1
        g.strike(a, "packet flood") // the first two aged out
        assertEquals(GateVerdict.ALLOW, g.admit(a))
        g.strike(a, "failed logins"); g.strike(a, "failed logins")
        assertEquals(GateVerdict.BANNED, g.admit(a))
        assertEquals("failed logins", g.bans().single().reason)
    }

    @Test
    fun `repeat offenders are banned for twice as long each time, up to a maximum, and good behavior resets it`() {
        val g = gate(ConnectionGateSettings(strikesToBan = 1, banMinutes = 10, maxBanMinutes = 60))
        val a = ip("203.0.113.80")
        fun banLength(): Long { g.strike(a, "x"); val b = g.bans().single(); return (b.untilMillis - now) / minute }
        assertEquals(10L, banLength()); now += 11 * minute
        assertEquals(20L, banLength()); now += 21 * minute
        assertEquals(40L, banLength()); now += 41 * minute
        assertEquals(60L, banLength(), "capped at the maximum"); now += 61 * minute
        now += ConnectionGate.LEVEL_DECAY_MS
        assertEquals(10L, banLength(), "a day without trouble starts over")
    }

    // ---- operator bans ---------------------------------------------------------------------------

    @Test
    fun `operators can ban and unban, and a ban covers the whole IPv6 block`() {
        val g = gate()
        val closed = mutableListOf<String>()
        g.onBan = { closed += it }
        val ban = g.ban(ip("2001:db8:5:6::abc"), 30, "griefing")
        assertNotNull(ban)
        assertEquals(listOf("2001:db8:5:6:0:0:0:0/64"), closed)
        assertEquals(GateVerdict.BANNED, g.admit(ip("2001:db8:5:6::1")))
        assertTrue(g.unban(ip("2001:db8:5:6::999")))
        assertFalse(g.unban(ip("2001:db8:5:6::999")))
        assertEquals(GateVerdict.ALLOW, g.admit(ip("2001:db8:5:6::1")))
        assertEquals(43_200L * minute, g.ban(ip("1.2.3.4"), 10_000_000, "x")!!.untilMillis - now, "manual bans are capped at 30 days")
        assertNull(g.ban(ip("127.0.0.1"), 5, "no"))
    }

    @Test
    fun `bans survive a restart, expired ones are dropped, and a damaged file is ignored`(@TempDir dir: Path) {
        val file = dir.resolve("ip-bans.json")
        val first = gate(banFile = file)
        first.ban(ip("203.0.113.90"), 60, "persisted")
        first.ban(ip("203.0.113.91"), 1, "short")

        now += 5 * minute
        val second = gate(banFile = file)
        assertEquals(GateVerdict.BANNED, second.admit(ip("203.0.113.90")))
        assertEquals(GateVerdict.ALLOW, second.admit(ip("203.0.113.91")), "expired while the server was down")
        assertEquals(listOf("203.0.113.90"), second.bans().map { it.key })

        Files.writeString(file, "{ not valid")
        assertTrue(gate(banFile = file).bans().isEmpty())
        assertEquals(GateVerdict.ALLOW, gate(banFile = file).admit(ip("203.0.113.90")))
    }

    @Test
    fun `disabled limits admit everyone`() {
        val g = gate(ConnectionGateSettings(enabled = false, maxPerIp = 1, burst = 1))
        repeat(100) { assertEquals(GateVerdict.ALLOW, g.admit(ip("203.0.113.100"))) }
        g.strike(ip("203.0.113.100"), "x")
        assertTrue(g.bans().isEmpty())
    }

    @Test
    fun `memory stays bounded when many different addresses connect once`() {
        val g = gate(ConnectionGateSettings(maxPerIp = 5))
        for (i in 0 until 5000) {
            val a = ip("10.${(i shr 8) and 255}.${i and 255}.7").let { InetAddress.getByAddress(byteArrayOf(100.toByte(), ((i shr 8) and 255).toByte(), (i and 255).toByte(), 7)) }
            assertEquals(GateVerdict.ALLOW, g.admit(a))
            g.release(a)
            if (i % 100 == 0) now += 16 * minute // time passes, idle entries become forgettable
        }
        assertTrue(g.trackedAddresses() < 1200, "tracked=${g.trackedAddresses()}")
        assertEquals(0, g.activeConnections())
    }

    // ---- address ranges ---------------------------------------------------------------------------

    @Test
    fun `address ranges match addresses and CIDR blocks and never resolve host names`() {
        assertTrue(AddressRange.parse("10.0.0.0/8")!!.contains(ip("10.255.1.1")))
        assertFalse(AddressRange.parse("10.0.0.0/8")!!.contains(ip("11.0.0.1")))
        assertTrue(AddressRange.parse("192.168.1.77")!!.contains(ip("192.168.1.77")))
        assertFalse(AddressRange.parse("192.168.1.77")!!.contains(ip("192.168.1.78")))
        assertTrue(AddressRange.parse("0.0.0.0/0")!!.contains(ip("8.8.8.8")))
        assertFalse(AddressRange.parse("0.0.0.0/0")!!.contains(ip("2001:db8::1")), "IPv4 and IPv6 are separate")
        assertTrue(AddressRange.parse("2001:db8::/32")!!.contains(ip("2001:db8:ffff::1")))
        assertTrue(AddressRange.parse("172.16.0.0/12")!!.contains(ip("172.31.255.255")))
        assertFalse(AddressRange.parse("172.16.0.0/12")!!.contains(ip("172.32.0.1")))
        for (bad in listOf("", "example.com", "10.0.0.0/33", "10.0.0/8", "300.1.1.1", "10.0.0.0/x", "::1/129", "10.0.0.1/-1")) {
            assertNull(AddressRange.parse(bad), "should reject '$bad'")
        }
        assertNull(AddressRange.literal("localhost"))
    }
}
