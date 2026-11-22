package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.kotcrab.vis.ui.widget.Separator
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.ui.editor.EditorPalette
import com.morgana.client.ui.editor.EditorTheme

/**
 * Shared visual theme for the non-editor UI (Realm Hub, New Realm Wizard, in-game HUD, and Inventory/Shop windows),
 * built from the chosen [EditorPalette] so the entire engine feels cohesive.
 *
 * There is one long-lived instance ([shared]). When the colour scheme changes, [retint] re-points every style and
 * [dynamic] drawable in place, so windows that are already open recolour live without being rebuilt.
 */
class GameTheme private constructor(initial: EditorPalette) : Disposable {
    var palette: EditorPalette = initial
        private set
    var base = EditorTheme(initial)
        private set

    val windowStyle = Window.WindowStyle()
    val modalWindowStyle = Window.WindowStyle()
    val buttonStyle = VisTextButton.VisTextButtonStyle()
    val tabStyle = VisTextButton.VisTextButtonStyle()

    /** Small toggle for filter chips: tight padding, muted until chosen. */
    val chipStyle = VisTextButton.VisTextButtonStyle()

    /** High-emphasis primary action button (Enter Realm, Next, Create Realm). */
    val primaryButtonStyle = VisTextButton.VisTextButtonStyle()
    val labelStyle = Label.LabelStyle()
    val mutedLabelStyle = Label.LabelStyle()
    val accentLabelStyle = Label.LabelStyle()
    val separatorStyle = Separator.SeparatorStyle()
    val tooltipStyle = com.kotcrab.vis.ui.widget.Tooltip.TooltipStyle()

    private val dynamics = mutableListOf<SwappableDrawable>()

    /** A drawable rebuilt from the current theme whenever the scheme changes. */
    fun dynamic(build: (EditorTheme, EditorPalette) -> Drawable): Drawable =
        SwappableDrawable(build).also { it.refresh(base, palette); dynamics += it }

    val cardBackground: Drawable = dynamic { t, p -> t.bordered(p.panelRaised, p.border, padX = 10f, padY = 8f) }

    /** Card background for a selected list entry (realm, world, character). */
    val selectedCardBackground: Drawable = dynamic { t, p -> t.bordered(p.accentDim, p.accent, padX = 10f, padY = 8f) }

    init {
        fill(base, palette)
    }

    private fun fill(t: EditorTheme, p: EditorPalette) {
        copy(t.windowStyle, windowStyle)
        windowStyle.background = t.withTitleBar(t.beveledPanel(p.panel, highlight = p.border, padX = 6f, padY = 6f))
        modalWindowStyle.titleFont = t.font
        modalWindowStyle.titleFontColor = p.text
        modalWindowStyle.background = t.withTitleBar(t.beveledPanel(p.panel, highlight = p.accent, padX = 8f, padY = 8f))
        copy(t.buttonStyle, buttonStyle)
        buttonStyle.up = t.beveledPanel(p.panelRaised, highlight = p.border, padX = 10f, padY = 4f)
        buttonStyle.over = t.beveledPanel(p.hover, highlight = p.accent, padX = 10f, padY = 4f)
        buttonStyle.down = t.beveledPanel(p.pressed, highlight = p.border, padX = 10f, padY = 4f)
        copy(t.tabStyle, tabStyle)
        chipStyle.apply {
            font = t.font
            fontColor = p.textMuted
            overFontColor = p.text
            downFontColor = p.text
            checkedFontColor = p.strong
            checkedOverFontColor = p.strong
            up = t.bordered(p.panelRaised, p.border, padX = 6f, padY = 1f)
            over = t.bordered(p.hover, p.accent, padX = 6f, padY = 1f)
            down = t.bordered(p.pressed, p.border, padX = 6f, padY = 1f)
            checked = t.bordered(p.accentDim, p.accent, padX = 6f, padY = 1f)
            checkedOver = t.bordered(p.accentDim, p.accent, padX = 6f, padY = 1f)
        }
        primaryButtonStyle.apply {
            font = t.font
            fontColor = p.strong
            downFontColor = p.strong
            overFontColor = p.strong
            disabledFontColor = p.textDisabled
            up = t.beveledPanel(p.accentDim, highlight = p.accent, cornerAccent = p.strong, padX = 12f, padY = 5f)
            over = t.beveledPanel(p.accent, highlight = p.strong, cornerAccent = p.strong, padX = 12f, padY = 5f)
            down = t.beveledPanel(p.pressed, highlight = p.accent, padX = 12f, padY = 5f)
            disabled = t.beveledPanel(p.panel, highlight = p.border, padX = 12f, padY = 5f)
        }
        labelStyle.font = t.font; labelStyle.fontColor = p.text
        mutedLabelStyle.font = t.font; mutedLabelStyle.fontColor = p.textMuted
        accentLabelStyle.font = t.font; accentLabelStyle.fontColor = p.accent
        separatorStyle.background = t.separatorStyle.background
        tooltipStyle.background = t.tooltipStyle.background
        separatorStyle.thickness = t.separatorStyle.thickness
    }

