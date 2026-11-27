package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native 64x64 Liberated Pixel Cup (LPC) style paper-doll renderer.
 *
 * Produces 1:1 pixel-art sprites and compositable equipment layers in a 64x64 cell
 * (feet grounded around y = 60, ~50 px tall character, NW top-left lighting, soft dark outlines).
 */
internal object LpcPaperDollRenderer {

    private const val W = 64
    private const val H = 64
    private const val CX = 32
    private const val FOOT_Y = 60

    fun renderLayers(
        loadout: PaperDollLoadout,
        dir: Direction8,
        frameIndex: Int,
        action: PaperDollAction,
        @Suppress("UNUSED_PARAMETER") style: PixelStyle
    ): Pair<Map<PaperDollSlot, TileImage>, PaperDollSockets> {
        val theme = loadout.effectiveTheme()
        val pose = computePose(action, frameIndex, dir)

        val layers = linkedMapOf<PaperDollSlot, TileImage>()
        val shadow = TileImage(W, H)
        val body = TileImage(W, H)
        val eyes = TileImage(W, H)
        val hairBack = TileImage(W, H)
        val hairFront = TileImage(W, H)
        val legs = TileImage(W, H)
        val feet = TileImage(W, H)
        val torso = TileImage(W, H)
        val hands = TileImage(W, H)
        val cape = TileImage(W, H)
        val backAttachment = TileImage(W, H)
        val headgear = TileImage(W, H)
        val mainHand = TileImage(W, H)
        val offHand = TileImage(W, H)

        drawShadow(shadow, pose)
        drawBody(body, theme, dir, pose)
        drawEyes(eyes, theme, dir, pose)
        drawHair(hairBack, hairFront, loadout.hairStyle, theme.hairPalette, dir, pose)
        drawLegs(legs, loadout.legsStyle, theme.secondaryPalette, dir, pose)
        drawFeet(feet, loadout.feetStyle, theme.secondaryPalette, theme.accentPalette, dir, pose)
        drawTorso(torso, loadout.torsoStyle, theme.outfitPalette, theme.accentPalette, dir, pose)
        if (loadout.gloves != GloveStyle.NONE) {
            drawGloves(hands, loadout.gloves, theme.accentPalette, theme.secondaryPalette, dir, pose)
        }
        if (loadout.includeCape) {
            drawCape(cape, theme.accentPalette, dir, pose)
        }
        if (AttachmentKind.QUIVER in loadout.attachments) {
            drawQuiver(backAttachment, theme.secondaryPalette, theme.accentPalette, dir, pose)
        }
        if (loadout.headgear != HeadgearKind.NONE) {
            drawHeadgear(headgear, loadout.headgear, theme.accentPalette, theme.outfitPalette, dir, pose)
        }
        if (loadout.weapon != WeaponKind.NONE) {
            drawWeapon(mainHand, loadout.weapon, theme.accentPalette, theme.secondaryPalette, dir, pose)
        }
        if (loadout.shield != ShieldKind.NONE) {
            drawShield(offHand, loadout.shield, theme.accentPalette, theme.outfitPalette, dir, pose)
        }

        layers[PaperDollSlot.SHADOW] = shadow
        layers[PaperDollSlot.BODY] = body
        layers[PaperDollSlot.EYES] = eyes
        layers[PaperDollSlot.HAIR_BACK] = hairBack
        layers[PaperDollSlot.HAIR_FRONT] = hairFront
        layers[PaperDollSlot.LEGS] = legs
        layers[PaperDollSlot.FEET] = feet
        layers[PaperDollSlot.TORSO] = torso
        layers[PaperDollSlot.HANDS] = hands
        layers[PaperDollSlot.CAPE] = cape
        layers[PaperDollSlot.BACK_ATTACHMENT] = backAttachment
        layers[PaperDollSlot.HEADGEAR] = headgear
        layers[PaperDollSlot.MAIN_HAND] = mainHand
        layers[PaperDollSlot.OFF_HAND] = offHand

        val headY = 18 + pose.bodyDy
        val sockets = PaperDollSockets(
            pivotX = CX,
            pivotY = FOOT_Y,
            headX = CX,
            headY = headY - 7,
            faceX = if (dir == Direction8.W) CX - 4 else CX,
            faceY = headY + 2,
            backX = if (dir == Direction8.W) CX + 4 else CX,
            backY = 33 + pose.bodyDy,
            shoulderX = CX,
            shoulderY = 29 + pose.bodyDy,
            waistX = CX,
            waistY = 40 + pose.bodyDy,
            mainHandX = pose.mainHandX,
            mainHandY = pose.mainHandY,
            offHandX = pose.offHandX,
            offHandY = pose.offHandY
        )

        return layers to sockets
    }

    // -------------------------------------------------------------------------
    // Pose & Articulation
    // -------------------------------------------------------------------------

    private data class Pose(
        val bodyDy: Int = 0,
        val bodyDx: Int = 0,
        // Stride in [-1f..1f]: negative = back/planted, positive = forward/lifted
        val leftLegStride: Float = 0f,
        val rightLegStride: Float = 0f,
        val leftArmSwing: Float = 0f,
        val rightArmSwing: Float = 0f,
        val mainHandX: Int = 22,
        val mainHandY: Int = 40,
        val offHandX: Int = 41,
        val offHandY: Int = 40,
        val swingPhase: Int = -1, // -1=none, 0=windup, 1=upper-sweep, 2=strike, 3=low-sweep, 4=follow, 5=recover
        val castRaised: Boolean = false,
        val bowDrawn: Boolean = false,
        val pickupDepth: Int = 0, // 0=none, 1..7 crouch pixels (feet stay grounded at FOOT_Y)
        val isRiding: Boolean = false
    )

