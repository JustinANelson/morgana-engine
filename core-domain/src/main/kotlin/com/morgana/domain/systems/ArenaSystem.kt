package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.MapInstance
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.interruptActivities
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.ArenaDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import kotlin.math.abs
import kotlin.math.pow

/** A player waiting in the arena queue for a match of [size] a side. */
data class ArenaTicket(val characterId: String, val size: Int, val queuedTick: Long)

/**
 * One arena fight. [teamA] and [teamB] are character ids; [eliminated] are those who fell or left. The fight is on from
 * [startTick] (before it nobody can be hurt) and must end by [endTick]. [returns] is where each player goes back to.
 */
data class ArenaMatch(
    val id: Int,
    val mapId: Int,
    val size: Int,
    val teamA: List<String>,
    val teamB: List<String>,
    val startTick: Long,
    val endTick: Long,
    val returns: Map<String, WorldPosition>,
    val eliminated: Set<String> = emptySet()
) {
    fun teamOf(characterId: String): Int = when (characterId) { in teamA -> 0; in teamB -> 1; else -> -1 }

    /** Whether [attacker] may hurt [defender] now: opposite teams, both still in the fight, and the countdown over. */
    fun canFight(attackerId: String, defenderId: String, tick: Long): Boolean {
        if (tick < startTick) return false
        val a = teamOf(attackerId)
        val d = teamOf(defenderId)
        return a >= 0 && d >= 0 && a != d && attackerId !in eliminated && defenderId !in eliminated
    }

    fun alive(team: Int): List<String> = (if (team == 0) teamA else teamB).filter { it !in eliminated }
}

/**
 * The arena as a system: pairs queued players each tick, decides who may fight whom and where a death leads inside a match,
 * owns `/arena`, and carries out the `ArenaQueue` event command (the same as the chat command, offered from an NPC).
 */
object ArenaSystem : TickParticipant, CombatArbiter, ChatCommandHandler, EventCommandHandler, GameSystem {
    override val key: String = OptionalModule.ARENA.configKey
    override val stage = TickStage.AFTER_EVENTS

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) = sim.tickArena(out)

    override fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState): Boolean? =
        sim.arenaMatchOf(attacker.id)?.canFight(attacker.id, defender.id, sim.currentTick)

    override fun deathOutcome(sim: WorldSimulation, player: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState? =
        sim.arenaMatchOf(player.id)?.let { sim.eliminateFromArena(player, it, out) }

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "arena") return false
        sim.arenaCommand(player, parts, out)
        return true
    }

    override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
        if (cmd !is EventCommand.ArenaQueue) return false
        val player = sim.players[characterId] ?: return true
        sim.arenaCommand(player, listOfNotNull("arena", cmd.action.command, cmd.size.takeIf { it > 0 }?.toString()), out)
        return true
    }
}

internal fun WorldSimulation.arenaMatchOf(characterId: String): ArenaMatch? =
    arenaMatches.values.firstOrNull { characterId in it.teamA || characterId in it.teamB }?.takeIf { characterId !in it.eliminated }

private fun WorldSimulation.arenaDef(): ArenaDefinition? = game.definition.arena?.takeIf { it.enabled }

/** `/arena join [size]`, `/arena leave`, `/arena rating`. */
internal fun WorldSimulation.arenaCommand(player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>) {
    val def = arenaDef() ?: run { out.add(tellPlayer(player, "There is no arena here.", "WARN")); return }
    when (parts.getOrNull(1)?.lowercase()) {
        "join" -> {
            val size = parts.getOrNull(2)?.toIntOrNull() ?: def.teamSizes.first()
            when {
                size !in def.teamSizes -> out.add(tellPlayer(player, "Arena team sizes: ${def.teamSizes.joinToString(", ")}.", "WARN"))
                arenaMatchOf(player.id) != null -> out.add(tellPlayer(player, "You are already in a fight.", "WARN"))
                player.id in arenaQueue -> out.add(tellPlayer(player, "You are already queued; /arena leave to cancel.", "WARN"))
                player.isGhost || !player.isAlive(game.lifeVitalId) -> out.add(tellPlayer(player, "You cannot queue while dead.", "WARN"))
                MapInstances.isInstanceId(player.position.mapId) -> out.add(tellPlayer(player, "Leave this place before you queue for the arena.", "WARN"))
                else -> {
                    arenaQueue[player.id] = ArenaTicket(player.id, size, currentTick)
                    out.add(tellPlayer(player, "Queued for the arena, ${size}v$size. /arena leave cancels.", "INFO"))
                }
            }
        }
        "leave" -> {
            if (arenaQueue.remove(player.id) != null) out.add(tellPlayer(player, "You left the arena queue.", "INFO"))
            else out.add(tellPlayer(player, "You are not in the arena queue.", "WARN"))
        }
        "rating" -> out.add(tellPlayer(player, "Arena rating ${ratingOf(player, def)}: ${player.arenaWins} wins, ${player.arenaLosses} losses.", "INFO"))
        "top" -> {
            val ranked = players.values.filter { it.arenaWins + it.arenaLosses > 0 }.sortedWith(compareByDescending<CharacterState> { ratingOf(it, def) }.thenBy { it.name }).take(5)
            if (ranked.isEmpty()) out.add(tellPlayer(player, "Nobody online has fought in the arena yet.", "INFO"))
            else {
                out.add(tellPlayer(player, "Arena standings (players online):", "INFO"))
                ranked.forEachIndexed { i, p -> out.add(tellPlayer(player, "${i + 1}. ${p.name} ${ratingOf(p, def)} (${p.arenaWins}W ${p.arenaLosses}L)", "INFO")) }
            }
        }
        else -> out.add(tellPlayer(player, "Usage: /arena join [size] | leave | rating | top", "WARN"))
    }
}

