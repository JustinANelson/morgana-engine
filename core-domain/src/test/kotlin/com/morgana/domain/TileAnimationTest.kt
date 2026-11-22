package com.morgana.domain

import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAnimation
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Animated map tiles: which frame shows when, where the frames are on the sheet, and what validation rejects. */
class TileAnimationTest {

    private val torch = TilePlacement(tilesetId = 2, srcTileX = 4, srcTileY = 3, animationFrames = 3, animationSpeedMs = 200)

    @Test
    fun `a still tile always shows itself`() {
        val still = TilePlacement(srcTileX = 5, srcTileY = 1)
        assertFalse(TileAnimation.isAnimated(still))
        for (t in listOf(0L, 199L, 5_000L)) assertEquals(5 to 1, TileAnimation.sourceAt(still, t))
    }

    @Test
    fun `frames advance every speed milliseconds and loop`() {
        assertEquals(listOf(0, 0, 1, 2, 0, 1), listOf(0L, 199L, 200L, 400L, 600L, 800L).map { TileAnimation.frameAt(torch, it) })
        assertEquals(4 to 3, TileAnimation.sourceAt(torch, 0))
        assertEquals(5 to 3, TileAnimation.sourceAt(torch, 200))
        assertEquals(6 to 3, TileAnimation.sourceAt(torch, 400))
        assertEquals(6, TileAnimation.lastFrameColumn(torch))
    }

    @Test
    fun `a multi-tile object steps by its width`() {
        // The right half of a 2-wide fountain whose three frames sit side by side: columns 1, 3 and 5.
        val right = TilePlacement(srcTileX = 1, srcTileY = 0, animationFrames = 3, animationSpeedMs = 100, animationFrameStep = 2)
        assertEquals(listOf(1, 3, 5, 1), listOf(0L, 100L, 200L, 300L).map { TileAnimation.sourceAt(right, it).first })
    }

    @Test
    fun `the frame is the same for the same time, including before the clock started`() {
        assertEquals(TileAnimation.frameAt(torch, 12_345L), TileAnimation.frameAt(torch, 12_345L))
        assertEquals(2, TileAnimation.frameAt(torch, -1L), "time just before zero is the last frame, not a negative one")
    }

    @Test
    fun `the old built-in water tile is left to its own frames`() {
        val water = TilePlacement(srcTileX = 3, autotileMode = AutotileMode.ANIMATED_3_FRAME, animationFrames = 3)
        assertFalse(TileAnimation.isAnimated(water))
        assertTrue(TileAnimation.isAnimated(torch))
    }

    @Test
    fun `validation rejects impossible animation settings and accepts good ones`() {
        fun report(p: TilePlacement) = ContentValidator.validate(
            ContentCatalog(maps = mapOf(1 to MapDefinition(id = 1, name = "Anim", width = 5, height = 5,
                cells = List(25) { if (it == 7) MapCell(layers = mapOf(MapLayer.MASK_2 to p)) else MapCell() }))),
            MorganaClassic.definition)
        fun codes(p: TilePlacement) = report(p).errors.filter { it.code == "invalid_tile_animation" }.map { it.pointer }
        assertEquals(emptyList<String>(), codes(torch))
        assertEquals(listOf("/cells/7/layers/MASK_2/animationFrames"), codes(torch.copy(animationFrames = 0)))
        assertEquals(listOf("/cells/7/layers/MASK_2/animationFrames"), codes(torch.copy(animationFrames = TileAnimation.MAX_FRAMES + 1)))
        assertEquals(listOf("/cells/7/layers/MASK_2/animationSpeedMs"), codes(torch.copy(animationSpeedMs = 5)))
        assertEquals(emptyList<String>(), codes(TilePlacement(animationSpeedMs = 0)), "speed does not matter for a still tile")
        assertEquals(listOf("/cells/7/layers/MASK_2/animationFrameStep"), codes(torch.copy(animationFrameStep = 0)))
    }
}
