package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.DualWieldDefinition
import com.morgana.domain.game.EquipmentSlotDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.GameDefinitionValidator
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
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DualWieldTest {
    private val blade = ItemDefinition(id = 2, name = "Blade", type = ItemType.WEAPON, attackDamage = 12)
    private val dagger = ItemDefinition(id = 3, name = "Dagger", type = ItemType.WEAPON, attackDamage = 20)
    private val axe = ItemDefinition(id = 6, name = "Axe", type = ItemType.WEAPON, attackDamage = 15)
    private val greatsword = ItemDefinition(id = 4, name = "Greatsword", type = ItemType.WEAPON, attackDamage = 30, twoHanded = true)
    private val shield = ItemDefinition(id = 5, name = "Shield", type = ItemType.SHIELD, armorDefense = 6)
    private val dummy = NpcDefinition(id = 1, name = "Dummy", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 5000), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1))

    private fun sim(dualWield: DualWieldDefinition?, classId: Int = 1, worn: Map<EquipmentSlot, ItemStack> = emptyMap(), bag: List<ItemDefinition> = emptyList()): WorldSimulation {
        val definition: GameDefinition = MorganaClassic.definition.copy(dualWield = dualWield)
        val s = WorldSimulation(SeededRandomSource(11L), game = CompiledGame.compile(definition))
        s.registerMap(MapDefinition(id = 1, name = "Yard", width = 10, height = 10))
        for (d in listOf(blade, dagger, greatsword, shield, axe)) s.items[d.id] = d
        s.npcs[1] = dummy
        var inv = Inventory()
        for (d in bag) inv = inv.addItem(d, 1).updatedInventory
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", classId = classId,
                position = WorldPosition.fromTile(1, 2, 5, Direction.RIGHT),
                inventory = inv, equipment = EquipmentLoadout(worn)
            )
        )
        s.mapNpcs[1] = mutableListOf(
            MapNpcInstance("n1", 0, 1, 1, 3, 5, 3, 5, vitals = mapOf("hp" to VitalState(5000, 5000)))
        )
        return s
    }

    private fun worn(s: WorldSimulation, slot: EquipmentSlot) = s.players.getValue("p1").equipment.getEquipped(slot)?.itemId
    private fun dummyHp(s: WorldSimulation) = s.mapNpcs.getValue(1).single().vitals.getValue("hp").cur

    /** Damage the dummy takes from one swing (the cooldown is waited out first). */
    private fun swing(s: WorldSimulation): Int {
        repeat(30) { s.stepTick() }
        val before = dummyHp(s)
        s.playerAttack("p1")
        return before - dummyHp(s)
    }

    @Test
    fun `a second one-handed weapon goes to the off-hand and a third swaps the main hand`() {
        val s = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2)), bag = listOf(dagger, axe))
        s.useInventorySlot("p1", 0)
        assertEquals(2, worn(s, EquipmentSlot.WEAPON))
        assertEquals(3, worn(s, EquipmentSlot.SHIELD), "the dagger is held in the off-hand")
        s.useInventorySlot("p1", 1)
        assertEquals(6, worn(s, EquipmentSlot.WEAPON), "the off-hand is taken, so the axe swaps the main hand")
        assertEquals(3, worn(s, EquipmentSlot.SHIELD), "the off-hand dagger stays")
        assertEquals(1, s.players.getValue("p1").inventory.totalCountOf(2), "the old blade went to the bag")
    }

    @Test
    fun `dual-wielding needs an allowed class a one-handed pair and an empty off-hand`() {
        val wrongClass = sim(DualWieldDefinition(classIds = setOf(1)), classId = 2, worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2)), bag = listOf(dagger))
        wrongClass.useInventorySlot("p1", 0)
        assertEquals(3, worn(wrongClass, EquipmentSlot.WEAPON), "a class that cannot dual-wield just swaps the main hand")
        assertNull(worn(wrongClass, EquipmentSlot.SHIELD))

        val off = sim(null, worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2)), bag = listOf(dagger))
        off.useInventorySlot("p1", 0)
        assertEquals(3, worn(off, EquipmentSlot.WEAPON), "without a dualWield section nothing changes")

        val twoHandedMain = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(4)), bag = listOf(dagger))
        twoHandedMain.useInventorySlot("p1", 0)
        assertEquals(3, worn(twoHandedMain, EquipmentSlot.WEAPON), "a two-handed main weapon leaves no off-hand")

        val withShield = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.SHIELD to ItemStack(5)), bag = listOf(dagger))
        withShield.useInventorySlot("p1", 0)
        assertEquals(5, worn(withShield, EquipmentSlot.SHIELD), "a worn shield is not displaced by a weapon")
        assertEquals(3, worn(withShield, EquipmentSlot.WEAPON))

        val bag = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.SHIELD to ItemStack(3)), bag = listOf(greatsword))
        bag.useInventorySlot("p1", 0)
        assertEquals(4, worn(bag, EquipmentSlot.WEAPON))
        assertNull(worn(bag, EquipmentSlot.SHIELD), "a two-handed weapon bumps the off-hand weapon to the bag")
    }

    @Test
    fun `the off-hand weapon strikes on its chance for its own damage scaled by damagePercent`() {
        val pair = mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.SHIELD to ItemStack(3))
        val never = swing(sim(DualWieldDefinition(strikeChancePercent = 0), worn = pair))
        val always = swing(sim(DualWieldDefinition(strikeChancePercent = 100), worn = pair))
        val fullDagger = swing(sim(DualWieldDefinition(strikeChancePercent = 100, damagePercent = 100), worn = pair))
        val shielded = swing(sim(DualWieldDefinition(strikeChancePercent = 100), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.SHIELD to ItemStack(5))))

        assertTrue(never > 0, "the main hand always lands")
        assertEquals(never, shielded, "a shield never strikes")
        assertTrue(always > never, "a sure strike adds damage ($never -> $always)")
        assertTrue(fullDagger > always, "100% damage hits harder than 80% ($always -> $fullDagger)")
        assertTrue(fullDagger - never > blade.attackDamage / 2, "the dagger is the stronger weapon, so its strike is meaningful")
    }

    @Test
    fun `the default strike chance and damage make a second weapon beat a shield`() {
        val defaults = DualWieldDefinition()
        assertTrue(defaults.strikeChancePercent >= 60 && defaults.damagePercent >= 75)
        // Expected extra damage per swing as a share of one full off-hand hit; a shield gives no damage at all.
        val expectedShare = defaults.strikeChancePercent * defaults.damagePercent / 100
        assertTrue(expectedShare >= 50, "about half a weapon more damage per swing on average (got $expectedShare%)")
    }

    @Test
    fun `an off-hand weapon's plus level and rolls do not inflate the main swing`() {
        val s = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.SHIELD to ItemStack(3, plus = 9)))
        val equipment = s.players.getValue("p1").equipment
        assertEquals(0, equipment.rolledAttackDamage(s.items), "the +9 dagger counts for the off-hand strike only")
        assertEquals(20 + 9 * ItemStack.PLUS_WEAPON_DAMAGE, equipment.weaponDamageOf(equipment.getEquipped(EquipmentSlot.SHIELD)!!, s.items))
        assertNotNull(equipment.getActiveOffHandWeapon(s.items))
        val shielded = sim(DualWieldDefinition(), worn = mapOf(EquipmentSlot.SHIELD to ItemStack(5)))
        assertNull(shielded.players.getValue("p1").equipment.getActiveOffHandWeapon(shielded.items))
    }

    @Test
    fun `the dualWield section is validated`() {
        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(classic.copy(dualWield = DualWieldDefinition())).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(dualWield = DualWieldDefinition(strikeChancePercent = 101))).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(dualWield = DualWieldDefinition(damagePercent = 0))).isEmpty())
        val noOffHand = classic.copy(dualWield = DualWieldDefinition(), equipmentSlots = EquipmentSlotDefinition.CLASSIC.filter { it.id != "SHIELD" })
        assertFalse(GameDefinitionValidator.validate(noOffHand).isEmpty(), "needs the off-hand slot")
    }
}
