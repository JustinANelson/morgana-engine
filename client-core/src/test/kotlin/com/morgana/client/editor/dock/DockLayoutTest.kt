package com.morgana.client.editor.dock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The editor workspace's layout rules, independent of any drawing. */
class DockLayoutTest {

    private val specs = listOf(
        PanelSpec("toolbar", DockZone.TOP, preferredWidth = 800f, preferredHeight = 40f),
        PanelSpec("status", DockZone.BOTTOM, preferredWidth = 800f, preferredHeight = 24f),
        PanelSpec("tileset", DockZone.RIGHT, preferredWidth = 260f, preferredHeight = 400f),
        PanelSpec("layers", DockZone.RIGHT, preferredWidth = 260f, preferredHeight = 200f),
        PanelSpec("properties", DockZone.LEFT, preferredWidth = 220f, preferredHeight = 300f)
    )

    private fun layout() = DockLayout(specs)
    private val screenW = 1600f
    private val screenH = 900f

    @Test
    fun `panels start in their default zones in registration order`() {
        val l = layout()
        assertEquals(listOf("tileset", "layers"), l.docked(DockZone.RIGHT).map { it.id })
        assertEquals(listOf("toolbar"), l.docked(DockZone.TOP).map { it.id })
        assertEquals(listOf("properties"), l.docked(DockZone.LEFT).map { it.id })
        assertTrue(l.floating().isEmpty())
    }

    @Test
    fun `a panel can start as a hidden floating window that docks back to its default zone`() {
        val l = DockLayout(specs + PanelSpec("items", DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true))
        assertTrue(l.placement("items").isFloating)
        assertFalse(l.placement("items").visible)
        assertEquals(listOf("tileset", "layers"), l.docked(DockZone.RIGHT).map { it.id }, "a hidden floating panel takes no dock space")
        l.setVisible("items", true)
        assertEquals(listOf("items"), l.floating().map { it.id })
        assertEquals(560f, l.bounds("items", screenW, screenH)!!.width)
        l.dockBack("items")
        assertEquals(DockZone.RIGHT, l.placement("items").zone)
    }

    @Test
    fun `panel ids must be unique`() {
        assertThrows(IllegalArgumentException::class.java) { DockLayout(specs + specs.first()) }
    }

    @Test
    fun `docking moves a panel to the new zone at the chosen position`() {
        val l = layout()
        l.dock("properties", DockZone.RIGHT, index = 1)
        assertEquals(listOf("tileset", "properties", "layers"), l.docked(DockZone.RIGHT).map { it.id })
        assertTrue(l.docked(DockZone.LEFT).isEmpty())
        assertEquals(DockZone.RIGHT, l.placement("properties").lastZone)
    }

    @Test
    fun `docking at an out of range index clamps to the ends`() {
        val l = layout()
        l.dock("properties", DockZone.RIGHT, index = -5)
        assertEquals("properties", l.docked(DockZone.RIGHT).first().id)
        l.dock("toolbar", DockZone.RIGHT, index = 99)
        assertEquals("toolbar", l.docked(DockZone.RIGHT).last().id)
    }

    @Test
    fun `floating remembers the zone so dock returns the panel there`() {
        val l = layout()
        l.float("tileset", 300f, 200f)
        assertTrue(l.placement("tileset").isFloating)
        assertEquals(listOf("layers"), l.docked(DockZone.RIGHT).map { it.id })
        assertEquals(listOf("tileset"), l.floating().map { it.id })

        l.dockBack("tileset")
        assertEquals(listOf("layers", "tileset"), l.docked(DockZone.RIGHT).map { it.id }, "returns to the end of its old zone")
    }

    @Test
    fun `a panel can be moved and resized only while floating, and never below the minimum size`() {
        val l = layout()
        assertThrows(IllegalArgumentException::class.java) { l.moveFloating("tileset", 1f, 1f) }
        l.float("tileset", 100f, 100f)
        l.moveFloating("tileset", 250f, 150f)
        l.resizeFloating("tileset", 10f, 10f)
        val p = l.placement("tileset")
        assertEquals(250f, p.floatX)
        assertEquals(DockLayout.MIN_FLOAT_WIDTH, p.floatWidth)
        assertEquals(DockLayout.MIN_FLOAT_HEIGHT, p.floatHeight)
    }