    private fun copy(from: Window.WindowStyle, to: Window.WindowStyle) {
        to.titleFont = from.titleFont
        to.titleFontColor = from.titleFontColor
        to.background = from.background
    }

    private fun copy(f: VisTextButton.VisTextButtonStyle, t: VisTextButton.VisTextButtonStyle) {
        t.font = f.font
        t.fontColor = f.fontColor; t.downFontColor = f.downFontColor; t.overFontColor = f.overFontColor
        t.checkedFontColor = f.checkedFontColor; t.checkedOverFontColor = f.checkedOverFontColor
        t.disabledFontColor = f.disabledFontColor
        t.up = f.up; t.over = f.over; t.down = f.down
        t.checked = f.checked; t.checkedOver = f.checkedOver; t.disabled = f.disabled
    }

    /** Switches every style and dynamic drawable to [newPalette] in place. */
    fun retint(newPalette: EditorPalette) {
        if (newPalette === palette) return
        val old = base
        palette = newPalette
        base = EditorTheme(newPalette)
        fill(base, palette)
        dynamics.forEach { it.refresh(base, palette) }
        old.dispose()
    }

    fun divider(): Separator = Separator(separatorStyle)

    fun backdrop(): Image = Image(dynamic { t, _ -> t.solid(Color(0f, 0f, 0f, 0.55f)) }).apply {
        setFillParent(true)
    }

    fun vitalBar(fillColor: Color, width: Float = 138f, height: Float = 18f): VitalBar =
        VitalBar(this, fillColor, width, height)

    override fun dispose() {
        base.dispose()
        dynamics.clear()
        if (instance === this) instance = null
    }

    /** Forwards drawing and padding to a drawable that can be replaced. */
    private class SwappableDrawable(private val build: (EditorTheme, EditorPalette) -> Drawable) : BaseDrawable() {
        private var target: Drawable? = null

        fun refresh(t: EditorTheme, p: EditorPalette) {
            val d = build(t, p)
            target = d
            leftWidth = d.leftWidth
            rightWidth = d.rightWidth
            topHeight = d.topHeight
            bottomHeight = d.bottomHeight
            minWidth = d.minWidth
            minHeight = d.minHeight
        }

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            target?.draw(batch, x, y, width, height)
        }
    }

    companion object {
        private var instance: GameTheme? = null

        /** The one theme every game window uses; created on first use (needs a loaded VisUI and graphics). */
        val shared: GameTheme
            get() = instance ?: GameTheme(SkinThemer.savedPalette()).also { instance = it }

        /** Recolours the shared theme if it exists. */
        fun retintShared(palette: EditorPalette) {
            instance?.retint(palette)
        }

        fun disposeShared() {
            instance?.dispose()
        }
    }
}

