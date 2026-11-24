package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.GearRules
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
    fun `a game can let broken gear keep a percentage of its stats`() {
        val brokenSword = ItemStack(2, durability = 0)
        val brokenPlate = ItemStack(3, durability = 0)
        val gear = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to brokenSword, EquipmentSlot.ARMOR to brokenPlate))
        val items = mapOf(2 to sword, 3 to plate)

        // Default (0): the original rule, a broken piece adds nothing and is not "active".
        assertEquals(0, gear.totalArmorDefense(items))
        assertEquals(null, gear.getActive(EquipmentSlot.WEAPON))
        assertEquals(GearRules.DEFAULT.unarmedDamage, gear.weaponBaseDamage(gear.getActive(EquipmentSlot.WEAPON), items))

        // 10%: plate 7 -> 1 (0.7 rounds half up), sword 10 -> 1, and the pieces count as worn.
        assertEquals(1, gear.totalArmorDefense(items, GearRules(brokenPercent = 10)))
        assertEquals(brokenSword, gear.getActive(EquipmentSlot.WEAPON, GearRules(brokenPercent = 10)))
        assertEquals(1, gear.weaponBaseDamage(gear.getActive(EquipmentSlot.WEAPON, GearRules(brokenPercent = 10)), items, GearRules(brokenPercent = 10)))

        // 50%: 7 -> 4 (3.5 rounds up), sword 10 -> 5. 100% is as good as new.
        assertEquals(4, gear.totalArmorDefense(items, GearRules(brokenPercent = 50)))
        assertEquals(5, gear.weaponBaseDamage(gear.getActive(EquipmentSlot.WEAPON, GearRules(brokenPercent = 50)), items, GearRules(brokenPercent = 50)))
        assertEquals(7, gear.totalArmorDefense(items, GearRules(brokenPercent = 100)))

        // Unbroken gear is never scaled, whatever the setting.
        val fresh = EquipmentLoadout(mapOf(EquipmentSlot.ARMOR to ItemStack(3)))
        assertEquals(7, fresh.totalArmorDefense(items, GearRules(brokenPercent = 10)))
    }

    @Test
    fun `broken gear percentage reaches attributes and vitals through the game rules`() {
        val ring = ItemDefinition(id = 4, name = "Ring", type = ItemType.RING, attributeBonuses = mapOf("str" to 20), vitalMaxBonuses = mapOf("hp" to 100))
        fun game(percent: Int) = CompiledGame.compile(MorganaClassic.definition.copy(durability = DurabilityDefinition(brokenStatPercent = percent)))
        val worn = CharacterState(id = "p", accountId = "a", name = "P", equipment = EquipmentLoadout(mapOf(EquipmentSlot.RING to ItemStack(4, durability = 0))))
        val items = mapOf(4 to ring)
        val base = worn.copy(equipment = EquipmentLoadout()).effectiveAttributes(game(0), items).getValue("str")

        assertEquals(base, worn.effectiveAttributes(game(0), items).getValue("str"), "0%: nothing")
        assertEquals(base + 2, worn.effectiveAttributes(game(10), items).getValue("str"), "10% of +20 STR")
        // Max HP also follows STR, so measure the vital bonus with an item that has no attribute bonus.
        val charm = ItemDefinition(id = 5, name = "Charm", type = ItemType.AMULET, vitalMaxBonuses = mapOf("hp" to 100))
        val charmed = worn.copy(equipment = EquipmentLoadout(mapOf(EquipmentSlot.AMULET to ItemStack(5, durability = 0))))
        val hp0 = charmed.recalculateVitals(game(0), mapOf(5 to charm)).vitals.getValue("hp").max
        val hp10 = charmed.recalculateVitals(game(10), mapOf(5 to charm)).vitals.getValue("hp").max
        assertEquals(10, hp10 - hp0, "10% of +100 max HP")
    }

    @Test
    fun `brokenStatPercent is read from the game, ignored when durability is off, and validated`() {
        assertEquals(0, MorganaClassic.definition.brokenGearStatPercent, "no durability section")
        assertEquals(10, MorganaClassic.definition.copy(durability = DurabilityDefinition(brokenStatPercent = 10)).brokenGearStatPercent)
        assertEquals(0, MorganaClassic.definition.copy(durability = DurabilityDefinition(enabled = false, brokenStatPercent = 10)).brokenGearStatPercent)
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(durability = DurabilityDefinition(brokenStatPercent = 10))).isEmpty())
        for (bad in listOf(-1, 101)) {
            val pointers = GameDefinitionValidator.validate(MorganaClassic.definition.copy(durability = DurabilityDefinition(brokenStatPercent = bad))).map { it.pointer }
            assertTrue("/durability/brokenStatPercent" in pointers, "$bad rejected: $pointers")
        }
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
