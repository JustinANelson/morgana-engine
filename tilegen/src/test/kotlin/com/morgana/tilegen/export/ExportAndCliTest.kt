package com.morgana.tilegen.export

import com.morgana.tilegen.ProceduralTilesetGenerator
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.cli.TilesetCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ExportAndCliTest {

    @Test
    fun `png encoder round-trips opaque and translucent pixels through ImageIO`() {
        val img = TileImage(4, 3)
        img.setPixel(0, 0, 0xFF0000FF.toInt()) // red opaque
        img.setPixel(1, 0, 0x00FF0080.toInt()) // green 50% alpha
        img.setPixel(2, 1, 0x11223344)          // arbitrary RGBA
        img.setPixel(3, 2, 0x00000000)          // transparent

        val bytes = PngEncoder.encode(img)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])

        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)

        for (y in 0 until 3) {
            for (x in 0 until 4) {
                val expectedRgba = img.getPixel(x, y)
                val argb = decoded.getRGB(x, y)
                val actualRgba = (argb shl 8) or ((argb ushr 24) and 0xFF)
                assertEquals(expectedRgba, actualRgba, "Pixel mismatch at ($x, $y)")
            }
        }
    }

    @Test
    fun `autotile exporter writes sheet png, metadata json, and split tiles`(@TempDir tempDir: Path) {
        val sheet = AutotileGenerator(seed = 12L, tileSize = 16)
            .generateTerrainAutotile(TerrainPair.GRASS_ON_DIRT, AutotileMode.CARDINAL_16)
        val result = TilesetExporter.exportAutotile("grass_dirt", sheet, tempDir, splitTiles = true)

        assertTrue(Files.isRegularFile(result.sheetPng))
        assertTrue(Files.isRegularFile(result.metadataJson))
        assertEquals(16, result.splitTileFiles.size)
        result.splitTileFiles.forEach { assertTrue(Files.isRegularFile(it)) }

        val jsonText = Files.readString(result.metadataJson)
        assertTrue(jsonText.contains("\"mode\": \"CARDINAL_16\""))
        assertTrue(jsonText.contains("\"tileSize\": 16"))
    }

    @Test
    fun `cli list, theme, autotile, and material commands succeed and invalid commands fail`(@TempDir tempDir: Path) {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val out = PrintStream(outBuf)
        val err = PrintStream(errBuf)

        assertEquals(0, TilesetCli.run(arrayOf("list"), out, err))
        assertTrue(outBuf.toString().contains("Themes:"))

        val themeOut = tempDir.resolve("theme_out")
        assertEquals(0, TilesetCli.run(arrayOf("theme", "farm", "--seed", "42", "--out", themeOut.toString(), "--split"), out, err))
        assertTrue(Files.isRegularFile(themeOut.resolve("farm.png")))
        assertTrue(Files.isRegularFile(themeOut.resolve("farm.json")))
        assertTrue(Files.isDirectory(themeOut.resolve("tiles")))

        val autoOut = tempDir.resolve("auto_out")
        assertEquals(0, TilesetCli.run(arrayOf("autotile", "sand_on_water", "--mode", "blob47", "--size", "16", "--out", autoOut.toString()), out, err))
        assertTrue(Files.isRegularFile(autoOut.resolve("sand_on_water_blob_47.png")))

        val matOut = tempDir.resolve("mat_out")
        assertEquals(0, TilesetCli.run(arrayOf("material", "water_caustics", "--palette", "lava_magma", "--frames", "4", "--size", "32", "--out", matOut.toString()), out, err))
        assertTrue(Files.isRegularFile(matOut.resolve("water_caustics_lava_magma.png")))

        assertEquals(1, TilesetCli.run(arrayOf("unknown_cmd"), out, err))
    }

    @Test
    fun `procedural starter sheet encodes cleanly to png`(@TempDir tempDir: Path) {
        val sheet = ProceduralTilesetGenerator(seed = 42L).generateTileset(columns = 16, rows = 3)
        val outFile = tempDir.resolve("starter.png")
        PngEncoder.writeToFile(sheet, outFile)
        val decoded = ImageIO.read(outFile.toFile())
        assertEquals(512, decoded.width)
        assertEquals(96, decoded.height)
    }

    @Test
    fun `rmxp exporter produces 96x128 3x4 autotile sheet from 32px blob47`() {
        val sheet = AutotileGenerator(seed = 9L, tileSize = 32)
            .generateTerrainAutotile(TerrainPair.GRASS_ON_DIRT, AutotileMode.BLOB_47)
        val rmxp = RpgMakerExporter.toRmxpAutotile(sheet)
        assertEquals(96, rmxp.width)
        assertEquals(128, rmxp.height)
    }
}
