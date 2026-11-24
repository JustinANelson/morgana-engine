package com.morgana.persistence

import com.morgana.domain.game.ScheduleCalendar
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.game.ScheduleRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The full [ScheduleCalendar]: any IANA time zone, with daylight saving, from the JVM's time-zone database. The shared domain cannot
 * carry that database (the browser client is compiled from it), so the server installs this on `WorldSimulation.scheduleCalendar`.
 *
 * Wall-clock rules: a weekly event at 10:00 `America/New_York` stays at 10:00 across a daylight-saving change; a local time that does
 * not exist that day (spring forward) moves on by the gap instead of being skipped, and one that happens twice (fall back) happens
 * once, at the first. For `UTC` it must agree exactly with `UtcScheduleCalendar`; `JavaTimeScheduleCalendarTest` checks that.
 * (A change to the time-zone database in a newer JVM can move future occurrences; a recorded replay stores the wall-clock times it was
 * fed, not these results, so it is unaffected until it is replayed on a differently updated machine.)
 */
object JavaTimeScheduleCalendar : ScheduleCalendar {
    private val LOCAL_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm z", Locale.ENGLISH)

    private fun zoneOf(def: ScheduleDefinition): ZoneId? = runCatching { ZoneId.of(def.timezone) }.getOrNull()

    /** [text] as epoch milliseconds, read in [zone] when it carries no offset; null if blank or unreadable. */
    private fun parseInstant(text: String, zone: ZoneId): Long? {
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

    override fun problems(def: ScheduleDefinition): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val zone = zoneOf(def)
        if (zone == null) {
            out += "timezone" to "'${def.timezone}' is not a time-zone id (try UTC or America/New_York)"
        } else {
            val needsAt = def.kind == ScheduleKind.ONCE || (def.kind == ScheduleKind.INTERVAL && def.at.isNotBlank())
            if (needsAt && parseInstant(def.at, zone) == null) out += "at" to "must be an ISO date-time such as 2026-12-05T10:00"
        }
        return out
    }

    override fun occurrences(def: ScheduleDefinition, afterMillis: Long, upToMillis: Long): List<Long> {
        if (upToMillis <= afterMillis || ScheduleRules.structureProblems(def).isNotEmpty() || problems(def).isNotEmpty()) return emptyList()
        val zone = zoneOf(def) ?: return emptyList()
        return when (def.kind) {
            ScheduleKind.ONCE -> listOfNotNull(parseInstant(def.at, zone)).filter { it in (afterMillis + 1)..upToMillis }
            ScheduleKind.INTERVAL -> intervalOccurrences(def, zone, afterMillis, upToMillis)
            ScheduleKind.DAILY, ScheduleKind.WEEKLY -> calendarOccurrences(def, zone, afterMillis, upToMillis)
        }
    }

    private fun intervalOccurrences(def: ScheduleDefinition, zone: ZoneId, after: Long, upTo: Long): List<Long> {
        val interval = def.intervalSeconds * 1000L
        val anchor = if (def.at.isBlank()) 0L else parseInstant(def.at, zone) ?: return emptyList()
        // Smallest k with anchor + k * interval > after, and k >= 0 (an interval schedule starts at its anchor).
        val first = if (after < anchor) 0L else Math.floorDiv(after - anchor, interval) + 1
        val out = ArrayList<Long>()
        var k = first
        while (out.size < ScheduleRules.MAX_OCCURRENCES) {
            val t = anchor + k * interval
            if (t > upTo) break
            out += t
            k++
        }
        return out
    }

    private fun calendarOccurrences(def: ScheduleDefinition, zone: ZoneId, after: Long, upTo: Long): List<Long> {
        val time = LocalTime.ofNanoOfDay((ScheduleRules.parseTimeOfDayMillis(def.time) ?: return emptyList()) * 1_000_000L)
        val weekday = if (def.kind == ScheduleKind.WEEKLY) ScheduleRules.parseDayOfWeekIndex(def.dayOfWeek)?.let { DayOfWeek.of(it + 1) } else null
        // A local date can map to an instant a day either side of its UTC date, so scan one day wider each end.
        var date: LocalDate = Instant.ofEpochMilli(after).atZone(zone).toLocalDate().minusDays(1)
        val last: LocalDate = Instant.ofEpochMilli(upTo).atZone(zone).toLocalDate().plusDays(1)
        val out = ArrayList<Long>()
        while (!date.isAfter(last) && out.size < ScheduleRules.MAX_OCCURRENCES) {
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

    override fun describeLocal(def: ScheduleDefinition, millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zoneOf(def) ?: ZoneId.of("UTC")).format(LOCAL_FORMAT)
}
