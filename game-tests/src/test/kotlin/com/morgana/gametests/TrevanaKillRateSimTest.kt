package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.HotbarSlot
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.ServerConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.math.max

/**
 * A headless kill-rate simulation for Trevana Realms (design draft: `games/trevana-realms/MODERNIZATION.md`, section 3.3).
 *
 * It answers one question with data instead of an assumption: how many kills an hour does a solo hunter get, and how fast does
 * that turn into levels? The economy model assumes 360 (`TrevanaEconomyModelTest.Play.KILLS_PER_HOUR`); this measures it.
 *
 * A bot hunter runs in the real, seeded [WorldSimulation] on the game's own maps, classes, spells and monsters (20 ticks a second,
 * so an hour is 72,000 ticks and a run takes seconds). Each class is set to a chosen level with its attributes spent on its main
 * stats, the best shop gear it may wear, class spells, and potions it drinks when low. The bot walks to the nearest living monster,
 * lines up, and fights with a spell rotation (best damage per cooldown, area spells scored by how many monsters they hit) or its
 * basic attack. Two profiles bracket real play:
 *  - Ideal: sees the whole map, no reaction time. A ceiling.
 *  - Human: sees 14 tiles, pauses a second after each kill (looting, looking) and half a second to pick a target.
 *
 * Known limits: no kiting or dodging, greedy pathing, gear is plain shop gear (no dropped or upgraded items), promotion bonus
 * points and skill levels are not modelled, and no other players share the camps. It is a measuring stick, not a prediction.
 *
 * The full matrix (5 classes x 6 levels x 2 profiles, a few minutes) runs only when `KILL_RATE_MATRIX=1` is set; a quick smoke run
 * is always on. Output: `build/kill-rate/trevana_kill_rate.md`.
 */
class TrevanaKillRateSimTest {
    private val gameDir: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { it.exists() }

    private companion object {
        const val DEATH_PENALTY_TICKS = 1200
        const val TICKS_PER_HOUR = 72_000.0
    }

    private class Profile(val name: String, val sightTiles: Int, val killPauseTicks: Int, val retargetTicks: Int)
    private val ideal = Profile("Ideal", sightTiles = 100_000, killPauseTicks = 0, retargetTicks = 0)
    private val human = Profile("Human", sightTiles = 14, killPauseTicks = 20, retargetTicks = 10)

    private class Run(
        val classId: Int, val className: String, val level: Int, val mapId: Int, val profile: String, val hours: Double,
        val kills: Int, val actions: Int, val deaths: Int, val potions: Int, val endLevel: Int, val xpGained: Long, val xpToNext: Long, val endXp: Long,
        val swingTicks: Long, val walkTicks: Long, val idleTicks: Long
    ) {
        val killsPerHour get() = kills / hours
        val actionsPerKill get() = if (kills > 0) actions.toDouble() / kills else 0.0
        val xpPerHour get() = xpGained / hours
        val minutesPerLevel get() = if (xpPerHour > 0) xpToNext / xpPerHour * 60.0 else Double.NaN
        val killsPerLevel get() = if (xpGained > 0) kills * xpToNext.toDouble() / xpGained else Double.NaN
        val deathsPerHour get() = deaths / hours
        val potionsPerHour get() = potions / hours
        private val total get() = (swingTicks + walkTicks + idleTicks).coerceAtLeast(1)
        val swingPct get() = 100.0 * swingTicks / total
        val walkPct get() = 100.0 * walkTicks / total
    }

    private class World(val game: CompiledGame, val cat: ContentCatalog, val gameDir: Path)

    private val world: World by lazy {
        val dir = gameDir!!
        World(GameDefinitionLoader.load(dir).game, ContentLoader.load(dir.resolve("data")).catalog, dir)
    }

