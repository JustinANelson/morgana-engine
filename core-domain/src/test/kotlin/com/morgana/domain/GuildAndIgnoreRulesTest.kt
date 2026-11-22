package com.morgana.domain

import com.morgana.domain.social.GuildRules
import com.morgana.domain.social.GuildRules.Change
import com.morgana.domain.social.IgnoreRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuildAndIgnoreRulesTest {
    private fun guild() = GuildRules.create("g1", " Heroes ", "hro", "f")

    private fun Change.guild() = (this as Change.Updated).guild

    @Test
    fun `creation checks name and tag and uniqueness`() {
        assertNull(GuildRules.creationProblem("Heroes", "HRO", listOf("Others")))
        assertNotNull(GuildRules.creationProblem("H", "HRO", emptyList()))
        assertNotNull(GuildRules.creationProblem("x".repeat(GuildRules.MAX_NAME + 1), "T", emptyList()))
        assertNotNull(GuildRules.creationProblem("Heroes", "", emptyList()))
        assertNotNull(GuildRules.creationProblem("Heroes", "TOOLONG", emptyList()))
        assertNotNull(GuildRules.creationProblem("heroes", "HRO", listOf("Heroes")))
        assertEquals("Heroes", guild().name)
        assertEquals("HRO", guild().tag)
        assertEquals(mapOf("f" to GuildRules.FOUNDER), guild().memberRanks)
    }

    @Test
    fun `joining adds a member and respects the size limit`() {
        val g = GuildRules.join(guild(), "a").guild()
        assertEquals(GuildRules.MEMBER, g.memberRanks["a"])
        assertTrue(GuildRules.join(g, "a") is Change.Refused)
        val full = guild().copy(memberRanks = (1..GuildRules.MAX_MEMBERS).associate { "m$it" to GuildRules.MEMBER })
        assertTrue(GuildRules.join(full, "new") is Change.Refused)
    }

    @Test
    fun `ranks decide who can invite, kick and promote`() {
        assertTrue(GuildRules.canInvite(GuildRules.OFFICER))
        assertFalse(GuildRules.canInvite(GuildRules.MEMBER))
        assertTrue(GuildRules.canKick(GuildRules.OFFICER, GuildRules.MEMBER))
        assertFalse(GuildRules.canKick(GuildRules.OFFICER, GuildRules.OFFICER))
        assertTrue(GuildRules.canKick(GuildRules.FOUNDER, GuildRules.OFFICER))
        assertFalse(GuildRules.canKick(GuildRules.FOUNDER, GuildRules.FOUNDER))
        assertFalse(GuildRules.canKick(GuildRules.MEMBER, GuildRules.MEMBER))
    }

    @Test
    fun `the founder promotes and demotes, nobody else can`() {
        var g = GuildRules.join(guild(), "a").guild()
        g = GuildRules.join(g, "b").guild()
        g = GuildRules.setRank(g, "f", "a", promote = true).guild()
        assertEquals(GuildRules.OFFICER, g.memberRanks["a"])
        assertTrue(GuildRules.setRank(g, "a", "b", promote = true) is Change.Refused)
        assertTrue(GuildRules.setRank(g, "f", "a", promote = true) is Change.Refused, "already an officer")
        assertTrue(GuildRules.setRank(g, "f", "f", promote = false) is Change.Refused)
        assertEquals(GuildRules.MEMBER, GuildRules.setRank(g, "f", "a", promote = false).guild().memberRanks["a"])
    }

    @Test
    fun `kicking follows the rank rules`() {
        var g = GuildRules.join(guild(), "a").guild()
        g = GuildRules.join(g, "b").guild()
        g = GuildRules.setRank(g, "f", "a", promote = true).guild()
        assertTrue(GuildRules.kick(g, "b", "a") is Change.Refused)
        assertTrue(GuildRules.kick(g, "a", "f") is Change.Refused)
        assertFalse("b" in GuildRules.kick(g, "a", "b").guild().memberRanks)
        assertTrue(GuildRules.kick(g, "f", "nobody") is Change.Refused)
    }

    @Test
    fun `a leaving founder hands over to an officer, else a member, and the last member disbands`() {
        var g = GuildRules.join(guild(), "a").guild()
        g = GuildRules.join(g, "b").guild()
        g = GuildRules.setRank(g, "f", "b", promote = true).guild()
        val handed = GuildRules.leave(g, "f").guild()
        assertEquals("b", handed.founderCharacterId)
        assertEquals(GuildRules.FOUNDER, handed.memberRanks["b"])
        assertEquals(setOf("a", "b"), handed.memberRanks.keys)

        val soloHeir = GuildRules.leave(GuildRules.join(guild(), "a").guild(), "f").guild()
        assertEquals("a", soloHeir.founderCharacterId)
        assertEquals(Change.Disbanded, GuildRules.leave(guild(), "f"))
        assertTrue(GuildRules.leave(guild(), "stranger") is Change.Refused)
    }

    @Test
    fun `only founder and officers set the message of the day`() {
        val g = GuildRules.join(guild(), "a").guild()
        assertEquals("Hi", GuildRules.setMotd(g, "f", "Hi").guild().motd)
        assertTrue(GuildRules.setMotd(g, "a", "Hi") is Change.Refused)
        assertTrue(GuildRules.setMotd(g, "f", "x".repeat(GuildRules.MAX_MOTD + 1)) is Change.Refused)
    }

    @Test
    fun `ignore list ignores case and refuses self and duplicates`() {
        val one = IgnoreRules.add(emptySet(), "Ann", "Bob") as IgnoreRules.Change.Updated
        assertTrue(IgnoreRules.contains(one.names, "bOB"))
        assertTrue(IgnoreRules.add(one.names, "Ann", "bob") is IgnoreRules.Change.Refused)
        assertTrue(IgnoreRules.add(one.names, "Ann", "ann") is IgnoreRules.Change.Refused)
        assertTrue(IgnoreRules.add((1..IgnoreRules.MAX_IGNORED).map { "n$it" }.toSet(), "Ann", "Extra") is IgnoreRules.Change.Refused)
        assertEquals(emptySet<String>(), (IgnoreRules.remove(one.names, "BOB") as IgnoreRules.Change.Updated).names)
        assertTrue(IgnoreRules.remove(one.names, "Zed") is IgnoreRules.Change.Refused)
    }
}