    @Test
    fun `hidden panels are not listed and have no bounds`() {
        val l = layout()
        l.setVisible("layers", false)
        assertEquals(listOf("tileset"), l.docked(DockZone.RIGHT).map { it.id })
        assertNull(l.bounds("layers", screenW, screenH))
        l.setVisible("layers", true)
        assertEquals(2, l.docked(DockZone.RIGHT).size)
    }

    @Test
    fun `reordering within a zone moves a panel by the given number of places`() {
        val l = layout()
        l.moveWithinZone("layers", -1)
        assertEquals(listOf("layers", "tileset"), l.docked(DockZone.RIGHT).map { it.id })
        l.moveWithinZone("layers", +5)
        assertEquals(listOf("tileset", "layers"), l.docked(DockZone.RIGHT).map { it.id })
    }

    // ---- geometry ----

    @Test
    fun `insets are the widest column and tallest band on each side`() {
        val i = layout().insets(screenW, screenH)
        assertEquals(ViewInsets(left = 220f, right = 260f, top = 40f, bottom = 24f), i)
    }

    @Test
    fun `with nothing docked the insets are zero`() {
        val l = layout()
        specs.forEach { l.setVisible(it.id, false) }
        assertTrue(l.insets(screenW, screenH).isNone)
    }

    @Test
    fun `insets are limited so the world always keeps room`() {
        val l = DockLayout(listOf(
            PanelSpec("a", DockZone.LEFT, 900f, 300f), PanelSpec("b", DockZone.RIGHT, 900f, 300f),
            PanelSpec("c", DockZone.TOP, 800f, 900f)
        ))
        val i = l.insets(1000f, 800f)
        assertTrue(i.left + i.right <= 1000f * DockLayout.MAX_SIDE_TOTAL + 0.01f, "sides ${i.left + i.right}")
        assertTrue(i.top <= 800f * DockLayout.MAX_BAND_SHARE + 0.01f, "top ${i.top}")
    }

    @Test
    fun `a collapsed band shrinks to its header`() {
        val l = layout()
        l.toggleCollapsed("toolbar")
        assertEquals(DockLayout.HEADER_HEIGHT, l.insets(screenW, screenH).top)
    }

    @Test
    fun `a single band panel spans the full width`() {
        val r = layout().bounds("toolbar", screenW, screenH)!!
        assertEquals(PanelRect(0f, 0f, screenW, 40f), r)
        assertEquals(PanelRect(0f, screenH - 24f, screenW, 24f), layout().bounds("status", screenW, screenH))
    }

    @Test
    fun `band panels sit side by side and the last one stretches`() {
        val l = DockLayout(listOf(PanelSpec("a", DockZone.TOP, 300f, 40f), PanelSpec("b", DockZone.TOP, 200f, 40f)))
        assertEquals(PanelRect(0f, 0f, 300f, 40f), l.bounds("a", 1000f, 600f))
        assertEquals(PanelRect(300f, 0f, 700f, 40f), l.bounds("b", 1000f, 600f))
    }

    @Test
    fun `band panels wider than the screen are scaled down to fit`() {
        val l = DockLayout(listOf(PanelSpec("a", DockZone.TOP, 800f, 40f), PanelSpec("b", DockZone.TOP, 800f, 40f)))
        val a = l.bounds("a", 1000f, 600f)!!
        val b = l.bounds("b", 1000f, 600f)!!
        assertEquals(500f, a.width)
        assertEquals(500f, b.x)
        assertEquals(1000f, b.x + b.width)
    }

    @Test
    fun `a side column sits between the bands and splits its height by preferred height`() {
        val l = layout()
        val tileset = l.bounds("tileset", screenW, screenH)!!
        val layers = l.bounds("layers", screenW, screenH)!!
        val free = screenH - 40f - 24f
        assertEquals(screenW - 260f, tileset.x)
        assertEquals(40f, tileset.y)
        assertEquals(free * 400f / 600f, tileset.height, 0.01f)
        assertEquals(tileset.y + tileset.height, layers.y, 0.01f)
        assertEquals(40f + free, layers.y + layers.height, 0.01f)
        assertEquals(260f, layers.width)
    }

    @Test
    fun `a collapsed panel in a column keeps only its header and gives the rest to the others`() {
        val l = layout()
        l.toggleCollapsed("tileset")
        val tileset = l.bounds("tileset", screenW, screenH)!!
        val layers = l.bounds("layers", screenW, screenH)!!
        assertEquals(DockLayout.HEADER_HEIGHT, tileset.height)
        assertEquals(screenH - 40f - 24f - DockLayout.HEADER_HEIGHT, layers.height, 0.01f)
    }