private fun ratingOf(p: CharacterState, def: ArenaDefinition) = if (p.arenaRating > 0) p.arenaRating else def.startRating

/** Every tick: drop dead tickets, pair players into matches, and run the fights that are on. */
internal fun WorldSimulation.tickArena(out: MutableList<WorldSimulationEvent>) {
    val def = arenaDef() ?: return
    // Tickets of players who left the world or died are dropped.
    arenaQueue.keys.toList().forEach { id -> val p = players[id]; if (p == null || !p.isAlive(game.lifeVitalId) || arenaMatchOf(id) != null) arenaQueue.remove(id) }
    if (currentTick % 20L == 0L) for (size in def.teamSizes) pairQueued(def, size, out)
    for (match in arenaMatches.values.toList()) runMatch(def, match, out)
}

private fun WorldSimulation.pairQueued(def: ArenaDefinition, size: Int, out: MutableList<WorldSimulationEvent>) {
    val waiting = arenaQueue.values.filter { it.size == size }.mapNotNull { t -> players[t.characterId]?.let { t to it } }
    if (waiting.size < size * 2) return
    val byRating = waiting.sortedWith(compareBy({ ratingOf(it.second, def) }, { it.first.queuedTick }, { it.first.characterId }))
    val oldest = waiting.minOf { it.first.queuedTick }
    val gap = def.maxRatingGap + def.gapGrowth * ((currentTick - oldest) / def.gapGrowthTicks).toInt()
    for (start in 0..byRating.size - size * 2) {
        val window = byRating.subList(start, start + size * 2)
        if (ratingOf(window.last().second, def) - ratingOf(window.first().second, def) > gap) continue
        // Snake split of the sorted window (A B B A A B B A ...) keeps the two teams close in strength.
        val a = mutableListOf<CharacterState>()
        val b = mutableListOf<CharacterState>()
        window.map { it.second }.forEachIndexed { i, p -> if (i % 4 == 0 || i % 4 == 3) a.add(p) else b.add(p) }
        startMatch(def, size, a, b, out)
        return
    }
}

private fun WorldSimulation.startMatch(def: ArenaDefinition, size: Int, a: List<CharacterState>, b: List<CharacterState>, out: MutableList<WorldSimulationEvent>) {
    val template = maps[def.mapId] ?: run {
        (a + b).forEach { out.add(tellPlayer(it, "The arena is closed right now.", "WARN")) }
        (a + b).forEach { arenaQueue.remove(it.id) }
        return
    }
    val id = nextInstanceId++
    val copy = template.copy(id = id)
    registerMap(copy)
    val matchId = nextArenaMatchId++
    instances[id] = MapInstance(id, template.id, "arena:$matchId", currentTick, (a + b).map { it.id }.toSet())
    val returns = HashMap<String, WorldPosition>()
    fun place(team: List<CharacterState>, spawns: List<com.morgana.domain.game.ArenaSpawn>, facing: Direction) {
        team.forEachIndexed { i, p ->
            val spot = spawns[i % spawns.size]
            returns[p.id] = p.position
            arenaQueue.remove(p.id)
            interruptActivities(p.id, out)
            val moved = p.copy(
                position = WorldPosition.fromTile(id, spot.x.coerceIn(0, copy.width - 1), spot.y.coerceIn(0, copy.height - 1), facing, copy.tileSize),
                instanceReturn = p.position, statusEffects = emptyList(), castingSpellId = null, castFinishTick = 0L, castTotalTicks = 0
            ).recalculateVitals(game, items, refill = true)
            players[p.id] = moved
            out.add(WorldSimulationEvent.PlayerMoved(moved, p.position.mapId, id))
            out.add(WorldSimulationEvent.CharacterUpdated(moved))
            out.add(tellPlayer(moved, "Arena match found: ${size}v$size. The fight starts in ${def.countdownTicks / 20} seconds.", "INFO"))
        }
    }
    place(a, def.teamASpawns, Direction.RIGHT)
    place(b, def.teamBSpawns, Direction.LEFT)
    val start = currentTick + def.countdownTicks
    arenaMatches[matchId] = ArenaMatch(matchId, id, size, a.map { it.id }, b.map { it.id }, start, start + def.matchTicks, returns)
}

