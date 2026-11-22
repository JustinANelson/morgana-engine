package com.morgana.client.ui

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.MapNpcInstance

/**
 * Pure presentation model for the roguelike/dungeon HUD overlays:
 * - A compact floor/biome badge (e.g. `"Floor 3 · The Sunken Ruins"`).
 * - A boss health bar when an alive guardian/boss NPC is on the current map.
 */
object DungeonHudModel {

    data class FloorBadgeInfo(
        val label: String,
        val isBossFloor: Boolean
    )

    data class BossBarInfo(
        val name: String,
        val title: String,
        val curHp: Int,
        val maxHp: Int
    ) {
        val fraction: Float
            get() = if (maxHp <= 0) 0f else (curHp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)
    }

    private val FLOOR_SUFFIX = Regex("""^(.+?)\s*\((?:floor|depth)\s+(\d+)\)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Builds the floor badge for [mapName], or null on non-dungeon maps (e.g. town) when
     * [hasGenerator] and [hasAliveBoss] are both false.
     */
    fun floorBadge(
        mapName: String,
        hasGenerator: Boolean,
        hasAliveBoss: Boolean,
        hasGeneratorBoss: Boolean = false
    ): FloorBadgeInfo? {
        val trimmed = mapName.trim()
        val match = FLOOR_SUFFIX.matchEntire(trimmed)
        if (match != null) {
            val biome = match.groupValues[1].trim()
            val floor = match.groupValues[2].toIntOrNull()
            val label = if (floor != null) "Floor $floor · $biome" else trimmed
            val bossFloor = hasAliveBoss || hasGeneratorBoss
            return FloorBadgeInfo(label = label, isBossFloor = bossFloor)
        }
        if (!hasGenerator && !hasAliveBoss) return null
        return FloorBadgeInfo(label = trimmed.ifEmpty { "Dungeon" }, isBossFloor = hasAliveBoss || hasGeneratorBoss)
    }

    /**
     * Returns true when [npcId] / [npcDef] represents a boss or guardian:
     * - matches the current floor's [FloorGenerator.bossNpcId] ([floorBossNpcId]), or
     * - has a boss/guardian title on a non-shopkeeper NPC (fallback for hand-placed bosses).
     */
    fun isBossNpc(npcId: Int, npcDef: NpcDefinition?, floorBossNpcId: Int = 0): Boolean {
        if (floorBossNpcId > 0 && npcId == floorBossNpcId) return true
        if (npcDef == null) return false
        if (npcDef.behavior == NpcBehavior.SHOPKEEPER) return false
        // Any monster with a scripted encounter (phases, telegraphed attacks) is a boss, whatever its title says.
        if (npcDef.encounter != null) return true
        return isBossTitle(npcDef.title)
    }

    /**
     * Returns the primary alive boss/guardian NPC on the current map, or null if none is present.
     */
    fun activeBoss(
        npcsOnMap: Collection<MapNpcInstance>,
        npcDefsById: Map<Int, NpcDefinition>,
        lifeVitalId: String = "hp",
        floorBossNpcId: Int = 0
    ): BossBarInfo? {
        var best: BossBarInfo? = null
        for (npc in npcsOnMap) {
            if (!npc.isAlive(lifeVitalId)) continue
            val def = npcDefsById[npc.npcId] ?: continue
            if (!isBossNpc(npc.npcId, def, floorBossNpcId)) continue
            val vital = npc.vitals[lifeVitalId] ?: continue
            if (vital.max <= 0) continue
            val candidate = BossBarInfo(
                name = def.name,
                title = def.title.trim(),
                curHp = vital.cur.coerceIn(0, vital.max),
                maxHp = vital.max
            )
            if (best == null || candidate.maxHp > best.maxHp) {
                best = candidate
            }
        }
        return best
    }

    data class ToastNotice(
        val text: String,
        val kind: ToastKind
    )

    fun floorEntryToast(
        mapName: String,
        hasGenerator: Boolean,
        hasAliveBoss: Boolean,
        hasGeneratorBoss: Boolean = false
    ): ToastNotice {
        val badge = floorBadge(mapName, hasGenerator, hasAliveBoss, hasGeneratorBoss)
        if (badge != null) {
            return if (badge.isBossFloor) {
                ToastNotice("Entered ${badge.label} — Guardian Floor", ToastKind.WARNING)
            } else {
                ToastNotice("Entered ${badge.label}", ToastKind.INFO)
            }
        }
        val clean = mapName.trim().ifEmpty { "Unknown Area" }
        return ToastNotice("Entered $clean", ToastKind.INFO)
    }

    fun bossDefeatedToast(
        wasAlive: Boolean,
        isNowAlive: Boolean,
        npcId: Int,
        npcDef: NpcDefinition?,
        floorBossNpcId: Int = 0
    ): ToastNotice? {
        if (!wasAlive || isNowAlive || npcDef == null) return null
        if (!isBossNpc(npcId, npcDef, floorBossNpcId)) return null
        return ToastNotice("${npcDef.name} defeated!", ToastKind.LEVEL)
    }

    internal fun isBossTitle(title: String): Boolean {
        val lower = title.lowercase()
        return "guardian" in lower || "boss" in lower
    }
}