    @Test
    fun `a docked panel never overlaps another or the bands`() {
        val l = layout()
        val rects = l.ids.map { it to l.bounds(it, screenW, screenH)!! }
        for ((i, a) in rects.withIndex()) for ((j, b) in rects.withIndex()) {
            if (i >= j) continue
            val overlapX = a.second.x < b.second.x + b.second.width && b.second.x < a.second.x + a.second.width
            val overlapY = a.second.y < b.second.y + b.second.height && b.second.y < a.second.y + a.second.height
            assertFalse(overlapX && overlapY, "${a.first} overlaps ${b.first}")
        }
    }

    @Test
    fun `floating panels are kept reachable on screen`() {
        val l = layout()
        l.float("tileset", -5000f, 5000f, width = 300f, height = 300f)
        val r = l.bounds("tileset", screenW, screenH)!!
        assertTrue(r.x + r.width >= DockLayout.GRAB_MARGIN, "some of the panel stays on screen horizontally")
        assertTrue(r.y <= screenH - DockLayout.HEADER_HEIGHT, "the header stays on screen vertically")
        assertTrue(r.y >= 0f)
        l.float("layers", 4000f, -10f, width = 300f, height = 300f)
        assertTrue(l.bounds("layers", screenW, screenH)!!.x <= screenW - DockLayout.GRAB_MARGIN)
    }

    // ---- drag to dock ----

    @Test
    fun `releasing near an edge docks to it and the middle does not`() {
        val l = layout()
        assertEquals(DockZone.RIGHT, l.dropZoneAt(screenW - 10f, 450f, screenW, screenH))
        assertEquals(DockZone.LEFT, l.dropZoneAt(12f, 450f, screenW, screenH))
        assertEquals(DockZone.TOP, l.dropZoneAt(800f, 5f, screenW, screenH))
        assertEquals(DockZone.BOTTOM, l.dropZoneAt(800f, screenH - 20f, screenW, screenH))
        assertNull(l.dropZoneAt(800f, 450f, screenW, screenH))
    }

    @Test
    fun `in a corner the nearer edge wins`() {
        val l = layout()
        assertEquals(DockZone.TOP, l.dropZoneAt(30f, 8f, screenW, screenH))
        assertEquals(DockZone.LEFT, l.dropZoneAt(6f, 30f, screenW, screenH))
    }

    // ---- saving ----

    @Test
    fun `a saved layout restores exactly`() {
        val a = layout()
        a.dock("properties", DockZone.RIGHT, 0)
        a.float("layers", 321f, 123f, 280f, 190f)
        a.toggleCollapsed("tileset")
        a.setVisible("status", false)

        val b = layout()
        b.restore(a.serialize())

        assertEquals(a.ids.map { a.placement(it) }, b.ids.map { b.placement(it) })
    }

    @Test
    fun `restoring ignores unknown panels and damaged lines`() {
        val l = layout()
        val before = l.ids.map { l.placement(it) }
        l.restore("${DockLayout.FORMAT}\nnonsense\nghost|RIGHT|0|RIGHT|1|2|3|4|true|false|false\ntileset|SIDEWAYS|0|RIGHT|1|2|3|4|true|false|false\ntileset|LEFT|x|RIGHT|1|2|3|4|true|false|false\ntileset|LEFT|0|RIGHT|NaN|2|3|4|true|false|false\n\n|||")
        assertEquals(before, l.ids.map { l.placement(it) })
    }

    @Test
    fun `restored orders that collide are renumbered without losing panels`() {
        val l = layout()
        l.restore("${DockLayout.FORMAT}\ntileset|RIGHT|0|RIGHT|0|0|260|400|true|false|false\nlayers|RIGHT|0|RIGHT|0|0|260|200|true|false|false")
        val right = l.docked(DockZone.RIGHT)
        assertEquals(setOf("tileset", "layers"), right.map { it.id }.toSet())
        assertEquals(listOf(0, 1), right.map { it.order })
    }

    @Test
    fun `restored sizes are never below the minimum`() {
        val l = layout()
        l.restore("${DockLayout.FORMAT}\ntileset|FLOAT|0|RIGHT|10|10|1|1|true|false|false")
        assertEquals(DockLayout.MIN_FLOAT_WIDTH, l.placement("tileset").floatWidth)
        assertEquals(DockLayout.MIN_FLOAT_HEIGHT, l.placement("tileset").floatHeight)
    }

