package com.morgana.mapbuilder

import com.morgana.domain.map.InstanceOwner
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.tilegen.autotile.AutotileBitmask
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapCompilerTest {

    /** A tiny index: grass with two variants, a sea with an autotile and animation frames, a wall set, and two props. */
    private val index: TileIndex = run {
        val autoCells = AutotileBitmask.BLOB_47_MASKS.withIndex().joinToString(",") { (i, m) -> "\"$m\": [${i % 8}, ${i / 8}]" }
        val wall = listOf("void", "top", "bot").flatMap { r -> listOf("left", "center", "right").map { s -> "\"w_${r}_$s\": {\"tilesetId\": 9, \"x\": 0, \"y\": 0}" } }
        TileIndex.parse(
            """
            {
              "tiles": {
                "grass": {"tilesetId": 1, "x": 0, "y": 0}, "grass_v1": {"tilesetId": 1, "x": 1, "y": 0},
                "rock": {"tilesetId": 1, "x": 2, "y": 0},
                "sea_f0": {"tilesetId": 1, "x": 0, "y": 1}, "sea_f1": {"tilesetId": 1, "x": 1, "y": 1},
                ${wall.joinToString(",")}
              },
              "props": {
                "tree": {"tilesetId": 2, "x": 0, "y": 0, "w": 1, "h": 2},
                "gate": {"tilesetId": 2, "x": 4, "y": 0, "w": 3, "h": 2}
              },
              "autotiles": { "sea_on_grass": {"tilesetId": 3, "cells": {$autoCells}} }
            }
            """.trimIndent()
        )
    }

    private val json = Json { classDiscriminator = "op" }

    private fun recipe(ops: String, w: Int = 30, h: Int = 30, extra: String = ""): MapRecipe = json.decodeFromString(
        MapRecipe.serializer(),
        """
        {"id": 7, "name": "Test", "width": $w, "height": $h, "seed": 5, "base": "grass", $extra
         "terrains": {
           "grass": {"tile": "grass"},
           "rock": {"tile": "rock", "blocked": true},
           "sea": {"tile": "sea", "blocked": true, "autotile": "sea_on_grass", "joins": [], "animatedFrames": 2}
         },
         "ops": [$ops]}
        """.trimIndent()
    )

    @Test
    fun `base terrain fills the map, picks variants by position, and is deterministic`() {
        val a = MapCompiler.compile(recipe(""), index)
        val b = MapCompiler.compile(recipe(""), index)
        assertEquals(30 * 30, a.cells.size)
        assertEquals(a, b)
        val tiles = a.cells.map { it.layers.getValue(MapLayer.GROUND).srcTileX }.toSet()
        assertEquals(setOf(0, 1), tiles, "both grass variants appear")
    }

    @Test
    fun `blocked terrain blocks and an autotile rounds its edge by neighbour mask while the inside animates`() {
        val m = MapCompiler.compile(recipe("""{"op": "rect", "terrain": "sea", "x": 10, "y": 10, "w": 6, "h": 6}"""), index)
        fun cell(x: Int, y: Int) = m.cells[y * m.width + x]
        assertEquals(TileAttribute.Blocked, cell(12, 12).attribute)
        assertEquals(TileAttribute.None, cell(5, 5).attribute)
        val inside = cell(12, 12).layers.getValue(MapLayer.GROUND)
        assertEquals(2, inside.animationFrames)
        assertEquals(1, inside.tilesetId)
        val corner = cell(10, 10).layers.getValue(MapLayer.GROUND)
        assertEquals(3, corner.tilesetId, "an edge cell uses the autotile sheet")
        val expected = AutotileBitmask.blobMask(e = true, se = true, s = true)
        assertEquals(AutotileBitmask.BLOB_47_MASKS.indexOf(expected) % 8, corner.srcTileX)
    }

    @Test
    fun `props block their bottom row, draw upper rows in front, leave gate columns open, and cannot leave the map`() {
        val m = MapCompiler.compile(recipe("""{"op": "prop", "prop": "gate", "x": 4, "y": 4, "passableCols": [1]}, {"op": "prop", "prop": "tree", "x": 9, "y": 9}"""), index)
        fun cell(x: Int, y: Int) = m.cells[y * m.width + x]
        assertEquals(TileAttribute.Blocked, cell(4, 5).attribute)
        assertEquals(TileAttribute.None, cell(5, 5).attribute, "the gate column stays open")
        assertTrue(MapLayer.FRINGE_1 in cell(5, 4).layers)
        assertTrue(MapLayer.MASK_1 in cell(5, 5).layers)
        assertEquals(TileAttribute.Blocked, cell(9, 10).attribute)
        assertThrows<RecipeException> { MapCompiler.compile(recipe("""{"op": "prop", "prop": "tree", "x": 29, "y": 29}"""), index) }
    }

    @Test
    fun `walls use the cap and face rows and a frame leaves its gap open`() {
        val m = MapCompiler.compile(recipe("""{"op": "wall", "set": "w", "x": 2, "y": 2, "w": 5, "h": 4, "passableCols": [2]}, {"op": "frame", "terrain": "rock", "thickness": 2, "gaps": {"north": [10, 14]}}"""), index)
        fun cell(x: Int, y: Int) = m.cells[y * m.width + x]
        assertEquals(TileAttribute.Blocked, cell(3, 3).attribute)
        assertEquals(TileAttribute.None, cell(4, 5).attribute, "gate column of the face rows is open")
        assertEquals(TileAttribute.Blocked, cell(4, 3).attribute, "the flat top is still blocked")
        assertEquals(TileAttribute.None, cell(12, 0).attribute, "the gap in the north band")
        assertEquals(TileAttribute.Blocked, cell(20, 0).attribute)
    }

    @Test
    fun `a recipe can make the map instanced for a party or a guild and is not instanced by default`() {
        val plain = MapCompiler.compile(recipe("""{"op": "rect", "terrain": "rock", "x": 0, "y": 0, "w": 3, "h": 3}"""), index)
        assertEquals(false, plain.instanced)
        assertEquals(InstanceOwner.PARTY, plain.instanceOwner)
        val raid = MapCompiler.compile(recipe("", extra = "\"instanced\": true,"), index)
        assertEquals(true, raid.instanced)
        assertEquals(InstanceOwner.PARTY, raid.instanceOwner)
        val hall = MapCompiler.compile(recipe("", extra = "\"instanced\": true, \"instanceOwner\": \"GUILD\","), index)
        assertEquals(InstanceOwner.GUILD, hall.instanceOwner)
    }

    @Test
    fun `warps and spawns land where asked and camps keep to open terrain with the requested density`() {
        val m = MapCompiler.compile(
            recipe(
                """{"op": "rect", "terrain": "rock", "x": 0, "y": 0, "w": 60, "h": 6},
                   {"op": "warp", "x": 3, "y": 20, "toMap": 2, "toX": 5, "toY": 6, "w": 2},
                   {"op": "spawn", "npcId": 4, "x": 8, "y": 8},
                   {"op": "camps", "npcIds": [20, 21], "terrains": ["grass"], "tilesPerEnemy": 30, "sizeMin": 4, "sizeMax": 6, "spacing": 6}""",
                w = 60, h = 60
            ),
            index
        )
        assertEquals(TileAttribute.Warp(2, 5, 6), m.cells[20 * 60 + 4].attribute)
        assertEquals(4, m.npcSpawns.first().npcId)
        val camp = m.npcSpawns.drop(1)
        assertTrue(camp.isNotEmpty())
        assertTrue(camp.all { it.spawnY >= 6 }, "no enemy stands on the rock")
        assertEquals(camp.size, camp.map { it.spawnX to it.spawnY }.toSet().size, "one enemy per tile")
        assertEquals(m.npcSpawns.indices.toList(), m.npcSpawns.map { it.slotIndex }, "slots are numbered in order")
        val perEnemy = (60 * 54).toFloat() / camp.size
        assertTrue(perEnemy in 15f..90f, "about one enemy per 30 tiles, was one per $perEnemy")
        assertNotEquals(camp.map { it.npcId }.toSet().size, 0)
    }

    @Test
    fun `scatter keeps props apart and off blocked ground`() {
        val m = MapCompiler.compile(
            recipe("""{"op": "rect", "terrain": "rock", "x": 0, "y": 0, "w": 30, "h": 10}, {"op": "scatter", "props": ["tree"], "terrain": "grass", "density": 0.3, "gap": 1}"""),
            index
        )
        val trees = m.cells.withIndex().filter { (_, c) -> c.layers[MapLayer.MASK_1]?.tilesetId == 2 }.map { it.index }
        assertTrue(trees.size > 10, "trees were placed")
        assertTrue(trees.all { it / 30 >= 11 }, "none on or next to the rock")
        val set = trees.toSet()
        assertTrue(trees.none { (it + 1) in set || (it + 30) in set }, "a free tile between trees")
    }

    @Test
    fun `unknown names are reported by name`() {
        assertThrows<RecipeException> { MapCompiler.compile(recipe("""{"op": "rect", "terrain": "lava", "x": 0, "y": 0, "w": 2, "h": 2}"""), index) }
        assertThrows<RecipeException> { MapCompiler.compile(recipe("""{"op": "prop", "prop": "dragon", "x": 2, "y": 2}"""), index) }
    }

    private fun seaCells(m: com.morgana.domain.map.MapDefinition) = m.cells.count { it.attribute == TileAttribute.Blocked }

    @Test
    fun `a noise op paints organic bands, the same every time, and a different seed changes them`() {
        val ops = """{"op": "noise", "scale": 10, "bands": [{"below": 0.3, "terrain": "rock"}]}"""
        val a = MapCompiler.compile(recipe(ops, 60, 60), index)
        assertEquals(a, MapCompiler.compile(recipe(ops, 60, 60), index))
        val rock = seaCells(a)
        assertTrue(rock in 300..1800, "about a third of 3600 tiles is rock, was $rock")
        val other = MapCompiler.compile(recipe(ops.replace("\"scale\"", "\"salt\": 3, \"scale\""), 60, 60), index)
        assertNotEquals(a, other)
    }

    @Test
    fun `minArea undoes small patches and onlyOn limits where a band paints`() {
        val speckled = """{"op": "noise", "scale": 4, "octaves": 5, "smooth": 0, "bands": [{"below": 0.3, "terrain": "rock"}]}"""
        assertTrue(seaCells(MapCompiler.compile(recipe(speckled, 60, 60), index)) > 0)
        val cleaned = speckled.replace("\"smooth\": 0,", "\"smooth\": 0, \"minArea\": 100000,")
        assertEquals(0, seaCells(MapCompiler.compile(recipe(cleaned, 60, 60), index)), "every patch is smaller than the minimum, so none stays")
        val restricted = MapCompiler.compile(recipe("""{"op": "noise", "scale": 10, "onlyOn": ["sea"], "bands": [{"below": 0.9, "terrain": "rock"}]}"""), index)
        assertEquals(0, seaCells(restricted), "no cell was sea, so nothing painted")
    }

    @Test
    fun `a meandering path bends but still starts and ends where it was told`() {
        val straight = MapCompiler.compile(recipe("""{"op": "path", "terrain": "rock", "points": [[2, 15], [57, 15]], "width": 1}""", 60, 30), index)
        val bent = MapCompiler.compile(recipe("""{"op": "path", "terrain": "rock", "points": [[2, 15], [57, 15]], "width": 1, "meander": 6, "wavelength": 10}""", 60, 30), index)
        fun rows(m: com.morgana.domain.map.MapDefinition) = m.cells.withIndex().filter { it.value.attribute == TileAttribute.Blocked }.map { it.index / 60 }.toSet()
        assertEquals(setOf(15), rows(straight))
        assertTrue(rows(bent).size > 3, "the bent path crosses several rows: ${rows(bent)}")
        assertEquals(TileAttribute.Blocked, bent.cells[15 * 60 + 2].attribute)
        assertEquals(TileAttribute.Blocked, bent.cells[15 * 60 + 57].attribute)
    }

    @Test
    fun `a terrace raises plateaus with two-row cliff faces and walkable ramps`() {
        val ops = """{"op": "noise", "store": "elev", "scale": 18, "salt": 4, "bands": []},
            {"op": "terrace", "field": "elev", "set": "w", "thresholds": [0.5], "terrains": ["grass"], "rampTerrain": "grass", "rampEvery": 8}"""
        val built = MapCompiler.build(recipe(ops, 80, 80), index)
        val blocked = built.analysis.blocked
        val faces = (0 until 80).sumOf { y -> blocked[y].count { it == '#' } }
        assertTrue(faces > 20, "cliff faces were drawn, found $faces")
        // Every face tile has another face tile directly above or below it: the cornice and the plinth, never a single row.
        for (y in 0 until 80) for (x in 0 until 80) if (blocked[y][x] == '#') {
            val above = y > 0 && blocked[y - 1][x] == '#'
            val below = y < 79 && blocked[y + 1][x] == '#'
            assertTrue(above || below, "a lone face tile at ($x,$y)")
        }
        assertEquals(built.map, MapCompiler.build(recipe(ops, 80, 80), index).map, "deterministic")
        val noRamps = MapCompiler.build(recipe(ops.replace("\"rampTerrain\": \"grass\", \"rampEvery\": 8", "\"ramps\": false"), 80, 80), index)
        val rampCells = built.analysis.occupied.sumOf { r -> r.count { it == 'o' } } - noRamps.analysis.occupied.sumOf { r -> r.count { it == 'o' } }
        assertTrue(noRamps.analysis.blocked.sumOf { r -> r.count { it == '#' } } > faces, "ramps open gaps in the faces")
        assertTrue(rampCells >= 0)
    }

    @Test
    fun `a terrace needs its field and one terrain per level`() {
        assertThrows<RecipeException> { MapCompiler.compile(recipe("""{"op": "terrace", "field": "nope", "set": "w", "thresholds": [0.5], "terrains": ["grass"]}"""), index) }
        assertThrows<RecipeException> {
            MapCompiler.compile(recipe("""{"op": "noise", "store": "e", "bands": []}, {"op": "terrace", "field": "e", "set": "w", "thresholds": [0.5, 0.7], "terrains": ["grass"]}"""), index)
        }
    }

    @Test
    fun `the analysis lists terrain, blocked tiles and spawns in the same grid as the map`() {
        val built = MapCompiler.build(recipe("""{"op": "rect", "terrain": "rock", "x": 3, "y": 4, "w": 5, "h": 2}, {"op": "spawn", "npcId": 4, "x": 1, "y": 1}"""), index)
        val a = built.analysis
        assertEquals(30, a.terrain.size)
        assertEquals(30, a.terrain[0].length)
        assertEquals("rock", a.legend.getValue(a.terrain[4][3].toString()))
        assertEquals('#', a.blocked[4][3])
        assertEquals('.', a.blocked[0][0])
        assertEquals(listOf(listOf(4, 1, 1)), a.spawns)
    }

    @Test
    fun `a tilted noise field makes a coast, with rock on one side and land on the other and a wandering shoreline`() {
        val m = MapCompiler.build(recipe("""{"op": "noise", "scale": 12, "salt": 2, "tiltX": 2.0, "bands": [{"below": 0.4, "terrain": "rock"}]}""", 80, 60), index)
        val b = m.analysis.blocked
        val shore = (0 until 60).map { y -> b[y].lastIndexOf('#') }
        assertTrue(shore.all { it < 60 }, "no rock on the far east side: $shore")
        assertTrue(b.sumOf { r -> r.substring(0, 20).count { it == '#' } } > b.sumOf { r -> r.substring(60).count { it == '#' } } * 5, "rock lies mostly on the west")
        assertTrue(shore.toSet().size > 4, "the shoreline wanders instead of running straight: ${shore.toSet()}")
    }
}
