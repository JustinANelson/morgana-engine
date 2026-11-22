package com.morgana.domain

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.FloorGeneration
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.map.WeightedLoot
import com.morgana.domain.map.WeightedSpawn
import com.morgana.domain.map.WeightedTile
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.contentMapId
import com.morgana.domain.sim.handleNpcKilled
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.game.MorganaClassic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Procedurally generated dungeon floors: the pure generator, its validation, and floors as party instances in a run. */
class FloorGeneratorTest {

    private val hub = 1
    private val floor1 = 2
    private val floor2 = 3

    private fun recipe(next: Int = 0, boss: Int = 0) = FloorGenerator(
        width = 40, height = 30, roomsMin = 6, roomsMax = 8,
        floorTile = TilePlacement(srcTileX = 0), wallTile = TilePlacement(srcTileX = 1), stairsTile = TilePlacement(srcTileX = 2),
        decorations = listOf(WeightedTile(TilePlacement(srcTileX = 5))), decorationChanceBp = 800,
        monsters = listOf(WeightedSpawn(1, 3), WeightedSpawn(2, 1)), monstersPerRoomMin = 1, monstersPerRoomMax = 2,
        loot = listOf(WeightedLoot(10, 1, 3)), lootMin = 2, lootMax = 4,
        bossNpcId = boss, nextMapId = next, exitMapId = hub, exitX = 5, exitY = 6
    )

    private fun template(id: Int, g: FloorGenerator) = MapDefinition(id = id, name = "Floor $id", width = 5, height = 5, instanced = true, generator = g)

    private fun walkable(map: MapDefinition, x: Int, y: Int) = map.getCell(x, y)?.attribute != TileAttribute.Blocked

