package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Walking must not flood the map: one step is one public update per watcher, and a broadcast is encoded once. */
class BroadcastEfficiencyTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        val encodedTexts = mutableListOf<String>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun sendEncoded(packet: ServerPacket, encoded: () -> String) {
            sent.add(packet)
            encodedTexts.add(encoded())
        }
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(rootDir = dir, gameDir = TestGames.blank(dir)))

    private fun login(c: MorganaWorldCoordinator, name: String): Session {
        val s = Session(name)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `one step sends each watcher one public update of the walker, not two`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val walker = login(c, "Walker")
        val watcher = login(c, "Watcher")
        val walkerId = c.simulation.players.values.first { it.name == "Walker" }.id
        c.stepServerTick()
        watcher.sent.clear()
        walker.sent.clear()

        c.handleClientPacket(walker, ClientPacket.MoveInput(Direction.RIGHT, seq = 1))
        repeat(2) { c.stepServerTick() } // moves reach watchers in a batch every second tick

        // A walker who only moved is a compact move, not the whole snapshot, and it is sent once.
        val moves = watcher.sent.filterIsInstance<ServerPacket.EntityMoves>().flatMap { it.players }.filter { it.characterId == walkerId }
        val full = watcher.sent.filterIsInstance<ServerPacket.PlayerUpdated>().filter { it.player.characterId == walkerId }
        assertEquals(1, moves.size + full.size, "sent: ${watcher.sent.map { it::class.simpleName }}")
        assertEquals(0, full.size, "a plain step does not resend the whole snapshot")
        // The walker still hears about their own step (the acknowledgement the client waits for).
        assertEquals(1, walker.sent.filterIsInstance<ServerPacket.CharacterSync>().size)
    }

    @Test
    fun `a broadcast is encoded once and shared by every recipient`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alice")
        val b = login(c, "Bobby")
        c.stepServerTick()
        a.encodedTexts.clear()
        b.encodedTexts.clear()

        // Something every viewer can see changes (a level), so the whole snapshot is broadcast to the map.
        val id = c.simulation.players.values.first { it.name == "Alice" }.id
        c.simulation.spawnOrUpdatePlayer(c.simulation.players.getValue(id).copy(level = 7))
        c.handleClientPacket(a, ClientPacket.FaceDirection(Direction.UP))
        c.stepServerTick()

        assertNotNull(a.encodedTexts.firstOrNull(), "A got: ${a.sent.map { it::class.simpleName }}, B got: ${b.sent.map { it::class.simpleName }}")
        assertEquals(a.encodedTexts.size, b.encodedTexts.size)
        a.encodedTexts.zip(b.encodedTexts).forEach { (x, y) -> assertSame(x, y, "both recipients got the very same text") }
    }
}
