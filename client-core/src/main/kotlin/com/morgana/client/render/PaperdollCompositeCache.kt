package com.morgana.client.render

import com.morgana.domain.item.PaperdollComposer
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.spatial.Direction

/** Raw top-down `RGBA8888` pixel buffer (`y * width + x`) extracted from a sprite cell or legacy overlay. */
class RgbaCell(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val isOverlay: Boolean = false
)

/** One ordered, optionally tinted pixel source ready for CPU composition into a character cell. */
data class ComposedPixelLayer(
    val slot: PaperdollSlot,
    val cell: RgbaCell,
    val tintHex: String? = null,
    /** Outline this layer's silhouette with a 1 pixel [GLOW_RGBA] line (drawn behind the layer, over the layers below it). */
    val glow: Boolean = false
)

/** Cache key identifying one composed character animation frame under a specific asset revision. */
data class PaperdollCompositeKey(
    val spriteId: Int,
    val directionRow: Int,
    val column: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val layers: List<PaperdollLayer>,
    val artEpoch: Long
)

private data class CharacterAppearanceSignature(
    val spriteId: Int,
    val layers: List<PaperdollLayer>,
    val artEpoch: Long
)

private data class CompositeSlotKey(
    val characterId: String,
    val frame: PaperdollCompositeKey
)

/**
 * Pure, deterministic per-character LRU cache and pixel compositor for Paperdolling v2.
 *
 * Keeps composed character frames keyed per `(characterId, frameKey)`, automatically dropping a character's
 * old entries (and invoking [onEvict] to free GPU textures) when their equipment/sprite/art changes or when
 * total cached frames exceed [maxEntries].
 */
