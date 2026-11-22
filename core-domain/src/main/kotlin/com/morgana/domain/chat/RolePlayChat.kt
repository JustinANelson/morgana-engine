package com.morgana.domain.chat

import kotlinx.serialization.Serializable

/**
 * How a line of local speech is meant to be read. Sent to clients alongside the chat channel so they can show an action
 * as "* Aria draws her sword", out-of-character talk apart from the story, and narration with no speaker.
 */
@Serializable
enum class ChatStyle {
    /** Ordinary in-character speech ("Aria: Hello"). */
    NORMAL,

    /** An action written in the third person (`/me draws her sword`). */
    ACTION,

    /** Out-of-character talk (`/ooc brb` or `((brb))`). */
    OOC,

    /** Loud speech that carries further (`/shout`). */
    SHOUT,

    /** Quiet speech only those right beside the speaker hear (`/quiet`). */
    QUIET,

    /** Scene description by staff, shown without a speaker (`/narrate`). */
    NARRATION
}

/**
 * Operator settings for role-play speech (the `roleplay` section of `server.json`).
 *
 * @property sayRadius how many tiles ordinary speech and actions carry; 0 (the default) means the whole map, which is how
 *   map chat always worked, so a realm only becomes range-limited when its operator asks for it
 * @property quietRadius how many tiles `/quiet` speech carries (default 2)
 * @property shoutReachesNeighbors whether `/shout` is also heard on the maps directly north, south, east and west
 */
data class RolePlayChatSettings(
    val sayRadius: Int = 0,
    val quietRadius: Int = DEFAULT_QUIET_RADIUS,
    val shoutReachesNeighbors: Boolean = true
) {
    init {
        require(sayRadius in 0..MAX_RADIUS) { "sayRadius must be between 0 and $MAX_RADIUS" }
        require(quietRadius in 1..MAX_RADIUS) { "quietRadius must be between 1 and $MAX_RADIUS" }
    }

    companion object {
        const val MAX_RADIUS = 64
        const val DEFAULT_QUIET_RADIUS = 2
    }
}

/** A parsed line of local role-play speech. [text] may be blank when a command was typed without words. */
data class RolePlayLine(val style: ChatStyle, val text: String, val command: String = "")

/** Where a speaker stands, for working out who hears them. */
data class Listener(val id: String, val mapId: Int, val tileX: Int, val tileY: Int)

/**
 * The rules of local role-play speech: which typed lines are role-play commands, and who hears each style.
 * Pure and deterministic; the server does the sending.
 */
object RolePlayChat {
    private val commandStyles: Map<String, ChatStyle> = linkedMapOf(
        "me" to ChatStyle.ACTION,
        "em" to ChatStyle.ACTION,
        "emote" to ChatStyle.ACTION,
        "ooc" to ChatStyle.OOC,
        "say" to ChatStyle.NORMAL,
        "shout" to ChatStyle.SHOUT,
        "yell" to ChatStyle.SHOUT,
        "quiet" to ChatStyle.QUIET,
        "mutter" to ChatStyle.QUIET,
        "narrate" to ChatStyle.NARRATION,
        "describe" to ChatStyle.NARRATION
    )

    /** The slash commands this module answers, without the slash. */
    val commands: Set<String> get() = commandStyles.keys

    /** One-line help for each style's commands, shown when a command is typed without words. */
    fun usage(style: ChatStyle): String = when (style) {
        ChatStyle.ACTION -> "Usage: /me <action>, e.g. /me draws her sword"
        ChatStyle.OOC -> "Usage: /ooc <message> (or wrap it in double brackets: ((message)))"
        ChatStyle.NORMAL -> "Usage: /say <message>"
        ChatStyle.SHOUT -> "Usage: /shout <message>"
        ChatStyle.QUIET -> "Usage: /quiet <message>"
        ChatStyle.NARRATION -> "Usage: /narrate <scene description>"
    }

