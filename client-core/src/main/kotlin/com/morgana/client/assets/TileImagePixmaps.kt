package com.morgana.client.assets

import com.badlogic.gdx.graphics.Pixmap
import com.morgana.tilegen.TileImage

/**
 * Bridges the engine-free `:tilegen` module and LibGDX. Both sides use packed RGBA8888, so pixels copy one-to-one
 * (blending is switched off so translucent pixels are copied, not blended a second time).
 */

/** A new [Pixmap] owned by the caller with the same pixels as this image. */
fun TileImage.toPixmap(): Pixmap {
    val pm = Pixmap(width, height, Pixmap.Format.RGBA8888)
    pm.blending = Pixmap.Blending.None
    for (y in 0 until height) for (x in 0 until width) pm.drawPixel(x, y, pixels[y * width + x])
    pm.blending = Pixmap.Blending.SourceOver
    return pm
}

/** A [TileImage] copy of this pixmap's pixels (read as RGBA8888 whatever the pixmap's format). */
fun Pixmap.toTileImage(): TileImage {
    val image = TileImage(width, height)
    for (y in 0 until height) for (x in 0 until width) image.pixels[y * width + x] = getPixel(x, y)
    return image
}
