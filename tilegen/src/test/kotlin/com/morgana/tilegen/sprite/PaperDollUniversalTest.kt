package com.morgana.tilegen.sprite

import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.export.PaperDollExporter
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.spec.PaperDollSpecJson
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

class PaperDollUniversalTest {

    @Test
    fun `layered frame separates shadow from body and equipment with direction-aware z-order`() {
        val gen = PaperDollSpriteGenerator(seed = 1337L, tileSize = 32)
        val loadout = PaperDollLoadout(
            job = PaperDollJob.LORD_KNIGHT,
            attachments = listOf(AttachmentKind.KNIGHT_PAULDRONS, AttachmentKind.ANGEL_WINGS, AttachmentKind.ROYAL_CROWN)
        )

        val (layers, sockets) = gen.generateLayeredFrame(loadout, Direction8.S, PaperDollAction.WALK, frameIndex = 1)
        assertTrue(PaperDollSlot.SHADOW in layers)
        assertTrue(PaperDollSlot.BODY in layers)
        assertTrue(PaperDollSlot.EYES in layers)
        assertTrue(PaperDollSlot.TORSO in layers)
        assertTrue(PaperDollSlot.LEGS in layers)
        assertTrue(PaperDollSlot.FEET in layers)
        assertTrue(PaperDollSlot.MAIN_HAND in layers)
        assertTrue(PaperDollSlot.OFF_HAND in layers)
        assertTrue(PaperDollSlot.CAPE in layers)
        assertTrue(PaperDollSlot.BACK_ATTACHMENT in layers)
        assertTrue(PaperDollSlot.SHOULDER_ATTACHMENT in layers)
        assertTrue(PaperDollSlot.HEADGEAR in layers)

        assertEquals(16, sockets.pivotX)
        assertEquals(28, sockets.pivotY)

        // Composite without shadow differs from composite with shadow
        val withShadow = gen.compositeLayers(layers, Direction8.S, PaperDollAction.WALK, includeShadow = true)
        val noShadow = gen.compositeLayers(layers, Direction8.S, PaperDollAction.WALK, includeShadow = false)
        assertFalse(withShadow.pixels.contentEquals(noShadow.pixels))

        // Z-order places CAPE before BODY when facing South, and after BODY when facing North
        val zSouth = PaperDollSlot.zOrderFor(Direction8.S, PaperDollAction.WALK)
        val zNorth = PaperDollSlot.zOrderFor(Direction8.N, PaperDollAction.WALK)
        assertTrue(zSouth.indexOf(PaperDollSlot.CAPE) < zSouth.indexOf(PaperDollSlot.BODY))
        assertTrue(zNorth.indexOf(PaperDollSlot.CAPE) > zNorth.indexOf(PaperDollSlot.BODY))
    }

    @Test
    fun `all 12 universal actions render deterministically with distinct action poses`() {
        val gen = PaperDollSpriteGenerator(seed = 42L, tileSize = 32)
        val loadout = PaperDollLoadout.forJob(PaperDollJob.PALADIN_CRUSADER)

        val rendered = PaperDollAction.ALL.associateWith { action ->
            val f0 = gen.generateCharacterFrame(loadout, Direction8.SW, action, frameIndex = 0)
            val f1 = gen.generateCharacterFrame(loadout, Direction8.SW, action, frameIndex = 1)
            assertTrue(f0.pixels.any { (it and 0xFF) != 0 })
            assertTrue(f1.pixels.any { (it and 0xFF) != 0 })
            f1
        }

        // Combat actions (SLASH, THRUST, SHOOT, CAST) and locomotion/emotes (JUMP, SIT, EMOTE) produce distinct frames
        val slash = rendered.getValue(PaperDollAction.SLASH)
        val thrust = rendered.getValue(PaperDollAction.THRUST)
        val shoot = rendered.getValue(PaperDollAction.SHOOT)
        val jump = rendered.getValue(PaperDollAction.JUMP)
        val sit = rendered.getValue(PaperDollAction.SIT)
        val emote = rendered.getValue(PaperDollAction.EMOTE)

        assertFalse(slash.pixels.contentEquals(thrust.pixels))
        assertFalse(thrust.pixels.contentEquals(shoot.pixels))
        assertFalse(jump.pixels.contentEquals(sit.pixels))
        assertFalse(sit.pixels.contentEquals(emote.pixels))
    }

