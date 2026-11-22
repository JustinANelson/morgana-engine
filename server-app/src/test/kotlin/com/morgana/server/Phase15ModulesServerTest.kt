package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ReplayLogCodec
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.ServerConfigException
import com.morgana.domain.sim.OptionalModule
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.TradeActionType
import com.morgana.server.tools.modulesCommand
import com.morgana.server.tools.replayLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase15ModulesServerTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun auth() = outbox.filterIsInstance<ServerPacket.AuthResult>().last()
        fun systemMessages() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `with no server json everything works and clients are told nothing is disabled`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        assertTrue(a.auth().success)
        assertEquals(emptyList<String>(), a.auth().disabledModules)
        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Nobody"))
        assertTrue(a.systemMessages().none { it.contains("switched off") })
    }

    @Test
    fun `switched-off modules are announced at login and their packets are refused with a message`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(
            OptionalModule.PARTIES, OptionalModule.GUILDS, OptionalModule.TRADING, OptionalModule.CRAFTING
        )))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        assertEquals(listOf("crafting", "guilds", "parties", "trading"), a.auth().disabledModules)

        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Bravo"))
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "HRO"))
        c.handleClientPacket(a, ClientPacket.TradeAction(TradeActionType.REQUEST, "Bravo"))
        c.handleClientPacket(a, ClientPacket.CraftRecipe(1))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.PARTY, "hi party"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.GUILD, "hi guild"))

        val msgs = a.systemMessages()
        assertEquals(6, msgs.count { it.contains("switched off") }, msgs.toString())
        assertTrue(c.simulation.parties.isEmpty() && c.simulation.guilds.isEmpty() && c.simulation.tradeSessions.isEmpty())
        assertTrue(c.dataStore.social.guilds.findAll().isEmpty(), "nothing persisted for a refused guild")

        // Core features keep working: map chat reaches the other player.
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "hello"))
        assertTrue(b.outbox.filterIsInstance<ServerPacket.ChatBroadcast>().any { it.message == "hello" })
    }

    @Test
    fun `an invalid server json stops the server from starting with a clear error`(@TempDir dir: Path) {
        java.nio.file.Files.writeString(ServerConfig.fileIn(dir), """{ "modules": { "tradng": false } }""")
        val e = assertThrows(ServerConfigException::class.java) { coordinator(dir) }
        assertTrue(e.message!!.contains("tradng"))
    }

    @Test
    fun `a recording made with a module off replays only with that module off`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.PROJECTILES)))
        val c = coordinator(dir)
        c.startRecording()
        val a = login(c, "Alpha")
        repeat(30) { c.stepServerTick() }
        c.handleClientPacket(a, ClientPacket.Attack)
        repeat(30) { c.stepServerTick() }
        val log = c.stopRecording()!!
        val file = dir.resolve("modules.replay.json")
        ReplayLogCodec.write(file, log)
        assertEquals(0, replayLog(dir, file.toString()), "same config replays")

        ServerConfig.save(dir, ServerConfig())
        assertEquals(1, replayLog(dir, file.toString()), "different modules are flagged as a content mismatch")
    }

    @Test
    fun `the modules command shows, changes, and validates switches`(@TempDir dir: Path) {
        assertEquals(0, modulesCommand(dir, "", ""))
        assertEquals(0, modulesCommand(dir, "", "trading,guilds"))
        assertEquals(listOf("guilds", "trading"), ServerConfig.load(dir).moduleFlags().disabledKeys())
        assertEquals(0, modulesCommand(dir, "trading", ""))
        assertEquals(listOf("guilds"), ServerConfig.load(dir).moduleFlags().disabledKeys())
        assertEquals(2, modulesCommand(dir, "", "bogus"))
        assertEquals(2, modulesCommand(dir, "guilds", "guilds"))
        assertFalse(ServerConfig.load(dir).moduleFlags().isEnabled(OptionalModule.GUILDS), "failed commands change nothing")
    }
}
