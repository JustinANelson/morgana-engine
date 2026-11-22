package com.morgana.domain

import com.morgana.domain.game.ClockDefinition
import com.morgana.domain.game.DayPhase
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.WorldClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldClockTest {

    // 1000-tick days make the arithmetic readable: 1 tick = 1 permille of a day.
    private val clock = WorldClock(
        ClockDefinition(dayLengthTicks = 1000, daysPerSeason = 2, seasons = listOf("Wet", "Dry"), startTimePermille = 0)
    )

    @Test
    fun `time of day, phase and light follow the tick`() {
        assertEquals(DayPhase.NIGHT, clock.at(0).phase)
        assertEquals(WorldClock.NIGHT_LIGHT, clock.at(0).light)
        assertEquals("00:00", clock.at(0).clockText)

        val dawn = clock.at(250)
        assertEquals(DayPhase.DAWN, dawn.phase)
        assertTrue(dawn.light in (WorldClock.NIGHT_LIGHT + 1)..999, "dawn is brightening: ${dawn.light}")

        val noon = clock.at(500)
        assertEquals(DayPhase.DAY, noon.phase)
        assertEquals(1000, noon.light)
        assertEquals("12:00", noon.clockText)

        assertEquals(DayPhase.DUSK, clock.at(720).phase)
        assertEquals(DayPhase.NIGHT, clock.at(900).phase)
    }

    @Test
    fun `light rises through dawn and falls through dusk without jumps`() {
        val dawn = (230..300).map { WorldClock.lightAt(it) }
        assertEquals(dawn, dawn.sorted(), "dawn only brightens")
        val dusk = (700..770).map { WorldClock.lightAt(it) }
        assertEquals(dusk, dusk.sortedDescending(), "dusk only darkens")
        assertTrue(WorldClock.lightAt(300) - WorldClock.lightAt(299) < 30, "no jump at sunrise")
        assertTrue(WorldClock.lightAt(700) - WorldClock.lightAt(701) < 30, "no jump at sunset")
    }

    @Test
    fun `days, seasons and years roll over`() {
        val first = clock.at(0)
        assertEquals(0L, first.day)
        assertEquals(1, first.dayOfSeason)
        assertEquals("Wet", first.seasonName)

        assertEquals(2, clock.at(1000).dayOfSeason)
        val secondSeason = clock.at(2000)
        assertEquals("Dry", secondSeason.seasonName)
        assertEquals(1, secondSeason.dayOfSeason)

        val nextYear = clock.at(4000)
        assertEquals("Wet", nextYear.seasonName)
        assertEquals(1L, nextYear.year)
    }

    @Test
    fun `the start time shifts the whole calendar and tick zero is a morning by default`() {
        val noonStart = WorldClock(ClockDefinition(dayLengthTicks = 1000, startTimePermille = 500))
        assertEquals("12:00", noonStart.at(0).clockText)
        assertEquals(1L, noonStart.at(500).day, "noon plus half a day is the next midnight")

        val default = WorldClock(ClockDefinition())
        assertEquals(DayPhase.DAY, default.at(0).phase)
        assertEquals("08:24", default.at(0).clockText)
    }

    @Test
    fun `the same tick always gives the same time and negative ticks are clamped`() {
        assertEquals(clock.at(123_456), clock.at(123_456))
        assertEquals(clock.at(0), clock.at(-50))
    }

    @Test
    fun `a game without a clock section has no calendar and still validates`() {
        assertNull(MorganaClassic.definition.clock)
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition).isEmpty())
    }

    @Test
    fun `a bad clock is reported with a pointer`() {
        fun problems(clock: ClockDefinition) = GameDefinitionValidator.validate(MorganaClassic.definition.copy(clock = clock)).map { it.pointer }

        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(clock = ClockDefinition())).isEmpty(), "defaults are valid")
        assertTrue("/clock/dayLengthTicks" in problems(ClockDefinition(dayLengthTicks = 10)))
        assertTrue("/clock/daysPerSeason" in problems(ClockDefinition(daysPerSeason = 0)))
        assertTrue("/clock/seasons" in problems(ClockDefinition(seasons = emptyList())))
        assertTrue("/clock/seasons" in problems(ClockDefinition(seasons = listOf("Dup", "Dup"))))
        assertTrue("/clock/seasons/0" in problems(ClockDefinition(seasons = listOf(" "))))
        assertTrue("/clock/startTimePermille" in problems(ClockDefinition(startTimePermille = 1000)))
    }
}
