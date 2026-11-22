package com.morgana.persistence

import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.map.WeatherType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class TiledProperty(
    val name: String,
    val type: String = "string",
    val value: JsonElement
)

@Serializable
data class TiledLayer(
    val id: Int,
    val name: String,
    val type: String = "tilelayer",
    val width: Int,
    val height: Int,
    val x: Int = 0,
    val y: Int = 0,
    val opacity: Float = 1.0f,
    val visible: Boolean = true,
    val data: List<Int> = emptyList()
)

@Serializable
data class TiledTilesetRef(
    val firstgid: Int = 1,
    val name: String = "morgana_retro_tileset",
    val tilewidth: Int = 32,
    val tileheight: Int = 32,
    val columns: Int = 16,
    val image: String = "tileset.png"
)

@Serializable
data class TiledMapDocument(
    val width: Int,
    val height: Int,
    val tilewidth: Int = 32,
    val tileheight: Int = 32,
    val orientation: String = "orthogonal",
    val renderorder: String = "right-down",
    val type: String = "map",
    val version: String = "1.10",
    val layers: List<TiledLayer> = emptyList(),
    val tilesets: List<TiledTilesetRef> = listOf(TiledTilesetRef()),
    val properties: List<TiledProperty> = emptyList()
)

/**
 * Bi-directional converter between Morgana ORPG [MapDefinition] and standard Tiled Map (.json / .tmj) format.
 * Enables community creators to design maps in the Tiled Map Editor and import them directly into the engine.
 */
object TiledMapConverter {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    const val DEFAULT_TILESET_COLS = 16

