package com.morgana.client.access

import com.badlogic.gdx.graphics.Color
import com.morgana.client.editor.dock.KeyValueStore
import java.util.EnumMap

/**
 * The sizes the interface can be scaled to, from smaller than usual to triple (low vision or very dense screens), plus
 * [AUTO], which lets the platform pick a size from the screen (see [auto]).
 */
object UiScales {
    val STEPS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** The "Auto" setting: the platform picks the size ([resolve]). Stored as 0. */
    const val AUTO = 0f

    /** Screen height (in physical pixels) that the interface was laid out for at 100%. */
    private const val REFERENCE_SHORT_SIDE = 1080f

    /** Fewest interface units the short screen side should keep, so Auto never crowds the windows off screen. */
    private const val MIN_UNITS_SHORT_SIDE = 720f

    fun isAuto(value: Float): Boolean = value <= 0f

    /** The step closest to [value], or [AUTO] for [AUTO]. */
    fun nearest(value: Float): Float = if (isAuto(value)) AUTO else STEPS.minByOrNull { Math.abs(it - value) } ?: 1f

    /** The setting after [value]: Auto, then each step from smallest to largest, then Auto again. */
    fun next(value: Float): Float {
        if (isAuto(value)) return STEPS.first()
        val i = STEPS.indexOf(nearest(value))
        return if (i == STEPS.lastIndex) AUTO else STEPS[i + 1]
    }

    /** The scale to draw at: [setting] itself, or [autoScale] when the setting is [AUTO]. */
    fun resolve(setting: Float, autoScale: Float): Float = if (isAuto(setting)) autoScale else setting

    /**
     * A size for a screen of [screenWidth] x [screenHeight] physical pixels at [density] (1 = 160 dpi): whichever of
     * resolution (1080p is 100%) and density asks for more, never below 100%, and never so large that the short side has
     * fewer than [MIN_UNITS_SHORT_SIDE] interface units. A density outside 0.5..6 is treated as unknown.
     */
    fun auto(screenWidth: Int, screenHeight: Int, density: Float): Float {
        val shortSide = Math.max(1, Math.min(screenWidth, screenHeight)).toFloat()
        val byDensity = if (density in 0.5f..6f) density else 1f
        val wanted = maxOf(1f, shortSide / REFERENCE_SHORT_SIDE, byDensity)
        val fits = Math.max(1f, shortSide / MIN_UNITS_SHORT_SIDE)
        val capped = Math.min(wanted, fits)
        return STEPS.filter { it >= 1f && it <= fits }.minByOrNull { Math.abs(it - capped) } ?: 1f
    }

    fun label(value: Float): String = if (isAuto(value)) "Auto" else "${Math.round(value * 100)}%"

    /** The label for a [setting] drawn at [effective]: "Auto (175%)" or "150%". */
    fun describe(setting: Float, effective: Float): String =
        if (isAuto(setting)) "Auto (${label(effective)})" else label(setting)
}

/**
 * Everything the Accessibility window changes. A plain value: the game keeps the current one in [Accessibility] and the
 * [AccessibilityStore] writes it to the platform's preferences (a file on desktop, local storage in a browser).
 *
 * @property uiScale how large the whole interface is (one of [UiScales.STEPS], or [UiScales.AUTO] to follow the screen)
 * @property palette the colour set for what colours mean
 * @property reduceMotion turns off easing, pulsing, bobbing, fades and falling weather
 * @property keys the keyboard layout
 * @property autoLoot picks up loot within reach without a key press
 */
data class AccessibilitySettings(
    val uiScale: Float = UiScales.AUTO,
    val palette: ColourPalette = ColourPalette.DEFAULT,
    val reduceMotion: Boolean = false,
    val keys: KeyBindings = KeyBindings.DEFAULT,
    val autoLoot: Boolean = false
)

class AccessibilityStore(private val store: KeyValueStore) {
    /** The saved settings; anything missing or unreadable is the default, so a damaged file never blocks the game. */
    fun load(defaultReduceMotion: Boolean = false): AccessibilitySettings = AccessibilitySettings(
        uiScale = store.get(SCALE)?.toFloatOrNull()?.let { UiScales.nearest(it) } ?: UiScales.AUTO,
        palette = ColourPalette.fromName(store.get(PALETTE)),
        reduceMotion = store.get(MOTION)?.let { it == "true" } ?: defaultReduceMotion,
        keys = KeyBindings.decode(store.get(KEYS)),
        autoLoot = store.get(AUTO_LOOT) == "true"
    )

    fun save(settings: AccessibilitySettings) {
        store.put(SCALE, settings.uiScale.toString())
        store.put(PALETTE, settings.palette.name)
        store.put(MOTION, settings.reduceMotion.toString())
        store.put(AUTO_LOOT, settings.autoLoot.toString())
        val encoded = settings.keys.encode()
        if (encoded.isEmpty()) store.remove(KEYS) else store.put(KEYS, encoded)
    }

    private companion object {
        const val SCALE = "uiScale"
        const val PALETTE = "palette"
        const val MOTION = "reduceMotion"
        const val KEYS = "keys"
        const val AUTO_LOOT = "autoLoot"
    }
}

/**
 * The accessibility settings in force, read by the HUD, the renderer and the input code. Set once at startup and whenever the
 * Accessibility window changes something; nothing else writes it.
 */
object Accessibility {
    @Volatile
    var settings: AccessibilitySettings = AccessibilitySettings()
        private set

    /** The player's own choice if they made one; otherwise what the operating system or browser asks for. Set by the launcher. */
    @Volatile
    var systemPrefersReducedMotion: Boolean = false

    private val colours = EnumMap<ColourPalette, EnumMap<PaletteRole, Color>>(ColourPalette::class.java)

    fun apply(next: AccessibilitySettings) {
        settings = next
    }

    val reduceMotion: Boolean get() = settings.reduceMotion
    val keys: KeyBindings get() = settings.keys
    val palette: ColourPalette get() = settings.palette

    /** The colour of [role] in the current palette. The returned [Color] is shared: copy it before changing it. */
    fun colour(role: PaletteRole, palette: ColourPalette = settings.palette): Color =
        colours.getOrPut(palette) { EnumMap(PaletteRole::class.java) }
            .getOrPut(role) { Color(PaletteColours.rgb(palette, role) shl 8 or 0xFF) }
}
