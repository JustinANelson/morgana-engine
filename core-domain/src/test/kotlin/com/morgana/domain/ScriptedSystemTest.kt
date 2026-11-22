package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.script.ScriptFightHook
import com.morgana.domain.script.ScriptMonsterHook
import com.morgana.domain.script.ScriptPluginManifest
import com.morgana.domain.script.ScriptRequirementHook
import com.morgana.domain.script.ScriptVariantMultipliers
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.adjustedVariant
import com.morgana.domain.sim.monsterKillLockout
import com.morgana.domain.sim.monsterMayBeElite
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ScriptedSystem` is the adapter that turns what Lua plugins register into kernel seams. These tests register hooks straight
 * in the registry (no Lua), so the adapter's own rules are pinned down here: ordering, plugin requirements, validity checks and
 * what happens to a monster that a step changes. `LuaPluginSeamsTest` in `:script-luak` runs the same seams with real Lua.
 */
class ScriptedSystemTest {
    private val ogreDef = NpcDefinition(
        id = 10, name = "Ogre", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 8, vitals = mapOf("hp" to 100_000),
        attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10), attackDamage = 1
    )

    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition))
        sim.npcs[10] = ogreDef
        sim.spells[1] = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, cooldownTicks = 0)
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 30, height = 30, npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 10, spawnY = 10))))
        for ((id, x) in listOf("tank" to 10, "healer" to 12)) {
            sim.spawnOrUpdatePlayer(
                CharacterState(id = id, accountId = "a-$id", name = id, knownSpellIds = listOf(1), attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10), position = WorldPosition.fromTile(1, x, 13, Direction.UP))
                    .recalculateVitals(sim.game, sim.items, refill = true)
            )
        }
        return sim
    }

    private fun WorldSimulation.ogre() = mapNpcs.getValue(1).single()
    private fun List<WorldSimulationEvent>.cast() = any { it is WorldSimulationEvent.SpellCast }

    @Test
    fun `with no hooks registered the adapter has no opinion and costs nothing`() {
        val sim = world()
        assertTrue(sim.playerCastSpell("tank", 1, null, 0, 0).cast())
        assertTrue(sim.canAttackPlayer(sim.players.getValue("tank"), sim.players.getValue("healer")))
        assertTrue(sim.monsterMayBeElite(ogreDef))
        assertEquals(0L, sim.monsterKillLockout(ogreDef))
    }

    @Test
    fun `the first requirement hook with a reason wins and a blank reason is no objection`() {
        val sim = world()
        sim.scripts.registry.registerRequirementHook(ScriptRequirementHook("a") { _, _, _ -> "   " })
        assertTrue(sim.playerCastSpell("tank", 1, null, 0, 0).cast(), "a blank reason does not refuse")
        sim.scripts.registry.registerRequirementHook(ScriptRequirementHook("b") { subject, _, _ -> "no ${subject.kind} ${subject.name}" })
        sim.scripts.registry.registerRequirementHook(ScriptRequirementHook("c") { _, _, _ -> "second" })
        val events = sim.playerCastSpell("tank", 1, null, 0, 0)
        assertFalse(events.cast())
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && "no spell Smite" in it.message })
    }

    @Test
    fun `a hook is quiet while a system its plugin requires is off`() {
        val sim = world()
        sim.scripts.registry.registerPlugin(ScriptPluginManifest("a", "needs-guilds", requires = setOf("guilds", "stocks:market")))
        sim.scripts.registry.registerRequirementHook(ScriptRequirementHook("a") { _, _, _ -> "no" })
        assertFalse(sim.playerCastSpell("tank", 1, null, 0, 0).cast(), "everything it requires is on")
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("stocks:market"))
        assertTrue(sim.playerCastSpell("tank", 1, null, 0, 0).cast(), "a plugin-registered system counts too")
        sim.modules = ModuleFlags.ALL_ENABLED
        assertFalse(sim.playerCastSpell("tank", 1, null, 0, 0).cast())
    }

    @Test
    fun `fight hooks are asked in order and the first ruling wins`() {
        val sim = world()
        val tank = sim.players.getValue("tank")
        val healer = sim.players.getValue("healer")
        sim.scripts.registry.registerFightHook(ScriptFightHook("a") { _, _, _ -> null })
        sim.scripts.registry.registerFightHook(ScriptFightHook("b") { a, _, _ -> if (a.id == "tank") false else null })
        sim.scripts.registry.registerFightHook(ScriptFightHook("c") { _, _, _ -> true })
        assertFalse(sim.canAttackPlayer(tank, healer), "b rules before c")
        assertTrue(sim.canAttackPlayer(healer, tank), "b has no opinion about the healer, c allows it")
    }

    @Test
    fun `variant hooks multiply into the monster variant, one after another, only for the monsters they cover`() {
        val sim = world()
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("a", npcIds = setOf(10), variant = { _, _ -> ScriptVariantMultipliers(attackPercent = 200, moveSpeedPercent = 50) }))
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("b", variant = { _, _ -> ScriptVariantMultipliers(attackPercent = 150) }))
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("c", npcIds = setOf(99), variant = { _, _ -> ScriptVariantMultipliers(attackPercent = 1000) }))
        val adjusted = sim.adjustedVariant(sim.ogre(), sim.npcVariant(sim.ogre()))!!
        assertEquals(300, adjusted.attackPercent, "100 x 200% x 150%; the hook for monster 99 does not apply")
        assertEquals(50, adjusted.moveSpeedPercent)
    }

    @Test
    fun `a retarget names a player who must be alive on the monster's map or it is ignored`() {
        val sim = world()
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("a", retarget = { _, _ -> "ghost-of-nobody" }))
        sim.ticks(40)
        assertTrue(sim.ogre().targetEntityId != "ghost-of-nobody", "an unknown id is ignored")
        val sim2 = world()
        sim2.scripts.registry.registerMonsterHook(ScriptMonsterHook("a", retarget = { _, _ -> "healer" }))
        sim2.ticks(40)
        assertEquals("healer", sim2.ogre().targetEntityId)
    }

    private fun WorldSimulation.ticks(n: Int) = repeat(n) { stepTick() }

    @Test
    fun `a step that hurts its own monster is not undone by the stale copy the kernel holds`() {
        val sim = world()
        sim.scripts.registry.registerMonsterHook(
            ScriptMonsterHook("a", npcIds = setOf(10), step = { npc, _ -> listOf(ScriptEffect.DamageNpc(npc.mapId, npc.instanceId, 7)) })
        )
        sim.ticks(4)
        assertEquals(100_000 - 28, sim.ogre().vitals.getValue("hp").cur)
    }

    @Test
    fun `elite and lockout hooks combine as one no and the longest lockout`() {
        val sim = world()
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("a", mayBeElite = { _, _ -> true }, killLockoutTicks = { _, _ -> 100L }))
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("b", mayBeElite = { _, _ -> false }, killLockoutTicks = { _, _ -> null }))
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("c", killLockoutTicks = { _, _ -> -50L }))
        assertFalse(sim.monsterMayBeElite(ogreDef))
        assertEquals(100L, sim.monsterKillLockout(ogreDef))
    }

    private val everyKindOfEffectOnA = listOf(
        ScriptEffect.DamagePlayer("tank", 5),
        ScriptEffect.HealPlayer("tank", 5),
        ScriptEffect.GiveItem("tank", 9, 2),
        ScriptEffect.RemoveItem("tank", 9, 1),
        ScriptEffect.WarpPlayer("tank", 1, 5, 5),
        ScriptEffect.ApplyStatus("tank", com.morgana.domain.stats.ActiveStatusEffect(id = "s", type = com.morgana.domain.stats.StatusEffectType.TAUNT, sourceEntityId = "healer", magnitude = 0, remainingTicks = 50, tickInterval = 10)),
        ScriptEffect.RemoveStatus("tank", com.morgana.domain.stats.StatusEffectType.TAUNT),
        ScriptEffect.AwardExperience("tank", 100),
        ScriptEffect.ConsumeVital("tank", "hp", 3)
    )

    @Test
    fun `an interrupt hook cannot change the character being interrupted but can reach everyone else`() {
        val sim = world()
        val before = sim.players.getValue("tank")
        val beforeB = sim.players.getValue("healer")
        sim.scripts.registry.registerInterruptHook(
            com.morgana.domain.script.ScriptInterruptHook("p") { _, _ ->
                everyKindOfEffectOnA + listOf(
                    ScriptEffect.SendPlayerMessage("tank", "bye"),
                    ScriptEffect.WarpPlayer("healer", 1, 6, 6),
                    ScriptEffect.SetState("k", mapOf("v" to "1"))
                )
            }
        )
        val out = mutableListOf<WorldSimulationEvent>()
        com.morgana.domain.systems.ScriptedSystem.interrupt(sim, "tank", out)
        assertEquals(before, sim.players.getValue("tank"), "nothing that would change the interrupted character was applied")
        assertTrue(out.any { it is WorldSimulationEvent.ChatEmitted && "bye" in it.message }, "a message to them is fine")
        assertEquals(6, sim.players.getValue("healer").position.tileX, "another character can be changed")
        assertTrue(sim.players.getValue("healer") != beforeB)
        assertEquals(mapOf("v" to "1"), sim.scriptState["k"])
    }

    @Test
    fun `a struck hook is held to the same rule but an attack or kill hook may change its character`() {
        val sim = world()
        val before = sim.players.getValue("tank")
        sim.scripts.registry.registerCombatObserverHook(
            com.morgana.domain.script.ScriptCombatObserverHook("p", afterStruck = { _, _ -> everyKindOfEffectOnA })
        )
        val returned = com.morgana.domain.systems.ScriptedSystem.afterStruck(sim, before, mutableListOf())
        assertEquals(before, returned)
        assertEquals(before, sim.players.getValue("tank"), "the struck character is untouched")

        sim.scripts.registry.registerKillHook(com.morgana.domain.script.ScriptKillHook("p") { _, xp, _ -> listOf(ScriptEffect.GiveItem("tank", 9, xp.toInt())) })
        sim.items[9] = com.morgana.domain.item.ItemDefinition(id = 9, name = "Prize", type = com.morgana.domain.item.ItemType.CONSUMABLE)
        com.morgana.domain.systems.ScriptedSystem.onKillReward(sim, before, 4L, mutableListOf())
        assertEquals(4, sim.players.getValue("tank").inventory.totalCountOf(9), "a kill hook changes the killer through the map the engine re-reads")
    }

    @Test
    fun `tick hooks run at their stage, on multiples of their period, and only while their plugin can run`() {
        val sim = world()
        val ran = mutableListOf<String>()
        sim.scripts.registry.registerTickHook(com.morgana.domain.script.ScriptTickHook("p", com.morgana.domain.script.ScriptTickStage.AFTER_NPCS, 1) { ran += "npcs"; emptyList() })
        sim.scripts.registry.registerTickHook(com.morgana.domain.script.ScriptTickHook("p", com.morgana.domain.script.ScriptTickStage.AFTER_EVENTS, 4) { ran += "events"; emptyList() })
        sim.ticks(8)
        assertEquals(8, ran.count { it == "npcs" })
        assertEquals(2, ran.count { it == "events" }, "every 4th tick of 8")
        ran.clear()
        sim.scripts.registry.registerPlugin(ScriptPluginManifest("p", "p", requires = setOf("guilds")))
        sim.modules = ModuleFlags.fromDisabledKeys(listOf("guilds"))
        sim.ticks(8)
        assertTrue(ran.isEmpty(), "the plugin requires guilds, which are off")
    }

    @Test
    fun `an item use hook only applies its effects when it takes the item, and an action gate only asks about its activities`() {
        val sim = world()
        sim.items[9] = com.morgana.domain.item.ItemDefinition(id = 9, name = "Prize", type = com.morgana.domain.item.ItemType.CONSUMABLE)
        var asked = 0
        sim.scripts.registry.registerItemUseHook(
            com.morgana.domain.script.ScriptItemUseHook("p", setOf(9)) { _, _, _, _ ->
                asked++
                com.morgana.domain.script.ScriptItemUseResult(handled = asked > 1, effects = listOf(ScriptEffect.SendPlayerMessage("tank", "used")))
            }
        )
        val player = sim.players.getValue("tank")
        val item = sim.items.getValue(9)
        val declined = mutableListOf<WorldSimulationEvent>()
        assertFalse(com.morgana.domain.systems.ScriptedSystem.use(sim, player, 0, item, declined))
        assertTrue(declined.isEmpty(), "a declined use causes nothing")
        val taken = mutableListOf<WorldSimulationEvent>()
        assertTrue(com.morgana.domain.systems.ScriptedSystem.use(sim, player, 0, item, taken))
        assertTrue(taken.any { it is WorldSimulationEvent.ChatEmitted && "used" in it.message })
        assertEquals(2, asked)
        // a hook limited to item 9 is never asked about another item
        assertFalse(com.morgana.domain.systems.ScriptedSystem.use(sim, player, 0, item.copy(id = 10), mutableListOf()))
        assertEquals(2, asked)

        var gateAsked = 0
        sim.scripts.registry.registerActionGateHook(com.morgana.domain.script.ScriptActionGateHook("p", setOf("cast")) { _, _, _ -> gateAsked++; true })
        assertFalse(com.morgana.domain.systems.ScriptedSystem.blocks(sim, "tank", com.morgana.domain.sim.PlayerActivity.MOVE))
        assertEquals(0, gateAsked, "movement is not one of its activities, so Lua is not even called")
        assertTrue(com.morgana.domain.systems.ScriptedSystem.blocks(sim, "tank", com.morgana.domain.sim.PlayerActivity.CAST))
        assertEquals(1, gateAsked)
    }

    @Test
    fun `resetting the registry removes plugins and every hook`() {
        val sim = world()
        sim.scripts.registry.registerPlugin(ScriptPluginManifest("a", "p"))
        sim.scripts.registry.registerRequirementHook(ScriptRequirementHook("a") { _, _, _ -> "no" })
        sim.scripts.registry.registerMonsterHook(ScriptMonsterHook("a", holdsStill = { _, _ -> true }))
        sim.scripts.registry.registerFightHook(ScriptFightHook("a") { _, _, _ -> false })
        sim.scripts.registry.registerTickHook(com.morgana.domain.script.ScriptTickHook("a", "after_npcs", 1) { emptyList() })
        sim.scripts.registry.registerItemUseHook(com.morgana.domain.script.ScriptItemUseHook("a") { _, _, _, _ -> com.morgana.domain.script.ScriptItemUseResult(true) })
        sim.scripts.registry.registerActionGateHook(com.morgana.domain.script.ScriptActionGateHook("a") { _, _, _ -> true })
        sim.scripts.registry.registerInterruptHook(com.morgana.domain.script.ScriptInterruptHook("a") { _, _ -> emptyList() })
        sim.scripts.registry.registerCombatObserverHook(com.morgana.domain.script.ScriptCombatObserverHook("a", afterAttack = { _, _ -> emptyList() }))
        sim.scripts.registry.registerKillHook(com.morgana.domain.script.ScriptKillHook("a") { _, _, _ -> emptyList() })
        sim.scripts.registry.clear()
        assertNull(sim.scripts.registry.pluginOf("a"))
        assertTrue(sim.scripts.registry.requirementHooks().isEmpty() && sim.scripts.registry.monsterHooks().isEmpty() && sim.scripts.registry.fightHooks().isEmpty())
        assertTrue(
            sim.scripts.registry.tickHooks().isEmpty() && sim.scripts.registry.itemUseHooks().isEmpty() && sim.scripts.registry.actionGateHooks().isEmpty() &&
                sim.scripts.registry.interruptHooks().isEmpty() && sim.scripts.registry.combatObserverHooks().isEmpty() && sim.scripts.registry.killHooks().isEmpty()
        )
        assertTrue(sim.playerCastSpell("tank", 1, null, 0, 0).cast())
    }
}
