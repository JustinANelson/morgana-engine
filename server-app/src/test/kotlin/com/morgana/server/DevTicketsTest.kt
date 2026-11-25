package com.morgana.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DevTicketsTest {
    @TempDir
    lateinit var dir: Path

    private var now = 1_000_000L
    private fun tickets() = DevTickets(dir, clock = { now })

    @Test
    fun aTicketWorksOnceForItsAccount() {
        val t = tickets()
        val ticket = t.mint("Alice")
        assertTrue(ticket.startsWith("dev_"))
        assertEquals("Alice", t.redeem(ticket))
        assertNull(t.redeem(ticket), "second use must fail")
    }

    @Test
    fun anExpiredTicketIsRefused() {
        val t = tickets()
        val ticket = t.mint("Alice", ttlSeconds = 30)
        now += 31_000
        assertNull(t.redeem(ticket))
    }

    @Test
    fun onlyTheHashIsStoredOnDisk() {
        val ticket = tickets().mint("Alice")
        val files = Files.list(dir.resolve(DevTickets.DIR)).use { it.toList() }
        assertEquals(1, files.size)
        assertFalse(files.single().fileName.toString().contains(ticket))
        assertFalse(Files.readString(files.single()).contains(ticket))
    }

    @Test
    fun guessedOrMalformedTicketsAreRefused() {
        val t = tickets()
        t.mint("Alice")
        assertNull(t.redeem("dev_" + "A".repeat(32)))
        assertNull(t.redeem("not-a-ticket"))
        assertNull(t.redeem("dev_" + "x".repeat(200)))
        assertNull(t.redeem("dev_../../owners"))
    }

    @Test
    fun purgingDropsExpiredTicketsAndKeepsLiveOnes() {
        val t = tickets()
        t.mint("Old", ttlSeconds = 10)
        now += 20_000
        val live = t.mint("New")
        assertEquals(1, Files.list(dir.resolve(DevTickets.DIR)).use { it.count() })
        assertNotNull(t.redeem(live))
    }

    @Test
    fun rejectsBadNamesAndLifetimes() {
        assertThrows(IllegalArgumentException::class.java) { tickets().mint("x") }
        assertThrows(IllegalArgumentException::class.java) { tickets().mint("Alice", 0) }
    }
}
