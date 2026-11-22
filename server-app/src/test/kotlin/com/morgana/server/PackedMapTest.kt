package com.morgana.server

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** Maps travel as a palette of distinct cells plus run-length indices: lossless, much smaller, and safe to decode. */
class PackedMapTest {

    /**
     * Hand-shaped sample maps like a real game's (not tied to any game in `games/`): grass with a walled border, a pond,
     * a road, scattered decorations on the mask layers and a few warps. Map 1 is a hub linked to maps 2 to 5.
     */
    private fun sampleMaps(): Map<String, Map<Int, MapDefinition>> {
        fun place(x: Int, y: Int) = TilePlacement(srcTileX = x, srcTileY = y)
        fun sample(id: Int, w: Int, h: Int, links: MapNeighborLinks = MapNeighborLinks()): MapDefinition {
            val rnd = Random(id.toLong())
            val cells = List(w * h) { i ->
                val x = i % w
                val y = i / w
                val layers = linkedMapOf(MapLayer.GROUND to place(if (rnd.nextInt(10) == 0) 1 else 0, 0))
                var attr: TileAttribute = TileAttribute.None
                when {
                    x == 0 || y == 0 || x == w - 1 || y == h - 1 -> { layers[MapLayer.MASK_1] = place(4, 1); attr = TileAttribute.Blocked }
                    x in 6..12 && y in 4..8 -> { layers[MapLayer.GROUND] = place(3, 0); attr = TileAttribute.Blocked }
                    y == h / 2 -> layers[MapLayer.GROUND] = place(2, 0)
                    rnd.nextInt(25) == 0 -> layers[MapLayer.MASK_1] = place(5 + rnd.nextInt(3), 2)
                }
                if (x == w - 2 && y == 2) attr = TileAttribute.Warp(id % 5 + 1, 3, 3)
                MapCell(layers, attr)
            }
            return MapDefinition(id = id, name = "Sample $id", width = w, height = h, neighbors = links, cells = cells)
        }
        val hub = sample(1, 48, 40, MapNeighborLinks(northMapId = 2, southMapId = 3, eastMapId = 4, westMapId = 5))
        return mapOf("sample" to (listOf(hub) + (2..5).map { sample(it, 40, 32) }).associateBy { it.id })
    }

