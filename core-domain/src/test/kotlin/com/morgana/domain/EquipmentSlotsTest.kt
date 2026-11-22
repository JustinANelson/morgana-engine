package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.EquipmentSlotDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EquipmentSlotsTest {
    private val garment = EquipmentSlot("GARMENT")
    private val cloak = ItemDefinition(id = 7, name = "Silk Cloak", type = ItemType.ARMOR, equipSlot = "GARMENT", armorDefense = 4, attributeBonuses = mapOf("str" to 2))
    private val plate = ItemDefinition(id = 8, name = "Plate", type = ItemType.ARMOR, armorDefense = 6)

    private val withGarment = MorganaClassic.definition.copy(
        equipmentSlots = EquipmentSlotDefinition.CLASSIC + EquipmentSlotDefinition("GARMENT", "Garment")
    )

    private fun sim(definition: com.morgana.domain.game.GameDefinition): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(2L), game = CompiledGame.compile(definition))
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.items[7] = cloak
        s.items[8] = plate
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
                inventory = Inventory().addItem(cloak, 1).updatedInventory.addItem(plate, 1).updatedInventory
            )
        )
        return s
    }

    @Test
    fun `a game without equipmentSlots has the classic nine and items derive their slot from their type`() {
        val classic = MorganaClassic.definition
        assertEquals(EquipmentSlot.CLASSIC.map { it.id }, classic.slots.map { it.id })
        assertEquals(EquipmentSlot.ARMOR, plate.equipmentSlotOrNull())
        assertEquals(garment, cloak.equipmentSlotOrNull(), "equipSlot overrides the type")
        assertNull(ItemDefinition(id = 1, name = "Rock", type = ItemType.MATERIAL).equipmentSlotOrNull())
    }

    @Test
    fun `an item can be worn in a game-defined slot next to the armor slot and both count`() {
        val s = sim(withGarment)
        s.useInventorySlot("p1", 0)
        s.useInventorySlot("p1", 1)
        val worn = s.players.getValue("p1").equipment
        assertEquals(7, worn.getEquipped(garment)?.itemId)
        assertEquals(8, worn.getEquipped(EquipmentSlot.ARMOR)?.itemId, "the garment did not displace the armor")
        assertEquals(10, worn.totalArmorDefense(s.items), "both pieces add defence")
        assertEquals(2, worn.totalAttributeBonuses(s.items)["str"])
    }

    @Test
    fun `a game that does not define the slot refuses the item and the content validator names the problem`() {
        val s = sim(MorganaClassic.definition)
        s.useInventorySlot("p1", 0)
        val player = s.players.getValue("p1")
        assertNull(player.equipment.getEquipped(garment))
        assertEquals(1, player.inventory.totalCountOf(7), "the cloak stays in the bag")

        val report = ContentValidator.validate(ContentCatalog(items = mapOf(7 to cloak)), MorganaClassic.definition)
        assertTrue(report.errors.any { it.pointer == "/equipSlot" && "GARMENT" in it.message }, "got ${report.issues}")
        assertTrue(ContentValidator.validate(ContentCatalog(items = mapOf(7 to cloak)), withGarment).errors.none { it.pointer == "/equipSlot" })
    }

    @Test
    fun `the equipmentSlots section is validated`() {
        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(withGarment).isEmpty())
        fun bad(slots: List<EquipmentSlotDefinition>) = GameDefinitionValidator.validate(classic.copy(equipmentSlots = slots))
        assertFalse(bad(emptyList()).isEmpty())
        assertFalse(bad(listOf(EquipmentSlotDefinition("SHIELD", "Off-Hand"))).isEmpty(), "WEAPON is required")
        assertFalse(bad(EquipmentSlotDefinition.CLASSIC + EquipmentSlotDefinition("WEAPON", "Again")).isEmpty(), "ids are unique")
        assertFalse(bad(EquipmentSlotDefinition.CLASSIC + EquipmentSlotDefinition("lower case", "Bad")).isEmpty(), "ids are upper-case words")
        assertFalse(bad(EquipmentSlotDefinition.CLASSIC + EquipmentSlotDefinition("CAPE", " ")).isEmpty(), "names are required")
        assertNotNull(classic.slots.firstOrNull { it.id == "AMMO" })
        val noAmmo = classic.copy(equipmentSlots = EquipmentSlotDefinition.CLASSIC.filter { it.id != "AMMO" })
        assertTrue(GameDefinitionValidator.validate(noAmmo).isEmpty(), "a game may drop slots it does not use")
    }
}
