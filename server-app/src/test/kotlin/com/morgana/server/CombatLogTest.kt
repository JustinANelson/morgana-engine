package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Disconnecting out of a PvP fight must not remove the character at once (the combat-log exploit). */
class CombatLogTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun messages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String, sessionId: String = "s-$name"): FakeSession {
        val s = FakeSession(sessionId)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private fun byName(c: MorganaWorldCoordinator, name: String): CharacterState? = c.simulation.players.values.firstOrNull { it.name == name }

    /** Two players next to each other on an open-PvP map (id 2, added for the test), the attacker facing the defender. */
    private class Duel(val c: MorganaWorldCoordinator, val attacker: FakeSession, val defender: FakeSession, val defenderId: String)

    private fun duel(c: MorganaWorldCoordinator): Duel {
        c.simulation.registerMap(com.morgana.domain.map.MapDefinition(id = 2, name = "Duel Field", width = 16, height = 16, moral = com.morgana.domain.map.MapMoral.OPEN_PVP))
        val a = login(c, "Attacker")
        val d = login(c, "Defender")
        val aId = byName(c, "Attacker")!!.id
        val dId = byName(c, "Defender")!!.id
        c.simulation.players[aId] = c.simulation.players.getValue(aId).copy(position = WorldPosition.fromTile(2, 8, 8, Direction.RIGHT))
        c.simulation.players[dId] = c.simulation.players.getValue(dId).copy(position = WorldPosition.fromTile(2, 9, 8, Direction.LEFT))
        return Duel(c, a, d, dId)
    }

    /** The attacker swings until the defender has taken damage (the swing has a cooldown). */
    private fun hit(duel: Duel) {
        val before = duel.c.simulation.players.getValue(duel.defenderId).vitals.getValue("hp").cur
        repeat(60) {
            if (duel.c.simulation.players.getValue(duel.defenderId).vitals.getValue("hp").cur < before) return
            duel.c.handleClientPacket(duel.attacker, ClientPacket.Attack)
            repeat(5) { duel.c.stepServerTick() }
        }
        assertTrue(duel.c.simulation.players.getValue(duel.defenderId).vitals.getValue("hp").cur < before, "the attacker could not hurt the defender")
    }

    @Test
    fun `a player who disconnects in a PvP fight stays in the world and can still be hit`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        c.onSessionDisconnected(d.defender)

        assertNotNull(c.simulation.players[d.defenderId], "the character is still standing there")
        assertEquals(setOf(d.defenderId), c.ghostCharacterIds())
        val hpBefore = c.simulation.players.getValue(d.defenderId).vitals.getValue("hp").cur
        hit(d)
        assertTrue(c.simulation.players.getValue(d.defenderId).vitals.getValue("hp").cur < hpBefore, "a ghost takes damage like anyone else")
    }

    @Test
    fun `the ghost is removed and saved when the time is up`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        c.onSessionDisconnected(d.defender)
        repeat(15 * 20 + 20) { c.stepServerTick() }
        assertFalse(c.simulation.players.containsKey(d.defenderId))
        assertTrue(c.ghostCharacterIds().isEmpty())
        assertNotNull(c.dataStore.characters.findById(d.defenderId))
    }

    @Test
    fun `a player who leaves without fighting is removed at once`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        c.onSessionDisconnected(d.defender)
        assertFalse(c.simulation.players.containsKey(d.defenderId))
        assertTrue(c.ghostCharacterIds().isEmpty())
    }

    @Test
    fun `a fight long ago does not count`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        repeat(15 * 20 + 40) { c.stepServerTick() }
        c.onSessionDisconnected(d.defender)
        assertFalse(c.simulation.players.containsKey(d.defenderId))
    }

    @Test
    fun `returning in time picks the same character up where it was left`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        c.onSessionDisconnected(d.defender)
        hit(d) // hurt while away: only the live character knows about it
        val liveHp = c.simulation.players.getValue(d.defenderId).vitals.getValue("hp").cur
        val livePos = c.simulation.players.getValue(d.defenderId).position

        val back = login(c, "Defender", "s-back")
        val me = byName(c, "Defender")!!
        assertEquals(liveHp, me.vitals.getValue("hp").cur, "no respawn from the older saved copy")
        assertEquals(livePos, me.position)
        assertTrue(c.ghostCharacterIds().isEmpty())
        assertTrue(back.messages().any { "still in a fight" in it })
        assertEquals(1, c.simulation.players.values.count { it.name == "Defender" })
    }

    @Test
    fun `the realm can switch it off`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("server.json"), """{ "session": { "combatLogSeconds": 0 } }""")
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        c.onSessionDisconnected(d.defender)
        assertFalse(c.simulation.players.containsKey(d.defenderId))
    }

    @Test
    fun `shutdown saves and removes ghosts`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val d = duel(c)
        hit(d)
        c.onSessionDisconnected(d.defender)
        c.close()
        assertFalse(c.simulation.players.containsKey(d.defenderId))
        assertTrue(c.ghostCharacterIds().isEmpty())
    }
}
