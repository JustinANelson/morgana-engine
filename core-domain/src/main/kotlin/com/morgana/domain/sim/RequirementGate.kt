package com.morgana.domain.sim

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition

/**
 * The first seam between the kernel and the systems built on it (ADR 0018): something that can refuse a character the use of a
 * spell or an item for a reason of its own (a rank, a rebirth count). The kernel keeps its own checks (level, class, weapon) and
 * then asks every gate in [WorldSimulation.requirementGates]; the first reason wins and is shown to the player.
 *
 * A gate is a pure query: it must not change the world and must give the same answer for the same state, so replays agree.
 * It may be written in the engine (promotion and rebirth are) or, later, handed to a game's Lua plugin.
 */
interface RequirementGate {
    /** Why [player] may not cast [spell] or learn it from a scroll, or null when this gate has no objection. */
    fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition): String? = null

    /** Why [player] may not use, wear or upgrade into [item], or null when this gate has no objection. */
    fun itemRefusal(sim: WorldSimulation, player: CharacterState, item: ItemDefinition): String? = null
}

/** The first refusal any gate gives for [spell], or null when the character may use it. */
internal fun WorldSimulation.spellRefusal(player: CharacterState, spell: SpellDefinition): String? =
    requirementGates.firstNotNullOfOrNull { if (isOn(it)) it.spellRefusal(this, player, spell) else null }

/** The first refusal any gate gives for [item], or null when the character may use it. */
internal fun WorldSimulation.itemRefusal(player: CharacterState, item: ItemDefinition): String? =
    requirementGates.firstNotNullOfOrNull { if (isOn(it)) it.itemRefusal(this, player, item) else null }
