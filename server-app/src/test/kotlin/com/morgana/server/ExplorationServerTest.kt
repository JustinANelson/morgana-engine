package com.morgana.server

import com.morgana.domain.explore.Exploration
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExplorationServerTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun updates() = outbox.filterIsInstance<ServerPacket.ExplorationUpdate>()
        fun full() = outbox.filterIsInstance<ServerPacket.Exploration>()
        fun worldMaps() = outbox.filterIsInstance<ServerPacket.WorldMapDetail>()
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private fun player(c: MorganaWorldCoordinator, name: String) = c.simulation.players.values.first { it.name == name }

    private fun tick(c: MorganaWorldCoordinator, n: Int = 10) = repeat(n) { c.stepServerTick() }

    @Test
    fun `walking reveals the ground, saves it, and a new login gets it back`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        assertTrue(a.full().single().maps.isEmpty(), "a new character has explored nothing")

        tick(c)
        c.flushPersistence()
        val first = a.updates().last().map
        val p = player(c, "Alpha")
        assertEquals(p.position.mapId, first.mapId)
        assertTrue(Exploration.isTileExplored(first.explored, first.width, p.position.tileX, p.position.tileY), "the ground under the player is seen")
        assertTrue(c.dataStore.social.exploration.findById(p.id)!!.maps.containsKey(first.mapId))

        val before = Exploration.exploredChunks(first.explored)
        val map = c.simulation.maps.getValue(first.mapId)
        val farX = if (p.position.tileX < map.width / 2) map.width - 2 else 1
        c.simulation.players[p.id] = p.copy(position = WorldPosition.fromTile(first.mapId, farX, map.height / 2, Direction.DOWN))
        tick(c)
        val after = a.updates().last().map
        assertTrue(Exploration.exploredChunks(after.explored) > before, "moving far away reveals more")
        assertTrue(Exploration.isTileExplored(after.explored, after.width, p.position.tileX, p.position.tileY), "and the first spot stays seen")

        val updatesSoFar = a.updates().size
        tick(c)
        assertEquals(updatesSoFar, a.updates().size, "standing still sends nothing")

        c.onSessionDisconnected(a)
        val a2 = login(c, "Alpha")
        assertEquals(after.explored, a2.full().single().maps.single { it.mapId == first.mapId }.explored)
        assertEquals(map.name, a2.full().single().maps.single { it.mapId == first.mapId }.name)
    }

    @Test
    fun `each character explores on their own`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        tick(c)
        c.flushPersistence()
        val alphaId = player(c, "Alpha").id
        val bravoId = player(c, "Bravo").id
        assertTrue(c.dataStore.social.exploration.findById(alphaId) != null && c.dataStore.social.exploration.findById(bravoId) != null)
        assertTrue(a.updates().isNotEmpty())
    }

    @Test
    fun `the world map request returns the terrain and enemy spawns of explored maps only`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        c.handleClientPacket(a, ClientPacket.RequestWorldMap)
        assertTrue(a.worldMaps().last().maps.isEmpty(), "nothing explored, nothing sent")

        tick(c)
        c.handleClientPacket(a, ClientPacket.RequestWorldMap)
        val entry = a.worldMaps().last().maps.single()
        val map = c.simulation.maps.getValue(entry.mapId)
        assertEquals(player(c, "Alpha").position.mapId, entry.mapId)
        assertEquals(entry.cols * entry.rows, entry.terrain.length)
        assertEquals(com.morgana.domain.explore.WorldMapTerrain.thumbnail(map).cells, entry.terrain)
        assertEquals(map.npcSpawns.map { it.npcId to it.spawnX }, entry.spawns.map { it.npcId to it.x })
    }

    @Test
    fun `with the exploration module off the world map answer is empty`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.EXPLORATION)))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        c.handleClientPacket(a, ClientPacket.RequestWorldMap)
        assertTrue(a.worldMaps().single().maps.isEmpty())
    }

    @Test
    fun `with the exploration module off nothing is tracked or sent`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.EXPLORATION)))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        tick(c, 20)
        assertTrue(a.full().isEmpty() && a.updates().isEmpty())
        assertTrue(c.dataStore.social.exploration.findAll().isEmpty())
    }
}
