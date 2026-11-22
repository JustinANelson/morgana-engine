package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.systems.wearArmor
import com.morgana.domain.systems.wearWeapon
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DurabilityTest {
    private val silver = ItemDefinition(id = 1, name = "Silver", type = ItemType.CURRENCY, stackable = true, maxStack = 9999)
    private val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 10, price = 1000)
    private val plate = ItemDefinition(id = 3, name = "Plate", type = ItemType.ARMOR, armorDefense = 7, price = 500)

    private fun sim(wearPercent: Int = 100): WorldSimulation {
        val game = CompiledGame.compile(
            MorganaClassic.definition.copy(durability = DurabilityDefinition(weaponWearChancePercent = wearPercent, armorWearChancePercent = wearPercent))
        )
        val s = WorldSimulation(SeededRandomSource(3L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.items[1] = silver; s.items[2] = sword; s.items[3] = plate
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.ARMOR to ItemStack(3)))
            )
        )
        return s
    }

    @Test
    fun `repair cost is a percent of price and at least one`() {
        val d = DurabilityDefinition(repairPricePercent = 20)
        assertEquals(0, d.repairCost(1000, 0))
        assertEquals(200, d.repairCost(1000, 100))
        assertEquals(100, d.repairCost(1000, 50))
        assertEquals(1, d.repairCost(1, 1))
    }

    @Test
    fun `landed hits wear the weapon until it breaks and a broken weapon adds nothing`() {
        val s = sim()
        val events = mutableListOf<WorldSimulationEvent>()
        repeat(100) { s.wearWeapon("p1", events) }
        val weapon = s.players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!
        assertEquals(0, weapon.durability)
        assertEquals(null, s.players.getValue("p1").equipment.getActive(EquipmentSlot.WEAPON))
        assertEquals(1, events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().count { it.message.contains("broke!") })
    }

    @Test
    fun `taking hits wears worn armor and broken armor gives no defense`() {
        val s = sim()
        var p = s.players.getValue("p1")
        val full = p.equipment.totalArmorDefense(s.items)
        assertTrue(full >= 7)
        repeat(100) { p = s.wearArmor(p, mutableListOf()) }
        assertEquals(0, p.equipment.getEquipped(EquipmentSlot.ARMOR)!!.durability)
        assertEquals(0, p.equipment.totalArmorDefense(s.items))
    }

    @Test
    fun `without a durability section gear never wears`() {
        val s = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.items[2] = sword
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2)))))
        repeat(50) { s.wearWeapon("p1", mutableListOf()) }
        assertEquals(100, s.players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!.durability)
    }

    private fun worn(s: WorldSimulation, silverAmount: Int) {
        val p = s.players.getValue("p1")
        s.players["p1"] = p.copy(
            equipment = EquipmentLoadout(
                mapOf(EquipmentSlot.WEAPON to ItemStack(2, durability = 0), EquipmentSlot.ARMOR to ItemStack(3, durability = 50))
            ),
            inventory = Inventory().addItem(silver, silverAmount).updatedInventory
        )
    }

    @Test
    fun `repair gear charges the full price or repairs nothing`() {
        val s = sim()
        // weapon: 1000 * 100 * 20% = 200, armor: 500 * 50 * 20% = 50
        worn(s, 249)
        mutableListOf<WorldSimulationEvent>().also { s.executeEventCommands("p1", 1, listOf(EventCommand.RepairGear(1)), it) }
        assertEquals(0, s.players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!.durability, "249 silver is not enough")
        assertEquals(249, s.players.getValue("p1").inventory.totalCountOf(1))

        worn(s, 300)
        mutableListOf<WorldSimulationEvent>().also { s.executeEventCommands("p1", 1, listOf(EventCommand.RepairGear(1)), it) }
        val after = s.players.getValue("p1")
        assertEquals(100, after.equipment.getEquipped(EquipmentSlot.WEAPON)!!.durability)
        assertEquals(100, after.equipment.getEquipped(EquipmentSlot.ARMOR)!!.durability)
        assertEquals(50, after.inventory.totalCountOf(1))
    }
}
