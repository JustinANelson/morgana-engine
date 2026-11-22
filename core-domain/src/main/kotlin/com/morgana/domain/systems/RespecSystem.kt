package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.event.EventCommand
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.PlayerActivity

/** Attribute respec as a system: the `RespecAttributes` event command. */
object RespecSystem : EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.RESPEC.configKey
    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.RespecAttributes) return false
        out.addAll(sim.respecAttributes(characterId, cmd.currencyItemId, cmd.pricePerPoint))
        return true
    }
}

/**
 * Takes back the attribute points a player has spent (see `EventCommand.RespecAttributes`). Attributes go back to what the
 * class starts with plus any growth the class gives for free each level; every point above that returns to the player's
 * unspent points, for [pricePerPoint] of [currencyItemId] each. Gear and status bonuses are not part of a character's own
 * attributes and are untouched.
 */
internal fun WorldSimulation.respecAttributes(characterId: String, currencyItemId: Int, pricePerPoint: Int): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val cls = classes[player.classId]
    val floor = game.definition.attributes.associate { a ->
        a.id to (cls?.baseAttributes?.get(a.id) ?: a.base) + (cls?.autoStatGrowthPerLevel?.get(a.id) ?: 0) * (player.level - 1).coerceAtLeast(0)
    }
    val spent = floor.entries.sumOf { (id, base) -> ((player.attributes[id] ?: base) - base).coerceAtLeast(0) }
    if (spent <= 0) return listOf(tellPlayer(player, "You have no spent attribute points to take back.", "INFO"))
    val cost = spent.toLong() * pricePerPoint.coerceAtLeast(0)
    var inventory = player.inventory
    if (cost > 0) {
        val name = items[currencyItemId]?.name ?: "currency"
        inventory = inventory.removeByItemId(currencyItemId, cost.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            ?: return listOf(tellPlayer(player, "Taking back $spent points costs $cost $name; you don't have enough.", "WARNING"))
    }
    val updated = player.copy(
        attributes = player.attributes + floor,
        unspentStatPoints = player.unspentStatPoints + spent,
        inventory = inventory
    ).recalculateVitals(game, items, refill = false)
    players[characterId] = updated
    return listOf(
        tellPlayer(updated, "Your attributes are reset: $spent points are yours to place again" + if (cost > 0) " (paid $cost)." else ".", "GOOD"),
        WorldSimulationEvent.CharacterUpdated(updated)
    )
}
