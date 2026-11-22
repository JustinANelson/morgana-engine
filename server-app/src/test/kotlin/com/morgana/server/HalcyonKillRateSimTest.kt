package com.morgana.server

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.HotbarSlot
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemStack
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.ServerConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.abs

/**
 * A headless kill-rate simulation for Halcyon Reach (design draft: `games/halcyon-reach/MODERNIZATION.md`, section 3.3).
 *
 * It answers one question with data instead of an assumption: how many kills an hour does a solo hunter get? The economy model
 * assumes 360 (`HalcyonEconomyModelTest.Play.KILLS_PER_HOUR`); this measures it. A bot hunter runs in the real [WorldSimulation]
 * on the game's own maps, classes and monsters: it walks to the nearest living monster, faces it, and swings. Time is simulation
 * ticks (20 a second), so an hour is 72,000 ticks and the run is fast and deterministic (seeded).
 *
 * Limits of this first version (all of them make the figure a ceiling for a skilled melee player, not an average):
 *  - Melee basic attacks only (no skills, no ranged, no area spells), so it measures the Duelist-style hunter first.
 *  - Grid-locked stepping at the fastest legal rate; the bot never stops to loot, heal, repair or read the screen.
 *  - Pathing is greedy (it steps toward the target, sliding around blockers), so it can waste steps near walls and water.
 * It writes `build/kill-rate/halcyon_kill_rate.md`.
 */
class HalcyonKillRateSimTest {
    private val gameDir: Path? = listOf(Path.of("games/halcyon-reach"), Path.of("../games/halcyon-reach")).firstOrNull { it.exists() }

    private class Result(
        val label: String, val hours: Double, val kills: Int, val deaths: Int, val levelStart: Int, val levelEnd: Int,
        val swingTicks: Long, val walkTicks: Long, val idleTicks: Long,
        val attacks: Int, val byLevel: Map<Int, LevelStats>
    ) {
        val attacksPerKill get() = if (kills > 0) attacks.toDouble() / kills else 0.0
        val killsPerHour get() = if (hours > 0) kills / hours else 0.0
        val ticks get() = swingTicks + walkTicks + idleTicks
        fun pct(t: Long) = if (ticks > 0) 100.0 * t / ticks else 0.0
    }

    private companion object { const val DEATH_PENALTY_TICKS = 1200 }

    private class LevelStats { var ticks = 0L; var kills = 0; var attacks = 0 }

    private fun newHero(sim: WorldSimulation, classDef: ClassDefinition, mapId: Int, x: Int, y: Int): CharacterState {
        var inv = Inventory().grownTo(sim.game.definition.inventorySlots)
        val equipped = mutableMapOf<EquipmentSlot, ItemStack>()
        for (stack in classDef.startingItems) {
            val itemDef = sim.items[stack.itemId] ?: continue
            val slot = itemDef.equipmentSlotOrNull()
            val canEquip = classDef.autoEquipStartingItems && slot != null && sim.game.definition.hasSlot(slot) && slot !in equipped &&
                (!itemDef.twoHanded || EquipmentSlot.SHIELD !in equipped) &&
                (slot != EquipmentSlot.SHIELD || equipped[EquipmentSlot.WEAPON]?.let { sim.items[it.itemId]?.twoHanded != true } != false)
            if (canEquip) {
                equipped[slot] = stack.copy(amount = 1)
                if (stack.amount > 1) inv = inv.addItem(itemDef, stack.amount - 1).updatedInventory
            } else {
                inv = inv.addItem(itemDef, stack.amount).updatedInventory
            }
        }
        val attrs = sim.game.definition.attributes.associate { a -> a.id to (classDef.baseAttributes[a.id] ?: a.base) }
        return CharacterState(
            id = "bot", accountId = "bot", name = "Bot", classId = classDef.id,
            attributes = attrs,
            position = WorldPosition.fromTile(mapId, x, y, Direction.DOWN),
            inventory = inv, equipment = EquipmentLoadout(equipped),
            knownSpellIds = classDef.startingSpellIds, hotbar = List(10) { HotbarSlot(it) }
        ).recalculateVitals(sim.game, sim.items, refill = true)
    }

