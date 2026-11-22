package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemType
import com.morgana.domain.sim.WorldSimulation
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PluginVetoHooksTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun systemMessages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        .also { TestGames.addCombatKit(it.simulation) }

    private fun login(c: MorganaWorldCoordinator): FakeSession {
        val s = FakeSession("s-1")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate("Tester", "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private class Gate(val answer: (PlayerAction) -> HookDecision, override val pluginId: String = "gate") : MorganaServerHook {
        val seen = mutableListOf<PlayerAction>()
        override fun onBeforeAction(character: CharacterState, action: PlayerAction, sim: WorldSimulation): HookDecision {
            seen += action
            return answer(action)
        }
    }

    @Test
    fun `a denied chat is not sent or reported and the player is told why`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val gate = Gate({ if (it is PlayerAction.Chat && "spoiler" in it.message) HookDecision.Deny("No spoilers here.") else HookDecision.Allow })
        val after = mutableListOf<String>()
        c.hookRegistry.register(gate)
        c.hookRegistry.register(object : MorganaServerHook {
            override val pluginId = "after"
            override fun onPlayerChat(character: CharacterState, channel: ChatChannel, message: String, sim: WorldSimulation) { after += message }
        })
        val s = login(c)
        c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "a spoiler"))
        c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "hello"))
        assertEquals(listOf("hello"), after)
        assertTrue("No spoilers here." in s.systemMessages())
        assertEquals(2, gate.seen.size)
    }

    @Test
    fun `a denied item use leaves the item alone and slash commands are never offered`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val gate = Gate({ if (it is PlayerAction.UseItem) HookDecision.Deny() else HookDecision.Allow })
        c.hookRegistry.register(gate)
        val s = login(c)
        val me = c.simulation.players.values.single()
        val slot = me.inventory.slots.indexOfFirst { it != null && c.simulation.items[it.itemId]?.type == ItemType.CONSUMABLE }
        assertTrue(slot >= 0)
        val before = me.inventory.slots[slot]
        c.handleClientPacket(s, ClientPacket.UseInventorySlot(slot))
        assertEquals(before, c.simulation.players.values.single().inventory.slots[slot], "the item was not used")
        val offered = gate.seen.single() as PlayerAction.UseItem
        assertEquals(before!!.itemId, offered.itemId)
        c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "/help"))
        assertEquals(1, gate.seen.size, "slash commands are not offered")
    }

    @Test
    fun `the first deny wins and a throwing hook allows the action`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.hookRegistry.register(object : MorganaServerHook {
            override val pluginId = "boom"
            override fun onBeforeAction(character: CharacterState, action: PlayerAction, sim: WorldSimulation): HookDecision = error("bug")
        })
        c.hookRegistry.register(Gate({ HookDecision.Deny("first") }, "g1"))
        c.hookRegistry.register(Gate({ HookDecision.Deny("second") }, "g2"))
        val s = login(c)
        c.handleClientPacket(s, ClientPacket.Attack)
        assertTrue("first" in s.systemMessages())
        assertFalse("second" in s.systemMessages())
    }

    @Test
    fun `messagePlayer reaches an online character only`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c)
        val id = c.simulation.players.values.single().id
        assertTrue(c.messagePlayer(id, "Welcome from a plugin"))
        assertTrue("Welcome from a plugin" in s.systemMessages())
        assertFalse(c.messagePlayer("nobody", "hi"))
    }
}
