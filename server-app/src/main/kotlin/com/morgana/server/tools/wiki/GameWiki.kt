package com.morgana.server.tools.wiki

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.explore.WorldMapTerrain
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.validation.ContentCatalog
import com.morgana.server.tools.wiki.WikiHtml.Cell
import com.morgana.server.tools.wiki.WikiHtml.cell
import com.morgana.server.tools.wiki.WikiHtml.esc
import com.morgana.server.tools.wiki.WikiHtml.link
import com.morgana.server.tools.wiki.WikiHtml.num
import com.morgana.server.tools.wiki.WikiHtml.rawCell
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO

/**
 * A browsable wiki of one game, built from its data files: every item, monster, spell, quest, recipe, shop, resource, map
 * and class, cross-linked (an item page says who drops it, who sells it and what it crafts into; a monster page says where it
 * spawns and what it is worth), plus a Balance page that works out the numbers a designer tunes (experience per hit point,
 * loot value per kill, crafting margins, how long a level takes) and flags what looks off.
 *
 * Pure: [pages] turns a [ContentCatalog] and the [game] rules into a map of file name to bytes, so the `gameWiki` tool can
 * write it to a folder and the server can serve it live, from the same code. Nothing is typed by hand; change a data file
 * and the wiki follows.
 */
class GameWiki(private val game: CompiledGame, private val catalog: ContentCatalog) {
    private val gameName = game.definition.name
    private val items = catalog.items
    private val npcs = catalog.npcs
    private val lifeVital = game.lifeVitalId

    /** Server ticks per second, to show times in seconds. */
    private val tps = 20.0

    private val currencyName: String = items.values.filter { it.type == ItemType.CURRENCY }.minByOrNull { it.id }?.name ?: "gold"

    // ---- Cross-reference indexes -------------------------------------------------------------------------------------

    private class DropSource(val npc: NpcDefinition, val min: Int, val max: Int, val chanceBp: Int)
    private class ShopSource(val shop: ShopDefinition, val price: Long, val currency: String)

    private val droppedBy: Map<Int, List<DropSource>> = buildMap<Int, MutableList<DropSource>> {
        for (n in npcs.values) for (d in n.drops) getOrPut(d.itemId) { mutableListOf() } += DropSource(n, d.minAmount, d.maxAmount, d.chanceBasisPoints)
    }
    private val soldBy: Map<Int, List<ShopSource>> = buildMap<Int, MutableList<ShopSource>> {
        for (s in catalog.shops.values) for (e in s.entries) {
            val base = e.priceOverride.takeIf { it > 0 } ?: items[e.itemId]?.price ?: 0
            val price = if (e.priceOverride > 0) e.priceOverride.toLong() else base.toLong() * s.buyRatePercent / 100
            getOrPut(e.itemId) { mutableListOf() } += ShopSource(s, price, items[e.currencyItemId]?.name ?: currencyName)
        }
    }
    private val craftedBy: Map<Int, List<RecipeDefinition>> = catalog.recipes.values.groupBy { it.outputItemId }
    private val usedIn: Map<Int, List<RecipeDefinition>> = buildMap<Int, MutableList<RecipeDefinition>> {
        for (r in catalog.recipes.values) for (i in r.ingredients) getOrPut(i.itemId) { mutableListOf() } += r
    }
    private val gatheredFrom: Map<Int, List<ResourceDefinition>> = buildMap<Int, MutableList<ResourceDefinition>> {
        for (r in catalog.resources.values) {
            getOrPut(r.rewardItemId) { mutableListOf() } += r
            for (b in r.bonusDrops) getOrPut(b.itemId) { mutableListOf() } += r
        }
    }
    private val questRewards: Map<Int, List<QuestDefinition>> = buildMap<Int, MutableList<QuestDefinition>> {
        for (q in catalog.quests.values) for (i in q.reward.items) getOrPut(i.itemId) { mutableListOf() } += q
    }
    private val startingItemOf: Map<Int, List<ClassDefinition>> = buildMap<Int, MutableList<ClassDefinition>> {
        for (c in catalog.classes.values) for (i in c.startingItems) getOrPut(i.itemId) { mutableListOf() } += c
    }
    private val spawnsByNpc: Map<Int, Map<MapDefinition, Int>> = buildMap<Int, MutableMap<MapDefinition, Int>> {
        for (m in catalog.maps.values) for (s in m.npcSpawns) {
            val per = getOrPut(s.npcId) { linkedMapOf() }
            per[m] = (per[m] ?: 0) + 1
        }
    }
    /** Maps whose generated floors can contain each NPC (a generator lists its monsters and its boss), so they count as placed. */
    private val generatedOn: Map<Int, List<MapDefinition>> = buildMap<Int, MutableList<MapDefinition>> {
        for (m in catalog.maps.values) m.generator?.let { g ->
            (g.monsters.map { it.npcId } + listOfNotNull(g.bossNpcId.takeIf { it != 0 })).distinct().forEach { getOrPut(it) { mutableListOf() } += m }
        }
    }

    private fun isPlaced(npcId: Int): Boolean = (spawnsByNpc[npcId]?.isNotEmpty() == true) || generatedOn.containsKey(npcId)

    private val questsForNpc: Map<Int, List<QuestDefinition>> = buildMap<Int, MutableList<QuestDefinition>> {
        for (q in catalog.quests.values) for (o in q.objectives) {
            if (o.type == QuestObjectiveType.KILL_NPC || o.type == QuestObjectiveType.TALK_TO_NPC) getOrPut(o.targetId) { mutableListOf() } += q
        }
    }

    // ---- Names and links ---------------------------------------------------------------------------------------------

    private fun itemLink(id: Int): String = items[id]?.let { link("item-$id.html", it.name) } ?: "<span class=\"bad\">item #$id (missing)</span>"
    private fun npcLink(id: Int): String = npcs[id]?.let { link("npc-$id.html", it.name) } ?: "<span class=\"bad\">npc #$id (missing)</span>"
    private fun spellLink(id: Int): String = catalog.spells[id]?.let { link("spell-$id.html", it.name) } ?: "<span class=\"bad\">spell #$id (missing)</span>"
    private fun mapLink(id: Int): String = catalog.maps[id]?.let { link("map-$id.html", it.name) } ?: "<span class=\"bad\">map #$id (missing)</span>"
    private fun questLink(q: QuestDefinition) = link("quest-${q.id}.html", q.name)
    private fun classLink(id: Int): String = catalog.classes[id]?.let { link("class-$id.html", it.name) } ?: "class #$id"
    private fun attrName(id: String): String = game.definition.attributes.firstOrNull { it.id == id }?.name ?: id
    private fun vitalName(id: String): String = game.definition.vitals.firstOrNull { it.id == id }?.name ?: id