    private fun sim(game: Path): WorldSimulation {
        val compiled = GameDefinitionLoader.load(game).game
        val cat = ContentLoader.load(game.resolve("data")).catalog
        val sim = WorldSimulation(SeededRandomSource(20261122L), game = compiled)
        sim.modules = ServerConfig.load(game).moduleFlags()
        cat.classes.values.sortedBy { it.id }.forEach { sim.classes[it.id] = it }
        cat.items.values.sortedBy { it.id }.forEach { sim.items[it.id] = it }
        cat.npcs.values.sortedBy { it.id }.forEach { sim.npcs[it.id] = it }
        cat.spells.values.sortedBy { it.id }.forEach { sim.spells[it.id] = it }
        cat.shops.values.sortedBy { it.id }.forEach { sim.shops[it.id] = it }
        cat.resources.values.sortedBy { it.id }.forEach { sim.resources[it.id] = it }
        cat.recipes.values.sortedBy { it.id }.forEach { sim.recipes[it.id] = it }
        cat.quests.values.sortedBy { it.id }.forEach { sim.quests[it.id] = it }
        cat.events.values.sortedBy { it.id }.forEach { sim.events[it.id] = it }
        cat.commonEvents.values.sortedBy { it.id }.forEach { sim.commonEvents[it.id] = it }
        cat.animations.values.sortedBy { it.id }.forEach { sim.animations[it.id] = it }
        cat.maps.values.sortedBy { it.id }.forEach { sim.registerMap(it) }
        return sim
    }

    private fun direction(dx: Int, dy: Int): Direction =
        if (abs(dx) >= abs(dy)) (if (dx > 0) Direction.RIGHT else Direction.LEFT) else (if (dy > 0) Direction.DOWN else Direction.UP)

    /** One hunter, one map, [hours] of simulated play. */
    private fun hunt(game: Path, label: String, classId: Int, mapId: Int, hours: Double): Result {
        val sim = sim(game)
        val classDef = sim.classes.getValue(classId)
        val spawns = sim.mapNpcs[mapId].orEmpty().filter { sim.npcs[it.npcId]?.behavior == NpcBehavior.ATTACK_ON_SIGHT || sim.npcs[it.npcId]?.xpReward!! > 0 }
        assertTrue(spawns.isNotEmpty(), "map $mapId has no monsters")
        // Start in the middle of the monster field: the centroid, snapped to the nearest spawn.
        val cx = spawns.sumOf { it.tileX } / spawns.size
        val cy = spawns.sumOf { it.tileY } / spawns.size
        val start = spawns.minBy { abs(it.tileX - cx) + abs(it.tileY - cy) }
        sim.spawnOrUpdatePlayer(newHero(sim, classDef, mapId, start.tileX, start.tileY))
        val levelStart = sim.players.getValue("bot").level

        var kills = 0; var deaths = 0; var attacks = 0
        val byLevel = sortedMapOf<Int, LevelStats>()
        var swing = 0L; var walk = 0L; var idle = 0L
        val totalTicks = (hours * 72_000).toLong()
        // Stuck handling: if the hunter has not moved or attacked for a while, it gives up on that monster for a minute.
        val ignored = HashMap<String, Long>()
        var chasing: String? = null; var chaseTicks = 0
        val lifeVital = sim.game.lifeVitalId
        for (t in 0 until totalTicks) {
            val me = sim.players["bot"] ?: break
            val stats = byLevel.getOrPut(me.level) { LevelStats() }
            stats.ticks++
            val killsBefore = kills
            val events = ArrayList<WorldSimulationEvent>()
            if (!me.isAlive(lifeVital) || me.isGhost) {
                deaths++
                // A real player releases, walks back, and loses about a minute. Charge that minute, then put the hero back.
                sim.executeAdminSlashCommand("bot", "/release")
                repeat(DEATH_PENALTY_TICKS) { sim.stepTick(); idle++ }
                val back = sim.players["bot"]!!
                sim.players["bot"] = back.copy(position = WorldPosition.fromTile(mapId, start.tileX, start.tileY, Direction.DOWN))
                continue
            }
            val mobs = sim.mapNpcs[mapId].orEmpty().filter { it.isAlive && sim.npcs[it.npcId]?.xpReward!! > 0 && (ignored[it.instanceId] ?: 0L) <= t }
            val target: MapNpcInstance? = mobs.minByOrNull { abs(it.tileX - me.position.tileX) + abs(it.tileY - me.position.tileY) }
            if (target == null) { idle++; sim.stepTick().also { events += it }; kills += killsIn(events); continue }
            if (chasing == target.instanceId) chaseTicks++ else { chasing = target.instanceId; chaseTicks = 0 }
            if (chaseTicks > 160) { ignored[target.instanceId] = t + 1200; chasing = null; chaseTicks = 0 }
            val dx = target.tileX - me.position.tileX
            val dy = target.tileY - me.position.tileY
            val dist = abs(dx) + abs(dy)
            if (dist == 1) {
                val dir = direction(dx, dy)
                if (me.position.direction != dir) events += sim.movePlayer("bot", dir) // blocked by the monster: only turns
                val swung = sim.playerAttack("bot")
                if (swung.isNotEmpty()) { attacks++; stats.attacks++ }
                events += swung
                swing++
            } else {
                val dir = direction(dx, dy)
                val moved = sim.movePlayer("bot", dir)
                events += moved
                // If the straight step was refused (rate limit or an obstacle), try the other axis.
                if (sim.players["bot"]?.position?.tileCoord == me.position.tileCoord && abs(dx) > 0 && abs(dy) > 0) {
                    events += sim.movePlayer("bot", direction(if (abs(dx) >= abs(dy)) 0 else dx, if (abs(dx) >= abs(dy)) dy else 0))
                }
                walk++
            }
            events += sim.stepTick()
            kills += killsIn(events)
            stats.kills += kills - killsBefore
        }
        val levelEnd = sim.players["bot"]?.level ?: levelStart
        return Result(label, hours, kills, deaths, levelStart, levelEnd, swing, walk, idle, attacks, byLevel)
    }

