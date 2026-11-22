package com.morgana.client

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.protocol.NeighborMapEntities
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Soft seams: entities across a border are shown, and walking over a border does not make sprites or the camera jump. */
class SoftSeamsClientTest {

    private val west = MapDefinition(id = 1, name = "West", width = 10, height = 10, neighbors = MapNeighborLinks(eastMapId = 2))
    private val east = MapDefinition(id = 2, name = "East", width = 12, height = 10, neighbors = MapNeighborLinks(westMapId = 1))
    private val far = MapDefinition(id = 9, name = "Far", width = 10, height = 10)

    private fun hero(map: Int, x: Int, y: Int = 5) = CharacterState(
        id = "hero", accountId = "a", name = "Hero", position = WorldPosition.fromTile(map, x, y, Direction.RIGHT)
    )

    private fun npc(map: Int, id: String, x: Int, y: Int = 5) = MapNpcInstance(
        instanceId = id, spawnSlotIndex = 0, npcId = 1, mapId = map, tileX = x, tileY = y, homeX = x, homeY = y,
        vitals = mapOf("hp" to VitalState(10, 10))
    )

    private fun snapshot(c: CharacterState) = PublicPlayerSnapshot(
        characterId = c.id, name = c.name, level = 1, spriteId = 1, accessRank = 0, nameplateColorHex = "#FFFFFF", position = c.position
    )

    private fun stateOn(map: MapDefinition, neighbors: Map<Int, MapDefinition>, x: Int): ClientWorldState {
        val state = ClientWorldState()
        state.applyServerPacket(ServerPacket.AuthResult(true, "ok", character = hero(map.id, x), catalog = WorldContentCatalogDto()))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = map, neighborMaps = neighbors, players = listOf(snapshot(hero(map.id, x))))
        )
        return state
    }

    @Test
    fun `entities across the east border are drawn at the right place in the active map space`() {
        val state = stateOn(west, mapOf(2 to east), x = 8)
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(2, npcs = listOf(npc(2, "wolf", x = 3, y = 4))))))
        val v = state.seamNpcVisuals["2:wolf"]
        assertNotNull(v)
        // The east map starts one active-map width (10 tiles = 320px) to the right.
        assertEquals(320f + 3 * 32f, v!!.renderPixelX, 0.01f)
        assertEquals(4 * 32f, v.renderPixelY, 0.01f)
    }

    @Test
    fun `entities across the west border use the neighbour's own width for the offset`() {
        val state = stateOn(east, mapOf(1 to west), x = 1)
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(1, npcs = listOf(npc(1, "slime", x = 8))))))
        // West map is 10 wide: its tile 8 sits at 8 - 10 = -2 tiles.
        assertEquals(-2 * 32f, state.seamNpcVisuals["1:slime"]!!.renderPixelX, 0.01f)
    }

    @Test
    fun `an empty seam view clears what was shown`() {
        val state = stateOn(west, mapOf(2 to east), x = 8)
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(2, npcs = listOf(npc(2, "wolf", x = 3))))))
        state.applyServerPacket(ServerPacket.SeamView(emptyList()))
        assertTrue(state.seamMaps.isEmpty())
        assertTrue(state.seamNpcVisuals.isEmpty())
    }

    @Test
    fun `walking over a border keeps the hero where it is on screen and glides on`() {
        val state = stateOn(west, mapOf(2 to east), x = 9)
        assertEquals(9 * 32f, state.playerVisuals["hero"]!!.renderPixelX, 0.01f)

        // The server moves the hero to the east map's first column.
        val moved = hero(2, 0)
        state.applyServerPacket(ServerPacket.CharacterSync(moved))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = east, neighborMaps = mapOf(1 to west), players = listOf(snapshot(moved)))
        )
        val vis = state.playerVisuals["hero"]!!
        // Old render x=288 in the west map is 288 - 320 = -32 in the east map's space: no jump, just one tile to glide.
        assertEquals(-32f, vis.renderPixelX, 0.01f)
        assertEquals(0f, vis.targetPixelX, 0.01f)
        state.updateVisualTimers(1f)
        assertEquals(0f, state.playerVisuals["hero"]!!.renderPixelX, 0.01f)
    }

    @Test
    fun `an npc seen across the border keeps its sprite position when its map becomes the active one`() {
        val state = stateOn(west, mapOf(2 to east), x = 9)
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(2, npcs = listOf(npc(2, "wolf", x = 2))))))
        state.updateVisualTimers(1f)
        val before = state.seamNpcVisuals["2:wolf"]!!.renderPixelX // 320 + 64

        val moved = hero(2, 0)
        state.applyServerPacket(ServerPacket.CharacterSync(moved))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(
                activeMap = east, neighborMaps = mapOf(1 to west), players = listOf(snapshot(moved)), npcs = listOf(npc(2, "wolf", x = 2))
            )
        )
        // Shifted by -320 into the east map's space, i.e. exactly its own tile 2: promoted, not re-created.
        assertEquals(before - 320f, state.npcVisuals["wolf"]!!.renderPixelX, 0.01f)
        assertEquals(2 * 32f, state.npcVisuals["wolf"]!!.renderPixelX, 0.01f)
        assertFalse(state.seamNpcVisuals.containsKey("2:wolf"))
    }

    @Test
    fun `a warp to a map that is not a neighbour snaps instead of gliding`() {
        val state = stateOn(west, mapOf(2 to east), x = 9)
        val warped = hero(9, 4)
        state.applyServerPacket(ServerPacket.CharacterSync(warped))
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = far, players = listOf(snapshot(warped))))
        val vis = state.playerVisuals["hero"]!!
        assertEquals(4 * 32f, vis.renderPixelX, 0.01f)
        assertEquals(vis.targetPixelX, vis.renderPixelX, 0.01f)
        assertTrue(state.seamNpcVisuals.isEmpty())
    }

    @Test
    fun `when softSeams module is disabled on the server the client ignores seam views and snaps across borders`() {
        val state = ClientWorldState()
        state.applyServerPacket(
            ServerPacket.AuthResult(
                true, "ok",
                character = hero(1, 9),
                catalog = WorldContentCatalogDto(),
                disabledModules = listOf("softSeams")
            )
        )
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = west, neighborMaps = mapOf(2 to east), players = listOf(snapshot(hero(1, 9))))
        )
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(2, npcs = listOf(npc(2, "wolf", x = 3))))))
        assertTrue(state.seamMaps.isEmpty(), "SeamView ignored when softSeams is disabled")
        assertTrue(state.seamNpcVisuals.isEmpty())

        val moved = hero(2, 0)
        state.applyServerPacket(ServerPacket.CharacterSync(moved))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = east, neighborMaps = mapOf(1 to west), players = listOf(snapshot(moved)))
        )
        assertEquals(0f, state.playerVisuals["hero"]!!.renderPixelX, 0.01f, "snaps instead of gliding when softSeams is disabled")
    }
}
