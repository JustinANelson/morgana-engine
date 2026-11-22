package com.morgana.script.luak

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.game.ClockDefinition
import com.morgana.domain.game.ClockTime
import com.morgana.domain.game.WorldClock
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.script.ScriptEntitySnapshot
import com.morgana.domain.script.ScriptMapSnapshot
import com.morgana.domain.script.ScriptNpcSnapshot
import com.morgana.domain.script.ScriptPlayerSnapshot
import com.morgana.domain.script.ScriptWorldView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `world.clock` exposes the game's calendar to scripts, and is nil for a game without one. */
class WorldClockScriptTest {

    private class View(override val currentTick: Long, override val clock: ClockTime?) : ScriptWorldView {
        override fun random(min: Int, max: Int) = min
        override fun getPlayer(playerId: String): ScriptPlayerSnapshot? = null
        override fun getNpc(mapId: Int, instanceId: String): ScriptNpcSnapshot? = null
        override fun getMap(mapId: Int): ScriptMapSnapshot? = null
        override fun getItem(itemId: Int): ItemDefinition? = null
        override fun getSpell(spellId: Int): SpellDefinition? = null
        override fun getEntitiesInRange(mapId: Int, x: Int, y: Int, radius: Int): List<ScriptEntitySnapshot> = emptyList()
    }

    private val player = ScriptPlayerSnapshot(id = "p", name = "P", mapId = 1, x = 1, y = 1, level = 1, attributes = emptyMap(), vitals = emptyMap(), accessLevel = 0)

    private fun run(view: ScriptWorldView, body: String): String? {
        val runtime = LuakScriptRuntime()
        val script = """register.command("t", "test", 0, function(player, args, world) $body end)"""
        assertTrue(runtime.loadModule("clock_test", script, view) is ScriptCompilationResult.Success)
        return runtime.registry.getCommand("t")!!.handler(player, emptyList(), view).feedbackMessage
    }

    @Test
    fun `scripts read the calendar`() {
        val clock = WorldClock(ClockDefinition(dayLengthTicks = 1000, daysPerSeason = 2, seasons = listOf("Wet", "Dry"), startTimePermille = 0))
        val view = View(2500, clock.at(2500))
        val msg = run(
            view,
            """return world.clock.season .. "|" .. world.clock.day_of_season .. "|" .. world.clock.phase .. "|" .. world.clock.text .. "|" .. world.clock.year"""
        )
        // Tick 2500 is day 2 (the first day of the second season), half past midnight... 500 permille is noon.
        assertEquals("Dry|1|day|12:00|0", msg)
    }

    @Test
    fun `a game without a clock gives nil`() {
        assertEquals("none", run(View(5, null), """if world.clock == nil then return "none" else return "has" end"""))
    }
}
