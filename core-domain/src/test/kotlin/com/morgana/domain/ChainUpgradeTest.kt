package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GearUpgradeDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.UpgradeCost
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A chain upgrade with a per-item cost: the Wood Sword becomes a Bronze Sword for metal, wood and gold, at the right level. */
class ChainUpgradeTest {
    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999)
    private val metal = ItemDefinition(id = 5, name = "Metal Scrap", type = ItemType.MATERIAL, stackable = true, maxStack = 99)
    private val wood = ItemDefinition(id = 6, name = "Timber", type = ItemType.MATERIAL, stackable = true, maxStack = 99)
    private val woodSword = ItemDefinition(
        id = 2, name = "Wood Sword", type = ItemType.WEAPON, attackDamage = 5, levelReq = 1, nextTierItemId = 3,
        upgradeCost = UpgradeCost(materials = mapOf(5 to 3, 6 to 2), gold = 50)
    )
    private val bronzeSword = ItemDefinition(id = 3, name = "Bronze Sword", type = ItemType.WEAPON, attackDamage = 9, levelReq = 6, sockets = 2)

    private fun sim(level: Int, vararg have: Pair<ItemDefinition, Int>): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(gearUpgrade = GearUpgradeDefinition()))
        val s = WorldSimulation(SeededRandomSource(4L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        listOf(gold, metal, wood, woodSword, bronzeSword).forEach { s.items[it.id] = it }
        var inv = Inventory()
        for ((def, n) in have) inv = inv.addItem(def, n).updatedInventory
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = level, inventory = inv,
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, plus = 4)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        return s
    }

    private fun WorldSimulation.upgrade(): List<WorldSimulationEvent> =
        mutableListOf<WorldSimulationEvent>().also {
            executeEventCommands("p1", 1, listOf(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_LEVEL, EquipmentSlot.WEAPON)), it)
        }

    private fun WorldSimulation.weapon() = players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!
    private fun WorldSimulation.carried(id: Int) = players.getValue("p1").inventory.totalCountOf(id)

    @Test
    fun `upgrades to the next item for all its materials and gold, keeping plus level and gaining its sockets`() {
        val s = sim(level = 6, metal to 3, wood to 2, gold to 60)
        s.upgrade()
        assertEquals(3, s.weapon().itemId)
        assertEquals(4, s.weapon().plus)
        assertEquals(listOf(0, 0), s.weapon().sockets)
        assertEquals(0, s.carried(5))
        assertEquals(0, s.carried(6))
        assertEquals(10, s.carried(1))
    }

    @Test
    fun `refused below the next item's level, taking nothing`() {
        val s = sim(level = 5, metal to 3, wood to 2, gold to 60)
        s.upgrade()
        assertEquals(2, s.weapon().itemId)
        assertEquals(3, s.carried(5))
        assertEquals(60, s.carried(1))
    }

    @Test
    fun `refused when any material or the gold is short, taking nothing`() {
        val s = sim(level = 10, metal to 3, wood to 1, gold to 60)
        s.upgrade()
        assertEquals(2, s.weapon().itemId)
        assertEquals(3, s.carried(5))
        assertEquals(1, s.carried(6))
        assertEquals(60, s.carried(1))
        val poor = sim(level = 10, metal to 3, wood to 2, gold to 49)
        poor.upgrade()
        assertEquals(2, poor.weapon().itemId)
        assertEquals(49, poor.carried(1))
    }
}
