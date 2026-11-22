package com.morgana.client.realms

import com.morgana.client.preview.demoCharacterList
import com.morgana.client.preview.demoRealmDirectory
import com.morgana.client.preview.fakeCharactersFromQuery
import com.morgana.client.preview.fakeRealmsFromQuery
import com.morgana.client.state.RecentRealmEntry
import com.morgana.protocol.realms.RealmSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealmBrowserModelTest {

    private fun realm(id: String, name: String = id, online: Int = 0, max: Int = 100, tags: List<String> = emptyList(), desc: String = "", api: Int = 1) =
        RealmSummary(id = id, name = name, description = desc, host = "$id.example.com", onlinePlayers = online, maxPlayers = max, tags = tags, engineApi = api)

    private fun model(
        realms: List<RealmSummary>,
        featured: String? = null,
        favorites: Set<String> = emptySet(),
        recents: Map<String, Long> = emptyMap()
    ) = RealmBrowserModel(realms, featured, { it in favorites }, recents.map { (id, t) -> RecentRealmEntry(id, id, "h", 1, false, t) })

    private fun names(v: RealmBrowserView) = v.sections.flatMap { it.rows }.map { it.realm.id }

    private val many = (1..300).map { realm("r$it", online = it % 50, tags = listOf(if (it % 2 == 0) "PvP" else "Cozy", if (it % 3 == 0) "Economy" else "Quests")) }

    @Test
    fun `the default view groups favorites, recently played, the featured realm and the rest`() {
        val m = model(
            listOf(realm("a", online = 5), realm("b", online = 50), realm("c", online = 1), realm("d", online = 9), realm("e")),
            featured = "d", favorites = setOf("c"), recents = mapOf("b" to 2000L, "a" to 1000L)
        )
        val v = m.view(RealmFilter())
        assertEquals(listOf("Favorites", "Recently played", "Featured", "All realms"), v.sections.map { it.title })
        assertEquals(listOf("c"), v.sections[0].rows.map { it.realm.id })
        assertEquals(listOf("b", "a"), v.sections[1].rows.map { it.realm.id }, "most recent first")
        assertEquals(listOf("d"), v.sections[2].rows.map { it.realm.id })
        assertEquals(listOf("e"), v.sections[3].rows.map { it.realm.id })
        assertEquals(5, v.total); assertEquals(5, v.matched); assertEquals(0, v.hidden)
    }

    @Test
    fun `a realm appears once, and a favorite that was also played is not repeated`() {
        val v = model(listOf(realm("a"), realm("b")), favorites = setOf("a"), recents = mapOf("a" to 5L, "b" to 4L)).view(RealmFilter())
        assertEquals(listOf("a", "b"), names(v))
    }

    @Test
    fun `in the recommended order full realms come after ones you can join`() {
        val v = model(listOf(realm("full", online = 100, max = 100), realm("busy", online = 90, max = 100), realm("quiet", online = 2, max = 100))).view(RealmFilter())
        assertEquals(listOf("busy", "quiet", "full"), names(v))
    }

    @Test
    fun `empty sections are left out`() {
        val v = model(listOf(realm("a"), realm("b"))).view(RealmFilter())
        assertEquals(listOf("All realms"), v.sections.map { it.title })
    }

    @Test
    fun `search matches every word anywhere in name, game, description, tags or host`() {
        val m = model(listOf(
            realm("farm", name = "Hearthvale", desc = "A peaceful farming village", tags = listOf("Cozy")),
            realm("war", name = "Havenwood", desc = "Faction warfare", tags = listOf("PvP"))
        ))
        assertEquals(listOf("farm"), names(m.view(RealmFilter(text = "farming cozy"))))
        assertEquals(listOf("war"), names(m.view(RealmFilter(text = "PVP war"))))
        assertEquals(listOf("farm"), names(m.view(RealmFilter(text = "farm.example"))), "the host counts")
        assertEquals(emptyList<String>(), names(m.view(RealmFilter(text = "farming pvp"))), "all words must match")
    }

    @Test
    fun `tag filters narrow the list and the chips count what is left`() {
        val m = model(many)
        val all = m.view(RealmFilter())
        val pvp = m.view(RealmFilter(tags = setOf("pvp")))
        assertEquals(150, pvp.matched)
        assertTrue(pvp.sections.flatMap { it.rows }.all { r -> r.realm.tags.any { it == "PvP" } })
        val both = m.view(RealmFilter(tags = setOf("PvP", "Economy")))
        assertEquals((1..300).count { it % 2 == 0 && it % 3 == 0 }, both.matched)
        assertTrue(all.facets.size <= 8)
        assertEquals(150, all.facets.first { it.tag == "PvP" }.count)
        assertTrue(pvp.facets.first { it.tag == "PvP" }.selected)
    }

    @Test
    fun `a chosen tag stays as a chip even when nothing matches it`() {
        val v = model(listOf(realm("a", tags = listOf("Cozy")))).view(RealmFilter(tags = setOf("Hardcore")))
        assertEquals(0, v.matched)
        assertTrue(v.facets.any { it.tag == "Hardcore" && it.selected && it.count == 0 })
    }

    @Test
    fun `favorites only and hide full are filters`() {
        val m = model(listOf(realm("a", online = 100, max = 100), realm("b", online = 3), realm("c", online = 4)), favorites = setOf("a", "b"))
        assertEquals(setOf("a", "b"), names(m.view(RealmFilter(favoritesOnly = true))).toSet())
        assertEquals(setOf("b", "c"), names(m.view(RealmFilter(hideFull = true))).toSet())
        assertEquals(listOf("b"), names(m.view(RealmFilter(favoritesOnly = true, hideFull = true))))
        assertTrue(m.view(RealmFilter()).sections.flatMap { it.rows }.first { it.realm.id == "a" }.full)
    }

    @Test
    fun `any filter or sort switches to one flat list`() {
        val m = model(listOf(realm("b", online = 5), realm("a", online = 9), realm("c", online = 1)), favorites = setOf("c"))
        assertEquals(listOf("Results"), m.view(RealmFilter(hideFull = true)).sections.map { it.title })
        assertEquals(listOf("a", "b", "c"), names(m.view(RealmFilter(sort = RealmSort.NAME))))
        assertEquals(listOf("a", "b", "c"), names(m.view(RealmFilter(sort = RealmSort.PLAYERS))))
        assertEquals(listOf("c", "a", "b"), names(m.view(RealmFilter(hideFull = true))), "recommended order still puts favorites first")
    }

    @Test
    fun `a long list is capped and says how many were cut off`() {
        val v = model(many).view(RealmFilter(), maxRows = 40)
        assertEquals(40, v.shown)
        assertEquals(300, v.matched)
        assertEquals(260, v.hidden)
        val narrowed = model(many).view(RealmFilter(text = "r29"), maxRows = 40)
        assertEquals(0, narrowed.hidden)
        assertEquals(11, narrowed.matched, "r29 plus r290..r299")
    }

    @Test
    fun `the cap applies across sections in order, favorites first`() {
        val realms = (1..10).map { realm("r$it") }
        val v = model(realms, favorites = setOf("r9", "r10")).view(RealmFilter(), maxRows = 3)
        assertEquals(listOf("r10", "r9"), v.sections[0].rows.map { it.realm.id }, "both favorites are shown first, in name order")
        assertEquals(3, v.shown)
        assertEquals(7, v.hidden)
    }

    @Test
    fun `fullness, the population bar and engine compatibility`() {
        val full = RealmRow(realm("a", online = 100, max = 100), favorite = false, lastPlayedMs = null, featured = false)
        val half = RealmRow(realm("b", online = 50, max = 100), false, null, false)
        val unlimited = RealmRow(realm("c", online = 500, max = 0), false, null, false)
        assertTrue(full.full); assertFalse(half.full); assertFalse(unlimited.full)
        assertEquals(1f, full.fillFraction); assertEquals(0.5f, half.fillFraction); assertEquals(0f, unlimited.fillFraction)
        assertTrue(half.compatible(1))
        assertFalse(RealmRow(realm("d", api = 2), false, null, false).compatible(1), "needs a newer client")
    }

    @Test
    fun `times read as plain words`() {
        val now = 10_000_000_000L
        assertEquals("just now", RealmBrowserModel.agoText(now, now - 20_000))
        assertEquals("5 min ago", RealmBrowserModel.agoText(now, now - 5 * 60_000))
        assertEquals("3 h ago", RealmBrowserModel.agoText(now, now - 3 * 3_600_000L))
        assertEquals("2 days ago", RealmBrowserModel.agoText(now, now - 2 * 86_400_000L))
        assertEquals("3 weeks ago", RealmBrowserModel.agoText(now, now - 21 * 86_400_000L))
        assertEquals("just now", RealmBrowserModel.agoText(now, now + 1000), "a clock in the future is not an error")
    }

    @Test
    fun `the demo directory is deterministic, big, varied and keeps the real realms first`() {
        val bundled = defaultBundledDirectory().realms.size
        val a = demoRealmDirectory(120)
        assertEquals(a, demoRealmDirectory(120))
        assertEquals(bundled + 120, a.realms.size)
        assertEquals(defaultBundledDirectory().realms.map { it.id }, a.realms.take(bundled).map { it.id })
        assertTrue(a.realms.drop(bundled).any { it.onlinePlayers >= it.maxPlayers }, "some are full")
        assertTrue(a.realms.drop(bundled).any { !it.tls } && a.realms.drop(bundled).any { it.tls })
        assertTrue(a.realms.drop(bundled).any { it.engineApi > 1 }, "one needs a newer client")
        assertEquals(a.realms.size, a.realms.map { it.id }.toSet().size, "ids are unique")
        assertEquals(bundled, demoRealmDirectory(0).realms.size)
        assertEquals(bundled + 2000, demoRealmDirectory(99999).realms.size, "capped")
    }

    @Test
    fun `fakeRealms in a page address asks for a demo directory`() {
        assertEquals(200, fakeRealmsFromQuery("?port=7022&fakeRealms=200"))
        assertEquals(null, fakeRealmsFromQuery("?fakeRealms=0"))
        assertEquals(null, fakeRealmsFromQuery("?fakeRealms=5000"))
        assertEquals(null, fakeRealmsFromQuery(""))
    }

    @Test
    fun `arrow keys move through the rows in the order shown, across sections, and stop at the ends`() {
        val m = model(listOf(realm("a"), realm("b"), realm("c"), realm("d")), favorites = setOf("c"), recents = mapOf("b" to 9L))
        val v = m.view(RealmFilter()) // c (favorite), b (recent), a, d
        assertEquals(listOf("c", "b", "a", "d"), names(v))
        assertEquals("c", RealmBrowserModel.step(v, null, 1)!!.id, "nothing selected: first going down")
        assertEquals("d", RealmBrowserModel.step(v, null, -1)!!.id, "nothing selected: last going up")
        assertEquals("b", RealmBrowserModel.step(v, "c", 1)!!.id)
        assertEquals("d", RealmBrowserModel.step(v, "d", 1)!!.id, "stays on the last row")
        assertEquals("c", RealmBrowserModel.step(v, "c", -1)!!.id, "stays on the first row")
        assertEquals("c", RealmBrowserModel.step(v, "gone", 1)!!.id, "a selection that was filtered out starts over")
        assertEquals(null, RealmBrowserModel.step(m.view(RealmFilter(text = "zzz")), "c", 1))
    }
}
