package com.morgana.client.render

import com.morgana.client.state.XpOrb
import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MorganaWorldRendererTest {

    @Test
    fun `projectileRotationDegrees maps all 8 directions from right-facing base`() {
        assertEquals(0f, MorganaWorldRenderer.projectileRotationDegrees(Direction.RIGHT))
        assertEquals(45f, MorganaWorldRenderer.projectileRotationDegrees(Direction.UP_RIGHT))
        assertEquals(90f, MorganaWorldRenderer.projectileRotationDegrees(Direction.UP))
        assertEquals(135f, MorganaWorldRenderer.projectileRotationDegrees(Direction.UP_LEFT))
        assertEquals(180f, MorganaWorldRenderer.projectileRotationDegrees(Direction.LEFT))
        assertEquals(225f, MorganaWorldRenderer.projectileRotationDegrees(Direction.DOWN_LEFT))
        assertEquals(270f, MorganaWorldRenderer.projectileRotationDegrees(Direction.DOWN))
        assertEquals(315f, MorganaWorldRenderer.projectileRotationDegrees(Direction.DOWN_RIGHT))
    }

    @Test
    fun `xp orb start and target render Y align with SpritePlacement centers`() {
        val mapHeight = 100
        val ts = 32
        val mapRenderHeight = mapHeight * ts.toFloat()

        // Classic 32x32 sprites
        val npcTileY = 20
        val playerTileY = 25
        val npcBox = SpritePlacement.place(10f * ts, npcTileY * ts.toFloat(), mapHeight, ts, null)
        val playerBox = SpritePlacement.place(14f * ts, playerTileY * ts.toFloat(), mapHeight, ts, null)

        val orbStartTopDownY = npcTileY * ts + ts - ts * 0.5f
        val orbTargetTopDownY = playerTileY * ts + ts - ts * 0.55f
        val orb = XpOrb(mapId = 1, startX = 10f * ts + 16f, startY = orbStartTopDownY, arcSign = 1f, arcHeight = 0f, durationSeconds = 0.5f)

        val (_, startY) = orb.positionAt(0f, 14f * ts + 16f, orbTargetTopDownY)
        val (_, endY) = orb.positionAt(1f, 14f * ts + 16f, orbTargetTopDownY)

        assertEquals(npcBox.drawY + npcBox.drawHeight * 0.5f, mapRenderHeight - startY, 0.01f)
        assertEquals(playerBox.drawY + playerBox.drawHeight * 0.55f, mapRenderHeight - endY, 0.01f)

        // Taller 32x64 sprites
        val tallProfile = SpriteProfile(cellWidth = 32, cellHeight = 64)
        val tallNpcBox = SpritePlacement.place(10f * ts, npcTileY * ts.toFloat(), mapHeight, ts, tallProfile)
        val tallPlayerBox = SpritePlacement.place(14f * ts, playerTileY * ts.toFloat(), mapHeight, ts, tallProfile)

        val tallStartTopDownY = npcTileY * ts + ts - 64f * 0.5f
        val tallTargetTopDownY = playerTileY * ts + ts - 64f * 0.55f
        val tallOrb = XpOrb(mapId = 1, startX = 10f * ts + 16f, startY = tallStartTopDownY, arcSign = 1f, arcHeight = 0f, durationSeconds = 0.5f)

        val (_, tallStartY) = tallOrb.positionAt(0f, 14f * ts + 16f, tallTargetTopDownY)
        val (_, tallEndY) = tallOrb.positionAt(1f, 14f * ts + 16f, tallTargetTopDownY)

        assertEquals(tallNpcBox.drawY + tallNpcBox.drawHeight * 0.5f, mapRenderHeight - tallStartY, 0.01f)
        assertEquals(tallPlayerBox.drawY + tallPlayerBox.drawHeight * 0.55f, mapRenderHeight - tallEndY, 0.01f)
    }

    @Test
    fun `highlighted ground item detection and label formatting`() {
        val normal = ItemDefinition(id = 1, name = "Silver")
        val meteor = ItemDefinition(id = 7, name = "Meteor", highlightOnGround = true, dropHighlightColorHex = "#FBBF24")
        val dragonBall = ItemDefinition(id = 8, name = "Dragon Ball", broadcastOnDrop = true)

        assertFalse(MorganaWorldRenderer.isHighlightedGroundItem(null))
        assertFalse(MorganaWorldRenderer.isHighlightedGroundItem(normal))
        assertTrue(MorganaWorldRenderer.isHighlightedGroundItem(meteor))
        assertTrue(MorganaWorldRenderer.isHighlightedGroundItem(dragonBall))

        assertEquals("Meteor", MorganaWorldRenderer.groundItemLabel(meteor, 1))
        assertEquals("Dragon Ball x2", MorganaWorldRenderer.groundItemLabel(dragonBall, 2))
    }

    @Test
    fun `currency piles scale across 1-99, 100-999, 1000-9999, and 10000+ tiers`() {
        val small = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(1)
        val smallTop = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(99)
        val medium = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(100)
        val mediumTop = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(999)
        val large = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(1000)
        val largeTop = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(9999)
        val huge = MorganaWorldRenderer.Companion.CurrencyPileTier.forAmount(10_000)

        assertEquals(MorganaWorldRenderer.Companion.CurrencyPileTier.SMALL, small)
        assertEquals(small, smallTop)
        assertEquals(MorganaWorldRenderer.Companion.CurrencyPileTier.MEDIUM, medium)
        assertEquals(medium, mediumTop)
        assertEquals(MorganaWorldRenderer.Companion.CurrencyPileTier.LARGE, large)
        assertEquals(large, largeTop)
        assertEquals(MorganaWorldRenderer.Companion.CurrencyPileTier.HUGE, huge)

        assertTrue(small.stackCopies < medium.stackCopies)
        assertTrue(medium.stackCopies < large.stackCopies)
        assertTrue(small.iconSize < medium.iconSize && medium.iconSize < large.iconSize && large.iconSize < huge.iconSize)
    }
}
