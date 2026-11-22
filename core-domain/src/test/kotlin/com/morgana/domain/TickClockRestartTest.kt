package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The simulation clock (`currentTick`) restarts at 0 on every server launch, but characters are saved with tick
 * values from the previous run. Values from a clock that is ahead of this one must not freeze the character.
 */
class TickClockRestartTest {

    private fun saved(lastMoveTick: Long = 0L, lastAttackTick: Long = 0L, cooldowns: Map<Int, Long> = emptyMap()) = CharacterState(
        id = "p1", accountId = "a1", name = "Tester",
        position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN),
        lastMoveTick = lastMoveTick, lastAttackTick = lastAttackTick, spellCooldowns = cooldowns
    )

    private fun freshServer(): WorldSimulation = WorldSimulation(game = MorganaClassic.COMPILED).also {
        it.registerMap(MapDefinition(id = 1, name = "Map 1", width = 10, height = 10))
        it.spells[7] = SpellDefinition(id = 7, name = "Bolt", cooldownTicks = 40)
    }

    private fun WorldSimulation.advance(ticks: Int) = repeat(ticks) { stepTick() }

    @Test
    fun `a character saved late in a previous run can move right after a restart`() {
        val sim = freshServer()
        sim.spawnOrUpdatePlayer(saved(lastMoveTick = 19_616L)) // 16 minutes into the previous run; this run is at tick 0
        sim.advance(5)

        sim.movePlayer("p1", Direction.RIGHT)

        assertEquals(4, sim.players.getValue("p1").position.tileX, "the first move after login is accepted")
    }

    @Test
    fun `attack and spell timers from a clock that is ahead are discarded`() {
        val sim = freshServer()
        sim.spawnOrUpdatePlayer(saved(lastAttackTick = 19_000L, cooldowns = mapOf(7 to 19_040L)))

        val p = sim.players.getValue("p1")
        assertEquals(0L, p.lastAttackTick)
        assertEquals(emptyMap<Int, Long>(), p.spellCooldowns)
    }

    @Test
    fun `timers that this run could have produced survive logging out and back in`() {
        val sim = freshServer()
        sim.advance(100)
        // Saved by this same run: moved at tick 98, attacked at tick 99, spell ready at tick 130 (cast at 90, cooldown 40).
        sim.spawnOrUpdatePlayer(saved(lastMoveTick = 98L, lastAttackTick = 99L, cooldowns = mapOf(7 to 130L)))

        val p = sim.players.getValue("p1")
        assertEquals(98L, p.lastMoveTick)
        assertEquals(99L, p.lastAttackTick)
        assertEquals(mapOf(7 to 130L), p.spellCooldowns, "relogging must not reset an active cooldown")
    }

    @Test
    fun `a spell cooldown longer than the spell can produce is discarded but an expired one is harmless`() {
        val sim = freshServer()
        sim.advance(1_000)
        // 1,500 is 500 ticks ahead of this clock but the spell only has a 40 tick cooldown, so it came from an older run.
        sim.spawnOrUpdatePlayer(saved(cooldowns = mapOf(7 to 1_500L)))
        assertEquals(emptyMap<Int, Long>(), sim.players.getValue("p1").spellCooldowns)
    }

    @Test
    fun `updating a player who is already in the simulation never rewrites their timers`() {
        val sim = freshServer()
        sim.spawnOrUpdatePlayer(saved())
        sim.advance(10)
        sim.movePlayer("p1", Direction.RIGHT)
        val moved = sim.players.getValue("p1")

        sim.spawnOrUpdatePlayer(moved.copy(pkPoints = 3))

        assertEquals(moved.lastMoveTick, sim.players.getValue("p1").lastMoveTick)
    }
}
