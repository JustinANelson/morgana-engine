package com.morgana.domain

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.Comparison
import com.morgana.domain.event.ConditionMatchMode
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventPageCondition
import com.morgana.domain.event.VariableScope
import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestCheck
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** If steps, the new small steps, and dialogues that pause an event until the player answers or closes them. */
class EventFlowTest {
    private val key = ItemDefinition(id = 3, name = "Iron Key", stackable = true, maxStack = 99)

    private fun sim(): WorldSimulation {
        val s = WorldSimulation(game = MorganaClassic.COMPILED)
        s.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        s.items[key.id] = key
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a1", name = "Tester", position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)))
        return s
    }

    private fun WorldSimulation.p() = players.getValue("p1")
    private fun WorldSimulation.run(vararg steps: EventCommand): List<WorldSimulationEvent> =
        mutableListOf<WorldSimulationEvent>().also { executeEventCommands("p1", 7, steps.toList(), it) }

    private fun say(text: String, vararg choices: DialogueChoice) = EventCommand.ShowDialogue("Mira", text, choices = choices.toList())
    private fun set(name: String, value: Int) = EventCommand.SetVariable(name, value)
    private fun List<WorldSimulationEvent>.lines() = filterIsInstance<WorldSimulationEvent.OpenUiRequested>().map { it.dialogueText }
    private fun holds(s: WorldSimulation, c: EventCondition): Boolean {
        s.run(EventCommand.Conditional(c, listOf(set("result", 1)), listOf(set("result", 0))))
        return s.p().variables["result"] == 1
    }

    @Test
    fun `an If step runs one side, then the event carries on`() {
        val s = sim()
        val check = EventCondition(EventConditionType.SWITCH, key = "met", value = 1)
        val step = EventCommand.Conditional(check, listOf(set("side", 1)), listOf(set("side", 2), EventCommand.SetSwitch("met", true)))
        s.run(step, set("after", 9))
        assertEquals(2, s.p().variables["side"])
        assertEquals(9, s.p().variables["after"])
        s.run(step)
        assertEquals(1, s.p().variables["side"]) // the first run switched it on
    }

    @Test
    fun `every kind of check reads the player`() {
        val s = sim()
        fun c(type: EventConditionType, key: String = "", target: Int = 0, cmp: Comparison = Comparison.AT_LEAST, value: Int = 1, quest: QuestCheck = QuestCheck.COMPLETED) =
            EventCondition(type, key, target, cmp, value, quest)

        assertTrue(holds(s, c(EventConditionType.SWITCH, "gone", value = 0))) // a switch never set is off
        s.run(set("n", 5))
        assertTrue(holds(s, c(EventConditionType.VARIABLE, "n", cmp = Comparison.EQUAL, value = 5)))
        assertTrue(holds(s, c(EventConditionType.VARIABLE, "n", cmp = Comparison.GREATER, value = 4)))
        assertFalse(holds(s, c(EventConditionType.VARIABLE, "n", cmp = Comparison.LESS, value = 5)))
        assertTrue(holds(s, c(EventConditionType.VARIABLE, "n", cmp = Comparison.NOT_EQUAL, value = 4)))
        assertTrue(holds(s, c(EventConditionType.VARIABLE, "n", cmp = Comparison.AT_MOST, value = 5)))

        assertFalse(holds(s, c(EventConditionType.HAS_ITEM, target = 3)))
        s.run(EventCommand.GiveItem(3, 2))
        assertTrue(holds(s, c(EventConditionType.HAS_ITEM, target = 3, value = 2)))
        assertFalse(holds(s, c(EventConditionType.HAS_ITEM, target = 3, value = 3)))

        assertTrue(holds(s, c(EventConditionType.LEVEL, cmp = Comparison.EQUAL, value = 1)))
        assertTrue(holds(s, c(EventConditionType.CLASS, target = s.p().classId)))
        assertFalse(holds(s, c(EventConditionType.CLASS, target = s.p().classId + 1)))

        assertTrue(holds(s, c(EventConditionType.CHANCE, value = 100)))
        assertFalse(holds(s, c(EventConditionType.CHANCE, value = 0)))
    }

    @Test
    fun `quest checks tell not started, in progress, ready to hand in and completed apart`() {
        val s = sim()
        s.quests[5] = QuestDefinition(id = 5, name = "Keys", objectives = listOf(QuestObjective(QuestObjectiveType.COLLECT_ITEM, 3, 2, "")))
        fun q(check: QuestCheck) = holds(s, EventCondition(EventConditionType.QUEST, targetId = 5, quest = check))
        assertTrue(q(QuestCheck.NOT_STARTED))
        s.run(EventCommand.StartQuest(5))
        assertTrue(q(QuestCheck.IN_PROGRESS))
        assertFalse(q(QuestCheck.READY_TO_COMPLETE))
        s.spawnOrUpdatePlayer(s.p().copy(quests = mapOf(5 to PlayerQuestState(5, QuestStatus.IN_PROGRESS, listOf(2)))))
        assertTrue(q(QuestCheck.READY_TO_COMPLETE))
        assertTrue(q(QuestCheck.IN_PROGRESS))
        s.run(EventCommand.CompleteQuest(5))
        assertTrue(q(QuestCheck.COMPLETED))
        assertFalse(q(QuestCheck.IN_PROGRESS))
    }

