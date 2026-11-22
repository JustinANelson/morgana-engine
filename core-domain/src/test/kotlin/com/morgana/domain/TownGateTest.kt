package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.TownGateDefinition
import com.morgana.domain.game.TownGateTown
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TownGates
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TownGateTest {
    // 1 (town A) - 2 - 3 - 4 (town B); map 5 is a cave reached by a warp from 3; map 6 is cut off.
    private fun world(): Map<Int, MapDefinition> {
        fun map(id: Int, n: MapNeighborLinks = MapNeighborLinks(), warpTo: Int = 0): MapDefinition {
            val base = MapDefinition(id = id, name = "M$id", width = 10, height = 10, neighbors = n)
            if (warpTo == 0) return base
            val cells = base.cells.toMutableList()
            cells[0] = MapCell(attribute = TileAttribute.Warp(warpTo, 1, 1))
            return base.copy(cells = cells)
        }
        return listOf(
            map(1, MapNeighborLinks(eastMapId = 2)),
            map(2, MapNeighborLinks(westMapId = 1, eastMapId = 3)),
            map(3, MapNeighborLinks(westMapId = 2, eastMapId = 4), warpTo = 5),
            map(4, MapNeighborLinks(westMapId = 3)),
            map(5, warpTo = 3),
            map(6)
        ).associateBy { it.id }
    }

    private val towns = listOf(TownGateTown("A", 1, 5, 5), TownGateTown("B", 4, 6, 6))

    @Test
    fun theNearestTownByHopsWins() {
        val maps = world()
        assertEquals("A", TownGates.nearest(maps, 2, towns)?.name)
        assertEquals("B", TownGates.nearest(maps, 3, towns)?.name)
        assertEquals("A", TownGates.nearest(maps, 1, towns)?.name)
    }

    @Test
    fun warpTilesCountAsLinks() {
        assertEquals("B", TownGates.nearest(world(), 5, towns)?.name)
    }

    @Test
    fun aTieGoesToTheTownListedFirstAndAnIslandFallsBackToIt() {
        val maps = world()
        assertEquals("A", TownGates.nearest(maps, 2, towns.reversed().let { listOf(towns[0], towns[1]) })?.name)
        assertEquals("A", TownGates.nearest(maps, 6, towns)?.name)
        assertNull(TownGates.nearest(maps, 2, emptyList()))
    }

    private fun sim(disabled: Set<Int> = emptySet(), startMap: Int = 3): WorldSimulation {
        val definition = MorganaClassic.definition.copy(townGate = TownGateDefinition(towns = towns, disabledMapIds = disabled))
        val s = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(definition))
        world().values.forEach { s.registerMap(it) }
        s.items[1] = ItemDefinition(id = 1, name = "Town Portal", type = ItemType.CONSUMABLE, stackable = true, maxStack = 9, townGate = true)
        val withScroll = CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(startMap, 3, 3, Direction.DOWN))
        s.spawnOrUpdatePlayer(withScroll)
        val given = s.players.getValue("p").inventory.addItem(s.items.getValue(1), 2).updatedInventory
        s.players["p"] = s.players.getValue("p").copy(inventory = given)
        return s
    }

    private fun WorldSimulation.slotOfScroll(): Int = players.getValue("p").inventory.slots.indexOfFirst { it?.itemId == 1 }

    @Test
    fun usingThePortalWarpsToTheNearestTownAndConsumesOne() {
        val s = sim()
        s.useInventorySlot("p", s.slotOfScroll())
        val p = s.players.getValue("p")
        assertEquals(4, p.position.mapId)
        assertEquals(6, p.position.tileX)
        assertEquals(1, p.inventory.slots.filterNotNull().first { it.itemId == 1 }.amount)
    }

    @Test
    fun aForbiddenMapKeepsTheScroll() {
        val s = sim(disabled = setOf(3))
        s.useInventorySlot("p", s.slotOfScroll())
        val p = s.players.getValue("p")
        assertEquals(3, p.position.mapId)
        assertEquals(2, p.inventory.slots.filterNotNull().first { it.itemId == 1 }.amount)
    }

    @Test
    fun validatorWantsTownsOnRealMapsWithoutDuplicates() {
        val bad = MorganaClassic.definition.copy(townGate = TownGateDefinition(towns = listOf(TownGateTown("", 0, -1, 0), TownGateTown("X", 0, 1, 1))))
        val problems = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(problems.any { "/townGate/towns/0/name" in it }, problems.toString())
        assertTrue(problems.any { "/townGate/towns/0/mapId" in it }, problems.toString())
        assertTrue(problems.any { "share a map" in it }, problems.toString())
        assertNotNull(TownGateDefinition())
    }
}
