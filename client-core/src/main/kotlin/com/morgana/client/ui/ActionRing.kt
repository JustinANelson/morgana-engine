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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What a button in the [ActionRing] is for; decides where it sits. */
enum class RingRole {
    /** The large basic attack in the corner. */
    ATTACK,
    /** One of the four round skills in the arc (index 0..3, nearest the thumb first). */
    SKILL,
    /** The More button, above the arc: opens the list of other actions (potions, scrolls, food, stance). */
    MORE,
    /** Cycles the locked target (nearest enemy, next, next, then none); lit while a target is locked. */
    TARGET,
    /** The dodge, below and left of the attack. */
    DODGE,
    /** Use / Get: shown only when there is something to use or pick up. */
    CONTEXT,
    /** Jump, when the game has it. */
    JUMP,
    /** Run, and the action-combat inputs a weapon set can use (right click, guard, special, free key); index 0..4. */
    EXTRA
}

/** Which slot of the ring a button takes: a [role] and, for skills and extras, its [index]. */
data class RingKey(val role: RingRole, val index: Int = 0)

/** How many skills the ring has, and how many extra slots it reserves. */
const val RING_SKILLS = 4
const val RING_EXTRAS = 5

private fun polar(cx: Float, cy: Float, radius: Float, degrees: Float, diameter: Float): TouchSlot {
    val a = Math.toRadians(degrees.toDouble())
    return TouchSlot(cx + (cos(a) * radius).toFloat(), cy + (sin(a) * radius).toFloat(), diameter)
}

/**
 * Where every ring button goes, for a base button size [d]. The attack is the biggest and sits in the bottom-right corner (its
 * right edge at [rightEdge], its bottom no lower than [bottom]); the skills follow an arc around it from the left up to the top,
 * the dodge sits below-left, the More button above, and the context, jump and extra buttons on outer arcs. The whole set is placed
 * once and shifted as a block so the core buttons (attack, skills, potion, dodge, context, jump) stay inside the margins, so a
 * button appearing or disappearing never moves another. Pure, so the geometry is unit-tested on phone-sized screens.
 */
fun ringLayout(rightEdge: Float, bottom: Float, d: Float, gap: Float = 6f): Map<RingKey, TouchSlot> {
    val big = d * 1.35f
    val small = (d * 0.78f).coerceIn(40f, 60f)
    val potion = (small * 0.82f).coerceAtLeast(36f)
    val r1 = big / 2 + small / 2 + gap
    val r2 = r1 + small + gap
    val r3 = r2 + small + gap
    val cx = 0f
    val cy = 0f
    val slots = LinkedHashMap<RingKey, TouchSlot>()
    slots[RingKey(RingRole.ATTACK)] = TouchSlot(cx, cy, big)
    for (k in 0 until RING_SKILLS) slots[RingKey(RingRole.SKILL, k)] = polar(cx, cy, r1, 195f - 43f * k, small)
    slots[RingKey(RingRole.DODGE)] = polar(cx, cy, r1, 238f, small)
    slots[RingKey(RingRole.MORE)] = polar(cx, cy, r2, 80f, potion)
    slots[RingKey(RingRole.TARGET)] = polar(cx, cy, r2, 116f, potion)
    slots[RingKey(RingRole.CONTEXT)] = polar(cx, cy, r2, 188f, small)
    slots[RingKey(RingRole.JUMP)] = polar(cx, cy, r2, 152f, small)
    val core = slots.values.toList()
    for (k in 0 until RING_EXTRAS) slots[RingKey(RingRole.EXTRA, k)] = polar(cx, cy, r3, 196f - 21f * k, small)

    // Shift the block so the core buttons fit: the right-most inside rightEdge, the lowest above bottom.
    val dx = rightEdge - core.maxOf { it.right }
    val dy = bottom - core.minOf { it.bottom }
    return slots.mapValues { (_, s) -> TouchSlot(s.cx + dx, s.cy + dy, s.diameter) }
}

/** The base button size for a stage of [w] x [h]. */
fun ringBaseSize(w: Float, h: Float): Float = (minOf(w, h) * 0.17f).coerceIn(54f, 92f)

/** How far the ring's core buttons reach in from the right edge of a [w] x [h] stage, so other HUD parts can stay clear of it. */
fun ringReach(w: Float, h: Float, margin: Float = 14f): Float {
    val core = ringLayout(w - margin, margin, ringBaseSize(w, h)).filterKeys { it.role != RingRole.EXTRA || it.index == 0 }.values
    return w - core.minOf { it.left }
}