    @Test
    fun `custom themes, ancestries, and custom pixel-grid attachments customize rendered layers`() {
        val gen = PaperDollSpriteGenerator(seed = 99L, tileSize = 32)
        val human = PaperDollLoadout.forJobWithTheme(PaperDollJob.SNIPER_HUNTER, PaperDollThemes.HIGHLAND_HERO)
        val elf = PaperDollLoadout.forJobWithTheme(PaperDollJob.SNIPER_HUNTER, PaperDollThemes.ELVEN_RANGER)
        val orc = PaperDollLoadout.forJobWithTheme(PaperDollJob.SNIPER_HUNTER, PaperDollThemes.ORC_WARLORD)

        val humanFrame = gen.generateCharacterFrame(human, Direction8.S, PaperDollAction.IDLE)
        val elfFrame = gen.generateCharacterFrame(elf, Direction8.S, PaperDollAction.IDLE)
        val orcFrame = gen.generateCharacterFrame(orc, Direction8.S, PaperDollAction.IDLE)

        assertFalse(humanFrame.pixels.contentEquals(elfFrame.pixels))
        assertFalse(elfFrame.pixels.contentEquals(orcFrame.pixels))

        val crest = CustomPixelAttachment(
            name = "rune_crest",
            socket = AttachmentSocket.HEAD,
            rows = listOf(
                ".4.",
                "454",
                ".4."
            ),
            palette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(80, 220, 255), steps = 6),
            offsetY = -2
        )
        val withCrest = elf.copy(customAttachments = listOf(crest))
        val (layersWithout, _) = gen.generateLayeredFrame(elf, Direction8.S, PaperDollAction.IDLE)
        val (layersWith, _) = gen.generateLayeredFrame(withCrest, Direction8.S, PaperDollAction.IDLE)
        assertNotEquals(
            layersWithout[PaperDollSlot.HEADGEAR]?.pixels?.toList(),
            layersWith[PaperDollSlot.HEADGEAR]?.pixels?.toList()
        )
    }

    @Test
    fun `universal sheet and equipment pack export cleanly with manifest and interactive previewer`(@TempDir tmpDir: Path) {
        val gen = PaperDollSpriteGenerator(seed = 1337L, tileSize = 64)
        val loadout = PaperDollLoadout(
            job = PaperDollJob.HIGH_WIZARD,
            theme = PaperDollThemes.GEFFEN_ARCHMAGE,
            attachments = listOf(AttachmentKind.RUNE_CIRCLE, AttachmentKind.FAIRY_WINGS, AttachmentKind.GLASSES)
        )
        val actions = listOf(PaperDollAction.IDLE, PaperDollAction.WALK, PaperDollAction.CAST, PaperDollAction.SLASH)
        val dirs = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)

        val sheet1 = gen.generateUniversalSheet("archmage", loadout, actions, dirs)
        val sheet2 = gen.generateUniversalSheet("archmage", loadout, actions, dirs)
        assertArrayEquals(sheet1.compositeSheet.pixels, sheet2.compositeSheet.pixels)
        assertEquals(4, sheet1.columns)
        assertEquals(actions.size * dirs.size, sheet1.rows)
        assertEquals(4 * 64, sheet1.compositeSheet.width)
        assertEquals(actions.size * dirs.size * 64, sheet1.compositeSheet.height)
        assertTrue(sheet1.layerSheets.containsKey(PaperDollSlot.BODY))
        assertTrue(sheet1.layerSheets.containsKey(PaperDollSlot.BACK_ATTACHMENT))

        val exported = PaperDollExporter.exportUniversal(sheet1, tmpDir.resolve("archmage"))
        assertTrue(Files.exists(exported.compositePng))
        assertTrue(Files.exists(exported.manifestJson))
        assertTrue(exported.previewHtml != null && Files.exists(exported.previewHtml!!))
        assertTrue(exported.layerPngs.containsKey(PaperDollSlot.BODY))
        assertTrue(Files.exists(exported.layerPngs.getValue(PaperDollSlot.BODY)))

        val manifestText = Files.readString(exported.manifestJson)
        assertTrue(manifestText.contains("\"zOrderByDirection\""))
        assertTrue(manifestText.contains("\"sockets\""))

        val previewText = Files.readString(exported.previewHtml!!)
        assertTrue(previewText.contains("data:image/png;base64,"))

        // Equipment pack generates hair, headgear, weapon, shield, and attachment sheets
        val pack = PaperDollSpriteGenerator(seed = 1337L, tileSize = 32).generateEquipmentPack(
            theme = PaperDollThemes.PRONTERA_KNIGHT,
            actions = listOf(PaperDollAction.IDLE, PaperDollAction.WALK),
            directions = listOf(Direction8.S, Direction8.W)
        )
        assertTrue("weapon_knight_sword" in pack)
        assertTrue("attachment_angel_wings" in pack)
        assertTrue("headgear_wizard_hat" in pack)
    }

    @Test
    fun `paperdoll json spec round-trips and cli exports universal layers and pack`(@TempDir tmpDir: Path) {
        val rawJson = """
            {
              "name": "elven_hero",
              "job": "sniper_hunter",
              "tileSize": 32,
              "seed": 777,
              "theme": {
                "preset": "elven_ranger",
                "ancestry": "elf",
                "eyeColor": "#44ddaa",
                "outfitPalette": ["#1b2e26", "#2f5a44", "#4d8a64", "#82c48c", "#c8f0c0"],
                "style": "ragnarok_25d"
              },
              "attachments": ["quiver", "fairy_wings", "glasses"],
              "customAttachments": [
                {
                  "name": "leaf_pin",
                  "socket": "shoulder",
                  "rows": ["01", "12"],
                  "offsetX": 1,
                  "offsetY": 0
                }
              ],
              "actions": ["lpc_standard"],
              "directions8": false,
              "exportLayers": true,
              "includeShadow": true
            }
        """.trimIndent()

        val parsed = PaperDollSpecJson.parse(rawJson)
        assertEquals("elven_hero", parsed.name)
        assertEquals(PaperDollAction.LPC_STANDARD, parsed.actions)
        assertEquals(1, parsed.customAttachments.size)
        val reserialized = PaperDollSpecJson.toJson(parsed)
        val reparsed = PaperDollSpecJson.parse(reserialized)
        assertEquals(parsed.name, reparsed.name)
        assertEquals(parsed.actions, reparsed.actions)

        val specFile = tmpDir.resolve("elven_hero.json")
        Files.writeString(specFile, rawJson)

        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val specCode = TilesetCli.run(
            arrayOf("paperdoll", "spec", specFile.toString(), "--out", tmpDir.resolve("cli_spec").toString()),
            PrintStream(outBuf),
            PrintStream(errBuf)
        )
        assertEquals(0, specCode, errBuf.toString())
        assertTrue(Files.exists(tmpDir.resolve("cli_spec/elven_hero_composite.png")))
        assertTrue(Files.exists(tmpDir.resolve("cli_spec/layers/body.png")))
        assertTrue(Files.exists(tmpDir.resolve("cli_spec/elven_hero_manifest.json")))

        val packCode = TilesetCli.run(
            arrayOf(
                "paperdoll", "pack",
                "--theme", "demon_reaper",
                "--actions", "idle,slash",
                "--out", tmpDir.resolve("cli_pack").toString()
            ),
            PrintStream(outBuf),
            PrintStream(errBuf)
        )
        assertEquals(0, packCode, errBuf.toString())
        assertTrue(Files.exists(tmpDir.resolve("cli_pack/pack/attachment_demon_wings.png")))
    }

    @Test
    fun `halcyon action set, harvest swing, and neutral equipment pack include torsos, legs, feet, and offhand blades`() {
        val gen = PaperDollSpriteGenerator(seed = 3001L, tileSize = 32)
        val miner = PaperDollLoadout(
            job = PaperDollJob.BLACKSMITH_MERCHANT,
            weapon = WeaponKind.PICKAXE,
            torsoStyle = TorsoStyle.BRIGANDINE
        )
        val h0 = gen.generateCharacterFrame(miner, Direction8.S, PaperDollAction.HARVEST, frameIndex = 0)
        val h1 = gen.generateCharacterFrame(miner, Direction8.S, PaperDollAction.HARVEST, frameIndex = 1)
        assertFalse(h0.pixels.contentEquals(h1.pixels))

        val pack = gen.generateEquipmentPack(
            theme = PaperDollThemes.HALCYON_NEUTRAL,
            actions = PaperDollAction.HALCYON,
            directions = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
        )
        assertTrue("torso_traveler" in pack)
        assertTrue("torso_brigandine" in pack)
        assertTrue("torso_skyrender" in pack)
        assertTrue("legs_trousers" in pack)
        assertTrue("feet_boots" in pack)
        assertTrue("feet_sabatons" in pack)
        assertTrue("headgear_iron_helmet" in pack)
        assertTrue("headgear_steel_helmet" in pack)
        assertTrue("headgear_skyrender_helm" in pack)
        assertTrue("weapon_single_blade" in pack)
        assertTrue("weapon_katana" in pack)
        assertTrue("weapon_pickaxe" in pack)
        assertTrue("shield_tower_shield" in pack)
        assertTrue("shield_offhand_blade" in pack)
        assertTrue("attachment_satchel" in pack)
        assertTrue("attachment_silk_garment" in pack)

        // Every pack sheet's tile list is grid-aligned to (actions * directions) rows x maxFrames columns
        val expectedRows = PaperDollAction.HALCYON.size * 4
        val expectedCols = PaperDollAction.HALCYON.maxOf { it.defaultFrames }
        val (swordSheet, swordTiles) = pack.getValue("weapon_knight_sword")
        assertEquals(expectedCols * 32, swordSheet.width)
        assertEquals(expectedRows * 32, swordSheet.height)
        assertEquals(expectedRows * expectedCols, swordTiles.size)
        // Row 4 (start of WALK) begins at tile index 4 * expectedCols
        assertEquals("weapon_knight_sword_walk_s_f0", swordTiles[4 * expectedCols].name)

        // Chunky proportions fill ~22-26px wide x 28-30px tall inside 32x32 with feet landing at y=28..29
        val chunkyLoadout = PaperDollLoadout(
            job = PaperDollJob.LORD_KNIGHT,
            theme = PaperDollThemes.HALCYON_NEUTRAL,
            attachments = listOf(AttachmentKind.KNIGHT_PAULDRONS)
        )
        val chunkyFrame = gen.generateCharacterFrame(chunkyLoadout, Direction8.S, PaperDollAction.IDLE, frameIndex = 0)
        var minX = 32
        var maxX = -1
        var maxY = -1
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                if ((chunkyFrame.getPixel(x, y) and 0xFF) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue((maxX - minX + 1) >= 20, "Expected chunky width >= 20px, got ${maxX - minX + 1}")
        assertTrue(maxY in 28..30, "Expected feet/shadow bottom at y=28..30, got $maxY")

        // RMXP 32x48 proportions: 32px wide x 48px tall with headgear headroom and feet at y=44..47
        val rmxpGen = PaperDollSpriteGenerator(seed = 3003L, tileSize = 32, tileHeight = 48)
        val rmxpLoadout = PaperDollLoadout(
            job = PaperDollJob.HIGH_WIZARD,
            headgear = HeadgearKind.WIZARD_HAT,
            theme = PaperDollThemes.HALCYON_NEUTRAL.copy(proportions = PaperDollProportions.RMXP_32X48)
        )
        val rmxpSheet = rmxpGen.generateUniversalSheet(
            name = "rmxp_mage",
            loadout = rmxpLoadout,
            actions = listOf(PaperDollAction.IDLE, PaperDollAction.CAST),
            directions = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
        )
        assertEquals(32, rmxpSheet.cellWidth)
        assertEquals(48, rmxpSheet.cellHeight)
        assertEquals(4 * 32, rmxpSheet.compositeSheet.width)
        assertEquals(8 * 48, rmxpSheet.compositeSheet.height)

        val rmxpFrame = rmxpGen.generateCharacterFrame(rmxpLoadout, Direction8.S, PaperDollAction.IDLE)
        var rMinY = 48
        var rMaxY = -1
        for (y in 0 until 48) {
            for (x in 0 until 32) {
                if ((rmxpFrame.getPixel(x, y) and 0xFF) != 0) {
                    if (y < rMinY) rMinY = y
                    if (y > rMaxY) rMaxY = y
                }
            }
        }
        assertTrue(rMinY <= 8, "Expected wizard hat headroom near top, got minY=$rMinY")
        assertTrue(rMaxY in 44..47, "Expected RMXP feet/shadow bottom at y=44..47, got $rMaxY")

        // Verify 32x48 RMXP legs and feet layers are populated in ALL 4 directions (S, W, E, N) for both trousers and robes
        for (legsStyle in listOf(LegsStyle.TROUSERS, LegsStyle.ROBE_SKIRT)) {
            val lo = rmxpLoadout.copy(legsStyle = legsStyle)
            for (dir in listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)) {
                val (layers, _) = rmxpGen.generateLayeredFrame(lo, dir, PaperDollAction.WALK, frameIndex = 1)
                assertTrue(PaperDollSlot.LEGS in layers, "Missing LEGS layer for $legsStyle facing $dir")
                assertTrue(PaperDollSlot.FEET in layers, "Missing FEET layer for $legsStyle facing $dir")
            }
        }

        // Halcyon Reach 32x48 RMXP NPCs (12) and Enemies (22)
        val halcyonGen = HalcyonSpriteGenerator(seed = 1337L, cellWidth = 32, cellHeight = 48)
        assertEquals(12, HalcyonNpcKind.entries.size)
        assertEquals(22, HalcyonEnemyKind.entries.size)
        val (npcSheet, npcTiles) = halcyonGen.generateNpcSheet(HalcyonNpcKind.CAPTAIN)
        assertEquals(4 * 32, npcSheet.width)
        assertEquals(36 * 48, npcSheet.height)
        assertEquals(36 * 4, npcTiles.size)

        for (enemy in HalcyonEnemyKind.entries) {
            val frame = halcyonGen.generateEnemyFrame(enemy, Direction8.S, PaperDollAction.IDLE, 0)
            assertEquals(32, frame.width)
            assertEquals(48, frame.height)
            assertTrue(frame.pixels.any { (it and 0xFF) != 0 }, "Expected visible pixels for ${enemy.key}")
        }
    }
}
