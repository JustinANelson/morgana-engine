package com.morgana.protocol

import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.WeatherType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wire form of a [MapDefinition]: a **palette** of the distinct cells plus **run-length encoded** palette indices.
 *
 * A normal map has a handful of distinct cells (a few terrain and decoration combinations) repeated hundreds of times,
 * so listing each cell once and then only "N cells of palette entry K" cuts a map to a few percent of its naive size,
 * and makes the 256 x 256 limit reachable within the frame cap. The conversion is lossless and deterministic
 * (palette in first-appearance order), and only the wire changes: servers and clients still handle plain
 * [MapDefinition]s, and the on-disk JSON format is untouched.
 */
@Serializable
private class PackedMapSurrogate(
    val id: Int,
    val name: String,
    val revision: Int,
    val width: Int,
    val height: Int,
    val tileSize: Int,
    val moral: MapMoral,
    val bgmTrackId: String,
    val weather: WeatherType,
    val weatherIntensity: Float,
    val ambientLightHex: String,
    val isIndoors: Boolean,
    val zoneId: Int = 0,
    val instanced: Boolean = false,
    val neighbors: MapNeighborLinks,
    /** The distinct cells, in order of first appearance. */
    val palette: List<MapCell>,
    /** Flat pairs `[count, paletteIndex, count, paletteIndex, ...]` covering every cell in row-major order. */
    val runs: List<Int>,
    val npcSpawns: List<MapNpcSpawnSlot>,
    val eventIds: List<Int>,
    val schemaVersion: Int,
    /** A floor template's recipe, so the editor can show and keep it (older peers ignore it). */
    val generator: FloorGenerator? = null
)

/**
 * Serializes a [MapDefinition] in its packed wire form (see [PackedMapSurrogate]). Use it on every packet field that
 * carries a map: `@Serializable(with = PackedMapSerializer::class)`.
 *
 * Decoding treats the input as untrusted: sizes are checked before anything is allocated, so a hostile or corrupt
 * packet fails with a [SerializationException] instead of exhausting memory or yielding a half-built map.
 */
object PackedMapSerializer : KSerializer<MapDefinition> {
    private val surrogate = PackedMapSurrogate.serializer()

    override val descriptor: SerialDescriptor = surrogate.descriptor

    override fun serialize(encoder: Encoder, value: MapDefinition) {
        encoder.encodeSerializableValue(surrogate, pack(value))
    }

    override fun deserialize(decoder: Decoder): MapDefinition =
        unpack(decoder.decodeSerializableValue(surrogate))

    private fun pack(map: MapDefinition): PackedMapSurrogate {
        val palette = ArrayList<MapCell>()
        val indexOf = HashMap<MapCell, Int>()
        val runs = ArrayList<Int>()
        var previous = -1
        for (cell in map.cells) {
            val index = indexOf.getOrPut(cell) { palette.add(cell); palette.size - 1 }
            if (index == previous) {
                runs[runs.size - 2] = runs[runs.size - 2] + 1
            } else {
                runs.add(1)
                runs.add(index)
                previous = index
            }
        }
        return PackedMapSurrogate(
            id = map.id, name = map.name, revision = map.revision, width = map.width, height = map.height,
            tileSize = map.tileSize, moral = map.moral, bgmTrackId = map.bgmTrackId, weather = map.weather,
            weatherIntensity = map.weatherIntensity, ambientLightHex = map.ambientLightHex, isIndoors = map.isIndoors,
            zoneId = map.zoneId, instanced = map.instanced,
            neighbors = map.neighbors, palette = palette, runs = runs, npcSpawns = map.npcSpawns,
            eventIds = map.eventIds, schemaVersion = map.schemaVersion, generator = map.generator
        )
    }

    private fun unpack(p: PackedMapSurrogate): MapDefinition {
        fun bad(why: String): Nothing = throw SerializationException("Invalid packed map ${p.id}: $why")
        if (p.width !in 5..256 || p.height !in 5..256) bad("size ${p.width}x${p.height} is outside 5..256")
        val total = p.width * p.height
        if (p.runs.size % 2 != 0) bad("runs must come in (count, index) pairs")
        if (p.palette.isEmpty() && total > 0) bad("empty palette")

        // Validate the whole run list before allocating the cell list: the sum is what decides memory use.
        var sum = 0L
        for (i in p.runs.indices step 2) {
            val count = p.runs[i]
            val index = p.runs[i + 1]
            if (count <= 0) bad("run length $count must be positive")
            if (index !in p.palette.indices) bad("palette index $index out of range 0..${p.palette.size - 1}")
            sum += count
            if (sum > total) bad("runs cover more than the $total cells of a ${p.width}x${p.height} map")
        }
        if (sum != total.toLong()) bad("runs cover $sum cells, expected $total")

        val cells = ArrayList<MapCell>(total)
        for (i in p.runs.indices step 2) {
            val cell = p.palette[p.runs[i + 1]]
            repeat(p.runs[i]) { cells.add(cell) }
        }
        return try {
            MapDefinition(
                id = p.id, name = p.name, revision = p.revision, width = p.width, height = p.height,
                tileSize = p.tileSize, moral = p.moral, bgmTrackId = p.bgmTrackId, weather = p.weather,
                weatherIntensity = p.weatherIntensity, ambientLightHex = p.ambientLightHex, isIndoors = p.isIndoors,
                zoneId = p.zoneId, instanced = p.instanced,
                neighbors = p.neighbors, cells = cells, npcSpawns = p.npcSpawns, eventIds = p.eventIds,
                schemaVersion = p.schemaVersion, generator = p.generator
            )
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "rejected by map rules")
        }
    }
}
