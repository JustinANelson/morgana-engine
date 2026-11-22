package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color

/**
 * Colours that mean something in the game rather than follow the colour scheme: the same vital gets the same colour on
 * the HUD bars and the compact status bars. Everything else (frames, tracks, text) comes from the palette.
 */
object UiColors {
    /**
     * The fill colour for vital [id], the [index]th in the game's vital list: in the standard palette health red, mana blue,
     * then green and amber; the player's colour-blind palette changes them (see [com.morgana.client.access.PaletteColours]).
     * The colour is shared: copy it before changing it.
     */
    fun vital(id: String, index: Int): Color =
        com.morgana.client.access.Accessibility.colour(com.morgana.client.access.PaletteColours.vitalRole(id, index))

    /** The experience bar's colour: its own role, so it never matches a vital such as a gauge. */
    fun xp(): Color = com.morgana.client.access.Accessibility.colour(com.morgana.client.access.PaletteRole.XP)
}
