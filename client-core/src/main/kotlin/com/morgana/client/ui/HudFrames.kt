package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.game.VitalRole

/** The two flat shapes the HUD frames are drawn from: a white pixel and a white disc, tinted at draw time. */
class HudShapes {
    val pixel: Texture = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(Color.WHITE); pm.fill()
        Texture(pm).also { pm.dispose() }
    }
    val disc: Texture = Pixmap(128, 128, Pixmap.Format.RGBA8888).let { pm ->
        pm.setColor(0f, 0f, 0f, 0f); pm.fill()
        pm.setColor(Color.WHITE); pm.fillCircle(64, 64, 63)
        Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); pm.dispose() }
    }

    fun rect(batch: Batch, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        batch.setColor(c.r, c.g, c.b, c.a * alpha)
        batch.draw(pixel, x, y, w, h)
    }

    fun disc(batch: Batch, cx: Float, cy: Float, r: Float, c: Color, alpha: Float) {
        batch.setColor(c.r, c.g, c.b, c.a * alpha)
        batch.draw(disc, cx - r, cy - r, r * 2, r * 2)
    }

    fun dispose() { pixel.dispose(); disc.dispose() }
}

/** The bronze and dark colours the ring and the frames share. */
object HudStyle {
    val rim = Color(0.62f, 0.48f, 0.3f, 1f)
    val rimDark = Color(0.3f, 0.22f, 0.14f, 1f)
    val well = Color(0.07f, 0.06f, 0.06f, 1f)
}

/**
 * The player's frame at the top-left: a round portrait with the level, one wide life bar with its numbers, thin bars for the
 * other vitals (stamina, mana) and experience, and a row of status badges. Touches pass through it. Used on every platform when a
 * game asks for the ring layout.
 */
