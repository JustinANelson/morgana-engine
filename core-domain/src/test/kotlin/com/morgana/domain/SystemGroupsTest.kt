package com.morgana.domain

import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SystemGroups
import com.morgana.domain.sim.SystemTags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The system list as the editors show it, and what a genre starter switches off. */
class SystemGroupsTest {
    @Test
    fun `every system appears exactly once, under one heading, in genre order`() {
        val groups = SystemGroups.grouped()
        val all = groups.flatMap { it.systems }
        assertEquals(OptionalModule.entries.size, all.size)
        assertEquals(OptionalModule.entries.toSet(), all.toSet(), "nothing is missing and nothing is listed twice")
        assertTrue(groups.none { it.systems.isEmpty() }, "empty groups are left out")
        val order = (SystemTags.TITLES.keys + SystemGroups.GENERAL).toList()
        assertEquals(groups.map { it.tag }, groups.map { it.tag }.sortedBy { order.indexOf(it) }, "headings follow the genre order, general last")
    }

    @Test
    fun `a system lives under the first genre it lists and a system for every genre goes under general`() {
        val groups = SystemGroups.grouped().associate { it.tag to it.systems }
        assertTrue(OptionalModule.PARTIES in groups.getValue(SystemTags.RPG))
        assertTrue(OptionalModule.INSTANCING in groups.getValue(SystemTags.ROGUELIKE), "instancing lists roguelike first")
        assertTrue(OptionalModule.ROLE_PLAY_CHAT in groups.getValue(SystemTags.ROLEPLAY))
        assertEquals(listOf(OptionalModule.LUA_PLUGINS), groups.getValue(SystemGroups.GENERAL))
        assertEquals("Online RPG", SystemGroups.grouped().first { it.tag == SystemTags.RPG }.title)
    }

    @Test
    fun `grouping a subset keeps only that subset`() {
        val groups = SystemGroups.grouped(listOf(OptionalModule.MAIL, OptionalModule.THREAT))
        assertEquals(setOf(OptionalModule.MAIL, OptionalModule.THREAT), groups.flatMap { it.systems }.toSet())
    }

    @Test
    fun `a genre keeps the systems tagged for it and switches the rest off`() {
        val off = SystemGroups.disabledKeysFor(SystemTags.ROGUELIKE)
        for (kept in listOf("parties", "instancing", "projectiles", "crafting", "threat", "bossEncounters", "durability", "luaPlugins")) {
            assertFalse(kept in off, "$kept belongs to a roguelike")
        }
        for (dropped in listOf("guilds", "trading", "market", "mail", "friends", "arena", "guildWar", "guildBank", "stalls", "promotion", "rebirth")) {
            assertTrue(dropped in off, "$dropped is not part of a roguelike")
        }
        assertEquals(OptionalModule.entries.size, off.size + OptionalModule.withTag(SystemTags.ROGUELIKE).size)
    }

    @Test
    fun `every genre leaves some systems on, every one of its own, and only real keys off`() {
        val real = OptionalModule.entries.map { it.configKey }.toSet()
        for (genre in SystemTags.TITLES.keys) {
            val off = SystemGroups.disabledKeysFor(genre)
            assertTrue(real.containsAll(off), "$genre switches off only real systems")
            assertTrue(off.size < real.size, "$genre keeps something on")
            assertTrue(OptionalModule.withTag(genre).none { it.configKey in off }, "$genre keeps all of its own systems")
            assertFalse("luaPlugins" in off, "Lua plugins work in every genre")
        }
    }

    @Test
    fun `a name that is not a genre is refused`() {
        val e = assertThrows(IllegalArgumentException::class.java) { SystemGroups.disabledKeysFor("western") }
        assertTrue("western" in e.message!! && "roguelike" in e.message!!)
        assertTrue(SystemGroups.isGenre("farming") && !SystemGroups.isGenre("general"))
    }
}
