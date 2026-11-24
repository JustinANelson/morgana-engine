package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.content.BossAbility
import com.morgana.domain.content.BossEncounter
import com.morgana.domain.content.BossPhase
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptPluginManifest
import com.morgana.domain.script.ScriptRequirementHook
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.monsterHoldsStill
import com.morgana.domain.sim.monsterKillLockout
import com.morgana.domain.sim.systemStatus
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.Stall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The unified system list: every engine system has one id, the `server.json` switch that goes with it, and the kernel only
 * calls a system while it is on and not replaced by a running plugin (ADR 0018).
 */
class SystemListTest {
    private val smite = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, cooldownTicks = 0, promotionReq = 3)
    private val trinket = ItemDefinition(id = 9, name = "Humming Trinket", type = ItemType.CONSUMABLE)

    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.spells[1] = smite
        s.items[9] = trinket
        for ((id, tile) in listOf("a" to 2, "b" to 4)) {
            s.spawnOrUpdatePlayer(
                CharacterState(
                    id = id, accountId = id, name = id.uppercase(), knownSpellIds = listOf(1),
                    position = WorldPosition.fromTile(1, tile, 2, Direction.DOWN),
                    inventory = Inventory().addItem(trinket, 1).updatedInventory
                )
            )
        }
        return s
    }

    private fun WorldSimulation.switchOff(vararg keys: String) {
        modules = ModuleFlags.fromDisabledKeys(keys.toList())
    }

    private fun List<WorldSimulationEvent>.cast() = any { it is WorldSimulationEvent.SpellCast }

    @Test
    fun `every installed engine system answers to a key in the unified list`() {
        val s = sim()
        val keys = s.systems.filterIsInstance<GameSystem>().map { it.key }
        assertEquals(s.systems.size, keys.size, "every engine system is a GameSystem")
        // The Lua plugins system is three objects (the system and a tick participant per stage) that share the one switch.
        assertEquals(keys.filter { it != "luaPlugins" }.size, keys.filter { it != "luaPlugins" }.toSet().size, "and no two others share a key")
        assertEquals(4, keys.count { it == "luaPlugins" }, "the Lua plugins system is four objects: the system, its warp router and a tick participant per stage")
        assertTrue(keys.all { OptionalModule.fromConfigKey(it) != null }, "each key is an OptionalModule, so server.json and the F1 panel know it")
        assertEquals(
            setOf("rebirth", "promotion", "arena", "runLifecycle", "boons", "skillTrees", "guildWar", "pets", "stalls", "durability", "gearUpgrades", "respec", "threat", "bossEncounters", "instancing", "scheduler", "luaPlugins"),
            keys.toSet()
        )
    }

    @Test
    fun `the switches the kernel checks itself are a short, deliberate list and everything else is an installed system`() {
        val s = sim()
        val installed = s.systems.filterIsInstance<GameSystem>().map { it.key }.toSet()
        val kernelChecked = OptionalModule.entries.map { it.configKey }.filter { it !in installed }.toSet()
        // A new OptionalModule has to be either an installed GameSystem or added here on purpose, with the kernel code that checks it.
        assertEquals(
            setOf(
                "parties", "guilds", "trading", "crafting", "projectiles", "subTileMovement", "softSeams", "rolePlayChat", "friends",
                "exploration", "ignore", "market", "mail", "guildBank"
            ),
            kernelChecked
        )
    }

    @Test
    fun `the status list has a row for every system with its switch and whether an object is installed`() {
        val s = sim()
        val rows = s.systemStatus().associateBy { it.key }
        assertEquals(OptionalModule.entries.map { it.configKey }, s.systemStatus().map { it.key })
        assertTrue(rows.getValue("threat").installed && rows.getValue("threat").active)
        assertFalse(rows.getValue("parties").installed, "parties is a switch the kernel checks itself, not an installed object")
        assertTrue(rows.getValue("guildWar").requires == setOf("guilds"))
        s.switchOff("threat", "parties")
        val after = s.systemStatus().associateBy { it.key }
        assertFalse(after.getValue("threat").switchedOn)
        assertFalse(after.getValue("threat").active)
        assertFalse(after.getValue("parties").active)
        assertTrue(after.getValue("arena").active)
    }

    @Test
    fun `switching promotion off stops its gate refusing, switching it on restores it`() {
        val s = sim()
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast(), "a spell that needs a rank is refused by default")
        s.switchOff("promotion")
        assertTrue(s.playerCastSpell("a", 1, null, 0, 0).cast(), "with the system off nothing asks for a rank")
        s.modules = ModuleFlags.ALL_ENABLED
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast())
    }

    @Test
    fun `switching stalls off lifts what an open stall forbids and drops the stall command`() {
        val s = sim()
        s.stalls["a"] = Stall("Wares").also { it.open = true }
        assertTrue(s.initiateTrade("a", "b").isEmpty(), "an open stall keeps its owner out of trades")
        assertTrue(s.executeAdminSlashCommand("a", "/stall add").isNotEmpty(), "the command answers with its usage")
        s.switchOff("stalls")
        assertTrue(s.initiateTrade("a", "b").isNotEmpty(), "with the system off the stall holds nobody")
        assertTrue(s.executeAdminSlashCommand("a", "/stall add").isEmpty(), "and /stall is no longer a command")
    }

    @Test
    fun `switching the Lua plugins system off silences every registered hook`() {
        val s = sim()
        s.scripts.registry.registerRequirementHook(ScriptRequirementHook("p") { _, _, _ -> "no" })
        s.spells[1] = smite.copy(promotionReq = 0)
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast(), "the hook refuses")
        s.switchOff("luaPlugins")
        assertTrue(s.playerCastSpell("a", 1, null, 0, 0).cast(), "with the system off the plugins do nothing")
    }

    @Test
    fun `monster systems follow their switch`() {
        val s = sim()
        val boss = NpcDefinition(
            id = 20, name = "Warden", behavior = NpcBehavior.ATTACK_ON_SIGHT, vitals = mapOf("hp" to 100),
            encounter = BossEncounter(phases = listOf(BossPhase(fromLifePercent = 100, abilities = listOf(BossAbility(spellId = 50))), BossPhase(fromLifePercent = 50)), lockoutTicks = 4000)
        )
        assertEquals(4000L, s.monsterKillLockout(boss))
        s.switchOff("bossEncounters")
        assertEquals(0L, s.monsterKillLockout(boss), "with boss encounters off a boss has no lockout")

        val pole = NpcDefinition(id = 30, name = "Pole", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 10), guildWarRole = "POLE")
        val inst = MapNpcInstance(instanceId = "x", npcId = 30, mapId = 1, tileX = 1, tileY = 1, homeX = 1, homeY = 1, spawnSlotIndex = 0, vitals = emptyMap())
        val fresh = sim()
        assertTrue(fresh.monsterHoldsStill(inst, pole), "Guild War poles stand still")
        fresh.switchOff("guildWar")
        assertFalse(fresh.monsterHoldsStill(inst, pole), "unless the Guild War system is off")
    }

    // --- a plugin replacing a system

    private val replacer = ScriptPluginManifest("rank-plugin", id = "ranks", replaces = setOf("promotion"))

    @Test
    fun `a running plugin that replaces a system takes it out of play`() {
        val s = sim()
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast(), "promotion refuses by default")
        s.scripts.registry.registerPlugin(replacer)
        assertTrue(s.playerCastSpell("a", 1, null, 0, 0).cast(), "the plugin replaces promotion, so the built-in gate is out of play")
        val row = s.systemStatus().first { it.key == "promotion" }
        assertEquals("ranks", row.replacedBy)
        assertTrue(row.switchedOn)
        assertFalse(row.active)
    }

    @Test
    fun `the replaced system comes back when the plugin cannot run because a system it requires is off`() {
        val s = sim()
        s.scripts.registry.registerPlugin(replacer.copy(requires = setOf("guilds")))
        assertTrue(s.playerCastSpell("a", 1, null, 0, 0).cast())
        s.switchOff("guilds")
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast(), "the plugin is quiet, so promotion is back")
        assertNull(s.systemStatus().first { it.key == "promotion" }.replacedBy)
    }

    @Test
    fun `replacing a system that is switched off changes nothing and a registry reset gives it back`() {
        val s = sim()
        s.scripts.registry.registerPlugin(replacer)
        s.scripts.registry.clear()
        assertFalse(s.playerCastSpell("a", 1, null, 0, 0).cast(), "no plugin, so promotion is back")
    }

    @Test
    fun `a custom behaviour that is not a GameSystem is always on`() {
        val s = sim()
        val loose = object : MonsterBehavior {
            override fun holdsStill(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition) = true
        }
        s.install(loose)
        s.switchOff(*OptionalModule.entries.map { it.configKey }.toTypedArray())
        val npc = NpcDefinition(id = 40, name = "N", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 1))
        val inst = MapNpcInstance(instanceId = "y", npcId = 40, mapId = 1, tileX = 1, tileY = 1, homeX = 1, homeY = 1, spawnSlotIndex = 0, vitals = emptyMap())
        assertTrue(s.monsterHoldsStill(inst, npc), "nothing switches it off")
    }
}
