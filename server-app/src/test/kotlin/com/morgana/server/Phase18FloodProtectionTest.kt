package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.domain.game.BlankGame
import com.morgana.persistence.LimitsConfig
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase18FloodProtectionTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        var closed = false
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) { closed = true }
        fun auth() = outbox.filterIsInstance<ServerPacket.AuthResult>().lastOrNull()
        fun system() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
        fun mapChat() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.MAP }.map { it.message }
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `chat spam is cut to the burst, the speaker is told once, and others see only the allowed messages`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val spammer = login(c, "Spammer")
        val listener = login(c, "Listener")
        repeat(30) { c.handleClientPacket(spammer, ClientPacket.SendChat(ChatChannel.MAP, "buy gold $it")) }

        assertEquals(6, listener.mapChat().size, "only the burst reached other players")
        assertEquals(1, spammer.system().count { it.startsWith("Slow down") }, "one notice, not one per dropped message")
        assertFalse(spammer.closed, "a short burst is not a kick")
        assertEquals(24L, c.limiter.droppedCounts()[PacketClass.CHAT])

        // Time passes (ticks), budget returns.
        repeat(60) { c.stepServerTick() } // 3 seconds
        c.handleClientPacket(spammer, ClientPacket.SendChat(ChatChannel.MAP, "back again"))
        assertTrue(listener.mapChat().contains("back again"))
    }

    @Test
    fun `a sustained flood gets the connection closed and nothing more is processed`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val flooder = login(c, "Flooder")
        val other = login(c, "Other")
        repeat(500) { c.handleClientPacket(flooder, ClientPacket.SendChat(ChatChannel.MAP, "x")) }
        assertTrue(flooder.closed)
        assertTrue(flooder.system().any { it.contains("Disconnected for flooding") })
        assertEquals(1L, c.limiter.kickCount())
        assertEquals(6, other.mapChat().size)
        assertFalse(other.closed)
    }

    @Test
    fun `a login flood is stopped before it can create accounts or hash passwords`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = FakeSession("attacker")
        c.onSessionConnected(s)
        repeat(100) { i ->
            c.handleClientPacket(s, ClientPacket.Authenticate("Bot$i", "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        }
        assertTrue(s.closed)
        assertTrue(c.dataStore.accounts.count() <= 1, "at most one account (one login per connection), got ${c.dataStore.accounts.count()}")
    }

    @Test
    fun `a logged-in connection cannot log in again to register more accounts`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "First")
        assertTrue(s.auth()!!.success)
        c.handleClientPacket(s, ClientPacket.Authenticate("Second", "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        assertFalse(s.auth()!!.success)
        assertTrue(s.auth()!!.message.contains("Already logged in"))
        assertEquals(1, c.dataStore.accounts.count())
    }

    @Test
    fun `unauthenticated peers sending gameplay packets are dropped silently, then disconnected`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = FakeSession("stranger")
        c.onSessionConnected(s)
        repeat(30) { c.handleClientPacket(s, ClientPacket.MoveInput(Direction.UP)) }
        assertTrue(s.closed)
        assertTrue(s.outbox.isEmpty(), "no replies to unauthenticated noise")
    }

    @Test
    fun `oversized passwords, chat, commands and guild text are refused without side effects`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val huge = FakeSession("huge")
        c.onSessionConnected(huge)
        c.handleClientPacket(huge, ClientPacket.Authenticate("Huge", "p".repeat(InputLimits.MAX_PASSWORD + 1), true, 1, protocolVersion = PROTOCOL_VERSION))
        assertFalse(huge.auth()!!.success)
        assertTrue(huge.auth()!!.message.contains("at most ${InputLimits.MAX_PASSWORD}"))
        assertEquals(0, c.dataStore.accounts.count())

        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "y".repeat(InputLimits.MAX_CHAT + 1)))
        assertTrue(a.system().any { it.contains("too long") })
        assertTrue(b.mapChat().isEmpty(), "nothing was broadcast")
        c.handleClientPacket(a, ClientPacket.AdminSlashCommand("/" + "z".repeat(InputLimits.MAX_COMMAND)))
        assertTrue(a.system().count { it.contains("too long") } >= 2)
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "G".repeat(InputLimits.MAX_GUILD_TEXT + 1), "TAG"))
        assertTrue(c.simulation.guilds.isEmpty())
        assertTrue(c.dataStore.social.guilds.findAll().isEmpty())

        // The maximum allowed length still works.
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "k".repeat(InputLimits.MAX_CHAT)))
        assertEquals(1, b.mapChat().size)
    }

    @Test
    fun `normal play over a long session is never limited`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Honest")
        val dirs = listOf(Direction.LEFT, Direction.UP, Direction.RIGHT, Direction.DOWN)
        repeat(1200) { tick -> // one minute
            // Free pixel movement at its fastest: about 22 moves per second.
            repeat(if (tick % 10 == 0) 2 else 1) { c.handleClientPacket(s, ClientPacket.MoveInput(dirs[tick % 4])) }
            if (tick % 4 == 0) c.handleClientPacket(s, ClientPacket.Attack)
            if (tick % 200 == 0) c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "gg"))
            c.stepServerTick()
        }
        assertTrue(c.limiter.droppedCounts().isEmpty(), c.limiter.droppedCounts().toString())
        assertFalse(s.closed)
        c.close()
    }

    @Test
    fun `server json can turn limits off or scale them`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(limits = LimitsConfig(enabled = false)))
        val off = coordinator(dir)
        val s = login(off, "Free")
        repeat(100) { off.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "m$it")) }
        assertTrue(off.limiter.droppedCounts().isEmpty())
        assertFalse(s.closed)
        off.dataStore.close()

        ServerConfig.save(dir, ServerConfig(limits = LimitsConfig(scale = 0.5)))
        val strict = coordinator(dir)
        val t = login(strict, "Strict")
        repeat(20) { strict.handleClientPacket(t, ClientPacket.SendChat(ChatChannel.MAP, "m$it")) }
        assertEquals(17L, strict.limiter.droppedCounts()[PacketClass.CHAT], "burst of 6 halves to 3")
    }

    @Test
    fun `dropped packets never reach a replay recording, so replays still match`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.startRecording()
        val s = login(c, "Recorded")
        repeat(200) { c.handleClientPacket(s, ClientPacket.MoveInput(Direction.LEFT)) } // mostly dropped
        repeat(30) { c.stepServerTick() }
        val log = c.stopRecording()!!
        assertTrue(c.limiter.droppedCounts().getOrDefault(PacketClass.MOVE, 0L) > 100)
        assertEquals(40, log.inputs.count { it is com.morgana.domain.replay.SimInput.Move }, "only admitted moves were recorded")

        val store = MorganaDataStore(dir)
        val sim = com.morgana.domain.sim.WorldSimulation(com.morgana.domain.sim.SeededRandomSource(log.seed), game = BlankGame.COMPILED)
        store.world.loadIntoSimulation(sim)
        val result = com.morgana.domain.replay.ReplayPlayer.replay(sim, log)
        assertTrue(result.ok, result.toString() + " gaps=" + log.gaps)
    }
}