    /** A fresh simulation holding all content but only the one map a run hunts on (so ticking stays cheap). */
    private fun sim(mapId: Int): WorldSimulation {
        val w = world
        val sim = WorldSimulation(SeededRandomSource(20261122L + mapId), game = w.game)
        sim.modules = ServerConfig.load(w.gameDir).moduleFlags()
        val c = w.cat
        c.classes.values.sortedBy { it.id }.forEach { sim.classes[it.id] = it }
        c.items.values.sortedBy { it.id }.forEach { sim.items[it.id] = it }
        c.npcs.values.sortedBy { it.id }.forEach { sim.npcs[it.id] = it }
        c.spells.values.sortedBy { it.id }.forEach { sim.spells[it.id] = it }
        c.shops.values.sortedBy { it.id }.forEach { sim.shops[it.id] = it }
        c.resources.values.sortedBy { it.id }.forEach { sim.resources[it.id] = it }
        c.recipes.values.sortedBy { it.id }.forEach { sim.recipes[it.id] = it }
        c.quests.values.sortedBy { it.id }.forEach { sim.quests[it.id] = it }
        c.events.values.sortedBy { it.id }.forEach { sim.events[it.id] = it }
        c.commonEvents.values.sortedBy { it.id }.forEach { sim.commonEvents[it.id] = it }
        c.animations.values.sortedBy { it.id }.forEach { sim.animations[it.id] = it }
        sim.registerMap(c.maps.getValue(mapId))
        return sim
    }

    /** Promotion tiers (game.json `promotion`): Journeyman 30, Expert 45, Paragon 60 since the cap moved to 60 on 2026-11-22. */
    private fun promotionTier(level: Int) = when { level >= 60 -> 3; level >= 45 -> 2; level >= 30 -> 1; else -> 0 }

    /** How a class spends its stat points (3 per level): fractions of the points for each attribute. */
    private fun allocation(classId: Int): Map<String, Double> = when (classId) {
        1 -> mapOf("str" to 0.6, "vit" to 0.4)
        2 -> mapOf("vit" to 0.55, "str" to 0.45)
        3 -> mapOf("agi" to 0.65, "vit" to 0.35)
        4 -> mapOf("spi" to 0.65, "vit" to 0.35)
        else -> mapOf("spi" to 0.7, "vit" to 0.3)
    }

    private fun weaponFits(classId: Int, i: ItemDefinition): Boolean = when (classId) {
        1 -> i.weaponStyle == WeaponStyle.MELEE && i.weaponType == "blade" && !i.twoHanded
        2 -> i.weaponStyle == WeaponStyle.MELEE && i.weaponType == "sword" && !i.twoHanded
        3 -> i.weaponStyle == WeaponStyle.RANGED_BOW
        else -> i.weaponStyle == WeaponStyle.MAGIC_STAFF
    }