/** What one ring button shows and does. */
class RingButton(
    val key: RingKey,
    val glyph: () -> String,
    val tint: Color,
    val visible: () -> Boolean = { true },
    /** The skill or item art, or null to show [glyph]. */
    val icon: () -> TextureRegion? = { null },
    /** The key that does the same thing, shown on a keyboard ("1", "Q"); empty on touch. */
    val keyHint: () -> String = { "" },
    /** A stack or cost badge. */
    val count: () -> String = { "" },
    /** Cooldown left, 0..1. */
    val cooldown: () -> Float = { 0f },
    /** A meter drawn as a ring of beads around the button (0..1), and whether it is full and burning (empowered). */
    val meter: () -> Float = { 0f },
    val meterHot: () -> Boolean = { false },
    /** The seconds left on the cooldown ("3", "0.8"), drawn over the button; empty for none. */
    val cooldownLabel: () -> String = { "" },
    /** Cannot be used right now (cooldown, cost, level). */
    val dimmed: () -> Boolean = { false },
    /** Lit: a latched Run, loot in reach. */
    val highlighted: () -> Boolean = { false },
    /** An unassigned slot: drawn as a faint empty socket. */
    val empty: () -> Boolean = { false },
    /**
     * True for a skill that can be aimed by dragging: pressing does nothing yet, dragging sets a direction (shown through [onAim]), and
     * letting go fires [onPress] (with that aim); a plain tap fires it at once, aimed automatically. False: fires on press.
     */
    val aimable: () -> Boolean = { false },
    val onPress: () -> Unit
) {
    var onRelease: (() -> Unit)? = null
    fun withRelease(action: () -> Unit): RingButton = apply { onRelease = action }

    /** Told the drag in screen pixels (y up) while the player aims, and null when the aim ends. */
    var onAim: ((Pair<Float, Float>?) -> Unit)? = null
    fun withAim(action: (Pair<Float, Float>?) -> Unit): RingButton = apply { onAim = action }
}

/**
 * The action buttons, laid out like a mobile action RPG and the same on every platform: tap or click them, or press the
 * matching key. The touch joystick is separate ([TouchControls]); on a keyboard this ring is the only overlay, so nothing
 * covers the map's mouse input.
 */
