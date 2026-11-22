package com.morgana.domain.game

import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.WeatherType
import kotlinx.serialization.Serializable

/**
 * One kind of weather a zone can have, with how strong it is and how likely it is relative to the zone's other states
 * ([weight] 3 against 1 means three times as often).
 */
@Serializable
data class WeatherState(
    val type: WeatherType,
    /** Strength from 0 (barely) to 1 (heaviest). Ignored for [WeatherType.NONE]. */
    val intensity: Float = 0.5f,
    val weight: Int = 1
)

/**
 * A weather zone: a named region of the world that shares one weather. Maps join a zone through [MapDefinition.zoneId].
 * Weather changes every [periodTicks]; which of [states] comes next is a pure function of the zone's seed, its id and the time
 * (see [WorldWeather]), so the server, every client and a replay agree without sending anything.
 */
@Serializable
data class WeatherZone(
    val id: Int,
    val name: String,
    /** Ticks each weather lasts (20 ticks per second, so 3600 is three minutes). */
    val periodTicks: Int = DEFAULT_PERIOD_TICKS,
    /** Mixed into the schedule so two zones (or two games) do not share the same weather. */
    val seed: Int = 0,
    val states: List<WeatherState> = listOf(WeatherState(WeatherType.NONE))
) {
    companion object {
        const val DEFAULT_PERIOD_TICKS = 3600
        const val MIN_PERIOD_TICKS = 200
        const val MAX_PERIOD_TICKS = 1_000_000
        const val MAX_STATES = 16
        const val MAX_WEIGHT = 1000
    }
}

/**
 * The game's weather: its zones. Zone 0 is the default world zone, used by maps that have not chosen one; a game that lists no
 * zone 0 simply has clear skies there. A game with no weather definition at all keeps the older behaviour where every map
 * carries its own fixed weather (see [WorldWeather.effective]).
 */
@Serializable
data class WeatherDefinition(
    val zones: List<WeatherZone> = emptyList()
) {
    companion object {
        const val MAX_ZONES = 64
    }
}

/** The weather a player sees right now. */
data class WeatherNow(val type: WeatherType, val intensity: Float) {
    companion object {
        val CLEAR = WeatherNow(WeatherType.NONE, 0f)
    }
}

/** Pure weather rules: the schedule of a zone and which maps feel it. */
object WorldWeather {

    /** The weather in [zone] at [tick]: a weighted, deterministic pick that stays the same for a whole period. */
    fun inZone(zone: WeatherZone, tick: Long): WeatherNow {
        val states = zone.states.filter { it.weight > 0 }
        if (states.isEmpty()) return WeatherNow.CLEAR
        val period = tick.coerceAtLeast(0L) / zone.periodTicks.coerceAtLeast(1)
        val total = states.sumOf { it.weight.toLong() }
        var roll = mix(zone.seed.toLong(), zone.id.toLong(), period) % total
        for (s in states) {
            if (roll < s.weight) return WeatherNow(s.type, if (s.type == WeatherType.NONE) 0f else s.intensity.coerceIn(0f, 1f))
            roll -= s.weight
        }
        return WeatherNow.CLEAR
    }

    /**
     * The weather [map] shows at [tick]. In a game with a [WeatherDefinition], interior maps are never affected and an exterior
     * map takes its zone's weather. A game without one keeps the older behaviour exactly: each map's own fixed
     * [MapDefinition.weather], interior or not (an authored look such as fog in a crypt).
     */
    fun effective(map: MapDefinition, definition: WeatherDefinition?, tick: Long): WeatherNow {
        if (definition == null) {
            return if (map.weather == WeatherType.NONE) WeatherNow.CLEAR else WeatherNow(map.weather, map.weatherIntensity.coerceIn(0f, 1f))
        }
        if (map.isIndoors) return WeatherNow.CLEAR
        val zone = definition.zones.firstOrNull { it.id == map.zoneId } ?: return WeatherNow.CLEAR
        return inZone(zone, tick)
    }

    /** A small integer hash (SplitMix-style) of three values, non-negative and identical on every platform. */
    private fun mix(a: Long, b: Long, c: Long): Long {
        var z = a * -7046029254386353131L + b * -4658895280553007687L + c * 6364136223846793005L + 1442695040888963407L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return z and Long.MAX_VALUE
    }
}
