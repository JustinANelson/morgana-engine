package com.morgana.client.editor

import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks

/**
 * The map properties form as plain data: what the panel shows for a map, what is wrong with what was typed, and how typed
 * values turn into a changed map. No graphics, so the rules are unit-tested. Values are text so a half-typed number can sit in
 * a field; [problems] says what is wrong and [apply] is forgiving (bad values keep the map's current setting).
 */
data class MapPropertiesValues(
    val name: String,
    val moral: MapMoral,
    val bgm: String,
    val light: String,
    /** Interior (true) or exterior (false); interiors ignore weather and the day and night cycle. */
    val interior: Boolean,
    /** The weather zone the map belongs to (0 = default world zone). */
    val zone: String,
    val instanced: Boolean,
    val north: String,
    val south: String,
    val east: String,
    val west: String,
    /** Where the map's top left tile sits in a seamless world (`worldLayout`); both blank when it takes no part. */
    val worldX: String = "",
    val worldY: String = ""
)

object MapPropertiesForm {
    const val MAX_NAME = 40

    fun valuesOf(map: MapDefinition) = MapPropertiesValues(
        name = map.name,
        moral = map.moral,
        bgm = map.bgmTrackId,
        light = map.ambientLightHex,
        interior = map.isIndoors,
        zone = map.zoneId.toString(),
        instanced = map.instanced,
        north = map.neighbors.northMapId.toString(),
        south = map.neighbors.southMapId.toString(),
        east = map.neighbors.eastMapId.toString(),
        west = map.neighbors.westMapId.toString(),
        worldX = map.worldX?.toString() ?: "",
        worldY = map.worldY?.toString() ?: ""
    )

    /**
     * What is wrong with [v] for map [mapId]. [knownMapIds] are the maps that exist (empty when the list is not loaded yet, in
     * which case neighbour ids are not checked against it). A border id of 0 means "no neighbour". [knownZoneIds] are the
     * weather zones the game defines (null when it defines none, in which case the zone is not checked).
     */
    fun problems(mapId: Int, v: MapPropertiesValues, knownMapIds: Set<Int>, knownZoneIds: Set<Int>? = null): List<String> {
        val out = ArrayList<String>()
        if (v.name.isBlank()) out += "Name cannot be empty"
        else if (v.name.trim().length > MAX_NAME) out += "Name can be at most $MAX_NAME characters"
        if (v.bgm.isBlank()) out += "Music track cannot be empty"
        val zone = v.zone.trim().toIntOrNull()
        if (zone == null || zone < 0) out += "Weather zone must be a whole number (0 for the default zone)"
        else if (knownZoneIds != null && zone != 0 && zone !in knownZoneIds) out += "No weather zone $zone"
        if (!HEX_COLOR.matches(v.light.trim())) out += "Ambient light must look like #RRGGBB"
        for ((label, raw) in listOf("North" to v.north, "South" to v.south, "East" to v.east, "West" to v.west)) {
            val id = raw.trim().toIntOrNull()
            when {
                id == null -> out += "$label neighbour must be a whole number (0 for none)"
                id < 0 -> out += "$label neighbour cannot be negative"
                id == mapId -> out += "$label neighbour cannot be this map"
                id != 0 && knownMapIds.isNotEmpty() && id !in knownMapIds -> out += "$label neighbour: no map $id"
            }
        }
        if (v.instanced && listOf(v.north, v.south, v.east, v.west).any { (it.trim().toIntOrNull() ?: 0) != 0 }) {
            out += "An instanced map cannot have neighbouring maps"
        }
        val wx = v.worldX.trim()
        val wy = v.worldY.trim()
        if ((wx.isEmpty()) != (wy.isEmpty())) out += "World position needs both x and y (or neither)"
        else if (wx.isNotEmpty() && (wx.toIntOrNull() == null || wy.toIntOrNull() == null)) out += "World position must be whole numbers"
        else if (wx.isNotEmpty() && v.instanced) out += "An instanced map cannot have a world position"
        return out
    }

    /** Applies [v] to [map]; anything invalid leaves that setting as it was. */
    fun apply(map: MapDefinition, v: MapPropertiesValues): MapDefinition {
        fun id(raw: String, current: Int): Int = raw.trim().toIntOrNull()?.takeIf { it >= 0 && it != map.id } ?: current
        return map.copy(
            name = v.name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: map.name,
            moral = v.moral,
            bgmTrackId = v.bgm.trim().ifEmpty { map.bgmTrackId },
            ambientLightHex = v.light.trim().takeIf { HEX_COLOR.matches(it) } ?: map.ambientLightHex,
            isIndoors = v.interior,
            zoneId = v.zone.trim().toIntOrNull()?.takeIf { it >= 0 } ?: map.zoneId,
            instanced = v.instanced,
            worldX = if (v.worldX.isBlank() && v.worldY.isBlank()) null else v.worldX.trim().toIntOrNull() ?: map.worldX,
            worldY = if (v.worldX.isBlank() && v.worldY.isBlank()) null else v.worldY.trim().toIntOrNull() ?: map.worldY,
            neighbors = MapNeighborLinks(
                northMapId = id(v.north, map.neighbors.northMapId),
                southMapId = id(v.south, map.neighbors.southMapId),
                eastMapId = id(v.east, map.neighbors.eastMapId),
                westMapId = id(v.west, map.neighbors.westMapId)
            )
        )
    }

    /** The smallest unused map id (at least 1), for the "new map" button. */
    fun nextFreeMapId(knownMapIds: Collection<Int>): Int {
        val used = knownMapIds.toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    /** Whether a width and height are a legal map size. */
    fun sizeProblem(width: String, height: String): String? {
        val w = width.trim().toIntOrNull()
        val h = height.trim().toIntOrNull()
        if (w == null || h == null) return "Width and height must be whole numbers"
        if (w !in MIN_SIZE..MAX_SIZE || h !in MIN_SIZE..MAX_SIZE) return "Width and height must be between $MIN_SIZE and $MAX_SIZE"
        return null
    }

    const val MIN_SIZE = 5
    const val MAX_SIZE = 256

    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

}
