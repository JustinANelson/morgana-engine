package com.morgana.script.luak

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.script.ScriptBudget
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.script.ScriptEntitySnapshot
import com.morgana.domain.script.ScriptMapSnapshot
import com.morgana.domain.script.ScriptNpcSnapshot
import com.morgana.domain.script.ScriptPlayerSnapshot
import com.morgana.domain.script.ScriptWorldView
import com.morgana.domain.stats.VitalState
import net.blueva.luak.Budget
import net.blueva.luak.LuaError
import net.blueva.luak.LuaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LuakSandboxTest {

    private class MockWorldView(
        override val currentTick: Long = 42L,
        private val seed: Long = 12345L
    ) : ScriptWorldView {
        private val rng = Random(seed)

        val player = ScriptPlayerSnapshot(
            id = "hero-1",
            name = "Aria",
            mapId = 1,
            x = 10,
            y = 15,
            level = 5,
            attributes = mapOf("strength" to 18, "agility" to 14, "intelligence" to 12),
            vitals = mapOf("hp" to VitalState(80, 100), "mp" to VitalState(40, 50)),
            accessLevel = 2
        )

        val npc = ScriptNpcSnapshot(
            instanceId = "npc-1",
            npcId = 7,
            name = "Forest Goblin",
            mapId = 1,
            x = 12,
            y = 15,
            vitals = mapOf("hp" to VitalState(25, 25)),
            isAlive = true
        )

        val map = ScriptMapSnapshot(
            id = 1,
            name = "Verdant Glade",
            width = 30,
            height = 30,
            moral = "SAFE"
        )

        override fun random(min: Int, max: Int): Int = rng.nextInt(min, max + 1)
        override fun getPlayer(playerId: String): ScriptPlayerSnapshot? = if (playerId == player.id) player else null
        override fun getNpc(mapId: Int, instanceId: String): ScriptNpcSnapshot? = if (mapId == 1 && instanceId == npc.instanceId) npc else null
        override fun getMap(mapId: Int): ScriptMapSnapshot? = if (mapId == 1) map else null
        override fun getItem(itemId: Int): ItemDefinition? = null
        override fun getSpell(spellId: Int): SpellDefinition? = null
        override fun getEntitiesInRange(mapId: Int, x: Int, y: Int, radius: Int): List<ScriptEntitySnapshot> =
            listOf(ScriptEntitySnapshot(true, player.id, mapId, player.x, player.y, player.name))
    }

    @Test
    fun `forbidden globals are completely inaccessible`() {
        val g = LuakSandbox.createGlobals()
        assertEquals("nil", g.load("return tostring(io)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(os)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(package)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(require)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(debug)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(dofile)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(loadfile)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(load)")!!.call()!!.tojstring())
        assertEquals("nil", g.load("return tostring(collectgarbage)")!!.call()!!.tojstring())
    }

    @Test
    fun `infinite loop is halted deterministically by instruction budget`() {
        val g = LuakSandbox.createGlobals()
        g.budget = Budget().apply { instructions = 50_000 }
        try {
            g.load("while true do end")!!.call()
            fail("Expected budget exhaustion LuaError")
        } catch (e: LuaError) {
            assertTrue("budget" in (e.message ?: ""))
        }
    }

    @Test
    fun `pcall cannot swallow instruction budget exhaustion`() {
        val g = LuakSandbox.createGlobals()
        g.budget = Budget().apply { instructions = 50_000 }
        try {
            g.load("pcall(function() while true do end end)")!!.call()
            fail("Expected budget exhaustion to escape pcall")
        } catch (e: LuaError) {
            assertTrue("budget" in (e.message ?: ""))
        }
    }

    @Test
    fun `memory ceiling halts excessive memory allocation`() {
        val g = LuakSandbox.createGlobals()
        g.memoryceiling = 4L * 1024 * 1024
        g.startmemorycount()
        try {
            g.load("local t = {} for i = 1, 5000000 do t[i] = {i} end")!!.call()
            fail("Expected memory ceiling LuaError")
        } catch (e: LuaError) {
            assertTrue("not enough memory" in (e.message ?: ""))
        }
    }

    @Test
    fun `catastrophic regex ReDoS is halted immediately by bounded pattern wrapper`() {
        val g = LuakSandbox.createGlobals()
        // The probe case with 12 nested greedy quantifiers
        val script = "string.find(string.rep('a', 3000), string.rep('a*', 12) .. 'b')"
        try {
            g.load(script)!!.call()
            fail("Expected pattern bounds check to reject catastrophic pattern")
        } catch (e: LuaError) {
            assertTrue("exceeded max quantifiers" in (e.message ?: ""))
        }
    }

    @Test
    fun `tostring never leaks memory pointer addresses`() {
        val g = LuakSandbox.createGlobals()
        val tableStr = g.load("return tostring({})")!!.call()!!.tojstring()
        assertEquals("table", tableStr)

        val errStr = g.load("local ok, err = pcall(function() error({}) end) return err")!!.call()!!.tojstring()
        assertFalse(Regex("[0-9a-fA-F]{6,}").containsMatchIn(errStr), "Error string should not contain hex addresses: $errStr")
    }

    @Test
    fun `deep non-tail recursion throws Lua stack overflow without crashing JVM`() {
        val g = LuakSandbox.createGlobals()
        val script = """
            local function recurse(n)
                return 1 + recurse(n + 1)
            end
            recurse(1)
        """.trimIndent()
        try {
            g.load(script)!!.call()
            fail("Expected stack overflow")
        } catch (e: LuaError) {
            assertTrue("stack overflow" in (e.message ?: ""))
        }
    }

    @Test
    fun `seeded deterministic randomness from world is reproducible`() {
        fun roll(seed: Long): List<Int> {
            val runtime = LuakScriptRuntime()
            val world = MockWorldView(seed = seed)
            val script = """
                register.formula("test_rand", function(params, world)
                    return world.random(1, 1000)
                end)
            """.trimIndent()
            runtime.loadModule("rand_mod", script, world)
            return List(5) { runtime.registry.evaluateFormula("test_rand", emptyMap(), world)!!.toInt() }
        }

        assertEquals(roll(999L), roll(999L))
    }

    @Test
    fun `world queries read player and npc snapshots accurately`() {
        val runtime = LuakScriptRuntime()
        val world = MockWorldView()
        val script = """
            register.command("check_stats", "Inspect stats", 0, function(player, args, world)
                local hero = world.get_player("hero-1")
                local goblin = world.get_npc(1, "npc-1")
                
                assert(hero ~= nil, "hero should exist")
                assert(hero.name == "Aria", "hero name should match")
                assert(hero.attributes["strength"] == 18, "strength should be 18")
                assert(hero.vitals["hp"].cur == 80, "current HP should be 80")
                
                assert(goblin ~= nil, "goblin should exist")
                assert(goblin.name == "Forest Goblin", "goblin name should match")
                assert(goblin.is_alive == true, "goblin should be alive")
                
                return "Stats verified: " .. hero.name .. " vs " .. goblin.name
            end)
        """.trimIndent()

        val res = runtime.loadModule("stats_mod", script, world)
        assertTrue(res is ScriptCompilationResult.Success)

        val cmd = runtime.registry.getCommand("check_stats")
        assertNotNull(cmd)
        val cmdRes = cmd!!.handler(world.player, emptyList(), world)
        assertTrue(cmdRes.handled)
        assertEquals("Stats verified: Aria vs Forest Goblin", cmdRes.feedbackMessage)
    }

    @Test
    fun `effect emission helpers produce declarative script effects`() {
        val runtime = LuakScriptRuntime()
        val world = MockWorldView()
        val script = """
            register.command("cast_meteor", "AOE meteor spell", 0, function(player, args, world)
                world.damage_player(player.id, 10, "hp", "self")
                world.damage_npc(1, "npc-1", 45, "hp", player.id)
                world.heal_player(player.id, 5, "hp")
                world.give_item(player.id, 101, 2)
                world.warp_player(player.id, 1, 15, 20)
                world.broadcast("A fiery meteor crashes!", 1, "#FF5555")
                world.send_message(player.id, "You cast meteor!")
                world.floating_text(1, 12, 15, "-45!", "CRIT", "#FF0000")
                world.animation(1, 12, 15, 9, "explosion.wav")
                return "Meteor launched"
            end)
        """.trimIndent()

        runtime.loadModule("spell_mod", script, world)
        val cmd = runtime.registry.getCommand("cast_meteor")!!
        val cmdRes = cmd.handler(world.player, emptyList(), world)

        assertTrue(cmdRes.handled)
        assertEquals("Meteor launched", cmdRes.feedbackMessage)
        assertEquals(9, cmdRes.effects.size)

        assertTrue(cmdRes.effects[0] is ScriptEffect.DamagePlayer)
        assertEquals(10, (cmdRes.effects[0] as ScriptEffect.DamagePlayer).amount)

        assertTrue(cmdRes.effects[1] is ScriptEffect.DamageNpc)
        assertEquals("npc-1", (cmdRes.effects[1] as ScriptEffect.DamageNpc).npcInstanceId)
        assertEquals(45, (cmdRes.effects[1] as ScriptEffect.DamageNpc).amount)

        assertTrue(cmdRes.effects[2] is ScriptEffect.HealPlayer)
        assertTrue(cmdRes.effects[3] is ScriptEffect.GiveItem)
        assertEquals(101, (cmdRes.effects[3] as ScriptEffect.GiveItem).itemId)
        assertEquals(2, (cmdRes.effects[3] as ScriptEffect.GiveItem).amount)

        assertTrue(cmdRes.effects[4] is ScriptEffect.WarpPlayer)
        assertTrue(cmdRes.effects[5] is ScriptEffect.BroadcastMessage)
        assertTrue(cmdRes.effects[6] is ScriptEffect.SendPlayerMessage)
        assertTrue(cmdRes.effects[7] is ScriptEffect.EmitFloatingText)
        assertTrue(cmdRes.effects[8] is ScriptEffect.TriggerAnimation)
    }

    @Test
    fun `formula registration and evaluation works under budget`() {
        val runtime = LuakScriptRuntime()
        val world = MockWorldView()
        val script = """
            register.formula("custom_melee", function(params, world)
                local str = params["a.strength"] or 0
                local wpn = params["weapon"] or 0
                local arm = params["armor"] or 0
                return math.max(1, (str * 3) + wpn - arm)
            end)
        """.trimIndent()

        runtime.loadModule("formula_mod", script, world)
        assertTrue(runtime.registry.hasFormula("custom_melee"))

        val damage = runtime.registry.evaluateFormula(
            "custom_melee",
            mapOf("a.strength" to 15L, "weapon" to 10L, "armor" to 5L),
            world
        )
        // (15 * 3) + 10 - 5 = 50
        assertEquals(50L, damage)
    }

    @Test
    fun `event command and condition handlers execute properly`() {
        val runtime = LuakScriptRuntime()
        val world = MockWorldView()
        val script = """
            register.event_condition("check_level_5", function(player, event_id, params, world)
                return player.level >= 5
            end)

            register.event_command("grant_quest_reward", function(player, event_id, params, world)
                world.send_message(player.id, "Quest completed: " .. (params["quest_name"] or ""))
                world.give_item(player.id, 99, 1)
            end)
        """.trimIndent()

        runtime.loadModule("event_mod", script, world)

        val meetsCondition = runtime.registry.checkEventCondition("check_level_5", world.player, 1, emptyMap(), world)
        assertEquals(true, meetsCondition)

        val effects = runtime.registry.executeEventCommand(
            "grant_quest_reward",
            world.player,
            1,
            mapOf("quest_name" to "Goblin Menace"),
            world
        )
        assertNotNull(effects)
        assertEquals(2, effects!!.size)
        assertTrue(effects[0] is ScriptEffect.SendPlayerMessage)
        assertTrue(effects[1] is ScriptEffect.GiveItem)
    }

    @Test
    fun `circuit breaker disables module after repeated budget exhaustion`() {
        val runtime = LuakScriptRuntime(
            budget = ScriptBudget(commandInstructions = 1_000L)
        )
        val world = MockWorldView()
        val script = """
            register.command("hang", "Hang loop", 0, function(player, args, world)
                while true do end
            end)
        """.trimIndent()

        runtime.loadModule("hostile_mod", script, world)
        val cmd = runtime.registry.getCommand("hang")!!

        // Trip 5 consecutive failures
        for (i in 1..5) {
            val res = cmd.handler(world.player, emptyList(), world)
            assertFalse(res.handled)
        }

        assertTrue(runtime.isModuleDisabled("hostile_mod"))

        // Next call is rejected immediately by the circuit breaker
        val res = cmd.handler(world.player, emptyList(), world)
        assertFalse(res.handled)
        assertTrue("disabled" in (res.feedbackMessage ?: ""))
    }

    @Test
    fun `static validator detects syntax errors and forbidden symbols`() {
        val invalidTokens = LuakScriptValidator.validate("bad_token", "local x = io.open('foo')")
        assertTrue(invalidTokens is ScriptValidationResult.Invalid)
        assertTrue("Forbidden" in (invalidTokens as ScriptValidationResult.Invalid).message)

        val syntaxError = LuakScriptValidator.validate("syntax_err", "function bad( end")
        assertTrue(syntaxError is ScriptValidationResult.Invalid)

        val validScript = LuakScriptValidator.validate("good", "local x = 42 return x * 2")
        assertTrue(validScript is ScriptValidationResult.Valid)
    }

    @Test
    fun `combat hooks and custom action registration and execution work cleanly`() {
        val runtime = LuakScriptRuntime()
        val world = MockWorldView()

        val script = """
            register.action("ground_slam", function(char_id, params, world)
                world.floating_text(1, 10, 10, "SLAM!", "SKILL", "#F97316")
                world.award_xp(char_id, 100)
                world.consume_vital(char_id, "mp", 15)
            end)

            register.combat({
                on_attack = function(char_id, world)
                    world.spawn_projectile(1, char_id, true, 5, 5, "RIGHT", 4, 12, 2, 1, 0)
                    world.floating_text(1, 5, 5, "FIRE!", "ATTACK", "#EF4444")
                end,
                on_cast = function(char_id, spell_id, target_id, tx, ty, world)
                    world.damage_npc(1, target_id, 30, "hp", char_id)
                end,
                handle_action = function(char_id, action_id, params, world)
                    if action_id == "taunt" then
                        world.broadcast("Taunted by " .. char_id, 1, "#FBBF24")
                    end
                end
            })
        """.trimIndent()

        val comp = runtime.loadModule("combat_mod", script, world)
        assertTrue(comp is ScriptCompilationResult.Success)

        // Verify action execution
        assertTrue(runtime.registry.hasAction("ground_slam"))
        val actionEffects = runtime.registry.executeAction("hero-1", "ground_slam", emptyMap(), world)
        assertNotNull(actionEffects)
        assertEquals(3, actionEffects!!.size)
        assertTrue(actionEffects[0] is ScriptEffect.EmitFloatingText)
        assertTrue(actionEffects[1] is ScriptEffect.AwardExperience)
        assertEquals(100L, (actionEffects[1] as ScriptEffect.AwardExperience).amount)
        assertTrue(actionEffects[2] is ScriptEffect.ConsumeVital)
        assertEquals(15, (actionEffects[2] as ScriptEffect.ConsumeVital).amount)

        // Verify combat hooks
        val hooks = runtime.registry.getCombatHooks()
        assertNotNull(hooks)

        val attackEffects = hooks!!.onPlayerAttack?.invoke("hero-1", world)
        assertNotNull(attackEffects)
        assertEquals(2, attackEffects!!.size)
        assertTrue(attackEffects[0] is ScriptEffect.SpawnProjectile)
        val proj = attackEffects[0] as ScriptEffect.SpawnProjectile
        assertEquals(12, proj.damage)
        assertEquals(com.morgana.domain.spatial.Direction.RIGHT, proj.direction)

        val castEffects = hooks.onPlayerCastSpell?.invoke("hero-1", 42, "npc-1", 5, 6, world)
        assertNotNull(castEffects)
        assertEquals(1, castEffects!!.size)
        assertTrue(castEffects[0] is ScriptEffect.DamageNpc)
        assertEquals(30, (castEffects[0] as ScriptEffect.DamageNpc).amount)

        val combatActionEffects = hooks.handleAction?.invoke("hero-1", "taunt", emptyMap(), world)
        assertNotNull(combatActionEffects)
        assertEquals(1, combatActionEffects!!.size)
        assertTrue(combatActionEffects[0] is ScriptEffect.BroadcastMessage)
    }
}

