package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.TileNoise
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hand-pixelled overworld nature props in the style of commercial top-down sheets: leafy crowns made of lit clumps
 * with a ragged fringe and a dark-green outline, root-flared trunks, grass skirts of lit blades around the base,
 * fruit and flowers, mossy mounds, flat-shaded boulders and vine-covered ruins.
 *
 * Every prop is drawn relative to its canvas, so it works at any tile size. Palettes run dark (index 0) to light.
 */
object NatureProps {
    private class Lobe(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

    private fun put(img: TileImage, x: Int, y: Int, rgba: Int) {
        if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, rgba)
    }

    private fun tone(r: PaletteRamp, i: Int): Int = r.colors[i.coerceIn(0, r.colors.lastIndex)]

    private fun h(x: Int, y: Int, s: Long): Float = TileNoise.hash(x, y, s)

    /** Soft two-ring translucent ground shadow. */
    private fun groundShadow(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        for (ring in 0..1) {
            val k = 1f - ring * 0.35f
            val alpha = if (ring == 0) 54 else 70
            for (y in (cy - ry * k - 1).toInt()..(cy + ry * k + 1).toInt()) {
                for (x in (cx - rx * k - 1).toInt()..(cx + rx * k + 1).toInt()) {
                    val dx = (x + 0.5f - cx) / (rx * k)
                    val dy = (y + 0.5f - cy) / (ry * k)
                    if (dx * dx + dy * dy <= 1f && x in 0 until img.width && y in 0 until img.height) {
                        img.drawPixel(x, y, PaletteRamp.rgba(18, 28, 20, alpha))
                    }
                }
            }
        }
    }

    /** Lit grass blades scattered inside an ellipse, drawn under the prop so only a fringe shows. */
    private fun skirt(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, ramp: PaletteRamp, s: Long, density: Float = 0.5f) {
        for (y in (cy - ry - 3).toInt()..(cy + ry + 2).toInt()) {
            for (x in (cx - rx - 2).toInt()..(cx + rx + 2).toInt()) {
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = hypot(nx, ny) + (h(x, y, s) - 0.5f) * 0.45f
                if (d > 1f) continue
                if (h(x, y, s + 5L) > density * (1.15f - d * 0.5f)) continue
                put(img, x, y, tone(ramp, 1))
                put(img, x, y - 1, tone(ramp, 2))
                val lean = if (h(x, y, s + 9L) > 0.5f) 1 else -1
                put(img, x + lean, y - 2, tone(ramp, if (h(x, y, s + 11L) > 0.55f) 4 else 3))
            }
        }
    }

    private class Crown(val inside: Array<BooleanArray>, val edge: Array<BooleanArray>)

    /**
     * Paints a leafy mass from overlapping spherical [lobes]: spherical shading from the upper left, 2 x 2 leaf-clump
     * lights and shadows, dark seams where a lobe overlaps its upper-left neighbour, a ragged outline of the ramp's
     * darkest colour and a lit rim inside it.
     */
    private fun paintCrown(img: TileImage, lobes: List<Lobe>, ramp: PaletteRamp, s: Long, rough: Float = 0.14f, leafy: Boolean = true): Crown {
        val w = img.width
        val hh = img.height
        val inside = Array(hh) { BooleanArray(w) }
        val id = Array(hh) { IntArray(w) { -1 } }
        val t = Array(hh) { FloatArray(w) }
        for (y in 0 until hh) {
            for (x in 0 until w) {
                var best = -9f
                var bi = -1
                var bnx = 0f
                var bny = 0f
                for ((i, l) in lobes.withIndex()) {
                    val nx = (x + 0.5f - l.cx) / l.rx
                    val ny = (y + 0.5f - l.cy) / l.ry
                    val jag = (h(x, y, s) - 0.5f) * rough + (h(x / 3, y / 3, s + 9L) - 0.5f) * rough * 0.6f
                    val d = 1f - hypot(nx, ny) + jag
                    if (d > best) { best = d; bi = i; bnx = nx; bny = ny }
                }
                if (best <= 0f) continue
                inside[y][x] = true
                id[y][x] = bi
                val z = sqrt(max(0f, 1f - bnx * bnx - bny * bny))
                var v = 0.30f + 0.24f * z - 0.24f * bnx - 0.34f * bny + (0.5f - y.toFloat() / hh) * 0.20f
                if (leafy) {
                    val clump = h(x / 2 + y / 4, y / 2, s + 31L)
                    if (clump > 0.78f) v += 0.20f else if (clump < 0.16f) v -= 0.17f
                }
                t[y][x] = v
            }
        }
        // Drop stray pixels so the silhouette is one connected mass with a continuous outline.
        for (y in 1 until hh - 1) {
            for (x in 1 until w - 1) {
                if (!inside[y][x]) continue
                val n = (if (inside[y - 1][x]) 1 else 0) + (if (inside[y + 1][x]) 1 else 0) +
                    (if (inside[y][x - 1]) 1 else 0) + (if (inside[y][x + 1]) 1 else 0)
                if (n <= 1) inside[y][x] = false
            }
        }
        val edge = Array(hh) { BooleanArray(w) }
        for (y in 0 until hh) {
            for (x in 0 until w) {
                if (!inside[y][x]) continue
                edge[y][x] = x == 0 || y == 0 || x == w - 1 || y == hh - 1 ||
                    !inside[y - 1][x] || !inside[y + 1][x] || !inside[y][x - 1] || !inside[y][x + 1]
            }
        }
        for (y in 0 until hh) {
            for (x in 0 until w) {
                if (!inside[y][x]) continue
                var v = t[y][x]
                if (x > 0 && y > 0 && inside[y - 1][x - 1] && id[y - 1][x - 1] != id[y][x]) v -= 0.14f
                if (!edge[y][x] && ((x > 0 && edge[y][x - 1]) || (y > 0 && edge[y - 1][x]))) v += 0.12f
                val c = if (edge[y][x]) tone(ramp, 0) else ramp.sample(v.coerceIn(0.06f, 0.97f))
                img.setPixel(x, y, c)
            }
        }
        return Crown(inside, edge)
    }

