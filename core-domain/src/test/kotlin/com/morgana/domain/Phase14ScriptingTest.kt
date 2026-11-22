package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.handlePlayerDeath
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase14ScriptingTest {

    private fun world(npcHp: Int = 35, game: CompiledGame = MorganaClassic.COMPILED): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(7L), game = game)
        sim.items[1] = ItemDefinition(id = 1, name = "Potion", type = ItemType.CONSUMABLE, stackable = true, restore = mapOf("hp" to 10))
        sim.items[2] = ItemDefinition(
            id = 2, name = "Sword", type = ItemType.WEAPON, weaponStyle = WeaponStyle.MELEE, attackDamage = 12,
            attackSpeedTicks = 1, rangeTiles = 1
        )
        sim.spells[1] = SpellDefinition(
            id = 1, name = "Glow", targetType = SpellTargetType.SELF, costs = mapOf("mp" to 1), cooldownTicks = 1, basePower = 5, isHealing = true
        )
        sim.npcs[1] = NpcDefinition(
            id = 1, name = "Slime", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to npcHp),
            attributes = mapOf("str" to 2, "def" to 2, "int" to 2, "agi" to 2, "vit" to 2)
        )
        sim.registerMap(
            MapDefinition(
                id = 1, name = "Field", width = 12, height = 12,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 4, spawnY = 5, respawnTicks = 100))
            )
        )
        return sim
    }

    private fun hero(sim: WorldSimulation): CharacterState {
        val c = CharacterState(
            id = "p1", accountId = "a1", name = "Hero", attributes = mapOf("str" to 12, "def" to 12, "int" to 12, "agi" to 12, "vit" to 12),
            position = WorldPosition.fromTile(1, 3, 5, Direction.RIGHT),
            inventory = Inventory().addItem(sim.items.getValue(1), 2).updatedInventory
                .addItem(sim.items.getValue(2), 1).updatedInventory,
            knownSpellIds = listOf(1)
        )
        sim.spawnOrUpdatePlayer(c)
        sim.stepTick()
        return sim.players.getValue("p1")
    }

    @Test
    fun `using a consumable or equipment emits ItemUsed, and a failed use emits nothing`() {
        val sim = world()
        hero(sim)
        val potion = sim.useInventorySlot("p1", 0)
        assertTrue(potion.contains(WorldSimulationEvent.ItemUsed("p1", 1, 0)), potion.toString())
        val equip = sim.useInventorySlot("p1", 1)
        assertTrue(equip.any { it is WorldSimulationEvent.ItemUsed && it.itemId == 2 }, equip.toString())
        assertTrue(sim.useInventorySlot("p1", 30).none { it is WorldSimulationEvent.ItemUsed })
    }

    @Test
    fun `a successful cast emits SpellCast and a rejected cast does not`() {
        val sim = world()
        hero(sim)
        val cast = sim.playerCastSpell("p1", 1)
        assertTrue(cast.contains(WorldSimulationEvent.SpellCast("p1", 1, 1)), cast.toString())
        assertTrue(sim.playerCastSpell("p1", 1).none { it is WorldSimulationEvent.SpellCast }, "on cooldown")
        assertTrue(sim.playerCastSpell("p1", 99).none { it is WorldSimulationEvent.SpellCast }, "unknown spell")
    }

    @Test
    fun `killing an NPC emits NpcKilled naming the killer`() {
        val sim = world(npcHp = 1)
        hero(sim)
        sim.useInventorySlot("p1", 1) // equip the sword
        sim.movePlayer("p1", Direction.RIGHT)
        repeat(6) { sim.stepTick() }
        val killed = (1..10).flatMap { sim.playerAttack("p1") + sim.stepTick() }.filterIsInstance<WorldSimulationEvent.NpcKilled>()
        assertEquals(1, killed.size, "exactly one kill event")
        assertEquals(WorldSimulationEvent.NpcKilled("npc-1-0", 1, 1, "p1"), killed.single())
    }

    @Test
    fun `player death emits PlayerDied`() {
        val sim = world()
        val player = hero(sim)
        val out = mutableListOf<WorldSimulationEvent>()
        sim.handlePlayerDeath(player, sim.maps[1], out)
        assertTrue(out.contains(WorldSimulationEvent.PlayerDied("p1", 1)), out.toString())
    }

    @Test
    fun `custom formulas in game definition change real combat results deterministically`() {
        fun npcHpAfterOneHit(customGame: CompiledGame?): Int {
            val sim = world(npcHp = 500, game = customGame ?: MorganaClassic.COMPILED)
            hero(sim)
            sim.useInventorySlot("p1", 1)
            sim.movePlayer("p1", Direction.RIGHT)
            repeat(6) { sim.stepTick() }
            sim.playerAttack("p1")
            return sim.mapNpcs.getValue(1).first().vitals["hp"]?.cur ?: 0
        }
        val customDef = MorganaClassic.definition.copy(
            combat = MorganaClassic.definition.combat.copy(
                melee = "7",
                critChanceBp = "0"
            )
        )
        val flatGame = CompiledGame.compile(customDef)
        assertEquals(493, npcHpAfterOneHit(flatGame))
        assertEquals(npcHpAfterOneHit(flatGame), npcHpAfterOneHit(flatGame))
        assertTrue(npcHpAfterOneHit(null) < 500, "built-in formula still damages")
    }

    @Test
    fun `script effect execution obeys declared capabilities`() {
        val restrictedDef = MorganaClassic.definition.copy(
            capabilities = setOf(com.morgana.domain.script.ScriptCapability.MESSAGE)
        )
        val restrictedGame = CompiledGame.compile(restrictedDef)
        val sim = world(game = restrictedGame)
        val player = hero(sim)

        // Damage effect should be blocked because "damage" capability is not declared
        val blockedEvents = sim.applyScriptEffect(com.morgana.domain.script.ScriptEffect.DamagePlayer("p1", 10))
        assertTrue(blockedEvents.isEmpty(), "damage effect must be rejected when not in capabilities")
        assertEquals(player.vitals["hp"]?.cur, sim.players["p1"]?.vitals?.get("hp")?.cur)

        // Message effect should be permitted because "message" capability is declared
        val allowedEvents = sim.applyScriptEffect(com.morgana.domain.script.ScriptEffect.BroadcastMessage("Welcome!"))
        assertEquals(1, allowedEvents.size)
        assertTrue(allowedEvents.first() is WorldSimulationEvent.ChatEmitted)
    }
}
