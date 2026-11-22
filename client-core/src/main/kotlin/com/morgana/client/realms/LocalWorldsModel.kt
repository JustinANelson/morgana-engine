package com.morgana.client.realms

import com.morgana.client.LocalHostingStatus
import com.morgana.client.LocalWorldDetails
import com.morgana.domain.sim.OptionalModule

/** The wording of the Local Worlds screen, kept free of widgets so it can be tested. */
object LocalWorldsModel {
    /** Where a world came from, in words. */
    fun originText(origin: String): String = when {
        origin == "blank" -> "Blank game"
        origin == "server-data" -> "Your original world"
        origin.startsWith("game:") -> "Copy of ${origin.removePrefix("game:")}"
        origin.startsWith("pack:") -> "Imported world pack (${origin.removePrefix("pack:")})"
        else -> origin
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    /** "Blank game, 3 maps, played 2 h ago": the second line of a list row. */
    fun subtitle(d: LocalWorldDetails, nowMs: Long, hosting: Boolean): String {
        val parts = mutableListOf(originText(d.origin))
        if (d.mapCount > 0) parts += plural(d.mapCount, "map")
        when {
            hosting -> parts += "hosting now"
            d.lastPlayedAtMillis != null -> parts += "played ${RealmBrowserModel.agoText(nowMs, d.lastPlayedAtMillis)}"
            d.isDefault -> {} // the original world keeps no play record
            else -> parts += "not played yet"
        }
        return parts.joinToString(" - ")
    }

    /** Which optional systems are off, or a note that everything is on. */
    fun featuresText(d: LocalWorldDetails): String {
        if (d.disabledFeatures.isEmpty()) return "All optional systems are on."
        val names = d.disabledFeatures.map { OptionalModule.fromConfigKey(it)?.displayName ?: it }
        return "Switched off: ${names.joinToString(", ")}."
    }

    /** One line about what this machine is hosting. */
    fun hostingLine(s: LocalHostingStatus?): String =
        if (s == null) "Not hosting a world right now. Playing a world starts it."
        else "Hosting ${s.name} on port ${s.port} - ${plural(s.onlinePlayers, "player")} online"

    /** What friends type to join, or why they cannot. */
    fun friendsLine(s: LocalHostingStatus?): String = when {
        s == null -> "Friends can join once you start a world."
        !s.encrypted -> "Remote joining is off for this world (encryption is disabled)."
        s.address != null -> "Friends on your network can join at ${s.address}:${s.port}. For friends elsewhere, forward port ${s.port} on your router and share an invite code."
        else -> "No network address was found. Copy an invite code and fill in your public address."
    }

    /** The warning shown before a world is deleted. */
    fun deleteWarning(d: LocalWorldDetails, hosting: Boolean): String =
        "Delete ${d.name}? Its maps, accounts and characters are removed for good and cannot be brought back." +
            if (hosting) " It is being hosted now and will be stopped first." else ""
}
