package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstancingServerTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun chat() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel != ChatChannel.SYSTEM }.map { it.message }
    }

    private val hubId = 900
    private val cryptId = 901

    private fun coordinator(dir: Path, serverJson: String? = null): MorganaWorldCoordinator {
        if (serverJson != null) Files.writeString(ServerConfig.fileIn(dir), serverJson)
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        // A hub with a warp at (5, 5) into an instanced crypt (kept in memory only; nothing is written to the game folder).
        c.simulation.registerMap(MapDefinition(id = hubId, name = "Hub", width = 12, height = 12).withCell(5, 5, MapCell(attribute = TileAttribute.Warp(cryptId, 2, 2))))
        c.simulation.registerMap(MapDefinition(id = cryptId, name = "Crypt", width = 10, height = 10, instanced = true))
        return c
    }

    private var counter = 0
    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name-${counter++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        val p = c.simulation.players.values.first { it.name == name }
        c.simulation.players[p.id] = p.copy(position = WorldPosition.fromTile(hubId, 4, 5, Direction.RIGHT))
        return s
    }

    /** Steps onto the warp tile through the normal movement packet. */
    private fun enterCrypt(c: MorganaWorldCoordinator, s: FakeSession) {
        repeat(20) { c.simulation.stepTick() }
        c.handleClientPacket(s, ClientPacket.MoveInput(Direction.RIGHT))
    }

    private fun mapOf(c: MorganaWorldCoordinator, name: String) = c.simulation.players.values.first { it.name == name }.position.mapId

    @Test
    fun `two solo players get separate crypts and cannot hear each other`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val ann = login(c, "Ann")
        val ben = login(c, "Ben")
        enterCrypt(c, ann)
        enterCrypt(c, ben)

        assertTrue(MapInstances.isInstanceId(mapOf(c, "Ann")))
        assertTrue(MapInstances.isInstanceId(mapOf(c, "Ben")))
        assertNotEquals(mapOf(c, "Ann"), mapOf(c, "Ben"))

        ben.sent.clear()
        c.handleClientPacket(ann, ClientPacket.SendChat(ChatChannel.MAP, "anyone here?"))
        assertEquals(listOf("anyone here?"), ann.chat())
        assertTrue(ben.chat().isEmpty(), "map chat stays inside the instance")
    }

    @Test
    fun `the editor catalogue lists content maps only`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(login(c, "Boss"))
        c.dataStore.accounts.findByUsername("Boss")!!.let { c.dataStore.accounts.save(it.copy(accessLevel = AccessLevel.OWNER)) }
        val boss = login(c, "Boss")
        enterCrypt(c, boss)
        assertTrue(c.simulation.instances.isNotEmpty())

        c.handleClientPacket(boss, ClientPacket.RequestAdminCatalog)
        val catalog = boss.sent.filterIsInstance<ServerPacket.ContentCatalogUpdated>().last().catalog
        assertFalse(catalog.maps.any { MapInstances.isInstanceId(it.id) }, "instances never reach the editor")
        assertTrue(catalog.maps.any { it.id == cryptId })
    }

    @Test
    fun `server json sets the empty delay and the cap`(@TempDir dir: Path) {
        val c = coordinator(dir, """{ "instances": { "emptySeconds": 5, "max": 1 } }""")
        assertEquals(100L, c.simulation.instanceSettings.emptyTicks)
        assertEquals(1, c.simulation.instanceSettings.maxInstances)
        val ann = login(c, "Ann")
        val ben = login(c, "Ben")
        enterCrypt(c, ann)
        enterCrypt(c, ben)
        assertEquals(hubId, mapOf(c, "Ben"), "the cap turned Ben away")
        assertTrue(ben.sent.filterIsInstance<ServerPacket.ChatBroadcast>().any { "full" in it.message })
    }
}
