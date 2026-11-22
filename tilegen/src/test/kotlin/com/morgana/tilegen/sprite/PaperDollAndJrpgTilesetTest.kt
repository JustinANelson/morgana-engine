package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.spec.CliffLayerSpec
import com.morgana.tilegen.spec.MaterialTileSpec
import com.morgana.tilegen.spec.PropLayerSpec
import com.morgana.tilegen.spec.SpecPalettes
import com.morgana.tilegen.spec.TilesetSpec
import com.morgana.tilegen.spec.TilesetSpecJson
import com.morgana.tilegen.spec.TilesetSpecRenderer
import com.morgana.tilegen.structure.PropGenerator
import com.morgana.tilegen.structure.PropKind
import com.morgana.tilegen.structure.StructureGenerator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class PaperDollAndJrpgTilesetTest {

    @Test
    fun `selective outline tints top-left lit edges brighter than bottom-right shadow edges`() {
        val img = TileImage(16, 16)
        val fill = PaletteRamp.rgba(220, 120, 90)
        for (y in 5..10) {
            for (x in 5..10) {
                img.setPixel(x, y, fill)
            }
        }
        val outlined = PixelDither.applySelectiveOutline(img)
        val topLitBorder = outlined.getPixel(7, 4)
        val botShadowBorder = outlined.getPixel(7, 11)
        assertNotEquals(0, topLitBorder and 0xFF)
        assertNotEquals(0, botShadowBorder and 0xFF)

        val litLuma = ((topLitBorder ushr 24) and 0xFF) + ((topLitBorder ushr 16) and 0xFF) + ((topLitBorder ushr 8) and 0xFF)
        val shaLuma = ((botShadowBorder ushr 24) and 0xFF) + ((botShadowBorder ushr 16) and 0xFF) + ((botShadowBorder ushr 8) and 0xFF)
        assertTrue(litLuma > shaLuma, "Expected top-left selective outline ($litLuma) to be brighter than bottom-right outline ($shaLuma)")
    }

    @Test
    fun `suikoden and ragnarok palettes and cluster material patterns render deterministically`() {
        val expectedPalettes = listOf(
            "sk_highland_grass", "sk_kyaro_timber", "sk_muse_limestone", "sk_scarlet_roof", "sk_true_rune_gold",
            "ro_prontera_marble", "ro_payon_jade", "ro_geffen_arcane", "ro_morroc_sandstone", "ro_poring_pastel"
        )
        for (name in expectedPalettes) {
            val ramp = SpecPalettes.presets[name]
            assertTrue(ramp != null && ramp.colors.size == 6, "Expected 6-stop preset for $name")
        }

        val gen1 = MaterialTileGenerator(seed = 42L, tileSize = 32)
        val gen2 = MaterialTileGenerator(seed = 42L, tileSize = 32)
        val patterns = listOf(
            MaterialPattern.LEAF_CANOPY_CLUSTER to PalettePresets.SK_HIGHLAND_GRASS,
            MaterialPattern.HALF_TIMBER_PLASTER to PalettePresets.SK_MUSE_LIMESTONE,
            MaterialPattern.SHINGLE_THATCH_ROOF to PalettePresets.SK_SCARLET_ROOF,
            MaterialPattern.PRONTERA_COURTYARD to PalettePresets.RO_PRONTERA_MARBLE
        )
        for ((pattern, palette) in patterns) {
            val spec = MaterialSpec(pattern.name.lowercase(), pattern, palette, style = PixelStyle.SUIKODEN_32BIT)
            val t1 = gen1.generateTile(spec)
            val t2 = gen2.generateTile(spec)
            assertArrayEquals(t1.pixels, t2.pixels)
            assertTrue(t1.pixels.distinct().size >= 4, "Expected rich cluster shading in $pattern")
        }
    }

    @Test
    fun `town facade structure and jrpg props render and round-trip via TilesetSpec`() {
        val matGen = MaterialTileGenerator(seed = 1337L, tileSize = 32)
        val roofMat = matGen.generateTile(MaterialSpec("roof", MaterialPattern.SHINGLE_THATCH_ROOF, PalettePresets.SK_SCARLET_ROOF))
        val wallMat = matGen.generateTile(MaterialSpec("wall", MaterialPattern.HALF_TIMBER_PLASTER, PalettePresets.SK_MUSE_LIMESTONE))

        val facadeTiles = StructureGenerator(tileSize = 32).generateTownFacadeSet(
            name = "kyaro_inn",
            roofMaterial = roofMat,
            wallMaterial = wallMat,
            style = PixelStyle.SUIKODEN_32BIT
        )
        assertEquals(9, facadeTiles.size)
        assertEquals("kyaro_inn_roof_left", facadeTiles.first().name)
        assertEquals("kyaro_inn_base_right", facadeTiles.last().name)

        val propGen = PropGenerator(seed = 1337L, tileSize = 32)
        val jrpgProps = listOf(
            PropKind.HALF_TIMBER_HOUSE,
            PropKind.MARKET_STALL,
            PropKind.RUNE_CRYSTAL_FOUNTAIN,
            PropKind.CHERRY_BLOSSOM_TREE,
            PropKind.KAFRA_WAYPOINT_PILLAR,
            PropKind.GUILD_EMBLEM_FLAG
        )
        for (kind in jrpgProps) {
            val prop = propGen.generateProp(kind.name.lowercase(), kind, style = PixelStyle.RAGNAROK_25D)
            assertEquals(kind.widthTiles * 32, prop.fullImage.width)
            assertEquals(kind.heightTiles * 32, prop.fullImage.height)
            assertEquals(kind.widthTiles * kind.heightTiles, prop.slicedTiles.size)
            assertTrue(prop.fullImage.pixels.any { (it and 0xFF) != 0 })
        }

        val spec = TilesetSpec(
            name = "prontera_kyaro_town",
            tileSize = 32,
            style = PixelStyle.SUIKODEN_32BIT,
            materials = listOf(
                MaterialTileSpec("roof", MaterialPattern.SHINGLE_THATCH_ROOF, palette = "sk_scarlet_roof"),
                MaterialTileSpec("wall", MaterialPattern.HALF_TIMBER_PLASTER, palette = "sk_muse_limestone")
            ),
            cliffs = listOf(
                CliffLayerSpec("inn_facade", topMaterial = "roof", faceMaterial = "wall", townFacade = true)
            ),
            props = listOf(
                PropLayerSpec("town_fountain", PropKind.RUNE_CRYSTAL_FOUNTAIN, primaryPalette = "ro_prontera_marble")
            )
        )
        val json = TilesetSpecJson.toJson(spec)
        val parsed = TilesetSpecJson.parse(json)
        assertTrue(parsed.cliffs.single().townFacade)
        val rendered = TilesetSpecRenderer.render(parsed)
        assertEquals(2 + 9 + 6, rendered.tiles.size)
    }

    @Test
    fun `paper-doll generator supports 8 directions, modular equipment swaps, and multi-clip animations`() {
        val gen = PaperDollSpriteGenerator(seed = 1337L, tileSize = 32)
        val (sheet, allTiles) = gen.generateAllJobsSheet(include8Directions = true, frameCount = 2)
        assertEquals(PaperDollJob.entries.size * Direction8.entries.size * 2, allTiles.size)
        assertEquals(10 * 32, sheet.width)

        val baseLoadout = PaperDollLoadout.forJob(PaperDollJob.RUNE_HERO)
        val south = gen.generateCharacterFrame(baseLoadout, Direction8.S)
        val southWest = gen.generateCharacterFrame(baseLoadout, Direction8.SW)
        val west = gen.generateCharacterFrame(baseLoadout, Direction8.W)
        val north = gen.generateCharacterFrame(baseLoadout, Direction8.N)

        assertFalse(south.pixels.contentEquals(southWest.pixels), "3/4 diagonal SW view must differ from S view")
        assertFalse(southWest.pixels.contentEquals(west.pixels), "3/4 diagonal SW view must differ from side W view")
        assertFalse(south.pixels.contentEquals(north.pixels), "Front S view must differ from back N view")

        // Swapping headgear, weapon, or shield changes the paper-doll composite
        val withWizardHat = gen.generateCharacterFrame(baseLoadout.copy(headgear = HeadgearKind.WIZARD_HAT), Direction8.S)
        val withShield = gen.generateCharacterFrame(baseLoadout.copy(shield = ShieldKind.CRUSADER_CROSS_SHIELD), Direction8.S)
        assertFalse(south.pixels.contentEquals(withWizardHat.pixels))
        assertFalse(south.pixels.contentEquals(withShield.pixels))

        // Animation clips produce distinct poses
        val walkF1 = gen.generateCharacterFrame(baseLoadout, Direction8.S, frameIndex = 1, clip = SpriteAnimationClip.WALK)
        val attackF1 = gen.generateCharacterFrame(baseLoadout, Direction8.S, frameIndex = 1, clip = SpriteAnimationClip.ATTACK)
        val castF1 = gen.generateCharacterFrame(baseLoadout, Direction8.S, frameIndex = 1, clip = SpriteAnimationClip.CAST)
        assertFalse(walkF1.pixels.contentEquals(attackF1.pixels))
        assertFalse(walkF1.pixels.contentEquals(castF1.pixels))

        // Monsters sheet
        val (monsterSheet, monsterTiles) = gen.generateAllMonstersSheet(frameCount = 2)
        assertEquals(JrpgMonsterKind.entries.size * 2, monsterTiles.size)
        assertEquals(8 * 32, monsterSheet.width)
    }

    @Test
    fun `cli paperdoll command exports jobs and monsters sheets`(@TempDir tempDir: Path) {
        val outBuf = ByteArrayOutputStream()
        val code = TilesetCli.run(
            arrayOf("paperdoll", "all", "--size", "32", "--frames", "1", "--out", tempDir.toString()),
            PrintStream(outBuf)
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(tempDir.resolve("jobs/paperdoll_jobs_all.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("monsters/paperdoll_monsters_all.png")))
        assertFalse(outBuf.toString().isBlank())
    }
}
