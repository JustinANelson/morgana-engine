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
import kotlin.math.min

/**
 * Top-center dungeon HUD overlay:
 * - Shows a compact floor/biome pill on dungeon floors (highlighted in amber on guardian/boss floors).
 * - Shows a wide boss health bar underneath when an alive guardian or boss is on the current map.
 * Touches pass straight through it.
 */
class DungeonHudOverlay(
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
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val st = stage ?: return
        val map = worldState.activeMap ?: return
        val defsById = worldState.catalog.npcs.associateBy { it.id }
        val lifeId = worldState.gameDefinition?.vitals?.firstOrNull { it.role == com.morgana.domain.game.VitalRole.LIFE }?.id ?: "hp"
        val floorBossNpcId = map.generator?.bossNpcId ?: 0
        val boss = DungeonHudModel.activeBoss(worldState.npcsOnMap.values, defsById, lifeId, floorBossNpcId)
        val badge = DungeonHudModel.floorBadge(map.name, map.generator != null, boss != null, floorBossNpcId > 0)
        if (badge == null && boss == null) return

        val f = font()
        val palette = GameTheme.shared.palette
        var cursorTop = st.height - TOP_MARGIN

        if (badge != null) {
            layout.setText(f, badge.label)
            val pillW = layout.width + PILL_PAD_X * 2f
            val pillH = f.lineHeight + PILL_PAD_Y * 2f
            val pillX = (st.width - pillW) * 0.5f
            val pillY = cursorTop - pillH

            val borderColor = if (badge.isBossFloor) palette.accent else palette.border
            drawOrnateFrame(batch, pillX, pillY, pillW, pillH, palette.panel, borderColor, palette.accent, 0.90f * parentAlpha)

            val textColor = if (badge.isBossFloor) palette.accent else palette.text
            drawShadowText(batch, f, badge.label, pillX + PILL_PAD_X, cursorTop - PILL_PAD_Y, textColor, 0.98f * parentAlpha)

            cursorTop = pillY - GAP_Y
        }

        if (boss != null) {
            val barW = min(BOSS_MAX_WIDTH, st.width * 0.48f).coerceAtLeast(200f)
            val header = if (boss.title.isNotEmpty()) "${boss.name} · ${boss.title}" else boss.name
            layout.setText(f, header)
            val boxW = maxOf(barW + BOSS_PAD_X * 2f, layout.width + BOSS_PAD_X * 2f + 16f)
            val boxH = f.lineHeight + BOSS_BAR_HEIGHT + BOSS_PAD_Y * 2f + 8f
            val boxX = (st.width - boxW) * 0.5f
            val boxY = cursorTop - boxH

            drawOrnateFrame(batch, boxX, boxY, boxW, boxH, palette.panel, palette.accent, palette.strong, 0.92f * parentAlpha)

            drawShadowText(batch, f, header, boxX + (boxW - layout.width) * 0.5f, cursorTop - BOSS_PAD_Y - 1f, palette.text, 0.98f * parentAlpha)

            val trackX = boxX + BOSS_PAD_X
            val trackW = boxW - BOSS_PAD_X * 2f
            val trackY = boxY + BOSS_PAD_Y + 1f
            // Recessed dark track
            batch.setColor(0.05f, 0.04f, 0.04f, 0.95f * parentAlpha)
            batch.draw(pixel, trackX - 1f, trackY - 1f, trackW + 2f, BOSS_BAR_HEIGHT + 2f)
            batch.setColor(palette.background.r, palette.background.g, palette.background.b, 0.92f * parentAlpha)
            batch.draw(pixel, trackX, trackY, trackW, BOSS_BAR_HEIGHT)

            // Glossy shaded crimson HP fill
            val fillW = trackW * boss.fraction
            if (fillW > 0.5f) {
                val hpColor = UiColors.vital(lifeId, 0)
                batch.setColor(hpColor.r * 0.65f, hpColor.g * 0.65f, hpColor.b * 0.65f, 0.96f * parentAlpha)
                batch.draw(pixel, trackX, trackY, fillW, BOSS_BAR_HEIGHT)
                batch.setColor(hpColor.r, hpColor.g, hpColor.b, 0.96f * parentAlpha)
                batch.draw(pixel, trackX, trackY + 3f, fillW, (BOSS_BAR_HEIGHT - 4f).coerceAtLeast(1f))
                batch.setColor(
                    (hpColor.r * 1.32f).coerceAtMost(1f),
                    (hpColor.g * 1.32f).coerceAtMost(1f),
                    (hpColor.b * 1.32f).coerceAtMost(1f),
                    0.82f * parentAlpha
                )
                batch.draw(pixel, trackX, trackY + BOSS_BAR_HEIGHT - 3f, fillW, 2f)
            }

            val hpText = "${boss.curHp} / ${boss.maxHp}"
            layout.setText(f, hpText)
            drawShadowText(batch, f, hpText, trackX + (trackW - layout.width) * 0.5f, trackY + BOSS_BAR_HEIGHT - 1f, Color.WHITE, 0.98f * parentAlpha)
        }

        batch.setColor(Color.WHITE)
        f.color = Color.WHITE
    }

    private fun drawOrnateFrame(
        batch: Batch,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fill: Color,
        border: Color,
        stud: Color,
        alpha: Float
    ) {
        // Drop shadow
        batch.setColor(0f, 0f, 0f, 0.42f * alpha)
        batch.draw(pixel, x + 2f, y - 2f, w, h)
        // Outer dark rim
        batch.setColor(0.05f, 0.04f, 0.04f, alpha)
        batch.draw(pixel, x, y, w, h)
        // Main fill
        batch.setColor(fill.r, fill.g, fill.b, alpha)
        batch.draw(pixel, x + 2f, y + 2f, (w - 4f).coerceAtLeast(1f), (h - 4f).coerceAtLeast(1f))
        // Inner bevel border
        drawBorder(batch, x + 1f, y + 1f, w - 2f, h - 2f, border, alpha)
        // Four metallic corner studs
        batch.setColor(stud.r, stud.g, stud.b, alpha)
        batch.draw(pixel, x + 2f, y + 2f, 2f, 2f)
        batch.draw(pixel, x + w - 4f, y + 2f, 2f, 2f)
        batch.draw(pixel, x + 2f, y + h - 4f, 2f, 2f)
        batch.draw(pixel, x + w - 4f, y + h - 4f, 2f, 2f)
    }

    private fun drawShadowText(batch: Batch, f: BitmapFont, text: String, x: Float, y: Float, color: Color, alpha: Float) {
        f.setColor(0f, 0f, 0f, 0.85f * alpha)
        f.draw(batch, text, x + 1f, y - 1f)
        f.setColor(color.r, color.g, color.b, alpha)
        f.draw(batch, text, x, y)
    }

    private fun drawBorder(batch: Batch, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        batch.setColor(c.r, c.g, c.b, alpha)
        batch.draw(pixel, x, y, w, 1f)
        batch.draw(pixel, x, y + h - 1f, w, 1f)
        batch.draw(pixel, x, y, 1f, h)
        batch.draw(pixel, x + w - 1f, y, 1f, h)
    }

    fun dispose() = pixel.dispose()

    private companion object {
        const val TOP_MARGIN = 10f
        const val GAP_Y = 7f
        const val PILL_PAD_X = 14f
        const val PILL_PAD_Y = 5f
        const val BOSS_MAX_WIDTH = 340f
        const val BOSS_BAR_HEIGHT = 16f
        const val BOSS_PAD_X = 12f
        const val BOSS_PAD_Y = 6f
    }
}
