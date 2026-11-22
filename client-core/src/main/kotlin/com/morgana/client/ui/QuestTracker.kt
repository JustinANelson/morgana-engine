package com.morgana.client.ui

import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestGuide
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import kotlin.math.abs

/**
 * What the on-screen quest tracker shows: the player's active quests, one line per objective with progress, and for
 * objectives whose target is on the current map a [Waypoint] the renderer marks in the world. Pure (no graphics, no
 * client state), so the wording and the choice of waypoint are unit-tested.
 *
 * The words stay genre-neutral: they name the target but never a place or person of a particular game.
 */
object QuestTracker {
    /** Most quests tracked at once, so the panel stays small. */
    const val MAX_QUESTS = 5

    /** Waypoints marked in the world at once. */
    const val MAX_WAYPOINTS = 6

    /** Names the tracker needs to turn ids into words. A missing name shows as `#id`. */
    data class Names(
        val npcs: Map<Int, String> = emptyMap(),
        val items: Map<Int, String> = emptyMap(),
        val resources: Map<Int, String> = emptyMap(),
        val recipes: Map<Int, String> = emptyMap(),
        val maps: Map<Int, String> = emptyMap()
    )

    /** Something of a kind (an NPC definition, a resource definition) standing on a tile of the current map. */
    data class Sighting(val id: Int, val tileX: Int, val tileY: Int)

    /** The slice of the world the tracker looks at: the player's map and tile, what is on it, and the names. */
    data class World(
        val mapId: Int,
        val playerX: Int,
        val playerY: Int,
        val npcs: List<Sighting> = emptyList(),
        val resources: List<Sighting> = emptyList(),
        val names: Names = Names(),
        /** The server's directions to targets on other maps; only those for [mapId] count. */
        val guides: List<QuestGuide> = emptyList()
    )

    /** A tile on the current map to mark for the player. */
    data class Waypoint(val tileX: Int, val tileY: Int, val label: String)

    /**
     * One objective line. [hint] explains why there is no [waypoint] when the target should be findable
     * (for example "not on this map"); it is empty otherwise.
     */
    data class Line(val text: String, val current: Int, val required: Int, val waypoint: Waypoint?, val hint: String = "") {
        val done: Boolean get() = current >= required
    }

    data class Entry(val questId: Int, val name: String, val lines: List<Line>) {
        /** Every objective is met: the quest only needs handing in. */
        val ready: Boolean get() = lines.isNotEmpty() && lines.all { it.done }
    }

    /**
     * The tracked quests: those in progress that [untracked] does not exclude, at most [MAX_QUESTS], in the order the
     * character holds them. A quest whose definition is not known is skipped.
     */
    fun entries(quests: Map<Int, PlayerQuestState>, definitions: Map<Int, QuestDefinition>, world: World, untracked: Set<Int> = emptySet()): List<Entry> =
        quests.values
            .filter { it.status == QuestStatus.IN_PROGRESS && it.questId !in untracked }
            .mapNotNull { state -> definitions[state.questId]?.let { entry(it, state, world) } }
            .take(MAX_QUESTS)

    private fun entry(def: QuestDefinition, state: PlayerQuestState, world: World): Entry =
        Entry(def.id, def.name, def.objectives.mapIndexed { i, obj -> line(obj, state.objectiveCounts.getOrElse(i) { 0 }, world, world.guides.firstOrNull { it.questId == def.id && it.objectiveIndex == i && it.mapId == world.mapId }) })

    private fun line(obj: QuestObjective, count: Int, world: World, guide: QuestGuide? = null): Line {
        val current = count.coerceIn(0, obj.requiredCount)
        val done = current >= obj.requiredCount
        val text = objectiveText(obj, world.names)
        if (done) return Line(text, current, obj.requiredCount, null)
        return when (obj.type) {
            QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC ->
                located(text, current, obj, nearest(world.npcs, obj.targetId, world), world.names.npcs[obj.targetId] ?: text, guide)
            QuestObjectiveType.HARVEST_RESOURCE ->
                located(text, current, obj, nearest(world.resources, obj.targetId, world), world.names.resources[obj.targetId] ?: text, guide)
            QuestObjectiveType.VISIT_MAP ->
                if (obj.targetId == world.mapId) Line(text, current, obj.requiredCount, null)
                else if (guide != null) guided(text, current, obj, guide)
                else Line(text, current, obj.requiredCount, null, "another map")
            QuestObjectiveType.COLLECT_ITEM, QuestObjectiveType.CRAFT_RECIPE -> Line(text, current, obj.requiredCount, null)
        }
    }

    private fun located(text: String, current: Int, obj: QuestObjective, at: Sighting?, label: String, guide: QuestGuide?): Line =
        if (at != null) Line(text, current, obj.requiredCount, Waypoint(at.tileX, at.tileY, label))
        else if (guide != null) guided(text, current, obj, guide)
        else Line(text, current, obj.requiredCount, null, "not on this map")

    /** An objective on another map: the marker is the tile that leads there, and the hint names the way. */
    private fun guided(text: String, current: Int, obj: QuestObjective, guide: QuestGuide): Line {
        val via = if (guide.viaMapName == guide.targetMapName) "" else " via ${guide.viaMapName}"
        return Line(text, current, obj.requiredCount, Waypoint(guide.tileX, guide.tileY, "To ${guide.targetMapName}"), "in ${guide.targetMapName}$via")
    }

    /** The sighting of [id] closest to the player (straight-line steps, ties broken by tile order so the pick is stable). */
    fun nearest(sightings: List<Sighting>, id: Int, world: World): Sighting? =
        sightings.filter { it.id == id }
            .minWithOrNull(compareBy<Sighting>({ abs(it.tileX - world.playerX) + abs(it.tileY - world.playerY) }, { it.tileY }, { it.tileX }))

    /** The objective in words: the author's description when there is one, else a generic phrase naming the target. */
    fun objectiveText(obj: QuestObjective, names: Names): String {
        if (obj.description.isNotBlank()) return obj.description.trim()
        fun name(map: Map<Int, String>) = map[obj.targetId] ?: "#${obj.targetId}"
        return when (obj.type) {
            QuestObjectiveType.KILL_NPC -> "Defeat ${name(names.npcs)}"
            QuestObjectiveType.COLLECT_ITEM -> "Collect ${name(names.items)}"
            QuestObjectiveType.TALK_TO_NPC -> "Talk to ${name(names.npcs)}"
            QuestObjectiveType.HARVEST_RESOURCE -> "Gather ${name(names.resources)}"
            QuestObjectiveType.CRAFT_RECIPE -> "Craft ${name(names.recipes)}"
            QuestObjectiveType.VISIT_MAP -> "Visit ${name(names.maps)}"
        }
    }

    /** One display line: the text, `done` or the count when more than one is needed, and the hint. */
    fun lineText(line: Line): String {
        val progress = when {
            line.done -> " (done)"
            line.required > 1 -> " (${line.current}/${line.required})"
            else -> ""
        }
        val hint = if (line.hint.isNotEmpty() && !line.done) " - ${line.hint}" else ""
        return line.text + progress + hint
    }

    /** The waypoints to mark in the world: the first open objective with one from each quest, nearest quests first, capped. */
    fun waypoints(entries: List<Entry>, world: World): List<Waypoint> =
        entries.mapNotNull { e -> e.lines.firstOrNull { !it.done && it.waypoint != null }?.waypoint }
            .distinct()
            .sortedBy { abs(it.tileX - world.playerX) + abs(it.tileY - world.playerY) }
            .take(MAX_WAYPOINTS)
}
