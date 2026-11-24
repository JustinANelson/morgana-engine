package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.game.ScheduleRules
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.replay.SimInput
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.sim.ScheduleHandler
import com.morgana.domain.sim.ScheduleTrigger
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The scheduler: wall-clock events with advance warnings. Dates are real ones (2026-11-24 is a Tuesday). */
class SchedulerTest {
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val cleansing = ScheduleDefinition(
        id = "cleansing", name = "Weekly Cleansing", kind = ScheduleKind.WEEKLY, timezone = "America/New_York",
        dayOfWeek = "SATURDAY", time = "10:00", warningsSeconds = listOf(3600, 900, 60)
    )

    // ---- the date maths -------------------------------------------------------------------------------------------------------

    @Test
    fun `a weekly schedule lands on its weekday and local time`() {
        // Saturday 28 November 2026, 10:00 in New York (standard time, UTC-5) is 15:00 UTC.
        val found = ScheduleRules.occurrences(cleansing, ms("2026-11-24T00:00:00Z"), ms("2026-12-13T00:00:00Z"))
        assertEquals(listOf(ms("2026-11-28T15:00:00Z"), ms("2026-12-05T15:00:00Z"), ms("2026-12-12T15:00:00Z")), found)
    }

    @Test
    fun `a weekly schedule keeps its local time across daylight saving`() {
        // Clocks go forward on Sunday 8 March 2026: Saturday 10:00 is 15:00 UTC before it and 14:00 UTC after.
        val found = ScheduleRules.occurrences(cleansing, ms("2026-03-01T00:00:00Z"), ms("2026-03-20T00:00:00Z"))
        assertEquals(listOf(ms("2026-03-07T15:00:00Z"), ms("2026-03-14T14:00:00Z")), found)
        // And back on Sunday 1 November 2026.
        val fall = ScheduleRules.occurrences(cleansing, ms("2026-10-25T00:00:00Z"), ms("2026-11-10T00:00:00Z"))
        assertEquals(listOf(ms("2026-10-31T14:00:00Z"), ms("2026-11-07T15:00:00Z")), fall)
    }

    @Test
    fun `a time that does not exist or happens twice still occurs exactly once a day`() {
        val gap = ScheduleDefinition(id = "gap", kind = ScheduleKind.DAILY, timezone = "America/New_York", time = "02:30")
        val springDay = ScheduleRules.occurrences(gap, ms("2026-03-08T00:00:00Z"), ms("2026-03-09T00:00:00Z"))
        assertEquals(1, springDay.size, "02:30 does not exist on 8 March, so it moves on instead of being skipped")
        val twice = ScheduleDefinition(id = "twice", kind = ScheduleKind.DAILY, timezone = "America/New_York", time = "01:30")
        val fallDay = ScheduleRules.occurrences(twice, ms("2026-11-01T00:00:00Z"), ms("2026-11-02T06:00:00Z"))
        assertEquals(1, fallDay.size, "01:30 happens twice on 1 November, but the schedule fires once")
    }

    @Test
    fun `daily, interval and one-off schedules`() {
        val daily = ScheduleDefinition(id = "d", kind = ScheduleKind.DAILY, timezone = "UTC", time = "06:00")
        assertEquals(
            listOf(ms("2026-11-24T06:00:00Z"), ms("2026-11-25T06:00:00Z")),
            ScheduleRules.occurrences(daily, ms("2026-11-24T00:00:00Z"), ms("2026-11-25T23:00:00Z"))
        )

        val hourly = ScheduleDefinition(id = "h", kind = ScheduleKind.INTERVAL, intervalSeconds = 3600, at = "2026-11-24T00:30:00Z")
        assertEquals(
            listOf(ms("2026-11-24T01:30:00Z"), ms("2026-11-24T02:30:00Z")),
            ScheduleRules.occurrences(hourly, ms("2026-11-24T00:30:00Z"), ms("2026-11-24T02:45:00Z")),
            "the range excludes its start, and counts from the anchor"
        )
        assertEquals(emptyList<Long>(), ScheduleRules.occurrences(hourly, ms("2026-11-20T00:00:00Z"), ms("2026-11-24T00:29:00Z")), "nothing before the anchor")

        val once = ScheduleDefinition(id = "o", kind = ScheduleKind.ONCE, timezone = "UTC", at = "2026-12-01T12:00")
        assertEquals(listOf(ms("2026-12-01T12:00:00Z")), ScheduleRules.occurrences(once, 0, ms("2027-01-01T00:00:00Z")))
        assertEquals(emptyList<Long>(), ScheduleRules.occurrences(once, ms("2026-12-01T12:00:00Z"), ms("2027-01-01T00:00:00Z")), "already past")
    }

