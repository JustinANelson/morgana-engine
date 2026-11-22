package com.morgana.client.ui

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.PetState

/**
 * What the Pets window shows and the `/pet ...` commands its buttons send. Pure, so the rows and the commands are
 * unit-tested and the window can never disagree with the chat command.
 */
object PetsModel {
    const val DISMISS = "/pet dismiss"

    /** One adopted pet: its kind's name, what the owner calls it, level, XP toward the next level and whether it is out. */
    data class Row(
        val npcId: Int, val kind: String, val nickname: String, val level: Int, val xp: Int, val xpNeeded: Int,
        val maxLevel: Int, val active: Boolean
    ) {
        /** "Pip the Tame Finch" when named, else the kind's own name. */
        val title: String get() = if (nickname.isBlank()) kind else "$nickname the $kind"

        /** "Level 3, 120/600 XP", or "Level 20 (highest)". */
        val progress: String get() = if (level >= maxLevel) "Level $level (highest)" else "Level $level, $xp/$xpNeeded XP"
    }

    /** One row for each adopted pet, the one with the player first, then by id. Pets whose NPC is gone from the catalog are left out. */
    fun rows(pets: Map<Int, PetState>, activeNpcId: Int, npcs: List<NpcDefinition>): List<Row> {
        val byId = npcs.associateBy { it.id }
        return pets.entries.mapNotNull { (id, st) ->
            val def = byId[id] ?: return@mapNotNull null
            val traits = def.pet ?: return@mapNotNull null
            Row(id, def.name, st.name, st.level, st.xp, st.level * traits.xpPerLevel, traits.maxLevel, id == activeNpcId)
        }.sortedWith(compareByDescending<Row> { it.active }.thenBy { it.npcId })
    }

    /** True when the game has any pet at all, so the window is worth offering. */
    fun gameHasPets(npcs: List<NpcDefinition>): Boolean = npcs.any { it.pet != null }

    fun summon(npcId: Int) = "/pet summon $npcId"

    /** Renames the pet that is out; null for a blank name. */
    fun rename(text: String): String? = text.trim().take(16).takeIf { it.isNotEmpty() }?.let { "/pet name $it" }
}
