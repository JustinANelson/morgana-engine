package com.morgana.client.realms

import com.morgana.protocol.CharacterNames
import com.morgana.protocol.CharacterSlot
import com.morgana.protocol.ServerPacket

/** The wording and checks of the character screen, free of widgets so they can be tested. */
object CharacterSelectModel {
    /** "Level 5 Knight": the second line of a character card. */
    fun subtitle(slot: CharacterSlot): String = "Level ${slot.level} ${slot.className}"

    /** Whether the account may make another character. */
    fun canCreate(list: ServerPacket.CharacterList): Boolean = list.characters.size < list.maxCharacters

    /** A line saying how many slots are used, for example "2 of 3 characters". */
    fun slotsLine(list: ServerPacket.CharacterList): String {
        val used = list.characters.size
        return if (list.characters.isEmpty()) "No characters yet" else "$used of ${list.maxCharacters} character${if (list.maxCharacters == 1) "" else "s"}"
    }

    /** What is wrong with a new character's [name] before the server is asked, or null. The server checks the rest (taken names). */
    fun createProblem(list: ServerPacket.CharacterList, name: String): String? {
        CharacterNames.problem(name)?.let { return it }
        if (list.characters.any { it.name.equals(name.trim(), ignoreCase = true) }) return "You already have a character called ${name.trim()}."
        if (!canCreate(list)) return "You already have the most characters this realm allows (${list.maxCharacters})."
        return null
    }

    /** Whether the text typed to confirm a deletion matches the character's name. */
    fun deleteConfirmed(slot: CharacterSlot, typed: String): Boolean = slot.name.equals(typed.trim(), ignoreCase = true)

    /** The warning shown before a character is deleted. */
    fun deleteWarning(slot: CharacterSlot): String =
        "Delete ${slot.name} (${subtitle(slot)})? Its items, progress and quests are gone for good. Type ${slot.name} to confirm."

    /** The slot to select when the list first appears: the character played last this session, else the first one. */
    fun initialSelection(list: ServerPacket.CharacterList, lastPlayedName: String?): CharacterSlot? =
        list.characters.firstOrNull { it.name.equals(lastPlayedName, ignoreCase = true) } ?: list.characters.firstOrNull()
}
