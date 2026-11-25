package com.morgana.tilegen.structure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastleKitTest {
    @Test
    fun `each castle-kit prop is deterministic, fills its footprint and has real content`() {
        for (kind in CastleKit.kinds) {
            val a = PropGenerator(seed = 5).generateProp("p", kind)
            val b = PropGenerator(seed = 5).generateProp("p", kind)
            assertEquals(kind.widthTiles * kind.heightTiles, a.slicedTiles.size)
            var opaque = 0
            for (y in 0 until a.fullImage.height) for (x in 0 until a.fullImage.width) {
                assertEquals(a.fullImage.getPixel(x, y), b.fullImage.getPixel(x, y))
                if ((a.fullImage.getPixel(x, y) ushr 24) > 0) opaque++
            }
            assertTrue(opaque > a.fullImage.width * a.fullImage.height / 8, "$kind has real content")
        }
    }
}