    private fun pct(bp: Int): String = trim(bp / 100.0) + "%"
    private fun trim(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) String.format(Locale.ROOT, "%,.0f", v) else String.format(Locale.ROOT, "%,.2f", v).trimEnd('0').trimEnd('.')
    private fun money(v: Number): String = String.format(Locale.ROOT, "%,d", v.toLong())
    private fun seconds(ticks: Int): String = trim(ticks / tps) + " s"
    private fun amountRange(min: Int, max: Int) = if (min == max) "$min" else "$min-$max"

    private fun isMonster(n: NpcDefinition) = (n.behavior == NpcBehavior.PASSIVE || n.behavior == NpcBehavior.ATTACK_ON_SIGHT) && n.xpReward > 0L

    // ---- Derived balance numbers -------------------------------------------------------------------------------------

    /** What fighting a monster is worth, worked out from its data. */
    class MonsterStats(
        val npc: NpcDefinition,
        val hp: Int,
        /** Damage per second of its basic attack. */
        val dps: Double,
        val xpPerHp: Double,
        /** Expected value of one kill's loot at item base prices (chance x average amount x price). */
        val lootValue: Double,
        /** Kills of this monster that fill a level for a character of its level; null at the level cap. */
        val killsPerLevel: Double?,
        val spawns: Int
    )

    fun monsterStats(n: NpcDefinition): MonsterStats {
        val hp = n.vitals[lifeVital] ?: 0
        val dps = n.attackDamage / (n.attackSpeedTicks.coerceAtLeast(1) / tps)
        val loot = n.drops.sumOf { d ->
            val price = items[d.itemId]?.price ?: 0
            d.chanceBasisPoints / 10000.0 * (d.minAmount + d.maxAmount) / 2.0 * price
        }
        val need = game.xpToNext(n.level.coerceAtLeast(1))
        return MonsterStats(
            n, hp, dps, if (hp > 0) n.xpReward.toDouble() / hp else 0.0, loot,
            need?.let { if (n.xpReward > 0) it.toDouble() / n.xpReward else null },
            spawnsByNpc[n.id]?.values?.sum() ?: 0
        )
    }

    private val monsters: List<NpcDefinition> = npcs.values.filter(::isMonster).sortedWith(compareBy({ it.level }, { it.id }))
    private val stats: Map<Int, MonsterStats> = monsters.associate { it.id to monsterStats(it) }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** What stands out about a monster against the others within 5 levels of it (needs at least 3 of them). */
    fun flagsFor(m: MonsterStats): List<String> {
        val peers = stats.values.filter { it.npc.id != m.npc.id && kotlin.math.abs(it.npc.level - m.npc.level) <= 5 }
        val out = mutableListOf<String>()
        if (m.hp <= 0) out += "no HP"
        if (m.npc.drops.isEmpty()) out += "no drops"
        if (!isPlaced(m.npc.id)) out += "never spawns"
        if (peers.size >= 3) {
            fun check(label: String, mine: Double, theirs: List<Double>) {
                val med = median(theirs)
                if (med <= 0.0) return
                if (mine < med * 0.5) out += "$label low" else if (mine > med * 2.0) out += "$label high"
            }
            check("XP per HP", m.xpPerHp, peers.map { it.xpPerHp })
            check("DPS", m.dps, peers.map { it.dps })
            check("HP", m.hp.toDouble(), peers.map { it.hp.toDouble() })
            if (m.npc.drops.isNotEmpty()) check("loot", m.lootValue, peers.filter { it.npc.drops.isNotEmpty() }.map { it.lootValue })
        }
        return out
    }

    // ---- Pages -------------------------------------------------------------------------------------------------------

    private val out = linkedMapOf<String, ByteArray>()
    private val searchEntries = mutableListOf<Triple<String, String, String>>()

    private fun put(name: String, title: String, current: String, body: String) {
        out[name] = WikiHtml.page(title, gameName, current, body).toByteArray(Charsets.UTF_8)
    }

    private fun search(kind: String, name: String, href: String) { searchEntries += Triple(kind, name, href) }

    /** Every file of the site: file name to contents. */
    fun pages(): Map<String, ByteArray> {
        if (out.isNotEmpty()) return out
        items.values.sortedBy { it.id }.forEach { itemPage(it); search("Item", it.name, "item-${it.id}.html") }
        npcs.values.sortedBy { it.id }.forEach { npcPage(it); search(if (isMonster(it)) "Monster" else "NPC", it.name, "npc-${it.id}.html") }
        catalog.spells.values.sortedBy { it.id }.forEach { spellPage(it); search("Spell", it.name, "spell-${it.id}.html") }
        catalog.quests.values.sortedBy { it.id }.forEach { questPage(it); search("Quest", it.name, "quest-${it.id}.html") }
        catalog.shops.values.sortedBy { it.id }.forEach { shopPage(it); search("Shop", it.name, "shop-${it.id}.html") }
        catalog.maps.values.sortedBy { it.id }.forEach { mapPage(it); search("Map", it.name, "map-${it.id}.html") }
        catalog.classes.values.sortedBy { it.id }.forEach { classPage(it); search("Class", it.name, "class-${it.id}.html") }
        catalog.recipes.values.forEach { search("Recipe", it.name, "recipes.html") }
        catalog.resources.values.forEach { search("Resource", it.name, "resources.html") }
        itemsIndex(); monstersIndex(); npcsIndex(); spellsIndex(); questsIndex(); recipesIndex(); shopsIndex(); resourcesIndex(); mapsIndex(); classesIndex()
        balancePage()
        homePage()
        out["wiki.css"] = WikiHtml.CSS.toByteArray(Charsets.UTF_8)
        out["wiki.js"] = WikiHtml.JS.toByteArray(Charsets.UTF_8)
        out["search-index.js"] = searchIndexJs().toByteArray(Charsets.UTF_8)
        return out
    }

    private fun searchIndexJs(): String = buildString {
        append("window.WIKI_INDEX=[")
        searchEntries.sortedBy { it.second.lowercase() }.forEachIndexed { i, (kind, name, href) ->
            if (i > 0) append(',')
            append('[').append(jsString(kind)).append(',').append(jsString(name)).append(',').append(jsString(href)).append(']')
        }
        append("];")
    }

