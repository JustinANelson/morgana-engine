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

    @Test
    fun `in a seamless world a player sees what is near them on any map around, corners included, and nothing far away`() {
        fun map(id: Int, w: Int, h: Int, x: Int, y: Int) = com.morgana.domain.map.MapDefinition(id = id, name = "M$id", width = w, height = h, worldX = x, worldY = y)
        val def = com.morgana.domain.game.MorganaClassic.COMPILED.definition.copy(worldLayout = com.morgana.domain.map.WorldLayoutDefinition(seamViewTiles = 10))
        val sim = com.morgana.domain.sim.WorldSimulation(com.morgana.domain.sim.SeededRandomSource(1L), game = com.morgana.domain.game.CompiledGame.compile(def))
        sim.registerMap(map(1, 40, 40, 0, 0))
        sim.registerMap(map(2, 40, 40, 40, 0))    // east
        sim.registerMap(map(3, 40, 40, 40, 40))   // south-east, touching only at the corner
        sim.registerMap(map(4, 40, 40, 200, 0))   // far away
        fun npc(map: Int, id: String, x: Int, y: Int) = com.morgana.domain.entity.MapNpcInstance(
            instanceId = id, spawnSlotIndex = 0, npcId = 1, mapId = map, tileX = x, tileY = y, homeX = x, homeY = y,
            vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(10, 10))
        )
        sim.mapNpcs[2] = mutableListOf(npc(2, "east-near", 3, 36), npc(2, "east-far", 30, 5))
        sim.mapNpcs[3] = mutableListOf(npc(3, "corner", 2, 2))
        sim.mapNpcs[4] = mutableListOf(npc(4, "far", 0, 0))
        val player = com.morgana.domain.entity.CharacterState(
            id = "p", accountId = "a", name = "P", position = com.morgana.domain.spatial.WorldPosition.fromTile(1, 37, 37)
        )
        sim.spawnOrUpdatePlayer(player)
        val views = SeamViews(sim) { error("no players here") }.compute(player)
        assertEquals(setOf(2, 3), views.map { it.mapId }.toSet(), "the east map and the corner map, not the far one")
        assertEquals(listOf("east-near"), views.single { it.mapId == 2 }.npcs.map { it.instanceId }, "only what is within ten tiles of the player")
        assertEquals(listOf("corner"), views.single { it.mapId == 3 }.npcs.map { it.instanceId })
    }
}
