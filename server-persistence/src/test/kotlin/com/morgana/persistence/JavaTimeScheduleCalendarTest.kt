package com.morgana.persistence

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.game.UtcScheduleCalendar
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/** The server's full calendar: time zones and daylight saving, and agreement with the shared domain's pure UTC calendar. */
class JavaTimeScheduleCalendarTest {
    private val calendar = JavaTimeScheduleCalendar
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val cleansing = ScheduleDefinition(
        id = "cleansing", name = "Weekly Cleansing", kind = ScheduleKind.WEEKLY, timezone = "America/New_York",
        dayOfWeek = "SATURDAY", time = "10:00", warningsSeconds = listOf(3600, 900, 60)
    )

    @Test
    fun `a weekly schedule lands on its weekday and local time`() {
        // Saturday 28 November 2026, 10:00 in New York (standard time, UTC-5) is 15:00 UTC.
        val found = calendar.occurrences(cleansing, ms("2026-11-24T00:00:00Z"), ms("2026-12-13T00:00:00Z"))
        assertEquals(listOf(ms("2026-11-28T15:00:00Z"), ms("2026-12-05T15:00:00Z"), ms("2026-12-12T15:00:00Z")), found)
        assertEquals(ms("2026-11-28T15:00:00Z"), calendar.next(cleansing, ms("2026-11-24T12:00:00Z")))
    }

    @Test
    fun `a weekly schedule keeps its local time across daylight saving`() {
        // Clocks go forward on Sunday 8 March 2026: Saturday 10:00 is 15:00 UTC before it and 14:00 UTC after.
        assertEquals(
            listOf(ms("2026-03-07T15:00:00Z"), ms("2026-03-14T14:00:00Z")),
            calendar.occurrences(cleansing, ms("2026-03-01T00:00:00Z"), ms("2026-03-20T00:00:00Z"))
        )
        // And back on Sunday 1 November 2026.
        assertEquals(
            listOf(ms("2026-10-31T14:00:00Z"), ms("2026-11-07T15:00:00Z")),
            calendar.occurrences(cleansing, ms("2026-10-25T00:00:00Z"), ms("2026-11-10T00:00:00Z"))
        )
    }

    @Test
    fun `a time that does not exist or happens twice still occurs exactly once a day`() {
        val gap = ScheduleDefinition(id = "gap", kind = ScheduleKind.DAILY, timezone = "America/New_York", time = "02:30")
        assertEquals(1, calendar.occurrences(gap, ms("2026-03-08T00:00:00Z"), ms("2026-03-09T00:00:00Z")).size, "02:30 does not exist on 8 March, so it moves on instead of being skipped")
        val twice = ScheduleDefinition(id = "twice", kind = ScheduleKind.DAILY, timezone = "America/New_York", time = "01:30")
        assertEquals(1, calendar.occurrences(twice, ms("2026-11-01T00:00:00Z"), ms("2026-11-02T06:00:00Z")).size, "01:30 happens twice on 1 November, but the schedule fires once")
    }

    @Test
    fun `dates without an offset are read in the schedule's own zone`() {
        val once = ScheduleDefinition(id = "o", kind = ScheduleKind.ONCE, timezone = "America/New_York", at = "2026-12-05T10:00")
        assertEquals(listOf(ms("2026-12-05T15:00:00Z")), calendar.occurrences(once, 0, ms("2027-01-01T00:00:00Z")))
        val withOffset = once.copy(at = "2026-12-05T10:00:00+01:00")
        assertEquals(listOf(ms("2026-12-05T09:00:00Z")), calendar.occurrences(withOffset, 0, ms("2027-01-01T00:00:00Z")), "an explicit offset wins over the zone")
        val tokyo = ScheduleDefinition(id = "t", kind = ScheduleKind.DAILY, timezone = "Asia/Tokyo", time = "09:00")
        assertEquals(listOf(ms("2026-11-25T00:00:00Z")), calendar.occurrences(tokyo, ms("2026-11-24T12:00:00Z"), ms("2026-11-25T06:00:00Z")), "09:00 in Tokyo (no daylight saving) is 00:00 UTC")
    }

    @Test
    fun `an unknown zone or unreadable date is reported and has no occurrences`() {
        assertTrue(calendar.problems(cleansing).isEmpty())
        assertEquals("timezone", calendar.problems(cleansing.copy(timezone = "Mars/Olympus")).single().first)
        assertEquals(emptyList<Long>(), calendar.occurrences(cleansing.copy(timezone = "Mars/Olympus"), 0, ms("2027-01-01T00:00:00Z")))
        assertEquals("at", calendar.problems(cleansing.copy(kind = ScheduleKind.ONCE, at = "soon")).single().first)
    }

