package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.MapRef
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Send-once map cache: each map revision crosses a connection once; later snapshots only reference it. */
class MapCacheServerTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
        fun snapshots() = sent.filterIsInstance<ServerPacket.MapStateSnapshot>()
    }

    /** Runs on a private copy of the game: live edits are saved to the game's own data folder, which must never be the repo's. */
    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        return MorganaWorldCoordinator(MorganaDataStore(rootDir = dir, gameDir = TestGames.withNeighbours(dir)))
    }

    private fun login(c: MorganaWorldCoordinator, name: String, capabilities: List<String> = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES): Session {
        val s = Session(name)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION, capabilities = capabilities))
        return s
    }

    @Test
    fun `the first snapshot carries the maps and references them`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "First")
        val snap = s.snapshots().single()
        val active = snap.activeMap!!
        assertEquals(MapRef(active.id, active.revision), snap.mapRefs.first())
        assertTrue(snap.neighborMaps.isNotEmpty())
        assertEquals(snap.neighborMaps.keys + active.id, snap.mapRefs.map { it.id }.toSet())
        assertTrue(ProtocolCapabilities.MAP_CACHE in (s.sent.filterIsInstance<ServerPacket.AuthResult>().single().capabilities))
    }

    @Test
    fun `an unchanged map is only referenced the second time`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Again")
        val first = s.snapshots().single()
        val mapId = first.activeMap!!.id

        c.sendFullMapSnapshotTo(s, mapId)
        val second = s.snapshots().last()
        assertNull(second.activeMap, "the client already holds this map")
        assertTrue(second.neighborMaps.isEmpty())
        assertEquals(first.mapRefs, second.mapRefs)
        assertTrue(PacketCodec.encodeServerJson(second).length < PacketCodec.encodeServerJson(first).length / 2)
    }

    @Test
    fun `moving to a neighbouring map sends only the maps not yet held`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Walker")
        val first = s.snapshots().single()
        val home = first.activeMap!!
        val east = first.neighborMaps.keys.first()

        c.sendFullMapSnapshotTo(s, east)
        val crossing = s.snapshots().last()
        assertEquals(east, crossing.mapRefs.first().id)
        assertNull(crossing.activeMap, "the destination arrived earlier as a neighbour")
        assertTrue(home.id !in crossing.neighborMaps.keys, "the map just left is held")
        val everythingReferenced = crossing.mapRefs.map { it.id }.toSet()
        val newlySent = crossing.neighborMaps.keys
        assertTrue(newlySent.all { it in everythingReferenced })
        assertTrue(newlySent.none { it in first.neighborMaps.keys || it == home.id }, "only genuinely new maps are sent")
    }

    @Test
    fun `a connection without the capability always receives full maps`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Legacy", capabilities = listOf(ProtocolCapabilities.SOFT_SEAMS))
        val mapId = s.snapshots().single().activeMap!!.id
        c.sendFullMapSnapshotTo(s, mapId)
        for (snap in s.snapshots()) {
            assertNotNull(snap.activeMap)
            assertTrue(snap.mapRefs.isEmpty())
            assertTrue(snap.neighborMaps.isNotEmpty())
        }
    }

    @Test
    fun `an edited map is sent once to everyone and referenced afterwards`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("owners.txt"), "Builder\n")
        val c = coordinator(dir)
        val owner = login(c, "Builder")
        val other = login(c, "Visitor")
        val original = owner.snapshots().single().activeMap!!
        val edited = original.copy(name = original.name + " (edited)")

        c.handleClientPacket(owner, ClientPacket.SaveMapDefinition(edited))

        val update = other.sent.filterIsInstance<ServerPacket.MapDefinitionUpdated>().single()
        assertTrue(update.map.revision > original.revision)
        val after = other.snapshots().last()
        assertEquals(update.map.revision, after.mapRefs.first().revision)
        assertNull(after.activeMap, "the broadcast already delivered the new revision")
    }

    @Test
    fun `a resync request forgets what was sent and resends everything`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Lost")
        c.sendFullMapSnapshotTo(s, s.snapshots().single().activeMap!!.id)
        assertNull(s.snapshots().last().activeMap)

        c.handleClientPacket(s, ClientPacket.RequestMapResync)
        val full = s.snapshots().last()
        assertNotNull(full.activeMap)
        assertTrue(full.neighborMaps.isNotEmpty())
    }

    @Test
    fun `each connection keeps its own record and a new connection starts from nothing`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alice")
        val b = login(c, "Bobby")
        assertNotNull(a.snapshots().single().activeMap)
        assertNotNull(b.snapshots().single().activeMap, "Bobby has not been sent anything yet, whatever Alice holds")
    }

    @Test
    fun `snapshots without a map and with references survive the wire`() {
        val packet = ServerPacket.MapStateSnapshot(activeMap = null, mapRefs = listOf(MapRef(1, 4), MapRef(2, 9)))
        val json = PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(packet)) as ServerPacket.MapStateSnapshot
        assertNull(json.activeMap)
        assertEquals(packet.mapRefs, json.mapRefs)
        val cbor = PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(packet)) as ServerPacket.MapStateSnapshot
        assertNull(cbor.activeMap)
        assertEquals(packet.mapRefs, cbor.mapRefs)
    }
}
