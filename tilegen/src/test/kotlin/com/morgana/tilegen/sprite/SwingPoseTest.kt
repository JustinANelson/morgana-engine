package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PixelStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwingPoseTest {
    private val gen = PaperDollSpriteGenerator(seed = 11L, tileSize = 32, tileHeight = 48)

    private fun loadout(weapon: WeaponKind) = PaperDollLoadout.forJob(PaperDollJob.KNIGHT).copy(weapon = weapon)

    private fun weaponLayer(weapon: WeaponKind, dir: Direction8, action: PaperDollAction, frame: Int): TileImage =
        gen.generateLayeredFrame(loadout(weapon), dir, action, frame, PixelStyle.MORGANA).first.getValue(PaperDollSlot.MAIN_HAND)

    private fun bounds(img: TileImage): IntArray {
        var minX = img.width; var minY = img.height; var maxX = -1; var maxY = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getPixel(x, y) and 0xFF) != 0) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `a resting sword is long enough to read as a sword`() {
        val b = bounds(weaponLayer(WeaponKind.KNIGHT_SWORD, Direction8.S, PaperDollAction.IDLE, 0))
        assertTrue(b[3] - b[1] >= 14, "sword should be at least 14 px tall at rest, was ${b[3] - b[1] + 1}")
    }

    @Test
    fun `the four slash frames are all different and the blade travels across the body`() {
        for (dir in listOf(Direction8.S, Direction8.W, Direction8.N)) {
            val frames = (0..3).map { weaponLayer(WeaponKind.KNIGHT_SWORD, dir, PaperDollAction.SLASH, it) }
            for (i in 0..3) for (j in i + 1..3) {
                assertTrue(!frames[i].pixels.contentEquals(frames[j].pixels), "frames $i and $j match for $dir")
            }
            val spans = frames.map { bounds(it) }
            assertTrue(spans.maxOf { it[3] } - spans.minOf { it[1] } >= 20, "the blade should sweep a tall arc for $dir")
        }
    }

    @Test
    fun `a bow animates through raise, draw, release and lower with an arrow in flight`() {
        val frames = (0..3).map { weaponLayer(WeaponKind.COMPOSITE_BOW, Direction8.W, PaperDollAction.SLASH, it) }
        for (i in 0..3) for (j in i + 1..3) assertTrue(!frames[i].pixels.contentEquals(frames[j].pixels), "bow frames $i and $j match")
        val drawn = bounds(frames[1])
        val released = bounds(frames[2])
        assertTrue(drawn[2] - drawn[0] >= 10, "the drawn bow should show the long arrow")
        assertTrue(released[2] - released[0] >= 8, "the released arrow should be in flight")
    }

    @Test
    fun `swing frames are deterministic`() {
        val a = weaponLayer(WeaponKind.KATANA, Direction8.S, PaperDollAction.SLASH, 2)
        val b = weaponLayer(WeaponKind.KATANA, Direction8.S, PaperDollAction.SLASH, 2)
        assertEquals(a.pixels.toList(), b.pixels.toList())
    }
}
