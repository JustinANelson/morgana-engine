package com.morgana.domain.systems

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.game.ScheduleRules
import com.morgana.domain.game.ScheduledOneShot
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.sim.ScheduleTrigger
import com.morgana.domain.sim.WallClockListener
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.systemOn
import com.morgana.domain.sim.notifySchedule
import com.morgana.domain.sim.tellPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The scheduler: real-world-time events for any game. It turns the `schedules` in `game.json` (and one-offs booked with
 * `WorldSimulation.scheduleOnce`) into advance warnings and starts, announces them in chat, and passes each to the systems and Lua
 * plugins that listen ([com.morgana.domain.sim.ScheduleHandler], `register.schedule`).
 *
 * **Deterministic by construction.** The simulation never reads a clock. The server reports the wall-clock time through the
 * recorded `SimInput.WallClock`, and everything here is a pure function of that time, the definitions and the state the simulation
 * keeps (`scheduleCursorMillis`, `scheduleLastFired`, `scheduleOneShots`), so a replay raises exactly the same triggers.
 *
 * **Never retroactive.** The first time it hears the clock it only notes the time. After that, an occurrence starts only if it is
 * due within its [ScheduleDefinition.lateGraceSeconds]; if the server was down longer, it is skipped rather than fired hours late. A
 * warning is raised only while its occurrence is still ahead, and if several come due in one reading (after a pause) only the one
 * closest to the start is raised.
 */
object SchedulerSystem : WallClockListener, ChatCommandHandler, GameSystem {
    override val key: String = OptionalModule.SCHEDULER.configKey

    override fun onWallClock(sim: WorldSimulation, nowMillis: Long, out: MutableList<WorldSimulationEvent>) {
        val cursor = sim.scheduleCursorMillis
        if (cursor <= 0L) {
            sim.scheduleCursorMillis = nowMillis
            return
        }
        if (nowMillis <= cursor) return

        val due = sim.collectDue(cursor, nowMillis)
        sim.scheduleCursorMillis = nowMillis
        for (item in due) {
            if (item.trigger.phase == SchedulePhase.FIRE) sim.scheduleLastFired[item.trigger.scheduleId] = item.trigger.occurrenceMillis
            deliver(sim, item.def, item.trigger, out)
        }
        // A one-off is finished once its time has passed, whether it started or was too late to start.
        val finished = sim.scheduleOneShots.filterValues { oneShotMillis(it) <= nowMillis }.keys
        for (id in finished) {
            sim.scheduleOneShots.remove(id)
            sim.scheduleLastFired.remove(id)
        }
    }

