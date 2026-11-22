package com.morgana.client.access

import com.badlogic.gdx.graphics.Color
import com.morgana.client.editor.dock.KeyValueStore
import java.util.EnumMap

/** The sizes the interface can be scaled to, from smaller than usual to double (low vision). */
object UiScales {
    val STEPS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** The step closest to [value]. */
    fun nearest(value: Float): Float = STEPS.minByOrNull { Math.abs(it - value) } ?: 1f

    /** The step after [value], wrapping round to the smallest. */
    fun next(value: Float): Float = STEPS[(STEPS.indexOf(nearest(value)) + 1) % STEPS.size]

    fun label(value: Float): String = "${(value * 100).toInt()}%"
}

/**
 * Everything the Accessibility window changes. A plain value: the game keeps the current one in [Accessibility] and the
 * [AccessibilityStore] writes it to the platform's preferences (a file on desktop, local storage in a browser).
 *
 * @property uiScale how large the whole interface is (one of [UiScales.STEPS])
 * @property palette the colour set for what colours mean
 * @property reduceMotion turns off easing, pulsing, bobbing, fades and falling weather
 * @property keys the keyboard layout
 * @property autoLoot picks up loot within reach without a key press
 */
data class AccessibilitySettings(
    val uiScale: Float = 1f,
    val palette: ColourPalette = ColourPalette.DEFAULT,
    val reduceMotion: Boolean = false,
    val keys: KeyBindings = KeyBindings.DEFAULT,
    val autoLoot: Boolean = false
)

class AccessibilityStore(private val store: KeyValueStore) {
    /** The saved settings; anything missing or unreadable is the default, so a damaged file never blocks the game. */
    fun load(defaultReduceMotion: Boolean = false): AccessibilitySettings = AccessibilitySettings(
        uiScale = store.get(SCALE)?.toFloatOrNull()?.let { UiScales.nearest(it) } ?: 1f,
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