    private fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '<' -> append("\\u003c"); '>' -> append("\\u003e")
            else -> append(c)
        }
        append('"')
    }

    private fun homePage() {
        val counts = listOf(
            "Items" to (items.size to "items.html"), "Monsters" to (monsters.size to "monsters.html"),
            "NPCs" to ((npcs.size - monsters.size) to "npcs.html"), "Spells" to (catalog.spells.size to "spells.html"),
            "Quests" to (catalog.quests.size to "quests.html"), "Recipes" to (catalog.recipes.size to "recipes.html"),
            "Shops" to (catalog.shops.size to "shops.html"), "Resources" to (catalog.resources.size to "resources.html"),
            "Maps" to (catalog.maps.size to "maps.html"), "Classes" to (catalog.classes.size to "classes.html")
        )
        val body = buildString {
            if (game.definition.description.isNotBlank()) append("<p>${esc(game.definition.description)}</p>")
            append("<div class=\"cards\">")
            for ((label, v) in counts) append("<a class=\"card\" href=\"${v.second}\"><b>${v.first}</b>${esc(label)}</a>")
            append("</div>")
            append("<h2>For designers</h2><p>The <a href=\"balance.html\">Balance</a> page works out experience per hit point, loot value per kill, ")
            append("crafting margins and the experience curve from the data files, and flags what stands out. Every table sorts (click a heading) and filters.</p>")
            append(WikiHtml.facts(listOf(
                "Game id" to esc(game.definition.id),
                "Max level" to "${game.definition.progression.maxLevel}",
                "Attributes" to esc(game.definition.attributes.joinToString(", ") { it.name }),
                "Vitals" to esc(game.definition.vitals.joinToString(", ") { it.name }),
                "Currency" to esc(currencyName)
            )))
        }
        put("index.html", "Home", "index.html", body)
    }

    // ---- Items -------------------------------------------------------------------------------------------------------

    private fun itemStats(i: ItemDefinition): String {
        val parts = mutableListOf<String>()
        if (i.attackDamage != 0) parts += "Attack ${i.attackDamage}"
        if (i.armorDefense != 0) parts += "Defense ${i.armorDefense}"
        if (i.type == ItemType.WEAPON) parts += "speed ${seconds(i.attackSpeedTicks)}"
        if (i.rangeTiles > 1) parts += "range ${i.rangeTiles}"
        i.attributeBonuses.forEach { (k, v) -> parts += "${if (v >= 0) "+" else ""}$v ${attrName(k)}" }
        i.vitalMaxBonuses.forEach { (k, v) -> parts += "${if (v >= 0) "+" else ""}$v max ${vitalName(k)}" }
        i.restore.forEach { (k, v) -> parts += "restores $v ${vitalName(k)}" }
        return parts.joinToString(", ")
    }

    private fun itemsIndex() {
        val rows = items.values.sortedBy { it.id }.map { i ->
            listOf(
                num(i.id), rawCell(link("item-${i.id}.html", i.name), null), cell(i.type.name.lowercase().replace('_', ' ')),
                num(i.levelReq), num(i.price), cell(itemStats(i)),
                num((droppedBy[i.id]?.size ?: 0) + (soldBy[i.id]?.size ?: 0) + (craftedBy[i.id]?.size ?: 0) + (gatheredFrom[i.id]?.size ?: 0))
            )
        }
        put("items.html", "Items", "items.html", WikiHtml.table(listOf("ID", "Name", "Type", "Level", "Price", "Stats", "Sources"), rows, "t-items"))
    }

    private fun itemPage(i: ItemDefinition) {
        val body = buildString {
            if (i.description.isNotBlank()) append("<p>${esc(i.description)}</p>")
            val facts = mutableListOf<Pair<String, String>>()
            facts += "Type" to esc(i.type.name.lowercase().replace('_', ' '))
            facts += "Level required" to "${i.levelReq}"
            facts += "Base price" to "${money(i.price)} $currencyName"
            if (i.classReqIds.isNotEmpty()) facts += "Classes" to i.classReqIds.joinToString(", ") { classLink(it) }
            if (i.attributeReq.isNotEmpty()) facts += "Requires" to esc(i.attributeReq.entries.joinToString(", ") { "${it.value} ${attrName(it.key)}" })
            itemStats(i).takeIf { it.isNotEmpty() }?.let { facts += "Stats" to esc(it) }
            if (i.weaponType.isNotBlank()) facts += "Weapon type" to esc(i.weaponType)
            if (i.teachSpellId != 0) facts += "Teaches" to spellLink(i.teachSpellId)
            if (i.nextTierItemId != 0) facts += "Upgrades to" to itemLink(i.nextTierItemId)
            if (i.boundOnPickup) facts += "Binding" to "bound when picked up"
            if (i.stackable) facts += "Stack size" to "${i.maxStack}"
            append(WikiHtml.facts(facts))

            droppedBy[i.id]?.let { list ->
                append("<h2>Dropped by</h2>")
                append(WikiHtml.table(listOf("Monster", "Level", "Chance", "Amount"), list.sortedBy { it.npc.level }.map {
                    listOf(rawCell(npcLink(it.npc.id)), num(it.npc.level), cell(pct(it.chanceBp), it.chanceBp), cell(amountRange(it.min, it.max)))
                }, "t-dropped", false))
            }
            soldBy[i.id]?.let { list ->
                append("<h2>Sold by</h2>")
                append(WikiHtml.table(listOf("Shop", "Price"), list.map {
                    listOf(rawCell(link("shop-${it.shop.id}.html", it.shop.name)), cell("${money(it.price)} ${it.currency}", it.price))
                }, "t-sold", false))
            }
            craftedBy[i.id]?.let { list ->
                append("<h2>Crafted by</h2><ul>")
                list.forEach { r -> append("<li>${esc(r.name)}: ${r.ingredients.joinToString(", ") { "${it.amount}x ${itemLink(it.itemId)}" }} (${esc(r.stationType.name.lowercase().replace('_', ' '))}, level ${r.levelReq})</li>") }
                append("</ul>")
            }
            usedIn[i.id]?.let { list ->
                append("<h2>Used in recipes</h2><ul>")
                list.forEach { r -> append("<li>${esc(r.name)} makes ${r.outputAmount}x ${itemLink(r.outputItemId)}</li>") }
                append("</ul>")
            }
            gatheredFrom[i.id]?.let { list ->
                append("<h2>Gathered from</h2><ul>")
                list.distinctBy { it.id }.forEach { r -> append("<li>${esc(r.name)} (${esc(r.category.name.lowercase().replace('_', ' '))})</li>") }
                append("</ul>")
            }
            questRewards[i.id]?.let { list ->
                append("<h2>Quest reward</h2><ul>")
                list.forEach { q -> append("<li>${questLink(q)} (level ${q.levelReq})</li>") }
                append("</ul>")
            }
            startingItemOf[i.id]?.let { list ->
                append("<h2>Starting item of</h2><p>${list.joinToString(", ") { classLink(it.id) }}</p>")
            }
            if (droppedBy[i.id] == null && soldBy[i.id] == null && craftedBy[i.id] == null && gatheredFrom[i.id] == null &&
                questRewards[i.id] == null && startingItemOf[i.id] == null && i.type != ItemType.CURRENCY
            ) {
                append("<p class=\"note warn\">Nothing in the data gives this item to a player.</p>")
            }
        }
        put("item-${i.id}.html", i.name, "items.html", body)
    }

    // ---- Monsters and NPCs -------------------------------------------------------------------------------------------

    private fun monstersIndex() {
        val rows = monsters.map { n ->
            val m = stats.getValue(n.id)
            val flags = flagsFor(m)
            listOf(
                num(n.id), rawCell(link("npc-${n.id}.html", n.name)), num(n.level), num(m.hp), num(n.attackDamage),
                num(round1(m.dps)), num(n.xpReward), num(round2(m.xpPerHp)), num(round1(m.lootValue)),
                if (m.killsPerLevel != null) num(round1(m.killsPerLevel)) else cell("-"), num(m.spawns),
                rawCell(if (flags.isEmpty()) "" else "<span class=\"warn\">${esc(flags.joinToString(", "))}</span>")
            )
        }
        val intro = "<p class=\"muted\">Monsters are the creatures fought for experience. <b>DPS</b> is basic attack damage per second; " +
            "<b>XP/HP</b> is experience per hit point; <b>Loot</b> is the expected base price of one kill's drops; <b>Kills/level</b> is how many " +
            "kills of it a character of its level needs to level up. <b>Flags</b> mark values far from other monsters within 5 levels.</p>"
        put("monsters.html", "Monsters", "monsters.html", intro + WikiHtml.table(
            listOf("ID", "Name", "Level", "HP", "Damage", "DPS", "XP", "XP/HP", "Loot", "Kills/level", "Spawns", "Flags"), rows, "t-monsters"
        ))
    }

    private fun npcsIndex() {
        val rows = npcs.values.filter { !isMonster(it) }.sortedBy { it.id }.map { n ->
            listOf(
                num(n.id), rawCell(link("npc-${n.id}.html", n.name)), cell(n.title), cell(n.behavior.name.lowercase().replace('_', ' ')),
                num(n.level), rawCell(if (n.shopId != 0) link("shop-${n.shopId}.html", catalog.shops[n.shopId]?.name ?: "shop ${n.shopId}") else ""),
                num(spawnsByNpc[n.id]?.values?.sum() ?: 0)
            )
        }
        put("npcs.html", "NPCs", "npcs.html", WikiHtml.table(listOf("ID", "Name", "Title", "Behavior", "Level", "Shop", "Spawns"), rows, "t-npcs"))
    }

    private fun npcPage(n: NpcDefinition) {
        val body = buildString {
            if (n.chatGreeting.isNotBlank()) append("<p><i>${esc(n.chatGreeting)}</i></p>")
            val facts = mutableListOf<Pair<String, String>>()
            if (n.title.isNotBlank()) facts += "Title" to esc(n.title)
            facts += "Behavior" to esc(n.behavior.name.lowercase().replace('_', ' '))
            facts += "Level" to "${n.level}"
            if (isMonster(n)) {
                val m = stats.getValue(n.id)
                n.vitals.forEach { (k, v) -> facts += vitalName(k) to "$v" }
                n.attributes.takeIf { it.isNotEmpty() }?.let { facts += "Attributes" to esc(it.entries.joinToString(", ") { e -> "${attrName(e.key)} ${e.value}" }) }
                facts += "Attack" to "${n.attackDamage} every ${seconds(n.attackSpeedTicks)} (${round1(m.dps)} DPS)"
                facts += "Aggro / leash" to "${n.aggroRangeTiles} / ${n.leashRangeTiles} tiles"
                facts += "Experience" to "${money(n.xpReward)} (${round2(m.xpPerHp)} per HP)"
                m.killsPerLevel?.let { facts += "Kills to level" to "${round1(it)} at level ${n.level}" }
                facts += "Loot value per kill" to "${round1(m.lootValue)} $currencyName at base prices"
                val flags = flagsFor(m)
                if (flags.isNotEmpty()) facts += "Stands out" to "<span class=\"warn\">${esc(flags.joinToString(", "))}</span>"
            }
            if (n.spellIds.isNotEmpty()) facts += "Spells" to n.spellIds.joinToString(", ") { spellLink(it) }
            if (n.shopId != 0) facts += "Shop" to (catalog.shops[n.shopId]?.let { link("shop-${it.id}.html", it.name) } ?: "shop #${n.shopId} (missing)")
            append(WikiHtml.facts(facts))

            if (n.drops.isNotEmpty()) {
                append("<h2>Drops</h2>")
                append(WikiHtml.table(listOf("Item", "Chance", "Amount", "Expected value"), n.drops.sortedByDescending { it.chanceBasisPoints }.map { d ->
                    val ev = d.chanceBasisPoints / 10000.0 * (d.minAmount + d.maxAmount) / 2.0 * (items[d.itemId]?.price ?: 0)
                    listOf(rawCell(itemLink(d.itemId)), cell(pct(d.chanceBasisPoints), d.chanceBasisPoints), cell(amountRange(d.minAmount, d.maxAmount)), num(round2(ev)))
                }, "t-drops", false))
            }
            spawnsByNpc[n.id]?.takeIf { it.isNotEmpty() }?.let { per ->
                append("<h2>Found on</h2><ul>")
                per.forEach { (map, count) -> append("<li>${mapLink(map.id)}: $count spawn${if (count == 1) "" else "s"}</li>") }
                append("</ul>")
            }
            generatedOn[n.id]?.let { list ->
                append("<h2>Generated floors</h2><p>Appears on the generated floors of ${list.joinToString(", ") { mapLink(it.id) }}.</p>")
            }
            questsForNpc[n.id]?.let { list ->
                append("<h2>Quests</h2><ul>")
                list.forEach { append("<li>${questLink(it)} (level ${it.levelReq})</li>") }
                append("</ul>")
            }
        }
        put("npc-${n.id}.html", n.name, if (isMonster(n)) "monsters.html" else "npcs.html", body)
    }

    // ---- Spells ------------------------------------------------------------------------------------------------------

    private fun spellsIndex() {
        val rows = catalog.spells.values.sortedBy { it.id }.map { s ->
            val cd = s.cooldownTicks / tps
            val perSecond = if (cd > 0) s.basePower * s.hitCount.coerceAtLeast(1) / cd else 0.0
            listOf(
                num(s.id), rawCell(link("spell-${s.id}.html", s.name)), cell(s.targetType.name.lowercase().replace('_', ' ')), num(s.levelReq),
                cell(s.costs.entries.joinToString(", ") { "${it.value} ${vitalName(it.key)}" }), num(s.basePower), num(round1(cd)), num(round1(perSecond)),
                cell(if (s.classReqIds.isEmpty()) "any" else s.classReqIds.joinToString(", ") { catalog.classes[it]?.name ?: "#$it" })
            )
        }
        put("spells.html", "Spells", "spells.html", "<p class=\"muted\"><b>Power/s</b> is base power times hits divided by the cooldown in seconds.</p>" +
            WikiHtml.table(listOf("ID", "Name", "Target", "Level", "Cost", "Power", "Cooldown s", "Power/s", "Classes"), rows, "t-spells"))
    }

    private fun spellPage(s: SpellDefinition) {
        val body = buildString {
            if (s.description.isNotBlank()) append("<p>${esc(s.description)}</p>")
            val f = mutableListOf<Pair<String, String>>()
            f += "Target" to esc(s.targetType.name.lowercase().replace('_', ' '))
            f += "Level required" to "${s.levelReq}"
            f += "Classes" to if (s.classReqIds.isEmpty()) "any" else s.classReqIds.joinToString(", ") { classLink(it) }
            if (s.costs.isNotEmpty()) f += "Cost" to esc(s.costs.entries.joinToString(", ") { "${it.value} ${vitalName(it.key)}" })
            f += (if (s.isHealing) "Healing" else "Power") to "${s.basePower}${if (s.hitCount > 1) " x ${s.hitCount} hits" else ""}"
            f += "Cast / cooldown" to "${seconds(s.castTimeTicks)} / ${seconds(s.cooldownTicks)}"
            f += "Range" to "${s.rangeTiles} tiles${if (s.aoeRadiusTiles > 0) ", radius ${s.aoeRadiusTiles}" else ""}"
            s.statusEffectType?.let { f += "Effect" to esc("${it.name.lowercase().replace('_', ' ')} ${s.statusMagnitude} for ${seconds(s.statusDurationTicks)}") }
            f += "Spell levels" to "up to ${s.maxSpellLevel} (${s.castsPerLevel} casts each)"
            append(WikiHtml.facts(f))
            val books = items.values.filter { it.teachSpellId == s.id }
            if (books.isNotEmpty()) append("<h2>Taught by</h2><p>${books.joinToString(", ") { itemLink(it.id) }}</p>")
            val users = npcs.values.filter { s.id in it.spellIds }
            if (users.isNotEmpty()) append("<h2>Used by</h2><p>${users.joinToString(", ") { npcLink(it.id) }}</p>")
        }
        put("spell-${s.id}.html", s.name, "spells.html", body)
    }

    // ---- Quests ------------------------------------------------------------------------------------------------------

    private fun objectiveText(o: com.morgana.domain.event.QuestObjective): String {
        val target = when (o.type) {
            QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC -> npcLink(o.targetId)
            QuestObjectiveType.COLLECT_ITEM -> itemLink(o.targetId)
            QuestObjectiveType.HARVEST_RESOURCE -> catalog.resources[o.targetId]?.name?.let(::esc) ?: "resource #${o.targetId}"
            QuestObjectiveType.CRAFT_RECIPE -> catalog.recipes[o.targetId]?.name?.let(::esc) ?: "recipe #${o.targetId}"
            QuestObjectiveType.VISIT_MAP -> mapLink(o.targetId)
        }
        val verb = when (o.type) {
            QuestObjectiveType.KILL_NPC -> "Defeat"; QuestObjectiveType.COLLECT_ITEM -> "Collect"; QuestObjectiveType.TALK_TO_NPC -> "Talk to"
            QuestObjectiveType.HARVEST_RESOURCE -> "Gather from"; QuestObjectiveType.CRAFT_RECIPE -> "Craft"; QuestObjectiveType.VISIT_MAP -> "Visit"
        }
        return "$verb ${if (o.requiredCount > 1) "${o.requiredCount}x " else ""}$target"
    }

    private fun questsIndex() {
        val rows = catalog.quests.values.sortedWith(compareBy({ it.levelReq }, { it.id })).map { q ->
            val need = game.xpToNext(q.levelReq.coerceAtLeast(1))
            listOf(
                num(q.id), rawCell(link("quest-${q.id}.html", q.name)), num(q.levelReq), num(q.objectives.size), num(q.reward.xp),
                if (need != null && need > 0) cell("${round1(q.reward.xp * 100.0 / need)}%", round1(q.reward.xp * 100.0 / need)) else cell("-"),
                num(q.reward.currencyAmount), cell(if (q.repeatable) "yes" else "")
            )
        }
        put("quests.html", "Quests", "quests.html", "<p class=\"muted\"><b>% of level</b> is the quest's experience against what a character at its level requirement needs to level up.</p>" +
            WikiHtml.table(listOf("ID", "Name", "Level", "Objectives", "XP", "% of level", currencyName.replaceFirstChar { it.uppercase() }, "Repeatable"), rows, "t-quests"))
    }

    private fun questPage(q: QuestDefinition) {
        val body = buildString {
            if (q.summary.isNotBlank()) append("<p>${esc(q.summary)}</p>")
            val f = mutableListOf<Pair<String, String>>()
            f += "Level required" to "${q.levelReq}"
            if (q.prerequisiteQuestIds.isNotEmpty()) f += "After" to q.prerequisiteQuestIds.joinToString(", ") { id -> catalog.quests[id]?.let { questLink(it) } ?: "quest #$id" }
            if (q.repeatable) f += "Repeatable" to "yes"
            f += "Reward" to buildString {
                append("${money(q.reward.xp)} XP, ${money(q.reward.currencyAmount)} $currencyName")
                q.reward.items.forEach { append(", ${it.amount}x ${itemLink(it.itemId)}") }
                if (q.reward.teachSpellId != 0) append(", teaches ${spellLink(q.reward.teachSpellId)}")
            }
            append(WikiHtml.facts(f))
            append("<h2>Objectives</h2><ul>")
            q.objectives.forEach { append("<li>${objectiveText(it)}</li>") }
            append("</ul>")
            listOf("Offer" to q.startText, "In progress" to q.inProgressText, "Complete" to q.completionText).filter { it.second.isNotBlank() }.forEach { (k, v) ->
                append("<h2>$k text</h2><p>${esc(v)}</p>")
            }
            val next = catalog.quests.values.filter { q.id in it.prerequisiteQuestIds }
            if (next.isNotEmpty()) append("<h2>Unlocks</h2><p>${next.joinToString(", ") { questLink(it) }}</p>")
        }
        put("quest-${q.id}.html", q.name, "quests.html", body)
    }

    // ---- Recipes, shops, resources -----------------------------------------------------------------------------------

    private fun recipesIndex() {
        val rows = catalog.recipes.values.sortedBy { it.id }.map { r ->
            val cost = r.ingredients.sumOf { (items[it.itemId]?.price ?: 0).toLong() * it.amount }
            val value = (items[r.outputItemId]?.price ?: 0).toLong() * r.outputAmount
            listOf(
                num(r.id), cell(r.name), cell(r.stationType.name.lowercase().replace('_', ' ')), num(r.levelReq),
                rawCell(r.ingredients.joinToString(", ") { "${it.amount}x ${itemLink(it.itemId)}" }),
                rawCell("${r.outputAmount}x ${itemLink(r.outputItemId)}"), num(cost), num(value), num(value - cost), num(r.xpReward)
            )
        }
        put("recipes.html", "Recipes", "recipes.html", "<p class=\"muted\"><b>Margin</b> is the output's base price minus the ingredients' base prices.</p>" +
            WikiHtml.table(listOf("ID", "Recipe", "Station", "Level", "Ingredients", "Makes", "Cost", "Value", "Margin", "XP"), rows, "t-recipes"))
    }

    private fun shopsIndex() {
        val rows = catalog.shops.values.sortedBy { it.id }.map { s ->
            val keepers = npcs.values.filter { it.shopId == s.id }
            listOf(
                num(s.id), rawCell(link("shop-${s.id}.html", s.name)), num(s.entries.size), cell("${s.buyRatePercent}%", s.buyRatePercent), cell("${s.sellRatePercent}%", s.sellRatePercent),
                rawCell(keepers.joinToString(", ") { npcLink(it.id) })
            )
        }
        put("shops.html", "Shops", "shops.html", "<p class=\"muted\"><b>Buy</b> is the price players pay as a percent of an item's base price; <b>Sell</b> is what the shop pays them.</p>" +
            WikiHtml.table(listOf("ID", "Shop", "Items", "Buy", "Sell", "Shopkeepers"), rows, "t-shops"))
    }

    private fun shopPage(s: ShopDefinition) {
        val body = buildString {
            append(WikiHtml.facts(listOf(
                "Players pay" to "${s.buyRatePercent}% of base price", "Shop pays" to "${s.sellRatePercent}% of base price",
                "Shopkeepers" to (npcs.values.filter { it.shopId == s.id }.joinToString(", ") { npcLink(it.id) }.ifEmpty { "none" })
            )))
            append("<h2>For sale</h2>")
            append(WikiHtml.table(listOf("Item", "Level", "Price", "Currency"), s.entries.map { e ->
                val item = items[e.itemId]
                val price = if (e.priceOverride > 0) e.priceOverride.toLong() else (item?.price ?: 0).toLong() * s.buyRatePercent / 100
                listOf(rawCell(itemLink(e.itemId)), num(item?.levelReq ?: 0), num(price), cell(items[e.currencyItemId]?.name ?: currencyName))
            }, "t-shopitems", false))
        }
        put("shop-${s.id}.html", s.name, "shops.html", body)
    }

    private fun resourcesIndex() {
        val rows = catalog.resources.values.sortedBy { it.id }.map { r ->
            listOf(
                num(r.id), cell(r.name), cell(r.category.name.lowercase().replace('_', ' ')), cell(r.requiredTool.name.lowercase()),
                rawCell(itemLink(r.rewardItemId)), cell("${r.minYield}-${r.maxYield}"), num(r.maxHealth), cell(seconds(r.respawnTicks), r.respawnTicks), num(r.xpReward)
            )
        }
        put("resources.html", "Resources", "resources.html", WikiHtml.table(listOf("ID", "Name", "Kind", "Tool", "Gives", "Yield", "Swings", "Respawn", "XP"), rows, "t-resources"))
    }

    // ---- Maps --------------------------------------------------------------------------------------------------------

    private val markerColours = listOf(
        Color(0xFF, 0x4D, 0x4D), Color(0xFF, 0xB0, 0x2E), Color(0xFF, 0xF0, 0x5A), Color(0xC8, 0x6B, 0xFF),
        Color(0xFF, 0x6B, 0xC8), Color(0x5A, 0xE6, 0xE6), Color(0xFF, 0xFF, 0xFF), Color(0xB8, 0xFF, 0x5A)
    )

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun monsterLevels(m: MapDefinition): List<Int> =
        m.npcSpawns.mapNotNull { npcs[it.npcId] }.filter(::isMonster).map { it.level }

    private fun mapsIndex() {
        val rows = catalog.maps.values.sortedBy { it.id }.map { m ->
            val lv = monsterLevels(m)
            listOf(
                num(m.id), rawCell(link("map-${m.id}.html", m.name)), cell("${m.width} x ${m.height}", m.width * m.height),
                cell(m.moral.name.lowercase().replace('_', ' ')),
                cell(if (lv.isEmpty()) "-" else if (lv.min() == lv.max()) "${lv.min()}" else "${lv.min()}-${lv.max()}", lv.minOrNull() ?: 0),
                num(lv.size), num(m.npcSpawns.size - lv.size)
            )
        }
        put("maps.html", "Maps", "maps.html", WikiHtml.table(listOf("ID", "Map", "Size", "Rules", "Monster levels", "Monsters", "Other NPCs"), rows, "t-maps"))
    }

    private fun mapImage(m: MapDefinition, colourOf: Map<Int, Color>): ByteArray {
        val thumb = WorldMapTerrain.thumbnail(m)
        val px = (512 / maxOf(thumb.cols, thumb.rows)).coerceIn(2, 8)
        val img = BufferedImage(thumb.cols * px, thumb.rows * px, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        for (row in 0 until thumb.rows) for (col in 0 until thumb.cols) {
            g.color = when (thumb.at(col, row)) {
                WorldMapTerrain.BLOCKED -> Color(0x59, 0x61, 0x73)
                WorldMapTerrain.WATER -> Color(0x26, 0x59, 0xBF)
                WorldMapTerrain.WARP -> Color(0x33, 0xD9, 0xE6)
                else -> Color(0x2E, 0x61, 0x38)
            }
            g.fillRect(col * px, row * px, px, px)
        }
        val tilePx = px.toDouble() / thumb.scale
        for (s in m.npcSpawns) {
            val colour = colourOf[s.npcId] ?: continue
            val cx = ((s.spawnX + 0.5) * tilePx).toInt()
            val cy = ((s.spawnY + 0.5) * tilePx).toInt()
            val r = (px / 2 + 2).coerceAtLeast(3)
            g.color = Color.BLACK; g.fillOval(cx - r - 1, cy - r - 1, 2 * r + 2, 2 * r + 2)
            g.color = colour; g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
        }
        g.dispose()
        val bytes = ByteArrayOutputStream()
        ImageIO.write(img, "png", bytes)
        return bytes.toByteArray()
    }

    private fun mapPage(m: MapDefinition) {
        val spawnGroups = m.npcSpawns.groupBy { it.npcId }
        val monsterIds = spawnGroups.keys.filter { npcs[it]?.let(::isMonster) == true }.sortedBy { npcs[it]?.level ?: 0 }
        val colourOf = monsterIds.withIndex().associate { (i, id) -> id to markerColours[i % markerColours.size] }
        out["map-${m.id}.png"] = mapImage(m, colourOf)
        val warps = m.cells.withIndex().mapNotNull { (idx, c) ->
            (c.attribute as? TileAttribute.Warp)?.let { Triple(it, idx % m.width, idx / m.width) }
        }.groupBy { it.first.targetMapId }
        val body = buildString {
            val f = mutableListOf<Pair<String, String>>()
            f += "Size" to "${m.width} x ${m.height} tiles"
            f += "Rules" to esc(m.moral.name.lowercase().replace('_', ' '))
            if (m.isIndoors) f += "Setting" to "indoors"
            if (m.instanced) f += "Instanced" to "one copy per ${m.instanceOwner.name.lowercase()}"
            m.generator?.let { f += "Generated floors" to (if (it.bossNpcId != 0) "boss: ${npcLink(it.bossNpcId)}" else "yes") }
            val borders = listOf("North" to m.neighbors.northMapId, "South" to m.neighbors.southMapId, "East" to m.neighbors.eastMapId, "West" to m.neighbors.westMapId).filter { it.second != 0 }
            if (borders.isNotEmpty()) f += "Borders" to borders.joinToString(", ") { "${it.first}: ${mapLink(it.second)}" }
            append(WikiHtml.facts(f))
            append("<h2>Map</h2><p class=\"muted\">Terrain from the map's tiles: water blue, walls grey, warps cyan. Dots mark monster spawns, coloured as in the table below.</p>")
            append("<img class=\"map\" src=\"map-${m.id}.png\" alt=\"Map of ${esc(m.name)}\">")
            if (monsterIds.isNotEmpty()) {
                append("<h2>Monsters</h2>")
                append(WikiHtml.table(listOf("", "Monster", "Level", "HP", "XP", "Spawns", "Positions"), monsterIds.map { id ->
                    val n = npcs.getValue(id)
                    val slots = spawnGroups.getValue(id)
                    listOf(
                        rawCell("<span style=\"color:${hex(colourOf.getValue(id))}\">&#9679;</span>"), rawCell(npcLink(id)), num(n.level),
                        num(stats[id]?.hp ?: 0), num(n.xpReward), num(slots.size),
                        cell(slots.take(8).joinToString(" ") { "(${it.spawnX},${it.spawnY})" } + if (slots.size > 8) " ..." else "")
                    )
                }, "t-mapmonsters", false))
            }
            val others = spawnGroups.keys.filter { it !in monsterIds }.sorted()
            if (others.isNotEmpty()) {
                append("<h2>Other NPCs</h2><ul>")
                others.forEach { id -> append("<li>${npcLink(id)}${npcs[id]?.title?.takeIf { it.isNotBlank() }?.let { " <span class=\"muted\">(${esc(it)})</span>" } ?: ""}</li>") }
                append("</ul>")
            }
            if (warps.isNotEmpty()) {
                append("<h2>Warps</h2><ul>")
                warps.forEach { (target, list) -> append("<li>To ${mapLink(target)}: ${list.size} tile${if (list.size == 1) "" else "s"}, e.g. (${list.first().second}, ${list.first().third})</li>") }
                append("</ul>")
            }
        }
        put("map-${m.id}.html", m.name, "maps.html", body)
    }

    // ---- Classes -----------------------------------------------------------------------------------------------------

    private fun classesIndex() {
        val rows = catalog.classes.values.sortedBy { it.id }.map { c ->
            listOf(
                num(c.id), rawCell(link("class-${c.id}.html", c.name)),
                cell(c.baseAttributes.entries.joinToString(", ") { "${attrName(it.key)} ${it.value}" }),
                cell(c.autoStatGrowthPerLevel.entries.joinToString(", ") { "+${it.value} ${attrName(it.key)}" }), num(c.statPointsPerLevel)
            )
        }
        put("classes.html", "Classes", "classes.html", WikiHtml.table(listOf("ID", "Class", "Base attributes", "Growth per level", "Points per level"), rows, "t-classes", false))
    }

    private fun classPage(c: ClassDefinition) {
        val body = buildString {
            if (c.description.isNotBlank()) append("<p>${esc(c.description)}</p>")
            append(WikiHtml.facts(listOf(
                "Base attributes" to esc(c.baseAttributes.entries.joinToString(", ") { "${attrName(it.key)} ${it.value}" }),
                "Automatic growth" to esc(c.autoStatGrowthPerLevel.entries.joinToString(", ") { "+${it.value} ${attrName(it.key)} per level" }.ifEmpty { "none" }),
                "Free points" to "${c.statPointsPerLevel} per level",
                "Starts on" to "${mapLink(c.startMapId)} at (${c.startX}, ${c.startY})"
            )))
            if (c.startingItems.isNotEmpty()) append("<h2>Starting items</h2><ul>" + c.startingItems.joinToString("") { "<li>${it.amount}x ${itemLink(it.itemId)}</li>" } + "</ul>")
            val spells = catalog.spells.values.filter { c.id in it.classReqIds || it.id in c.startingSpellIds }.sortedBy { it.levelReq }
            if (spells.isNotEmpty()) {
                append("<h2>${esc(c.abilityTerminology.pluralLabel)}</h2>")
                append(WikiHtml.table(listOf("Name", "Level", "Power", "Cooldown s"), spells.map {
                    listOf(rawCell(spellLink(it.id)), num(it.levelReq), num(it.basePower), num(round1(it.cooldownTicks / tps)))
                }, "t-classspells", false))
            }
        }
        put("class-${c.id}.html", c.name, "classes.html", body)
    }

    // ---- Balance -----------------------------------------------------------------------------------------------------

    private fun balancePage() {
        val body = buildString {
            append("<p class=\"note\">Everything here is computed from the data files. The numbers are for comparing content with other content ")
            append("(is this monster worth more experience than its neighbours? does this recipe lose money?), not predictions of play.</p>")

            append("<h2>Experience curve</h2>")
            val maxLevel = game.definition.progression.maxLevel
            var cumulative = 0L
            val levels = (1 until maxLevel).map { lv ->
                val need = game.xpToNext(lv) ?: 0L
                cumulative += need
                val near = stats.values.filter { kotlin.math.abs(it.npc.level - lv) <= 2 }
                val avgXp = median(near.map { it.npc.xpReward.toDouble() })
                listOf(
                    num(lv), num(need), num(cumulative),
                    if (near.isEmpty()) cell("-") else num(round1(avgXp)),
                    if (near.isEmpty() || avgXp <= 0) cell("-") else num(round1(need / avgXp)),
                    num(near.size)
                )
            }
            append("<p class=\"muted\"><b>Typical kills</b> is the experience to the next level divided by the median experience of monsters within 2 levels. ")
            append("A jump in this column marks a level that will feel much slower or faster than its neighbours.</p>")
            append(WikiHtml.table(listOf("Level", "XP to next", "Total XP", "Median monster XP", "Typical kills", "Monsters nearby"), levels, "t-curve"))

            append("<h2>Monsters that stand out</h2>")
            val flagged = monsters.map { stats.getValue(it.id) to flagsFor(stats.getValue(it.id)) }.filter { it.second.isNotEmpty() }
            if (flagged.isEmpty()) append("<p class=\"good\">Nothing stands out.</p>") else {
                append(WikiHtml.table(listOf("Monster", "Level", "Flags"), flagged.map { (m, f) ->
                    listOf(rawCell(npcLink(m.npc.id)), num(m.npc.level), cell(f.joinToString(", ")))
                }, "t-flagged"))
            }

            append("<h2>Loot per kill by level</h2>")
            append(WikiHtml.table(listOf("Monster", "Level", "HP", "XP", "Loot value", "Value per HP"), monsters.map { n ->
                val m = stats.getValue(n.id)
                listOf(rawCell(npcLink(n.id)), num(n.level), num(m.hp), num(n.xpReward), num(round1(m.lootValue)), num(if (m.hp > 0) round2(m.lootValue / m.hp) else 0.0))
            }, "t-loot"))

            append("<h2>Crafting that loses money</h2>")
            val losing = catalog.recipes.values.mapNotNull { r ->
                val cost = r.ingredients.sumOf { (items[it.itemId]?.price ?: 0).toLong() * it.amount }
                val value = (items[r.outputItemId]?.price ?: 0).toLong() * r.outputAmount
                if (value < cost) Triple(r, cost, value) else null
            }
            if (losing.isEmpty()) append("<p class=\"good\">Every recipe's output is worth at least its ingredients.</p>") else {
                append("<p class=\"muted\">Not necessarily wrong (crafting also gives experience), but worth a look.</p>")
                append(WikiHtml.table(listOf("Recipe", "Cost", "Value", "Margin"), losing.map { (r, c, v) -> listOf(cell(r.name), num(c), num(v), num(v - c)) }, "t-losing", false))
            }

            append("<h2>Shop loops</h2>")
            val loops = catalog.shops.values.flatMap { s ->
                s.entries.mapNotNull { e ->
                    val item = items[e.itemId] ?: return@mapNotNull null
                    val buy = if (e.priceOverride > 0) e.priceOverride.toLong() else item.price.toLong() * s.buyRatePercent / 100
                    val sell = item.price.toLong() * s.sellRatePercent / 100
                    if (e.currencyItemId == s.currencyItemId && sell > buy) Triple(s, item, buy to sell) else null
                }
            }
            if (loops.isEmpty()) append("<p class=\"good\">No shop pays more for an item than it sells it for.</p>") else {
                append("<p class=\"bad\">Buying and reselling these at the same shop makes money without limit.</p>")
                append(WikiHtml.table(listOf("Shop", "Item", "Buy", "Sell"), loops.map { (s, i, p) ->
                    listOf(rawCell(link("shop-${s.id}.html", s.name)), rawCell(itemLink(i.id)), num(p.first), num(p.second))
                }, "t-loops", false))
            }

            append("<h2>Data problems</h2>")
            val unobtainable = items.values.filter { i ->
                i.type != ItemType.CURRENCY && droppedBy[i.id] == null && soldBy[i.id] == null && craftedBy[i.id] == null &&
                    gatheredFrom[i.id] == null && questRewards[i.id] == null && startingItemOf[i.id] == null
            }.sortedBy { it.id }
            val unspawned = monsters.filter { !isPlaced(it.id) }
            val unusedMaterials = items.values.filter { it.type == ItemType.MATERIAL && usedIn[it.id] == null && it.id !in questObjectiveItems }.sortedBy { it.id }
            append("<ul>")
            append("<li>${unobtainable.size} item(s) nothing in the data gives a player${unobtainable.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(", ") { i -> itemLink(i.id) } } ?: ""}</li>")
            append("<li>${unspawned.size} monster(s) that never spawn${unspawned.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(", ") { n -> npcLink(n.id) } } ?: ""}</li>")
            append("<li>${unusedMaterials.size} material(s) no recipe or quest uses${unusedMaterials.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(", ") { i -> itemLink(i.id) } } ?: ""}</li>")
            append("</ul>")
        }
        put("balance.html", "Balance", "balance.html", body)
    }

    private val questObjectiveItems: Set<Int> =
        catalog.quests.values.flatMap { q -> q.objectives.filter { it.type == QuestObjectiveType.COLLECT_ITEM }.map { it.targetId } }.toSet()

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
}
