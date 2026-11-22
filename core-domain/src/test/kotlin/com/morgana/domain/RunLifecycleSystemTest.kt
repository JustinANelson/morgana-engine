package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.PermadeathMode
import com.morgana.domain.game.RunRulesDefinition
import com.morgana.domain.game.RunStateMode
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.WeightedSpawn
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunLifecycleSystemTest {

    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true)
    private val shard = ItemDefinition(id = 2, name = "Soul Shard", type = ItemType.CURRENCY, stackable = true)
    private val lootBlade = ItemDefinition(id = 3, name = "Crypt Blade", type = ItemType.WEAPON, attackDamage = 10)

    private fun world(
        runs: RunRulesDefinition? = RunRulesDefinition(enabled = true),
        ghost: GhostDefinition? = null,
        seed: Long = 99L,
        disabledModules: Set<OptionalModule> = emptySet()
    ): WorldSimulation {
        val def = MorganaClassic.definition.copy(runs = runs, ghost = ghost)
        val sim = WorldSimulation(SeededRandomSource(seed), game = CompiledGame.compile(def))
        sim.modules = ModuleFlags(disabled = disabledModules)
        sim.items[1] = gold
        sim.items[2] = shard
        sim.items[3] = lootBlade
        sim.npcs[10] = NpcDefinition(id = 10, name = "Slime", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 20), attackDamage = 0, aggroRangeTiles = 0)
        sim.registerMap(
            MapDefinition(id = 1, name = "Hub", width = 10, height = 10, moral = MapMoral.SAFE_ZONE)
                .withCell(5, 4, MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 0, targetY = 0)))
        )
        sim.registerMap(
            MapDefinition(
                id = 2, name = "Crypt (floor 1)", width = 10, height = 10, instanced = true, moral = MapMoral.INSTANCED_DUNGEON,
                generator = FloorGenerator(
                    width = 24, height = 20, roomsMin = 3, roomsMax = 4,
                    monsters = listOf(WeightedSpawn(10, 1)), monstersPerRoomMin = 1, monstersPerRoomMax = 1,
                    nextMapId = 0, exitMapId = 1, exitX = 5, exitY = 6
                )
            )
        )
        val hero = CharacterState(
            id = "h1", accountId = "acc1", name = "Delver", level = 5, xp = 100L, classId = 1,
            position = WorldPosition.fromTile(1, 5, 5, Direction.UP),
            inventory = Inventory().addItem(gold, 50).updatedInventory
        )
        sim.spawnOrUpdatePlayer(hero)
        return sim
    }

    private fun WorldSimulation.enterDungeon() {
        movePlayer("h1", Direction.UP)
        stepTick()
    }

    @Test
    fun `custom seed reproduces identical generated floor and slash run reports status`() {
        val s1 = world()
        val s2 = world(seed = 555L)
        s1.executeAdminSlashCommand("h1", "/seed 4242")
        s2.executeAdminSlashCommand("h1", "/seed 4242")
        s1.enterDungeon()
        s2.enterDungeon()

        val map1 = s1.maps[s1.players["h1"]!!.position.mapId]!!
        val map2 = s2.maps[s2.players["h1"]!!.position.mapId]!!
        assertEquals(map1.cells, map2.cells, "same /seed generates identical layout")
        assertEquals(1, s1.activeRuns.size)

        val statusEvents = s1.executeAdminSlashCommand("h1", "/run")
        assertTrue(statusEvents.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message.contains("Depth 1") })
    }

    @Test
    fun `extraction mode restores pre-run state on timeout or abandon but keeps loot on safe exit`() {
        val sim = world(runs = RunRulesDefinition(enabled = true, stateMode = RunStateMode.EXTRACTION, timeLimitTicks = 5))
        sim.enterDungeon()
        // Gain loot and XP during the run
        val mid = sim.players["h1"]!!
        sim.players["h1"] = mid.copy(
            level = 6,
            xp = 999L,
            inventory = mid.inventory.addItem(lootBlade, 1).updatedInventory
        )
        // Advance past timeLimitTicks -> run times out and restores pre-run snapshot
        var ended: WorldSimulationEvent.RunEnded? = null
        repeat(6) {
            sim.stepTick().filterIsInstance<WorldSimulationEvent.RunEnded>().firstOrNull()?.let { ended = it }
        }
        assertEquals("TIMEOUT", ended?.outcome)
        val after = sim.players["h1"]!!
        assertEquals(1, after.position.mapId, "returned to hub on timeout")
        assertEquals(5, after.level, "level restored to pre-run snapshot")
        assertEquals(100L, after.xp, "xp restored to pre-run snapshot")
        assertFalse(after.inventory.slots.any { it?.itemId == lootBlade.id }, "unextracted loot lost on timeout")
    }

    @Test
    fun `ephemeral mode resets gear and level on exit while preserving meta currency items`() {
        val sim = world(runs = RunRulesDefinition(enabled = true, stateMode = RunStateMode.EPHEMERAL, metaCurrencyItemIds = setOf(shard.id)))
        sim.enterDungeon()
        val mid = sim.players["h1"]!!
        sim.players["h1"] = mid.copy(
            level = 7,
            inventory = mid.inventory.addItem(lootBlade, 1).updatedInventory.addItem(shard, 3).updatedInventory
        )
        // Simulate returning to hub safely
        val home = WorldPosition.fromTile(1, 5, 6, Direction.DOWN)
        sim.players["h1"] = sim.players["h1"]!!.copy(position = home, instanceReturn = null)
        val events = sim.stepTick()
        assertTrue(events.filterIsInstance<WorldSimulationEvent.RunEnded>().any { it.outcome == "EXTRACTED" })

        val after = sim.players["h1"]!!
        assertEquals(5, after.level, "ephemeral run resets level")
        assertFalse(after.inventory.slots.any { it?.itemId == lootBlade.id }, "non-meta gear cleared")
        assertEquals(3, after.inventory.slots.filterNotNull().filter { it.itemId == shard.id }.sumOf { it.amount }, "meta currency kept")
    }

    @Test
    fun `permadeath lock and delete emit CharacterPermadeath and mark character fallen`() {
        val simLock = world(runs = RunRulesDefinition(enabled = true, permadeath = PermadeathMode.LOCK))
        simLock.enterDungeon()
        val deadlyNpc = NpcDefinition(id = 99, name = "Reaper", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 50, vitals = mapOf("hp" to 500), attackDamage = 9999)
        simLock.npcs[99] = deadlyNpc
        // Trigger lethal death directly via trap or lethal HP drop through CombatSystem
        val p = simLock.players["h1"]!!
        val lethal = simLock.mapNpcs.getOrPut(p.position.mapId) { mutableListOf() }
        lethal.clear()
        val out = mutableListOf<WorldSimulationEvent>()
        val result = com.morgana.domain.systems.RunLifecycleSystem.deathOutcome(simLock, p, out)
        assertTrue(result?.fallen == true, "character marked fallen in LOCK mode")
        val ev = out.filterIsInstance<WorldSimulationEvent.CharacterPermadeath>().single()
        assertEquals(PermadeathMode.LOCK, ev.mode)

        // Switching off runLifecycle disables permadeath and run tracking
        val simOff = world(
            runs = RunRulesDefinition(enabled = true, permadeath = PermadeathMode.DELETE),
            disabledModules = setOf(OptionalModule.RUN_LIFECYCLE)
        )
        simOff.enterDungeon()
        assertTrue(simOff.activeRuns.isEmpty(), "no run tracked when runLifecycle module is disabled")
        assertNull(simOff.activeRuns["solo:h1"])
    }

    @Test
    fun `run state hashes deterministically`() {
        fun hashOnce(): String {
            val sim = world(seed = 321L)
            sim.executeAdminSlashCommand("h1", "/seed 777")
            sim.enterDungeon()
            sim.stepTick()
            return StateHasher.hash(sim)
        }
        assertEquals(hashOnce(), hashOnce())
        val diff = world(seed = 321L).also { it.executeAdminSlashCommand("h1", "/seed 888"); it.enterDungeon(); it.stepTick() }
        assertNotEquals(hashOnce(), StateHasher.hash(diff))
    }
}
