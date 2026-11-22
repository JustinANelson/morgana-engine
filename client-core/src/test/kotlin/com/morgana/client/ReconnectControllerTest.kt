package com.morgana.client

import com.morgana.client.net.ReconnectController
import com.morgana.client.net.ReconnectController.Action
import com.morgana.client.net.ReconnectController.State
import com.morgana.client.net.ReconnectPolicy
import com.morgana.client.net.ResumeInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconnectControllerTest {

    private val policy = ReconnectPolicy(
        pingIntervalMs = 1_000, deadAfterMs = 5_000, probeTimeoutMs = 1_500, attemptTimeoutMs = 3_000,
        backoffMs = listOf(100, 200, 400), maxAttempts = 4
    )

    private fun online(now: Long = 1_000) = ReconnectController(policy).also { it.loggedIn(now) }

    @Test
    fun `offline controller never acts`() {
        val c = ReconnectController(policy)
        assertEquals(Action.None, c.update(1_000, connected = false))
        assertFalse(c.isReconnecting)
    }

    @Test
    fun `a healthy connection is pinged on schedule and left alone otherwise`() {
        val c = online(1_000)
        assertEquals(Action.None, c.update(1_500, true))
        assertEquals(Action.SendPing, c.update(2_000, true))
        c.heard(2_100)
        assertEquals(Action.None, c.update(2_500, true))
        assertEquals(Action.SendPing, c.update(3_000, true))
    }

    @Test
    fun `a silent connection is dropped after the dead-after window`() {
        val c = online(1_000)
        var now = 1_000L
        var dropped = false
        while (now < 7_000 && !dropped) {
            now += 500
            if (c.update(now, true) == Action.DropConnection) dropped = true
        }
        assertTrue(dropped)
        assertTrue(now >= 6_000, "must not drop before 5s of silence (was $now)")
    }

    @Test
    fun `losing the connection starts an immediate reconnect attempt`() {
        val c = online(1_000)
        val a = c.update(2_000, connected = false)
        assertEquals(Action.StartAttempt(1), a)
        assertEquals(State.ATTEMPTING, c.state)
        assertTrue(c.isReconnecting)
        assertEquals("Reconnecting... (attempt 1)", c.statusText(2_000))
    }

    @Test
    fun `failed attempts back off then give up, and retry restarts the count`() {
        val c = online(0)
        assertEquals(Action.StartAttempt(1), c.update(1_000, false))
        c.attemptFailed(1_000)
        assertEquals(State.WAITING, c.state)
        assertEquals(1_100, c.nextAttemptAtMs)
        assertEquals(Action.None, c.update(1_050, false))
        assertEquals(Action.StartAttempt(2), c.update(1_100, false))
        c.attemptFailed(1_100)
        assertEquals(1_300, c.nextAttemptAtMs) // 200ms second step
        assertEquals(Action.StartAttempt(3), c.update(1_300, false))
        c.attemptFailed(1_300)
        assertEquals(1_700, c.nextAttemptAtMs) // 400ms third step
        assertEquals(Action.StartAttempt(4), c.update(1_700, false))
        c.attemptFailed(1_700)
        assertEquals(State.GAVE_UP, c.state)
        assertEquals(Action.None, c.update(60_000, false))
        assertEquals("Could not reach the server.", c.statusText(60_000))

        c.retryNow(60_000)
        assertEquals(Action.StartAttempt(1), c.update(60_000, false))
    }

    @Test
    fun `retry now skips the wait`() {
        val c = online(0)
        c.update(1_000, false)
        c.attemptFailed(1_000)
        c.retryNow(1_010)
        assertEquals(Action.StartAttempt(2), c.update(1_010, false))
    }

    @Test
    fun `an attempt that never finishes times out and the connection is dropped`() {
        val c = online(0)
        assertEquals(Action.StartAttempt(1), c.update(1_000, false))
        assertEquals(Action.None, c.update(3_999, true))
        assertEquals(Action.DropConnection, c.update(4_000, true))
        assertEquals(State.WAITING, c.state)
    }

    @Test
    fun `a successful resume returns to online and clears the attempt counter`() {
        val c = online(0)
        c.update(1_000, false)
        c.loggedIn(1_500)
        assertEquals(State.ONLINE, c.state)
        assertEquals(0, c.attempt)
        assertEquals(Action.None, c.update(1_600, true))
    }

    @Test
    fun `returning to the foreground probes the connection and drops it if nothing answers`() {
        val c = online(1_000)
        c.update(1_500, true)
        c.heard(9_900) // a short stint in the background: the last packet is recent but the socket may have died since
        c.appResumed(10_000)
        // The probe pings straight away...
        assertEquals(Action.SendPing, c.update(10_000, true))
        // ...and a silent connection is declared dead at the probe deadline, long before deadAfterMs would notice.
        assertTrue(c.update(11_000, true) != Action.DropConnection)
        assertEquals(Action.DropConnection, c.update(11_500, true))
    }

    @Test
    fun `a prompt answer cancels the probe`() {
        val c = online(1_000)
        c.heard(9_900)
        c.appResumed(10_000)
        assertEquals(Action.SendPing, c.update(10_000, true))
        c.heard(10_200)
        assertTrue(c.update(11_600, true) != Action.DropConnection)
    }

    @Test
    fun `stop and launch-time resume`() {
        val c = ReconnectController(policy)
        c.resumeAtLaunch(5_000)
        assertEquals(State.WAITING, c.state)
        assertEquals(Action.StartAttempt(1), c.update(5_000, false))
        c.stop()
        assertEquals(State.OFFLINE, c.state)
        assertEquals(Action.None, c.update(9_000, false))
    }

    @Test
    fun `stall hint appears when a connection goes quiet`() {
        val c = online(1_000)
        assertFalse(c.isStalled(5_000))
        assertTrue(c.isStalled(9_500))
    }

    @Test
    fun `resume info survives a json round trip and tolerates garbage`() {
        val info = ResumeInfo("play.example.com", 7002, true, "morgana-classic", "tok_123")
        assertEquals(info, ResumeInfo.fromJson(info.toJson()))
        assertNull(ResumeInfo.fromJson("not json"))
        assertNull(ResumeInfo.fromJson(""))
        assertNull(ResumeInfo.fromJson(null))
    }

    @Test
    fun `a long absence is detected as a dead connection without waiting for a probe`() {
        val c = online(1_000)
        c.appResumed(100_000)
        assertEquals(Action.DropConnection, c.update(100_000, true))
    }

    @Test
    fun `losing the network drops a connection that still looks open`() {
        val c = online()
        c.networkLost(2_000)
        assertEquals(Action.DropConnection, c.update(2_000, connected = true))
        // The transport then reports closed, and the controller starts reconnecting at once.
        assertEquals(Action.StartAttempt(1), c.update(2_001, connected = false))
    }

    @Test
    fun `losing the network does nothing when not online`() {
        val c = ReconnectController(policy)
        c.networkLost(1_000)
        assertEquals(Action.None, c.update(1_000, connected = false))
    }

    @Test
    fun `the network returning probes a connection that claims to be up`() {
        val c = online()
        c.networkReturned(2_000)
        assertEquals(Action.SendPing, c.update(2_000, connected = true))
        assertEquals(Action.DropConnection, c.update(2_000 + policy.probeTimeoutMs, connected = true))
    }

    @Test
    fun `the network returning cuts short the back-off and revives a given-up controller`() {
        val c = online()
        c.update(2_000, connected = false) // attempt 1
        c.attemptFailed(2_000)
        assertEquals(Action.None, c.update(2_050, connected = false), "still backing off")
        c.networkReturned(2_050)
        assertEquals(Action.StartAttempt(2), c.update(2_050, connected = false))

        var now = 3_000L
        c.attemptFailed(now)
        while (c.state != State.GAVE_UP) {
            now += 1_000
            if (c.update(now, connected = false) is Action.StartAttempt) c.attemptFailed(now)
        }
        c.networkReturned(now)
        assertEquals(Action.StartAttempt(1), c.update(now, connected = false))
    }
}
