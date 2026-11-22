package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.RequirementGate
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer

/**
 * Promotion as a system: spells and items with a `promotionReq` are refused until the character has reached that rank, and the
 * `Promote` event command promotes them.
 */
object PromotionSystem : RequirementGate, EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.PROMOTION.configKey
    override fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition): String? =
        refusal(sim, player, spell.name, spell.promotionReq)

    override fun itemRefusal(sim: WorldSimulation, player: CharacterState, item: ItemDefinition): String? =
        refusal(sim, player, item.name, item.promotionReq)

    private fun refusal(sim: WorldSimulation, player: CharacterState, name: String, required: Int): String? =
        if (sim.meetsPromotionReq(player, required)) null else "$name requires the rank ${sim.promotionTierName(required)}."

    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.Promote) return false
        sim.promoteCharacter(characterId, out)
        return true
    }
}

/** True when [player] has reached promotion tier [required] (always true for 0 or less). A game without promotions never meets a requirement. */
internal fun WorldSimulation.meetsPromotionReq(player: CharacterState, required: Int): Boolean {
    if (required <= 0) return true
    val cfg = game.definition.promotion?.takeIf { it.enabled } ?: return false
    return cfg.tierOf(player) >= required
}

/** Display name of the promotion tier [required], for notices. */
internal fun WorldSimulation.promotionTierName(required: Int): String =
    game.definition.promotion?.nameOf(required) ?: "tier $required"

/**
 * Promotes [characterId] one tier: needs the next tier's minimum level, then raises the tier variable, grants the
 * tier's attribute points and the spells its class can use. Refused with a notice when promotions are off, the
 * character is already at the top or is too low.
 */
internal fun WorldSimulation.promoteCharacter(characterId: String, out: MutableList<WorldSimulationEvent>) {
    val cfg = game.definition.promotion?.takeIf { it.enabled } ?: return
    val player = players[characterId] ?: return
    val tier = cfg.tierOf(player)
    val next = cfg.tiers.getOrNull(tier)
    if (next == null) {
        out.add(tellPlayer(player, "You have reached the highest rank, ${cfg.nameOf(tier, player.classId)}.", "WARN"))
        return
    }
    if (player.level < next.minLevel) {
        out.add(tellPlayer(player, "You must reach level ${next.minLevel} to become a ${next.classNames[player.classId] ?: next.name}.", "WARN"))
        return
    }
    val learned = (next.grantSpellIds + (next.classGrantSpellIds[player.classId] ?: emptyList())).filter { id ->
        val sp = spells[id]
        sp != null && id !in player.knownSpellIds && (sp.classReqIds.isEmpty() || player.classId in sp.classReqIds)
    }
    val promoted = player.copy(
        unspentStatPoints = player.unspentStatPoints + next.bonusStatPoints,
        knownSpellIds = (player.knownSpellIds + learned).distinct(),
        variables = player.variables + (cfg.variable to tier + 1)
    )
    players[characterId] = promoted
    out.add(tellPlayer(promoted, "You have been promoted to ${cfg.nameOf(tier + 1, player.classId)}!", "GOOD"))
    out.add(WorldSimulationEvent.CharacterUpdated(promoted))
}