    /** Announces [trigger] in chat (when the definition has text for it), then tells every handler. */
    private fun deliver(sim: WorldSimulation, def: ScheduleDefinition, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) {
        val template = if (trigger.phase == SchedulePhase.WARNING) def.warningMessage else def.fireMessage
        if (template.isNotBlank()) {
            val text = template
                .replace("{name}", trigger.name)
                .replace("{remaining}", ScheduleRules.describeDuration(trigger.secondsBefore.toLong()))
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "GLOBAL", mapId = 0, senderId = "SYSTEM", senderName = "SYSTEM", targetCharacterId = null,
                    message = text, showOverheadBubble = false, colorHex = def.messageColorHex, notice = "INFO"
                )
            )
        }
        sim.notifySchedule(trigger, out)
    }

    /** What the scheduler raises for (`cursor`, `now`], oldest first: warnings before the start when they share a moment. */
    private fun WorldSimulation.collectDue(cursor: Long, now: Long): List<Due> {
        val found = ArrayList<Due>()
        for (def in game.definition.schedules) if (def.enabled) collect(def, recurring = def.kind != ScheduleKind.ONCE, data = emptyMap(), cursor, now, found)
        for (shot in scheduleOneShots.values) collect(shot.def, recurring = false, data = shot.data, cursor, now, found)
        return found.sortedWith(compareBy<Due> { it.at }.thenBy { if (it.trigger.phase == SchedulePhase.WARNING) 0 else 1 }.thenByDescending { it.trigger.secondsBefore })
    }

    private fun WorldSimulation.collect(
        def: ScheduleDefinition, recurring: Boolean, data: Map<String, String>, cursor: Long, now: Long, into: MutableList<Due>
    ) {
        val grace = def.lateGraceSeconds * 1000L
        val warnings = def.warningsSeconds.sortedDescending()
        val lookahead = (warnings.firstOrNull() ?: 0) * 1000L
        // Starts that are due and not too late, and starts still ahead whose warning time has come. Older occurrences are not looked at.
        val lower = maxOf(cursor, now - grace - 1)
        val lastFired = scheduleLastFired[def.id] ?: 0L
        for (occurrence in ScheduleRules.occurrences(def, lower, now + lookahead)) {
            if (occurrence <= now) {
                if (occurrence > cursor && occurrence > lastFired && now - occurrence <= grace) {
                    into += Due(occurrence, def, trigger(def, SchedulePhase.FIRE, 0, occurrence, recurring, data))
                }
            } else {
                // Every warning whose time has come since the last reading. Normally that is one, since the clock is read every second;
                // after a pause several can be due at once, and then only the latest (the closest to the start) is worth saying.
                val ready = warnings.filter { occurrence - it * 1000L in (cursor + 1)..now }
                ready.minOrNull()?.let { seconds ->
                    into += Due(occurrence - seconds * 1000L, def, trigger(def, SchedulePhase.WARNING, seconds, occurrence, recurring, data))
                }
            }
        }
    }

    private fun trigger(
        def: ScheduleDefinition, phase: SchedulePhase, secondsBefore: Int, occurrence: Long, recurring: Boolean, data: Map<String, String>
    ) = ScheduleTrigger(def.id, def.displayName, phase, secondsBefore, occurrence, recurring, data)

    private class Due(val at: Long, val def: ScheduleDefinition, val trigger: ScheduleTrigger)

    private fun oneShotMillis(shot: ScheduledOneShot): Long = ScheduleRules.parseInstant(shot.def.at, ZoneId.of("UTC")) ?: 0L

    internal fun scheduleOnce(
        sim: WorldSimulation, id: String, atMillis: Long, name: String, warningsSeconds: List<Int>, data: Map<String, String>,
        warningMessage: String, fireMessage: String
    ): Boolean {
        if (!sim.systemOn(key)) return false
        if (sim.game.definition.schedules.any { it.id == id }) return false
        if (sim.wallClockMillis <= 0L || atMillis <= sim.wallClockMillis) return false
        val def = ScheduleDefinition(
            id = id, name = name, kind = ScheduleKind.ONCE, timezone = "UTC", at = Instant.ofEpochMilli(atMillis).toString(),
            warningsSeconds = warningsSeconds.distinct().sortedDescending(), warningMessage = warningMessage, fireMessage = fireMessage
        )
        if (ScheduleRules.problems(def).isNotEmpty()) return false
        sim.scheduleOneShots[id] = ScheduledOneShot(def, data.toSortedMap())
        return true
    }

    // ---- /schedule --------------------------------------------------------------------------------------------------------

    private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm z", Locale.ENGLISH)

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "schedule") return false
        when (parts.getOrNull(1)?.lowercase()) {
            null, "", "list", "next" -> list(sim, player, out)
            "fire" -> fire(sim, player, parts.getOrNull(2), out)
            else -> out.add(sim.tellPlayer(player, "Usage: /schedule [list]  ·  /schedule fire <id> (staff)", "WARN"))
        }
        return true
    }

    private fun list(sim: WorldSimulation, player: CharacterState, out: MutableList<WorldSimulationEvent>) {
        val now = sim.wallClockMillis
        val defs = sim.game.definition.schedules.filter { it.enabled } + sim.scheduleOneShots.values.map { it.def }
        if (defs.isEmpty()) {
            out.add(sim.tellPlayer(player, "Nothing is scheduled.", "INFO"))
            return
        }
        if (now <= 0L) {
            out.add(sim.tellPlayer(player, "The schedule clock has not started yet.", "INFO"))
            return
        }
        out.add(sim.tellPlayer(player, "Upcoming:", "INFO"))
        for (def in defs) {
            val next = ScheduleRules.next(def, now)
            val line = if (next == null) "${def.displayName}: nothing coming up" else {
                val zone = ScheduleRules.zoneOf(def) ?: ZoneId.of("UTC")
                "${def.displayName}: in ${ScheduleRules.describeDuration((next - now) / 1000)} (${Instant.ofEpochMilli(next).atZone(zone).format(whenFormat)})"
            }
            out.add(sim.tellPlayer(player, line, "INFO"))
        }
    }

    private fun fire(sim: WorldSimulation, player: CharacterState, id: String?, out: MutableList<WorldSimulationEvent>) {
        if (!player.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) return
        val def = sim.game.definition.schedules.firstOrNull { it.id == id } ?: sim.scheduleOneShots[id]?.def
        if (id == null || def == null) {
            out.add(sim.tellPlayer(player, "Usage: /schedule fire <id>. Known ids: ${sim.game.definition.schedules.joinToString { it.id }}", "WARN"))
            return
        }
        // A test fire: it does not touch the cursor or the last-fired record, so the real occurrence still happens.
        val shotData = sim.scheduleOneShots[def.id]?.data.orEmpty()
        deliver(sim, def, trigger(def, SchedulePhase.FIRE, 0, sim.wallClockMillis, def.kind != ScheduleKind.ONCE, shotData), out)
    }
}
