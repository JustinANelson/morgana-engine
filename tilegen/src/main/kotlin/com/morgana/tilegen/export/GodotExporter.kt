package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.autotile.AutotileBitmask
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileSheet

/**
 * Generates Godot 4 `.tres` TileSet resource files with `TileSetAtlasSource`
 * and terrain peering-bit metadata.
 */
object GodotExporter {

    /**
     * Generates a Godot 4 `.tres` TileSet resource for a named [tiles] sheet.
     */
    fun toNamedTres(
        imageFileName: String,
        tileSize: Int,
        columns: Int,
        tiles: List<NamedTile>
    ): String {
        return buildString {
            appendLine("[gd_resource type=\"TileSet\" format=3]")
            appendLine()
            appendLine("[ext_resource type=\"Texture2D\" path=\"res://$imageFileName\" id=\"1_tex\"]")
            appendLine()
            appendLine("[sub_resource type=\"TileSetAtlasSource\" id=\"TileSetAtlasSource_1\"]")
            appendLine("texture = ExtResource(\"1_tex\")")
            appendLine("texture_region_size = Vector2i($tileSize, $tileSize)")
            tiles.forEachIndexed { idx, _ ->
                val col = idx % columns
                val row = idx / columns
                appendLine("$col:$row/0 = 0")
            }
            appendLine()
            appendLine("[resource]")
            appendLine("tile_size = Vector2i($tileSize, $tileSize)")
            appendLine("sources/0 = SubResource(\"TileSetAtlasSource_1\")")
        }
    }

    /**
     * Generates a Godot 4 `.tres` TileSet resource with terrain set 0 and peering bits for [autotile].
     */
    fun toAutotileTres(
        name: String,
        imageFileName: String,
        autotile: AutotileSheet
    ): String {
        val ts = autotile.tileSize
        val terrainMode = if (autotile.mode == AutotileMode.BLOB_47) 0 else 2 // 0=Match Corners and Sides, 2=Match Sides
        return buildString {
            appendLine("[gd_resource type=\"TileSet\" format=3]")
            appendLine()
            appendLine("[ext_resource type=\"Texture2D\" path=\"res://$imageFileName\" id=\"1_tex\"]")
            appendLine()
            appendLine("[sub_resource type=\"TileSetAtlasSource\" id=\"TileSetAtlasSource_1\"]")
            appendLine("texture = ExtResource(\"1_tex\")")
            appendLine("texture_region_size = Vector2i($ts, $ts)")
            for (entry in autotile.entries) {
                val prefix = "${entry.col}:${entry.row}/0"
                appendLine("$prefix = 0")
                appendLine("$prefix/terrain_set = 0")
                appendLine("$prefix/terrain = 0")
                for (bit in peeringBitsForMask(entry.mask, autotile.mode)) {
                    appendLine("$prefix/terrains_peering_bit/$bit = 0")
                }
            }
            appendLine()
            appendLine("[resource]")
            appendLine("tile_size = Vector2i($ts, $ts)")
            appendLine("terrain_set_0/mode = $terrainMode")
            appendLine("terrain_set_0/terrain_0/name = \"$name\"")
            appendLine("terrain_set_0/terrain_0/color = Color(0.27, 0.67, 0.4, 1)")
            appendLine("sources/0 = SubResource(\"TileSetAtlasSource_1\")")
        }
    }

    private fun peeringBitsForMask(mask: Int, mode: AutotileMode): List<String> {
        val bits = mutableListOf<String>()
        if (mode == AutotileMode.BLOB_47) {
            if ((mask and AutotileBitmask.N) != 0) bits += "top_side"
            if ((mask and AutotileBitmask.NE) != 0) bits += "top_right_corner"
            if ((mask and AutotileBitmask.E) != 0) bits += "right_side"
            if ((mask and AutotileBitmask.SE) != 0) bits += "bottom_right_corner"
            if ((mask and AutotileBitmask.S) != 0) bits += "bottom_side"
            if ((mask and AutotileBitmask.SW) != 0) bits += "bottom_left_corner"
            if ((mask and AutotileBitmask.W) != 0) bits += "left_side"
            if ((mask and AutotileBitmask.NW) != 0) bits += "top_left_corner"
        } else {
            if ((mask and AutotileBitmask.CARDINAL_N) != 0) bits += "top_side"
            if ((mask and AutotileBitmask.CARDINAL_E) != 0) bits += "right_side"
            if ((mask and AutotileBitmask.CARDINAL_S) != 0) bits += "bottom_side"
            if ((mask and AutotileBitmask.CARDINAL_W) != 0) bits += "left_side"
        }
        return bits
    }
}
