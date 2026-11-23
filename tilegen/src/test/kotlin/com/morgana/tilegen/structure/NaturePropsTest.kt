package com.morgana.tilegen.structure

import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PalettePresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NaturePropsTest {
    private val kinds = listOf(
        PropKind.LEAFY_TREE, PropKind.FRUIT_TREE, PropKind.FLOWER_BUSH,
        PropKind.MOSS_MOUND, PropKind.BOULDER, PropKind.RUIN_WALL
    )

    private fun opaque(p: com.morgana.tilegen.TileImage): Int = p.pixels.count { (it and 0xFF) > 200 }

    @Test
    fun `nature props are deterministic and fill a good part of their footprint`() {
        for (size in listOf(16, 32)) {
            val gen = PropGenerator(seed = 3L, tileSize = size)
            for (kind in kinds) {
                val a = gen.generateProp("n_${kind.name}", kind)
                val b = gen.generateProp("n_${kind.name}", kind)
                assertTrue(a.fullImage.pixels.contentEquals(b.fullImage.pixels), "$kind not deterministic")
                val area = a.fullImage.width * a.fullImage.height
                assertTrue(opaque(a.fullImage) > area / 8, "$kind too sparse at $size px: ${opaque(a.fullImage)} of $area")
            }
        }
    }

    @Test
    fun `tree crown is outlined in the darkest foliage colour and uses the whole ramp`() {
        val leaf = PalettePresets.MEADOW_GRASS
        val img = PropGenerator(5L, 32).generateProp("t", PropKind.LEAFY_TREE, primaryPalette = leaf).fullImage
        val used = img.pixels.filter { (it and 0xFF) == 255 }.toSet()
        assertTrue(leaf.shadow in used, "outline colour missing")
        assertTrue(leaf.colors.count { it in used } >= 4, "ramp barely used")
    }

    @Test
    fun `fruit and bloom colours come from the secondary palette`() {
        val red = PaletteRamp.fromBaseColor(PaletteRamp.rgba(210, 40, 40))
        val plain = PropGenerator(5L, 32).generateProp("t", PropKind.LEAFY_TREE).fullImage
        val apple = PropGenerator(5L, 32).generateProp("t", PropKind.FRUIT_TREE, secondaryPalette = red).fullImage
        assertNotEquals(plain.pixels.toList(), apple.pixels.toList())
        assertTrue(apple.pixels.any { it == red.colors[4] || it == red.colors[2] }, "no fruit pixels")
        assertEquals(96, apple.width)
        assertEquals(96, apple.height)
    }
}