    private fun computePose(action: PaperDollAction, frameIndex: Int, dir: Direction8): Pose {
        val f = frameIndex.mod(action.defaultFrames.coerceAtLeast(1))
        val isSide = dir == Direction8.W || dir == Direction8.E || dir.isDiagonal
        return when (action) {
            PaperDollAction.IDLE -> {
                val bob = if (f % 2 == 1) 1 else 0
                Pose(
                    bodyDy = bob,
                    mainHandX = if (isSide) CX - 2 else CX - 10,
                    mainHandY = 41 + bob,
                    offHandX = if (isSide) CX + 2 else CX + 9,
                    offHandY = 41 + bob
                )
            }
            PaperDollAction.WALK, PaperDollAction.RUN -> {
                // Classic 4-beat LPC walk cycle: right forward, passing, left forward, passing
                val beat = f % 4
                val bob = if (beat == 1 || beat == 3) -1 else 0
                val lStride = when (beat) {
                    0 -> -0.8f
                    1 -> 0f
                    2 -> 0.8f
                    else -> 0f
                }
                val rStride = -lStride
                val handDxL = if (isSide) (-lStride * 4f).roundToInt() else 0
                val handDxR = if (isSide) (-rStride * 4f).roundToInt() else 0
                val handDyL = if (!isSide) (lStride * 1.5f).roundToInt() else 0
                val handDyR = if (!isSide) (rStride * 1.5f).roundToInt() else 0
                Pose(
                    bodyDy = bob,
                    leftLegStride = lStride,
                    rightLegStride = rStride,
                    leftArmSwing = -lStride,
                    rightArmSwing = -rStride,
                    mainHandX = (if (isSide) CX - 2 + handDxL else CX - 10),
                    mainHandY = 41 + bob + handDyL,
                    offHandX = (if (isSide) CX + 2 + handDxR else CX + 9),
                    offHandY = 41 + bob + handDyR
                )
            }
            PaperDollAction.SLASH, PaperDollAction.THRUST, PaperDollAction.HARVEST -> {
                val phase = f % 6
                val dx = when {
                    !isSide -> 0
                    phase == 0 -> 2
                    phase == 1 -> -1
                    phase == 2 -> -4
                    phase == 3 -> -3
                    phase == 4 -> -2
                    else -> 0
                }
                val bob = if (phase in 1..3) 1 else 0
                val (mhx, mhy) = when (dir) {
                    Direction8.S -> when (phase) {
                        0 -> (CX - 11) to (28 + bob)
                        1 -> (CX - 12) to (34 + bob)
                        2 -> (CX - 5) to (42 + bob)
                        3 -> (CX - 2) to (43 + bob)
                        4 -> (CX - 7) to (42 + bob)
                        else -> (CX - 10) to (40 + bob)
                    }
                    Direction8.N -> when (phase) {
                        0 -> (CX - 11) to (28 + bob)
                        1 -> (CX - 12) to (31 + bob)
                        2 -> (CX - 8) to (29 + bob)
                        3 -> (CX - 6) to (32 + bob)
                        4 -> (CX - 9) to (36 + bob)
                        else -> (CX - 10) to (39 + bob)
                    }
                    else -> when (phase) {
                        0 -> (CX + 4 + dx) to (22 + bob)
                        1 -> (CX - 6 + dx) to (30 + bob)
                        2 -> (CX - 11 + dx) to (35 + bob)
                        3 -> (CX - 10 + dx) to (38 + bob)
                        4 -> (CX - 7 + dx) to (41 + bob)
                        else -> (CX - 4 + dx) to (40 + bob)
                    }
                }
                Pose(
                    bodyDy = bob,
                    bodyDx = dx,
                    leftLegStride = if (phase in 1..3) 0.65f else if (phase == 0) -0.3f else 0.1f,
                    rightLegStride = if (phase in 1..3) -0.55f else if (phase == 0) 0.25f else -0.1f,
                    swingPhase = phase,
                    mainHandX = mhx,
                    mainHandY = mhy,
                    offHandX = if (isSide) CX + 3 + dx else CX + 9,
                    offHandY = 40 + bob
                )
            }
            PaperDollAction.SHOOT -> {
                // 6-frame bow draw & release: 0=raise, 1=half-draw, 2=full-draw, 3=hold, 4=release, 5=recover
                val phase = f % 6
                val drawn = phase in 1..3
                Pose(
                    bodyDy = if (phase in 2..3) 1 else 0,
                    leftLegStride = if (phase in 1..4) 0.45f else 0f,
                    rightLegStride = if (phase in 1..4) -0.45f else 0f,
                    bowDrawn = drawn,
                    swingPhase = phase,
                    mainHandX = if (isSide) CX - 9 else CX - 10,
                    mainHandY = 34,
                    offHandX = if (isSide) (if (drawn) CX + 2 else CX - 2) else CX + 8,
                    offHandY = 34
                )
            }
            PaperDollAction.CAST -> {
                val phase = f % 4
                val raise = if (phase in 1..2) -6 else -3
                Pose(
                    bodyDy = if (phase == 1) -1 else 0,
                    castRaised = true,
                    swingPhase = phase,
                    mainHandX = CX - 11,
                    mainHandY = 33 + raise,
                    offHandX = CX + 10,
                    offHandY = 33 + raise
                )
            }
            PaperDollAction.PICKUP -> {
                // 4-frame kneel/crouch & reach-down to pick up ground loot (feet stay grounded at FOOT_Y)
                val phase = f % 4
                val crouch = when (phase) {
                    0 -> 3
                    1 -> 7
                    2 -> 6
                    else -> 2
                }
                val reachY = when (phase) {
                    0 -> 49
                    1 -> 57
                    2 -> 54
                    else -> 46
                }
                val dx = if (isSide && phase in 1..2) -2 else 0
                Pose(
                    bodyDy = crouch,
                    bodyDx = dx,
                    leftLegStride = if (phase in 1..2) 0.55f else 0.15f,
                    rightLegStride = if (phase in 1..2) -0.55f else -0.15f,
                    mainHandX = if (isSide) CX - 7 + dx else CX - 6,
                    mainHandY = reachY,
                    offHandX = if (isSide) CX + 2 + dx else CX + 8,
                    offHandY = 40 + crouch,
                    pickupDepth = crouch
                )
            }
            PaperDollAction.RIDE -> {
                // 4-beat seated riding pose synced with LpcMountRenderer (frame 0 = seated idle/neutral, 1..3 = gallop bob)
                val beat = f % 4
                val rideBob = if (beat == 1 || beat == 3) -1 else 0
                val dy = -7 + rideBob
                Pose(
                    bodyDy = dy,
                    mainHandX = if (isSide) CX - 7 else CX - 9,
                    mainHandY = 33 + rideBob,
                    offHandX = if (isSide) CX - 2 else CX + 8,
                    offHandY = 33 + rideBob,
                    isRiding = true
                )
            }
            PaperDollAction.JUMP -> {
                val lift = if (f == 1) -4 else if (f == 2) -2 else 0
                Pose(
                    bodyDy = lift,
                    mainHandX = if (isSide) CX - 2 else CX - 10,
                    mainHandY = 39 + lift,
                    offHandX = if (isSide) CX + 2 else CX + 9,
                    offHandY = 39 + lift
                )
            }
            PaperDollAction.HURT, PaperDollAction.KO -> {
                Pose(
                    bodyDy = 2,
                    bodyDx = if (isSide) 2 else 0,
                    mainHandX = if (isSide) CX else CX - 11,
                    mainHandY = 42,
                    offHandX = if (isSide) CX + 4 else CX + 10,
                    offHandY = 42
                )
            }
            else -> Pose()
        }
    }

    // -------------------------------------------------------------------------
    // Shadow & Body (LPC Contoured Anatomy)
    // -------------------------------------------------------------------------

    private fun drawShadow(img: TileImage, pose: Pose) {
        if (pose.isRiding) return // The mount layer provides the full ground shadow
        val shadowColor = rgba(14, 12, 20, 90)
        val cy = FOOT_Y + 1
        val rx = 10
        val ry = 3
        for (y in (cy - ry)..(cy + ry)) {
            for (x in (CX + pose.bodyDx - rx)..(CX + pose.bodyDx + rx)) {
                val dx = (x - (CX + pose.bodyDx)) / rx.toFloat()
                val dy = (y - cy) / ry.toFloat()
                if (dx * dx + dy * dy <= 1f) {
                    img.setPixel(x, y, shadowColor)
                }
            }
        }
    }

