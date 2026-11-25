package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.spec.RenderedTileset
import com.morgana.tilegen.spec.TilesetSpecRenderer
import com.morgana.tilegen.structure.GeneratedProp
import com.morgana.tilegen.structure.PropKind
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
 * With [Layout.RPG_MAKER] each spec is a **pack** of role sheets instead (see [Role]).
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
        val nextFreeId: Int,
        val packs: List<Pack> = emptyList()
    )

    /** Renders each of [specFiles] (JSON) in order and writes the owned tilesets into [gameDir]; ids start at [firstId]. */
    fun export(gameDir: Path, specFiles: List<Path>, firstId: Int = 100, layout: Layout = Layout.PER_SPEC): Result {
        if (layout == Layout.RPG_MAKER) return exportPacks(gameDir, specFiles, firstId)
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


    // ---- RPG Maker / Time Fantasy layout ---------------------------------------------------------------------------

    /** How a spec's output is arranged: one sheet per spec plus one per autotile ([PER_SPEC]), or role sheets ([RPG_MAKER]). */
    enum class Layout { PER_SPEC, RPG_MAKER }

    /**
     * The sheet roles of the RPG Maker VX Ace / MV and Time Fantasy tileset format. A pack (one spec, one zone) owns
     * [PACK_STRIDE] ids; role [slot] is the offset from the pack's first id, so a pack's sheet ids never move when its
     * contents change. [columns] is the sheet width in tiles.
     */
    enum class Role(val slot: Int, val columns: Int, val title: String) {
        A1(0, 16, "animated water and its autotiles"),
        A2(1, 16, "ground tiles and autotiles"),
        A3(2, 16, "reserved (building walls and roofs)"),
        A4(3, 15, "walls and cliffs, 3 x 3 sets"),
        A5(4, 8, "floors and plain tiles"),
        B(5, 16, "nature: trees, bushes, rocks, mountains"),
        C(6, 16, "structures: ruins, gates, landmarks, bridges"),
        D(7, 16, "objects: containers, camp, signs, fences"),
        E(8, 16, "buildings")
    }

    const val PACK_STRIDE = 10

    /** The sheets one pack wrote, by role name (`A1`, `A2`, ...). */
    data class Pack(val spec: String, val firstId: Int, val sheets: Map<String, Int>)

    private fun propRole(kind: PropKind): Role = when (kind) {
        PropKind.OAK_TREE, PropKind.PINE_TREE, PropKind.LEAFY_TREE, PropKind.FRUIT_TREE, PropKind.EVERGREEN_TREE, PropKind.DEAD_TREE,
        PropKind.CHERRY_BLOSSOM_TREE, PropKind.BAMBOO_GROVE, PropKind.BUSH, PropKind.FLOWER_BUSH, PropKind.GRASS_TUFT,
        PropKind.WILDFLOWER_PATCH, PropKind.MOSS_MOUND, PropKind.BOULDER, PropKind.OLD_STUMP, PropKind.TREE_STUMP,
        PropKind.FALLEN_LOG, PropKind.CRYSTAL_BLOOM, PropKind.REED_CLUMP, PropKind.WHEAT_SHEAF, PropKind.MOUNTAIN_PEAK,
        PropKind.ROCK_SPIRE -> Role.B
        PropKind.RUIN_WALL, PropKind.RUIN_TOWER, PropKind.RUIN_PILLAR, PropKind.PILLAR, PropKind.STONE_ARCHWAY, PropKind.SEALED_GATE,
        PropKind.GATEHOUSE, PropKind.WATCH_TOWER, PropKind.OBELISK, PropKind.SHRINE, PropKind.WAYPOINT_PILLAR, PropKind.PORTAL,
        PropKind.DARK_RIFT_ALTAR, PropKind.GARGOYLE_SPIRE, PropKind.GARGOYLE_PLAQUE, PropKind.ANCIENT_EXCAVATION,
        PropKind.SARCOPHAGUS, PropKind.TOMBSTONE, PropKind.WOOD_BRIDGE, PropKind.ROPE_BRIDGE, PropKind.PIER,
        PropKind.RUNE_CRYSTAL_FOUNTAIN, PropKind.STAIRS_DOWN, PropKind.STAIRS_UP, PropKind.PAGODA_ROOF, PropKind.PAIFANG_GATE,
        PropKind.JADE_LION_STATUE, PropKind.BELL_TOWER, PropKind.KEEP_TOWER, PropKind.GRAND_STAIRS, PropKind.CLOISTER -> Role.C
        PropKind.COTTAGE, PropKind.COTTAGE_LARGE, PropKind.INN, PropKind.SMITHY, PropKind.SHOP, PropKind.CHAPEL,
        PropKind.GUARDHOUSE, PropKind.GREAT_HALL, PropKind.HALF_TIMBER_HOUSE, PropKind.CATHEDRAL -> Role.E
        else -> Role.D
    }

    /** Places 1 x 1 tiles in reading order; an animated tile's frames (`name_f0`, ...) always sit side by side. */
    private fun placeUnits(units: List<NamedTile>, columns: Int): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        var uc = 0
        var ur = 0
        var i0 = 0
        while (i0 < units.size) {
            val m = Regex("^(.*)_f0$").matchEntire(units[i0].name)
            var group = 1
            if (m != null) while (i0 + group < units.size && units[i0 + group].name == "${m.groupValues[1]}_f$group") group++
            if (uc + group > columns) { uc = 0; ur++ }
            repeat(group) { cells += uc++ to ur }
            if (uc >= columns) { uc = 0; ur++ }
            i0 += group
        }
        return cells
    }

    private fun exportPacks(gameDir: Path, specFiles: List<Path>, firstId: Int): Result {
        require(firstId >= 2) { "firstId must leave tileset 1 free for the built-in tileset" }
        val tilesetsDir = gameDir.resolve("assets").resolve("tilesets")
        Files.createDirectories(tilesetsDir)
        val indexFile = gameDir.resolve("tilesets.index.json")
        val previous = if (Files.isRegularFile(indexFile)) Regex("\"ownedIds\"\\s*:\\s*\\[([^\\]]*)]").find(Files.readString(indexFile))
            ?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList() else emptyList()

        val written = mutableListOf<Path>()
        val tiles = linkedMapOf<String, Placement>()
        val props = linkedMapOf<String, Placement>()
        val autos = linkedMapOf<String, AutotileIndex>()
        val owned = mutableListOf<Int>()
        val packs = mutableListOf<Pack>()

        fun write(id: Int, img: TileImage) {
            val file = tilesetsDir.resolve("$id.png")
            PngEncoder.writeToFile(img, file)
            written.add(file)
            owned += id
        }

        for ((packIndex, specFile) in specFiles.withIndex()) {
            val rendered = TilesetSpecRenderer.renderJson(Files.readString(specFile))
            val spec = rendered.spec
            val ts = spec.tileSize
            val base = firstId + packIndex * PACK_STRIDE
            val multi = rendered.props.filterValues { it.widthTiles != 1 || it.heightTiles != 1 }
            val isSliceOfMulti = { name: String -> multi.keys.any { Regex("^" + Regex.escape(it) + "_\\d+_\\d+$").matches(name) } }
            val units = rendered.tiles.filterNot { isSliceOfMulti(it.name) }

            val materials = spec.materials.associateBy { it.name }
            val unitProps = rendered.props.filterValues { it.widthTiles == 1 && it.heightTiles == 1 }
                .mapKeys { (_, p) -> p.slicedTiles.first().name }
            fun unitRole(name: String): Role {
                unitProps[name]?.let { return propRole(it.kind) }
                if (spec.cliffs.any { name.startsWith(it.name + "_") }) return Role.A4
                val m = materials[name.replace(Regex("_[vf]\\d+$"), "")] ?: return Role.A5
                return when (m.pattern) {
                    MaterialPattern.FLAT_WATER -> Role.A1
                    MaterialPattern.TUFTED_GROUND -> Role.A2
                    else -> Role.A5
                }
            }

            val unitsByRole = Role.entries.associateWith { mutableListOf<NamedTile>() }
            for (t in units) unitsByRole.getValue(unitRole(t.name)) += t
            val multiByRole = Role.entries.associateWith { mutableListOf<GeneratedProp>() }
            for (p in multi.values) multiByRole.getValue(propRole(p.kind)) += p
            val autoByRole = Role.entries.associateWith { mutableListOf<Pair<String, com.morgana.tilegen.autotile.AutotileSheet>>() }
            for ((name, auto) in rendered.autotiles) {
                val fg = spec.autotiles.firstOrNull { it.name == name }?.foregroundMaterial?.let { materials[it] }
                val role = if (fg?.pattern == MaterialPattern.FLAT_WATER) Role.A1 else Role.A2
                autoByRole.getValue(role) += name to auto
            }

            val sheets = linkedMapOf<String, Int>()
            for (role in Role.entries) {
                val us = unitsByRole.getValue(role)
                val ms = multiByRole.getValue(role)
                val bs = autoByRole.getValue(role)
                if (us.isEmpty() && ms.isEmpty() && bs.isEmpty()) continue
                val id = base + role.slot
                val cols = role.columns

                if (role == Role.A4) {
                    // Wall and cliff sets as 3 x 3 blocks, five to a row.
                    val groups = spec.cliffs.map { c -> us.filter { it.name.startsWith(c.name + "_") } }.filter { it.isNotEmpty() }
                    val perRow = cols / 3
                    var rows = 0
                    val origins = ArrayList<Triple<List<NamedTile>, Int, Int>>()
                    var bandTop = 0
                    var bandHeight = 0
                    for ((gi, g) in groups.withIndex()) {
                        if (gi % perRow == 0 && gi > 0) { bandTop += bandHeight; bandHeight = 0 }
                        origins += Triple(g, (gi % perRow) * 3, bandTop)
                        bandHeight = maxOf(bandHeight, (g.size + 2) / 3)
                    }
                    rows = bandTop + bandHeight
                    val sheet = TileImage(cols * ts, rows.coerceAtLeast(1) * ts)
                    for ((g, ox, oy) in origins) g.forEachIndexed { i, t ->
                        val x = ox + i % 3
                        val y = oy + i / 3
                        sheet.drawImage(t.image, 0, 0, ts, ts, x * ts, y * ts)
                        tiles[t.name] = Placement(id, x, y)
                    }
                    write(id, sheet)
                    sheets[role.name] = id
                    continue
                }

                val unitCells = placeUnits(us, cols)
                val unitRows = if (us.isEmpty()) 0 else unitCells.last().second + 1
                // Autotile blocks below the single tiles, as many across as fit.
                val blockCols = bs.firstOrNull()?.second?.columns ?: 8
                val blockRows = bs.firstOrNull()?.second?.let { it.image.height / ts } ?: 0
                val perRow = (cols / blockCols).coerceAtLeast(1)
                val blockBands = if (bs.isEmpty()) 0 else (bs.size + perRow - 1) / perRow
                var curRow = unitRows + blockBands * blockRows
                var curCol = 0
                var band = 0
                val packed = mutableListOf<Triple<GeneratedProp, Int, Int>>()
                for (p in ms) {
                    if (curCol + p.widthTiles > cols) { curRow += band; curCol = 0; band = 0 }
                    packed += Triple(p, curCol, curRow)
                    curCol += p.widthTiles
                    band = maxOf(band, p.heightTiles)
                }
                val rows = (curRow + band).coerceAtLeast(1)
                val sheet = TileImage(cols * ts, rows * ts)
                us.forEachIndexed { i, t ->
                    sheet.drawImage(t.image, 0, 0, ts, ts, unitCells[i].first * ts, unitCells[i].second * ts)
                    tiles[t.name] = Placement(id, unitCells[i].first, unitCells[i].second)
                }
                for ((name, p) in rendered.props) if (name !in multi) {
                    us.indexOfFirst { it.name == p.slicedTiles.first().name }.takeIf { it >= 0 }?.let { i ->
                        props[name] = Placement(id, unitCells[i].first, unitCells[i].second)
                    }
                }
                for ((p, c, r) in packed) {
                    sheet.drawImage(p.fullImage, 0, 0, p.fullImage.width, p.fullImage.height, c * ts, r * ts)
                    props[p.name] = Placement(id, c, r, p.widthTiles, p.heightTiles)
                }
                for ((k, entry) in bs.withIndex()) {
                    val (name, auto) = entry
                    val bx = (k % perRow) * blockCols
                    val by = unitRows + (k / perRow) * blockRows
                    sheet.drawImage(auto.image, 0, 0, auto.image.width, auto.image.height, bx * ts, by * ts)
                    autos[name] = AutotileIndex(id, auto.mode, auto.columns, auto.entries.associate { it.mask to (bx + it.col to by + it.row) })
                }
                write(id, sheet)
                sheets[role.name] = id
            }
            packs += Pack(spec.name, base, sheets)
        }

        val removed = mutableListOf<Path>()
        for (old in previous) if (old !in owned) {
            val f = tilesetsDir.resolve("$old.png")
            if (Files.deleteIfExists(f)) removed.add(f)
        }
        val nextFree = firstId + specFiles.size * PACK_STRIDE
        Files.writeString(indexFile, indexJson(firstId, owned, tiles, props, autos, packs))
        return Result(written, removed, tiles, props, autos, nextFree, packs)
    }

    private fun placement(p: Placement) = "{\"tilesetId\": ${p.tilesetId}, \"x\": ${p.x}, \"y\": ${p.y}" +
        (if (p.w != 1 || p.h != 1) ", \"w\": ${p.w}, \"h\": ${p.h}" else "") + "}"

    private fun indexJson(
        firstId: Int,
        owned: List<Int>,
        tiles: Map<String, Placement>,
        props: Map<String, Placement>,
        autos: Map<String, AutotileIndex>,
        packs: List<Pack> = emptyList()
    ): String = buildString {
        append("{\n")
        append("  \"note\": \"Written by the tileset exporter. Owns tilesets from $firstId upward; do not edit by hand.\",\n")
        append("  \"firstId\": $firstId,\n")
        append("  \"ownedIds\": [${owned.joinToString(", ")}],\n")
        if (packs.isNotEmpty()) {
            append("  \"layout\": \"rpgmaker\",\n  \"packs\": [\n")
            append(packs.joinToString(",\n") { p ->
                "    {\"spec\": \"${p.spec}\", \"firstId\": ${p.firstId}, \"sheets\": {" +
                    p.sheets.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" } + "}}"
            })
            append("\n  ],\n")
        }
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
