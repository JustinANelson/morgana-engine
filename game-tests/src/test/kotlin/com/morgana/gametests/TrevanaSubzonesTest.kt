package com.morgana.gametests

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.persistence.ContentLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The eight dungeons off the overworld: the starter cavern, the six Lv 60 subzones and the two raids. Checks the door from each
 * zone, the way back, that everything inside can be walked to from the entrance, and that the monsters and bosses are the right
 * level and drop the zone's core material. Reads the game folder; skipped when it is not present.
 */
class TrevanaSubzonesTest {

    private val game: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { Files.isDirectory(it) }
    private val loaded by lazy {
        assumeTrue(game != null, "games/trevana-realms is not in this checkout")
        ContentLoader.load(game!!.resolve("data"))
    }
    private val maps: Map<Int, MapDefinition> get() = loaded.catalog.maps

    /** dungeon id -> (parent zone id, the door's warp cell, name, core material, instanced). */
    private val dungeons = mapOf(
        9 to listOf("The Cavern of an Ogof", 3, 139, 45, 164, false), 10 to listOf("Eglos an Bereon", 3, 25, 23, 164, false),
        11 to listOf("The Hollow of Yr Allt", 4, 121, 169, 165, false), 12 to listOf("The Vault of yr Wiber", 5, 21, 15, 166, false),
        13 to listOf("The Obelisk of Maen Llia", 6, 165, 157, 167, false), 14 to listOf("The Vault of the Cranner", 7, 151, 169, 168, false),
        15 to listOf("The Throne of Yr Orsedd", 8, 136, 14, 169, true), 16 to listOf("The Rift of Caer Vandwy", 8, 170, 29, 169, true)
    )

    private fun open(m: MapDefinition, x: Int, y: Int) = m.getCell(x, y)?.attribute?.let { it !is TileAttribute.Blocked } ?: false

    private fun reach(m: MapDefinition, sx: Int, sy: Int): BooleanArray {
        val seen = BooleanArray(m.width * m.height)
        val q = ArrayDeque<Int>()
        seen[sy * m.width + sx] = true
        q.add(sy * m.width + sx)
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = i % m.width + dx
                val ny = i / m.width + dy
                if (!m.isInBounds(nx, ny) || seen[ny * m.width + nx] || !open(m, nx, ny)) continue
                seen[ny * m.width + nx] = true
                q.add(ny * m.width + nx)
            }
        }
        return seen
    }

    @Test
    fun `each zone has a door to its dungeon and each dungeon a way back to the tile below that door`() {
        for ((id, d) in dungeons) {
            val name = d[0] as String
            val parent = maps.getValue(d[1] as Int)
            val dun = maps.getValue(id)
            assertEquals(name, dun.name)
            assertTrue(dun.width in 90..100 && dun.height in 90..100, "$name is 90 to 100 tiles square")
            val door = parent.getCell(d[2] as Int, d[3] as Int)!!.attribute as? TileAttribute.Warp
            assertTrue(door != null && door.targetMapId == id, "$name: the door in ${parent.name} leads in")
            assertTrue(open(dun, door!!.targetX, door.targetY), "$name: arrival tile is open")
            val back = dun.cells.mapNotNull { it.attribute as? TileAttribute.Warp }
            assertTrue(back.isNotEmpty() && back.all { it.targetMapId == parent.id }, "$name: exits lead back to ${parent.name}")
            val b = back.first()
            assertTrue(open(parent, b.targetX, b.targetY) && b.targetX == d[2] as Int && b.targetY == (d[3] as Int) + 1, "$name: you step out below the door")
            // From the tile below the door the hero can walk to the zone's other openings: the door is not in a sealed pocket.
            val seen = reach(parent, b.targetX, b.targetY)
            val anyEdge = (0 until parent.width).any { open(parent, it, 0) && seen[it] } || (0 until parent.height).any { (open(parent, 0, it) && seen[it * parent.width]) || (open(parent, parent.width - 1, it) && seen[it * parent.width + parent.width - 1]) } ||
                (0 until parent.width).any { open(parent, it, parent.height - 1) && seen[(parent.height - 1) * parent.width + it] }
            assertTrue(anyEdge, "$name: the door's landing in ${parent.name} connects to the rest of the zone")
            assertEquals(d[5] as Boolean, dun.instanced, "$name instanced")
        }
    }

    @Test
    fun `everything in a dungeon can be walked to from the entrance, the boss is there, and levels and drops fit`() {
        for ((id, d) in dungeons) {
            val dun = maps.getValue(id)
            val door = maps.getValue(d[1] as Int).getCell(d[2] as Int, d[3] as Int)!!.attribute as TileAttribute.Warp
            val seen = reach(dun, door.targetX, door.targetY)
            val core = d[4] as Int
            var bosses = 0
            for (s in dun.npcSpawns) {
                val npc = loaded.catalog.npcs.getValue(s.npcId)
                assertTrue(seen[s.spawnY * dun.width + s.spawnX], "${dun.name}: ${npc.name} cannot be reached")
                if (id != 9) assertTrue(npc.level >= 60, "${dun.name}: ${npc.name} is level ${npc.level}")
                assertTrue(npc.behavior == NpcBehavior.ATTACK_ON_SIGHT || (id == 9 && npc.behavior == NpcBehavior.PASSIVE), "${npc.name} is a monster")
                assertTrue(id == 9 && npc.title.isEmpty() || npc.drops.any { it.itemId == core }, "${dun.name}: ${npc.name} drops material $core")
                if (npc.title.isNotEmpty()) bosses++
            }
            assertTrue(bosses >= 1, "${dun.name} has a boss")
            val walkable = dun.cells.count { it.attribute !is TileAttribute.Blocked }
            assertTrue(walkable.toFloat() / dun.npcSpawns.size in 25f..70f || id == 16, "${dun.name}: one enemy per ${walkable.toFloat() / dun.npcSpawns.size} tiles")
        }
        assertEquals(2, loaded.catalog.maps.values.count { it.instanced && it.id > 8 })
    }

    @Test
    fun `each zone's warden sends the hero to the boss of its dungeon, and the weekly raid repeats`() {
        val bossMaps = mapOf(114 to 10, 124 to 11, 134 to 12, 144 to 13, 154 to 14, 164 to 15, 165 to 16)
        for ((qid, mapId) in bossMaps) {
            val q = loaded.catalog.quests.getValue(qid)
            val bossId = q.objectives.first().targetId
            assertTrue(maps.getValue(mapId).npcSpawns.any { it.npcId == bossId }, "${q.name}: boss $bossId lives in ${maps.getValue(mapId).name}")
            assertEquals(60, q.levelReq.coerceAtLeast(60))
        }
        val weekly = loaded.catalog.quests.getValue(165)
        assertTrue(weekly.repeatable && weekly.cooldownTicks == 12_096_000L, "the raid quest repeats weekly")
    }
}
