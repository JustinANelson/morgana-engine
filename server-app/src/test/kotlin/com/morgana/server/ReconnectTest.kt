package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Dropped connections: resume tokens, single use, grace period, and takeover of a stale session. */
class ReconnectTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        var closedReason: String? = null
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) { closedReason = reason }
        fun authResults() = sent.filterIsInstance<ServerPacket.AuthResult>()
    }

    private fun coordinator(dataDir: Path): MorganaWorldCoordinator {
        return MorganaWorldCoordinator(MorganaDataStore(rootDir = dataDir, gameDir = TestGames.blank(dataDir)))
    }

    private fun MorganaWorldCoordinator.login(id: String): Session {
        val s = Session(id)
        onSessionConnected(s)
        handleClientPacket(s, ClientPacket.Authenticate("Wanderer", "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        simulation.stepTick()
        return s
    }

    @Test
    fun `tokens are single use, tied to the account, and expire after the grace period`() {
        var now = 1_000L
        val tokens = ResumeTokens(graceMs = 60_000, overlapMs = 5_000, clock = { now })
        val t = tokens.issue("acc-1", "s1")
        // Still connected: valid regardless of time.
        now += 10_000_000
        tokens.sessionEnded("s1")
        now += 59_000
        assertEquals("acc-1", tokens.redeem(t))
        now += 6_000
        assertNull(tokens.redeem(t), "a redeemed token stops working once its overlap has passed")

        val t2 = tokens.issue("acc-1", "s2")
        tokens.sessionEnded("s2")
        now += 61_000
        assertNull(tokens.redeem(t2), "expired after the grace period")
        assertNull(tokens.redeem("not-a-token"))
    }

    @Test
    fun `a redeemed token works once more inside the overlap when the reply with the fresh token was lost`() {
        var now = 1_000L
        val tokens = ResumeTokens(graceMs = 60_000, overlapMs = 5_000, clock = { now })
        val t = tokens.issue("acc-1", "s1", "char-1")
        tokens.sessionEnded("s1")
        assertEquals("acc-1", tokens.redeem(t))
        // The server issued a fresh token for the resumed session, but the phone never got it.
        tokens.issue("acc-1", "s2", "char-1")
        tokens.sessionEnded("s2") // ...because that connection died too
        now += 2_000
        val again = tokens.redeemEntry(t)
        assertEquals("acc-1", again?.accountId)
        assertEquals("char-1", again?.characterId)
        assertNull(tokens.redeem(t), "the retry is allowed once, not repeatedly")
    }

    @Test
    fun `a retired token cannot be replayed while the session that used it is still connected`() {
        var now = 1_000L
        val tokens = ResumeTokens(graceMs = 60_000, overlapMs = 5_000, clock = { now })
        val t = tokens.issue("acc-1", "s1")
        tokens.sessionEnded("s1")
        tokens.redeem(t)
        tokens.issue("acc-1", "s2") // the resumed session is live
        now += 1_000
        assertNull(tokens.redeem(t), "a captured token must not take over a live session")
    }

    @Test
    fun `using the fresh token voids the retired one`() {
        var now = 1_000L
        val tokens = ResumeTokens(graceMs = 60_000, overlapMs = 5_000, clock = { now })
        val t = tokens.issue("acc-1", "s1")
        tokens.sessionEnded("s1")
        tokens.redeem(t)
        val fresh = tokens.issue("acc-1", "s2")
        tokens.sessionEnded("s2")
        now += 1_000
        assertEquals("acc-1", tokens.redeem(fresh))
        assertNull(tokens.redeem(t), "the client proved it has the fresh token, so the old one is gone")
    }

    @Test
    fun `issuing a new token replaces the account's old one`() {
        val tokens = ResumeTokens()
        val old = tokens.issue("acc-1", "s1")
        val fresh = tokens.issue("acc-1", "s1")
        assertNotEquals(old, fresh)
        assertNull(tokens.redeem(old))
        assertEquals("acc-1", tokens.redeem(fresh))
    }

    @Test
    fun `a dropped client resumes its character with the token and gets a fresh one`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val first = c.login("s1")
        val auth1 = first.authResults().single()
        assertTrue(auth1.success)
        val token = auth1.resumeToken
        assertNotNull(token, "login must issue a resume token")
        val charId = first.characterId!!

        c.onSessionDisconnected(first)
        assertFalse(c.simulation.players.containsKey(charId), "dropped player is removed from the world")

        val second = Session("s2")
        c.onSessionConnected(second)
        c.handleClientPacket(second, ClientPacket.ResumeSession(token!!, PROTOCOL_VERSION))
        val auth2 = second.authResults().single()
        assertTrue(auth2.success, auth2.message)
        assertEquals(charId, auth2.character?.id)
        assertEquals(charId, second.characterId)
        assertTrue(c.simulation.players.containsKey(charId))
        assertNotNull(auth2.resumeToken)
        assertNotEquals(token, auth2.resumeToken, "the token rotates on every resume")

        // The used token is dead.
        val third = Session("s3")
        c.onSessionConnected(third)
        c.handleClientPacket(third, ClientPacket.ResumeSession(token, PROTOCOL_VERSION))
        assertFalse(third.authResults().single().success)
        assertNull(third.characterId)
    }

    @Test
    fun `resuming with a bad token is rejected without a world side effect`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = Session("s1")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.ResumeSession("bogus", PROTOCOL_VERSION))
        val result = s.authResults().single()
        assertFalse(result.success)
        assertTrue(result.message.contains("expired", ignoreCase = true))
        assertTrue(c.simulation.players.isEmpty())
    }

    @Test
    fun `resuming while the dead connection is still open takes over without losing the character`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val stale = c.login("stale")
        val token = stale.authResults().single().resumeToken!!
        val charId = stale.characterId!!

        // The server has not noticed the drop yet: the old socket is still registered.
        val fresh = Session("fresh")
        c.onSessionConnected(fresh)
        c.handleClientPacket(fresh, ClientPacket.ResumeSession(token, PROTOCOL_VERSION))
        assertTrue(fresh.authResults().single().success)

        val end = stale.sent.filterIsInstance<ServerPacket.SessionEnded>().single()
        assertFalse(end.resumable, "the evicted client must not fight back by reconnecting")
        assertNotNull(stale.closedReason)
        assertNull(stale.characterId)

        // When the stale socket finally times out it must not remove the live character.
        c.onSessionDisconnected(stale)
        assertTrue(c.simulation.players.containsKey(charId), "stale disconnect must not kick the resumed player")
        assertEquals(charId, fresh.characterId)
    }

    @Test
    fun `logging in with the password while an older session lingers also takes over`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val stale = c.login("stale")
        val charId = stale.characterId!!
        val fresh = c.login("fresh")
        assertTrue(fresh.authResults().single().success)
        assertEquals(1, stale.sent.filterIsInstance<ServerPacket.SessionEnded>().size)
        c.onSessionDisconnected(stale)
        assertTrue(c.simulation.players.containsKey(charId))
    }

    @Test
    fun `when session resume is disabled in server json no token is issued and resume is refused`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("server.json"), """{ "schemaVersion": 1, "session": { "resume": false, "graceMinutes": 10, "idleSeconds": 60 } }""")
        val c = coordinator(dir)
        assertEquals(60, c.sessionConfig.idleSeconds)
        val first = c.login("s1")
        val auth1 = first.authResults().single()
        assertTrue(auth1.success)
        assertNull(auth1.resumeToken, "no resume token issued when session.resume is false")

        val second = Session("s2")
        c.onSessionConnected(second)
        c.handleClientPacket(second, ClientPacket.ResumeSession("any-token", PROTOCOL_VERSION))
        val auth2 = second.authResults().single()
        assertFalse(auth2.success)
        assertTrue(auth2.message.contains("disabled", ignoreCase = true))
    }
}
