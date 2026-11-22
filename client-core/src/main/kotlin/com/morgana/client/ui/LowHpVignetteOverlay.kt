package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.morgana.client.access.Accessibility
import com.morgana.client.state.ClientWorldState

/**
 * Non-interactive screen-edge crimson danger vignette when the player's primary vital (HP) drops
 * to or below [LowHpVignetteModel.CRITICAL_THRESHOLD]. Respects [Accessibility.reduceMotion].
 */
class LowHpVignetteOverlay(
    private val worldState: ClientWorldState
) : Actor() {
    private val pixel: Texture = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(Color.WHITE)
        pm.fill()
        Texture(pm).also { pm.dispose() }
    }
    private var elapsed = 0f

    init {
        touchable = Touchable.disabled
    }

    override fun act(delta: Float) {
        super.act(delta)
        elapsed += delta
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (!worldState.authenticated) return
        val ch = worldState.localCharacter ?: return
        val lifeId = worldState.lifeVitalId()
        val vital = ch.vitals[lifeId] ?: ch.vitals.values.firstOrNull() ?: return
        val alpha = LowHpVignetteModel.vignetteAlpha(
            curHp = vital.cur,
            maxHp = vital.max,
            elapsedSeconds = elapsed,
            reduceMotion = Accessibility.reduceMotion
        )
        if (alpha <= 0f) return

        val s = stage ?: return
        val w = s.width
        val h = s.height
        val band = (minOf(w, h) * 0.06f).coerceIn(14f, 36f)

        // Outer band + softer inner feather band on all 4 edges without overlapping corners
        val outerA = alpha * parentAlpha
        val innerA = alpha * 0.45f * parentAlpha

        batch.setColor(0.85f, 0.1f, 0.12f, outerA)
        batch.draw(pixel, 0f, h - band, w, band)
        batch.draw(pixel, 0f, 0f, w, band)
        batch.draw(pixel, 0f, band, band, (h - band * 2f).coerceAtLeast(0f))
        batch.draw(pixel, w - band, band, band, (h - band * 2f).coerceAtLeast(0f))

        batch.setColor(0.85f, 0.1f, 0.12f, innerA)
        batch.draw(pixel, band, h - band * 2f, (w - band * 2f).coerceAtLeast(0f), band)
        batch.draw(pixel, band, band, (w - band * 2f).coerceAtLeast(0f), band)
        batch.draw(pixel, band, band * 2f, band, (h - band * 4f).coerceAtLeast(0f))
        batch.draw(pixel, w - band * 2f, band * 2f, band, (h - band * 4f).coerceAtLeast(0f))

        batch.color = Color.WHITE
    }

    fun dispose() = pixel.dispose()
}
