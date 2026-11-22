package com.morgana.client.editor.events

import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventPageCondition
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.event.QuestStatus
import com.morgana.protocol.MapSummary
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventEditSessionTest {
    private val catalog = WorldContentCatalogDto(
        items = listOf(ItemDefinition(id = 3, name = "Iron Key")),
        maps = listOf(MapSummary(1, "Town", 20, 15, 1))
    )

    private val talk = EventCommand.ShowDialogue(
        "Mira", "Will you help?",
        choices = listOf(
            DialogueChoice("Yes", listOf(EventCommand.GiveItem(3, 1), EventCommand.SetSwitch("helped", true))),
            DialogueChoice("No")
        )
    )
    private val event = EventDefinition(7, "Mira", mapId = 1, tileX = 4, tileY = 5, pages = listOf(EventPage(commands = listOf(talk, EventCommand.HealPlayerFull))))

    @Test
    fun `rows show steps, answers and nested steps in plain words`() {
        val rows = EventEditSession(event).rows(catalog)
        assertEquals(
            listOf(
                "0|1. Mira says: \"Will you help?\"", "1|If the player answers \"Yes\":", "2|1. Give 1 x Iron Key (#3)",
                "2|2. Turn switch helped on", "1|If the player answers \"No\":", "0|2. Heal the player fully"
            ),
            rows.map { "${it.depth}|${it.text}" }
        )
        assertEquals(listOf(listOf(0), listOf(0, 0), listOf(0, 0, 0), listOf(0, 0, 1), listOf(0, 1), listOf(1)), rows.map { it.path })
        assertEquals(listOf(false, true, false, false, true, false), rows.map { it.isChoice })
    }

    @Test
    fun `a new step goes after the selection, under a selected answer, or at the end`() {
        val s = EventEditSession(event)
        s.addCommand(EventCommand.OpenBank)
        assertEquals(listOf(2), s.selection)
        assertEquals(EventCommand.OpenBank, s.page.commands[2])

        s.select(listOf(0))
        s.addCommand(EventCommand.GiveXp(5))
        assertEquals(listOf(1), s.selection)
        assertEquals(listOf(talk, EventCommand.GiveXp(5), EventCommand.HealPlayerFull, EventCommand.OpenBank), s.page.commands)

        s.select(listOf(0, 1)) // the "No" answer
        s.addCommand(EventCommand.GiveXp(1))
        assertEquals(listOf(0, 1, 0), s.selection)
        assertEquals(listOf(EventCommand.GiveXp(1)), (s.page.commands[0] as EventCommand.ShowDialogue).choices[1].commands)

        s.select(listOf(0, 0, 0)) // a nested step
        s.addCommand(EventCommand.OpenBank)
        assertEquals(listOf(0, 0, 1), s.selection)
        assertEquals(3, (s.page.commands[0] as EventCommand.ShowDialogue).choices[0].commands.size)
    }

    @Test
    fun `steps and answers move, copy and delete among their siblings`() {
        val s = EventEditSession(event)
        s.select(listOf(0, 0, 0))
        assertFalse(s.canMove(-1))
        assertTrue(s.canMove(1))
        s.moveSelected(1)
        assertEquals(listOf(0, 0, 1), s.selection)
        assertEquals(EventCommand.SetSwitch("helped", true), s.commandAt(listOf(0, 0, 0)))

        s.select(listOf(0, 1)) // the "No" answer moves above "Yes" and takes nothing with it
        s.moveSelected(-1)
        assertEquals(listOf("No", "Yes"), (s.page.commands[0] as EventCommand.ShowDialogue).choices.map { it.label })
        assertEquals(2, s.choiceAt(listOf(0, 1))!!.commands.size)

        s.select(listOf(0, 1))
        s.duplicateSelected() // an answer is copied with its steps
        assertEquals(listOf(0, 2), s.selection)
        assertEquals(s.choiceAt(listOf(0, 1)), s.choiceAt(listOf(0, 2)))

        s.deleteSelected()
        assertEquals(listOf(0), s.selection) // back on the dialogue
        assertEquals(2, (s.page.commands[0] as EventCommand.ShowDialogue).choices.size)

        s.select(listOf(1))
        s.deleteSelected()
        assertNull(s.selection)
        assertEquals(1, s.page.commands.size)
    }

    @Test
    fun `steps go under the Then and Otherwise parts of an If step, which cannot themselves be moved or deleted`() {
        val s = EventEditSession(EventDefinition(1, "E", pages = listOf(EventPage(commands = listOf(EventCommand.Conditional())))))
        s.select(listOf(0, 0)) // Then
        assertTrue(s.selectedIsFixedBranch)
        assertFalse(s.canEditSelection)
        assertFalse(s.canMove(1))
        s.addCommand(EventCommand.OpenBank)
        assertEquals(listOf(0, 0, 0), s.selection)
        s.select(listOf(0, 1)) // Otherwise
        s.addCommand(EventCommand.Stop)
        s.select(listOf(0, 1))
        s.deleteSelected()
        s.duplicateSelected()
        s.setSelectedValues(mapOf("label" to "x"))
        val step = s.page.commands.single() as EventCommand.Conditional
        assertEquals(listOf(EventCommand.OpenBank), step.then)
        assertEquals(listOf(EventCommand.Stop), step.otherwise)
        s.select(listOf(0, 2)) // there is no third part
        assertNull(s.selection)

        // An If inside an answer inside an If: paths keep working at depth.
        s.select(listOf(0, 0, 0))
        s.addCommand(EventCommand.ShowDialogue("", "Sure?", choices = listOf(DialogueChoice("Yes"))))
        s.select(listOf(0, 0, 1, 0))
        s.addCommand(EventCommand.Conditional())
        s.select(listOf(0, 0, 1, 0, 0, 1))
        s.addCommand(EventCommand.GiveXp(5))
        assertEquals(EventCommand.GiveXp(5), s.commandAt(listOf(0, 0, 1, 0, 0, 1, 0)))
        s.select(listOf(0)) // the If step itself can be copied, with everything under it
        assertTrue(s.canEditSelection)
        s.duplicateSelected()
        assertEquals(s.page.commands[0], s.page.commands[1])
    }

    @Test
    fun `answers are added to the selected dialogue with names that do not repeat`() {
        val s = EventEditSession(EventDefinition(1, "E", pages = listOf(EventPage(commands = listOf(EventCommand.ShowDialogue("", "Hi"), EventCommand.OpenBank)))))
        assertFalse(s.canAddChoice)
        s.select(listOf(1))
        assertFalse(s.canAddChoice) // not a dialogue
        s.select(listOf(0))
        s.addChoice()
        assertEquals(listOf(0, 0), s.selection)
        s.addChoice() // from a selected answer: its dialogue gets another
        s.addChoice()
        assertEquals(listOf("Yes", "No", "Answer 3"), (s.page.commands[0] as EventCommand.ShowDialogue).choices.map { it.label })
        s.setSelectedValues(mapOf("label" to " Maybe "))
        assertEquals("Maybe", s.selectedChoice!!.label)
    }

    @Test
    fun `typed values change the selected step and keep a dialogue's answers`() {
        val s = EventEditSession(event)
        s.select(listOf(0))
        s.setSelectedValues(mapOf("speaker" to "Old Mira", "text" to "Help me?", "portrait" to "2"))
        val d = s.selectedCommand as EventCommand.ShowDialogue
        assertEquals("Old Mira", d.speakerName)
        assertEquals(2, d.portraitId)
        assertEquals(talk.choices, d.choices)

        s.select(listOf(0, 0, 0))
        s.setSelectedValues(mapOf("item" to "3", "amount" to "nonsense")) // a bad number keeps the old amount
        assertEquals(EventCommand.GiveItem(3, 1), s.selectedCommand)
    }

    @Test
    fun `pages are added, copied, reordered and deleted, and stay numbered`() {
        val s = EventEditSession(event)
        s.duplicatePage()
        assertEquals(1, s.pageIndex)
        assertEquals(event.pages[0].commands, s.page.commands)
        s.setPageValues(mapOf("trigger" to "PLAYER_TOUCH", "switchId" to "helped", "switchValue" to "true", "questId" to "0", "questStatus" to "", "variableId" to ""))
        assertEquals(EventTriggerType.PLAYER_TOUCH, s.page.trigger)
        assertEquals("helped", s.page.condition.requiredSwitchId)
        s.addPage()
        assertEquals(2, s.pageIndex)
        assertEquals(listOf(0, 1, 2), s.draft.pages.map { it.pageIndex })
        s.movePage(-1)
        assertEquals(1, s.pageIndex)
        assertTrue(s.page.commands.isEmpty())
        assertEquals(listOf(0, 1, 2), s.draft.pages.map { it.pageIndex })
        s.deletePage()
        assertEquals(2, s.draft.pages.size)
        assertEquals(0, s.pageIndex)
        s.deletePage()
        s.deletePage() // the last page cannot be deleted
        assertEquals(1, s.draft.pages.size)
        assertEquals(0, s.draft.pages[0].pageIndex)
    }

    @Test
    fun `undo and redo step through changes, and typing in one form is one step`() {
        val s = EventEditSession(event)
        assertFalse(s.isDirty)
        s.setEventValues(mapOf("name" to "M"))
        s.setEventValues(mapOf("name" to "Mi"))
        s.setEventValues(mapOf("name" to "Mir"))
        s.select(listOf(1))
        s.deleteSelected()
        assertTrue(s.isDirty)
        s.undo()
        assertEquals(2, s.page.commands.size)
        assertEquals("Mir", s.draft.name)
        s.undo() // all three keystrokes at once
        assertEquals("Mira", s.draft.name)
        assertFalse(s.isDirty)
        assertFalse(s.canUndo)
        s.redo()
        assertEquals("Mir", s.draft.name)
        s.redo()
        assertEquals(1, s.page.commands.size)
        assertFalse(s.canRedo)
        s.setEventValues(mapOf("name" to "X")) // a new change drops the redo history
        assertFalse(s.canRedo)
    }

    @Test
    fun `an event with no pages or wrong page numbers is tidied on load, and a new event is dirty until saved`() {
        val s = EventEditSession(EventDefinition(1, "E", pages = emptyList()))
        assertEquals(1, s.draft.pages.size)
        assertFalse(s.isDirty) // tidying alone is not a change

        val fresh = EventEditSession(EventDefinition(2, "New"), saved = null)
        assertTrue(fresh.isDirty)
        fresh.markSaved(fresh.draft)
        assertFalse(fresh.isDirty)
    }

    @Test
    fun `moveToTile updates coordinates as an undo step and findAtTile respects unsaved drafts`() {
        val s = EventEditSession(event)
        s.moveToTile(2, 7, 8)
        assertEquals(2, s.draft.mapId)
        assertEquals(7, s.draft.tileX)
        assertEquals(8, s.draft.tileY)
        assertTrue(s.isDirty)
        // Old catalog position (1, 4, 5) is overridden by the unsaved draft at (2, 7, 8).
        assertNull(EventEditSession.findAtTile(listOf(event), s.draft, isNew = false, mapId = 1, tileX = 4, tileY = 5))
        assertEquals(s.draft, EventEditSession.findAtTile(listOf(event), s.draft, isNew = false, mapId = 2, tileX = 7, tileY = 8))
        s.undo()
        assertEquals(1, s.draft.mapId)
        assertEquals(4, s.draft.tileX)
        assertEquals(5, s.draft.tileY)
    }

    @Test
    fun `problems name the page and step and what is wrong`() {
        val bad = EventDefinition(
            9, "Bad", mapId = 1, tileX = 99, tileY = 0,
            pages = listOf(
                EventPage(condition = EventPageCondition(requiredItemId = 44, requiredQuestId = 0, requiredQuestStatus = QuestStatus.COMPLETED)),
                EventPage(commands = listOf(
                    EventCommand.GiveItem(8, 1), EventCommand.WarpPlayer(2, 0, 0), EventCommand.WarpPlayer(1, 20, 0),
                    EventCommand.ShowDialogue("", "", choices = listOf(DialogueChoice(" ", listOf(EventCommand.TakeItem(8, 1)))))
                ))
            )
        )
        val problems = EventEditSession(bad).problems(catalog)
        assertEquals(
            listOf(
                "Tile 99, 0 is outside Town (20 x 15)",
                "Page 1: item 44 does not exist",
                "Page 1: choose which quest must be completed",
                "Page 2, step 1: item 8 does not exist",
                "Page 2, step 2: map 2 does not exist",
                "Page 2, step 3: tile 20, 0 is outside Town (20 x 15)",
                "Page 2, step 4: What they say cannot be empty",
                "Page 2, step 4: answer 1 needs some text",
                "Page 2, step 4, answer 1, step 1: item 8 does not exist"
            ),
            problems
        )
        assertEquals(emptyList<String>(), EventEditSession(event).problems(catalog))
    }

    @Test
    fun `a quest condition with no state chosen is flagged because the server would not check it`() {
        val e = EventDefinition(1, "E", pages = listOf(EventPage(condition = EventPageCondition(requiredQuestId = 5))))
        assertTrue(EventForms.problems(e, WorldContentCatalogDto()).single().contains("in progress or completed"))
    }

    @Test
    fun `commandsFromSelection slices from top-level steps and nested branches through the end of the page`() {
        val innerStep = EventCommand.SetSwitch("inner", true)
        val elseStep = EventCommand.ShowMessage("Else")
        val check = EventCommand.Conditional(
            condition = EventCondition(EventConditionType.LEVEL),
            then = listOf(innerStep, EventCommand.Stop),
            otherwise = listOf(elseStep)
        )
        val after = EventCommand.ShowMessage("End")
        val rich = EventDefinition(
            id = 2,
            name = "Rich",
            pages = listOf(
                EventPage(
                    commands = listOf(
                        EventCommand.ShowDialogue("Mira", "Hi", choices = listOf(DialogueChoice("One", listOf(check)))),
                        after
                    )
                )
            )
        )
        val s = EventEditSession(rich)
        assertEquals(rich.pages[0].commands, s.commandsFromSelection())

        s.select(listOf(1))
        assertEquals(listOf(after), s.commandsFromSelection())

        s.select(listOf(0, 0))
        assertEquals(listOf(check, after), s.commandsFromSelection())

        s.select(listOf(0, 0, 0, 1, 0))
        assertEquals(listOf(elseStep, after), s.commandsFromSelection())
    }

    @Test
    fun `common event session supports details, step tree edits, undo-redo, problems, and callers`() {
        val ce = com.morgana.domain.event.CommonEventDefinition(
            id = 5,
            name = "Town Bell",
            description = "Rings once",
            commands = listOf(EventCommand.SetSwitch("bell", true, com.morgana.domain.event.VariableScope.WORLD))
        )
        val caller = EventDefinition(1, "Bell Rope", pages = listOf(EventPage(commands = listOf(EventCommand.RunCommonEvent(5)))))
        val fullCatalog = catalog.copy(events = listOf(caller), commonEvents = listOf(ce))
        val s = CommonEventEditSession(ce)
        assertFalse(s.isDirty)
        assertEquals("Called by: event Bell Rope (#1)", s.usedBy(fullCatalog))

        s.setCommonEventValues(mapOf("name" to "Great Bell", "description" to "Echoes"))
        assertTrue(s.isDirty)
        assertEquals("Great Bell", s.draft.name)
        s.undo()
        assertEquals("Town Bell", s.draft.name)
        s.redo()
        assertEquals("Echoes", s.draft.description)

        s.select(listOf(0))
        s.addCommand(EventCommand.ShowMessage("Dong!"))
        assertEquals(2, s.draft.commands.size)
        assertEquals(listOf(EventCommand.ShowMessage("Dong!")), s.commandsFromSelection())

        val bad = CommonEventEditSession(ce.copy(name = "", commands = listOf(EventCommand.GiveItem(999, 1))))
        assertEquals(listOf("Name cannot be empty", "Steps, step 1: item 999 does not exist"), bad.problems(fullCatalog))
    }

    @Test
    fun `conditional step can add, edit, undo, and remove extra conditions`() {
        val cond = EventCommand.Conditional(EventForms.newCondition(EventConditionType.SWITCH))
        val s = EventEditSession(EventDefinition(1, "Multi", pages = listOf(EventPage(commands = listOf(cond)))))
        s.select(listOf(0))
        s.updateSelectedCommand { if (it is EventCommand.Conditional) EventForms.withAddedCondition(it) else it }
        var cur = s.selectedCommand as EventCommand.Conditional
        assertEquals(1, cur.extraConditions.size)

        s.setSelectedValues(EventForms.valuesOf(cur) + mapOf("matchMode" to "ANY", "e0_check" to "LEVEL"))
        cur = s.selectedCommand as EventCommand.Conditional
        assertEquals(com.morgana.domain.event.ConditionMatchMode.ANY, cur.matchMode)
        assertEquals(EventConditionType.LEVEL, cur.extraConditions.single().type)

        s.updateSelectedCommand { if (it is EventCommand.Conditional) EventForms.withRemovedLastCondition(it) else it }
        assertTrue((s.selectedCommand as EventCommand.Conditional).extraConditions.isEmpty())

        s.undo()
        assertEquals(1, (s.selectedCommand as EventCommand.Conditional).extraConditions.size)
        s.redo()
        assertTrue((s.selectedCommand as EventCommand.Conditional).extraConditions.isEmpty())
    }

    @Test
    fun `loop steps can be populated, reordered, and tested from any nested step`() {
        val s = EventEditSession(EventDefinition(1, "Looper", pages = listOf(EventPage(commands = listOf(EventCommand.Loop())))))
        s.select(listOf(0, EventForms.LOOP_BODY))
        assertTrue(s.selectedIsFixedBranch)
        assertFalse(s.canEditSelection)
        s.addCommand(EventCommand.SetVariable("n", 1))
        assertEquals(listOf(0, EventForms.LOOP_BODY, 0), s.selection)
        s.addCommand(EventCommand.BreakLoop)
        assertEquals(listOf(EventCommand.BreakLoop), s.commandsFromSelection())
        assertEquals(2, (s.draft.pages[0].commands[0] as EventCommand.Loop).commands.size)
        s.moveSelected(-1)
        assertEquals(listOf(0, EventForms.LOOP_BODY, 0), s.selection)
        assertTrue(s.selectedCommand is EventCommand.BreakLoop)
    }

    @Test
    fun `duplicating an event to another map assigns a fresh id, preserves pages, and clamps coordinates`() {
        val source = EventDefinition(
            id = 4,
            name = "Town Sign",
            mapId = 1,
            tileX = 35,
            tileY = 28,
            pages = listOf(EventPage(commands = listOf(EventCommand.ShowMessage("Hello!"))))
        )
        val maps = listOf(
            com.morgana.protocol.MapSummary(1, "Big Town", 40, 40, revision = 1),
            com.morgana.protocol.MapSummary(2, "Tiny Cave", 12, 10, revision = 1)
        )
        val onSmallerMap = EventEditSession.duplicateToMap(source, newId = 5, targetMapId = 2, catalogMaps = maps)
        assertEquals(5, onSmallerMap.id)
        assertEquals("Town Sign (copy)", onSmallerMap.name)
        assertEquals(2, onSmallerMap.mapId)
        assertEquals(11, onSmallerMap.tileX)
        assertEquals(9, onSmallerMap.tileY)
        assertEquals(source.pages, onSmallerMap.pages)

        val unplaced = EventEditSession.duplicateToMap(source, newId = 6, targetMapId = 0, newName = "  ")
        assertEquals(0, unplaced.mapId)
        assertEquals(0, unplaced.tileX)
        assertEquals(0, unplaced.tileY)
        assertEquals("Town Sign (copy)", unplaced.name)
    }
}
