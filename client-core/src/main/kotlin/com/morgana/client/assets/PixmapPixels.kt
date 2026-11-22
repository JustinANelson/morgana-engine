package com.morgana.client.assets

import com.badlogic.gdx.graphics.Pixmap
import java.nio.ByteOrder

/**
 * Fast whole-pixmap writes for pixmaps that go straight to the GPU.
 *
 * Filling a pixmap with [Pixmap.drawPixel] is fine on desktop, but on the browser (gdx-teavm) every drawing call copies
 * the pixmap's entire pixel buffer out of the wasm heap, so a per-pixel loop costs O(pixels²): filling the starter
 * character atlas that way kept the web client on a black screen for over a minute after the asset preloader finished.
 * Writing the packed RGBA8888 values straight into [Pixmap.getPixels] is one linear copy everywhere.
 */

/**
 * Overwrites every pixel of this RGBA8888 pixmap with [pixels] (packed `0xRRGGBBAA`, row-major, top row first), with no
 * blending: the same result as [Pixmap.drawPixel] under [Pixmap.Blending.None].
 *
 * Only for pixmaps that are uploaded to a [com.badlogic.gdx.graphics.Texture] (or written to a file) next: on the browser
 * the write lands in the buffer textures upload from, not in the wasm copy that [Pixmap.getPixel] and the drawing calls
 * use, so do not read or draw on the pixmap afterwards.
 */
fun Pixmap.writeRgba8888(pixels: IntArray) {
    require(format == Pixmap.Format.RGBA8888) { "writeRgba8888 needs an RGBA8888 pixmap, not $format" }
    val count = width * height
    require(pixels.size >= count) { "need $count pixels for a ${width}x$height pixmap, got ${pixels.size}" }
    // A duplicate, so the pixmap's own buffer keeps its position and byte order; big-endian ints are R, G, B, A bytes.
    val view = getPixels().duplicate().order(ByteOrder.BIG_ENDIAN)
    view.clear()
    view.asIntBuffer().put(pixels, 0, count)
}

/** A new RGBA8888 pixmap owned by the caller, holding [pixels] (see [writeRgba8888] for when this is safe to use). */
fun rgba8888UploadPixmap(width: Int, height: Int, pixels: IntArray): Pixmap =
    Pixmap(width, height, Pixmap.Format.RGBA8888).also { it.writeRgba8888(pixels) }
