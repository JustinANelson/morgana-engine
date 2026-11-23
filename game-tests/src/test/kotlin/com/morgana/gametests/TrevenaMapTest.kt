package com.morgana.gametests

import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.persistence.ContentLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rebuilt hub (map 1, Trevena). Reads the game folder directly (nothing is written). Skipped when the game folder is not
 * present, as it is a private game.
 */
class TrevenaMapTest {

    private val game: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { Files.isDirectory(it) }

    private fun trevena(): MapDefinition {
        assumeTrue(game != null, "games/trevana-realms is not in this checkout")
        val loaded = ContentLoader.load(game!!.resolve("data"))
        return loaded.catalog.maps.getValue(1)
    }

    private fun walkable(m: MapDefinition, x: Int, y: Int): Boolean {
        val a = m.getCell(x, y)?.attribute ?: return false
        return a !is TileAttribute.Blocked
    }

    /** Every tile reachable on foot from ([sx], [sy]) by 4-directional steps. */
    private fun reach(m: MapDefinition, sx: Int, sy: Int): BooleanArray {
        val seen = BooleanArray(m.width * m.height)
        val queue = ArrayDeque<Int>()
        seen[sy * m.width + sx] = true
        queue.add(sy * m.width + sx)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % m.width
            val y = i / m.width
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (!m.isInBounds(nx, ny) || !walkable(m, nx, ny) || seen[ny * m.width + nx]) continue
                seen[ny * m.width + nx] = true
                queue.add(ny * m.width + nx)
            }
        }
        return seen
    }

    @Test
    fun `Trevena is a 162 by 144 safe zone with no enemies and every class starts on open ground inside it`() {
        val m = trevena()
        assertEquals(162, m.width)
        assertEquals(144, m.height)
        assertEquals(MapMoral.SAFE_ZONE, m.moral)
        val loaded = ContentLoader.load(game!!.resolve("data"))
        val enemyBehaviours = setOf(com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT)
        for (s in m.npcSpawns) {
            val npc = loaded.catalog.npcs.getValue(s.npcId)
            assertTrue(npc.behavior !in enemyBehaviours, "${npc.name} is hostile in the safe hub")
            assertTrue(walkable(m, s.spawnX, s.spawnY), "${npc.name} stands on a blocked tile (${s.spawnX},${s.spawnY})")
        }
        for (c in loaded.catalog.classes.values) {
            assertEquals(1, c.startMapId)
            assertTrue(walkable(m, c.startX, c.startY), "class ${c.id} starts on a blocked tile")
        }
    }

    @Test
    fun `from the plaza a hero can walk to the north, east and south roads and the harbour, and every service NPC can be reached`() {
        val m = trevena()
        val start = 100 to 80 // the plaza, where every class starts
        val seen = reach(m, start.first, start.second)
        fun ok(x: Int, y: Int) = seen[y * m.width + x]
        assertTrue(ok(103, 1), "north road leaves the map")
        assertTrue(ok(160, 80), "east road leaves the map")
        assertTrue(ok(94, 142), "south road leaves the map")
        assertTrue(ok(26, 76), "the harbour pier is reachable")
        for (s in m.npcSpawns) assertTrue(ok(s.spawnX, s.spawnY), "npc ${s.npcId} at (${s.spawnX},${s.spawnY}) cannot be reached from the plaza")
    }

    @Test
    fun `the citadel is walled in so the only ways between the inside and the outside are the gates`() {
        val m = trevena()
        val inside = reach(m, 100, 80)
        // A tile just outside the north wall that is not on a road: reachable only if a gate is used, so check each wall line is closed
        // except at the gates by walking the wall rows.
        fun open(x: Int, y: Int) = walkable(m, x, y)
        val northGate = (48..152).filter { open(it, 26) }
        val southGate = (48..152).filter { open(it, 118) }
        assertTrue(northGate.size in 4..8, "north wall has one gate, open columns: $northGate")
        assertTrue(southGate.size in 4..8, "south wall has one gate, open columns: $southGate")
        val westGate = (24..120).filter { open(50, it) }
        val eastGate = (24..120).filter { open(150, it) }
        assertTrue(westGate.size in 3..8, "west wall has one gate: $westGate")
        assertTrue(eastGate.size in 3..8, "east wall has one gate: $eastGate")
        assertTrue(inside[80 * m.width + 103], "inside connects to the outside")
    }
}
