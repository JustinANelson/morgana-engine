package com.morgana.client.ui

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition

/**
 * Tracks a single dungeon delve from entry until returning to town/safe map (either extracted
 * alive or defeated) and builds a concise summary for the end-of-run modal.
 */
object RunSummaryModel {

    enum class RunOutcome {
        EXTRACTED,
        DEFEATED
    }

    data class RunSummary(
        val outcome: RunOutcome,
        val deepestFloor: Int,
        val floorsVisited: Int,
        val kills: Int,
        val bossesDefeated: List<String>,
        val xpGained: Long,
        val levelsGained: Int,
        val durationSeconds: Int
    )

    private val FLOOR_NUM = Regex("""\((?:floor|depth)\s+(\d+)\)""", RegexOption.IGNORE_CASE)

    fun parseFloorNumber(mapName: String): Int? =
        FLOOR_NUM.find(mapName)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun formatDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val mins = s / 60
        val rem = s % 60
        return if (mins > 0) "${mins}m ${rem}s" else "${rem}s"
    }

    class RunTracker {
        var active: Boolean = false
            private set

        private var startMs: Long = 0L
        private var startLevel: Int = 1
        private var startXp: Long = 0L
        private var deepestFloor: Int = 1
        private val visitedMaps = LinkedHashSet<String>()
        private var kills: Int = 0
        private val bossesDefeated = mutableListOf<String>()
        private var sawDefeat: Boolean = false

        fun reset() {
            active = false
            startMs = 0L
            startLevel = 1
            startXp = 0L
            deepestFloor = 1
            visitedMaps.clear()
            kills = 0
            bossesDefeated.clear()
            sawDefeat = false
        }

        fun onMapEntered(
            mapName: String,
            hasGenerator: Boolean,
            currentLevel: Int,
            currentXp: Long,
            nowMs: Long
        ): RunSummary? {
            val floor = parseFloorNumber(mapName)

            if (!active) {
                if (!hasGenerator) return null
                active = true
                startMs = nowMs
                startLevel = currentLevel
                startXp = currentXp
                deepestFloor = floor ?: 1
                visitedMaps.clear()
                visitedMaps += mapName
                kills = 0
                bossesDefeated.clear()
                sawDefeat = false
                return null
            }

            if (hasGenerator) {
                visitedMaps += mapName
                if (floor != null && floor > deepestFloor) {
                    deepestFloor = floor
                }
                return null
            }

            val summary = RunSummary(
                outcome = if (sawDefeat) RunOutcome.DEFEATED else RunOutcome.EXTRACTED,
                deepestFloor = deepestFloor,
                floorsVisited = visitedMaps.size,
                kills = kills,
                bossesDefeated = bossesDefeated.toList(),
                xpGained = (currentXp - startXp).coerceAtLeast(0L),
                levelsGained = (currentLevel - startLevel).coerceAtLeast(0),
                durationSeconds = ((nowMs - startMs) / 1000L).toInt().coerceAtLeast(0)
            )
            reset()
            return summary
        }

        fun onNpcKilled(
            wasAlive: Boolean,
            isNowAlive: Boolean,
            npcDef: NpcDefinition?,
            floorBossNpcId: Int = 0
        ) {
            if (!active || !wasAlive || isNowAlive || npcDef == null) return
            when (npcDef.behavior) {
                NpcBehavior.SHOPKEEPER,
                NpcBehavior.BANKER,
                NpcBehavior.GUILD_BANKER,
                NpcBehavior.QUEST_GIVER,
                NpcBehavior.FRIENDLY_ROAM -> return
                else -> Unit
            }
            kills += 1
            if (DungeonHudModel.isBossNpc(npcDef.id, npcDef, floorBossNpcId)) {
                bossesDefeated += npcDef.name
            }
        }

        fun onPlayerVitals(curHp: Int) {
            if (active && curHp <= 0) {
                sawDefeat = true
            }
        }

        fun onSystemNotice(message: String) {
            if (!active) return
            val lower = message.lowercase()
            if ("you were defeated" in lower || "you have died" in lower || "you were slain" in lower) {
                sawDefeat = true
            }
        }
    }
}
