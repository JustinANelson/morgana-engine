package com.morgana.tilegen.structure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CitadelPropsTest {
    @Test
    fun `each castle-church prop fills its footprint, is deterministic and is tall`() {
        for (kind in CitadelProps.kinds) {
            val a = PropGenerator(seed = 5).generateProp("p", kind)
            val b = PropGenerator(seed = 5).generateProp("p", kind)
            assertEquals(kind.widthTiles * kind.heightTiles, a.slicedTiles.size)
            assertEquals(a.fullImage.width, b.fullImage.width)
            var opaque = 0
            for (y in 0 until a.fullImage.height) for (x in 0 until a.fullImage.width) {
                assertEquals(a.fullImage.getPixel(x, y), b.fullImage.getPixel(x, y))
                if ((a.fullImage.getPixel(x, y) ushr 24) > 0) opaque++
            }
            assertTrue(opaque > a.fullImage.width * a.fullImage.height / 5, "$kind has real content")
        }
        assertTrue(PropKind.CATHEDRAL.heightTiles >= 9 && PropKind.BELL_TOWER.heightTiles >= 6)
    }
}
