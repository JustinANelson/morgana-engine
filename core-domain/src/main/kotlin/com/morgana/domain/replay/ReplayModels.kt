package com.morgana.domain.replay

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import kotlinx.serialization.Serializable

/**
 * One externally-driven input to the deterministic simulation. Everything that can change world state
 * (including the passage of time via [Tick]) is an input, so a seed plus an ordered input list fully
 * reproduces a run. Inputs are pure data, hence serializable into a replay log.
 */
@Serializable
sealed interface SimInput {
    fun applyTo(sim: WorldSimulation): List<WorldSimulationEvent>

    @Serializable data class Tick(val deltaMs: Int = 50) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.stepTick(deltaMs)
    }
    @Serializable data class SpawnPlayer(val character: CharacterState) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.spawnOrUpdatePlayer(character)
    }
    /** Wholesale replacement of one player's state, used for coordinator-side edits (hotbar, party tag, ...). */
    @Serializable data class SetPlayerState(val character: CharacterState) : SimInput {
        override fun applyTo(sim: WorldSimulation): List<WorldSimulationEvent> {
            sim.players[character.id] = character
            return emptyList()
        }
    }
    @Serializable data class RemovePlayer(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.removePlayer(characterId)
    }
    @Serializable data class Move(
        val characterId: String,
        val direction: Direction,
        val mode: MovementMode = MovementMode.GRID_LOCKED,
        val deltaPixelsX: Float = 0f,
        val deltaPixelsY: Float = 0f
    ) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.movePlayer(characterId, direction, mode, deltaPixelsX, deltaPixelsY)
    }
    @Serializable data class Attack(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.playerAttack(characterId)
    }
    @Serializable data class CastSpell(
        val characterId: String,
        val spellId: Int,
        val targetEntityId: String? = null,
        val targetTileX: Int = 0,
        val targetTileY: Int = 0
    ) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.playerCastSpell(characterId, spellId, targetEntityId, targetTileX, targetTileY)
    }
    @Serializable data class InteractFacing(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.playerInteractFacing(characterId)
    }
    @Serializable data class PickupGroundItem(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.pickupGroundItem(characterId)
    }
    @Serializable data class UseInventorySlot(val characterId: String, val slotIndex: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.useInventorySlot(characterId, slotIndex)
    }
    @Serializable data class DropInventorySlot(val characterId: String, val slotIndex: Int, val amount: Int = 1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.dropInventorySlot(characterId, slotIndex, amount)
    }
    @Serializable data class UnequipSlot(val characterId: String, val slot: EquipmentSlot) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.unequipSlot(characterId, slot)
    }
    @Serializable data class AllocateStatPoint(val characterId: String, val statName: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.allocateStatPoint(characterId, statName)
    }
    @Serializable data class SetPkMode(val characterId: String, val mode: com.morgana.domain.entity.PkMode) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.setPkMode(characterId, mode)
    }
    @Serializable data class JumpTo(val characterId: String, val targetX: Int, val targetY: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.jumpTo(characterId, targetX, targetY)
    }
    @Serializable data class ShopBuy(val characterId: String, val shopId: Int, val entryIndex: Int, val quantity: Int = 1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.shopBuy(characterId, shopId, entryIndex, quantity)
    }
    @Serializable data class ShopSell(
        val characterId: String,
        val shopId: Int,
        val inventorySlotIndex: Int = -1,
        val quantity: Int = 1,
        val slotIndices: List<Int> = emptyList()
    ) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.shopSell(characterId, shopId, inventorySlotIndex, quantity, slotIndices)
    }
    @Serializable data class ShopBuyback(val characterId: String, val shopId: Int, val entryIndex: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.shopBuyback(characterId, shopId, entryIndex)
    }
    @Serializable data class SortInventory(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.sortInventory(characterId)
    }
    @Serializable data class BankDeposit(val characterId: String, val inventorySlotIndex: Int, val amount: Int = 1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.bankDeposit(characterId, inventorySlotIndex, amount)
    }
    @Serializable data class BankWithdraw(val characterId: String, val bankSlotIndex: Int, val amount: Int = 1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.bankWithdraw(characterId, bankSlotIndex, amount)
    }
    @Serializable data class GuildBankDeposit(val characterId: String, val inventorySlotIndex: Int, val amount: Int = 1, val tab: Int = -1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.guildBankDeposit(characterId, inventorySlotIndex, amount, tab)
    }
    @Serializable data class EjectFromGuildHall(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.ejectFromGuildHall(characterId)
    }
    @Serializable data class UpgradeGuildBank(val characterId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.upgradeGuildBank(characterId)
    }
    @Serializable data class GuildBankWithdraw(val characterId: String, val bankSlotIndex: Int, val amount: Int = 1) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.guildBankWithdraw(characterId, bankSlotIndex, amount)
    }
    @Serializable data class SetGuildBankAccess(val characterId: String, val rank: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.setGuildBankAccess(characterId, rank)
    }
    @Serializable data class CraftRecipe(val characterId: String, val recipeId: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.craftRecipe(characterId, recipeId)
    }
    @Serializable data class MergeGear(val characterId: String, val targetSlotIndex: Int, val materialSlotIndex: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.mergeGear(characterId, targetSlotIndex, materialSlotIndex)
    }
    @Serializable data class SelectDialogueChoice(val characterId: String, val eventId: Int, val choiceIndex: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.selectDialogueChoice(characterId, eventId, choiceIndex)
    }
    @Serializable data class SubmitTextInput(val characterId: String, val eventId: Int, val value: String = "", val cancelled: Boolean = false) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.submitTextInput(characterId, eventId, value, cancelled)
    }
    @Serializable data class AdminCommand(val characterId: String, val commandLine: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.executeAdminSlashCommand(characterId, commandLine)
    }
    @Serializable data class InitiateTrade(val initiatorId: String, val partnerId: String) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.initiateTrade(initiatorId, partnerId)
    }
    @Serializable data class AddTradeOfferItem(val characterId: String, val inventorySlotIndex: Int, val amount: Int) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.addTradeOfferItem(characterId, inventorySlotIndex, amount)
    }
    @Serializable data class ConfirmTrade(val characterId: String, val confirmStage: Boolean) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.lockAndConfirmTrade(characterId, confirmStage)
    }
    @Serializable data class ExecuteScriptEffect(val effect: com.morgana.domain.script.ScriptEffect) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.applyScriptEffect(effect)
    }
    @Serializable data class CustomAction(
        val characterId: String,
        val actionId: String,
        val params: Map<String, String> = emptyMap()
    ) : SimInput {
        override fun applyTo(sim: WorldSimulation) = sim.handleAction(characterId, actionId, params)
    }
}

