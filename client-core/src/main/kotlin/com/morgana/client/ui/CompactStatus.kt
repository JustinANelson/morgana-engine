package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.game.BlankGame

/**
 * Minimal always-on status for small screens: the character's name/level, one thin bar per vital, and an XP bar over
 * a translucent backdrop so the name stays legible on light map tiles. Touches pass straight through it.
 */
class CompactStatus(
    private val worldState: ClientWorldState,
    private val font: () -> BitmapFont
) : Actor() {
    private val pixel: Texture = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(Color.WHITE)
        pm.fill()
        Texture(pm).also { pm.dispose() }
    }
    private val layout = GlyphLayout()

    init {
        touchable = Touchable.disabled
        setSize(BAR_WIDTH + PAD_X * 2f, 70f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val ch = worldState.localCharacter ?: return
        val game0 = worldState.gameDefinition
        val shown = game0?.let { VitalBars.visibleIds(it, worldState.catalog.spells, ch.classId).toSet() }
        val vitals = game0?.vitals?.filter { shown == null || it.id in shown }?.map { it.id to it.short }
            ?: ch.vitals.keys.map { it to it }
        val top = y + height
        val f = font()
        val palette = GameTheme.shared.palette
        val title = "${ch.name}  Lv.${ch.level}"
        layout.setText(f, title)

        // Short vital names ("HP", "MP", "XP") sit left of the bars so each bar can be told apart.
        val labels = vitals.map { it.second } + "XP"
        var labelW = 0f
        for (l in labels) { layout.setText(f, l); labelW = maxOf(labelW, layout.width) }
        val barX = x + PAD_X + labelW + LABEL_GAP
        layout.setText(f, title)
        val barCount = vitals.size + 1
        val barsHeight = barCount * BAR_HEIGHT + (barCount - 1) * BAR_GAP + 2f
        val boxW = maxOf(labelW + LABEL_GAP + BAR_WIDTH, layout.width) + PAD_X * 2f
        val boxH = f.lineHeight + barsHeight + PAD_Y * 2f
        val boxY = top - boxH

        val lifeVital = vitals.firstOrNull()?.first?.let { ch.vitals[it] } ?: ch.vitals.values.firstOrNull()
        val critical = lifeVital != null && LowHpVignetteModel.isCritical(lifeVital.cur, lifeVital.max)

        batch.setColor(palette.background.r, palette.background.g, palette.background.b, 0.78f * parentAlpha)
        batch.draw(pixel, x, boxY, boxW, boxH)
        if (critical) {
            batch.setColor(0.92f, 0.2f, 0.22f, 0.9f * parentAlpha)
        } else {
            batch.setColor(palette.border.r, palette.border.g, palette.border.b, 0.65f * parentAlpha)
        }
        batch.draw(pixel, x, boxY, boxW, 1f)
        batch.draw(pixel, x, top - 1f, boxW, 1f)
        batch.draw(pixel, x, boxY, 1f, boxH)
        batch.draw(pixel, x + boxW - 1f, boxY, 1f, boxH)

        f.color = Color(palette.text.r, palette.text.g, palette.text.b, 0.95f * parentAlpha)
        f.draw(batch, title, x + PAD_X, top - PAD_Y)
        var by = top - PAD_Y - f.lineHeight - 2f
        vitals.forEachIndexed { i, (id, short) ->
            val vs = ch.vitals[id]
            val frac = if (vs == null || vs.max <= 0) 0f else (vs.cur.toFloat() / vs.max).coerceIn(0f, 1f)
            drawLabel(batch, f, short, by, palette.text, parentAlpha)
            drawBar(batch, palette.panel, barX, by - BAR_HEIGHT, frac, UiColors.vital(id, game0?.vitals?.indexOfFirst { it.id == id }?.takeIf { it >= 0 } ?: i), parentAlpha)
            by -= BAR_HEIGHT + BAR_GAP
        }
        val game = worldState.compiledGame ?: BlankGame.COMPILED
        val nextXp = game.xpToNext(ch) ?: 0L
        val shownXp = worldState.visualXp(ch.xp)
        val xpFrac = if (nextXp <= 0L) 0f else (shownXp.toFloat() / nextXp.toFloat()).coerceIn(0f, 1f)
        drawLabel(batch, f, "XP", by, palette.text, parentAlpha)
        drawBar(batch, palette.panel, barX, by - BAR_HEIGHT, xpFrac, UiColors.xp(), parentAlpha)

        batch.setColor(Color.WHITE)
        f.color = Color.WHITE
    }

    private fun drawLabel(batch: Batch, f: BitmapFont, text: String, barTop: Float, c: Color, parentAlpha: Float) {
        f.color = Color(c.r, c.g, c.b, 0.9f * parentAlpha)
        f.draw(batch, text, x + PAD_X, barTop - BAR_HEIGHT / 2f + f.capHeight / 2f)
    }

    private fun drawBar(batch: Batch, bg: Color, bx: Float, by: Float, frac: Float, fill: Color, parentAlpha: Float) {
        batch.setColor(bg.r, bg.g, bg.b, 0.7f * parentAlpha)
        batch.draw(pixel, bx, by, BAR_WIDTH, BAR_HEIGHT)
        batch.setColor(fill.r, fill.g, fill.b, 0.92f * parentAlpha)
        batch.draw(pixel, bx, by, BAR_WIDTH * frac, BAR_HEIGHT)
    }

    fun dispose() = pixel.dispose()

    private companion object {
        const val BAR_WIDTH = 120f
        const val BAR_HEIGHT = 10f
        const val BAR_GAP = 3f
        const val LABEL_GAP = 4f
        const val PAD_X = 6f
        const val PAD_Y = 5f
    }
}