    @Test
    fun `local times are described in the schedule's zone`() {
        assertEquals("Sat 28 Nov 2026 10:00 EST", calendar.describeLocal(cleansing, ms("2026-11-28T15:00:00Z")))
        assertEquals("Sat 14 Mar 2026 10:00 EDT", calendar.describeLocal(cleansing, ms("2026-03-14T14:00:00Z")))
    }

    @Test
    fun `it agrees exactly with the pure UTC calendar the shared domain carries`() {
        val rng = Random(42)
        val defs = listOf(
            ScheduleDefinition(id = "d", kind = ScheduleKind.DAILY, timezone = "UTC", time = "06:30:15"),
            ScheduleDefinition(id = "w", kind = ScheduleKind.WEEKLY, timezone = "UTC", dayOfWeek = "WEDNESDAY", time = "23:59"),
            ScheduleDefinition(id = "w2", kind = ScheduleKind.WEEKLY, timezone = "Etc/UTC", dayOfWeek = "SUNDAY", time = "00:00"),
            ScheduleDefinition(id = "i", kind = ScheduleKind.INTERVAL, timezone = "UTC", intervalSeconds = 5_400),
            ScheduleDefinition(id = "ia", kind = ScheduleKind.INTERVAL, timezone = "UTC", intervalSeconds = 777, at = "2026-02-28T23:59:59Z"),
            ScheduleDefinition(id = "ib", kind = ScheduleKind.INTERVAL, timezone = "UTC", intervalSeconds = 3_600, at = "2026-03-01T00:00:00+05:30"),
            ScheduleDefinition(id = "o", kind = ScheduleKind.ONCE, timezone = "UTC", at = "2024-02-29T12:00"),
            ScheduleDefinition(id = "o2", kind = ScheduleKind.ONCE, timezone = "UTC", at = "2100-03-01T00:00:00Z")
        )
        val low = ms("1999-12-25T00:00:00Z")
        val high = ms("2101-01-01T00:00:00Z")
        repeat(300) {
            val from = low + (rng.nextDouble() * (high - low - 50L * 86_400_000L)).toLong()
            val to = from + 1 + (rng.nextDouble() * 50L * 86_400_000L).toLong()
            for (def in defs) {
                assertEquals(UtcScheduleCalendar.occurrences(def, from, to), calendar.occurrences(def, from, to), "${def.id} between $from and $to")
            }
        }
        for (def in defs) {
            assertEquals(UtcScheduleCalendar.next(def, ms("2026-11-24T12:00:00Z")), calendar.next(def, ms("2026-11-24T12:00:00Z")), "next of ${def.id}")
            assertEquals(UtcScheduleCalendar.problems(def), calendar.problems(def), "problems of ${def.id}")
        }
    }

    @Test
    fun `a simulation with this calendar fires a New York schedule at the right instant`() {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(schedules = listOf(cleansing)))
        val sim = WorldSimulation(SeededRandomSource(1L), game = game)
        sim.scheduleCalendar = calendar
        val seen = mutableListOf<Pair<SchedulePhase, Int>>()
        var t = ms("2026-11-28T13:30:00Z")
        sim.advanceWallClock(t)
        while (t < ms("2026-11-28T15:00:30Z")) {
            t += 1000
            for (e in sim.advanceWallClock(t)) if (e is WorldSimulationEvent.ScheduleTriggered) seen += e.trigger.phase to e.trigger.secondsBefore
        }
        assertEquals(listOf(SchedulePhase.WARNING to 3600, SchedulePhase.WARNING to 900, SchedulePhase.WARNING to 60, SchedulePhase.FIRE to 0), seen, "10:00 in New York is 15:00 UTC in November")

        val utcOnly = WorldSimulation(SeededRandomSource(1L), game = game)
        var u = ms("2026-11-28T13:30:00Z")
        utcOnly.advanceWallClock(u)
        var fired = 0
        while (u < ms("2026-11-28T15:00:30Z")) { u += 1000; fired += utcOnly.advanceWallClock(u).count { it is WorldSimulationEvent.ScheduleTriggered } }
        assertEquals(0, fired, "without the server's calendar a New York schedule does nothing, rather than firing at the wrong time")
    }
}
