package com.morgana.server

import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Clients with the `world_objects` capability are told about world objects: in the snapshot and as changes. */
class WorldObjectsPacketTest {
    private class Fake(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        GameScaffolder.scaffold(dir, "Objects Test")
        val gameJson = dir.resolve("game.json")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(gameJson)) as kotlinx.serialization.json.JsonObject
        val objects = kotlinx.serialization.json.Json.parseToJsonElement("""{ "kinds": [ { "id": "wall", "name": "Wall", "maxHp": 50 } ] }""")
        Files.writeString(gameJson, kotlinx.serialization.json.JsonObject(root + ("worldObjects" to objects)).toString())
        return MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
    }

    private fun join(c: MorganaWorldCoordinator, name: String, capabilities: List<String>): Fake {
        val s = Fake("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION, capabilities = capabilities))
        return s
    }

    @Test
    fun `a client that draws objects gets them in the snapshot and as changes, and one that does not hears nothing`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val withCap = join(c, "drawer", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        val without = join(c, "plain", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES - ProtocolCapabilities.WORLD_OBJECTS)
        val mapId = c.simulation.players.getValue(withCap.characterId!!).position.mapId
        val tile = c.simulation.players.getValue(withCap.characterId!!).position
        val x = tile.tileX + 3
        val y = tile.tileY

        assertTrue(c.placeWorldObject("wall", mapId, x, y, withCap.characterId))
        val placed = withCap.sent.filterIsInstance<ServerPacket.WorldObjectsUpdated>().single()
        assertEquals(mapId, placed.mapId)
        assertEquals(listOf("wall"), placed.upserted.map { it.kind })
        assertTrue(without.sent.none { it is ServerPacket.WorldObjectsUpdated }, "no capability, no packet")

        // A client that joins later sees it in its snapshot (and only if it asked).
        val late = join(c, "late", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        val lateNone = join(c, "latenone", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES - ProtocolCapabilities.WORLD_OBJECTS)
        assertEquals(1, late.sent.filterIsInstance<ServerPacket.MapStateSnapshot>().last().worldObjects.size)
        assertTrue(lateNone.sent.filterIsInstance<ServerPacket.MapStateSnapshot>().last().worldObjects.isEmpty())

        val id = placed.upserted.single().id
        assertTrue(c.removeWorldObject(id, "razed"))
        assertEquals(listOf(id), withCap.sent.filterIsInstance<ServerPacket.WorldObjectsUpdated>().last().removedIds)
        assertFalse(c.removeWorldObject(id), "already gone")
    }
}
