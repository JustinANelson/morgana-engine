package com.morgana.client.render

import com.morgana.client.assets.SpriteSheetLayout
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.EntityVisualSmoothing
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.game.SpriteState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.FloatingTextCategory
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpritePlacementAndSlicingTest {

    @Test
    fun `bottom-center anchor keeps feet on the tile and lifts UI offsets with sprite height`() {
        val mapHeight = 10
        val footX = 3 * 32f
        val footY = 4 * 32f

        val classic = SpritePlacement.place(footX, footY, mapHeight, tileSize = 32, profile = null)
        assertEquals(96f, classic.drawX, 0.01f)
        assertEquals(160f, classic.drawY, 0.01f)
        assertEquals(32f, classic.drawWidth, 0.01f)
        assertEquals(32f, classic.drawHeight, 0.01f)
        assertEquals(160f + 32f + 4f, classic.uiTopY, 0.01f)
        assertEquals(96f to 160f, SpritePlacement.placeOverlay(classic, 32f))

        val tall = SpritePlacement.place(footX, footY, mapHeight, tileSize = 32, profile = SpriteProfile.MODERN_TALL)
        assertEquals(96f, tall.drawX, 0.01f)
        assertEquals(160f, tall.drawY, 0.01f, "feet stay pinned to bottom of the tile")
        assertEquals(32f, tall.drawWidth, 0.01f)
        assertEquals(64f, tall.drawHeight, 0.01f, "taller sprite extends upward")
        assertEquals(160f + 64f + 4f, tall.uiTopY, 0.01f, "nameplates and HP bars sit above tall head")
        assertEquals(96f to 176f, SpritePlacement.placeOverlay(tall, 32f))

        val wide = SpritePlacement.place(
            footX,
            footY,
            mapHeight,
            tileSize = 32,
            profile = SpriteProfile(cellWidth = 48, cellHeight = 64)
        )
        assertEquals(88f, wide.drawX, 0.01f, "48px wide cell is centered horizontally around 32px tile")
        assertEquals(footX + 16f, wide.centerX, 0.01f)
        assertEquals(160f, wide.drawY, 0.01f)
    }

    @Test
    fun `foot Y-sorting orders top-of-screen behind bottom-of-screen across active and seam entities`() {
        val northSeamPlayer = EntitySortKey(footPixelY = -32f, SpritePlacement.KIND_PLAYER, "seam:p1")
        val row0Npc = EntitySortKey(footPixelY = 0f, SpritePlacement.KIND_NPC, "npc1")
        val row0Player = EntitySortKey(footPixelY = 0f, SpritePlacement.KIND_PLAYER, "hero")
        val movingNpc = EntitySortKey(footPixelY = 18.5f, SpritePlacement.KIND_NPC, "npc2")
        val row1Player = EntitySortKey(footPixelY = 32f, SpritePlacement.KIND_PLAYER, "p2")

        val sorted = listOf(row1Player, row0Player, movingNpc, row0Npc, northSeamPlayer).sorted()
        assertEquals(
            listOf(northSeamPlayer, row0Npc, row0Player, movingNpc, row1Player),
            sorted
        )
    }

    @Test
    fun `sheet slicer uses declared cell size and state columns, falling back to 3x4 when unconfigured`() {
        val classicGrid = SpriteSheetLayout.computeSheetGrid(96, 128, profile = null)
        assertEquals(4, classicGrid.size)
        assertEquals(3, classicGrid[0].size)
        assertEquals(32, classicGrid[0][0].width)
        assertEquals(32, classicGrid[0][0].height)

        val profile = SpriteProfile.MODERN_TALL // 32x64, 13 columns x 4 rows -> 416x256
        val tallGrid = SpriteSheetLayout.computeSheetGrid(416, 256, profile)
        assertEquals(4, tallGrid.size)
        assertEquals(13, tallGrid[0].size)
        assertEquals(32, tallGrid[0][0].width)
        assertEquals(64, tallGrid[0][0].height)
        assertEquals(4 * 32, tallGrid[1][4].x)
        assertEquals(1 * 64, tallGrid[1][4].y)

        assertEquals(0, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.IDLE))
        assertEquals(1, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.WALK, walkFrame = 1))
        assertEquals(2, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.WALK, walkFrame = 2))
        assertEquals(4, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.ATTACK, actionProgress = 0.0f))
        assertEquals(6, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.ATTACK, actionProgress = 0.99f))
        assertEquals(7, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.CAST, actionProgress = 0.1f))
        assertEquals(11, SpriteSheetLayout.resolveColumn(13, profile, SpriteState.HARVEST, actionProgress = 0.5f))

        // Unmatched sheet dimensions fall back safely to 3x4 instead of throwing out-of-bounds
        val mismatchFallback = SpriteSheetLayout.computeSheetGrid(96, 128, profile)
        assertEquals(3, mismatchFallback[0].size)
        assertEquals(1, SpriteSheetLayout.resolveColumn(3, profile, SpriteState.ATTACK, actionProgress = 0.2f))
    }

    @Test
    fun `client world state triggers attack, harvest, and cast cues and lifts floating text above tall sprites`() {
        val state = ClientWorldState()
        state.gameDefinition = MorganaClassic.definition.copy(sprites = SpriteProfile.MODERN_TALL)
        val map = MapDefinition(id = 1, name = "Town", width = 10, height = 10)
            .withCell(3, 2, com.morgana.domain.map.MapCell(attribute = TileAttribute.ResourceNode(resourceId = 1)))
        state.activeMap = map
        state.catalog = WorldContentCatalogDto(
            resources = listOf(ResourceDefinition(id = 1, name = "Oak Tree"))
        )
        state.localCharacter = CharacterState(
            id = "hero",
            accountId = "a",
            name = "Hero",
            position = WorldPosition(mapId = 1, tileX = 3, tileY = 3, direction = Direction.UP)
        )
        state.playerVisuals["hero"] = EntityVisualSmoothing(96f, 96f, 96f, 96f, Direction.UP)

        // Facing resource -> triggers HARVEST on both interact and attack
        state.triggerLocalInteract()
        assertEquals(SpriteState.HARVEST, state.playerVisuals["hero"]!!.actionState)
        state.updateVisualTimers(0.5f)
        assertNull(state.playerVisuals["hero"]!!.actionState, "action expires after duration")

        // Turn away from resource -> triggers ATTACK
        state.localCharacter = state.localCharacter!!.copy(
            position = WorldPosition(mapId = 1, tileX = 3, tileY = 3, direction = Direction.DOWN)
        )
        state.triggerLocalAttackOrHarvest()
        assertEquals(SpriteState.ATTACK, state.playerVisuals["hero"]!!.actionState)

        // Floating text spawns above tall sprite height (64 px instead of 32 px)
        state.applyServerPacket(
            ServerPacket.FloatingText(
                mapId = 1,
                tileX = 3,
                tileY = 3,
                text = "12",
                colorHex = "#FFFFFF",
                category = FloatingTextCategory.DAMAGE
            )
        )
        assertEquals(3 * 32 + 64f, state.floatingTexts.single().worldY, 0.01f)
    }

    @Test
    fun `four frame walk cycles on the clock and jump and hurt pick their own columns`() {
        val profile = SpriteProfile(
            stateFrames = mapOf(
                SpriteState.IDLE to 2, SpriteState.WALK to 4, SpriteState.ATTACK to 4, SpriteState.CAST to 4,
                SpriteState.JUMP to 3, SpriteState.HURT to 2
            )
        )
        val cols = profile.totalFrames
        val seen = (0 until 8).map {
            SpriteSheetLayout.resolveColumn(cols, profile, SpriteState.WALK, walkFrame = 1, elapsedSeconds = it / SpriteSheetLayout.WALK_FRAMES_PER_SECOND + 0.01f)
        }.toSet()
        assertEquals(setOf(2, 3, 4, 5), seen)
        assertEquals(14, SpriteSheetLayout.resolveColumn(cols, profile, SpriteState.JUMP, actionProgress = 0f))
        assertEquals(16, SpriteSheetLayout.resolveColumn(cols, profile, SpriteState.JUMP, actionProgress = 0.99f))
        assertEquals(17, SpriteSheetLayout.resolveColumn(cols, profile, SpriteState.HURT, actionProgress = 0.1f))
        assertEquals(18, SpriteSheetLayout.resolveColumn(cols, profile, SpriteState.HURT, actionProgress = 0.9f))
    }

    @Test
    fun `a scaled sprite grows from its feet and lifts the name plate with it`() {
        val profile = SpriteProfile(scales = mapOf(3 to 2f))
        assertEquals(2f, profile.scaleOf(3))
        assertEquals(1f, profile.scaleOf(4))
        val normal = SpritePlacement.place(64f, 64f, 10, 32, profile)
        val big = SpritePlacement.place(64f, 64f, 10, 32, profile, scale = profile.scaleOf(3))
        assertEquals(64f, big.drawWidth)
        assertEquals(64f, big.drawHeight)
        assertEquals(normal.drawY, big.drawY) // feet stay put
        assertEquals(normal.centerX, big.centerX)
        assertEquals(normal.uiTopY + 32f, big.uiTopY)
        assertEquals(normal.drawX - 16f, big.drawX)
    }

    @Test
    fun `labels of a short sprite in a tall cell sit at its visible height`() {
        val profile = SpriteProfile(cellWidth = 32, cellHeight = 48, uiHeights = mapOf(10 to 30))
        val tall = SpritePlacement.place(64f, 64f, 10, 32, profile, spriteId = 1)
        val short = SpritePlacement.place(64f, 64f, 10, 32, profile, spriteId = 10)
        assertEquals(48f, short.drawHeight) // the cell is still drawn whole
        assertEquals(tall.uiTopY - 18f, short.uiTopY)
        assertEquals(30f, profile.uiHeightOf(10))
        assertEquals(48f, profile.uiHeightOf(1))
    }

    @Test
    fun `a projectile leaves the middle of a tall shooter's body, not the middle of the tile`() {
        val profile = SpriteProfile(cellWidth = 32, cellHeight = 48, uiHeights = mapOf(3 to 42, 101 to 28))
        // Feet on the tile bottom; a 42 px tall body is centred 21 px up, the tile's middle is 16.
        assertEquals(5f, MorganaWorldRenderer.projectileLift(profile, 3, 32f))
        assertEquals(0f, MorganaWorldRenderer.projectileLift(profile, 101, 32f), "a short monster's body is already at tile height")
        assertEquals(8f, MorganaWorldRenderer.projectileLift(profile, 50, 32f), "a sprite with no entry uses the whole cell")
        assertEquals(0f, MorganaWorldRenderer.projectileLift(null, 3, 32f), "classic 32 px sprites are unchanged")
        assertEquals(0f, MorganaWorldRenderer.projectileLift(profile, null, 32f))
    }
}