    private fun killsIn(events: List<WorldSimulationEvent>) = events.count { it is WorldSimulationEvent.NpcKilled && it.killerCharacterId == "bot" }

    @Test
    fun `a level 1 Duelist in Wrenfall Wood kills at a measurable rate`() {
        assumeTrue(gameDir != null, "Developer game games/halcyon-reach is not present in this checkout")
        val r = hunt(gameDir!!, "Duelist, Wrenfall Wood (map 4), from level 1", classId = 1, mapId = 4, hours = 0.5)
        val out = Path.of("build/kill-rate").also { Files.createDirectories(it) }.resolve("halcyon_kill_rate.md")
        val text = buildString {
            appendLine("Hits per kill (attacks that fired / kills): ${"%.1f".format(r.attacksPerKill)}")
            appendLine()
            appendLine("| Level | Minutes | Kills | Kills / hour | Attacks / kill |")
            appendLine("|-------|---------|-------|--------------|----------------|")
            for ((lv, st) in r.byLevel) appendLine("| $lv | ${"%.1f".format(st.ticks / 1200.0)} | ${st.kills} | ${if (st.ticks > 0) "%.0f".format(st.kills * 72000.0 / st.ticks) else "-"} | ${if (st.kills > 0) "%.1f".format(st.attacks.toDouble() / st.kills) else "-"} |")
            appendLine()
            appendLine("# Halcyon Reach kill-rate simulation")
            appendLine()
            appendLine("| Run | Sim hours | Kills | Kills / hour | Deaths | Level | Swinging | Walking | Idle |")
            appendLine("|-----|-----------|-------|--------------|--------|-------|----------|---------|------|")
            appendLine(
                "| ${r.label} | ${"%.2f".format(r.hours)} | ${r.kills} | ${"%.0f".format(r.killsPerHour)} | ${r.deaths} | " +
                    "${r.levelStart} to ${r.levelEnd} | ${"%.0f".format(r.pct(r.swingTicks))}% | ${"%.0f".format(r.pct(r.walkTicks))}% | ${"%.0f".format(r.pct(r.idleTicks))}% |"
            )
        }
        Files.writeString(out, text)
        println(text)
        assertTrue(r.kills > 0, "the hunter killed nothing: $text")
    }
}
