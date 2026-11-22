package com.morgana.client.ui.bag

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.ui.HudWindow
import com.morgana.client.ui.editor.giveScrollFocusOnHover

/** Small widget helpers shared by the inventory and shop windows. */
internal object BagUi {
    private val palette get() = com.morgana.client.ui.GameTheme.shared.palette

    val gold: Color get() = palette.accent

    fun color(tone: Tone): Color = when (tone) {
        Tone.GOOD -> palette.success
        Tone.BAD -> palette.danger
        Tone.MUTED -> palette.textMuted
        Tone.NEUTRAL -> palette.text
    }

    val font: BitmapFont get() = VisUI.getSkin().getFont("default-font")

    /** Side of one grid square: a little bigger on touch screens. */
    val cellSize: Float get() = if (HudWindow.touchMode) 48f else 42f

    val buttonHeight: Float get() = if (HudWindow.touchMode) 40f else 28f

    fun label(text: String, tone: Tone = Tone.NEUTRAL): VisLabel = VisLabel(text).also { it.color = color(tone) }

    /** Parses a `#rrggbb` rarity colour; null (or anything malformed) gives null so the caller keeps its usual colour. */
    fun hexColor(hex: String?): Color? {
        val h = hex?.removePrefix("#") ?: return null
        if (h.length != 6 || h.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
        return Color.valueOf(h)
    }

    /** An item's name, wrapped, in its rarity colour [hex] (the normal text colour for plain items). */
    fun rarityTitle(text: String, hex: String?): VisLabel = wrapped(text).also { l -> hexColor(hex)?.let { l.color = it } }

    /** A label that wraps inside the width the caller gives its table cell. */
    fun wrapped(text: String, tone: Tone = Tone.NEUTRAL): VisLabel = label(text, tone).also { it.setWrap(true) }

    fun button(text: String, enabled: Boolean = true, onClick: () -> Unit): VisTextButton =
        VisTextButton(text).also { b ->
            b.isDisabled = !enabled
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { if (!b.isDisabled) onClick() }
            })
        }

    /**
     * A toggle used as a tab or filter chip: [checked] shows it pressed. Only a player's tap calls [onClick]; checking it
     * from code (a button group pressing its first chip when the chip is added) must not, or building the chips would
     * redraw the window, which builds the chips again, without end.
     */
    fun chip(text: String, checked: Boolean, onClick: () -> Unit): VisTextButton =
        VisTextButton(text, "toggle").also { b ->
            b.setProgrammaticChangeEvents(false)
            b.isChecked = checked
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { if (b.isChecked) onClick() }
            })
        }

    fun scroll(content: Actor, stage: Stage): VisScrollPane = VisScrollPane(content).also {
        it.setScrollingDisabled(true, false)
        it.setFadeScrollBars(false)
        it.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, it)
    }

    fun icon(assets: ProceduralRetroAssets, iconId: Int, size: Float): Image =
        Image(TextureRegionDrawable(assets.getItemIconRegion(iconId))).also { it.setSize(size, size) }

    /** Size for a window that should be at most [maxW] x [maxH] but always fits the screen with a margin. */
    fun fit(stage: Stage, maxW: Float, maxH: Float): Pair<Float, Float> =
        minOf(maxW, stage.width - 16f).coerceAtLeast(240f) to minOf(maxH, stage.height - 16f).coerceAtLeast(200f)
}
