package com.morgana.gameart.export

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.sprite.*
import com.morgana.gameart.whiteriver.*

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class TrevanaGameExporterTest {
    private val specs: Path = Path.of("specs")

    @Test
    fun `soft outline is a darker step of the fill and never flat black`() {
        val fill = PaletteRamp.rgba(180, 60, 50)
        val img = TileImage(8, 8)
        for (y in 2..5) for (x in 2..5) img.setPixel(x, y, fill)
        val out = SpriteFinish.finish(img)
        val outline = out.getPixel(1, 3)
        assertEquals(255, outline and 0xFF)
        val r = (outline ushr 24) and 0xFF
        val g = (outline ushr 16) and 0xFF
        val b = (outline ushr 8) and 0xFF
        assertTrue(r < 180 && r > 40, "outline keeps some of the fill's red, got $r")
        assertTrue(r + g + b > 60, "outline is not black")
        // Pixels clear of the silhouette stay empty.
        assertEquals(0, out.getPixel(0, 0) and 0xFF)
    }

    @Test
    fun `rim shading lightens the lit edge and darkens the shadow edge`() {
        val img = TileImage(12, 12)
        for (y in 2..9) for (x in 2..9) img.setPixel(x, y, PaletteRamp.rgba(120, 120, 120))
        val out = SpriteFinish.shade(img)
        fun luma(p: Int) = ((p ushr 24) and 0xFF) + ((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF)
        assertTrue(luma(out.getPixel(2, 5)) > luma(out.getPixel(5, 5)), "left edge is lighter than the middle")
        assertTrue(luma(out.getPixel(9, 5)) < luma(out.getPixel(5, 5)), "right edge is darker than the middle")
    }

    @Test
    fun `export writes only the owned sprite and paperdoll ids in the engine layout and is deterministic`(@TempDir dir: Path) {
        val game = dir.resolve("game")
        val sprites = game.resolve("assets/sprites")
        Files.createDirectories(sprites)
        // A hand-drawn sprite in an id the exporter does not own must survive untouched.
        val handDrawn = sprites.resolve("40.png")
        Files.write(handDrawn, byteArrayOf(1, 2, 3))

        val first = TrevanaGameExporter.export(game, specs)
        assertEquals(5 + 12 + 22 + 21, first.written.size)
        assertTrue(Files.readAllBytes(handDrawn).contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(first.written.any { it.fileName.toString() == "40.png" })

        val body = ImageIO.read(sprites.resolve("1.png").toFile())
        assertEquals(32 * TrevanaGameExporter.COLUMNS, body.width)
        assertEquals(736, body.width)
        assertEquals(192, body.height)
        val gear = ImageIO.read(game.resolve("assets/paperdolls/17.png").toFile())
        assertEquals(736, gear.width)
        assertEquals(192, gear.height)

        val before = Files.readAllBytes(sprites.resolve("101.png"))
        TrevanaGameExporter.export(game, specs)
        assertTrue(before.contentEquals(Files.readAllBytes(sprites.resolve("101.png"))), "same input, same bytes")
        assertTrue(first.uiHeights.getValue(1) in 38..48, "class bodies are about a cell tall")
    }

    @Test
    fun `a part can be exported alone`(@TempDir dir: Path) {
        val result = TrevanaGameExporter.export(dir, specs, setOf(TrevanaGameExporter.Part.GEAR))
        assertEquals(TrevanaGameExporter.GEAR.size, result.written.size)
        assertFalse(Files.exists(dir.resolve("assets/sprites/1.png")))
    }
}
