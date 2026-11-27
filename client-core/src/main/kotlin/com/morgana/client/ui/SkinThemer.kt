package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.kotcrab.vis.ui.VisUI
import com.morgana.client.ui.editor.EditorPalette
import com.morgana.client.ui.editor.EditorSettings
import com.morgana.client.ui.editor.EditorTheme
import com.morgana.client.ui.editor.PreferencesKeyValueStore

/**
 * Recolours the shared VisUI skin from the chosen colour scheme, so every window built from stock styles (quest log,
 * spellbook, bank, dialogue, party, settings, ...) follows the scheme instead of staying in VisUI's default grey.
 *
 * Styles are mutated in place, so windows that are already open update too. Call [apply] once after `VisUI.load()` and
 * again whenever the scheme changes; [dispose] frees the generated textures (before `VisUI.dispose()`).
 */
object SkinThemer {
    private const val STORE_NAME = "morgana-editor-settings"

    private var theme: EditorTheme? = null

    /** The scheme saved by the F1 Settings panel, or [EditorPalette.DEFAULT]. */
    fun savedPalette(): EditorPalette =
        try {
            EditorSettings(PreferencesKeyValueStore(STORE_NAME)).palette
        } catch (_: Throwable) {
            EditorPalette.DEFAULT
        }

    /** Persists [palette] and restyles the skin. */
    fun choose(palette: EditorPalette) {
        EditorSettings(PreferencesKeyValueStore(STORE_NAME)).palette = palette
        apply(palette)
    }

