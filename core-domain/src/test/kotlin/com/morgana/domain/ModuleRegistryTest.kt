package com.morgana.domain

import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.ModuleRegistry
import com.morgana.domain.sim.OptionalModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleRegistryTest {
    @Test
    fun `built-in modules are registered the same way as plugin ones`() {
        val r = ModuleRegistry()
        assertEquals(OptionalModule.entries.size, r.all().size)
        assertTrue(r.all().all { it.builtIn })
        val d = r.register("stocks", "market", "Stock market", "Buy and sell shares")
        assertEquals("stocks:market", d.id)
        assertFalse(d.builtIn)
        assertEquals("Stock market", r.get("stocks:market")!!.displayName)
    }

    @Test
    fun `ids are namespaced so plugins cannot collide or shadow built-ins`() {
        val r = ModuleRegistry()
        r.register("stocks", "market", "Market")
        assertThrows(IllegalArgumentException::class.java) { r.register("stocks", "market", "Again") }
        assertThrows(IllegalArgumentException::class.java) { r.register("Bad Id", "market", "x") }
        assertThrows(IllegalArgumentException::class.java) { r.register("stocks", "has:colon", "x") }
        assertFalse(r.unregister("parties"))
        assertTrue(r.unregister("stocks:market"))
        assertNull(r.get("stocks:market"))
    }

    @Test
    fun `flags cover built-in and plugin module ids`() {
        val flags = ModuleFlags.fromDisabledKeys(listOf("trading", "stocks:market", "typo", "also:bad:id"))
        assertFalse(flags.isEnabled("trading"))
        assertFalse(flags.isEnabled(OptionalModule.TRADING))
        assertFalse(flags.isEnabled("stocks:market"))
        assertTrue(flags.isEnabled("guilds"))
        assertTrue(flags.isEnabled("stocks:other"))
        assertEquals(listOf("stocks:market", "trading"), flags.disabledKeys())
    }

    @Test
    fun `every system carries genre tags and requirements that name real systems`() {
        for (system in OptionalModule.entries) {
            assertTrue(system.tags.isNotEmpty(), "${system.configKey} has a genre tag")
            assertTrue(system.tags.all { it in com.morgana.domain.sim.SystemTags.TITLES }, "${system.configKey} uses known tags")
            assertTrue(system.requires.all { OptionalModule.fromConfigKey(it) != null }, "${system.configKey} requires real systems")
            assertFalse(system.configKey in system.requires, "${system.configKey} does not require itself")
        }
        // The registry shows the same metadata for the built-ins.
        val market = ModuleRegistry().get("market")!!
        assertEquals(setOf("mail"), market.requires)
        assertTrue("rpg" in market.tags)
    }

    @Test
    fun `a genre collects the systems tagged for it`() {
        val roguelike = OptionalModule.withTag(com.morgana.domain.sim.SystemTags.ROGUELIKE)
        assertTrue(OptionalModule.INSTANCING in roguelike && OptionalModule.PARTIES in roguelike)
        assertFalse(OptionalModule.GUILDS in roguelike)
        // A system can serve several genres.
        assertTrue(OptionalModule.INSTANCING in OptionalModule.withTag(com.morgana.domain.sim.SystemTags.FARMING))
    }

    @Test
    fun `unmet requirements are found from the switched off keys`() {
        assertEquals(listOf(OptionalModule.MARKET to OptionalModule.MAIL), OptionalModule.unmetRequirements(listOf("mail")))
        // With the market off too, nothing is wrong.
        assertTrue(OptionalModule.unmetRequirements(listOf("mail", "market")).isEmpty())
        assertTrue(OptionalModule.unmetRequirements(emptyList()).isEmpty())
        assertTrue(OptionalModule.unmetRequirements(listOf("trading", "typo")).isEmpty())
        // Guild War and the guild bank need guilds.
        assertEquals(
            listOf(OptionalModule.GUILD_WAR to OptionalModule.GUILDS, OptionalModule.GUILD_BANK to OptionalModule.GUILDS),
            OptionalModule.unmetRequirements(listOf("guilds"))
        )
    }
}