    private fun trunk(img: TileImage, cx: Float, topY: Int, baseY: Int, width: Int, wood: PaletteRamp) {
        val half = width / 2f
        val rootRows = ((baseY - topY) * 0.28f).toInt().coerceAtLeast(3)
        for (y in topY..baseY) {
            val flare = if (y > baseY - rootRows) ((y - (baseY - rootRows)) * 1.1f).toInt() else 0
            val x0 = (cx - half - flare).toInt()
            val x1 = (cx + half + flare).toInt()
            for (x in x0..x1) {
                val n = (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1)
                val edge = x == x0 || x == x1
                var v = 0.80f - n * 0.55f
                if ((x + y / 3) % 3 == 0) v -= 0.12f
                if (y < topY + 5) v -= 0.18f
                put(img, x, y, if (edge) tone(wood, 0) else wood.sample(v.coerceIn(0.1f, 0.92f)))
            }
        }
        // Two roots creeping out sideways along the ground.
        for (dir in intArrayOf(-1, 1)) {
            for (i in 0 until rootRows) {
                val x = (cx + dir * (half + rootRows * 1.1f + i)).toInt()
                val y = baseY - (i * 0.5f).toInt()
                put(img, x, y, if (dir < 0) wood.sample(0.55f) else wood.sample(0.25f))
                put(img, x, y + 1, tone(wood, 0))
            }
        }
    }

    private fun fruit(img: TileImage, crown: Crown, ramp: PaletteRamp, s: Long, chance: Float, cell: Int) {
        for (cy in 0 until img.height / cell) {
            for (cx in 0 until img.width / cell) {
                if (h(cx, cy, s + 401L) > chance) continue
                val x = cx * cell + (h(cx, cy, s + 402L) * (cell - 2)).toInt()
                val y = cy * cell + (h(cx, cy, s + 403L) * (cell - 2)).toInt()
                if (x + 1 >= img.width || y + 1 >= img.height) continue
                if (!(crown.inside[y][x] && crown.inside[y + 1][x + 1]) || crown.edge[y][x] || crown.edge[y + 1][x + 1]) continue
                put(img, x, y, tone(ramp, 4))
                put(img, x + 1, y, tone(ramp, 2))
                put(img, x, y + 1, tone(ramp, 2))
                put(img, x + 1, y + 1, tone(ramp, 1))
            }
        }
    }

    // ---- public props -------------------------------------------------------------------------------------------

