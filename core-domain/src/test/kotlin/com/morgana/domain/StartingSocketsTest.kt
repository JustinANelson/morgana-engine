package com.morgana.domain

import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gear that names a starting socket count comes with that many empty sockets (ADR-017: every piece starts with its maximum). */
class StartingSocketsTest {
    private val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 10, sockets = 2)
    private val plain = ItemDefinition(id = 3, name = "Plain Sword", type = ItemType.WEAPON, attackDamage = 5)
    private val gem = ItemDefinition(id = 4, name = "Ruby", type = ItemType.MATERIAL, stackable = true, maxStack = 99, sockets = 3)

    @Test
    fun `a newly created piece starts with its empty sockets`() {
        val inv = Inventory(4).addItem(sword, 1).updatedInventory
        assertEquals(listOf(0, 0), inv.getSlot(0)!!.sockets)
    }

    @Test
    fun `gear without a socket count and stackable items start with none`() {
        val inv = Inventory(4).addItem(plain, 1).updatedInventory.addItem(gem, 5).updatedInventory
        assertTrue(inv.getSlot(0)!!.sockets.isEmpty())
        assertTrue(inv.getSlot(1)!!.sockets.isEmpty())
    }

    @Test
    fun `the starting count is capped at the engine maximum`() {
        val absurd = sword.copy(sockets = 99)
        assertEquals(ItemStack.MAX_SOCKETS, absurd.freshSockets().size)
    }
}
