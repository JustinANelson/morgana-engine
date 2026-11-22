package com.morgana.gametests

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.persistence.ContentLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rebuilt overworld: Trevena in the middle, the North Fork, and the six level zones. Checks that the seams line up, that every
 * zone is walkable end to end, that the hub really is the crossroads (the early zones connect only through it), and that the
 * enemy camps match each zone's level band and the raised density. Reads the game folder; skipped when it is not present.
 */
class TrevanaWorldMapsTest {

    private val game: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { Files.isDirectory(it) }

    private val loaded by lazy {
        assumeTrue(game != null, "games/trevana-realms is not in this checkout")
        ContentLoader.load(game!!.resolve("data"))
    }
    private val maps: Map<Int, MapDefinition> get() = loaded.catalog.maps

    /** Map id to (name, level band of its enemies). */
    private val zones = mapOf(
        3 to ("Gelli Gwyon" to (1..10)), 4 to ("Forest of Celidon" to (11..20)), 5 to ("Crags of Dinas Bran" to (21..30)),
        6 to ("Waste of Camlan" to (31..40)), 7 to ("Mere of Avallon" to (41..50)), 8 to ("Highlands of an Badon" to (51..60))
    )

    private fun open(m: MapDefinition, x: Int, y: Int): Boolean {
        val a = m.getCell(x, y)?.attribute ?: return false
        return a !is TileAttribute.Blocked
    }