    private fun hero(sim: WorldSimulation, classDef: ClassDefinition, level: Int, x: Int, y: Int): CharacterState {
        val tier = promotionTier(level)
        fun usable(i: ItemDefinition) = i.levelReq <= level && i.promotionReq <= tier && (i.classReqIds.isEmpty() || classDef.id in i.classReqIds)
        val gearTypes = setOf(ItemType.WEAPON, ItemType.ARMOR, ItemType.HELMET, ItemType.BOOTS, ItemType.SHIELD)
        val equipped = mutableMapOf<EquipmentSlot, ItemStack>()
        val gear = sim.items.values.filter { it.type in gearTypes && usable(it) }
        for ((slot, group) in gear.groupBy { it.equipmentSlotOrNull() }) {
            if (slot == null || !sim.game.definition.hasSlot(slot)) continue
            val pool = when (slot) {
                EquipmentSlot.WEAPON -> group.filter { weaponFits(classDef.id, it) }
                EquipmentSlot.SHIELD -> if (classDef.id == 2) group else emptyList()
                else -> group
            }
            val best = pool.maxByOrNull { it.attackDamage + it.armorDefense } ?: continue
            equipped[slot] = ItemStack(itemId = best.id, amount = 1)
        }
        // Quality: a first-playthrough hero rarely has Pearls, so gear is mostly Common and Fine early and Fine and Enchanted later,
        // with an occasional Exalted drop (every fifth piece from level 30). `+n` composition is modelled as one step per 15 levels.
        val rules = sim.game.definition.loot
        val rng = SeededRandomSource(777L + level * 31L + classDef.id)
        val plus = (level / 15).coerceIn(0, ItemStack.MAX_PLUS)
        for ((index, entry) in equipped.entries.toList().withIndex()) {
            val def = sim.items[entry.value.itemId] ?: continue
            if (!ItemRoller.canRoll(def) || rules == null || rules.rarities.isEmpty()) continue
            var q = if (level < 10) index % 2 else 1 + index % 2
            if (level >= 30 && index % 5 == 4) q += 1
            val rarity = rules.rarities[q.coerceIn(0, rules.rarities.size - 1)]
            val rolls = ItemRoller.rollAtRarity(def, def.levelReq.coerceAtLeast(1), rules, rarity, rng)
            equipped[entry.key] = entry.value.copy(rolls = rolls, plus = plus)
        }
        if (classDef.id == 3) {
            val ammoId = sim.items.values.filter { it.type == ItemType.AMMO && usable(it) }.maxByOrNull { it.attackDamage }?.id ?: 0
            if (ammoId > 0) equipped[EquipmentSlot.AMMO] = ItemStack(itemId = ammoId, amount = 999)
        }
        val points = 3 * (level - 1) + (if (tier >= 1) 5 else 0) + (if (tier >= 2) 8 else 0) + (if (tier >= 3) 12 else 0)
        val attrs = sim.game.definition.attributes.associate { a ->
            val base = classDef.baseAttributes[a.id] ?: a.base
            a.id to base + ((allocation(classDef.id)[a.id] ?: 0.0) * points).toInt()
        }
        val spells = sim.spells.values.filter { classDef.id in it.classReqIds && it.levelReq <= level && it.rebirthReq == 0 && it.promotionReq <= tier }.map { it.id }
        return CharacterState(
            id = "bot", accountId = "bot", name = "Bot", classId = classDef.id, level = level,
            attributes = attrs, position = WorldPosition.fromTile(1, x, y, Direction.DOWN),
            equipment = EquipmentLoadout(equipped), inventory = Inventory().grownTo(sim.game.definition.inventorySlots),
            knownSpellIds = spells, hotbar = List(10) { HotbarSlot(it) },
            variables = mapOf("promotion" to tier),
            // Skills level with use (10 x level casts per level, about 360 casts to the cap): assume one skill level per three hero levels
            // since the spell was learnt.
            spellLevels = spells.mapNotNull { id -> sim.spells[id]?.let { sp -> id to (1 + (level - sp.levelReq) / 3).coerceIn(1, sp.maxSpellLevel) } }.toMap(),
            // Proficiency takes about 4,750 hits to cap (25 x L hits a level), roughly the first twenty levels of hunting, so a hero
            // is assumed to have min(level, cap) in the weapon it uses.
            weaponProficiencyLevels = sim.game.definition.weaponProficiency?.let { wp -> wp.weaponTypes.associate { it.id to level.coerceIn(1, wp.maxLevel) } }.orEmpty()
        ).recalculateVitals(sim.game, sim.items, refill = true)
    }

    private fun direction(dx: Int, dy: Int): Direction =
        if (abs(dx) >= abs(dy)) (if (dx > 0) Direction.RIGHT else Direction.LEFT) else (if (dy > 0) Direction.DOWN else Direction.UP)

    private fun isDamage(spell: SpellDefinition) = spell.targetType != SpellTargetType.SELF && spell.targetType != SpellTargetType.WARP && !spell.isHealing

    /** Rough damage of a cast, used to rank the rotation: power x hits, with area spells scored by the monsters they would catch. */
    private fun spellValue(spell: SpellDefinition, hitsInArea: Int): Double {
        val power = if (spell.basePower > 0) spell.basePower.toDouble() else max(20.0, spell.baseDamagePercent / 4.0)
        val perHit = power * max(1, spell.hitCount)
        val targets = if (spell.aoeRadiusTiles > 0) hitsInArea.coerceAtLeast(1) else 1
        return perHit * targets / spell.cooldownTicks.coerceAtLeast(10)
    }

