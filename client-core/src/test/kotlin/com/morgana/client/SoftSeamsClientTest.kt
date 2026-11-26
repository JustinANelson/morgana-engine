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

    // ---- a seamless world: maps of different sizes meeting at offsets ----

    /** The 10 x 10 [west] map with a 12 x 5 map whose top left tile sits 10 tiles east and 3 down from its own. */
    private val shelf = MapDefinition(id = 5, name = "Shelf", width = 12, height = 5)
    private val offsets = mapOf(5 to com.morgana.domain.map.TileOffset(10, 3))

    private fun seamlessState(x: Int, y: Int): ClientWorldState {
        val state = ClientWorldState()
        state.applyServerPacket(ServerPacket.AuthResult(true, "ok", character = hero(1, x, y), catalog = WorldContentCatalogDto()))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = west, neighborMaps = mapOf(5 to shelf), neighborOffsets = offsets, players = listOf(snapshot(hero(1, x, y))))
        )
        return state
    }

    @Test
    fun `a neighbour at an offset puts its entities at that offset in the active map's pixel space`() {
        val state = seamlessState(8, 5)
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(5, npcs = listOf(npc(5, "wolf", x = 2, y = 1))))))
        val v = state.seamNpcVisuals["5:wolf"]!!
        assertEquals((10 + 2) * 32f, v.renderPixelX, 0.01f)
        assertEquals((3 + 1) * 32f, v.renderPixelY, 0.01f)
    }

    @Test
    fun `crossing into an offset map shifts everything by that offset, so nothing jumps`() {
        val state = seamlessState(9, 5)
        assertEquals(9 * 32f, state.playerVisuals["hero"]!!.renderPixelX, 0.01f)
        val moved = hero(5, 0, 2) // row 5 of the west map is row 2 of the shelf
        state.applyServerPacket(ServerPacket.CharacterSync(moved))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = shelf, neighborMaps = mapOf(1 to west), neighborOffsets = mapOf(1 to com.morgana.domain.map.TileOffset(-10, -3)), players = listOf(snapshot(moved)))
        )
        val vis = state.playerVisuals["hero"]!!
        assertEquals(9 * 32f - 10 * 32f, vis.renderPixelX, 0.01f, "no jump: tile 9 of the west map is tile -1 of the shelf")
        assertEquals(5 * 32f - 3 * 32f, vis.renderPixelY, 0.01f)
        assertEquals(0f, vis.targetPixelX, 0.01f)
        assertEquals(2 * 32f, vis.targetPixelY, 0.01f)
    }

    @Test
    fun `the hero is predicted across a border instead of stopping at the edge`() {
        val state = seamlessState(9, 4)
        // Row 4 is above the shelf (which starts at row 3: rows 3..7) only if row >= 3; the hero is on row 4, so east of x=9 is the shelf.
        // Walking is free pixel movement here: 8 pixels a step, so the hero is over the edge (past tile 9's middle) after four steps.
        repeat(3) { state.predictAndCreateMovePacket(Direction.RIGHT) }
        val packet = state.predictAndCreateMovePacket(Direction.RIGHT)
        val predicted = state.localCharacter!!.position
        assertEquals(10, predicted.tileX, "the step onto the shelf was predicted, in the west map's coordinates")
        assertEquals(1, predicted.mapId)
        assertEquals(4, packet.seq)
        // Row 1 has no neighbour: stopped at the edge of the world.
        val stuck = seamlessState(9, 1)
        repeat(6) { stuck.predictAndCreateMovePacket(Direction.RIGHT) }
        assertEquals(9, stuck.localCharacter!!.position.tileX)
    }

    // ---- fighting across a border (seamless world) ----

    private val wolfDef = com.morgana.domain.content.NpcDefinition(id = 1, name = "Wolf", behavior = com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT, level = 1)

    private fun fightState(): ClientWorldState {
        val state = ClientWorldState()
        state.applyServerPacket(ServerPacket.AuthResult(true, "ok", character = hero(1, 8, 5), catalog = WorldContentCatalogDto(npcs = listOf(wolfDef))))
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(activeMap = west, neighborMaps = mapOf(5 to shelf), neighborOffsets = offsets, players = listOf(snapshot(hero(1, 8, 5))))
        )
        return state
    }

    @Test
    fun `a monster across the border that changes is updated in place, and removed when it dies`() {
        val state = fightState()
        state.applyServerPacket(ServerPacket.NpcUpdated(npc(5, "wolf", x = 2, y = 1)))
        assertEquals(listOf("wolf"), state.seamMaps.single { it.mapId == 5 }.npcs.map { it.instanceId })
        assertEquals((10 + 2) * 32f, state.seamNpcVisuals["5:wolf"]!!.targetPixelX, 0.01f)
        assertTrue(state.npcsOnMap.isEmpty(), "it is not on the active map")
        // It moves (a compact move for a monster on another map).
        state.applyServerPacket(ServerPacket.NpcMoved("wolf", 3, 1, Direction.LEFT, mapId = 5))
        assertEquals((10 + 3) * 32f, state.seamNpcVisuals["5:wolf"]!!.targetPixelX, 0.01f)
        // It dies.
        state.applyServerPacket(ServerPacket.NpcUpdated(npc(5, "wolf", x = 3, y = 1).copy(vitals = mapOf("hp" to VitalState(0, 10)))))
        assertTrue(state.seamNpcVisuals.isEmpty())
        assertTrue(state.seamMaps.none { it.npcs.isNotEmpty() })
    }

    @Test
    fun `damage text and skill effects from the other map are drawn where they happened in this map's space`() {
        val state = fightState()
        state.applyServerPacket(ServerPacket.SkillEffect(mapId = 5, effect = "bolt", fromX = 32f, fromY = 64f, toX = 64f, toY = 96f))
        val fx = state.skillFx.single()
        assertEquals(32f + 10 * 32f, fx.fromX, 0.01f)
        assertEquals(64f + 3 * 32f, fx.fromY, 0.01f)
        assertEquals(64f + 10 * 32f, fx.toX, 0.01f)
        state.applyServerPacket(ServerPacket.FloatingText(mapId = 5, tileX = 2, tileY = 1, text = "-9"))
        val text = state.floatingTexts.single()
        assertEquals((10 + 2) * 32f + 16f, text.worldX, 0.01f)
        // A map that is not a neighbour is ignored.
        state.applyServerPacket(ServerPacket.FloatingText(mapId = 99, tileX = 2, tileY = 1, text = "-1"))
        assertEquals(1, state.floatingTexts.size)
    }

    @Test
    fun `monsters across the border can be targeted and locked`() {
        val state = fightState()
        state.applyServerPacket(ServerPacket.SeamView(listOf(NeighborMapEntities(5, npcs = listOf(npc(5, "wolf", x = 2, y = 1))))))
        val candidates = state.targetCandidates()
        assertEquals(listOf("wolf"), candidates.map { it.id })
        assertEquals((10 + 2) * 32f + 16f, candidates.single().x, 0.5f)
        state.lockedNpcId = "wolf"
        state.refreshLock()
        assertEquals("wolf", state.lockedNpcId, "the lock survives while the monster lives across the border")
        assertNotNull(state.npcOrSeamNpc("wolf"))
    }

    @Test
    fun `a player on the map across the border is shown moving in real time, not only when the seam view refreshes`() {
        val state = fightState()
        val other = CharacterState(id = "ally", accountId = "b", name = "Ally", position = WorldPosition.fromTile(5, 2, 1, Direction.LEFT))
        state.applyServerPacket(ServerPacket.PlayerUpdated(snapshot(other)))
        assertTrue(state.playersOnMap.keys.none { it == "ally" }, "not on this map")
        assertEquals((10 + 2) * 32f, state.seamPlayerVisuals["ally"]!!.targetPixelX, 0.01f)
        state.applyServerPacket(ServerPacket.PlayerMoved("ally", WorldPosition.fromTile(5, 3, 1, Direction.RIGHT), mapId = 5))
        assertEquals((10 + 3) * 32f, state.seamPlayerVisuals["ally"]!!.targetPixelX, 0.01f)
        assertEquals(1, state.seamMaps.single { it.mapId == 5 }.players.size)
    }
}
