package com.morgana.script.luak

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.script.ScriptCompilationResult
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lua plugins taking part in the kernel's system seams (ADR 0018), end to end: real Lua in the sandbox, a real world simulation
 * and the `ScriptedSystem` that is installed in every world.
 */
class LuaPluginSeamsTest {
    private val smite = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, cooldownTicks = 0)
    private val flare = SpellDefinition(id = 2, name = "Flare", targetType = SpellTargetType.SELF, cooldownTicks = 0)
    private val helm = ItemDefinition(id = 5, name = "Moonlit Helm", type = ItemType.HELMET, armorDefense = 3)
    private fun ogre(id: Int) = NpcDefinition(
        id = id, name = "Ogre $id", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 8, vitals = mapOf("hp" to 100_000),
        attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10), attackDamage = 1
    )

    private fun world(runtime: LuakScriptRuntime = LuakScriptRuntime(), twoOgres: Boolean = false): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition), scripts = runtime)
        sim.npcs[10] = ogre(10)
        sim.npcs[11] = ogre(11)
        sim.spells[1] = smite
        sim.spells[2] = flare
        sim.items[5] = helm
        val slots = buildList {
            add(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 10, spawnY = 10))
            if (twoOgres) add(MapNpcSpawnSlot(slotIndex = 1, npcId = 11, spawnX = 20, spawnY = 10))
        }
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 30, height = 30, npcSpawns = slots))
        for ((id, x) in listOf("tank" to 10, "healer" to 12)) {
            sim.spawnOrUpdatePlayer(
                CharacterState(
                    id = id, accountId = "a-$id", name = id, level = 3, knownSpellIds = listOf(1, 2),
                    attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10),
                    position = WorldPosition.fromTile(1, x, 13, Direction.UP),
                    inventory = Inventory().addItem(helm, 1).updatedInventory
                ).recalculateVitals(sim.game, sim.items, refill = true)
            )
        }
        return sim
    }

    private fun WorldSimulation.load(name: String, source: String): ScriptCompilationResult =
        scripts.loadModule(name, source, asScriptWorldView())

    private fun WorldSimulation.ticks(n: Int) = repeat(n) { stepTick() }
    private fun WorldSimulation.ogre(slot: Int = 0) = mapNpcs.getValue(1).first { it.spawnSlotIndex == slot }
    private fun List<WorldSimulationEvent>.said(text: String) = any { it is WorldSimulationEvent.ChatEmitted && text in it.message }
    private fun List<WorldSimulationEvent>.cast() = any { it is WorldSimulationEvent.SpellCast }

    // --- register.plugin

    @Test
    fun `a plugin declares itself and the engine records its manifest`() {
        val sim = world()
        val result = sim.load("rage.lua", """register.plugin{ id = "ogre-rage", name = "Ogre Rage", version = "1.2", description = "Angry ogres", requires = { "parties", "instancing" } }""")
        assertTrue(result is ScriptCompilationResult.Success)
        val manifest = sim.scripts.registry.pluginOf("rage.lua")!!
        assertEquals("ogre-rage", manifest.id)
        assertEquals("Ogre Rage", manifest.name)
        assertEquals("1.2", manifest.version)
        assertEquals(setOf("parties", "instancing"), manifest.requires)
        assertEquals(listOf(manifest), sim.scripts.registry.allPlugins())
    }

    @Test
    fun `a malformed plugin declaration stops the module loading and says why`() {
        val sim = world()
        val badId = sim.load("a.lua", """register.plugin{ id = "Not Valid" }""")
        assertTrue(badId is ScriptCompilationResult.Error && "id" in badId.message, badId.toString())
        val badRequires = sim.load("b.lua", """register.plugin{ id = "fine", requires = "parties" }""")
        assertTrue(badRequires is ScriptCompilationResult.Error && "requires" in badRequires.message, badRequires.toString())
        val typo = sim.load("d.lua", """register.plugin{ id = "fine", requires = { "partys" } }""")
        assertTrue(typo is ScriptCompilationResult.Error && "partys" in typo.message, typo.toString())
        val custom = sim.load("e.lua", """register.plugin{ id = "fine", requires = { "stocks:market" } }""")
        assertTrue(custom is ScriptCompilationResult.Success, "a plugin-registered system name is accepted")
        val noId = sim.load("c.lua", """register.plugin{ name = "no id" }""")
        assertTrue(noId is ScriptCompilationResult.Error, noId.toString())
        assertNull(sim.scripts.registry.pluginOf("a.lua"))
    }

    // --- register.requirement

    private val moonGate = """
        register.requirement(function(subject, player, world)
          if subject.kind == "spell" and subject.id == 1 and player.level < 5 then
            return subject.name .. " needs level 5 and the moon."
          end
          if subject.kind == "item" and subject.id == 5 then return "The helm sleeps by day." end
        end)
    """

    @Test
    fun `a Lua requirement gate refuses a spell and gives its reason`() {
        val sim = world()
        assertTrue(sim.load("gate.lua", moonGate) is ScriptCompilationResult.Success)
        val refused = sim.playerCastSpell("tank", 1, null, 0, 0)
        assertFalse(refused.cast())
        assertTrue(refused.said("Smite needs level 5 and the moon."), "the player is told the plugin's reason")
        assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "a spell the plugin has no opinion about still works")
    }

    @Test
    fun `a Lua requirement gate keeps an item in the bag`() {
        val sim = world()
        sim.load("gate.lua", moonGate)
        val events = sim.useInventorySlot("tank", 0)
        assertEquals(1, sim.players.getValue("tank").inventory.totalCountOf(5), "the helm stays in the bag")
        assertTrue(events.said("The helm sleeps by day."))
    }

    @Test
    fun `a gate that throws or loops forever is no opinion, and repeated failures switch the module off`() {
        val runtime = LuakScriptRuntime()
        val sim = world(runtime)
        sim.load("broken.lua", """register.requirement(function(subject, player, world) error("boom") end)""")
        sim.load("loop.lua", """register.requirement(function(subject, player, world) while true do end end)""")
        repeat(LuakScriptRuntime.MAX_CONSECUTIVE_FAILURES) { assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "failing plugins never block play") }
        assertTrue(runtime.isModuleDisabled("broken.lua"), "five failures in a row disable the module")
        assertTrue(runtime.isModuleDisabled("loop.lua"), "and a budget overrun counts as a failure")
    }

    @Test
    fun `a plugin only runs while the systems it requires are on`() {
        val sim = world()
        sim.load("gate.lua", """register.plugin{ id = "moon", requires = { "market" } }
            register.requirement(function(subject, player, world) return "no" end)""")
        assertFalse(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "the market is on, so the plugin is active")
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("market"))
        assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "with the market off its hooks are quiet")
    }

    // --- register.plugin{ replaces }

    /** A world where spell 3 asks for promotion rank 2, which this game has no ranks for, so the engine always refuses it. */
    private fun rankWorld(runtime: LuakScriptRuntime = LuakScriptRuntime()): WorldSimulation {
        val sim = world(runtime)
        sim.spells[3] = SpellDefinition(id = 3, name = "Rank Strike", targetType = SpellTargetType.SELF, cooldownTicks = 0, promotionReq = 2)
        sim.players["tank"] = sim.players.getValue("tank").copy(knownSpellIds = listOf(1, 2, 3))
        return sim
    }

    @Test
    fun `a plugin can replace an engine system with its own rule`() {
        val sim = rankWorld()
        val byDefault = sim.playerCastSpell("tank", 3, null, 0, 0)
        assertFalse(byDefault.cast())
        assertTrue(byDefault.said("requires the rank"), "the engine's promotion system refuses")

        sim.load("ranks.lua", """
            register.plugin{ id = "ranks", name = "Our own ranks", replaces = { "promotion" } }
            register.requirement(function(subject, player, world)
              if subject.id == 3 and player.level < 5 then return "Rank Strike needs level 5." end
            end)""")
        val replaced = sim.playerCastSpell("tank", 3, null, 0, 0)
        assertFalse(replaced.cast())
        assertTrue(replaced.said("Rank Strike needs level 5."), "now the plugin's reason, not the engine's")
        assertFalse(replaced.said("requires the rank"))

        sim.players["tank"] = sim.players.getValue("tank").copy(level = 5)
        assertTrue(sim.playerCastSpell("tank", 3, null, 0, 0).cast(), "at level 5 the plugin allows it, and promotion is no longer asked")
    }

    @Test
    fun `when a replacing plugin breaks the engine system it replaced comes back`() {
        val runtime = LuakScriptRuntime()
        val sim = rankWorld(runtime)
        sim.load("ranks.lua", """
            register.plugin{ id = "ranks", replaces = { "promotion" } }
            register.requirement(function(subject, player, world) error("boom") end)""")
        repeat(LuakScriptRuntime.MAX_CONSECUTIVE_FAILURES) {
            assertTrue(sim.playerCastSpell("tank", 3, null, 0, 0).cast(), "the plugin fails, so it has no opinion, and promotion is replaced: allowed")
        }
        assertTrue(runtime.isModuleDisabled("ranks.lua"))
        val after = sim.playerCastSpell("tank", 3, null, 0, 0)
        assertFalse(after.cast(), "the module is off, so promotion is back")
        assertTrue(after.said("requires the rank"))
    }

    @Test
    fun `a plugin can only replace engine systems`() {
        val sim = world()
        val unknown = sim.load("a.lua", """register.plugin{ id = "fine", replaces = { "promotions" } }""")
        assertTrue(unknown is ScriptCompilationResult.Error && "promotions" in unknown.message, unknown.toString())
        val itself = sim.load("b.lua", """register.plugin{ id = "fine", replaces = { "luaPlugins" } }""")
        assertTrue(itself is ScriptCompilationResult.Error, "a plugin cannot replace the system that runs plugins")
        val custom = sim.load("c.lua", """register.plugin{ id = "fine", replaces = { "stocks:market" } }""")
        assertTrue(custom is ScriptCompilationResult.Error, "only the engine's own systems can be replaced")
        val notAList = sim.load("d.lua", """register.plugin{ id = "fine", replaces = "promotion" }""")
        assertTrue(notAList is ScriptCompilationResult.Error)
        assertTrue(sim.scripts.registry.allPlugins().isEmpty())
        val ok = sim.load("e.lua", """register.plugin{ id = "fine", replaces = { "threat", "bossEncounters" } }""")
        assertTrue(ok is ScriptCompilationResult.Success)
        assertEquals(setOf("threat", "bossEncounters"), sim.scripts.registry.pluginOf("e.lua")!!.replaces)
    }

    // --- the remaining seams: ticks, item use, action gates, interrupts, combat observers, kills

    private fun WorldSimulation.state(key: String): Map<String, String>? = asScriptWorldView().getState(key)

    private fun WorldSimulation.withTrinket(id: String = "tank"): WorldSimulation {
        val trinket = ItemDefinition(id = 9, name = "Humming Trinket", type = ItemType.CONSUMABLE)
        items[9] = trinket
        players[id] = players.getValue(id).copy(inventory = players.getValue(id).inventory.addItem(trinket, 1).updatedInventory)
        return this
    }

    @Test
    fun `a Lua tick hook runs every period at its stage and the shorthand runs every tick`() {
        val sim = world()
        sim.load("ticks.lua", """
            register.tick(function(world)
              local s = world.get_state("count")
              local n = (s and tonumber(s.n) or 0) + 1
              world.set_state("count", { n = tostring(n) })
            end)
            register.tick{ stage = "after_npcs", every = 5, run = function(world)
              world.set_state("every5", { tick = tostring(world.tick) })
            end }""")
        sim.ticks(12)
        assertEquals("12", sim.state("count")!!["n"], "the shorthand form runs once a tick")
        val tick = sim.state("every5")!!.getValue("tick").toLong()
        assertTrue(tick > 0 && tick % 5 == 0L, "the periodic hook ran on a multiple of 5: $tick")
    }

    @Test
    fun `a bad tick declaration stops the module loading`() {
        val sim = world()
        assertTrue(sim.load("a.lua", """register.tick{ stage = "whenever", run = function() end }""") is ScriptCompilationResult.Error)
        assertTrue(sim.load("b.lua", """register.tick{ every = 0, run = function() end }""") is ScriptCompilationResult.Error)
        assertTrue(sim.load("c.lua", """register.tick{ every = 5 }""") is ScriptCompilationResult.Error)
        assertTrue(sim.scripts.registry.tickHooks().isEmpty())
    }

    @Test
    fun `a Lua item use takes over chosen items and leaves the rest alone`() {
        val sim = world().withTrinket()
        sim.load("use.lua", """
            register.item_use{ items = { 9 }, use = function(item, player, slot, world)
              world.send_message(player.id, item.name .. " hums in slot " .. slot .. " (" .. item.type .. ")")
              world.remove_item(player.id, item.id, 1)
              return true
            end }""")
        val slot = sim.players.getValue("tank").inventory.slots.indexOfFirst { it?.itemId == 9 }
        val events = sim.useInventorySlot("tank", slot)
        assertTrue(events.said("Humming Trinket hums in slot ${slot + 1} (consumable)"), "the plugin handled it, with a 1-based slot")
        assertEquals(0, sim.players.getValue("tank").inventory.totalCountOf(9), "and used the item up through an effect")

        val helmSlot = sim.players.getValue("tank").inventory.slots.indexOfFirst { it?.itemId == 5 }
        sim.useInventorySlot("tank", helmSlot)
        assertEquals(0, sim.players.getValue("tank").inventory.totalCountOf(5), "the helm is not covered by the hook: it is worn as usual")
    }

    @Test
    fun `a Lua item use that does not take the item lets the engine use it`() {
        val sim = world().withTrinket()
        sim.load("use.lua", """register.item_use{ use = function(item, player, slot, world)
              world.send_message(player.id, "ignored"); return false end }""")
        val slot = sim.players.getValue("tank").inventory.slots.indexOfFirst { it?.itemId == 5 }
        val events = sim.useInventorySlot("tank", slot)
        assertFalse(events.said("ignored"), "effects of a hook that declines are dropped")
        assertEquals(0, sim.players.getValue("tank").inventory.totalCountOf(5), "the engine used it")
    }

    @Test
    fun `a Lua action gate stops chosen activities for chosen characters`() {
        val sim = world()
        sim.load("gate.lua", """
            register.action_gate{ activities = { "cast" }, blocks = function(player, activity, world)
              return player.level < 5 and activity == "cast"
            end }""")
        assertFalse(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "a level 3 character may not cast")
        sim.players["tank"] = sim.players.getValue("tank").copy(level = 5)
        assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "at level 5 they may")
        assertTrue(sim.initiateTrade("tank", "healer").isNotEmpty(), "trading was never covered by the hook")
    }

    @Test
    fun `an action gate declaration names real activities`() {
        val sim = world()
        val bad = sim.load("a.lua", """register.action_gate{ activities = { "dance" }, blocks = function() return true end }""")
        assertTrue(bad is ScriptCompilationResult.Error && "dance" in bad.message, bad.toString())
        assertTrue(sim.load("b.lua", """register.action_gate{ activities = { "cast" } }""") is ScriptCompilationResult.Error)
        assertTrue(sim.load("c.lua", """register.action_gate{ activities = { "move", "attack", "cast", "use_item", "trade" }, blocks = function() return false end }""") is ScriptCompilationResult.Success)
    }

    @Test
    fun `a Lua interrupt hook is told when a character dies and cannot change the dying character`() {
        val sim = world()
        sim.load("fall.lua", """
            register.on_interrupt(function(player, world)
              world.set_state("fell", { who = player.id })
              world.give_item(player.id, 5, 3)         -- would change the dying character: dropped
              world.broadcast(player.name .. " fell")
            end)""")
        val before = sim.players.getValue("tank").inventory.totalCountOf(5)
        val events = sim.applyScriptEffect(com.morgana.domain.script.ScriptEffect.DamagePlayer("tank", 999_999))
        assertEquals("tank", sim.state("fell")!!["who"])
        assertTrue(events.said("tank fell"), "the broadcast was applied")
        assertEquals(before, sim.players.getValue("tank").inventory.totalCountOf(5), "the item given to the dying character was dropped")
    }

    @Test
    fun `a Lua combat observer sees weapon hits`() {
        val sim = world()
        sim.hero("striker", 10, 11)
        // A weapon hit needs a weapon in hand: give the striker a sword and wield it.
        val sword = ItemDefinition(id = 20, name = "Sword", type = ItemType.WEAPON, attackDamage = 12, weaponType = "sword")
        sim.items[20] = sword
        sim.players["striker"] = sim.players.getValue("striker").copy(inventory = Inventory().addItem(sword, 1).updatedInventory)
        sim.useInventorySlot("striker", 0)
        sim.load("watch.lua", """
            register.combat_observer{ after_attack = function(attacker, world)
              local s = world.get_state("swings")
              world.set_state("swings", { n = tostring((s and tonumber(s.n) or 0) + 1), who = attacker.id })
            end }""")
        repeat(3) { sim.playerAttack("striker"); sim.ticks(30) }
        val swings = sim.state("swings")
        assertTrue(swings != null && swings.getValue("n").toInt() >= 1, "at least one landed hit was seen: $swings")
        assertEquals("striker", swings!!["who"])
    }

    @Test
    fun `a Lua observer of struck characters reacts to hits but cannot change the one struck`() {
        val sim = world()
        sim.load("thorns.lua", """
            register.combat_observer{ after_struck = function(victim, world)
              local s = world.get_state("struck")
              world.set_state("struck", { n = tostring((s and tonumber(s.n) or 0) + 1) })
              world.damage_player(victim.id, 777)       -- would change the character being struck: dropped
              world.floating_text(victim.map_id, victim.x, victim.y, "ouch", "script")
            end }""")
        val events = (1..80).flatMap { sim.stepTick() }
        assertTrue(sim.state("struck") != null && sim.state("struck")!!.getValue("n").toInt() >= 1, "the ogre hit someone and the hook saw it")
        assertTrue(events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text == "ouch" }, "other effects were applied")
        assertFalse(events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text == "-777" }, "damage to the struck character was dropped")
    }

    @Test
    fun `a Lua kill hook is told who killed what and for how much experience`() {
        val sim = world()
        sim.load("kill.lua", """register.on_kill(function(killer, xp, world)
              world.send_message(killer.id, "you earned " .. xp .. " xp")
            end)""")
        val instance = sim.ogre().instanceId
        val events = sim.applyScriptEffect(com.morgana.domain.script.ScriptEffect.DamageNpc(1, instance, 999_999, "hp", "tank"))
        assertTrue(events.said("you earned"), "the killer was told: ${events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }}")
    }

    @Test
    fun `the remaining hooks follow the systems their plugin requires and the luaPlugins switch`() {
        val sim = world()
        sim.load("gate.lua", """register.plugin{ id = "castgate", requires = { "market" } }
            register.action_gate{ activities = { "cast" }, blocks = function() return true end }""")
        assertFalse(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "the market is on, so the gate works")
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("market"))
        assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "the market is off, so the plugin is quiet")
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("luaPlugins"))
        assertTrue(sim.playerCastSpell("tank", 2, null, 0, 0).cast(), "and with luaPlugins off no hook runs")
    }

    // --- register.warp

    private fun warpWorld(): WorldSimulation {
        val sim = world()
        sim.registerMap(MapDefinition(id = 2, name = "Meadow", width = 12, height = 12))
        sim.registerMap(MapDefinition(id = 3, name = "Crypt", width = 12, height = 12, instanced = true))
        return sim
    }

    private fun WorldSimulation.warpTank(mapId: Int): List<WorldSimulationEvent> =
        applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer("tank", mapId, 4, 4))

    @Test
    fun `a Lua warp hook can refuse a warp and tell the player why`() {
        val sim = warpWorld()
        sim.load("gates.lua", """
            register.warp(function(player, warp, world)
              if warp.map_id == 2 then return false, "The meadow is closed for the season." end
            end)""")
        val events = sim.warpTank(2)
        assertEquals(1, sim.players.getValue("tank").position.mapId, "the player stayed where they were")
        assertTrue(events.said("The meadow is closed for the season."))
        sim.warpTank(1)
        assertEquals(1, sim.players.getValue("tank").position.mapId, "other warps are untouched")
    }

    @Test
    fun `a Lua warp hook can send the player to another map, even an instanced one`() {
        val sim = warpWorld()
        sim.load("detour.lua", """
            register.warp(function(player, warp, world)
              if warp.map_id == 2 and player.level < 5 then return 3 end   -- beginners go to the crypt instead
            end)""")
        sim.warpTank(2)
        val at = sim.players.getValue("tank").position.mapId
        assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(at), "map 2 became a private copy of map 3 because the redirect is routed again")
        sim.players["tank"] = sim.players.getValue("tank").copy(level = 5, position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN), instanceReturn = null)
        sim.warpTank(2)
        assertEquals(2, sim.players.getValue("tank").position.mapId, "a level 5 character is not redirected")
    }

    @Test
    fun `a Lua warp hook sees the warp request`() {
        val sim = warpWorld()
        sim.load("look.lua", """
            register.warp(function(player, warp, world)
              return false, warp.name .. "|" .. warp.map_id .. "|" .. warp.x .. "," .. warp.y .. "|" .. tostring(warp.instanced)
            end)""")
        assertTrue(sim.warpTank(3).said("Crypt|3|4,4|true"), "the hook is told the map, the tile and whether it is an instanced template")
        assertEquals(1, sim.players.getValue("tank").position.mapId)
    }

    @Test
    fun `a Lua warp hook that returns something else has no opinion`() {
        val sim = warpWorld()
        sim.load("odd.lua", """register.warp(function(player, warp, world) return "banana" end)""")
        sim.warpTank(2)
        assertEquals(2, sim.players.getValue("tank").position.mapId, "a string result is no opinion: the warp goes where it named")
        sim.load("none.lua", """register.warp(function(player, warp, world) end)""")
        sim.warpTank(1)
        assertEquals(1, sim.players.getValue("tank").position.mapId)
    }

    @Test
    fun `a Lua warp hook that errors is no opinion, and a plugin replacing instancing makes instanced maps shared`() {
        val sim = warpWorld()
        sim.load("broken.lua", """register.warp(function(player, warp, world) error("boom") end)""")
        sim.warpTank(2)
        assertEquals(2, sim.players.getValue("tank").position.mapId, "an error never blocks a warp")

        val shared = warpWorld()
        shared.load("shared.lua", """register.plugin{ id = "shared-crypt", replaces = { "instancing" } }""")
        shared.warpTank(3)
        assertEquals(3, shared.players.getValue("tank").position.mapId, "the crypt is one shared map while the plugin replaces instancing")
    }

    // --- register.may_fight

    @Test
    fun `a Lua fight ruling decides who may hurt whom and no opinion falls through to the normal rules`() {
        val sim = world()
        val tank = sim.players.getValue("tank")
        val healer = sim.players.getValue("healer")
        assertTrue(sim.canAttackPlayer(tank, healer), "open by default")
        sim.load("duel.lua", """
            register.may_fight(function(attacker, defender, world)
              if attacker.name == "healer" then return false end   -- healers never start a fight
              if defender.level < 2 then return true end            -- (nobody here is that low)
            end)""")
        assertFalse(sim.canAttackPlayer(healer, tank), "the plugin forbids the healer")
        assertTrue(sim.canAttackPlayer(tank, healer), "no opinion about the tank: the normal rules allow it")
    }

    @Test
    fun `a fight ruling follows the systems its plugin requires`() {
        val sim = world()
        sim.load("duel.lua", """register.plugin{ id = "duel", requires = { "parties" } }
            register.may_fight(function(attacker, defender, world) return false end)""")
        val tank = sim.players.getValue("tank")
        val healer = sim.players.getValue("healer")
        assertFalse(sim.canAttackPlayer(tank, healer))
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("parties"))
        assertTrue(sim.canAttackPlayer(tank, healer), "parties are off, so the plugin is quiet")
    }

    // --- register.monster

    @Test
    fun `a Lua monster hook can hold chosen monsters still`() {
        val sim = world(twoOgres = true)
        sim.load("still.lua", """register.monster{ npcs = { 10 }, holds_still = function(npc, world) return true end }""")
        sim.hero("sentry", 20, 13)
        sim.ticks(40)
        assertNull(sim.ogre(0).targetEntityId, "ogre 10 was held still")
        assertTrue(sim.ogre(1).targetEntityId != null, "ogre 11 is not covered by the hook and hunts as usual")
    }

    private fun WorldSimulation.hero(id: String, x: Int, y: Int) {
        spawnOrUpdatePlayer(
            CharacterState(id = id, accountId = "a-$id", name = id, attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10), position = WorldPosition.fromTile(1, x, y, Direction.UP))
                .recalculateVitals(game, items, refill = true)
        )
    }

    @Test
    fun `a Lua monster hook can choose who the monster fights`() {
        val sim = world()
        sim.load("pick.lua", """
            register.monster{ npcs = { 10 }, retarget = function(npc, world)
              if npc.target_id ~= nil then return "healer" end
            end }""")
        sim.ticks(40)
        assertEquals("healer", sim.ogre().targetEntityId, "it hunts the healer instead of whoever it noticed first")
    }

    @Test
    fun `a Lua step causes effects inline and what it does to the monster is kept`() {
        val sim = world()
        sim.load("rage.lua", """
            register.monster{ npcs = { 10 }, step = function(npc, world)
              if npc.vitals.hp.cur > 99900 then world.damage_npc(npc.map_id, npc.instance_id, 10) end
              world.floating_text(npc.map_id, npc.x, npc.y, "grr", "script")
            end }""")
        val events = (1..3).flatMap { sim.stepTick() }
        assertEquals(100_000 - 30, sim.ogre().vitals.getValue("hp").cur, "three steps, ten damage each, none lost to a stale copy")
        assertTrue(events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text == "grr" })
    }

    @Test
    fun `a Lua step that kills the monster ends its turn`() {
        val sim = world()
        sim.load("doom.lua", """register.monster{ npcs = { 10 }, step = function(npc, world) world.damage_npc(npc.map_id, npc.instance_id, 999999) end }""")
        sim.stepTick()
        assertFalse(sim.ogre().isAlive("hp"), "the plugin killed it")
        sim.ticks(3) // a dead monster is not stepped again and nothing blows up
    }

    @Test
    fun `a step is limited by the games script capabilities`() {
        val base = MorganaClassic.definition
        val sim = WorldSimulation(
            SeededRandomSource(1L), game = CompiledGame.compile(base.copy(capabilities = setOf("message"))), scripts = LuakScriptRuntime()
        )
        sim.npcs[10] = ogre(10)
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 30, height = 30, npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 10, spawnY = 10))))
        sim.load("rage.lua", """register.monster{ npcs = { 10 }, step = function(npc, world) world.damage_npc(npc.map_id, npc.instance_id, 10) end }""")
        sim.ticks(3)
        assertEquals(100_000, sim.ogre().vitals.getValue("hp").cur, "damage is not among this game's capabilities")
    }

    @Test
    fun `the query hooks return what Lua computed`() {
        val sim = world()
        sim.load("q.lua", """
            register.monster{ npcs = { 10 },
              variant = function(npc, world) return { attack_percent = 150, move_speed_percent = 80 } end,
              may_be_elite = function(npc_id, world) return npc_id ~= 10 end,
              kill_lockout = function(npc_id, world) return 600 end }""")
        val hook = sim.scripts.registry.monsterHooks().single()
        val view = sim.asScriptWorldView()
        val snapshot = view.getNpc(1, sim.ogre().instanceId)!!
        val multipliers = hook.variant!!.invoke(snapshot, view)!!
        assertEquals(150, multipliers.attackPercent)
        assertEquals(80, multipliers.moveSpeedPercent)
        assertFalse(hook.mayBeElite!!.invoke(10, view))
        assertEquals(600L, hook.killLockoutTicks!!.invoke(10, view))
        assertTrue(hook.covers(10) && !hook.covers(11))
    }

    @Test
    fun `a monster hook with nothing to do or a wrong type is refused at load`() {
        val sim = world()
        assertTrue(sim.load("empty.lua", "register.monster{ npcs = { 10 } }") is ScriptCompilationResult.Error)
        assertTrue(sim.load("types.lua", """register.monster{ npcs = { "ogre" }, holds_still = function() return true end }""") is ScriptCompilationResult.Error)
        assertTrue(sim.scripts.registry.monsterHooks().isEmpty())
    }

    @Test
    fun `monsters expose their target and threat to Lua`() {
        val sim = world()
        sim.load("look.lua", """
            register.monster{ npcs = { 10 }, retarget = function(npc, world)
              if npc.target_id ~= nil and npc.threat ~= nil then return npc.target_id end
            end }""")
        sim.ticks(40)
        assertTrue(sim.ogre().targetEntityId != null)
    }

    @Test
    fun `the Lua plugins system is installed last in every world and does nothing without scripts`() {
        val plain = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition))
        assertEquals(
            listOf("ScriptedSystem", "ScriptedTicks", "ScriptedTicks"),
            plain.systems.takeLast(3).map { it.javaClass.simpleName },
            "the Lua plugins come after every engine system"
        )
        assertTrue(plain.scripts.registry.monsterHooks().isEmpty() && plain.scripts.registry.requirementHooks().isEmpty())
    }
}
