package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.PROTOCOL_VERSION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase18PacketLimiterTest {

    private var now = 0L
    private fun limiter(settings: PacketLimitSettings = PacketLimitSettings()) = PacketLimiter(settings) { now }

    private val chat = ClientPacket.SendChat(ChatChannel.MAP, "hi")
    private val move = ClientPacket.MoveInput(Direction.UP)
    private val login = ClientPacket.Authenticate("name", "pw", true, 1, protocolVersion = PROTOCOL_VERSION)

    private fun LimitDecision.ok() = verdict == LimitVerdict.ALLOW

    @Test
    fun `a burst is allowed, the excess is dropped, and tokens refill over time`() {
        val l = limiter()
        val results = (1..10).map { l.admit("s", true, chat).ok() }
        assertEquals(List(6) { true } + List(4) { false }, results, "chat burst is 6")

        now += 1_500 // chat refills 40 per minute = one token per 1.5 s
        assertTrue(l.admit("s", true, chat).ok())
        assertFalse(l.admit("s", true, chat).ok())

        now += 60_000 // a long pause never banks more than the burst
        assertEquals(6, (1..20).count { l.admit("s", true, chat).ok() })
    }

    @Test
    fun `classes and connections have independent budgets`() {
        val l = limiter()
        repeat(6) { l.admit("a", true, chat) }
        assertFalse(l.admit("a", true, chat).ok(), "chat exhausted on a")
        assertTrue(l.admit("a", true, move).ok(), "movement has its own bucket")
        assertTrue(l.admit("b", true, chat).ok(), "another connection is unaffected")
    }

    @Test
    fun `normal play stays far below the budgets`() {
        val l = limiter()
        // Two minutes of the busiest legitimate client: free movement at about 22/s plus 4 actions/s and an occasional chat.
        var dropped = 0
        for (tick in 0 until 2400) {
            now = tick * 50L
            repeat(if (tick % 10 == 0) 2 else 1) { if (!l.admit("p", true, move).ok()) dropped++ }
            if (tick % 5 == 0 && !l.admit("p", true, ClientPacket.Attack).ok()) dropped++
            if (tick % 100 == 0 && !l.admit("p", true, chat).ok()) dropped++ // one chat every 5 s
        }
        assertEquals(0, dropped)
        assertTrue(l.droppedCounts().isEmpty())
    }

    @Test
    fun `a script hammering movement is cut off almost at once`() {
        val l = limiter()
        val allowed = (1..500).count { l.admit("bot", true, move).ok() }
        assertEquals(40, allowed, "only the burst gets through when nothing refills")
        assertEquals(460L, l.droppedCounts()[PacketClass.MOVE])
    }

    @Test
    fun `sustained flooding disconnects, with a clear notice, and the window resets`() {
        val l = limiter()
        repeat(6) { l.admit("s", true, chat) }
        val verdicts = (1..100).map { l.admit("s", true, chat).verdict }
        assertEquals(LimitVerdict.KICK, verdicts.last())
        assertTrue(verdicts.dropLast(1).all { it == LimitVerdict.DROP })
        assertEquals(1L, l.kickCount())

        // Occasional drops spread over time never add up to a kick.
        val l2 = limiter()
        repeat(6) { l2.admit("s", true, chat) }
        var kicked = false
        repeat(60) {
            now += 11_000 // a fresh window every time
            l2.admit("s", true, chat)
            if (l2.admit("s", true, chat).verdict == LimitVerdict.KICK) kicked = true
        }
        assertFalse(kicked)
    }

    @Test
    fun `the player is told to slow down at most every few seconds`() {
        val l = limiter()
        repeat(6) { l.admit("s", true, chat) }
        val first = l.admit("s", true, chat)
        assertNotNull(first.notice)
        assertTrue(first.notice!!.contains("chat"))
        assertNull(l.admit("s", true, chat).notice, "no repeat within 5 s")
        now += PacketLimiter.NOTICE_EVERY_MS // refills about 3 chat tokens, so spend them and hit the limit again
        val dropAgain = (1..6).map { l.admit("s", true, chat) }.first { !it.ok() }
        assertNotNull(dropAgain.notice)
    }

    @Test
    fun `before login only login and ping are accepted, and strangers are kicked quickly`() {
        val l = limiter()
        assertTrue(l.admit("x", false, login).ok())
        assertTrue(l.admit("x", false, ClientPacket.Ping(1)).ok())
        assertEquals(PacketClass.PRE_AUTH, PacketLimiter.classify(move, authenticated = false))
        assertEquals(PacketClass.MOVE, PacketLimiter.classify(move, authenticated = true))

        val l2 = limiter()
        val verdicts = (1..40).map { l2.admit("stranger", false, move) }
        assertEquals(5, verdicts.count { it.ok() })
        assertEquals(LimitVerdict.KICK, verdicts.first { !it.ok() && it.verdict == LimitVerdict.KICK }.verdict)
        assertTrue(verdicts.none { it.notice != null && it.verdict == LimitVerdict.DROP }, "no chatter to unauthenticated peers")
    }

    @Test
    fun `a client pinging at its default cadence is never limited, even with tick jitter`() {
        val l = limiter()
        val interval = 3_000L // SendGatePolicy.pingIntervalMs (client-core), plus the 10 s keep-alive pings
        var dropped = 0
        repeat(600) { i ->
            // Ticks stall for a moment every so often, then the buffered pings arrive together.
            now += if (i % 7 == 0) 0 else interval + (if (i % 5 == 0) 250 else 0)
            if (!l.admit("s", true, ClientPacket.Ping(now)).ok()) dropped++
        }
        assertEquals(0, dropped)
    }

    @Test
    fun `login attempts are limited tightly because each can cost a password hash`() {
        val l = limiter()
        assertEquals(6, (1..30).count { l.admit("s", false, login).ok() })
        assertEquals(1L, l.kickCount(), "the 20th dropped login attempt disconnected the connection")
        assertFalse(l.admit("s", false, login).ok(), "and a kicked connection stays blocked")
        assertEquals(1L, l.kickCount(), "without being kicked again")
    }

    @Test
    fun `scale makes the budgets stricter or looser and disabled turns them off`() {
        val half = limiter(PacketLimitSettings(scale = 0.5))
        assertEquals(3, (1..20).count { half.admit("s", true, chat).ok() })
        val double = limiter(PacketLimitSettings(scale = 2.0))
        assertEquals(12, (1..40).count { double.admit("s", true, chat).ok() })
        val off = limiter(PacketLimitSettings(enabled = false))
        assertEquals(1000, (1..1000).count { off.admit("s", true, chat).ok() })
        assertTrue(off.droppedCounts().isEmpty())
    }

    @Test
    fun `forgetting a connection releases its state`() {
        val l = limiter()
        repeat(6) { l.admit("s", true, chat) }
        assertFalse(l.admit("s", true, chat).ok())
        l.forget("s")
        assertTrue(l.admit("s", true, chat).ok())
    }

    @Test
    fun `every packet type is classified so none can bypass the limiter`() {
        // The classifier is an exhaustive `when`, so a new packet fails to compile until classified. Spot check the groups.
        val samples = mapOf(
            PacketClass.ACTION to listOf(ClientPacket.Attack, ClientPacket.UseInventorySlot(0), ClientPacket.InteractFacing),
            PacketClass.ECONOMY to listOf(ClientPacket.ShopBuy(1, 0), ClientPacket.CraftRecipe(1), ClientPacket.PartyAction(PartyActionType.LEAVE)),
            PacketClass.EDIT to listOf(ClientPacket.RequestAdminCatalog),
            PacketClass.COMMAND to listOf(ClientPacket.AdminSlashCommand("/who")),
            PacketClass.CHAT to listOf(chat)
        )
        for ((cls, packets) in samples) packets.forEach { assertEquals(cls, PacketLimiter.classify(it, true), it.toString()) }
    }

    @Test
    fun `over-long text fields are refused with a reason`() {
        assertNull(InputLimits.violation(chat))
        assertNull(InputLimits.violation(ClientPacket.SendChat(ChatChannel.MAP, "x".repeat(InputLimits.MAX_CHAT))))
        assertNotNull(InputLimits.violation(ClientPacket.SendChat(ChatChannel.MAP, "x".repeat(InputLimits.MAX_CHAT + 1))))
        assertNotNull(InputLimits.violation(ClientPacket.SendChat(ChatChannel.WHISPER, "hi", "n".repeat(33))))
        assertNotNull(InputLimits.violation(ClientPacket.AdminSlashCommand("/" + "a".repeat(400))))
        assertNotNull(InputLimits.violation(ClientPacket.GuildAction(GuildActionType.CREATE, "g".repeat(201), "TAG")))
        assertNotNull(InputLimits.violation(ClientPacket.GuildAction(GuildActionType.CREATE, "Guild", "T".repeat(9))))
        assertNull(InputLimits.violation(ClientPacket.GuildAction(GuildActionType.CREATE, "Guild", "TAG")))
        assertNotNull(InputLimits.violation(ClientPacket.PartyAction(PartyActionType.INVITE, "p".repeat(33))))
    }

    @Test
    fun `the raw ceiling allows real traffic and cuts a message storm`() {
        val guard = RawFloodGuard()
        val second = 1_000_000_000L
        // A second of heavy but legitimate traffic, then the next second starts a fresh count.
        assertTrue((0 until 100).all { guard.allow(it * 1_000_000L) })
        assertTrue(guard.allow(second + 5))
        val storm = RawFloodGuard()
        val allowed = (0 until 2000).count { storm.allow(it * 100L) } // 2000 messages inside 0.2 ms
        assertEquals(RawFloodGuard.MAX_PER_SECOND, allowed)
        assertTrue(storm.allow(second * 3), "a quiet period resets the count")
    }
}