    private fun hunt(
        classId: Int, level: Int, mapId: Int, profile: Profile, hours: Double,
        startXp: Long = 0L, untilLevel: Int = Int.MAX_VALUE, pinned: Boolean = pin
    ): Run {
        val sim = sim(mapId)
        val classDef = sim.classes.getValue(classId)
        val monsters0 = sim.mapNpcs[mapId].orEmpty().filter { (sim.npcs[it.npcId]?.xpReward ?: 0) > 0 }
        assertTrue(monsters0.isNotEmpty(), "map $mapId has no monsters")
        val cx = monsters0.sumOf { it.tileX } / monsters0.size
        val cy = monsters0.sumOf { it.tileY } / monsters0.size
        val start = monsters0.minBy { abs(it.tileX - cx) + abs(it.tileY - cy) }
        val h0 = hero(sim, classDef, level, start.tileX, start.tileY)
        sim.spawnOrUpdatePlayer(h0.copy(xp = startXp, position = WorldPosition.fromTile(mapId, start.tileX, start.tileY, Direction.DOWN)))

        val known = h0.knownSpellIds.mapNotNull { sim.spells[it] }
        val rotation = known.filter { isDamage(it) }
        val healSpell = known.firstOrNull { it.isHealing && it.targetType == SpellTargetType.SELF }
        val healItem = sim.items.values.filter { (it.restore["hp"] ?: 0) > 0 && it.levelReq <= level }.maxByOrNull { it.restore["hp"] ?: 0 }
        val manaItem = sim.items.values.filter { (it.restore["mp"] ?: 0) > 0 && it.levelReq <= level }.maxByOrNull { it.restore["mp"] ?: 0 }
        val ranged = classId in setOf(3, 4, 5)
        val engage = if (classId == 3) 5 else 4

        var kills = 0; var actions = 0; var deaths = 0; var potions = 0
        var swing = 0L; var walk = 0L; var idle = 0L
        val ignored = HashMap<String, Long>()
        var chasing: String? = null; var chaseTicks = 0
        var pause = 0
        var lastPotion = -100L
        var pinnedXp = 0L; var lastXp = 0L
        val totalTicks = (hours * TICKS_PER_HOUR).toLong()
        val life = sim.game.lifeVitalId

        fun potion(item: ItemDefinition?) {
            item ?: return
            var me = sim.players["bot"] ?: return
            var idx = me.inventory.slots.indexOfFirst { it?.itemId == item.id }
            if (idx < 0) { // the hero restocks for free; only the use rate is measured
                me = me.copy(inventory = me.inventory.addItem(item, 30).updatedInventory); sim.players["bot"] = me
                idx = me.inventory.slots.indexOfFirst { it?.itemId == item.id }
            }
            if (idx >= 0) { sim.useInventorySlot("bot", idx); potions++; lastPotion = sim.currentTick }
        }

        for (i in 0 until totalTicks) {
            val me = sim.players["bot"] ?: break
            if (me.level >= untilLevel) break
            val now = sim.currentTick
            if (!me.isAlive(life) || me.isGhost) {
                deaths++
                sim.executeAdminSlashCommand("bot", "/release")
                repeat(DEATH_PENALTY_TICKS) { sim.stepTick(); idle++ }
                val back = sim.players["bot"] ?: me
                sim.players["bot"] = back.copy(position = WorldPosition.fromTile(mapId, start.tileX, start.tileY, Direction.DOWN))
                    .recalculateVitals(sim.game, sim.items, refill = true)
                continue
            }
            if (pause > 0) { pause--; idle++; sim.stepTick(); continue }

            val hpVital = me.vitals["hp"]; val mpVital = me.vitals["mp"]
            if (now - lastPotion >= 20) {
                if (hpVital != null && hpVital.current * 100 < hpVital.max * 40L) potion(healItem)
                else if (mpVital != null && mpVital.max > 0 && ranged && classId != 3 && mpVital.current * 100 < mpVital.max * 25L) potion(manaItem)
            }
            if (classId == 3) { // arrows are refilled for free
                val ammo = me.equipment.getEquipped(EquipmentSlot.AMMO)
                if (ammo != null && ammo.amount < 100) sim.players["bot"] = me.copy(equipment = me.equipment.copy(equipped = me.equipment.equipped + (EquipmentSlot.AMMO to ammo.copy(amount = 999))))
            }

            val px = me.position.tileX; val py = me.position.tileY
            val mobs = sim.mapNpcs[mapId].orEmpty().filter {
                it.isAlive && (sim.npcs[it.npcId]?.xpReward ?: 0) > 0 && (ignored[it.instanceId] ?: 0L) <= now &&
                    max(abs(it.tileX - px), abs(it.tileY - py)) <= profile.sightTiles
            }
            val target: MapNpcInstance? = mobs.minByOrNull { abs(it.tileX - px) + abs(it.tileY - py) }
            if (target == null) {
                // Nothing in sight: head back toward the middle of the monster field.
                val d = abs(start.tileX - px) + abs(start.tileY - py)
                if (d > 2 && profile.sightTiles < 1000) { sim.movePlayer("bot", direction(start.tileX - px, start.tileY - py)); walk++ } else idle++
                sim.stepTick(); continue
            }
            if (chasing != target.instanceId) { chasing = target.instanceId; chaseTicks = 0; pause = profile.retargetTicks } else chaseTicks++
            if (chaseTicks > 200) { ignored[target.instanceId] = now + 1200; chasing = null; continue }

            val dx = target.tileX - px; val dy = target.tileY - py
            val dist = abs(dx) + abs(dy)
            val events = ArrayList<WorldSimulationEvent>()
            var acted = false

            // Self-heal when hurt and a heal spell is ready.
            if (healSpell != null && hpVital != null && hpVital.current * 100 < hpVital.max * 55L && now >= (me.spellCooldowns[healSpell.id] ?: 0L)) {
                val before = me.spellCooldowns[healSpell.id] ?: 0L
                events += sim.playerCastSpell("bot", healSpell.id)
                if ((sim.players["bot"]?.spellCooldowns?.get(healSpell.id) ?: 0L) > before) { actions++; acted = true }
            }

            // Spell rotation: best value first among spells in range that are off cooldown.
            if (!acted) {
                val scored = rotation.filter { sp ->
                    now >= (me.spellCooldowns[sp.id] ?: 0L) && sp.costs.all { (v, c) -> (me.vitals[v]?.current ?: 0) >= c } &&
                        when (sp.targetType) {
                            SpellTargetType.AOE_SELF_CENTERED -> mobs.any { abs(it.tileX - px) + abs(it.tileY - py) <= sp.aoeRadiusTiles + 1 }
                            SpellTargetType.DIRECTIONAL_PROJECTILE -> (dx == 0 || dy == 0) && dist <= sp.rangeTiles
                            else -> dist <= sp.rangeTiles
                        }
                }.map { sp ->
                    val center = if (sp.targetType == SpellTargetType.AOE_SELF_CENTERED) Pair(px, py) else Pair(target.tileX, target.tileY)
                    val inArea = mobs.count { abs(it.tileX - center.first) + abs(it.tileY - center.second) <= sp.aoeRadiusTiles }
                    sp to spellValue(sp, inArea)
                }.sortedByDescending { it.second }
                for ((sp, _) in scored) {
                    val before = me.spellCooldowns[sp.id] ?: 0L
                    if (sp.targetType == SpellTargetType.DIRECTIONAL_PROJECTILE) {
                        val face = direction(dx, dy)
                        if (me.position.direction != face) events += sim.movePlayer("bot", face)
                    }
                    events += sim.playerCastSpell("bot", sp.id, target.instanceId, target.tileX, target.tileY)
                    if ((sim.players["bot"]?.spellCooldowns?.get(sp.id) ?: 0L) > before) { actions++; acted = true; swing++; break }
                }
            }

            if (!acted) {
                val inMelee = dist == 1
                val aligned = dx == 0 || dy == 0
                val canShoot = classId == 3 && aligned && dist <= 6
                if (inMelee || canShoot) {
                    val face = direction(dx, dy)
                    if (me.position.direction != face) events += sim.movePlayer("bot", face)
                    val swung = sim.playerAttack("bot")
                    if (swung.isNotEmpty()) actions++
                    events += swung
                    swing++
                } else {
                    // Walk: melee closes in; ranged classes stop at engage range (the Ranger also lines up for projectiles).
                    val want: Direction? = when {
                        !ranged -> direction(dx, dy)
                        classId == 3 && !aligned ->
                            if (abs(dx) < abs(dy)) (if (dx > 0) Direction.RIGHT else Direction.LEFT) else (if (dy > 0) Direction.DOWN else Direction.UP)
                        dist > engage -> direction(dx, dy)
                        else -> null
                    }
                    if (want != null) {
                        events += sim.movePlayer("bot", want)
                        if (sim.players["bot"]?.position?.tileCoord == me.position.tileCoord && abs(dx) > 0 && abs(dy) > 0) {
                            events += sim.movePlayer("bot", if (abs(dx) >= abs(dy)) (if (dy > 0) Direction.DOWN else Direction.UP) else (if (dx > 0) Direction.RIGHT else Direction.LEFT))
                        }
                        walk++
                    } else idle++
                }
            }
            events += sim.stepTick()
            if (pinned) sim.players["bot"]?.let { p ->
                if (p.level > level) { pinnedXp += (sim.game.xpToNext(level) ?: 0L) - lastXp + p.xp; sim.players["bot"] = p.copy(level = level, xp = 0L, unspentStatPoints = 0); lastXp = 0L }
                else { pinnedXp += p.xp - lastXp; lastXp = p.xp }
            }
            val k = events.count { it is WorldSimulationEvent.NpcKilled && it.killerCharacterId == "bot" }
            if (k > 0) { kills += k; pause = profile.killPauseTicks; chasing = null }
        }

        val end = sim.players["bot"]
        val endLevel = end?.level ?: level
        var xpGained = end?.xp ?: 0L
        for (l in level until endLevel) xpGained += sim.game.xpToNext(l) ?: 0L
        if (pinned) xpGained = pinnedXp
        val usedHours = if (untilLevel < Int.MAX_VALUE) sim.currentTick / TICKS_PER_HOUR else hours
        return Run(
            classId, classDef.name, level, mapId, profile.name, usedHours, kills, actions, deaths, potions, endLevel, xpGained,
            sim.game.xpToNext(level) ?: 1L, end?.xp ?: 0L, swing, walk, idle
        )
    }

