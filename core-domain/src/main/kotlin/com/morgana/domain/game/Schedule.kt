package com.morgana.domain.game

import kotlinx.serialization.Serializable

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
 * Times use a wall clock, so a weekly event at 10:00 `America/New_York` stays at 10:00 across daylight-saving changes. Working out
 * those times needs a time-zone database, which the shared domain does not carry: a [ScheduleCalendar] does it, and the server
 * provides the full one. Without it only `UTC` schedules work (see [UtcScheduleCalendar]).
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
 * Turns a [ScheduleDefinition] into points in real time. This is a port: the shared domain stays free of JVM-only APIs (the browser
 * client is compiled from it), so the time-zone database lives behind this interface. [UtcScheduleCalendar] is the pure default and
 * handles `UTC` only; the server installs a full one (`JavaTimeScheduleCalendar` in `:server-persistence`) on `WorldSimulation.scheduleCalendar`.
 * Both must agree for UTC, which a test checks.
 */
interface ScheduleCalendar {
    /** Problems with [def] that need time-zone or date knowledge (an unknown zone, an unreadable date-time), as (field, message). Structure is [ScheduleRules.structureProblems]. */
    fun problems(def: ScheduleDefinition): List<Pair<String, String>>

    /**
     * Every occurrence of [def] in the half-open range (`afterMillis`, `upToMillis`], oldest first, as epoch milliseconds. A definition
     * this calendar cannot read has none. At most [ScheduleRules.MAX_OCCURRENCES] are returned.
     */
    fun occurrences(def: ScheduleDefinition, afterMillis: Long, upToMillis: Long): List<Long>

    /** [millis] as local time in [def]'s zone for people to read, such as `Sat 28 Nov 2026 10:00 EST`. */
    fun describeLocal(def: ScheduleDefinition, millis: Long): String

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
}

/** Calendar-independent parts of [ScheduleDefinition]: its shape, and the words used to announce it. Pure Kotlin. */
object ScheduleRules {
    /** Most occurrences one query returns (a guard against an interval of a few seconds over a long range). */
    const val MAX_OCCURRENCES = 10_000

    private val TIME = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val ZONE_SYNTAX = Regex("[A-Za-z0-9_+:/-]{1,64}")
    private val COLOR = Regex("#[0-9a-fA-F]{6}")

    val DAYS_OF_WEEK: List<String> = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

