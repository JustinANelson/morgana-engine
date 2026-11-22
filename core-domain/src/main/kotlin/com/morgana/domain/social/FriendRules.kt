package com.morgana.domain.social

/**
 * Pure rules for the friends list, kept on the character as [com.morgana.domain.entity.CharacterState.friendNames].
 * Names are compared ignoring case; the list stores each friend's name as the character spells it.
 */
object FriendRules {
    const val MAX_FRIENDS = 100

    sealed interface Change {
        data class Updated(val friends: Set<String>) : Change
        data class Refused(val reason: String) : Change
    }

    /** [friends] with [friendName] added, or why that is not allowed. [selfName] is the player's own name. */
    fun add(friends: Set<String>, selfName: String, friendName: String): Change = when {
        friendName.equals(selfName, ignoreCase = true) -> Change.Refused("You cannot add yourself as a friend.")
        friends.any { it.equals(friendName, ignoreCase = true) } -> Change.Refused("$friendName is already on your friends list.")
        friends.size >= MAX_FRIENDS -> Change.Refused("Your friends list is full ($MAX_FRIENDS).")
        else -> Change.Updated(friends + friendName)
    }

    /** [friends] without [name] (case-insensitive), or why nothing changed. */
    fun remove(friends: Set<String>, name: String): Change {
        val kept = friends.filterNot { it.equals(name, ignoreCase = true) }.toSet()
        return if (kept.size == friends.size) Change.Refused("$name is not on your friends list.") else Change.Updated(kept)
    }

    /** Whether [name] is on [friends]. */
    fun contains(friends: Set<String>, name: String): Boolean = friends.any { it.equals(name, ignoreCase = true) }

    /** Friends sorted for display: online first, then by name. */
    fun sorted(friends: Collection<String>, isOnline: (String) -> Boolean): List<String> =
        friends.sortedWith(compareBy<String>({ !isOnline(it) }, { it.lowercase() }))
}
