package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.PlayerActivity

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemStack

/** Gear wear as a system: the weapon wears after a landed hit, worn armour after a hit taken, and `RepairGear` mends it all. */
object DurabilitySystem : CombatObserver, EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.DURABILITY.configKey
    override fun afterAttack(sim: WorldSimulation, attackerId: String, out: MutableList<WorldSimulationEvent>) =
        sim.wearWeapon(attackerId, out)

    override fun afterStruck(sim: WorldSimulation, victim: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState =
        sim.wearArmor(victim, out)

    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.RepairGear) return false
        out.addAll(sim.repairGear(characterId, cmd.currencyItemId))
        return true
    }
}

private val ARMOR_SLOTS = listOf(EquipmentSlot.ARMOR, EquipmentSlot.HELMET, EquipmentSlot.SHIELD, EquipmentSlot.BOOTS)

/** Wears the player's weapon after a landed hit (see `DurabilityDefinition`); no-op without a `durability` config. */
internal fun WorldSimulation.wearWeapon(characterId: String, out: MutableList<WorldSimulationEvent>) {
    val cfg = game.definition.durability?.takeIf { it.enabled } ?: return
    val player = players[characterId] ?: return
    val before = player.equipment.getActive(EquipmentSlot.WEAPON) ?: return
    if (cfg.weaponWearChancePercent <= 0 || rng.nextIntInclusive(1, 100) > cfg.weaponWearChancePercent) return
    val after = worn(before, cfg.wearPerHit)
    val updated = player.copy(equipment = player.equipment.copy(equipped = player.equipment.equipped + (EquipmentSlot.WEAPON to after)))
    players[characterId] = updated
    announceWear(updated, before, after, out)
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
}

/** Wears one random worn armor piece of [player] after they took a hit and returns the updated character. */
internal fun WorldSimulation.wearArmor(player: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState {
    val cfg = game.definition.durability?.takeIf { it.enabled } ?: return player
    if (cfg.armorWearChancePercent <= 0) return player
    val candidates = ARMOR_SLOTS.filter { player.equipment.getActive(it) != null }
    if (candidates.isEmpty() || rng.nextIntInclusive(1, 100) > cfg.armorWearChancePercent) return player
    val slot = candidates[rng.nextIntInclusive(0, candidates.size - 1)]
    val before = player.equipment.getEquipped(slot)!!
    val after = worn(before, cfg.wearPerHit)
    val updated = player.copy(equipment = player.equipment.copy(equipped = player.equipment.equipped + (slot to after)))
    announceWear(updated, before, after, out)
    return updated
}

private fun worn(stack: ItemStack, by: Int): ItemStack = stack.copy(durability = (stack.durability - by).coerceAtLeast(0))

private fun WorldSimulation.announceWear(player: CharacterState, before: ItemStack, after: ItemStack, out: MutableList<WorldSimulationEvent>) {
    val name = items[after.itemId]?.name ?: "gear"
    when {
        after.durability <= 0 && before.durability > 0 ->
            out.add(tellPlayer(player, "Your $name broke! Repair it at an Armorer.", "WARNING"))
        after.durability <= 10 && before.durability > 10 ->
            out.add(tellPlayer(player, "Your $name is nearly broken (${after.durability}%).", "WARNING"))
    }
}

/**
 * Repairs all worn and carried gear for [currencyItemId], or nothing when the player cannot pay for all of it.
 * Returns the events to emit (a notice and the updated character).
 */
internal fun WorldSimulation.repairGear(characterId: String, currencyItemId: Int): List<WorldSimulationEvent> {
    val cfg = game.definition.durability?.takeIf { it.enabled } ?: return emptyList()
    val player = players[characterId] ?: return emptyList()
    fun cost(stack: ItemStack?): Int {
        if (stack == null || stack.durability >= 100) return 0
        val def = items[stack.itemId] ?: return 0
        return cfg.repairCost(def.price, 100 - stack.durability)
    }
    val total = player.equipment.equipped.values.sumOf { cost(it) } + player.inventory.slots.sumOf { cost(it) }
    if (total <= 0) return listOf(tellPlayer(player, "Nothing needs repairing.", "INFO"))
    val currencyName = items[currencyItemId]?.name ?: "currency"
    val paid = player.inventory.removeByItemId(currencyItemId, total)
        ?: return listOf(tellPlayer(player, "Repairs cost $total $currencyName; you don't have enough.", "WARNING"))
    fun fixed(stack: ItemStack) = if (stack.durability < 100) stack.copy(durability = 100) else stack
    val updated = player.copy(
        inventory = paid.copy(slots = paid.slots.map { it?.let(::fixed) }),
        equipment = player.equipment.copy(equipped = player.equipment.equipped.mapValues { fixed(it.value) })
    )
    players[characterId] = updated
    return listOf(tellPlayer(updated, "Repaired all gear for $total $currencyName.", "GOOD"), WorldSimulationEvent.CharacterUpdated(updated))
}
