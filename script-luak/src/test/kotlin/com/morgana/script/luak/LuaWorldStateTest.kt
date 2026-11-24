package com.morgana.script.luak

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.game.WorldObjectsDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.systems.WorldObjectResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lua taking part in world state: placement rules (`register.object_gate`), queries and effects. */
class LuaWorldStateTest {
    private fun world(): WorldSimulation {
        val definition = MorganaClassic.definition.copy(
            worldObjects = WorldObjectsDefinition(listOf(WorldObjectKind("wall", "Wall", maxHp = 40), WorldObjectKind("sign", "Sign", blocksMovement = false, maxHp = 5)))
        )
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(definition), scripts = LuakScriptRuntime())
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 20, height = 20))
        return sim
    }

    private fun WorldSimulation.load(name: String, source: String): ScriptCompilationResult = scripts.loadModule(name, source, asScriptWorldView())
    private fun refusal(r: WorldObjectResult) = (r as WorldObjectResult.Refused).reason

    @Test
    fun `a Lua gate refuses placements by reading what is nearby, and only for the kinds it names`() {
        val sim = world()
        assertTrue(sim.load("rules.lua", """
            register.object_gate{ kinds = { "wall" }, check = function(info, world)
              for _, o in ipairs(world.objects_near(info.map_id, info.x, info.y, 2)) do
                if o.owner_id ~= info.owner_id then return false, "Too close to " .. tostring(o.owner_id) .. "'s base." end
              end
            end }""") is ScriptCompilationResult.Success)

        assertTrue(sim.placeWorldObject("wall", 1, 5, 5, "alice") is WorldObjectResult.Placed, "nothing near")
        assertEquals("Too close to alice's base.", refusal(sim.placeWorldObject("wall", 1, 6, 5, "bob")), "within 2 tiles of someone else's wall")
        assertTrue(sim.placeWorldObject("wall", 1, 6, 5, "alice") is WorldObjectResult.Placed, "your own base is fine")
        assertTrue(sim.placeWorldObject("wall", 1, 15, 15, "bob") is WorldObjectResult.Placed, "far away is fine")
        assertTrue(sim.placeWorldObject("sign", 1, 7, 5, "bob") is WorldObjectResult.Placed, "the gate names only walls")
    }

    @Test
    fun `the shorthand gate with a default message, and bad declarations`() {
        val sim = world()
        assertTrue(sim.load("all.lua", "register.object_gate(function(info, world) if info.x < 3 then return false end end)") is ScriptCompilationResult.Success)
        assertEquals("You cannot build that here.", refusal(sim.placeWorldObject("sign", 1, 1, 1)))
        assertTrue(sim.placeWorldObject("sign", 1, 4, 4) is WorldObjectResult.Placed)
        assertTrue(sim.load("bad.lua", """register.object_gate{ kinds = { "wall" } }""") is ScriptCompilationResult.Error)
        assertTrue(sim.load("bad2.lua", """register.object_gate{ kinds = 5, check = function() end }""") is ScriptCompilationResult.Error)
    }

    @Test
    fun `scripts place, read, tag, damage and remove objects through effects`() {
        val sim = world()
        sim.load("builder.lua", """
            register.tick{ every = 1, run = function(world)
              local step = tonumber((world.get_state("step") or { n = "0" }).n)
              if step == 0 then
                world.place_object("wall", 1, 8, 8, "carol", { tier = "2", paid = "yes" })
              elseif step == 1 then
                local near = world.objects_near(1, 8, 8, 1)
                world.set_state("seen", { count = tostring(#near), kind = near[1].kind, owner = near[1].owner_id, tier = near[1].data.tier, hp = tostring(near[1].hp) })
                world.set_object_data(near[1].id, { tier = "3" })
                world.damage_object(near[1].id, 15)
              elseif step == 2 then
                local o = world.objects_near(1, 8, 8, 0)[1]
                world.set_state("after", { tier = o.data.tier, hp = tostring(o.hp), by_id = world.get_object(o.id).kind })
                world.remove_object(o.id, "razed")
              end
              world.set_state("step", { n = tostring(step + 1) })
            end }""")
        repeat(4) { sim.stepTick() }
        assertEquals(mapOf("count" to "1", "kind" to "wall", "owner" to "carol", "tier" to "2", "hp" to "40"), sim.scriptState.getValue("seen").toMap())
        assertEquals(mapOf("tier" to "3", "hp" to "25", "by_id" to "wall"), sim.scriptState.getValue("after").toMap())
        assertNull(sim.worldObjectAt(1, 8, 8), "removed in the last step")
    }
}