class PlayerFrame(
    private val worldState: ClientWorldState,
    private val shapes: HudShapes,
    private val font: () -> BitmapFont,
    /** The character's face (a sprite region), or null. */
    private val portrait: () -> TextureRegion?
) : Actor() {
    private val layout = GlyphLayout()
    private var cropSource: TextureRegion? = null
    private var crop: TextureRegion? = null

    init {
        touchable = Touchable.disabled
        setSize(FRAME_WIDTH, FRAME_HEIGHT)
    }

    /** The top-left square of the sprite (its head and chest), cached so a frame allocates nothing. */
    private fun faceOf(src: TextureRegion): TextureRegion {
        if (src !== cropSource) {
            cropSource = src
            val side = minOf(src.regionWidth, src.regionHeight)
            crop = TextureRegion(src, 0, 0, side, side)
        }
        return crop!!
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val ch = worldState.localCharacter ?: return
        val game = worldState.gameDefinition
        val a = parentAlpha
        val f = font()
        val top = y + height
        val cx = x + PORTRAIT_R + 2f
        val cy = top - PORTRAIT_R - 2f

        // Bars first, so the portrait overlaps their left end.
        val lifeDef = game?.vitals?.firstOrNull { it.role == VitalRole.LIFE } ?: game?.vitals?.firstOrNull()
        val life = lifeDef?.let { ch.vitals[it.id] } ?: ch.vitals.values.firstOrNull()
        val barX = cx + PORTRAIT_R * 0.55f
        val hpTop = top - 10f
        val critical = life != null && LowHpVignetteModel.isCritical(life.cur, life.max)
        shapes.rect(batch, barX - 2f, hpTop - HP_HEIGHT - 2f, HP_WIDTH + 4f, HP_HEIGHT + 4f, HudStyle.rimDark, 0.95f * a)
        shapes.rect(batch, barX, hpTop - HP_HEIGHT, HP_WIDTH, HP_HEIGHT, HudStyle.well, 0.95f * a)
        val hpFrac = if (life == null || life.max <= 0) 0f else (life.cur.toFloat() / life.max).coerceIn(0f, 1f)
        val lifeIndex = game?.vitals?.indexOfFirst { it.id == lifeDef?.id }?.takeIf { it >= 0 } ?: 0
        val hpColor = if (critical) Color(0.95f, 0.2f, 0.22f, 1f) else UiColors.vital(lifeDef?.id ?: "hp", lifeIndex)
        shapes.rect(batch, barX, hpTop - HP_HEIGHT, HP_WIDTH * hpFrac, HP_HEIGHT, hpColor, 0.95f * a)
        shapes.rect(batch, barX, hpTop - HP_HEIGHT / 2f, HP_WIDTH * hpFrac, HP_HEIGHT / 2f, Color.WHITE, 0.12f * a)
        if (life != null) {
            val oldScale = f.data.scaleX
            f.data.setScale(0.8f)
            f.color = Color(1f, 1f, 1f, a)
            layout.setText(f, "${life.cur} / ${life.max}")
            f.draw(batch, layout, barX + (HP_WIDTH - layout.width) / 2f, hpTop - HP_HEIGHT / 2f + layout.height / 2f)
            f.data.setScale(oldScale)
        }

        // Thin bars for the other vitals the class uses, then experience.
        var by = hpTop - HP_HEIGHT - 6f
        val shown = if (game == null) emptySet() else VitalBars.visibleIds(game, worldState.catalog.spells, ch.classId).toSet()
        game?.vitals?.forEachIndexed { i, v ->
            if (v.id == lifeDef?.id || v.id !in shown) return@forEachIndexed
            val vs = ch.vitals[v.id] ?: return@forEachIndexed
            val frac = if (vs.max <= 0) 0f else (vs.cur.toFloat() / vs.max).coerceIn(0f, 1f)
            thinBar(batch, barX, by, THIN_WIDTH, frac, UiColors.vital(v.id, i), a)
            by -= THIN_HEIGHT + 3f
        }
        val compiled = worldState.compiledGame
        val nextXp = compiled?.xpToNext(ch) ?: 0L
        if (nextXp > 0L) {
            thinBar(batch, barX, by, THIN_WIDTH, (worldState.visualXp(ch.xp).toFloat() / nextXp).coerceIn(0f, 1f), UiColors.xp(), a, height = 4f)
            by -= 7f
        }

        // Status badges (buffs and debuffs).
        var bx = barX
        for (eff in ch.statusEffects.take(6)) {
            val tint = if (eff.type.isNegative) Color(0.85f, 0.25f, 0.25f, 1f) else Color(0.3f, 0.75f, 0.4f, 1f)
            shapes.rect(batch, bx, by - BADGE, BADGE, BADGE, HudStyle.well, 0.9f * a)
            shapes.rect(batch, bx, by - BADGE, BADGE, 2f, tint, a)
            f.data.setScale(0.65f)
            f.color = Color(1f, 1f, 1f, a)
            layout.setText(f, eff.type.name.take(2))
            f.draw(batch, layout, bx + (BADGE - layout.width) / 2f, by - BADGE / 2f + layout.height / 2f - 1f)
            f.data.setScale(1f)
            bx += BADGE + 3f
        }

        // Portrait: a bronze rim, a dark well and the face, with the level in a badge.
        shapes.disc(batch, cx, cy - 1.5f, PORTRAIT_R + 2f, Color.BLACK, 0.5f * a)
        shapes.disc(batch, cx, cy, PORTRAIT_R, if (critical) Color(0.95f, 0.25f, 0.25f, 1f) else HudStyle.rim, a)
        shapes.disc(batch, cx, cy, PORTRAIT_R - 3f, HudStyle.well, a)
        portrait()?.let { src ->
            val side = (PORTRAIT_R - 5f) * 1.5f
            batch.setColor(1f, 1f, 1f, a)
            batch.draw(faceOf(src), cx - side / 2f, cy - side / 2f - 2f, side, side)
        }
        val lvR = 11f
        shapes.disc(batch, cx - PORTRAIT_R * 0.62f, cy - PORTRAIT_R * 0.7f, lvR + 1.5f, HudStyle.rim, a)
        shapes.disc(batch, cx - PORTRAIT_R * 0.62f, cy - PORTRAIT_R * 0.7f, lvR, HudStyle.well, a)
        f.data.setScale(0.8f)
        f.color = Color(1f, 0.92f, 0.7f, a)
        layout.setText(f, ch.level.toString())
        f.draw(batch, layout, cx - PORTRAIT_R * 0.62f - layout.width / 2f, cy - PORTRAIT_R * 0.7f + layout.height / 2f)
        f.data.setScale(1f)
        f.color = Color.WHITE
        batch.setColor(Color.WHITE)
    }

    private fun thinBar(batch: Batch, bx: Float, by: Float, w: Float, frac: Float, fill: Color, a: Float, height: Float = THIN_HEIGHT) {
        shapes.rect(batch, bx - 1f, by - height - 1f, w + 2f, height + 2f, HudStyle.rimDark, 0.9f * a)
        shapes.rect(batch, bx, by - height, w, height, HudStyle.well, 0.9f * a)
        shapes.rect(batch, bx, by - height, w * frac, height, fill, 0.95f * a)
    }

    /** The y of the frame's lowest drawn pixel, so the quest tracker can start under it. */
    fun bottomY(): Float = y

    companion object {
        const val FRAME_WIDTH = 230f
        const val FRAME_HEIGHT = 98f
        private const val PORTRAIT_R = 32f
        private const val HP_WIDTH = 150f
        private const val HP_HEIGHT = 18f
        private const val THIN_WIDTH = 130f
        private const val THIN_HEIGHT = 7f
        private const val BADGE = 20f
    }
}