    /** Polymorphic JSON used for the full-fidelity `morgana*` map properties (same `_type` discriminator as content files). */
    private val dataJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "_type"
    }

    @Serializable
    private data class AttributeEntry(val x: Int, val y: Int, val attribute: TileAttribute)

    /**
     * Number shown in the Tiled `COLLISION` layer. It is a visual/collision aid for Tiled users; the complete,
     * parameterized attribute data travels in the `morganaAttributes` map property so nothing is lost on round trips.
     */
    internal fun attributeCode(attr: TileAttribute): Int = when (attr) {
        is TileAttribute.None -> 0
        is TileAttribute.Blocked -> 1
        is TileAttribute.DirectionalBlock -> 2
        is TileAttribute.Warp -> 3
        is TileAttribute.ResourceNode -> 4
        is TileAttribute.ShopTile -> 5
        is TileAttribute.BankTile -> 6
        is TileAttribute.HealTrap -> 7
        is TileAttribute.Slide -> 8
        is TileAttribute.LightSource -> 9
        is TileAttribute.KeyDoor -> 10
        else -> 0 // item_spawn, npc_avoid, sound_emitter have no collision meaning; they live in morganaAttributes
    }

    /** Attribute for a code a Tiled user typed by hand (only parameter-free attributes can be created that way). */
    private fun simpleAttribute(code: Int): TileAttribute = when (code) {
        1 -> TileAttribute.Blocked
        6 -> TileAttribute.BankTile
        else -> TileAttribute.None
    }

    fun exportToTiled(map: MapDefinition, tilesetColumns: Int = DEFAULT_TILESET_COLS): TiledMapDocument {
        var layerIdCounter = 1
        val layers = mutableListOf<TiledLayer>()

        // 1. Export standard visual tile layers
        for (mapLayer in MapLayer.entries) {
            val hasAnyTile = map.cells.any { it.layers.containsKey(mapLayer) }
            if (!hasAnyTile && mapLayer != MapLayer.GROUND) continue

            val data = ArrayList<Int>(map.width * map.height)
            for (cell in map.cells) {
                val placement = cell.layers[mapLayer]
                if (placement != null) {
                    val gid = (placement.srcTileY * tilesetColumns) + placement.srcTileX + 1
                    data.add(gid)
                } else {
                    data.add(0)
                }
            }

            layers.add(
                TiledLayer(
                    id = layerIdCounter++,
                    name = mapLayer.name,
                    width = map.width,
                    height = map.height,
                    opacity = if (mapLayer == MapLayer.ROOF) 0.85f else 1.0f,
                    data = data
                )
            )
        }

        // 2. Export Collision & Attribute layer (codes: see attributeCode; full data is in `morganaAttributes`)
        val attrData = ArrayList<Int>(map.width * map.height)
        for (cell in map.cells) attrData.add(attributeCode(cell.attribute))
        layers.add(
            TiledLayer(
                id = layerIdCounter++,
                name = "COLLISION",
                width = map.width,
                height = map.height,
                opacity = 0.5f,
                visible = false,
                data = attrData
            )
        )

        // 3. Export Map Metadata properties
        val props = listOf(
            TiledProperty("morganaMapId", "int", JsonPrimitive(map.id)),
            TiledProperty("morganaMapName", "string", JsonPrimitive(map.name)),
            TiledProperty("morganaRevision", "int", JsonPrimitive(map.revision)),
            TiledProperty("morganaMoral", "string", JsonPrimitive(map.moral.name)),
            TiledProperty("bgmTrackId", "string", JsonPrimitive(map.bgmTrackId)),
            TiledProperty("weather", "string", JsonPrimitive(map.weather.name)),
            TiledProperty("ambientLightHex", "string", JsonPrimitive(map.ambientLightHex)),
            TiledProperty("isIndoors", "bool", JsonPrimitive(map.isIndoors)),
            TiledProperty("neighborNorth", "int", JsonPrimitive(map.neighbors.northMapId)),
            TiledProperty("neighborSouth", "int", JsonPrimitive(map.neighbors.southMapId)),
            TiledProperty("neighborEast", "int", JsonPrimitive(map.neighbors.eastMapId)),
            TiledProperty("neighborWest", "int", JsonPrimitive(map.neighbors.westMapId)),
            TiledProperty("morganaAttributes", "string", JsonPrimitive(encodeAttributes(map))),
            TiledProperty(
                "morganaNpcSpawns", "string",
                JsonPrimitive(dataJson.encodeToString(ListSerializer(MapNpcSpawnSlot.serializer()), map.npcSpawns))
            ),
            TiledProperty("morganaEventIds", "string", JsonPrimitive(dataJson.encodeToString(ListSerializer(Int.serializer()), map.eventIds)))
        )

        return TiledMapDocument(
            width = map.width,
            height = map.height,
            tilewidth = map.tileSize,
            tileheight = map.tileSize,
            layers = layers,
            properties = props
        )
    }

    private fun encodeAttributes(map: MapDefinition): String {
        val entries = map.cells.mapIndexedNotNull { idx, cell ->
            if (cell.attribute is TileAttribute.None) null
            else AttributeEntry(idx % map.width, idx / map.width, cell.attribute)
        }
        return dataJson.encodeToString(ListSerializer(AttributeEntry.serializer()), entries)
    }

    fun exportToTiledJson(map: MapDefinition, tilesetColumns: Int = DEFAULT_TILESET_COLS): String {
        val doc = exportToTiled(map, tilesetColumns)
        return json.encodeToString(TiledMapDocument.serializer(), doc)
    }

    fun importFromTiled(doc: TiledMapDocument, tilesetColumns: Int = DEFAULT_TILESET_COLS): MapDefinition {
        val propMap = doc.properties.associate { it.name to it.value }

        val id = propMap["morganaMapId"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 1
        val name = propMap["morganaMapName"]?.let { (it as? JsonPrimitive)?.content } ?: "Imported Tiled Map"
        val revision = propMap["morganaRevision"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 1
        val moral = propMap["morganaMoral"]?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { MapMoral.valueOf(it) }.getOrNull() } ?: MapMoral.SAFE_ZONE
        val bgm = propMap["bgmTrackId"]?.let { (it as? JsonPrimitive)?.content } ?: "town_theme"
        val weather = propMap["weather"]?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { WeatherType.valueOf(it) }.getOrNull() } ?: WeatherType.NONE
        val ambient = propMap["ambientLightHex"]?.let { (it as? JsonPrimitive)?.content } ?: "#FFFFFF"
        val isIndoors = propMap["isIndoors"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false

        val nNorth = propMap["neighborNorth"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val nSouth = propMap["neighborSouth"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val nEast = propMap["neighborEast"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val nWest = propMap["neighborWest"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0

        val totalCells = doc.width * doc.height
        val cellLayers = Array(totalCells) { mutableMapOf<MapLayer, TilePlacement>() }
        val cellAttrs = Array<TileAttribute>(totalCells) { TileAttribute.None }
        val storedAttrs: Map<Int, TileAttribute> = propMap["morganaAttributes"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { dataJson.decodeFromString(ListSerializer(AttributeEntry.serializer()), it) }.getOrNull() }
            .orEmpty()
            .filter { it.x in 0 until doc.width && it.y in 0 until doc.height }
            .associate { (it.y * doc.width + it.x) to it.attribute }
        var sawCollisionLayer = false

        for (layer in doc.layers) {
            val upperName = layer.name.uppercase()
            if (upperName == "COLLISION") {
                sawCollisionLayer = true
                for (idx in 0 until totalCells) {
                    val code = layer.data.getOrElse(idx) { 0 }
                    val stored = storedAttrs[idx]
                    // Unchanged in Tiled -> keep the full parameterized attribute; edited -> fall back to the simple code.
                    cellAttrs[idx] = if (stored != null && attributeCode(stored) == code) stored else simpleAttribute(code)
                }
                continue
            }

            val mapLayer = runCatching { MapLayer.valueOf(upperName) }.getOrNull() ?: continue
            layer.data.forEachIndexed { idx, gid ->
                if (gid > 0 && idx < totalCells) {
                    val tileIdx = gid - 1
                    val srcX = tileIdx % tilesetColumns
                    val srcY = tileIdx / tilesetColumns
                    cellLayers[idx][mapLayer] = TilePlacement(
                        tilesetId = 1,
                        srcTileX = srcX,
                        srcTileY = srcY,
                        autotileMode = if (srcX == 3 && srcY == 0) AutotileMode.ANIMATED_3_FRAME else AutotileMode.NONE,
                        animationFrames = if (srcX == 3 && srcY == 0) 3 else 1
                    )
                }
            }
        }

        if (!sawCollisionLayer) storedAttrs.forEach { (idx, attr) -> cellAttrs[idx] = attr }

        val npcSpawns = propMap["morganaNpcSpawns"]?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { dataJson.decodeFromString(ListSerializer(MapNpcSpawnSlot.serializer()), it) }.getOrNull() }
            .orEmpty()
        val eventIds = propMap["morganaEventIds"]?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { dataJson.decodeFromString(ListSerializer(Int.serializer()), it) }.getOrNull() }
            .orEmpty()

        val cells = List(totalCells) { idx ->
            MapCell(
                layers = cellLayers[idx].toMap(),
                attribute = cellAttrs[idx]
            )
        }

        return MapDefinition(
            id = id,
            name = name,
            revision = revision,
            width = doc.width,
            height = doc.height,
            tileSize = doc.tilewidth,
            moral = moral,
            bgmTrackId = bgm,
            weather = weather,
            ambientLightHex = ambient,
            isIndoors = isIndoors,
            neighbors = MapNeighborLinks(northMapId = nNorth, southMapId = nSouth, eastMapId = nEast, westMapId = nWest),
            cells = cells,
            npcSpawns = npcSpawns,
            eventIds = eventIds
        )
    }

    fun importFromTiledJson(jsonStr: String, tilesetColumns: Int = DEFAULT_TILESET_COLS): MapDefinition {
        val doc = json.decodeFromString(TiledMapDocument.serializer(), jsonStr)
        return importFromTiled(doc, tilesetColumns)
    }
}

