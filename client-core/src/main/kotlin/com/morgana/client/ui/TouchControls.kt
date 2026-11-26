package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.Group
import com.morgana.domain.spatial.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Maps a joystick offset (screen y up) to a 4-way direction, or null inside the dead zone. Pure, so it is unit-tested. */
/**
 * Whether the on-screen joystick and buttons are shown. They are never shown while the map editor is open: the joystick
 * zone covers half the screen and would swallow the mouse presses the editor needs to paint tiles.
 */
fun shouldShowTouchControls(loggedIn: Boolean, enabledInSettings: Boolean, mapEditorActive: Boolean): Boolean =
    loggedIn && enabledInSettings && !mapEditorActive

/** Maps a joystick offset (screen y up) to an 8-way (or 4-way when [allowDiagonals] is false) direction, or null inside the dead zone. */
fun joystickDirection(dx: Float, dy: Float, deadZone: Float, allowDiagonals: Boolean = true): Direction? {
    if (hypot(dx, dy) < deadZone) return null
    val ax = abs(dx)
    val ay = abs(dy)
    if (allowDiagonals) {
        // tan(22.5°) = sqrt(2) - 1: splits the circle into eight equal 45° sectors.
        val tan225 = 0.41421356f
        if (ay >= ax * tan225 && ax >= ay * tan225) {
            return when {
                dx > 0f && dy > 0f -> Direction.UP_RIGHT
                dx < 0f && dy > 0f -> Direction.UP_LEFT
                dx > 0f && dy < 0f -> Direction.DOWN_RIGHT
                else -> Direction.DOWN_LEFT
            }
        }
    }
    return if (ax > ay) {
        if (dx > 0f) Direction.RIGHT else Direction.LEFT
    } else {
        if (dy > 0f) Direction.UP else Direction.DOWN
    }
}

/** How a [GameAction] is reached without a keyboard. */
enum class TouchRoute {
    /** The floating joystick. */
    JOYSTICK,
    /** One of the round action buttons. */
    ACTION_BUTTON,
    /** The latching Run button or the held modifier button. */
    TOGGLE_BUTTON,
    /** A hotkey on the right edge. */
    HOTKEY,
    /** The Menu launcher, which lists every window with its key. */
    MENU,
    /** The More button's list of other actions (a stance, items on quickbar slots 5-0). */
    ACTION_LIST
}

/**
 * The touch route for every key action. The `when` has no `else`, so adding a [GameAction] without deciding how a phone player
 * reaches it does not compile: keyboard and touch cannot drift apart.
 */
fun touchRoute(action: com.morgana.client.access.GameAction): TouchRoute = when (action) {
    com.morgana.client.access.GameAction.MOVE_UP,
    com.morgana.client.access.GameAction.MOVE_DOWN,
    com.morgana.client.access.GameAction.MOVE_LEFT,
    com.morgana.client.access.GameAction.MOVE_RIGHT -> TouchRoute.JOYSTICK
    com.morgana.client.access.GameAction.ATTACK,
    com.morgana.client.access.GameAction.JUMP,
    com.morgana.client.access.GameAction.INTERACT,
    com.morgana.client.access.GameAction.PICK_UP,
    com.morgana.client.access.GameAction.MORE_ACTIONS,
    com.morgana.client.access.GameAction.TARGET_NEXT -> TouchRoute.ACTION_BUTTON
    com.morgana.client.access.GameAction.STANCE -> TouchRoute.ACTION_LIST
    com.morgana.client.access.GameAction.SPRINT -> TouchRoute.TOGGLE_BUTTON
    com.morgana.client.access.GameAction.HOTBAR_1,
    com.morgana.client.access.GameAction.HOTBAR_2,
    com.morgana.client.access.GameAction.HOTBAR_3,
    com.morgana.client.access.GameAction.HOTBAR_4,
    com.morgana.client.access.GameAction.HOTBAR_5,
    com.morgana.client.access.GameAction.HOTBAR_6,
    com.morgana.client.access.GameAction.HOTBAR_7,
    com.morgana.client.access.GameAction.HOTBAR_8,
    com.morgana.client.access.GameAction.HOTBAR_9,
    com.morgana.client.access.GameAction.HOTBAR_10 -> TouchRoute.HOTKEY
    com.morgana.client.access.GameAction.CHARACTER,
    com.morgana.client.access.GameAction.INVENTORY,
    com.morgana.client.access.GameAction.SPELLBOOK,
    com.morgana.client.access.GameAction.QUESTS,
    com.morgana.client.access.GameAction.CRAFTING,
    com.morgana.client.access.GameAction.PARTY,
    com.morgana.client.access.GameAction.MINIMAP,
    com.morgana.client.access.GameAction.WORLD_MAP,
    com.morgana.client.access.GameAction.CONTROLS -> TouchRoute.MENU
}