    @Test
    fun `next finds the following occurrence and durations read in words`() {
        assertEquals(ms("2026-11-28T15:00:00Z"), ScheduleRules.next(cleansing, ms("2026-11-24T12:00:00Z")))
        assertNull(ScheduleRules.next(ScheduleDefinition(id = "x", kind = ScheduleKind.ONCE, at = "2020-01-01T00:00"), ms("2026-11-24T00:00:00Z")))
        assertEquals("1 hour", ScheduleRules.describeDuration(3600))
        assertEquals("15 minutes", ScheduleRules.describeDuration(900))
        assertEquals("1 minute", ScheduleRules.describeDuration(60))
        assertEquals("1 minute 30 seconds", ScheduleRules.describeDuration(90))
        assertEquals("2 days 3 hours", ScheduleRules.describeDuration(2 * 86_400 + 3 * 3_600 + 59))
    }

    @Test
    fun `bad schedules are reported with a pointer and have no occurrences`() {
        fun pointers(def: ScheduleDefinition) =
            GameDefinitionValidator.validate(MorganaClassic.definition.copy(schedules = listOf(def))).map { it.pointer }

        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(schedules = listOf(cleansing))).isEmpty(), "a good schedule validates")
        assertTrue("/schedules/0/timezone" in pointers(cleansing.copy(timezone = "Mars/Olympus")))
        assertTrue("/schedules/0/time" in pointers(cleansing.copy(time = "ten o'clock")))
        assertTrue("/schedules/0/dayOfWeek" in pointers(cleansing.copy(dayOfWeek = "Caturday")))
        assertTrue("/schedules/0/id" in pointers(cleansing.copy(id = "Bad Id")))
        assertTrue("/schedules/0/warningsSeconds" in pointers(cleansing.copy(warningsSeconds = listOf(60, 60))))
        assertTrue("/schedules/0/warningsSeconds" in pointers(cleansing.copy(warningsSeconds = listOf(0))))
        assertTrue("/schedules/0/intervalSeconds" in pointers(cleansing.copy(kind = ScheduleKind.INTERVAL, intervalSeconds = 0)))
        assertTrue("/schedules/0/at" in pointers(cleansing.copy(kind = ScheduleKind.ONCE, at = "")))
        assertTrue("/schedules/0/lateGraceSeconds" in pointers(cleansing.copy(lateGraceSeconds = -1)))
        assertTrue("/schedules/0/messageColorHex" in pointers(cleansing.copy(messageColorHex = "yellow")))
        val twice = GameDefinitionValidator.validate(MorganaClassic.definition.copy(schedules = listOf(cleansing, cleansing))).map { it.pointer }
        assertTrue("/schedules/1/id" in twice, "duplicate ids: $twice")
        assertEquals(emptyList<Long>(), ScheduleRules.occurrences(cleansing.copy(timezone = "Nope"), 0, ms("2027-01-01T00:00:00Z")))
    }

    // ---- the system -----------------------------------------------------------------------------------------------------------

    private fun sim(vararg defs: ScheduleDefinition, modules: ModuleFlags = ModuleFlags()): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(schedules = defs.toList()))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.modules = modules
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "Pat", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN)))
        return s
    }

    private fun WorldSimulation.at(iso: String): List<WorldSimulationEvent> = advanceWallClock(ms(iso))

    private fun List<WorldSimulationEvent>.triggers() = filterIsInstance<WorldSimulationEvent.ScheduleTriggered>().map { it.trigger }
    private fun List<WorldSimulationEvent>.announcements() =
        filterIsInstance<WorldSimulationEvent.ChatEmitted>().filter { it.senderId == "SYSTEM" }.map { it.message }

    @Test
    fun `the first clock reading only sets the cursor and starts nothing`() {
        val s = sim(cleansing)
        assertTrue(s.at("2026-11-28T15:00:00Z").triggers().isEmpty(), "even exactly at an occurrence")
        assertEquals(ms("2026-11-28T15:00:00Z"), s.scheduleCursorMillis)
    }

    @Test
    fun `warnings and the start arrive in order, once each, with their announcements`() {
        val s = sim(cleansing)
        s.at("2026-11-28T13:00:00Z") // an hour and a half before: sets the cursor
        val seen = mutableListOf<ScheduleTrigger>()
        val said = mutableListOf<String>()
        var t = ms("2026-11-28T13:00:00Z")
        while (t < ms("2026-11-28T15:05:00Z")) { // one-second steps, the way the server reports time
            t += 1000
            val events = s.advanceWallClock(t)
            seen += events.triggers()
            said += events.announcements()
        }
        assertEquals(
            listOf(SchedulePhase.WARNING to 3600, SchedulePhase.WARNING to 900, SchedulePhase.WARNING to 60, SchedulePhase.FIRE to 0),
            seen.map { it.phase to it.secondsBefore }
        )
        assertEquals(listOf("14:00", "14:45", "14:59", "15:00").map { ms("2026-11-28T$it:00Z") }, seen.map { s.scheduleLastFiredOrWarningTime(it) })
        assertTrue(seen.all { it.scheduleId == "cleansing" && it.name == "Weekly Cleansing" && it.occurrenceMillis == ms("2026-11-28T15:00:00Z") && it.recurring })
        assertEquals(
            listOf("Weekly Cleansing begins in 1 hour.", "Weekly Cleansing begins in 15 minutes.", "Weekly Cleansing begins in 1 minute.", "Weekly Cleansing begins now."),
            said
        )
        assertEquals(ms("2026-11-28T15:00:00Z"), s.scheduleLastFired["cleansing"])
    }

    /** The moment a trigger was due: the occurrence minus its warning offset. */
    private fun WorldSimulation.scheduleLastFiredOrWarningTime(t: ScheduleTrigger) = t.occurrenceMillis - t.secondsBefore * 1000L

    @Test
    fun `a coarse clock reading raises only what is still worth saying`() {
        val s = sim(cleansing)
        s.at("2026-11-28T10:00:00Z")
        // After a pause the 1 hour, 15 minute and 1 minute warnings are all overdue: only the closest to the start is raised.
        val late = s.at("2026-11-28T14:59:30Z").triggers()
        assertEquals(listOf(60), late.map { it.secondsBefore })
        // A reading past the start raises the start, and no warnings for something that has begun.
        assertEquals(listOf(SchedulePhase.FIRE), s.at("2026-11-28T15:00:30Z").triggers().map { it.phase })
        assertTrue(s.at("2026-11-28T15:10:00Z").triggers().isEmpty(), "and never again")
    }

    @Test
    fun `an occurrence the server was too late for is skipped, not fired hours late`() {
        val s = sim(cleansing)
        s.at("2026-11-28T10:00:00Z")
        val events = s.at("2026-11-28T18:00:00Z") // the server was down across the Cleansing
        assertTrue(events.triggers().isEmpty())
        assertTrue(events.announcements().isEmpty())
        assertNull(s.scheduleLastFired["cleansing"])
        assertEquals(listOf(3600), s.at("2026-12-05T14:30:00Z").triggers().map { it.secondsBefore }, "the next week's warning (1 hour) still comes")
    }

    @Test
    fun `an occurrence within its grace still starts`() {
        val s = sim(cleansing.copy(lateGraceSeconds = 120))
        s.at("2026-11-28T14:00:00Z")
        val inGrace = s.at("2026-11-28T15:01:00Z").triggers()
        assertEquals(listOf(SchedulePhase.FIRE), inGrace.map { it.phase }, "a minute late is within the grace: it starts, with no stale warnings")
        val s2 = sim(cleansing.copy(lateGraceSeconds = 30))
        s2.at("2026-11-28T14:00:00Z")
        assertEquals(emptyList<SchedulePhase>(), s2.at("2026-11-28T15:01:00Z").triggers().filter { it.phase == SchedulePhase.FIRE }.map { it.phase }, "a minute late is too late with a 30 second grace")
    }

    @Test
    fun `a restart does not start the same occurrence twice`() {
        val s = sim(cleansing)
        s.at("2026-11-28T14:59:50Z")
        assertEquals(1, s.at("2026-11-28T15:00:05Z").triggers().count { it.phase == SchedulePhase.FIRE })
        // A new run that has the saved last-fired record but an old cursor (the cursor is only saved now and then).
        val restarted = sim(cleansing)
        restarted.scheduleLastFired.putAll(s.scheduleLastFired)
        restarted.scheduleCursorMillis = ms("2026-11-28T14:59:50Z")
        assertEquals(0, restarted.at("2026-11-28T15:00:20Z").triggers().count { it.phase == SchedulePhase.FIRE })
    }

    @Test
    fun `a disabled schedule and a switched-off scheduler do nothing`() {
        val off = sim(cleansing.copy(enabled = false))
        off.at("2026-11-28T14:00:00Z")
        assertTrue(off.at("2026-11-28T15:00:30Z").triggers().isEmpty())

        val switched = sim(cleansing, modules = ModuleFlags(disabled = setOf(OptionalModule.SCHEDULER)))
        switched.at("2026-11-28T14:00:00Z")
        assertTrue(switched.at("2026-11-28T15:00:30Z").triggers().isEmpty())
        assertEquals(0L, switched.scheduleCursorMillis, "a switched-off scheduler is not even running its cursor")
    }

    @Test
    fun `blank announcement text sends no chat but the trigger still happens`() {
        val s = sim(cleansing.copy(warningMessage = "", fireMessage = ""))
        s.at("2026-11-28T14:00:00Z")
        val events = s.at("2026-11-28T15:00:30Z")
        assertTrue(events.announcements().isEmpty())
        assertEquals(listOf(SchedulePhase.FIRE), events.triggers().map { it.phase })
    }

    @Test
    fun `other systems hear the schedule through the handler seam`() {
        val s = sim(cleansing)
        val heard = mutableListOf<ScheduleTrigger>()
        s.install(object : ScheduleHandler {
            override fun onSchedule(sim: WorldSimulation, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) {
                heard += trigger
            }
        })
        var t = ms("2026-11-28T14:40:00Z")
        s.advanceWallClock(t)
        while (t < ms("2026-11-28T15:00:30Z")) { t += 1000; s.advanceWallClock(t) }
        assertEquals(listOf(900, 60, 0), heard.map { it.secondsBefore })
    }

    @Test
    fun `a one-off can be booked while the game runs and is gone once it has started`() {
        val s = sim()
        assertFalse(s.scheduleOnce("window", ms("2026-11-25T12:00:00Z")), "the clock has not started")
        s.at("2026-11-24T12:00:00Z")
        assertFalse(s.scheduleOnce("window", ms("2026-11-24T11:00:00Z")), "in the past")
        assertTrue(s.scheduleOnce("window", ms("2026-11-25T12:00:00Z"), name = "Siege window", warningsSeconds = listOf(3600), data = mapOf("guild" to "g1")))
        assertEquals(setOf("window"), s.scheduleOneShots.keys)

        val warn = s.at("2026-11-25T11:00:01Z").triggers().single()
        assertEquals(SchedulePhase.WARNING to mapOf("guild" to "g1"), warn.phase to warn.data)
        assertFalse(warn.recurring)
        val fire = s.at("2026-11-25T12:00:01Z")
        assertEquals(listOf("Siege window begins now."), fire.announcements())
        assertEquals(SchedulePhase.FIRE, fire.triggers().single().phase)
        assertTrue(s.scheduleOneShots.isEmpty(), "removed after it started")
        assertTrue(s.scheduleLastFired.isEmpty())
    }

    @Test
    fun `a one-off can be cancelled, replaced, and cannot shadow a configured schedule`() {
        val s = sim(cleansing)
        s.at("2026-11-24T12:00:00Z")
        assertFalse(s.scheduleOnce("cleansing", ms("2026-11-25T12:00:00Z")), "that id belongs to game.json")
        assertTrue(s.scheduleOnce("w", ms("2026-11-25T12:00:00Z")))
        assertTrue(s.scheduleOnce("w", ms("2026-11-26T12:00:00Z")), "booking again replaces")
        assertEquals(ms("2026-11-26T12:00:00Z"), ScheduleRules.next(s.scheduleOneShots.getValue("w").def, ms("2026-11-24T12:00:00Z")))
        assertTrue(s.cancelSchedule("w"))
        assertFalse(s.cancelSchedule("w"))
        assertTrue(s.at("2026-11-26T12:00:30Z").triggers().none { it.scheduleId == "w" })
    }

    @Test
    fun `the same inputs give the same state, so replays agree`() {
        fun run(): WorldSimulation {
            val s = sim(cleansing)
            s.scheduleOnce("x", ms("2026-11-28T14:30:00Z")) // refused: clock not started, same both times
            for (iso in listOf("2026-11-28T13:00:00Z", "2026-11-28T14:00:00Z", "2026-11-28T14:45:30Z", "2026-11-28T15:00:10Z")) {
                SimInput.WallClock(ms(iso)).applyTo(s)
            }
            return s
        }
        val a = run()
        val b = run()
        assertEquals(StateHasher.hash(a), StateHasher.hash(b))
        val c = sim(cleansing)
        SimInput.WallClock(ms("2026-11-28T13:00:00Z")).applyTo(c)
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(c), "the hash sees the scheduler's state")
        assertFalse(StateHasher.canonicalState(sim(cleansing)).contains("schedule"), "a world that never heard the clock hashes as before")
    }

    @Test
    fun `slash schedule lists what is coming`() {
        val s = sim(cleansing)
        assertTrue(s.executeAdminSlashCommand("p1", "/schedule").announcementsAnyChannel().any { it.contains("not started") })
        s.at("2026-11-24T12:00:00Z")
        val lines = s.executeAdminSlashCommand("p1", "/schedule").announcementsAnyChannel()
        assertTrue(lines.any { it.startsWith("Weekly Cleansing: in 4 days") && it.contains("Sat 28 Nov 2026 10:00") }, "got $lines")
        // A player who is not staff cannot force a start.
        assertTrue(s.executeAdminSlashCommand("p1", "/schedule fire cleansing").triggers().isEmpty())
    }

    private fun List<WorldSimulationEvent>.announcementsAnyChannel() = filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }
}
