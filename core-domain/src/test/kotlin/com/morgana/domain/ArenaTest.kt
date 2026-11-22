package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.ArenaDefinition
import com.morgana.domain.game.ArenaSpawn
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.applyPvpDamage
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The arena: queueing, pairing by rating, countdown, team fights, no loss on death, and rating changes. */
class ArenaTest {
    private val town = 1
    private val arena = 2
    private val def = ArenaDefinition(
        mapId = arena, teamSizes = listOf(1, 2),
        teamASpawns = listOf(ArenaSpawn(2, 5), ArenaSpawn(2, 6)), teamBSpawns = listOf(ArenaSpawn(17, 5), ArenaSpawn(17, 6)),
        countdownTicks = 40, matchTicks = 400, maxRatingGap = 100, gapGrowth = 200, gapGrowthTicks = 100
    )

    private fun world(config: ArenaDefinition = def): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(arena = config))
        val sim = WorldSimulation(SeededRandomSource(4L), game = game)
        sim.registerMap(MapDefinition(id = town, name = "Town", width = 20, height = 20))
        sim.registerMap(MapDefinition(id = arena, name = "Pit", width = 20, height = 12, moral = MapMoral.ARENA, instanced = true))
        return sim
    }

    private fun WorldSimulation.hero(id: String, rating: Int = 0) =
        spawnOrUpdatePlayer(
            CharacterState(
                id = id, accountId = "a-$id", name = id, arenaRating = rating,
                attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10),
                position = WorldPosition.fromTile(town, 3, 3, Direction.DOWN)
            ).recalculateVitals(game, items, refill = true)
        )

    private fun WorldSimulation.say(id: String, line: String) = executeAdminSlashCommand(id, line)
    private fun WorldSimulation.advance(ticks: Int) = repeat(ticks) { stepTick() }
    private fun WorldSimulation.told(events: List<WorldSimulationEvent>, text: String) =
        events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { text in it.message }
    private fun WorldSimulation.life(id: String) = players.getValue(id).vitals.getValue("hp").cur

    private fun WorldSimulation.startOneVsOne() {
        hero("ann"); hero("bob")
        say("ann", "/arena join 1"); say("bob", "/arena join 1")
        advance(25)
    }

    @Test
    fun `two queued players are paired into a match on their own copy of the arena`() {
        val sim = world()
        sim.startOneVsOne()
        assertEquals(1, sim.arenaMatches.size)
        assertTrue(sim.arenaQueue.isEmpty())
        val m = sim.arenaMatches.values.single()
        assertEquals(m.mapId, sim.players.getValue("ann").position.mapId)
        assertEquals(m.mapId, sim.players.getValue("bob").position.mapId)
        val xs = setOf(sim.players.getValue("ann").position.tileX, sim.players.getValue("bob").position.tileX)
        assertEquals(setOf(2, 17), xs, "the teams start on opposite sides")
    }

    @Test
    fun `nobody can be hurt during the countdown, then only the other team can`() {
        val sim = world()
        sim.hero("a1"); sim.hero("a2"); sim.hero("b1"); sim.hero("b2")
        for (id in listOf("a1", "a2", "b1", "b2")) sim.say(id, "/arena join 2")
        sim.advance(25)
        val m = sim.arenaMatches.values.single()
        val mates = m.teamA
        val foe = m.teamB.first()
        fun can(a: String, d: String) = sim.canAttackPlayer(sim.players.getValue(a), sim.players.getValue(d))
        assertFalse(can(mates[0], foe), "the countdown protects everyone")
        sim.advance(40)
        assertTrue(can(mates[0], foe), "the fight is on")
        assertFalse(can(mates[0], mates[1]), "teammates never hurt each other")
    }

    @Test
    fun `a fallen player leaves with full life, the survivor wins, and ratings move`() {
        val sim = world()
        sim.startOneVsOne()
        sim.advance(40)
        val m = sim.arenaMatches.values.single()
        val winner = m.teamA.single()
        val loser = m.teamB.single()
        sim.applyPvpDamage(sim.players.getValue(winner), sim.players.getValue(loser), 99_999, isCrit = false, out = mutableListOf())
        sim.stepTick()
        assertTrue(sim.arenaMatches.isEmpty(), "the match is over")
        for (id in listOf(winner, loser)) {
            assertEquals(town, sim.players.getValue(id).position.mapId, "$id is back in town")
            assertEquals(sim.players.getValue(id).vitals.getValue("hp").max, sim.life(id), "$id has full life")
        }
        assertEquals(1012, sim.players.getValue(winner).arenaRating)
        assertEquals(988, sim.players.getValue(loser).arenaRating)
        assertEquals(1, sim.players.getValue(winner).arenaWins)
        assertEquals(1, sim.players.getValue(loser).arenaLosses)
    }

    @Test
    fun `when time runs out the team with more life left wins, and an equal one draws`() {
        val sim = world()
        sim.startOneVsOne()
        sim.advance(40)
        val m = sim.arenaMatches.values.single()
        val hurt = m.teamB.single()
        sim.players[hurt] = sim.players.getValue(hurt).let { it.copy(vitals = it.vitals + ("hp" to it.vitals.getValue("hp").copy(cur = 5))) }
        sim.advance(420)
        assertTrue(sim.arenaMatches.isEmpty())
        assertEquals(1, sim.players.getValue(m.teamA.single()).arenaWins, "the healthier team wins on time")

        val drawWorld = world()
        drawWorld.startOneVsOne()
        drawWorld.advance(40 + 420)
        assertTrue(drawWorld.arenaMatches.isEmpty())
        assertEquals(0, drawWorld.players.getValue("ann").arenaRating, "a draw leaves ratings alone")
    }

    @Test
    fun `a player who leaves the world forfeits`() {
        val sim = world()
        sim.startOneVsOne()
        sim.advance(40)
        val m = sim.arenaMatches.values.single()
        sim.removePlayer(m.teamB.single())
        sim.stepTick()
        assertTrue(sim.arenaMatches.isEmpty())
        assertEquals(1, sim.players.getValue(m.teamA.single()).arenaWins)
    }

    @Test
    fun `players far apart in rating wait until the allowed gap grows`() {
        val sim = world()
        sim.hero("low", rating = 1000); sim.hero("high", rating = 1500)
        sim.say("low", "/arena join 1"); sim.say("high", "/arena join 1")
        sim.advance(60)
        assertTrue(sim.arenaMatches.isEmpty(), "500 apart is beyond the starting gap of 100")
        sim.advance(300)
        assertEquals(1, sim.arenaMatches.size, "after waiting the gap has widened enough")
    }

    @Test
    fun `the commands check sizes, repeats and leaving`() {
        val sim = world()
        sim.hero("ann")
        assertTrue(sim.told(sim.say("ann", "/arena join 7"), "team sizes"))
        sim.say("ann", "/arena join 1")
        assertTrue(sim.told(sim.say("ann", "/arena join 1"), "already queued"))
        assertTrue(sim.told(sim.say("ann", "/arena leave"), "left the arena queue"))
        assertTrue(sim.arenaQueue.isEmpty())
        assertTrue(sim.told(sim.say("ann", "/arena rating"), "Arena rating 1000"))
        val none = world(def.copy(enabled = false))
        none.hero("zed")
        assertTrue(none.told(none.say("zed", "/arena join"), "no arena"))
    }

    @Test
    fun `top lists the best ratings among players online who have fought`() {
        val sim = world()
        sim.hero("ann"); sim.hero("bob"); sim.hero("cy")
        assertTrue(sim.told(sim.say("ann", "/arena top"), "Nobody online"))
        sim.players["ann"] = sim.players.getValue("ann").copy(arenaRating = 1100, arenaWins = 3)
        sim.players["bob"] = sim.players.getValue("bob").copy(arenaRating = 1250, arenaWins = 5, arenaLosses = 1)
        val lines = sim.say("cy", "/arena top").filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }
        assertTrue(lines.any { it.startsWith("1. bob 1250") }, lines.toString())
        assertTrue(lines.any { it.startsWith("2. ann 1100") }, lines.toString())
        assertTrue(lines.none { "cy" in it && it.first().isDigit() }, "players who never fought are not listed")
    }
}
