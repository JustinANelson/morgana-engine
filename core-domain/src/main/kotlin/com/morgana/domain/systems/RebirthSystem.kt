package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.event.EventCommand
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.RequirementGate
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.EquipmentSlot

/**
 * Rebirth as a system: spells with a `rebirthReq` are refused until the character has been reborn that many times, and the
 * `Rebirth` event command carries out the rebirth.
 */
object RebirthSystem : RequirementGate, EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.REBIRTH.configKey
    override fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition): String? =
        if (sim.meetsRebirthReq(player, spell)) null else "${spell.name} requires ${spell.rebirthReq} rebirth(s)."

    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.Rebirth) return false
        sim.rebirthCharacter(characterId, cmd.classId, out)
        return true
    }
}

/** True when [player] has been reborn often enough for [spell] (always true for spells with no `rebirthReq`). */
internal fun WorldSimulation.meetsRebirthReq(player: CharacterState, spell: SpellDefinition): Boolean {
    if (spell.rebirthReq <= 0) return true
    val cfg = game.definition.rebirth ?: return false
    return cfg.count(player) >= spell.rebirthReq
}

/**
 * Rebirth: resets [characterId] to the configured level (optionally as [requestedClassId]), rebuilds their attributes
 * for that level of the class, keeps spells the class can use, adds the class's starting spells and rebirth rewards,
 * moves gear they can no longer wear into the bag, refills vitals and counts the rebirth in the configured variable.
 * Refused with a notice when rebirth is off, the level is too low or the maximum has been reached.
 */
internal fun WorldSimulation.rebirthCharacter(
    characterId: String,
    requestedClassId: Int,
    out: MutableList<WorldSimulationEvent>
) {
    val cfg = game.definition.rebirth?.takeIf { it.enabled } ?: return
    val player = players[characterId] ?: return
    val count = cfg.count(player)
    if (count >= cfg.maxRebirths) {
        out.add(tellPlayer(player, "You have already been reborn the most times possible.", "WARN"))
        return
    }
    if (player.level < cfg.minLevel) {
        out.add(tellPlayer(player, "You must reach level ${cfg.minLevel} before you can be reborn.", "WARN"))
        return
    }
    val newClassId = if (cfg.allowClassChange && requestedClassId > 0 && requestedClassId in classes) requestedClassId else player.classId
    val cls = classes[newClassId]
    val levelsGained = (cfg.resetLevel - 1).coerceAtLeast(0)
    val attributes = game.definition.attributes.associate { a ->
        val base = cls?.baseAttributes?.get(a.id) ?: a.base
        a.id to base + (cls?.autoStatGrowthPerLevel?.get(a.id) ?: 0) * levelsGained
    }
    val pointsFromLevels = if (cls == null || cls.autoStatGrowthPerLevel.isNotEmpty()) 0 else cls.statPointsPerLevel * levelsGained
    val newCount = count + 1
    val unspent = pointsFromLevels + cfg.bonusStatPointsPerRebirth * newCount

    val grants = cfg.grantSpellIds.filterKeys { it <= newCount }.values.flatten()
    val keptSpells = player.knownSpellIds.filter { id ->
        val sp = spells[id]
        sp != null && (sp.classReqIds.isEmpty() || newClassId in sp.classReqIds) && meetsRebirthReq(player.copy(variables = player.variables + (cfg.countVariable to newCount)), sp)
    }
    val knownSpells = (keptSpells + (cls?.startingSpellIds.orEmpty()) + grants.filter { id -> spells[id]?.let { it.classReqIds.isEmpty() || newClassId in it.classReqIds } == true }).distinct()

    var inventory = player.inventory
    val keptEquipment = player.equipment.equipped.toMutableMap()
    for ((slot, stack) in player.equipment.equipped) {
        val def = items[stack.itemId] ?: continue
        val fits = def.levelReq <= cfg.resetLevel && (def.classReqIds.isEmpty() || newClassId in def.classReqIds)
        if (fits || slot == EquipmentSlot.AMMO) continue
        val moved = inventory.addStack(def, stack)
        if (moved.overflowAmount == 0) {
            inventory = moved.updatedInventory
            keptEquipment.remove(slot)
        }
    }

    val reborn = player.copy(
        classId = newClassId,
        level = cfg.resetLevel,
        xp = 0L,
        attributes = attributes,
        unspentStatPoints = unspent,
        knownSpellIds = knownSpells,
        statusEffects = emptyList(),
        inventory = inventory,
        equipment = player.equipment.copy(equipped = keptEquipment),
        variables = player.variables + (cfg.countVariable to newCount)
    ).recalculateVitals(game, items, refill = true)
    players[characterId] = reborn
    out.add(tellPlayer(reborn, "You have been reborn (${newCount}/${cfg.maxRebirths})! Your journey begins anew at level ${cfg.resetLevel}.", "GOOD"))
    out.add(WorldSimulationEvent.CharacterUpdated(reborn))
}
