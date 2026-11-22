package com.morgana.client.ui.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A colour scheme is only professional if it stays readable: every scheme's contrast is checked against the WCAG thresholds. */
class EditorPaletteTest {

    private fun assertContrast(min: Double, fg: com.badlogic.gdx.graphics.Color, bg: com.badlogic.gdx.graphics.Color, what: String) {
        val ratio = EditorPalette.contrast(fg, bg)
        assertTrue(ratio >= min, "$what contrast is %.2f, need at least %.1f".format(ratio, min))
    }

    private fun eachScheme(check: (EditorPalette) -> Unit) = EditorPalette.SCHEMES.forEach(check)

    @Test
    fun `normal text is easy to read on every surface`() = eachScheme { p ->
        assertContrast(7.0, p.strong, p.accentDim, "${p.id}: selected text on the accent fill")
        for ((name, surface) in listOf("panel" to p.panel, "raised" to p.panelRaised, "hover" to p.hover, "accent fill" to p.accentDim)) {
            assertContrast(7.0, p.text, surface, "${p.id}: text on $name")
        }
    }

    @Test
    fun `muted text meets the AA threshold on panels`() = eachScheme { p ->
        assertContrast(4.5, p.textMuted, p.panel, "${p.id}: muted text on panel")
        assertContrast(4.5, p.textMuted, p.panelRaised, "${p.id}: muted text on raised")
    }

    @Test
    fun `the accent and status colours stand out from the panels`() = eachScheme { p ->
        for ((name, c) in listOf("accent" to p.accent, "danger" to p.danger, "success" to p.success, "warning" to p.warning)) {
            assertContrast(4.5, c, p.panel, "${p.id}: $name on panel")
        }
    }

    @Test
    fun `borders are visible but quiet`() = eachScheme { p ->
        val ratio = EditorPalette.contrast(p.border, p.panel)
        assertTrue(ratio in 1.3..3.0, "${p.id}: border/panel contrast %.2f should be subtle but present".format(ratio))
    }

    @Test
    fun `neighbouring surfaces can be told apart and pressed is darker than resting`() = eachScheme { p ->
        val order = listOf(p.background, p.panel, p.panelRaised, p.hover)
        for (i in 0 until order.size - 1) assertTrue(EditorPalette.contrast(order[i], order[i + 1]) > 1.0, "${p.id}: surface $i and ${i + 1} are identical")
        assertTrue(p.pressed.r + p.pressed.g + p.pressed.b < p.panelRaised.r + p.panelRaised.g + p.panelRaised.b, "${p.id}: pressed is darker than resting")
    }

    @Test
    fun `contrast of identical colours is 1 and black on white is 21`() {
        assertEquals(1.0, EditorPalette.contrast(EditorPalette.DEFAULT.panel, EditorPalette.DEFAULT.panel), 1e-9)
        assertEquals(21.0, EditorPalette.contrast(com.badlogic.gdx.graphics.Color.BLACK, com.badlogic.gdx.graphics.Color.WHITE), 1e-6)
    }

    @Test
    fun `schemes have unique ids and the default is not the generic blue`() {
        val ids = EditorPalette.SCHEMES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(EditorPalette.DEFAULT in EditorPalette.SCHEMES)
        assertTrue(EditorPalette.DEFAULT.id != EditorPalette.SLATE.id)
        assertTrue(EditorPalette.SCHEMES.size >= 4, "there is a real choice")
    }

    @Test
    fun `the chosen scheme is saved and an unknown one falls back to the default`() {
        val store = com.morgana.client.editor.dock.MemoryKeyValueStore()
        val settings = EditorSettings(store)
        assertEquals(EditorPalette.DEFAULT, settings.palette)
        settings.palette = EditorPalette.PARCHMENT
        assertEquals(EditorPalette.PARCHMENT, EditorSettings(store).palette)
        store.put(EditorSettings.KEY_SCHEME, "no-such-scheme")
        assertEquals(EditorPalette.DEFAULT, settings.palette)
    }

    @Test
    fun `the tile animation speed steps through the list and stops at the ends`() {
        assertEquals(300, stepSpeed(250, +1))
        assertEquals(200, stepSpeed(250, -1))
        assertEquals(250, stepSpeed(220, +1), "an odd value moves to the next one on the list")
        assertEquals(200, stepSpeed(220, -1))
        assertEquals(ANIMATION_SPEEDS.last(), stepSpeed(ANIMATION_SPEEDS.last(), +1))
        assertEquals(ANIMATION_SPEEDS.first(), stepSpeed(ANIMATION_SPEEDS.first(), -1))
    }
}
