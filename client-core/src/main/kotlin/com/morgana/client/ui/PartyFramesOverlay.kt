package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.spatial.Direction

/** Left-edge party frames: portrait, name, level, HP bar, and MP bar per other party member. Touches pass through. */
class PartyFramesOverlay(
    private val worldState: ClientWorldState,
    private val assets: ProceduralRetroAssets,
    private val font: () -> BitmapFont
) : Actor() {

    private val pixel: Texture = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(Color.WHITE)
        pm.fill()
        Texture(pm).also { pm.dispose() }
    }

    init { touchable = Touchable.disabled }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val st = stage ?: return
        if (!worldState.showPartyUi()) return
        val lifeId = worldState.gameDefinition?.vitals?.firstOrNull { it.role == com.morgana.domain.game.VitalRole.LIFE }?.id ?: "hp"
        val manaId = worldState.gameDefinition?.vitals?.firstOrNull { it.role != com.morgana.domain.game.VitalRole.LIFE }?.id ?: "mp"
        val frames = PartyFramesModel.frames(worldState.activeParty, worldState.localCharacter?.id, worldState.playersOnMap, lifeId, manaVitalId = manaId)
        if (frames.isEmpty()) return
        val f = font()
        val palette = GameTheme.shared.palette
        var top = st.height - TOP_OFFSET
        for (fr in frames) {
            val x = MARGIN_X
            val y = top - HEIGHT
            // Outer border + dark background
            batch.setColor(0.06f, 0.07f, 0.09f, 0.9f * parentAlpha)
            batch.draw(pixel, x - 1f, y - 1f, WIDTH + 2f, HEIGHT + 2f)
            batch.setColor(palette.background.r, palette.background.g, palette.background.b, 0.86f * parentAlpha)
            batch.draw(pixel, x, y, WIDTH, HEIGHT)

            // Framed mini portrait on the left
            val px = x + 4f
            val py = y + (HEIGHT - PORTRAIT_SIZE) * 0.5f
            batch.setColor(palette.border.r, palette.border.g, palette.border.b, 0.95f * parentAlpha)
            batch.draw(pixel, px - 1f, py - 1f, PORTRAIT_SIZE + 2f, PORTRAIT_SIZE + 2f)
            batch.setColor(palette.panelRaised.r, palette.panelRaised.g, palette.panelRaised.b, 0.95f * parentAlpha)
            batch.draw(pixel, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE)
            if (fr.nearby) {
                batch.setColor(1f, 1f, 1f, if (fr.ghost) 0.45f * parentAlpha else parentAlpha)
                val region = assets.getSpriteRegion(fr.spriteId, Direction.DOWN, 0)
                batch.draw(region, px + 1f, py + 1f, PORTRAIT_SIZE - 2f, PORTRAIT_SIZE - 2f)
            }

            val textX = px + PORTRAIT_SIZE + 6f
            val barW = WIDTH - (textX - x) - 6f
            val label = (if (fr.isLeader) "* " else "") + fr.name + if (fr.nearby) " Lv.${fr.level}" else " (away)"
            val textColor = if (fr.nearby && !fr.ghost) palette.text else palette.border
            f.color = Color(textColor.r, textColor.g, textColor.b, 0.96f * parentAlpha)
            f.draw(batch, label, textX, top - 3f, barW, 8, true)

            val hpY = y + 12f
            batch.setColor(palette.panel.r, palette.panel.g, palette.panel.b, 0.85f * parentAlpha)
            batch.draw(pixel, textX, hpY, barW, BAR_HEIGHT)
            if (fr.nearby) {
                val c = UiColors.vital(lifeId, 0)
                batch.setColor(c.r, c.g, c.b, 0.94f * parentAlpha)
                batch.draw(pixel, textX, hpY, barW * fr.fraction, BAR_HEIGHT)
            }

            val mpY = y + 4f
            batch.setColor(palette.panel.r, palette.panel.g, palette.panel.b, 0.85f * parentAlpha)
            batch.draw(pixel, textX, mpY, barW, MP_BAR_HEIGHT)
            if (fr.nearby && fr.maxMp > 0) {
                val mc = UiColors.vital(manaId, 1)
                batch.setColor(mc.r, mc.g, mc.b, 0.94f * parentAlpha)
                batch.draw(pixel, textX, mpY, barW * fr.mpFraction, MP_BAR_HEIGHT)
            }
            top = y - GAP
        }
        batch.setColor(Color.WHITE)
        f.color = Color.WHITE
    }

    fun dispose() = pixel.dispose()

    private companion object {
        const val MARGIN_X = 8f
        const val TOP_OFFSET = 108f
        const val WIDTH = 166f
        const val HEIGHT = 40f
        const val PORTRAIT_SIZE = 30f
        const val BAR_HEIGHT = 7f
        const val MP_BAR_HEIGHT = 5f
        const val GAP = 4f
    }
}
