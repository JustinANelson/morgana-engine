package com.morgana.tilegen.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** The RPG Maker / Time Fantasy layout: role sheets at fixed ids per pack, autotile blocks packed into the A sheets. */
class RpgMakerLayoutTest {
    private val spec = """
        {
          "name": "pk", "seed": 3, "tileSize": 16, "columns": 4, "style": "CLASSIC_RPG",
          "materials": [
            { "name": "grass", "pattern": "TUFTED_GROUND", "palette": "meadow_grass", "variants": 2 },
            { "name": "dirt", "pattern": "TUFTED_GROUND", "palette": "earth_dirt" },
            { "name": "sea", "pattern": "FLAT_WATER", "palette": "calm_water", "frames": 4 },
            { "name": "flag", "pattern": "FLAT_PAVER", "palette": "slate_stone" },
            { "name": "rockface", "pattern": "ROUNDED_STONES", "palette": "slate_stone" }
          ],
          "cliffs": [ { "name": "wall", "topMaterial": "flag", "faceMaterial": "rockface", "a4Layout": true } ],
          "props": [
            { "name": "oak", "kind": "LEAFY_TREE", "primaryPalette": "meadow_grass" },
            { "name": "bush", "kind": "FLOWER_BUSH", "primaryPalette": "meadow_grass" },
            { "name": "ruin", "kind": "RUIN_WALL" },
            { "name": "barrel", "kind": "BARREL" },
            { "name": "house", "kind": "COTTAGE_LARGE" }
          ],
          "autotiles": [
            { "name": "dirt_on_grass", "mode": "BLOB_47", "columns": 8, "foregroundMaterial": "dirt", "backgroundMaterial": "grass" },
            { "name": "sea_on_grass", "mode": "BLOB_47", "columns": 8, "foregroundMaterial": "sea", "backgroundMaterial": "grass" }
          ]
        }
    """.trimIndent()

    private fun write(dir: Path, name: String, text: String): Path {
        val f = dir.resolve(name)
        Files.createDirectories(dir)
        Files.writeString(f, text)
        return f
    }

    @Test
    fun `each spec is a pack of role sheets at fixed ids ten apart`(@TempDir dir: Path) {
        val a = write(dir.resolve("specs"), "a.json", spec)
        val b = write(dir.resolve("specs"), "b.json", """
            { "name": "pk2", "seed": 4, "tileSize": 16, "columns": 4, "style": "CLASSIC_RPG",
              "materials": [ { "name": "snow", "pattern": "TUFTED_GROUND", "palette": "frost_snow" } ],
              "props": [ { "name": "pine", "kind": "EVERGREEN_TREE", "primaryPalette": "meadow_grass" } ] }
        """.trimIndent())
        val game = dir.resolve("game")
        val r = GameTilesetExporter.export(game, listOf(a, b), firstId = 100, layout = GameTilesetExporter.Layout.RPG_MAKER)

        val first = r.packs[0]
        assertEquals(100, first.firstId)
        assertEquals(mapOf("A1" to 100, "A2" to 101, "A4" to 103, "A5" to 104, "B" to 105, "C" to 106, "D" to 107, "E" to 108), first.sheets)
        assertEquals(110, r.packs[1].firstId, "the next pack starts ten ids on")
        assertEquals(120, r.nextFreeId)
        assertEquals(mapOf("A2" to 111, "B" to 115), r.packs[1].sheets)
        assertEquals(setOf(100, 101, 103, 104, 105, 106, 107, 108, 111, 115), r.written.map { it.fileName.toString().removeSuffix(".png").toInt() }.toSet())

        // Roles: water in A1, ground in A2, cliff set in A4, paving in A5, nature in B, structures in C, objects in D, buildings in E.
        assertEquals(100, r.tiles.getValue("sea_f0").tilesetId)
        assertEquals(100, r.autotiles.getValue("sea_on_grass").tilesetId)
        assertEquals(101, r.tiles.getValue("grass").tilesetId)
        assertEquals(101, r.autotiles.getValue("dirt_on_grass").tilesetId)
        assertEquals(103, r.tiles.getValue("wall_top_left").tilesetId)
        assertEquals(104, r.tiles.getValue("flag").tilesetId)
        assertEquals(105, r.props.getValue("oak").tilesetId)
        assertEquals(105, r.props.getValue("bush").tilesetId)
        assertEquals(106, r.props.getValue("ruin").tilesetId)
        assertEquals(107, r.props.getValue("barrel").tilesetId)
        assertEquals(108, r.props.getValue("house").tilesetId)
        assertEquals(1, r.props.getValue("barrel").w)

        val index = Files.readString(game.resolve("tilesets.index.json"))
        assertTrue(index.contains("\"layout\": \"rpgmaker\""))
        assertTrue(index.contains("\"A1\": 100"))
    }