/** State fingerprint taken after a given tick. */
@Serializable
data class ReplayCheckpoint(val tick: Long, val hash: String)

/**
 * A complete, replayable run. The world content and starting state are NOT embedded: replays start from a
 * simulation built with [seed] and the same content, which [contentFingerprint] lets tools sanity-check.
 */
@Serializable
data class ReplayLog(
    val formatVersion: Int = FORMAT_VERSION,
    val seed: Long,
    val contentFingerprint: String = "",
    val inputs: List<SimInput> = emptyList(),
    val checkpoints: List<ReplayCheckpoint> = emptyList(),
    val finalTick: Long = 0L,
    val finalHash: String = "",
    /** Unrecorded mutations (e.g. live content edits) that make a faithful replay impossible. Empty = complete. */
    val gaps: List<String> = emptyList(),
    /** Total simulation ticks elapsed prior to this replay run (world epoch). */
    val startingEpochTicks: Long = 0L,
    /** World-wide switches present when this replay started. */
    val startingWorldSwitches: Map<String, Boolean> = emptyMap(),
    /** World-wide numbers present when this replay started. */
    val startingWorldVariables: Map<String, Int> = emptyMap(),
    /** World-wide strings present when this replay started. */
    val startingWorldStringVariables: Map<String, String> = emptyMap()
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}