    private val defaultSamples = listOf(1 to 4, 10 to 4, 20 to 7, 30 to 10, 40 to 10, 50 to 15)

    /** `KILL_RATE_SAMPLES="1:4,10:4"` (level:map pairs), `KILL_RATE_CLASSES="1,2"`, `KILL_RATE_PROFILES=human|ideal|both`, `KILL_RATE_HOURS=0.25`. */
    private val samples: List<Pair<Int, Int>> = System.getenv("KILL_RATE_SAMPLES")?.split(",")?.map { it.trim().split(":").let { p -> p[0].toInt() to p[1].toInt() } } ?: defaultSamples
    private val classIds: List<Int> = System.getenv("KILL_RATE_CLASSES")?.split(",")?.map { it.trim().toInt() } ?: (1..5).toList()
    private val profiles: List<Profile> = when (System.getenv("KILL_RATE_PROFILES")) { "human" -> listOf(human); "ideal" -> listOf(ideal); else -> listOf(ideal, human) }
    private val runHours: Double = System.getenv("KILL_RATE_HOURS")?.toDouble() ?: 0.25

    /**
     * `KILL_RATE_PIN=1` holds the hero at its starting level for the whole run (level and XP are reset after every tick, the XP
     * earned is still counted). XP from a kill depends on the hero's level against the monster's, so a hero that levels up during
     * the run drifts off the zone and reads low; pinning measures XP an hour at exactly that level, which is what a curve is built from.
     */
    private val pin: Boolean = System.getenv("KILL_RATE_PIN") == "1"