class ActionRing(
    private val font: () -> BitmapFont,
    private val buttons: List<RingButton>,
    /** Space kept clear at the right edge and bottom. */
    private val margin: Float = 14f
) : Group() {
    private var disc: Texture? = null
    private val actors = buttons.map { RingActor(it) }

    init {
        touchable = Touchable.childrenOnly
        actors.forEach { addActor(it) }
    }

    override fun act(delta: Float) {
        stage?.let { layoutFor(it.width, it.height) }
        super.act(delta)
    }

    internal fun layoutFor(w: Float, h: Float) {
        setSize(w, h)
        val slots = ringLayout(w - margin, margin, ringBaseSize(w, h))
        actors.forEachIndexed { i, a ->
            val b = buttons[i]
            a.isVisible = b.visible()
            val s = slots.getValue(b.key)
            a.setSize(s.diameter, s.diameter)
            a.setPosition(s.left, s.bottom)
        }
    }

    internal fun visibleCount(): Int = actors.count { it.isVisible }

    /** Lets go of every held button (the overlay was hidden mid-press, so a guard does not stay up). */
    fun releaseAll() = actors.forEach { it.release() }

    fun dispose() {
        disc?.dispose()
        disc = null
    }

    private fun drawDisc(batch: Batch, cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
        val tex = disc ?: makeDisc().also { disc = it }
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(tex, cx - r, cy - r, r * 2, r * 2)
    }

    private inner class RingActor(private val def: RingButton) : Actor() {
        private var pointer = -2
        private val layout = GlyphLayout()
        private var downX = 0f
        private var downY = 0f
        private var aiming = false

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, p: Int, button: Int): Boolean {
                    if (pointer != -2) return false
                    stage?.keyboardFocus = null
                    pointer = p
                    downX = x
                    downY = y
                    aiming = false
                    if (!def.aimable()) def.onPress()
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, p: Int) {
                    if (p != pointer || !def.aimable()) return
                    val dx = x - downX
                    val dy = y - downY
                    if (!aiming && hypot(dx, dy) >= AIM_DRAG_START) aiming = true
                    if (aiming) def.onAim?.invoke(dx to dy)
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, p: Int, button: Int) {
                    if (p == pointer) release()
                }
            })
        }

        fun release() {
            if (pointer == -2) return
            pointer = -2
            if (def.aimable()) {
                // Let go: fire now (the HUD reads the drag as the aim), then clear the aim.
                def.onPress()
                if (aiming) def.onAim?.invoke(null)
                aiming = false
            }
            def.onRelease?.invoke()
        }

        override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
            if (touchable && getTouchable() != Touchable.enabled) return null
            if (!isVisible) return null
            val r = width / 2
            return if (hypot(x - r, y - r) <= r) this else null
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val cx = x + width / 2
            val cy = y + height / 2
            val r = width / 2
            val pressed = pointer != -2
            val empty = def.empty()
            val dimmed = def.dimmed()
            val lit = def.highlighted()
            val a = parentAlpha

            // Drop shadow, then a bronze rim, then the dark well the art sits in.
            drawDisc(batch, cx, cy - 1.5f, r + 1.5f, Color.BLACK, 0.5f * a)
            val rim = if (lit) Color(1f, 0.82f, 0.4f, 1f) else if (empty) Color(0.35f, 0.3f, 0.26f, 1f) else Color(0.62f, 0.48f, 0.3f, 1f)
            drawDisc(batch, cx, cy, r, rim, (if (empty) 0.6f else 0.95f) * a)
            drawDisc(batch, cx, cy, r * 0.9f, Color(0.07f, 0.06f, 0.06f, 1f), (if (empty) 0.55f else 0.92f) * a)
            val meter = def.meter()
            val hot = def.meterHot()
            if (meter > 0f || hot) {
                // A ring of beads that fills clockwise from the top; when full and burning it pulses gold.
                val n = 16
                val filled = if (hot) n else (meter * n + 0.5f).toInt().coerceIn(0, n)
                val pulse = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 110.0).toFloat()
                for (i in 0 until n) {
                    val ang = Math.PI / 2 - i * 2 * Math.PI / n
                    val bx = cx + (kotlin.math.cos(ang) * (r + 7f)).toFloat()
                    val by = cy + (kotlin.math.sin(ang) * (r + 7f)).toFloat()
                    val lit = i < filled
                    val color = if (hot) Color(1f, 0.75f + 0.2f * pulse, 0.2f, 1f) else if (lit) Color(1f, 0.82f, 0.3f, 1f) else Color(0.35f, 0.3f, 0.22f, 1f)
                    drawDisc(batch, bx, by, if (hot) 3.4f + pulse * 1.2f else if (lit) 3.2f else 2.2f, color, (if (lit || hot) 1f else 0.6f) * a)
                }
            }
            if (!empty) drawDisc(batch, cx, cy, r * 0.82f, def.tint, (if (pressed) 0.75f else 0.38f) * a)

            val icon = def.icon()
            if (icon != null && !empty) {
                val side = r * 1.36f
                batch.setColor(1f, 1f, 1f, (if (dimmed) 0.45f else 1f) * a)
                batch.draw(icon, cx - side / 2, cy - side / 2, side, side)
            }
            val cd = def.cooldown().coerceIn(0f, 1f)
            if (cd > 0f) drawDisc(batch, cx, cy, r * 0.88f * cd, Color.BLACK, 0.68f * a)
            batch.setColor(Color.WHITE)
            val cdText = def.cooldownLabel()
            if (cdText.isNotEmpty()) {
                val cf = font()
                val scale = cf.data.scaleX
                cf.data.setScale(if (r < 26f) 0.95f else 1.2f)
                layout.setText(cf, cdText, Color(1f, 1f, 1f, a), r * 2f, com.badlogic.gdx.utils.Align.center, false)
                cf.draw(batch, layout, cx - r, cy + layout.height / 2f)
                cf.data.setScale(scale)
                cf.color = Color.WHITE
            }

            val f = font()
            val oldScale = f.data.scaleX
            if (icon == null && !empty && cdText.isEmpty()) {
                f.data.setScale(if (def.key.role == RingRole.ATTACK) 1.25f else if (r < 26f) 0.8f else 1f)
                f.color = Color(1f, 1f, 1f, (if (dimmed) 0.55f else 1f) * a)
                layout.setText(f, def.glyph(), f.color, r * 2.2f, com.badlogic.gdx.utils.Align.center, true)
                f.draw(batch, layout, cx - r * 1.1f, cy + layout.height / 2)
            }
            // Key badge (top-left) and stack or cost badge (bottom-right), small and shadowed.
            f.data.setScale(0.78f)
            val hint = def.keyHint()
            if (hint.isNotEmpty()) {
                f.color = Color(1f, 0.92f, 0.7f, a)
                layout.setText(f, hint)
                f.draw(batch, layout, cx - r * 0.72f - layout.width / 2 + 3f, cy + r * 0.95f)
            }
            val count = def.count()
            if (count.isNotEmpty()) {
                f.color = Color(1f, 1f, 1f, a)
                layout.setText(f, count)
                f.draw(batch, layout, cx + r * 0.78f - layout.width, cy - r * 0.58f + layout.height)
            }
            f.data.setScale(oldScale)
            f.color = Color.WHITE
        }
    }

    companion object {
        /** How far (stage units) a finger must move on a skill before it counts as aiming instead of a tap. */
        const val AIM_DRAG_START = 18f

        private fun makeDisc(): Texture {
            val size = 128
            val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pm.setColor(0f, 0f, 0f, 0f)
            pm.fill()
            pm.setColor(Color.WHITE)
            pm.fillCircle(size / 2, size / 2, size / 2 - 1)
            val tex = Texture(pm)
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pm.dispose()
            return tex
        }
    }
}
