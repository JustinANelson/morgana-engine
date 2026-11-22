package com.morgana.server

import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.market.MarketRules
import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.MailActionType
import com.morgana.protocol.MarketActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MarketServerTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun systemMessages() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
        fun lastMarket() = outbox.filterIsInstance<ServerPacket.MarketListings>().lastOrNull()?.listings
        fun lastInbox() = outbox.filterIsInstance<ServerPacket.MailInbox>().lastOrNull()?.messages
    }

    private val goldId = 9100
    private val gemId = 9101

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }).also {
        it.simulation.items[goldId] = ItemDefinition(id = goldId, name = "Test Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 1_000_000)
        it.simulation.items[gemId] = ItemDefinition(id = gemId, name = "Test Gem", stackable = true, maxStack = 99)
    }

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private fun player(c: MorganaWorldCoordinator, name: String) = c.simulation.players.values.first { it.name == name }

    private fun give(c: MorganaWorldCoordinator, name: String, slot: Int, stack: ItemStack) {
        val p = player(c, name)
        c.simulation.players[p.id] = p.copy(inventory = p.inventory.copy(slots = p.inventory.slots.toMutableList().also { it[slot] = stack }))
    }

    /** The currency item this test world uses: the lowest-numbered CURRENCY item, so make sure none lower exists. */
    private fun onlyOurCurrency(c: MorganaWorldCoordinator) {
        c.simulation.items.values.filter { it.type == ItemType.CURRENCY && it.id != goldId }.map { it.id }.forEach { c.simulation.items.remove(it) }
    }

    @Test
    fun `a sale moves the item and currency and pays the seller by mail minus tax`(@TempDir dir: Path) {
        val c = coordinator(dir)
        onlyOurCurrency(c)
        val seller = login(c, "Alpha")
        val buyer = login(c, "Bravo")
        give(c, "Alpha", 0, ItemStack(gemId, 5))
        give(c, "Bravo", 0, ItemStack(goldId, 500))

        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, amount = 2, price = 200))
        assertEquals(3, player(c, "Alpha").inventory.totalCountOf(gemId), "escrowed in the listing")
        val listing = seller.lastMarket()!!.single()
        assertEquals(200, listing.priceCurrencyAmount)

        c.handleClientPacket(buyer, ClientPacket.MarketAction(MarketActionType.BUY, listing.id))
        assertEquals(300, player(c, "Bravo").inventory.totalCountOf(goldId))
        assertEquals(2, player(c, "Bravo").inventory.totalCountOf(gemId))
        assertTrue(c.dataStore.social.marketListings.findAll().isEmpty())

        val letter = seller.lastInbox()!!.single()
        assertEquals(ItemStack(goldId, MarketRules.proceeds(200)), letter.attachedItem)
        c.handleClientPacket(seller, ClientPacket.MailAction(MailActionType.CLAIM, letter.id))
        assertEquals(190, player(c, "Alpha").inventory.totalCountOf(goldId))
    }

    @Test
    fun `bad listings and purchases are refused without losing anything`(@TempDir dir: Path) {
        val c = coordinator(dir)
        onlyOurCurrency(c)
        val seller = login(c, "Alpha")
        val buyer = login(c, "Bravo")
        give(c, "Alpha", 0, ItemStack(gemId, 1))
        give(c, "Alpha", 1, ItemStack(goldId, 10))
        give(c, "Bravo", 0, ItemStack(goldId, 50))

        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 1, price = 5)) // currency
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, price = 0)) // free
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 7, price = 5)) // empty slot
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, amount = 3, price = 5)) // too many
        assertTrue(c.dataStore.social.marketListings.findAll().isEmpty())
        assertEquals(1, player(c, "Alpha").inventory.totalCountOf(gemId))

        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, price = 500))
        val id = seller.lastMarket()!!.single().id
        c.handleClientPacket(buyer, ClientPacket.MarketAction(MarketActionType.BUY, id)) // too poor
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.BUY, id)) // own listing
        assertEquals(1, c.dataStore.social.marketListings.findAll().size)
        assertEquals(50, player(c, "Bravo").inventory.totalCountOf(goldId))
        assertTrue(buyer.systemMessages().any { it.contains("You need 500") })
    }

    @Test
    fun `only the seller can cancel and the item comes back`(@TempDir dir: Path) {
        val c = coordinator(dir)
        onlyOurCurrency(c)
        val seller = login(c, "Alpha")
        val other = login(c, "Bravo")
        give(c, "Alpha", 0, ItemStack(gemId, 4))
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, amount = 4, price = 10))
        val id = seller.lastMarket()!!.single().id

        c.handleClientPacket(other, ClientPacket.MarketAction(MarketActionType.CANCEL, id))
        assertEquals(1, c.dataStore.social.marketListings.findAll().size)
        c.handleClientPacket(seller, ClientPacket.MarketAction(MarketActionType.CANCEL, id))
        assertTrue(c.dataStore.social.marketListings.findAll().isEmpty())
        assertEquals(4, player(c, "Alpha").inventory.totalCountOf(gemId))
    }

    @Test
    fun `an expired listing returns the item to the seller by mail`(@TempDir dir: Path) {
        val c = coordinator(dir)
        onlyOurCurrency(c)
        val seller = login(c, "Alpha")
        val sellerId = player(c, "Alpha").id
        val old = MarketListingDocument("mkt-old", sellerId, "Alpha", ItemStack(gemId, 3), 10, createdAtTick = 0)
        c.dataStore.social.marketListings.save(old.id, old)
        c.simulation.worldEpochTicks = MarketRules.LISTING_TICKS + 5
        repeat(1200) { c.stepServerTick() }

        assertTrue(c.dataStore.social.marketListings.findAll().isEmpty())
        val letter = seller.lastInbox()!!.single()
        assertEquals(ItemStack(gemId, 3), letter.attachedItem)
        assertTrue(letter.subject.contains("expired"))
    }

    @Test
    fun `with the market or mail module off the market refuses`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.MARKET)))
        val off = coordinator(dir)
        val a = login(off, "Alpha")
        off.handleClientPacket(a, ClientPacket.MarketAction(MarketActionType.BROWSE))
        assertTrue(a.systemMessages().any { it.contains("Player market is switched off") })

        val dir2 = dir.resolve("two").also { java.nio.file.Files.createDirectories(it) }
        ServerConfig.save(dir2, ServerConfig().withModules(disable = listOf(OptionalModule.MAIL)))
        val noMail = coordinator(dir2)
        onlyOurCurrency(noMail)
        val b = login(noMail, "Bravo")
        give(noMail, "Bravo", 0, ItemStack(gemId, 1))
        noMail.handleClientPacket(b, ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = 0, price = 10))
        assertTrue(b.systemMessages().any { it.contains("mail is switched off") })
        assertTrue(noMail.dataStore.social.marketListings.findAll().isEmpty())
    }
}
