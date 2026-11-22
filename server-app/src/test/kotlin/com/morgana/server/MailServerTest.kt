package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.MailActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MailServerTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun systemMessages() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
        fun lastInbox() = outbox.filterIsInstance<ServerPacket.MailInbox>().lastOrNull()?.messages
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    /** Puts [count] of item 1 into the first bag slot of the named player (adds the item to the catalog if needed). */
    private fun giveItem(c: MorganaWorldCoordinator, name: String, count: Int, bound: Boolean = false): CharacterState {
        c.simulation.items[9001] = ItemDefinition(id = 9001, name = "Test Gem", boundOnPickup = bound, stackable = true, maxStack = 99)
        val p = c.simulation.players.values.first { it.name == name }
        val updated = p.copy(inventory = p.inventory.copy(slots = p.inventory.slots.toMutableList().also { it[0] = ItemStack(9001, count) }))
        c.simulation.players[p.id] = updated
        return updated
    }

    @Test
    fun `a letter with an item reaches an online recipient who can claim it once`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        giveItem(c, "Alpha", 5)

        c.handleClientPacket(a, ClientPacket.SendMail("bravo", "Gift", "For you", inventorySlotIndex = 0, amount = 2))

        val alpha = c.simulation.players.values.first { it.name == "Alpha" }
        assertEquals(3, alpha.inventory.totalCountOf(9001), "the attachment left the sender's bag")
        assertTrue(a.systemMessages().any { it.contains("Letter sent") })
        val letter = b.lastInbox()!!.single()
        assertEquals("Gift", letter.subject)
        assertEquals(ItemStack(9001, 2), letter.attachedItem)
        assertTrue(b.systemMessages().any { it.contains("New letter from Alpha") })

        c.handleClientPacket(b, ClientPacket.MailAction(MailActionType.CLAIM, letter.id))
        val bravo = c.simulation.players.values.first { it.name == "Bravo" }
        assertEquals(2, bravo.inventory.totalCountOf(9001))
        assertTrue(b.lastInbox()!!.single().claimed)

        c.handleClientPacket(b, ClientPacket.MailAction(MailActionType.CLAIM, letter.id))
        assertEquals(2, c.simulation.players.values.first { it.name == "Bravo" }.inventory.totalCountOf(9001), "no second copy")
    }

    @Test
    fun `mail to an offline character waits in the mailbox until they log in`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.onSessionDisconnected(b)
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Hello", "Are you there?"))
        val bravoId = c.dataStore.characters.findByName("Bravo")!!.id
        assertEquals(1, c.dataStore.social.mailFor(bravoId).size)

        val b2 = login(c, "Bravo")
        assertEquals(1, b2.lastInbox()!!.size)
        assertTrue(b2.systemMessages().any { it.contains("1 unread letter") })
    }

    @Test
    fun `bad letters are refused and nothing is taken`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        giveItem(c, "Alpha", 1, bound = true)

        c.handleClientPacket(a, ClientPacket.SendMail("Nobody", "Hi", ""))
        c.handleClientPacket(a, ClientPacket.SendMail("Alpha", "Hi", ""))
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "", ""))
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Gem", "", inventorySlotIndex = 0, amount = 1))
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Gem", "", inventorySlotIndex = 5, amount = 1))

        assertTrue(c.dataStore.social.mail.findAll().isEmpty())
        assertEquals(1, c.simulation.players.values.first { it.name == "Alpha" }.inventory.totalCountOf(9001))
        val said = a.systemMessages().joinToString(" | ")
        for (part in listOf("No character named", "cannot mail yourself", "needs a subject", "bound to you", "nothing in that slot")) assertTrue(said.contains(part), "missing \"$part\" in: $said")
    }

    @Test
    fun `an unclaimed attachment protects the letter from deletion and others cannot touch it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        giveItem(c, "Alpha", 1)
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Gem", "", inventorySlotIndex = 0, amount = 1))
        val id = b.lastInbox()!!.single().id

        c.handleClientPacket(a, ClientPacket.MailAction(MailActionType.DELETE, id))
        c.handleClientPacket(a, ClientPacket.MailAction(MailActionType.CLAIM, id))
        assertEquals(1, c.dataStore.social.mail.findAll().size, "the sender cannot delete or claim another player's letter")

        c.handleClientPacket(b, ClientPacket.MailAction(MailActionType.DELETE, id))
        assertEquals(1, c.dataStore.social.mail.findAll().size, "an unclaimed attachment blocks deletion")
        c.handleClientPacket(b, ClientPacket.MailAction(MailActionType.CLAIM, id))
        c.handleClientPacket(b, ClientPacket.MailAction(MailActionType.DELETE, id))
        assertNull(c.dataStore.social.mail.findById(id))
    }

    @Test
    fun `with the mail module off the packets are refused`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.MAIL)))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Hi", "x"))
        assertTrue(a.systemMessages().any { it.contains("Mail is switched off") })
        assertTrue(c.dataStore.social.mail.findAll().isEmpty())
    }
}
