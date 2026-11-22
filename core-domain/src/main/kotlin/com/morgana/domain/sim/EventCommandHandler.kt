package com.morgana.domain.sim

import com.morgana.domain.event.EventCommand

/**
 * The second seam between the kernel and the systems built on it (ADR 0018): carries out an event command that belongs to a
 * system rather than to the kernel. The command's data class stays in the event model (it is the JSON format the editors and
 * validation share); what it *does* is decided by the handlers in [WorldSimulation.eventCommandHandlers], asked in order until
 * one returns true. A command no handler takes is skipped, which is also what happens to a system's command in a game that has
 * switched that system off.
 *
 * Handlers change the world only through the same recorded paths the kernel uses (`players`, [out]), so replays agree.
 */
interface EventCommandHandler {
    /** Carries out [cmd] for [characterId] and returns true, or returns false when this handler does not own the command. */
    fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean
}

/** Hands [cmd] to the first handler that owns it; returns whether one did. */
internal fun WorldSimulation.runSystemCommand(characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean =
    eventCommandHandlers.any { isOn(it) && it.handle(this, characterId, cmd, out) }
