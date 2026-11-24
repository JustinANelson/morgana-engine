package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.export.PngEncoder
import com.morgana.tilegen.spec.PaperDollSpec
import com.morgana.tilegen.spec.PaperDollSpecJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The glove layer: a separate `hands` layer drawn over the body's skin hands, and nothing at all when no gloves are worn. */
class GloveLayerTest {
    private val gen = PaperDollSpriteGenerator(seed = 5L, tileSize = 32, tileHeight = 48)

    private fun loadout(gloves: GloveStyle, job: PaperDollJob = PaperDollJob.KNIGHT) = PaperDollLoadout(job = job, gloves = gloves)

    private fun opaque(img: TileImage): Int = (0 until img.height).sumOf { y -> (0 until img.width).count { x -> (img.getPixel(x, y) and 0xFF) != 0 } }

    @Test
    fun `no gloves means no hands layer, exactly as before`() {
        val (layers, _) = gen.generateLayeredFrame(loadout(GloveStyle.NONE), Direction8.S, PaperDollAction.WALK, frameIndex = 1)
        assertFalse(PaperDollSlot.HANDS in layers, "a loadout without gloves makes no hands layer")
        val sheet = gen.generateUniversalSheet("plain", loadout(GloveStyle.NONE), actions = listOf(PaperDollAction.WALK), directions = listOf(Direction8.S))
        assertFalse(PaperDollSlot.HANDS in sheet.layerSheets)
    }

    @Test
    fun `each glove style makes a hands layer that is small, deterministic and leaves the other layers untouched`() {
        val bare = gen.generateLayeredFrame(loadout(GloveStyle.NONE), Direction8.S, PaperDollAction.WALK, frameIndex = 1).first
        for (style in listOf(GloveStyle.LEATHER, GloveStyle.GAUNTLET, GloveStyle.WRAPS)) {
            val worn = gen.generateLayeredFrame(loadout(style), Direction8.S, PaperDollAction.WALK, frameIndex = 1).first
            val hands = worn.getValue(PaperDollSlot.HANDS)
            assertTrue(opaque(hands) in 8..120, "$style covers the hands and little else: ${opaque(hands)} pixels")
            assertEquals(hands.pixels.toList(), gen.generateLayeredFrame(loadout(style), Direction8.S, PaperDollAction.WALK, frameIndex = 1).first.getValue(PaperDollSlot.HANDS).pixels.toList(), "$style is deterministic")
            for ((slot, image) in bare) assertEquals(image.pixels.toList(), worn.getValue(slot).pixels.toList(), "$style leaves $slot as it was")
        }
        val leather = gen.generateLayeredFrame(loadout(GloveStyle.LEATHER), Direction8.S, PaperDollAction.WALK, 1).first.getValue(PaperDollSlot.HANDS)
        val gauntlet = gen.generateLayeredFrame(loadout(GloveStyle.GAUNTLET), Direction8.S, PaperDollAction.WALK, 1).first.getValue(PaperDollSlot.HANDS)
        assertTrue(opaque(gauntlet) > opaque(leather), "a gauntlet runs further up the arm than a glove")
        assertNotEquals(leather.pixels.toList(), gauntlet.pixels.toList())
    }

    @Test
    fun `gloves follow the hand through every action and direction`() {
        for (action in listOf(PaperDollAction.WALK, PaperDollAction.SLASH, PaperDollAction.CAST, PaperDollAction.HARVEST, PaperDollAction.IDLE)) {
            for (dir in listOf(Direction8.S, Direction8.N, Direction8.E, Direction8.W)) {
                val hands = gen.generateLayeredFrame(loadout(GloveStyle.LEATHER), dir, action, frameIndex = 1).first[PaperDollSlot.HANDS]
                assertTrue(hands != null && opaque(hands) > 0, "$action facing $dir has gloves on")
            }
        }
    }

    @Test
    fun `the hands layer sits above the torso and below held weapons in every facing`() {
        for (dir in Direction8.entries) {
            val z = PaperDollSlot.zOrderFor(dir, PaperDollAction.WALK)
            assertTrue(z.indexOf(PaperDollSlot.HANDS) > z.indexOf(PaperDollSlot.TORSO), "$dir: over the torso")
            assertTrue(z.indexOf(PaperDollSlot.HANDS) < z.indexOf(PaperDollSlot.HEADGEAR), "$dir: under headgear")
        }
        val composite = gen.compositeLayers(gen.generateLayeredFrame(loadout(GloveStyle.GAUNTLET), Direction8.S, PaperDollAction.WALK, 1).first, Direction8.S)
        val without = gen.compositeLayers(gen.generateLayeredFrame(loadout(GloveStyle.NONE), Direction8.S, PaperDollAction.WALK, 1).first, Direction8.S)
        assertNotEquals(composite.pixels.toList(), without.pixels.toList(), "gloves change the finished picture")
    }

    @Test
    fun `a spec names the glove style and round-trips`() {
        val spec = PaperDollSpec(name = "gloved", job = PaperDollJob.KNIGHT, gloves = GloveStyle.GAUNTLET)
        assertEquals(GloveStyle.GAUNTLET, spec.toLoadout().gloves)
        val json = PaperDollSpecJson.toJson(spec)
        assertTrue("\"gloves\": \"gauntlet\"" in json, json)
        assertEquals(GloveStyle.GAUNTLET, PaperDollSpecJson.parse(json).gloves)
        assertNull(PaperDollSpecJson.parse(PaperDollSpecJson.toJson(PaperDollSpec(name = "bare", job = PaperDollJob.KNIGHT))).gloves)
        assertEquals(GloveStyle.NONE, PaperDollSpec(name = "bare", job = PaperDollJob.KNIGHT).toLoadout().gloves)
    }

    /** Writes a contact sheet to build/ so a human can look at the gloves (not an assertion). */
    @Test
    fun `a preview sheet can be written for a human to check`() {
        val styles = listOf(GloveStyle.NONE, GloveStyle.LEATHER, GloveStyle.GAUNTLET, GloveStyle.WRAPS)
        val frames = listOf(Direction8.S to PaperDollAction.WALK, Direction8.E to PaperDollAction.WALK, Direction8.N to PaperDollAction.WALK, Direction8.S to PaperDollAction.SLASH, Direction8.E to PaperDollAction.SLASH, Direction8.S to PaperDollAction.CAST)
        val scale = 6
        val sheet = TileImage(32 * frames.size, 48 * styles.size)
        styles.forEachIndexed { row, style ->
            frames.forEachIndexed { col, (dir, action) ->
                val layers = gen.generateLayeredFrame(loadout(style, if (col % 2 == 0) PaperDollJob.HUNTER else PaperDollJob.WIZARD), dir, action, frameIndex = 1).first
                val cell = gen.compositeLayers(layers, dir, action)
                sheet.drawImage(cell, 0, 0, 32, 48, col * 32, row * 48)
            }
        }
        val big = TileImage(sheet.width * scale, sheet.height * scale)
        for (y in 0 until big.height) for (x in 0 until big.width) big.setPixel(x, y, sheet.getPixel(x / scale, y / scale))
        val out = Path.of("build", "glove-preview.png")
        Files.createDirectories(out.parent)
        Files.write(out, PngEncoder.encode(big))
        assertTrue(Files.size(out) > 1000)
    }
}
