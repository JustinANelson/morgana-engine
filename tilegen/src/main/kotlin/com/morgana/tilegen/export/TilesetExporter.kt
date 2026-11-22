package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileSheet
import com.morgana.tilegen.spec.RenderedTileset
import java.nio.file.Files
import java.nio.file.Path

/**
 * Paths produced by a [TilesetExporter] operation.
 */
data class ExportResult(
    val sheetPng: Path,
    val metadataJson: Path,
    val splitTileFiles: List<Path>,
    val tiledTsx: Path? = null,
    val godotTres: Path? = null,
    val previewHtml: Path? = null
)

/**
 * Exports generated tilesets and autotile sheets to PNG + JSON atlas metadata on disk.
 */
object TilesetExporter {

    /**
     * Writes [sheet] to `<outDir>/<name>.png` and its tile layout to `<outDir>/<name>.json`.
     * When [splitTiles] is true, each [NamedTile] is also written to `<outDir>/tiles/<tile.name>.png`.
     */
    fun exportNamedSheet(
        name: String,
        sheet: TileImage,
        tiles: List<NamedTile>,
        tileSize: Int,
        columns: Int,
        outDir: Path,
        splitTiles: Boolean = false,
        exportEngineFormats: Boolean = true,
        exportPreview: Boolean = true,
        cellHeight: Int = tileSize
    ): ExportResult {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(tileSize > 0) { "tileSize must be positive, got $tileSize" }
        require(cellHeight > 0) { "cellHeight must be positive, got $cellHeight" }
        Files.createDirectories(outDir)

        val pngPath = outDir.resolve("$name.png")
        val jsonPath = outDir.resolve("$name.json")
        PngEncoder.writeToFile(sheet, pngPath)

        val rows = ((tiles.size + columns - 1) / columns).coerceAtLeast(1)
        val animGroups = TiledExporter.detectAnimationGroups(tiles)
        val json = buildString {
            append("{\n")
            append("  \"name\": \"$name\",\n")
            append("  \"tileSize\": $tileSize,\n")
            append("  \"cellWidth\": $tileSize,\n")
            append("  \"cellHeight\": $cellHeight,\n")
            append("  \"columns\": $columns,\n")
            append("  \"rows\": $rows,\n")
            append("  \"width\": ${sheet.width},\n")
            append("  \"height\": ${sheet.height},\n")
            append("  \"tiles\": [\n")
            tiles.forEachIndexed { index, tile ->
                val col = index % columns
                val row = index / columns
                val comma = if (index < tiles.lastIndex) "," else ""
                append("    {\"index\": $index, \"name\": \"${tile.name}\", \"col\": $col, \"row\": $row, \"x\": ${col * tileSize}, \"y\": ${row * cellHeight}}$comma\n")
            }
            if (animGroups.isEmpty()) {
                append("  ]\n")
            } else {
                append("  ],\n")
                append("  \"animations\": [\n")
                val entries = animGroups.entries.toList()
                entries.forEachIndexed { idx, (animName, frameIndices) ->
                    val comma = if (idx < entries.lastIndex) "," else ""
                    val framesArr = frameIndices.joinToString(", ")
                    append("    {\"name\": \"$animName\", \"frameDurationMs\": 150, \"frames\": [$framesArr]}$comma\n")
                }
                append("  ]\n")
            }
            append("}")
        }
        Files.writeString(jsonPath, json)

        val splitFiles = if (splitTiles) {
            val tilesDir = outDir.resolve("tiles")
            Files.createDirectories(tilesDir)
            tiles.map { tile ->
                val tilePath = tilesDir.resolve("${tile.name}.png")
                PngEncoder.writeToFile(tile.image, tilePath)
                tilePath
            }
        } else {
            emptyList()
        }

        val tsxPath = if (exportEngineFormats) {
            outDir.resolve("$name.tsx").also {
                Files.writeString(it, TiledExporter.toNamedTsx(name, "$name.png", sheet.width, sheet.height, tileSize, columns, tiles))
            }
        } else null

        val tresPath = if (exportEngineFormats) {
            outDir.resolve("$name.tres").also {
                Files.writeString(it, GodotExporter.toNamedTres("$name.png", tileSize, columns, tiles))
            }
        } else null

        val previewPath = if (exportPreview) {
            outDir.resolve("${name}_preview.html").also {
                Files.writeString(it, PreviewHtmlExporter.toNamedPreviewHtml(name, "$name.png", tileSize, columns, tiles))
            }
        } else null

        return ExportResult(pngPath, jsonPath, splitFiles, tsxPath, tresPath, previewPath)
    }