    private fun drawBody(img: TileImage, theme: PaperDollTheme, dir: Direction8, pose: Pose) {
        val skin = theme.skinPalette
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy

        when (dir) {
            Direction8.S -> {
                if (pose.isRiding) {
                    drawFrontBackSeatedLeg(raw, cx - 7, 42 + dy, skin, isBack = false)
                    drawFrontBackSeatedLeg(raw, cx + 3, 42 + dy, skin, isBack = false)
                } else {
                    drawFrontBackLeg(raw, cx - 4, 43 + dy, pose.leftLegStride, skin, isBack = false)
                    drawFrontBackLeg(raw, cx + 1, 43 + dy, pose.rightLegStride, skin, isBack = false)
                }
                drawContouredTorsoFront(raw, cx, 28 + dy, skin, darkBias = 0f)
                fillShadedEllipse(raw, cx, 26 + dy, 2, 2, skin, darkBias = 0.2f) // neck
                drawFrontArm(raw, cx - 10, 29 + dy, pose.leftArmSwing, pose.swingPhase, isLeft = true, skin = skin, castRaised = pose.castRaised, pickupDepth = pose.pickupDepth, bowDrawn = pose.bowDrawn)
                drawFrontArm(raw, cx + 7, 29 + dy, pose.rightArmSwing, -1, isLeft = false, skin = skin, castRaised = pose.castRaised, pickupDepth = 0, bowDrawn = pose.bowDrawn)
                drawHeadFront(raw, cx, 11 + dy, skin, theme.ancestry)
            }
            Direction8.N -> {
                if (pose.isRiding) {
                    drawFrontBackSeatedLeg(raw, cx - 7, 42 + dy, skin, isBack = true)
                    drawFrontBackSeatedLeg(raw, cx + 3, 42 + dy, skin, isBack = true)
                } else {
                    drawFrontBackLeg(raw, cx - 4, 43 + dy, pose.leftLegStride, skin, isBack = true)
                    drawFrontBackLeg(raw, cx + 1, 43 + dy, pose.rightLegStride, skin, isBack = true)
                }
                drawContouredTorsoFront(raw, cx, 28 + dy, skin, darkBias = 0.14f)
                fillShadedEllipse(raw, cx, 26 + dy, 2, 2, skin, darkBias = 0.25f)
                drawFrontArm(raw, cx - 10, 29 + dy, pose.leftArmSwing, pose.swingPhase, isLeft = true, skin = skin, castRaised = pose.castRaised, pickupDepth = pose.pickupDepth, bowDrawn = pose.bowDrawn)
                drawFrontArm(raw, cx + 7, 29 + dy, pose.rightArmSwing, -1, isLeft = false, skin = skin, castRaised = pose.castRaised, pickupDepth = 0, bowDrawn = pose.bowDrawn)
                drawHeadBack(raw, cx, 11 + dy, skin, theme.ancestry)
            }
            else -> {
                // Side view (W; E is mirrored)
                if (!pose.isRiding) {
                    drawSideLeg(raw, cx, 43 + dy, pose.rightLegStride, skin, darkBias = 0.24f)
                }
                drawSideArm(raw, cx, 29 + dy, pose.rightArmSwing, -1, skin, darkBias = 0.24f, castRaised = pose.castRaised, pickupDepth = 0, bowDrawn = false, isRiding = pose.isRiding)
                drawContouredTorsoSide(raw, cx, 28 + dy, skin)
                fillShadedBox(raw, cx - 2, 25 + dy, 4, 4, skin, darkBias = 0.2f)
                if (pose.isRiding) {
                    drawSideSeatedLeg(raw, cx, 42 + dy, skin, darkBias = 0f)
                } else {
                    drawSideLeg(raw, cx, 43 + dy, pose.leftLegStride, skin, darkBias = 0f)
                }
                drawHeadSide(raw, cx, 11 + dy, skin, theme.ancestry)
                drawSideArm(raw, cx, 29 + dy, pose.leftArmSwing, pose.swingPhase, skin, darkBias = 0f, castRaised = pose.castRaised, pickupDepth = pose.pickupDepth, bowDrawn = pose.bowDrawn, isRiding = pose.isRiding)
            }
        }

        applyOutline(raw, img, outlineColorFor(skin))
    }

    private fun drawContouredTorsoFront(img: TileImage, cx: Int, topY: Int, ramp: PaletteRamp, darkBias: Float) {
        // Tapered LPC torso: shoulders 14px -> chest 13px -> waist 11px -> hips 13px
        for (ry in 0 until 16) {
            val halfW = when (ry) {
                0 -> 5
                1, 2 -> 7
                3, 4 -> 7
                5, 6, 7 -> 6
                8, 9, 10 -> 5
                11, 12 -> 6
                else -> 6
            }
            for (dx in -halfW until halfW) {
                val nx = (dx + halfW) / (2f * halfW).coerceAtLeast(1f)
                val ny = ry / 15f
                // Slight pectoral/rib shading
                val centerHighlight = if (ry in 3..7 && abs(dx) in 1..4) 0.08f else 0f
                val t = (0.9f - (nx * 0.4f + ny * 0.42f) + centerHighlight - darkBias).coerceIn(0.1f, 0.96f)
                img.setPixel(cx + dx, topY + ry, ramp.sample(t))
            }
        }
    }

    private fun drawContouredTorsoSide(img: TileImage, cx: Int, topY: Int, ramp: PaletteRamp) {
        // Side torso facing left: chest curves forward (-5), back curves slightly (+4)
        for (ry in 0 until 16) {
            val (minX, maxX) = when (ry) {
                0 -> -3 to 3
                1, 2 -> -4 to 4
                3, 4, 5 -> -5 to 4
                6, 7, 8 -> -4 to 4
                9, 10, 11 -> -3 to 4
                else -> -4 to 4
            }
            for (dx in minX..maxX) {
                val nx = (dx - minX) / (maxX - minX).toFloat().coerceAtLeast(1f)
                val ny = ry / 15f
                val t = (0.92f - (nx * 0.45f + ny * 0.42f)).coerceIn(0.12f, 0.96f)
                img.setPixel(cx + dx, topY + ry, ramp.sample(t))
            }
        }
    }

    private fun drawFrontBackLeg(
        img: TileImage,
        x0: Int,
        hipY: Int,
        stride: Float,
        ramp: PaletteRamp,
        isBack: Boolean
    ) {
        // In front/back walk, positive stride = leg lifted/stepping forward (shorter by 3px, brighter knee); planted & neutral stay grounded at FOOT_Y
        val lift = if (stride > 0.35f) 3 else 0
        val dark = when {
            isBack && stride > 0.35f -> 0.04f
            isBack -> 0.16f
            stride < -0.35f -> 0.18f
            stride > 0.35f -> -0.05f
            else -> 0f
        }
        val maxH = (FOOT_Y - hipY).coerceIn(8, 18)
        val totalH = (maxH - lift).coerceAtLeast(6)
        for (ry in 0 until totalH) {
            val w = if (ry < 7) 4 else 3 // thigh 4px, shin 3px
            val xOff = if (ry < 7) 0 else 0
            for (rx in 0 until w) {
                val nx = rx / (w - 1).coerceAtLeast(1).toFloat()
                val ny = ry / totalH.toFloat()
                val t = (0.88f - (nx * 0.42f + ny * 0.38f) - dark).coerceIn(0.1f, 0.95f)
                img.setPixel(x0 + xOff + rx, hipY + ry, ramp.sample(t))
            }
        }
    }

    private fun drawFrontBackSeatedLeg(img: TileImage, x0: Int, hipY: Int, ramp: PaletteRamp, isBack: Boolean) {
        val dark = if (isBack) 0.16f else 0f
        // Foreshortened straddling thigh + knee + shin resting toward stirrups
        fillShadedBox(img, x0, hipY, 4, 5, ramp, darkBias = dark)
        fillShadedBox(img, x0 + (if (x0 < CX) 0 else 1), hipY + 5, 3, 4, ramp, darkBias = dark + 0.08f)
    }

    private fun drawSideSeatedLeg(img: TileImage, cx: Int, hipY: Int, ramp: PaletteRamp, darkBias: Float) {
        // Seated in saddle facing LEFT: thigh angles forward-down, shin drops vertically into stirrup
        for (ry in 0 until 5) {
            val xOff = -(ry * 3) / 4
            fillShadedBox(img, cx + xOff - 2, hipY + ry, 4, 1, ramp, darkBias)
        }
        fillShadedBox(img, cx - 5, hipY + 5, 3, 6, ramp, darkBias + 0.06f)
    }

    private fun drawSideLeg(
        img: TileImage,
        cx: Int,
        hipY: Int,
        stride: Float,
        ramp: PaletteRamp,
        darkBias: Float
    ) {
        // Articulated thigh (hip -> knee) + shin (knee -> ankle), clamped to FOOT_Y so crouch/pickup never clips below boots
        val maxTotalH = (FOOT_Y - hipY).coerceIn(8, 17)
        val thighH = (maxTotalH * 0.47f).roundToInt().coerceAtLeast(4)
        val shinBaseH = (maxTotalH - thighH).coerceAtLeast(4)
        val kneeDx = (stride * 3.5f).roundToInt()
        val footDx = (stride * 5.0f).roundToInt()
        val footLift = if (maxTotalH < 15) 0 else if (stride > 0.3f) -2 else 0

        // Thigh
        for (ry in 0 until thighH) {
            val frac = ry / (thighH - 1).coerceAtLeast(1).toFloat()
            val xCenter = cx + (kneeDx * frac).roundToInt()
            for (dx in -2..1) {
                val t = (0.88f - ((dx + 2) / 3f) * 0.4f - frac * 0.25f - darkBias).coerceIn(0.1f, 0.94f)
                img.setPixel(xCenter + dx, hipY + ry, ramp.sample(t))
            }
        }
        // Shin
        val shinH = (shinBaseH + footLift).coerceAtLeast(4)
        for (ry in 0 until shinH) {
            val frac = ry / shinH.coerceAtLeast(1).toFloat()
            val xCenter = cx + kneeDx + ((footDx - kneeDx) * frac).roundToInt()
            for (dx in -1..1) {
                val t = (0.85f - ((dx + 1) / 2f) * 0.4f - frac * 0.3f - darkBias).coerceIn(0.1f, 0.92f)
                img.setPixel(xCenter + dx, hipY + thighH + ry, ramp.sample(t))
            }
        }
    }

