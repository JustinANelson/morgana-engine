package com.morgana.client.render

/**
 * Experimental visual upgrades layered on the 2D renderer. Each can be toggled live (Ctrl+F6 / F7 / F8 in the client) so the
 * look can be compared with and without it. Nothing here changes game rules or the maps; it only changes how the client draws.
 */
object VisualFx {
    /** Light map + day/night colour grade + split-toned palette + mist (Ctrl+F6). */
    @JvmField var lighting: Boolean = true

    /** Sun/moon projected cast shadows for characters instead of a fixed oval (Ctrl+F7). */
    @JvmField var shadows: Boolean = true

    /** "HD-2D" look: tilt-shift depth of field, saturation lift and vignette on the flat 2D world (Ctrl+F8). */
    @JvmField var hd2d: Boolean = true

    /** Set false when the platform could not create framebuffers or compile the shader; everything falls back to the plain renderer. */
    @JvmField var available: Boolean = true

    /** The sky for the frame being drawn, or null when the game has no day/night clock (or the map is indoors). Set each frame by the client. */
    @JvmField var sky: SkyState? = null

    val postProcessing: Boolean get() = available && (lighting || hd2d)
}
