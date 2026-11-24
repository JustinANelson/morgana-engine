package com.morgana.script.luak

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Lua plugins reacting to the scheduler (`register.schedule`), reading `world.now_ms` and booking one-offs, end to end. */
class LuaSchedulerTest {
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val cleansing = ScheduleDefinition(
        id = "cleansing", name = "Weekly Cleansing", kind = ScheduleKind.WEEKLY, timezone = "UTC",
        dayOfWeek = "SATURDAY", time = "15:00", warningsSeconds = listOf(900, 60)
    )

    private fun world(): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(schedules = listOf(cleansing)))
        val sim = WorldSimulation(SeededRandomSource(1L), game = game, scripts = LuakScriptRuntime())
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 10, height = 10))
        return sim
    }

    private fun WorldSimulation.load(name: String, source: String): ScriptCompilationResult = scripts.loadModule(name, source, asScriptWorldView())

    /** Reads the clock once a second from [from] to [to], the way the server does. */
    private fun WorldSimulation.run(from: String, to: String) {
        var t = ms(from)
        advanceWallClock(t)
        while (t < ms(to)) { t += 1000; advanceWallClock(t) }
    }

    private val script = """
        register.schedule{ id = "cleansing",
          on_warning = function(info, world)
            local log = world.get_state("warnings") or {}
            log[tostring(info.seconds_before)] = info.phase .. ":" .. info.name
            world.set_state("warnings", log)
          end,
          on_fire = function(info, world)
            world.set_state("fired", { id = info.id, phase = info.phase, occ = tostring(info.occurrence_ms), recurring = tostring(info.recurring) })
            world.schedule_once("aftermath", world.now_ms + 3600000, { name = "Aftermath", warnings = { 60 }, data = { arc = "7", boss = "stag" } })
          end }
        register.schedule{ id = "aftermath", on_warning = function(info, world) world.set_state("aftermath_warn", { s = tostring(info.seconds_before) }) end,
          on_fire = function(info, world) world.set_state("aftermath_fire", { arc = info.data.arc, boss = info.data.boss, recurring = tostring(info.recurring) }) end }
    """.trimIndent()

    @Test
    fun `a Lua hook hears each warning and the start, and can book a follow-up one-off`() {
        val sim = world()
        assertTrue(sim.load("cleansing.lua", script) is ScriptCompilationResult.Success)

        sim.run("2026-11-28T14:40:00Z", "2026-11-28T15:00:05Z")
        val warnings = sim.scriptState.getValue("warnings")
        assertEquals("warning:Weekly Cleansing", warnings["900"])
        assertEquals("warning:Weekly Cleansing", warnings["60"])
        val fired = sim.scriptState.getValue("fired")
        assertEquals("cleansing", fired["id"])
        assertEquals("fire", fired["phase"])
        assertEquals(ms("2026-11-28T15:00:00Z").toString(), fired["occ"], "the occurrence time reaches Lua exactly")
        assertEquals("true", fired["recurring"])

        // The script booked a one-off an hour after the start, using world.now_ms.
        val booked = sim.scheduleOneShots.getValue("aftermath")
        assertEquals(ms("2026-11-28T16:00:00Z"), ms(booked.def.at))
        assertEquals(mapOf("arc" to "7", "boss" to "stag"), booked.data)

        sim.run("2026-11-28T15:00:05Z", "2026-11-28T16:00:05Z")
        assertEquals("60", sim.scriptState.getValue("aftermath_warn")["s"])
        val after = sim.scriptState.getValue("aftermath_fire")
        assertEquals(mapOf("arc" to "7", "boss" to "stag", "recurring" to "false"), after.filterKeys { it in setOf("arc", "boss", "recurring") })
        assertTrue(sim.scheduleOneShots.isEmpty())
    }

    @Test
    fun `a hook only hears the schedules it names and a bad declaration stops the module`() {
        val sim = world()
        assertTrue(sim.load("other.lua", """register.schedule{ id = "something-else", on_fire = function(info, world) world.set_state("wrong", { x = "1" }) end }""") is ScriptCompilationResult.Success)
        assertTrue(sim.load("all.lua", """register.schedule{ on_fire = function(info, world) world.set_state("any", { id = info.id }) end }""") is ScriptCompilationResult.Success)
        sim.run("2026-11-28T14:59:50Z", "2026-11-28T15:00:05Z")
        assertEquals(null, sim.scriptState["wrong"])
        assertEquals("cleansing", sim.scriptState.getValue("any")["id"], "no id means every schedule")

        assertTrue(sim.load("bad.lua", """register.schedule{ id = "x" }""") is ScriptCompilationResult.Error)
        assertTrue(sim.load("bad2.lua", """register.schedule{ ids = 5, on_fire = function() end }""") is ScriptCompilationResult.Error)
    }

    @Test
    fun `world now_ms is the clock the server reported, and cancel_schedule removes a one-off`() {
        val sim = world()
        sim.load("clock.lua", """
            register.tick{ every = 20, run = function(world)
              world.set_state("now", { ms = tostring(world.now_ms) })
              if world.get_state("booked") == nil then
                world.schedule_once("later", world.now_ms + 5000)
                world.set_state("booked", { yes = "1" })
              end
            end }
            register.command("drop", "cancel it", 0, function(player, args, world) world.cancel_schedule("later") return "ok" end)""")
        sim.advanceWallClock(ms("2026-11-24T12:00:00Z"))
        repeat(40) { sim.stepTick() }
        assertEquals(ms("2026-11-24T12:00:00Z").toString(), sim.scriptState.getValue("now")["ms"].toString().substringBefore("."))
        assertTrue("later" in sim.scheduleOneShots)
        assertTrue(sim.cancelSchedule("later"))
    }
}