    private fun drawFrontArm(
        img: TileImage,
        x0: Int,
        shoulderY: Int,
        swing: Float,
        swingPhase: Int,
        isLeft: Boolean,
        skin: PaletteRamp,
        castRaised: Boolean,
        pickupDepth: Int = 0,
        bowDrawn: Boolean = false
    ) {
        if (pickupDepth > 0 && isLeft) {
            // Reach forward and down toward the ground
            fillShadedBox(img, x0 + 1, shoulderY + 1, 3, 7, skin)
            fillShadedBox(img, x0 + 2, shoulderY + 8, 3, 9, skin, darkBias = 0.08f)
            return
        }
        if (bowDrawn) {
            // Hold bow / draw string
            fillShadedBox(img, x0, shoulderY, 3, 7, skin)
            return
        }
        if (castRaised || (isLeft && swingPhase == 0)) {
            fillShadedBox(img, x0, shoulderY - 6, 3, 9, skin)
            return
        }
        if (isLeft && swingPhase == 1) {
            fillShadedBox(img, x0 - 2, shoulderY, 4, 8, skin)
            return
        }
        if (isLeft && (swingPhase == 2 || swingPhase == 3)) {
            // Extended across front for strike & low sweep
            val xShift = if (swingPhase == 3) 3 else 1
            fillShadedBox(img, x0 + xShift, shoulderY + 3, 5, 8, skin)
            return
        }
        if (isLeft && swingPhase == 4) {
            // Follow-through arm angled down-out
            fillShadedBox(img, x0 - 1, shoulderY + 2, 4, 9, skin)
            return
        }
        val dy = (swing * 3.0f).roundToInt()
        val flare = if (isLeft) -1 else 1
        val armDark = if (swing < -0.35f) 0.18f else 0f
        val forearmH = if (swing < -0.35f) 5 else if (swing > 0.35f) 8 else 7
        val forearmFlare = if (swing > 0.35f) flare * 2 else flare
        fillShadedBox(img, x0, shoulderY + dy, 3, 5, skin, darkBias = armDark)
        fillShadedBox(img, x0 + forearmFlare, shoulderY + 5 + dy, 3, forearmH, skin, darkBias = armDark + 0.08f)
    }

    private fun drawSideArm(
        img: TileImage,
        cx: Int,
        shoulderY: Int,
        swing: Float,
        swingPhase: Int,
        ramp: PaletteRamp,
        darkBias: Float,
        castRaised: Boolean,
        pickupDepth: Int = 0,
        bowDrawn: Boolean = false,
        isRiding: Boolean = false
    ) {
        if (isRiding && swingPhase < 0 && !castRaised && !bowDrawn) {
            // Reins grip: upper arm down, forearm angled forward toward horse's neck/reins
            fillShadedBox(img, cx - 1, shoulderY, 3, 6, ramp, darkBias)
            fillShadedBox(img, cx - 6, shoulderY + 5, 7, 3, ramp, darkBias + 0.05f)
            return
        }
        if (pickupDepth > 0) {
            // Reach forward & down toward the ground (meets hand at cx - 7, reachY)
            for (ry in 0 until 15) {
                val xOff = -(ry * 7) / 15
                fillShadedBox(img, cx + xOff - 1, shoulderY + 2 + ry, 3, 2, ramp, darkBias)
            }
            return
        }
        if (bowDrawn) {
            // Extended forward holding bow horizontally
            fillShadedBox(img, cx - 9, shoulderY + 3, 10, 3, ramp, darkBias)
            return
        }
        if (castRaised || swingPhase == 0) {
            fillShadedBox(img, cx, shoulderY - 6, 3, 8, ramp, darkBias)
            return
        }
        if (swingPhase == 1 || swingPhase == 2 || swingPhase == 3) {
            val reach = if (swingPhase == 2) 11 else 9
            val yOff = when (swingPhase) { 1 -> 1; 2 -> 4; else -> 6 }
            fillShadedBox(img, cx - reach, shoulderY + yOff, reach + 2, 3, ramp, darkBias)
            return
        }
        if (swingPhase == 4) {
            for (ry in 0 until 10) {
                val xOff = -(ry * 7) / 10
                fillShadedBox(img, cx + xOff - 1, shoulderY + ry, 3, 2, ramp, darkBias)
            }
            return
        }
        val handDx = (swing * 4f).roundToInt()
        for (ry in 0 until 12) {
            val frac = ry / 11f
            val xOff = (handDx * frac).roundToInt()
            for (dx in -1..1) {
                val t = (0.88f - ((dx + 1) / 2f) * 0.4f - frac * 0.3f - darkBias).coerceIn(0.1f, 0.94f)
                img.setPixel(cx + xOff + dx, shoulderY + ry, ramp.sample(t))
            }
        }
    }

    private fun drawHeadFront(img: TileImage, cx: Int, topY: Int, skin: PaletteRamp, ancestry: BodyAncestry) {
        // 16x16 rounded LPC head
        for (ry in 0 until 16) {
            val y = topY + ry
            val halfW = when (ry) {
                0 -> 4
                1 -> 6
                2, 3 -> 7
                in 4..10 -> 8
                11, 12 -> 7
                13 -> 6
                14 -> 4
                else -> 2
            }
            for (dx in -halfW until halfW) {
                val nx = (dx + halfW) / (2f * halfW).coerceAtLeast(1f)
                val ny = ry / 15f
                val light = (0.96f - (nx * 0.42f + ny * 0.48f)).coerceIn(0.16f, 0.96f)
                img.setPixel(cx + dx, y, skin.sample(light))
            }
        }
        // Subtle nose & mouth shadow
        img.setPixel(cx, topY + 11, skin.sample(0.45f))
        img.setPixel(cx - 1, topY + 13, skin.sample(0.36f))
        img.setPixel(cx, topY + 13, skin.sample(0.36f))

        // Ears
        val earY = topY + 8
        img.setPixel(cx - 9, earY, skin.sample(0.45f))
        img.setPixel(cx - 9, earY + 1, skin.shadow)
        img.setPixel(cx + 8, earY, skin.sample(0.45f))
        img.setPixel(cx + 8, earY + 1, skin.shadow)
        if (ancestry == BodyAncestry.ELF) {
            img.setPixel(cx - 10, earY - 1, skin.mid)
            img.setPixel(cx + 9, earY - 1, skin.mid)
        }
    }

    private fun drawHeadBack(img: TileImage, cx: Int, topY: Int, skin: PaletteRamp, ancestry: BodyAncestry) {
        for (ry in 0 until 16) {
            val y = topY + ry
            val halfW = when (ry) {
                0 -> 4
                1 -> 6
                2, 3 -> 7
                in 4..10 -> 8
                11, 12 -> 7
                13 -> 6
                14 -> 4
                else -> 2
            }
            for (dx in -halfW until halfW) {
                val nx = (dx + halfW) / (2f * halfW).coerceAtLeast(1f)
                val ny = ry / 15f
                val light = (0.82f - (nx * 0.38f + ny * 0.48f)).coerceIn(0.14f, 0.85f)
                img.setPixel(cx + dx, y, skin.sample(light))
            }
        }
        val earY = topY + 8
        img.setPixel(cx - 9, earY, skin.sample(0.35f))
        img.setPixel(cx + 8, earY, skin.sample(0.35f))
        if (ancestry == BodyAncestry.ELF) {
            img.setPixel(cx - 10, earY - 1, skin.sample(0.45f))
            img.setPixel(cx + 9, earY - 1, skin.sample(0.45f))
        }
    }

