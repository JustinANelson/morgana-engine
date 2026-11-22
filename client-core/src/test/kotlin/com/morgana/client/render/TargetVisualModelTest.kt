package com.morgana.client.render

import com.morgana.client.state.ClientWorldState.FacingTarget
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetVisualModelTest {

    @Test
    fun `facingReticle returns null when facing nothing or out of bounds`() {
        assertNull(TargetVisualModel.facingReticle(FacingTarget.NOTHING, 3, 4, 10, 10))
        assertNull(TargetVisualModel.facingReticle(FacingTarget.HOSTILE_NPC, -1, 4, 10, 10))
        assertNull(TargetVisualModel.facingReticle(FacingTarget.HOSTILE_NPC, 10, 4, 10, 10))
        assertNull(TargetVisualModel.facingReticle(FacingTarget.RESOURCE, 3, -1, 10, 10))
        assertNull(TargetVisualModel.facingReticle(FacingTarget.RESOURCE, 3, 10, 10, 10))
    }

    @Test
    fun `facingReticle distinguishes hostile, friendly, and resource targets`() {
        val hostile = TargetVisualModel.facingReticle(FacingTarget.HOSTILE_NPC, 2, 3, 10, 10)
        val friendly = TargetVisualModel.facingReticle(FacingTarget.FRIENDLY_NPC, 2, 3, 10, 10)
        val shop = TargetVisualModel.facingReticle(FacingTarget.SHOP, 2, 3, 10, 10)
        val resource = TargetVisualModel.facingReticle(FacingTarget.RESOURCE, 2, 3, 10, 10)

        assertEquals("F87171", hostile?.colorHex)
        assertEquals("67E8F9", friendly?.colorHex)
        assertEquals("67E8F9", shop?.colorHex)
        assertEquals("86EFAC", resource?.colorHex)
    }

    @Test
    fun `npcNameplateStyle colors by behavior and tags bosses`() {
        val boss = TargetVisualModel.npcNameplateStyle(
            NpcDefinition(id = 1, name = "Cinder Warden", title = "Floor Guardian", behavior = NpcBehavior.ATTACK_ON_SIGHT)
        )
        assertTrue(boss.isBoss)
        assertEquals("[BOSS] Cinder Warden", boss.label)
        assertEquals("FBBF24", boss.colorHex)

        val hostile = TargetVisualModel.npcNameplateStyle(
            NpcDefinition(id = 2, name = "Ash Imp", behavior = NpcBehavior.ATTACK_ON_SIGHT)
        )
        assertFalse(hostile.isBoss)
        assertEquals("Ash Imp", hostile.label)
        assertEquals("FCA5A5", hostile.colorHex)

        val vendor = TargetVisualModel.npcNameplateStyle(
            NpcDefinition(id = 3, name = "Mira", title = "Boss Quartermaster", behavior = NpcBehavior.SHOPKEEPER)
        )
        assertFalse(vendor.isBoss)
        assertEquals("86EFAC", vendor.colorHex)

        val guard = TargetVisualModel.npcNameplateStyle(
            NpcDefinition(id = 4, name = "Gatewatch", behavior = NpcBehavior.GUARD)
        )
        assertEquals("93C5FD", guard.colorHex)

        val passive = TargetVisualModel.npcNameplateStyle(
            NpcDefinition(id = 5, name = "Cave Beetle", behavior = NpcBehavior.PASSIVE)
        )
        assertEquals("FDE68A", passive.colorHex)
    }

    @Test
    fun `level offset colors and labels apply to monsters and world map summaries`() {
        val mob = NpcDefinition(id = 20, name = "Pheasant", behavior = NpcBehavior.PASSIVE, level = 10, xpReward = 25)
        assertEquals("Lv.10 Pheasant" to "86EFAC", TargetVisualModel.npcNameplateStyle(mob, playerLevel = 15, levelOffsetColors = true).let { it.label to it.colorHex })
        assertEquals("Lv.10 Pheasant" to "FFFFFF", TargetVisualModel.npcNameplateStyle(mob, playerLevel = 10, levelOffsetColors = true).let { it.label to it.colorHex })
        assertEquals("Lv.10 Pheasant" to "F87171", TargetVisualModel.npcNameplateStyle(mob, playerLevel = 5, levelOffsetColors = true).let { it.label to it.colorHex })
        assertEquals("Lv.11 Pheasant" to "111827", TargetVisualModel.npcNameplateStyle(mob.copy(level = 11), playerLevel = 1, levelOffsetColors = true).let { it.label to it.colorHex })

        val map = com.morgana.domain.map.MapDefinition(
            id = 2,
            name = "Outskirts",
            width = 10,
            height = 10,
            npcSpawns = listOf(com.morgana.domain.map.MapNpcSpawnSlot(slotIndex = 0, npcId = 20, spawnX = 2, spawnY = 2))
        )
        assertEquals("Lv.10" to "FFFFFF", TargetVisualModel.mapLevelSummary(map, listOf(mob), playerLevel = 10))
        val town = com.morgana.domain.map.MapDefinition(id = 1, name = "Twin City", width = 10, height = 10)
        assertEquals("Safe" to "9CA3AF", TargetVisualModel.mapLevelSummary(town, listOf(mob), playerLevel = 10))

        val elite = com.morgana.domain.game.EnemyVariantDefinition(id = "elite", label = "Elite", forceAggro = true, levelBonus = 4, colorHex = "C084FC")
        assertEquals("Lv.14 [Elite] Pheasant" to "F87171", TargetVisualModel.npcNameplateStyle(mob, playerLevel = 10, levelOffsetColors = true, variant = elite).let { it.label to it.colorHex })
        assertEquals("Lv.14 [Elite] Pheasant" to "FFFFFF", TargetVisualModel.npcNameplateStyle(mob, playerLevel = 14, levelOffsetColors = true, variant = elite).let { it.label to it.colorHex })
        assertEquals("[Elite] Pheasant" to "C084FC", TargetVisualModel.npcNameplateStyle(mob, levelOffsetColors = false, variant = elite).let { it.label to it.colorHex })
    }

    @Test
    fun `npc quest indicator shows available, in-progress, and ready-to-turn-in markers`() {
        val npc = NpcDefinition(id = 4, name = "General", behavior = NpcBehavior.QUEST_GIVER, eventId = 1)
        val quest = com.morgana.domain.event.QuestDefinition(
            id = 1,
            name = "Pheasant Hunt",
            levelReq = 1,
            objectives = listOf(
                com.morgana.domain.event.QuestObjective(
                    type = com.morgana.domain.event.QuestObjectiveType.KILL_NPC,
                    targetId = 20,
                    requiredCount = 2,
                    description = "Pheasants"
                )
            )
        )
        val event = com.morgana.domain.event.EventDefinition(
            id = 1,
            name = "General Quests",
            pages = listOf(
                com.morgana.domain.event.EventPage(
                    commands = listOf(
                        com.morgana.domain.event.EventCommand.ShowDialogue(
                            speakerName = "General",
                            text = "Hunt",
                            choices = listOf(com.morgana.domain.event.DialogueChoice("Accept", listOf(com.morgana.domain.event.EventCommand.StartQuest(1))))
                        ),
                        com.morgana.domain.event.EventCommand.CompleteQuest(1)
                    )
                )
            )
        )
        val basePlayer = com.morgana.domain.entity.CharacterState(id = "c1", accountId = "a1", name = "Hero", level = 1)

        assertEquals(
            TargetVisualModel.QuestIndicatorKind.AVAILABLE,
            TargetVisualModel.npcQuestIndicator(npc, listOf(event), listOf(quest), basePlayer)?.kind
        )
        val inProgress = basePlayer.copy(
            quests = mapOf(1 to com.morgana.domain.event.PlayerQuestState(questId = 1, objectiveCounts = listOf(1)))
        )
        assertEquals(
            TargetVisualModel.QuestIndicatorKind.IN_PROGRESS,
            TargetVisualModel.npcQuestIndicator(npc, listOf(event), listOf(quest), inProgress)?.kind
        )
        val ready = basePlayer.copy(
            quests = mapOf(1 to com.morgana.domain.event.PlayerQuestState(questId = 1, objectiveCounts = listOf(2)))
        )
        assertEquals(
            TargetVisualModel.QuestIndicatorKind.READY_TO_TURN_IN,
            TargetVisualModel.npcQuestIndicator(npc, listOf(event), listOf(quest), ready)?.kind
        )
        val completed = basePlayer.copy(
            quests = mapOf(1 to com.morgana.domain.event.PlayerQuestState(questId = 1, status = com.morgana.domain.event.QuestStatus.COMPLETED, objectiveCounts = listOf(2)))
        )
        assertNull(TargetVisualModel.npcQuestIndicator(npc, listOf(event), listOf(quest), completed))
    }
}