    /**
     * Writes [autotile] to `<outDir>/<name>.png` and `<outDir>/<name>.json`.
     * When [splitTiles] is true, each bitmask tile is also written to `<outDir>/tiles/<entry.name>.png`.
     */
    fun exportAutotile(
        name: String,
        autotile: AutotileSheet,
        outDir: Path,
        splitTiles: Boolean = false,
        exportEngineFormats: Boolean = true,
        exportPreview: Boolean = true
    ): ExportResult {
        Files.createDirectories(outDir)
        val pngPath = outDir.resolve("$name.png")
        val jsonPath = outDir.resolve("$name.json")
        PngEncoder.writeToFile(autotile.image, pngPath)
        Files.writeString(jsonPath, autotile.toMetadataJson())
        if (autotile.mode == AutotileMode.BLOB_47) {
            val rmxpImg = RpgMakerExporter.toRmxpAutotile(autotile)
            PngEncoder.writeToFile(rmxpImg, outDir.resolve("${name}_rmxp.png"))
        }

        val splitFiles = if (splitTiles) {
            val tilesDir = outDir.resolve("tiles")
            Files.createDirectories(tilesDir)
            autotile.entries.map { entry ->
                val tileImg = autotile.image.crop(
                    entry.col * autotile.tileSize,
                    entry.row * autotile.tileSize,
                    autotile.tileSize,
                    autotile.tileSize
                )
                val tilePath = tilesDir.resolve("${entry.name}.png")
                PngEncoder.writeToFile(tileImg, tilePath)
                tilePath
            }
        } else {
            emptyList()
        }

        val tsxPath = if (exportEngineFormats) {
            outDir.resolve("$name.tsx").also {
                Files.writeString(it, TiledExporter.toAutotileTsx(name, "$name.png", autotile))
            }
        } else null

        val tresPath = if (exportEngineFormats) {
            outDir.resolve("$name.tres").also {
                Files.writeString(it, GodotExporter.toAutotileTres(name, "$name.png", autotile))
            }
        } else null

        val previewPath = if (exportPreview) {
            outDir.resolve("${name}_preview.html").also {
                Files.writeString(it, PreviewHtmlExporter.toAutotilePreviewHtml(name, "$name.png", autotile))
            }
        } else null

        return ExportResult(pngPath, jsonPath, splitFiles, tsxPath, tresPath, previewPath)
    }

    /**
     * Exports a [RenderedTileset] produced from an LLM-driven spec:
     * - If material tiles are present, writes `<outDir>/<spec.name>.png` and `<outDir>/<spec.name>.json`.
     * - For each autotile layer, writes `<outDir>/autotile_<layer>.png` and `<outDir>/autotile_<layer>.json`.
     */
    fun exportRenderedSpec(
        rendered: RenderedTileset,
        outDir: Path,
        splitTiles: Boolean = false
    ): List<ExportResult> {
        val results = mutableListOf<ExportResult>()
        val spec = rendered.spec
        if (rendered.sheet != null && rendered.tiles.isNotEmpty()) {
            results += exportNamedSheet(
                name = spec.name,
                sheet = rendered.sheet,
                tiles = rendered.tiles,
                tileSize = spec.tileSize,
                columns = spec.columns,
                outDir = outDir,
                splitTiles = splitTiles
            )
        }
        rendered.autotiles.forEach { (layerName, autotileSheet) ->
            val autoDir = if (splitTiles) outDir.resolve("autotile_$layerName") else outDir
            results += exportAutotile(
                name = "${spec.name}_$layerName",
                autotile = autotileSheet,
                outDir = autoDir,
                splitTiles = splitTiles
            )
        }
        if (rendered.props.isNotEmpty()) {
            val propsDir = outDir.resolve("props")
            Files.createDirectories(propsDir)
            rendered.props.forEach { (propName, prop) ->
                PngEncoder.writeToFile(prop.fullImage, propsDir.resolve("$propName.png"))
            }
        }
        return results
    }
}
