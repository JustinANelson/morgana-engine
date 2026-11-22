package com.morgana.client.render

import com.morgana.client.state.ClientWorldState.FacingTarget
import com.morgana.client.ui.DungeonHudModel
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.LevelOffsetRules

/**
 * Pure presentation model for world-space targeting and NPC nameplate styling:
 * - Ground reticle color/coordinates for the tile the local player is facing.
 * - Behavior- and boss-aware NPC nameplate labels and colors.
 */
object TargetVisualModel {

    data class ReticleSpec(
        val tileX: Int,
        val tileY: Int,
        val colorHex: String
    )

    data class NpcNameplateStyle(
        val label: String,
        val colorHex: String,
        val isBoss: Boolean
    )

    enum class QuestIndicatorKind { READY_TO_TURN_IN, AVAILABLE, IN_PROGRESS }

    data class QuestIndicatorSpec(
        val kind: QuestIndicatorKind,
        val symbol: String,
        val colorHex: String
    )

    fun facingReticle(
        facingTarget: FacingTarget,
        tileX: Int,
        tileY: Int,
        mapWidth: Int,
        mapHeight: Int
    ): ReticleSpec? {
        if (facingTarget == FacingTarget.NOTHING) return null
        if (tileX !in 0 until mapWidth || tileY !in 0 until mapHeight) return null
        val colorHex = when (facingTarget) {
            FacingTarget.HOSTILE_NPC -> "F87171"
            FacingTarget.FRIENDLY_NPC, FacingTarget.SHOP, FacingTarget.BANK, FacingTarget.DOOR -> "67E8F9"
            FacingTarget.RESOURCE -> "86EFAC"
            FacingTarget.NOTHING -> return null
        }
        return ReticleSpec(tileX = tileX, tileY = tileY, colorHex = colorHex)
    }

    fun npcNameplateStyle(
        npcDef: NpcDefinition,
        floorBossNpcId: Int = 0,
        playerLevel: Int? = null,
        levelOffsetColors: Boolean = false,
        variant: com.morgana.domain.game.EnemyVariantDefinition? = null,
        /** Levels an instanced dungeon's scaling adds to the monster (see `NpcScale.levelBonus`). */
        levelShift: Int = 0
    ): NpcNameplateStyle {
        val isBoss = DungeonHudModel.isBossNpc(npcDef.id, npcDef, floorBossNpcId)
        val isMonster = npcDef.behavior == NpcBehavior.PASSIVE || npcDef.behavior == NpcBehavior.ATTACK_ON_SIGHT
        val useOffset = levelOffsetColors && playerLevel != null && isMonster
        val effectiveLevel = (if (variant != null && !isBoss) variant.effectiveLevel(npcDef.level) else npcDef.level) + levelShift
        val variantPrefix = if (variant != null && !isBoss) "[${variant.label}] " else ""
        val baseLabel = if (useOffset) "Lv.$effectiveLevel $variantPrefix${npcDef.name}" else "$variantPrefix${npcDef.name}"
        if (isBoss) {
            val hex = if (useOffset) LevelOffsetRules.tier(playerLevel!!, effectiveLevel).colorHex else "FBBF24"
            return NpcNameplateStyle(
                label = "[BOSS] $baseLabel",
                colorHex = hex,
                isBoss = true
            )
        }
        val effectiveBehavior = if (variant?.forceAggro == true) NpcBehavior.ATTACK_ON_SIGHT else npcDef.behavior
        val colorHex = when {
            useOffset -> LevelOffsetRules.tier(playerLevel!!, effectiveLevel).colorHex
            variant != null -> variant.colorHex
            else -> when (effectiveBehavior) {
                NpcBehavior.ATTACK_ON_SIGHT -> "FCA5A5"
                NpcBehavior.GUARD -> "93C5FD"
                NpcBehavior.SHOPKEEPER,
                NpcBehavior.BANKER,
                NpcBehavior.GUILD_BANKER,
                NpcBehavior.QUEST_GIVER,
                NpcBehavior.FRIENDLY_ROAM -> "86EFAC"
                NpcBehavior.PASSIVE -> "FDE68A"
            }
        }
        return NpcNameplateStyle(
            label = baseLabel,
            colorHex = colorHex,
            isBoss = false
        )
    }