    private fun drawHeadSide(img: TileImage, cx: Int, topY: Int, skin: PaletteRamp, ancestry: BodyAncestry) {
        for (ry in 0 until 16) {
            val y = topY + ry
            val (minX, maxX) = when (ry) {
                0 -> -3 to 3
                1 -> -5 to 5
                2, 3 -> -6 to 6
                in 4..9 -> -6 to 7
                10, 11 -> -7 to 6 // subtle nose/cheek
                12 -> -6 to 5
                13 -> -5 to 4
                14 -> -4 to 3
                else -> -2 to 2
            }
            for (dx in minX..maxX) {
                val nx = (dx - minX) / (maxX - minX).toFloat().coerceAtLeast(1f)
                val ny = ry / 15f
                val light = (0.96f - (nx * 0.42f + ny * 0.48f)).coerceIn(0.16f, 0.96f)
                img.setPixel(cx + dx, y, skin.sample(light))
            }
        }
        img.setPixel(cx + 2, topY + 8, skin.sample(0.45f))
        img.setPixel(cx + 2, topY + 9, skin.shadow)
        if (ancestry == BodyAncestry.ELF) {
            img.setPixel(cx + 3, topY + 7, skin.mid)
        }
    }

    private fun drawEyes(img: TileImage, theme: PaperDollTheme, dir: Direction8, pose: Pose) {
        if (dir == Direction8.N) return
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy
        val sclera = rgba(244, 246, 252, 255)
        val iris = theme.eyeColor
        val brow = outlineColorFor(theme.hairPalette)

        if (dir == Direction8.S) {
            // Left eye (x = cx - 5..-4), Right eye (x = cx + 3..+4)
            img.setPixel(cx - 5, 19 + dy, brow)
            img.setPixel(cx - 4, 19 + dy, brow)
            img.setPixel(cx - 5, 20 + dy, sclera)
            img.setPixel(cx - 4, 20 + dy, iris)
            img.setPixel(cx - 5, 21 + dy, sclera)
            img.setPixel(cx - 4, 21 + dy, iris)

            img.setPixel(cx + 3, 19 + dy, brow)
            img.setPixel(cx + 4, 19 + dy, brow)
            img.setPixel(cx + 3, 20 + dy, iris)
            img.setPixel(cx + 4, 20 + dy, sclera)
            img.setPixel(cx + 3, 21 + dy, iris)
            img.setPixel(cx + 4, 21 + dy, sclera)
        } else {
            // Facing W
            img.setPixel(cx - 5, 19 + dy, brow)
            img.setPixel(cx - 4, 19 + dy, brow)
            img.setPixel(cx - 5, 20 + dy, iris)
            img.setPixel(cx - 4, 20 + dy, sclera)
            img.setPixel(cx - 5, 21 + dy, iris)
            img.setPixel(cx - 4, 21 + dy, sclera)
        }
    }

    // -------------------------------------------------------------------------
    // Hair (Cohesive silhouette with internal strand shading)
    // -------------------------------------------------------------------------

    private fun drawHair(
        hairBack: TileImage,
        hairFront: TileImage,
        style: HairStyle,
        palette: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val rawFront = TileImage(W, H)
        val rawBack = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy
        val topY = 9 + dy

        when (dir) {
            Direction8.S -> {
                // Cohesive hair cap + bangs (no outline gaps between strands)
                fillShadedEllipse(rawFront, cx, topY + 5, 9, 5, palette)
                // Side temples
                fillShadedBox(rawFront, cx - 8, topY + 6, 2, 6, palette)
                fillShadedBox(rawFront, cx + 6, topY + 6, 2, 6, palette)
                // Cohesive brow bangs
                for (dx in -6..5) {
                    val bangH = if (dx in -1..0) 1 else if (abs(dx) % 3 == 0) 3 else 2
                    for (by in 0 until bangH) {
                        val shade = if (dx % 2 == 0) 0.55f else 0.78f
                        rawFront.setPixel(cx + dx, topY + 7 + by, palette.sample(shade))
                    }
                }
                if (style == HairStyle.LONG_FLOWING || style == HairStyle.TWIN_TAILS) {
                    fillShadedBox(rawBack, cx - 9, topY + 9, 3, 15, palette, darkBias = 0.25f)
                    fillShadedBox(rawBack, cx + 6, topY + 9, 3, 15, palette, darkBias = 0.25f)
                }
            }
            Direction8.N -> {
                fillShadedEllipse(rawBack, cx, topY + 6, 9, 6, palette)
                val backLen = if (style == HairStyle.LONG_FLOWING) 17 else 10
                fillShadedBox(rawBack, cx - 8, topY + 6, 16, backLen, palette)
                // Subtle hair sheen highlight band on back of head
                for (dx in -5..4) {
                    rawBack.setPixel(cx + dx, topY + 5, palette.highlight)
                }
            }
            else -> {
                fillShadedEllipse(rawFront, cx + 1, topY + 5, 7, 5, palette)
                fillShadedBox(rawFront, cx + 1, topY + 6, 6, 8, palette)
                fillShadedBox(rawFront, cx - 5, topY + 6, 4, 3, palette)
                if (style == HairStyle.LONG_FLOWING) {
                    fillShadedBox(rawBack, cx + 2, topY + 9, 5, 15, palette)
                }
            }
        }

        val outline = outlineColorFor(palette)
        applyOutline(rawBack, hairBack, outline)
        applyOutline(rawFront, hairFront, outline)
    }

    // -------------------------------------------------------------------------
    // Equipment Layers
    // -------------------------------------------------------------------------

