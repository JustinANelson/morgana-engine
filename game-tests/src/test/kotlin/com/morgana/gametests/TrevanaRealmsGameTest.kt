package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.systems.isStalled
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import com.morgana.server.assets.ArtCheck
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Integration tests for the Trevana Realms (`games/trevana-realms`): validates content, art, ORPG module
 * configuration, all 5 classes, all 9 interconnected maps/warps, shops, quests, and crafting on a temp copy.
 */
class TrevanaRealmsGameTest {

    private val gameOrNull: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { it.exists() }
    private val game: Path
        get() {
            org.junit.jupiter.api.Assumptions.assumeTrue(gameOrNull != null, "Developer game games/trevana-realms is not present in this checkout")
            return gameOrNull!!
        }

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
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

    private fun join(c: MorganaWorldCoordinator, name: String, classId: Int): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(
            s,
            ClientPacket.Authenticate(
                name, "pw", true, classId,
                protocolVersion = PROTOCOL_VERSION,
                capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW
            )
        )
        return s
    }

    @Test
    fun `content, art, and ORPG modules are valid with zero issues`() {
        val def = GameDefinitionLoader.load(game).game.definition
        assertEquals("trevana-realms", def.id)
        assertEquals("realtime", def.combat.module)

        val serverCfg = ServerConfig.load(game)
        val flags = serverCfg.moduleFlags()
        assertTrue(flags.isEnabled(OptionalModule.PARTIES))
        assertTrue(flags.isEnabled(OptionalModule.GUILDS))
        assertTrue(flags.isEnabled(OptionalModule.TRADING))
        assertTrue(flags.isEnabled(OptionalModule.CRAFTING))
        assertTrue(flags.isEnabled(OptionalModule.SOFT_SEAMS))
        assertTrue(flags.isEnabled(OptionalModule.EXPLORATION))
        assertTrue(flags.isEnabled(OptionalModule.MARKET))

        val loaded = ContentLoader.load(game.resolve("data"))
        val report = loaded.fullReport(def)
        // While the world is rebuilt, quest-giver NPCs have no event yet (their quests and events were removed with the old maps).
        val issues = report.issues.filterNot { it.toString().contains("QUEST_GIVER NPC has no eventId") }
        assertTrue(issues.isEmpty(), "Expected 0 content issues, found: $issues")

        val art = ArtCheck.check(game)
        assertTrue(art.errors.isEmpty(), "Expected 0 art errors, found: ${art.errors}")
        assertTrue(art.warnings.isEmpty(), "Expected 0 art warnings, found: ${art.warnings}")

        assertEquals(5, loaded.catalog.classes.size)
        assertTrue(loaded.catalog.maps.isNotEmpty(), "the world has at least the placeholder Trevena map while it is rebuilt")
        val pvpMaps = setOf(17, 19, 25)
        val arenaMaps = setOf(27, 30)
        for ((id, map) in loaded.catalog.maps) {
            val want = when (id) { in pvpMaps -> MapMoral.OPEN_PVP; in arenaMaps -> MapMoral.ARENA; else -> MapMoral.SAFE_ZONE }
            assertEquals(want, map.moral, "map $id (${map.name})")
        }

        // Cities must not directly warp to one another
        val cityIds = setOf(1)
        for (cityId in cityIds) {
            val city = loaded.catalog.maps.getValue(cityId)
            val directTargets = city.cells.mapNotNull { (it.attribute as? TileAttribute.Warp)?.targetMapId }.toSet()
            val cityTargets = directTargets.intersect(cityIds - cityId)
            assertTrue(cityTargets.isEmpty(), "City $cityId should not warp directly to other cities: $cityTargets")
        }

        // Healing items are staunchers
        val healItems = loaded.catalog.items.values.filter { (it.restore["hp"] ?: 0) > 0 }
        assertTrue(healItems.isNotEmpty())
        assertTrue(healItems.all { "salve" in it.name.lowercase() }, "Expected all HP healing items to be Salves: ${healItems.map { it.name }}")

        // Rarities: Common (White), Fine (Green), Enchanted (Blue), Exalted (Red), Mythic (Yellow)
        val rarities = def.loot?.rarities.orEmpty()
        assertEquals(listOf("normal", "refined", "magic", "elite", "super"), rarities.map { it.id })
        assertEquals(listOf("#e5e7eb", "#34d399", "#60a5fa", "#f87171", "#fbbf24"), rarities.map { it.color })
        assertTrue(rarities.first().unlabeled)

        // Early enemies are PASSIVE and enemies are slower, drop gear, and have rare Starshard/Wyrm Pearl drops
        for (earlyId in 20..25) {
            val npc = loaded.catalog.npcs.getValue(earlyId)
            assertEquals(com.morgana.domain.content.NpcBehavior.PASSIVE, npc.behavior, "Early enemy ${npc.name} should not auto-aggro")
        }
        val monsters = loaded.catalog.npcs.values.filter {
            it.guildWarRole.isEmpty() && it.name != "Training Dummy" && (it.behavior == com.morgana.domain.content.NpcBehavior.PASSIVE ||
                it.behavior == com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT)
        }
        assertTrue(monsters.all { it.moveSpeedTicks >= 18 }, "Monsters should move slower")
        assertTrue(monsters.all { m -> m.drops.any { loaded.catalog.items[it.itemId]?.equipmentSlotOrNull() != null } }, "Every monster should drop gear")
        val meteorDrop = monsters.first { it.id == 20 }.drops.first { it.itemId == 7 }
        val dbDrop = monsters.first { it.id == 20 }.drops.first { it.itemId == 8 }
        assertTrue(meteorDrop.chanceBasisPoints in 1..100, "Starshard drop should be tiny")
        assertTrue(dbDrop.chanceBasisPoints in 1 until meteorDrop.chanceBasisPoints, "Wyrm Pearl drop should be even smaller than Starshard")

        // Ranger Volley auto-targets up to 5 enemies within 3 tiles with 40% (+5%/lv) base damage (rebalanced 2026-11-22: was 10 enemies in 8 tiles)
        val scatter = loaded.catalog.spells.getValue(7)
        assertEquals("Volley", scatter.name)
        assertEquals(15, scatter.levelReq)
        assertEquals(3, scatter.aoeRadiusTiles)
        assertEquals(5, scatter.maxTargets)
        assertEquals(40, scatter.baseDamagePercent)
        assertEquals(5, scatter.baseDamagePercentPerLevel)
        assertTrue(scatter.costs.isEmpty(), "Volley should not cost mana")
        assertEquals(60, scatter.cooldownTicks)

        // Hail of Arrows deals 105% base damage per hit, scaling from 2 hits at Lv 1 to 4 hits at max level
        val rapidFire = loaded.catalog.spells.getValue(8)
        assertEquals("Hail of Arrows", rapidFire.name)
        assertEquals(30, rapidFire.levelReq)
        assertEquals(2, rapidFire.hitCount)
        assertEquals(4, rapidFire.maxHitCount)
        assertEquals(105, rapidFire.baseDamagePercent)
        assertEquals(0, rapidFire.baseDamagePercentPerLevel)
        assertEquals(60, rapidFire.cooldownTicks)
        assertEquals(2, rapidFire.effectiveHitCount(1))
        assertEquals(4, rapidFire.effectiveHitCount(rapidFire.maxSpellLevel))

        // Town NPCs 1..11 and 13..15 are friendly; Town Guard (12) is GUARD
        for (npcId in (1..11) + (13..15)) {
            val b = loaded.catalog.npcs.getValue(npcId).behavior
            assertTrue(
                b in setOf(
                    com.morgana.domain.content.NpcBehavior.SHOPKEEPER,
                    com.morgana.domain.content.NpcBehavior.BANKER,
                    com.morgana.domain.content.NpcBehavior.QUEST_GIVER,
                    com.morgana.domain.content.NpcBehavior.FRIENDLY_ROAM
                ),
                "Town NPC $npcId should not be attackable (was $b)"
            )
        }
        assertEquals(com.morgana.domain.content.NpcBehavior.GUARD, loaded.catalog.npcs.getValue(12).behavior)
        assertEquals(0, loaded.catalog.npcs.getValue(10).shopId, "Ascension Envoy should not sell skills")
        assertEquals(5, loaded.catalog.npcs.getValue(13).shopId, "Training Master should sell skills")

        // Town Gate is town-specific and consumable, not a learned spell
        assertEquals(50, loaded.catalog.spells.size)
        listOf(14, 16).forEach { id ->
            val sp = loaded.catalog.spells.getValue(id)
            assertTrue(sp.allyTargetable && sp.rangeTiles > 1, "${sp.name} supports party/guild allies")
        }
        assertTrue(loaded.catalog.spells.values.none { it.targetType == com.morgana.domain.content.SpellTargetType.WARP })
        val tcGate = loaded.catalog.items.getValue(6)
        val pcGate = loaded.catalog.items.getValue(63)
        val dcGate = loaded.catalog.items.getValue(64)
        assertEquals(com.morgana.domain.item.ItemType.CONSUMABLE, tcGate.type)
        assertEquals(1, tcGate.warpMapId)
        assertEquals(com.morgana.domain.item.ItemType.CONSUMABLE, pcGate.type)
        assertEquals(1, pcGate.warpMapId)
        assertEquals(com.morgana.domain.item.ItemType.CONSUMABLE, dcGate.type)
        assertEquals(1, dcGate.warpMapId)

        // Skill books match the required level of the skill they teach
        for (bookId in 45..62) {
            val book = loaded.catalog.items.getValue(bookId)
            val taught = loaded.catalog.spells.getValue(book.teachSpellId)
            assertEquals(taught.levelReq, book.levelReq, "${book.name} levelReq should match ${taught.name}")
        }

        // Enemy variants are higher level than base monsters
        assertTrue(def.combat.enemyVariants.all { it.levelBonus >= 1 && it.effectiveLevel(10) > 10 })
    }

    @Test
    fun `worn gear is coloured per class so the classes stay apart at every tier`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val types = setOf(com.morgana.domain.item.ItemType.WEAPON, com.morgana.domain.item.ItemType.ARMOR, com.morgana.domain.item.ItemType.HELMET,
            com.morgana.domain.item.ItemType.BOOTS, com.morgana.domain.item.ItemType.SHIELD)
        val gear = c.simulation.items.values.filter { it.type in types && it.id in (19..44) + (65..67) && it.paperdollSpriteId in (1..21) && it.paperdollSpriteId != 2 && it.paperdollSpriteId != 10 }
        assertTrue(gear.size >= 20, "expected the three gear tiers, found ${gear.map { it.id }}")
        for (item in gear) {
            assertEquals(setOf(1, 2, 3, 4, 5), item.paperdollClassTints.keys, "${item.name} should have a colour for each class")
            assertEquals(5, item.paperdollClassTints.values.toSet().size, "${item.name} should look different on each class")
        }
    }

    @Test
    fun `every class starts in its own armor with its own colour`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val armors = (1..5).map { classId ->
            val s = join(c, "colour$classId", classId)
            val worn = c.simulation.players.getValue(s.characterId!!).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.ARMOR)
            assertNotNull(worn, "class $classId should start with armor equipped")
            c.simulation.items.getValue(worn!!.itemId)
        }
        assertEquals(5, armors.map { it.id }.toSet().size, "each class has its own starting armor")
        assertEquals(5, armors.map { it.paperdollTintHex }.toSet().size, "each class's armor is its own worn colour")
        assertTrue(armors.all { it.paperdollTintHex != null && it.paperdollSpriteId > 0 })
    }

    @Test
    fun `archer starts with bow and armor equipped, learns scatter via book, and levels it up`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val s = join(c, "archer1", 3) // Ranger
        val sim = c.simulation
        val charId = s.characterId!!
        val player = sim.players.getValue(charId)
        assertTrue(player.knownSpellIds.isEmpty(), "Ranger should start with no bow skills")
        assertEquals(22, player.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId, "Hunter Bow should start equipped")
        assertEquals(100, player.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.ARMOR)?.itemId, "the Ranger's Tunic should start equipped")

        // Learn Volley (spell 7) from Volley Book (item 51)
        val invWithBook = player.inventory.addItem(sim.items.getValue(51), 1).updatedInventory
        val archerPos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.DOWN)
        sim.spawnOrUpdatePlayer(player.copy(level = 15, position = archerPos, inventory = invWithBook, vitals = player.vitals + ("mp" to com.morgana.domain.stats.VitalState(500, 500))))
        val bookSlot = sim.players.getValue(charId).inventory.slots.indexOfFirst { it?.itemId == 51 }
        sim.useInventorySlot(charId, bookSlot)
        assertTrue(7 in sim.players.getValue(charId).knownSpellIds, "Using Volley Book should teach Volley")

        // Spawn 12 pheasants in range of (20, 20) on map 2
        val pheasants = (0 until 12).map { i ->
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "test-pheasant-$i",
                spawnSlotIndex = i,
                npcId = 20,
                mapId = 5,
                tileX = 22 + (i % 4),
                tileY = 20 + (i / 4),
                homeX = 22,
                homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(50000, 50000))
            )
        }
        sim.mapNpcs[5] = pheasants.toMutableList()

        // Cast Volley with no explicit target (hotbar cast)
        sim.playerCastSpell(charId, 7, null, 0, 0)
        val damagedCount = sim.mapNpcs.getValue(5).count { (it.vitals["hp"]?.cur ?: 50000) < 50000 }
        assertEquals(5, damagedCount, "Volley should auto-target and hit at most 5 enemies")

        // Cast enough times to level up Volley from Lv 1 -> Lv 2 (castsPerLevel = 80)
        repeat(79) {
            sim.players[charId] = sim.players.getValue(charId).copy(spellCooldowns = emptyMap())
            sim.playerCastSpell(charId, 7, null, 0, 0)
        }
        assertEquals(2, sim.players.getValue(charId).spellLevel(7), "Volley should level up to Lv 2 after 80 hits")
    }

    @Test
    fun `archer rapid fire auto-targets at range and fires multiple shots`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val s = join(c, "archer_rf", 3) // Ranger
        val sim = c.simulation
        val charId = s.characterId!!
        val player = sim.players.getValue(charId)
        val archerPos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.DOWN)
        sim.spawnOrUpdatePlayer(player.copy(level = 30, knownSpellIds = player.knownSpellIds + 8, position = archerPos, variables = player.variables + ("promotion" to 1)))

        // Spawn two pheasants 4 tiles away: first has low HP so Hail of Arrows kills it and retargets the second
        sim.mapNpcs[5] = mutableListOf(
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "rf-pheasant-1",
                spawnSlotIndex = 0,
                npcId = 20,
                mapId = 5,
                tileX = 24,
                tileY = 20,
                homeX = 24,
                homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(15, 500))
            ),
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "rf-pheasant-2",
                spawnSlotIndex = 1,
                npcId = 20,
                mapId = 5,
                tileX = 25,
                tileY = 20,
                homeX = 25,
                homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(500, 500))
            )
        )

        val events = sim.playerCastSpell(charId, 8, null, 0, 0)
        val hits = events.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
            .filter { it.category == "DAMAGE" }
        assertEquals(2, hits.size, "Hail of Arrows at Lv 1 should fire 2 shots at enemies in range")
        assertTrue(!sim.mapNpcs.getValue(5)[0].isAlive, "First pheasant should be killed by Hail of Arrows")
        assertTrue((sim.mapNpcs.getValue(5)[1].vitals["hp"]?.cur ?: 500) < 500, "Remaining shot should retarget second pheasant")

        // At max level (Lv 9), Hail of Arrows fires 4 shots
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(charId).copy(
                spellLevels = mapOf(8 to 9),
                spellCooldowns = emptyMap()
            )
        )
        val maxEvents = sim.playerCastSpell(charId, 8, null, 0, 0)
        val maxHits = maxEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
            .filter { it.category == "DAMAGE" }
        assertEquals(4, maxHits.size, "Hail of Arrows at max level should fire 4 shots")
    }

    @Test
    fun `every class skill is restricted to the classes that own it`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        // Ascendant Aura (19) is the one skill any class may use, and 36 to 38 are boss-only ground attacks nobody can learn; everything else belongs to some classes.
        sim.spells.values.filter { it.id != 19 && it.id !in 36..38 }.forEach { assertTrue(it.classReqIds.isNotEmpty(), "${it.name} has no class restriction") }
        sim.classes.values.forEach { cls ->
            cls.startingSpellIds.forEach { id ->
                assertTrue(cls.id in sim.spells.getValue(id).classReqIds, "${cls.name} starts with ${sim.spells.getValue(id).name} but may not cast it")
            }
        }
        assertEquals(setOf(5), sim.spells.getValue(10).classReqIds, "Cinder Bolt is Emberweaver only")
        assertEquals(setOf(3), sim.spells.getValue(7).classReqIds, "Volley is Ranger only")
    }

    @Test
    fun `the bestiary pays out upgrade materials steadily and gear can drop with sockets`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val monsters = sim.npcs.values.filter { it.id in 20..51 }
        assertEquals(32, monsters.size)
        assertEquals(monsters.size, monsters.map { it.name }.toSet().size, "every creature has its own name")
        for (m in monsters) {
            val starshards = m.drops.filter { it.itemId == 7 }
            val fragments = m.drops.filter { it.itemId == 15 }
            assertTrue(starshards.isNotEmpty() && fragments.isNotEmpty(), "${m.name} drops Starshards and Pearl Fragments")
            if (m.title.isNotBlank()) {
                assertTrue(starshards.any { it.chanceBasisPoints == 10_000 && it.minAmount >= 2 }, "${m.name} always drops 2 or more Starshards")
                assertTrue(fragments.any { it.chanceBasisPoints == 10_000 && it.minAmount >= 4 }, "${m.name} always drops 4 or more Pearl Fragments")
            } else {
                assertTrue(fragments.all { it.chanceBasisPoints in 100..900 }, "${m.name}'s fragment chance follows its level")
            }
        }
        // Pearl Fragments bind into a Wyrm Pearl: 8 fragments and 500 Marks.
        val bind = sim.recipes.getValue(11)
        assertEquals(setOf(15 to 8, 1 to 500), bind.ingredients.map { it.itemId to it.amount }.toSet())
        assertEquals(8, bind.outputItemId)
        // Dropped gear: the better the rarity, the likelier the socket; the game allows two sockets in all.
        val chances = sim.game.definition.loot!!.rarities.map { it.socketChanceBasisPoints }
        assertEquals(chances.sorted(), chances, "socket chances rise with rarity")
        assertTrue(chances.first() > 0 && chances.last() <= 2_000, "a small chance even for the best gear: $chances")
        assertEquals(2, sim.game.definition.gearUpgrade!!.maxSockets)
    }

    @Test
    fun `each class has its role in spells and the control spells land on enemies`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        fun kit(classId: Int) = sim.spells.values.filter { classId in it.classReqIds }.map { it.id }.toSet()
        // The kits: seven for the fighters and casters, the original five for the archer.
        assertEquals(setOf(1, 2, 3, 4, 21, 22, 23, 40, 41, 42), kit(1), "Duelist: strikes, slow, bleed, area, burst, the XP skill and Ironclad")
        assertEquals(setOf(1, 5, 6, 20, 24, 25, 26, 33, 43, 44), kit(2), "Bulwark: strike, shield, stun, party buff, area slow, taunt, XP skill, reflect")
        assertEquals(setOf(7, 8, 9, 27, 28, 39, 45, 46), kit(3), "Ranger keeps Volley, Hail of Arrows and Skyleap and adds a snare, an area shower and Accuracy")
        assertEquals(setOf(14, 15, 16, 17, 18, 31, 32, 34, 35, 47, 48), kit(4), "Tidecaller: heals, group heal, cleanse, haste, revive, poison, barrier, heal over time")
        assertEquals(setOf(10, 11, 12, 13, 29, 30, 31, 49, 50), kit(5), "Emberweaver: bolts, areas, slow, burn and a barrier")
        assertEquals(setOf(1), sim.game.definition.dualWield!!.classIds, "only the Duelist dual-wields")
        assertEquals(com.morgana.domain.stats.StatusEffectType.FLY, sim.spells.getValue(9).statusEffectType, "the archer keeps Fly")
        assertEquals(com.morgana.domain.content.SpellTargetType.AOE_TARGET_CENTERED, sim.spells.getValue(7).targetType, "the archer keeps Scatter")
        // Every class has a way to learn each of its spells: a book, or the Ascension Envoy (spells 19 and 20).
        for (sp in sim.spells.values) {
            if (sp.id == 19 || sp.id == 20 || sp.id in 36..38 || sp.id in 41..50) continue // 36 to 38 are boss-only; 41 to 50 come with a promotion
            assertTrue(sim.items.values.any { it.teachSpellId == sp.id }, "${sp.name} has a skill book")
        }

        fun prepare(classId: Int, name: String): String {
            val id = join(c, name, classId).characterId!!
            val p = sim.players.getValue(id)
            sim.spawnOrUpdatePlayer(
                p.copy(
                    level = 60, classId = classId, variables = p.variables + ("promotion" to 2), knownSpellIds = kit(classId).toList(),
                    position = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT),
                    vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1000, 1000), "mp" to com.morgana.domain.stats.VitalState(1000, 1000))
                )
            )
            sim.mapNpcs[5] = (0 until 2).map { i ->
                com.morgana.domain.entity.MapNpcInstance(
                    instanceId = "k-npc-$i", spawnSlotIndex = i, npcId = 20, mapId = 5, tileX = 21 + i, tileY = 20, homeX = 21, homeY = 20,
                    vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(100_000, 100_000))
                )
            }.toMutableList()
            return id
        }
        fun cast(id: String, spellId: Int, target: String? = null) {
            sim.players[id] = sim.players.getValue(id).copy(spellCooldowns = emptyMap())
            sim.playerCastSpell(id, spellId, target, 0, 0)
        }
        fun hasEffect(type: com.morgana.domain.stats.StatusEffectType) = sim.mapNpcs.getValue(5).any { n -> n.statusEffects.any { it.type == type } }

        val duelist = prepare(1, "kit_duelist")
        cast(duelist, 21, "k-npc-0")
        assertTrue(hasEffect(com.morgana.domain.stats.StatusEffectType.SLOW), "Hamstring slows its target")
        cast(duelist, 22, "k-npc-0")
        assertTrue(hasEffect(com.morgana.domain.stats.StatusEffectType.DOT_BLEED), "Rend makes its target bleed")

        val bulwark = prepare(2, "kit_bulwark")
        cast(bulwark, 24, "k-npc-0")
        assertTrue(hasEffect(com.morgana.domain.stats.StatusEffectType.STUN), "Shield Bash stuns its target")
        cast(bulwark, 33)
        assertTrue(sim.mapNpcs.getValue(5).all { n -> n.statusEffects.any { it.type == com.morgana.domain.stats.StatusEffectType.TAUNT && it.sourceEntityId == bulwark } }, "Taunting Roar marks every monster nearby with the Bulwark as taunter")
        cast(bulwark, 6)
        assertTrue(sim.players.getValue(bulwark).statusEffects.any { it.type == com.morgana.domain.stats.StatusEffectType.SHIELD }, "Aegis Ward shields the caster")

        val mage = prepare(5, "kit_mage")
        cast(mage, 30, "k-npc-0")
        assertTrue(hasEffect(com.morgana.domain.stats.StatusEffectType.DOT_BURN), "Scorch sets its target alight")
        cast(mage, 29)
        assertTrue(sim.mapNpcs.getValue(5).count { n -> n.statusEffects.any { it.type == com.morgana.domain.stats.StatusEffectType.SLOW } } >= 1, "Frostbind slows the area")

        val healer = prepare(4, "kit_healer")
        val hurt = sim.players.getValue(healer)
        sim.players[healer] = hurt.copy(vitals = hurt.vitals + ("hp" to com.morgana.domain.stats.VitalState(100, 1000)))
        cast(healer, 32)
        assertTrue(sim.players.getValue(healer).statusEffects.any { it.type == com.morgana.domain.stats.StatusEffectType.HOT_REGEN }, "Renew starts a heal over time")
        repeat(60) { sim.stepTick() }
        assertTrue(sim.players.getValue(healer).vitals.getValue("hp").cur > 100, "the heal over time heals")
        // Cleanse: a poisoned healer is cleansed (with no named target the spell finds the worst afflicted ally, here the caster).
        val sick = sim.players.getValue(healer)
        val poison = com.morgana.domain.stats.ActiveStatusEffect("x-poison", com.morgana.domain.stats.StatusEffectType.DOT_POISON, "x", magnitude = 1, remainingTicks = 1000)
        sim.players[healer] = sick.copy(statusEffects = sick.statusEffects + poison)
        cast(healer, 34)
        assertTrue(sim.players.getValue(healer).statusEffects.none { it.type.isNegative }, "Purify strips the poison")
        // Group heal: the tide mends the caster (and any party member within five tiles).
        val low = sim.players.getValue(healer)
        sim.players[healer] = low.copy(vitals = low.vitals + ("hp" to com.morgana.domain.stats.VitalState(50, low.vitals.getValue("hp").max)))
        cast(healer, 35)
        assertTrue(sim.players.getValue(healer).vitals.getValue("hp").cur > 50, "Soothing Tide heals")
    }

    @Test
    fun `all trevana realms aoe spells hit nearby enemies without duplicate animations`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val s = join(c, "aoe_caster", 5) // Emberweaver
        val sim = c.simulation
        val charId = s.characterId!!
        val player = sim.players.getValue(charId)
        val pos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
        // Set Lv 40 with plenty of MP so Titan's Might (3), Stormspire (11), Cinder Fall (12), and Blight Cloud (18) can all be cast
        sim.spawnOrUpdatePlayer(
            player.copy(
                level = 40,
                position = pos,
                variables = player.variables + ("promotion" to 2), // Titan's Might needs Journeyman, Cinder Fall needs Expert
                vitals = mapOf(
                    "hp" to com.morgana.domain.stats.VitalState(1000, 1000),
                    "mp" to com.morgana.domain.stats.VitalState(1000, 1000)
                )
            )
        )

        fun spawnCluster(centerX: Int, centerY: Int) {
            sim.mapNpcs[5] = (0 until 3).map { i ->
                com.morgana.domain.entity.MapNpcInstance(
                    instanceId = "aoe-npc-$i",
                    spawnSlotIndex = i,
                    npcId = 20,
                    mapId = 5,
                    tileX = centerX + i,
                    tileY = centerY,
                    homeX = centerX,
                    homeY = centerY,
                    vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(500, 500))
                )
            }.toMutableList()
        }

        for (spellId in listOf(3, 11, 12, 18)) {
            val centerX = if (spellId == 3) 20 else 24
            spawnCluster(centerX, 20)
            // Skills are class-restricted: cast each as a class allowed to use it (Titan's Might is a Vanguard skill, the rest Caster magic).
            val casterClass = sim.spells.getValue(spellId).classReqIds.first()
            val caster = sim.players.getValue(charId)
            // Bladestorm needs a blade in hand; the other spells need no particular weapon.
            val armed = if (spellId == 3) caster.equipment.copy(equipped = caster.equipment.equipped + (com.morgana.domain.item.EquipmentSlot.WEAPON to com.morgana.domain.item.ItemStack(19))) else caster.equipment
            sim.players[charId] = caster.copy(spellCooldowns = emptyMap(), classId = casterClass, equipment = armed)
            val events = sim.playerCastSpell(charId, spellId, null, 0, 0)
            val damaged = sim.mapNpcs.getValue(5).count { (it.vitals["hp"]?.cur ?: 500) < 500 }
            assertEquals(3, damaged, "AoE spell $spellId should damage all 3 clustered enemies")
            val animCount = events.count { it is com.morgana.domain.sim.WorldSimulationEvent.AnimationTriggered }
            assertEquals(3, animCount, "AoE spell $spellId should emit exactly 1 animation per hit enemy")
            if (spellId == 18) {
                assertTrue(
                    sim.mapNpcs.getValue(5).all { npc -> npc.statusEffects.any { it.type.name == "DOT_POISON" } },
                    "Blight Cloud should apply DOT_POISON to hit enemies"
                )
            }
        }
    }

    @Test
    fun `leveling up auto-distributes class stats without unspent stat points`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val s = join(c, "archer_lv", 3) // Ranger: base agi=15, vit=10; growth agi+2, vit+1
        val sim = c.simulation
        val charId = s.characterId!!
        val before = sim.players.getValue(charId)
        assertEquals(1, before.level)
        assertEquals(15, before.attributes["agi"])
        assertEquals(10, before.attributes["vit"])

        val needed = sim.game.xpToNext(1)!!
        val (after, gained) = before.gainExperience(
            sim.game,
            needed,
            statPointsPerLevel = sim.classes.getValue(3).statPointsPerLevel,
            itemCatalog = sim.items,
            autoStatGrowthPerLevel = sim.classes.getValue(3).autoStatGrowthPerLevel
        )
        assertEquals(1, gained)
        assertEquals(2, after.level)
        assertEquals(0, after.unspentStatPoints, "Pre-rebirth level-up should auto-distribute stats")
        assertEquals(17, after.attributes["agi"])
        assertEquals(11, after.attributes["vit"])
    }

    @Test
    fun `two-handed bow unequips off-hand shield and shield unequips two-handed bow`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val s = join(c, "archer_2h", 3) // Ranger starts with Hunter Bow (id 22, twoHanded=true)
        val sim = c.simulation
        val charId = s.characterId!!

        // Give Ranger a 1H sword (20) and Oak Shield (26)
        val p0 = sim.players.getValue(charId)
        val invWithGear = p0.inventory
            .addItem(sim.items.getValue(20), 1).updatedInventory
            .addItem(sim.items.getValue(26), 1).updatedInventory
        sim.spawnOrUpdatePlayer(p0.copy(inventory = invWithGear))

        // Equip 1H sword then Oak Shield -> both Main Hand and Off-Hand occupied
        val swordSlot = sim.players.getValue(charId).inventory.slots.indexOfFirst { it?.itemId == 20 }
        sim.useInventorySlot(charId, swordSlot)
        val shieldSlot = sim.players.getValue(charId).inventory.slots.indexOfFirst { it?.itemId == 26 }
        sim.useInventorySlot(charId, shieldSlot)
        assertEquals(20, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId)
        assertEquals(26, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.SHIELD)?.itemId)

        // Now equip Hunter Bow (id 22, twoHanded = true) -> should unequip Oak Shield too
        val bowSlot = sim.players.getValue(charId).inventory.slots.indexOfFirst { it?.itemId == 22 }
        sim.useInventorySlot(charId, bowSlot)
        assertEquals(22, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId)
        assertEquals(null, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.SHIELD))

        // Re-equip Oak Shield -> should unequip Hunter Bow
        val shieldSlot2 = sim.players.getValue(charId).inventory.slots.indexOfFirst { it?.itemId == 26 }
        sim.useInventorySlot(charId, shieldSlot2)
        assertEquals(null, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON))
        assertEquals(26, sim.players.getValue(charId).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.SHIELD)?.itemId)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `all 5 classes join in Wrenfall and every map is reachable by seams, warps and events`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val martial = setOf(1, 2, 3)
        val casters = setOf(4, 5)
        for (classId in martial) {
            assertEquals(com.morgana.domain.content.AbilityTerminology.SKILLS, c.simulation.classes.getValue(classId).abilityTerminology)
        }
        for (classId in casters) {
            assertEquals(com.morgana.domain.content.AbilityTerminology.SPELLS, c.simulation.classes.getValue(classId).abilityTerminology)
        }
        for (classId in 1..5) {
            val s = join(c, "hero$classId", classId)
            val p = c.simulation.players.getValue(s.characterId!!)
            assertEquals(3, p.position.mapId)
            val cell = c.simulation.maps.getValue(3).getCell(p.position.tileX, p.position.tileY)!!
            assertTrue(cell.attribute !is TileAttribute.Blocked, "Class $classId spawned on blocked tile")
        }

        // Walk the world graph: warps and seamless borders. The Bazaar and the ferry are reached by dialogue.
        fun reach(start: Int): Set<Int> {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val map = c.simulation.maps.getValue(queue.removeFirst())
                val next = mutableSetOf(map.neighbors.northMapId, map.neighbors.southMapId, map.neighbors.eastMapId, map.neighbors.westMapId)
                for (y in 0 until map.height) for (x in 0 until map.width) {
                    val attr = map.getCell(x, y)?.attribute
                    if (attr is TileAttribute.Warp) next += attr.targetMapId
                }
                for (n in next) if (n != 0 && seen.add(n)) queue.add(n)
            }
            return seen
        }
        assertEquals((1..17).toSet() - 2 + 28, reach(3), "from the start village on foot: the towns, the wilds, the mine and the guild hall door, but not the Bazaar or the isles")
        assertEquals((18..25).toSet(), reach(18), "from Skerry Landing: the isle, Hollow Deep, the Undercroft and Gravewatch")

        // Every map edge is closed except at a choke point, and every choke point meets an open tile on the neighbouring map
        for ((mid, map) in c.simulation.maps) {
            var open = 0
            var total = 0
            fun check(x: Int, y: Int, dir: com.morgana.domain.spatial.Direction) {
                total++
                val attr = map.getCell(x, y)?.attribute
                if (attr is TileAttribute.Blocked || attr is TileAttribute.Warp) return
                open++
                val neighbor = map.neighbors.neighborFor(dir)
                assertTrue(neighbor != 0, "Map $mid ($x, $y) edge is open but has no neighbour")
                val other = c.simulation.maps.getValue(neighbor)
                val ox = if (dir == com.morgana.domain.spatial.Direction.LEFT) other.width - 1 else if (dir == com.morgana.domain.spatial.Direction.RIGHT) 0 else x
                val oy = if (dir == com.morgana.domain.spatial.Direction.UP) other.height - 1 else if (dir == com.morgana.domain.spatial.Direction.DOWN) 0 else y
                assertTrue(other.getCell(ox, oy)?.attribute !is TileAttribute.Blocked, "Map $mid ($x, $y) leads to a blocked tile on map $neighbor ($ox, $oy)")
            }
            for (x in 0 until map.width) {
                check(x, 0, com.morgana.domain.spatial.Direction.UP)
                check(x, map.height - 1, com.morgana.domain.spatial.Direction.DOWN)
            }
            for (y in 0 until map.height) {
                check(0, y, com.morgana.domain.spatial.Direction.LEFT)
                check(map.width - 1, y, com.morgana.domain.spatial.Direction.RIGHT)
            }
            assertTrue(open <= total * 0.2, "Map $mid should be closed along most of its edge ($open of $total open)")
        }
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `every map is one connected walkable area holding every spawn, warp and arrival tile`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        // where dialogue events drop a hero (the events name these tiles, the maps do not)
        val eventArrivals = mapOf(1 to (70 to 74), 2 to (30 to 38), 3 to (30 to 33), 6 to (35 to 38), 14 to (40 to 46), 18 to (14 to 33), 27 to (4 to 20))
        for ((mid, map) in sim.maps) {
            fun walkable(x: Int, y: Int): Boolean {
                val cell = map.getCell(x, y) ?: return false
                return cell.attribute is TileAttribute.None || cell.attribute is TileAttribute.Warp
            }
            val start = (0 until map.height).flatMap { y -> (0 until map.width).map { x -> x to y } }.first { (x, y) -> walkable(x, y) }
            val seen = HashSet<Pair<Int, Int>>()
            val stack = ArrayDeque(listOf(start))
            seen += start
            while (stack.isNotEmpty()) {
                val (x, y) = stack.removeLast()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val n = (x + dx) to (y + dy)
                    if (walkable(n.first, n.second) && seen.add(n)) stack.add(n)
                }
            }
            val walkableCount = (0 until map.height).sumOf { y -> (0 until map.width).count { x -> walkable(x, y) } }
            assertEquals(walkableCount, seen.size, "map $mid (${map.name}) has walkable ground cut off from the rest")
            for (spawn in map.npcSpawns) assertTrue((spawn.spawnX to spawn.spawnY) in seen, "map $mid spawn of npc ${spawn.npcId} at ${spawn.spawnX},${spawn.spawnY} is unreachable")
            eventArrivals[mid]?.let { assertTrue(it in seen, "map $mid event arrival $it is not walkable") }
            for (other in sim.maps.values) for (cell in other.cells) {
                val warp = cell.attribute as? TileAttribute.Warp ?: continue
                if (warp.targetMapId == mid) assertTrue((warp.targetX to warp.targetY) in seen, "a warp on map ${other.id} lands on an unwalkable tile of map $mid")
            }
        }
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `rivers have jumpable narrows and the PvP borders have gatehouses with guards`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        val reach = sim.game.definition.jump!!.maxRangeTiles
        for (mid in listOf(7, 10, 16)) {
            val map = sim.maps.getValue(mid)
            fun open(x: Int, y: Int) = map.getCell(x, y)?.attribute is TileAttribute.None
            fun blocked(x: Int, y: Int) = map.getCell(x, y)?.attribute is TileAttribute.Blocked
            // a spot where 1 to 3 blocked tiles lie between two open ones, along a row, well inside the map
            val narrows = (10 until map.height - 10).count { y ->
                (10 until map.width - 10).any { x ->
                    open(x, y) && (1..3).any { gap -> (1..gap).all { blocked(x + it, y) } && open(x + gap + 1, y) && gap + 1 <= reach }
                        && blocked(x + 1, y) && blocked(x + 1, y - 2) && blocked(x + 1, y + 2)
                }
            }
            assertTrue(narrows >= 5, "map $mid should have a river narrow enough to jump (found $narrows rows)")
        }
        for (mid in listOf(15, 16)) {
            val guards = sim.maps.getValue(mid).npcSpawns.count { it.npcId == 12 }
            assertTrue(guards >= 2, "map $mid should have gatehouse guards (found $guards)")
        }
    }

    @Test
    fun `founding a guild works and every packet it sends encodes`(@TempDir tmp: Path) {
        val gameCopy = copyGame(tmp.resolve("game"))
        val c = MorganaWorldCoordinator(
            MorganaDataStore(rootDir = tmp.resolve("data"), gameDir = gameCopy),
            ownerPolicy = OwnerPolicy(tmp) { null },
            packetLimits = PacketLimitSettings(enabled = false)
        )
        val s = join(c, "founder_x", 1)
        c.handleClientPacket(s, ClientPacket.GuildAction(com.morgana.protocol.GuildActionType.CREATE, "Dragons", "DRG"))
        val me = c.simulation.players.getValue(s.characterId!!)
        assertEquals("Founder", me.guildRank)
        assertTrue(c.simulation.guilds.values.any { it.name == "Dragons" })
        repeat(200) { c.stepServerTick() }
        for (p in s.sent) {
            com.morgana.protocol.PacketCodec.decodeServerJson(com.morgana.protocol.PacketCodec.encodeServerJson(p))
            com.morgana.protocol.PacketCodec.decodeServerCbor(com.morgana.protocol.PacketCodec.encodeServerCbor(p))
        }
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the Guildhall is a guild-owned instance entered through the arch in Harrow Cross`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertTrue(sim.modules.isEnabled(com.morgana.domain.sim.OptionalModule.INSTANCING), "guild halls need the instancing module")
        val hall = sim.maps.getValue(28)
        assertTrue(hall.instanced)
        assertEquals(com.morgana.domain.map.InstanceOwner.GUILD, hall.instanceOwner)
        assertEquals(4, hall.npcSpawns.count { it.npcId == 75 }, "four practice dummies in the yard")
        val door = sim.maps.getValue(1).cells.withIndex().first { (_, cell) -> (cell.attribute as? TileAttribute.Warp)?.targetMapId == 28 }.index
        val w = sim.maps.getValue(1).width
        val founderSession = join(c, "hall_founder", 1)
        val founder = founderSession.characterId!!
        val loner = join(c, "hall_loner", 2).characterId!!
        sim.guilds["g1"] = com.morgana.domain.entity.GuildDocument("g1", "Hallkeepers", "HK", founder, mapOf(founder to "Founder"))
        sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(guildId = "g1"))
        fun stepOnto(id: String) {
            sim.spawnOrUpdatePlayer(sim.players.getValue(id).copy(position = com.morgana.domain.spatial.WorldPosition.fromTile(1, door % w, door / w - 1, com.morgana.domain.spatial.Direction.DOWN)))
            sim.movePlayer(id, com.morgana.domain.spatial.Direction.DOWN)
        }
        stepOnto(founder)
        assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(sim.players.getValue(founder).position.mapId), "a guild member steps into their hall")
        stepOnto(loner)
        assertEquals(1, sim.players.getValue(loner).position.mapId, "someone with no guild is turned away at the door")

        // The vault: the Quartermaster (npc 76) stands in the alcove, and a deposit through the packet reaches the guild
        assertEquals(com.morgana.domain.content.NpcBehavior.GUILD_BANKER, sim.npcs.getValue(76).behavior)
        assertEquals("Officer", sim.game.definition.guildBank!!.defaultWithdrawRank)
        val insideId = sim.players.getValue(founder).position.mapId
        val keeper = sim.mapNpcs.getValue(insideId).first { it.npcId == 76 }
        sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(position = com.morgana.domain.spatial.WorldPosition.fromTile(insideId, keeper.tileX + 1, keeper.tileY, com.morgana.domain.spatial.Direction.LEFT)))
        val slot = sim.players.getValue(founder).inventory.slots.indexOfFirst { it != null && !sim.items.getValue(it.itemId).boundOnPickup }
        assertTrue(slot >= 0, "the founder carries something that can be stored")
        val itemId = sim.players.getValue(founder).inventory.getSlot(slot)!!.itemId
        val had = sim.players.getValue(founder).inventory.totalCountOf(itemId)
        c.handleClientPacket(founderSession, ClientPacket.GuildBankDeposit(slot, 1))
        assertEquals(1, sim.guilds.getValue("g1").bank.totalCountOf(itemId), "the guild vault holds the deposit")
        assertEquals(had - 1, sim.players.getValue(founder).inventory.totalCountOf(itemId))
        assertTrue(sim.guilds.getValue("g1").bankLog.single().contains("deposited 1 x"))
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the Grand Hall opens only to the guild that holds the Holdfast, and a member shown out of the guild is shown out of the hall`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val grand = sim.maps.getValue(29)
        assertTrue(grand.instanced)
        assertEquals(com.morgana.domain.map.InstanceOwner.GUILD, grand.instanceOwner)
        assertTrue(grand.width > sim.maps.getValue(28).width && grand.height > sim.maps.getValue(28).height, "the Grand Hall is larger than the Guildhall")
        val inGrand = grand.npcSpawns.map { it.npcId }.toSet()
        assertTrue(inGrand.containsAll(setOf(76, 81, 82, 83)), "the Grand Hall has the vault, the banner, the notice board and the trophy keeper")
        grand.npcSpawns.forEach { spawn ->
            val cell = grand.cells[spawn.spawnY * grand.width + spawn.spawnX]
            assertTrue(cell.attribute !is TileAttribute.Blocked, "npc ${spawn.npcId} stands on open floor")
        }

        val door = sim.maps.getValue(1).cells.withIndex().first { (_, cell) -> (cell.attribute as? TileAttribute.Warp)?.targetMapId == 28 }.index
        val w = sim.maps.getValue(1).width
        val session = join(c, "grand_founder", 1)
        val founder = session.characterId!!
        sim.guilds["g1"] = com.morgana.domain.entity.GuildDocument("g1", "Hallkeepers", "HK", founder, mapOf(founder to "Founder"), motd = "Bring salves")
        sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(guildId = "g1", guildRank = "Founder",
            position = com.morgana.domain.spatial.WorldPosition.fromTile(1, door % w, door / w - 1, com.morgana.domain.spatial.Direction.DOWN)))
        sim.movePlayer(founder, com.morgana.domain.spatial.Direction.DOWN)
        val hallId = sim.players.getValue(founder).position.mapId
        assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(hallId))

        fun talkTo(npcId: Int): com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested {
            val npc = sim.mapNpcs.getValue(sim.players.getValue(founder).position.mapId).first { it.npcId == npcId }
            sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(sim.players.getValue(founder).position.mapId, npc.tileX, npc.tileY + 1, com.morgana.domain.spatial.Direction.UP)))
            return sim.playerInteractFacing(founder)
                .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
        }
        // The notice board and banner read the guild's own details.
        assertTrue("Bring salves" in talkTo(82).dialogueText, "the board shows the guild's message of the day")
        assertTrue("Hallkeepers [HK]" in talkTo(81).dialogueText, "the banner shows the guild's name and tag")

        // Without the Holdfast the steward turns the guild away.
        val refused = talkTo(80)
        sim.selectDialogueChoice(founder, refused.eventId, 0)
        assertEquals(hallId, sim.players.getValue(founder).position.mapId, "a guild that does not hold the Holdfast stays in the Guildhall")

        // Holding it opens the Grand Hall, with the guild's win count on the trophy keeper's wall.
        sim.guilds["g1"] = sim.guilds.getValue("g1").copy(holdsGuildArea = true, warWins = 3)
        val steward = talkTo(80)
        sim.selectDialogueChoice(founder, steward.eventId, 0)
        val grandId = sim.players.getValue(founder).position.mapId
        assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(grandId) && grandId != hallId, "the holder is in the Grand Hall")
        assertEquals(29, sim.instances.getValue(grandId).templateMapId)
        assertTrue("3 times" in talkTo(83).dialogueText, "the trophy keeper counts the wins")

        // A member who is no longer in the guild is shown out of the hall.
        sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(guildId = null, guildRank = ""))
        sim.ejectFromGuildHall(founder)
        assertEquals(1, sim.players.getValue(founder).position.mapId, "shown out to Harrow Cross")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the four Undercroft floors are party instances that scale with the party`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        for (id in 21..24) {
            val floor = sim.maps.getValue(id)
            assertTrue(floor.instanced, "${floor.name} is a private copy")
            assertEquals(com.morgana.domain.map.InstanceOwner.PARTY, floor.instanceOwner)
            val s = floor.scaling!!
            assertEquals(5, s.maxMembers)
            assertTrue(s.levelFollowsMembers)
            assertEquals(com.morgana.domain.entity.NpcScale(160, 115, 110, 0), s.scaleFor(2, 100, 100), "two players: 60% more life, 15% more damage, 10% more XP")
        }
        assertTrue(sim.maps.getValue(25).scaling == null && !sim.maps.getValue(25).instanced, "Gravewatch's world boss stays shared")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the endgame uses boss encounters, difficulty tiers, the arena, threat and the bestiary`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val regent = sim.npcs.getValue(49).encounter!!
        assertEquals(3, regent.phases.size)
        assertEquals(12_096_000L, regent.lockoutTicks, "the Pale Regent saves a party to it for a week")
        assertTrue(regent.phases.all { p -> p.abilities.all { it.spellId in sim.spells } && p.summonNpcIds.all { it in sim.npcs } })
        assertEquals(0L, sim.npcs.getValue(41).encounter!!.lockoutTicks, "the Deepwyrm is a shared world boss: no lockout")
        assertTrue(sim.npcs.getValue(51).encounter!!.phases.size >= 3)
        val regentSpawn = sim.maps.getValue(24).npcSpawns.single { it.npcId == 49 }
        assertEquals(0, regentSpawn.respawnTicks, "it does not come back inside the same run")

        for (id in 21..24) {
            val tiers = sim.maps.getValue(id).scaling!!.tiers
            assertEquals(listOf("Normal", "Heroic", "Mythic"), tiers.map { it.name })
            assertEquals(1, sim.maps.getValue(id).scaling!!.tierFor(2, 105), "a Lv 105 player asking for Mythic gets Heroic")
        }
        val warden = sim.mapNpcs.getValue(20).first { it.npcId == 84 }
        assertTrue(warden.isAlive, "the Delve Warden stands in Hollow Deep by the way down")

        val arena = sim.game.definition.arena!!
        val arenaMap = sim.maps.getValue(arena.mapId)
        assertTrue(arenaMap.instanced)
        assertEquals(com.morgana.domain.map.MapMoral.ARENA, arenaMap.moral)
        for (s in arena.teamASpawns + arena.teamBSpawns) {
            assertTrue(arenaMap.cells[s.y * arenaMap.width + s.x].attribute !is TileAttribute.Blocked, "arena spawn (${s.x}, ${s.y}) is open floor")
        }
        assertTrue(sim.mapNpcs.getValue(1).any { it.npcId == 85 }, "the Arena Master stands in Harrow Cross")
        val master = game.resolve("data/events/event_027.json").toFile().readText()
        assertEquals(6, Regex("\"_type\": \"arena_queue\"").findAll(master).count(), "the Arena Master queues, leaves, shows rating and standings from a menu")

        assertEquals(110, sim.game.definition.combat.npcs.threatSwitchPercent)
        assertTrue(sim.game.definition.bestiary!!.enabled)

        // The Delve Warden sets the tier, and the floor a player opens takes it.
        val session = join(c, "delver", 1)
        val id = session.characterId!!
        sim.spawnOrUpdatePlayer(sim.players.getValue(id).copy(level = 115, position = com.morgana.domain.spatial.WorldPosition.fromTile(20, warden.tileX, warden.tileY + 1, com.morgana.domain.spatial.Direction.UP)))
        val dialogue = sim.playerInteractFacing(id).filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
        sim.selectDialogueChoice(id, dialogue.eventId, 2)
        assertEquals(2, sim.players.getValue(id).variables["dungeon_tier"], "Mythic was chosen")
        sim.applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer(id, 21, 13, 10))
        val floor = sim.instances.getValue(sim.players.getValue(id).position.mapId)
        assertEquals(2, floor.tier, "a Lv 115 player gets the Mythic floor they asked for")
        assertTrue(sim.mapNpcs.getValue(floor.mapId).filter { it.isAlive }.all { (it.scale?.hpPercent ?: 0) >= 220 }, "and its monsters are Mythic strong")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `weekly contracts, the respec sage, the new Fury skills and the endgame consumables are in the world`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        for (id in 24..27) {
            val q = sim.quests.getValue(id)
            assertTrue(q.repeatable)
            assertEquals(12_096_000L, q.cooldownTicks, "${q.name} comes back a week after the hand-in")
        }
        val board = sim.mapNpcs.getValue(1).first { it.npcId == 86 }
        assertTrue(board.isAlive)
        assertTrue(sim.events.getValue(28).pages.single().commands.any { it is com.morgana.domain.event.EventCommand.ShowDialogue })
        val sage = sim.events.getValue(29).pages.single().commands.filterIsInstance<com.morgana.domain.event.EventCommand.ShowDialogue>().single()
        assertTrue(sage.choices.any { c -> c.commands.any { it is com.morgana.domain.event.EventCommand.RespecAttributes } })

        assertEquals(setOf(3), sim.spells.getValue(39).classReqIds.toSet(), "Accuracy is the Ranger's Fury skill")
        assertEquals(100L, sim.spells.getValue(39).costs.getValue("xp_gauge").toLong())
        assertEquals(setOf(1), sim.spells.getValue(40).classReqIds.toSet(), "Ironclad is the Duelist's Fury skill")
        assertEquals(com.morgana.domain.stats.StatusEffectType.SHIELD, sim.spells.getValue(40).statusEffectType)
        assertTrue(sim.shops.getValue(5).entries.any { it.itemId == 111 } && sim.shops.getValue(5).entries.any { it.itemId == 112 }, "both books are sold by the Training Master")

        val market = sim.shops.getValue(8).entries.map { it.itemId }
        assertTrue(market.containsAll(listOf(113, 114, 115, 116, 117)), "the Market sells the higher salves and mana draughts")
        assertTrue(sim.items.getValue(115).restore.getValue("hp") > sim.items.getValue(5).restore.getValue("hp") * 10, "the Ascendant Salve is a real endgame step")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the Pet Handler sells whistles that adopt pets, which follow and are named`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val sold = sim.shops.getValue(12).entries.map { it.itemId }
        assertEquals(listOf(118, 119, 120, 121), sold)
        for (id in sold) {
            val whistle = sim.items.getValue(id)
            val pet = sim.npcs.getValue(whistle.adoptPetNpcId)
            assertEquals(com.morgana.domain.content.NpcBehavior.FRIENDLY_ROAM, pet.behavior)
            assertTrue(pet.pet != null)
        }
        assertTrue(sim.mapNpcs.getValue(1).any { it.npcId == 92 }, "the Pet Handler stands in Harrow Cross")

        val session = join(c, "keeper", 1)
        val id = session.characterId!!
        val whistle = sim.items.getValue(118)
        sim.spawnOrUpdatePlayer(sim.players.getValue(id).let { it.copy(level = 10, inventory = it.inventory.addItem(whistle, 1).updatedInventory) })
        sim.useInventorySlot(id, sim.players.getValue(id).inventory.slots.indexOfFirst { it?.itemId == 118 })
        assertEquals(88, sim.players.getValue(id).activePetNpcId)
        repeat(3) { sim.stepTick() }
        val pet = sim.mapNpcs.values.flatten().single { it.ownerId == id }
        assertEquals(88, pet.npcId)
        sim.executeAdminSlashCommand(id, "/pet name Pip")
        sim.stepTick()
        assertEquals("Pip", sim.mapNpcs.values.flatten().single { it.ownerId == id }.displayName)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `a quest target on another map gets a guide tile toward it, re-sent only when the map or quest progress changes`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val session = join(c, "guided", 1)
        val id = session.characterId!!
        fun guides() = session.sent.filterIsInstance<ServerPacket.QuestGuides>()
        assertTrue(guides().all { it.guides.isEmpty() }, "no quests, no directions")

        // Ember Wisp Purge (quest 7) wants Tunnel Gnashers and Ember Wisps from Ashgate Plain, a few maps from Harrow Cross.
        sim.spawnOrUpdatePlayer(sim.players.getValue(id).copy(quests = mapOf(7 to com.morgana.domain.event.PlayerQuestState(7, com.morgana.domain.event.QuestStatus.IN_PROGRESS, listOf(0, 0)))))
        val before = guides().size
        c.handleClientPacket(session, ClientPacket.MoveInput(com.morgana.domain.spatial.Direction.DOWN, seq = 1))
        val sent = guides().drop(before)
        assertTrue(sent.isNotEmpty(), "a change of quests sends the directions")
        val g = sent.last().guides.single()
        assertEquals(7, g.questId)
        val here = sim.players.getValue(id).position.mapId
        assertEquals(here, g.mapId)
        assertTrue(g.targetMapName in setOf("Ashgate Plain", "Greyspine Pass"), "toward a map that holds the targets, got ${g.targetMapName}")
        val map = sim.maps.getValue(here)
        assertTrue(g.tileX in 0 until map.width && g.tileY in 0 until map.height)
        val again = guides().size
        c.handleClientPacket(session, ClientPacket.MoveInput(com.morgana.domain.spatial.Direction.UP, seq = 2))
        assertEquals(again, guides().size, "walking about on the same map sends nothing new")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `enemy variants boost xp and drops, rare drops trigger sounds and broadcasts, and loot is protected 30s`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertEquals(600, sim.game.definition.combat.lootOwnerProtectionTicks, "Drops should be protected for 30s (600 ticks)")
        assertEquals(setOf("feral", "elite", "swift", "golden"), sim.game.definition.combat.enemyVariants.map { it.id }.toSet())

        val killerSession = join(c, "hunter", 1)
        val otherSession = join(c, "bystander", 2)
        val killerId = killerSession.characterId!!
        val otherId = otherSession.characterId!!

        val pos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
        sim.spawnOrUpdatePlayer(sim.players.getValue(killerId).copy(position = pos))
        sim.spawnOrUpdatePlayer(sim.players.getValue(otherId).copy(position = pos))

        // Kill an Exalted Stubblehen (250% XP) with guaranteed Starshard + Wyrm Pearl drops to verify sounds, global broadcast, and 30s protection
        val basePheasant = sim.npcs.getValue(20)
        sim.npcs[20] = basePheasant.copy(
            drops = listOf(
                com.morgana.domain.content.NpcDropEntry(itemId = 7, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000),
                com.morgana.domain.content.NpcDropEntry(itemId = 8, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000)
            )
        )
        val eliteNpc = com.morgana.domain.entity.MapNpcInstance(
            instanceId = "elite-pheasant-1",
            spawnSlotIndex = 0,
            npcId = 20,
            mapId = 5,
            tileX = 21,
            tileY = 20,
            homeX = 21,
            homeY = 20,
            variantId = "elite",
            vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1, 200))
        )
        sim.mapNpcs[5] = mutableListOf(eliteNpc)

        var killEvents = emptyList<com.morgana.domain.sim.WorldSimulationEvent>()
        for (attempt in 0 until 10) {
            killEvents = sim.playerAttack(killerId)
            if (!sim.mapNpcs.getValue(5)[0].isAlive) break
            repeat(20) { sim.stepTick() }
        }
        assertTrue(!sim.mapNpcs.getValue(5)[0].isAlive, "Exalted pheasant should be slain")
        val sounds = killEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.SoundPlayed>().map { it.soundId }.toSet()
        assertTrue("starshard_drop" in sounds, "Starshard drop should emit starshard_drop sound (got $sounds)")
        assertTrue("wyrm_pearl_drop" in sounds, "Wyrm Pearl drop should emit wyrm_pearl_drop sound (got $sounds)")

        val broadcasts = killEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>()
            .filter { it.channel == "BROADCAST" }
        assertEquals(1, broadcasts.size, "Wyrm Pearl drop should emit one server-wide BROADCAST")
        assertTrue("Wyrm Pearl" in broadcasts[0].message && "Ashgate Causeway" in broadcasts[0].message)

        // Base Stubblehen (Lv 1) XP = 24, Exalted (+4 levels -> Lv 5, 250% XP) -> 60 base XP, RED tier (+4 levels -> 150%) -> 90 XP, plus the 25% catch-up bonus under Lv 80 -> 112 XP. Lv 1->2 needs 24,600 since the 2026-11-22 XP table (it was 30), so the hunter stays at Lv 1 with the 112 XP
        val killerAfter = sim.players.getValue(killerId)
        assertEquals(1, killerAfter.level, "112 XP is far short of the XP for Lv 2")
        assertEquals(112L, killerAfter.xp, "the whole 112 XP is banked towards Lv 2")

        val xpFloating = killEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
            .filter { it.category == "XP_GAIN" }
        assertEquals(listOf(killerId), xpFloating.map { it.targetCharacterId }, "XP_GAIN floating text must target only the killer when not in a party")
        assertEquals(0L, sim.players.getValue(otherId).xp, "Bystander outside party must not gain XP")

        // Bystander cannot pick up killer's drops before 30s (600 ticks)
        val groundBefore = sim.mapGroundItems.getValue(5).size
        assertTrue(groundBefore >= 2)
        sim.pickupGroundItem(otherId)
        assertEquals(groundBefore, sim.mapGroundItems.getValue(5).size, "Another player must not pick up protected loot before 30s")

        // Advance to tick +600 (30 seconds) -> bystander can now pick up
        repeat(600) { sim.stepTick() }
        sim.pickupGroundItem(otherId)
        assertEquals(groundBefore - 1, sim.mapGroundItems.getValue(5).size, "Loot should be pickable by others after 30s")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `General offers Stubblehen Hunt with a choice and turns it in when objectives are complete`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "quester", 1).characterId!!
        assertTrue(sim.players.getValue(cid).quests.isEmpty(), "New player should start with no accepted quests")

        val generalSlot = sim.mapNpcs.getValue(3).first { it.npcId == 4 }
        val standPos = com.morgana.domain.spatial.WorldPosition.fromTile(
            3,
            generalSlot.tileX,
            generalSlot.tileY + 1,
            com.morgana.domain.spatial.Direction.UP
        )
        sim.spawnOrUpdatePlayer(sim.players.getValue(cid).copy(position = standPos))

        val offerEvents = sim.playerInteractFacing(cid)
        assertTrue(sim.players.getValue(cid).quests.isEmpty(), "Talking to General must offer a choice, not auto-accept all quests")
        val dialogue = offerEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        assertEquals(listOf("Accept Quest", "Not now."), dialogue.choiceLabels)

        // The first-hour chain comes first: Rounds of the Crossing (13, talk to three NPCs), then The Road to Harrow Cross (14).
        sim.selectDialogueChoice(cid, dialogue.eventId, 0)
        assertEquals(setOf(13), sim.players.getValue(cid).quests.keys, "Accepting should start only Rounds of the Crossing (quest 13)")
        val talk = sim.quests.getValue(13).objectives
        assertTrue(talk.all { it.type == com.morgana.domain.event.QuestObjectiveType.TALK_TO_NPC }, "Quest 13 is talking, not killing")

        fun finish(questId: Int, counts: List<Int>) {
            val p = sim.players.getValue(cid)
            sim.players[cid] = p.copy(quests = p.quests + (questId to com.morgana.domain.event.PlayerQuestState(questId = questId, objectiveCounts = counts)))
            val events = sim.playerInteractFacing(cid)
            val turnIn = events.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
            sim.selectDialogueChoice(cid, turnIn.eventId, -1)
            assertEquals(com.morgana.domain.event.QuestStatus.COMPLETED, sim.players.getValue(cid).quests[questId]?.status, "General turns in quest $questId")
        }
        fun accept(questId: Int) {
            val events = sim.playerInteractFacing(cid)
            val offer = events.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
            sim.selectDialogueChoice(cid, offer.eventId, 0)
            assertTrue(questId in sim.players.getValue(cid).quests, "General offers quest $questId")
        }
        finish(13, listOf(1, 1, 1))
        accept(14)
        finish(14, listOf(1))
        // Quest 15 needs level 3, so a level 1 hero goes straight on to Stubblehen Hunt (the rewards so far may have levelled this hero, so start over at level 1).
        sim.players[cid] = sim.players.getValue(cid).copy(level = 1, xp = 0L)
        accept(1)
        finish(1, listOf(6, 4))
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `quests use every kind of objective and are not only kill counts`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        val kinds = sim.quests.values.flatMap { q -> q.objectives.map { it.type } }.toSet()
        assertTrue(
            kinds.containsAll(
                listOf(
                    com.morgana.domain.event.QuestObjectiveType.KILL_NPC, com.morgana.domain.event.QuestObjectiveType.COLLECT_ITEM,
                    com.morgana.domain.event.QuestObjectiveType.TALK_TO_NPC, com.morgana.domain.event.QuestObjectiveType.VISIT_MAP,
                    com.morgana.domain.event.QuestObjectiveType.HARVEST_RESOURCE
                )
            ),
            "Quests are not only kill counts (got $kinds)"
        )
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `town gate scrolls are consumable and town specific, and skills require level to learn and cast`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "gate_tester", 3).characterId!!

        // Give player Ashgate Keep Town Gate (63), Saltmere Town Gate (64), and Volley Book (51, requires Lv 15)
        val p0 = sim.players.getValue(cid)
        val inv = p0.inventory
            .addItem(sim.items.getValue(63), 1).updatedInventory
            .addItem(sim.items.getValue(64), 1).updatedInventory
            .addItem(sim.items.getValue(51), 1).updatedInventory
        val fieldPos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.DOWN)
        sim.spawnOrUpdatePlayer(p0.copy(level = 1, position = fieldPos, inventory = inv))

        // 1. Below required level (Lv 1 < Lv 15): cannot learn Volley Book (item 51), book is not consumed
        val bookSlot = sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 51 }
        val learnFailEvents = sim.useInventorySlot(cid, bookSlot)
        assertTrue(7 !in sim.players.getValue(cid).knownSpellIds, "Skill book must not be learnable below required level")
        assertEquals(51, sim.players.getValue(cid).inventory.getSlot(bookSlot)?.itemId, "Unlearned skill book must remain in inventory")
        assertTrue(
            learnFailEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>().any { "requires Lv 15" in it.message },
            "Using a skill book below its required level should warn the player"
        )

        // 2. Even if a Lv 15 skill is in knownSpellIds on a Lv 1 character, it cannot be cast
        sim.spawnOrUpdatePlayer(sim.players.getValue(cid).copy(knownSpellIds = listOf(7)))
        val castFailEvents = sim.playerCastSpell(cid, 7, null, 0, 0)
        assertTrue(castFailEvents.none { it is com.morgana.domain.sim.WorldSimulationEvent.SpellCast }, "Skill must not be castable below required level")

        // 3. Using Harrow Cross Town Gate (item 6, starting item x2) consumes 1 scroll and warps to Harrow Cross (map 1, 70, 74)
        val tcSlot = sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 6 }
        val tcBefore = sim.players.getValue(cid).inventory.totalCountOf(6)
        sim.useInventorySlot(cid, tcSlot)
        val afterTc = sim.players.getValue(cid)
        assertEquals(1, afterTc.position.mapId)
        assertEquals(70, afterTc.position.tileX)
        assertEquals(74, afterTc.position.tileY)
        assertEquals(tcBefore - 1, afterTc.inventory.totalCountOf(6), "Using Harrow Cross Town Gate should consume 1 scroll")

        // 4. Using Ashgate Keep Town Gate (item 63) consumes it and warps to Ashgate Keep (map 3, 32, 24)
        val pcSlot = sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 63 }
        sim.useInventorySlot(cid, pcSlot)
        val afterPc = sim.players.getValue(cid)
        assertEquals(6, afterPc.position.mapId)
        assertEquals(35, afterPc.position.tileX)
        assertEquals(38, afterPc.position.tileY)
        assertEquals(0, afterPc.inventory.totalCountOf(63))

        // 5. Using Saltmere Town Gate (item 64) consumes it and warps to Saltmere (map 6, 32, 24)
        val dcSlot = sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 64 }
        sim.useInventorySlot(cid, dcSlot)
        val afterDc = sim.players.getValue(cid)
        assertEquals(14, afterDc.position.mapId)
        assertEquals(40, afterDc.position.tileX)
        assertEquals(44, afterDc.position.tileY)
        assertEquals(0, afterDc.inventory.totalCountOf(64))
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `archer starts with iron arrows equipped, bow attacks consume arrows, and stamina vital is active`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "arrow_archer", 3).characterId!!
        val player = sim.players.getValue(cid)

        val ammo = player.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.AMMO)
        assertEquals(69, ammo?.itemId, "Ranger should start with Iron Arrows equipped in AMMO slot")
        assertEquals(200, (ammo?.amount ?: 0) + player.inventory.totalCountOf(69), "Ranger should start with 200 Iron Arrows total")
        assertTrue("stamina" in player.vitals, "Player should have stamina vital initialized")

        val fieldPos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
        sim.spawnOrUpdatePlayer(player.copy(position = fieldPos))
        repeat(20) { sim.stepTick() }
        sim.playerAttack(cid)
        val afterFirst = sim.players.getValue(cid)
        val totalAfterFirst = (afterFirst.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.AMMO)?.amount ?: 0) +
            afterFirst.inventory.totalCountOf(69)
        assertEquals(199, totalAfterFirst, "Basic bow attack should consume 1 Iron Arrow")

        assertEquals(26, sim.quests.size, "16 story quests, 6 daily bounties and 4 weekly contracts")
        assertEquals(53, sim.recipes.size)
        assertEquals(4, sim.npcs.getValue(10).eventId, "Ascension Envoy should be wired to event 4")
        assertEquals(5, sim.npcs.getValue(16).eventId, "Ashgate Captain should be wired to event 5")
        assertEquals(6, sim.npcs.getValue(17).eventId, "Saltmere Captain should be wired to event 6")
        assertEquals(7, sim.npcs.getValue(11).eventId, "Skerry Isle Scout should be wired to event 7")
        assertTrue(sim.npcs.getValue(41).drops.any { it.itemId == 10 }, "Deepwyrm should drop Gold Ingots")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `market conductress and pk jail warden work end-to-end`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "market_hero", 1).characterId!!

        // 1. Talk to Waymaster (npc 6) in Harrow Cross and choose "Enter the Market"
        val conductress = sim.mapNpcs.getValue(1).first { it.npcId == 6 }
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, conductress.tileX, conductress.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        val condDlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        assertEquals("Enter the Bazaar", condDlg.choiceLabels.first())
        sim.selectDialogueChoice(cid, condDlg.eventId, 0)
        assertEquals(2, sim.players.getValue(cid).position.mapId, "Waymaster should warp player to the Bazaar (map 2)")

        // 2. Talk to Market Waymaster (npc 18) to return to Harrow Cross
        val mCond = sim.mapNpcs.getValue(2).first { it.npcId == 18 }
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(2, mCond.tileX, mCond.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        val mCondDlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        sim.selectDialogueChoice(cid, mCondDlg.eventId, 0)
        assertEquals(1, sim.players.getValue(cid).position.mapId, "Market Waymaster should return player to Harrow Cross")

        // 4. Stockade Warden (npc 60) in Stockade (map 26) can pardon and return player to Harrow Cross
        val warden = sim.mapNpcs.getValue(26).first { it.npcId == 60 }
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(26, warden.tileX, warden.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        val jailDlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        sim.selectDialogueChoice(cid, jailDlg.eventId, 1) // Plead for Pardon
        assertEquals(1, sim.players.getValue(cid).position.mapId, "Stockade Warden pardon should return player to Harrow Cross")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `pk points accumulate to red name, town guard aggros and sends killer to pk jail, and pkp decays`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val pkRules = sim.game.definition.combat.pk!!
        assertEquals(10, pkRules.pointsPerKill)
        assertEquals(30, pkRules.redNameThreshold)
        assertEquals(100, pkRules.blackNameThreshold)
        assertEquals(26, pkRules.jailMapId)

        val killerId = join(c, "pk_killer", 1).characterId!!
        val victimId = join(c, "pk_victim", 2).characterId!!

        // Place killer (in PK mode) and low-HP victim next to each other in Starfall Field (map 17, OPEN_PVP)
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(killerId).copy(
                level = 50,
                pkMode = com.morgana.domain.entity.PkMode.PK,
                position = com.morgana.domain.spatial.WorldPosition.fromTile(17, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
            )
        )
        repeat(3) {
            sim.spawnOrUpdatePlayer(
                sim.players.getValue(victimId).copy(
                    isRoguePk = false,
                    pkPoints = 0,
                    position = com.morgana.domain.spatial.WorldPosition.fromTile(17, 21, 20, com.morgana.domain.spatial.Direction.LEFT),
                    vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1, 100), "mp" to com.morgana.domain.stats.VitalState(50, 50))
                )
            )
            repeat(20) { sim.stepTick() }
            sim.playerAttack(killerId)
        }
        val redKiller = sim.players.getValue(killerId)
        assertEquals(30, redKiller.pkPoints, "3 innocent kills should grant 30 PK points (Red Name)")
        assertTrue(redKiller.isRoguePk)

        // Move Red Name killer beside a Town Guard in Harrow Cross (map 1) with 1 HP -> Guard aggros and sends killer to Stockade (map 26)
        val guard = sim.mapNpcs.getValue(1).first { it.npcId == 12 }
        sim.spawnOrUpdatePlayer(
            redKiller.copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, guard.tileX, guard.tileY + 1, com.morgana.domain.spatial.Direction.UP),
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1, 500), "mp" to com.morgana.domain.stats.VitalState(50, 50))
            )
        )
        repeat(40) {
            if (sim.players.getValue(killerId).position.mapId == 26) return@repeat
            sim.stepTick()
        }
        assertEquals(26, sim.players.getValue(killerId).position.mapId, "Town Guard killing a Red Name should send them to Stockade (map 26)")

        // PKP decay: 1 PKP decays every 600 ticks
        val pkpBeforeDecay = sim.players.getValue(killerId).pkPoints
        repeat(pkRules.decayIntervalTicks) { sim.stepTick() }
        assertEquals(pkpBeforeDecay - 1, sim.players.getValue(killerId).pkPoints, "1 PK point should decay after decayIntervalTicks")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `pk modes filter pvp targeting across Peace, Team, Guild, and PK`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val s1 = join(c, "mode_attacker", 1)
        val s2 = join(c, "mode_defender", 2)
        val aId = s1.characterId!!
        val dId = s2.characterId!!
        sim.mapNpcs[17]?.clear()
        val dMaxHp = sim.players.getValue(dId).vitals["hp"]!!.max

        fun resetPair(
            mode: com.morgana.domain.entity.PkMode,
            aParty: String? = null,
            dParty: String? = null,
            aGuild: String? = null,
            dGuild: String? = null
        ) {
            sim.spawnOrUpdatePlayer(
                sim.players.getValue(aId).copy(
                    pkMode = mode,
                    partyId = aParty,
                    guildId = aGuild,
                    lastAttackTick = 0L,
                    position = com.morgana.domain.spatial.WorldPosition.fromTile(17, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
                )
            )
            sim.spawnOrUpdatePlayer(
                sim.players.getValue(dId).copy(
                    partyId = dParty,
                    guildId = dGuild,
                    position = com.morgana.domain.spatial.WorldPosition.fromTile(17, 21, 20, com.morgana.domain.spatial.Direction.LEFT),
                    vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(dMaxHp, dMaxHp), "mp" to com.morgana.domain.stats.VitalState(50, 50))
                )
            )
            repeat(20) { sim.stepTick() }
        }

        // 1. Default PEACE mode cannot damage another player
        assertEquals(com.morgana.domain.entity.PkMode.PEACE, sim.players.getValue(aId).pkMode)
        resetPair(com.morgana.domain.entity.PkMode.PEACE)
        val peaceEvents = sim.playerAttack(aId)
        assertEquals(dMaxHp, sim.players.getValue(dId).vitals["hp"]?.cur, "PEACE mode must not damage other players")
        assertTrue(
            peaceEvents.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
                .any { it.text == "Peace Mode" }
        )

        // 2. TEAM mode protects party mates, allows attacking outsiders
        resetPair(com.morgana.domain.entity.PkMode.TEAM, aParty = "p1", dParty = "p1")
        sim.playerAttack(aId)
        assertEquals(dMaxHp, sim.players.getValue(dId).vitals["hp"]?.cur, "TEAM mode must protect party members")

        resetPair(com.morgana.domain.entity.PkMode.TEAM, aParty = "p1", dParty = "p2")
        sim.playerAttack(aId)
        assertTrue((sim.players.getValue(dId).vitals["hp"]?.cur ?: dMaxHp) < dMaxHp, "TEAM mode should hit non-party players")

        // 3. GUILD mode protects guildmates, allows attacking outsiders
        resetPair(com.morgana.domain.entity.PkMode.GUILD, aGuild = "g1", dGuild = "g1")
        sim.playerAttack(aId)
        assertEquals(dMaxHp, sim.players.getValue(dId).vitals["hp"]?.cur, "GUILD mode must protect guildmates")

        resetPair(com.morgana.domain.entity.PkMode.GUILD, aGuild = "g1", dGuild = "g2")
        sim.playerAttack(aId)
        assertTrue((sim.players.getValue(dId).vitals["hp"]?.cur ?: dMaxHp) < dMaxHp, "GUILD mode should hit non-guild players")

        // 4. PK mode via ClientPacket.SetPkMode hits even party/guild mates
        c.handleClientPacket(s1, ClientPacket.SetPkMode(com.morgana.domain.entity.PkMode.PK))
        assertEquals(com.morgana.domain.entity.PkMode.PK, sim.players.getValue(aId).pkMode)
        resetPair(com.morgana.domain.entity.PkMode.PK, aParty = "p1", dParty = "p1", aGuild = "g1", dGuild = "g1")
        sim.playerAttack(aId)
        assertTrue((sim.players.getValue(dId).vitals["hp"]?.cur ?: dMaxHp) < dMaxHp, "PK mode should hit all players")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `jump mechanic leaps over obstacles at no stamina cost and can be disabled via game definition`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val s = join(c, "jumper", 1)
        val cid = s.characterId!!
        val jumpDef = sim.game.definition.jump!!
        assertTrue(jumpDef.enabled)
        assertEquals(4, jumpDef.maxRangeTiles)
        assertEquals(0, jumpDef.staminaCost, "jumping is free")
        assertEquals(setOf(26, 27), jumpDef.disabledMapIds, "no leaping over the Holdfast wall or out of the Stockade")

        // Stand south of the Harrow Cross canal (y = 101..102) and leap across it to y = 103
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, 30, 100, com.morgana.domain.spatial.Direction.DOWN)
            )
        )
        val stmBefore = sim.players.getValue(cid).vitals["stamina"]!!.cur
        c.handleClientPacket(s, ClientPacket.JumpTo(30, 103))
        val afterJump = sim.players.getValue(cid)
        assertEquals(30, afterJump.position.tileX)
        assertEquals(103, afterJump.position.tileY)
        assertEquals(stmBefore, afterJump.vitals["stamina"]!!.cur, "a jump spends no stamina")

        // Jumping onto a blocked canal tile (30, 101) or out of range (30, 90) is ignored
        c.handleClientPacket(s, ClientPacket.JumpTo(30, 101))
        assertEquals(103, sim.players.getValue(cid).position.tileY)
        c.handleClientPacket(s, ClientPacket.JumpTo(30, 90))
        assertEquals(103, sim.players.getValue(cid).position.tileY)

        // Even with no stamina left, jumping still works
        repeat(20) { sim.stepTick() }
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                vitals = sim.players.getValue(cid).vitals + ("stamina" to com.morgana.domain.stats.VitalState(0, 100))
            )
        )
        sim.jumpTo(cid, 30, 100)
        assertEquals(100, sim.players.getValue(cid).position.tileY)
        assertEquals(0, sim.players.getValue(cid).vitals["stamina"]!!.cur)

        // No jumping on the Holdfast, where the wall must hold
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(27, 28, 10, com.morgana.domain.spatial.Direction.RIGHT),
                vitals = sim.players.getValue(cid).vitals + ("stamina" to com.morgana.domain.stats.VitalState(100, 100))
            )
        )
        repeat(20) { sim.stepTick() }
        assertTrue(sim.jumpTo(cid, 32, 10).isEmpty())
        assertEquals(28, sim.players.getValue(cid).position.tileX)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `weapon proficiencies level up on hits, boost damage, and gate weapon skills`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val wpCfg = sim.game.definition.weaponProficiency!!
        assertTrue(wpCfg.enabled)
        assertEquals(20, wpCfg.maxLevel)
        assertEquals(25, wpCfg.hitsPerLevel)
        assertEquals(5, wpCfg.damageBonusPercentPerLevel)

        // Join as Vanguard (starts with Iron Blade equipped, weaponType = "blade")
        val s = join(c, "blademaster", 1)
        val cid = s.characterId!!
        val pos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                level = 30,
                knownSpellIds = sim.players.getValue(cid).knownSpellIds + 2, // Whirling Edge (requires blade/sword Lv 3)
                position = pos
            )
        )

        fun spawnDummy(hp: Int = 5000): com.morgana.domain.entity.MapNpcInstance {
            val npc = com.morgana.domain.entity.MapNpcInstance(
                instanceId = "wp-dummy",
                spawnSlotIndex = 0,
                npcId = 20,
                mapId = 5,
                tileX = 21,
                tileY = 20,
                homeX = 21,
                homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(hp, hp))
            )
            sim.mapNpcs[5] = mutableListOf(npc)
            return npc
        }

        // Whirling Edge requires Blade/Sword proficiency Lv 3; at Lv 1 it is blocked
        spawnDummy()
        val blockedCast = sim.playerCastSpell(cid, 2, null, 0, 0)
        assertTrue(
            blockedCast.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>()
                .any { it.message.contains("proficiency Lv 3") },
            "Whirling Edge should require weapon proficiency Lv 3"
        )

        // Attack 25 times with Iron Blade to level Blade proficiency from Lv 1 -> Lv 2
        repeat(12) { sim.stepTick() }
        val firstAtk = sim.playerAttack(cid)
        val lv1Dmg = firstAtk.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
            .first { it.category == "DAMAGE" || it.category == "CRIT_DAMAGE" }
            .text.filter { it.isDigit() }.toInt()
        assertEquals(1, sim.players.getValue(cid).weaponProficiencyHitsFor("blade"))
        assertEquals(1, sim.players.getValue(cid).weaponProficiencyLevel("blade"))

        var leveledUp = false
        repeat(24) {
            repeat(12) { sim.stepTick() }
            val ev = sim.playerAttack(cid)
            if (ev.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>()
                    .any { it.message.contains("Blade Proficiency leveled up to Lv 2") }
            ) {
                leveledUp = true
            }
        }
        assertTrue(leveledUp, "25 hits should level Blade proficiency to Lv 2")
        assertEquals(2, sim.players.getValue(cid).weaponProficiencyLevel("blade"))
        assertEquals(0, sim.players.getValue(cid).weaponProficiencyHitsFor("blade"))

        // At Lv 20 (+95% damage), damage is measurably higher than Lv 1 (even if Lv 1 crit at 1.5x)
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                weaponProficiencyLevels = mapOf("blade" to 20)
            )
        )
        repeat(12) { sim.stepTick() }
        val lv20Atk = sim.playerAttack(cid)
        val lv20Dmg = lv20Atk.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
            .first { it.category == "DAMAGE" || it.category == "CRIT_DAMAGE" }
            .text.filter { it.isDigit() }.toInt()
        assertTrue(lv20Dmg > lv1Dmg, "Lv 20 proficiency ($lv20Dmg) should deal more damage than Lv 1 ($lv1Dmg)")

        // Now Whirling Edge succeeds at Blade Lv 10 (>= 3) as a directional skillshot
        val okCast = sim.playerCastSpell(cid, 2, null, 21, 20) +
            sim.stepTick() + sim.stepTick()
        assertTrue(
            okCast.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.FloatingTextEmitted>()
                .any { it.category == "DAMAGE" },
            "Whirling Edge should hit when Blade proficiency >= Lv 3"
        )

        // Unequipping Blade blocks Whirling Edge with a weapon type message
        sim.unequipSlot(cid, com.morgana.domain.item.EquipmentSlot.WEAPON)
        repeat(35) { sim.stepTick() }
        val noWeaponCast = sim.playerCastSpell(cid, 2, null, 0, 0)
        assertTrue(
            noWeaponCast.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>()
                .any { it.message.contains("requires a Blade") },
            "Whirling Edge should require a Blade equipped"
        )

        // Bow projectile hit increments Bow proficiency
        val archerSession = join(c, "bowmaster", 3)
        val aid = archerSession.characterId!!
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(aid).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
            )
        )
        sim.mapNpcs[5] = mutableListOf(
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "bow-dummy",
                spawnSlotIndex = 0,
                npcId = 20,
                mapId = 5,
                tileX = 21,
                tileY = 20,
                homeX = 21,
                homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(500, 500))
            )
        )
        sim.playerAttack(aid)
        repeat(2) { sim.stepTick() }
        assertEquals(1, sim.players.getValue(aid).weaponProficiencyHitsFor("bow"))
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `nobility herald converts donated silver into rank and battle power`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "noble_hero", 1).characterId!!
        val herald = sim.mapNpcs.getValue(2).first { it.npcId == 62 }
        val silver = sim.items.getValue(1)
        val before = sim.players.getValue(cid)
        sim.spawnOrUpdatePlayer(
            before.copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(2, herald.tileX, herald.tileY + 1, com.morgana.domain.spatial.Direction.UP),
                inventory = before.inventory.addItem(silver, 9000).updatedInventory,
                variables = before.variables + ("nobility_donated" to 19_500)
            )
        )
        val bpBefore = sim.game.definition.battlePower!!.of(sim.players.getValue(cid), sim.game.definition, sim.items)
        assertEquals(null, sim.game.definition.nobility!!.rankOf(sim.players.getValue(cid)))
        val dlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        sim.selectDialogueChoice(cid, dlg.eventId, 0) // Donate 1,000 Marks
        val after = sim.players.getValue(cid)
        assertEquals(20_500, after.variables["nobility_donated"])
        assertEquals(8000, after.inventory.totalCountOf(1))
        assertEquals("Esquire", sim.game.definition.nobility!!.rankOf(after)?.name)
        assertEquals(bpBefore, sim.game.definition.battlePower!!.of(after, sim.game.definition, sim.items), "nobility is cosmetic and adds 0 Battle Power")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `repair smith restores worn gear for silver and broken gear stops counting`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertTrue(sim.game.definition.durability != null, "The game enables gear durability")
        val cid = join(c, "worn_hero", 1).characterId!!
        val smith = sim.mapNpcs.getValue(1).first { it.npcId == 63 }
        val hero = sim.players.getValue(cid)
        val weaponSlot = com.morgana.domain.item.EquipmentSlot.WEAPON
        val weapon = hero.equipment.getEquipped(weaponSlot)!!
        sim.spawnOrUpdatePlayer(
            hero.copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, smith.tileX, smith.tileY + 1, com.morgana.domain.spatial.Direction.UP),
                equipment = hero.equipment.copy(equipped = hero.equipment.equipped + (weaponSlot to weapon.copy(durability = 0))),
                inventory = hero.inventory.addItem(sim.items.getValue(1), 500).updatedInventory
            )
        )
        assertEquals(null, sim.players.getValue(cid).equipment.getActive(weaponSlot), "a broken weapon does not count")
        val dlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        val silverBefore = sim.players.getValue(cid).inventory.totalCountOf(1)
        sim.selectDialogueChoice(cid, dlg.eventId, 0)
        val after = sim.players.getValue(cid)
        assertEquals(100, after.equipment.getEquipped(weaponSlot)!!.durability)
        assertTrue(after.inventory.totalCountOf(1) < silverBefore, "repairs cost silver")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `ascension envoy ascends a level 60 hero without a reset and the paragon track then fills`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertNull(sim.game.definition.rebirth, "Trevana Realms has no rebirth any more")
        val cid = join(c, "ascended_hero", 2).characterId!!
        val (mapId, envoy) = sim.mapNpcs.entries.flatMap { (m, l) -> l.map { m to it } }.first { it.second.npcId == 10 }
        val hero = sim.players.getValue(cid)
        sim.spawnOrUpdatePlayer(
            hero.copy(
                level = 60,
                position = com.morgana.domain.spatial.WorldPosition.fromTile(mapId, envoy.tileX, envoy.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        val dlg = sim.playerInteractFacing(cid)
            .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
            .single { it.windowType == "DIALOGUE" }
        val ascendIdx = dlg.choiceLabels.indexOf("Ascend")
        assertTrue(ascendIdx >= 0, "the Envoy offers Ascension: ${dlg.choiceLabels}")
        sim.selectDialogueChoice(cid, dlg.eventId, ascendIdx)
        val ascended = sim.players.getValue(cid)
        assertEquals(60, ascended.level, "Ascension keeps the level")
        assertEquals(2, ascended.classId, "and the class")
        assertEquals(1, ascended.variables["ascended"])
        assertTrue(19 in ascended.knownSpellIds, "Ascendant Aura is taught")
        assertTrue(20 in ascended.knownSpellIds, "a Bulwark is taught Reflect")

        // XP at the cap now earns Paragon levels: 13,000,000 for the first (the cap moved from 120 to 60 on 2026-11-22).
        val (after, levels) = ascended.gainExperience(sim.game, 13_500_000L)
        assertEquals(0, levels)
        assertEquals(1, after.variables["paragon"])
        assertEquals(ascended.unspentStatPoints + 1, after.unspentStatPoints)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the Town Portal Scroll takes you to the nearest town and sprint is on`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertEquals(150, sim.game.definition.sprint!!.speedPercent)
        assertTrue(sim.items.getValue(103).townGate)
        assertTrue(sim.shops.values.any { shop -> shop.entries.any { it.itemId == 103 } }, "a shop sells the scroll")
        val cid = join(c, "portal_user", 3).characterId!!
        val p0 = sim.players.getValue(cid)
        val inv = p0.inventory.addItem(sim.items.getValue(103), 3).updatedInventory
        // Ashgate Causeway (5) borders Harrow Cross and Ashgate Plain, which leads to Ashgate Keep.
        sim.spawnOrUpdatePlayer(p0.copy(position = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.DOWN), inventory = inv))
        sim.useInventorySlot(cid, sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 103 })
        val townIds = sim.game.definition.townGate!!.towns.map { it.mapId }
        assertTrue(sim.players.getValue(cid).position.mapId in townIds, "landed in a town")

        // The Stockade and the Holdfast refuse it and keep the scroll.
        for (banned in listOf(26, 27)) {
            val p = sim.players.getValue(cid)
            sim.spawnOrUpdatePlayer(p.copy(position = com.morgana.domain.spatial.WorldPosition.fromTile(banned, 5, 5, com.morgana.domain.spatial.Direction.DOWN)))
            val before = sim.players.getValue(cid).inventory.totalCountOf(103)
            sim.useInventorySlot(cid, sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 103 })
            assertEquals(banned, sim.players.getValue(cid).position.mapId)
            assertEquals(before, sim.players.getValue(cid).inventory.totalCountOf(103))
        }
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `mounts are a gear slot that lengthens the stride and are sold at the market`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        assertTrue(sim.game.definition.equipmentSlots.orEmpty().any { it.id == "MOUNT" }, "the game has a Mount slot")
        val mounts = sim.items.values.filter { it.mountSpeedPercent > 0 }.sortedBy { it.levelReq }
        assertEquals(3, mounts.size)
        assertTrue(mounts.all { it.equipSlot == "MOUNT" })
        assertEquals(mounts.map { it.mountSpeedPercent }, mounts.map { it.mountSpeedPercent }.sorted(), "better mounts are faster")
        assertTrue(mounts.all { m -> sim.shops.getValue(8).entries.any { it.itemId == m.id } }, "the Market sells every mount")
        val stable = sim.npcs.getValue(93)
        assertEquals("Stablemaster", stable.name)
        assertTrue(sim.maps.getValue(1).npcSpawns.any { it.npcId == 93 }, "the Stablemaster stands in Harrow Cross")
        assertTrue(mounts.all { m -> sim.shops.getValue(stable.shopId).entries.any { it.itemId == m.id } }, "the Stablemaster sells every mount")
    }

    @Test
    fun `every gem has a Fine and a Mythic cut made at the anvil from the tier below`(@TempDir tmp: Path) {
        val sim = coordinator(tmp).simulation
        val basic = sim.items.values.filter { it.gem != null && it.levelReq <= 1 }
        assertEquals(6, basic.size)
        for (gem in basic) {
            val fine = sim.items.values.single { it.name == "Fine ${gem.name}" }
            val mythic = sim.items.values.single { it.name == "Mythic ${gem.name}" }
            fun power(i: com.morgana.domain.item.ItemDefinition) = i.gem!!.let { g -> g.attackDamage + g.armorDefense + g.xpPercent + g.attributeBonuses.values.sum() }
            assertTrue(power(gem) < power(fine) && power(fine) < power(mythic), "${gem.name} grows with each cut")
            val toFine = sim.recipes.values.single { it.outputItemId == fine.id }
            val toMythic = sim.recipes.values.single { it.outputItemId == mythic.id }
            assertTrue(toFine.ingredients.any { it.itemId == gem.id && it.amount == 3 }, "a Fine cut takes three of the plain gem")
            assertTrue(toMythic.ingredients.any { it.itemId == fine.id && it.amount == 3 }, "a Mythic cut takes three Fine gems")
            assertTrue(toMythic.ingredients.any { it.itemId == 8 }, "and a Wyrm Pearl")
            assertEquals(com.morgana.domain.content.CraftingStationType.ANVIL, toFine.stationType)
        }
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `bounty clerks post daily quests that come back a day after the hand-in`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val daily = sim.quests.values.filter { it.cooldownTicks in 1..1_728_000L }
        assertEquals(6, daily.size, "two daily bounties for each of the three hub towns")
        assertTrue(daily.all { it.repeatable && it.cooldownTicks == 1_728_000L })
        for ((npcId, mapId) in listOf(77 to 1, 78 to 6, 79 to 14)) {
            assertTrue(sim.maps.getValue(mapId).npcSpawns.any { it.npcId == npcId }, "clerk $npcId stands on map $mapId")
        }

        val cid = join(c, "bounty_hunter", 3).characterId!!
        val (mapId, clerk) = sim.mapNpcs.entries.flatMap { (m, l) -> l.map { m to it } }.first { it.second.npcId == 77 }
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(cid).copy(
                level = 5,
                position = com.morgana.domain.spatial.WorldPosition.fromTile(mapId, clerk.tileX, clerk.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        fun talk() = sim.playerInteractFacing(cid).filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
        val offer = talk()
        assertEquals(listOf("Accept Bounty", "Not now."), offer.choiceLabels)
        sim.selectDialogueChoice(cid, offer.eventId, 0)
        assertEquals(setOf(18), sim.players.getValue(cid).quests.keys)

        // Finish it and hand it in: the other bounty is offered, and this one waits a day.
        val p = sim.players.getValue(cid)
        sim.players[cid] = p.copy(quests = p.quests + (18 to p.quests.getValue(18).copy(objectiveCounts = listOf(15))))
        sim.selectDialogueChoice(cid, talk().eventId, -1)
        assertEquals(com.morgana.domain.event.QuestStatus.COMPLETED, sim.players.getValue(cid).quests.getValue(18).status)
        val next = talk()
        assertTrue("Dove" in next.dialogueText || "dove" in next.dialogueText, "the second bounty is offered: ${next.dialogueText}")

        sim.worldEpochTicks += 1_728_000L
        sim.selectDialogueChoice(cid, next.eventId, 1)
        val again = talk()
        assertTrue("hens" in again.dialogueText, "a day later the first bounty is posted again: ${again.dialogueText}")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `gear master sets gems, raises quality with pity, and upgrades level of worn gear`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "smith_hero", 1).characterId!!
        val master = sim.mapNpcs.getValue(1).first { it.npcId == 64 }
        val hero = sim.players.getValue(cid)
        var inv = hero.inventory
        inv = inv.addItem(sim.items.getValue(8), 6).updatedInventory  // Wyrm Pearls
        inv = inv.addItem(sim.items.getValue(7), 3).updatedInventory  // Starshards
        inv = inv.addItem(sim.items.getValue(11), 1).updatedInventory // Garnet
        sim.spawnOrUpdatePlayer(
            hero.copy(
                level = 30,
                inventory = inv,
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, master.tileX, master.tileY + 1, com.morgana.domain.spatial.Direction.UP)
            )
        )
        val weaponSlot = com.morgana.domain.item.EquipmentSlot.WEAPON
        fun talk(vararg picks: Int) {
            var dlg = sim.playerInteractFacing(cid)
                .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
            for ((n, pick) in picks.withIndex()) {
                val events = sim.selectDialogueChoice(cid, dlg.eventId, pick)
                if (n < picks.size - 1) {
                    dlg = events.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single { it.windowType == "DIALOGUE" }
                }
            }
        }
        val dragonDamageBefore = sim.players.getValue(cid).equipment.rolledAttackDamage(sim.items)

        // Sockets are not sold: there is no menu entry for one. A raise of quality can open one, so give the weapon one directly.
        val worn = sim.players.getValue(cid)
        sim.players[cid] = worn.copy(equipment = worn.equipment.copy(equipped = worn.equipment.equipped +
            (weaponSlot to worn.equipment.getEquipped(weaponSlot)!!.copy(sockets = listOf(0)))))
        talk(0, 0)  // set a gem -> Weapon
        assertEquals(listOf(11), sim.players.getValue(cid).equipment.getEquipped(weaponSlot)!!.sockets)
        assertEquals(dragonDamageBefore + 5, sim.players.getValue(cid).equipment.rolledAttackDamage(sim.items), "Garnet adds 5 damage")

        // Raising quality can fail: a full pity bar (25 stacks) makes the first step certain.
        sim.players[cid] = sim.players.getValue(cid).copy(upgradePityStacks = 25)
        talk(2, 0)  // raise quality -> Weapon
        val rolls = sim.players.getValue(cid).equipment.getEquipped(weaponSlot)!!.rolls
        assertEquals("refined", rolls?.rarity)
        assertEquals(5, sim.players.getValue(cid).inventory.totalCountOf(8), "6 - 1 pearl for the first step")
        assertEquals(0, sim.players.getValue(cid).upgradePityStacks, "the success spends the pity")

        // The Starshard level upgrade never fails: the starter blade (item 19) becomes its next tier (29).
        talk(1, 0)
        val tier = sim.players.getValue(cid).equipment.getEquipped(weaponSlot)!!
        assertEquals(29, tier.itemId)
        assertEquals(0, sim.players.getValue(cid).inventory.totalCountOf(7), "3 Starshards are used")
        assertEquals(1, tier.sockets.count { it == 11 }, "the gem stays in its socket")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `gear master tempers worn gear from +0 to +4 with starshards`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val enh = sim.game.definition.gearUpgrade?.enhancement
        assertTrue(enh != null && enh.tiers.size == 12, "Trevana Realms uses a 12-step material enhancement ladder")
        assertEquals(0, enh!!.dropPlusOnFailFrom, "failed tempers never drop + level")
        val cid = join(c, "temper_hero", 1).characterId!!
        val gm = sim.mapNpcs.getValue(1).first { it.npcId == 64 }
        val hero = sim.players.getValue(cid)
        val shard = sim.items.getValue(7)
        val inv = hero.inventory.addItem(shard, 10).updatedInventory
        sim.spawnOrUpdatePlayer(
            hero.copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(1, gm.tileX, gm.tileY + 1, com.morgana.domain.spatial.Direction.UP),
                inventory = inv
            )
        )
        repeat(4) {
            val dlg = sim.playerInteractFacing(cid)
                .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
                .single { it.windowType == "DIALOGUE" }
            val subDlg = sim.selectDialogueChoice(cid, dlg.eventId, 3) // Temper gear (+1 to +12)
                .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
                .single { it.windowType == "DIALOGUE" }
            sim.selectDialogueChoice(cid, subDlg.eventId, 0) // Weapon
        }
        val plus = sim.players.getValue(cid).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.plus ?: 0
        assertEquals(4, plus, "guaranteed +1..+4 tempering raises weapon to +4 without merging duplicates")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `xp skills spend the xp gauge and every kill recharges it and extends the burst`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "xp_trojan", 1).characterId!! // Vanguard: Gale Rush
        val pos = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)
        val start = sim.players.getValue(cid)
        assertEquals(100, start.vitals.getValue("xp_gauge").max)
        sim.spawnOrUpdatePlayer(start.copy(level = 20, position = pos, knownSpellIds = start.knownSpellIds + 4))

        sim.playerCastSpell(cid, 4, null, 0, 0)
        val cast = sim.players.getValue(cid)
        assertEquals(0, cast.vitals.getValue("xp_gauge").cur, "Gale Rush spends the whole gauge")
        val before = cast.statusEffects.single { it.id == "spell-4" }.remainingTicks

        // A second cast is refused until the gauge refills.
        sim.players[cid] = cast.copy(spellCooldowns = emptyMap())
        sim.playerCastSpell(cid, 4, null, 0, 0)
        assertEquals(0, sim.players.getValue(cid).vitals.getValue("xp_gauge").cur)

        sim.mapNpcs[5] = mutableListOf(
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "xp-pheasant", spawnSlotIndex = 0, npcId = 20, mapId = 5, tileX = 21, tileY = 20,
                homeX = 21, homeY = 20, vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1, 200))
            )
        )
        for (attempt in 0 until 10) {
            sim.playerAttack(cid)
            if (!sim.mapNpcs.getValue(5)[0].isAlive) break
            repeat(20) { sim.stepTick() }
        }
        assertTrue(!sim.mapNpcs.getValue(5)[0].isAlive, "the pheasant is slain")
        val after = sim.players.getValue(cid)
        assertTrue(after.vitals.getValue("xp_gauge").cur >= 5, "a kill charges the gauge")
        assertTrue(
            after.statusEffects.single { it.id == "spell-4" }.remainingTicks > before - 20 * 10,
            "a kill lengthens the running burst by 60 ticks"
        )
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `promotion master walks a hero from intern to dragon and ranks unlock skills and gear`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val cid = join(c, "rank_trojan", 1).characterId!!
        val (mapId, master) = sim.mapNpcs.entries.flatMap { (m, l) -> l.map { m to it } }.first { it.second.npcId == 65 }
        val pos = com.morgana.domain.spatial.WorldPosition.fromTile(mapId, master.tileX, master.tileY + 1, com.morgana.domain.spatial.Direction.UP)
        fun stand(level: Int, meteors: Int = 0, dragonBalls: Int = 0) {
            var hero = sim.players.getValue(cid)
            hero = hero.copy(level = level, position = pos, knownSpellIds = (hero.knownSpellIds + 3).distinct())
            for ((id, n) in listOf(7 to meteors, 8 to dragonBalls)) {
                if (n > 0) hero = hero.copy(inventory = hero.inventory.addItem(sim.items.getValue(id), n).updatedInventory)
            }
            sim.spawnOrUpdatePlayer(hero)
        }
        fun ask(label: String): String {
            val dlg = sim.playerInteractFacing(cid)
                .filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>()
                .single { it.windowType == "DIALOGUE" }
            val idx = dlg.choiceLabels.indexOfFirst { it.startsWith(label) }
            assertTrue(idx >= 0, "the Calling Master offers '$label': ${dlg.choiceLabels}")
            sim.selectDialogueChoice(cid, dlg.eventId, idx)
            return dlg.dialogueText
        }
        fun rank() = sim.game.definition.promotion!!.tierOf(sim.players.getValue(cid))

        assertEquals(0, rank())
        val hercules = sim.playerCastSpell(cid, 3, null, 0, 0)
        assertTrue(hercules.none { it is com.morgana.domain.sim.WorldSimulationEvent.SpellCast }, "Titan's Might needs the Journeyman rank")

        stand(level = 30)
        ask("Become a Journeyman")
        assertEquals(0, rank(), "no Starshards, no promotion")

        stand(level = 29, meteors = 3)
        ask("Become a Journeyman")
        assertEquals(0, rank(), "level 29 is too low")
        assertEquals(3, sim.players.getValue(cid).inventory.totalCountOf(7), "nothing is charged when the level is too low")

        stand(level = 30)
        ask("Become a Journeyman")
        assertEquals(1, rank())
        assertEquals(0, sim.players.getValue(cid).inventory.totalCountOf(7), "3 Starshards were paid")
        assertTrue(sim.players.getValue(cid).unspentStatPoints >= 5)

        stand(level = 60, meteors = 10)
        ask("Become a Expert")
        assertEquals(2, rank())
        assertTrue(41 in sim.players.getValue(cid).knownSpellIds, "the Duelist's Expert promotion teaches Flickering Step")
        assertFalse(43 in sim.players.getValue(cid).knownSpellIds, "and not the Bulwark's Bastion Stance")

        stand(level = 90, dragonBalls = 3)
        ask("Become a Paragon")
        assertEquals(3, rank())
        assertTrue(42 in sim.players.getValue(cid).knownSpellIds, "the Duelist's Paragon promotion teaches Thousand Cuts")
        val promo = sim.game.definition.promotion!!
        assertEquals("Bladesaint", promo.nameOf(3, sim.players.getValue(cid).classId), "each class has its own title")
        for (tier in promo.tiers) assertEquals(setOf(1, 2, 3, 4, 5), tier.classNames.keys, "every class is named at ${tier.name}")
        for (tier in promo.tiers.drop(1)) assertEquals(setOf(1, 2, 3, 4, 5), tier.classGrantSpellIds.keys, "every class gets a skill at ${tier.name}")
        assertTrue(sim.playerInteractFacing(cid).filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.OpenUiRequested>().single().dialogueText.contains("peak"))

        val gated = sim.items.values.filter { it.promotionReq > 0 }
        assertTrue(gated.size >= 10 && gated.all { it.levelReq >= 30 }, "the level 30+ gear needs a rank")
        assertEquals(setOf(3, 8, 12, 17, 23, 28, 35) + (41..50), sim.spells.values.filter { it.promotionReq > 0 }.map { it.id }.toSet())
    }

    @Test
    fun `equipment slots exclude bag garment steed crop fan and tower while keeping necklace`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val slotIds = sim.game.definition.slots.map { it.id }
        assertTrue(slotIds.none { it in setOf("BAG", "GARMENT", "STEED", "CROP", "FAN", "TOWER") }, "removed slots must be absent: $slotIds")
        assertEquals("Necklace", sim.game.definition.slots.first { it.id == "AMULET" }.name)
        assertTrue("katana" !in (sim.game.definition.weaponProficiency?.weaponTypes?.map { it.id } ?: emptyList()), "katana proficiency must be removed")

        val cid = join(c, "slot_warrior", 2).characterId!!
        var hero = sim.players.getValue(cid).copy(level = 30)
        hero = hero.copy(inventory = hero.inventory.addItem(sim.items.getValue(70), 1).updatedInventory)
        sim.spawnOrUpdatePlayer(hero)
        sim.useInventorySlot(cid, sim.players.getValue(cid).inventory.slots.indexOfFirst { it?.itemId == 70 })

        val worn = sim.players.getValue(cid).equipment
        assertEquals(70, worn.getEquipped(com.morgana.domain.item.EquipmentSlot("AMULET"))?.itemId, "AMULET is worn")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `a miner with a pickaxe breaks Gloamstone Crags veins for ore`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertEquals(com.morgana.domain.item.ToolType.PICKAXE, sim.items.getValue(77).toolType)
        assertTrue(77 in sim.shops.getValue(8).entries.map { it.itemId }, "the Market sells pickaxes")
        assertEquals(setOf(1, 2, 3), sim.resources.keys)
        assertTrue(sim.resources.getValue(3).bonusDrops.any { it.itemId == 7 }, "gold veins can hold a Starshard")

        val nodes = sim.maps.getValue(10).cells.withIndex().filter { (_, cell) -> (cell.attribute as? com.morgana.domain.map.TileAttribute.ResourceNode)?.resourceId == 1 }
        assertTrue(nodes.size >= 10, "copper veins are scattered over Gloamstone Crags")
        val width = sim.maps.getValue(10).width
        val (index, _) = nodes.first()
        val hero = join(c, "ape_miner", 1).characterId!!
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(hero).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(10, index % width - 1, index / width, com.morgana.domain.spatial.Direction.RIGHT),
                inventory = sim.players.getValue(hero).inventory.addItem(sim.items.getValue(77), 1).updatedInventory
            )
        )
        repeat(3) { i ->
            if (i > 0) repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { sim.stepTick() }
            sim.playerInteractFacing(hero)
        }
        assertTrue(sim.players.getValue(hero).inventory.totalCountOf(78) >= 1, "three swings break a copper vein")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `stalls open only in the Market and sell goods for Marks`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertEquals(setOf(2), sim.game.definition.stall!!.mapIds)
        val seller = join(c, "stall_seller", 1).characterId!!
        val buyer = join(c, "stall_buyer", 2).characterId!!
        val bagSlot = sim.players.getValue(seller).inventory.slots.indexOfFirst { it != null && sim.items.getValue(it.itemId).price > 0 && !sim.items.getValue(it.itemId).boundOnPickup } + 1
        assertTrue(bagSlot > 0, "the starting bag has something to sell")
        sim.executeAdminSlashCommand(seller, "/stall add $bagSlot 5 1")
        sim.executeAdminSlashCommand(seller, "/stall open")
        assertTrue(!sim.isStalled(seller), "Harrow Cross is not a stall map")

        val market = com.morgana.domain.spatial.WorldPosition.fromTile(2, 20, 30, com.morgana.domain.spatial.Direction.DOWN)
        sim.spawnOrUpdatePlayer(sim.players.getValue(seller).copy(position = market))
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(buyer).copy(
                position = com.morgana.domain.spatial.WorldPosition.fromTile(2, 22, 30, com.morgana.domain.spatial.Direction.DOWN),
                inventory = sim.players.getValue(buyer).inventory.addItem(sim.items.getValue(1), 100).updatedInventory
            )
        )
        sim.executeAdminSlashCommand(seller, "/stall open")
        assertTrue(sim.isStalled(seller))
        val silverBefore = sim.players.getValue(seller).inventory.totalCountOf(1)
        sim.executeAdminSlashCommand(buyer, "/stall buy ${sim.players.getValue(seller).name} 1 1")
        assertEquals(silverBefore + 5, sim.players.getValue(seller).inventory.totalCountOf(1))
    }

    @Test
    fun `Skyleap lifts an archer over walls and Reflect is an ascended warrior skill`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertEquals(com.morgana.domain.stats.StatusEffectType.FLY, sim.spells.getValue(9).statusEffectType)
        val reflect = sim.spells.getValue(20)
        assertEquals(com.morgana.domain.stats.StatusEffectType.REFLECT, reflect.statusEffectType)
        assertEquals(setOf(2), reflect.classReqIds)
        assertEquals(0, reflect.rebirthReq, "Reflect is taught by the Ascension Envoy, not by a rebirth")
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `the Holdfast is a walled arena with a gate and a pole and a guild war crowns its holder`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val war = sim.game.definition.guildWar!!
        assertEquals(27, war.mapId)
        assertEquals("ARENA", sim.maps.getValue(27).moral.name)
        assertEquals("POLE", sim.npcs.getValue(66).guildWarRole)
        assertEquals("GATE", sim.npcs.getValue(67).guildWarRole)
        assertEquals(2, sim.maps.getValue(27).npcSpawns.count { it.npcId == 67 }, "two gates in the wall")
        assertTrue(sim.maps.getValue(1).npcSpawns.any { it.npcId == 68 }, "the Guild Steward stands in Harrow Cross")

        val founder = join(c, "war_founder", 1).characterId!!
        val rival = join(c, "war_rival", 2).characterId!!
        sim.guilds["g1"] = com.morgana.domain.entity.GuildDocument("g1", "Dragons", "DRG", founder, mapOf(founder to "Founder"))
        sim.guilds["g2"] = com.morgana.domain.entity.GuildDocument("g2", "Ashgate", "PHX", rival, mapOf(rival to "Founder"))
        sim.spawnOrUpdatePlayer(sim.players.getValue(founder).copy(guildId = "g1", accessLevel = com.morgana.domain.access.AccessLevel.OWNER))
        sim.spawnOrUpdatePlayer(sim.players.getValue(rival).copy(guildId = "g2"))

        sim.executeAdminSlashCommand(founder, "/guildwar start")
        assertTrue(sim.guildWar.active)
        sim.guildWar.damage["g1"] = 500L
        sim.guildWar.damage["g2"] = 900L
        sim.executeAdminSlashCommand(founder, "/guildwar end")
        assertEquals("g2", sim.guilds.values.firstOrNull { it.holdsGuildArea }?.id)
        assertEquals(5, sim.players.getValue(rival).inventory.totalCountOf(7), "winners get the war prize of 5 Starshards")
    }

    @Test
    fun `a fallen hero is a ghost until a Tidecaller revives them or they release`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        assertTrue(sim.game.definition.ghost?.enabled == true)
        assertEquals(70, sim.spells.getValue(17).revivePercent)
        val fallen = join(c, "ghost_trojan", 1).characterId!!
        val healer = join(c, "ghost_taoist", 4).characterId!!
        val spot = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.DOWN)
        val rank = sim.game.definition.promotion!!.variable
        sim.spawnOrUpdatePlayer(sim.players.getValue(fallen).copy(position = spot))
        sim.spawnOrUpdatePlayer(
            sim.players.getValue(healer).copy(
                level = 40, position = com.morgana.domain.spatial.WorldPosition.fromTile(5, 22, 20, com.morgana.domain.spatial.Direction.LEFT), variables = mapOf(rank to 2), knownSpellIds = listOf(17),
                vitals = sim.players.getValue(healer).vitals + ("mp" to com.morgana.domain.stats.VitalState(500, 500))
            )
        )
        sim.players[fallen] = sim.players.getValue(fallen).let { it.copy(vitals = it.vitals + ("hp" to com.morgana.domain.stats.VitalState(0, it.vitals.getValue("hp").max))) }
        repeat(2) { sim.stepTick() }
        assertTrue(sim.players.getValue(fallen).isGhost)
        assertEquals(20, sim.players.getValue(fallen).position.tileX, "the ghost lies where they fell")

        sim.playerCastSpell(healer, 17, fallen, 0, 0)
        val back = sim.players.getValue(fallen)
        assertTrue(!back.isGhost && back.vitals.getValue("hp").cur == back.vitals.getValue("hp").max * 70 / 100, "Rekindle restores 70%")

        sim.players[fallen] = back.copy(vitals = back.vitals + ("hp" to com.morgana.domain.stats.VitalState(0, back.vitals.getValue("hp").max)))
        repeat(2) { sim.stepTick() }
        assertTrue(sim.players.getValue(fallen).isGhost)
        sim.executeAdminSlashCommand(fallen, "/release")
        assertTrue(!sim.players.getValue(fallen).isGhost && sim.players.getValue(fallen).position.mapId != 0)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `duelists dual-wield with a chance to strike twice and bulwarks keep their shields`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val sim = c.simulation
        val dw = sim.game.definition.dualWield!!
        assertEquals(setOf(1), dw.classIds)
        assertTrue(dw.strikeChancePercent >= 60 && dw.damagePercent >= 75, "the second weapon must be worth more than a shield")
        val slot = com.morgana.domain.item.EquipmentSlot.SHIELD

        val trojan = join(c, "dual_trojan", 1).characterId!!
        val warrior = join(c, "dual_warrior", 2).characterId!!
        for (id in listOf(trojan, warrior)) {
            val hero = sim.players.getValue(id)
            sim.spawnOrUpdatePlayer(hero.copy(level = 20, inventory = hero.inventory.addItem(sim.items.getValue(29), 1).updatedInventory))
        }
        fun useSteelBlade(id: String) = sim.useInventorySlot(id, sim.players.getValue(id).inventory.slots.indexOfFirst { it?.itemId == 29 })

        useSteelBlade(warrior)
        assertEquals(29, sim.players.getValue(warrior).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId, "a warrior just swaps the main hand")
        assertEquals(null, sim.players.getValue(warrior).equipment.getEquipped(slot)?.takeIf { sim.items.getValue(it.itemId).type == com.morgana.domain.item.ItemType.WEAPON })

        assertEquals(19, sim.players.getValue(trojan).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId)
        useSteelBlade(trojan)
        assertEquals(19, sim.players.getValue(trojan).equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId, "the Iron Blade stays in the main hand")
        assertEquals(29, sim.players.getValue(trojan).equipment.getEquipped(slot)?.itemId, "the Steel Blade goes to the off-hand")

        // Over many swings the off-hand weapon adds real damage.
        sim.spawnOrUpdatePlayer(sim.players.getValue(trojan).copy(position = com.morgana.domain.spatial.WorldPosition.fromTile(5, 20, 20, com.morgana.domain.spatial.Direction.RIGHT)))
        sim.mapNpcs[5] = mutableListOf(
            com.morgana.domain.entity.MapNpcInstance(
                instanceId = "dw-dummy", spawnSlotIndex = 0, npcId = 20, mapId = 5, tileX = 21, tileY = 20, homeX = 21, homeY = 20,
                vitals = mapOf("hp" to com.morgana.domain.stats.VitalState(1_000_000, 1_000_000))
            )
        )
        fun damageOver(swings: Int): Int {
            val before = sim.mapNpcs.getValue(5)[0].vitals.getValue("hp").cur
            repeat(swings) {
                repeat(20) { sim.stepTick() }
                sim.playerAttack(trojan)
            }
            return before - sim.mapNpcs.getValue(5)[0].vitals.getValue("hp").cur
        }
        val dual = damageOver(60)
        sim.unequipSlot(trojan, slot)
        val solo = damageOver(60)
        assertTrue(solo > 0 && dual > solo * 1.2, "dual-wielding out-damages one weapon ($dual vs $solo)")
    }

    @Test
    fun `new players get Trevana Realms's own tips for repairs, skills, ghosts and the level milestones`() {
        val def = GameDefinitionLoader.load(game).game.definition
        val tips = com.morgana.domain.onboarding.OnboardingRules.tips(def).associateBy { it.id }
        assertTrue(tips.keys.containsAll(listOf("welcome", "skills", "gear_worn", "gear_broken", "death", "promotion", "ascension", "gear", "stat_points")), tips.keys.toString())
        assertTrue("Repair Smith" in tips.getValue("gear_worn").text)
        assertTrue("Training Master" in tips.getValue("skills").text)
        assertTrue("/release" in tips.getValue("death").text && "Rekindle" in tips.getValue("death").text)
        assertEquals(30, tips.getValue("promotion").level)
        assertEquals(60, tips.getValue("ascension").level)
        // The people the tips send players to exist in the game's own data.
        val npcs = ContentLoader.load(game.resolve("data")).catalog.npcs.values.map { it.name }
        assertTrue("Repair Smith" in npcs && "Training Master" in npcs && "Calling Master" in npcs)
    }
}

/** These tests need the rebuilt world (maps, NPC placement, quests); re-enable them as the map rebuild lands. */
private const val MAP_REBUILD = "Trevana Realms map rebuild: needs the new maps, NPC placement and quests (see games/trevana-realms/MAP_REBUILD.md)"