class PaperdollCompositeCache<V>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val onEvict: (V) -> Unit = {}
) {
    private val entries = LinkedHashMap<CompositeSlotKey, V>()
    private val activeAppearance = LinkedHashMap<String, CharacterAppearanceSignature>()

    var hitCount: Long = 0L
        private set
    var missCount: Long = 0L
        private set

    val size: Int
        get() = entries.size

    fun getOrPut(characterId: String, key: PaperdollCompositeKey, factory: () -> V): V {
        val signature = CharacterAppearanceSignature(key.spriteId, key.layers, key.artEpoch)
        val prevSignature = activeAppearance[characterId]
        if (prevSignature != null && prevSignature != signature) {
            invalidateCharacter(characterId)
        }
        activeAppearance[characterId] = signature

        val slotKey = CompositeSlotKey(characterId, key)
        val existing = entries.remove(slotKey)
        if (existing != null) {
            entries[slotKey] = existing // refresh LRU order
            hitCount++
            return existing
        }

        missCount++
        val created = factory()
        entries[slotKey] = created
        trimToCapacity()
        return created
    }

    /** Drops every cached frame for [characterId] (called on equip/unequip/map leave) and disposes them. */
    fun invalidateCharacter(characterId: String) {
        activeAppearance.remove(characterId)
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val (key, value) = iter.next()
            if (key.characterId == characterId) {
                iter.remove()
                onEvict(value)
            }
        }
    }

    /** Drops and disposes all cached composites (called on realm change, art live-import, or dispose). */
    fun invalidateAll() {
        activeAppearance.clear()
        val values = entries.values.toList()
        entries.clear()
        for (value in values) onEvict(value)
    }

    private fun trimToCapacity() {
        val cap = maxEntries.coerceAtLeast(1)
        val iter = entries.entries.iterator()
        while (entries.size > cap && iter.hasNext()) {
            val (_, oldest) = iter.next()
            iter.remove()
            onEvict(oldest)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256

        /** The glow colour of the best gear: a warm yellow, packed `RGBA8888`. */
        const val GLOW_RGBA: Int = 0xFFD42AFF.toInt()

        /**
         * Paints a 1 pixel [GLOW_RGBA] outline around the opaque pixels of [src] as placed at ([offsetX], [offsetY]) in a
         * [w] x [h] buffer: every transparent-in-[src] pixel that touches one of its pixels above, below, left or right.
         * Pixels outside the buffer are clipped. The caller then draws the layer itself over the top.
         */
        private fun paintGlowOutline(dst: IntArray, w: Int, h: Int, src: RgbaCell, offsetX: Int, offsetY: Int, drawW: Int, drawH: Int) {
            val solid = BooleanArray(w * h)
            for (sy in 0 until drawH) {
                val dy = offsetY + sy
                if (dy !in 0 until h) continue
                for (sx in 0 until drawW) {
                    val dx = offsetX + sx
                    if (dx !in 0 until w) continue
                    if ((src.pixels[sy * src.width + sx] and 0xFF) != 0) solid[dy * w + dx] = true
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (solid[y * w + x]) continue
                    val touches = (x > 0 && solid[y * w + x - 1]) || (x < w - 1 && solid[y * w + x + 1]) ||
                        (y > 0 && solid[(y - 1) * w + x]) || (y < h - 1 && solid[(y + 1) * w + x])
                    if (touches) dst[y * w + x] = GLOW_RGBA
                }
            }
        }

        /**
         * Parses `#rrggbb` into packed `RGBA8888` (`0xRRGGBBFF`), defaulting to opaque white when null or malformed.
         */
        fun parseHexRgba8888(hex: String?): Int {
            val clean = hex?.trim()?.removePrefix("#") ?: return 0xFFFFFFFF.toInt()
            if (clean.length != 6) return 0xFFFFFFFF.toInt()
            val rgb = clean.toIntOrNull(16) ?: return 0xFFFFFFFF.toInt()
            return (rgb shl 8) or 0xFF
        }

        /**
         * Composes [rawLayers] (sorted via [PaperdollComposer.drawOrderRank] for [direction]) into a single top-down
         * `RGBA8888` pixel array of `[cellWidth] x [cellHeight]`.
         *
         * Legacy overlays (`cell.isOverlay`) shorter/narrower than the target cell are centered vertically and
         * horizontally identically to `SpritePlacement.placeOverlay`.
         */
        fun composeRgbaCell(
            cellWidth: Int,
            cellHeight: Int,
            rawLayers: List<ComposedPixelLayer>,
            direction: Direction
        ): IntArray {
            val w = cellWidth.coerceAtLeast(1)
            val h = cellHeight.coerceAtLeast(1)
            val dst = IntArray(w * h)
            val sorted = rawLayers.sortedBy { PaperdollComposer.drawOrderRank(it.slot, direction) }

            for (layer in sorted) {
                val src = layer.cell
                val tint = parseHexRgba8888(layer.tintHex)
                val tr = (tint ushr 24) and 0xFF
                val tg = (tint ushr 16) and 0xFF
                val tb = (tint ushr 8) and 0xFF

                val offsetX = if (src.isOverlay) (w - src.width) / 2 else 0
                val offsetY = if (src.isOverlay) (h - src.height) / 2 else 0
                val drawW = if (src.isOverlay) src.width else minOf(src.width, w)
                val drawH = if (src.isOverlay) src.height else minOf(src.height, h)

                if (layer.glow) paintGlowOutline(dst, w, h, src, offsetX, offsetY, drawW, drawH)

                for (sy in 0 until drawH) {
                    val dy = offsetY + sy
                    if (dy !in 0 until h) continue
                    for (sx in 0 until drawW) {
                        val dx = offsetX + sx
                        if (dx !in 0 until w) continue
                        val sp = src.pixels[sy * src.width + sx]
                        val sa = sp and 0xFF
                        if (sa == 0) continue

                        val sr = (((sp ushr 24) and 0xFF) * tr + 127) / 255
                        val sg = (((sp ushr 16) and 0xFF) * tg + 127) / 255
                        val sb = (((sp ushr 8) and 0xFF) * tb + 127) / 255

                        val idx = dy * w + dx
                        val dp = dst[idx]
                        val da = dp and 0xFF
                        if (sa == 255 || da == 0) {
                            dst[idx] = (sr shl 24) or (sg shl 16) or (sb shl 8) or sa
                        } else {
                            val invSa = 255 - sa
                            val dr = (dp ushr 24) and 0xFF
                            val dg = (dp ushr 16) and 0xFF
                            val db = (dp ushr 8) and 0xFF
                            val outA = sa + (da * invSa + 127) / 255
                            if (outA <= 0) {
                                dst[idx] = 0
                            } else {
                                val rNum = sr * sa + (dr * da * invSa + 127) / 255
                                val gNum = sg * sa + (dg * da * invSa + 127) / 255
                                val bNum = sb * sa + (db * da * invSa + 127) / 255
                                val outR = (rNum / outA).coerceIn(0, 255)
                                val outG = (gNum / outA).coerceIn(0, 255)
                                val outB = (bNum / outA).coerceIn(0, 255)
                                dst[idx] = (outR shl 24) or (outG shl 16) or (outB shl 8) or outA
                            }
                        }
                    }
                }
            }
            return dst
        }
    }
}