    private fun drawLegs(
        img: TileImage,
        style: LegsStyle,
        palette: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy

        when (dir) {
            Direction8.S, Direction8.N -> {
                if (pose.isRiding) {
                    fillShadedBox(raw, cx - 6, 41 + dy, 12, 3, palette)
                    drawFrontBackSeatedLeg(raw, cx - 7, 42 + dy, palette, isBack = dir == Direction8.N)
                    drawFrontBackSeatedLeg(raw, cx + 3, 42 + dy, palette, isBack = dir == Direction8.N)
                } else if (style == LegsStyle.ROBE_SKIRT) {
                    for (ry in 0 until 16) {
                        val halfW = 6 + ry / 8
                        fillShadedBox(raw, cx - halfW, 41 + dy + ry, halfW * 2, 1, palette)
                    }
                } else {
                    fillShadedBox(raw, cx - 6, 41 + dy, 12, 3, palette)
                    drawFrontBackLeg(raw, cx - 4, 43 + dy, pose.leftLegStride, palette, isBack = dir == Direction8.N)
                    drawFrontBackLeg(raw, cx + 1, 43 + dy, pose.rightLegStride, palette, isBack = dir == Direction8.N)
                }
            }
            else -> {
                if (pose.isRiding) {
                    fillShadedBox(raw, cx - 4, 41 + dy, 8, 3, palette)
                    drawSideSeatedLeg(raw, cx, 42 + dy, palette, darkBias = 0f)
                } else if (style == LegsStyle.ROBE_SKIRT) {
                    for (ry in 0 until 16) {
                        val halfW = 4 + ry / 7
                        fillShadedBox(raw, cx - halfW, 41 + dy + ry, halfW * 2, 1, palette)
                    }
                } else {
                    drawSideLeg(raw, cx, 43 + dy, pose.rightLegStride, palette, darkBias = 0.24f)
                    fillShadedBox(raw, cx - 4, 41 + dy, 8, 3, palette)
                    drawSideLeg(raw, cx, 43 + dy, pose.leftLegStride, palette, darkBias = 0f)
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawFeet(
        img: TileImage,
        style: FeetStyle,
        palette: PaletteRamp,
        accent: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = if (pose.pickupDepth > 0) 0 else pose.bodyDy
        val bootH = if (style == FeetStyle.SABATONS) 6 else 5

        when (dir) {
            Direction8.S, Direction8.N -> {
                if (pose.isRiding) {
                    val stirrupY = 49 + dy
                    fillShadedBox(raw, cx - 7, stirrupY, 4, bootH, palette)
                    fillShadedBox(raw, cx + 3, stirrupY, 4, bootH, palette)
                    if (style == FeetStyle.SABATONS) {
                        raw.setPixel(cx - 6, stirrupY, accent.highlight)
                        raw.setPixel(cx + 4, stirrupY, accent.highlight)
                    }
                } else {
                    // Planted & neutral feet stay anchored at FOOT_Y during idle/walk bob (-1..1); only lift on JUMP (bodyDy < -1) or active stride (3px)
                    val jumpDy = if (pose.bodyDy < -1) pose.bodyDy else 0
                    val liftL = if (pose.leftLegStride > 0.35f) 3 else 0
                    val liftR = if (pose.rightLegStride > 0.35f) 3 else 0
                    val darkL = if (pose.leftLegStride < -0.35f) 0.18f else 0f
                    val darkR = if (pose.rightLegStride < -0.35f) 0.18f else 0f
                    fillShadedBox(raw, cx - 4, FOOT_Y - bootH - liftL + jumpDy, 4, bootH, palette, darkBias = darkL)
                    fillShadedBox(raw, cx + 1, FOOT_Y - bootH - liftR + jumpDy, 4, bootH, palette, darkBias = darkR)
                    // On back view (N), show a crisp dark boot-sole band on the lifted heel so the step reads clearly
                    if (dir == Direction8.N) {
                        if (liftL > 0) fillShadedBox(raw, cx - 4, FOOT_Y - liftL - 2 + jumpDy, 4, 2, palette, darkBias = 0.45f)
                        if (liftR > 0) fillShadedBox(raw, cx + 1, FOOT_Y - liftR - 2 + jumpDy, 4, 2, palette, darkBias = 0.45f)
                    }
                    if (style == FeetStyle.SABATONS) {
                        raw.setPixel(cx - 3, FOOT_Y - bootH - liftL + jumpDy, accent.highlight)
                        raw.setPixel(cx + 2, FOOT_Y - bootH - liftR + jumpDy, accent.highlight)
                    }
                }
            }
            else -> {
                if (pose.isRiding) {
                    fillShadedBox(raw, cx - 6, 51 + dy, 5, bootH, palette)
                } else {
                    val jumpDy = if (pose.bodyDy < -1) pose.bodyDy else 0
                    drawSideBoot(raw, cx, FOOT_Y - bootH + jumpDy, pose.rightLegStride, bootH, palette, darkBias = 0.22f)
                    drawSideBoot(raw, cx, FOOT_Y - bootH + jumpDy, pose.leftLegStride, bootH, palette, darkBias = 0f)
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawSideBoot(
        img: TileImage,
        cx: Int,
        baseY: Int,
        stride: Float,
        bootH: Int,
        palette: PaletteRamp,
        darkBias: Float
    ) {
        val footDx = (stride * 5.0f).roundToInt()
        val footLift = if (stride > 0.3f) -2 else 0
        fillShadedBox(img, cx + footDx - 3, baseY + footLift, 5, bootH, palette, darkBias)
    }

    private fun drawTorso(
        img: TileImage,
        style: TorsoStyle,
        palette: PaletteRamp,
        accent: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy

        when (dir) {
            Direction8.S, Direction8.N -> {
                drawContouredTorsoFront(raw, cx, 28 + dy, palette, darkBias = if (dir == Direction8.N) 0.12f else 0f)
                // Sleeves
                drawFrontArm(raw, cx - 10, 29 + dy, pose.leftArmSwing, pose.swingPhase, isLeft = true, skin = palette, castRaised = pose.castRaised, pickupDepth = pose.pickupDepth, bowDrawn = pose.bowDrawn)
                drawFrontArm(raw, cx + 7, 29 + dy, pose.rightArmSwing, -1, isLeft = false, skin = palette, castRaised = pose.castRaised, pickupDepth = 0, bowDrawn = pose.bowDrawn)
                // Belt & buckle
                fillShadedBox(raw, cx - 6, 39 + dy, 12, 2, accent, darkBias = 0.1f)
                if (dir == Direction8.S) {
                    raw.setPixel(cx - 1, 39 + dy, accent.highlight)
                    raw.setPixel(cx, 39 + dy, accent.highlight)
                }
                if (style == TorsoStyle.BRIGANDINE || style == TorsoStyle.SKYRENDER) {
                    fillShadedEllipse(raw, cx - 7, 30 + dy, 3, 2, accent)
                    fillShadedEllipse(raw, cx + 7, 30 + dy, 3, 2, accent)
                }
            }
            else -> {
                drawContouredTorsoSide(raw, cx, 28 + dy, palette)
                fillShadedBox(raw, cx - 4, 39 + dy, 8, 2, accent, darkBias = 0.1f)
                drawSideArm(raw, cx, 29 + dy, pose.leftArmSwing, pose.swingPhase, palette, darkBias = 0f, castRaised = pose.castRaised, pickupDepth = pose.pickupDepth, bowDrawn = pose.bowDrawn, isRiding = pose.isRiding)
                if (style == TorsoStyle.BRIGANDINE || style == TorsoStyle.SKYRENDER) {
                    fillShadedEllipse(raw, cx, 30 + dy, 3, 2, accent)
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawGloves(
        img: TileImage,
        style: GloveStyle,
        palette: PaletteRamp,
        accent: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy
        val h = if (style == GloveStyle.GAUNTLET) 5 else 4

        when (dir) {
            Direction8.S, Direction8.N -> {
                fillShadedBox(raw, pose.mainHandX - 1, pose.mainHandY - h + 1, 3, h, palette)
                fillShadedBox(raw, pose.offHandX - 1, pose.offHandY - h + 1, 3, h, palette)
                if (style == GloveStyle.GAUNTLET) {
                    raw.setPixel(pose.mainHandX, pose.mainHandY - h + 1, accent.highlight)
                    raw.setPixel(pose.offHandX, pose.offHandY - h + 1, accent.highlight)
                }
            }
            else -> {
                fillShadedBox(raw, pose.mainHandX - 1, pose.mainHandY - h + 1, 3, h, palette)
                if (style == GloveStyle.GAUNTLET) {
                    raw.setPixel(pose.mainHandX, pose.mainHandY - h + 1, accent.highlight)
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawCape(img: TileImage, palette: PaletteRamp, dir: Direction8, pose: Pose) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy

        when (dir) {
            Direction8.S -> {
                for (ry in 0 until 22) {
                    val halfW = 7 + ry / 10
                    fillShadedBox(raw, cx - halfW, 30 + dy + ry, halfW * 2, 1, palette, darkBias = 0.28f)
                }
            }
            Direction8.N -> {
                for (ry in 0 until 23) {
                    val halfW = 7 + ry / 8
                    for (dx in -halfW until halfW) {
                        val fold = if (abs(dx) % 4 == 0) 0.14f else 0f
                        val nx = (dx + halfW) / (2f * halfW).coerceAtLeast(1f)
                        val ny = ry / 22f
                        val t = (0.88f - (nx * 0.35f + ny * 0.38f) - fold).coerceIn(0.12f, 0.94f)
                        raw.setPixel(cx + dx, 30 + dy + ry, palette.sample(t))
                    }
                }
            }
            else -> {
                for (ry in 0 until 22) {
                    val w = 4 + ry / 10
                    fillShadedBox(raw, cx + 3, 30 + dy + ry, w, 1, palette, darkBias = 0.12f)
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawQuiver(
        img: TileImage,
        leather: PaletteRamp,
        fletching: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy

        when (dir) {
            Direction8.S -> {
                fillShadedBox(raw, cx + 4, 24 + dy, 3, 5, leather)
                raw.setPixel(cx + 4, 22 + dy, fletching.highlight)
                raw.setPixel(cx + 6, 23 + dy, fletching.mid)
            }
            Direction8.N -> {
                for (i in 0..11) {
                    fillShadedBox(raw, cx + 3 - i / 2, 27 + dy + i, 3, 2, leather)
                }
                raw.setPixel(cx + 4, 25 + dy, fletching.highlight)
                raw.setPixel(cx + 6, 26 + dy, fletching.mid)
            }
            else -> {
                fillShadedBox(raw, cx + 3, 29 + dy, 3, 10, leather)
                raw.setPixel(cx + 4, 27 + dy, fletching.highlight)
                raw.setPixel(cx + 5, 28 + dy, fletching.mid)
            }
        }

        applyOutline(raw, img, outlineColorFor(leather))
    }

    private fun drawHeadgear(
        img: TileImage,
        kind: HeadgearKind,
        palette: PaletteRamp,
        accent: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val cx = CX + pose.bodyDx
        val dy = pose.bodyDy
        val topY = 9 + dy

        when (kind) {
            HeadgearKind.IRON_HELMET, HeadgearKind.STEEL_HELMET, HeadgearKind.VALKYRIE_HELM, HeadgearKind.SKYRENDER_HELM -> {
                // Wrap-around LPC spangenhelm
                fillShadedEllipse(raw, cx, topY + 5, 9, 6, palette)
                // Brow rim
                fillShadedBox(raw, cx - 8, topY + 6, 16, 2, accent)
                if (dir == Direction8.S) {
                    // Cheek guards + nasal bar
                    fillShadedBox(raw, cx - 8, topY + 8, 2, 5, palette)
                    fillShadedBox(raw, cx + 6, topY + 8, 2, 5, palette)
                    fillShadedBox(raw, cx - 1, topY + 7, 2, 5, accent)
                } else if (dir == Direction8.N) {
                    // Neck guard
                    fillShadedBox(raw, cx - 8, topY + 8, 16, 7, palette)
                } else {
                    // Side cheek/neck guard
                    fillShadedBox(raw, cx + 1, topY + 7, 6, 7, palette)
                }
            }
            HeadgearKind.WIZARD_HAT -> {
                fillShadedEllipse(raw, cx, topY + 5, 11, 3, palette)
                fillShadedBox(raw, cx - 5, topY + 1, 10, 4, palette)
                fillShadedBox(raw, cx - 3, topY - 3, 6, 4, palette)
                fillShadedBox(raw, cx - 1, topY - 6, 3, 3, palette)
                fillShadedBox(raw, cx - 5, topY + 3, 10, 2, accent)
            }
            HeadgearKind.PRIEST_MITRE, HeadgearKind.SLIME_BERET -> {
                // Circlet / runic crown
                fillShadedBox(raw, cx - 7, topY + 4, 14, 2, palette)
                raw.setPixel(cx - 4, topY + 2, palette.highlight)
                raw.setPixel(cx, topY + 1, accent.highlight)
                raw.setPixel(cx + 4, topY + 2, palette.highlight)
                raw.setPixel(cx, topY + 3, accent.mid)
            }
            HeadgearKind.FEATHER_CAP, HeadgearKind.BANDANA -> {
                // Full-coverage ranger hood / cap (covers hair cleanly on all sides)
                fillShadedEllipse(raw, cx, topY + 5, 10, 6, palette)
                if (dir == Direction8.S) {
                    fillShadedBox(raw, cx - 9, topY + 6, 3, 8, palette)
                    fillShadedBox(raw, cx + 6, topY + 6, 3, 8, palette)
                    fillShadedBox(raw, cx - 7, topY + 5, 14, 2, accent)
                } else if (dir == Direction8.N) {
                    fillShadedBox(raw, cx - 9, topY + 6, 18, 10, palette)
                } else {
                    fillShadedBox(raw, cx - 1, topY + 6, 9, 10, palette)
                    fillShadedBox(raw, cx - 6, topY + 5, 13, 2, accent)
                }
            }
            else -> {
                fillShadedEllipse(raw, cx, topY + 4, 8, 4, palette)
                fillShadedBox(raw, cx - 7, topY + 5, 14, 2, accent)
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    private fun drawWeapon(
        img: TileImage,
        kind: WeaponKind,
        palette: PaletteRamp,
        hilt: PaletteRamp,
        dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val hx = pose.mainHandX
        val hy = pose.mainHandY

        when (kind) {
            WeaponKind.COMPOSITE_BOW -> {
                if ((dir == Direction8.S || dir == Direction8.N) && pose.swingPhase >= 0) {
                    // Aiming DOWN (S) or UP (N): hold the recurve bow horizontally in front of the character with arrow pointing along facing
                    val sign = if (dir == Direction8.S) 1 else -1
                    val by = hy + sign * 4
                    val cx = CX + pose.bodyDx
                    for (dx in -10..10) {
                        val curve = (abs(dx) * abs(dx)) / 18
                        raw.setPixel(cx + dx, by - sign * curve, hilt.highlight)
                        raw.setPixel(cx + dx, by - sign * (curve + 1), hilt.mid)
                        raw.setPixel(cx + dx, by - sign * (curve + 2), hilt.shadow)
                    }
                    val pull = if (pose.bowDrawn) 5 else 0
                    val stringBase = by - sign * 6
                    for (dx in -9..9) {
                        val sy = stringBase - sign * if (pull > 0) ((9 - abs(dx)) * pull) / 9 else 0
                        raw.setPixel(cx + dx, sy, rgba(228, 228, 236, 225))
                    }
                    if (pose.bowDrawn) {
                        val tipY = by + sign * 5
                        val nockY = stringBase - sign * pull
                        val minY = minOf(tipY, nockY)
                        val maxY = maxOf(tipY, nockY)
                        for (ay in minY..maxY) {
                            raw.setPixel(cx, ay, hilt.highlight)
                        }
                        raw.setPixel(cx, tipY + sign, palette.highlight)
                        raw.setPixel(cx - 1, tipY, palette.mid)
                        raw.setPixel(cx + 1, tipY, palette.mid)
                        raw.setPixel(cx - 1, nockY, rgba(240, 240, 245, 255))
                        raw.setPixel(cx + 1, nockY, rgba(240, 240, 245, 255))
                    }
                } else {
                    // Side view (W; E is mirrored) or relaxed carry in idle/walk
                    val bx = if (dir == Direction8.W) hx - 3 else hx
                    for (dy in -11..11) {
                        val curve = (abs(dy) * abs(dy)) / 18
                        raw.setPixel(bx + curve, hy + dy, hilt.highlight)
                        raw.setPixel(bx + curve + 1, hy + dy, hilt.mid)
                        raw.setPixel(bx + curve + 2, hy + dy, hilt.shadow)
                    }
                    val pull = if (pose.bowDrawn) 6 else 0
                    val stringBase = bx + 6
                    for (dy in -10..10) {
                        val sx = stringBase + if (pull > 0) ((10 - abs(dy)) * pull) / 10 else 0
                        raw.setPixel(sx, hy + dy, rgba(228, 228, 236, 225))
                    }
                    if (pose.bowDrawn && dir != Direction8.N) {
                        // Visible nocked arrow pointing forward
                        val tipX = bx - 5
                        val nockX = stringBase + pull
                        for (ax in tipX..nockX) {
                            raw.setPixel(ax, hy, hilt.highlight)
                        }
                        raw.setPixel(tipX - 1, hy, palette.highlight)
                        raw.setPixel(tipX, hy - 1, palette.mid)
                        raw.setPixel(tipX, hy + 1, palette.mid)
                        raw.setPixel(nockX, hy - 1, rgba(240, 240, 245, 255))
                        raw.setPixel(nockX, hy + 1, rgba(240, 240, 245, 255))
                    }
                }
            }
            WeaponKind.ARCANE_STAFF, WeaponKind.HOLY_ROD -> {
                fillShadedBox(raw, hx - 1, hy - 14, 2, 20, hilt)
                fillShadedEllipse(raw, hx, hy - 15, 3, 3, palette)
            }
            else -> {
                when (pose.swingPhase) {
                    0 -> {
                        // Windup: blade angled up-back (or straight up outside left shoulder in S/N)
                        if (dir == Direction8.S || dir == Direction8.N) {
                            fillShadedBox(raw, hx - 1, hy - 13, 2, 13, palette)
                            fillShadedBox(raw, hx - 3, hy - 1, 6, 2, hilt)
                        } else {
                            for (i in 0..11) {
                                raw.setPixel(hx + i / 2, hy - i, palette.highlight)
                                raw.setPixel(hx + i / 2 + 1, hy - i, palette.mid)
                            }
                            fillShadedBox(raw, hx - 2, hy - 1, 5, 2, hilt)
                        }
                    }
                    1 -> {
                        // Mid-slash: 45-deg forward-up in W/E, 45-deg down-out in S, 45-deg up-out in N
                        when (dir) {
                            Direction8.S -> {
                                for (i in 0..11) {
                                    raw.setPixel(hx - i / 2, hy + i, palette.highlight)
                                    raw.setPixel(hx - i / 2 - 1, hy + i, palette.mid)
                                }
                                fillShadedBox(raw, hx - 2, hy - 1, 4, 2, hilt)
                            }
                            else -> {
                                for (i in 0..12) {
                                    raw.setPixel(hx - i, hy - i, palette.highlight)
                                    raw.setPixel(hx - i, hy - i + 1, palette.mid)
                                }
                                fillShadedBox(raw, hx - 2, hy - 1, 4, 2, hilt)
                            }
                        }
                    }
                    2 -> {
                        // Strike: forward slash (down-forward in S, up-forward in N, horizontal in W/E)
                        when (dir) {
                            Direction8.S -> {
                                fillShadedBox(raw, hx - 1, hy, 3, 12, palette)
                                fillShadedBox(raw, hx - 3, hy - 1, 6, 2, hilt)
                            }
                            Direction8.N -> {
                                fillShadedBox(raw, hx - 1, hy - 13, 3, 13, palette)
                                fillShadedBox(raw, hx - 3, hy, 6, 2, hilt)
                            }
                            else -> {
                                fillShadedBox(raw, hx - 14, hy - 1, 14, 3, palette)
                                fillShadedBox(raw, hx - 1, hy - 3, 3, 6, hilt)
                            }
                        }
                    }
                    3 -> {
                        // Low sweep: angled 30-deg down-forward
                        for (i in 0..12) {
                            raw.setPixel(hx - i, hy + i / 3, palette.highlight)
                            raw.setPixel(hx - i, hy + i / 3 + 1, palette.mid)
                        }
                        fillShadedBox(raw, hx - 1, hy - 2, 3, 5, hilt)
                    }
                    4 -> {
                        // Deep follow-through: angled downward-forward
                        for (i in 0..11) {
                            raw.setPixel(hx - i, hy + i / 2, palette.highlight)
                            raw.setPixel(hx - i, hy + i / 2 + 1, palette.mid)
                        }
                        fillShadedBox(raw, hx - 1, hy - 2, 2, 4, hilt)
                    }
                    else -> {
                        // Idle / recovery: upright blade
                        fillShadedBox(raw, hx - 1, hy - 14, 2, 14, palette)
                        fillShadedBox(raw, hx - 3, hy - 1, 6, 2, hilt)
                        fillShadedBox(raw, hx - 1, hy + 1, 2, 3, hilt)
                    }
                }
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
        // Bold 2-px crescent slash arc on strike (phase 2) & fading trail on phase 3
        if (kind != WeaponKind.COMPOSITE_BOW && kind != WeaponKind.ARCANE_STAFF && kind != WeaponKind.HOLY_ROD && pose.swingPhase in 2..3) {
            val alpha = if (pose.swingPhase == 2) 220 else 130
            val arc = rgba(242, 248, 255, alpha)
            val glow = rgba(196, 224, 255, (alpha * 0.7f).roundToInt())
            if (dir == Direction8.S) {
                for (dx in -8..8) {
                    val ay = hy + 13 - (dx * dx) / 10
                    img.drawPixel(hx + dx, ay, arc)
                    img.drawPixel(hx + dx, ay + 1, glow)
                }
            } else if (dir != Direction8.N) {
                for (dy in -8..7) {
                    val ax = hx - 16 + (dy * dy) / 10
                    img.drawPixel(ax, hy + dy, arc)
                    img.drawPixel(ax + 1, hy + dy, glow)
                }
            }
        }
    }

    private fun drawShield(
        img: TileImage,
        kind: ShieldKind,
        palette: PaletteRamp,
        trim: PaletteRamp,
        @Suppress("UNUSED_PARAMETER") dir: Direction8,
        pose: Pose
    ) {
        val raw = TileImage(W, H)
        val sx = pose.offHandX
        val sy = pose.offHandY

        when (kind) {
            ShieldKind.ROUND_BUCKLER -> {
                fillShadedEllipse(raw, sx, sy - 2, 4, 5, palette)
                raw.setPixel(sx, sy - 2, trim.highlight)
            }
            ShieldKind.SPELLBOOK -> {
                fillShadedBox(raw, sx - 3, sy - 4, 6, 7, palette)
                fillShadedBox(raw, sx - 2, sy - 3, 4, 5, trim)
            }
            ShieldKind.OFFHAND_BLADE, ShieldKind.OFFHAND_SWORD, ShieldKind.OFFHAND_KATANA -> {
                fillShadedBox(raw, sx - 1, sy - 8, 2, 8, palette)
                fillShadedBox(raw, sx - 2, sy, 4, 2, trim)
            }
            else -> {
                fillShadedBox(raw, sx - 4, sy - 6, 8, 10, palette)
                fillShadedBox(raw, sx - 1, sy - 5, 2, 8, trim)
                fillShadedBox(raw, sx - 3, sy - 2, 6, 2, trim)
            }
        }

        applyOutline(raw, img, outlineColorFor(palette))
    }

    // -------------------------------------------------------------------------
    // Pixel-Art Drawing Primitives
    // -------------------------------------------------------------------------

    private fun fillShadedBox(
        img: TileImage,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        ramp: PaletteRamp,
        darkBias: Float = 0f
    ) {
        for (ry in 0 until h) {
            for (rx in 0 until w) {
                val nx = rx / (w - 1).coerceAtLeast(1).toFloat()
                val ny = ry / (h - 1).coerceAtLeast(1).toFloat()
                val t = (0.9f - (nx * 0.45f + ny * 0.45f) - darkBias).coerceIn(0.08f, 0.96f)
                img.setPixel(x0 + rx, y0 + ry, ramp.sample(t))
            }
        }
    }

    private fun fillShadedEllipse(
        img: TileImage,
        cx: Int,
        cy: Int,
        rx: Int,
        ry: Int,
        ramp: PaletteRamp,
        darkBias: Float = 0f
    ) {
        for (dy in -ry..ry) {
            for (dx in -rx..rx) {
                val u = dx / rx.toFloat().coerceAtLeast(1f)
                val v = dy / ry.toFloat().coerceAtLeast(1f)
                if (u * u + v * v <= 1f) {
                    val nx = (u + 1f) * 0.5f
                    val ny = (v + 1f) * 0.5f
                    val t = (0.92f - (nx * 0.45f + ny * 0.45f) - darkBias).coerceIn(0.08f, 0.96f)
                    img.setPixel(cx + dx, cy + dy, ramp.sample(t))
                }
            }
        }
    }

    private fun applyOutline(src: TileImage, dst: TileImage, outlineRgba: Int) {
        for (y in 0 until H) {
            for (x in 0 until W) {
                val c = src.getPixel(x, y)
                if ((c and 0xFF) != 0) {
                    dst.setPixel(x, y, c)
                } else {
                    val hasNeighbor =
                        ((src.getPixel(x - 1, y) and 0xFF) != 0) ||
                            ((src.getPixel(x + 1, y) and 0xFF) != 0) ||
                            ((src.getPixel(x, y - 1) and 0xFF) != 0) ||
                            ((src.getPixel(x, y + 1) and 0xFF) != 0)
                    if (hasNeighbor) {
                        dst.setPixel(x, y, outlineRgba)
                    }
                }
            }
        }
    }

    private fun outlineColorFor(ramp: PaletteRamp): Int {
        val s = ramp.shadow
        val r = (((s ushr 24) and 0xFF) * 0.35f).roundToInt().coerceIn(14, 70)
        val g = (((s ushr 16) and 0xFF) * 0.32f).roundToInt().coerceIn(12, 65)
        val b = (((s ushr 8) and 0xFF) * 0.38f).roundToInt().coerceIn(18, 80)
        return rgba(r, g, b, 255)
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Int): Int =
        ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)
}
