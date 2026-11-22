package com.morgana.client.ui

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.game.GameDefinition

/**
 * Which vital bars the HUD shows. A kill-charged gauge (`xpGauge`) means nothing to a character whose class has no skill
 * that spends it, so its bar is hidden then. Every other vital always shows. Pure, so it is unit-tested.
 */
object VitalBars {
    /** The ids of [game]'s vitals the HUD draws for a character of class [classId], in the game's order. */
    fun visibleIds(game: GameDefinition, spells: List<SpellDefinition>, classId: Int): List<String> {
        val gauge = game.xpGauge?.takeIf { it.enabled }?.vitalId
        return game.vitals.map { it.id }.filter { it != gauge || gaugeUsed(gauge, spells, classId) }
    }

    /** True when some skill the class can cast spends the vital [vitalId]. */
    fun gaugeUsed(vitalId: String, spells: List<SpellDefinition>, classId: Int): Boolean =
        spells.any { (it.costs[vitalId] ?: 0) > 0 && (it.classReqIds.isEmpty() || classId in it.classReqIds) }
}
