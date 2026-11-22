package com.morgana.tilegen.export

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileSheet

/**
 * Converts [AutotileSheet]s into RPG Maker XP (3x4 tile = 96x128 at 32px) autotile sheets.
 */
object RpgMakerExporter {

    // Canonical BLOB_47 masks for the 3x4 RMXP autotile layout
    private val rmxpMasks = arrayOf(
        intArrayOf(0, 85, 255),      // Row 0: isolated, 4-inner-corners, full
        intArrayOf(28, 124, 112),    // Row 1: NW corner, N edge, NE corner
        intArrayOf(31, 255, 241),    // Row 2: W edge, center fill, E edge
        intArrayOf(7, 199, 193)      // Row 3: SW corner, S edge, SE corner
    )

    /**
     * Converts a [BLOB_47] [AutotileSheet] into a 3x4 RPG Maker XP autotile sheet.
     */
    fun toRmxpAutotile(sheet: AutotileSheet): TileImage {
        require(sheet.mode == AutotileMode.BLOB_47) {
            "RMXP autotile export requires BLOB_47 mode, got ${sheet.mode}"
        }
        val ts = sheet.tileSize
        val out = TileImage(3 * ts, 4 * ts)
        for (row in 0 until 4) {
            for (col in 0 until 3) {
                val entry = sheet.entryForMask(rmxpMasks[row][col])
                out.drawImage(
                    sheet.image,
                    entry.col * ts,
                    entry.row * ts,
                    ts,
                    ts,
                    col * ts,
                    row * ts
                )
            }
        }
        return out
    }
}