    private fun roundTrip(map: MapDefinition): MapDefinition =
        (PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(ServerPacket.MapDefinitionUpdated(map))) as ServerPacket.MapDefinitionUpdated).map

    /** The size every map used to cost on the wire: each cell written out in full. */
    private fun naiveBytes(map: MapDefinition) = Json { encodeDefaults = true }.encodeToString(MapDefinition.serializer(), map).length

    private fun noisyMap(size: Int, distinct: Int, seed: Long): MapDefinition {
        val rnd = Random(seed)
        val palette = (0 until distinct).map { MapCell(mapOf(MapLayer.GROUND to TilePlacement(srcTileX = it % 16, srcTileY = it / 16))) }
        return MapDefinition(id = 1, name = "noise", width = size, height = size, cells = List(size * size) { palette[rnd.nextInt(distinct)] })
    }

    private fun hubSnapshot(): ServerPacket.MapStateSnapshot {
        val maps = sampleMaps().getValue("sample")
        val hub = maps.getValue(1)
        val neighbours = listOf(hub.neighbors.northMapId, hub.neighbors.southMapId, hub.neighbors.eastMapId, hub.neighbors.westMapId)
            .filter { it > 0 }.associateWith { maps.getValue(it) }
        return ServerPacket.MapStateSnapshot(activeMap = hub, neighborMaps = neighbours)
    }

    @Test
    fun `every sample map survives the wire unchanged in JSON and CBOR`() {
        for ((game, maps) in sampleMaps()) for ((id, map) in maps) {
            assertEquals(map, roundTrip(map), "$game map $id (JSON)")
            val cbor = PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(ServerPacket.MapDefinitionUpdated(map)))
            assertEquals(map, (cbor as ServerPacket.MapDefinitionUpdated).map, "$game map $id (CBOR)")
        }
    }

    @Test
    fun `the weather zone and instanced flag survive the wire`() {
        val map = MapDefinition(id = 7, name = "Cave", isIndoors = true, zoneId = 3, instanced = true)
        val back = roundTrip(map)
        assertEquals(3, back.zoneId)
        assertTrue(back.instanced)
        assertTrue(back.isIndoors)
        assertEquals(map, back)
    }

    @Test
    fun `snapshots with neighbours round trip with every map intact`() {
        val packet = hubSnapshot()
        assertTrue(packet.neighborMaps.size >= 2)
        val back = PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(packet)) as ServerPacket.MapStateSnapshot
        assertEquals(packet.activeMap, back.activeMap)
        assertEquals(packet.neighborMaps, back.neighborMaps)
    }

    @Test
    fun `a realistic map costs a small fraction of its old wire size`() {
        for ((game, maps) in sampleMaps()) for ((id, map) in maps) {
            val packed = PacketCodec.encodeServerJson(ServerPacket.MapDefinitionUpdated(map)).length
            val naive = naiveBytes(map)
            assertTrue(packed * 10 < naive, "$game map $id: $packed bytes packed vs $naive naive")
        }
    }

    @Test
    fun `a hub snapshot with four neighbours is far below the old half megabyte`() {
        val size = PacketCodec.encodeServerJson(hubSnapshot()).length
        println("SIZE hub snapshot: $size bytes")
        assertTrue(size < 40_000, "snapshot is $size bytes")
    }

    @Test
    fun `packing is deterministic`() {
        val packet = ServerPacket.MapDefinitionUpdated(sampleMaps().getValue("sample").getValue(1))
        assertEquals(PacketCodec.encodeServerJson(packet), PacketCodec.encodeServerJson(packet))
    }

    @Test
    fun `the largest allowed map now fits in one frame even when it is noisy`() {
        val frameCap = 2 * 1024 * 1024
        val noisy = noisyMap(256, distinct = 8, seed = 7)
        val noisyBytes = PacketCodec.encodeServerJson(ServerPacket.MapDefinitionUpdated(noisy)).length
        println("SIZE noisy 256x256: $noisyBytes bytes")
        assertTrue(noisyBytes < frameCap, "256x256 with 8 random tile kinds: $noisyBytes bytes")
        assertEquals(noisy, roundTrip(noisy))

        val plain = MapDefinition(id = 2, name = "plain", width = 256, height = 256)
        val plainBytes = PacketCodec.encodeServerJson(ServerPacket.MapDefinitionUpdated(plain)).length
        assertTrue(plainBytes < 2_000, "a uniform 256x256 map is $plainBytes bytes")
        assertEquals(plain, roundTrip(plain))
    }

    @Test
    fun `cells with different attributes stay distinct`() {
        val a = MapCell(attribute = TileAttribute.Blocked)
        val b = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 3))
        val c = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 4))
        val pattern = listOf(a, b, c, a, MapCell())
        val map = MapDefinition(id = 3, name = "mixed", width = 5, height = 5, cells = List(25) { pattern[it % 5] })
        assertEquals(map, roundTrip(map))
    }

    // ---- hostile input: the decoder must refuse without allocating what the packet claims ----

    private fun tampered(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = MapDefinition(id = 9, name = "t", width = 5, height = 5)
        val root = Json.parseToJsonElement(PacketCodec.encodeServerJson(ServerPacket.MapDefinitionUpdated(map))).jsonObject.toMutableMap()
        val inner = root.getValue("map").jsonObject.toMutableMap()
        change(inner)
        root["map"] = JsonObject(inner)
        return JsonObject(root).toString()
    }

    private fun runs(vararg v: Int) = JsonArray(v.map { JsonPrimitive(it) })

    private fun rejects(change: (MutableMap<String, JsonElement>) -> Unit) {
        assertThrows(SerializationException::class.java) { PacketCodec.decodeServerJson(tampered(change)) }
    }

    @Test
    fun `runs that do not add up to the map size are rejected`() {
        rejects { it["runs"] = runs(24, 0) }
        rejects { it["runs"] = runs(26, 0) }
    }

    @Test
    fun `a claimed gigantic run is rejected before any allocation`() {
        rejects { it["runs"] = runs(Int.MAX_VALUE, 0, Int.MAX_VALUE, 0) }
    }

    @Test
    fun `bad palette indices, empty or negative runs and odd run lists are rejected`() {
        rejects { it["runs"] = runs(25, 5) }
        rejects { it["runs"] = runs(25, -1) }
        rejects { it["runs"] = runs(0, 0, 25, 0) }
        rejects { it["runs"] = runs(-5, 0, 30, 0) }
        rejects { it["runs"] = runs(25) }
    }

    @Test
    fun `sizes outside the map limits are rejected`() {
        rejects { it["width"] = JsonPrimitive(100_000) }
        rejects { it["height"] = JsonPrimitive(2) }
        rejects { it["tileSize"] = JsonPrimitive(48) }
    }
}
