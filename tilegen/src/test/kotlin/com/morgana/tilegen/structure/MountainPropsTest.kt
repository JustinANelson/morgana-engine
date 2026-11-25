package com.morgana.tilegen.structure

import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PixelStyle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The barrier and graveyard props, and the town buildings reachable as props. */
class MountainPropsTest {
    private val gen = PropGenerator(seed = 11L, tileSize = 32)

    private fun opaque(img: com.morgana.tilegen.TileImage) = img.pixels.count { (it and 0xFF) > 0 }

    @Test
    fun `mountain peaks and spires are deterministic, fill their footprint, and the snow cap changes the picture`() {
        for (kind in listOf(PropKind.MOUNTAIN_PEAK, PropKind.ROCK_SPIRE)) {
            val a = gen.generateProp("m_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG, primaryPalette = PalettePresets.SLATE_STONE)
            val b = gen.generateProp("m_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG, primaryPalette = PalettePresets.SLATE_STONE)
            assertArrayEquals(a.fullImage.pixels, b.fullImage.pixels, "$kind is deterministic")
            assertTrue(opaque(a.fullImage) > a.fullImage.width * a.fullImage.height / 8, "$kind fills a good share of its area")
            val snowy = gen.generateProp("m_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG,
                primaryPalette = PalettePresets.SLATE_STONE, secondaryPalette = PalettePresets.FROST_SNOW)
            assertFalse(a.fullImage.pixels.contentEquals(snowy.fullImage.pixels), "$kind with a snow palette looks different")
            val bright = snowy.fullImage.pixels.count { px ->
                (px and 0xFF) > 0 && ((px ushr 24) and 0xFF) > 220 && ((px ushr 16) and 0xFF) > 220 && ((px ushr 8) and 0xFF) > 220
            }
            assertTrue(bright > 20, "$kind has snow-white pixels when given a snow palette ($bright)")
        }
    }

    @Test
    fun `the sealed gate draws a glowing seal and tombstones vary by seed`() {
        val gate = gen.generateProp("gate", PropKind.SEALED_GATE, style = PixelStyle.CLASSIC_RPG,
            primaryPalette = PalettePresets.SLATE_STONE, secondaryPalette = PalettePresets.ARCANE_VIOLET)
        assertEquals(4 * 32, gate.fullImage.width)
        assertEquals(3 * 32, gate.fullImage.height)
        val glow = PalettePresets.ARCANE_VIOLET.colors.last()
        assertTrue(gate.fullImage.pixels.any { it == glow }, "the seal's bright core is drawn")

        val seen = (1..24).map { n ->
            gen.generateProp("grave_$n", PropKind.TOMBSTONE, style = PixelStyle.CLASSIC_RPG).fullImage.pixels.contentHashCode()
        }.toSet()
        assertTrue(seen.size > 3, "tombstones are not all alike (${seen.size} distinct)")
    }

    @Test
    fun `building kinds work as props at both tile sizes and the roof choice shows`() {
        for (tileSize in listOf(16, 32)) {
            val g = PropGenerator(seed = 5L, tileSize = tileSize)
            for (kind in listOf(PropKind.COTTAGE_LARGE, PropKind.INN, PropKind.SMITHY, PropKind.SHOP, PropKind.CHAPEL, PropKind.GUARDHOUSE, PropKind.GREAT_HALL)) {
                val p = g.generateProp("b_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG)
                assertEquals(kind.widthTiles * tileSize, p.fullImage.width)
                assertEquals(kind.heightTiles * tileSize, p.fullImage.height)
                assertEquals(kind.widthTiles * kind.heightTiles, p.slicedTiles.size)
                assertTrue(opaque(p.fullImage) > 100, "$kind at $tileSize px draws a building")
            }
        }
        val red = gen.generateProp("inn", PropKind.INN, style = PixelStyle.CLASSIC_RPG, buildingRoof = BuildingRoof.RED)
        val green = gen.generateProp("inn", PropKind.INN, style = PixelStyle.CLASSIC_RPG, buildingRoof = BuildingRoof.GREEN)
        assertFalse(red.fullImage.pixels.contentEquals(green.fullImage.pixels), "the roof colour changes the building")
    }

    @Test
    fun `smooth props and houses are deterministic and draw at both tile sizes`() {
        val smooth = listOf(
            PropKind.BARREL, PropKind.CRATE, PropKind.CHEST_CLOSED, PropKind.URN, PropKind.MEDIEVAL_TENT, PropKind.TRADE_WAGON,
            PropKind.WOOD_FENCE, PropKind.WOOD_BRIDGE, PropKind.STONE_ARCHWAY, PropKind.SARCOPHAGUS, PropKind.SKULL_PILE,
            PropKind.WEAPON_RACK, PropKind.SIEGE_BALLISTA, PropKind.TORCH_SCONCE, PropKind.PILLAR, PropKind.WAYPOINT_PILLAR,
            PropKind.SHRINE, PropKind.PORTAL, PropKind.DARK_RIFT_ALTAR,
            PropKind.COTTAGE_LARGE, PropKind.INN, PropKind.SMITHY, PropKind.SHOP, PropKind.CHAPEL, PropKind.GUARDHOUSE, PropKind.GREAT_HALL
        )
        for (tileSize in listOf(16, 32)) {
            val g = PropGenerator(seed = 3L, tileSize = tileSize)
            for (kind in smooth) {
                val a = g.generateProp("s_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG)
                val b = g.generateProp("s_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG)
                assertArrayEquals(a.fullImage.pixels, b.fullImage.pixels, "$kind is deterministic at $tileSize px")
                assertTrue(opaque(a.fullImage) > 30, "$kind draws something at $tileSize px")
            }
        }
    }
}
