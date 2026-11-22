package com.morgana.domain.social

/**
 * Guild vault log lines carry the world tick they happened at as an `@<tick> ` prefix, so a line stays a plain string
 * (older saves hold lines without it) and clients can show a time. Pure and deterministic.
 */
object GuildBankLog {
    private const val TICKS_PER_SECOND = 20L

    fun line(tick: Long, text: String): String = "@$tick $text"

    /** The tick (or null for an old line without one) and the text of a log line. */
    fun parse(line: String): Pair<Long?, String> {
        if (!line.startsWith("@")) return null to line
        val space = line.indexOf(' ')
        val tick = if (space > 1) line.substring(1, space).toLongOrNull() else null
        return if (tick == null) null to line else tick to line.substring(space + 1)
    }

    /** A line for display: `Day 3 14:05  Ana deposited 5 x Salve` (days count from 1 and last [dayTicks] ticks). */
    fun display(line: String, dayTicks: Long = 1_728_000L): String {
        val (tick, text) = parse(line)
        if (tick == null) return text
        val day = tick / dayTicks + 1
        val seconds = (tick % dayTicks) / TICKS_PER_SECOND
        val scaled = seconds * 86_400L / (dayTicks / TICKS_PER_SECOND)
        return "Day $day %02d:%02d  %s".format(scaled / 3600, scaled % 3600 / 60, text)
    }
}
