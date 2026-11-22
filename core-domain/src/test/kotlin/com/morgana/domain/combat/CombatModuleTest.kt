package com.morgana.domain.combat

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CombatModuleTest {

    private fun createBaseSim(combat: CombatModule): WorldSimulation {
        val sim = WorldSimulation(combat = combat, game = MorganaClassic.COMPILED)
        val map = MapDefinition(id = 1, name = "Arena", width = 10, height = 10)
        sim.maps[1] = map

        val sword = ItemDefinition(
            id = 10,
            name = "Iron Sword",
            type = ItemType.WEAPON,
            attackDamage = 8,
            attackSpeedTicks = 5
        )
        sim.items[10] = sword

        val player = CharacterState(
            id = "p1",
            accountId = "acc1",
            name = "Hero",
            position = WorldPosition.fromTile(1, 2, 2, Direction.RIGHT),
            vitals = mapOf("hp" to VitalState(100, 100), "mp" to VitalState(50, 50)),
            attributes = mapOf("strength" to 10),
            inventory = Inventory(),
            equipment = EquipmentLoadout(mapOf(com.morgana.domain.item.EquipmentSlot.WEAPON to com.morgana.domain.item.ItemStack(10, 1))),
            lastAttackTick = -100L
        )
        sim.spawnOrUpdatePlayer(player)

        val npcDef = com.morgana.domain.content.NpcDefinition(
            id = 1,
            name = "Goblin",
            spriteId = 1,
            vitals = mapOf("hp" to 50),
            attackDamage = 5
        )
        sim.npcs[1] = npcDef

        val npc = MapNpcInstance(
            instanceId = "npc-1",
            spawnSlotIndex = 0,
            npcId = 1,
            mapId = 1,
            tileX = 3,
            tileY = 2,
            homeX = 3,
            homeY = 2,
            vitals = mapOf("hp" to VitalState(50, 50))
        )
        sim.mapNpcs.getOrPut(1) { mutableListOf() }.add(npc)
        return sim
    }

    @Test
    fun `RealtimeCombatModule handles melee attack and generic attack action`() {
        val sim = createBaseSim(RealtimeCombatModule())

        // Melee attack directly via playerAttack
        val events = sim.playerAttack("p1")
        assertTrue(events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.category.contains("DAMAGE") })

        val npc = sim.mapNpcs[1]?.first()
        assertNotNull(npc)
        assertTrue((npc?.vitals?.get("hp")?.cur ?: 50) < 50, "NPC should take damage from Classic combat")

        // Generic action routed to attack
        val actionEvents = sim.handleAction("p1", "attack")
        // player on cooldown since attackSpeedTicks is 5
        assertTrue(actionEvents.isEmpty())

        // Advance 15 ticks (past unarmed attackSpeed 12) and attack via SimInput.CustomAction
        repeat(15) { sim.stepTick() }

        val inputEvents = SimInput.CustomAction("p1", "attack").applyTo(sim)
        assertTrue(inputEvents.any { it is WorldSimulationEvent.FloatingTextEmitted && it.category.contains("DAMAGE") })
    }

    @Test
    fun `NoOpCombatModule performs no damage or attacks`() {
        val sim = createBaseSim(NoOpCombatModule())

        val events = sim.playerAttack("p1")
        assertTrue(events.isEmpty(), "NoOp combat should produce no attack events")

        val actionEvents = sim.handleAction("p1", "attack")
        assertTrue(actionEvents.isEmpty())

        val npc = sim.mapNpcs[1]?.first()
        assertEquals(50, npc?.vitals?.get("hp")?.cur, "NPC health should remain untouched in NoOp combat")
    }

    @Test
    fun `Script-registered custom action executes when unhandled by combat module`() {
        val sim = createBaseSim(RealtimeCombatModule())

        sim.scripts.registry.registerAction("cheer") { charId, params, world ->
            val player = world.getPlayer(charId) ?: return@registerAction emptyList()
            listOf(
                com.morgana.domain.script.ScriptEffect.EmitFloatingText(
                    mapId = player.mapId,
                    x = player.x,
                    y = player.y,
                    text = "Hooray! ${params["mood"] ?: "Happy"}",
                    category = "CHEER",
                    colorHex = "#22C55E"
                )
            )
        }

        val events = sim.handleAction("p1", "cheer", mapOf("mood" to "Excited"))
        assertEquals(1, events.size)
        val textEvent = events.first() as WorldSimulationEvent.FloatingTextEmitted
        assertEquals("Hooray! Excited", textEvent.text)
    }
}

