package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MountSpriteTest {
    private val gen = PaperDollSpriteGenerator(seed = 5L, tileSize = 32, tileHeight = 48)
    private val loadout = PaperDollLoadout.forJob(PaperDollJob.HUNTER).let {
        it.copy(theme = it.effectiveTheme().copy(proportions = PaperDollProportions.RMXP_32X48))
    }

    private fun bounds(img: TileImage): IntArray {
        var minX = img.width; var minY = img.height; var maxX = -1; var maxY = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getPixel(x, y) and 0xFF) != 0) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `a horse is longer than it is tall in profile and narrower from the front`() {
        val side = bounds(MountSprite.horse(Direction8.W, PaperDollAction.IDLE, 0))
        val front = bounds(MountSprite.horse(Direction8.S, PaperDollAction.IDLE, 0))
        assertTrue(side[2] - side[0] > 30, "a profile horse should be long, was ${side[2] - side[0] + 1}")
        assertTrue(front[2] - front[0] < side[2] - side[0], "a horse seen head on is narrower than in profile")
        assertEquals(MountSprite.GROUND_Y, side[3], "the hooves stand on the ground line")
    }

    @Test
    fun `east is the mirror of west`() {
        val w = MountSprite.horse(Direction8.W, PaperDollAction.WALK, 1)
        val e = MountSprite.horse(Direction8.E, PaperDollAction.WALK, 1)
        for (y in 0 until w.height) for (x in 0 until w.width) assertEquals(w.getPixel(x, y), e.getPixel(w.width - 1 - x, y))
    }

    @Test
    fun `the four walk frames are all different and the idle breathes`() {
        for (dir in listOf(Direction8.S, Direction8.W, Direction8.N)) {
            val walk = (0..3).map { MountSprite.horse(dir, PaperDollAction.WALK, it) }
            for (i in 0..3) for (j in i + 1..3) assertTrue(!walk[i].pixels.contentEquals(walk[j].pixels), "walk frames $i and $j match for $dir")
            val idle = (0..1).map { MountSprite.horse(dir, PaperDollAction.IDLE, it) }
            assertTrue(!idle[0].pixels.contentEquals(idle[1].pixels), "idle frames match for $dir")
        }
    }

    @Test
    fun `a rider sits above the horse and the result is deterministic`() {
        for (dir in listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)) {
            val horseTop = bounds(MountSprite.horse(dir, PaperDollAction.IDLE, 0))[1]
            val a = MountSprite.mounted(gen, loadout, dir, PaperDollAction.IDLE, 0)
            val b = MountSprite.mounted(gen, loadout, dir, PaperDollAction.IDLE, 0)
            assertTrue(a.pixels.contentEquals(b.pixels), "mounted frame is not deterministic for $dir")
            assertTrue(bounds(a)[1] < horseTop - 12, "the rider's head should be well above the horse for $dir")
        }
    }
}
