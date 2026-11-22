package com.morgana.server

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.validation.ContentCatalog
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.WikiConfig
import com.morgana.server.tools.wiki.GameWiki
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.exists

class GameWikiTest {
    private val game = BlankGame.COMPILED
    private val life = game.lifeVitalId

    private fun monster(id: Int, level: Int, hp: Int, dmg: Int, xp: Long, drops: List<NpcDropEntry> = emptyList(), name: String = "Beast $id") =
        NpcDefinition(
            id = id, name = name, behavior = NpcBehavior.ATTACK_ON_SIGHT, level = level, vitals = mapOf(life to hp),
            attackDamage = dmg, attackSpeedTicks = 20, xpReward = xp, drops = drops
        )

    private fun blankMap(id: Int, spawns: List<MapNpcSpawnSlot>) = MapDefinition(id = id, name = "Field $id", width = 10, height = 10, npcSpawns = spawns)

    private fun catalog(vararg extraNpcs: NpcDefinition, spawnedIds: Set<Int> = emptySet()): ContentCatalog {
        val items = listOf(
            ItemDefinition(id = 1, name = "Coin", type = ItemType.CURRENCY, price = 1),
            ItemDefinition(id = 2, name = "Hide", type = ItemType.MATERIAL, price = 10),
            ItemDefinition(id = 3, name = "Cloak", type = ItemType.ARMOR, price = 5, armorDefense = 4),
            ItemDefinition(id = 4, name = "Orphan", type = ItemType.MATERIAL, price = 7)
        ).associateBy { it.id }
        val npcs = (listOf(monster(10, 5, 100, 10, 50, listOf(NpcDropEntry(2, 1, 3, 5000), NpcDropEntry(1, 10, 10, 10000)))) + extraNpcs).associateBy { it.id }
        return ContentCatalog(
            items = items, npcs = npcs,
            maps = mapOf(1 to blankMap(1, (npcs.keys.filter { it in spawnedIds || it == 10 }).mapIndexed { i, id -> MapNpcSpawnSlot(i, id, 2 + i, 3) })),
            shops = mapOf(1 to ShopDefinition(id = 1, name = "Tanner", buyRatePercent = 100, sellRatePercent = 200, entries = listOf(ShopEntry(2)))),
            recipes = mapOf(1 to RecipeDefinition(id = 1, name = "Sew Cloak", ingredients = listOf(RecipeIngredient(2, 2)), outputItemId = 3))
        )
    }

    private fun text(pages: Map<String, ByteArray>, name: String) = String(pages.getValue(name), Charsets.UTF_8)

    @Test
    fun `a monster's numbers are worked out from its data`() {
        val wiki = GameWiki(game, catalog())
        val m = wiki.monsterStats(catalog().npcs.getValue(10))
        assertEquals(100, m.hp)
        assertEquals(10.0 / (20 / 20.0), m.dps, 1e-9)
        assertEquals(0.5, m.xpPerHp, 1e-9)
        // Loot: 50% of 1-3 Hide (average 2, price 10) = 10, plus a sure 10 Coin at price 1 = 10.
        assertEquals(20.0, m.lootValue, 1e-9)
        assertEquals(game.xpToNext(5)!!.toDouble() / 50, m.killsPerLevel!!, 1e-9)
        assertEquals(1, m.spawns)
    }

    @Test
    fun `an item page says who drops it, who sells it and what uses it`() {
        val pages = GameWiki(game, catalog()).pages()
        val hide = text(pages, "item-2.html")
        assertTrue(hide.contains("Dropped by") && hide.contains("npc-10.html"), "the monster that drops it")
        assertTrue(hide.contains("Sold by") && hide.contains("shop-1.html"), "the shop that sells it")
        assertTrue(hide.contains("Used in recipes") && hide.contains("item-3.html"), "what it crafts into")
        assertTrue(text(pages, "item-3.html").contains("Crafted by"))
        assertTrue(text(pages, "item-4.html").contains("Nothing in the data gives this item to a player"), "an item no source reaches is called out")
    }