    @Test
    fun `stop ends the event even from inside an If, and the small steps do what they say`() {
        val s = sim()
        val always = EventCondition(EventConditionType.CHANCE, value = 100)
        val out = s.run(EventCommand.ShowMessage("The door creaks."), EventCommand.Conditional(always, listOf(set("a", 1), EventCommand.Stop, set("a", 2))), set("b", 1))
        assertEquals(1, s.p().variables["a"])
        assertNull(s.p().variables["b"])
        val chat = out.filterIsInstance<WorldSimulationEvent.ChatEmitted>().single()
        assertEquals("The door creaks.", chat.message)
        assertEquals("p1", chat.targetCharacterId)

        s.run(EventCommand.TeachSpell(4)) // no such spell: nothing learned
        assertTrue(s.p().knownSpellIds.isEmpty())
    }

    @Test
    fun `a line with steps after it pauses the event until it is closed`() {
        val s = sim()
        val out = s.run(say("One"), set("x", 1), say("Two"), set("x", 2))
        assertEquals(listOf("One"), out.lines())
        assertNull(s.p().variables["x"])

        val next = s.selectDialogueChoice("p1", 7, -1)
        assertEquals(listOf("Two"), next.lines())
        assertEquals(1, s.p().variables["x"])

        s.selectDialogueChoice("p1", 7, -1)
        assertEquals(2, s.p().variables["x"])
        assertTrue(s.pendingDialogues.isEmpty())
        assertTrue(s.selectDialogueChoice("p1", 7, -1).isEmpty()) // nothing is waiting any more
    }

    @Test
    fun `the last line of an event leaves nothing waiting`() {
        val s = sim()
        s.run(set("x", 1), say("Bye"))
        assertTrue(s.pendingDialogues.isEmpty())
    }

    @Test
    fun `answers work at any depth and the event carries on after the answer's steps`() {
        val s = sim()
        val inner = say("Really?", DialogueChoice("Yes", listOf(set("deep", 1))), DialogueChoice("No", listOf(set("deep", 2))))
        val outer = say("Help?", DialogueChoice("Sure", listOf(set("first", 1), inner, set("afterInner", 1))), DialogueChoice("No"))
        assertEquals(listOf("Help?"), s.run(outer, set("end", 1)).lines())
        assertNull(s.p().variables["end"]) // waits for the answer

        assertTrue(s.selectDialogueChoice("p1", 7, 5).isEmpty()) // not an answer: still waiting
        assertTrue(s.selectDialogueChoice("p1", 8, 0).isEmpty()) // another event: still waiting
        assertEquals(listOf("Really?"), s.selectDialogueChoice("p1", 7, 0).lines())
        assertEquals(1, s.p().variables["first"])
        assertNull(s.p().variables["afterInner"])

        s.selectDialogueChoice("p1", 7, 1) // "No" of the inner question
        assertEquals(2, s.p().variables["deep"])
        assertEquals(1, s.p().variables["afterInner"])
        assertEquals(1, s.p().variables["end"])
        assertTrue(s.pendingDialogues.isEmpty())
    }

    @Test
    fun `a dialogue inside an If keeps the steps after the If for later`() {
        val s = sim()
        val always = EventCondition(EventConditionType.CHANCE, value = 100)
        s.run(EventCommand.Conditional(always, listOf(say("Hi"), set("inIf", 1))), set("afterIf", 1))
        assertNull(s.p().variables["afterIf"])
        s.selectDialogueChoice("p1", 7, -1)
        assertEquals(1, s.p().variables["inIf"])
        assertEquals(1, s.p().variables["afterIf"])
    }

    @Test
    fun `with nothing waiting an answer falls back to the page's first dialogue, and leaving forgets what was waiting`() {
        val s = sim()
        s.events[7] = EventDefinition(7, "Old", mapId = 1, pages = listOf(EventPage(commands = listOf(say("Hi", DialogueChoice("Ok", listOf(set("old", 1))))))))
        s.selectDialogueChoice("p1", 7, 0)
        assertEquals(1, s.p().variables["old"])

        s.run(say("Wait", DialogueChoice("Ok")))
        assertEquals(1, s.pendingDialogues.size)
        s.removePlayer("p1")
        assertTrue(s.pendingDialogues.isEmpty())
    }

    @Test
    fun `a page cannot run forever`() {
        val s = sim()
        val many = List(5000) { EventCommand.AddVariable("n", 1) }
        s.run(*many.toTypedArray())
        assertEquals(2000, s.p().variables["n"])
    }

