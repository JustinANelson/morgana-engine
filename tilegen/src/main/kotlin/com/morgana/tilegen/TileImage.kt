package com.morgana.tilegen

/**
 * A plain in-memory RGBA8888 image (one `Int` per pixel, `0xRRGGBBAA`, row-major, origin top-left).
 *
 * The tile generators draw into this instead of an engine type so the module has no graphics dependency and runs
 * anywhere Kotlin/JVM does (desktop, Android, TeaVM, plain unit tests). New images start fully transparent.
 *
 * [drawPixel] and [drawImage] blend source-over like LibGDX `Pixmap`'s default blending (within rounding; the
 * generators only draw opaque colours, so their output is exact). Drawing outside the image is ignored.
 */
class TileImage(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "image size must be positive, got ${width}x$height" }
    }

    /** Raw pixels, `width * height` packed RGBA8888 values. */
    val pixels: IntArray = IntArray(width * height)

    fun getPixel(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    /** Overwrites a pixel without blending. */
    fun setPixel(x: Int, y: Int, rgba: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = rgba
    }

    /** Draws [rgba] at ([x], [y]) with source-over blending. */
    fun drawPixel(x: Int, y: Int, rgba: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        pixels[i] = blend(rgba, pixels[i])
    }

    /**
     * Draws the [w] x [h] region of [src] starting at ([srcX], [srcY]) onto this image at ([dstX], [dstY]) with
     * source-over blending.
     */
    fun drawImage(src: TileImage, srcX: Int, srcY: Int, w: Int, h: Int, dstX: Int, dstY: Int) {
        for (dy in 0 until h) {
            val sy = srcY + dy
            if (sy !in 0 until src.height) continue
            for (dx in 0 until w) {
                val sx = srcX + dx
                if (sx !in 0 until src.width) continue
                drawPixel(dstX + dx, dstY + dy, src.pixels[sy * src.width + sx])
            }
        }
    }

    /** A copy of the [w] x [h] region starting at ([x], [y]). */
    fun crop(x: Int, y: Int, w: Int, h: Int): TileImage {
        val out = TileImage(w, h)
        for (dy in 0 until h) for (dx in 0 until w) out.pixels[dy * w + dx] = getPixel(x + dx, y + dy)
        return out
    }

    fun copy(): TileImage = TileImage(width, height).also { pixels.copyInto(it.pixels) }

    companion object {
        /**
         * Porter-Duff source-over of straight-alpha [src] onto [dst]. Opaque sources replace, transparent sources
         * leave [dst] alone, and anything drawn onto a transparent pixel is copied unchanged.
         */
        fun blend(src: Int, dst: Int): Int {
            val srcA = src and 0xFF
            if (srcA == 0xFF) return src
            if (srcA == 0) return dst
            val dstA = dst and 0xFF
            if (dstA == 0) return src
            val sa = srcA / 255f
            val da = dstA / 255f * (1f - sa)
            val outA = sa + da
            fun channel(shift: Int): Int {
                val s = (src ushr shift) and 0xFF
                val d = (dst ushr shift) and 0xFF
                return ((s * sa + d * da) / outA + 0.5f).toInt().coerceIn(0, 255)
            }
            val a = (outA * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or a
        }
    }
}
