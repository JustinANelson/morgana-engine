package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.autotile.AutotileBitmask
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileSheet

/**
 * Generates Tiled Map Editor (`.tsx`) XML tileset files for named sprite sheets
 * and Wang-set autotile sheets.
 */
object TiledExporter {

    private val FRAME_REGEX = Regex("""^(.+)_f(\d+)$""")

    /**
     * Groups animated tiles matching `<base>_f<index>` into ordered frame index lists keyed by `<base>`.
     */
    fun detectAnimationGroups(tiles: List<NamedTile>): Map<String, List<Int>> {
        val grouped = linkedMapOf<String, MutableList<Pair<Int, Int>>>()
        tiles.forEachIndexed { tileIdx, tile ->
            val m = FRAME_REGEX.matchEntire(tile.name)
            if (m != null) {
                val base = m.groupValues[1]
                val frameNum = m.groupValues[2].toIntOrNull() ?: 0
                grouped.getOrPut(base) { mutableListOf() }.add(frameNum to tileIdx)
            }
        }
        return grouped
            .filterValues { it.size > 1 }
            .mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
    }

    /**
     * Generates a Tiled `.tsx` XML string for a named [tiles] sheet, including `<animation>`
     * frame sequences for multi-frame `<base>_f0..fN` tiles.
     */
    fun toNamedTsx(
        name: String,
        imageFileName: String,
        imageWidth: Int,
        imageHeight: Int,
        tileSize: Int,
        columns: Int,
        tiles: List<NamedTile>,
        frameDurationMs: Int = 150
    ): String {
        val escName = escapeXml(name)
        val escImg = escapeXml(imageFileName)
        val animByFirstTileId = detectAnimationGroups(tiles).values.associateBy { it.first() }
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<tileset version=\"1.10\" tiledversion=\"1.10.2\" name=\"$escName\" tilewidth=\"$tileSize\" tileheight=\"$tileSize\" tilecount=\"${tiles.size}\" columns=\"$columns\">")
            appendLine(" <image source=\"$escImg\" width=\"$imageWidth\" height=\"$imageHeight\"/>")
            tiles.forEachIndexed { idx, tile ->
                appendLine(" <tile id=\"$idx\">")
                appendLine("  <properties>")
                appendLine("   <property name=\"name\" value=\"${escapeXml(tile.name)}\"/>")
                appendLine("  </properties>")
                val animFrames = animByFirstTileId[idx]
                if (animFrames != null) {
                    appendLine("  <animation>")
                    for (frameTileId in animFrames) {
                        appendLine("   <frame tileid=\"$frameTileId\" duration=\"$frameDurationMs\"/>")
                    }
                    appendLine("  </animation>")
                }
                appendLine(" </tile>")
            }
            appendLine("</tileset>")
        }
    }

    /**
     * Generates a Tiled `.tsx` XML string with `<wangsets>` terrain bitmask metadata for [autotile].
     */
    fun toAutotileTsx(
        name: String,
        imageFileName: String,
        autotile: AutotileSheet
    ): String {
        val escName = escapeXml(name)
        val escImg = escapeXml(imageFileName)
        val wangType = if (autotile.mode == AutotileMode.BLOB_47) "mixed" else "edge"
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<tileset version=\"1.10\" tiledversion=\"1.10.2\" name=\"$escName\" tilewidth=\"${autotile.tileSize}\" tileheight=\"${autotile.tileSize}\" tilecount=\"${autotile.entries.size}\" columns=\"${autotile.columns}\">")
            appendLine(" <image source=\"$escImg\" width=\"${autotile.image.width}\" height=\"${autotile.image.height}\"/>")
            appendLine(" <wangsets>")
            appendLine("  <wangset name=\"$escName\" type=\"$wangType\" tile=\"-1\">")
            appendLine("   <wangcolor name=\"foreground\" color=\"#44aa66\" tile=\"-1\" probability=\"1\"/>")
            for (entry in autotile.entries) {
                val wangId = wangIdForMask(entry.mask, autotile.mode)
                appendLine("   <wangtile tileid=\"${entry.index}\" wangid=\"$wangId\"/>")
            }
            appendLine("  </wangset>")
            appendLine(" </wangsets>")
            appendLine("</tileset>")
        }
    }

    private fun wangIdForMask(mask: Int, mode: AutotileMode): String {
        // Tiled WangID order: top, top-right, right, bottom-right, bottom, bottom-left, left, top-left
        return if (mode == AutotileMode.BLOB_47) {
            val n = if ((mask and AutotileBitmask.N) != 0) 1 else 0
            val ne = if ((mask and AutotileBitmask.NE) != 0) 1 else 0
            val e = if ((mask and AutotileBitmask.E) != 0) 1 else 0
            val se = if ((mask and AutotileBitmask.SE) != 0) 1 else 0
            val s = if ((mask and AutotileBitmask.S) != 0) 1 else 0
            val sw = if ((mask and AutotileBitmask.SW) != 0) 1 else 0
            val w = if ((mask and AutotileBitmask.W) != 0) 1 else 0
            val nw = if ((mask and AutotileBitmask.NW) != 0) 1 else 0
            "0,$n,$ne,$e,$se,$s,$sw,$w,$nw".removePrefix("0,")
        } else {
            val n = if ((mask and AutotileBitmask.CARDINAL_N) != 0) 1 else 0
            val e = if ((mask and AutotileBitmask.CARDINAL_E) != 0) 1 else 0
            val s = if ((mask and AutotileBitmask.CARDINAL_S) != 0) 1 else 0
            val w = if ((mask and AutotileBitmask.CARDINAL_W) != 0) 1 else 0
            "$n,0,$e,0,$s,0,$w,0"
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