    private fun table(runs: List<Run>) = buildString {
        appendLine("| Class | Lv | Map | Profile | Kills / h | Actions / kill | XP / h | XP min / level | Kills / level | Deaths / h | Potions / h | Swing | Walk |")
        appendLine("|-------|----|-----|---------|-----------|----------------|--------|----------------|---------------|------------|-------------|-------|------|")
        for (r in runs) appendLine(
            "| ${r.className} | ${r.level} | ${r.mapId} | ${r.profile} | ${"%.0f".format(r.killsPerHour)} | ${"%.1f".format(r.actionsPerKill)} | ${"%.0f".format(r.xpPerHour)} | " +
                "${"%.1f".format(r.minutesPerLevel)} | ${"%.0f".format(r.killsPerLevel)} | ${"%.1f".format(r.deathsPerHour)} | ${"%.0f".format(r.potionsPerHour)} | " +
                "${"%.0f".format(r.swingPct)}% | ${"%.0f".format(r.walkPct)}% |"
        )
    }

    /** Hours to climb from [fromLevel] to [toLevel], interpolating minutes per level between the samples. */
    private fun hoursBetween(runs: List<Run>, fromLevel: Int, toLevel: Int): Double {
        val pts = runs.sortedBy { it.level }.filter { !it.minutesPerLevel.isNaN() }
        if (pts.size < 2) return Double.NaN
        var minutes = 0.0
        for (l in fromLevel until toLevel) {
            val lo = pts.lastOrNull { it.level <= l } ?: pts.first()
            val hi = pts.firstOrNull { it.level > l } ?: pts.last()
            val f = if (hi.level == lo.level) 0.0 else ((l - lo.level).toDouble() / (hi.level - lo.level)).coerceIn(0.0, 1.0)
            minutes += lo.minutesPerLevel + (hi.minutesPerLevel - lo.minutesPerLevel) * f
        }
        return minutes / 60.0
    }

