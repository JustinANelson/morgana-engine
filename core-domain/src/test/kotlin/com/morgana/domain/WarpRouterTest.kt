package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptPluginManifest
import com.morgana.domain.script.ScriptWarpHook
import com.morgana.domain.script.ScriptWarpRuling
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WarpRoute
import com.morgana.domain.sim.WarpRouter
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.contentMapId
import com.morgana.domain.sim.warpedPlayer
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The warp seam (ADR 0018): every warp asks the routers where it really goes. Instancing is one of them, so an instanced map is a
 * private copy only while that system is on and not replaced; `InstanceSystemTest` and `DungeonScalingTest` cover what it does.
 */
class WarpRouterTest {
    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(3L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        s.registerMap(MapDefinition(id = 2, name = "Meadow", width = 10, height = 10))
        s.registerMap(MapDefinition(id = 3, name = "Crypt", width = 10, height = 10, instanced = true))
        s.spawnOrUpdatePlayer(CharacterState(id = "a", accountId = "a", name = "A", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN)))
        return s
    }

    private fun WorldSimulation.warp(mapId: Int, out: MutableList<WorldSimulationEvent> = mutableListOf()): CharacterState? =
        warpedPlayer(players.getValue("a"), mapId, 4, 4, Direction.DOWN, out)

    @Test
    fun `with no opinion a warp goes to the map it named and an unknown map is no warp`() {
        val s = sim()
        s.warpRouters.clear()
        assertEquals(2, s.warp(2)!!.position.mapId)
        assertNull(s.warp(99))
        assertEquals(3, s.warp(3)!!.position.mapId, "with no router an instanced map is simply shared")
    }

    @Test
    fun `a router can send a warp to another map, and the first opinion wins`() {
        val s = sim()
        s.warpRouters.add(0, object : WarpRouter {
            override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
                if (target.id == 2) WarpRoute.To(sim.maps.getValue(1)) else null
        })
        s.warpRouters.add(0, object : WarpRouter {
            override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
                if (target.id == 2) WarpRoute.Refused else null
        })
        assertNull(s.warp(2), "the router in front refuses it before the other one is asked")
        assertTrue(MapInstances.isInstanceId(s.warp(3)!!.position.mapId), "other warps still reach instancing")
    }

    @Test
    fun `a redirect is routed again, so a redirect to an instanced map gets its private copy`() {
        val s = sim()
        s.warpRouters.add(0, object : WarpRouter {
            override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
                if (target.id == 2) WarpRoute.Redirect(3) else null
        })
        val moved = s.warp(2)!!
        assertTrue(MapInstances.isInstanceId(moved.position.mapId), "Meadow sent the player to a copy of the Crypt")
        assertEquals(3, s.instances.getValue(moved.position.mapId).templateMapId)
    }

    @Test
    fun `a redirect loop or a redirect to an unknown map is refused`() {
        val s = sim()
        s.warpRouters.add(0, object : WarpRouter {
            override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
                when (target.id) { 1 -> WarpRoute.Redirect(2); 2 -> WarpRoute.Redirect(1); else -> null }
        })
        assertNull(s.warp(1), "1 -> 2 -> 1 -> ... is a loop")
        val s2 = sim()
        s2.warpRouters.add(0, object : WarpRouter {
            override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
                if (target.id == 2) WarpRoute.Redirect(404) else null
        })
        assertNull(s2.warp(2), "there is no map 404")
    }

    private class KeyedRouter(override val key: String) : WarpRouter, GameSystem {
        override fun route(sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>) =
            WarpRoute.Refused
    }

    @Test
    fun `a router that is a system is skipped while its switch is off`() {
        val s = sim()
        s.install(KeyedRouter("arena"))
        assertNull(s.warp(2), "the router refuses everything")
        s.modules = ModuleFlags.fromDisabledKeys(listOf("arena"))
        assertEquals(2, s.warp(2)!!.position.mapId, "with the system off it is not asked")
    }

    // --- instancing as a system

    @Test
    fun `instancing routes an instanced map to a private copy and remembers where the player came from`() {
        val s = sim()
        val moved = s.warp(3)!!
        assertTrue(MapInstances.isInstanceId(moved.position.mapId))
        assertEquals(1, moved.instanceReturn!!.mapId, "the way back is kept")
        assertEquals(3, s.contentMapId(moved.position.mapId))
        assertEquals(2, s.warp(2)!!.position.mapId.also { assertFalse(MapInstances.isInstanceId(it)) }, "ordinary maps are not touched")
    }

    @Test
    fun `switching instancing off makes an instanced map one shared map`() {
        val s = sim()
        s.modules = ModuleFlags.fromDisabledKeys(listOf("instancing"))
        val moved = s.warp(3)!!
        assertEquals(3, moved.position.mapId)
        assertNull(moved.instanceReturn)
        assertTrue(s.instances.isEmpty())
    }

    @Test
    fun `a plugin that replaces instancing takes routing over`() {
        val s = sim()
        s.scripts.registry.registerPlugin(ScriptPluginManifest("shared", "shared-crypt", replaces = setOf("instancing")))
        val moved = s.warp(3)!!
        assertEquals(3, moved.position.mapId, "no private copy while the plugin replaces the system")
        s.scripts.registry.clear()
        assertTrue(MapInstances.isInstanceId(s.warp(3)!!.position.mapId), "the system is back without the plugin")
    }

    // --- Lua warp hooks, registered in Kotlin

    private fun hook(moduleId: String = "p", check: (String, Int) -> ScriptWarpRuling?) =
        ScriptWarpHook(moduleId) { player, warp, _ -> check(player.id, warp.mapId) }

    @Test
    fun `a plugin can refuse a warp and tell the player why, or send them elsewhere`() {
        val s = sim()
        s.scripts.registry.registerWarpHook(hook { _, map -> if (map == 2) ScriptWarpRuling.Refuse("The meadow is closed.") else null })
        val out = mutableListOf<WorldSimulationEvent>()
        assertNull(s.warp(2, out))
        assertTrue(out.any { it is WorldSimulationEvent.ChatEmitted && "The meadow is closed." in it.message })
        assertNotNull(s.warp(1))

        val s2 = sim()
        s2.scripts.registry.registerWarpHook(hook { _, map -> if (map == 2) ScriptWarpRuling.Redirect(3) else null })
        assertTrue(MapInstances.isInstanceId(s2.warp(2)!!.position.mapId), "the plugin's redirect still gets a private copy, because instancing is asked again")
    }

    @Test
    fun `a warp hook is quiet while a system its plugin requires is off and ignores a redirect to nowhere or to the same map`() {
        val s = sim()
        s.scripts.registry.registerPlugin(ScriptPluginManifest("p", "p", requires = setOf("guilds")))
        s.scripts.registry.registerWarpHook(hook { _, _ -> ScriptWarpRuling.Refuse(null) })
        assertNull(s.warp(2))
        s.modules = ModuleFlags.fromDisabledKeys(listOf("guilds"))
        assertNotNull(s.warp(2))

        val s2 = sim()
        s2.scripts.registry.registerWarpHook(hook { _, map -> ScriptWarpRuling.Redirect(if (map == 2) 2 else 404) })
        assertEquals(2, s2.warp(2)!!.position.mapId, "a redirect to the same map is no opinion")
        assertEquals(1, s2.warp(1)!!.position.mapId, "a redirect to an unknown map is no opinion")
    }
}
