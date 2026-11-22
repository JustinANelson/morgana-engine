package com.morgana.mapbuilder

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
}
