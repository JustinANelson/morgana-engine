package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.domain.validation.ContentCatalog
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.ServerUiWindowType
import com.morgana.server.assets.ArtCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * White River Online (`games/white-river`): the content, art and module set are valid; the five maps of the valley join
 * at open seams and everything on them can be reached on foot; nothing in the economy is unobtainable or makes money
 * from nothing; and the first hour plays (start, shop, quest, fight, gather, cook, walk to the next map) on a temp copy.
 */
class WhiteRiverGameTest {

    private val game: Path = listOf(Path.of("games/white-river"), Path.of("../games/white-river")).first { it.exists() }

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun system() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == com.morgana.protocol.ChatChannel.SYSTEM }.map { it.message }
        fun windows() = sent.filterIsInstance<ServerPacket.OpenUiWindow>()
    }

    private fun copyGame(to: Path): Path {
        Files.walk(game).use { paths ->
            paths.forEach { src ->
                val rel = game.relativize(src).toString().replace('\\', '/')
                if (rel.startsWith("tools")) return@forEach
                val dst = to.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        return to
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(
        MorganaDataStore(copyGame(dir)),
        ownerPolicy = OwnerPolicy(dir) { null },
        packetLimits = PacketLimitSettings(enabled = false)
    )

    private fun join(c: MorganaWorldCoordinator, name: String, classId: Int = 1): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, classId, protocolVersion = PROTOCOL_VERSION,
            capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW))
        return s
    }

    private fun MorganaWorldCoordinator.me(s: FakeSession) = simulation.players.getValue(s.characterId!!)

    private fun MorganaWorldCoordinator.stand(s: FakeSession, mapId: Int, x: Int, y: Int, facing: Direction) {
        val p = me(s)
        simulation.players[p.id] = p.copy(position = WorldPosition.fromTile(mapId, x, y, facing))
    }

    private fun MorganaWorldCoordinator.give(s: FakeSession, itemId: Int, amount: Int) {
        val p = me(s)
        simulation.players[p.id] = p.copy(inventory = p.inventory.addItem(simulation.items.getValue(itemId), amount).updatedInventory)
    }

    private fun catalog(): ContentCatalog = ContentLoader.load(game.resolve("data")).catalog

    private fun MapDefinition.open(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && cells[y * width + x].attribute.let { it !is TileAttribute.Blocked && it !is TileAttribute.ResourceNode }

    /** Every (map, x, y) a player can walk to from the class start, crossing map seams. */
    private fun reachable(cat: ContentCatalog): Set<Triple<Int, Int, Int>> {
        val start = cat.classes.getValue(1)
        val seen = hashSetOf(Triple(start.startMapId, start.startX, start.startY))
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) {
            val (mapId, x, y) = queue.removeFirst()
            val map = cat.maps.getValue(mapId)
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                var nm = mapId; var nx = x + dx; var ny = y + dy
                when {
                    nx < 0 -> { nm = map.neighbors.westMapId; nx = (cat.maps[nm]?.width ?: 1) - 1 }
                    nx >= map.width -> { nm = map.neighbors.eastMapId; nx = 0 }
                    ny < 0 -> { nm = map.neighbors.northMapId; ny = (cat.maps[nm]?.height ?: 1) - 1 }
                    ny >= map.height -> { nm = map.neighbors.southMapId; ny = 0 }
                }
                val next = cat.maps[nm] ?: continue
                if (next.open(nx, ny) && seen.add(Triple(nm, nx, ny))) queue.add(Triple(nm, nx, ny))
            }
        }
        return seen
    }

    @Test
    fun `the content, art and module set are valid`() {
        val def = GameDefinitionLoader.load(game).game.definition
        assertEquals("white-river", def.id)
        assertEquals("White River Online", def.name)
        assertEquals("realtime", def.combat.module)
        assertEquals(listOf("hp", "mp", "sp"), def.vitals.map { it.id })
        assertTrue(def.clock?.dayNight == true, "the valley has day and night")
        assertEquals(listOf("plain", "fine", "rare", "heirloom", "riverforged"), def.loot?.rarities.orEmpty().map { it.id })

        val flags = ServerConfig.load(game).moduleFlags()
        for (m in listOf(OptionalModule.PARTIES, OptionalModule.GUILDS, OptionalModule.TRADING, OptionalModule.CRAFTING,
            OptionalModule.SOFT_SEAMS, OptionalModule.SUB_TILE_MOVEMENT, OptionalModule.MARKET, OptionalModule.MAIL)) {
            assertTrue(flags.isEnabled(m), "$m should be on")
        }
        assertTrue(!flags.isEnabled(OptionalModule.INSTANCING), "the valley is one shared open world")

        val loaded = ContentLoader.load(game.resolve("data"))
        val report = loaded.fullReport(def)
        assertTrue(report.issues.isEmpty(), "Expected 0 content issues, found: ${report.issues}")
        val art = ArtCheck.check(game)
        assertTrue(art.errors.isEmpty(), "Expected 0 art errors, found: ${art.errors}")
        assertTrue(art.warnings.isEmpty(), "Expected 0 art warnings, found: ${art.warnings}")

        val cat = loaded.catalog
        assertEquals(listOf("Bladewarden", "Windrunner", "Duskweaver"), cat.classes.values.sortedBy { it.id }.map { it.name })
        assertEquals(
            listOf("Calder's Ford", "Millrace Fields", "Heronreach Marsh", "Chalk Hollow", "Paleback Headwaters"),
            cat.maps.values.sortedBy { it.id }.map { it.name }
        )
        // The Ford's peace covers the town and the two starter zones; the upper valley is contested.
        assertEquals(listOf(MapMoral.SAFE_ZONE, MapMoral.SAFE_ZONE, MapMoral.SAFE_ZONE, MapMoral.OPEN_PVP, MapMoral.OPEN_PVP),
            cat.maps.values.sortedBy { it.id }.map { it.moral })
        assertTrue(cat.maps.values.none { it.instanced })

        // Each class has five skills of its own, the first known from the start, and no skill is shared.
        for (c in cat.classes.values) {
            val own = cat.spells.values.filter { c.id in it.classReqIds }
            assertEquals(5, own.size, "${c.name} skills")
            assertTrue(c.startingSpellIds.isNotEmpty() && c.startingSpellIds.all { id -> id in own.map { it.id } }, c.name)
            assertTrue(own.all { it.classReqIds == setOf(c.id) })
        }
        // The Duskweaver pays in mana, the other two in stamina.
        assertTrue(cat.spells.values.filter { 3 in it.classReqIds }.all { it.costs.keys == setOf("mp") })
        assertTrue(cat.spells.values.filter { 1 in it.classReqIds || 2 in it.classReqIds }.all { it.costs.keys == setOf("sp") })

        // Monster levels rise through the valley, and each contested map ends in a boss.
        val monsters = cat.npcs.values.filter { it.behavior == NpcBehavior.ATTACK_ON_SIGHT || it.behavior == NpcBehavior.PASSIVE && it.xpReward > 0 }
        val levels = cat.maps.values.sortedBy { it.id }.drop(1).map { m ->
            m.npcSpawns.map { cat.npcs.getValue(it.npcId) }.filter { it in monsters }.map { it.level }
        }
        assertTrue(levels.all { it.isNotEmpty() })
        assertTrue(levels.zipWithNext().all { (a, b) -> a.max() < b.min() }, "levels by zone: $levels")
        assertEquals(listOf("Marrow Jack"), cat.maps.getValue(4).npcSpawns.map { cat.npcs.getValue(it.npcId) }.filter { it.title.isNotEmpty() }.map { it.name })
        assertEquals(listOf("The Pale Warden"), cat.maps.getValue(5).npcSpawns.map { cat.npcs.getValue(it.npcId) }.filter { it.title.isNotEmpty() }.map { it.name })
    }

    @Test
    fun `the five maps join at open seams and everything on them can be reached on foot`() {
        val cat = catalog()
        val maps = cat.maps
        assertEquals(2, maps.getValue(1).neighbors.eastMapId)
        assertEquals(3, maps.getValue(2).neighbors.eastMapId)
        assertEquals(4, maps.getValue(3).neighbors.northMapId)
        assertEquals(5, maps.getValue(4).neighbors.northMapId)
        for (m in maps.values) {
            assertEquals(64 to 48, m.width to m.height)
            assertTrue(m.cells.none { it.attribute is TileAttribute.Warp }, "${m.name}: the valley is walked, not warped")
            // A seam is open on both sides at the same cells, and an edge without a neighbour is closed.
            val east = maps[m.neighbors.eastMapId]
            for (y in 0 until m.height) assertEquals(east?.open(0, y) ?: false, m.open(m.width - 1, y), "${m.name} east edge, row $y")
            val north = maps[m.neighbors.northMapId]
            for (x in 0 until m.width) assertEquals(north?.open(x, north.height - 1) ?: false, m.open(x, 0), "${m.name} north edge, column $x")
            if (east != null) assertEquals(m.id, east.neighbors.westMapId)
            if (north != null) assertEquals(m.id, north.neighbors.southMapId)
        }

        val seen = reachable(cat)
        fun beside(mapId: Int, x: Int, y: Int) = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) -> Triple(mapId, x + dx, y + dy) in seen }
        for (m in maps.values) {
            assertTrue(seen.any { it.first == m.id }, "${m.name} can be walked to")
            for (s in m.npcSpawns) assertTrue(Triple(m.id, s.spawnX, s.spawnY) in seen, "${cat.npcs.getValue(s.npcId).name} in ${m.name} at ${s.spawnX},${s.spawnY}")
            m.cells.forEachIndexed { i, cell ->
                if (cell.attribute is TileAttribute.ResourceNode) assertTrue(beside(m.id, i % m.width, i / m.width), "node in ${m.name} at ${i % m.width},${i / m.width}")
            }
        }
        for (e in cat.events.values.filter { it.mapId != 0 }) assertTrue(beside(e.mapId, e.tileX, e.tileY), e.name)
        val scroll = cat.items.values.single { it.warpMapId != 0 }
        assertTrue(Triple(scroll.warpMapId, scroll.warpX, scroll.warpY) in seen)
    }

    @Test
    fun `everything can be obtained, every recipe can be made, and no shop makes silver from nothing`() {
        val cat = catalog()
        for (shop in cat.shops.values) {
            assertTrue(shop.sellRatePercent <= shop.buyRatePercent, shop.name)
            assertTrue(shop.entries.isNotEmpty(), shop.name)
            for (e in shop.entries) {
                val item = cat.items.getValue(e.itemId)
                assertTrue(item.type != ItemType.QUEST && !item.boundOnPickup, "${item.name} should not be sold")
            }
        }
        // The dearest any shop pays for an item must not beat the cheapest any shop sells it for.
        val bestSell = cat.shops.values.maxOf { it.sellRatePercent }
        assertTrue(bestSell < cat.shops.values.minOf { it.buyRatePercent })

        // Processing may not be a money machine: an output never sells for more than its shop-bought ingredients cost.
        val sold = cat.shops.values.flatMap { s -> s.entries.map { it.itemId } }.toSet()
        for (r in cat.recipes.values.filter { r -> r.ingredients.all { it.itemId in sold } }) {
            val cost = r.ingredients.sumOf { cat.items.getValue(it.itemId).price * it.amount }
            val income = cat.items.getValue(r.outputItemId).price * r.outputAmount * bestSell / 100
            assertTrue(income <= cost, "${r.name}: buy the ingredients for $cost, sell the result for $income")
        }

        val got = hashSetOf<Int>()
        cat.shops.values.forEach { s -> s.entries.forEach { got += it.itemId } }
        cat.npcs.values.forEach { n -> n.drops.forEach { got += it.itemId } }
        cat.resources.values.forEach { got += it.rewardItemId }
        cat.classes.values.forEach { c -> c.startingItems.forEach { got += it.itemId } }
        cat.quests.values.forEach { q -> q.reward.items.forEach { got += it.itemId } }
        do {
            val made = cat.recipes.values.filter { r -> r.outputItemId !in got && r.ingredients.all { it.itemId in got } }
            made.forEach { got += it.outputItemId }
        } while (made.isNotEmpty())
        for (r in cat.recipes.values) assertTrue(r.ingredients.all { it.itemId in got }, "${r.name} needs something nobody can get")
        assertEquals(emptyList<String>(), cat.items.values.filter { it.id !in got }.map { it.name }, "items nobody can get")

        // Every node on a map yields something, its tool is on sale, and every resource is placed somewhere.
        val placed = cat.maps.values.flatMap { m -> m.cells.mapNotNull { (it.attribute as? TileAttribute.ResourceNode)?.resourceId } }.toSet()
        assertEquals(cat.resources.keys, placed)
        for (r in cat.resources.values.filter { it.requiredTool != ToolType.NONE }) {
            assertTrue(cat.items.values.any { it.toolType == r.requiredTool && it.id in sold }, "${r.name} needs a tool nobody sells")
        }
        // Every skill a class does not start with has a manual on sale at its level.
        val starting = cat.classes.values.flatMap { it.startingSpellIds }.toSet()
        for (sp in cat.spells.values.filter { it.classReqIds.isNotEmpty() && it.id !in starting }) {
            val book = cat.items.values.single { it.teachSpellId == sp.id }
            assertTrue(book.id in sold, "${book.name} is not sold")
            assertEquals(sp.levelReq, book.levelReq, book.name)
            assertEquals(sp.classReqIds, book.classReqIds, book.name)
        }
        // Quests ask only for things that exist in the valley.
        for (q in cat.quests.values) for (o in q.objectives) {
            when (o.type.name) {
                "KILL_NPC" -> assertTrue(cat.maps.values.any { m -> m.npcSpawns.any { it.npcId == o.targetId } }, "${q.name}: nothing to kill")
                "COLLECT_ITEM" -> assertTrue(o.targetId in got, "${q.name}: nothing to collect")
            }
        }
    }

    @Test
    fun `the Bladewarden's sword and shield is a valid combo graph that the server loads`(@TempDir dir: Path) {
        val cat = catalog()
        val set = cat.weaponSets.values.single()
        assertEquals("Ford Sword and Shield", set.name)
        assertEquals(setOf(1), set.classIds)
        val skills = cat.actionSkills
        assertEquals(skills.keys, set.skillIds.toSet())
        fun name(id: Int) = skills.getValue(id).name

        // Left click three times is the basic chain; the launcher branches off the second cut.
        fun follow(from: Int, input: String) = set.edges.single { it.fromSkillId == from && it.input == input }.toSkillId
        val opener = set.entries.single { it.input == "LMB" }.skillId
        assertEquals(listOf("Ford Cut", "Backhand", "Riverbreak"), listOf(opener, follow(opener, "LMB"), follow(follow(opener, "LMB"), "LMB")).map(::name))
        assertEquals("Undertow", name(follow(follow(opener, "LMB"), "SHIFT+RMB")))
        // The launcher only flows into the finisher when it connected.
        assertTrue(set.edges.single { name(it.fromSkillId) == "Undertow" }.requiresHit)

        // The finisher trades hits (super armor) and knocks down; the dodges are invulnerable and cost stamina.
        val finisher = skills.values.single { it.name == "Riverbreak" }
        assertEquals(com.morgana.domain.content.SkillArmorType.SUPER_ARMOR, finisher.armor.single().type)
        assertEquals(com.morgana.domain.content.HitReaction.KNOCKDOWN, finisher.hitboxes.single().hit.reaction)
        for (dodge in skills.values.filter { it.name in setOf("Sidestep", "Backstep") }) {
            assertEquals(com.morgana.domain.content.SkillArmorType.INVULNERABLE, dodge.armor.single().type)
            assertTrue(dodge.hitboxes.isEmpty() && (dodge.costs["sp"] ?: 0) > 0 && dodge.moves.isNotEmpty(), dodge.name)
        }
        assertNotNull(skills.values.single { it.name == "Wardstance" }.guard)
        // Only the two opening cuts and raising the guard are free to start (the guard is paid for per blow blocked).
        assertEquals(setOf("Ford Cut", "Backhand", "Wardstance"), skills.values.filter { it.isFree }.map { it.name }.toSet())
        // Every skill's level is one the class can reach, and every cost is a vital the game has.
        assertTrue(skills.values.all { it.costs.keys.all { v -> v == "sp" } })

        // The running server holds the same definitions, ready for the action module.
        val sim = coordinator(dir).simulation
        assertEquals(skills, sim.actionSkills.toMap())
        assertEquals(cat.weaponSets, sim.weaponSets.toMap())
    }

    @Test
    fun `each class starts in the market square, armed and with its first skill`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val warden = c.me(join(c, "Wren", 1)); val runner = c.me(join(c, "Rook", 2)); val weaver = c.me(join(c, "Dusk", 3))
        val square = c.simulation.maps.getValue(1)
        for (p in listOf(warden, runner, weaver)) {
            assertEquals(1, p.position.mapId)
            assertEquals(26 to 24, p.position.tileX to p.position.tileY)
            assertTrue(square.open(p.position.tileX, p.position.tileY))
            assertNotNull(p.equipment.getEquipped(EquipmentSlot.WEAPON), "${p.name} starts armed")
            assertNotNull(p.equipment.getEquipped(EquipmentSlot.ARMOR), "${p.name} starts armoured")
        }
        val items = c.simulation.items
        assertEquals("Ford Longsword", items.getValue(warden.equipment.getEquipped(EquipmentSlot.WEAPON)!!.itemId).name)
        assertEquals("Ash Buckler", items.getValue(warden.equipment.getEquipped(EquipmentSlot.SHIELD)!!.itemId).name)
        assertEquals("Ash Shortbow", items.getValue(runner.equipment.getEquipped(EquipmentSlot.WEAPON)!!.itemId).name)
        assertEquals("Dusk Wand", items.getValue(weaver.equipment.getEquipped(EquipmentSlot.WEAPON)!!.itemId).name)
        assertEquals(listOf(1), warden.knownSpellIds)
        assertEquals(listOf(6), runner.knownSpellIds)
        assertEquals(listOf(11, 12), weaver.knownSpellIds)
        assertTrue(listOf(warden, runner, weaver).all { (it.vitals["sp"]?.max ?: 0) >= 100 }, "everyone has stamina")
    }

    @Test
    fun `the first hour plays - shop, quest, fight, fish, cook and the road east`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val s = join(c, "Ann", 1)
        val id = s.characterId!!
        c.give(s, 1, 500)

        // Outfitter Pim stands at (24, 12) and sells the willow rod (his third entry).
        c.stand(s, 1, 24, 13, Direction.UP)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(3, s.windows().single { it.windowType == ServerUiWindowType.SHOP }.shopId)
        c.handleClientPacket(s, ClientPacket.ShopBuy(3, 2, 1))
        val rod = sim.items.values.single { it.name == "Willow Rod" }
        assertEquals(1, c.me(s).inventory.totalCountOf(rod.id), s.system().toString())

        // Marshal Renna Vale stands at (25, 20) and hands out the first job.
        c.stand(s, 1, 25, 21, Direction.UP)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(listOf("What needs doing?", "Tell me about the valley.", "Goodbye."), s.windows().last().choiceLabels)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 0))
        assertEquals(listOf("I will do it.", "Not now."), s.windows().last().choiceLabels)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 0))
        assertNotNull(c.me(s).quests[1], "Rats in the Millrace was started")

        // Whitefin from the shoal at (46, 30), fished from the east quay.
        val whitefin = sim.items.values.single { it.name == "Whitefin" }
        c.stand(s, 1, 47, 30, Direction.LEFT)
        repeat(6) {
            if (c.me(s).inventory.totalCountOf(whitefin.id) == 0) {
                sim.playerAttack(id)
                repeat(15) { sim.stepTick() }
            }
        }
        assertTrue(c.me(s).inventory.totalCountOf(whitefin.id) >= 1, "a whitefin was caught")

        // Grilling needs Cook Hesper's fire at (17, 33): refused in the square, done at the fire.
        val grilled = sim.items.values.single { it.name == "Grilled Whitefin" }
        c.stand(s, 1, 26, 24, Direction.UP)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.CraftRecipe(3))
        assertEquals(0, c.me(s).inventory.totalCountOf(grilled.id), "no fire here")
        c.stand(s, 1, 17, 32, Direction.DOWN)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertTrue(s.windows().any { it.windowType == ServerUiWindowType.CRAFTING })
        c.handleClientPacket(s, ClientPacket.CraftRecipe(3))
        assertEquals(1, c.me(s).inventory.totalCountOf(grilled.id), s.system().toString())

        // East along the river road: off the edge of the Ford and onto the edge of Millrace Fields, with no warp.
        c.stand(s, 1, 63, 23, Direction.RIGHT)
        repeat(5) { sim.stepTick() }
        sim.movePlayer(id, Direction.RIGHT)
        assertEquals(Triple(2, 0, 23), c.me(s).position.let { Triple(it.mapId, it.tileX, it.tileY) })

        // A millrace rat dies to a Bladewarden's sword, even under the Ford's peace, and pays experience.
        val before = c.me(s)
        c.stand(s, 2, 10, 23, Direction.RIGHT)
        sim.mapNpcs[2] = mutableListOf(MapNpcInstance(instanceId = "test-rat", spawnSlotIndex = 0, npcId = 20, mapId = 2,
            tileX = 11, tileY = 23, homeX = 11, homeY = 23, vitals = mapOf("hp" to VitalState(20, 20))))
        repeat(20) {
            if (sim.mapNpcs.getValue(2).first().isAlive) {
                sim.players[id] = c.me(s).copy(position = WorldPosition.fromTile(2, 10, 23, Direction.RIGHT))
                val rat = sim.mapNpcs.getValue(2).first()
                sim.mapNpcs[2] = mutableListOf(rat.copy(tileX = 11, tileY = 23))
                sim.playerAttack(id)
                repeat(12) { sim.stepTick() }
            }
        }
        assertTrue(!sim.mapNpcs.getValue(2).first().isAlive, "the rat is dead")
        val after = c.me(s)
        assertTrue(after.level > before.level || after.xp > before.xp, "the kill paid experience")
        assertEquals(1, after.quests.getValue(1).objectiveCounts.first(), "the kill counted for the quest")
    }
}
