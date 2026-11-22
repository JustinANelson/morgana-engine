package com.morgana.client.ui

import kotlin.math.sin

/**
 * Pure presentation model for the low-HP peripheral danger vignette.
 * Active when the player is alive and at or below [CRITICAL_THRESHOLD] of max HP.
 */
object LowHpVignetteModel {

    const val CRITICAL_THRESHOLD = 0.30f

    fun isCritical(curHp: Int, maxHp: Int): Boolean {
        if (maxHp <= 0 || curHp <= 0) return false
        return curHp.toFloat() / maxHp.toFloat() <= CRITICAL_THRESHOLD
    }

    fun vignetteAlpha(
        curHp: Int,
        maxHp: Int,
        elapsedSeconds: Float,
        reduceMotion: Boolean
    ): Float {
        if (!isCritical(curHp, maxHp)) return 0f
        val frac = (curHp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)
        val severity = ((CRITICAL_THRESHOLD - frac) / CRITICAL_THRESHOLD).coerceIn(0f, 1f)
        if (reduceMotion) {
            return 0.10f + 0.20f * severity
        }
        val pulse = 0.5f + 0.5f * sin(elapsedSeconds * 5.0f)
        val minAlpha = 0.08f + 0.14f * severity
        val maxAlpha = 0.18f + 0.24f * severity
        return minAlpha + (maxAlpha - minAlpha) * pulse
    }
}
