package com.morgana.domain.game

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.WeatherType
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldWeatherTest {

    private val zone = WeatherZone(
        id = 1, name = "Northlands", periodTicks = 1000, seed = 7,
        states = listOf(WeatherState(WeatherType.NONE, weight = 2), WeatherState(WeatherType.SNOW, 0.8f, weight = 1), WeatherState(WeatherType.FOG, 0.3f, weight = 1))
    )
    private val definition = WeatherDefinition(listOf(zone, WeatherZone(0, "World")))

    private fun map(id: Int = 1, zoneId: Int = 0, indoors: Boolean = false, legacy: WeatherType = WeatherType.NONE) =
        MapDefinition(id = id, name = "M$id", zoneId = zoneId, isIndoors = indoors, weather = legacy, weatherIntensity = 0.7f)

    @Test
    fun `weather stays the same for a whole period and is repeatable`() {
        for (period in 0L..20L) {
            val first = WorldWeather.inZone(zone, period * 1000)
            assertEquals(first, WorldWeather.inZone(zone, period * 1000 + 999), "period $period")
            assertEquals(first, WorldWeather.inZone(zone, period * 1000))
        }
    }

    @Test
    fun `over many periods every state appears roughly in proportion to its weight`() {
        val counts = HashMap<WeatherType, Int>()
        for (period in 0L until 4000L) counts.merge(WorldWeather.inZone(zone, period * 1000).type, 1, Int::plus)
        assertEquals(setOf(WeatherType.NONE, WeatherType.SNOW, WeatherType.FOG), counts.keys)
        val clear = counts.getValue(WeatherType.NONE)
        assertTrue(clear in 1700..2300, "clear was $clear of 4000")
        assertTrue(counts.getValue(WeatherType.SNOW) in 700..1300)
    }

    @Test
    fun `zones and seeds give different weather`() {
        val other = zone.copy(id = 2, seed = 99)
        val a = (0L until 200L).map { WorldWeather.inZone(zone, it * 1000).type }
        val b = (0L until 200L).map { WorldWeather.inZone(other, it * 1000).type }
        assertTrue(a != b)
    }

    @Test
    fun `clear weather has no strength and strength is clamped`() {
        val z = WeatherZone(3, "Z", states = listOf(WeatherState(WeatherType.NONE, 0.9f)))
        assertEquals(WeatherNow.CLEAR, WorldWeather.inZone(z, 5))
        val rain = WeatherZone(4, "R", states = listOf(WeatherState(WeatherType.RAIN, 7f)))
        assertEquals(1f, WorldWeather.inZone(rain, 5).intensity)
    }

    @Test
    fun `a zone with no usable state is clear and negative ticks are safe`() {
        assertEquals(WeatherNow.CLEAR, WorldWeather.inZone(zone.copy(states = emptyList()), 10))
        assertEquals(WorldWeather.inZone(zone, 0), WorldWeather.inZone(zone, -50))
    }

    @Test
    fun `interior maps are never affected`() {
        val rainy = WeatherDefinition(listOf(WeatherZone(0, "World", states = listOf(WeatherState(WeatherType.RAIN, 1f)))))
        assertEquals(WeatherType.RAIN, WorldWeather.effective(map(), rainy, 100).type)
        assertEquals(WeatherNow.CLEAR, WorldWeather.effective(map(indoors = true), rainy, 100))
    }

    @Test
    fun `an exterior map takes its zone's weather and an unknown zone is clear`() {
        val snowy = WeatherDefinition(listOf(WeatherZone(5, "Peaks", states = listOf(WeatherState(WeatherType.SNOW, 0.6f)))))
        assertEquals(WeatherNow(WeatherType.SNOW, 0.6f), WorldWeather.effective(map(zoneId = 5), snowy, 0))
        assertEquals(WeatherNow.CLEAR, WorldWeather.effective(map(zoneId = 9), snowy, 0))
        assertEquals(WeatherNow.CLEAR, WorldWeather.effective(map(zoneId = 0), snowy, 0))
    }

    @Test
    fun `games without weather zones keep each map's own weather`() {
        assertEquals(WeatherNow(WeatherType.RAIN, 0.7f), WorldWeather.effective(map(legacy = WeatherType.RAIN), null, 0))
        assertEquals(WeatherNow.CLEAR, WorldWeather.effective(map(), null, 0))
        // An authored look such as fog in a crypt is kept in those games, interior or not.
        assertEquals(WeatherNow(WeatherType.RAIN, 0.7f), WorldWeather.effective(map(indoors = true, legacy = WeatherType.RAIN), null, 0))
    }

    // ---- validation ----

    private val classic = MorganaClassic.definition

    private fun problems(def: WeatherDefinition) = GameDefinitionValidator.validate(classic.copy(weather = def)).filter { it.pointer.startsWith("/weather") }

    @Test
    fun `a sensible weather definition has no issues`() {
        assertEquals(emptyList<GameIssue>(), problems(definition))
    }

    @Test
    fun `bad weather definitions are reported`() {
        assertTrue(problems(WeatherDefinition(listOf(zone, zone))).any { it.message.contains("used twice") })
        assertTrue(problems(WeatherDefinition(listOf(zone.copy(periodTicks = 5)))).any { it.pointer.endsWith("periodTicks") })
        assertTrue(problems(WeatherDefinition(listOf(zone.copy(states = emptyList())))).any { it.pointer.endsWith("states") })
        assertTrue(problems(WeatherDefinition(listOf(zone.copy(states = listOf(WeatherState(WeatherType.RAIN, 2f, 0)))))).size == 2)
        assertTrue(problems(WeatherDefinition(listOf(zone.copy(name = " ")))).any { it.pointer.endsWith("name") })
    }

    @Test
    fun `maps must name a defined zone and an instanced map has no neighbours`() {
        val game = classic.copy(weather = definition)
        val ok = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to map(zoneId = 1))), game)
        assertTrue(ok.isValid, ok.errors.toString())

        val unknown = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to map(zoneId = 9))), game)
        assertEquals("unknown_weather_zone", unknown.errors.single().code)

        val noZonesDefined = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to map(zoneId = 9))), classic)
        assertTrue(noZonesDefined.isValid, noZonesDefined.errors.toString())

        val instanced = map().copy(instanced = true, neighbors = MapNeighborLinks(eastMapId = 2))
        val report = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to instanced, 2 to map(2))), game)
        assertTrue(report.errors.any { it.code == "instanced_with_neighbors" }, report.toString())
        assertFalse(ContentValidator.validate(ContentCatalog(maps = mapOf(1 to instanced.copy(neighbors = MapNeighborLinks()))), game).errors.any { it.code == "instanced_with_neighbors" })
    }

    @Test
    fun `old map files without the new fields still load as exterior world-zone maps`() {
        val m = MapDefinition(id = 1, name = "Old")
        assertEquals(0, m.zoneId)
        assertFalse(m.instanced)
        assertFalse(m.isIndoors)
        assertEquals(List(m.width * m.height) { MapCell() }, m.cells)
    }
}
