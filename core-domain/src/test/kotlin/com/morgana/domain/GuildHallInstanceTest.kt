package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.InstanceOwner
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.contentMapId
import com.morgana.domain.sim.positionAfterLogin
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A guild hall is an instanced map owned by a guild: members share one copy, everyone else is turned away. */
class GuildHallInstanceTest {
    private val town = 1
    private val hall = 2

    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.instanceSettings = InstanceSettings(emptyTicks = 10)
        sim.registerMap(MapDefinition(id = town, name = "Town", width = 12, height = 12))
        sim.registerMap(MapDefinition(id = hall, name = "Guildhall", width = 10, height = 10, instanced = true, instanceOwner = InstanceOwner.GUILD))
        return sim
    }

    private fun WorldSimulation.hero(id: String, guild: String?, party: String? = null) =
        spawnOrUpdatePlayer(CharacterState(id = id, accountId = "a-$id", name = id, position = WorldPosition.fromTile(town, 3, 5, Direction.RIGHT), guildId = guild, partyId = party))

    private fun WorldSimulation.enter(id: String) = applyScriptEffect(ScriptEffect.WarpPlayer(id, hall, 2, 2))
    private fun WorldSimulation.mapOf(id: String) = players.getValue(id).position.mapId

    @Test
    fun `members of one guild share a hall, even when they are in different parties`() {
        val sim = world()
        sim.hero("ann", "g1", party = "p1")
        sim.hero("bob", "g1", party = "p2")
        sim.hero("cy", "g2")
        sim.enter("ann"); sim.enter("bob"); sim.enter("cy")
        assertTrue(MapInstances.isInstanceId(sim.mapOf("ann")))
        assertEquals(sim.mapOf("ann"), sim.mapOf("bob"), "the same guild shares one hall")
        assertNotEquals(sim.mapOf("ann"), sim.mapOf("cy"), "another guild has its own")
        assertEquals(hall, sim.contentMapId(sim.mapOf("cy")))
    }

    @Test
    fun `someone with no guild is turned away with a notice`() {
        val sim = world()
        sim.hero("dan", null)
        val events = sim.enter("dan")
        assertEquals(town, sim.mapOf("dan"), "they stay where they were")
        assertTrue(events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { "guild members only" in it.message })
    }

    @Test
    fun `a player who is no longer in the guild is put back outside at login`() {
        val sim = world()
        sim.hero("eve", "g1")
        sim.enter("eve")
        val inside = sim.players.getValue("eve")
        assertTrue(MapInstances.isInstanceId(inside.position.mapId))
        val relogged = sim.positionAfterLogin(inside.copy(guildId = null))
        assertFalse(MapInstances.isInstanceId(relogged.position.mapId), "kicked from the guild, they are back in town")
        val stays = sim.positionAfterLogin(inside)
        assertEquals(inside.position.mapId, stays.position.mapId, "a member who is still in the guild stays")
    }
}
