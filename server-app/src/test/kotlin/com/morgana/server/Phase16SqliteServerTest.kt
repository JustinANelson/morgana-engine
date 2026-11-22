package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.PlayerDataBackend
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.tools.storageCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase16SqliteServerTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun auth() = outbox.filterIsInstance<ServerPacket.AuthResult>().last()
    }

    private fun boot(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String, register: Boolean = true, id: String = "s-$name"): FakeSession {
        val s = FakeSession(id)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", register, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `accounts and character progress survive a server restart on sqlite`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withPlayerData(PlayerDataBackend.SQLITE))

        val first = boot(dir)
        val s = login(first, "Persisto")
        assertTrue(s.auth().success)
        val before = first.simulation.players.values.single()
        repeat(40) {
            first.handleClientPacket(s, ClientPacket.MoveInput(Direction.LEFT))
            repeat(5) { first.stepServerTick() }
        }
        val moved = first.simulation.players.values.single().position
        assertNotEquals(before.position, moved, "the character actually moved")
        first.onSessionDisconnected(s) // logout saves
        first.dataStore.close()

        assertTrue(Files.exists(dir.resolve(MorganaDataStore.PLAYER_DB_FILE)))
        assertFalse(Files.exists(dir.resolve("accounts")), "no per-account JSON files with the sqlite backend")

        val second = boot(dir)
        val again = login(second, "persisto", register = false, id = "s2") // different case: indexed lookup
        assertTrue(again.auth().success, again.auth().message)
        assertEquals(moved, second.simulation.players.values.single().position)
        second.dataStore.close()
    }

    @Test
    fun `registration creates the account and its character together`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withPlayerData(PlayerDataBackend.SQLITE))
        val c = boot(dir)
        login(c, "Pair")
        val account = c.dataStore.accounts.findByUsername("pair")!!
        assertEquals(account.characterIds.single(), c.dataStore.characters.findByAccountId(account.id).single().id)
        c.dataStore.close()
    }

    @Test
    fun `the runtime closes the database on shutdown so the file can be reused`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withPlayerData(PlayerDataBackend.SQLITE))
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { runtime ->
            val s = login(runtime.coordinator, "Runtime")
            assertTrue(s.auth().success)
        }
        // Reopening and deleting works only if every connection was released.
        MorganaDataStore(dir).use { assertEquals("Runtime", it.accounts.findByUsername("runtime")?.username) }
        Files.delete(dir.resolve(MorganaDataStore.PLAYER_DB_FILE))
    }

    @Test
    fun `an existing json server migrates to sqlite with the storage command and keeps its players`(@TempDir dir: Path) {
        val json = boot(dir)
        val s = login(json, "Veteran")
        assertTrue(s.auth().success)
        json.onSessionDisconnected(s)
        val charId = json.dataStore.accounts.findByUsername("veteran")!!.characterIds.single()
        val savedChar = json.dataStore.characters.findById(charId)
        assertEquals(0, storageCommand(dir, "")) // just shows the backend

        assertEquals(2, storageCommand(dir, "mongodb"), "unknown backend")
        assertEquals(0, storageCommand(dir, "sqlite"))
        assertEquals(1, storageCommand(dir, "sqlite"), "already migrated")

        val sql = boot(dir)
        assertEquals(PlayerDataBackend.SQLITE, sql.dataStore.playerDataBackend)
        assertEquals(savedChar, sql.dataStore.characters.findById(charId))
        val back = login(sql, "VETERAN", register = false)
        assertTrue(back.auth().success, back.auth().message)
        sql.dataStore.close()
    }
}
