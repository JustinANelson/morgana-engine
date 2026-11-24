package com.morgana.domain.game

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** How a [ScheduleDefinition] repeats. */
@Serializable
enum class ScheduleKind {
    /** Every day at [ScheduleDefinition.time] in the schedule's time zone. */
    DAILY,

    /** Every week on [ScheduleDefinition.dayOfWeek] at [ScheduleDefinition.time] in the schedule's time zone. */
    WEEKLY,

    /** Every [ScheduleDefinition.intervalSeconds] seconds, counted from [ScheduleDefinition.at] (or the Unix epoch). */
    INTERVAL,

    /** Once, at [ScheduleDefinition.at]. */
    ONCE
}

/**
 * Something that happens at a real-world (wall-clock) time, declared in `game.json` under `schedules`: a weekly event, a
 * daily reset, a one-off launch. The scheduler (`scheduler` system) turns each occurrence into advance warnings and a start,
 * announces them in chat, and tells Lua plugins and other systems. Nothing here needs engine code: a game picks the days, times,
 * time zone and wording.
 *
 * Times use a wall clock, so a weekly event at 10:00 `America/New_York` stays at 10:00 across daylight-saving changes.
 */
@Serializable
data class ScheduleDefinition(
    /** Lower-case letters, digits, `_` and `-`; what Lua hooks and `/schedule` call it. Unique. */
    val id: String,
    /** The name shown in announcements and `/schedule`. Defaults to [id] when blank. */
    val name: String = "",
    val kind: ScheduleKind = ScheduleKind.WEEKLY,
    /** An IANA time-zone id such as `America/New_York` or `UTC`. */
    val timezone: String = "UTC",
    /** For [ScheduleKind.WEEKLY]: `MONDAY` to `SUNDAY`. */
    val dayOfWeek: String = "SATURDAY",
    /** For [ScheduleKind.DAILY] and [ScheduleKind.WEEKLY]: local time `HH:mm` or `HH:mm:ss`. */
    val time: String = "00:00",
    /** For [ScheduleKind.INTERVAL]: seconds between occurrences. */
    val intervalSeconds: Long = 0,
    /**
     * For [ScheduleKind.ONCE] (required) and [ScheduleKind.INTERVAL] (optional anchor): an ISO date-time, either local to
     * [timezone] (`2026-12-05T10:00`) or with an offset (`2026-12-05T10:00:00-05:00`).
     */
    val at: String = "",
    /** Seconds before each occurrence at which a warning is raised (for example `[3600, 900, 60]`). */
    val warningsSeconds: List<Int> = emptyList(),
    /** Chat text for a warning. Placeholders: `{name}`, `{remaining}` (such as "15 minutes"). Blank sends nothing. */
    val warningMessage: String = DEFAULT_WARNING_MESSAGE,
    /** Chat text when the occurrence starts. Placeholder: `{name}`. Blank sends nothing. */
    val fireMessage: String = DEFAULT_FIRE_MESSAGE,
    /** `#rrggbb` colour of the announcements. */
    val messageColorHex: String = DEFAULT_MESSAGE_COLOR,
    /**
     * How late an occurrence may still start, in seconds. If the server was down or paused longer than this when the time came,
     * the occurrence is skipped instead of firing hours late.
     */
    val lateGraceSeconds: Int = DEFAULT_LATE_GRACE_SECONDS,
    /** Easy kill-switch for one schedule. */
    val enabled: Boolean = true
) {
    /** [name], or [id] when no name was given. */
    val displayName: String get() = name.ifBlank { id }

    companion object {
        const val DEFAULT_WARNING_MESSAGE = "{name} begins in {remaining}."
        const val DEFAULT_FIRE_MESSAGE = "{name} begins now."
        const val DEFAULT_MESSAGE_COLOR = "#FACC15"
        const val DEFAULT_LATE_GRACE_SECONDS = 120
        const val MAX_SCHEDULES = 64
        const val MAX_WARNINGS = 8
        const val MAX_WARNING_SECONDS = 2_592_000
        const val MAX_LATE_GRACE_SECONDS = 604_800
        const val MAX_INTERVAL_SECONDS = 31_536_000L
        val ID_PATTERN = Regex("[a-z0-9_-]{1,40}")
    }
}