    fun apply(palette: EditorPalette = savedPalette()) {
        if (!VisUI.isLoaded()) return
        val skin = VisUI.getSkin()
        val t = EditorTheme(palette)
        // Skin.getAll matches the exact class, and VisUI registers its own subclasses of the libgdx styles, so ask for both.
        fun <T> all(c: Class<T>): List<T> = skin.getAll(c)?.values()?.toList() ?: emptyList()

        val rim = com.badlogic.gdx.graphics.Color(
            (palette.border.r * 0.55f + palette.accent.r * 0.45f).coerceIn(0f, 1f),
            (palette.border.g * 0.55f + palette.accent.g * 0.45f).coerceIn(0f, 1f),
            (palette.border.b * 0.55f + palette.accent.b * 0.45f).coerceIn(0f, 1f),
            1f
        )
        val titleCol = com.badlogic.gdx.graphics.Color(
            (palette.text.r * 0.62f + palette.accent.r * 0.38f).coerceIn(0f, 1f),
            (palette.text.g * 0.62f + palette.accent.g * 0.38f).coerceIn(0f, 1f),
            (palette.text.b * 0.62f + palette.accent.b * 0.38f).coerceIn(0f, 1f),
            1f
        )
        val titleHeight = if (HudWindow.touchMode) 40 else 28
        val winBg = t.windowFrame(fill = palette.panel, highlight = rim, titleHeight = titleHeight, padX = 8f, padY = 8f)
        val btnUp = t.beveledPanel(palette.panelRaised, highlight = rim, padX = 10f, padY = 4f)
        val btnOver = t.beveledPanel(palette.hover, highlight = palette.accent, cornerAccent = palette.strong, padX = 10f, padY = 4f)
        val btnDown = t.beveledPanel(palette.pressed, highlight = palette.border, padX = 10f, padY = 4f)
        val btnChecked = t.beveledPanel(palette.accentDim, highlight = palette.accent, cornerAccent = palette.strong, padX = 10f, padY = 4f)
        val btnDisabled = t.beveledPanel(palette.panel, highlight = palette.border, padX = 10f, padY = 4f)

        for (s in all(Window.WindowStyle::class.java)) {
            s.titleFontColor = titleCol
            s.background = winBg
        }
        for (s in all(Label.LabelStyle::class.java)) {
            s.fontColor = palette.text
        }
        for (s in all(TextButton.TextButtonStyle::class.java) + all(com.kotcrab.vis.ui.widget.VisTextButton.VisTextButtonStyle::class.java)) {
            val b = t.buttonStyle
            s.fontColor = b.fontColor
            s.downFontColor = b.downFontColor
            s.overFontColor = palette.strong
            s.checkedFontColor = b.checkedFontColor
            s.checkedOverFontColor = b.checkedFontColor
            s.disabledFontColor = b.disabledFontColor
            s.up = btnUp
            s.over = btnOver
            s.down = btnDown
            s.checked = btnChecked
            s.checkedOver = btnChecked
            s.disabled = btnDisabled
        }
        for (s in all(TextField.TextFieldStyle::class.java) + all(com.kotcrab.vis.ui.widget.VisTextField.VisTextFieldStyle::class.java)) {
            s.fontColor = palette.text
            s.disabledFontColor = palette.textDisabled
            s.messageFontColor = palette.textMuted
            s.background = t.recessedSlot(palette.background, palette.border, padX = 6f, padY = 4f)
            s.focusedBackground = t.recessedSlot(palette.background, palette.accent, padX = 6f, padY = 4f)
            s.disabledBackground = t.recessedSlot(palette.panel, palette.border, padX = 6f, padY = 4f)
            s.selection = t.solid(palette.accentDim)
        }
        val cbOff = t.checkboxIcon(checked = false, hovered = false)
        val cbOn = t.checkboxIcon(checked = true, hovered = false)
        val cbOver = t.checkboxIcon(checked = false, hovered = true)
        val cbOnOver = t.checkboxIcon(checked = true, hovered = true)
        for (s in all(CheckBox.CheckBoxStyle::class.java)) {
            s.fontColor = palette.text
            s.overFontColor = palette.strong
            s.checkedFontColor = palette.text
            s.downFontColor = palette.text
            s.disabledFontColor = palette.textDisabled
            s.checkboxOff = cbOff
            s.checkboxOn = cbOn
            s.checkboxOver = cbOver
            s.checkboxOnOver = cbOnOver
        }
        for (s in all(com.kotcrab.vis.ui.widget.VisCheckBox.VisCheckBoxStyle::class.java)) {
            s.fontColor = palette.text
            s.overFontColor = palette.strong
            s.checkedFontColor = palette.text
            s.downFontColor = palette.text
            s.disabledFontColor = palette.textDisabled
            s.checkBackground = cbOff
            s.checkBackgroundOver = cbOver
            s.checkBackgroundDown = cbOver
            s.tick = cbOn
            s.tickDisabled = cbOff
        }
        val track = t.scrollTrack()
        val knob = t.scrollKnob()
        for (s in all(com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle::class.java)) {
            s.vScroll = track
            s.vScrollKnob = knob
            s.hScroll = track
            s.hScrollKnob = knob
        }
        for (s in all(com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle::class.java)) {
            s.background = track
            s.knob = knob
            s.knobOver = t.beveledPanel(palette.accent, highlight = palette.strong, padX = 4f, padY = 4f)
            s.knobDown = t.beveledPanel(palette.accentDim, highlight = palette.accent, padX = 4f, padY = 4f)
            s.knobBefore = t.solid(palette.accent)
        }
        if (skin.has("close-window", com.kotcrab.vis.ui.widget.VisImageButton.VisImageButtonStyle::class.java)) {
            val closeStyle = skin.get("close-window", com.kotcrab.vis.ui.widget.VisImageButton.VisImageButtonStyle::class.java)
            closeStyle.up = t.beveledPanel(palette.panelRaised, highlight = rim, padX = 3f, padY = 3f)
            closeStyle.over = t.beveledPanel(palette.hover, highlight = palette.accent, cornerAccent = palette.strong, padX = 3f, padY = 3f)
            closeStyle.down = t.beveledPanel(palette.pressed, highlight = palette.border, padX = 3f, padY = 3f)
        }

        fun themeList(l: com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle) {
            l.fontColorSelected = palette.strong
            l.fontColorUnselected = palette.text
            l.selection = t.solid(palette.accentDim)
            l.over = t.solid(palette.hover)
            l.down = t.solid(palette.pressed)
            l.background = t.bordered(palette.panel, palette.border, padX = 4f, padY = 2f)
        }
        for (s in skin.getAll(com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle::class.java).values()) themeList(s)
        for (s in skin.getAll(com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle::class.java).values()) {
            s.fontColor = palette.text
            s.disabledFontColor = palette.textDisabled
            s.background = t.bordered(palette.panelRaised, palette.border, padX = 6f, padY = 3f)
            s.backgroundOver = t.bordered(palette.hover, palette.accent, padX = 6f, padY = 3f)
            s.backgroundOpen = t.bordered(palette.pressed, palette.accent, padX = 6f, padY = 3f)
            s.backgroundDisabled = t.bordered(palette.panel, palette.border, padX = 6f, padY = 3f)
            s.listStyle?.let { themeList(it) }
            s.scrollStyle?.background = t.bordered(palette.panel, palette.border, padX = 1f, padY = 1f)
        }

        // Progress bars keep the skin's own shapes (their size comes from the drawables) but take the scheme's accent.
        fun tinted(d: com.badlogic.gdx.scenes.scene2d.utils.Drawable?, c: com.badlogic.gdx.graphics.Color): com.badlogic.gdx.scenes.scene2d.utils.Drawable? = when (d) {
            is com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable -> d.tint(c)
            is com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable -> d.tint(c)
            is com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable -> d.tint(c)
            else -> d
        }
        for (s in all(com.badlogic.gdx.scenes.scene2d.ui.ProgressBar.ProgressBarStyle::class.java)) {
            s.knobBefore = tinted(s.knobBefore, palette.accent)
            s.knobAfter = tinted(s.knobAfter, palette.border)
        }
        GameTheme.retintShared(palette)
        val old = theme
        theme = t
        old?.dispose()
    }

    fun dispose() {
        GameTheme.disposeShared()
        theme?.dispose()
        theme = null
    }
}
