package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.itemRefusal
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.PlayerActivity

import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemStack

/** Gear upgrades as a system: the `UpgradeGear` event command (sockets, gems, tier and quality upgrades). */
object GearUpgradeSystem : EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.GEAR_UPGRADES.configKey
    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.UpgradeGear) return false
        out.addAll(sim.upgradeGear(characterId, cmd))
        return true
    }
}

/**
 * The `upgrade_gear` event step: socketing, gems, tier upgrades and quality upgrades on worn gear. Every refusal is a
 * chat notice and takes nothing; an attempt that rolls under `successPercent` and fails uses up the materials.
 */
internal fun WorldSimulation.upgradeGear(characterId: String, cmd: EventCommand.UpgradeGear): List<WorldSimulationEvent> {
    val cfg = game.definition.gearUpgrade?.takeIf { it.enabled } ?: return emptyList()
    val player = players[characterId] ?: return emptyList()
    fun warn(text: String) = listOf(tellPlayer(player, text, "WARNING"))

    val stack = player.equipment.getEquipped(cmd.slot)
        ?: return warn("You are not wearing anything in that slot.")
    val def = items[stack.itemId] ?: return emptyList()
    if (cmd.slot == EquipmentSlot.AMMO || def.isEffectivelyStackable) return warn("${def.name} cannot be upgraded.")
    val amount = cmd.amount.coerceAtLeast(1)
    val name = ItemRollText.displayName(def, stack.rolls, game.definition.loot, stack.plus)
    if (cmd.kind == GearUpgradeKind.ENHANCE) return enhanceGear(characterId, cmd, cfg, stack, name)
    if (cmd.kind == GearUpgradeKind.UPGRADE_QUALITY && cfg.quality != null) return raiseQuality(characterId, cmd, cfg, stack, name)

    var materialId = cmd.materialItemId
    if (cmd.kind == GearUpgradeKind.SOCKET_GEM && materialId <= 0) {
        materialId = player.inventory.slots.firstOrNull { s -> s != null && items[s.itemId]?.gem != null }?.itemId ?: 0
        if (materialId <= 0) return warn("You are not carrying a gem.")
    }
    val materialDef = items[materialId] ?: return warn("That upgrade needs a material that does not exist.")

    // Everything that can refuse is checked before any material is taken.
    val upgradedStack: ItemStack
    var upgradedMessage = ""
    when (cmd.kind) {
        GearUpgradeKind.ADD_SOCKET -> {
            if (stack.sockets.size >= cfg.maxSockets) return warn("$name already has the most sockets (${cfg.maxSockets}).")
        }
        GearUpgradeKind.SOCKET_GEM -> {
            if (materialDef.gem == null) return warn("${materialDef.name} is not a gem.")
            if (stack.sockets.none { it == 0 }) return warn("$name has no empty socket. Open one first.")
        }
        GearUpgradeKind.UPGRADE_LEVEL -> {
            val next = items[def.nextTierItemId]
            if (next == null || def.nextTierItemId <= 0) return warn("$name cannot be upgraded to a higher level.")
            if (player.level < next.levelReq) return warn("You need level ${next.levelReq} to wear ${next.name}.")
            itemRefusal(player, next)?.let { return warn(it) }
        }
        GearUpgradeKind.ENHANCE -> return emptyList()
        GearUpgradeKind.UPGRADE_QUALITY -> {
            val loot = game.definition.loot
            if (loot == null || !ItemRoller.canRoll(def)) return warn("$name cannot change quality.")
            if (nextRarityOf(stack, def.levelReq) == null) return warn("$name is already of the best quality.")
        }
    }
    val paid = player.inventory.removeByItemId(materialId, amount)
        ?: return warn("You need $amount ${materialDef.name} for that.")

    var updatedPlayer = player.copy(inventory = paid)
    val roll = if (cmd.successPercent >= 100) 1 else rng.nextIntInclusive(1, 100)
    if (roll > cmd.successPercent) {
        players[characterId] = updatedPlayer
        return listOf(
            tellPlayer(updatedPlayer, "The attempt failed and the ${materialDef.name} crumbled away.", "WARNING"),
            WorldSimulationEvent.CharacterUpdated(updatedPlayer)
        )
    }

    upgradedStack = when (cmd.kind) {
        GearUpgradeKind.ADD_SOCKET -> stack.copy(sockets = stack.sockets + 0).also { upgradedMessage = "$name gains a socket (${it.sockets.size}/${cfg.maxSockets})!" }
        GearUpgradeKind.SOCKET_GEM -> {
            val idx = stack.sockets.indexOf(0)
            stack.copy(sockets = stack.sockets.toMutableList().also { it[idx] = materialId }).also { upgradedMessage = "${materialDef.name} is set into $name!" }
        }
        GearUpgradeKind.UPGRADE_LEVEL -> {
            val next = items.getValue(def.nextTierItemId)
            stack.copy(
                itemId = next.id,
                durability = 100,
                rolls = stack.rolls?.let { it.copy(itemLevel = maxOf(it.itemLevel, next.levelReq)) }
            ).also { upgradedMessage = "$name is upgraded to ${ItemRollText.displayName(next, it.rolls, game.definition.loot, it.plus)}!" }
        }
        GearUpgradeKind.ENHANCE -> stack
        GearUpgradeKind.UPGRADE_QUALITY -> {
            val loot = game.definition.loot!!
            val level = stack.rolls?.itemLevel ?: def.levelReq
            val next = nextRarityOf(stack, def.levelReq)!!
            val rolls = ItemRoller.rollAtRarity(def, level, loot, next, rng)
            stack.copy(rolls = rolls).also { upgradedMessage = "$name is refined to ${next.name} quality: ${ItemRollText.displayName(def, rolls, loot, stack.plus)}!" }
        }
    }
    updatedPlayer = updatedPlayer.copy(equipment = updatedPlayer.equipment.copy(equipped = updatedPlayer.equipment.equipped + (cmd.slot to upgradedStack)))
        .let { if (cmd.kind == GearUpgradeKind.UPGRADE_LEVEL || cmd.kind == GearUpgradeKind.SOCKET_GEM) it.recalculateVitals(game, items) else it }
    players[characterId] = updatedPlayer
    return listOf(tellPlayer(updatedPlayer, upgradedMessage, "GOOD"), WorldSimulationEvent.CharacterUpdated(updatedPlayer))
}

