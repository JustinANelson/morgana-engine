package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.kotcrab.vis.ui.widget.VisLabel
import com.morgana.client.access.Accessibility
import com.morgana.client.access.PaletteRole
import com.morgana.client.net.LinkLevel
import com.morgana.client.net.LinkQuality

/**
 * A small signal indicator at the top centre of the screen: four bars and the round-trip time. The bar count carries the
 * meaning, so it reads without colour; colour only reinforces it (and follows the colour-blind palettes). Never
 * intercepts touches, and stays hidden outside the world.
 */
class LinkQualityIndicator(private val stage: Stage) : Actor() {
    private val pixel: Texture = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(Color.WHITE)
        pm.fill()
        Texture(pm).also { pm.dispose() }
    }
    private val label = VisLabel("")
    private var quality: LinkQuality? = null
    private val fair = Color(0.98f, 0.8f, 0.08f, 1f)

    init {
        touchable = Touchable.disabled
        isVisible = false
        stage.addActor(this)
    }

    /** Shows [next] (or hides the indicator when null). */
    fun update(next: LinkQuality?) {
        quality = next
        isVisible = next != null
        if (next == null) return
        if (label.text.toString() != next.text) label.setText(next.text)
        label.pack()
        setSize(BARS_WIDTH + 6f + label.width, BAR_MAX_HEIGHT)
        setPosition((stage.width - width) / 2f, stage.height - height - 4f)
        toFront()
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val q = quality ?: return
        val base = when (q.level) {
            LinkLevel.GOOD -> Accessibility.colour(PaletteRole.GOOD)
            LinkLevel.FAIR -> fair
            LinkLevel.POOR, LinkLevel.LOST -> Accessibility.colour(PaletteRole.BAD)
        }
        for (i in 0 until BAR_COUNT) {
            val h = 4f + i * 3f
            if (i < q.bars) batch.setColor(base.r, base.g, base.b, 0.95f * parentAlpha)
            else batch.setColor(0.7f, 0.7f, 0.7f, 0.3f * parentAlpha)
            batch.draw(pixel, x + i * (BAR_WIDTH + 2f), y, BAR_WIDTH, h)
        }
        batch.setColor(Color.WHITE)
        label.color.a = 0.85f * parentAlpha
        label.setPosition(x + BARS_WIDTH + 6f, y - 3f)
        label.draw(batch, parentAlpha)
    }

    private companion object {
        const val BAR_COUNT = 4
        const val BAR_WIDTH = 4f
        const val BARS_WIDTH = BAR_COUNT * (BAR_WIDTH + 2f)
        const val BAR_MAX_HEIGHT = 13f
    }
}
