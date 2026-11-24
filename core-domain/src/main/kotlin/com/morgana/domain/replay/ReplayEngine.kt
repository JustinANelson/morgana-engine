package com.morgana.domain.replay

import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.contentMaps

/**
 * Canonical, order-stable fingerprint of everything the simulation mutates. Uses 64-bit FNV-1a over the
 * data-class rendering of the (ordered) state maps, so it needs no I/O or platform crypto.
 */
object StateHasher {
    private const val DEFAULT_STANCE_FIELDS = ", stance=PVE, stanceArmsAtTick=-1, lastCombatTick=-1"

    fun canonicalState(sim: WorldSimulation): String = buildString {
        append("tick=").append(sim.currentTick).append('\n')
        if (sim.worldEpochTicks != 0L) append("epoch=").append(sim.worldEpochTicks).append('\n')
        if (sim.worldSwitches.isNotEmpty()) append("worldSwitches=").append(sim.worldSwitches.toSortedMap()).append('\n')
        if (sim.worldVariables.isNotEmpty()) append("worldVariables=").append(sim.worldVariables.toSortedMap()).append('\n')
        if (sim.worldStringVariables.isNotEmpty()) append("worldStringVariables=").append(sim.worldStringVariables.toSortedMap()).append('\n')
        if (sim.wallClockMillis != 0L) append("wallClock=").append(sim.wallClockMillis).append('\n')
        if (sim.scheduleCursorMillis != 0L) append("scheduleCursor=").append(sim.scheduleCursorMillis).append('\n')
        if (sim.scheduleLastFired.isNotEmpty()) append("scheduleLastFired=").append(sim.scheduleLastFired.toSortedMap()).append('\n')
        if (sim.scheduleOneShots.isNotEmpty()) append("scheduleOneShots=").append(sim.scheduleOneShots.toSortedMap()).append('\n')
        if (sim.worldObjects.isNotEmpty()) append("worldObjects=").append(sim.worldObjects.toSortedMap().mapValues { it.value.toSortedMap() }).append('\n')
        if (sim.pendingEventWaits.isNotEmpty()) append("waits=").append(sim.pendingEventWaits).append('\n')
        if (sim.pendingTextInputs.isNotEmpty()) append("textInputs=").append(sim.pendingTextInputs).append('\n')
        append("entitySeq=").append(sim.entitySeqSnapshot()).append('\n')
        append("rng=").append(sim.rng.stateFingerprint).append('\n')
        // The combat-stance fields are left out while they hold their defaults, so state hashed before they existed (golden replays) is unchanged.
        append("players=").append(sim.players.toString().replace(DEFAULT_STANCE_FIELDS, "")).append('\n')
        append("npcs=").append(sim.mapNpcs).append('\n')
        append("ground=").append(sim.mapGroundItems).append('\n')
        append("projectiles=").append(sim.mapProjectiles).append('\n')
        append("resources=").append(sim.mapResources).append('\n')
        append("doors=").append(sim.unlockedDoors).append('\n')
        append("parties=").append(sim.parties).append('\n')
        if (sim.scriptState.isNotEmpty()) append("scriptState=").append(sim.scriptState).append('\n')
        if (sim.actionStates.isNotEmpty()) append("actionStates=").append(sim.actionStates).append('\n')
        if (sim.instances.isNotEmpty()) append("instances=").append(sim.instances).append('\n')
        if (sim.activeRuns.isNotEmpty()) append("activeRuns=").append(sim.activeRuns).append('\n')
        if (sim.customRunSeeds.isNotEmpty()) append("customRunSeeds=").append(sim.customRunSeeds.toSortedMap()).append('\n')
        if (sim.returnPortals.isNotEmpty()) append("returnPortals=").append(sim.returnPortals).append('\n')
        if (sim.pendingBoonOffers.isNotEmpty()) append("pendingBoonOffers=").append(sim.pendingBoonOffers.toSortedMap()).append('\n')
        append("guilds=").append(sim.guilds).append('\n')
        append("trades=").append(sim.tradeSessions).append('\n')
    }

    fun hash(sim: WorldSimulation): String = fnv1a64(canonicalState(sim))

    /** Fingerprint of a loaded content set, for detecting a replay run against different data. */
    fun contentFingerprint(sim: WorldSimulation): String = fnv1a64(
        listOf(sim.contentMaps(), sim.classes, sim.items, sim.npcs, sim.spells, sim.shops, sim.resources, sim.recipes, sim.quests, sim.events, sim.animations)
            .joinToString("\n") { it.toString() } +
            // Switched-off modules change the rules, so a replay must match them too. Appended only when something
            // is disabled, which keeps the fingerprint of a default server unchanged.
            (if (sim.modules.disabled.isEmpty()) "" else "\nmodules-disabled=" + sim.modules.disabledKeys()) +
            (if (sim.commonEvents.isEmpty()) "" else "\ncommonEvents=${sim.commonEvents}") +
            // Action-combat content, appended only when a game has some, so other games keep their fingerprint.
            (if (sim.actionSkills.isEmpty()) "" else "\nactionSkills=${sim.actionSkills}") +
            (if (sim.weaponSets.isEmpty()) "" else "\nweaponSets=${sim.weaponSets}")
    )