/** The rarity after [stack]'s current one in the game's loot rules, or null when it is already the best. */
private fun WorldSimulation.nextRarityOf(stack: ItemStack, baseLevel: Int): com.morgana.domain.item.RarityDefinition? {
    val rarities = game.definition.loot?.rarities.orEmpty()
    val currentIdx = stack.rolls?.let { r -> rarities.indexOfFirst { it.id == r.rarity } } ?: rarities.indexOfFirst { it.unlabeled }.coerceAtLeast(0)
    return rarities.getOrNull(currentIdx + 1)
}

/**
 * One enhancement attempt (see `EnhancementDefinition`). Guaranteed tiers always work and draw no random number; a chancy tier
 * rolls `chance + pity bonus`. Everything that can refuse is checked before the material is taken.
 */
private fun WorldSimulation.enhanceGear(
    characterId: String,
    cmd: EventCommand.UpgradeGear,
    cfg: com.morgana.domain.game.GearUpgradeDefinition,
    stack: ItemStack,
    name: String
): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    fun warn(text: String) = listOf(tellPlayer(player, text, "WARNING"))
    val ladder = cfg.enhancement ?: return warn("Gear cannot be enhanced here.")
    val target = stack.plus + 1
    val tier = ladder.tiers.firstOrNull { it.plus == target } ?: return warn("$name is already at its highest +${stack.plus}.")
    val materialDef = items[tier.materialItemId] ?: return warn("That enhancement needs a material that does not exist.")
    if (game.definition.durability?.enabled == true && stack.durability <= 0) return warn("$name is broken. Repair it first.")
    val paid = player.inventory.removeByItemId(tier.materialItemId, tier.materialAmount)
        ?: return warn("To reach +$target you need ${tier.materialAmount} ${materialDef.name}.")

    val chancy = tier.chanceBasisPoints < 10_000
    val stacks = player.upgradePityStacks
    val chance = if (chancy) minOf(10_000, tier.chanceBasisPoints + stacks * ladder.pityBonusBasisPointsPerStack) else 10_000
    val success = !chancy || chance >= 10_000 || rng.rollBasisPoints(chance)

    var updated = player.copy(inventory = paid)
    val stackAfter: ItemStack
    val message: String
    val tone: String
    if (success) {
        stackAfter = stack.copy(plus = target)
        updated = updated.copy(upgradePityStacks = if (chancy) 0 else stacks)
        message = "${ItemRollText.displayName(items.getValue(stack.itemId), stack.rolls, game.definition.loot, target)} " +
            "is enhanced to +$target!" + if (chancy && stacks > 0) " The $stacks pity stacks are spent." else ""
        tone = "GOOD"
    } else {
        val newStacks = minOf(ladder.maxPityStacks, stacks + ladder.pityGainPerFailure)
        val drops = ladder.dropPlusOnFailFrom in 1..target && stack.plus > 0
        stackAfter = stack.copy(
            plus = if (drops) stack.plus - 1 else stack.plus,
            durability = (stack.durability - ladder.failDurabilityLoss).coerceAtLeast(0)
        )
        updated = updated.copy(upgradePityStacks = newStacks)
        val next = minOf(10_000, tier.chanceBasisPoints + newStacks * ladder.pityBonusBasisPointsPerStack)
        message = "The enhancement of $name to +$target failed" + (if (drops) " and it falls back to +${stackAfter.plus}" else "") +
            ". Pity $newStacks: the next try is ${next / 100}%."
        tone = "WARNING"
    }
    updated = updated.copy(equipment = updated.equipment.copy(equipped = updated.equipment.equipped + (cmd.slot to stackAfter)))
        .recalculateVitals(game, items)
    players[characterId] = updated
    return listOf(tellPlayer(updated, message, tone), WorldSimulationEvent.CharacterUpdated(updated))
}

