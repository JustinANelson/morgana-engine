package com.morgana.domain.social

import com.morgana.domain.entity.GuildDocument

/**
 * Pure rules for guilds: names, ranks, who may invite, kick and change ranks, and what happens when members join or leave.
 * Every function returns a new [GuildDocument]; the server stores it and updates the members' characters.
 *
 * Ranks are plain strings in [GuildDocument.memberRanks] (character id to rank): exactly one [FOUNDER], any number of
 * [OFFICER]s and the rest [MEMBER]s.
 */
object GuildRules {
    const val FOUNDER = "Founder"
    const val OFFICER = "Officer"
    const val MEMBER = "Member"

    /** The default guild size limit; a game sets `tuning.maxGuildMembers`. */
    const val MAX_MEMBERS = com.morgana.domain.game.GameplayTuning.DEFAULT_MAX_GUILD_MEMBERS
    const val MIN_NAME = 2
    const val MAX_NAME = 24
    const val MAX_TAG = 5
    const val MAX_MOTD = 200

    sealed interface Change {
        data class Updated(val guild: GuildDocument) : Change
        /** The last member left, so the guild is gone. */
        data object Disbanded : Change
        data class Refused(val reason: String) : Change
    }

    private fun order(rank: String?): Int = when (rank) { FOUNDER -> 0; OFFICER -> 1; MEMBER -> 2; else -> 3 }

    /** True when [rank] is [min] or higher (Founder above Officer above Member). */
    fun atLeast(rank: String?, min: String): Boolean = order(rank) <= order(min)

    fun canInvite(rank: String?): Boolean = rank == FOUNDER || rank == OFFICER
    fun canSetMotd(rank: String?): Boolean = canInvite(rank)
    fun canChangeRanks(rank: String?): Boolean = rank == FOUNDER

    /** The officers kick members; the founder kicks anyone else. Nobody kicks themselves (they leave). */
    fun canKick(actorRank: String?, targetRank: String?): Boolean = when (actorRank) {
        FOUNDER -> targetRank != null && targetRank != FOUNDER
        OFFICER -> targetRank == MEMBER
        else -> false
    }

    /** Why a new guild cannot be called [name] with [tag], or null. [existingNames] are the guilds that already exist. */
    fun creationProblem(name: String, tag: String, existingNames: Collection<String>): String? = when {
        name.trim().length < MIN_NAME || name.trim().length > MAX_NAME -> "A guild name needs $MIN_NAME to $MAX_NAME characters."
        tag.trim().isEmpty() || tag.trim().length > MAX_TAG -> "A guild tag needs 1 to $MAX_TAG characters."
        existingNames.any { it.equals(name.trim(), ignoreCase = true) } -> "A guild with that name already exists."
        else -> null
    }

    fun create(id: String, name: String, tag: String, founderId: String): GuildDocument =
        GuildDocument(id = id, name = name.trim(), tag = tag.trim().uppercase(), founderCharacterId = founderId, memberRanks = mapOf(founderId to FOUNDER))

    fun join(guild: GuildDocument, characterId: String, maxMembers: Int = MAX_MEMBERS): Change = when {
        characterId in guild.memberRanks -> Change.Refused("You are already in that guild.")
        guild.memberRanks.size >= maxMembers -> Change.Refused("${guild.name} is full ($maxMembers members).")
        else -> Change.Updated(guild.copy(memberRanks = guild.memberRanks + (characterId to MEMBER)))
    }

    /** A member leaves. A founder who leaves hands the guild to an officer first, else to the longest-standing member. */
    fun leave(guild: GuildDocument, characterId: String): Change {
        if (characterId !in guild.memberRanks) return Change.Refused("You are not in that guild.")
        val rest = guild.memberRanks - characterId
        if (rest.isEmpty()) return Change.Disbanded
        if (guild.memberRanks[characterId] != FOUNDER) return Change.Updated(guild.copy(memberRanks = rest))
        val heir = rest.entries.firstOrNull { it.value == OFFICER }?.key ?: rest.keys.first()
        return Change.Updated(guild.copy(founderCharacterId = heir, memberRanks = rest + (heir to FOUNDER)))
    }

    fun kick(guild: GuildDocument, actorId: String, targetId: String): Change {
        val target = guild.memberRanks[targetId] ?: return Change.Refused("That player is not in your guild.")
        if (!canKick(guild.memberRanks[actorId], target)) return Change.Refused("You cannot remove a $target.")
        return Change.Updated(guild.copy(memberRanks = guild.memberRanks - targetId))
    }

    /** The founder makes a member an officer ([promote]) or an officer a member again. */
    fun setRank(guild: GuildDocument, actorId: String, targetId: String, promote: Boolean): Change {
        if (!canChangeRanks(guild.memberRanks[actorId])) return Change.Refused("Only the founder can change ranks.")
        val current = guild.memberRanks[targetId] ?: return Change.Refused("That player is not in your guild.")
        val next = when {
            current == FOUNDER -> return Change.Refused("The founder's rank cannot change.")
            promote && current == MEMBER -> OFFICER
            !promote && current == OFFICER -> MEMBER
            else -> return Change.Refused("That player is already ${if (promote) "an officer" else "a plain member"}.")
        }
        return Change.Updated(guild.copy(memberRanks = guild.memberRanks + (targetId to next)))
    }

    fun setMotd(guild: GuildDocument, actorId: String, text: String): Change = when {
        !canSetMotd(guild.memberRanks[actorId]) -> Change.Refused("Only the founder and officers can change the message of the day.")
        text.length > MAX_MOTD -> Change.Refused("The message of the day is too long (max $MAX_MOTD characters).")
        else -> Change.Updated(guild.copy(motd = text))
    }
}