/** One round button's centre and diameter, in stage units. */
class TouchSlot(val cx: Float, val cy: Float, val diameter: Float) {
    val left get() = cx - diameter / 2
    val right get() = cx + diameter / 2
    val bottom get() = cy - diameter / 2
    val top get() = cy + diameter / 2
}

/**
 * Where the action buttons go. Slot 0 is the large primary button in the bottom-right corner (its right edge at [rightEdge]);
 * the other [count] - 1 are small buttons in a grid of [perRow] to its left, filling the bottom row from the primary outwards and
 * then the rows above, so the buttons used most (listed first) sit nearest the thumb. [d] is the base button size. Pure so the
 * layout is unit-tested on phone-sized screens.
 */
fun touchClusterSlots(count: Int, rightEdge: Float, bottom: Float, d: Float, perRow: Int = 3, gap: Float = 6f): List<TouchSlot> {
    if (count <= 0) return emptyList()
    val big = d * 1.2f
    val small = (d * 0.74f).coerceIn(40f, 56f)
    val slots = ArrayList<TouchSlot>(count)
    slots += TouchSlot(rightEdge - big / 2, bottom + big / 2, big)
    for (i in 0 until count - 1) {
        val row = i / perRow
        val col = i % perRow
        slots += TouchSlot(rightEdge - big - gap - small / 2 - col * (small + gap), bottom + small / 2 + row * (small + gap), small)
    }
    return slots
}

/** One on-screen side hotkey: a caption (e.g. the hotbar slot's action) and what it does. */
class TouchHotkey(
    val caption: () -> String,
    val visible: () -> Boolean = { true },
    val cooldownFraction: () -> Float = { 0f },
    val dimmed: () -> Boolean = { false },
    val onPress: () -> Unit
) {
    constructor(caption: () -> String, onPress: () -> Unit) : this(caption, { true }, { 0f }, { false }, onPress)
    constructor(caption: () -> String, visible: () -> Boolean, onPress: () -> Unit) : this(caption, visible, { 0f }, { false }, onPress)
}

/**
 * Console-style touch overlay, drawn behind the HUD windows so windows always win touches:
 *  - a floating circular joystick that appears wherever a finger lands on the left part of the screen;
 *  - four round face buttons (diamond layout) at bottom right;
 *  - a column of small hotkey buttons down the right edge (starting below the top-right Menu button).
 * Multi-touch works: each control tracks its own pointer, so you can walk and attack together.
 */