    fun fnv1a64(text: String): String {
        var h = -3750763034362895579L // FNV offset basis 0xcbf29ce484222325
        for (b in text.encodeToByteArray()) {
            h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }
}

/**
 * Wraps a simulation, applying and recording every input. Call [finish] to obtain the replay log.
 * A checkpoint hash is stored every [checkpointEveryTicks] ticks.
 */
class InputRecorder(
    val sim: WorldSimulation,
    private val seed: Long,
    private val checkpointEveryTicks: Int = 20
) {
    private val inputs = mutableListOf<SimInput>()
    private val checkpoints = mutableListOf<ReplayCheckpoint>()
    private val contentFingerprint = StateHasher.contentFingerprint(sim)
    private val gaps = linkedSetOf<String>()

    // The world as it was when recording began: a replay has to start from exactly this, not from where the run ended.
    private val startEpochTicks = sim.worldEpochTicks
    private val startSwitches = sim.worldSwitches.toMap()
    private val startVariables = sim.worldVariables.toMap()
    private val startStringVariables = sim.worldStringVariables.toMap()
    private val startScheduleCursor = sim.scheduleCursorMillis
    private val startScheduleLastFired = sim.scheduleLastFired.toMap()
    private val startScheduleOneShots = sim.scheduleOneShots.toMap()
    private val startWorldObjects = sim.worldObjects.values.flatMap { it.values }

    /** Notes a state change that bypassed the recorder, so the log is flagged as not faithfully replayable. */
    fun markGap(reason: String) {
        gaps += reason
    }

    fun apply(input: SimInput): List<WorldSimulationEvent> {
        val events = input.applyTo(sim)
        inputs += input
        if (input is SimInput.Tick && checkpointEveryTicks > 0 && sim.currentTick % checkpointEveryTicks == 0L) {
            checkpoints += ReplayCheckpoint(sim.currentTick, StateHasher.hash(sim))
        }
        return events
    }

    fun finish(): ReplayLog = ReplayLog(
        seed = seed,
        contentFingerprint = contentFingerprint,
        inputs = inputs.toList(),
        checkpoints = checkpoints.toList(),
        finalTick = sim.currentTick,
        finalHash = StateHasher.hash(sim),
        gaps = gaps.toList(),
        startingEpochTicks = startEpochTicks,
        startingWorldSwitches = startSwitches,
        startingWorldVariables = startVariables,
        startingWorldStringVariables = startStringVariables,
        startingScheduleCursorMillis = startScheduleCursor,
        startingScheduleLastFired = startScheduleLastFired,
        startingScheduleOneShots = startScheduleOneShots,
        startingWorldObjects = startWorldObjects
    )
}

data class ReplayDivergence(val tick: Long, val expected: String, val actual: String)

data class ReplayResult(
    val finalTick: Long,
    val finalHash: String,
    val contentMatches: Boolean,
    val divergences: List<ReplayDivergence>
) {
    val ok: Boolean get() = contentMatches && divergences.isEmpty()
}

object ReplayPlayer {
    /**
     * Applies the log to [sim], which must be freshly built with the log's seed and the same content and
     * contain no players yet. Reports the first checkpoint that differs (if any) plus the final-state check.
     */
    fun replay(sim: WorldSimulation, log: ReplayLog): ReplayResult {
        require(log.formatVersion == ReplayLog.FORMAT_VERSION) { "Unsupported replay format ${log.formatVersion}" }
        sim.worldEpochTicks = log.startingEpochTicks
        sim.worldSwitches.putAll(log.startingWorldSwitches)
        sim.worldVariables.putAll(log.startingWorldVariables)
        sim.worldStringVariables.putAll(log.startingWorldStringVariables)
        sim.scheduleCursorMillis = log.startingScheduleCursorMillis
        sim.scheduleLastFired.putAll(log.startingScheduleLastFired)
        sim.scheduleOneShots.putAll(log.startingScheduleOneShots)
        sim.loadWorldObjects(log.startingWorldObjects)
        val contentMatches = log.contentFingerprint.isEmpty() || log.contentFingerprint == StateHasher.contentFingerprint(sim)
        val expected = log.checkpoints.associateBy { it.tick }
        val divergences = mutableListOf<ReplayDivergence>()
        for (input in log.inputs) {
            input.applyTo(sim)
            if (input is SimInput.Tick) {
                val cp = expected[sim.currentTick]
                if (cp != null) {
                    val actual = StateHasher.hash(sim)
                    if (actual != cp.hash) {
                        divergences += ReplayDivergence(sim.currentTick, cp.hash, actual)
                        break // later hashes are meaningless once state has diverged
                    }
                }
            }
        }
        val finalHash = StateHasher.hash(sim)
        if (divergences.isEmpty() && (sim.currentTick != log.finalTick || finalHash != log.finalHash)) {
            divergences += ReplayDivergence(sim.currentTick, log.finalHash, finalHash)
        }
        return ReplayResult(sim.currentTick, finalHash, contentMatches, divergences)
    }
}
