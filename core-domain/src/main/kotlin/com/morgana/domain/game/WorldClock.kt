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
    val dayNight: Boolean = false,
    /**
     * Where each part of the day starts, in thousandths of a day (0 = midnight, 500 = noon). The world is dark from
     * [nightStartPermille] through midnight to [dawnStartPermille], brightens through dawn until [dayStartPermille], is fully lit
     * until [duskStartPermille], and darkens through dusk until [nightStartPermille]. They must be in that order, from 0 to 1000.
     * The defaults give the original cycle (night about 46% of the day); a game sets its own to make long or short nights without
     * touching the engine. With a 60-minute day, `dawn 100, day 150, dusk 850, night 900` is 12 minutes of night and 48 of day.
     */
    val dawnStartPermille: Int = DEFAULT_DAWN_START,
    val dayStartPermille: Int = DEFAULT_DAY_START,
    val duskStartPermille: Int = DEFAULT_DUSK_START,
    val nightStartPermille: Int = DEFAULT_NIGHT_START,
    /** How bright the world is at its darkest, as light out of 1000 (0 = pitch black). */
    val nightLight: Int = DEFAULT_NIGHT_LIGHT
) {
    /** Rough part of the day at [permille] (0..999). */
    fun phaseAt(permille: Int): DayPhase = when {
        permille < dawnStartPermille -> DayPhase.NIGHT
        permille < dayStartPermille -> DayPhase.DAWN
        permille < duskStartPermille -> DayPhase.DAY
        permille < nightStartPermille -> DayPhase.DUSK
        else -> DayPhase.NIGHT
    }

    /** Light level [nightLight]..1000: dark at night, ramping up through dawn, full by day, ramping down through dusk. */
    fun lightAt(permille: Int): Int = when {
        permille < dawnStartPermille -> nightLight
        permille < dayStartPermille -> nightLight + (1000 - nightLight) * (permille - dawnStartPermille) / (dayStartPermille - dawnStartPermille)
        permille < duskStartPermille -> 1000
        permille < nightStartPermille -> 1000 - (1000 - nightLight) * (permille - duskStartPermille) / (nightStartPermille - duskStartPermille)
        else -> nightLight
    }

    companion object {
        /** The original cycle: night until 22.9% of the day (about 05:30), dawn to 30%, day to 70%, dusk to 77%, then night. */
        const val DEFAULT_DAWN_START = 230
        const val DEFAULT_DAY_START = 300
        const val DEFAULT_DUSK_START = 700
        const val DEFAULT_NIGHT_START = 770
        const val DEFAULT_NIGHT_LIGHT = 150

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
            phase = definition.phaseAt(permille),
            light = definition.lightAt(permille)
        )
    }

    companion object {
        /** The darkest the world gets in a game that does not set its own `nightLight`, as light out of 1000. */
        const val NIGHT_LIGHT = ClockDefinition.DEFAULT_NIGHT_LIGHT

        /** [phaseAt] for the default cycle; a game with its own boundaries asks its [ClockDefinition]. */
        fun phaseAt(permille: Int): DayPhase = ClockDefinition().phaseAt(permille)

        /** [lightAt] for the default cycle; a game with its own boundaries asks its [ClockDefinition]. */
        fun lightAt(permille: Int): Int = ClockDefinition().lightAt(permille)
    }
}
