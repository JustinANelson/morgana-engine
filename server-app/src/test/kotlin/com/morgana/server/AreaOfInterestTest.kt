package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
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
import kotlin.io.path.exists

/** Area-of-interest buckets: pure rules, then the server using them on a big map. */
class AreaOfInterestTest {
    private val aoi = AreaOfInterest(cellTiles = 30, radiusCells = 1)

    @Test
    fun `a player sees their own cell and the cells around it`() {
        assertEquals(0, aoi.cell(0))
        assertEquals(0, aoi.cell(29))
        assertEquals(1, aoi.cell(30))
        assertTrue(aoi.sees(29, 29, 59, 59), "the diagonal neighbour cell is in view")
        assertTrue(aoi.sees(30, 30, 0, 0))
        assertFalse(aoi.sees(29, 29, 60, 10), "two cells away is out of view")
        assertFalse(aoi.sees(10, 10, 10, 90))
    }

    @Test
    fun `moving across a cell border newly covers only the far side`() {
        assertFalse(aoi.cellChanged(10, 10, 20, 25), "still in the same cell")
        assertTrue(aoi.cellChanged(29, 10, 30, 10))
        assertTrue(aoi.newlyCovered(29, 10, 30, 10, 75, 10), "cell column 2 is covered only after the step")
        assertFalse(aoi.newlyCovered(29, 10, 30, 10, 10, 10), "what was already in view is not new")
        assertFalse(aoi.newlyCovered(29, 10, 30, 10, 130, 10), "and what stays out of view is not new either")
    }

    private class Fake(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        com.morgana.persistence.GameScaffolder.scaffold(dir, "AOI Test")
        // Replace map 1 with a 120x40 open map so players can stand two AoI cells apart without depending on a developer game.
        val cells = List(120 * 40) { com.morgana.domain.map.MapCell() }
        val bigMap = com.morgana.domain.map.MapDefinition(
            id = 1, name = "AOI Field", width = 120, height = 40,
            moral = com.morgana.domain.map.MapMoral.SAFE_ZONE, cells = cells
        )
        val store = MorganaDataStore(dir)
        store.world.maps.save(1, bigMap)
        Files.writeString(dir.resolve("server.json"), """{ "areaOfInterest": { "enabled": true } }""")
        return MorganaWorldCoordinator(store, ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
    }

    private fun join(c: MorganaWorldCoordinator, name: String): Fake {
        val s = Fake("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(
            s,
            ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION, capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        )
        return s
    }

    private fun Fake.updatesAbout(id: String) = sent.filterIsInstance<ServerPacket.PlayerUpdated>().count { it.player.characterId == id }

    @Test
    fun `players hear about changes only near them, and learn the far side when their area moves`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val a = join(c, "aoi_a")
        val near = join(c, "aoi_near")
        val far = join(c, "aoi_far")
        val (ida, idNear, idFar) = listOf(a, near, far).map { it.characterId!! }
        fun place(id: String, x: Int, y: Int) = sim.spawnOrUpdatePlayer(sim.players.getValue(id).copy(position = WorldPosition.fromTile(1, x, y, Direction.DOWN)))
        place(ida, 29, 10)
        place(idNear, 31, 10)
        place(idFar, 75, 10)

        c.handleClientPacket(a, ClientPacket.FaceDirection(Direction.LEFT)) // settles where the player was last announced
        val nearBefore = near.updatesAbout(ida)
        val farBefore = far.updatesAbout(ida)
        c.handleClientPacket(a, ClientPacket.FaceDirection(Direction.UP))
        assertEquals(nearBefore + 1, near.updatesAbout(ida), "a neighbour hears that the player turned")
        assertEquals(farBefore, far.updatesAbout(ida), "someone two cells away does not")

        // Steps east from the cell's edge: the first two stay in the cell, the third crosses it, and only then is the player
        // told about the player who was out of view.
        fun step(seq: Int) {
            c.handleClientPacket(a, ClientPacket.MoveInput(Direction.RIGHT, seq = seq))
            repeat(12) { c.stepServerTick() }
        }
        place(ida, 27, 10)
        step(1)
        step(2)
        assertEquals(29, sim.players.getValue(ida).position.tileX)
        val before = a.updatesAbout(idFar)
        step(3)
        assertEquals(30, sim.players.getValue(ida).position.tileX)
        assertTrue(a.updatesAbout(idFar) > before, "the newly covered cell is brought up to date")
    }
}
