package com.morgana.tilegen.export

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.spec.RenderedTileset
import com.morgana.tilegen.spec.TilesetSpecRenderer
import com.morgana.tilegen.structure.GeneratedProp
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders tileset spec files and writes them into a game's `assets/tilesets/<id>.png` as sheets the engine can draw, plus
 * `tilesets.index.json` next to `game.json`, so maps (written by hand, by the `F1` editors or by a tool) can look a tile up by
 * name instead of by sheet coordinates.
 *
 * It owns the ids from [firstId] upward and nothing below: tilesets below [firstId] are never touched. Re-running replaces
 * the ids the previous index listed (and removes owned sheets that no spec produces any more), so do not hand-edit those.
 * Output is deterministic for the same specs.
 *
 * Per spec: one **sheet** (1 x 1 tiles in reading order, `columns` wide, then multi-tile props packed below), plus one sheet
 * per **autotile** (its own layout; the index lists the grid cell of every neighbour mask).
 */
object GameTilesetExporter {

    /** Where one named thing sits: sheet [tilesetId], grid cell ([x], [y]) in tiles, [w] x [h] tiles. */
    data class Placement(val tilesetId: Int, val x: Int, val y: Int, val w: Int = 1, val h: Int = 1)

    data class AutotileIndex(val tilesetId: Int, val mode: AutotileMode, val columns: Int, val cells: Map<Int, Pair<Int, Int>>)

    data class Result(
        val written: List<Path>,
        val removed: List<Path>,
        val tiles: Map<String, Placement>,
        val props: Map<String, Placement>,
        val autotiles: Map<String, AutotileIndex>,
        val nextFreeId: Int
    )

