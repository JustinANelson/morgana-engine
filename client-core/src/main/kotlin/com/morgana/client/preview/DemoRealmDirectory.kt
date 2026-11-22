package com.morgana.client.preview

import com.morgana.client.realms.defaultBundledDirectory
import com.morgana.protocol.realms.RealmClassSummary
import com.morgana.protocol.realms.RealmDirectoryDocument
import com.morgana.protocol.realms.RealmSummary

/**
 * A made-up realm directory of any size, for looking at the realm browser the way it will look once public directories exist
 * (a page opened with `?fakeRealms=200`, see `fakeRealmsFromQuery`). Deterministic, so a screenshot is repeatable. Every realm
 * points at [host]:[port], so none of them is a real place to play: the real ones from [defaultBundledDirectory] come first.
 */
fun demoRealmDirectory(count: Int, host: String = "127.0.0.1", port: Int = 7001): RealmDirectoryDocument {
    val real = defaultBundledDirectory(host, port)
    val firsts = listOf("Amber", "Ash", "Bright", "Crow", "Dawn", "Ember", "Frost", "Gale", "Hollow", "Iron", "Juniper", "Kestrel", "Lantern", "Moss", "Night", "Oak", "Pine", "Quill", "Raven", "Stone", "Thorn", "Umber", "Vale", "Willow")
    val seconds = listOf("haven", "reach", "hold", "fall", "ford", "mere", "wick", "gate", "crest", "deep", "wood", "moor")
    val kinds = listOf("Realm", "Lands", "Chronicles", "Legends", "Frontier", "Isles")
    val tagPool = listOf("PvE", "PvP", "Cozy", "Hardcore", "Roleplay", "Economy", "Guilds", "Farming", "Crafting", "Quests", "Co-op", "Fantasy", "Sci-fi", "Casual", "Events")
    val blurbs = listOf(
        "A friendly community with weekly events and a helpful staff.",
        "Hardcore rules: death costs you your gear, and the wilds are open to everyone.",
        "A slow, cozy realm for farming, fishing and chatting by the fire.",
        "Player-run economy with a bustling market and guild halls.",
        "Story-driven quests written by the community, new chapters every month."
    )
    var seed = 12345L
    fun next(bound: Int): Int {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 33) % bound).toInt()
    }

    val made = (1..count.coerceIn(0, 2000)).map { i ->
        val name = "${firsts[next(firsts.size)]}${seconds[next(seconds.size)]} ${kinds[next(kinds.size)]}"
        val max = listOf(40, 60, 100, 150, 200)[next(5)]
        val online = if (next(10) == 0) max else next(max + 1) / (1 + next(3))
        RealmSummary(
            id = "demo-$i",
            name = "$name #$i",
            description = blurbs[next(blurbs.size)],
            host = host,
            port = port,
            tls = next(3) != 0,
            gameTitle = name,
            engineApi = if (i % 47 == 0) 2 else 1,
            onlinePlayers = online,
            maxPlayers = max,
            tags = (0 until 2 + next(3)).map { tagPool[next(tagPool.size)] }.distinct(),
            classes = listOf(RealmClassSummary(1, "Adventurer"))
        )
    }
    return real.copy(realms = real.realms + made)
}

/** The realm count asked for with `?fakeRealms=N` in a page address (1..2000), or null. */
fun fakeRealmsFromQuery(search: String): Int? =
    search.removePrefix("?").split('&')
        .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "fakeRealms" }?.get(1)?.trim()?.toIntOrNull() }
        .firstOrNull { it in 1..2000 }
