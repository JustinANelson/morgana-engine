package com.morgana.client.ui.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The editor icons are hand-drawn text, so their format is checked rather than trusted. */
class IconArtTest {

    @Test
    fun `every icon is exactly 16 by 16 and uses only the art alphabet`() {
        for ((name, rows) in IconArt.icons) {
            assertEquals(IconArt.SIZE, rows.size, "$name has ${rows.size} rows")
            rows.forEachIndexed { y, row ->
                assertEquals(IconArt.SIZE, row.length, "$name row $y is ${row.length} wide: '$row'")
                row.forEach { c -> assertTrue(IconArt.alphaOf(c) != null, "$name row $y has '$c'") }
            }
        }
    }

    @Test
    fun `every icon draws something substantial and not just a speck`() {
        for ((name, rows) in IconArt.icons) {
            val solid = rows.sumOf { r -> r.count { it == '#' } }
            assertTrue(solid >= 12, "$name has only $solid solid pixels")
        }
    }

    @Test
    fun `each icon spreads across the grid instead of huddling in a corner`() {
        // A drawing that is entirely in one corner would look broken in a button; require some spread.
        for ((name, rows) in IconArt.icons) {
            val xs = rows.flatMap { r -> r.indices.filter { r[it] != '.' } }
            val ys = rows.indices.filter { y -> rows[y].any { it != '.' } }
            val width = xs.max() - xs.min() + 1
            val height = ys.max() - ys.min() + 1
            // A minus sign is legitimately flat, so one direction must be wide enough, not both.
            assertTrue(maxOf(width, height) >= 7, "$name is only ${width}x$height px")
        }
    }

    @Test
    fun `all the icons the editor panels will need exist`() {
        val needed = listOf(
            "brush", "eraser", "fill", "picker", "rect", "spawn", "event", "undo", "redo", "save", "grid", "zoom_in", "zoom_out",
            "eye", "eye_off", "layers", "tiles", "attribute", "map", "plus", "minus", "minimize", "close", "float", "dock", "warning"
        )
        needed.forEach { assertTrue(it in IconArt.icons, "missing icon $it") }
        assertEquals(IconArt.icons.size, IconArt.names.toSet().size, "icon names are unique")
    }
}