    /** Renders each of [specFiles] (JSON) in order and writes the owned tilesets into [gameDir]; ids start at [firstId]. */
    fun export(gameDir: Path, specFiles: List<Path>, firstId: Int = 100): Result {
        require(firstId >= 2) { "firstId must leave tileset 1 free for the built-in tileset" }
        val tilesetsDir = gameDir.resolve("assets").resolve("tilesets")
        Files.createDirectories(tilesetsDir)
        val indexFile = gameDir.resolve("tilesets.index.json")
        val previous = if (Files.isRegularFile(indexFile)) Regex("\"ownedIds\"\\s*:\\s*\\[([^\\]]*)]").find(Files.readString(indexFile))
            ?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList() else emptyList()

        var nextId = firstId
        val written = mutableListOf<Path>()
        val tiles = linkedMapOf<String, Placement>()
        val props = linkedMapOf<String, Placement>()
        val autos = linkedMapOf<String, AutotileIndex>()
        val owned = mutableListOf<Int>()

        fun write(img: TileImage): Int {
            val id = nextId++
            val file = tilesetsDir.resolve("$id.png")
            PngEncoder.writeToFile(img, file)
            written.add(file)
            owned += id
            return id
        }

        for (specFile in specFiles) {
            val rendered = TilesetSpecRenderer.renderJson(Files.readString(specFile))
            val spec = rendered.spec
            val columns = spec.columns
            val tileSize = spec.tileSize
            val multi = rendered.props.filterValues { it.widthTiles != 1 || it.heightTiles != 1 }
            val isSliceOfMulti = { name: String -> multi.keys.any { Regex("^" + Regex.escape(it) + "_\\d+_\\d+$").matches(name) } }
            val units = rendered.tiles.filterNot { isSliceOfMulti(it.name) }

            // Single tiles in reading order. An animated tile's frames (`name_f0`, `name_f1`, ...) must sit side by side, so a
            // group that would run past the end of a row starts on the next one.
            val unitCells = ArrayList<Pair<Int, Int>>()
            var uc = 0
            var ur = 0
            var i0 = 0
            while (i0 < units.size) {
                val m = Regex("^(.*)_f0$").matchEntire(units[i0].name)
                var group = 1
                if (m != null) while (i0 + group < units.size && units[i0 + group].name == "${m.groupValues[1]}_f$group") group++
                if (uc + group > columns) { uc = 0; ur++ }
                repeat(group) { unitCells += uc++ to ur }
                if (uc >= columns) { uc = 0; ur++ }
                i0 += group
            }
            val unitRows = if (units.isEmpty()) 0 else (unitCells.last().second + 1)
            var curCol = 0
            var curRow = unitRows
            var band = 0
            val packed = mutableListOf<Triple<GeneratedProp, Int, Int>>()
            for (p in multi.values) {
                if (curCol + p.widthTiles > columns) { curRow += band; curCol = 0; band = 0 }
                packed += Triple(p, curCol, curRow)
                curCol += p.widthTiles
                band = maxOf(band, p.heightTiles)
            }
            val rows = (curRow + band).coerceAtLeast(1)
            if (units.isNotEmpty() || packed.isNotEmpty()) {
                val sheet = TileImage(columns * tileSize, rows * tileSize)
                units.forEachIndexed { i, t -> sheet.drawImage(t.image, 0, 0, tileSize, tileSize, unitCells[i].first * tileSize, unitCells[i].second * tileSize) }
                for ((p, c, r) in packed) sheet.drawImage(p.fullImage, 0, 0, p.fullImage.width, p.fullImage.height, c * tileSize, r * tileSize)
                val id = write(sheet)
                units.forEachIndexed { i, t -> tiles[t.name] = Placement(id, unitCells[i].first, unitCells[i].second) }
                for ((p, c, r) in packed) props[p.name] = Placement(id, c, r, p.widthTiles, p.heightTiles)
                for ((name, p) in rendered.props) if (name !in multi) {
                    units.indexOfFirst { it.name == p.slicedTiles.first().name }.takeIf { it >= 0 }?.let { i ->
                        props[name] = Placement(id, unitCells[i].first, unitCells[i].second)
                    }
                }
            }
            for ((name, auto) in rendered.autotiles) {
                val id = write(auto.image)
                autos[name] = AutotileIndex(id, auto.mode, auto.columns, auto.entries.associate { it.mask to (it.col to it.row) })
            }
        }

        val removed = mutableListOf<Path>()
        for (old in previous) if (old !in owned) {
            val f = tilesetsDir.resolve("$old.png")
            if (Files.deleteIfExists(f)) removed.add(f)
        }
        Files.writeString(indexFile, indexJson(firstId, owned, tiles, props, autos))
        return Result(written, removed, tiles, props, autos, nextId)
    }

    private fun placement(p: Placement) = "{\"tilesetId\": ${p.tilesetId}, \"x\": ${p.x}, \"y\": ${p.y}" +
        (if (p.w != 1 || p.h != 1) ", \"w\": ${p.w}, \"h\": ${p.h}" else "") + "}"

    private fun indexJson(
        firstId: Int,
        owned: List<Int>,
        tiles: Map<String, Placement>,
        props: Map<String, Placement>,
        autos: Map<String, AutotileIndex>
    ): String = buildString {
        append("{\n")
        append("  \"note\": \"Written by the tileset exporter. Owns tilesets from $firstId upward; do not edit by hand.\",\n")
        append("  \"firstId\": $firstId,\n")
        append("  \"ownedIds\": [${owned.joinToString(", ")}],\n")
        append("  \"tiles\": {\n")
        append(tiles.entries.joinToString(",\n") { "    \"${it.key}\": ${placement(it.value)}" })
        append("\n  },\n  \"props\": {\n")
        append(props.entries.joinToString(",\n") { "    \"${it.key}\": ${placement(it.value)}" })
        append("\n  },\n  \"autotiles\": {\n")
        append(autos.entries.joinToString(",\n") { (name, a) ->
            "    \"$name\": {\"tilesetId\": ${a.tilesetId}, \"mode\": \"${a.mode.name}\", \"columns\": ${a.columns}, \"cells\": {" +
                a.cells.entries.sortedBy { it.key }.joinToString(", ") { "\"${it.key}\": [${it.value.first}, ${it.value.second}]" } + "}}"
        })
        append("\n  }\n}\n")
    }
}
