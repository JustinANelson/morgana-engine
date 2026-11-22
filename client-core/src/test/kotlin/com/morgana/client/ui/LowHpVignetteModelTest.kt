package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowHpVignetteModelTest {

    @Test
    fun `isCritical is false above threshold, at zero, or with non-positive max`() {
        assertFalse(LowHpVignetteModel.isCritical(31, 100))
        assertFalse(LowHpVignetteModel.isCritical(0, 100))
        assertFalse(LowHpVignetteModel.isCritical(10, 0))
        assertTrue(LowHpVignetteModel.isCritical(30, 100))
        assertTrue(LowHpVignetteModel.isCritical(1, 100))
    }

    @Test
    fun `vignetteAlpha is zero when safe and scales with severity`() {
        assertEquals(0f, LowHpVignetteModel.vignetteAlpha(80, 100, elapsedSeconds = 1f, reduceMotion = false))
        assertEquals(0f, LowHpVignetteModel.vignetteAlpha(0, 100, elapsedSeconds = 1f, reduceMotion = false))

        val mild = LowHpVignetteModel.vignetteAlpha(28, 100, elapsedSeconds = 0f, reduceMotion = true)
        val severe = LowHpVignetteModel.vignetteAlpha(5, 100, elapsedSeconds = 0f, reduceMotion = true)
        assertTrue(mild > 0f)
        assertTrue(severe > mild)
    }

    @Test
    fun `reduceMotion produces steady alpha across time`() {
        val a0 = LowHpVignetteModel.vignetteAlpha(15, 100, elapsedSeconds = 0f, reduceMotion = true)
        val a1 = LowHpVignetteModel.vignetteAlpha(15, 100, elapsedSeconds = 0.7f, reduceMotion = true)
        assertEquals(a0, a1, 0.0001f)
    }
}
