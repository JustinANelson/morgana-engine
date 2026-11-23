package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.SpriteFinish

/** A horse's coat: the body ramp and the (usually darker) mane and tail ramp, both dark to light. */
enum class HorseCoat(val coat: PaletteRamp, val mane: PaletteRamp) {
    BAY(ramp(52, 30, 24, 92, 54, 36, 134, 82, 50, 172, 114, 70, 208, 154, 108), ramp(18, 14, 16, 34, 26, 28, 54, 42, 42, 78, 64, 60, 104, 88, 82)),
    CHESTNUT(ramp(70, 32, 20, 118, 56, 30, 164, 84, 40, 200, 118, 58, 232, 160, 96), ramp(60, 30, 20, 96, 50, 28, 134, 74, 38, 170, 104, 56, 204, 140, 84)),
    BLACK(ramp(16, 16, 22, 34, 34, 44, 56, 56, 68, 82, 82, 96, 114, 114, 130), ramp(8, 8, 12, 18, 18, 24, 30, 30, 38, 46, 46, 56, 64, 64, 76)),
    WHITE(ramp(108, 112, 130, 156, 160, 178, 196, 200, 214, 226, 228, 238, 250, 250, 252), ramp(120, 124, 142, 156, 160, 176, 182, 186, 198, 206, 208, 218, 230, 232, 240)),
    DUN(ramp(104, 78, 46, 148, 116, 66, 190, 154, 90, 218, 188, 122, 238, 216, 158), ramp(36, 28, 22, 58, 46, 36, 82, 66, 52, 108, 88, 70, 134, 112, 90))
}

private fun ramp(vararg c: Int): PaletteRamp =
    PaletteRamp(IntArray(c.size / 3) { i -> PaletteRamp.rgba(c[i * 3], c[i * 3 + 1], c[i * 3 + 2]) })

/**
 * A horse for riders, drawn in code the same way as the paper dolls (banded shading from [PixelForm], a soft outline from
 * [SpriteFinish]), and a mounted pose that puts a paper-doll rider in the saddle.
 *
 * The canvas is [WIDTH] x [HEIGHT] (a horse is longer than a person and the rider sits above it) with the hooves on
 * [GROUND_Y]. Four facings are drawn: front (S), side (W, with E a mirror) and back (N). Actions are [PaperDollAction.IDLE]
 * (two frames: a breath, the tail and head moving) and [PaperDollAction.WALK] (four frames: a lateral-sequence walk, with the
 * body bobbing). The rider's legs are redrawn astride the horse; every other layer of the paper doll is reused.
 */
object MountSprite {
    const val WIDTH = 48
    const val HEIGHT = 64
    const val GROUND_Y = 60

    /** The saddle seat (where the rider's hips sit) for each facing: x of the rider's centre and y of its leg top. */
    private const val SADDLE_Y = 37

    /** Draws just the horse. [rider] shifts the head a little so the reins read; it does not draw the rider. */
    fun horse(dir: Direction8, action: PaperDollAction, frame: Int, coat: HorseCoat = HorseCoat.BAY, saddle: Boolean = false): TileImage {
        val base = dir.mirrorFrom ?: dir
        val (out, _) = drawHorse(base, action, frame, coat, saddle)
        val finished = finish(out)
        return if (dir.mirrorFrom != null) mirror(finished) else finished
    }

    /** A paper-doll [loadout] riding a horse. */
    fun mounted(
        gen: PaperDollSpriteGenerator,
        loadout: PaperDollLoadout,
        dir: Direction8,
        action: PaperDollAction,
        frame: Int,
        coat: HorseCoat = HorseCoat.BAY,
        style: PixelStyle = loadout.effectiveTheme().style
    ): TileImage {
        val base = dir.mirrorFrom ?: dir
        val (horse, bob) = drawHorse(base, action, frame, coat, saddle = true, part = if (base == Direction8.S) 1 else 0)
        val out = TileImage(WIDTH, HEIGHT)
        // Ground shadow first, under everything.
        SpriteFinish.softShadow(out, 24f, GROUND_Y - 0.5f, if (base == Direction8.W) 17f else 11f, 3f)
        val horseFin = finish(horse)
        // Far side of a profile horse is already inside the horse image; the rider goes on top.
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) out.drawPixel(x, y, horseFin.getPixel(x, y))

