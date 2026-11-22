package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.TownGateDefinition
import com.morgana.domain.game.TownGateTown
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.FloorAlgorithm
import com.morgana.domain.map.FloorGeneration
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FloorVarietyAndPortalsTest {

    private fun isReachable(map: MapDefinition, sx: Int, sy: Int, tx: Int, ty: Int): Boolean {
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue += sx to sy
        visited += sy * map.width + sx
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            if (x == tx && y == ty) return true
            for ((dx, dy) in dirs) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until map.width || ny !in 0 until map.height) continue
                val key = ny * map.width + nx
                if (key in visited) continue
                if (map.getCell(nx, ny)?.attribute is TileAttribute.Blocked) continue
                visited += key
                queue += nx to ny
            }
        }
        return false
    }

    @Test
    fun `all four floor algorithms generate deterministic reachable floors with entry and stairs`() {
        for (algo in FloorAlgorithm.entries) {
            val template = MapDefinition(
                id = 2, name = "Floor", width = 10, height = 10, instanced = true,
                generator = FloorGenerator(
                    width = 36, height = 28,
                    algorithm = algo,
                    corridorWidth = 2,
                    caveFillPercent = 45,
                    roomsMin = 4, roomsMax = 7,
                    nextMapId = 0, exitMapId = 1, exitX = 5, exitY = 5
                )
            )
            assertTrue(FloorGeneration.problems(template.generator!!).isEmpty(), "valid recipe for $algo")
            val f1 = FloorGeneration.generate(template, 1_000_000, seed = 12345L)
            val f2 = FloorGeneration.generate(template, 1_000_000, seed = 12345L)
            assertEquals(f1.map.cells, f2.map.cells, "$algo is deterministic for the same seed")
            assertFalse(f1.map.getCell(f1.entryX, f1.entryY)?.attribute is TileAttribute.Blocked, "$algo entry is walkable")
            assertTrue(isReachable(f1.map, f1.entryX, f1.entryY, f1.stairsX, f1.stairsY), "$algo stairs are reachable from entry")
        }
    }

    @Test
    fun `two-way town portal returns player to exact dungeon instance coordinates via slash return`() {
        val def = MorganaClassic.definition.copy(
            townGate = TownGateDefinition(
                enabled = true,
                towns = listOf(TownGateTown("Hub", mapId = 1, x = 3, y = 3)),
                leaveReturnPortal = true
            )
        )
        val sim = WorldSimulation(SeededRandomSource(42L), game = CompiledGame.compile(def))
        val scroll = ItemDefinition(id = 50, name = "Town Portal Scroll", type = ItemType.CONSUMABLE, stackable = true, townGate = true)
        sim.items[50] = scroll
        sim.registerMap(
            MapDefinition(id = 1, name = "Hub", width = 10, height = 10, moral = MapMoral.SAFE_ZONE)
                .withCell(5, 4, MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 0, targetY = 0)))
        )
        sim.registerMap(
            MapDefinition(
                id = 2, name = "Crypt (floor 1)", width = 10, height = 10, instanced = true, moral = MapMoral.INSTANCED_DUNGEON,
                generator = FloorGenerator(width = 24, height = 20, roomsMin = 3, roomsMax = 4, nextMapId = 0, exitMapId = 1, exitX = 5, exitY = 5)
            )
        )
        val hero = CharacterState(
            id = "p1", accountId = "a1", name = "Hero", level = 5, classId = 1,
            position = WorldPosition.fromTile(1, 5, 5, Direction.UP),
            inventory = Inventory().addItem(scroll, 2).updatedInventory
        )
        sim.spawnOrUpdatePlayer(hero)
        // Enter dungeon instance
        sim.movePlayer("p1", Direction.UP)
        val inDungeon = sim.players["p1"]!!
        assertTrue(MapInstances.isInstanceId(inDungeon.position.mapId), "inside instanced floor")
        val dungeonMapId = inDungeon.position.mapId
        val dungeonX = inDungeon.position.tileX
        val dungeonY = inDungeon.position.tileY

        // Use town portal scroll -> warps to Hub (map 1) and opens return portal
        sim.useInventorySlot("p1", 0)
        val inTown = sim.players["p1"]!!
        assertEquals(1, inTown.position.mapId, "warped to town")
        assertEquals(3, inTown.position.tileX)
        assertEquals(3, inTown.position.tileY)
        assertTrue(sim.returnPortals.containsKey("solo:p1"), "return portal recorded")

        // Use /return -> warps right back to the exact dungeon instance and tile
        sim.executeAdminSlashCommand("p1", "/return")
        val returned = sim.players["p1"]!!
        assertEquals(dungeonMapId, returned.position.mapId, "returned to same dungeon instance")
        assertEquals(dungeonX, returned.position.tileX)
        assertEquals(dungeonY, returned.position.tileY)
    }
}