    /** Every tile reachable on foot from (sx, sy). */
    private fun reachable(map: MapDefinition, sx: Int, sy: Int): Set<Pair<Int, Int>> {
        val seen = hashSetOf(sx to sy)
        val queue = ArrayDeque(listOf(sx to sy))
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = x + dx to y + dy
                if (map.isInBounds(n.first, n.second) && walkable(map, n.first, n.second) && seen.add(n)) queue.add(n)
            }
        }
        return seen
    }

    @Test
    fun `the same seed gives the same floor and different seeds give different floors`() {
        val t = template(floor1, recipe(next = floor2))
        val a = FloorGeneration.generate(t, 1_000_000, 42L)
        val b = FloorGeneration.generate(t, 1_000_000, 42L)
        val c = FloorGeneration.generate(t, 1_000_000, 43L)
        assertEquals(a, b)
        assertNotEquals(a.map.cells, c.map.cells)
    }

    @Test
    fun `room features are placed once each in rooms after the arrival room, never respawn, and change nothing when absent`() {
        val plain = FloorGeneration.generate(template(floor1, recipe(next = floor2)), 1_000_000, 7L)
        val withFeatures = FloorGeneration.generate(template(floor1, recipe(next = floor2).copy(features = listOf(WeightedSpawn(50, 1)), featuresMin = 2, featuresMax = 2)), 1_000_000, 7L)
        val shrines = withFeatures.map.npcSpawns.filter { it.npcId == 50 }
        assertEquals(2, shrines.size)
        assertTrue(shrines.all { it.respawnTicks == 0 }, "a shrine never comes back")
        assertTrue(shrines.none { s -> withFeatures.rooms[0].contains(s.spawnX, s.spawnY) }, "none in the arrival room")
        assertTrue(shrines.all { s -> walkable(withFeatures.map, s.spawnX, s.spawnY) && (s.spawnX to s.spawnY) in reachable(withFeatures.map, withFeatures.entryX, withFeatures.entryY) })
        assertEquals(plain.map.npcSpawns, withFeatures.map.npcSpawns.take(plain.map.npcSpawns.size), "monsters and the boss are placed before features")
        // A recipe without features draws no extra random numbers: the same floor as before the feature existed.
        assertEquals(plain, FloorGeneration.generate(template(floor1, recipe(next = floor2).copy(features = emptyList(), featuresMin = 3, featuresMax = 3)), 1_000_000, 7L))
        val bad = FloorGeneration.problems(recipe(next = floor2).copy(features = listOf(WeightedSpawn(50, 0)), featuresMin = 3, featuresMax = 1))
        assertTrue(bad.any { it.first.contains("features") })
    }

    @Test
    fun `a wall face tile goes on walls with floor below them and changes nothing else`() {
        val face = TilePlacement(srcTileX = 7)
        val plain = template(floor1, recipe(next = floor2))
        val faced = template(floor1, recipe(next = floor2).copy(wallFaceTile = face))
        for (seed in 1L..20L) {
            val a = FloorGeneration.generate(plain, 1_000_000, seed)
            val b = FloorGeneration.generate(faced, 1_000_000, seed)
            assertEquals(a.rooms, b.rooms, "same layout")
            assertEquals(a.map.npcSpawns, b.map.npcSpawns)
            assertEquals(a.loot, b.loot)
            var faces = 0
            for (y in 0 until b.map.height) for (x in 0 until b.map.width) {
                val cell = b.map.getCell(x, y)!!
                val floorBelow = y + 1 < b.map.height && b.map.getCell(x, y + 1)!!.attribute != TileAttribute.Blocked
                val wall = cell.attribute == TileAttribute.Blocked
                if (wall && floorBelow) { faces++; assertEquals(face, cell.layers[MapLayer.GROUND], "seed $seed: ($x, $y) is a wall face") }
                else assertEquals(a.map.getCell(x, y), cell, "seed $seed: ($x, $y) unchanged")
            }
            assertTrue(faces > 0)
        }
    }

    @Test
    fun `a fixed seed gives the same floor whatever seed the run asks for`() {
        val t = template(floor1, recipe(next = floor2).copy(fixedSeed = 99L))
        assertEquals(FloorGeneration.generate(t, 1_000_000, 1L).map.cells, FloorGeneration.generate(t, 1_000_000, 2L).map.cells)
    }

    @Test
    fun `every room, the stairs, the monsters and the loot can be reached from the entry, over many seeds`() {
        val t = template(floor1, recipe(next = floor2))
        for (seed in 1L..60L) {
            val f = FloorGeneration.generate(t, 1_000_000, seed)
            val map = f.map
            assertEquals(40, map.width)
            assertNull(map.generator, "a generated floor is not itself a template")
            val canReach = reachable(map, f.entryX, f.entryY)
            for (r in f.rooms) assertTrue((r.centerX to r.centerY) in canReach, "seed $seed: room $r is cut off")
            assertTrue((f.stairsX to f.stairsY) in canReach, "seed $seed: stairs unreachable")
            map.npcSpawns.forEach { s -> assertTrue((s.spawnX to s.spawnY) in canReach, "seed $seed: monster in a wall") }
            f.loot.forEach { l -> assertTrue((l.x to l.y) in canReach, "seed $seed: loot in a wall") }
            assertTrue(f.rooms.size >= 2, "seed $seed: only ${f.rooms.size} rooms")
            assertTrue(f.rooms[0].contains(f.entryX, f.entryY))
            assertTrue(map.npcSpawns.none { f.rooms[0].contains(it.spawnX, it.spawnY) }, "seed $seed: nothing waits in the arrival room")
            assertTrue(map.npcSpawns.all { it.respawnTicks == 0 }, "generated monsters never respawn")
            assertEquals(map.npcSpawns.indices.toList(), map.npcSpawns.map { it.slotIndex })
            val positions = map.npcSpawns.map { it.spawnX to it.spawnY } + f.loot.map { it.x to it.y } + listOf(f.entryX to f.entryY, f.stairsX to f.stairsY)
            assertEquals(positions.size, positions.toSet().size, "seed $seed: two things on one tile")
            f.loot.forEach { assertTrue(it.amount in 1..3) }
        }
    }

    @Test
    fun `walls are blocked, the stairs lead to the next floor and the last floor's exit to the hub with the boss beside it`() {
        val middle = FloorGeneration.generate(template(floor1, recipe(next = floor2)), 1_000_000, 7L)
        val wall = middle.map.cells.first { it.attribute == TileAttribute.Blocked }
        assertEquals(TilePlacement(srcTileX = 1), wall.layers[MapLayer.GROUND])
        val stairs = middle.map.getCell(middle.stairsX, middle.stairsY)!!
        assertEquals(TileAttribute.Warp(floor2, 0, 0), stairs.attribute)
        assertEquals(TilePlacement(srcTileX = 2), stairs.layers[MapLayer.MASK_1])
        assertTrue(middle.map.npcSpawns.none { it.npcId == 9 })
        // A mini-boss can guard the stairs of a middle floor too.
        val guarded = FloorGeneration.generate(template(floor1, recipe(next = floor2, boss = 9)), 1_000_002, 7L)
        val mini = guarded.map.npcSpawns.single { it.npcId == 9 }
        assertEquals(1, Math.abs(mini.spawnX - guarded.stairsX) + Math.abs(mini.spawnY - guarded.stairsY), "the mini-boss stands beside the stairs")
        assertEquals(TileAttribute.Warp(floor2, 0, 0), guarded.map.getCell(guarded.stairsX, guarded.stairsY)!!.attribute)

        val last = FloorGeneration.generate(template(floor2, recipe(boss = 9)), 1_000_001, 7L)
        assertEquals(TileAttribute.Warp(hub, 5, 6), last.map.getCell(last.stairsX, last.stairsY)!!.attribute)
        val boss = last.map.npcSpawns.single { it.npcId == 9 }
        assertEquals(1, Math.abs(boss.spawnX - last.stairsX) + Math.abs(boss.spawnY - last.stairsY), "the boss guards the exit")
    }

    @Test
    fun `validation checks the recipe and what it points at (a missing map is a warning, like every map link)`() {
        fun codes(maps: Map<Int, MapDefinition>, npcs: Set<Int> = setOf(1, 2, 9), items: Set<Int> = setOf(10)) = ContentValidator.validate(
            ContentCatalog(
                maps = maps,
                npcs = npcs.associateWith { NpcDefinition(id = it, name = "N$it") },
                items = items.associateWith { ItemDefinition(id = it, name = "I$it") }
            ),
            MorganaClassic.definition).issues.filter { it.collection == com.morgana.domain.validation.ContentCollection.MAPS }.map { it.code to it.pointer }

        val hubMap = MapDefinition(id = hub, name = "Hub", width = 10, height = 10)
        val good = mapOf(hub to hubMap, floor1 to template(floor1, recipe(next = floor2)), floor2 to template(floor2, recipe(boss = 9)))
        assertEquals(emptyList<Pair<String, String>>(), codes(good))

        assertTrue(("missing_reference" to "/generator/monsters/1/npcId") in codes(good, npcs = setOf(1, 9)))
        assertTrue(codes(good, items = emptySet()).any { it.second == "/generator/loot/0/itemId" })
        assertTrue(("generator_not_instanced" to "/generator") in codes(good + (floor1 to template(floor1, recipe(next = floor2)).copy(instanced = false))))
        assertTrue(("next_floor_not_generated" to "/generator/nextMapId") in codes(good + (floor1 to template(floor1, recipe(next = hub)))))
        assertTrue(codes(good + (floor2 to template(floor2, recipe().copy(exitMapId = 77)))).any { it.second == "/generator/exitMapId" })
        assertTrue(("out_of_bounds" to "/generator/exitX") in codes(good + (floor2 to template(floor2, recipe().copy(exitX = 50)))))
        assertTrue(("invalid_floor_generator" to "/generator/roomSizeMin") in codes(good + (floor2 to template(floor2, recipe().copy(roomSizeMin = 9, roomSizeMax = 4)))))
        assertTrue(("invalid_floor_generator" to "/generator/width") in codes(good + (floor2 to template(floor2, recipe().copy(width = 300)))))
    }

    // ---- floors in a run ----

    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(7L), game = MorganaClassic.COMPILED)
        sim.instanceSettings = InstanceSettings(emptyTicks = 10)
        listOf(1, 2, 9).forEach { sim.npcs[it] = NpcDefinition(id = it, name = "Monster $it") }
        sim.items[10] = ItemDefinition(id = 10, name = "Gold")
        sim.registerMap(MapDefinition(id = hub, name = "Hub", width = 12, height = 12))
        sim.registerMap(template(floor1, recipe(next = floor2)))
        sim.registerMap(template(floor2, recipe(boss = 9)))
        return sim
    }

    private fun player(id: String, party: String? = null) =
        CharacterState(id = id, accountId = "a-$id", name = id, position = WorldPosition.fromTile(hub, 3, 3, Direction.DOWN), partyId = party)

    private fun WorldSimulation.warp(id: String, mapId: Int) = applyScriptEffect(ScriptEffect.WarpPlayer(id, mapId, 0, 0))

    private fun WorldSimulation.at(id: String) = players.getValue(id).position

    @Test
    fun `a party descends through fresh generated floors and the exit brings them home`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(player("ann", "p1"))
        sim.spawnOrUpdatePlayer(player("ben", "p1"))

        sim.warp("ann", floor1)
        sim.warp("ben", floor1)
        val first = sim.at("ann").mapId
        assertTrue(MapInstances.isInstanceId(first))
        assertEquals(first, sim.at("ben").mapId, "the party shares the floor")
        val inst = sim.instances.getValue(first)
        assertEquals(inst.entryX to inst.entryY, sim.at("ann").tileX to sim.at("ann").tileY, "arrivals stand at the floor's entry")
        assertEquals(40, sim.maps.getValue(first).width, "the copy is the generated floor, not the 5x5 template")
        assertTrue(sim.mapNpcs.getValue(first).isNotEmpty())
        assertTrue(sim.mapGroundItems.getValue(first).isNotEmpty(), "loot lies on the floor")

        // Take the stairs: step onto them from a neighbouring floor tile.
        val map = sim.maps.getValue(first)
        val stairs = map.cells.indexOfFirst { it.attribute is TileAttribute.Warp }
        val sx = stairs % map.width
        val sy = stairs / map.width
        val (fromX, dir) = if (walkable(map, sx - 1, sy)) sx - 1 to Direction.RIGHT else sx + 1 to Direction.LEFT
        sim.spawnOrUpdatePlayer(sim.players.getValue("ann").copy(position = WorldPosition.fromTile(first, fromX, sy, dir)))
        repeat(20) { sim.stepTick() }
        sim.movePlayer("ann", dir, MovementMode.GRID_LOCKED)
        val second = sim.at("ann").mapId
        assertEquals(floor2, sim.contentMapId(second), "the stairs lead to the next floor")
        assertNotEquals(first, second)
        assertTrue(sim.mapNpcs.getValue(second).any { it.npcId == 9 }, "the last floor has its boss")
        assertEquals(WorldPosition.fromTile(hub, 3, 3, Direction.DOWN), sim.players.getValue("ann").instanceReturn, "the way home is kept across floors")

        // The exit goes back to the hub.
        sim.applyScriptEffect(ScriptEffect.WarpPlayer("ann", hub, 5, 6))
        assertEquals(hub, sim.at("ann").mapId)
        assertNull(sim.players.getValue("ann").instanceReturn)
    }

    @Test
    fun `monsters on a generated floor stay dead, and walking back into an abandoned floor starts a fresh one`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", floor1)
        val first = sim.at("ann").mapId
        val npc = sim.mapNpcs.getValue(first).first()
        val dead = sim.handleNpcKilled(npc, sim.npcs.getValue(npc.npcId), null, mutableListOf())
        sim.mapNpcs.getValue(first)[0] = dead
        repeat(500) { sim.stepTick() }
        assertFalse(sim.mapNpcs.getValue(first)[0].isAlive, "no respawn on a generated floor")

        sim.warp("ann", hub)
        repeat(3) { sim.stepTick() }
        sim.warp("ann", floor1)
        val again = sim.at("ann").mapId
        assertNotEquals(first, again, "a fresh floor")
        assertFalse(first in sim.maps, "the abandoned floor is closed")
    }

    @Test
    fun `the same inputs build the same floors`() {
        fun run(): String {
            val sim = world()
            sim.spawnOrUpdatePlayer(player("ann"))
            repeat(5) { sim.stepTick() }
            sim.warp("ann", floor1)
            repeat(5) { sim.stepTick() }
            return StateHasher.hash(sim) + sim.maps.getValue(sim.at("ann").mapId).cells.hashCode()
        }
        assertEquals(run(), run())
    }
}