/**
 * A quality raise under `gearUpgrade.quality`: the chance and the cost come from the step for the next rarity, a failure uses
 * up the material and builds pity, and a success may open a socket. Everything that can refuse is checked before anything is taken.
 */
private fun WorldSimulation.raiseQuality(
    characterId: String,
    cmd: EventCommand.UpgradeGear,
    cfg: com.morgana.domain.game.GearUpgradeDefinition,
    stack: ItemStack,
    name: String
): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    fun warn(text: String) = listOf(tellPlayer(player, text, "WARNING"))
    val rules = cfg.quality ?: return emptyList()
    val def = items[stack.itemId] ?: return emptyList()
    val loot = game.definition.loot
    if (loot == null || !ItemRoller.canRoll(def)) return warn("$name cannot change quality.")
    val next = nextRarityOf(stack, def.levelReq) ?: return warn("$name is already of the best quality.")
    val step = rules.steps.firstOrNull { it.rarityId == next.id } ?: return warn("$name cannot be raised to ${next.name} this way.")
    val materialDef = items[cmd.materialItemId] ?: return warn("That upgrade needs a material that does not exist.")
    val paid = player.inventory.removeByItemId(cmd.materialItemId, step.materialAmount)
        ?: return warn("Raising $name to ${next.name} needs ${step.materialAmount} ${materialDef.name}.")

    val chancy = step.chanceBasisPoints < 10_000
    val stacks = player.upgradePityStacks
    val chance = if (chancy) minOf(10_000, step.chanceBasisPoints + stacks * rules.pityBonusBasisPointsPerStack) else 10_000
    val success = !chancy || chance >= 10_000 || rng.rollBasisPoints(chance)
    var updated = player.copy(inventory = paid)
    if (!success) {
        val newStacks = minOf(rules.maxPityStacks, stacks + rules.pityGainPerFailure)
        updated = updated.copy(upgradePityStacks = newStacks)
        players[characterId] = updated
        val nextChance = minOf(10_000, step.chanceBasisPoints + newStacks * rules.pityBonusBasisPointsPerStack)
        return listOf(
            tellPlayer(updated, "The attempt failed and the ${materialDef.name} crumbled away. Pity $newStacks: the next try is ${nextChance / 100}%.", "WARNING"),
            WorldSimulationEvent.CharacterUpdated(updated)
        )
    }

    val level = stack.rolls?.itemLevel ?: def.levelReq
    val rolls = ItemRoller.rollAtRarity(def, level, loot, next, rng)
    var raised = stack.copy(rolls = rolls)
    var message = "$name is refined to ${next.name} quality: ${ItemRollText.displayName(def, rolls, loot, stack.plus)}!"
    // A small chance of a new empty socket, only while the gear has room; no random number is drawn otherwise.
    if (rules.socketChanceBasisPoints > 0 && raised.sockets.size < cfg.maxSockets && rng.rollBasisPoints(rules.socketChanceBasisPoints)) {
        raised = raised.copy(sockets = raised.sockets + 0)
        message += " It also gains a socket (${raised.sockets.size}/${cfg.maxSockets})!"
    }
    updated = updated.copy(upgradePityStacks = if (chancy) 0 else stacks)
    updated = updated.copy(equipment = updated.equipment.copy(equipped = updated.equipment.equipped + (cmd.slot to raised)))
    players[characterId] = updated
    return listOf(tellPlayer(updated, message, "GOOD"), WorldSimulationEvent.CharacterUpdated(updated))
}
