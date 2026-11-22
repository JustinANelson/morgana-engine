package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * The calendar of a game, declared in `game.json` under `clock`. A game without a clock section has no calendar and no
 * day and night.
 *
 * Time is the simulation tick, so the clock costs nothing to run, needs no network traffic (the client computes the same
 * value from the tick it already receives), and replays agree exactly. Everything here is integer arithmetic.
 */
@Serializable
data class ClockDefinition(
    /** Ticks in one day (20 ticks per second, so 1200 is one minute). */
    val dayLengthTicks: Int = DEFAULT_DAY_LENGTH_TICKS,
    /** Days in each season. */
    val daysPerSeason: Int = DEFAULT_DAYS_PER_SEASON,
    /** Season names in order; the year is this many seasons long. */
    val seasons: List<String> = DEFAULT_SEASONS,
    /** Time of day when tick 0 happens, in thousandths of a day (0 = midnight, 500 = noon). New worlds start in the morning. */
    val startTimePermille: Int = 350,
    /** Opt in to the visual day and night cycle: outdoor maps darken at night. Off by default so existing games look the same. */
    val dayNight: Boolean = false
) {
    companion object {
        const val DEFAULT_DAY_LENGTH_TICKS = 1200
        const val DEFAULT_DAYS_PER_SEASON = 7
        val DEFAULT_SEASONS = listOf("Spring", "Summer", "Autumn", "Winter")
        const val MIN_DAY_LENGTH_TICKS = 200
        const val MAX_DAY_LENGTH_TICKS = 1_000_000
        const val MAX_DAYS_PER_SEASON = 365
        const val MAX_SEASONS = 12
    }
}

/** Rough part of the day, for flavour text and light. */
enum class DayPhase { NIGHT, DAWN, DAY, DUSK }

/**
 * A moment on the calendar. [day] counts from 0 since the world began; [dayOfSeason] and [year] are derived.
 * [timeOfDayPermille] is 0 at midnight, 500 at noon, up to 999. [light] is how bright the world is, 0 (darkest) to 1000.
 */
data class ClockTime(
    val day: Long,
    val dayOfSeason: Int,
    val seasonIndex: Int,
    val seasonName: String,
    val year: Long,
    val timeOfDayPermille: Int,
    val phase: DayPhase,
    val light: Int
) {
    /** 24-hour clock text such as "07:30". */
    val clockText: String
        get() {
            val minutes = timeOfDayPermille * 24 * 60 / 1000
            return (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
        }
}

/** Pure calendar maths over a [ClockDefinition]. */
class WorldClock(val definition: ClockDefinition) {

    fun at(tick: Long): ClockTime {
        val dayLength = definition.dayLengthTicks.toLong()
        val start = definition.startTimePermille.toLong() * dayLength / 1000L
        val shifted = tick.coerceAtLeast(0L) + start
        val day = shifted / dayLength
        val permille = ((shifted % dayLength) * 1000L / dayLength).toInt()
        val perSeason = definition.daysPerSeason.toLong()
        val seasons = definition.seasons.size
        val seasonNumber = day / perSeason
        val seasonIndex = (seasonNumber % seasons).toInt()
        return ClockTime(
            day = day,
            dayOfSeason = (day % perSeason).toInt() + 1,
            seasonIndex = seasonIndex,
            seasonName = definition.seasons[seasonIndex],
            year = seasonNumber / seasons,
            timeOfDayPermille = permille,
            phase = phaseAt(permille),
            light = lightAt(permille)
        )
    }

    companion object {
        /** Night until 22.9% of the day (about 05:30), dawn to 30%, day to 70%, dusk to 77%, then night. */
        private const val DAWN_START = 230
        private const val DAY_START = 300
        private const val DUSK_START = 700
        private const val NIGHT_START = 770

        /** The darkest the world gets, as light out of 1000. */
        const val NIGHT_LIGHT = 150

        fun phaseAt(permille: Int): DayPhase = when {
            permille < DAWN_START -> DayPhase.NIGHT
            permille < DAY_START -> DayPhase.DAWN
            permille < DUSK_START -> DayPhase.DAY
            permille < NIGHT_START -> DayPhase.DUSK
            else -> DayPhase.NIGHT
        }

        /** Light level 150..1000: dark at night, ramping up through dawn, full by day, ramping down through dusk. */
        fun lightAt(permille: Int): Int = when {
            permille < DAWN_START -> NIGHT_LIGHT
            permille < DAY_START -> NIGHT_LIGHT + (1000 - NIGHT_LIGHT) * (permille - DAWN_START) / (DAY_START - DAWN_START)
            permille < DUSK_START -> 1000
            permille < NIGHT_START -> 1000 - (1000 - NIGHT_LIGHT) * (permille - DUSK_START) / (NIGHT_START - DUSK_START)
            else -> NIGHT_LIGHT
        }
    }
}
