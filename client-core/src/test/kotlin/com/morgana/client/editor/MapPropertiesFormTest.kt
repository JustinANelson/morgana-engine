package com.morgana.client.editor

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.map.WeatherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapPropertiesFormTest {

    private val map = MapDefinition(
        id = 3, name = "Crypt", moral = MapMoral.OPEN_PVP, bgmTrackId = "crypt", weather = WeatherType.FOG,
        weatherIntensity = 0.25f, ambientLightHex = "#112233", isIndoors = true, zoneId = 2, instanced = false,
        neighbors = MapNeighborLinks(northMapId = 1, southMapId = 0, eastMapId = 2, westMapId = 0)
    )

    @Test
    fun `values round trip through apply`() {
        val v = MapPropertiesForm.valuesOf(map)
        assertEquals(map, MapPropertiesForm.apply(map, v))
        assertTrue(MapPropertiesForm.problems(map.id, v, setOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun `apply changes only what was typed`() {
        val v = MapPropertiesForm.valuesOf(map).copy(name = "  Deep Crypt ", zone = "5", west = "4", interior = false, instanced = true)
        val changed = MapPropertiesForm.apply(map, v)
        assertEquals("Deep Crypt", changed.name)
        assertEquals(5, changed.zoneId)
        assertTrue(changed.instanced)
        // The legacy fixed weather is not touched by the editor.
        assertEquals(WeatherType.FOG, changed.weather)
        assertEquals(0.25f, changed.weatherIntensity)
        assertEquals(4, changed.neighbors.westMapId)
        assertFalse(changed.isIndoors)
        assertEquals(map.cells, changed.cells)
        assertEquals(map.moral, changed.moral)
    }

    @Test
    fun `problems name each bad field`() {
        val v = MapPropertiesForm.valuesOf(map).copy(name = " ", light = "red", zone = "7", north = "x", south = "-1", east = "3", west = "9")
        val p = MapPropertiesForm.problems(map.id, v, setOf(1, 2, 3), setOf(2))
        assertEquals(7, p.size, p.toString())
        assertTrue(p.any { it.startsWith("Name") })
        assertTrue(p.any { it.startsWith("Ambient light") })
        assertTrue(p.any { it.startsWith("No weather zone") })
        assertTrue(p.any { it.startsWith("North") })
        assertTrue(p.any { it.startsWith("South") && it.contains("negative") })
        assertTrue(p.any { it.startsWith("East") && it.contains("this map") })
        assertTrue(p.any { it.startsWith("West") && it.contains("no map 9") })
    }

    @Test
    fun `an instanced map cannot have neighbours and zones are only checked when the game has them`() {
        val v = MapPropertiesForm.valuesOf(map).copy(instanced = true)
        assertTrue(MapPropertiesForm.problems(map.id, v, emptySet()).any { it.contains("instanced") })
        val free = v.copy(north = "0", east = "0")
        assertTrue(MapPropertiesForm.problems(map.id, free, emptySet()).isEmpty())
        assertTrue(MapPropertiesForm.problems(map.id, free.copy(zone = "40"), emptySet(), null).isEmpty())
        assertTrue(MapPropertiesForm.problems(map.id, free.copy(zone = "40"), emptySet(), setOf(0, 2)).any { it.startsWith("No weather zone") })
        assertTrue(MapPropertiesForm.problems(map.id, free.copy(zone = "0"), emptySet(), setOf(2)).isEmpty())
    }

    @Test
    fun `neighbours are not checked against an empty map list`() {
        val v = MapPropertiesForm.valuesOf(map).copy(west = "9")
        assertTrue(MapPropertiesForm.problems(map.id, v, emptySet()).isEmpty())
    }

    @Test
    fun `invalid values leave the setting unchanged`() {
        val v = MapPropertiesForm.valuesOf(map).copy(name = "", light = "oops", zone = "-4", north = "3", east = "abc")
        val changed = MapPropertiesForm.apply(map, v)
        assertEquals("Crypt", changed.name)
        assertEquals("#112233", changed.ambientLightHex)
        assertEquals(2, changed.zoneId)
        assertEquals(1, changed.neighbors.northMapId)
        assertEquals(2, changed.neighbors.eastMapId)
    }

    @Test
    fun `the next free map id fills gaps`() {
        assertEquals(1, MapPropertiesForm.nextFreeMapId(emptyList()))
        assertEquals(4, MapPropertiesForm.nextFreeMapId(listOf(1, 2, 3)))
        assertEquals(2, MapPropertiesForm.nextFreeMapId(listOf(1, 3)))
    }

    @Test
    fun `size limits`() {
        assertNull(MapPropertiesForm.sizeProblem("24", "18"))
        assertTrue(MapPropertiesForm.sizeProblem("4", "18") != null)
        assertTrue(MapPropertiesForm.sizeProblem("24", "300") != null)
        assertTrue(MapPropertiesForm.sizeProblem("a", "18") != null)
    }

    @Test
    fun `resizing keeps tiles from the top left drops outside spawns and is one undo step`() {
        val ground = MapCell(layers = mapOf(MapLayer.GROUND to TilePlacement(1, 2, 0)))
        val start = MapDefinition(
            id = 1, name = "T", width = 10, height = 8, cells = List(80) { ground },
            npcSpawns = listOf(MapNpcSpawnSlot(0, 4, 1, 1, 100), MapNpcSpawnSlot(1, 4, 9, 7, 100))
        )
        val s = MapEditSession()
        s.load(start)
        assertTrue(s.resize(12, 6))
        val d = s.draft!!
        assertEquals(12, d.width)
        assertEquals(6, d.height)
        assertEquals(72, d.cells.size)
        assertEquals(ground, d.getCell(9, 5))
        assertTrue(d.getCell(11, 0)!!.layers.isEmpty())
        assertEquals(1, d.npcSpawns.size)
        assertEquals(1, s.undoDepth)
        s.undo()
        assertEquals(start, s.draft)
        assertFalse(s.resize(4, 8))
        assertFalse(s.resize(10, 8))
    }
}
