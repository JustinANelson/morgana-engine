package com.morgana.client.assets

/** Geometry input for packing one rectangular sprite/icon/sheet entry into a texture atlas. */
data class AtlasRectInput(
    val key: String,
    val width: Int,
    val height: Int
)

/** Pixel placement of [key] inside atlas page [pageIndex]. */
data class AtlasPlacement(
    val key: String,
    val pageIndex: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/** One packed atlas page's dimensions and member placements. */
data class AtlasPageSpec(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val placements: List<AtlasPlacement>
)

/** Full result of packing a batch of textures into bounded atlas pages. */
data class AtlasPackResult(
    val pages: List<AtlasPageSpec>,
    val placementsByKey: Map<String, AtlasPlacement>,
    val oversizedKeys: Set<String>
)

/**
 * Pure, deterministic shelf bin-packer for bundling sprites, icons, paperdolls, projectiles,
 * and animation strips onto shared atlas pages (zero LibGDX/GL dependency; TeaVM/Android/JVM safe).
 */
object TextureAtlasPacker {

    const val DEFAULT_MAX_PAGE_SIZE = 1024
    const val DEFAULT_PADDING = 1

    /**
     * Packs [items] deterministically into pages bounded by [maxPageWidth] x [maxPageHeight], leaving [padding]
     * transparent gutter pixels on every side of each region. Items larger than a single page are returned in
     * [AtlasPackResult.oversizedKeys] so callers can keep them as standalone textures.
     */
    fun pack(
        items: List<AtlasRectInput>,
        maxPageWidth: Int = DEFAULT_MAX_PAGE_SIZE,
        maxPageHeight: Int = DEFAULT_MAX_PAGE_SIZE,
        padding: Int = DEFAULT_PADDING,
        powerOfTwo: Boolean = true
    ): AtlasPackResult {
        val pad = padding.coerceAtLeast(0)
        val maxW = maxPageWidth.coerceAtLeast(32)
        val maxH = maxPageHeight.coerceAtLeast(32)

        val oversized = linkedSetOf<String>()
        val packable = ArrayList<AtlasRectInput>(items.size)
        for (item in items) {
            if (item.width <= 0 || item.height <= 0 || item.width + pad * 2 > maxW || item.height + pad * 2 > maxH) {
                oversized += item.key
            } else {
                packable += item
            }
        }

        // Deterministic sort: tallest first reduces wasted vertical space in each shelf.
        packable.sortWith(compareByDescending<AtlasRectInput> { it.height }.thenByDescending { it.width }.thenBy { it.key })

        val builtPages = mutableListOf<PageBuilder>()
        val placementsByKey = linkedMapOf<String, AtlasPlacement>()

        for (item in packable) {
            val needW = item.width + pad * 2
            val needH = item.height + pad * 2
            var placed: AtlasPlacement? = null
            for (page in builtPages) {
                placed = page.tryPlace(item.key, item.width, item.height, needW, needH, pad)
                if (placed != null) break
            }
            if (placed == null) {
                val page = PageBuilder(builtPages.size, maxW, maxH)
                builtPages += page
                placed = page.tryPlace(item.key, item.width, item.height, needW, needH, pad)
                    ?: error("Validated item ${item.key} (${item.width}x${item.height}) failed to fit fresh page")
            }
            placementsByKey[item.key] = placed
        }

        val pageSpecs = builtPages.map { it.build(powerOfTwo) }
        return AtlasPackResult(pages = pageSpecs, placementsByKey = placementsByKey, oversizedKeys = oversized)
    }

    /**
     * Composes one atlas page's top-down `RGBA8888` pixel buffer from [page] placements and [pixelsByKey].
     * Padding pixels remain transparent `0x00000000`.
     */
    fun blitPageRgba(page: AtlasPageSpec, pixelsByKey: Map<String, IntArray>): IntArray {
        val out = IntArray(page.width * page.height)
        for (placement in page.placements) {
            val src = pixelsByKey[placement.key] ?: continue
            val pw = placement.width
            val ph = placement.height
            for (y in 0 until ph) {
                val srcOff = y * pw
                val dstOff = (placement.y + y) * page.width + placement.x
                for (x in 0 until pw) {
                    out[dstOff + x] = src[srcOff + x]
                }
            }
        }
        return out
    }

    private class Shelf(val y: Int, var height: Int) {
        var cursorX: Int = 0
    }

    private class PageBuilder(
        private val pageIndex: Int,
        private val maxWidth: Int,
        private val maxHeight: Int
    ) {
        private val shelves = mutableListOf<Shelf>()
        private val placements = mutableListOf<AtlasPlacement>()
        private var usedWidth = 0
        private var usedHeight = 0

        fun tryPlace(
            key: String,
            width: Int,
            height: Int,
            needW: Int,
            needH: Int,
            pad: Int
        ): AtlasPlacement? {
            // Try existing shelves first (only if height fits inside the shelf height).
            for (shelf in shelves) {
                if (needH <= shelf.height && shelf.cursorX + needW <= maxWidth) {
                    val x = shelf.cursorX + pad
                    val y = shelf.y + pad
                    shelf.cursorX += needW
                    usedWidth = maxOf(usedWidth, shelf.cursorX)
                    return AtlasPlacement(key, pageIndex, x, y, width, height).also { placements += it }
                }
            }
            // Start a new shelf below previous ones.
            if (usedHeight + needH <= maxHeight && needW <= maxWidth) {
                val shelf = Shelf(y = usedHeight, height = needH)
                shelves += shelf
                val x = shelf.cursorX + pad
                val y = shelf.y + pad
                shelf.cursorX += needW
                usedWidth = maxOf(usedWidth, shelf.cursorX)
                usedHeight += needH
                return AtlasPlacement(key, pageIndex, x, y, width, height).also { placements += it }
            }
            return null
        }

        fun build(powerOfTwo: Boolean): AtlasPageSpec {
            val w = if (powerOfTwo) nextPow2(usedWidth.coerceAtLeast(32)).coerceAtMost(maxWidth) else usedWidth.coerceAtLeast(1)
            val h = if (powerOfTwo) nextPow2(usedHeight.coerceAtLeast(32)).coerceAtMost(maxHeight) else usedHeight.coerceAtLeast(1)
            return AtlasPageSpec(pageIndex, w, h, placements.toList())
        }

        private fun nextPow2(v: Int): Int {
            var n = 1
            while (n < v) n = n shl 1
            return n
        }
    }
}