/**
 * Compact RPG progress bar with a recessed dark track, glossy vertical-shaded fill, trailing damage band,
 * and drop-shadowed centered text overlay.
 */
class VitalBar(
    theme: GameTheme,
    fillColor: Color,
    barWidth: Float,
    barHeight: Float
) : VisTable() {
    private val fillImage = Image(theme.dynamic { t, _ -> t.glossyBar(fillColor) })
    /** A pale band behind the fill that drains slowly after damage, so a hit reads as a chunk leaving the bar. */
    private val trailImage = Image(theme.dynamic { t, _ -> t.solid(Color(1f, 0.96f, 0.88f, 0.62f)) })
    private val fillHolder = Table()
    private val trailHolder = Table()
    private val innerWidth = (barWidth - 4f).coerceAtLeast(0f)
    private var targetFraction = 0f
    private var shownFraction = 0f
    private var trailFraction = 0f
    private var pulse = 0f
    private val shadowLabel = VisLabel("", Label.LabelStyle(theme.labelStyle.font, Color(0f, 0f, 0f, 0.85f))).apply {
        setAlignment(Align.center)
    }
    private val textLabel = VisLabel("", theme.labelStyle).apply {
        setAlignment(Align.center)
    }

    init {
        val stack = Stack()
        val track = Table().apply {
            background = theme.dynamic { t, p -> t.recessedSlot(p.background, p.border, padX = 2f, padY = 2f) }
        }
        fillHolder.left().pad(2f)
        fillHolder.add(fillImage).growY().width(0f)
        trailHolder.left().pad(2f)
        trailHolder.add(trailImage).growY().width(0f)
        val shadowWrap = Table().apply { add(shadowLabel).grow().padLeft(2f).padTop(2f) }
        stack.add(track)
        stack.add(trailHolder)
        stack.add(fillHolder)
        stack.add(shadowWrap)
        stack.add(textLabel)
        add(stack).size(barWidth, barHeight)
    }

    fun update(text: String, current: Number, max: Number) {
        shadowLabel.setText(text)
        textLabel.setText(text)
        val cur = current.toDouble()
        val m = max.toDouble()
        val fraction = if (m > 0.0) (cur / m).toFloat().coerceIn(0f, 1f) else 0f
        if (!seen) { shownFraction = fraction; trailFraction = fraction; seen = true }
        targetFraction = fraction
    }

    private var seen = false

    /** Eases the fill toward the value, drains the trail behind it, and pulses the fill when the value is low. */
    override fun act(delta: Float) {
        super.act(delta)
        if (com.morgana.client.access.Accessibility.reduceMotion) {
            // No easing, no draining trail and no pulse: the bar jumps to the value; a low bar is dimmer instead of flashing.
            shownFraction = targetFraction
            trailFraction = targetFraction
            fillImage.color.a = if (targetFraction in 0.001f..LOW) 0.7f else 1f
            fillHolder.getCell(fillImage)?.width(innerWidth * shownFraction)
            trailHolder.getCell(trailImage)?.width(0f)
            fillHolder.invalidate()
            trailHolder.invalidate()
            return
        }
        shownFraction += (targetFraction - shownFraction) * minOf(1f, delta * 12f)
        if (Math.abs(targetFraction - shownFraction) < 0.002f) shownFraction = targetFraction
        trailFraction = if (trailFraction < shownFraction) shownFraction
        else maxOf(shownFraction, trailFraction - delta * 0.35f)
        pulse += delta
        fillImage.color.a = if (targetFraction in 0.001f..LOW) 0.65f + 0.35f * Math.sin(pulse * 7.0).toFloat() else 1f
        fillHolder.getCell(fillImage)?.width(innerWidth * shownFraction)
        trailHolder.getCell(trailImage)?.width(innerWidth * trailFraction)
        fillHolder.invalidate()
        trailHolder.invalidate()
    }

    private companion object {
        /** Below this fraction the bar pulses to warn the player. */
        const val LOW = 0.25f
    }
}
