package com.morgana.client

import com.morgana.client.render.VisualSky
import com.morgana.client.render.applyList
import com.morgana.domain.game.ClockDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisualSkyTest {
    private val clock = ClockDefinition(dawnStartPermille = 100, dayStartPermille = 150, duskStartPermille = 850, nightStartPermille = 900)

    @Test
    fun noonIsBrightWithShortShadowsAndMidnightIsDarkBlue() {
        val noon = VisualSky.at(clock, 500)
        val midnight = VisualSky.at(clock, 0)
        assertEquals(1f, noon.daylight)
        assertEquals(0f, midnight.daylight)
        assertTrue(noon.ambientR > 0.95f && noon.ambientB > 0.9f)
        assertTrue(midnight.ambientB > midnight.ambientR, "night light is cool blue")
        assertTrue(noon.shadowLength < VisualSky.at(clock, 150).shadowLength, "shadows are short at noon and long near dawn")
        assertFalse(noon.isMoon)
        assertTrue(midnight.isMoon)
    }

    @Test
    fun shadowsSwingFromWestInTheMorningToEastInTheEvening() {
        val morning = VisualSky.at(clock, 200)
        val evening = VisualSky.at(clock, 800)
        assertTrue(morning.shadowShear < 0f, "morning sun is in the east, so shadows lean west")
        assertTrue(evening.shadowShear > 0f)
    }

    @Test
    fun dawnAndDuskAreWarmerThanNoonOrNight() {
        assertTrue(VisualSky.at(clock, 125).warmth > 0.9f)
        assertTrue(VisualSky.at(clock, 875).warmth > 0.9f)
        assertEquals(0f, VisualSky.at(clock, 500).warmth)
        assertEquals(0f, VisualSky.at(clock, 0).warmth)
    }

    @Test
    fun everyPermilleGivesFiniteValues() {
        for (p in 0 until 1000) {
            val s = VisualSky.at(clock, p)
            assertTrue(s.shadowLength.isFinite() && s.shadowShear.isFinite() && s.ambientR in 0f..1.01f, "permille $p")
        }
    }
}

class VisualFxListTest {
    @org.junit.jupiter.api.Test
    fun `an fx list turns exactly the named effects on`() {
        val fx = com.morgana.client.render.VisualFx
        fx.applyList("lighting, shadows")
        org.junit.jupiter.api.Assertions.assertTrue(fx.lighting && fx.shadows && !fx.hd2d)
        fx.applyList("none")
        org.junit.jupiter.api.Assertions.assertTrue(!fx.lighting && !fx.shadows && !fx.hd2d)
        fx.applyList("all")
        org.junit.jupiter.api.Assertions.assertTrue(fx.lighting && fx.shadows && fx.hd2d)
        fx.applyList(null)
        org.junit.jupiter.api.Assertions.assertTrue(fx.lighting && fx.shadows && fx.hd2d, "null leaves them alone")
        org.junit.jupiter.api.Assertions.assertEquals("none", com.morgana.client.render.fxFromQuery("?a=1&fx=none"))
    }
}
