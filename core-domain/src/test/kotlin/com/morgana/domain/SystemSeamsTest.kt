package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.PlayerActivity
import com.morgana.domain.sim.actionBlocked
import com.morgana.domain.sim.interruptActivities
import com.morgana.domain.sim.afterAttack
import com.morgana.domain.sim.afterStruck
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.KillObserver
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.ArenaSystem
import com.morgana.domain.systems.BoonSystem
import com.morgana.domain.systems.BossEncounterSystem
import com.morgana.domain.systems.RunLifecycleSystem
import com.morgana.domain.systems.SkillTreeSystem
import com.morgana.domain.systems.DurabilitySystem
import com.morgana.domain.systems.ThreatSystem
import com.morgana.domain.systems.GearUpgradeSystem
import com.morgana.domain.systems.GuildWarSystem
import com.morgana.domain.systems.InstancingSystem
import com.morgana.domain.systems.ScriptedWarps
import com.morgana.domain.systems.RespecSystem
import com.morgana.domain.systems.ScriptedSystem
import com.morgana.domain.systems.Stall
import com.morgana.domain.systems.StallSystem
import com.morgana.domain.systems.isStalled
import com.morgana.domain.systems.PetSystem
import com.morgana.domain.systems.PromotionSystem
import com.morgana.domain.systems.RebirthSystem
import com.morgana.domain.systems.SchedulerSystem
import com.morgana.domain.systems.WorldStateSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seams between the kernel and the systems built on it (ADR 0018): a system is an object registered with
 * [WorldSimulation.install], and the kernel only calls the interfaces. These tests act as a game's own system would.
 */
