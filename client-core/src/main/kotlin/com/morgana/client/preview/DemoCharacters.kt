package com.morgana.client.preview

import com.morgana.client.realms.DEFAULT_CLASSES
import com.morgana.protocol.CharacterSlot
import com.morgana.protocol.ServerPacket

/** A made-up character list for previewing the character screen (a page opened with `?fakeCharacters=1`, or `=0` for none yet). */
fun demoCharacterList(count: Int = 2): ServerPacket.CharacterList = ServerPacket.CharacterList(
    characters = listOf(
        CharacterSlot("demo-1", "Mirabel", 1, "Knight", 14, 1),
        CharacterSlot("demo-2", "Tobias the Brewer", 3, "Cleric", 6, 3),
        CharacterSlot("demo-3", "Nix", 2, "Rogue", 2, 2)
    ).take(count.coerceIn(0, 3)),
    maxCharacters = 3,
    classes = DEFAULT_CLASSES
)

/** The character count asked for with `?fakeCharacters=N` (0..3) in a page address, or null when it is not there. */
fun fakeCharactersFromQuery(search: String): Int? =
    search.removePrefix("?").split('&')
        .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "fakeCharacters" }?.get(1)?.trim()?.toIntOrNull() }
        .firstOrNull { it in 0..3 }
