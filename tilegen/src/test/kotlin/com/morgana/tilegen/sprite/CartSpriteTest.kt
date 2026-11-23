package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CartSpriteTest {
    private fun bounds(img: TileImage): IntArray {
        var minX = img.width; var minY = img.height; var maxX = -1; var maxY = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getPixel(x, y) and 0xFF) != 0) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `the cart trails behind the horse in profile and the east view mirrors the west view`() {
        val w = CartSprite.hitched(null, null, Direction8.W, PaperDollAction.IDLE, 0)
        val e = CartSprite.hitched(null, null, Direction8.E, PaperDollAction.IDLE, 0)
        assertEquals(CartSprite.WIDTH, w.width)
        val b = bounds(w)
        assertTrue(b[2] - b[0] >= 70, "horse and cart should span most of the canvas, spanned ${b[2] - b[0] + 1}")
        assertTrue(b[3] == MountSprite.GROUND_Y + 0 || b[3] >= MountSprite.GROUND_Y - 1, "wheels and hooves stand on the ground")
        for (y in 0 until w.height) for (x in 0 until w.width) assertEquals(w.getPixel(x, y), e.getPixel(w.width - 1 - x, y))
    }

    @Test
    fun `the wheels turn through the walk and every view is deterministic`() {
        for (dir in listOf(Direction8.S, Direction8.W, Direction8.N)) {
            val a = CartSprite.hitched(null, null, dir, PaperDollAction.WALK, 2)
            val b = CartSprite.hitched(null, null, dir, PaperDollAction.WALK, 2)
            assertTrue(a.pixels.contentEquals(b.pixels), "not deterministic for $dir")
        }
        val frames = (0..3).map { CartSprite.hitched(null, null, Direction8.W, PaperDollAction.WALK, it) }
        for (i in 0..3) for (j in i + 1..3) assertTrue(!frames[i].pixels.contentEquals(frames[j].pixels), "walk frames $i and $j match")
    }

    @Test
    fun `a rider can drive the cart`() {
        val gen = PaperDollSpriteGenerator(seed = 5L, tileSize = 32, tileHeight = 48)
        val loadout = PaperDollLoadout.forJob(PaperDollJob.HUNTER).let { it.copy(theme = it.effectiveTheme().copy(proportions = PaperDollProportions.RMXP_32X48)) }
        val bare = bounds(CartSprite.hitched(null, null, Direction8.W, PaperDollAction.IDLE, 0))
        val ridden = bounds(CartSprite.hitched(gen, loadout, Direction8.W, PaperDollAction.IDLE, 0))
        assertTrue(ridden[1] < bare[1] - 8, "the rider should rise above the bare horse and cart")
    }
}
