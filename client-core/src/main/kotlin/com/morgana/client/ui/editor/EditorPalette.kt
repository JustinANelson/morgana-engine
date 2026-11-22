package com.morgana.client.ui.editor

import com.badlogic.gdx.graphics.Color

/**
 * One colour scheme of the editor. Pure values (no graphics context), so readability is unit-tested for every scheme:
 * text and accents must keep enough contrast against the panels they are drawn on.
 *
 * The schemes are listed in [SCHEMES]; [DEFAULT] is the warm *Ember* scheme, chosen so the editor does not look like every
 * other dark-grey-and-blue tool (owner feedback, 2026-11-13). The developer's choice is remembered by [EditorSettings].
 */
class EditorPalette(
    /** Stable id, saved in the settings. */
    val id: String,
    val name: String,
    /** One plain-language line for the settings list. */
    val description: String,
    /** Behind everything (and the dimmed world while an editor panel is open). */
    val background: Color,
    /** Panel and window surfaces. */
    val panel: Color,
    /** Headers, inputs and resting buttons: one step from [panel]. */
    val panelRaised: Color,
    val hover: Color,
    val pressed: Color,
    val border: Color,
    val accent: Color,
    /** Fill of a selected tool or tab: the accent, toned down to sit quietly inside a panel. */
    val accentDim: Color,
    val text: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** Text and icons on a selected or hovered control, a step beyond [text] (white on dark schemes, black on light). */
    val strong: Color
) {
    override fun toString(): String = "EditorPalette($id)"

    companion object {
        private fun c(hex: String): Color = Color.valueOf(hex)

        val EMBER = EditorPalette(
            "ember", "Ember", "Warm charcoal with an amber accent, like a forge (the default)",
            background = c("1a1512"), panel = c("221c18"), panelRaised = c("2d2620"), hover = c("3a3129"), pressed = c("16120f"),
            border = c("4f4338"), accent = c("f0a04b"), accentDim = c("5c3814"),
            text = c("f5ede2"), textMuted = c("b8a996"), textDisabled = c("6e6358"),
            danger = c("f26b5e"), success = c("8fcf7a"), warning = c("f2c94c"), strong = c("ffffff")
        )

        val PARCHMENT = EditorPalette(
            "parchment", "Parchment", "Light: cream paper with dark ink and a teal accent",
            background = c("e4dac4"), panel = c("f4eedf"), panelRaised = c("ebe2cd"), hover = c("e0d5bb"), pressed = c("d6c9ab"),
            border = c("b3a382"), accent = c("17615f"), accentDim = c("cfe3dc"),
            text = c("241c12"), textMuted = c("5a4c3a"), textDisabled = c("9c8f75"),
            danger = c("a8251c"), success = c("2b6e2f"), warning = c("7d5200"), strong = c("000000")
        )

        val MOSS = EditorPalette(
            "moss", "Moss and brass", "Deep forest green with a brass accent",
            background = c("111a14"), panel = c("17221b"), panelRaised = c("203026"), hover = c("2a3d31"), pressed = c("0e1611"),
            border = c("3c5444"), accent = c("d4ad3a"), accentDim = c("4a3f12"),
            text = c("e8f0e5"), textMuted = c("a2b6a5"), textDisabled = c("5b6d5f"),
            danger = c("f07462"), success = c("7fd696"), warning = c("e8c35a"), strong = c("ffffff")
        )

        val PLUM = EditorPalette(
            "plum", "Royal plum", "Dark aubergine with an orchid accent",
            background = c("1a1320"), panel = c("221a2a"), panelRaised = c("2d2337"), hover = c("3a2e47"), pressed = c("150f1a"),
            border = c("4f4061"), accent = c("ec8cc0"), accentDim = c("5a2848"),
            text = c("f4ecf7"), textMuted = c("b9a9c6"), textDisabled = c("706281"),
            danger = c("ff7a7a"), success = c("74d8a0"), warning = c("f2c14b"), strong = c("ffffff")
        )

        val SLATE = EditorPalette(
            "slate", "Slate", "Cool dark grey with a blue accent (the original look)",
            background = c("14171c"), panel = c("1c2027"), panelRaised = c("252b34"), hover = c("2e3540"), pressed = c("171b21"),
            border = c("38404d"), accent = c("4c8dff"), accentDim = c("21407a"),
            text = c("e8ebf0"), textMuted = c("9aa4b5"), textDisabled = c("5d6676"),
            danger = c("f0645c"), success = c("4cc38a"), warning = c("f2b84b"), strong = c("ffffff")
        )

        val HIGH_CONTRAST = EditorPalette(
            "contrast", "High contrast", "Black and white with a yellow accent, for the clearest text",
            background = c("000000"), panel = c("0b0b0b"), panelRaised = c("181818"), hover = c("262626"), pressed = c("050505"),
            border = c("4d4d4d"), accent = c("ffd60a"), accentDim = c("3d3300"),
            text = c("ffffff"), textMuted = c("d0d0d0"), textDisabled = c("808080"),
            danger = c("ff6b6b"), success = c("5cff9a"), warning = c("ffb020"), strong = c("ffffff")
        )

        /** Every scheme, in the order the settings list shows them. */
        val SCHEMES: List<EditorPalette> = listOf(EMBER, PARCHMENT, MOSS, PLUM, SLATE, HIGH_CONTRAST)

        val DEFAULT: EditorPalette = EMBER

        /** The scheme with [id], or [DEFAULT] for an unknown or missing id (a setting saved by a newer or older editor). */
        fun byId(id: String?): EditorPalette = SCHEMES.firstOrNull { it.id == id } ?: DEFAULT

        /** WCAG contrast ratio between two opaque colours (1 = identical, 21 = black on white). */
        fun contrast(a: Color, b: Color): Double {
            val l1 = luminance(a)
            val l2 = luminance(b)
            return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
        }

        private fun luminance(c: Color): Double {
            fun channel(v: Float): Double {
                val s = v.toDouble()
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
        }
    }
}

/**
 * The editor's own settings, kept on this computer (or in this browser): for now the colour scheme. Pure over a
 * [com.morgana.client.editor.dock.KeyValueStore], so it is tested without a platform.
 */
class EditorSettings(private val store: com.morgana.client.editor.dock.KeyValueStore) {
    /** The chosen colour scheme ([EditorPalette.DEFAULT] until one is chosen). */
    var palette: EditorPalette
        get() = EditorPalette.byId(store.get(KEY_SCHEME))
        set(value) = store.put(KEY_SCHEME, value.id)

    companion object {
        const val KEY_SCHEME = "color-scheme"
    }
}
