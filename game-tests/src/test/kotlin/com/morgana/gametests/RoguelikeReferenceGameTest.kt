package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.content.ShopEntry
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.contentMapId
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.ServerUiWindowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The reference co-op dungeon crawler & roguelike, `games/roguelike` (Ashen Keep): its content and art are valid and
 * rich, classes start with their own paperdoll gear and spells, the hub's shops and quest giver work, and a party walks
 * through the northern archway into Floor 1 (The Sunken Crypt), faces Malakor, the Ashen Warden, and returns home.
 * Runs on a copy in a temp folder, never on the game itself.
 */
class RoguelikeReferenceGameTest {

    private val game: Path = listOf(Path.of("games/roguelike"), Path.of("../games/roguelike")).first { it.exists() }

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
                val dst = to.resolve(game.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        return to
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(
        MorganaDataStore(copyGame(dir)), ownerPolicy = OwnerPolicy(dir) { null },
        packetLimits = com.morgana.server.PacketLimitSettings(enabled = false)
    )

    private fun join(c: MorganaWorldCoordinator, name: String, classId: Int = 1): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, classId, protocolVersion = PROTOCOL_VERSION,
            capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW))
        return s
    }

    private fun MorganaWorldCoordinator.me(s: FakeSession) = simulation.players.getValue(s.characterId!!)

    private fun MorganaWorldCoordinator.stand(s: FakeSession, x: Int, y: Int, facing: Direction) {
        val p = me(s)
        simulation.players[p.id] = p.copy(position = WorldPosition.fromTile(1, x, y, facing))
    }

    @Test
    fun `the content loads with no problems and has hub, Floor 1 crypt, paperdoll art, and full roguelike content`() {
        val loaded = ContentLoader.load(game.resolve("data"))
        val definition = com.morgana.persistence.GameDefinitionLoader.load(game).game.definition
        val report = com.morgana.domain.validation.ContentValidator.validate(loaded.catalog, definition)
        assertTrue(report.issues.isEmpty(), "issues: ${report.issues}")
        assertEquals("Ashen Keep", definition.name)
        assertTrue(definition.sprites?.paperdollingV2 == true, "uses the Trevana 32x48 paperdoll workflow")
        assertEquals(32 to 48, definition.sprites!!.cellWidth to definition.sprites!!.cellHeight)

        val cat = loaded.catalog
        assertTrue(cat.items.size >= 40, "items: ${cat.items.size}")
        assertEquals(setOf(ItemType.WEAPON, ItemType.SHIELD, ItemType.HELMET, ItemType.ARMOR, ItemType.BOOTS, ItemType.RING, ItemType.AMULET, ItemType.AMMO,
            ItemType.CONSUMABLE, ItemType.SPELL_SCROLL, ItemType.MATERIAL, ItemType.CURRENCY, ItemType.QUEST), cat.items.values.map { it.type }.toSet())
        assertEquals(3, cat.classes.size)
        assertEquals(4, cat.shops.size, "three hub shops and the wandering peddler")
        assertEquals(6, cat.recipes.size)
        assertEquals(3, cat.quests.size)
        assertEquals(7, cat.spells.size)
        assertEquals(ItemType.CURRENCY, cat.items.getValue(1).type, "gold is item 1: quests pay into it")
        assertEquals(40, definition.inventorySlots)

        // Exactly 2 maps: Map 1 (Ashen Keep multiplayer hub) and Map 2 (Floor 1 instanced procedural crypt).
        assertEquals(listOf(1, 2), cat.maps.keys.sorted())
        val floors = cat.maps.values.filter { it.generator != null }.sortedBy { it.id }
        assertEquals(listOf(2), floors.map { it.id }, "single instanced dungeon floor to start")
        val floor1 = floors.single()
        assertEquals(0, floor1.generator!!.nextMapId)
        assertEquals("Malakor, the Ashen Warden", cat.npcs.getValue(floor1.generator!!.bossNpcId).name)
        assertEquals(1, floor1.generator!!.exitMapId, "Floor 1 exit leads home to Ashen Keep")

        val loot = definition.loot
        assertTrue(loot != null && loot.rarities.size >= 4 && loot.affixes.size >= 16, "Ashen Keep rolls loot: $loot")
        assertTrue(com.morgana.domain.game.GameDefinitionValidator.validate(definition).isEmpty())
        assertTrue(cat.items.values.filter { it.name.startsWith("Warden's") && it.type != ItemType.QUEST && it.name != "Warden's Scepter" }.all { !it.rollAffixes }, "boss uniques stay fixed")
    }

    @Test
    fun `selling never earns more than buying, and the shops stock real things`() {
        val cat = ContentLoader.load(game.resolve("data")).catalog
        for (shop in cat.shops.values) {
            assertTrue(shop.sellRatePercent <= shop.buyRatePercent, "${shop.name}: sell rate must not exceed buy rate or gold can be farmed")
            assertTrue(shop.entries.isNotEmpty())
            for (e: ShopEntry in shop.entries) {
                val item = cat.items.getValue(e.itemId)
                assertTrue(item.type != ItemType.QUEST && !item.boundOnPickup, "${item.name} should not be sold")
                val price = if (e.priceOverride > 0) e.priceOverride else maxOf(1, item.price * shop.buyRatePercent / 100)
                assertTrue(price > 0)
                assertTrue(maxOf(1, item.price * shop.sellRatePercent / 100) * e.amount <= price, "${item.name}: buying then selling back must not make gold")
            }
        }
        for (r in cat.recipes.values) {
            assertTrue(r.ingredients.isNotEmpty() && r.ingredients.all { it.itemId in cat.items })
        }
    }

    @Test
    fun `each class starts with its own equipped gear, spells, and open hub spawn`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val vanguard = c.me(join(c, "Val", 1))
        val ranger = c.me(join(c, "Ren", 2))
        val mage = c.me(join(c, "Pyra", 3))
        assertEquals(40, vanguard.inventory.maxSlots)
        assertTrue(vanguard.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId == 2 && vanguard.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.ARMOR)?.itemId == 20, "Vanguard starts armed and armored")
        assertTrue(ranger.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId == 6 && ranger.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.ARMOR)?.itemId == 21, "Ranger starts armed and armored")
        assertTrue(mage.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId == 9 && mage.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.ARMOR)?.itemId == 22, "Pyromancer starts armed and armored")
        assertEquals(listOf(4), vanguard.knownSpellIds, "every class can Rekindle a fallen ally")
        assertEquals(listOf(1, 2, 4), mage.knownSpellIds)
        assertTrue(ranger.attributes.getValue("agi") > vanguard.attributes.getValue("agi"))
        assertTrue(mage.attributes.getValue("int") > vanguard.attributes.getValue("int"))
    }

    @Test
    fun `the hub apothecary sells and buys, and dealers will not trade from across Ashen Keep`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = join(c, "Ann")
        val gold = c.simulation.items.values.first { it.type == ItemType.CURRENCY }
        val ann = c.me(s)
        c.simulation.players[ann.id] = ann.copy(inventory = ann.inventory.addItem(gold, 300).updatedInventory)
        // Apothecary Lyra stands at (29, 8).
        c.stand(s, 28, 8, Direction.RIGHT)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        val shop = s.windows().single { it.windowType == ServerUiWindowType.SHOP }
        assertEquals(1, shop.shopId)

        val healing = c.simulation.items.values.first { it.name == "Healing Draught" }
        val before = c.me(s).inventory.totalCountOf(healing.id)
        c.handleClientPacket(s, ClientPacket.ShopBuy(1, 0, 4))
        assertEquals(before + 4, c.me(s).inventory.totalCountOf(healing.id))
        assertEquals(300 - 4 * healing.price, c.me(s).inventory.totalCountOf(gold.id))
        assertTrue(s.system().any { it.startsWith("Bought 4 x Healing Draught") }, s.system().toString())

        val slot = c.me(s).inventory.slots.indexOfFirst { it?.itemId == healing.id }
        c.handleClientPacket(s, ClientPacket.ShopSell(1, slot, 2))
        assertEquals(before + 2, c.me(s).inventory.totalCountOf(healing.id))
        assertTrue(s.system().any { it.startsWith("Sold 2 x Healing Draught") })

        // Walking away from Lyra: the shop is out of reach.
        c.stand(s, 5, 12, Direction.DOWN)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.ShopBuy(1, 0, 1))
        assertEquals(listOf("You are too far from the shop."), s.system())
    }

    @Test
    fun `Commander Vael explains runs and offers the first Floor 1 contract`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = join(c, "Ann")
        c.stand(s, 14, 8, Direction.UP) // Commander Vael is at (14, 7)
        s.sent.clear()
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        val greeting = s.windows().last()
        assertEquals(listOf("How does a run work?", "Any contracts for me?", "Goodbye."), greeting.choiceLabels)

        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 1))
        val offer = s.windows().last()
        assertTrue(offer.dialogueText.contains("Crypt Gnashers"), offer.dialogueText)
        assertEquals(listOf("I will do it.", "Not now."), offer.choiceLabels)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 0))
        assertNotNull(c.me(s).quests[1], "Clear the Threshold was started")

        s.sent.clear()
        c.stand(s, 14, 8, Direction.UP)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 1))
        assertTrue(s.windows().last().dialogueText.startsWith("Four Crypt Gnashers"), s.windows().last().dialogueText)
    }

    @Test
    fun `a party enters Floor 1 through the northern archway, meets Malakor, and returns to Ashen Keep`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val sessions = listOf("Ann", "Ben").map { join(c, it) }
        val ann = sim.players.values.first { it.name == "Ann" }
        val ben = sim.players.values.first { it.name == "Ben" }
        assertEquals(1, ann.position.mapId, "new delvers start in Ashen Keep")
        sim.players[ann.id] = ann.copy(partyId = "p1", position = WorldPosition.fromTile(1, 17, 4, Direction.UP))
        sim.players[ben.id] = ben.copy(partyId = "p1", position = WorldPosition.fromTile(1, 17, 4, Direction.UP))

        // Both step onto the dungeon archway portal at (17, 3).
        repeat(20) { sim.stepTick() }
        sessions.forEach { c.handleClientPacket(it, ClientPacket.MoveInput(Direction.UP)) }
        val floor1 = sim.players.getValue(ann.id).position.mapId
        assertTrue(MapInstances.isInstanceId(floor1), "the portal opens a private floor")
        assertEquals(floor1, sim.players.getValue(ben.id).position.mapId, "the party shares it")
        assertEquals(2, sim.contentMapId(floor1))
        val generated = sim.maps.getValue(floor1)
        assertEquals(44 to 34, generated.width to generated.height)
        assertTrue(sim.mapNpcs.getValue(floor1).any { sim.npcs.getValue(it.npcId).name == "Malakor, the Ashen Warden" }, "Malakor waits on Floor 1")
        assertNotNull(
            sessions[0].sent.filterIsInstance<ServerPacket.MapStateSnapshot>().lastOrNull { it.activeMap?.id == floor1 },
            "the client is sent the generated floor"
        )

        val exit = sim.maps.getValue(floor1).cells.first { it.attribute is TileAttribute.Warp }.attribute as TileAttribute.Warp
        assertEquals(1, exit.targetMapId)
        sim.applyScriptEffect(ScriptEffect.WarpPlayer(ann.id, exit.targetMapId, exit.targetX, exit.targetY))
        val home = sim.players.getValue(ann.id)
        assertEquals(1, home.position.mapId)
        assertEquals(17 to 6, home.position.tileX to home.position.tileY)
        assertEquals(null, home.instanceReturn)
    }

    @Test
    fun `the game uses action combat, boss encounters, difficulty tiers, elites, and room features`() {
        val def = com.morgana.persistence.GameDefinitionLoader.load(game).game.definition
        assertTrue(com.morgana.domain.game.GameDefinitionValidator.validate(def).isEmpty())
        assertTrue(def.sprint?.enabled == true && def.combat.module == "action")
        assertTrue(def.ghost?.enabled == true && def.ghost!!.wipeEndsRun)
        assertTrue(def.bestiary?.enabled == true && def.paragon?.enabled == true)

        val cat = ContentLoader.load(game.resolve("data")).catalog
        val boss = cat.npcs.getValue(15)
        val phases = boss.encounter!!.phases
        assertTrue(phases.size == 3 && phases.zipWithNext().all { (a, b) -> b.fromLifePercent < a.fromLifePercent })
        assertTrue(phases.flatMap { it.abilities }.isNotEmpty() && phases.any { it.summonNpcIds.isNotEmpty() })

        val floor = cat.maps.getValue(2)
        assertEquals(listOf("Normal", "Hard", "Nightmare"), floor.scaling!!.tiers.map { it.name })
        assertEquals(listOf("Wayside Shrine", "Wandering Peddler"), floor.generator!!.features.map { cat.npcs.getValue(it.npcId).name })

        val sets = cat.weaponSets.values.sortedBy { it.id }
        assertEquals(listOf(setOf(1), setOf(2), setOf(3)), sets.map { it.classIds })
        assertTrue(cat.actionSkills.values.any { skill -> skill.armor.any { it.type == com.morgana.domain.content.SkillArmorType.INVULNERABLE } })
        assertTrue(cat.actionSkills.values.any { skill -> skill.hitboxes.any { it.projectile != null } })
        assertTrue(cat.actionSkills.values.any { it.guard != null })
    }

    @Test
    fun `a fallen delver is a ghost and a party mate's Rekindle brings them back`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val fallen = join(c, "Fay", 1).characterId!!
        val healer = join(c, "Hal", 3).characterId!!
        sim.spawnOrUpdatePlayer(sim.players.getValue(fallen).copy(position = WorldPosition.fromTile(1, 14, 15, Direction.DOWN)))
        sim.spawnOrUpdatePlayer(sim.players.getValue(healer).copy(position = WorldPosition.fromTile(1, 16, 15, Direction.LEFT)))
        sim.players[fallen] = sim.players.getValue(fallen).let { it.copy(vitals = it.vitals + ("hp" to com.morgana.domain.stats.VitalState(0, it.vitals.getValue("hp").max))) }
        repeat(2) { sim.stepTick() }
        assertTrue(sim.players.getValue(fallen).isGhost, "the game's ghost rule applies")
        sim.playerCastSpell(healer, 4, fallen, 0, 0)
        val back = sim.players.getValue(fallen)
        assertTrue(!back.isGhost && back.vitals.getValue("hp").cur == back.vitals.getValue("hp").max * 60 / 100, "Rekindle restores 60%")
    }

    @Test
    fun `Delve Warden Orin sets the difficulty tier Floor 1 opens with`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val s = join(c, "Tia")
        val id = s.characterId!!
        sim.players[id] = sim.players.getValue(id).copy(level = 6)
        c.stand(s, 21, 8, Direction.UP) // Delve Warden Orin is at (21, 7)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(2, 1))
        assertEquals(1, sim.players.getValue(id).variables["dungeon_tier"])

        sim.applyScriptEffect(ScriptEffect.WarpPlayer(id, 2, 0, 0))
        val floor = sim.players.getValue(id).position.mapId
        val monster = sim.mapNpcs.getValue(floor).first { isHostileForTest(sim, it) }
        val expected = sim.maps.getValue(floor).scaling!!.scaleFor(1, 6, sim.npcs.getValue(monster.npcId).level, tier = 1)
        assertEquals(expected, monster.scale)
    }

    @Test
    fun `in action combat a Vanguard chains a combo and rolls, and a Ranger shoots`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val vanguard = join(c, "Val", 1).characterId!!
        val ranger = join(c, "Ren", 2).characterId!!
        sim.applyScriptEffect(ScriptEffect.WarpPlayer(vanguard, 2, 0, 0))
        val floor = sim.players.getValue(vanguard).position.mapId
        val monster = sim.mapNpcs.getValue(floor).first { isHostileForTest(sim, it) }
        fun stand(id: String, dx: Int, dir: Direction) {
            sim.spawnOrUpdatePlayer(sim.players.getValue(id).copy(position = WorldPosition.fromTile(floor, monster.tileX + dx, monster.tileY, dir)))
        }
        fun life() = sim.mapNpcs.getValue(floor).single { it.instanceId == monster.instanceId }.vitals.getValue("hp").cur

        sim.mapNpcs.getValue(floor).let { list ->
            val i = list.indexOfFirst { it.instanceId == monster.instanceId }
            list[i] = list[i].copy(statusEffects = listOf(com.morgana.domain.stats.ActiveStatusEffect("hold", com.morgana.domain.stats.StatusEffectType.STUN, vanguard, 0, emptyMap(), 100_000)))
        }
        stand(vanguard, -1, Direction.RIGHT)
        val full = life()
        sim.handleAction(vanguard, "input", mapOf("token" to "LMB"))
        assertEquals(1, sim.actionStates.getValue(vanguard).skillId)
        repeat(5) { sim.stepTick() }
        assertTrue(life() < full, "Keep Slash landed")
        sim.handleAction(vanguard, "input", mapOf("token" to "LMB"))
        assertEquals(2, sim.actionStates.getValue(vanguard).skillId, "chains into Cross Cut")

        repeat(30) { sim.stepTick() }
        sim.handleAction(vanguard, "input", mapOf("token" to "SPACE"))
        assertEquals(5, sim.actionStates.getValue(vanguard).skillId, "Space starts Dodge Roll")
        assertTrue(sim.players.getValue(vanguard).vitals.getValue("stamina").cur < sim.players.getValue(vanguard).vitals.getValue("stamina").max)

        sim.mapNpcs.getValue(floor).let { list ->
            val i = list.indexOfFirst { it.instanceId == monster.instanceId }
            val live = list[i]
            list[i] = live.copy(vitals = live.vitals + ("hp" to live.vitals.getValue("hp").copy(cur = live.vitals.getValue("hp").max)), respawnAtTick = 0L, tileX = monster.tileX, tileY = monster.tileY)
        }
        val floorMap = sim.maps.getValue(floor)
        fun passable(tx: Int, ty: Int) = tx in 0 until floorMap.width && ty in 0 until floorMap.height && floorMap.cells[ty * floorMap.width + tx].attribute !is TileAttribute.Blocked
        val (rx, ry, rDir) = listOf(
            Triple(monster.tileX - 2, monster.tileY, Direction.RIGHT),
            Triple(monster.tileX + 2, monster.tileY, Direction.LEFT),
            Triple(monster.tileX, monster.tileY - 2, Direction.DOWN),
            Triple(monster.tileX, monster.tileY + 2, Direction.UP)
        ).firstOrNull { (tx, ty, _) ->
            val midX = (tx + monster.tileX) / 2
            val midY = (ty + monster.tileY) / 2
            passable(tx, ty) && passable(midX, midY)
        } ?: Triple(monster.tileX - 1, monster.tileY, Direction.RIGHT)
        sim.spawnOrUpdatePlayer(sim.players.getValue(ranger).copy(position = WorldPosition.fromTile(floor, rx, ry, rDir)))
        val hurt = life()
        sim.handleAction(ranger, "input", mapOf("token" to "LMB"))
        repeat(25) { sim.stepTick() }
        assertTrue(life() < hurt, "Ranger arrow flew and hit")
    }

    private fun isHostileForTest(sim: com.morgana.domain.sim.WorldSimulation, npc: com.morgana.domain.entity.MapNpcInstance): Boolean {
        val behavior = sim.npcs.getValue(npc.npcId).behavior
        return behavior == com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT && npc.isAlive
    }

    @Test
    fun `a Wayside Shrine heals for 40 gold and refuses without it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val sim = c.simulation
        val s = join(c, "Sam")
        val id = s.characterId!!
        sim.mapNpcs.getValue(1).add(
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "shrine-test", spawnSlotIndex = 99, npcId = 7, mapId = 1, tileX = 17, tileY = 18, homeX = 17, homeY = 18,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(200, 200))
            )
        )
        val gold = sim.items.values.first { it.type == ItemType.CURRENCY }
        val me = sim.players.getValue(id)
        val hurt = me.vitals.getValue("hp").copy(cur = 5)
        sim.players[id] = me.copy(vitals = me.vitals + ("hp" to hurt), inventory = me.inventory.addItem(gold, 100).updatedInventory)
        c.stand(s, 17, 17, Direction.DOWN)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(3, 0))
        assertEquals(60, sim.players.getValue(id).inventory.totalCountOf(gold.id))
        assertEquals(hurt.max, sim.players.getValue(id).vitals.getValue("hp").cur)

        val poor = sim.players.getValue(id)
        sim.players[id] = poor.copy(vitals = poor.vitals + ("hp" to hurt), inventory = poor.inventory.removeByItemId(gold.id, 60) ?: poor.inventory)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(3, 0))
        assertEquals(5, sim.players.getValue(id).vitals.getValue("hp").cur)
    }
}