    /** Returns `(label, colorHex)` summarizing combat monster levels on [map], or null if [map] is not cached. */
    fun mapLevelSummary(map: MapDefinition?, npcs: List<NpcDefinition>, playerLevel: Int?): Pair<String, String>? {
        if (map == null) return null
        val npcById = npcs.associateBy { it.id }
        val levels = map.npcSpawns.mapNotNull { spawn ->
            val def = npcById[spawn.npcId] ?: return@mapNotNull null
            val isMonster = def.behavior == NpcBehavior.PASSIVE || def.behavior == NpcBehavior.ATTACK_ON_SIGHT
            if (isMonster && def.xpReward > 0L) def.level else null
        }
        if (levels.isEmpty()) return "Safe" to "9CA3AF"
        val minLv = levels.min()
        val maxLv = levels.max()
        val label = if (minLv == maxLv) "Lv.$minLv" else "Lv.$minLv-$maxLv"
        val hex = if (playerLevel != null) {
            val tier = LevelOffsetRules.tier(playerLevel, (minLv + maxLv) / 2)
            if (tier.colorHex == "111827") "9CA3AF" else tier.colorHex
        } else {
            "E5E7EB"
        }
        return label to hex
    }

    /**
     * Determines whether an NPC should display a floating quest indicator (`!` available, gold `?` ready to
     * turn in, or grey `?` in progress) based on the NPC's linked [EventDefinition] and [player]'s quest state.
     */
    fun npcQuestIndicator(
        npcDef: NpcDefinition,
        events: List<EventDefinition>,
        quests: List<QuestDefinition>,
        player: CharacterState?
    ): QuestIndicatorSpec? {
        if (npcDef.eventId == 0 || player == null) return null
        val event = events.firstOrNull { it.id == npcDef.eventId } ?: return null
        val startQuestIds = linkedSetOf<Int>()
        val completeQuestIds = linkedSetOf<Int>()

        fun scan(commands: List<EventCommand>) {
            for (cmd in commands) {
                when (cmd) {
                    is EventCommand.StartQuest -> startQuestIds += cmd.questId
                    is EventCommand.CompleteQuest -> completeQuestIds += cmd.questId
                    is EventCommand.Conditional -> {
                        scan(cmd.then)
                        scan(cmd.otherwise)
                    }
                    is EventCommand.ShowDialogue -> cmd.choices.forEach { scan(it.commands) }
                    else -> {}
                }
            }
        }
        event.pages.forEach { scan(it.commands) }
        if (startQuestIds.isEmpty() && completeQuestIds.isEmpty()) return null

        val questById = quests.associateBy { it.id }

        val readyToTurnIn = completeQuestIds.any { qid ->
            val qDef = questById[qid] ?: return@any false
            val qState = player.quests[qid] ?: return@any false
            qState.status == QuestStatus.IN_PROGRESS &&
                qDef.objectives.withIndex().all { (i, obj) ->
                    qState.objectiveCounts.getOrElse(i) { 0 } >= obj.requiredCount
                }
        }
        if (readyToTurnIn) {
            return QuestIndicatorSpec(QuestIndicatorKind.READY_TO_TURN_IN, "?", "FACC15")
        }

        val hasAvailable = startQuestIds.any { qid ->
            val qDef = questById[qid] ?: return@any false
            val qState = player.quests[qid]
            // A repeatable quest with a cooldown shows no marker once handed in: the client does not know when its wait ends.
            val notStartedOrRepeatable = qState == null || (qState.status == QuestStatus.COMPLETED && qDef.repeatable && qDef.cooldownTicks == 0L)
            notStartedOrRepeatable &&
                player.level >= qDef.levelReq &&
                qDef.prerequisiteQuestIds.all { pre -> player.quests[pre]?.status == QuestStatus.COMPLETED }
        }
        if (hasAvailable) {
            return QuestIndicatorSpec(QuestIndicatorKind.AVAILABLE, "!", "FACC15")
        }

        val hasInProgress = completeQuestIds.any { qid ->
            player.quests[qid]?.status == QuestStatus.IN_PROGRESS
        }
        if (hasInProgress) {
            return QuestIndicatorSpec(QuestIndicatorKind.IN_PROGRESS, "?", "9CA3AF")
        }
        return null
    }
}