/**
 * How visible the chat box is. It is shown while it is being used (hovered, touched, typed in, or a message just arrived),
 * stays for [HOLD_MS] after that, then fades out over [FADE_MS]. Pure, so the rule is unit-tested apart from the window.
 */
object ChatFade {
    const val HOLD_MS = 6000L
    const val FADE_MS = 350L

    /** True when the chat should be shown now. */
    fun wanted(typing: Boolean, pointerOver: Boolean, msSinceUse: Long): Boolean = typing || pointerOver || msSinceUse < HOLD_MS

    /** The next opacity (0..1) after [deltaMs], moving towards fully shown or fully hidden. */
    fun step(alpha: Float, wanted: Boolean, deltaMs: Long): Float {
        val move = deltaMs.coerceAtLeast(0L).toFloat() / FADE_MS
        return if (wanted) (alpha + move).coerceAtMost(1f) else (alpha - move).coerceAtLeast(0f)
    }
}

/** Where the phone's compact chat goes: the box, and the corner where the chat button waits while the box is faded out. */
class TouchChatSlot(val feedX: Float, val feedY: Float, val feedW: Float, val feedH: Float, val buttonX: Float, val buttonY: Float)

/**
 * The phone's compact chat sits in the gap between the movement pad (resting at the bottom-left) and the action ring (bottom-right),
 * so it never covers either thumb. Pure, so it is unit-tested on phone-sized screens.
 */
fun touchChatSlot(w: Float, h: Float, insetLeft: Float = 0f, insetRight: Float = 0f): TouchChatSlot {
    val d = ringBaseSize(w, h)
    val left = (14f + d * 0.95f * 2.25f + 10f + insetLeft).coerceAtMost(w * 0.4f)
    val right = w - ringReach(w, h) - 10f - insetRight
    val feedW = (right - left).coerceIn(120f, 380f)
    val feedH = (h * 0.34f).coerceIn(84f, 130f)
    return TouchChatSlot(left, 10f, feedW, feedH, left, 10f)
}

/**
 * The phone's compact chat: a strip of channel tabs over the latest lines (newest at the bottom, older ones fainter) on a dark
 * see-through panel. Tapping a tab switches the view; tapping the lines opens the full chat panel. [alpha] is the fade level set by
 * the HUD (the box takes touches only while it is visible).
 */