class TouchControls(
    private val font: () -> BitmapFont,
    private val faceButtons: List<TouchFaceButton>,
    private val hotkeys: List<TouchHotkey>,
    /** Space left clear at the top-right corner for the compact HUD's Menu button. */
    private val topRightInset: Float = DEFAULT_TOP_RIGHT_INSET,
    /** Whether the joystick outputs 8-way diagonal directions (true for sub-tile movement). */
    private val allowDiagonals: () -> Boolean = { true },
    /** A modifier held with the left thumb while the right thumb presses an action button (the keyboard's Shift). Sits at the left edge. */
    private val modifierButton: TouchFaceButton? = null,
    /** Space kept clear at the bottom-left for the minimized chat bar. */
    private val bottomLeftInset: Float = DEFAULT_BOTTOM_LEFT_INSET,
    /** True when the action ring replaces the button cluster and the hotkey column: only the joystick (and the modifier) remain. */
    private val clusterHidden: () -> Boolean = { false }
) : Group() {

    /** The direction currently held on the joystick, or null. Polled each frame by the game loop. */
    var heldDirection: Direction? = null
        private set

    private var circle: Texture? = null
    private val zone = JoystickZone()
    private val faceActors = faceButtons.map {
        CircleButton(it.glyph, { it.color }, big = true, onPress = it.onPress, onRelease = { it.onRelease?.invoke() }, highlighted = it.highlighted)
    }
    private val modifierActor = modifierButton?.let {
        CircleButton(it.glyph, { it.color }, big = false, onPress = it.onPress, onRelease = { it.onRelease?.invoke() }, highlighted = it.highlighted)
    }
    private val hotkeyActors = hotkeys.map { hk ->
        CircleButton({ "" }, { GameTheme.shared.palette.textMuted }, big = false, onPress = hk.onPress, caption = hk.caption, cooldownFraction = hk.cooldownFraction, dimmed = hk.dimmed)
    }

    init {
        touchable = Touchable.childrenOnly
        addActor(zone)
        faceActors.forEach { addActor(it) }
        modifierActor?.let { addActor(it) }
        hotkeyActors.forEach { addActor(it) }
    }

    /** Positions everything for the current stage size. Called every frame (cheap) so resizes and zoom just work. */
    internal fun layoutFor(w: Float, h: Float) {
        setSize(w, h)
        val margin = 14f
        val topMargin = topRightInset.coerceAtLeast(margin)
        val d = (min(w, h) * 0.17f).coerceIn(54f, 92f)

        hotkeyActors.forEachIndexed { idx, a -> a.isVisible = hotkeys[idx].visible() && !clusterHidden() }
        val visibleHotkeys = hotkeyActors.filter { it.isVisible }
        val n = visibleHotkeys.size.coerceAtLeast(1)
        val availH = (h - topMargin - margin).coerceAtLeast(56f)
        val gapFactor = 1.18f
        // Wrap into multiple columns when a single column would shrink buttons below comfortable touch size.
        val maxSingleColRows = (availH / (34f * gapFactor)).toInt().coerceAtLeast(1)
        val cols = if (n <= maxSingleColRows) 1 else ((n + maxSingleColRows - 1) / maxSingleColRows).coerceAtMost(3)
        val rowsPerCol = (n + cols - 1) / cols
        val sd = (availH / (rowsPerCol * gapFactor)).coerceIn(28f, 42f)

        // Hotkey columns hug the right edge, top to bottom, starting below the top-right Menu button.
        visibleHotkeys.forEachIndexed { i, a ->
            val col = i / rowsPerCol
            val row = i % rowsPerCol
            a.setSize(sd, sd)
            a.setPosition(
                w - margin - sd - col * sd * gapFactor,
                h - topMargin - sd - row * sd * gapFactor
            )
        }
        val hotkeyColumnsWidth = cols * sd + (cols - 1) * sd * (gapFactor - 1f)
        // Action buttons: one large primary in the corner and a grid of small ones to its left, left of the hotkey columns.
        faceActors.forEachIndexed { i, a -> a.isVisible = faceButtons[i].visible() && !clusterHidden() }
        val shown = faceActors.indices.filter { faceActors[it].isVisible }
        val primaryIndex = shown.firstOrNull { faceButtons[it].primary } ?: shown.firstOrNull()
        val ordered = listOfNotNull(primaryIndex) + shown.filter { it != primaryIndex }
        val slots = touchClusterSlots(ordered.size, w - margin - hotkeyColumnsWidth - 12f, margin, d)
        ordered.forEachIndexed { n, idx ->
            val slot = slots[n]
            faceActors[idx].setSize(slot.diameter, slot.diameter)
            faceActors[idx].setPosition(slot.left, slot.bottom)
        }
        modifierActor?.let { m ->
            m.isVisible = modifierButton!!.visible()
            val md = (d * 0.74f).coerceIn(40f, 56f)
            m.setSize(md, md)
            m.setPosition(margin, bottomLeftInset)
        }
        zone.setSize(w * 0.5f, h)
        zone.setPosition(0f, 0f)
        zone.baseRadius = d * 0.95f
    }

    /** Y coordinate of the top edge of the highest visible side hotkey (for layout verification). */
    internal fun topHotkeyMaxY(): Float? = hotkeyActors.filter { it.isVisible }.maxOfOrNull { it.y + it.height }
    /** Y coordinate of the bottom edge of the lowest visible side hotkey (for layout verification). */
    internal fun bottomHotkeyMinY(): Float? = hotkeyActors.filter { it.isVisible }.minOfOrNull { it.y }
    internal fun visibleFaceCount(): Int = faceActors.count { it.isVisible }
    internal fun visibleHotkeyCount(): Int = hotkeyActors.count { it.isVisible }

    override fun act(delta: Float) {
        val s = stage
        if (s != null) layoutFor(s.width, s.height)
        super.act(delta)
    }

    /** Releases everything (used when the overlay is hidden mid-press, so the hero does not keep walking). */
    fun releaseAll() {
        zone.release()
        (faceActors + listOfNotNull(modifierActor)).forEach { it.release() }
        hotkeyActors.forEach { it.pressedPointer = -2 }
        heldDirection = null
    }

    fun dispose() {
        circle?.dispose()
        circle = null
    }

    private fun drawCircle(batch: Batch, cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
        val tex = circle ?: makeCircleTexture().also { circle = it }
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(tex, cx - r, cy - r, r * 2, r * 2)
    }

    /** Left-half touch area hosting the floating joystick. */
    private inner class JoystickZone : Actor() {
        var baseRadius = 60f
        private var pointer = -2
        private var baseX = 0f
        private var baseY = 0f
        private var knobX = 0f
        private var knobY = 0f

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, p: Int, button: Int): Boolean {
                    if (pointer != -2) return false
                    stage?.keyboardFocus = null
                    pointer = p
                    // Keep the whole base on screen even for touches near the edges.
                    baseX = x.coerceIn(baseRadius + 6f, (width - baseRadius).coerceAtLeast(baseRadius + 6f))
                    baseY = y.coerceIn(baseRadius + 6f, (height - baseRadius - 6f).coerceAtLeast(baseRadius + 6f))
                    knobX = x; knobY = y
                    update(x, y)
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, p: Int) {
                    if (p == pointer) update(x, y)
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, p: Int, button: Int) {
                    if (p == pointer) release()
                }
            })
        }

        private fun update(x: Float, y: Float) {
            var dx = x - baseX
            var dy = y - baseY
            val len = hypot(dx, dy)
            if (len > baseRadius) { dx = dx / len * baseRadius; dy = dy / len * baseRadius }
            knobX = baseX + dx
            knobY = baseY + dy
            heldDirection = joystickDirection(dx, dy, baseRadius * 0.3f, allowDiagonals())
        }

        fun release() {
            pointer = -2
            heldDirection = null
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            if (pointer == -2) {
                // At rest the stick is a faint pad where a thumb naturally lands; it moves under the finger once pressed.
                val gx = x + baseRadius * 1.25f + 14f
                val gy = y + bottomLeftInset + baseRadius * 0.9f
                val palette = GameTheme.shared.palette
                drawCircle(batch, gx, gy, baseRadius, palette.background, 0.28f * parentAlpha)
                drawCircle(batch, gx, gy, baseRadius * 0.9f, palette.text, 0.05f * parentAlpha)
                drawCircle(batch, gx, gy, baseRadius * 0.38f, palette.text, 0.16f * parentAlpha)
                batch.setColor(Color.WHITE)
                return
            }
            val dark = GameTheme.shared.palette.background
            val light = GameTheme.shared.palette.text
            drawCircle(batch, x + baseX, y + baseY, baseRadius, dark, 0.45f * parentAlpha)
            drawCircle(batch, x + baseX, y + baseY, baseRadius * 0.92f, light, 0.12f * parentAlpha)
            drawCircle(batch, x + knobX, y + knobY, baseRadius * 0.42f, light, 0.65f * parentAlpha)
            batch.setColor(Color.WHITE)
        }
    }

    /** A round button that fires on press and shows a pressed state; hit-tests as a circle, not a square. */
    private inner class CircleButton(
        private val glyph: () -> String,
        private val tint: () -> Color,
        private val big: Boolean,
        private val onPress: () -> Unit,
        private val onRelease: () -> Unit = {},
        private val caption: (() -> String)? = null,
        private val cooldownFraction: () -> Float = { 0f },
        private val dimmed: () -> Boolean = { false },
        private val highlighted: () -> Boolean = { false }
    ) : Actor() {
        var pressedPointer = -2
        private val layout = GlyphLayout()

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, p: Int, button: Int): Boolean {
                    if (pressedPointer != -2) return false
                    stage?.keyboardFocus = null
                    pressedPointer = p
                    onPress()
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, p: Int, button: Int) {
                    if (p == pressedPointer) release()
                }
            })
        }

        /** Lets go of the button and runs its release action (once) if it was held. */
        fun release() {
            if (pressedPointer == -2) return
            pressedPointer = -2
            onRelease()
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
            val pressed = pressedPointer != -2
            val isDimmed = dimmed()
            val isHighlighted = highlighted()
            val cd = cooldownFraction().coerceIn(0f, 1f)
            drawCircle(batch, cx, cy, r, if (isHighlighted && !pressed) tint() else GameTheme.shared.palette.background, (if (isHighlighted) 0.85f else 0.65f) * parentAlpha)
            val fillAlpha = when {
                pressed -> 0.95f
                isDimmed -> 0.22f
                isHighlighted -> 0.85f
                else -> 0.5f
            }
            drawCircle(batch, cx, cy, r * 0.86f, tint(), fillAlpha * parentAlpha)
            if (cd > 0f) {
                drawCircle(batch, cx, cy, r * 0.86f * cd, GameTheme.shared.palette.background, 0.72f * parentAlpha)
            }
            batch.setColor(Color.WHITE)

            val f = font()
            val text = if (caption != null) caption.invoke() else glyph()
            f.color = Color(1f, 1f, 1f, (if (isDimmed) 0.55f else 1f) * parentAlpha)
            // Wide enough that a five-letter caption such as "Guard" or "Dodge" stays on one line in a small button.
            val boxW = r * 2.3f
            layout.setText(f, text, f.color, boxW, com.badlogic.gdx.utils.Align.center, true)
            f.draw(batch, layout, cx - boxW / 2, cy + layout.height / 2)
            f.color = Color.WHITE
        }
    }

    companion object {
        /** Leaves room for the 42px Menu button + 8px top margin + 8px gap. */
        const val DEFAULT_TOP_RIGHT_INSET = 58f

        /** Leaves room above the minimized chat bar (12px margin + a ~36px title bar + a gap). */
        const val DEFAULT_BOTTOM_LEFT_INSET = 62f

        /** A soft white disc; tinted and scaled at draw time. */
        private fun makeCircleTexture(): Texture {
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

/**
 * Definition of one round action button. [onRelease] runs when the finger lifts (for held inputs such as a guard), [primary] makes
 * it the large button of the cluster (the first visible button is the primary when none is marked), and [highlighted] shows a
 * latched or active state.
 */
class TouchFaceButton(
    val glyph: () -> String,
    val color: Color,
    val visible: () -> Boolean = { true },
    val highlighted: () -> Boolean = { false },
    val onPress: () -> Unit
) {
    /** Runs when the finger lifts or the overlay hides mid-press. */
    var onRelease: (() -> Unit)? = null
    var primary: Boolean = false

    fun withRelease(action: () -> Unit): TouchFaceButton = apply { onRelease = action }
    fun asPrimary(): TouchFaceButton = apply { primary = true }

    constructor(glyph: () -> String, color: Color, onPress: () -> Unit) : this(glyph, color, { true }, { false }, onPress)
    constructor(glyph: () -> String, color: Color, visible: () -> Boolean, onPress: () -> Unit) : this(glyph, color, visible, { false }, onPress)
    constructor(glyph: String, color: Color, visible: () -> Boolean, onPress: () -> Unit) : this({ glyph }, color, visible, onPress)
    constructor(glyph: String, color: Color, onPress: () -> Unit) : this({ glyph }, color, { true }, onPress)
}