    /** A broad leafy tree (3 x 3 tiles): five-lobe crown, root-flared trunk, grass skirt. */
    fun leafyTree(img: TileImage, w: Int, hh: Int, leaf: PaletteRamp, wood: PaletteRamp, s: Long, fruitRamp: PaletteRamp? = null) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        groundShadow(img, fw * 0.50f, fh * 0.90f, fw * 0.30f, fh * 0.05f)
        skirt(img, fw * 0.50f, fh * 0.90f, fw * 0.36f, fh * 0.06f, leaf, s)
        trunk(img, fw * 0.50f, (fh * 0.62f).toInt(), (fh * 0.90f).toInt(), (fw * 0.15f).toInt().coerceAtLeast(3), wood)
        val lobes = listOf(
            Lobe(fw * 0.50f, fh * 0.43f, fw * 0.36f, fh * 0.30f),
            Lobe(fw * 0.27f, fh * 0.50f, fw * 0.22f, fh * 0.21f),
            Lobe(fw * 0.73f, fh * 0.50f, fw * 0.22f, fh * 0.21f),
            Lobe(fw * 0.37f, fh * 0.28f, fw * 0.25f, fh * 0.22f),
            Lobe(fw * 0.64f, fh * 0.29f, fw * 0.24f, fh * 0.21f),
            Lobe(fw * 0.50f, fh * 0.17f, fw * 0.20f, fh * 0.14f)
        )
        val crown = paintCrown(img, lobes, leaf, s)
        if (fruitRamp != null) fruit(img, crown, fruitRamp, s, 0.62f, (fw / 11f).toInt().coerceAtLeast(5))
        // Front grass tufts overlapping the trunk base.
        skirt(img, fw * 0.50f, fh * 0.92f, fw * 0.27f, fh * 0.025f, leaf, s + 77L, 0.8f)
    }

    /** A rounded bush (2 x 2 tiles) dotted with blooms or berries in [bloom]. */
    fun bloomBush(img: TileImage, w: Int, hh: Int, leaf: PaletteRamp, bloom: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        groundShadow(img, fw * 0.50f, fh * 0.84f, fw * 0.32f, fh * 0.06f)
        skirt(img, fw * 0.50f, fh * 0.84f, fw * 0.40f, fh * 0.08f, leaf, s)
        val lobes = listOf(
            Lobe(fw * 0.50f, fh * 0.52f, fw * 0.31f, fh * 0.28f),
            Lobe(fw * 0.33f, fh * 0.58f, fw * 0.20f, fh * 0.20f),
            Lobe(fw * 0.67f, fh * 0.58f, fw * 0.20f, fh * 0.20f),
            Lobe(fw * 0.50f, fh * 0.36f, fw * 0.20f, fh * 0.17f)
        )
        val crown = paintCrown(img, lobes, leaf, s)
        fruit(img, crown, bloom, s + 3L, 0.78f, (fw / 9f).toInt().coerceAtLeast(4))
    }

    /** A grassy earth mound (2 x 2 tiles): dirt dome with a tufted grass cap. */
    fun mossMound(img: TileImage, w: Int, hh: Int, grass: PaletteRamp, dirt: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        skirt(img, fw * 0.50f, fh * 0.80f, fw * 0.42f, fh * 0.09f, grass, s)
        paintCrown(img, listOf(Lobe(fw * 0.50f, fh * 0.68f, fw * 0.37f, fh * 0.20f)), dirt, s + 2L, 0.12f, leafy = false)
        val cap = listOf(
            Lobe(fw * 0.50f, fh * 0.50f, fw * 0.31f, fh * 0.17f),
            Lobe(fw * 0.32f, fh * 0.56f, fw * 0.17f, fh * 0.12f),
            Lobe(fw * 0.68f, fh * 0.57f, fw * 0.17f, fh * 0.11f)
        )
        paintCrown(img, cap, grass, s + 4L, 0.3f)
        skirt(img, fw * 0.50f, fh * 0.80f, fw * 0.40f, fh * 0.03f, grass, s + 8L, 0.9f)
    }

    private fun rock(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, ramp: PaletteRamp, s: Long) {
        val sides = 7
        val radii = FloatArray(sides) { 0.66f + h(it, 0, s) * 0.34f }
        fun radiusAt(a: Float): Float {
            val f = ((a + PI.toFloat()) / (2f * PI.toFloat())) * sides
            val i = f.toInt() % sides
            val frac = f - f.toInt()
            // Straight edges between corners give the rock flat facets.
            return radii[i] * (1f - frac) + radii[(i + 1) % sides] * frac
        }
        val inside = Array(img.height) { BooleanArray(img.width) }
        for (y in (cy - ry - 1).toInt()..(cy + ry + 1).toInt()) {
            for (x in (cx - rx - 1).toInt()..(cx + rx + 1).toInt()) {
                if (x !in 0 until img.width || y !in 0 until img.height) continue
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                inside[y][x] = hypot(nx, ny) < radiusAt(atan2(ny, nx))
            }
        }
        for (y in (cy - ry - 1).toInt()..(cy + ry + 1).toInt()) {
            for (x in (cx - rx - 1).toInt()..(cx + rx + 1).toInt()) {
                if (x !in 1 until img.width - 1 || y !in 1 until img.height - 1 || !inside[y][x]) continue
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                val edge = !inside[y - 1][x] || !inside[y + 1][x] || !inside[y][x - 1] || !inside[y][x + 1]
                val a = atan2(ny, nx)
                val sector = (((a + PI.toFloat()) / (2f * PI.toFloat())) * sides).toInt() % sides
                val na = (sector + 0.5f) / sides * 2f * PI.toFloat() - PI.toFloat()
                // Flat facet: shade by the facet's own direction, light from the upper left; the middle is the top face.
                val facet = -0.62f * cos(na) - 0.78f * sin(na)
                val r = hypot(nx, ny)
                val idx = when {
                    r < 0.50f && ny < 0.10f -> 4
                    facet > 0.55f -> 4
                    facet > 0.10f -> 3
                    facet > -0.45f -> 2
                    else -> 1
                }
                put(img, x, y, if (edge) tone(ramp, 0) else tone(ramp, idx))
            }
        }
        // A crack across the face.
        var xx = (cx + rx * 0.20f).toInt()
        var yy = (cy - ry * 0.20f).toInt()
        for (i in 0 until (ry * 0.8f).toInt()) {
            if (xx in 1 until img.width - 1 && yy in 1 until img.height - 1 && inside[yy][xx] &&
                inside[yy + 1][xx] && inside[yy][xx - 1] && inside[yy][xx + 1]) put(img, xx, yy, tone(ramp, 1))
            yy++
            if (h(i, 3, s) > 0.45f) xx--
        }
    }

    /** A boulder cluster (2 x 2 tiles) of flat-shaded rocks with a grass skirt. */
    fun boulder(img: TileImage, w: Int, hh: Int, stone: PaletteRamp, grass: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        skirt(img, fw * 0.50f, fh * 0.80f, fw * 0.44f, fh * 0.10f, grass, s)
        rock(img, fw * 0.62f, fh * 0.48f, fw * 0.24f, fh * 0.26f, stone, s + 1L)
        rock(img, fw * 0.36f, fh * 0.60f, fw * 0.22f, fh * 0.22f, stone, s + 2L)
        rock(img, fw * 0.58f, fh * 0.66f, fw * 0.20f, fh * 0.17f, stone, s + 3L)
        skirt(img, fw * 0.50f, fh * 0.82f, fw * 0.40f, fh * 0.03f, grass, s + 6L, 0.9f)
    }

    /** A broken wall (3 x 2 tiles) of stone courses with a ragged top, creeping vines, rubble and a grass skirt. */
    fun ruinWall(img: TileImage, w: Int, hh: Int, stone: PaletteRamp, vine: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        val base = (fh * 0.82f).toInt()
        val x0 = (fw * 0.10f).toInt()
        val x1 = (fw * 0.90f).toInt()
        skirt(img, fw * 0.50f, fh * 0.84f, fw * 0.46f, fh * 0.07f, vine, s)
        val course = (fh * 0.11f).toInt().coerceAtLeast(3)
        for (x in x0..x1) {
            val nx = (x - x0).toFloat() / (x1 - x0)
            val topN = 0.16f + 0.34f * h(x / 9, 0, s) + 0.16f * kotlin.math.abs(sin(nx * PI.toFloat() * 1.3f))
            // Whole courses only, so the broken top steps down in blocks.
            val top = ((fh * topN).toInt() / course) * course
            for (y in top..base) {
                val row = (y - top) / course
                val offset = if (row % 2 == 0) 0 else course
                val joint = (x + offset) % (course * 2) == 0
                val seam = (y - top) % course == 0
                val edgeCol = x == x0 || x == x1 || y == top
                var v = 0.74f - nx * 0.28f - (y - top).toFloat() / fh * 0.35f + (h(x / 3, y / 3, s + 3L) - 0.5f) * 0.18f
                if (seam || joint) v -= 0.22f
                put(img, x, y, if (edgeCol || y == base) tone(stone, 0) else stone.sample(v.coerceIn(0.08f, 0.95f)))
            }
        }
        // Vines draped from the top, with leaf pairs along them.
        for (k in 0 until 4) {
            var vx = (x0 + (x1 - x0) * (0.12f + 0.24f * k + 0.08f * h(k, 1, s))).toInt()
            var vy = (fh * (0.22f + 0.1f * h(k, 2, s))).toInt()
            val len = (fh * (0.22f + 0.22f * h(k, 4, s))).toInt()
            for (i in 0 until len) {
                put(img, vx, vy, tone(vine, 1))
                put(img, vx + 1, vy, tone(vine, 2))
                if (i % 3 == 1) {
                    put(img, vx - 1, vy, tone(vine, 3))
                    put(img, vx - 2, vy, tone(vine, 2))
                    put(img, vx + 2, vy + 1, tone(vine, 3))
                    put(img, vx + 3, vy + 1, tone(vine, 4))
                }
                vy++
                if (h(i, k, s + 8L) > 0.65f) vx += if (h(i, k, s + 9L) > 0.5f) 1 else -1
            }
        }
        rock(img, fw * 0.14f, fh * 0.84f, fw * 0.06f, fh * 0.06f, stone, s + 5L)
        rock(img, fw * 0.84f, fh * 0.85f, fw * 0.07f, fh * 0.06f, stone, s + 6L)
        skirt(img, fw * 0.50f, fh * 0.86f, fw * 0.44f, fh * 0.03f, vine, s + 12L, 0.9f)
    }
}