    /**
     * Reads a slash command line (`/me waves`). Returns null when the line is not a role-play command, so other
     * handlers (moderation, plugins, scripts, admin commands) can have it.
     */
    fun parseCommand(line: String): RolePlayLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/")) return null
        val body = trimmed.removePrefix("/")
        val word = body.substringBefore(' ').lowercase()
        val style = commandStyles[word] ?: return null
        val text = body.substring(word.length).trim()
        return RolePlayLine(style, if (style == ChatStyle.OOC) stripOocBrackets(text) else text, word)
    }

    /**
     * Reads ordinary text typed on the map or emote channel: text on the emote channel is an action, text wrapped in
     * double brackets is out of character, anything else is normal speech.
     */
    fun parseText(text: String, emoteChannel: Boolean): RolePlayLine {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("((") -> RolePlayLine(ChatStyle.OOC, stripOocBrackets(trimmed))
            emoteChannel -> RolePlayLine(ChatStyle.ACTION, trimmed)
            else -> RolePlayLine(ChatStyle.NORMAL, trimmed)
        }
    }

    private fun stripOocBrackets(text: String): String {
        var t = text.trim()
        if (t.startsWith("((")) t = t.removePrefix("((")
        if (t.endsWith("))")) t = t.removeSuffix("))")
        return t.trim()
    }

    /** The radius in tiles a style carries on the speaker's own map; 0 means the whole map. */
    fun radiusFor(style: ChatStyle, settings: RolePlayChatSettings): Int = when (style) {
        ChatStyle.NORMAL, ChatStyle.ACTION -> settings.sayRadius
        ChatStyle.QUIET -> settings.quietRadius
        ChatStyle.OOC, ChatStyle.SHOUT, ChatStyle.NARRATION -> 0
    }

    /**
     * Who hears [speaker] say something in [style], in the order [everyone] lists them. The speaker always hears
     * themselves. Distance is counted in tiles, diagonals included (a square around the speaker), and speech never
     * crosses into another map except a shout, which reaches the four neighbouring maps ([neighborsOf]) when
     * [RolePlayChatSettings.shoutReachesNeighbors] is on.
     */
    fun recipients(
        speaker: Listener,
        everyone: List<Listener>,
        style: ChatStyle,
        settings: RolePlayChatSettings,
        neighborsOf: (Int) -> Set<Int> = { emptySet() }
    ): List<String> {
        val radius = radiusFor(style, settings)
        val extraMaps = if (style == ChatStyle.SHOUT && settings.shoutReachesNeighbors) {
            neighborsOf(speaker.mapId).filter { it > 0 && it != speaker.mapId }.toSet()
        } else emptySet()
        return everyone.filter { other ->
            when {
                other.id == speaker.id -> true
                other.mapId == speaker.mapId ->
                    radius == 0 || maxOf(kotlin.math.abs(other.tileX - speaker.tileX), kotlin.math.abs(other.tileY - speaker.tileY)) <= radius
                else -> other.mapId in extraMaps
            }
        }.map { it.id }
    }

    /**
     * How a line reads on a client that does not know about chat styles: the style is folded into the text so nothing
     * is lost. Returns the sender name and message to send instead.
     */
    fun plainFallback(style: ChatStyle, senderName: String, text: String): Pair<String, String> = when (style) {
        ChatStyle.NORMAL -> senderName to text
        ChatStyle.ACTION -> senderName to "* $senderName $text"
        ChatStyle.OOC -> senderName to "(( $text ))"
        ChatStyle.SHOUT -> senderName to "(shouts) $text"
        ChatStyle.QUIET -> senderName to "(quietly) $text"
        ChatStyle.NARRATION -> NARRATOR_NAME to text
    }

    /** Name shown for narration on clients and in logs meant for players (the staff member is recorded separately). */
    const val NARRATOR_NAME = "Narrator"
}
