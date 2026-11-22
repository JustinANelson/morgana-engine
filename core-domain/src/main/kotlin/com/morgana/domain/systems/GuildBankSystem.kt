package com.morgana.domain.systems

import com.morgana.domain.sim.PlayerActivity
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.actionBlocked
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.warningNotice

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.BankWithdrawal
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.game.GuildBankDefinition
import com.morgana.domain.item.Inventory
import com.morgana.domain.social.GuildBankLog
import com.morgana.domain.social.GuildRules
import kotlin.math.abs
import kotlin.math.max

/*
 * The guild bank: one vault per guild, kept in [GuildDocument.bank]. Members deposit items (Marks are an item too) at a
 * `GUILD_BANKER` NPC; the founder picks the lowest rank that may withdraw; every movement is written to
 * [GuildDocument.bankLog]. Needs the `guildBank` section in the game definition.
 */
private class BankAccess(val player: CharacterState, val guild: GuildDocument, val rank: String, val config: GuildBankDefinition)

/** The player's guild vault access, or null (with a notice in [out]) when they cannot use it right now. */
private fun WorldSimulation.bankAccess(characterId: String, out: MutableList<WorldSimulationEvent>): BankAccess? {
    val config = game.definition.guildBank?.takeIf { it.enabled } ?: return null
    val player = players[characterId] ?: return null
    val guild = player.guildId?.let { guilds[it] }
    val rank = guild?.memberRanks?.get(player.id)
    if (guild == null || rank == null) {
        out.add(warningNotice(player, "The vault is for guild members only."))
        return null
    }
    val near = mapNpcs[player.position.mapId].orEmpty().any {
        npcs[it.npcId]?.behavior == NpcBehavior.GUILD_BANKER && max(abs(it.tileX - player.position.tileX), abs(it.tileY - player.position.tileY)) <= config.reachTiles
    }
    if (!near) {
        out.add(warningNotice(player, "Step up to the Quartermaster to use the vault."))
        return null
    }
    return BankAccess(player, guild, rank, config)
}

private fun WorldSimulation.withLog(guild: GuildDocument, text: String, keep: Int): List<String> =
    (guild.bankLog + GuildBankLog.line(effectiveWorldTick(), text)).takeLast(keep)

private fun WorldSimulation.saveBank(guild: GuildDocument, player: CharacterState, out: MutableList<WorldSimulationEvent>) {
    guilds[guild.id] = guild
    players[player.id] = player
    out.add(WorldSimulationEvent.CharacterUpdated(player))
    out.add(WorldSimulationEvent.GuildBankChanged(guild.id))
}

internal fun WorldSimulation.guildBankDepositImpl(characterId: String, inventorySlotIndex: Int, amount: Int, tab: Int = -1): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val a = bankAccess(characterId, out) ?: return out
    val stack = a.player.inventory.getSlot(inventorySlotIndex) ?: return out
    val def = items[stack.itemId] ?: return out
    if (def.boundOnPickup) {
        out.add(warningNotice(a.player, "${def.name} is bound to you and cannot go in the vault."))
        return out
    }
    val qty = amount.coerceIn(1, stack.amount)
    val (afterInv, moved) = a.player.inventory.removeFromSlot(inventorySlotIndex, qty) ?: return out
    val placed = placeInVault(a.guild.bank, def, moved, tab, a.config.tabSlots)
    if (placed == null) {
        out.add(warningNotice(a.player, if (tab >= 0) "That vault tab is full." else "The vault is full."))
        return out
    }
    val guild = a.guild.copy(bank = placed, bankLog = withLog(a.guild, "${a.player.name} deposited ${moved.amount} x ${def.name}", a.config.logEntries))
    saveBank(guild, a.player.copy(inventory = afterInv), out)
    return out
}