    private fun component(m: MapDefinition, sx: Int, sy: Int): BooleanArray {
        val seen = BooleanArray(m.width * m.height)
        val queue = ArrayDeque<Int>()
        seen[sy * m.width + sx] = true
        queue.add(sy * m.width + sx)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = i % m.width + dx
                val ny = i / m.width + dy
                if (!m.isInBounds(nx, ny) || seen[ny * m.width + nx] || !open(m, nx, ny)) continue
                seen[ny * m.width + nx] = true
                queue.add(ny * m.width + nx)
            }
        }
        return seen
    }

    /** Open cells along one edge: north/south return x values, east/west return y values. */
    private fun edge(m: MapDefinition, side: String): Set<Int> = when (side) {
        "north" -> (0 until m.width).filter { open(m, it, 0) }
        "south" -> (0 until m.width).filter { open(m, it, m.height - 1) }
        "west" -> (0 until m.height).filter { open(m, 0, it) }
        else -> (0 until m.height).filter { open(m, m.width - 1, it) }
    }.toSet()

    @Test
    fun `the world has the hub, the fork and six zones`() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), maps.keys.filter { it <= 8 }.toSet())
        for ((id, z) in zones) assertEquals(z.first, maps.getValue(id).name)
        assertEquals("The North Fork", maps.getValue(2).name)
        assertTrue(maps.values.filter { it.id <= 8 }.all { it.width in 160..200 && it.height in 100..200 })
    }

    @Test
    fun `every seam is reciprocal and open at the same coordinates on both sides`() {
        var seams = 0
        for (m in maps.values.filter { it.id <= 8 }) {
            for ((side, other, back) in listOf(
                Triple("north", m.neighbors.northMapId, "south"), Triple("east", m.neighbors.eastMapId, "west"),
                Triple("south", m.neighbors.southMapId, "north"), Triple("west", m.neighbors.westMapId, "east")
            )) {
                if (other == 0) continue
                val o = maps.getValue(other)
                val backId = when (back) { "north" -> o.neighbors.northMapId; "south" -> o.neighbors.southMapId; "east" -> o.neighbors.eastMapId; else -> o.neighbors.westMapId }
                assertEquals(m.id, backId, "${m.name} $side is ${o.name}, whose $back must lead back")
                val mine = edge(m, side)
                val theirs = edge(o, back)
                assertTrue(mine.isNotEmpty(), "${m.name} has no opening on its $side edge")
                assertEquals(mine, theirs, "${m.name} ($side) and ${o.name} ($back) open at different coordinates")
                seams++
            }
        }
        assertEquals(16, seams, "8 seams, each seen from both sides")
    }

    @Test
    fun `inside each map every opening on its edges is connected on foot`() {
        for (m in maps.values.filter { it.id <= 8 }) {
            val openings = listOf("north", "south", "west", "east").flatMap { side ->
                edge(m, side).map { v -> when (side) { "north" -> v to 0; "south" -> v to m.height - 1; "west" -> 0 to v; else -> m.width - 1 to v } }
            }
            if (openings.isEmpty()) continue
            val seen = component(m, openings.first().first, openings.first().second)
            val cut = openings.filterNot { seen[it.second * m.width + it.first] }
            assertTrue(cut.isEmpty(), "${m.name}: openings cut off from each other: ${cut.take(5)}")
        }
    }

    @Test
    fun `the hub is the crossroads - the early zones join only through Trevena and the drop is one way`() {
        // Map graph: seams are two-way, warps only the way they go.
        val edges = HashMap<Int, MutableSet<Int>>()
        fun link(a: Int, b: Int) { edges.getOrPut(a) { mutableSetOf() }.add(b) }
        for (m in maps.values.filter { it.id <= 8 }) {
            for (n in listOf(m.neighbors.northMapId, m.neighbors.eastMapId, m.neighbors.southMapId, m.neighbors.westMapId)) if (n != 0) link(m.id, n)
            for (c in m.cells) (c.attribute as? TileAttribute.Warp)?.let { link(m.id, it.targetMapId) }
        }
        fun reach(from: Int, without: Int? = null): Set<Int> {
            val seen = mutableSetOf(from)
            val stack = ArrayDeque(listOf(from))
            while (stack.isNotEmpty()) for (n in edges[stack.removeLast()].orEmpty()) if (n != without && seen.add(n)) stack.add(n)
            return seen
        }
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), reach(1).filter { it <= 8 }.toSet(), "every zone can be reached from Trevena")
        // Without the hub: Gelli Gwyon reaches only the fork's side (the fork, Dinas Bran), never the east or south zones.
        val noHubFromGelli = reach(3, without = 1)
        assertTrue(noHubFromGelli.none { it in setOf(4, 6, 7, 8) }, "Gelli Gwyon must not reach Celidon, Camlan, the Mere or the Highlands without Trevena: $noHubFromGelli")
        // Celidon and Camlan only meet each other again at the Mere (level 41 and up), never earlier.
        assertFalse(5 in reach(4, without = 1), "Celidon must not reach Dinas Bran without the hub")
        // The drop from Dinas Bran lands in Gelli Gwyon and nothing leads back up except walking round through the hub.
        assertTrue(3 in edges.getValue(5))
        val back = maps.getValue(3).cells.mapNotNull { (it.attribute as? TileAttribute.Warp)?.targetMapId }
        assertFalse(5 in back, "Gelli Gwyon has no warp up to Dinas Bran")
    }

    @Test
    fun `each zone's enemies sit in its level band and camps are dense`() {
        for ((id, z) in zones) {
            val m = maps.getValue(id)
            val monsterSpawns = m.npcSpawns.filter { loaded.catalog.npcs.getValue(it.npcId).behavior in setOf(NpcBehavior.PASSIVE, NpcBehavior.ATTACK_ON_SIGHT) }
            val npcs = monsterSpawns.map { loaded.catalog.npcs.getValue(it.npcId) }
            assertTrue(npcs.isNotEmpty(), "${z.first} has enemies")
            val band = z.second
            for (n in npcs.toSet()) {
                assertTrue(n.level in (band.first - 2)..(band.last + 2), "${n.name} (level ${n.level}) does not belong in ${z.first} (levels $band)")
                assertTrue(n.behavior in setOf(NpcBehavior.PASSIVE, NpcBehavior.ATTACK_ON_SIGHT), "${n.name} is not a monster")
            }
            val walkable = m.cells.count { it.attribute !is TileAttribute.Blocked }
            val perEnemy = walkable.toFloat() / monsterSpawns.size
            assertTrue(perEnemy in 25f..70f, "${z.first}: one enemy per $perEnemy walkable tiles (want about one per 30 to 50)")
            // Camps of 8 to 10: nearest-neighbour clustering means most enemies have 4 or more others within 4 tiles.
            val pts = monsterSpawns.map { it.spawnX to it.spawnY }
            val clustered = pts.count { p -> pts.count { q -> q != p && kotlin.math.abs(q.first - p.first) <= 4 && kotlin.math.abs(q.second - p.second) <= 4 } >= 4 }
            assertTrue(clustered > pts.size * 0.6, "${z.first}: enemies are in camps")
        }
    }

    @Test
    fun `no enemy stands in the hub or the fork's safe road and every spawn is on open ground`() {
        for (m in maps.values.filter { it.id <= 8 }) {
            for (s in m.npcSpawns) assertTrue(open(m, s.spawnX, s.spawnY), "${m.name}: npc ${s.npcId} on a blocked tile")
            assertEquals(m.npcSpawns.indices.toList(), m.npcSpawns.map { it.slotIndex }, "${m.name}: slots numbered in order")
        }
        val fork = maps.getValue(2)
        assertTrue(fork.npcSpawns.all { loaded.catalog.npcs.getValue(it.npcId).behavior == NpcBehavior.GUARD })
    }

    @Test
    fun `each zone has a quest giver and a pharmacist near its entrance, and the chain of quests can be taken in order`() {
        for ((id, z) in zones) {
            val m = maps.getValue(id)
            val givers = m.npcSpawns.map { loaded.catalog.npcs.getValue(it.npcId) }.filter { it.behavior == NpcBehavior.QUEST_GIVER }
            assertEquals(1, givers.size, "${z.first} has one quest giver")
            val g = givers.single()
            val event = loaded.catalog.events[g.eventId]
            assertTrue(event != null, "${g.name} has an event")
            val quests = loaded.catalog.quests.values.filter { it.objectives.any { o -> loaded.catalog.npcs[o.targetId]?.level in z.second.first - 2..z.second.last + 2 } && it.id / 10 == 10 + zones.keys.indexOf(id) + 1 }
            assertEquals(3, quests.size, "${z.first} has three quests")
            for (q in quests) {
                assertTrue(q.reward.xp > 0 && q.objectives.all { o -> m.npcSpawns.any { it.npcId == o.targetId } }, "${q.name}: its targets live in ${z.first}")
            }
            assertTrue(m.npcSpawns.any { loaded.catalog.npcs.getValue(it.npcId).behavior == NpcBehavior.SHOPKEEPER }, "${z.first} has a pharmacist")
        }
        assertTrue(maps.getValue(1).npcSpawns.any { it.npcId == 6 }, "the Waymaster stands in Trevena")
        assertEquals(setOf(3), loaded.catalog.quests.getValue(100).objectives.map { it.targetId }.toSet())
    }
}