private fun WorldSimulation.runMatch(def: ArenaDefinition, match: ArenaMatch, out: MutableList<WorldSimulationEvent>) {
    var m = match
    // Anyone who left the world forfeits.
    val gone = (m.teamA + m.teamB).filter { it !in m.eliminated && (players[it] == null || players[it]?.position?.mapId != m.mapId) }
    if (gone.isNotEmpty()) { m = m.copy(eliminated = m.eliminated + gone); arenaMatches[m.id] = m }
    if (currentTick == m.startTick) {
        (m.teamA + m.teamB).forEach { id -> players[id]?.let { out.add(tellPlayer(it, "Fight!", "WARN")) } }
    }
    val aAlive = m.alive(0)
    val bAlive = m.alive(1)
    val winner = when {
        aAlive.isEmpty() && bAlive.isEmpty() -> -1
        aAlive.isEmpty() -> 1
        bAlive.isEmpty() -> 0
        currentTick >= m.endTick -> {
            val a = lifeLeft(aAlive)
            val b = lifeLeft(bAlive)
            if (a > b) 0 else if (b > a) 1 else -1
        }
        else -> return
    }
    finishMatch(def, m, winner, out)
}

private fun WorldSimulation.lifeLeft(ids: List<String>): Long = ids.sumOf { (players[it]?.vitals?.get(game.lifeVitalId)?.cur ?: 0).toLong() }

private fun WorldSimulation.finishMatch(def: ArenaDefinition, m: ArenaMatch, winner: Int, out: MutableList<WorldSimulationEvent>) {
    arenaMatches.remove(m.id)
    val everyone = (m.teamA + m.teamB)
    val ratings = everyone.associateWith { id -> players[id]?.let { ratingOf(it, def) } ?: def.startRating }
    fun avg(team: List<String>) = team.map { ratings.getValue(it) }.average()
    val expectedA = 1.0 / (1.0 + 10.0.pow((avg(m.teamB) - avg(m.teamA)) / 400.0))
    val scoreA = when (winner) { 0 -> 1.0; 1 -> 0.0; else -> 0.5 }
    val deltaA = Math.round(def.ratingK * (scoreA - expectedA)).toInt()
    for (id in everyone) {
        val live = players[id] ?: continue
        val team = m.teamOf(id)
        val won = winner == team
        val draw = winner < 0
        var updated = live
        if (!draw) {
            val delta = if (team == 0) deltaA else -deltaA
            updated = updated.copy(
                arenaRating = (ratings.getValue(id) + delta).coerceAtLeast(1),
                arenaWins = updated.arenaWins + if (won) 1 else 0,
                arenaLosses = updated.arenaLosses + if (won) 0 else 1
            )
            val rewards = if (won) def.winnerRewards else def.loserRewards
            var inv = updated.inventory
            for ((itemId, amount) in rewards) items[itemId]?.let { inv = inv.addItem(it, amount).updatedInventory }
            updated = updated.copy(inventory = inv)
        }
        if (id !in m.eliminated || updated.position.mapId == m.mapId) updated = sendOutOfArena(updated, m, out)
        players[id] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
        val change = if (draw) "A draw." else if (won) "Victory! Rating ${updated.arenaRating} (+${abs(updated.arenaRating - ratings.getValue(id))})." else "Defeat. Rating ${updated.arenaRating} (-${abs(updated.arenaRating - ratings.getValue(id))})."
        out.add(tellPlayer(updated, change, if (won) "GOOD" else "INFO"))
    }
}

/** A player who falls in an arena fight leaves it at once with full life; nothing is lost. */
internal fun WorldSimulation.eliminateFromArena(player: CharacterState, match: ArenaMatch, out: MutableList<WorldSimulationEvent>): CharacterState {
    arenaMatches[match.id] = match.copy(eliminated = match.eliminated + player.id)
    out.add(tellPlayer(player, "You are out of the fight.", "WARN"))
    return sendOutOfArena(player, match, out)
}

private fun WorldSimulation.sendOutOfArena(player: CharacterState, match: ArenaMatch, out: MutableList<WorldSimulationEvent>): CharacterState {
    if (player.position.mapId != match.mapId) return player
    val back = match.returns[player.id]
        ?: classes[player.classId]?.let { WorldPosition.fromTile(it.startMapId, it.startX, it.startY, Direction.DOWN) }
        ?: player.position
    val moved = player.copy(
        position = back, instanceReturn = null, statusEffects = emptyList(), castingSpellId = null, castFinishTick = 0L, castTotalTicks = 0,
        isGhost = false, ghostSinceTick = 0L
    ).recalculateVitals(game, items, refill = true)
    out.add(WorldSimulationEvent.PlayerMoved(moved, player.position.mapId, back.mapId))
    return moved
}
