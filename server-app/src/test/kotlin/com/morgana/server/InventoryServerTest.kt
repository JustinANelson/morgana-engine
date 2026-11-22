package com.morgana.server

import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The inventory and shop packets as a client sends them: sort the bag, and no trading away from a shop. */
class InventoryServerTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun system() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `sorting merges stacks and packs the bag, and the client is sent the result`(@TempDir dir: Path) {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        val s = login(c, "Ann")
        val me = c.simulation.players.values.first { it.name == "Ann" }
        val potion = c.simulation.items.values.first { it.type == ItemType.WEAPON } // the class's starting weapon, to have something to sort
        val bag = Inventory(maxSlots = me.inventory.maxSlots, slots = List(me.inventory.maxSlots) { i ->
            when (i) { 5 -> ItemStack(potion.id); else -> null }
        })
        c.simulation.players[me.id] = me.copy(inventory = bag)
        s.sent.clear()

        c.handleClientPacket(s, ClientPacket.SortInventory)

        val after = c.simulation.players.getValue(me.id).inventory
        assertEquals(potion.id, after.getSlot(0)?.itemId, "the item moved to the front")
        assertEquals(null, after.getSlot(5))
        assertTrue(s.sent.any { it is ServerPacket.CharacterSync }, "the client is told: ${s.sent.map { it::class.simpleName }}")
    }

    @Test
    fun `buying or selling without a shop open is refused with a message and changes nothing`(@TempDir dir: Path) {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        val s = login(c, "Ben")
        val me = c.simulation.players.values.first { it.name == "Ben" }
        val before = me.inventory
        // The blank game has no shop; add one in memory, as a player would find it on a shop tile.
        c.simulation.shops[77] = com.morgana.domain.content.ShopDefinition(
            id = 77, name = "Stall", currencyItemId = 1, entries = listOf(com.morgana.domain.content.ShopEntry(itemId = c.simulation.items.keys.first()))
        )
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.ShopBuy(77, 0, 1))
        c.handleClientPacket(s, ClientPacket.ShopSell(77, 0, 1))
        assertEquals(listOf("Open the shop first.", "Open the shop first."), s.system())
        assertEquals(before, c.simulation.players.getValue(me.id).inventory)
    }

    @Test
    fun `selling sends the buy-back list and buying back returns the item`(@TempDir dir: Path) {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        val s = login(c, "Cat")
        val me = c.simulation.players.values.first { it.name == "Cat" }
        val sword = c.simulation.items.values.first { it.type == ItemType.WEAPON && !it.boundOnPickup }
        val currency = com.morgana.domain.item.ItemDefinition(id = 9001, name = "Test Coin", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
        c.simulation.items[currency.id] = currency
        c.simulation.shops[77] = com.morgana.domain.content.ShopDefinition(id = 77, name = "Stall", currencyItemId = currency.id, entries = emptyList())
        c.simulation.shopSessions[me.id] = com.morgana.domain.sim.ShopSession(77, me.position.mapId, me.position.tileX, me.position.tileY)
        val slots = me.inventory.maxSlots
        c.simulation.players[me.id] = me.copy(inventory = Inventory(maxSlots = slots, slots = List(slots) { if (it == 0) ItemStack(sword.id) else null }))
        s.sent.clear()

        c.handleClientPacket(s, ClientPacket.ShopSell(77, 0, 1))
        val offered = s.sent.filterIsInstance<ServerPacket.ShopBuyback>().lastOrNull() ?: error("no buy-back sent")
        assertEquals(77, offered.shopId)
        assertEquals(sword.id, offered.entries.single().stack.itemId)
        assertEquals(0, c.simulation.players.getValue(me.id).inventory.totalCountOf(sword.id))

        c.handleClientPacket(s, ClientPacket.ShopBuyback(77, 0))
        assertEquals(1, c.simulation.players.getValue(me.id).inventory.totalCountOf(sword.id))
        assertTrue(s.sent.filterIsInstance<ServerPacket.ShopBuyback>().last().entries.isEmpty(), "the list is emptied")
    }
}
