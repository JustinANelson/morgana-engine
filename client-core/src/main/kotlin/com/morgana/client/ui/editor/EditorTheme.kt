package com.morgana.client.ui.editor

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.Separator
import com.kotcrab.vis.ui.widget.Tooltip
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow

/**
 * The editor's look: window, button, tab, label and tooltip styles, the icon sheet, and small factories for the
 * widgets every editor panel needs.
 *
 * Styles are built here in code and passed to widgets explicitly, so the game's own HUD (which uses the stock VisUI skin)
 * is not affected. Needs a graphics context and a loaded VisUI (for the font); create it once and [dispose] it with the editor.
 * Colours come from [EditorPalette] and artwork from [IconArt].
 */
class EditorTheme(
    /** The colour scheme every style is built from. */
    val palette: EditorPalette = EditorPalette.DEFAULT
) : Disposable {
    val font: BitmapFont = VisUI.getSkin().getFont("default-font")
    private val textures = mutableListOf<Texture>()
    private val white: TextureRegion = TextureRegion(texture(1, 1) { it.drawPixel(0, 0, 0xffffffff.toInt()) })
    private val iconRegions: Map<String, TextureRegion>

    init {
        // One strip holding every icon side by side.
        val names = IconArt.names
        val sheet = texture(IconArt.SIZE * names.size, IconArt.SIZE) { pm ->
            names.forEachIndexed { index, name ->
                IconArt.icons.getValue(name).forEachIndexed { y, row ->
                    row.forEachIndexed { x, c ->
                        val alpha = IconArt.alphaOf(c) ?: 0
                        if (alpha > 0) pm.drawPixel(index * IconArt.SIZE + x, y, (0xffffff00.toInt()) or alpha)
                    }
                }
            }
        }
        iconRegions = names.mapIndexed { i, name -> name to TextureRegion(sheet, i * IconArt.SIZE, 0, IconArt.SIZE, IconArt.SIZE) }.toMap()
    }

    // ---- drawables ----

    /** A flat rectangle of one colour. */
    fun solid(color: Color): Drawable = TextureRegionDrawable(white).tint(color)

    /**
     * A resizable panel: [fill] inside a 1 px [border], optionally with a thicker [accentBottom] strip along the bottom
     * (used for the selected tab). [padX] and [padY] are the inner padding.
     */
    fun bordered(fill: Color, border: Color, accentBottom: Color? = null, padX: Float = 0f, padY: Float = 0f): Drawable {
        val bottom = if (accentBottom != null) 2 else 1
        val height = 2 + bottom
        val tex = texture(3, height) { pm ->
            for (y in 0 until height) for (x in 0 until 3) {
                val edgeX = x == 0 || x == 2
                val color = when {
                    accentBottom != null && y >= height - 2 -> accentBottom
                    y == 0 || edgeX -> border
                    else -> fill
                }
                pm.drawPixel(x, y, Color.rgba8888(color))
            }
        }
        val patch = NinePatch(TextureRegion(tex), 1, 1, 1, bottom)
        return NinePatchDrawable(patch).also {
            it.leftWidth = padX.coerceAtLeast(1f)
            it.rightWidth = padX.coerceAtLeast(1f)
            it.topHeight = padY.coerceAtLeast(1f)
            it.bottomHeight = padY.coerceAtLeast(bottom.toFloat())
        }
    }

    /**
     * An ornate 9-px pixel-art RPG frame: 1 px deep outer outline, 1 px top-left lit bevel / bottom-right shadow,
     * 1 px subtle inner trim, and crisp metallic corner studs.
     */
    fun beveledPanel(
        fill: Color,
        outerBorder: Color = Color(0.05f, 0.04f, 0.04f, fill.a),
        highlight: Color = palette.border,
        shadow: Color = Color(0.08f, 0.07f, 0.08f, fill.a),
        cornerAccent: Color = Color(
            (highlight.r * 0.65f + palette.accent.r * 0.35f).coerceIn(0f, 1f),
            (highlight.g * 0.65f + palette.accent.g * 0.35f).coerceIn(0f, 1f),
            (highlight.b * 0.65f + palette.accent.b * 0.35f).coerceIn(0f, 1f),
            fill.a
        ),
        padX: Float = 0f,
        padY: Float = 0f
    ): Drawable {
        val innerLine = Color(
            (fill.r * 0.72f).coerceIn(0f, 1f),
            (fill.g * 0.72f).coerceIn(0f, 1f),
            (fill.b * 0.72f).coerceIn(0f, 1f),
            fill.a
        )
        val topLit = Color(
            (highlight.r * 1.18f).coerceIn(0f, 1f),
            (highlight.g * 1.18f).coerceIn(0f, 1f),
            (highlight.b * 1.18f).coerceIn(0f, 1f),
            fill.a
        )
        val tex = texture(9, 9) { pm ->
            for (y in 0 until 9) for (x in 0 until 9) {
                val isCornerStud = (x in 1..2 && y in 1..2) || (x in 6..7 && y in 1..2) ||
                    (x in 1..2 && y in 6..7) || (x in 6..7 && y in 6..7)
                val color = when {
                    x == 0 || x == 8 || y == 0 || y == 8 -> outerBorder
                    isCornerStud && (x == 1 || x == 7) && (y == 1 || y == 7) -> cornerAccent
                    isCornerStud -> shadow
                    y == 1 && x in 2..6 -> topLit
                    x == 1 && y in 2..6 -> topLit
                    y == 7 && x in 2..6 -> shadow
                    x == 7 && y in 2..6 -> shadow
                    x == 2 || x == 6 || y == 2 || y == 6 -> innerLine
                    else -> fill
                }
                pm.drawPixel(x, y, Color.rgba8888(color))
            }
        }
        val patch = NinePatch(TextureRegion(tex), 3, 3, 3, 3)
        return NinePatchDrawable(patch).also {
            it.leftWidth = padX.coerceAtLeast(3f)
            it.rightWidth = padX.coerceAtLeast(3f)
            it.topHeight = padY.coerceAtLeast(3f)
            it.bottomHeight = padY.coerceAtLeast(3f)
        }
    }

    /** A recessed socket frame (dark top-left inner shadow, subtle bottom-right rim) for hotbar slots, portraits and bars. */
    fun recessedSlot(
        fill: Color,
        border: Color = palette.border,
        outerBorder: Color = Color(0.05f, 0.04f, 0.04f, fill.a),
        padX: Float = 2f,
        padY: Float = 2f
    ): Drawable {
        val innerShadow = Color(
            (fill.r * 0.45f).coerceIn(0f, 1f),
            (fill.g * 0.45f).coerceIn(0f, 1f),
            (fill.b * 0.45f).coerceIn(0f, 1f),
            fill.a
        )
        val innerRim = Color(
            (border.r * 0.85f).coerceIn(0f, 1f),
            (border.g * 0.85f).coerceIn(0f, 1f),
            (border.b * 0.85f).coerceIn(0f, 1f),
            fill.a
        )
        val tex = texture(7, 7) { pm ->
            for (y in 0 until 7) for (x in 0 until 7) {
                val color = when {
                    x == 0 || x == 6 || y == 0 || y == 6 -> outerBorder
                    x == 1 || y == 1 -> innerShadow
                    x == 5 || y == 5 -> innerRim
                    else -> fill
                }
                pm.drawPixel(x, y, Color.rgba8888(color))
            }
        }
        val patch = NinePatch(TextureRegion(tex), 2, 2, 2, 2)
        return NinePatchDrawable(patch).also {
            it.leftWidth = padX.coerceAtLeast(2f)
            it.rightWidth = padX.coerceAtLeast(2f)
            it.topHeight = padY.coerceAtLeast(2f)
            it.bottomHeight = padY.coerceAtLeast(2f)
        }
    }

    /** A vertical glossy gradient strip for vital, XP and boss bars (bright top sheen, rich center, shaded base). */
    fun glossyBar(fillColor: Color): Drawable {
        val tex = texture(1, 8) { pm ->
            val factors = floatArrayOf(1.32f, 1.14f, 1.04f, 0.98f, 0.90f, 0.80f, 0.70f, 0.56f)
            for (y in 0 until 8) {
                val f = factors[y]
                val c = Color(
                    (fillColor.r * f).coerceIn(0f, 1f),
                    (fillColor.g * f).coerceIn(0f, 1f),
                    (fillColor.b * f).coerceIn(0f, 1f),
                    fillColor.a
                )
                pm.drawPixel(0, y, Color.rgba8888(c))
            }
        }
        return TextureRegionDrawable(TextureRegion(tex))
    }

    /** A window frame with room above its content for the title (a window draws its title in the top padding). */
    fun withTitleBar(frame: Drawable): Drawable = frame.also { it.topHeight = TITLE_BAR_HEIGHT }

    /**
     * An ornate RPG window frame with a distinct shaded title bar band, bronze/accent top-left bevel,
     * metallic corner studs, and a burnished separator rule between the title bar and body well.
     */
    fun windowFrame(
        fill: Color = palette.panel,
        headerFill: Color = Color(
            (fill.r * 0.72f).coerceIn(0f, 1f),
            (fill.g * 0.68f).coerceIn(0f, 1f),
            (fill.b * 0.64f).coerceIn(0f, 1f),
            fill.a
        ),
        outerBorder: Color = Color(0.05f, 0.04f, 0.04f, fill.a),
        highlight: Color = Color(
            (palette.border.r * 0.58f + palette.accent.r * 0.42f).coerceIn(0f, 1f),
            (palette.border.g * 0.58f + palette.accent.g * 0.42f).coerceIn(0f, 1f),
            (palette.border.b * 0.58f + palette.accent.b * 0.42f).coerceIn(0f, 1f),
            fill.a
        ),
        shadow: Color = Color(0.09f, 0.07f, 0.06f, fill.a),
        cornerAccent: Color = Color(
            (highlight.r * 0.55f + palette.accent.r * 0.45f).coerceIn(0f, 1f),
            (highlight.g * 0.55f + palette.accent.g * 0.45f).coerceIn(0f, 1f),
            (highlight.b * 0.55f + palette.accent.b * 0.45f).coerceIn(0f, 1f),
            fill.a
        ),
        titleHeight: Int = TITLE_BAR_HEIGHT.toInt(),
        padX: Float = 8f,
        padY: Float = 8f
    ): Drawable {
        val topH = titleHeight.coerceAtLeast(24)
        val w = 13
        val h = topH + 10
        val topLit = Color(
            (highlight.r * 1.18f).coerceIn(0f, 1f),
            (highlight.g * 1.18f).coerceIn(0f, 1f),
            (highlight.b * 1.18f).coerceIn(0f, 1f),
            fill.a
        )
        val headerSheen = Color(
            (headerFill.r * 1.24f).coerceIn(0f, 1f),
            (headerFill.g * 1.22f).coerceIn(0f, 1f),
            (headerFill.b * 1.18f).coerceIn(0f, 1f),
            fill.a
        )
        val innerLine = Color(
            (fill.r * 0.68f).coerceIn(0f, 1f),
            (fill.g * 0.65f).coerceIn(0f, 1f),
            (fill.b * 0.62f).coerceIn(0f, 1f),
            fill.a
        )
        val tex = texture(w, h) { pm ->
            for (y in 0 until h) for (x in 0 until w) {
                val isTopCorner = y in 1..2 && (x in 1..2 || x in (w - 3)..(w - 2))
                val isBotCorner = y in (h - 3)..(h - 2) && (x in 1..2 || x in (w - 3)..(w - 2))
                val isCorner = isTopCorner || isBotCorner
                val isCornerTip = isCorner && (x == 1 || x == w - 2) && (y == 1 || y == h - 2)
                val color = when {
                    x == 0 || x == w - 1 || y == 0 || y == h - 1 -> outerBorder
                    isCornerTip -> cornerAccent
                    isCorner -> shadow
                    y == 1 -> topLit
                    y == h - 2 -> shadow
                    x == 1 -> topLit
                    x == w - 2 -> shadow
                    y == 2 && x in 2..(w - 3) -> headerSheen
                    y == topH - 2 && x in 2..(w - 3) -> highlight
                    y == topH - 1 && x in 2..(w - 3) -> shadow
                    y < topH - 2 -> headerFill
                    x == 2 || x == w - 3 || y == h - 3 -> innerLine
                    else -> fill
                }
                pm.drawPixel(x, y, Color.rgba8888(color))
            }
        }
        val patch = NinePatch(TextureRegion(tex), 4, 4, topH, 4)
        return NinePatchDrawable(patch).also {
            it.leftWidth = padX.coerceAtLeast(4f)
            it.rightWidth = padX.coerceAtLeast(4f)
            it.topHeight = topH.toFloat()
            it.bottomHeight = padY.coerceAtLeast(4f)
        }
    }

    /** A recessed dark scrollbar channel with a subtle outer rim. */
    fun scrollTrack(thickness: Int = 10): Drawable {
        val fill = Color(
            (palette.background.r * 0.8f).coerceIn(0f, 1f),
            (palette.background.g * 0.8f).coerceIn(0f, 1f),
            (palette.background.b * 0.8f).coerceIn(0f, 1f),
            0.9f
        )
        val border = Color(palette.border.r, palette.border.g, palette.border.b, 0.55f)
        val tex = texture(5, 5) { pm ->
            for (y in 0 until 5) for (x in 0 until 5) {
                val c = if (x == 0 || x == 4 || y == 0 || y == 4) border else fill
                pm.drawPixel(x, y, Color.rgba8888(c))
            }
        }
        return NinePatchDrawable(NinePatch(TextureRegion(tex), 1, 1, 1, 1)).also {
            it.minWidth = thickness.toFloat()
            it.minHeight = thickness.toFloat()
        }
    }

    /** A beveled bronze scrollbar thumb. */
    fun scrollKnob(thickness: Int = 10): Drawable {
        val base = Color(
            (palette.border.r * 0.65f + palette.accent.r * 0.35f).coerceIn(0f, 1f),
            (palette.border.g * 0.65f + palette.accent.g * 0.35f).coerceIn(0f, 1f),
            (palette.border.b * 0.65f + palette.accent.b * 0.35f).coerceIn(0f, 1f),
            1f
        )
        val lit = Color((base.r * 1.25f).coerceIn(0f, 1f), (base.g * 1.25f).coerceIn(0f, 1f), (base.b * 1.25f).coerceIn(0f, 1f), 1f)
        val dark = Color((base.r * 0.55f).coerceIn(0f, 1f), (base.g * 0.55f).coerceIn(0f, 1f), (base.b * 0.55f).coerceIn(0f, 1f), 1f)
        val tex = texture(7, 7) { pm ->
            for (y in 0 until 7) for (x in 0 until 7) {
                val c = when {
                    x == 0 || x == 6 || y == 0 || y == 6 -> Color(0.05f, 0.04f, 0.04f, 0.9f)
                    x == 1 || y == 1 -> lit
                    x == 5 || y == 5 -> dark
                    else -> base
                }
                pm.drawPixel(x, y, Color.rgba8888(c))
            }
        }
        return NinePatchDrawable(NinePatch(TextureRegion(tex), 2, 2, 2, 2)).also {
            it.minWidth = thickness.toFloat()
            it.minHeight = thickness.toFloat()
        }
    }

    /** A crisp 16x16 RPG checkbox square (recessed box with bronze rim and warm gold tick when [checked]). */
    fun checkboxIcon(checked: Boolean, hovered: Boolean = false): Drawable {
        val rim = if (hovered || checked) palette.accent else palette.border
        val bg = if (checked) palette.accentDim else palette.background
        val outer = Color(0.05f, 0.04f, 0.04f, 1f)
        val tick = palette.strong
        val tex = texture(16, 16) { pm ->
            for (y in 0 until 16) for (x in 0 until 16) {
                val c = when {
                    x == 0 || x == 15 || y == 0 || y == 15 -> outer
                    x == 1 || x == 14 || y == 1 || y == 14 -> rim
                    else -> bg
                }
                pm.drawPixel(x, y, Color.rgba8888(c))
            }
            if (checked) {
                val pts = listOf(4 to 8, 5 to 9, 6 to 10, 7 to 9, 8 to 8, 9 to 7, 10 to 6, 11 to 5)
                for ((px, py) in pts) {
                    pm.drawPixel(px, py, Color.rgba8888(tick))
                    pm.drawPixel(px, py + 1, Color.rgba8888(tick))
                }
            }
        }
        return TextureRegionDrawable(TextureRegion(tex))
    }

    /** An icon from [IconArt], tinted. */
    fun icon(name: String, tint: Color = palette.text): Drawable {
        val region = iconRegions[name] ?: error("Unknown editor icon '$name'")
        return TextureRegionDrawable(region).tint(tint)
    }

    // ---- styles ----

    val windowStyle: Window.WindowStyle = Window.WindowStyle(font, palette.text, withTitleBar(bordered(palette.panel, palette.border, padX = 6f, padY = 6f)))

    val label: Label.LabelStyle = Label.LabelStyle(font, palette.text)
    val mutedLabel: Label.LabelStyle = Label.LabelStyle(font, palette.textMuted)
    val accentLabel: Label.LabelStyle = Label.LabelStyle(font, palette.accent)
    val warningLabel: Label.LabelStyle = Label.LabelStyle(font, palette.warning)

    val tooltipStyle: Tooltip.TooltipStyle = Tooltip.TooltipStyle(bordered(palette.panelRaised, palette.border, padX = 6f, padY = 4f))

    val separatorStyle: Separator.SeparatorStyle = Separator.SeparatorStyle(solid(palette.border), 1)

    /** A normal push button. */
    val buttonStyle: VisTextButton.VisTextButtonStyle = VisTextButton.VisTextButtonStyle().apply {
        font = this@EditorTheme.font
        fontColor = palette.text
        downFontColor = palette.text
        overFontColor = palette.text
        checkedFontColor = palette.strong
        disabledFontColor = palette.textDisabled
        up = bordered(palette.panelRaised, palette.border, padX = 10f, padY = 4f)
        over = bordered(palette.hover, palette.accent, padX = 10f, padY = 4f)
        down = bordered(palette.pressed, palette.border, padX = 10f, padY = 4f)
        checked = bordered(palette.accentDim, palette.accent, padX = 10f, padY = 4f)
        checkedOver = bordered(palette.accentDim, palette.accent, padX = 10f, padY = 4f)
        disabled = bordered(palette.panel, palette.border, padX = 10f, padY = 4f)
    }

    /** A flat tab: quiet until hovered, with an accent underline when selected. */
    val tabStyle: VisTextButton.VisTextButtonStyle = VisTextButton.VisTextButtonStyle().apply {
        font = this@EditorTheme.font
        fontColor = palette.textMuted
        overFontColor = palette.text
        downFontColor = palette.text
        checkedFontColor = palette.text
        up = bordered(palette.background, palette.background, padX = 10f, padY = 5f)
        over = bordered(palette.hover, palette.hover, padX = 10f, padY = 5f)
        down = bordered(palette.pressed, palette.pressed, padX = 10f, padY = 5f)
        checked = bordered(palette.panel, palette.border, accentBottom = palette.accent, padX = 10f, padY = 5f)
        checkedOver = bordered(palette.panel, palette.border, accentBottom = palette.accent, padX = 10f, padY = 5f)
    }

    /** Builds the style for a square icon button; one per icon because each draws its own picture. */
    fun iconButtonStyle(iconName: String): VisImageButton.VisImageButtonStyle = VisImageButton.VisImageButtonStyle().apply {
        up = bordered(palette.panelRaised, palette.border)
        over = bordered(palette.hover, palette.accent)
        down = bordered(palette.pressed, palette.border)
        checked = bordered(palette.accentDim, palette.accent)
        checkedOver = bordered(palette.accentDim, palette.accent)
        disabled = bordered(palette.panel, palette.border)
        imageUp = icon(iconName, palette.text)
        imageOver = icon(iconName, palette.strong)
        imageDown = icon(iconName, palette.textMuted)
        imageChecked = icon(iconName, palette.strong)
        imageCheckedOver = icon(iconName, palette.strong)
        imageDisabled = icon(iconName, palette.textDisabled)
    }

    // ---- widget factories ----

    /** A themed window. */
    fun window(title: String): VisWindow = VisWindow(title, windowStyle)

    fun textButton(text: String, onClick: () -> Unit = {}): VisTextButton =
        VisTextButton(text, buttonStyle).also { b -> b.onChange { onClick() } }

    /** A toggle styled as a tab; add several to a [tabGroup] so exactly one is selected. */
    fun tab(text: String): VisTextButton = VisTextButton(text, tabStyle).also { it.setProgrammaticChangeEvents(false) }

    /** A square icon button (28 px) with a tooltip. [toggle] makes it stay pressed, for tool selection. */
    fun iconButton(iconName: String, tooltip: String, toggle: Boolean = false, onClick: () -> Unit = {}): VisImageButton {
        val b = VisImageButton(iconButtonStyle(iconName))
        b.image.setScaling(com.badlogic.gdx.utils.Scaling.none)
        b.setProgrammaticChangeEvents(false)
        if (!toggle) b.onChange { onClick() } else b.onChange { if (b.isChecked) onClick() }
        tip(b, tooltip)
        return b
    }

    /** A group where exactly one button is selected, like a row of tools or tabs. */
    fun <T : Button> selectGroup(vararg buttons: T): ButtonGroup<T> =
        ButtonGroup<T>().apply {
            setMinCheckCount(1)
            setMaxCheckCount(1)
            buttons.forEach { add(it) }
        }

    fun tip(target: Actor, text: String) {
        val tooltip = AnchoredTooltip(tooltipStyle)
        tooltip.setText(text)
        tooltip.setTarget(target)
    }

    /**
     * A tooltip that sits under its target at a whole screen pixel. VisUI's own tooltip follows the mouse on every move,
     * so at 2x and 3x zoom its text landed on fractional pixels and appeared to wobble left and right.
     */
    private class AnchoredTooltip(style: Tooltip.TooltipStyle) : Tooltip(style) {
        override fun setPosition(x: Float, y: Float) {
            val target = target
            val stage = target?.stage
            if (target == null || stage == null) {
                super.setPosition(x, y)
                return
            }
            pack()
            val corner = target.localToStageCoordinates(com.badlogic.gdx.math.Vector2(0f, 0f))
            val perPixel = (stage.viewport as? com.badlogic.gdx.utils.viewport.ScreenViewport)?.unitsPerPixel ?: 1f
            fun snap(v: Float) = Math.round(v / perPixel) * perPixel
            val px = (corner.x + target.width / 2f - width / 2f).coerceIn(0f, (stage.width - width).coerceAtLeast(0f))
            var py = corner.y - height - 4f * perPixel
            if (py < 0f) py = corner.y + target.height + 4f * perPixel
            super.setPosition(snap(px), snap(py))
        }
    }

    fun text(text: String): VisLabel = VisLabel(text, label)

    fun muted(text: String): VisLabel = VisLabel(text, mutedLabel)

    /** A small section heading with a rule beneath it, for grouping controls inside a panel. */
    fun heading(title: String): VisTable = VisTable().also { t ->
        t.add(VisLabel(title.uppercase(), accentLabel)).left().padBottom(2f).row()
        t.add(Separator(separatorStyle)).growX().row()
    }

    fun separator(): Separator = Separator(separatorStyle)

    // ---- plumbing ----

    private fun texture(width: Int, height: Int, draw: (Pixmap) -> Unit): Texture {
        val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
        draw(pixmap)
        val tex = Texture(pixmap)
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        pixmap.dispose()
        textures += tex
        return tex
    }

    override fun dispose() {
        textures.forEach { it.dispose() }
        textures.clear()
    }
}

/** Height of the strip at the top of a window frame that holds its title. */
internal const val TITLE_BAR_HEIGHT = 24f

/** Runs [action] when the widget's state changes. */
internal inline fun Actor.onChange(crossinline action: () -> Unit) {
    addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) = action()
    })
}