    @Test
    fun `the content rules check what the new steps point at, and how deep they nest`() {
        fun errors(vararg steps: EventCommand): List<String> {
            val event = EventDefinition(1, "E", pages = listOf(EventPage(commands = steps.toList())))
            val catalog = ContentCatalog(items = mapOf(3 to key), events = mapOf(1 to event))
            return ContentValidator.validate(catalog, MorganaClassic.definition).forDocument(ContentCollection.EVENTS, 1).errors.map { it.pointer }
        }
        fun check(type: EventConditionType, key: String = "", target: Int = 0, value: Int = 1) = EventCommand.Conditional(EventCondition(type, key, target, value = value))

        assertEquals(emptyList<String>(), errors(check(EventConditionType.HAS_ITEM, target = 3), check(EventConditionType.SWITCH, "met"), check(EventConditionType.LEVEL), EventCommand.Stop, set("n", 1), EventCommand.ShowMessage("Hi")))
        assertEquals(listOf("/pages/0/commands/0/condition/targetId"), errors(check(EventConditionType.HAS_ITEM, target = 9)))
        assertEquals(listOf("/pages/0/commands/0/condition/key"), errors(check(EventConditionType.VARIABLE)))
        assertEquals(listOf("/pages/0/commands/0/condition/targetId"), errors(check(EventConditionType.QUEST, target = 2)))
        assertEquals(listOf("/pages/0/commands/0/condition/targetId"), errors(check(EventConditionType.CLASS, target = 2)))
        assertEquals(listOf("/pages/0/commands/0/condition/targetId"), errors(check(EventConditionType.KNOWS_SPELL, target = 2)))
        assertEquals(listOf("/pages/0/commands/0/condition/value"), errors(check(EventConditionType.CHANCE, value = 101)))
        assertEquals(listOf("/pages/0/commands/0/spellId"), errors(EventCommand.TeachSpell(2)))
        assertEquals(listOf("/pages/0/commands/0/variableId"), errors(set(" ", 1)))
        assertEquals(listOf("/pages/0/commands/0/text"), errors(EventCommand.ShowMessage("")))
        // a broken step inside a branch is found
        assertEquals(listOf("/pages/0/commands/0/otherwise/0/itemId"), errors(EventCommand.Conditional(EventCondition(EventConditionType.LEVEL), otherwise = listOf(EventCommand.GiveItem(9, 1)))))

        var deep: EventCommand = EventCommand.Stop
        repeat(10) { deep = EventCommand.Conditional(EventCondition(EventConditionType.LEVEL), then = listOf(deep)) }
        assertTrue(errors(deep).single().endsWith("/then"))
    }

    @Test
    fun `testEventCommands requires EDIT_CONTENT and runs unsaved steps including dialogue resume`() {
        val s = sim()
        val steps = listOf(say("Testing?", DialogueChoice("Yes", listOf(set("tested", 1)))), set("done", 1))
        assertTrue(s.testEventCommands("p1", 99, steps).isEmpty())
        assertTrue(s.pendingDialogues.isEmpty())

        s.spawnOrUpdatePlayer(s.p().copy(accessLevel = AccessLevel.DEVELOPER))
        val out = s.testEventCommands("p1", 99, steps)
        assertEquals(listOf("Testing?"), out.lines())
        assertEquals(99, s.pendingDialogues["p1"]?.eventId)

        s.selectDialogueChoice("p1", 99, 0)
        assertEquals(1, s.p().variables["tested"])
        assertEquals(1, s.p().variables["done"])
        assertTrue(s.pendingDialogues.isEmpty())
    }

    @Test
    fun `world switches and world variables are shared across players and govern pages and conditionals`() {
        val s = sim()
        s.spawnOrUpdatePlayer(CharacterState(id = "p2", accountId = "a2", name = "Ally", position = WorldPosition.fromTile(1, 4, 3, Direction.DOWN)))
        s.events[7] = EventDefinition(
            id = 7,
            name = "Gate",
            mapId = 1,
            pages = listOf(
                EventPage(0, commands = listOf(EventCommand.ShowMessage("Locked"))),
                EventPage(
                    1,
                    condition = EventPageCondition(
                        requiredSwitchId = "gate_open",
                        switchScope = VariableScope.WORLD,
                        variableId = "bell_count",
                        variableScope = VariableScope.WORLD,
                        minVariableValue = 2
                    ),
                    commands = listOf(EventCommand.ShowMessage("Welcome through"))
                )
            )
        )

        s.run(
            EventCommand.SetSwitch("gate_open", true, VariableScope.WORLD),
            EventCommand.AddVariable("bell_count", 2, VariableScope.WORLD)
        )
        assertEquals(true, s.worldSwitches["gate_open"])
        assertEquals(2, s.worldVariables["bell_count"])
        assertFalse("gate_open" in s.p().switches)

        val p2Events = mutableListOf<WorldSimulationEvent>()
        val activePage = s.resolveActiveEventPage(s.players.getValue("p2"), s.events.getValue(7))!!
        assertEquals(1, activePage.pageIndex)
        s.executeEventCommands("p2", 7, activePage.commands, p2Events)
        assertTrue(p2Events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message == "Welcome through" })

