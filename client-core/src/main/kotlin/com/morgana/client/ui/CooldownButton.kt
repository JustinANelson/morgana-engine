package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisTextButton

/**
 * A quickbar slot that renders an icon with corner keybind/count badges, darkens from the top down while its spell
 * cools down, shows the seconds left, and gives a short light flash the moment it is ready again.
 * [fractionLeft] and [secondsLeft] are polled every frame (both 0 when ready).
 */
class CooldownButton(text: String = "") : VisTextButton(text) {
    var fractionLeft: () -> Float = { 0f }
    var secondsLeft: () -> Float = { 0f }
    var icon: TextureRegion? = null
    var keyText: String = ""
    var countText: String = ""

    private val slotBg = GameTheme.shared.dynamic { t, p -> t.recessedSlot(p.panelRaised, p.border, padX = 2f, padY = 2f) }
    private val slotOverBg = GameTheme.shared.dynamic { t, p -> t.recessedSlot(p.hover, p.accent, padX = 2f, padY = 2f) }
    private val badgeBg = GameTheme.shared.dynamic { t, _ -> t.solid(Color(0.04f, 0.04f, 0.05f, 0.72f)) }
    private val shade = GameTheme.shared.dynamic { t, _ -> t.solid(Color(0f, 0f, 0f, 0.62f)) }
    private val flashFill = GameTheme.shared.dynamic { t, p -> t.solid(p.strong) }
    private var wasCooling = false
    private var flashSeconds = 0f

    override fun act(delta: Float) {
        super.act(delta)
        if (flashSeconds > 0f) flashSeconds = (flashSeconds - delta).coerceAtLeast(0f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val alpha = parentAlpha * color.a
        val old = batch.color.cpy()

        // Draw crisp recessed slot frame
        batch.setColor(1f, 1f, 1f, alpha)
        val bg = if (isOver || isPressed) slotOverBg else slotBg
        bg.draw(batch, x, y, width, height)

        // Draw icon inset inside the slot
        val region = icon
        if (region != null) {
            val pad = 4f
            batch.draw(region, x + pad, y + pad, (width - pad * 2f).coerceAtLeast(1f), (height - pad * 2f).coerceAtLeast(1f))
        }

        val font = style.font
        val oldFont = font.color.cpy()
        val oldScaleX = font.data.scaleX
        val oldScaleY = font.data.scaleY

        // Top-left keybind badge with dark micro-pill backing for crisp legibility over bright icons
        if (keyText.isNotEmpty()) {
            font.data.setScale(oldScaleX * 0.76f, oldScaleY * 0.76f)
            val pillW = (keyText.length * 6.5f + 5f).coerceAtMost(width - 4f)
            val pillH = font.lineHeight + 1f
            batch.setColor(1f, 1f, 1f, alpha)
            badgeBg.draw(batch, x + 2f, y + height - pillH - 2f, pillW, pillH)
            drawShadowed(batch, font, keyText, x + 4f, y + height - 3f, width - 6f, Align.left, alpha)
        }

        // Bottom-right count / cost badge
        if (countText.isNotEmpty()) {
            font.data.setScale(oldScaleX * 0.78f, oldScaleY * 0.78f)
            drawShadowed(batch, font, countText, x + 3f, y + font.lineHeight + 1f, width - 6f, Align.right, alpha)
        }

        font.data.setScale(oldScaleX, oldScaleY)

        val fraction = fractionLeft()
        if (fraction > 0f) {
            wasCooling = true
            batch.setColor(1f, 1f, 1f, alpha)
            shade.draw(batch, x + 1f, y + 1f + (height - 2f) * (1f - fraction), width - 2f, (height - 2f) * fraction)
            font.setColor(1f, 1f, 1f, alpha)
            font.draw(batch, seconds(secondsLeft()), x, y + height * 0.5f + font.lineHeight * 0.35f, width, Align.center, false)
        } else if (wasCooling) {
            wasCooling = false
            if (!com.morgana.client.access.Accessibility.reduceMotion) flashSeconds = FLASH_SECONDS
        }

        if (flashSeconds > 0f) {
            batch.setColor(1f, 1f, 1f, alpha * 0.55f * (flashSeconds / FLASH_SECONDS))
            flashFill.draw(batch, x, y, width, height)
        }

        font.color = oldFont
        batch.color = old
    }

    private fun drawShadowed(
        batch: Batch,
        font: com.badlogic.gdx.graphics.g2d.BitmapFont,
        text: String,
        tx: Float,
        ty: Float,
        targetWidth: Float,
        align: Int,
        alpha: Float
    ) {
        font.setColor(0f, 0f, 0f, 0.88f * alpha)
        font.draw(batch, text, tx + 1f, ty - 1f, targetWidth, align, false)
        font.setColor(1f, 1f, 1f, alpha)
        font.draw(batch, text, tx, ty, targetWidth, align, false)
    }

    /** "3.4" under ten seconds, whole seconds above; no String.format so it works in the browser build. */
    private fun seconds(value: Float): String {
        if (value >= 10f) return value.toInt().toString()
        val tenths = (value * 10f).toInt().coerceAtLeast(1)
        return "${tenths / 10}.${tenths % 10}"
    }

    private companion object {
        const val FLASH_SECONDS = 0.3f
    }
}
