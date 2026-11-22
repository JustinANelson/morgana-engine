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
    private val allowDiagonals: () -> Boolean = { true }
) : Group() {

    /** The direction currently held on the joystick, or null. Polled each frame by the game loop. */
    var heldDirection: Direction? = null
        private set

    private var circle: Texture? = null
    private val zone = JoystickZone()
    private val faceActors = faceButtons.map {
        CircleButton(it.glyph, { it.color }, big = true, onPress = it.onPress, highlighted = it.highlighted)
    }
    private val hotkeyActors = hotkeys.map { hk ->
        CircleButton({ "" }, { GameTheme.shared.palette.textMuted }, big = false, onPress = hk.onPress, caption = hk.caption, cooldownFraction = hk.cooldownFraction, dimmed = hk.dimmed)
    }

    init {
        touchable = Touchable.childrenOnly
        addActor(zone)
        faceActors.forEach { addActor(it) }
        hotkeyActors.forEach { addActor(it) }
    }

    /** Positions everything for the current stage size. Called every frame (cheap) so resizes and zoom just work. */
    internal fun layoutFor(w: Float, h: Float) {
        setSize(w, h)
        val margin = 14f
        val topMargin = topRightInset.coerceAtLeast(margin)
        val d = (min(w, h) * 0.17f).coerceIn(54f, 92f)

        hotkeyActors.forEachIndexed { idx, a -> a.isVisible = hotkeys[idx].visible() }
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
        // Face buttons: diamond to the left of the hotkey columns.
        val cx = w - margin - hotkeyColumnsWidth - 12f - d * 1.2f
        val cy = margin + d * 1.2f
        val off = d * 0.72f
        val spots = listOf(
            0f to -off, // A (bottom)
            off to 0f,  // B (right)
            -off to 0f, // X (left)
            0f to off   // Y (top)
        )
        faceActors.forEachIndexed { i, a ->
            a.isVisible = faceButtons[i].visible()
            val (ox, oy) = spots.getOrElse(i) { 0f to 0f }
            a.setSize(d, d)
            a.setPosition(cx + ox - d / 2, cy + oy - d / 2)
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
        faceActors.forEach { it.pressedPointer = -2 }
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
            if (pointer == -2) return
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
                    if (p == pressedPointer) pressedPointer = -2
                }
            })
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
            layout.setText(f, text, f.color, r * 1.6f, com.badlogic.gdx.utils.Align.center, true)
            val boxW = r * 1.6f
            f.draw(batch, layout, cx - boxW / 2, cy + layout.height / 2)
            f.color = Color.WHITE
        }
    }

    companion object {
        /** Leaves room for the 42px Menu button + 8px top margin + 8px gap. */
        const val DEFAULT_TOP_RIGHT_INSET = 58f

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

/** Definition of one of the four face buttons. */
class TouchFaceButton(
    val glyph: () -> String,
    val color: Color,
    val visible: () -> Boolean = { true },
    val highlighted: () -> Boolean = { false },
    val onPress: () -> Unit
) {
    constructor(glyph: () -> String, color: Color, onPress: () -> Unit) : this(glyph, color, { true }, { false }, onPress)
    constructor(glyph: () -> String, color: Color, visible: () -> Boolean, onPress: () -> Unit) : this(glyph, color, visible, { false }, onPress)
    constructor(glyph: String, color: Color, visible: () -> Boolean, onPress: () -> Unit) : this({ glyph }, color, visible, onPress)
    constructor(glyph: String, color: Color, onPress: () -> Unit) : this({ glyph }, color, { true }, onPress)
}