    @Test
    fun `the balance page flags what is off and what can be exploited`() {
        // Four monsters at levels 4-6 with the same stats, and a fifth worth ten times the experience.
        val peers = (11..13).map { monster(it, 5, 100, 10, 50) }
        val rich = monster(14, 5, 100, 10, 900, name = "Jackpot")
        val c = catalog(*(peers + rich).toTypedArray(), spawnedIds = (11..14).toSet())
        val wiki = GameWiki(game, c)
        assertTrue("XP per HP high" in wiki.flagsFor(wiki.monsterStats(rich)))
        assertTrue(wiki.flagsFor(wiki.monsterStats(peers.first())).none { it.startsWith("XP per HP") })
        val balance = text(wiki.pages(), "balance.html")
        assertTrue(balance.contains("Jackpot"))
        // The shop pays 200% of base price for what it sells at 100%: buying and reselling makes money.
        assertTrue(balance.contains("Buying and reselling"))
        // A monster that is defined but placed on no map is a data problem.
        val orphan = GameWiki(game, catalog(monster(20, 5, 100, 10, 50))).pages()
        assertTrue(text(orphan, "balance.html").contains("1 monster(s) that never spawn"))

        // A monster that only appears on a dungeon's generated floors is placed, not an orphan.
        val base = catalog(monster(21, 5, 100, 10, 50))
        val dungeon = blankMap(2, emptyList()).copy(generator = com.morgana.domain.map.FloorGenerator(monsters = listOf(com.morgana.domain.map.WeightedSpawn(21))))
        val generated = GameWiki(game, base.copy(maps = base.maps + (2 to dungeon))).pages()
        assertTrue(text(generated, "balance.html").contains("0 monster(s) that never spawn"))
        assertTrue(text(generated, "npc-21.html").contains("Generated floors"))
    }

    @Test
    fun `names from the data cannot inject markup`() {
        val evil = monster(30, 5, 100, 10, 50, name = "<script>alert(1)</script>")
        val pages = GameWiki(game, catalog(evil, spawnedIds = setOf(30))).pages()
        assertFalse(text(pages, "monsters.html").contains("<script>alert(1)"))
        assertTrue(text(pages, "monsters.html").contains("&lt;script&gt;alert(1)"))
        assertFalse(text(pages, "search-index.js").contains("<script>"), "the search index escapes angle brackets too")
    }

    private val referenceGame: Path? = listOf(Path.of("games/roguelike"), Path.of("../games/roguelike")).firstOrNull { it.exists() }

    @Test
    fun `the wiki of a real game links only to pages that exist`() {
        val dir = referenceGame ?: return
        val pages = GameWiki(GameDefinitionLoader.load(dir).game, ContentLoader.load(dir.resolve("data")).catalog).pages()
        assertTrue(pages.size > 50, "pages: ${pages.size}")
        for (name in listOf("index.html", "items.html", "monsters.html", "balance.html", "wiki.css", "wiki.js", "search-index.js")) assertNotNull(pages[name], name)
        val href = Regex("(?:href|src)=\"([^\"#]+)\"")
        val broken = mutableListOf<String>()
        for ((name, bytes) in pages) {
            if (!name.endsWith(".html")) continue
            for (m in href.findAll(String(bytes, Charsets.UTF_8))) {
                val target = m.groupValues[1]
                if (target.startsWith("http") || target in pages) continue
                broken += "$name -> $target"
            }
        }
        assertEquals(emptyList<String>(), broken.take(10), "every link and image resolves")
        assertTrue(pages.keys.any { it.endsWith(".png") }, "maps have pictures")
        // A map picture really is a PNG.
        val png = pages.entries.first { it.key.endsWith(".png") }.value
        assertEquals(0x89, png[0].toInt() and 0xff)
        assertEquals('P'.code, png[1].toInt())
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `the server serves the wiki to players on its web port and an operator can turn it off`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, webSocketPort = 0).use { server ->
            val port = server.webSocketPort!!
            assertEquals(301, get(port, "/wiki").statusCode())
            val home = get(port, "/wiki/")
            assertEquals(200, home.statusCode())
            assertTrue(home.headers().firstValue("content-type").get().startsWith("text/html"))
            assertTrue(home.body().contains("Wiki"))
            assertEquals(200, get(port, "/wiki/items.html").statusCode())
            assertEquals(200, get(port, "/wiki/wiki.css").statusCode())
            assertEquals(404, get(port, "/wiki/nothing-here.html").statusCode())
        }
        val off = dir.resolve("off")
        ServerConfig.save(off, ServerConfig(wiki = WikiConfig(enabled = false)))
        MorganaServerRuntime.start(off, port = 0, watchContent = false, webSocketPort = 0).use { server ->
            assertEquals(404, get(server.webSocketPort!!, "/wiki/").statusCode())
        }
    }
}