class ChatFeed(
    private val shapes: HudShapes,
    private val font: () -> BitmapFont,
    private val lines: () -> List<Pair<String, Color>>,
    private val tabs: () -> List<String>,
    /** Index of the lit tab, or -1 when the view is not one of the tabs. */
    private val activeTab: () -> Int,
    private val onTab: (Int) -> Unit,
    private val onOpen: () -> Unit
) : Actor() {
    private val layout = GlyphLayout()

    init {
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                stage?.keyboardFocus = null
                val n = tabs().size
                if (y >= height - TAB_H && n > 0) onTab((x / width * n).toInt().coerceIn(0, n - 1)) else onOpen()
                return true
            }
        })
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val a = parentAlpha * color.a
        if (a <= 0.01f) return
        shapes.rect(batch, x, y, width, height, Color(0.05f, 0.04f, 0.04f, 1f), 0.42f * a)
        shapes.rect(batch, x, y + height - TAB_H, width, TAB_H, Color(0.02f, 0.02f, 0.02f, 1f), 0.5f * a)
        val f = font()
        f.data.setScale(SCALE)
        val names = tabs()
        val lit = activeTab()
        for ((i, name) in names.withIndex()) {
            val tx = x + width * i / names.size
            val tw = width / names.size
            if (i == lit) shapes.rect(batch, tx + 4f, y + height - 3f, tw - 8f, 2f, HudStyle.rim, a)
            layout.setText(f, name, if (i == lit) Color(1f, 0.93f, 0.7f, a) else Color(0.75f, 0.72f, 0.66f, 0.8f * a), tw, com.badlogic.gdx.utils.Align.center, false)
            f.draw(batch, layout, tx, y + height - (TAB_H - layout.height) / 2f)
        }
        val textW = (width - 10f).coerceAtLeast(40f)
        val limit = y + height - TAB_H - 2f
        var top = y + 4f
        fun put(text: String, c: Color, alpha: Float): Boolean {
            layout.setText(f, text, c, textW, com.badlogic.gdx.utils.Align.left, true)
            val lineTop = top + layout.height
            if (lineTop > limit) return false
            layout.setText(f, text, Color(0f, 0f, 0f, 0.9f * alpha * a), textW, com.badlogic.gdx.utils.Align.left, true)
            f.draw(batch, layout, x + 6f, lineTop - 1f)
            layout.setText(f, text, Color(c.r, c.g, c.b, alpha * a), textW, com.badlogic.gdx.utils.Align.left, true)
            f.draw(batch, layout, x + 5f, lineTop)
            top = lineTop + 3f
            return true
        }
        val all = lines()
        for (i in all.indices.reversed()) {
            val age = all.size - 1 - i
            if (!put(all[i].first, all[i].second, (1f - 0.13f * age).coerceAtLeast(0.35f))) break
        }
        f.data.setScale(1f)
        f.color = Color.WHITE
        batch.setColor(Color.WHITE)
    }

    companion object {
        const val SCALE = 0.85f
        const val TAB_H = 26f
    }
}

/** A small square button that shows or hides the chat box, with a count of messages that arrived while it was hidden. */
class ChatToggleButton(
    private val shapes: HudShapes,
    private val font: () -> BitmapFont,
    private val unread: () -> Int,
    private val onClick: () -> Unit
) : Actor() {
    private var down = false
    private val layout = GlyphLayout()

    init {
        setSize(SIZE, SIZE)
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                stage?.keyboardFocus = null
                down = true
                return true
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (down && x in 0f..width && y in 0f..height) onClick()
                down = false
            }
        })
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val a = parentAlpha
        shapes.rect(batch, x - 2f, y - 2f, width + 4f, height + 4f, HudStyle.rim, 0.9f * a)
        shapes.rect(batch, x, y, width, height, if (down) Color(0.3f, 0.2f, 0.14f, 1f) else HudStyle.well, 0.9f * a)
        val ink = Color(0.95f, 0.82f, 0.55f, 1f)
        val cx = x + width / 2f
        val cy = y + height / 2f
        // A speech bubble: a box, a tail and three dots.
        shapes.rect(batch, cx - 11f, cy - 5f, 22f, 15f, ink, a)
        shapes.rect(batch, cx - 7f, cy - 9f, 5f, 5f, ink, a)
        for (i in -1..1) shapes.rect(batch, cx + i * 6f - 1.5f, cy + 1f, 3f, 3f, HudStyle.well, a)
        val n = unread()
        if (n > 0) {
            shapes.disc(batch, x + width - 3f, y + height - 3f, 9f, Color(0.85f, 0.2f, 0.2f, 1f), a)
            val f = font()
            f.data.setScale(0.7f)
            f.color = Color(1f, 1f, 1f, a)
            layout.setText(f, if (n > 9) "9+" else n.toString())
            f.draw(batch, layout, x + width - 3f - layout.width / 2f, y + height - 3f + layout.height / 2f)
            f.data.setScale(1f)
            f.color = Color.WHITE
        }
        batch.setColor(Color.WHITE)
    }

    companion object {
        const val SIZE = 40f
    }
}

/**
 * The locked enemy's frame at the top centre: its name and level and a life bar with the numbers. Tapping it lets go of the lock.
 */
