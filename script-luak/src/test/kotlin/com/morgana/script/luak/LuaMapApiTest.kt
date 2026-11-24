package com.morgana.script.luak

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The per-map Lua API a zoned reset plugin needs: who is on a map, what lives there, send people away, clear and refill it. */
class LuaMapApiTest {
    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition), scripts = LuakScriptRuntime())
        sim.npcs[1] = NpcDefinition(id = 1, name = "Wolf", behavior = NpcBehavior.PASSIVE)
        sim.npcs[2] = NpcDefinition(id = 2, name = "Boar", behavior = NpcBehavior.PASSIVE)
        sim.registerMap(MapDefinition(id = 1, name = "Hub", width = 20, height = 20))
        sim.registerMap(
            MapDefinition(
                id = 2, name = "Wilds", width = 20, height = 20,
                npcSpawns = listOf(MapNpcSpawnSlot(0, 1, 5, 5), MapNpcSpawnSlot(1, 1, 7, 5), MapNpcSpawnSlot(2, 2, 9, 5))
            ).withCell(3, 3, MapCell(attribute = TileAttribute.ResourceNode(1)))
        )
        for ((id, x) in listOf("a" to 4, "b" to 6)) {
            sim.spawnOrUpdatePlayer(CharacterState(id = id, accountId = "acc-$id", name = id.uppercase(), position = WorldPosition.fromTile(2, x, 10, Direction.DOWN)))
        }
        sim.spawnOrUpdatePlayer(CharacterState(id = "c", accountId = "acc-c", name = "C", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN)))
        return sim
    }

    private fun WorldSimulation.load(name: String, source: String) = scripts.loadModule(name, source, asScriptWorldView())

    @Test
    fun `a plugin can list a map, send its people away, clear it and refill it`() {
        val sim = world()
        assertTrue(sim.load("zone.lua", """
            register.tick{ every = 1, run = function(world)
              local step = tonumber((world.get_state("step") or { n = "0" }).n)
              if step == 0 then
                local people, beasts = world.players_on_map(2), world.npcs_on_map(2)
                world.set_state("seen", { players = tostring(#people), npcs = tostring(#beasts), first = people[1].name, kind = beasts[1].name })
                for _, p in ipairs(people) do world.warp_player(p.id, 1, 10, 10) end
                world.despawn_npcs(2, 1)
              elseif step == 1 then
                world.set_state("after_despawn", { npcs = tostring(#world.npcs_on_map(2)), left = world.npcs_on_map(2)[1].name, hub = tostring(#world.players_on_map(1)) })
                world.reset_map(2)
              elseif step == 2 then
                world.set_state("after_reset", { npcs = tostring(#world.npcs_on_map(2)) })
              end
              world.set_state("step", { n = tostring(step + 1) })
            end }""") is ScriptCompilationResult.Success)

        val events = mutableListOf<WorldSimulationEvent>()
        repeat(4) { events += sim.stepTick() }
        assertEquals(mapOf("players" to "2", "npcs" to "3", "first" to "A", "kind" to "Wolf"), sim.scriptState.getValue("seen").toMap())
        assertEquals(mapOf("npcs" to "1", "left" to "Boar", "hub" to "3"), sim.scriptState.getValue("after_despawn").toMap())
        assertEquals(mapOf("npcs" to "3"), sim.scriptState.getValue("after_reset").toMap())
        assertEquals(2, events.count { it is WorldSimulationEvent.MapRuntimeReset }, "clients are told when a map is cleared or reset")
    }

    @Test
    fun `a reset puts wounded monsters and used nodes back`() {
        val sim = world()
        val life = sim.game.lifeVitalId
        val wolf = sim.mapNpcs.getValue(2).first { it.spawnSlotIndex == 0 }
        sim.mapNpcs.getValue(2)[sim.mapNpcs.getValue(2).indexOf(wolf)] = wolf.copy(vitals = wolf.vitals + (life to wolf.vitals.getValue(life).copy(cur = 0)), tileX = 15)
        assertTrue(sim.resetMapRuntime(2))
        val fresh = sim.mapNpcs.getValue(2).first { it.spawnSlotIndex == 0 }
        assertEquals(fresh.vitals.getValue(life).max, fresh.vitals.getValue(life).cur)
        assertEquals(5, fresh.tileX)
        assertTrue(!sim.resetMapRuntime(99), "no such map")
    }
}
