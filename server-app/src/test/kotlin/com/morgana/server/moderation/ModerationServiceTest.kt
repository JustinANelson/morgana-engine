package com.morgana.server.moderation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModerationServiceTest {
    private var now = 1_000_000L
    private fun service(dir: Path) = ModerationService(dir) { now }
    private val minute = 60_000L

    @Test
    fun `durations parse to milliseconds and nonsense is refused`() {
        assertEquals(30 * minute, ModerationService.parseDuration("30m"))
        assertEquals(12 * 60 * minute, ModerationService.parseDuration("12h"))
        assertEquals(7 * 24 * 60 * minute, ModerationService.parseDuration("7D"))
        assertEquals(0L, ModerationService.parseDuration("perm"))
        for (bad in listOf("", "30", "m", "0m", "-5m", "1.5h", "abc", "99999999d", "10y")) assertNull(ModerationService.parseDuration(bad), bad)
    }

    @Test
    fun `descriptions say how long is left`() {
        assertEquals("permanent", ModerationService.describeDuration(0, now))
        assertEquals("15 min", ModerationService.describeDuration(now + 15 * minute, now))
        assertEquals("2 h 5 min", ModerationService.describeDuration(now + 125 * minute, now))
        assertEquals("2 d 3 h", ModerationService.describeDuration(now + (2 * 1440 + 3 * 60) * minute, now))
    }

    @Test
    fun `a ban lasts for its time, survives a restart and can be lifted`(@TempDir dir: Path) {
        val s = service(dir)
        s.sanction(SanctionKind.BAN, "a1", "Pat", "spam", "Boss", 30 * minute)
        assertEquals("spam", s.active(SanctionKind.BAN, "a1")!!.reason)
        assertNull(s.active(SanctionKind.MUTE, "a1"), "a ban is not a mute")

        val restarted = service(dir)
        assertNotNull(restarted.active(SanctionKind.BAN, "a1"))
        now += 31 * minute
        assertNull(restarted.active(SanctionKind.BAN, "a1"), "expired")
        assertTrue(restarted.activeList(SanctionKind.BAN).isEmpty())

        restarted.sanction(SanctionKind.BAN, "a1", "Pat", "again", "Boss", 0)
        now += 1000 * 24 * 60 * minute
        assertNotNull(restarted.active(SanctionKind.BAN, "a1"), "permanent never expires")
        assertTrue(restarted.lift(SanctionKind.BAN, "a1", "Pat", "Boss"))
        assertFalse(restarted.lift(SanctionKind.BAN, "a1", "Pat", "Boss"), "nothing left to lift")
    }

    @Test
    fun `a newer sanction replaces the older one of the same kind`(@TempDir dir: Path) {
        val s = service(dir)
        s.sanction(SanctionKind.MUTE, "a1", "Pat", "first", "Boss", 5 * minute)
        s.sanction(SanctionKind.MUTE, "a1", "Pat", "second", "Boss", 10 * minute)
        assertEquals(listOf("second"), s.activeList(SanctionKind.MUTE).map { it.reason })
    }

    @Test
    fun `reports are queued, rate limited and closed by moderators`(@TempDir dir: Path) {
        val s = service(dir)
        val first = s.report("r1", "Quinn", "Pat", "cheating") as ModerationService.ReportResult.Filed
        assertEquals(1, first.report.id)
        assertTrue(s.report("r1", "Quinn", "Pat", "again") is ModerationService.ReportResult.Refused, "too fast")
        now += ModerationService.REPORT_COOLDOWN_MS
        assertEquals(2, (s.report("r1", "Quinn", "Pat", "again") as ModerationService.ReportResult.Filed).report.id)
        assertEquals(2, service(dir).openReports().size, "survives a restart")

        assertNull(s.closeReport(99, "Boss", ""))
        assertEquals(ReportStatus.CLOSED, s.closeReport(1, "Boss", "warned")!!.status)
        assertEquals(listOf(2), s.openReports().map { it.id })
        assertNull(s.closeReport(1, "Boss", ""), "already closed")
    }

    @Test
    fun `every action lands in the audit log, which can be filtered`(@TempDir dir: Path) {
        val s = service(dir)
        s.sanction(SanctionKind.MUTE, "a1", "Pat", "spam", "Boss", 5 * minute)
        s.lift(SanctionKind.MUTE, "a1", "Pat", "Mona")
        s.audit("Boss", "kick", "Quinn", "afk")
        val all = s.recentAudit()
        assertEquals(listOf("mute", "unmute", "kick"), all.map { it.action })
        assertEquals(listOf("mute", "unmute"), s.recentAudit("pat").map { it.action })
        assertEquals(listOf("unmute"), s.recentAudit("Mona").map { it.action })
        assertEquals(1, s.recentAudit(limit = 1).size)
        assertTrue(Files.readAllLines(dir.resolve(ModerationService.LOG_FILE)).all { it.startsWith("{") }, "one JSON object per line")
    }

    @Test
    fun `an unreadable state file is kept aside, not silently dropped`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(ModerationService.STATE_FILE), "{ not json")
        val s = service(dir)
        assertTrue(s.activeList(SanctionKind.BAN).isEmpty())
        assertTrue(Files.exists(dir.resolve("moderation.broken.json")))
    }
}