    @Test
    fun `wall sets are 3 by 3 blocks and every index cell lies inside its sheet`(@TempDir dir: Path) {
        val a = write(dir.resolve("specs"), "a.json", spec)
        val game = dir.resolve("game")
        val r = GameTilesetExporter.export(game, listOf(a), firstId = 100, layout = GameTilesetExporter.Layout.RPG_MAKER)
        val wall = listOf("void", "top", "bot").flatMap { row -> listOf("left", "center", "right").map { col -> "wall_${row}_$col" } }
        val origin = r.tiles.getValue("wall_void_left")
        wall.forEachIndexed { i, name ->
            val p = r.tiles.getValue(name)
            assertEquals(origin.x + i % 3, p.x, "$name column")
            assertEquals(origin.y + i / 3, p.y, "$name row")
        }

        fun size(id: Int) = ImageIO.read(game.resolve("assets/tilesets/$id.png").toFile()).let { it.width / 16 to it.height / 16 }
        for ((n, p) in r.tiles + r.props) {
            val (w, h) = size(p.tilesetId)
            assertTrue(p.x + p.w <= w && p.y + p.h <= h, "$n fits its sheet")
        }
        for ((n, a2) in r.autotiles) {
            val (w, h) = size(a2.tilesetId)
            assertEquals(47, a2.cells.size)
            assertTrue(a2.cells.values.all { (x, y) -> x < w && y < h }, "$n cells fit its sheet")
        }
        // Two autotile blocks never overlap on the shared sheet.
        val seen = HashSet<Pair<Int, Int>>()
        for (a2 in r.autotiles.values.filter { it.tilesetId == 101 }) for (c in a2.cells.values) assertTrue(seen.add(c), "autotile cell $c is used once")
    }

    @Test
    fun `the layout is deterministic and the old layout is unchanged`(@TempDir dir: Path) {
        val a = write(dir.resolve("specs"), "a.json", spec)
        val g1 = dir.resolve("g1")
        val g2 = dir.resolve("g2")
        GameTilesetExporter.export(g1, listOf(a), firstId = 100, layout = GameTilesetExporter.Layout.RPG_MAKER)
        GameTilesetExporter.export(g2, listOf(a), firstId = 100, layout = GameTilesetExporter.Layout.RPG_MAKER)
        assertEquals(Files.readString(g1.resolve("tilesets.index.json")), Files.readString(g2.resolve("tilesets.index.json")))
        for (id in listOf(100, 101, 103, 105)) {
            assertTrue(Files.readAllBytes(g1.resolve("assets/tilesets/$id.png")).contentEquals(Files.readAllBytes(g2.resolve("assets/tilesets/$id.png"))))
        }
        val legacy = GameTilesetExporter.export(dir.resolve("g3"), listOf(a), firstId = 100)
        assertFalse(Files.readString(dir.resolve("g3/tilesets.index.json")).contains("rpgmaker"))
        assertTrue(legacy.packs.isEmpty())
    }
}