    private fun write(text: String) {
        val out = Path.of("build/kill-rate").also { Files.createDirectories(it) }.resolve("trevana_kill_rate.md")
        Files.writeString(out, text)
        println(text)
    }

    /** Zones a hero may hunt in, as level windows (the zones' designed ranges, a few levels early to a few late). */
    private val zoneWindows = mapOf(4 to (1..20), 7 to (8..31), 10 to (20..47), 15 to (36..55), 16 to (46..65), 17 to (46..69))

    /** Every (level, map) pair a hero could sensibly hunt: used to sample XP an hour at each level. */
    private fun allZoneSamples(maxLevel: Int) = (1..maxLevel).flatMap { l -> zoneWindows.filter { l in it.value }.keys.map { l to it } }

    @Test
    fun `progression - each class plays from level 1 to 60, picking the best zone as it goes, and the four time buckets are measured`() {
        assumeTrue(gameDir != null, "Developer game games/trevana-realms is not present in this checkout")
        assumeTrue(System.getenv("KILL_RATE_PROGRESSION") == "1", "set KILL_RATE_PROGRESSION=1 (and optionally KILL_RATE_CLASSES) to play through; it takes minutes per class")
        val boundaries = listOf(30, 45, 55, 60)
        val text = StringBuilder()
        text.appendLine("# Trevana Realms progression to level 60 (Human profile)")
        text.appendLine()
        text.appendLine("| Class | 1 to 30 | 30 to 45 | 45 to 55 | 55 to 60 | Total |")
        text.appendLine("|-------|---------|----------|----------|----------|-------|")
        val detail = StringBuilder()
        for (classId in classIds) {
            var level = 1; var xp = 0L; var hours = 0.0
            val atBoundary = HashMap<Int, Double>()
            var name = ""
            while (level < 60) {
                // Like a player, hunt where the XP an hour is best right now: probe each allowed zone for a few minutes, pinned.
                val maps = zoneWindows.filter { level in it.value }.keys
                val best = maps.maxBy { m -> hunt(classId, level, m, human, 0.05, pinned = true).xpPerHour }
                val target = level + 1
                val r = hunt(classId, level, best, human, 8.0, startXp = xp, untilLevel = target, pinned = false)
                name = r.className
                hours += r.hours; level = r.endLevel; xp = r.endXp
                detail.appendLine("$name level ${target - 1} -> $level on map $best: ${"%.2f".format(r.hours)} h (total ${"%.1f".format(hours)} h)")
                boundaries.filter { it <= level && it !in atBoundary }.forEach { atBoundary[it] = hours }
                if (r.endLevel < target) { text.appendLine("| $name | stalled at level $level after ${"%.1f".format(hours)} h |"); break }
            }
            val h = boundaries.map { atBoundary[it] }
            if (h.all { it != null }) text.appendLine("| $name | ${"%.1f".format(h[0])} | ${"%.1f".format(h[1]!! - h[0]!!)} | ${"%.1f".format(h[2]!! - h[1]!!)} | ${"%.1f".format(h[3]!! - h[2]!!)} | ${"%.1f".format(h[3])} |")
        }
        Files.createDirectories(Path.of("build/kill-rate"))
        text.appendLine()
        text.appendLine("## Level by level")
        text.appendLine()
        text.append(detail)
        Files.writeString(Path.of("build/kill-rate/trevana_progression.md"), text.toString())
        println(text)
    }