    @Test
    fun `reset puts every panel back`() {
        val l = layout()
        l.float("tileset", 1f, 1f)
        l.dock("properties", DockZone.TOP)
        l.setVisible("layers", false)
        l.reset()
        assertEquals(layout().ids.map { layout().placement(it) }, l.ids.map { l.placement(it) })
    }

    // ---- camera ----

    @Test
    fun `the camera shifts so the player stays centred in the free area`() {
        // 300 px covered on the right: the free area's centre is 150 px left of the screen centre, so the camera moves right.
        val (dx, dy) = ViewInsets(right = 300f).cameraShift(zoom = 1f)
        assertEquals(150f, dx)
        assertEquals(0f, dy)
        // Covered on the left, the camera moves left; covered at the top the camera moves up (world y is up).
        val (lx, _) = ViewInsets(left = 200f).cameraShift(1f)
        assertEquals(-100f, lx)
        val (_, ty) = ViewInsets(top = 40f, bottom = 24f).cameraShift(1f)
        assertEquals(8f, ty)
        assertEquals(0f to 0f, ViewInsets.NONE.cameraShift(2f))
    }

    // ---- minimizing, presets and saved layouts ----

    @Test
    fun `a minimized panel is off the screen but listed for the panel bar and comes back with show`() {
        val l = layout()
        l.minimize("tileset")
        assertFalse(l.placement("tileset").visible)
        assertEquals(listOf("tileset"), l.minimized().map { it.id })
        assertFalse("tileset" in l.visibleIds())
        l.show("tileset")
        assertTrue(l.placement("tileset").visible)
        assertTrue(l.minimized().isEmpty())
    }

    @Test
    fun `closing a minimized panel takes it off the bar and docking or floating brings it back`() {
        val l = layout()
        l.minimize("layers")
        l.setVisible("layers", false)
        assertTrue(l.minimized().isEmpty())
        l.minimize("layers")
        l.float("layers", 100f, 100f)
        assertTrue(l.minimized().isEmpty() && l.placement("layers").visible)
        l.minimize("tileset")
        l.dock("tileset", DockZone.LEFT)
        assertTrue(l.minimized().isEmpty() && l.placement("tileset").visible)
    }

    @Test
    fun `minimized panels survive a save and restore`() {
        val a = layout()
        a.minimize("layers")
        val b = layout()
        b.restore(a.serialize())
        assertEquals(listOf("layers"), b.minimized().map { it.id })
        assertFalse(b.placement("layers").visible)
    }

    @Test
    fun `a layout saved in an older format is ignored so new defaults apply`() {
        val l = layout()
        val before = l.ids.map { l.placement(it) }
        l.restore("tileset|FLOAT|0|RIGHT|10|10|300|300|false|false\nlayers|FLOAT|0|RIGHT|10|10|300|300|false|false")
        assertEquals(before, l.ids.map { l.placement(it) })
        l.restore("layout-v1\ntileset|FLOAT|0|RIGHT|10|10|300|300|true|false|false")
        assertEquals(before, l.ids.map { l.placement(it) })
    }

    @Test
    fun `a preset shows exactly its panels at their default places and ignores unknown ids`() {
        val l = layout()
        l.float("tileset", 300f, 300f)
        l.minimize("layers")
        l.applyPreset(listOf("toolbar", "status", "layers", "nonsense"))
        assertEquals(setOf("toolbar", "status", "layers"), l.visibleIds())
        assertTrue(l.minimized().isEmpty())
        assertEquals(DockZone.RIGHT, l.placement("layers").zone)
        assertFalse(l.placement("tileset").isFloating)
    }

    private fun floatingSpecs() = specs + listOf("a", "b", "c").map { PanelSpec(it, DockZone.RIGHT, 400f, 300f, visibleByDefault = false, startFloating = true) }

    @Test
    fun `floating panels opened one after another fan out instead of stacking`() {
        val l = DockLayout(floatingSpecs())
        l.show("a")
        l.show("b")
        l.show("c")
        val corners = listOf("a", "b", "c").map { l.placement(it).let { p -> p.floatX to p.floatY } }
        assertEquals(3, corners.toSet().size, "no two panels share a corner: $corners")
        for ((i, x) in corners.withIndex()) for ((j, y) in corners.withIndex()) {
            if (i < j) assertTrue(Math.abs(x.first - y.first) >= DockLayout.STACK_DISTANCE || Math.abs(x.second - y.second) >= DockLayout.STACK_DISTANCE, "panels $i and $j overlap at their corners")
        }
        assertEquals(listOf("a", "b", "c"), l.floating().map { it.id })
        assertEquals(listOf(80f to 80f, 120f to 120f, 160f to 160f), corners, "each opens one cascade step after the last")
    }