class TargetFrame(
    private val shapes: HudShapes,
    private val font: () -> BitmapFont,
    /** Name, level, life now and life max of the locked enemy, or null when nothing is locked. */
    private val info: () -> Info?,
    private val onClear: () -> Unit
) : Actor() {
    data class Info(val name: String, val level: Int, val hp: Int, val maxHp: Int, val boss: Boolean)

    private val layout = GlyphLayout()

    init {
        setSize(WIDTH, HEIGHT)
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                stage?.keyboardFocus = null
                return true
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (x in 0f..width && y in 0f..height) onClear()
            }
        })
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val i = info() ?: return
        val a = parentAlpha
        shapes.rect(batch, x - 2f, y - 2f, width + 4f, height + 4f, if (i.boss) Color(0.95f, 0.75f, 0.2f, 1f) else HudStyle.rim, 0.95f * a)
        shapes.rect(batch, x, y, width, height, HudStyle.well, 0.9f * a)
        val ratio = (i.hp.toFloat() / i.maxHp.coerceAtLeast(1)).coerceIn(0f, 1f)
        shapes.rect(batch, x + 6f, y + 6f, width - 12f, 15f, Color(0.18f, 0.06f, 0.06f, 1f), a)
        shapes.rect(batch, x + 6f, y + 6f, (width - 12f) * ratio, 15f, Color(0.85f, 0.2f, 0.2f, 1f), a)
        val f = font()
        f.data.setScale(0.85f)
        f.color = Color(1f, 0.93f, 0.7f, a)
        layout.setText(f, "Lv ${i.level}  ${i.name}", f.color, width - 12f, com.badlogic.gdx.utils.Align.center, false)
        f.draw(batch, layout, x + 6f, y + height - 5f)
        f.data.setScale(0.7f)
        f.color = Color(1f, 1f, 1f, a)
        layout.setText(f, "${i.hp} / ${i.maxHp}", f.color, width - 12f, com.badlogic.gdx.utils.Align.center, false)
        f.draw(batch, layout, x + 6f, y + 6f + (15f + layout.height) / 2f)
        f.data.setScale(1f)
        f.color = Color.WHITE
        batch.setColor(Color.WHITE)
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? =
        if (isVisible && info() != null && x in 0f..width && y in 0f..height) this else null

    companion object {
        const val WIDTH = 220f
        const val HEIGHT = 48f
    }
}

/** The two square buttons in the top-right corner: Menu (the launcher) and Bag (the character menu on its Inventory tab). */
class CornerButtons(
    private val shapes: HudShapes,
    private val onMenu: () -> Unit,
    private val onBag: () -> Unit
) : Group() {
    private enum class Icon { MENU, BAG }

    private val menu = CornerButton(Icon.MENU, onMenu)
    private val bag = CornerButton(Icon.BAG, onBag)

    init {
        touchable = Touchable.childrenOnly
        addActor(menu)
        addActor(bag)
        setSize(SIZE, SIZE * 2 + GAP)
        menu.setPosition(0f, SIZE + GAP)
        bag.setPosition(0f, 0f)
    }

    private inner class CornerButton(private val icon: Icon, private val onClick: () -> Unit) : Actor() {
        private var down = false

        init {
            setSize(SIZE, SIZE)
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    stage?.keyboardFocus = null
                    down = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (down && x in 0f..width && y in 0f..height) onClick()
                    down = false
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val a = parentAlpha
            shapes.rect(batch, x - 2f, y - 2f, width + 4f, height + 4f, HudStyle.rim, 0.95f * a)
            shapes.rect(batch, x, y, width, height, if (down) Color(0.3f, 0.2f, 0.14f, 1f) else HudStyle.well, 0.92f * a)
            val ink = Color(0.95f, 0.82f, 0.55f, 1f)
            val cx = x + width / 2f
            val cy = y + height / 2f
            when (icon) {
                Icon.MENU -> for (i in -1..1) shapes.rect(batch, cx - 10f, cy + i * 7f - 1.5f, 20f, 3f, ink, a)
                Icon.BAG -> {
                    shapes.rect(batch, cx - 9f, cy - 10f, 18f, 14f, ink, a)
                    shapes.rect(batch, cx - 5f, cy + 4f, 10f, 3f, ink, a)
                    shapes.rect(batch, cx - 5f, cy + 4f, 3f, 6f, ink, a)
                    shapes.rect(batch, cx + 2f, cy + 4f, 3f, 6f, ink, a)
                    shapes.rect(batch, cx - 9f, cy - 4f, 18f, 2f, HudStyle.well, a * 0.8f)
                }
            }
            batch.setColor(Color.WHITE)
        }
    }

    companion object {
        const val SIZE = 44f
        const val GAP = 10f
    }
}