        assertTrue(
            holds(
                s,
                EventCondition(EventConditionType.SWITCH, key = "gate_open", scope = VariableScope.WORLD, value = 1)
            )
        )
        assertTrue(
            holds(
                s,
                EventCondition(EventConditionType.VARIABLE, key = "bell_count", scope = VariableScope.WORLD, comparison = Comparison.EQUAL, value = 2)
            )
        )
        assertFalse(
            holds(
                s,
                EventCondition(EventConditionType.SWITCH, key = "gate_open", scope = VariableScope.PLAYER, value = 1)
            )
        )
    }

    @Test
    fun `common events run inline, support dialogue resume, respect Stop, guard recursion, and validate references`() {
        val s = sim()
        s.commonEvents[10] = CommonEventDefinition(
            id = 10,
            name = "Ask Blessing",
            commands = listOf(
                say("Accept blessing?", DialogueChoice("Yes", listOf(EventCommand.SetVariable("blessed", 1, VariableScope.WORLD)))),
                EventCommand.ShowMessage("Blessed")
            )
        )
        val out = s.run(EventCommand.RunCommonEvent(10), set("after_ce", 1))
        assertEquals(listOf("Accept blessing?"), out.lines())
        assertNull(s.p().variables["after_ce"])

        val resumed = s.selectDialogueChoice("p1", 7, 0)
        assertEquals(1, s.worldVariables["blessed"])
        assertEquals(1, s.p().variables["after_ce"])
        assertTrue(resumed.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message == "Blessed" })

        s.commonEvents[11] = CommonEventDefinition(11, "Aborter", commands = listOf(set("before_stop", 1), EventCommand.Stop))
        s.run(EventCommand.RunCommonEvent(11), set("after_stop", 1))
        assertEquals(1, s.p().variables["before_stop"])
        assertNull(s.p().variables["after_stop"])

        // Mutual recursion terminates safely at step limit without throwing.
        s.commonEvents[12] = CommonEventDefinition(12, "Ping", commands = listOf(EventCommand.AddVariable("hops", 1), EventCommand.RunCommonEvent(13)))
        s.commonEvents[13] = CommonEventDefinition(13, "Pong", commands = listOf(EventCommand.RunCommonEvent(12)))
        s.run(EventCommand.RunCommonEvent(12))
        assertEquals(667, s.p().variables["hops"])

        val badEvent = EventDefinition(1, "Caller", pages = listOf(EventPage(commands = listOf(EventCommand.RunCommonEvent(99)))))
        val badCommon = CommonEventDefinition(1, "Broken", commands = listOf(EventCommand.GiveItem(99, 1)))
        val catalog = ContentCatalog(items = mapOf(3 to key), events = mapOf(1 to badEvent), commonEvents = mapOf(1 to badCommon))
        val report = ContentValidator.validate(catalog, MorganaClassic.definition)
        assertTrue(report.forDocument(ContentCollection.EVENTS, 1).errors.any { it.pointer == "/pages/0/commands/0/commonEventId" })
        assertTrue(report.forDocument(ContentCollection.COMMON_EVENTS, 1).errors.any { it.pointer == "/commands/0/itemId" })
    }

    @Test
    fun `wait pauses execution until exact resume tick, clears on logout, and plays effects`() {
        val s = sim()
        val out = s.run(
            EventCommand.PlaySound("click"),
            EventCommand.ShowPicture("banner", "Welcome", durationTicks = 40),
            EventCommand.Wait(3),
            EventCommand.ShowMessage("Resumed"),
            set("after_wait", 1)
        )
        assertEquals(listOf("click"), out.filterIsInstance<WorldSimulationEvent.SoundPlayed>().map { it.soundId })
        val pic = out.filterIsInstance<WorldSimulationEvent.PictureShown>().single()
        assertEquals("banner", pic.pictureId)
        assertEquals("Welcome", pic.title)
        assertEquals(40, pic.durationTicks)
        assertNull(s.p().variables["after_wait"])
        assertEquals(1, s.pendingEventWaits.size)

        // Ticks 1 and 2 have not reached tick 3 yet.
        assertTrue(s.stepTick().filterIsInstance<WorldSimulationEvent.ChatEmitted>().isEmpty())
        assertTrue(s.stepTick().filterIsInstance<WorldSimulationEvent.ChatEmitted>().isEmpty())
        assertNull(s.p().variables["after_wait"])

        // Tick 3 resumes the remaining steps.
        val tick3 = s.stepTick()
        assertTrue(tick3.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message == "Resumed" })
        assertEquals(1, s.p().variables["after_wait"])
        assertTrue(s.pendingEventWaits.isEmpty())

        // Removing the player cancels any pending wait cleanly.
        s.run(EventCommand.Wait(5), set("never", 1))
        assertEquals(1, s.pendingEventWaits.size)
        s.removePlayer("p1")
        assertTrue(s.pendingEventWaits.isEmpty())
    }

    @Test
    fun `moveNpc turns and moves the nearest NPC and validates media and move steps`() {
        val s = WorldSimulation(game = MorganaClassic.COMPILED)
        val npc = NpcDefinition(id = 4, name = "Guard", behavior = NpcBehavior.FRIENDLY_ROAM)
        s.npcs[4] = npc
        s.registerMap(
            MapDefinition(
                id = 1,
                name = "Gate",
                width = 6,
                height = 6,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 4, spawnX = 3, spawnY = 3, respawnTicks = 100))
            )
        )
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a1", name = "Tester", position = WorldPosition.fromTile(1, 2, 3, Direction.RIGHT)))

        val faceOut = mutableListOf<WorldSimulationEvent>()
        s.executeEventCommands("p1", 1, listOf(EventCommand.MoveNpc(4, Direction.LEFT, steps = 2, faceOnly = true)), faceOut)
        var guard = s.mapNpcs.getValue(1).single()
        assertEquals(Direction.LEFT, guard.direction)
        assertEquals(3, guard.tileX)
        assertEquals(3, guard.tileY)
        assertEquals(guard.instanceId, faceOut.filterIsInstance<WorldSimulationEvent.NpcUpdated>().single().npc.instanceId)

        // Move right up to the right edge (x=5 from x=3 -> at most 2 steps even when asking for 5).
        val moveOut = mutableListOf<WorldSimulationEvent>()
        s.executeEventCommands("p1", 1, listOf(EventCommand.MoveNpc(4, Direction.RIGHT, steps = 5, faceOnly = false)), moveOut)
        guard = s.mapNpcs.getValue(1).single()
        assertEquals(5, guard.tileX)
        assertEquals(5, guard.homeX)
        assertEquals(Direction.RIGHT, guard.direction)
        assertTrue(moveOut.filterIsInstance<WorldSimulationEvent.NpcUpdated>().isNotEmpty())

        val badEvent = EventDefinition(
            1,
            "Invalid",
            pages = listOf(
                EventPage(
                    commands = listOf(
                        EventCommand.Wait(0),
                        EventCommand.PlaySound(" "),
                        EventCommand.ShowPicture("", durationTicks = -1),
                        EventCommand.MoveNpc(99, Direction.DOWN, steps = 0)
                    )
                )
            )
        )
        val report = ContentValidator.validate(ContentCatalog(npcs = mapOf(4 to npc), events = mapOf(1 to badEvent)), MorganaClassic.definition)
        val pointers = report.forDocument(ContentCollection.EVENTS, 1).errors.map { it.pointer }
        assertTrue("/pages/0/commands/0/ticks" in pointers)
        assertTrue("/pages/0/commands/1/soundId" in pointers)
        assertTrue("/pages/0/commands/2/pictureId" in pointers)
        assertTrue("/pages/0/commands/2/durationTicks" in pointers)
        assertTrue("/pages/0/commands/3/npcId" in pointers)
        assertTrue("/pages/0/commands/3/steps" in pointers)
    }

    @Test
    fun `placeholders expand in dialogues, choices, prompts, and messages`() {
        val s = sim()
        s.run(
            EventCommand.GiveItem(3, 2),
            EventCommand.SetSwitch("met", true),
            EventCommand.SetVariable("visits", 4),
            EventCommand.SetVariable("bells", 9, VariableScope.WORLD)
        )
        s.worldStringVariables["motto"] = "Peace"
        s.players["p1"] = s.p().copy(level = 7, stringVariables = mapOf("pet" to "Fox"))

        val out = s.run(
            EventCommand.ShowMessage("{player} lv{level} in {map}: {item:3}x {item_name:3}, visits={visits}, world={world:bells}, sw={switch:met}, pet={pet}, unset={unknown_var}, literal={with space}"),
            EventCommand.ShowDialogue(
                speakerName = "Guide of {map}",
                text = "Hello {player}, motto is {world_text:motto} and you carry {item:3} {item_name:3}.",
                choices = listOf(DialogueChoice("Take {visits} coins"))
            )
        )
        val msg = out.filterIsInstance<WorldSimulationEvent.ChatEmitted>().single().message
        assertEquals("Tester lv7 in Town: 2x Iron Key, visits=4, world=9, sw=on, pet=Fox, unset=0, literal={with space}", msg)

        val dlg = out.filterIsInstance<WorldSimulationEvent.OpenUiRequested>().single()
        assertEquals("Guide of Town", dlg.speakerName)
        assertEquals("Hello Tester, motto is Peace and you carry 2 Iron Key.", dlg.dialogueText)
        assertEquals(listOf("Take 4 coins"), dlg.choiceLabels)
    }

    @Test
    fun `text input pauses, stores text or numeric values in player or world scope, supports cancel, and validates rules`() {
        val s = sim()
        val firstPrompt = s.run(
            EventCommand.TextInput("Name your pet {player}:", "pet", VariableScope.PLAYER, isNumeric = false, defaultValue = "Pup", maxLength = 5),
            EventCommand.ShowDialogue("", "Your pet is {pet}!")
        )
        val req1 = firstPrompt.filterIsInstance<WorldSimulationEvent.TextInputRequested>().single()
        assertEquals("Name your pet Tester:", req1.prompt)
        assertEquals("Pup", req1.defaultValue)
        assertEquals(5, req1.maxLength)
        assertTrue(firstPrompt.filterIsInstance<WorldSimulationEvent.OpenUiRequested>().isEmpty())
        assertEquals(7, s.pendingTextInputs["p1"]?.eventId)

        val resumed1 = s.submitTextInput("p1", 7, "  Shadowfang  ")
        assertEquals("Shado", s.p().stringVariables["pet"])
        assertTrue(s.pendingTextInputs.isEmpty())
        assertEquals("Your pet is Shado!", resumed1.filterIsInstance<WorldSimulationEvent.OpenUiRequested>().single().dialogueText)

        // Numeric world variable input + cancel path.
        s.run(
            EventCommand.TextInput("Set tax rate:", "tax", VariableScope.WORLD, isNumeric = true, maxLength = 8),
            EventCommand.ShowMessage("Tax={world:tax}")
        )
        val resumed2 = s.submitTextInput("p1", 7, "15")
        assertEquals(15, s.worldVariables["tax"])
        assertEquals("Tax=15", resumed2.filterIsInstance<WorldSimulationEvent.ChatEmitted>().single().message)

        // Cancelling leaves variables untouched and stops remaining steps; removePlayer clears pending input.
        s.run(
            EventCommand.TextInput("Password:", "pw"),
            set("unlocked", 1)
        )
        assertTrue(s.submitTextInput("p1", 7, "secret", cancelled = true).isEmpty())
        assertNull(s.p().stringVariables["pw"])
        assertNull(s.p().variables["unlocked"])

        s.run(EventCommand.TextInput("Wait on logout:", "late"))
        assertEquals(1, s.pendingTextInputs.size)
        s.removePlayer("p1")
        assertTrue(s.pendingTextInputs.isEmpty())

        val badEvent = EventDefinition(
            1,
            "InvalidInput",
            pages = listOf(EventPage(commands = listOf(EventCommand.TextInput(" ", " ", maxLength = 0))))
        )
        val pointers = ContentValidator.validate(ContentCatalog(events = mapOf(1 to badEvent)), MorganaClassic.definition)
            .forDocument(ContentCollection.EVENTS, 1).errors.map { it.pointer }
        assertTrue("/pages/0/commands/0/prompt" in pointers)
        assertTrue("/pages/0/commands/0/variableId" in pointers)
        assertTrue("/pages/0/commands/0/maxLength" in pointers)
    }

    @Test
    fun `conditional evaluates multiple conditions with ALL and ANY match modes and validates extra conditions`() {
        val s = sim()
        s.run(
            EventCommand.SetSwitch("has_pass", true),
            EventCommand.SetVariable("reputation", 5)
        )
        val c1 = EventCondition(EventConditionType.SWITCH, key = "has_pass", value = 1)
        val c2True = EventCondition(EventConditionType.VARIABLE, key = "reputation", comparison = Comparison.AT_LEAST, value = 5)
        val c2False = EventCondition(EventConditionType.VARIABLE, key = "reputation", comparison = Comparison.AT_LEAST, value = 10)

        s.run(
            EventCommand.Conditional(c1, then = listOf(set("all_ok", 1)), otherwise = listOf(set("all_ok", -1)), extraConditions = listOf(c2True), matchMode = ConditionMatchMode.ALL),
            EventCommand.Conditional(c1, then = listOf(set("all_fail", 1)), otherwise = listOf(set("all_fail", -1)), extraConditions = listOf(c2False), matchMode = ConditionMatchMode.ALL),
            EventCommand.Conditional(c2False, then = listOf(set("any_ok", 1)), otherwise = listOf(set("any_ok", -1)), extraConditions = listOf(c1), matchMode = ConditionMatchMode.ANY),
            EventCommand.Conditional(
                condition = EventCondition(EventConditionType.SWITCH, key = "nope", value = 1),
                then = listOf(set("any_fail", 1)),
                otherwise = listOf(set("any_fail", -1)),
                extraConditions = listOf(c2False),
                matchMode = ConditionMatchMode.ANY
            )
        )
        assertEquals(1, s.p().variables["all_ok"])
        assertEquals(-1, s.p().variables["all_fail"])
        assertEquals(1, s.p().variables["any_ok"])
        assertEquals(-1, s.p().variables["any_fail"])

        val badEvent = EventDefinition(
            1,
            "MultiCondBad",
            pages = listOf(
                EventPage(
                    commands = listOf(
                        EventCommand.Conditional(
                            condition = c1,
                            extraConditions = listOf(
                                EventCondition(EventConditionType.HAS_ITEM, targetId = 999, value = 1),
                                EventCondition(EventConditionType.CHANCE, value = 150)
                            ),
                            matchMode = ConditionMatchMode.ANY
                        )
                    )
                )
            )
        )
        val pointers = ContentValidator.validate(ContentCatalog(items = mapOf(3 to key), events = mapOf(1 to badEvent)), MorganaClassic.definition)
            .forDocument(ContentCollection.EVENTS, 1).errors.map { it.pointer }
        assertTrue("/pages/0/commands/0/extraConditions/0/targetId" in pointers)
        assertTrue("/pages/0/commands/0/extraConditions/1/value" in pointers)
    }

    @Test
    fun `common events can trigger automatically on login, on level-up, and on a tick timer`() {
        val s = WorldSimulation(game = MorganaClassic.COMPILED)
        s.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        s.items[key.id] = key
        s.commonEvents[1] = CommonEventDefinition(
            id = 1,
            name = "Welcome Setup",
            trigger = CommonEventTrigger.ON_LOGIN,
            commands = listOf(EventCommand.AddVariable("logins", 1), EventCommand.ShowMessage("Welcome, {player}!"))
        )
        s.commonEvents[2] = CommonEventDefinition(
            id = 2,
            name = "Level Reward",
            trigger = CommonEventTrigger.ON_LEVEL_UP,
            commands = listOf(EventCommand.AddVariable("levelUps", 1), EventCommand.GiveItem(3, 1))
        )
        s.commonEvents[3] = CommonEventDefinition(
            id = 3,
            name = "Pulse",
            trigger = CommonEventTrigger.ON_TIMER,
            intervalTicks = 5,
            commands = listOf(EventCommand.AddVariable("pulses", 1))
        )

        val loginEvents = s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a1", name = "Hero", position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)))
        assertEquals(1, s.p().variables["logins"])
        assertTrue(loginEvents.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message == "Welcome, Hero!" })

        // Re-updating an already present player does not re-fire ON_LOGIN
        s.spawnOrUpdatePlayer(s.p())
        assertEquals(1, s.p().variables["logins"])

        // Timer only fires on multiples of intervalTicks (ticks 5, 10)
        repeat(4) { s.stepTick() }
        assertNull(s.p().variables["pulses"])
        s.stepTick()
        assertEquals(1, s.p().variables["pulses"])
        repeat(5) { s.stepTick() }
        assertEquals(2, s.p().variables["pulses"])

        // Small XP that does not level up does not trigger ON_LEVEL_UP
        s.run(EventCommand.GiveXp(1))
        assertNull(s.p().variables["levelUps"])

        // Large XP triggers level-up and ON_LEVEL_UP reward
        s.run(EventCommand.GiveXp(500))
        assertEquals(1, s.p().variables["levelUps"])
        assertTrue(holds(s, EventCondition(EventConditionType.HAS_ITEM, targetId = 3, value = 1)))

        // Validation checks ON_TIMER intervalTicks bounds
        val badTimer = CommonEventDefinition(9, "BadTimer", trigger = CommonEventTrigger.ON_TIMER, intervalTicks = 0)
        val okManual = CommonEventDefinition(10, "ManualDefault", trigger = CommonEventTrigger.MANUAL, intervalTicks = 0)
        val res = ContentValidator.validate(ContentCatalog(commonEvents = mapOf(9 to badTimer, 10 to okManual)), MorganaClassic.definition)
        assertEquals(listOf("/intervalTicks"), res.forDocument(ContentCollection.COMMON_EVENTS, 9).errors.map { it.pointer })
        assertTrue(res.forDocument(ContentCollection.COMMON_EVENTS, 10).errors.isEmpty())
    }

    @Test
    fun `labels and loops support forward and backward jumps, nested break_loop, dialogue pauses inside loops, and validation`() {
        val s = sim()

        // Forward JumpToLabel skips skipped step
        s.run(
            EventCommand.JumpToLabel("target"),
            set("skipped", 1),
            EventCommand.Label("target"),
            set("reached", 1)
        )
        assertNull(s.p().variables["skipped"])
        assertEquals(1, s.p().variables["reached"])

        // Backward JumpToLabel across a Dialogue choice
        s.run(
            EventCommand.Label("ask"),
            EventCommand.AddVariable("attempts", 1),
            say("Ready?", DialogueChoice("No, ask again", listOf(EventCommand.JumpToLabel("ask"))), DialogueChoice("Yes", listOf(set("done", 1)))),
            set("after_retry", 1)
        )
        assertEquals(1, s.p().variables["attempts"])
        s.selectDialogueChoice("p1", 7, 0) // chooses "No, ask again" -> jumps back to "ask"
        assertEquals(2, s.p().variables["attempts"])
        assertNull(s.p().variables["done"])
        s.selectDialogueChoice("p1", 7, 1) // chooses "Yes"
        assertEquals(1, s.p().variables["done"])
        assertEquals(1, s.p().variables["after_retry"])

        // Loop with Conditional + BreakLoop and nested inner loop BreakLoop only exits inner loop
        s.run(
            EventCommand.Loop(
                listOf(
                    EventCommand.AddVariable("outer", 1),
                    EventCommand.Loop(
                        listOf(
                            EventCommand.AddVariable("inner", 1),
                            EventCommand.BreakLoop
                        )
                    ),
                    EventCommand.Conditional(
                        EventCondition(EventConditionType.VARIABLE, key = "outer", comparison = Comparison.AT_LEAST, value = 3),
                        then = listOf(EventCommand.BreakLoop)
                    )
                )
            ),
            set("after_loop", 1)
        )
        assertEquals(3, s.p().variables["outer"])
        assertEquals(3, s.p().variables["inner"])
        assertEquals(1, s.p().variables["after_loop"])

        // Infinite loop without break terminates safely at MAX_EVENT_STEPS
        s.run(EventCommand.Loop(listOf(EventCommand.AddVariable("ticks", 1))), set("never_reached", 1))
        assertTrue((s.p().variables["ticks"] ?: 0) > 100)
        assertNull(s.p().variables["never_reached"])

        // Validation catches blank label/jump names and deep loop nesting
        val badEvent = EventDefinition(
            1, "Loops",
            pages = listOf(
                EventPage(
                    commands = listOf(
                        EventCommand.Label(""),
                        EventCommand.JumpToLabel(" "),
                        EventCommand.Loop(listOf(EventCommand.GiveItem(999, 1)))
                    )
                )
            )
        )
        val pointers = ContentValidator.validate(ContentCatalog(events = mapOf(1 to badEvent)), MorganaClassic.definition)
            .forDocument(ContentCollection.EVENTS, 1).errors.map { it.pointer }
        assertTrue("/pages/0/commands/0/name" in pointers)
        assertTrue("/pages/0/commands/1/name" in pointers)
        assertTrue("/pages/0/commands/2/commands/0/itemId" in pointers)
    }

    @Test
    fun `disconnecting mid-dialogue or mid-wait finishes non-interactive steps without guessing choices`() {
        // 1. Mid-monologue -> Wait -> second monologue -> SetVariable -> stops before choice prompt
        val s1 = sim()
        s1.run(
            say("Welcome"),
            set("step1", 1),
            EventCommand.Wait(40),
            say("Here you go"),
            EventCommand.SetSwitch("claimed", true),
            say("Want bonus?", DialogueChoice("Yes", listOf(set("bonus", 1)))),
            set("after_choice", 1)
        )
        assertNull(s1.p().variables["step1"])
        val out1 = s1.removePlayer("p1")
        assertTrue(s1.pendingDialogues.isEmpty())
        assertTrue(s1.pendingEventWaits.isEmpty())
        val final1 = out1.filterIsInstance<WorldSimulationEvent.CharacterUpdated>().last().character
        assertEquals(1, final1.variables["step1"])
        assertEquals(true, final1.switches["claimed"])
        assertNull(final1.variables["bonus"])
        assertNull(final1.variables["after_choice"])
        assertTrue(out1.none { it is WorldSimulationEvent.OpenUiRequested })

        // 2. Pending Wait flushes trailing reward steps on disconnect
        val s2 = sim()
        s2.run(EventCommand.Wait(30), set("after_wait", 9))
        assertNull(s2.p().variables["after_wait"])
        val out2 = s2.removePlayer("p1")
        assertEquals(9, out2.filterIsInstance<WorldSimulationEvent.CharacterUpdated>().last().character.variables["after_wait"])
        assertTrue(s2.pendingEventWaits.isEmpty())

        // 3. Pending choice dialogue or TextInput does NOT run trailing steps on disconnect
        val s3 = sim()
        s3.run(say("Choose", DialogueChoice("A", listOf(set("chose", 1)))), set("after", 1))
        val out3 = s3.removePlayer("p1")
        assertTrue(out3.none { it is WorldSimulationEvent.CharacterUpdated && it.character.variables["after"] != null })
        assertTrue(s3.pendingDialogues.isEmpty())

        val s4 = sim()
        s4.run(EventCommand.TextInput("Code?", "code"), set("after_input", 1))
        val out4 = s4.removePlayer("p1")
        assertTrue(out4.none { it is WorldSimulationEvent.CharacterUpdated && it.character.variables["after_input"] != null })
        assertTrue(s4.pendingTextInputs.isEmpty())
    }

    @Test
    fun `warping to a map via an event advances VISIT_MAP quest objectives`() {
        val s = sim()
        s.maps[2] = MapDefinition(id = 2, name = "Bazaar", width = 10, height = 10)
        s.quests[15] = QuestDefinition(
            id = 15,
            name = "Market Day",
            objectives = listOf(QuestObjective(QuestObjectiveType.VISIT_MAP, targetId = 2, requiredCount = 1, description = "Reach the Bazaar"))
        )
        s.spawnOrUpdatePlayer(s.p().copy(quests = mapOf(15 to PlayerQuestState(questId = 15, status = QuestStatus.IN_PROGRESS, objectiveCounts = listOf(0)))))

        s.run(EventCommand.WarpPlayer(mapId = 2, tileX = 3, tileY = 4))

        assertEquals(2, s.p().position.mapId)
        assertEquals(listOf(1), s.p().quests[15]?.objectiveCounts)
    }
}