class SystemSeamsTest {
    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        for ((id, tile) in listOf("a" to 2, "b" to 4)) {
            s.spawnOrUpdatePlayer(
                CharacterState(
                    id = id, accountId = id, name = id.uppercase(), position = WorldPosition.fromTile(1, tile, 2, Direction.DOWN),
                    inventory = Inventory().addItem(TRINKET, 1).updatedInventory
                )
            )
        }
        s.items[TRINKET.id] = TRINKET
        return s
    }

    private fun names(list: List<Any>) = list.map { it.javaClass.simpleName }

    @Test
    fun `the engine's own systems are installed in order and each seam list is filled from what it implements`() {
        val s = sim()
        // The Lua warp router goes in just before instancing (a plugin sees a warp as it was made); the rest of the Lua plugins
        // come last: the system itself, then one tick participant per stage.
        assertEquals(
            listOf(
                RebirthSystem, PromotionSystem, ArenaSystem, GuildWarSystem, PetSystem, StallSystem, DurabilitySystem, GearUpgradeSystem, RespecSystem,
                ThreatSystem, BossEncounterSystem, RunLifecycleSystem, BoonSystem, SkillTreeSystem, SchedulerSystem, WorldStateSystem, ScriptedWarps, InstancingSystem, ScriptedSystem
            ),
            s.systems.take(19)
        )
        assertEquals(listOf("ScriptedTicks", "ScriptedTicks"), names(s.systems.drop(19)))
        assertEquals(listOf(ScriptedWarps, InstancingSystem), s.warpRouters)
        assertEquals(listOf(RebirthSystem, PromotionSystem, ScriptedSystem), s.requirementGates)
        assertEquals(listOf(RebirthSystem, PromotionSystem, ArenaSystem, DurabilitySystem, GearUpgradeSystem, RespecSystem), s.eventCommandHandlers)
        assertEquals(listOf(ArenaSystem, GuildWarSystem, PetSystem, StallSystem, RunLifecycleSystem, BoonSystem, SkillTreeSystem, SchedulerSystem, WorldStateSystem), s.chatCommandHandlers)
        assertEquals(listOf(SchedulerSystem), s.wallClockListeners)
        assertEquals(listOf(ScriptedSystem), s.scheduleHandlers)
        assertEquals(listOf(ScriptedSystem), s.worldObjectGates)
        assertEquals(listOf(ArenaSystem, GuildWarSystem, RunLifecycleSystem, ScriptedSystem), s.combatArbiters)
        assertEquals(listOf(ArenaSystem, GuildWarSystem, PetSystem, RunLifecycleSystem), s.tickParticipants.take(4))
        assertEquals(listOf("ScriptedTicks", "ScriptedTicks"), names(s.tickParticipants.drop(4)))
        assertEquals(listOf(PetSystem, RunLifecycleSystem, ScriptedSystem), s.killObservers)
        assertEquals(listOf(PetSystem, WorldStateSystem, ScriptedSystem), s.itemUseHandlers)
        assertEquals(listOf(StallSystem, ScriptedSystem), s.actionGates)
        assertEquals(listOf(StallSystem, ScriptedSystem), s.activityInterrupters)
        assertEquals(listOf(DurabilitySystem, ScriptedSystem), s.combatObservers)
        assertEquals(listOf(GuildWarSystem, ThreatSystem, BossEncounterSystem, ScriptedSystem), s.monsterBehaviors)
    }

    @Test
    fun `installing a system twice does nothing and one object can serve several seams`() {
        val s = sim()
        val both = object : TickParticipant, KillObserver {
            override val stage = TickStage.AFTER_NPCS
            override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {}
            override fun onKillReward(sim: WorldSimulation, killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>) {}
        }
        s.install(both)
        s.install(both)
        assertEquals(1, s.systems.count { it === both })
        assertEquals(1, s.tickParticipants.count { it === both })
        assertEquals(1, s.killObservers.count { it === both })
        assertFalse(s.requirementGates.any { (it as Any) === both }, "it does not implement that seam")
    }

    @Test
    fun `tick participants run at their stage in install order`() {
        val s = sim()
        val ran = mutableListOf<String>()
        fun participant(name: String, at: TickStage) = object : TickParticipant {
            override val stage = at
            override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) { ran += name }
        }
        s.install(participant("late-1", TickStage.AFTER_EVENTS))
        s.install(participant("early", TickStage.AFTER_NPCS))
        s.install(participant("late-2", TickStage.AFTER_EVENTS))
        s.stepTick()
        assertEquals(listOf("early", "late-1", "late-2"), ran)
        ran.clear()
        s.stepTick()
        assertEquals(listOf("early", "late-1", "late-2"), ran, "every tick")
    }

    @Test
    fun `a combat arbiter decides who may fight`() {
        val s = sim()
        val a = s.players.getValue("a")
        val b = s.players.getValue("b")
        assertTrue(s.canAttackPlayer(a, b), "open by default")
        s.install(object : CombatArbiter {
            override fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState) =
                if (attacker.id == "a") false else null
        })
        assertFalse(s.canAttackPlayer(a, b), "the arbiter forbids a")
        assertTrue(s.canAttackPlayer(b, a), "no opinion about b falls through to the normal rules")
    }

    @Test
    fun `a chat command handler owns its command and others fall through`() {
        val s = sim()
        s.install(object : ChatCommandHandler {
            override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
                if (parts.firstOrNull()?.lowercase() != "ping") return false
                out.add(sim.tellPlayer(player, "pong ${parts.drop(1).joinToString(" ")}".trim()))
                return true
            }
        })
        val events = s.executeAdminSlashCommand("a", "/ping there")
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && it.message == "pong there" })
        assertTrue(s.executeAdminSlashCommand("a", "/unknowncommand").none { it is WorldSimulationEvent.ChatEmitted && "pong" in it.message })
    }

    @Test
    fun `an item use handler gives an item a use of its own`() {
        val s = sim()
        s.install(object : ItemUseHandler {
            override fun use(sim: WorldSimulation, player: CharacterState, slotIndex: Int, item: ItemDefinition, out: MutableList<WorldSimulationEvent>): Boolean {
                if (item.id != TRINKET.id) return false
                out.add(sim.tellPlayer(player, "The trinket hums."))
                return true
            }
        })
        val events = s.useInventorySlot("a", 0)
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && it.message == "The trinket hums." })
        assertEquals(1, s.players.getValue("a").inventory.totalCountOf(TRINKET.id), "the handler decides whether it is used up")
    }

    @Test
    fun `an event command handler can take over a command ahead of the built in one`() {
        val s = sim()
        val seen = mutableListOf<EventCommand>()
        s.eventCommandHandlers.add(0, object : EventCommandHandler {
            override fun handle(sim: WorldSimulation, characterId: String, cmd: EventCommand, out: MutableList<WorldSimulationEvent>): Boolean {
                if (cmd !is EventCommand.Promote) return false
                seen += cmd
                return true
            }
        })
        s.executeEventCommands("a", 1, listOf(EventCommand.Promote), mutableListOf())
        assertEquals(listOf<EventCommand>(EventCommand.Promote), seen, "the game's handler took it; promotion never ran")
    }

    @Test
    fun `a system command in a game without that system is skipped`() {
        val s = sim()
        s.eventCommandHandlers.clear()
        val out = mutableListOf<WorldSimulationEvent>()
        s.executeEventCommands("a", 1, listOf(EventCommand.Promote, EventCommand.ShowMessage("still runs")), out)
        assertTrue(out.any { it is WorldSimulationEvent.ChatEmitted && it.message.contains("still runs") }, "the commands after it still run")
    }

    @Test
    fun `an action gate stops a character doing something and the others carry on`() {
        assertTrue(sim().initiateTrade("a", "b").isNotEmpty(), "trading is open by default")
        val gated = sim()
        gated.install(object : ActionGate {
            override fun blocks(sim: WorldSimulation, characterId: String, activity: PlayerActivity) =
                characterId == "a" && (activity == PlayerActivity.TRADE || activity == PlayerActivity.USE_ITEM)
        })
        assertTrue(gated.initiateTrade("a", "b").isEmpty(), "a may not trade")
        assertTrue(gated.initiateTrade("b", "a").isEmpty(), "nor be traded with")
        assertTrue(gated.useInventorySlot("a", 0).isEmpty(), "a may not use items")
        assertFalse(gated.actionBlocked("b", PlayerActivity.USE_ITEM), "b is unaffected")
        assertFalse(gated.actionBlocked("a", PlayerActivity.MOVE), "and a may still move")
    }

    @Test
    fun `the stall system keeps an open stall's owner in place through the action gate`() {
        val s = sim()
        s.stalls["a"] = Stall("Wares").also { it.open = true }
        assertTrue(s.isStalled("a"))
        assertTrue(s.useInventorySlot("a", 0).isEmpty(), "no item use while the stall is open")
        assertTrue(s.initiateTrade("a", "b").isEmpty(), "no trade either")
        s.stalls["a"]!!.open = false
        assertTrue(s.initiateTrade("a", "b").isNotEmpty(), "closing the stall frees them")
    }

    @Test
    fun `activity interrupters are told when a character is pulled away`() {
        val s = sim()
        val told = mutableListOf<String>()
        s.install(object : ActivityInterrupter {
            override fun interrupt(sim: WorldSimulation, characterId: String, out: MutableList<WorldSimulationEvent>) { told += characterId }
        })
        s.stalls["a"] = Stall("Wares").also { it.open = true }
        s.interruptActivities("a", mutableListOf())
        assertEquals(listOf("a"), told)
        assertFalse(s.isStalled("a"), "the stall system closed the stall")
    }

    @Test
    fun `combat observers see every blow and each can change the victim in turn`() {
        val s = sim()
        val log = mutableListOf<String>()
        fun observer(tag: String) = object : CombatObserver {
            override fun afterAttack(sim: WorldSimulation, attackerId: String, out: MutableList<WorldSimulationEvent>) { log += "$tag swing $attackerId" }
            override fun afterStruck(sim: WorldSimulation, victim: CharacterState, out: MutableList<WorldSimulationEvent>) =
                victim.copy(name = victim.name + tag)
        }
        s.install(observer("1"))
        s.install(observer("2"))
        val out = mutableListOf<WorldSimulationEvent>()
        s.afterAttack("a", out)
        assertEquals(listOf("1 swing a", "2 swing a"), log)
        assertEquals("A12", s.afterStruck(s.players.getValue("a"), out).name, "each observer sees the previous one's result")
    }

    @Test
    fun `gear event commands are carried out by their systems and skipped without them`() {
        val s = sim()
        val out = mutableListOf<WorldSimulationEvent>()
        s.executeEventCommands("a", 1, listOf(EventCommand.RepairGear(1), EventCommand.RespecAttributes(1, 0)), out)
        assertTrue(out.any { it is WorldSimulationEvent.ChatEmitted }, "respec tells a player with no spent points so")

        val bare = sim().also { it.eventCommandHandlers.clear() }
        val none = mutableListOf<WorldSimulationEvent>()
        bare.executeEventCommands("a", 1, listOf(EventCommand.RespecAttributes(1, 0)), none)
        assertTrue(none.isEmpty(), "no handler, no effect")
    }

    @Test
    fun `the kernel hands out the same objects it installed`() {
        val s = sim()
        assertSame(ArenaSystem, s.combatArbiters.first())
        assertSame(PetSystem, s.killObservers.first())
    }

    private companion object {
        val TRINKET = ItemDefinition(id = 9, name = "Humming Trinket", type = ItemType.CONSUMABLE)
    }
}
