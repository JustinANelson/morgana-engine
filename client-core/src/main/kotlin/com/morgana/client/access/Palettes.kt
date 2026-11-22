package com.morgana.client.access

/** Colour sets for players who cannot rely on telling red from green or blue from yellow. */
enum class ColourPalette(val label: String, val description: String) {
    DEFAULT("Standard", "The game's own colours."),
    RED_GREEN("Red-green safe", "For protanopia and deuteranopia: health, damage and friends use orange, blue and yellow instead of red and green."),
    BLUE_YELLOW("Blue-yellow safe", "For tritanopia: bars and markers keep apart without relying on blue against green or yellow against violet."),
    HIGH_CONTRAST("High contrast", "Very bright colours, easy to see against dark backgrounds.");

    companion object {
        fun fromName(name: String?): ColourPalette = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * What a colour in the game means. The game asks for a role, never for a literal colour, so a palette can change what the
 * role looks like without changing the meaning.
 */
enum class PaletteRole(val label: String) {
    /** The first vital's bar (health). */
    HEALTH("Health bar"),
    /** The second vital's bar (mana). */
    MANA("Second bar"),
    VITAL_THREE("Third bar"),
    VITAL_FOUR("Fourth bar"),
    /** The experience bar, which must not look like any vital. */
    XP("Experience bar"),
    /** Good news: healing text, the filled part of an overhead health bar. */
    GOOD("Healing and full health"),
    /** Bad news: damage text, the empty part of an overhead health bar. */
    BAD("Damage and missing health"),
    /** Monsters and guards on the minimap. */
    HOSTILE("Hostile on the minimap"),
    /** Shopkeepers and villagers on the minimap. */
    FRIENDLY("Friendly on the minimap"),
    /** Other players on the minimap. */
    OTHER_PLAYER("Other players"),
    /** Ground the character has explored on the world map. */
    EXPLORED("Explored ground")
}

/**
 * The colour of each [PaletteRole] in each [ColourPalette], as `0xRRGGBB`. Pure data and arithmetic, so the choices are
 * checked by tests that simulate how each kind of colour blindness sees them (see `PaletteColoursTest`).
 */
object PaletteColours {
    private val table: Map<ColourPalette, Map<PaletteRole, Int>> = mapOf(
        ColourPalette.DEFAULT to mapOf(
            PaletteRole.HEALTH to 0xB83838, PaletteRole.MANA to 0x3870B8, PaletteRole.VITAL_THREE to 0x389E5C, PaletteRole.VITAL_FOUR to 0xC78A29, PaletteRole.XP to 0x8E5BD6,
            PaletteRole.GOOD to 0x2ED152, PaletteRole.BAD to 0xD92E2E,
            PaletteRole.HOSTILE to 0xE63333, PaletteRole.FRIENDLY to 0xF2D933, PaletteRole.OTHER_PLAYER to 0x33E64D, PaletteRole.EXPLORED to 0x479457
        ),
        // Okabe and Ito's palette, chosen to stay apart for red-green colour blindness.
        ColourPalette.RED_GREEN to mapOf(
            PaletteRole.HEALTH to 0xD55E00, PaletteRole.MANA to 0x0072B2, PaletteRole.VITAL_THREE to 0xF0E442, PaletteRole.VITAL_FOUR to 0xAAAAAA, PaletteRole.XP to 0xFFFFFF,
            PaletteRole.GOOD to 0x56B4E9, PaletteRole.BAD to 0xD55E00,
            PaletteRole.HOSTILE to 0xD55E00, PaletteRole.FRIENDLY to 0xF0E442, PaletteRole.OTHER_PLAYER to 0x56B4E9, PaletteRole.EXPLORED to 0x56B4E9
        ),
        // Tol's "bright" qualitative palette, designed to hold up under every kind of colour vision.
        ColourPalette.BLUE_YELLOW to mapOf(
            PaletteRole.HEALTH to 0xEE6677, PaletteRole.MANA to 0x5566CC, PaletteRole.VITAL_THREE to 0x99DDFF, PaletteRole.VITAL_FOUR to 0xF0E442, PaletteRole.XP to 0xAA3377,
            PaletteRole.GOOD to 0x66CCEE, PaletteRole.BAD to 0xEE6677,
            PaletteRole.HOSTILE to 0xEE6677, PaletteRole.FRIENDLY to 0xCCBB44, PaletteRole.OTHER_PLAYER to 0x66CCEE, PaletteRole.EXPLORED to 0x66CCEE
        ),
        ColourPalette.HIGH_CONTRAST to mapOf(
            PaletteRole.HEALTH to 0xFF7070, PaletteRole.MANA to 0x55C0FF, PaletteRole.VITAL_THREE to 0x40FF80, PaletteRole.VITAL_FOUR to 0xFFD400, PaletteRole.XP to 0xD070FF,
            PaletteRole.GOOD to 0x40FF80, PaletteRole.BAD to 0xFF7070,
            PaletteRole.HOSTILE to 0xFF7070, PaletteRole.FRIENDLY to 0xFFD400, PaletteRole.OTHER_PLAYER to 0x40FF80, PaletteRole.EXPLORED to 0x40FF80
        )
    )

    fun rgb(palette: ColourPalette, role: PaletteRole): Int = table.getValue(palette).getValue(role)

    /** The colour of the vital in position [index] of the game's vital list, with health always the first. */
    fun vitalRole(id: String, index: Int): PaletteRole = when {
        id.contains("hp", ignoreCase = true) || index == 0 -> PaletteRole.HEALTH
        id.contains("mp", ignoreCase = true) || index == 1 -> PaletteRole.MANA
        index == 2 -> PaletteRole.VITAL_THREE
        else -> PaletteRole.VITAL_FOUR
    }

    /**
     * The colour the server chose for a floating text (`#RRGGBB`), moved to the palette's own damage or healing colour when
     * it is clearly red or green; any other colour is left alone. The standard palette changes nothing.
     */
    fun floatingText(hex: String, palette: ColourPalette): String {
        if (palette == ColourPalette.DEFAULT) return hex
        val rgb = hex.removePrefix("#").toIntOrNull(16) ?: return hex
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max <= 0.2f || (max - min) / max < 0.35f) return hex
        val hue = hueDegrees(r, g, b, max, min)
        val role = when {
            hue <= 25f || hue >= 335f -> PaletteRole.BAD
            hue in 80f..170f -> PaletteRole.GOOD
            else -> return hex
        }
        return "#%06X".format(rgb(palette, role))
    }

    private fun hueDegrees(r: Float, g: Float, b: Float, max: Float, min: Float): Float {
        val d = max - min
        val h = when (max) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return if (h < 0) h + 360f else h
    }
}