        val riderFrame = if (action == PaperDollAction.WALK) frame % 2 else frame % 2
        val (layers, _) = gen.generateLayeredFrame(loadout, base, PaperDollAction.IDLE, riderFrame, style)
        val ox = if (base == Direction8.W) 11 else 8
        val oy = SADDLE_Y - 34 + bob
        val theme = loadout.effectiveTheme()
        val legLayer = TileImage(WIDTH, HEIGHT)
        drawRiderLegs(legLayer, base, theme, loadout, bob)
        val legFin = finish(legLayer)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) out.drawPixel(x, y, legFin.getPixel(x, y))
        for (slot in PaperDollSlot.zOrderFor(base, PaperDollAction.IDLE)) {
            // A cape would hang through the horse, so a rider goes without one; the legs are redrawn astride.
            if (slot == PaperDollSlot.SHADOW || slot == PaperDollSlot.LEGS || slot == PaperDollSlot.FEET || slot == PaperDollSlot.CAPE) continue
            val layer = layers[slot] ?: continue
            for (y in 0 until layer.height) for (x in 0 until layer.width) {
                val p = layer.getPixel(x, y)
                if ((p and 0xFF) != 0) out.drawPixel(x + ox, y + oy, p)
            }
        }
        if (base == Direction8.S) {
            val (head, _) = drawHorse(base, action, frame, coat, saddle = true, part = 2)
            val headFin = finish(head)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) out.drawPixel(x, y, headFin.getPixel(x, y))
        }
        return if (dir.mirrorFrom != null) mirror(out) else out
    }

    // ---------------------------------------------------------------- horse

    private fun finish(img: TileImage): TileImage = SpriteFinish.finish(img)

    private fun mirror(src: TileImage): TileImage {
        val out = TileImage(src.width, src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) out.setPixel(src.width - 1 - x, y, src.getPixel(x, y))
        return out
    }

    /** The step (0..3) leg [legIndex] is at: a lateral sequence, hind right, front right, hind left, front left. */
    private fun step(action: PaperDollAction, frame: Int, legIndex: Int): Int =
        if (action == PaperDollAction.WALK) (frame + legIndex) % 4 else 1

    /** Hip-to-hoof geometry of a leg for [step]: x shift and how far the foot is lifted. */
    private fun gait(step: Int): Pair<Int, Int> = when (step) {
        0 -> -2 to 0
        1 -> 0 to 0
        2 -> 2 to 0
        else -> 0 to 3
    }

    private fun drawHorse(dir: Direction8, action: PaperDollAction, frame: Int, coat: HorseCoat, saddle: Boolean, part: Int = 0): Pair<TileImage, Int> {
        val img = TileImage(WIDTH, HEIGHT)
        val walk = action == PaperDollAction.WALK
        val bob = if (walk && frame % 2 == 1) -1 else 0
        val breath = if (!walk && frame % 2 == 1) 1 else 0
        when (dir) {
            Direction8.W -> drawSide(img, action, frame, coat, saddle, bob, breath)
            Direction8.N -> drawBack(img, action, frame, coat, saddle, bob)
            else -> drawFront(img, action, frame, coat, saddle, bob, breath, part)
        }
        return img to bob
    }

    private fun leg(img: TileImage, x: Int, hipY: Int, step: Int, pal: PaletteRamp, tone: Float, hoofPal: PaletteRamp, w: Int = 3) {
        val (dx, lift) = gait(step)
        val footY = GROUND_Y - 1 - lift
        val kneeY = (hipY + footY) / 2 + (if (lift > 0) 1 else 0)
        val kneeX = x + dx / 2 + (if (lift > 0) -1 else 0)
        val footX = x + dx
        // Thigh (wide) to the knee, cannon and fetlock (narrow) to the hoof.
        for (i in 0 until w) PixelForm.line(img, x + i, hipY, kneeX + i, kneeY) { pal.sample(tone + (if (i == 0) 0.2f else if (i == w - 1) -0.2f else 0f)) }
        for (i in 0 until (w - 1)) PixelForm.line(img, kneeX + i, kneeY, footX + i, footY - 2) { pal.sample(tone + (if (i == 0) 0.15f else -0.12f)) }
        // Hoof.
        PixelForm.column(img, footX - 1, footY - 2, w + 1, 3, hoofPal, 0.42f, round = false)
    }

    private fun hooves(coat: HorseCoat): PaletteRamp = HorseCoat.BLACK.coat

    private fun drawSide(img: TileImage, action: PaperDollAction, frame: Int, coat: HorseCoat, saddle: Boolean, bob: Int, breath: Int) {
        val c = coat.coat
        val m = coat.mane
        val b = bob
        val hoof = hooves(coat)
        // Far legs first (darker), then the body, then near legs.
        leg(img, 30, 46 + b, step(action, frame, 0), c, 0.34f, hoof)
        leg(img, 20, 46 + b, step(action, frame, 1), c, 0.34f, hoof)
        // Tail: from the croup, falling and swaying.
        val sway = when (frame % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 1 }
        for (i in 0 until 3) {
            PixelForm.line(img, 40 + i, 38 + b, 41 + sway + i, 52 + b) { t -> m.sample(0.78f - 0.45f * t - (if (i == 2) 0.15f else 0f)) }
        }
        PixelForm.line(img, 41, 52 + b, 41 + sway, 55 + b) { m.sample(0.3f) }
        // Neck, then barrel and quarters over it.
        for (t in 0..7) {
            val u = t / 7f
            val nx = 12f + 4f * u
            val ny = 28f + 11f * u + breath * 0.0f
            PixelForm.dome(img, nx, ny + b, 3.4f, 3.8f, c)
        }
        PixelForm.dome(img, 26f, 42f + b, 12.5f, 6.8f, c)
        PixelForm.dome(img, 35f, 42f + b, 7f, 7f, c)
        PixelForm.dome(img, 17f, 42f + b, 6.5f, 7f, c)
        // Belly shadow line and a lighter shoulder highlight.
        for (x in 14..38) img.setPixel(x, 48 + b, c.sample(0.22f))
        // Head: skull, then a muzzle pointing down and forward, nostril, eye, ear.
        val hy = 27 + b + breath
        PixelForm.dome(img, 9f, hy.toFloat(), 3.9f, 3.9f, c)
        PixelForm.dome(img, 5.2f, hy + 4.4f, 2.7f, 2.9f, c, bias = 0.06f)
        img.setPixel(3, hy + 6, c.sample(0.12f))
        img.setPixel(4, hy + 6, m.sample(0.2f))
        img.setPixel(7, hy - 1, m.sample(0.05f))
        img.setPixel(7, hy - 2, PaletteRamp.rgba(240, 236, 228))
        img.setPixel(11, hy - 5, c.sample(0.5f)); img.setPixel(12, hy - 6, c.sample(0.7f)); img.setPixel(11, hy - 4, c.sample(0.3f))
        // Mane along the crest and a forelock.
        PixelForm.line(img, 12, 22 + b + breath, 19, 36 + b) { t -> m.sample(0.74f - 0.3f * t) }
        PixelForm.line(img, 13, 22 + b + breath, 20, 36 + b) { t -> m.sample(0.48f - 0.2f * t) }
        PixelForm.line(img, 10, hy - 5, 9, hy - 2) { m.sample(0.6f) }
        // Near legs.
        leg(img, 33, 46 + b, step(action, frame, 2), c, 0.62f, hoof)
        leg(img, 17, 46 + b, step(action, frame, 3), c, 0.62f, hoof)
        if (saddle) {
            // A saddle blanket and the saddle itself on the back.
            val blanket = PaletteRamp(intArrayOf(PaletteRamp.rgba(78, 28, 34), PaletteRamp.rgba(120, 40, 46), PaletteRamp.rgba(168, 62, 62), PaletteRamp.rgba(208, 96, 84)))
            val leather = PaletteRamp(intArrayOf(PaletteRamp.rgba(50, 30, 22), PaletteRamp.rgba(86, 54, 36), PaletteRamp.rgba(122, 80, 52), PaletteRamp.rgba(156, 108, 70)))
            PixelForm.column(img, 21, 38 + b, 10, 3, blanket, 0.6f)
            PixelForm.column(img, 23, 36 + b, 6, 2, leather, 0.6f)
        }
    }

    private fun drawFront(img: TileImage, action: PaperDollAction, frame: Int, coat: HorseCoat, saddle: Boolean, bob: Int, breath: Int, part: Int) {
        // part 0 draws everything, 1 the body only and 2 the head only (a mounted horse's head is drawn over its rider).
        val c = coat.coat
        val m = coat.mane
        val hoof = hooves(coat)
        if (part != 2) {
        // Hind legs show a little behind the front pair; front legs flank the chest.
        leg(img, 29, 46 + bob, step(action, frame, 0), c, 0.34f, hoof, 2)
        leg(img, 17, 46 + bob, step(action, frame, 2), c, 0.34f, hoof, 2)
        PixelForm.dome(img, 24f, 42f + bob, 8.6f, 7.8f, c)
        leg(img, 28, 46 + bob, step(action, frame, 1), c, 0.55f, hoof)
        leg(img, 18, 46 + bob, step(action, frame, 3), c, 0.55f, hoof)
        if (saddle) {
            val blanket = PaletteRamp(intArrayOf(PaletteRamp.rgba(78, 28, 34), PaletteRamp.rgba(120, 40, 46), PaletteRamp.rgba(168, 62, 62), PaletteRamp.rgba(208, 96, 84)))
            PixelForm.column(img, 17, 38 + bob, 14, 2, blanket, 0.6f)
        }
        }
        if (part == 1) return
        // Neck and mane behind the head, then the head looking at us.
        PixelForm.dome(img, 24f, 40f + bob, 4.6f, 4.4f, c)
        PixelForm.column(img, 22, 36 + bob, 5, 4, m, 0.5f)
        val hy = 44 + bob + breath
        PixelForm.dome(img, 24f, hy.toFloat(), 3.7f, 5.2f, c)
        PixelForm.dome(img, 24f, hy + 4.2f, 2.7f, 2.5f, c, bias = 0.1f)
        img.setPixel(23, hy + 6, m.sample(0.1f)); img.setPixel(25, hy + 6, m.sample(0.1f))
        img.setPixel(21, hy - 1, m.sample(0.05f)); img.setPixel(27, hy - 1, m.sample(0.05f))
        img.setPixel(21, hy - 2, PaletteRamp.rgba(232, 228, 220)); img.setPixel(27, hy - 2, PaletteRamp.rgba(232, 228, 220))
        // Ears and forelock.
        for (ex in listOf(21, 27)) { img.setPixel(ex, hy - 6, c.sample(0.6f)); img.setPixel(ex, hy - 7, c.sample(0.78f)); img.setPixel(ex + (if (ex < 24) 1 else -1), hy - 6, c.sample(0.3f)) }
        PixelForm.column(img, 23, hy - 6, 3, 3, m, 0.55f, round = false)
    }

    private fun drawBack(img: TileImage, action: PaperDollAction, frame: Int, coat: HorseCoat, saddle: Boolean, bob: Int) {
        val c = coat.coat
        val m = coat.mane
        val hoof = hooves(coat)
        leg(img, 28, 46 + bob, step(action, frame, 0), c, 0.5f, hoof)
        leg(img, 18, 46 + bob, step(action, frame, 2), c, 0.5f, hoof)
        PixelForm.dome(img, 24f, 42f + bob, 9f, 7.8f, c)
        // Ears peeking over the neck, and the tail hanging from the croup.
        for (ex in listOf(20, 28)) { img.setPixel(ex, 33 + bob, c.sample(0.6f)); img.setPixel(ex, 32 + bob, c.sample(0.78f)) }
        val sway = when (frame % 4) { 1 -> 1; 3 -> -1; else -> 0 }
        for (i in 0 until 3) PixelForm.line(img, 23 + i, 38 + bob, 23 + sway + i, 54 + bob) { t -> m.sample(0.8f - 0.4f * t - (if (i == 2) 0.15f else 0f)) }
        PixelForm.line(img, 24, 54 + bob, 24 + sway, 57 + bob) { m.sample(0.3f) }
        if (saddle) {
            val blanket = PaletteRamp(intArrayOf(PaletteRamp.rgba(78, 28, 34), PaletteRamp.rgba(120, 40, 46), PaletteRamp.rgba(168, 62, 62), PaletteRamp.rgba(208, 96, 84)))
            PixelForm.column(img, 17, 38 + bob, 14, 2, blanket, 0.6f)
        }
    }

    // ---------------------------------------------------------------- rider legs

    /** Thighs astride the barrel (front and back), or the near leg forward and bent (profile), ending in boots. */
    private fun drawRiderLegs(out: TileImage, dir: Direction8, theme: PaperDollTheme, loadout: PaperDollLoadout, bob: Int) {
        val legPal = when (loadout.legsStyle) {
            LegsStyle.GREAVES -> theme.accentPalette
            LegsStyle.ROBE_SKIRT -> theme.outfitPalette
            else -> theme.secondaryPalette
        }
        val footPal = when (loadout.feetStyle) {
            FeetStyle.SABATONS -> theme.accentPalette
            FeetStyle.BAREFOOT -> theme.skinPalette
            else -> theme.secondaryPalette
        }
        val hipY = SADDLE_Y - 1 + bob
        fun limb(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, tone: Float, toeDir: Int) {
            for (i in 0..2) PixelForm.line(out, x0 + i, y0, x1 + i, y1) { legPal.sample(tone + (if (i == 0) 0.18f else if (i == 2) -0.2f else 0f)) }
            for (i in 0..1) PixelForm.line(out, x1 + i, y1, x2 + i, y2) { legPal.sample(tone + (if (i == 0) 0.12f else -0.12f)) }
            // Boot: shaft in the foot colour, a lit cuff, and a toe pointing outward or forward.
            PixelForm.column(out, x2 - 1, y2 - 1, 4, 5, footPal, tone, round = false)
            for (i in 0..3) out.setPixel(x2 - 1 + i, y2 - 1, footPal.sample((tone + 0.3f).coerceAtMost(0.95f)))
            for (i in 0..3) out.setPixel(x2 - 1 + i, y2 + 3, footPal.sample(0.14f))
            if (toeDir != 0) out.setPixel(if (toeDir < 0) x2 - 2 else x2 + 3, y2 + 2, footPal.sample(tone))
        }
        if (dir == Direction8.W) {
            // Far leg (behind the horse's flank) peeks out below the belly line, near leg in front of it.
            limb(26, hipY, 22, hipY + 5, 23, hipY + 11, 0.4f, -1)
            limb(26, hipY, 21, hipY + 5, 22, hipY + 11, 0.66f, -1)
        } else {
            val flare = if (dir == Direction8.N) 1 else 0
            limb(19, hipY, 15 - flare, hipY + 5, 15 - flare, hipY + 11, 0.68f, -1)
            limb(27, hipY, 31 + flare, hipY + 5, 31 + flare, hipY + 11, 0.5f, 1)
        }
    }
}
