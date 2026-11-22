package com.morgana.client

import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.HotbarSlotAction
import com.morgana.client.state.MapEditorTool
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.entity.TradeSessionState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.FloatingTextCategory
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase5And6ClientStateTest {

    @Test
    fun `client world state applies server packets, tracks visual timers, and paints live F1 map edits`() {
        val state = ClientWorldState()
        val hero = CharacterState(
            id = "c1",
            accountId = "a1",
            name = "Hero",
            position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)
        )
        state.applyServerPacket(
            ServerPacket.AuthResult(
                success = true,
                message = "Logged in",
                character = hero,
                catalog = WorldContentCatalogDto()
            )
        )
        assertTrue(state.authenticated)
        assertEquals("Hero", state.localCharacter?.name)

        val map1 = MapDefinition(id = 1, name = "Havenwood", width = 10, height = 10)
        val map2 = MapDefinition(id = 2, name = "Outskirts", width = 10, height = 10)
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(
                activeMap = map1,
                neighborMaps = mapOf(2 to map2)
            )
        )
        assertEquals(1, state.activeMap?.id)
        assertTrue(state.neighborMaps.containsKey(2))

        // Client-side movement prediction (Grid-Locked & Sub-Tile Pixel) + visual interpolation
        state.disabledModules = setOf("subTileMovement") // realm without free pixel movement
        val gridMovePkt = state.predictAndCreateMovePacket(Direction.RIGHT)
        assertEquals(Direction.RIGHT, gridMovePkt.direction)
        assertEquals(6, state.localCharacter?.position?.tileX)
        assertEquals(5, state.localCharacter?.position?.tileY)

        // Advance visual interpolation timer so hero glides smoothly toward tile 6 (192px)
        state.updateVisualTimers(0.25f)
        val visualAfterGlide = state.playerVisuals["c1"]
        assertNotNull(visualAfterGlide)
        assertEquals(192f, visualAfterGlide!!.renderPixelX, 0.5f)

        state.disabledModules = emptySet() // module on: every player moves by pixels
        val subTilePkt = state.predictAndCreateMovePacket(Direction.DOWN)
        assertEquals(MovementMode.SUB_TILE, subTilePkt.mode)
        assertTrue(subTilePkt.deltaPixelsY > 0f)
        val diagonalPkt = state.predictAndCreateMovePacket(Direction.UP_LEFT)
        assertTrue(diagonalPkt.deltaPixelsX < 0f && diagonalPkt.deltaPixelsY < 0f, "a diagonal moves on both axes")
        assertEquals(0f, state.movementMode.let { if (it == MovementMode.SUB_TILE) 0f else 1f }, "module on means pixel movement for everyone")

        // Chat bubble & floating combat text lifecycle
        state.applyServerPacket(
            ServerPacket.ChatBroadcast(
                channel = ChatChannel.MAP,
                senderEntityId = "c1",
                senderName = "Hero",
                message = "Hello Havenwood!",
                showOverheadBubble = true
            )
        )
        state.applyServerPacket(
            ServerPacket.FloatingText(
                mapId = 1,
                tileX = 5,
                tileY = 5,
                text = "-25!",
                category = FloatingTextCategory.CRIT_DAMAGE
            )
        )
        assertEquals(1, state.chatBubbles.size)
        assertEquals(1, state.floatingTexts.size)

        state.updateVisualTimers(2.0f)
        assertEquals(0, state.floatingTexts.size)
        assertEquals(1, state.chatBubbles.size)
        state.updateVisualTimers(3.0f)
        assertEquals(0, state.chatBubbles.size)

        // Live F1 Map Editor painting, flood fill, eyedropper, and NPC spawn placement
        state.selectedLayer = MapLayer.MASK_1
        state.selectedSrcTileX = 4
        state.selectedAttribute = TileAttribute.Blocked
        state.paintOnWorkingMap(tileX = 3, tileY = 3, eraseLayer = false)

        val paintedCell = state.workingMapDraft?.getCell(3, 3)
        assertNotNull(paintedCell)
        assertEquals(4, paintedCell!!.layers[MapLayer.MASK_1]?.srcTileX)
        assertEquals(TileAttribute.Blocked, paintedCell.attribute)

        // Eyedropper picks tile & attribute from (3, 3)
        state.selectedSrcTileX = 0
        state.selectedAttribute = TileAttribute.None
        state.selectedTool = MapEditorTool.EYEDROPPER
        state.applyEditorActionAt(3, 3, isRightClick = false)
        assertEquals(4, state.selectedSrcTileX)
        assertEquals(TileAttribute.Blocked, state.selectedAttribute)
        assertEquals(MapEditorTool.BRUSH, state.selectedTool)

        // Erase layer
        state.paintOnWorkingMap(tileX = 3, tileY = 3, eraseLayer = true)
        val erasedCell = state.workingMapDraft?.getCell(3, 3)
        assertTrue(erasedCell!!.layers[MapLayer.MASK_1] == null)

        // Place and remove NPC spawn via NPC_SPAWN tool
        state.selectedTool = MapEditorTool.NPC_SPAWN
        state.selectedNpcSpawnId = 1
        state.applyEditorActionAt(2, 2, isRightClick = false)
        assertTrue(state.workingMapDraft!!.npcSpawns.any { it.npcId == 1 && it.spawnX == 2 && it.spawnY == 2 })
        state.applyEditorActionAt(2, 2, isRightClick = true)
        assertTrue(state.workingMapDraft!!.npcSpawns.none { it.spawnX == 2 && it.spawnY == 2 })

        // Hotbar configuration and slot assignments
        state.hotbarSlots[0] = HotbarSlotAction.CastSpell(spellId = 1, spellName = "Fireball")
        state.hotbarSlots[1] = HotbarSlotAction.UseItem(itemId = 3, itemName = "Health Potion")
        assertEquals("Fireball", state.hotbarSlots[0]?.label)
        assertEquals("Health Potion", state.hotbarSlots[1]?.label)
    }

    @Test
    fun `procedural retro sound engine synthesizes valid WAV byte streams for all SFX`() {
        for (sfx in RetroSoundEngine.Sfx.entries) {
            val wavBytes = RetroSoundEngine.generateWavFor(sfx)
            assertTrue(wavBytes.size > 44, "WAV size for $sfx must exceed 44-byte header")
            // Verify RIFF and WAVE header markers
            assertEquals('R'.code.toByte(), wavBytes[0])
            assertEquals('I'.code.toByte(), wavBytes[1])
            assertEquals('F'.code.toByte(), wavBytes[2])
            assertEquals('F'.code.toByte(), wavBytes[3])
            assertEquals('W'.code.toByte(), wavBytes[8])
            assertEquals('A'.code.toByte(), wavBytes[9])
            assertEquals('V'.code.toByte(), wavBytes[10])
            assertEquals('E'.code.toByte(), wavBytes[11])
        }
    }

    @Test
    fun `client tracks trade and party state updates and updates cast progress timers`() {
        val state = ClientWorldState()

        // Trade session update
        val trade = TradeSessionState(
            sessionId = "t1",
            initiatorId = "c1",
            partnerId = "c2",
            initiatorLocked = true,
            partnerLocked = false
        )
        state.applyServerPacket(ServerPacket.TradeUpdated(trade))
        assertEquals("t1", state.activeTrade?.sessionId)
        assertTrue(state.activeTrade?.initiatorLocked == true)

        // Trade finished/cancelled
        state.applyServerPacket(ServerPacket.TradeUpdated(null))
        assertEquals(null, state.activeTrade)

        // Party update
        val party = PartyState(
            partyId = "p1",
            leaderCharacterId = "c1",
            memberCharacterIds = listOf("c1", "c2"),
            shareXp = true
        )
        state.applyServerPacket(ServerPacket.PartyUpdated(party))
        assertEquals("p1", state.activeParty?.partyId)
        assertEquals(2, state.activeParty?.memberCharacterIds?.size)

        // Player cast progress tracking
        val castingPlayer = PublicPlayerSnapshot(
            characterId = "c2",
            name = "Mage",
            level = 5,
            spriteId = 1,
            accessRank = 0,
            nameplateColorHex = "#FFFFFF",
            position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
            castingSpellId = 10,
            castFinishTick = 20L,
            castTotalTicks = 20,
            castRemainingTicks = 20
        )
        state.applyServerPacket(ServerPacket.PlayerUpdated(castingPlayer))
        state.updateVisualTimers(0.5f) // 0.5s into a 20 tick * 0.05s = 1.0s cast
        val progress = state.playerCastProgress["c2"]
        assertNotNull(progress)
        assertTrue(progress!! in 0.45f..0.55f, "Cast progress should be ~0.5, was $progress")
    }

    @Test
    fun `client keeps the stall the server last showed`() {
        val state = ClientWorldState()
        assertEquals(null, state.stallView)
        val view = com.morgana.domain.game.StallView("Sam", "Sam's Potions", own = false, open = true, goods = listOf(com.morgana.domain.game.StallGood(1, 2, 0, 6, 20)))
        state.applyServerPacket(ServerPacket.StallViewed(view))
        assertEquals("Sam's Potions", state.stallView?.title)
        assertEquals(20, state.stallView?.goods?.single()?.unitPrice)
    }
}
