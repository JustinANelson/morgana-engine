package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.social.GuildRules
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.IgnoreActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GuildAndIgnoreServerTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun systemMessages() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
        fun chat(channel: ChatChannel) = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == channel }.map { it.message }
        fun lastRoster() = outbox.filterIsInstance<ServerPacket.GuildRoster>().lastOrNull()?.members
        fun lastIgnored() = outbox.filterIsInstance<ServerPacket.IgnoreList>().lastOrNull()?.names
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private fun player(c: MorganaWorldCoordinator, name: String) = c.simulation.players.values.first { it.name == name }

    private fun guildOf(c: MorganaWorldCoordinator, name: String) = player(c, name).guildId?.let { c.simulation.guilds[it] }

    @Test
    fun `a guild is founded, joined by invitation, and ranks and kicks follow the rules`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        val d = login(c, "Delta")

        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "HRO"))
        val guildId = player(c, "Alpha").guildId!!
        assertEquals(GuildRules.FOUNDER, player(c, "Alpha").guildRank)
        assertEquals(listOf("Alpha"), a.lastRoster()!!.map { it.name })

        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.INVITE, "Bravo"))
        assertTrue(b.systemMessages().any { it.contains("invited you to Heroes") })
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.ACCEPT))
        assertEquals(guildId, player(c, "Bravo").guildId)
        assertEquals(setOf("Alpha", "Bravo"), b.lastRoster()!!.map { it.name }.toSet())
        assertEquals(setOf("Alpha", "Bravo"), a.lastRoster()!!.map { it.name }.toSet(), "the founder's roster refreshed")

        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.INVITE, "Delta"))
        assertTrue(b.systemMessages().any { it.contains("Only the founder and officers") })
        c.handleClientPacket(d, ClientPacket.GuildAction(GuildActionType.ACCEPT))
        assertTrue(d.systemMessages().any { it.contains("no guild invitation") })

        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.PROMOTE, "Bravo"))
        assertEquals(GuildRules.OFFICER, player(c, "Bravo").guildRank)
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.INVITE, "Delta"))
        c.handleClientPacket(d, ClientPacket.GuildAction(GuildActionType.ACCEPT))
        assertEquals(guildId, player(c, "Delta").guildId)

        c.handleClientPacket(d, ClientPacket.GuildAction(GuildActionType.KICK, "Bravo"))
        assertEquals(guildId, player(c, "Bravo").guildId, "a member cannot kick")
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.KICK, "Delta"))
        assertNull(player(c, "Delta").guildId)
        assertEquals("", player(c, "Delta").guildRank)
        assertEquals(setOf("Alpha", "Bravo"), c.dataStore.social.guilds.findById(guildId)!!.memberRanks.keys.let { ids -> ids.map { id -> c.simulation.players[id]!!.name }.toSet() })
    }

    @Test
    fun `the founder leaving hands the guild on, and the last member disbands it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "HRO"))
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.INVITE, "Bravo"))
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.ACCEPT))
        val id = player(c, "Alpha").guildId!!

        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.LEAVE))
        assertNull(player(c, "Alpha").guildId)
        assertEquals(GuildRules.FOUNDER, player(c, "Bravo").guildRank)
        assertEquals(player(c, "Bravo").id, c.dataStore.social.guilds.findById(id)!!.founderCharacterId)

        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.LEAVE))
        assertNull(c.dataStore.social.guilds.findById(id))
        assertTrue(c.simulation.guilds.isEmpty())
    }

    @Test
    fun `a kick while offline takes effect at the next login`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "HRO"))
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.INVITE, "Bravo"))
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.ACCEPT))
        c.onSessionDisconnected(b)

        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.KICK, "Bravo"))
        assertNull(c.dataStore.characters.findByName("Bravo")!!.guildId, "the stored character was updated")
        val b2 = login(c, "Bravo")
        assertNull(player(c, "Bravo").guildId)
        assertTrue(b2.outbox.none { it is ServerPacket.GuildRoster && it.members.isNotEmpty() })
    }

    @Test
    fun `bad guild requests are refused`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "H", "X"))
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "WAYTOOLONGTAG"))
        assertNull(guildOf(c, "Alpha"))
        c.handleClientPacket(a, ClientPacket.GuildAction(GuildActionType.CREATE, "Heroes", "HRO"))
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.CREATE, "heroes", "HRO"))
        assertNull(guildOf(c, "Bravo"))
        assertTrue(b.systemMessages().any { it.contains("already exists") })
        c.handleClientPacket(b, ClientPacket.GuildAction(GuildActionType.SET_MOTD, "hi"))
        assertTrue(b.systemMessages().any { it.contains("not in a guild") })
    }

    @Test
    fun `party invitations tell the invited player`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Bravo"))
        assertTrue(b.systemMessages().any { it.contains("Alpha invited you to a party") })
        assertTrue(a.systemMessages().any { it.contains("Invitation sent to Bravo") })
        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Nobody"))
        assertTrue(a.systemMessages().any { it.contains("No player named") })
        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.ACCEPT))
        assertTrue(a.systemMessages().any { it.contains("no party invitation") })
    }

    @Test
    fun `an ignored player's chat, whispers, invitations and mail do not arrive`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        val d = login(c, "Delta")

        c.handleClientPacket(b, ClientPacket.IgnoreAction(IgnoreActionType.ADD, "alpha"))
        assertEquals(listOf("Alpha"), b.lastIgnored())

        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.GLOBAL, "hello world"))
        assertTrue(d.chat(ChatChannel.GLOBAL).contains("hello world"))
        assertFalse(b.chat(ChatChannel.GLOBAL).contains("hello world"))

        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.WHISPER, "psst", targetPlayerName = "Bravo"))
        assertTrue(a.chat(ChatChannel.WHISPER).contains("psst"), "the sender cannot tell")
        assertFalse(b.chat(ChatChannel.WHISPER).contains("psst"))

        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Bravo"))
        assertTrue(b.systemMessages().none { it.contains("invited you") })
        c.handleClientPacket(a, ClientPacket.SendMail("Bravo", "Hi", "x"))
        assertTrue(a.systemMessages().any { it.contains("not accepting letters") })
        assertTrue(c.dataStore.social.mail.findAll().isEmpty())

        c.handleClientPacket(b, ClientPacket.IgnoreAction(IgnoreActionType.REMOVE, "ALPHA"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.GLOBAL, "back again"))
        assertTrue(b.chat(ChatChannel.GLOBAL).contains("back again"))
    }

    @Test
    fun `slash commands work, staff cannot be ignored, and the list is kept`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        val mod = login(c, "Mod")
        c.simulation.players[player(c, "Mod").id] = player(c, "Mod").copy(accessLevel = AccessLevel.MONITOR)

        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "/ignore Bravo"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "/ignore Mod"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "/ignore Alpha"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "/ignorelist"))
        val said = a.systemMessages().joinToString(" | ")
        for (part in listOf("You now ignore Bravo", "Staff cannot be ignored", "cannot ignore yourself", "Ignoring: Bravo")) assertTrue(said.contains(part), "missing $part in $said")

        c.handleClientPacket(mod, ClientPacket.SendChat(ChatChannel.GLOBAL, "staff here"))
        assertEquals(setOf("Bravo"), player(c, "Alpha").ignoredNames)
        c.onSessionDisconnected(a)
        login(c, "Alpha")
        assertEquals(setOf("Bravo"), player(c, "Alpha").ignoredNames, "saved with the character")
        assertTrue(b.systemMessages().none { it.contains("ignore") })
    }

    @Test
    fun `with the ignore module off the packets and commands are refused`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.IGNORE)))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.IgnoreAction(IgnoreActionType.ADD, "Bravo"))
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "/ignore Bravo"))
        assertEquals(2, a.systemMessages().count { it.contains("switched off") })
        assertTrue(player(c, "Alpha").ignoredNames.isEmpty())
    }
}
