package com.morgana.mapbuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The tile lookup a tileset export wrote (`tilesets.index.json`): where every named tile, prop and autotile cell sits. */
class TileIndex(val tiles: Map<String, Place>, val props: Map<String, Place>, val autotiles: Map<String, Auto>) {

    /** Sheet [tilesetId], grid cell ([x], [y]) in tiles, [w] x [h] tiles, plus optional default collision metadata. */
    data class Place(
        val tilesetId: Int,
        val x: Int,
        val y: Int,
        val w: Int = 1,
        val h: Int = 1,
        val walkable: Boolean = false,
        val passableCols: List<Int> = emptyList()
    )

    /** An autotile sheet: for each canonical 8-neighbour mask the grid cell that draws it. */
    data class Auto(val tilesetId: Int, val cells: Map<Int, Pair<Int, Int>>)

    companion object {
        fun parse(json: String): TileIndex {
            val root = Json.parseToJsonElement(json).jsonObject
            fun places(key: String): Map<String, Place> = (root[key] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val o = v.jsonObject
                Place(
                    o.getValue("tilesetId").jsonPrimitive.int, o.getValue("x").jsonPrimitive.int, o.getValue("y").jsonPrimitive.int,
                    o["w"]?.jsonPrimitive?.int ?: 1, o["h"]?.jsonPrimitive?.int ?: 1,
                    o["walkable"]?.jsonPrimitive?.boolean ?: false,
                    o["passableCols"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList()
                )
            }
            val autos = (root["autotiles"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val o = v.jsonObject
                Auto(o.getValue("tilesetId").jsonPrimitive.int, o.getValue("cells").jsonObject.entries.associate { (mask, xy) ->
                    val a = xy as JsonArray
                    mask.toInt() to (a[0].jsonPrimitive.int to a[1].jsonPrimitive.int)
                })
            }
            return TileIndex(places("tiles"), places("props"), autos)
        }
    }
}