/**
 * A one-off schedule made while the game runs (a siege window a guild booked, a vote that closes in a day): the same shape as a
 * `game.json` schedule, always [ScheduleKind.ONCE], plus [data] that comes back on every trigger. Saved with the server state.
 */
@Serializable
data class ScheduledOneShot(val def: ScheduleDefinition, val data: Map<String, String> = emptyMap())

/**
 * Pure wall-clock maths for [ScheduleDefinition]s: when each occurs, and how long to say it will be. No clock is read here, so the
 * results are the same on every machine and in a replay.
 */
object ScheduleRules {
    /** Most occurrences one query returns (a guard against an interval of a few seconds over a long range). */
    const val MAX_OCCURRENCES = 10_000

    fun zoneOf(def: ScheduleDefinition): ZoneId? = runCatching { ZoneId.of(def.timezone) }.getOrNull()

    fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseDayOfWeek(text: String): DayOfWeek? = runCatching { DayOfWeek.valueOf(text.trim().uppercase()) }.getOrNull()

    /** [ScheduleDefinition.at] as epoch milliseconds, read in [zone] when it carries no offset; null if blank or unreadable. */
    fun parseInstant(text: String, zone: ZoneId): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Why [def] cannot work, as short messages keyed by the `game.json` field; empty when it is valid. */
    fun problems(def: ScheduleDefinition): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!ScheduleDefinition.ID_PATTERN.matches(def.id)) out += "id" to "must be 1 to 40 characters: lower-case letters, digits, _ or -"
        if (def.name.length > 60) out += "name" to "must be at most 60 characters"
        val zone = zoneOf(def)
        if (zone == null) out += "timezone" to "'${def.timezone}' is not a time-zone id (try UTC or America/New_York)"
        when (def.kind) {
            ScheduleKind.DAILY -> if (parseTime(def.time) == null) out += "time" to "must be a time such as 10:00"
            ScheduleKind.WEEKLY -> {
                if (parseTime(def.time) == null) out += "time" to "must be a time such as 10:00"
                if (parseDayOfWeek(def.dayOfWeek) == null) out += "dayOfWeek" to "must be MONDAY to SUNDAY"
            }
            ScheduleKind.INTERVAL -> {
                if (def.intervalSeconds !in 1..ScheduleDefinition.MAX_INTERVAL_SECONDS) {
                    out += "intervalSeconds" to "must be 1 to ${ScheduleDefinition.MAX_INTERVAL_SECONDS}"
                }
                if (def.at.isNotBlank() && zone != null && parseInstant(def.at, zone) == null) out += "at" to "must be an ISO date-time such as 2026-12-05T10:00"
            }
            ScheduleKind.ONCE ->
                if (zone != null && parseInstant(def.at, zone) == null) out += "at" to "is required: an ISO date-time such as 2026-12-05T10:00"
        }
        if (def.warningsSeconds.size > ScheduleDefinition.MAX_WARNINGS) out += "warningsSeconds" to "at most ${ScheduleDefinition.MAX_WARNINGS} warnings"
        if (def.warningsSeconds.any { it !in 1..ScheduleDefinition.MAX_WARNING_SECONDS }) {
            out += "warningsSeconds" to "each must be 1 to ${ScheduleDefinition.MAX_WARNING_SECONDS} seconds"
        }
        if (def.warningsSeconds.toSet().size != def.warningsSeconds.size) out += "warningsSeconds" to "warnings must be different from each other"
        if (def.lateGraceSeconds !in 0..ScheduleDefinition.MAX_LATE_GRACE_SECONDS) {
            out += "lateGraceSeconds" to "must be 0 to ${ScheduleDefinition.MAX_LATE_GRACE_SECONDS}"
        }
        if (def.warningMessage.length > 200) out += "warningMessage" to "must be at most 200 characters"
        if (def.fireMessage.length > 200) out += "fireMessage" to "must be at most 200 characters"
        if (!Regex("#[0-9a-fA-F]{6}").matches(def.messageColorHex)) out += "messageColorHex" to "must be #rrggbb"
        return out
    }

    /**
     * Every occurrence of [def] in the half-open range (`afterMillis`, `upToMillis`], oldest first, as epoch milliseconds. An invalid
     * definition has none. At most [MAX_OCCURRENCES] are returned.
     */
    fun occurrences(def: ScheduleDefinition, afterMillis: Long, upToMillis: Long): List<Long> {
        if (upToMillis <= afterMillis || problems(def).isNotEmpty()) return emptyList()
        val zone = zoneOf(def) ?: return emptyList()
        return when (def.kind) {
            ScheduleKind.ONCE -> listOfNotNull(parseInstant(def.at, zone)).filter { it in (afterMillis + 1)..upToMillis }
            ScheduleKind.INTERVAL -> intervalOccurrences(def, zone, afterMillis, upToMillis)
            ScheduleKind.DAILY, ScheduleKind.WEEKLY -> calendarOccurrences(def, zone, afterMillis, upToMillis)
        }
    }

    /** The first occurrence after [afterMillis] within the next [horizonDays] days, or null. */
    fun next(def: ScheduleDefinition, afterMillis: Long, horizonDays: Int = 400): Long? {
        var from = afterMillis
        val step = 40L * 24 * 3_600_000
        val limit = afterMillis + horizonDays * 24L * 3_600_000
        while (from < limit) {
            val to = minOf(from + step, limit)
            occurrences(def, from, to).firstOrNull()?.let { return it }
            from = to
        }
        return null
    }

    private fun intervalOccurrences(def: ScheduleDefinition, zone: ZoneId, after: Long, upTo: Long): List<Long> {
        val interval = def.intervalSeconds * 1000L
        val anchor = if (def.at.isBlank()) 0L else parseInstant(def.at, zone) ?: return emptyList()
        // Smallest k with anchor + k * interval > after, and k >= 0 (an interval schedule starts at its anchor).
        val first = if (after < anchor) 0L else Math.floorDiv(after - anchor, interval) + 1
        val out = ArrayList<Long>()
        var k = first
        while (out.size < MAX_OCCURRENCES) {
            val t = anchor + k * interval
            if (t > upTo) break
            out += t
            k++
        }
        return out
    }

    private fun calendarOccurrences(def: ScheduleDefinition, zone: ZoneId, after: Long, upTo: Long): List<Long> {
        val time = parseTime(def.time) ?: return emptyList()
        val weekday = if (def.kind == ScheduleKind.WEEKLY) parseDayOfWeek(def.dayOfWeek) else null
        // A local date can map to an instant a day either side of its UTC date, so scan one day wider each end.
        var date: LocalDate = Instant.ofEpochMilli(after).atZone(zone).toLocalDate().minusDays(1)
        val last: LocalDate = Instant.ofEpochMilli(upTo).atZone(zone).toLocalDate().plusDays(1)
        val out = ArrayList<Long>()
        while (!date.isAfter(last) && out.size < MAX_OCCURRENCES) {
            if (weekday == null || date.dayOfWeek == weekday) {
                // ZonedDateTime resolves a time that does not exist (spring forward) by moving it on, and one that happens twice
                // (fall back) to the first, so an occurrence is never skipped or doubled.
                val t = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
                if (t in (after + 1)..upTo) out += t
            }
            date = date.plusDays(1)
        }
        return out.sorted()
    }

    /** "1 hour", "15 minutes", "1 minute 30 seconds", "2 days 3 hours": [seconds] in words, for announcements. */
    fun describeDuration(seconds: Long): String {
        if (seconds <= 0) return "0 seconds"
        val d = seconds / 86_400
        val h = seconds % 86_400 / 3_600
        val m = seconds % 3_600 / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        fun add(n: Long, unit: String) { if (n > 0) parts += "$n $unit${if (n == 1L) "" else "s"}" }
        add(d, "day"); add(h, "hour"); add(m, "minute"); add(s, "second")
        return parts.take(2).joinToString(" ")
    }
}
