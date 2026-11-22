package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GearUpgradeDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.GemEffect
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GearUpgradeTest {
    private val ball = ItemDefinition(id = 10, name = "Dragon Ball", type = ItemType.MATERIAL)
    private val meteor = ItemDefinition(id = 11, name = "Meteor", type = ItemType.MATERIAL)
    private val dragonGem = ItemDefinition(id = 12, name = "Dragon Gem", type = ItemType.MATERIAL, gem = GemEffect(attackDamage = 5, xpPercent = 10))
    private val tortoiseGem = ItemDefinition(id = 13, name = "Tortoise Gem", type = ItemType.MATERIAL, gem = GemEffect(armorDefense = 4, attributeBonuses = mapOf("str" to 3)))
    private val sword1 = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 10, levelReq = 1, nextTierItemId = 3)
    private val sword2 = ItemDefinition(id = 3, name = "Steel Sword", type = ItemType.WEAPON, attackDamage = 20, levelReq = 10)
    private val plate = ItemDefinition(id = 4, name = "Plate", type = ItemType.ARMOR, armorDefense = 5)

    private val loot = LootRules(
        rarities = listOf(
            RarityDefinition("normal", "Normal", weight = 10, unlabeled = true),
            RarityDefinition("refined", "Refined", weight = 10, affixesMin = 0, affixesMax = 0),
            RarityDefinition("super", "Super", weight = 10, affixesMin = 0, affixesMax = 0)
        )
    )

    private fun sim(level: Int = 20, mats: Map<ItemDefinition, Int> = emptyMap(), maxSockets: Int = 2, seed: Long = 9L): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(loot = loot, gearUpgrade = GearUpgradeDefinition(maxSockets = maxSockets)))
        val s = WorldSimulation(SeededRandomSource(seed), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        listOf(ball, meteor, dragonGem, tortoiseGem, sword1, sword2, plate).forEach { s.items[it.id] = it }
        var inv = Inventory()
        for ((def, n) in mats) inv = inv.addItem(def, n).updatedInventory
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = level, inventory = inv,
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, plus = 3), EquipmentSlot.ARMOR to ItemStack(4)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        return s
    }

    private fun WorldSimulation.run(vararg steps: EventCommand): List<WorldSimulationEvent> =
        mutableListOf<WorldSimulationEvent>().also { executeEventCommands("p1", 1, steps.toList(), it) }

    private fun WorldSimulation.weapon() = players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!
    private fun WorldSimulation.carried(id: Int) = players.getValue("p1").inventory.totalCountOf(id)

    @Test
    fun `dragon balls open sockets up to the maximum`() {
        val s = sim(mats = mapOf(ball to 5))
        val step = EventCommand.UpgradeGear(GearUpgradeKind.ADD_SOCKET, EquipmentSlot.WEAPON, 10)
        s.run(step); s.run(step); s.run(step)
        assertEquals(listOf(0, 0), s.weapon().sockets, "two sockets then refused")
        assertEquals(3, s.carried(10), "the refused third attempt took nothing")
        assertEquals(3, s.weapon().plus, "+ level is kept")
    }

    @Test
    fun `gems fill sockets and their bonuses count until the gear breaks`() {
        val s = sim(mats = mapOf(ball to 2, dragonGem to 1, tortoiseGem to 1))
        val open = EventCommand.UpgradeGear(GearUpgradeKind.ADD_SOCKET, EquipmentSlot.WEAPON, 10)
        s.run(open, open)
        val noGemYet = s.players.getValue("p1").equipment.rolledAttackDamage(s.items)
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.SOCKET_GEM, EquipmentSlot.WEAPON, 12))
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.SOCKET_GEM, EquipmentSlot.WEAPON, 0)) // 0 = any gem carried
        assertEquals(listOf(12, 13), s.weapon().sockets)
        val eq = s.players.getValue("p1").equipment
        assertEquals(noGemYet + 5, eq.rolledAttackDamage(s.items))
        assertEquals(4, eq.totalArmorDefense(s.items) - 5, "tortoise gem armour on top of the plate's 5")
        assertEquals(3, eq.totalAttributeBonuses(s.items)["str"])
        assertEquals(10, eq.totalGemXpPercent(s.items))
        assertEquals(2, eq.socketedGemCount())
        // a full weapon refuses another gem and keeps the gem
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.SOCKET_GEM, EquipmentSlot.WEAPON, 12))
        assertEquals(listOf(12, 13), s.weapon().sockets)
        // broken gear gives nothing
        s.players["p1"] = s.players.getValue("p1").let { it.copy(equipment = it.equipment.copy(equipped = it.equipment.equipped + (EquipmentSlot.WEAPON to s.weapon().copy(durability = 0)))) }
        assertEquals(0, s.players.getValue("p1").equipment.totalGemXpPercent(s.items))
    }

    @Test
    fun `meteors raise gear to its next tier keeping plus level and sockets, and failure still costs them`() {
        val s = sim(level = 20, mats = mapOf(meteor to 6, ball to 1))
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.ADD_SOCKET, EquipmentSlot.WEAPON, 10))
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_LEVEL, EquipmentSlot.WEAPON, 11, amount = 3))
        assertEquals(3, s.weapon().itemId)
        assertEquals(3, s.weapon().plus)
        assertEquals(listOf(0), s.weapon().sockets)
        assertEquals(3, s.carried(11))
        // the top tier has nowhere to go and keeps its meteors
        s.run(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_LEVEL, EquipmentSlot.WEAPON, 11, amount = 3))
        assertEquals(3, s.carried(11))
    }

    @Test
    fun `a level upgrade needs the player level, and an unlucky attempt uses the materials up`() {
        val low = sim(level = 5, mats = mapOf(meteor to 3))
        low.run(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_LEVEL, EquipmentSlot.WEAPON, 11, amount = 3))
        assertEquals(2, low.weapon().itemId)
        assertEquals(3, low.carried(11))

        var failures = 0
        repeat(40) { i ->
            val s = sim(mats = mapOf(meteor to 3), seed = 100L + i)
            s.run(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_LEVEL, EquipmentSlot.WEAPON, 11, amount = 3, successPercent = 30))
            if (s.weapon().itemId == 2) { failures++; assertEquals(0, s.carried(11), "failed attempts still consume") }
        }
        assertTrue(failures in 10..39, "a 30% chance fails often but not always (failures=$failures)")
    }

    @Test
    fun `dragon balls lift quality one rarity at a time until the best`() {
        val s = sim(mats = mapOf(ball to 5))
        val step = EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_QUALITY, EquipmentSlot.WEAPON, 10)
        assertNull(s.weapon().rolls)
        s.run(step)
        assertEquals("refined", s.weapon().rolls?.rarity)
        s.run(step)
        assertEquals("super", s.weapon().rolls?.rarity)
        s.run(step)
        assertEquals("super", s.weapon().rolls?.rarity)
        assertEquals(3, s.carried(10), "the third attempt was refused and took nothing")
        assertEquals(3, s.weapon().plus)
    }

    @Test
    fun `no gearUpgrade section means nothing happens`() {
        val s = sim(mats = mapOf(ball to 1))
        val plain = WorldSimulation(SeededRandomSource(1L), game = MorganaClassic.COMPILED)
        plain.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        plain.items[10] = ball; plain.items[2] = sword1
        plain.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2)))))
        plain.run(EventCommand.UpgradeGear(GearUpgradeKind.ADD_SOCKET, EquipmentSlot.WEAPON, 10))
        assertEquals(emptyList<Int>(), plain.weapon().sockets)
        assertEquals(1, s.carried(10))
    }
}
