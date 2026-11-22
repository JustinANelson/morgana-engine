package com.morgana.client.ui

import com.morgana.client.ui.bag.BagFilter

/**
 * What a list says when it has nothing to show: what is missing and what to do about it, never a blank space. The words are
 * kept genre-neutral (the engine runs any game), so they never name a place, person or item. Pure, so they are unit-tested.
 */
object EmptyStates {
    const val QUESTS = "No quests yet. Talk to the characters around you; some may have work for you."
    const val RECIPES = "No crafting recipes are available in this realm."
    const val SPELLS = "You don't know any spells yet. New ones may come from levelling up, trainers or items."
    const val SKILLS = "You don't know any skills yet. New ones may come from levelling up, trainers or items."
    const val VAULT = "Nothing is stored here yet. Deposit items from the list below."
    const val VAULT_BAG = "Your bag is empty, so there is nothing to deposit."
    const val LOCAL_WORLDS = "No worlds on this computer yet. Use New Realm Wizard to make one."

    fun abilities(terminology: com.morgana.domain.content.AbilityTerminology): String =
        if (terminology == com.morgana.domain.content.AbilityTerminology.SKILLS) SKILLS else SPELLS

    /** Why the bag list shows no items: a search, a tab, or neither (null when something is showing). */
    fun bag(filter: BagFilter, query: String): String {
        val q = query.trim()
        return when {
            q.isNotEmpty() -> "No items match \"$q\". Clear the search to see everything."
            filter != BagFilter.ALL -> "You have no ${filter.label.lowercase()} items. Pick the All tab to see everything."
            else -> "Your bag is empty."
        }
    }

    /** Why the realm list is empty: filters hiding everything, or nothing listed at all. */
    fun realms(filtering: Boolean): String =
        if (filtering) "No realms match. Clear the filters to see them all."
        else "No realms are listed right now. Press Refresh, join by address on Direct Join, or play a local world."
}