    @Test
    fun `a panel the user placed somewhere clear stays put when shown again`() {
        val l = DockLayout(floatingSpecs())
        l.show("a")
        l.moveFloating("a", 500f, 200f)
        l.setVisible("a", false)
        l.show("b")
        l.show("a")
        assertEquals(500f to 200f, l.placement("a").let { it.floatX to it.floatY })
    }

    @Test
    fun `showing a docked panel just makes it visible`() {
        val l = DockLayout(specs)
        l.setVisible("layers", false)
        l.show("layers")
        assertTrue(l.placement("layers").visible)
        assertEquals(DockZone.RIGHT, l.placement("layers").zone)
    }

    @Test
    fun `the camera shift scales with the world zoom`() {
        assertEquals(300f, ViewInsets(right = 300f).cameraShift(2f).first)
    }

    @Test
    fun `a side column can be dragged wider or narrower within limits`() {
        val l = layout()
        assertEquals(260f, l.insets(screenW, screenH).right)
        l.setColumnWidth(DockZone.RIGHT, 400f, screenW)
        assertEquals(400f, l.insets(screenW, screenH).right)
        assertEquals(400f, l.bounds("tileset", screenW, screenH)!!.width)
        l.setColumnWidth(DockZone.RIGHT, 20f, screenW)
        assertEquals(DockLayout.MIN_COLUMN_WIDTH, l.columnWidth(DockZone.RIGHT))
        l.setColumnWidth(DockZone.RIGHT, 5000f, screenW)
        assertEquals(screenW * DockLayout.MAX_SIDE_SHARE, l.columnWidth(DockZone.RIGHT))
        l.setColumnWidth(DockZone.TOP, 300f, screenW)
        assertNull(l.columnWidth(DockZone.TOP), "bands are not resized this way")
    }

    @Test
    fun `dragging the line between two docked panels moves height from one to the other`() {
        val l = layout()
        val before = l.bounds("tileset", screenW, screenH)!!.height to l.bounds("layers", screenW, screenH)!!.height
        assertTrue(l.resizeSplit("tileset", "layers", 50f, screenW, screenH))
        val after = l.bounds("tileset", screenW, screenH)!!.height to l.bounds("layers", screenW, screenH)!!.height
        assertEquals(before.first + 50f, after.first, 0.01f)
        assertEquals(before.second - 50f, after.second, 0.01f)
        assertTrue(l.resizeSplit("tileset", "layers", 10_000f, screenW, screenH))
        assertEquals(DockLayout.MIN_DOCKED_HEIGHT, l.bounds("layers", screenW, screenH)!!.height, 0.01f)
        assertFalse(l.resizeSplit("layers", "tileset", 10f, screenW, screenH), "only neighbours in display order")
        assertFalse(l.resizeSplit("tileset", "properties", 10f, screenW, screenH), "only within one column")
    }

    @Test
    fun `dragged sizes are saved, restored and cleared by reset`() {
        val l = layout()
        l.setColumnWidth(DockZone.RIGHT, 333f, screenW)
        l.resizeSplit("tileset", "layers", 40f, screenW, screenH)
        val heights = l.bounds("tileset", screenW, screenH)!!.height to l.bounds("layers", screenW, screenH)!!.height
        val copy = layout().also { it.restore(l.serialize()) }
        assertEquals(333f, copy.columnWidth(DockZone.RIGHT))
        assertEquals(heights.first, copy.bounds("tileset", screenW, screenH)!!.height, 0.01f)
        assertEquals(heights.second, copy.bounds("layers", screenW, screenH)!!.height, 0.01f)
        copy.reset()
        assertNull(copy.columnWidth(DockZone.RIGHT))
        assertEquals(260f, copy.insets(screenW, screenH).right)
    }

    @Test
    fun `a layout saved before panels could be resized still restores`() {
        val l = layout()
        l.dock("properties", DockZone.RIGHT)
        val old = l.serialize().lines().joinToString("\n") { line -> if (line.count { it == '|' } == 11) line.substringBeforeLast('|') else line }
        val copy = layout().also { it.restore(old) }
        assertEquals(DockZone.RIGHT, copy.placement("properties").zone)
    }
}
