package com.morgana.server.moderation

import com.morgana.domain.access.AccessLevel
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.ClientSession
import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.OwnerPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ModerationCommandsTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        var closedWith: String? = null
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) { closedWith = reason }
        fun messages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().map { it.message }
        fun mapChat() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.MAP }.map { it.message }
        fun lastAuth() = sent.filterIsInstance<ServerPacket.AuthResult>().lastOrNull()
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private var counter = 0
    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name-${counter++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    /** Creates the account at the given level (log in once, raise it, log in again so the character picks it up). */
    private fun loginAs(c: MorganaWorldCoordinator, name: String, level: AccessLevel): FakeSession {
        val first = login(c, name)
        c.dataStore.accounts.findByUsername(name)!!.let { c.dataStore.accounts.save(it.copy(accessLevel = level)) }
        c.onSessionDisconnected(first)
        return login(c, name)
    }

    private fun say(c: MorganaWorldCoordinator, s: FakeSession, text: String) = c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, text))

    @Test
    fun `commands typed in the game's chat box reach the server as admin command packets and work the same`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)
        c.handleClientPacket(boss, ClientPacket.AdminSlashCommand("/mute Pat 5m spam"))
        assertTrue(pat.messages().any { "You have been muted" in it })
        c.handleClientPacket(pat, ClientPacket.AdminSlashCommand("/report Boss testing"))
        assertTrue(pat.messages().any { it.startsWith("Report #1 sent") })
        c.handleClientPacket(boss, ClientPacket.AdminSlashCommand("/plugins"))
        assertTrue(boss.messages().any { "does not load plugins" in it }, "the plugin command is reachable too")
        c.handleClientPacket(boss, ClientPacket.AdminSlashCommand("/ban Pat 1d"))
        assertFalse(online(c, "Pat"))
    }

    private fun online(c: MorganaWorldCoordinator, name: String) = c.simulation.players.values.any { it.name == name }

    @Test
    fun `ordinary players and monitors cannot use what they have no permission for`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)
        val mona = loginAs(c, "Mona", AccessLevel.MONITOR)
        loginAs(c, "Quinn", AccessLevel.PLAYER)

        say(c, pat, "/mute Quinn 5m spam")
        assertTrue(pat.messages().any { "permission" in it })
        say(c, pat, "/reports")
        assertTrue(pat.messages().count { "permission" in it } == 2)
        say(c, mona, "/ban Quinn 1d x")
        assertTrue(mona.messages().any { "permission" in it }, "monitors can mute and kick, not ban")
        assertTrue(online(c, "Quinn"))
    }

    @Test
    fun `a muted player cannot chat until unmuted, and it survives a restart`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)

        say(c, boss, "/mute Pat 30m too loud")
        assertTrue(pat.messages().any { "You have been muted" in it && "too loud" in it })
        say(c, pat, "hello?")
        assertTrue(pat.messages().any { it.startsWith("You are muted") })
        assertFalse(boss.mapChat().any { "hello?" in it }, "a muted player's chat goes nowhere")

        val c2 = coordinator(dir) // a restart
        val pat2 = login(c2, "Pat")
        say(c2, pat2, "still muted?")
        assertTrue(pat2.messages().any { it.startsWith("You are muted") })

        val boss2 = login(c2, "Boss")
        say(c2, boss2, "/unmute Pat")
        say(c2, pat2, "free again")
        assertTrue(boss2.mapChat().any { "free again" in it })
    }

    @Test
    fun `a ban closes the connection, blocks login with the reason and can be lifted`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)

        say(c, boss, "/ban Pat 7d selling gold")
        assertTrue(pat.sent.any { it is ServerPacket.SessionEnded && "selling gold" in it.reason })
        assertNotNull(pat.closedWith)
        assertFalse(online(c, "Pat"))

        val retry = login(c, "Pat")
        val refused = retry.lastAuth()!!
        assertFalse(refused.success)
        assertTrue("banned" in refused.message && "selling gold" in refused.message, refused.message)
        assertFalse(online(c, "Pat"))

        say(c, boss, "/bans")
        assertTrue(boss.messages().any { it.startsWith("Pat:") })
        say(c, boss, "/unban Pat")
        assertTrue(login(c, "Pat").lastAuth()!!.success)
    }

    @Test
    fun `a ban without a duration is permanent and its reason is optional`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        loginAs(c, "Pat", AccessLevel.PLAYER)
        say(c, boss, "/ban Pat griefing the market")
        val refused = login(c, "Pat").lastAuth()!!
        assertTrue("permanently" in refused.message && "griefing the market" in refused.message, refused.message)
    }

    @Test
    fun `nobody can act on themselves or on an equal or higher level, and bad names are explained`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        val dev = loginAs(c, "Dev", AccessLevel.DEVELOPER)
        loginAs(c, "Dev2", AccessLevel.DEVELOPER)

        say(c, dev, "/ban Boss 1d x")
        say(c, dev, "/ban Dev2 1d x")
        say(c, dev, "/ban Dev 1d x")
        assertEquals(3, dev.messages().count { "higher access level" in it || "yourself" in it }, dev.messages().toString())
        assertTrue(online(c, "Boss") && online(c, "Dev2"))

        say(c, boss, "/mute Nobody 5m x")
        assertTrue(boss.messages().any { "No player named Nobody" in it })
        say(c, boss, "/mute Dev2 soon x")
        assertTrue(boss.messages().any { it.startsWith("Usage: /mute") })
    }

    @Test
    fun `kick ends the session with the reason and needs an online player`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val mona = loginAs(c, "Mona", AccessLevel.MONITOR)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)
        say(c, mona, "/kick Pat being rude")
        assertTrue(pat.sent.any { it is ServerPacket.SessionEnded && "being rude" in it.reason && "Mona" in it.reason })
        assertFalse(online(c, "Pat"))
        say(c, mona, "/kick Pat again")
        assertTrue(mona.messages().any { "is not online" in it })
        assertTrue(login(c, "Pat").lastAuth()!!.success, "a kick is not a ban")
    }

    @Test
    fun `reports reach online moderators and are worked through in order`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val mona = loginAs(c, "Mona", AccessLevel.MONITOR)
        val pat = loginAs(c, "Pat", AccessLevel.PLAYER)
        loginAs(c, "Quinn", AccessLevel.PLAYER)

        say(c, pat, "/report Quinn using a bot")
        assertTrue(pat.messages().any { it.startsWith("Report #1 sent") })
        assertTrue(mona.messages().any { it.startsWith("New report #1") && "Quinn" in it })
        say(c, pat, "/report Quinn again")
        assertTrue(pat.messages().any { "Please wait" in it })
        say(c, pat, "/report Pat myself")
        assertTrue(pat.messages().any { "cannot report yourself" in it })
        say(c, pat, "/report Ghost something")
        assertTrue(pat.messages().any { "No player named Ghost" in it })

        say(c, mona, "/reports")
        assertTrue(mona.messages().any { it.startsWith("#1 Pat about Quinn") && "using a bot" in it }, mona.messages().toString())
        say(c, mona, "/report close 1 warned them")
        assertTrue(mona.messages().any { it == "Closed report #1." })
        say(c, mona, "/reports")
        assertTrue(mona.messages().any { it == "No open reports." })
    }

    @Test
    fun `the audit log records every action and can be read in game`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        loginAs(c, "Pat", AccessLevel.PLAYER)
        say(c, boss, "/mute Pat 5m spam")
        say(c, boss, "/unmute Pat")
        say(c, boss, "/modlog Pat")
        val lines = boss.messages().filter { " Boss mute Pat" in it || " Boss unmute Pat" in it }
        assertEquals(2, lines.size, boss.messages().toString())
        assertEquals(listOf("mute", "unmute"), c.moderation.recentAudit("Pat").map { it.action })
    }
}