internal fun WorldSimulation.guildBankWithdrawImpl(characterId: String, bankSlotIndex: Int, amount: Int): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val a = bankAccess(characterId, out) ?: return out
    val need = a.guild.bankWithdrawRank.ifBlank { a.config.defaultWithdrawRank }
    if (!GuildRules.atLeast(a.rank, need)) {
        out.add(warningNotice(a.player, "Only ${if (need == GuildRules.FOUNDER) "the founder" else "$need rank and above"} may withdraw from the vault."))
        return out
    }
    val stack = a.guild.bank.getSlot(bankSlotIndex) ?: return out
    val def = items[stack.itemId] ?: return out
    // A rank with a daily limit may take only what is left of it today; the founder never has one.
    val limit = if (a.rank == GuildRules.FOUNDER) 0 else a.config.withdrawLimits[a.rank] ?: 0
    val day = effectiveWorldTick() / a.config.limitDayTicks
    val taken = a.guild.bankWithdrawn[a.player.id]?.takeIf { it.day == day }?.count ?: 0
    var qty = amount.coerceIn(1, stack.amount)
    if (limit > 0) {
        if (taken >= limit) {
            out.add(warningNotice(a.player, "You have reached today's vault limit of $limit items."))
            return out
        }
        qty = qty.coerceAtMost(limit - taken)
    }
    val (afterBank, moved) = a.guild.bank.removeFromSlot(bankSlotIndex, qty) ?: return out
    val res = a.player.inventory.addStack(def, moved)
    if (res.overflowAmount > 0) {
        out.add(warningNotice(a.player, "Your bag is full."))
        return out
    }
    val withdrawn = if (limit > 0) a.guild.bankWithdrawn + (a.player.id to BankWithdrawal(day, taken + moved.amount)) else a.guild.bankWithdrawn
    val guild = a.guild.copy(
        bank = afterBank, bankWithdrawn = withdrawn,
        bankLog = withLog(a.guild, "${a.player.name} withdrew ${moved.amount} x ${def.name}", a.config.logEntries)
    )
    saveBank(guild, a.player.copy(inventory = res.updatedInventory), out)
    return out
}

internal fun WorldSimulation.setGuildBankAccessImpl(characterId: String, rank: String): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val a = bankAccess(characterId, out) ?: return out
    if (a.rank != GuildRules.FOUNDER) {
        out.add(warningNotice(a.player, "Only the founder sets who may withdraw."))
        return out
    }
    if (rank !in listOf(GuildRules.FOUNDER, GuildRules.OFFICER, GuildRules.MEMBER)) return out
    val guild = a.guild.copy(bankWithdrawRank = rank, bankLog = withLog(a.guild, "${a.player.name} set withdrawals to $rank and above", a.config.logEntries))
    saveBank(guild, a.player, out)
    return out
}

/**
 * Puts [stack] in the vault: anywhere when [tab] is negative, else only in that tab's slots (each tab holds [tabSlots]
 * slots, the last may be shorter). Null when it does not all fit.
 */
private fun placeInVault(bank: Inventory, def: com.morgana.domain.item.ItemDefinition, stack: com.morgana.domain.item.ItemStack, tab: Int, tabSlots: Int): Inventory? {
    if (tab < 0) return bank.addStack(def, stack).takeIf { it.overflowAmount == 0 }?.updatedInventory
    val start = tab * tabSlots
    if (start >= bank.maxSlots) return null
    val end = minOf(start + tabSlots, bank.maxSlots)
    val part = Inventory(maxSlots = end - start, slots = bank.slots.subList(start, end))
    val res = part.addStack(def, stack)
    if (res.overflowAmount > 0) return null
    return bank.copy(slots = bank.slots.subList(0, start) + res.updatedInventory.slots + bank.slots.subList(end, bank.maxSlots))
}

/** The founder buys the next vault upgrade with Marks held in the vault itself; it adds slots (and so tabs). */
internal fun WorldSimulation.upgradeGuildBankImpl(characterId: String): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val a = bankAccess(characterId, out) ?: return out
    if (a.rank != GuildRules.FOUNDER) {
        out.add(warningNotice(a.player, "Only the founder may upgrade the vault."))
        return out
    }
    val next = a.config.upgrades.getOrNull(a.guild.bankLevel)
    if (next == null) {
        out.add(warningNotice(a.player, "The vault is fully upgraded."))
        return out
    }
    val have = a.guild.bank.totalCountOf(a.config.currencyItemId)
    if (have < next.cost) {
        out.add(warningNotice(a.player, "The next upgrade costs ${next.cost} Marks in the vault (it holds $have)."))
        return out
    }
    val paid = if (next.cost > 0) a.guild.bank.removeByItemId(a.config.currencyItemId, next.cost) ?: return out else a.guild.bank
    val grown = paid.grownTo(paid.maxSlots + next.extraSlots)
    val guild = a.guild.copy(
        bank = grown, bankLevel = a.guild.bankLevel + 1,
        bankLog = withLog(a.guild, "${a.player.name} upgraded the vault to ${grown.maxSlots} slots for ${next.cost} Marks", a.config.logEntries)
    )
    saveBank(guild, a.player, out)
    return out
}
