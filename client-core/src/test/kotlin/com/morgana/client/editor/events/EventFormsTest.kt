package com.morgana.client.editor.events

import com.morgana.client.editor.forms.FormRules
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.event.ArenaQueueAction
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.Comparison
import com.morgana.domain.event.ConditionMatchMode
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.QuestCheck
import com.morgana.domain.event.EventCommand
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventPageCondition
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.event.VariableScope
import com.morgana.domain.spatial.Direction
import com.morgana.protocol.MapSummary
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventFormsTest {
    private val everyCheck = listOf(
        EventCondition(EventConditionType.SWITCH, key = "met", value = 0),
        EventCondition(EventConditionType.VARIABLE, key = "visits", comparison = Comparison.LESS, value = 3),
        EventCondition(EventConditionType.HAS_ITEM, targetId = 4, value = 2),
        EventCondition(EventConditionType.QUEST, targetId = 6, quest = QuestCheck.READY_TO_COMPLETE),
        EventCondition(EventConditionType.LEVEL, comparison = Comparison.GREATER, value = 9),
        EventCondition(EventConditionType.CLASS, targetId = 2),
        EventCondition(EventConditionType.KNOWS_SPELL, targetId = 5),
        EventCondition(EventConditionType.CHANCE, value = 25),
        EventCondition(EventConditionType.GUILD_HOLDS_AREA, value = 1)
    )

    private val everyStep: List<EventCommand> = listOf(
        EventCommand.ShowDialogue("Mira", "Hello", 3, listOf(DialogueChoice("Yes", listOf(EventCommand.OpenBank)))),
        EventCommand.GiveItem(4, 2), EventCommand.TakeItem(5, 3), EventCommand.GiveXp(120), EventCommand.SetSwitch("met", false, VariableScope.WORLD),
        EventCommand.AddVariable("visits", -2, VariableScope.WORLD), EventCommand.StartQuest(6), EventCommand.CompleteQuest(7), EventCommand.WarpPlayer(2, 8, 9),
        EventCommand.HealPlayerFull, EventCommand.OpenShop(3), EventCommand.OpenBank, EventCommand.OpenCrafting(CraftingStationType.COOKING_FIRE),
        EventCommand.PlayAnimation(11), EventCommand.RunCommonEvent(2), EventCommand.InvokeKotlinHook("festival", mapOf("stage" to "2", "who" to "all")),
        EventCommand.Stop, EventCommand.SetVariable("visits", 7, VariableScope.WORLD), EventCommand.ShowMessage("The door creaks."), EventCommand.TeachSpell(4), EventCommand.RepairGear(1), EventCommand.RespecAttributes(1, 25), EventCommand.ArenaQueue(ArenaQueueAction.JOIN, 2), EventCommand.Rebirth(2), EventCommand.Promote, EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_QUALITY, EquipmentSlot.ARMOR, 8, 2, 50),
        EventCommand.Wait(40), EventCommand.PlaySound("heal"), EventCommand.ShowPicture("banner", "Town Notice", 60),
        EventCommand.MoveNpc(5, Direction.UP, 3, faceOnly = true),
        EventCommand.TextInput("How many?", "qty", VariableScope.WORLD, isNumeric = true, defaultValue = "2", maxLength = 12),
        EventCommand.Label("retry"),
        EventCommand.JumpToLabel("retry"),
        EventCommand.Loop(listOf(EventCommand.BreakLoop)),
        EventCommand.BreakLoop
    ) + everyCheck.map { EventCommand.Conditional(it, listOf(EventCommand.OpenBank), listOf(EventCommand.Stop)) }

    @Test
    fun `every kind of step has a form that round-trips it unchanged`() {
        assertEquals(EventCommandKind.entries.toSet(), everyStep.map { EventForms.kindOf(it) }.toSet(), "a step kind is missing from this test")
        for (cmd in everyStep) {
            val kind = EventForms.kindOf(cmd)
            val values = EventForms.valuesOf(cmd)
            assertEquals(EventForms.fieldsFor(cmd).map { it.key }.toSet(), values.keys, "$kind: fields and values disagree")
            assertEquals(emptyList<String>(), FormRules.fieldProblems(EventForms.fieldsFor(cmd), values), "$kind")
            assertEquals(cmd, EventForms.apply(cmd, values), "$kind changed by an unedited save")
        }
    }

    @Test
    fun `new steps are of the kind asked for and point at things the game has`() {
        val catalog = WorldContentCatalogDto(maps = listOf(MapSummary(4, "Dock", 10, 10, 1)))
        for (kind in EventCommandKind.entries) {
            val cmd = EventForms.create(kind, catalog)
            assertEquals(kind, EventForms.kindOf(cmd))
            assertTrue(kind.label.isNotBlank() && kind.explain.isNotBlank() && kind.group.isNotBlank())
        }
        assertEquals(EventCommand.WarpPlayer(4, 0, 0), EventForms.create(EventCommandKind.WARP, catalog))
    }

    @Test
    fun `page settings and conditions round-trip, and empty names mean no check`() {
        val page = EventPage(
            2,
            EventPageCondition(
                requiredSwitchId = "met",
                requiredSwitchValue = false,
                switchScope = VariableScope.WORLD,
                variableId = "visits",
                minVariableValue = 3,
                variableScope = VariableScope.WORLD,
                requiredQuestId = 6,
                requiredQuestStatus = QuestStatus.COMPLETED,
                requiredItemId = 4,
                minPlayerLevel = 5
            ),
            EventTriggerType.AUTORUN_ON_ENTER, 9, everyStep
        )
        val values = EventForms.pageValues(page)
        assertEquals(emptyList<String>(), FormRules.fieldProblems(EventForms.pageFields + EventForms.conditionFields, values))
        assertEquals(page, EventForms.applyPage(page, values))
        assertEquals(EventPage(), EventForms.applyPage(EventPage(), EventForms.pageValues(EventPage())))

        val cleared = EventForms.applyPage(page, values + mapOf("switchId" to " ", "variableId" to "", "questStatus" to ""))
        assertEquals(null, cleared.condition.requiredSwitchId)
        assertEquals(null, cleared.condition.variableId)
        assertEquals(null, cleared.condition.requiredQuestStatus)
    }

    @Test
    fun `the event's own fields round-trip and a blank name keeps the old one`() {
        val e = EventDefinition(3, "Well", mapId = 2, tileX = 5, tileY = 6)
        assertEquals(e, EventForms.applyEvent(e, EventForms.eventValues(e)))
        assertEquals("Well", EventForms.applyEvent(e, mapOf("name" to "  ")).name)
    }

    @Test
    fun `conditions and where an event starts are described in words`() {
        val catalog = WorldContentCatalogDto(
            maps = listOf(MapSummary(2, "Dock", 10, 10, 1)),
            npcs = listOf(NpcDefinition(id = 5, name = "Mira", eventId = 3), NpcDefinition(id = 6, name = "Tom", eventId = 9))
        )
        assertEquals("always", EventForms.conditionSummary(EventPage(), catalog))
        assertEquals(
            "only when switch met is off and visits is at least 3 and level 5 or higher",
            EventForms.conditionSummary(
                EventPage(
                    condition = EventPageCondition(
                        requiredSwitchId = "met",
                        requiredSwitchValue = false,
                        variableId = "visits",
                        minVariableValue = 3,
                        minPlayerLevel = 5
                    )
                ),
                catalog
            )
        )
        assertEquals("Started from: tile 5, 6 on Dock (#2); creature Mira (#5)", EventForms.usedBy(EventDefinition(3, "Well", 2, 5, 6), catalog))
        assertTrue(EventForms.usedBy(EventDefinition(4, "Lost"), catalog).startsWith("Nothing starts this event yet"))
    }

    @Test
    fun `an If step covers every kind of check, reads in plain words, and starts afresh when the kind changes`() {
        assertEquals(EventConditionType.entries.toSet(), everyCheck.map { it.type }.toSet(), "a kind of check is missing from this test")
        val catalog = WorldContentCatalogDto()
        assertEquals(
            listOf(
                "switch met is off", "visits is less than 3", "the player carries 2 x #4 (missing)", "quest #6 (missing) is ready to hand in",
                "the player's level is more than 9", "the player's class is #2 (missing)", "the player knows #5 (missing)", "a 25 in 100 chance comes up", "the player's guild holds the guild area"
            ),
            everyCheck.map { EventForms.conditionText(it, catalog) }
        )
        val step = EventCommand.Conditional(everyCheck[0], listOf(EventCommand.OpenBank), listOf(EventCommand.Stop))
        val changed = EventForms.apply(step, EventForms.valuesOf(step) + ("check" to "CHANCE")) as EventCommand.Conditional
        assertEquals(EventCondition(EventConditionType.CHANCE, value = 50), changed.condition)
        assertEquals(step.then, changed.then) // the branches are kept
        assertEquals(step.otherwise, changed.otherwise)
        assertEquals(listOf("check", "value"), EventForms.fieldsFor(changed).map { it.key })
    }

    @Test
    fun `an If step lists its two branches and their steps`() {
        val step = EventCommand.Conditional(everyCheck[0], listOf(EventCommand.OpenBank), listOf(EventCommand.Stop))
        assertEquals(
            listOf("0|1. If switch met is off:", "1|Then:", "2|1. Open the bank", "1|Otherwise:", "2|1. Stop the event"),
            EventForms.rows(listOf(step), WorldContentCatalogDto()).map { "${it.depth}|${it.text}" }
        )
    }

    @Test
    fun `plugin settings are key=value lines`() {
        val (map, problems) = EventForms.parsePayload("stage = 2\n\nwho=all\nbroken\n=x")
        assertEquals(mapOf("stage" to "2", "who" to "all"), map)
        assertEquals(listOf("Settings line 3: write key=value", "Settings line 4: write key=value"), problems)
    }

    @Test
    fun `long speech is clipped in the step list`() {
        val text = EventForms.summary(EventCommand.ShowDialogue("", "word ".repeat(40)), WorldContentCatalogDto())
        assertTrue(text.startsWith("Say: \"word") && text.endsWith("...\"") && text.length < 60, text)
    }

    @Test
    fun `world scope round-trips in page conditions and steps, and common event forms describe callers and problems`() {
        val page = EventPage(
            condition = EventPageCondition(
                requiredSwitchId = "door",
                requiredSwitchValue = true,
                switchScope = VariableScope.WORLD,
                variableId = "bells",
                minVariableValue = 3,
                variableScope = VariableScope.WORLD
            )
        )
        assertEquals(page, EventForms.applyPage(page, EventForms.pageValues(page)))
        assertTrue(EventForms.conditionSummary(page, WorldContentCatalogDto()).contains("world"))

        val worldCheck = EventCondition(EventConditionType.SWITCH, key = "door", scope = VariableScope.WORLD, value = 1)
        assertTrue(EventForms.conditionText(worldCheck, WorldContentCatalogDto()).contains("world"))
        assertEquals(
            EventCommand.Conditional(worldCheck),
            EventForms.apply(EventCommand.Conditional(worldCheck), EventForms.valuesOf(EventCommand.Conditional(worldCheck)))
        )

        val ce = CommonEventDefinition(2, "Ring Bell", "World bell", commands = listOf(EventCommand.RunCommonEvent(9)))
        val catalog = WorldContentCatalogDto(
            events = listOf(EventDefinition(1, "Bell", pages = listOf(EventPage(commands = listOf(EventCommand.RunCommonEvent(2)))))),
            commonEvents = listOf(ce)
        )
        assertEquals(ce, EventForms.applyCommonEvent(ce, EventForms.commonEventValues(ce)))
        assertEquals("Called by: event Bell (#1)", EventForms.commonEventUsedBy(ce, catalog))
        assertEquals(listOf("Steps, step 1: common event 9 does not exist"), EventForms.commonEventProblems(ce, catalog))

        val moveMissingNpc = EventDefinition(
            2,
            "Patrol",
            pages = listOf(EventPage(commands = listOf(EventCommand.MoveNpc(99, Direction.RIGHT, 2))))
        )
        val npcCatalog = catalog.copy(npcs = listOf(NpcDefinition(id = 1, name = "Guard")))
        assertEquals(listOf("Page 1, step 1: creature 99 does not exist"), EventForms.problems(moveMissingNpc, npcCatalog))
        assertEquals("Move Guard (#1) right (2 steps)", EventForms.summary(EventCommand.MoveNpc(1, Direction.RIGHT, 2), npcCatalog))
        assertEquals("Turn Guard (#1) up", EventForms.summary(EventCommand.MoveNpc(1, Direction.UP, 2, faceOnly = true), npcCatalog))
        assertEquals("Wait 20 ticks", EventForms.summary(EventCommand.Wait(20), npcCatalog))
        assertEquals("Play sound chime", EventForms.summary(EventCommand.PlaySound("chime"), npcCatalog))
        assertEquals("Show picture banner (Town)", EventForms.summary(EventCommand.ShowPicture("banner", "Town"), npcCatalog))
        assertEquals("Ask \"How many?\" -> world qty (number)", EventForms.summary(EventCommand.TextInput("How many?", "qty", VariableScope.WORLD, isNumeric = true), npcCatalog))

        val multiAll = EventCommand.Conditional(
            condition = EventCondition(EventConditionType.SWITCH, key = "met", value = 1),
            extraConditions = listOf(EventCondition(EventConditionType.LEVEL, comparison = Comparison.AT_LEAST, value = 5)),
            matchMode = ConditionMatchMode.ALL
        )
        val multiAny = multiAll.copy(matchMode = ConditionMatchMode.ANY)
        assertEquals(multiAny, EventForms.apply(multiAny, EventForms.valuesOf(multiAny)))
        assertEquals(
            setOf("matchMode", "check", "name", "scope", "on", "e0_check", "e0_comparison", "e0_value"),
            EventForms.fieldsFor(multiAll).map { it.key }.toSet()
        )
        assertEquals("If all: switch met is on and the player's level is at least 5:", EventForms.summary(multiAll, npcCatalog))
        assertEquals("If any: switch met is on or the player's level is at least 5:", EventForms.summary(multiAny, npcCatalog))

        val badExtra = EventDefinition(
            3,
            "MultiProblem",
            pages = listOf(
                EventPage(
                    commands = listOf(
                        multiAll.copy(extraConditions = listOf(EventCondition(EventConditionType.HAS_ITEM, targetId = 777, value = 1)))
                    )
                )
            )
        )
        assertEquals(
            listOf("Page 1, step 1 (check 2): item 777 does not exist"),
            EventForms.problems(badExtra, npcCatalog.copy(items = listOf(com.morgana.domain.item.ItemDefinition(id = 1, name = "Coin", type = com.morgana.domain.item.ItemType.CURRENCY))))
        )

        val timedCommon = CommonEventDefinition(5, "Weather Pulse", trigger = CommonEventTrigger.ON_TIMER, intervalTicks = 60)
        assertEquals(timedCommon, EventForms.applyCommonEvent(timedCommon, EventForms.commonEventValues(timedCommon)))
        assertEquals("Runs automatically every 60 ticks", EventForms.commonEventUsedBy(timedCommon, npcCatalog))
        assertEquals(
            "Runs automatically when a player logs in",
            EventForms.commonEventUsedBy(timedCommon.copy(trigger = CommonEventTrigger.ON_LOGIN), npcCatalog)
        )
        assertEquals(
            "Runs automatically when a player levels up",
            EventForms.commonEventUsedBy(timedCommon.copy(trigger = CommonEventTrigger.ON_LEVEL_UP), npcCatalog)
        )
        assertEquals(
            listOf("Timer interval must be between 1 and 72000 ticks"),
            EventForms.commonEventProblems(timedCommon.copy(intervalTicks = 0), npcCatalog)
        )

        val loopStep = EventCommand.Loop(listOf(EventCommand.Label("ask"), EventCommand.BreakLoop, EventCommand.JumpToLabel("ask")))
        assertEquals(
            listOf("1. Repeat:", "Do each pass:", "1. Label: ask", "2. Break loop", "3. Jump to label: ask"),
            EventForms.rows(listOf(loopStep), npcCatalog).map { it.text }
        )
    }
}
