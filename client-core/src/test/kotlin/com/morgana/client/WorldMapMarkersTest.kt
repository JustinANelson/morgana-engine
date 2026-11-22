package com.morgana.client

import com.morgana.client.ui.WorldMapMarkers
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.protocol.WorldMapSpawn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldMapMarkersTest {
    private val wolf = NpcDefinition(id = 1, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 12, xpReward = 40)
    private val deer = NpcDefinition(id = 2, name = "Deer", behavior = NpcBehavior.PASSIVE, level = 3, xpReward = 5)
    private val smith = NpcDefinition(id = 3, name = "Smith", behavior = NpcBehavior.SHOPKEEPER, level = 20, xpReward = 0)
    private val harmless = NpcDefinition(id = 4, name = "Chicken", behavior = NpcBehavior.PASSIVE, level = 1, xpReward = 0)
    private val warden = NpcDefinition(id = 5, name = "Grove Warden", title = "Boss", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 30, xpReward = 900)
    private val byId = listOf(wolf, deer, smith, harmless, warden).associateBy { it.id }

    @Test
    fun `only creatures fought for experience are enemies`() {
        assertTrue(WorldMapMarkers.isEnemy(wolf))
        assertTrue(WorldMapMarkers.isEnemy(deer))
        assertFalse(WorldMapMarkers.isEnemy(smith))
        assertFalse(WorldMapMarkers.isEnemy(harmless))
        assertFalse(WorldMapMarkers.isEnemy(null))
    }

    @Test
    fun `a pack is one marker at its centre and different creatures stay apart`() {
        val spawns = listOf(
            WorldMapSpawn(1, 6, 10), WorldMapSpawn(1, 10, 10), // same 12-tile cell: one marker
            WorldMapSpawn(1, 60, 60), // far away: its own marker
            WorldMapSpawn(2, 11, 11), // another creature in the same cell
            WorldMapSpawn(3, 12, 12) // a shopkeeper: no marker
        )
        val markers = WorldMapMarkers.markers(spawns, byId)
        assertEquals(3, markers.size)
        val pack = markers.single { it.npcId == 1 && it.count == 2 }
        assertEquals(8f, pack.x)
        assertEquals(10f, pack.y)
        assertEquals("Wolf Lv.12 x2", pack.caption)
        assertEquals("Lv.12", pack.label)
        assertEquals(listOf(3, 12, 12), markers.map { it.level }, "weakest first, so the strongest is drawn on top")
    }

    @Test
    fun `ground the player has not seen shows no enemies, and bosses are flagged`() {
        val spawns = listOf(WorldMapSpawn(1, 5, 5), WorldMapSpawn(5, 90, 90))
        val seen = WorldMapMarkers.markers(spawns, byId) { x, _ -> x < 50 }
        assertEquals(listOf(1), seen.map { it.npcId })
        val all = WorldMapMarkers.markers(spawns, byId)
        assertTrue(all.single { it.npcId == 5 }.boss)
        assertFalse(all.single { it.npcId == 1 }.boss)
    }

    @Test
    fun `colour shows danger against the player's level and never vanishes into the dark map`() {
        assertEquals("86EFAC", WorldMapMarkers.colourHex(playerLevel = 20, level = 10)) // far below: green
        assertEquals("FFFFFF", WorldMapMarkers.colourHex(20, 21)) // even: white
        assertEquals("F87171", WorldMapMarkers.colourHex(20, 25)) // tough: red
        assertEquals("9CA3AF", WorldMapMarkers.colourHex(20, 40)) // hopeless: grey instead of near-black
        assertEquals("E5E7EB", WorldMapMarkers.colourHex(null, 40))
    }
}
