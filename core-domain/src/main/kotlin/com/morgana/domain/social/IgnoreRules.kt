package com.morgana.domain.social

/**
 * Pure rules for the ignore list, kept on the character as `ignoredNames`. Names compare ignoring case. Staff cannot be
 * ignored (the server checks the access level), so a player can never silence a moderator talking to them.
 */
object IgnoreRules {
    const val MAX_IGNORED = 100

    sealed interface Change {
        data class Updated(val names: Set<String>) : Change
        data class Refused(val reason: String) : Change
    }

    fun add(ignored: Set<String>, selfName: String, name: String): Change = when {
        name.equals(selfName, ignoreCase = true) -> Change.Refused("You cannot ignore yourself.")
        contains(ignored, name) -> Change.Refused("You already ignore $name.")
        ignored.size >= MAX_IGNORED -> Change.Refused("Your ignore list is full ($MAX_IGNORED).")
        else -> Change.Updated(ignored + name)
    }

    fun remove(ignored: Set<String>, name: String): Change {
        val kept = ignored.filterNot { it.equals(name, ignoreCase = true) }.toSet()
        return if (kept.size == ignored.size) Change.Refused("You are not ignoring $name.") else Change.Updated(kept)
    }

    fun contains(ignored: Set<String>, name: String): Boolean = ignored.any { it.equals(name, ignoreCase = true) }
}
