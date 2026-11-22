package com.morgana.client.ui

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.game.BlankGame
import com.morgana.domain.game.VitalDefinition
import com.morgana.domain.game.XpGaugeDefinition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class VitalBarsTest {
    private val fury = VitalDefinition("fury", "Fury", "Fury", max = "100")
    private val base = BlankGame.definition
    private val game = base.copy(vitals = base.vitals + fury, xpGauge = XpGaugeDefinition(vitalId = "fury"))
    private val allVitals = game.vitals.map { it.id }

    private fun spell(id: Int, classes: Set<Int>, costs: Map<String, Int>) =
        SpellDefinition(id = id, name = "s$id", classReqIds = classes, costs = costs)

    private val burst = spell(1, setOf(1), mapOf("fury" to 100))
    private val strike = spell(2, emptySet(), mapOf("mp" to 3))

    @Test
    fun `the gauge shows only for a class with a skill that spends it`() {
        assertEquals(allVitals, VitalBars.visibleIds(game, listOf(burst, strike), classId = 1))
        assertEquals(allVitals - "fury", VitalBars.visibleIds(game, listOf(burst, strike), classId = 2))
    }

    @Test
    fun `a skill any class can cast shows the gauge for everyone`() {
        val any = spell(3, emptySet(), mapOf("fury" to 50))
        assertEquals(allVitals, VitalBars.visibleIds(game, listOf(any), classId = 9))
    }

    @Test
    fun `the gauge is hidden when no skill spends it, and a disabled gauge is an ordinary vital`() {
        assertEquals(allVitals - "fury", VitalBars.visibleIds(game, listOf(strike), classId = 1))
        val off = game.copy(xpGauge = XpGaugeDefinition(enabled = false, vitalId = "fury"))
        assertEquals(allVitals, VitalBars.visibleIds(off, listOf(strike), classId = 1))
        assertEquals(allVitals, VitalBars.visibleIds(base.copy(vitals = game.vitals), emptyList(), classId = 1))
    }
}