    /** `HH:mm[:ss]` as milliseconds after midnight, or null if it is not a time. */
    fun parseTimeOfDayMillis(text: String): Long? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val s = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (h !in 0..23 || min !in 0..59 || s !in 0..59) return null
        return ((h * 60L + min) * 60L + s) * 1000L
    }

    /** 0 for Monday to 6 for Sunday, or null if [text] is not a day of the week. */
    fun parseDayOfWeekIndex(text: String): Int? = DAYS_OF_WEEK.indexOf(text.trim().uppercase()).takeIf { it >= 0 }

    /** Why [def] is malformed regardless of any calendar, as (field, message); empty when its shape is valid. */
    fun structureProblems(def: ScheduleDefinition): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!ScheduleDefinition.ID_PATTERN.matches(def.id)) out += "id" to "must be 1 to 40 characters: lower-case letters, digits, _ or -"
        if (def.name.length > 60) out += "name" to "must be at most 60 characters"
        if (!ZONE_SYNTAX.matches(def.timezone)) out += "timezone" to "'${def.timezone}' is not a time-zone id (try UTC or America/New_York)"
        when (def.kind) {
            ScheduleKind.DAILY -> if (parseTimeOfDayMillis(def.time) == null) out += "time" to "must be a time such as 10:00"
            ScheduleKind.WEEKLY -> {
                if (parseTimeOfDayMillis(def.time) == null) out += "time" to "must be a time such as 10:00"
                if (parseDayOfWeekIndex(def.dayOfWeek) == null) out += "dayOfWeek" to "must be MONDAY to SUNDAY"
            }
            ScheduleKind.INTERVAL ->
                if (def.intervalSeconds !in 1..ScheduleDefinition.MAX_INTERVAL_SECONDS) out += "intervalSeconds" to "must be 1 to ${ScheduleDefinition.MAX_INTERVAL_SECONDS}"
            ScheduleKind.ONCE -> if (def.at.isBlank()) out += "at" to "is required: an ISO date-time such as 2026-12-05T10:00"
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
        if (!COLOR.matches(def.messageColorHex)) out += "messageColorHex" to "must be #rrggbb"
        return out
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

/**
 * The calendar the shared domain carries: pure Kotlin, `UTC` only (`UTC`, `Z`, `GMT` or `Etc/UTC`), with no daylight saving and no time-zone
 * database. It is the default on a new simulation, so schedules in UTC work everywhere, tests included; a schedule in another zone has
 * no occurrences until the server installs a full calendar, and [problems] says so.
 */
object UtcScheduleCalendar : ScheduleCalendar {
    private const val DAY_MS = 86_400_000L
    private val UTC_NAMES = setOf("UTC", "Z", "GMT", "ETC/UTC", "ETC/GMT")
    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?(Z|[+-]\\d{2}:\\d{2})?")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private fun isUtc(zone: String) = zone.trim().uppercase() in UTC_NAMES

    /** Days since 1970-01-01 of a calendar date (proleptic Gregorian; Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097 + doe - 719_468
    }

    /** The calendar date (year, month, day) of a day count since 1970-01-01. */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = (yoe + era * 400).toInt() + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    /** Monday = 0 for a day count since 1970-01-01 (a Thursday). */
    private fun weekdayIndex(days: Long): Int = Math.floorMod(days + 3, 7L).toInt()

    /** Epoch milliseconds as an ISO UTC time to the second (`2026-12-05T15:00:00Z`), the form [parseIso] reads. Fractions of a second are dropped. */
    fun formatIso(millis: Long): String {
        val seconds = Math.floorDiv(millis, 1000L)
        val days = Math.floorDiv(seconds, 86_400L)
        val secondOfDay = Math.floorMod(seconds, 86_400L).toInt()
        val (y, m, d) = civilFromDays(days)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${y.toString().padStart(4, '0')}-${two(m)}-${two(d)}T${two(secondOfDay / 3_600)}:${two(secondOfDay % 3_600 / 60)}:${two(secondOfDay % 60)}Z"
    }

    /** An ISO date-time as epoch milliseconds, read as UTC when it carries no offset; null if blank or unreadable. */
    fun parseIso(text: String): Long? {
        val m = ISO.matchEntire(text.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].ifEmpty { "0" }.toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val days = daysFromCivil(year, month, day)
        val round = civilFromDays(days)
        if (round.second != month || round.third != day) return null // 31 February and the like
        var millis = (days * 86_400L + hour * 3_600L + minute * 60L + second) * 1000L
        val zone = m.groupValues[7]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val offsetSeconds = (zone.substring(1, 3).toInt() * 3_600 + zone.substring(4, 6).toInt() * 60) * sign
            millis -= offsetSeconds * 1000L
        }
        return millis
    }

    override fun problems(def: ScheduleDefinition): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!isUtc(def.timezone)) out += "timezone" to "'${def.timezone}' needs the server's calendar; this one only knows UTC"
        val needsAt = def.kind == ScheduleKind.ONCE || (def.kind == ScheduleKind.INTERVAL && def.at.isNotBlank())
        if (needsAt && parseIso(def.at) == null) out += "at" to "must be an ISO date-time such as 2026-12-05T10:00"
        return out
    }

    override fun occurrences(def: ScheduleDefinition, afterMillis: Long, upToMillis: Long): List<Long> {
        if (upToMillis <= afterMillis || ScheduleRules.structureProblems(def).isNotEmpty() || problems(def).isNotEmpty()) return emptyList()
        return when (def.kind) {
            ScheduleKind.ONCE -> listOfNotNull(parseIso(def.at)).filter { it in (afterMillis + 1)..upToMillis }
            ScheduleKind.INTERVAL -> interval(def, afterMillis, upToMillis)
            ScheduleKind.DAILY, ScheduleKind.WEEKLY -> calendar(def, afterMillis, upToMillis)
        }
    }

    private fun interval(def: ScheduleDefinition, after: Long, upTo: Long): List<Long> {
        val step = def.intervalSeconds * 1000L
        val anchor = if (def.at.isBlank()) 0L else parseIso(def.at) ?: return emptyList()
        // Smallest k with anchor + k * step > after, and k >= 0 (an interval schedule starts at its anchor).
        var k = if (after < anchor) 0L else Math.floorDiv(after - anchor, step) + 1
        val out = ArrayList<Long>()
        while (out.size < ScheduleRules.MAX_OCCURRENCES) {
            val t = anchor + k * step
            if (t > upTo) break
            out += t
            k++
        }
        return out
    }

    private fun calendar(def: ScheduleDefinition, after: Long, upTo: Long): List<Long> {
        val timeOfDay = ScheduleRules.parseTimeOfDayMillis(def.time) ?: return emptyList()
        val weekday = if (def.kind == ScheduleKind.WEEKLY) ScheduleRules.parseDayOfWeekIndex(def.dayOfWeek) else null
        val out = ArrayList<Long>()
        var day = Math.floorDiv(after, DAY_MS) - 1
        val last = Math.floorDiv(upTo, DAY_MS) + 1
        while (day <= last && out.size < ScheduleRules.MAX_OCCURRENCES) {
            if (weekday == null || weekdayIndex(day) == weekday) {
                val t = day * DAY_MS + timeOfDay
                if (t in (after + 1)..upTo) out += t
            }
            day++
        }
        return out
    }

    override fun describeLocal(def: ScheduleDefinition, millis: Long): String {
        val days = Math.floorDiv(millis, DAY_MS)
        val minutes = (Math.floorMod(millis, DAY_MS) / 60_000).toInt()
        val (y, m, d) = civilFromDays(days)
        return "${WEEKDAYS[weekdayIndex(days)]} $d ${MONTHS[m - 1]} $y ${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')} UTC"
    }
}