    @Test
    @Disabled(MAP_REBUILD)
    fun `smoke - a level 1 Duelist and a level 1 Emberweaver both kill in Wrenfall Wood`() {
        assumeTrue(gameDir != null, "Developer game games/trevana-realms is not present in this checkout")
        assumeTrue(System.getenv("KILL_RATE_MATRIX") != "1", "the full matrix runs instead")
        val runs = listOf(hunt(1, 1, 4, human, 0.1), hunt(5, 1, 4, human, 0.1))
        write("# Trevana Realms kill-rate smoke run\n\n" + table(runs))
        runs.forEach { assertTrue(it.kills > 0, "${it.className} killed nothing") }
    }

    @Test
    fun `matrix - every class at six levels with an ideal and a human hunter`() {
        assumeTrue(gameDir != null, "Developer game games/trevana-realms is not present in this checkout")
        assumeTrue(System.getenv("KILL_RATE_MATRIX") == "1", "set KILL_RATE_MATRIX=1 to run the full matrix")
        val runs = ArrayList<Run>()
        for (classId in classIds) for ((level, map) in samples) for (p in profiles) runs += hunt(classId, level, map, p, runHours)
        val text = buildString {
            appendLine("# Trevana Realms kill-rate simulation (matrix)")
            appendLine()
            appendLine("Each row is 15 simulated minutes (0.25 h) of one hunter. XP min / level is how long the next level would take at that rate; Kills / level likewise.")
            appendLine()
            append(table(runs))
            appendLine()
            val maxSample = samples.maxOf { it.first }
            appendLine("## Class spread (Human profile where present): kills an hour and XP an hour, mean across classes = 100%")
            appendLine()
            appendLine("| Lv | " + classIds.joinToString(" | ") { id -> runs.first { it.classId == id }.className } + " |")
            appendLine("|----|" + classIds.joinToString("|") { "------" } + "|")
            for ((level, _) in samples) {
                val row = classIds.map { id -> runs.firstOrNull { it.classId == id && it.level == level && it.profile == profiles.last().name } }
                val mean = row.filterNotNull().map { it.xpPerHour }.average()
                appendLine("| $level | " + row.joinToString(" | ") { r -> if (r == null) "-" else "${"%.0f".format(100.0 * r.xpPerHour / mean)}% (${"%.0f".format(r.killsPerHour)}/h)" } + " |")
            }
            appendLine()
            appendLine("## Estimated hours (interpolated between samples, up to level $maxSample)")
            appendLine()
            appendLine("| Class | Profile | " + listOf(1 to 30, 30 to 45, 45 to 55, 55 to 60).filter { it.second <= maxSample }.joinToString(" | ") { "${it.first} to ${it.second}" } + " | 1 to $maxSample |")
            appendLine("|-------|---------|" + "------|".repeat(listOf(1 to 30, 30 to 45, 45 to 55, 55 to 60).count { it.second <= maxSample } + 1))
            for (classId in classIds) for (p in profiles) {
                val rs = runs.filter { it.classId == classId && it.profile == p.name }
                val cls = rs.first().className
                val cols = listOf(1 to 30, 30 to 45, 45 to 55, 55 to 60).filter { it.second <= maxSample }.joinToString(" | ") { "%.1f".format(hoursBetween(rs, it.first, it.second)) }
                appendLine("| $cls | ${p.name} | $cols | ${"%.1f".format(hoursBetween(rs, 1, maxSample))} |")
            }
        }
        write(text)
        assertTrue(runs.all { it.kills > 0 }, "some hunter killed nothing: ${runs.filter { it.kills == 0 }.map { "${it.className} L${it.level} ${it.profile}" }}")
    }
}

/** These tests need the rebuilt world (maps, NPC placement, quests); re-enable them as the map rebuild lands. */
private const val MAP_REBUILD = "Trevana Realms map rebuild: needs the new maps, NPC placement and quests (see games/trevana-realms/MAP_REBUILD.md)"
