package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import com.morgana.persistence.MorganaDataStore
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
import java.security.MessageDigest

/** Covers owner bootstrap, protocol versioning, password hashing/upgrade, login throttling, hooks, and the shared runtime. */
class SecurityHardeningTest {

    private class FakeSession(override val sessionId: String, override val isLoopback: Boolean = true) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        var closed = false
        override fun send(packet: ServerPacket) { outbox.add(packet) }
        override fun close(reason: String) { closed = true }
        fun lastAuth(): ServerPacket.AuthResult = outbox.filterIsInstance<ServerPacket.AuthResult>().last()
    }

    private fun coordinator(dir: Path, ownersEnv: String? = null) =
        MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { ownersEnv })

    private fun login(
        c: MorganaWorldCoordinator, name: String, password: String = "pw", loopback: Boolean = true,
        register: Boolean = true, version: Int = PROTOCOL_VERSION, id: String = "s-$name"
    ): FakeSession {
        val s = FakeSession(id, loopback)
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, password, register, 1, protocolVersion = version))
        return s
    }

    private fun access(s: FakeSession) = s.lastAuth().character?.accessLevel

    // ---- Protocol version ---------------------------------------------------------------------------

    @Test
    fun `clients with a missing or different protocol version are rejected before anything is created`(@TempDir dir: Path) {
        val c = coordinator(dir)
        for (bad in listOf(0, PROTOCOL_VERSION + 1)) {
            val s = login(c, "OldClient$bad", version = bad)
            assertFalse(s.lastAuth().success)
            assertTrue(s.lastAuth().message.contains("Protocol mismatch"), s.lastAuth().message)
            assertTrue(s.lastAuth().message.contains("update your client"))
            assertEquals(PROTOCOL_VERSION, s.lastAuth().serverProtocolVersion)
        }
        assertEquals(0, c.dataStore.accounts.count())
        assertTrue(login(c, "Current").lastAuth().success)
    }

    // ---- Owner policy ---------------------------------------------------------------------------------

    @Test
    fun `only the first local account becomes Owner and the username admin grants nothing`(@TempDir dir: Path) {
        val remoteFirst = coordinator(dir.resolve("a"))
        assertEquals(AccessLevel.PLAYER, access(login(remoteFirst, "admin", loopback = false)), "remote first account, even named admin")

        val local = coordinator(dir.resolve("b"))
        assertEquals(AccessLevel.OWNER, access(login(local, "Founder")), "first loopback account")
        assertEquals(AccessLevel.PLAYER, access(login(local, "admin")), "admin as a later account")
        assertEquals(AccessLevel.PLAYER, access(login(local, "Third")))
    }

    @Test
    fun `configured owners win over bootstrap and can be promoted at login`(@TempDir dir: Path) {
        val c = coordinator(dir.resolve("env"), ownersEnv = "Boss, other")
        assertEquals(AccessLevel.PLAYER, access(login(c, "Founder")), "owners are configured, so no implicit bootstrap")
        assertEquals(AccessLevel.OWNER, access(login(c, "boss", loopback = false)), "case-insensitive configured owner, remote")

        val d = dir.resolve("file")
        val c2 = coordinator(d)
        assertEquals(AccessLevel.OWNER, access(login(c2, "Founder")))
        assertEquals(AccessLevel.PLAYER, access(login(c2, "Carol")))
        assertTrue(OwnerPolicy.grant(d, "Carol"))
        assertFalse(OwnerPolicy.grant(d, "carol"), "already listed")
        val relog = login(c2, "Carol", register = false, id = "carol-2")
        assertEquals(AccessLevel.OWNER, access(relog))
        assertEquals(AccessLevel.OWNER, c2.dataStore.accounts.findByUsername("Carol")?.accessLevel)
    }

    // ---- Passwords ------------------------------------------------------------------------------------

    @Test
    fun `passwords are stored salted, verified on login, and legacy hashes upgrade transparently`(@TempDir dir: Path) {
        val c = coordinator(dir)
        login(c, "Alice", "secret-1")
        login(c, "Bob", "secret-1")
        val alice = c.dataStore.accounts.findByUsername("Alice")!!
        val bob = c.dataStore.accounts.findByUsername("Bob")!!
        assertTrue(alice.passwordHash.startsWith("pbkdf2-sha256$"), alice.passwordHash)
        assertNotEquals(alice.passwordHash, bob.passwordHash, "same password, different salt")

        assertFalse(login(c, "Alice", "wrong", register = false, id = "w1").lastAuth().success)
        assertTrue(login(c, "Alice", "secret-1", register = false, id = "ok1").lastAuth().success)

        // Simulate an account created before the upgrade: unsalted SHA-256 hex.
        val legacy = MessageDigest.getInstance("SHA-256").digest("old-pass".toByteArray()).joinToString("") { "%02x".format(it) }
        c.dataStore.accounts.save(bob.copy(passwordHash = legacy))
        assertFalse(login(c, "Bob", "nope", register = false, id = "w2").lastAuth().success)
        assertTrue(login(c, "Bob", "old-pass", register = false, id = "ok2").lastAuth().success)
        val upgraded = c.dataStore.accounts.findByUsername("Bob")!!.passwordHash
        assertTrue(upgraded.startsWith("pbkdf2-sha256$"), "legacy hash replaced after a successful login")
        assertTrue(login(c, "Bob", "old-pass", register = false, id = "ok3").lastAuth().success)
    }

    @Test
    fun `off-lock login path registers, verifies, rejects and upgrades legacy hashes`(@TempDir dir: Path) {
        val c = coordinator(dir)
        fun offLock(name: String, pw: String, register: Boolean, id: String): FakeSession {
            val s = FakeSession(id)
            c.onSessionConnected(s)
            c.handleClientPacketOffLock(s, ClientPacket.Authenticate(name, pw, register, 1, protocolVersion = PROTOCOL_VERSION))
            return s
        }
        assertTrue(offLock("Dana", "pw-1", true, "o1").lastAuth().success)
        assertTrue(c.dataStore.accounts.findByUsername("Dana")!!.passwordHash.startsWith("pbkdf2-sha256$"))
        assertFalse(offLock("Dana", "bad", false, "o2").lastAuth().success)
        assertTrue(offLock("Dana", "pw-1", false, "o3").lastAuth().success)
        assertFalse(offLock("Nobody", "pw", false, "o4").lastAuth().success)

        val legacy = MessageDigest.getInstance("SHA-256").digest("old".toByteArray()).joinToString("") { "%02x".format(it) }
        c.dataStore.accounts.save(c.dataStore.accounts.findByUsername("Dana")!!.copy(passwordHash = legacy))
        assertTrue(offLock("Dana", "old", false, "o5").lastAuth().success)
        assertTrue(c.dataStore.accounts.findByUsername("Dana")!!.passwordHash.startsWith("pbkdf2-sha256$"))
    }

    @Test
    fun `a connection is closed after five consecutive failed logins`(@TempDir dir: Path) {
        val c = coordinator(dir)
        login(c, "Victim", "right")
        val attacker = FakeSession("attacker", isLoopback = false)
        c.onSessionConnected(attacker)
        repeat(4) {
            c.handleClientPacket(attacker, ClientPacket.Authenticate("Victim", "guess$it", false, 1, protocolVersion = PROTOCOL_VERSION))
            assertFalse(attacker.closed, "still open after ${it + 1} failures")
        }
        c.handleClientPacket(attacker, ClientPacket.Authenticate("Victim", "guess5", false, 1, protocolVersion = PROTOCOL_VERSION))
        assertTrue(attacker.closed)
        assertTrue(attacker.lastAuth().message.contains("Too many failed login attempts"))
    }

    // ---- Hooks ----------------------------------------------------------------------------------------

    @Test
    fun `kotlin_hook event commands reach registered plugins and a failing plugin cannot break others`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = login(c, "Scripter")
        val seen = mutableListOf<Triple<String, String, Map<String, String>>>()
        c.hookRegistry.register(object : MorganaServerHook {
            override val pluginId = "boom"
            override fun onCustomEventHook(character: CharacterState, hookId: String, payload: Map<String, String>, sim: WorldSimulation) {
                error("plugin bug")
            }
        })
        c.hookRegistry.register(object : MorganaServerHook {
            override val pluginId = "recorder"
            override fun onCustomEventHook(character: CharacterState, hookId: String, payload: Map<String, String>, sim: WorldSimulation) {
                seen += Triple(character.name, hookId, payload)
            }
        })

        val me = c.simulation.players.values.single()
        c.simulation.events[90] = EventDefinition(
            id = 90, name = "Hook Test", mapId = me.position.mapId, tileX = me.position.tileX, tileY = me.position.tileY,
            pages = listOf(
                EventPage(
                    trigger = EventTriggerType.ACTION_INTERACT,
                    commands = listOf(EventCommand.InvokeKotlinHook("test.hook", mapOf("k" to "v")))
                )
            )
        )
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(listOf(Triple("Scripter", "test.hook", mapOf("k" to "v"))), seen)
    }

    // ---- Shared runtime ------------------------------------------------------------------------------

    @Test
    fun `the shared server runtime hot-reloads edited content files for embedded and dedicated servers alike`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = true).use { runtime ->
            assertTrue(runtime.port > 0)
            val itemFile = dir.resolve("world/items/item_001.json")
            val original = runtime.coordinator.simulation.items.getValue(1).name
            Files.writeString(itemFile, Files.readString(itemFile).replaceFirst("\"name\": \"$original\"", "\"name\": \"Reloaded By Watcher\""))

            var name = original
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && name == original) {
                Thread.sleep(100)
                synchronized(runtime.coordinator) { name = runtime.coordinator.simulation.items.getValue(1).name }
            }
            assertEquals("Reloaded By Watcher", name)
        }
    }
}
