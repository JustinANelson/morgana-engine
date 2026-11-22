package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Soft seams on the server: players near a shared border are told about the entities just across it. */
class SeamViewsTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        return MorganaWorldCoordinator(MorganaDataStore(rootDir = dir, gameDir = TestGames.withNeighbours(dir)))
    }

    private fun login(c: MorganaWorldCoordinator, name: String): Session {
        val s = Session(name)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `a player near two borders sees the monsters across both`(@TempDir dir: Path) {
        val c = coordinator(dir)
        login(c, "Edge")
        val player = c.simulation.players.values.single()
        assertEquals(1, player.position.mapId)

        val views = SeamViews(c.simulation) { error("no other players expected") }.compute(player)
        val mapIds = views.map { it.mapId }.toSet()
        assertTrue(3 in mapIds, "the west map has an NPC near the shared border: $mapIds")
        assertTrue(2 in mapIds, "the east map has an NPC near the shared border: $mapIds")
        assertTrue(views.first { it.mapId == 3 }.npcs.all { it.mapId == 3 })
    }

    @Test
    fun `a map with no neighbours produces no view`(@TempDir dir: Path) {
        val c = coordinator(dir)
        login(c, "Mid")
        val player = c.simulation.players.values.single()
        val onIsland = player.copy(position = player.position.copy(mapId = 5)) // the island map has no neighbours
        assertTrue(SeamViews(c.simulation) { error("unused") }.compute(onIsland).isEmpty())
    }

    @Test
    fun `the view is sent once and again only when it changes`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val session = login(c, "Edge")
        assertTrue(session.sent.any { it is ServerPacket.SeamView }, "the view across the borders is sent right after the map snapshot")

        val before = session.sent.count { it is ServerPacket.SeamView }
        repeat(20) { c.stepServerTick() }
        val extra = session.sent.count { it is ServerPacket.SeamView } - before
        // Quiet neighbours barely change, so a still player is not spammed with identical packets.
        assertTrue(extra <= 6, "sent $extra extra views in 20 ticks")
    }

    @Test
    fun `when softSeams is disabled in server json no SeamView packets are sent`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("server.json"), """{ "schemaVersion": 1, "modules": { "softSeams": false } }""")
        val c = coordinator(dir)
        val session = login(c, "EdgeOff")
        assertTrue(session.sent.none { it is ServerPacket.SeamView }, "no SeamView on login when softSeams is off")
        repeat(10) { c.stepServerTick() }
        assertTrue(session.sent.none { it is ServerPacket.SeamView }, "no SeamView on ticks when softSeams is off")
    }
}
