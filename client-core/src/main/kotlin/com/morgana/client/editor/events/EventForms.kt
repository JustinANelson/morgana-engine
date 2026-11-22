package com.morgana.client.editor.events

import com.morgana.client.editor.ContentNames
import com.morgana.client.editor.FieldRef
import com.morgana.client.editor.forms.FormField
import com.morgana.client.editor.forms.FormFieldType
import com.morgana.client.editor.forms.FormRules
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.event.ArenaQueueAction
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.ConditionMatchMode
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.Comparison
import com.morgana.domain.event.EventCommand
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.QuestCheck
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.event.VariableScope
import com.morgana.domain.spatial.Direction
import com.morgana.protocol.WorldContentCatalogDto

/** Every kind of step an event page can hold, with the words the editor shows for it. */
enum class EventCommandKind(val label: String, val group: String, val explain: String) {
    DIALOGUE("Say something", "Talk", "Shows a line of speech. Add choices to let the player answer; each choice has its own steps."),
    GIVE_ITEM("Give an item", "Items", "Puts items in the player's bag."),
    TAKE_ITEM("Take an item", "Items", "Removes items from the player's bag."),
    GIVE_XP("Give experience", "Player", "Adds experience points."),
    HEAL("Heal fully", "Player", "Restores the player to full."),
    WARP("Move the player", "Player", "Sends the player to a tile on a map."),
    SET_SWITCH("Set a switch", "Memory", "Remembers yes or no for this player under a name, for page conditions to check."),
    ADD_VARIABLE("Change a number", "Memory", "Adds to (or subtracts from) a named number kept for this player."),
    START_QUEST("Start a quest", "Quests", "Gives the player a quest."),
    COMPLETE_QUEST("Complete a quest", "Quests", "Finishes a quest and gives its reward."),
    OPEN_SHOP("Open a shop", "Windows", "Shows a shop's window."),
    OPEN_BANK("Open the bank", "Windows", "Shows the player's bank."),
    OPEN_CRAFTING("Open crafting", "Windows", "Shows the crafting window for a station."),
    PLAY_ANIMATION("Play an animation", "Effects", "Plays an animation on the player."),
    HOOK("Run plugin code", "Advanced", "Calls a named hook in a server plugin or script, with optional key=value settings."),
    CONDITIONAL("If ... otherwise", "Flow", "Checks something about the player and runs the steps under Then when it is true, or the steps under Otherwise when it is not. The event then carries on with the next step."),
    STOP("Stop the event", "Flow", "Ends the event here. Nothing after it runs, not even steps after the answer or the If it sits in."),
    SET_VARIABLE("Set a number", "Memory", "Sets a named number kept for this player to an exact value."),
    MESSAGE("Show a chat message", "Talk", "Puts a line in the player's chat window. Unlike Say something, there is no window to close."),
    UPGRADE_GEAR("Upgrade worn gear", "Items", "Opens a socket, sets a gem, raises the gear to its next tier or to a better quality, paying with items from the bag. The attempt can be chancy."),
    REBIRTH("Rebirth the player", "Player", "Sends a max-level player back to the rebirth start level, optionally as another class (0 = keep their class). Refused if they are too low or have no rebirths left."),
    PROMOTE("Promote the player", "Player", "Raises the player one promotion tier if they have reached its level, granting the tier's attribute points and spells."),
    REPAIR_GEAR("Repair gear", "Items", "Repairs all worn and carried gear for a price in the chosen currency item, or repairs nothing if the player cannot pay."),
    RESPEC_ATTRIBUTES("Reset attributes", "Player", "Takes back every attribute point the player has spent so they can place them again, for a price per point (0 is free)."),
    ARENA_QUEUE("Arena queue", "Player", "Queues the player for the arena (or leaves the queue, shows their rating, or lists the top ratings online), like the /arena command."),
    TEACH_SPELL("Teach a spell", "Player", "The player learns a spell, if they do not know it yet."),
    RUN_COMMON_EVENT("Run common event", "Flow", "Runs all steps from a shared common event in the library, then continues with the next step."),
    WAIT("Wait", "Flow", "Pauses the event for a number of ticks (20 ticks = 1 second) before running the next step."),
    PLAY_SOUND("Play a sound", "Effects", "Plays a sound effect for the player."),
    SHOW_PICTURE("Show a picture", "Effects", "Displays an image popup to the player."),
    MOVE_NPC("Move a creature", "World", "Turns or walks a creature on the player's current map."),
    TEXT_INPUT("Ask for input", "Talk", "Asks the player to type text or a number, stores it in a variable, and waits before the next step."),
    LABEL("Set a label", "Flow", "Marks a named anchor point you can jump to with Jump to label."),
    JUMP_TO_LABEL("Jump to label", "Flow", "Jumps to the matching Label step in this event or common event."),
    LOOP("Repeat loop", "Flow", "Repeats the steps inside until Break loop or Stop runs."),
    BREAK_LOOP("Break loop", "Flow", "Exits the innermost Repeat loop and continues with the step after it.")
}

/** One line of the step list: a step or a dialogue choice, with how deep it is nested. */
data class EventRow(val path: List<Int>, val depth: Int, val isChoice: Boolean, val text: String)

/**
 * The event editor's forms as plain data: the fields of the event, of a page and of each kind of step, how each becomes text
 * values and back, and a one-line description of a step in plain words. No graphics, so every rule is unit-tested.
 */
object EventForms {
    private val triggers = EventTriggerType.entries.map { it.name }
    private val commonTriggers = CommonEventTrigger.entries.map { it.name }
    private val statuses = QuestStatus.entries.map { it.name }
    private val stations = CraftingStationType.entries.map { it.name }
    private val scopes = VariableScope.entries.map { it.name }
    private val directions = Direction.entries.map { it.name }

    val eventFields: List<FormField> = listOf(
        FormField("name", "Name", FormFieldType.TEXT, required = true),
        FormField("mapId", "On map (0 = not placed on a map)", FormFieldType.INT, "0", min = 0, ref = FieldRef.MAP,
            hint = "An event can sit on a map tile, be started by a creature (set the creature's Event), or both."),
        FormField("tileX", "Tile X", FormFieldType.INT, "0", min = 0),
        FormField("tileY", "Tile Y", FormFieldType.INT, "0", min = 0)
    )

    val pageFields: List<FormField> = listOf(
        FormField("trigger", "Starts when", FormFieldType.CHOICE, triggers.first(), choices = triggers),
        FormField("spriteId", "Looks like (0 = invisible)", FormFieldType.SPRITE, "0", min = 0)
    )

    /** A page is used only when all of these hold; the last page whose conditions hold wins. */
    val conditionFields: List<FormField> = listOf(
        FormField("switchId", "Switch (empty = no check)", FormFieldType.TEXT),
        FormField("switchScope", "Switch stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
        FormField("switchValue", "Must be on (untick: must be off)", FormFieldType.BOOL, "true"),
        FormField("variableId", "Number (empty = no check)", FormFieldType.TEXT),
        FormField("variableScope", "Number stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
        FormField("minVariable", "The number must be at least", FormFieldType.INT, "0"),
        FormField("questId", "Quest (0 = no check)", FormFieldType.INT, "0", min = 0, ref = FieldRef.QUEST),
        FormField("questStatus", "The quest must be", FormFieldType.CHOICE, "", choices = statuses, optional = true),
        FormField("itemId", "Player carries item (0 = no check)", FormFieldType.INT, "0", min = 0, ref = FieldRef.ITEM),
        FormField("minLevel", "Player level at least", FormFieldType.INT, "1", min = 1, max = 100000)
    )

    val choiceFields: List<FormField> = listOf(FormField("label", "What the player can answer", FormFieldType.TEXT, required = true))

    fun eventValues(e: EventDefinition): Map<String, String> =
        mapOf("name" to e.name, "mapId" to e.mapId.toString(), "tileX" to e.tileX.toString(), "tileY" to e.tileY.toString())

    fun applyEvent(e: EventDefinition, v: Map<String, String>): EventDefinition = e.copy(
        name = FormRules.text(v, "name", e.name).ifBlank { e.name },
        mapId = FormRules.int(v, "mapId", e.mapId).coerceAtLeast(0),
        tileX = FormRules.int(v, "tileX", e.tileX).coerceAtLeast(0),
        tileY = FormRules.int(v, "tileY", e.tileY).coerceAtLeast(0)
    )

    fun pageValues(p: EventPage): Map<String, String> {
        val c = p.condition
        return mapOf(
            "trigger" to p.trigger.name, "spriteId" to p.spriteId.toString(),
            "switchId" to c.requiredSwitchId.orEmpty(), "switchScope" to c.switchScope.name,
            "switchValue" to c.requiredSwitchValue.toString(),
            "variableId" to c.variableId.orEmpty(), "variableScope" to c.variableScope.name,
            "minVariable" to c.minVariableValue.toString(),
            "questId" to c.requiredQuestId.toString(), "questStatus" to (c.requiredQuestStatus?.name ?: ""),
            "itemId" to c.requiredItemId.toString(), "minLevel" to c.minPlayerLevel.toString()
        )
    }

    fun applyPage(p: EventPage, v: Map<String, String>): EventPage {
        val c = p.condition
        return p.copy(
            trigger = EventTriggerType.entries.firstOrNull { it.name == v["trigger"] } ?: p.trigger,
            spriteId = FormRules.int(v, "spriteId", p.spriteId).coerceAtLeast(0),
            condition = c.copy(
                requiredSwitchId = if ("switchId" in v) v.getValue("switchId").trim().ifEmpty { null } else c.requiredSwitchId,
                requiredSwitchValue = FormRules.bool(v, "switchValue", c.requiredSwitchValue),
                switchScope = VariableScope.entries.firstOrNull { it.name == v["switchScope"] } ?: c.switchScope,
                variableId = if ("variableId" in v) v.getValue("variableId").trim().ifEmpty { null } else c.variableId,
                minVariableValue = FormRules.int(v, "minVariable", c.minVariableValue),
                variableScope = VariableScope.entries.firstOrNull { it.name == v["variableScope"] } ?: c.variableScope,
                requiredQuestId = FormRules.int(v, "questId", c.requiredQuestId).coerceAtLeast(0),
                requiredQuestStatus = if ("questStatus" in v) QuestStatus.entries.firstOrNull { it.name == v["questStatus"] } else c.requiredQuestStatus,
                requiredItemId = FormRules.int(v, "itemId", c.requiredItemId).coerceAtLeast(0),
                minPlayerLevel = FormRules.int(v, "minLevel", c.minPlayerLevel).coerceAtLeast(1)
            )
        )
    }

    private fun scopePrefix(scope: VariableScope): String = if (scope == VariableScope.WORLD) "world " else ""

    /** A page's conditions in words, or "always" when it has none. */
    fun conditionSummary(p: EventPage, catalog: WorldContentCatalogDto): String {
        val c = p.condition
        val parts = ArrayList<String>()
        c.requiredSwitchId?.let { parts += "${scopePrefix(c.switchScope)}switch $it is ${if (c.requiredSwitchValue) "on" else "off"}" }
        c.variableId?.let { parts += "${scopePrefix(c.variableScope)}$it is at least ${c.minVariableValue}" }
        if (c.requiredQuestId != 0) parts += "quest ${named(catalog, FieldRef.QUEST, c.requiredQuestId)}" +
            when (c.requiredQuestStatus) { QuestStatus.IN_PROGRESS -> " is in progress"; QuestStatus.COMPLETED -> " is completed"; null -> " (no state chosen, so not checked)" }
        if (c.requiredItemId != 0) parts += "carrying ${named(catalog, FieldRef.ITEM, c.requiredItemId)}"
        if (c.minPlayerLevel > 1) parts += "level ${c.minPlayerLevel} or higher"
        return if (parts.isEmpty()) "always" else "only when " + parts.joinToString(" and ")
    }

    // ---- steps ----

    fun kindOf(cmd: EventCommand): EventCommandKind = when (cmd) {
        is EventCommand.ShowDialogue -> EventCommandKind.DIALOGUE
        is EventCommand.GiveItem -> EventCommandKind.GIVE_ITEM
        is EventCommand.TakeItem -> EventCommandKind.TAKE_ITEM
        is EventCommand.GiveXp -> EventCommandKind.GIVE_XP
        is EventCommand.SetSwitch -> EventCommandKind.SET_SWITCH
        is EventCommand.AddVariable -> EventCommandKind.ADD_VARIABLE
        is EventCommand.StartQuest -> EventCommandKind.START_QUEST
        is EventCommand.CompleteQuest -> EventCommandKind.COMPLETE_QUEST
        is EventCommand.WarpPlayer -> EventCommandKind.WARP
        is EventCommand.HealPlayerFull -> EventCommandKind.HEAL
        is EventCommand.OpenShop -> EventCommandKind.OPEN_SHOP
        is EventCommand.OpenBank -> EventCommandKind.OPEN_BANK
        is EventCommand.OpenCrafting -> EventCommandKind.OPEN_CRAFTING
        is EventCommand.PlayAnimation -> EventCommandKind.PLAY_ANIMATION
        is EventCommand.InvokeKotlinHook -> EventCommandKind.HOOK
        is EventCommand.Conditional -> EventCommandKind.CONDITIONAL
        EventCommand.Stop -> EventCommandKind.STOP
        is EventCommand.SetVariable -> EventCommandKind.SET_VARIABLE
        is EventCommand.ShowMessage -> EventCommandKind.MESSAGE
        is EventCommand.UpgradeGear -> EventCommandKind.UPGRADE_GEAR
        is EventCommand.Rebirth -> EventCommandKind.REBIRTH
        EventCommand.Promote -> EventCommandKind.PROMOTE
        is EventCommand.RepairGear -> EventCommandKind.REPAIR_GEAR
        is EventCommand.RespecAttributes -> EventCommandKind.RESPEC_ATTRIBUTES
        is EventCommand.ArenaQueue -> EventCommandKind.ARENA_QUEUE
        is EventCommand.TeachSpell -> EventCommandKind.TEACH_SPELL
        is EventCommand.RunCommonEvent -> EventCommandKind.RUN_COMMON_EVENT
        is EventCommand.Wait -> EventCommandKind.WAIT
        is EventCommand.PlaySound -> EventCommandKind.PLAY_SOUND
        is EventCommand.ShowPicture -> EventCommandKind.SHOW_PICTURE
        is EventCommand.MoveNpc -> EventCommandKind.MOVE_NPC
        is EventCommand.TextInput -> EventCommandKind.TEXT_INPUT
        is EventCommand.Label -> EventCommandKind.LABEL
        is EventCommand.JumpToLabel -> EventCommandKind.JUMP_TO_LABEL
        is EventCommand.Loop -> EventCommandKind.LOOP
        EventCommand.BreakLoop -> EventCommandKind.BREAK_LOOP
    }

    private val checks = EventConditionType.entries.map { it.name }
    private val comparisons = Comparison.entries.map { it.name }
    private val questChecks = QuestCheck.entries.map { it.name }
    private val matchModes = ConditionMatchMode.entries.map { it.name }

    /** The fields of one check inside an If step; [keyPrefix] and [labelPrefix] distinguish extra checks cleanly. */
    fun conditionFields(type: EventConditionType, keyPrefix: String = "", labelPrefix: String = ""): List<FormField> {
        val check = FormField("${keyPrefix}check", "${labelPrefix}Check", FormFieldType.CHOICE, EventConditionType.SWITCH.name, choices = checks)
        val name = FormField("${keyPrefix}name", labelPrefix + if (type == EventConditionType.SWITCH) "Switch name" else "Number name", FormFieldType.TEXT, required = true)
        val scope = FormField("${keyPrefix}scope", "${labelPrefix}Stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes)
        val comparison = FormField("${keyPrefix}comparison", "${labelPrefix}Is", FormFieldType.CHOICE, Comparison.AT_LEAST.name, choices = comparisons)
        return listOf(check) + when (type) {
            EventConditionType.SWITCH -> listOf(name, scope, FormField("${keyPrefix}on", "${labelPrefix}Is on (untick: is off)", FormFieldType.BOOL, "true"))
            EventConditionType.VARIABLE -> listOf(name, scope, comparison, FormField("${keyPrefix}value", "${labelPrefix}This number", FormFieldType.INT, "1"))
            EventConditionType.HAS_ITEM -> listOf(
                FormField("${keyPrefix}target", "${labelPrefix}Item", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM),
                FormField("${keyPrefix}value", "${labelPrefix}How many, at least", FormFieldType.INT, "1", min = 1)
            )
            EventConditionType.QUEST -> listOf(
                FormField("${keyPrefix}target", "${labelPrefix}Quest", FormFieldType.INT, "1", min = 1, ref = FieldRef.QUEST),
                FormField("${keyPrefix}quest", "${labelPrefix}Is", FormFieldType.CHOICE, QuestCheck.COMPLETED.name, choices = questChecks)
            )
            EventConditionType.LEVEL -> listOf(comparison, FormField("${keyPrefix}value", "${labelPrefix}This level", FormFieldType.INT, "1", min = 1))
            EventConditionType.CLASS -> listOf(FormField("${keyPrefix}target", "${labelPrefix}Class", FormFieldType.INT, "1", min = 1, ref = FieldRef.CLASS))
            EventConditionType.KNOWS_SPELL -> listOf(FormField("${keyPrefix}target", "${labelPrefix}Spell", FormFieldType.INT, "1", min = 1, ref = FieldRef.SPELL))
            EventConditionType.CHANCE -> listOf(FormField("${keyPrefix}value", "${labelPrefix}Chance in 100", FormFieldType.INT, "50", min = 0, max = 100))
            EventConditionType.GUILD_HOLDS_AREA -> listOf(FormField("${keyPrefix}on", "${labelPrefix}Holds it (untick: does not)", FormFieldType.BOOL, "true"))
        }
    }

    /** The fields of [cmd]. An If step's fields depend on its checks and extra conditions; every other step's on its kind alone. */
    fun fieldsFor(cmd: EventCommand): List<FormField> = when {
        cmd is EventCommand.Conditional && cmd.extraConditions.isEmpty() -> conditionFields(cmd.condition.type)
        cmd is EventCommand.Conditional -> {
            val matchField = FormField("matchMode", "Match", FormFieldType.CHOICE, ConditionMatchMode.ALL.name, choices = matchModes)
            val first = conditionFields(cmd.condition.type, labelPrefix = "Check 1: ")
            val rest = cmd.extraConditions.flatMapIndexed { ei, extra ->
                conditionFields(extra.type, keyPrefix = "e${ei}_", labelPrefix = "Check ${ei + 2}: ")
            }
            listOf(matchField) + first + rest
        }
        else -> fields(kindOf(cmd))
    }

    private fun conditionValues(c: EventCondition, keyPrefix: String = ""): Map<String, String> = mapOf("${keyPrefix}check" to c.type.name) + when (c.type) {
        EventConditionType.SWITCH -> mapOf("${keyPrefix}name" to c.key, "${keyPrefix}scope" to c.scope.name, "${keyPrefix}on" to (c.value != 0).toString())
        EventConditionType.VARIABLE -> mapOf("${keyPrefix}name" to c.key, "${keyPrefix}scope" to c.scope.name, "${keyPrefix}comparison" to c.comparison.name, "${keyPrefix}value" to c.value.toString())
        EventConditionType.HAS_ITEM -> mapOf("${keyPrefix}target" to c.targetId.toString(), "${keyPrefix}value" to c.value.toString())
        EventConditionType.QUEST -> mapOf("${keyPrefix}target" to c.targetId.toString(), "${keyPrefix}quest" to c.quest.name)
        EventConditionType.LEVEL -> mapOf("${keyPrefix}comparison" to c.comparison.name, "${keyPrefix}value" to c.value.toString())
        EventConditionType.CLASS, EventConditionType.KNOWS_SPELL -> mapOf("${keyPrefix}target" to c.targetId.toString())
        EventConditionType.CHANCE -> mapOf("${keyPrefix}value" to c.value.toString())
        EventConditionType.GUILD_HOLDS_AREA -> mapOf("${keyPrefix}on" to (c.value != 0).toString())
    }

    /** A fresh check of [type] with defaults that make sense for it. */
    fun newCondition(type: EventConditionType): EventCondition = when (type) {
        EventConditionType.SWITCH -> EventCondition(type, key = "my_switch", value = 1)
        EventConditionType.VARIABLE -> EventCondition(type, key = "my_number", value = 1)
        EventConditionType.CHANCE -> EventCondition(type, value = 50)
        EventConditionType.LEVEL -> EventCondition(type, value = 1)
        else -> EventCondition(type, targetId = 1, value = 1)
    }

    /** [c] with typed values applied. Choosing a different kind of check starts that kind afresh, since its fields differ. */
    private fun applyCondition(c: EventCondition, v: Map<String, String>, keyPrefix: String = ""): EventCondition {
        val type = EventConditionType.entries.firstOrNull { it.name == v["${keyPrefix}check"] } ?: c.type
        if (type != c.type) return newCondition(type)
        return c.copy(
            key = if (type == EventConditionType.SWITCH || type == EventConditionType.VARIABLE) FormRules.text(v, "${keyPrefix}name", c.key).ifBlank { c.key } else c.key,
            targetId = FormRules.int(v, "${keyPrefix}target", c.targetId),
            comparison = Comparison.entries.firstOrNull { it.name == v["${keyPrefix}comparison"] } ?: c.comparison,
            value = if (type == EventConditionType.SWITCH || type == EventConditionType.GUILD_HOLDS_AREA) (if (FormRules.bool(v, "${keyPrefix}on", c.value != 0)) 1 else 0) else FormRules.int(v, "${keyPrefix}value", c.value),
            quest = QuestCheck.entries.firstOrNull { it.name == v["${keyPrefix}quest"] } ?: c.quest,
            scope = if (type == EventConditionType.SWITCH || type == EventConditionType.VARIABLE) {
                VariableScope.entries.firstOrNull { it.name == v["${keyPrefix}scope"] } ?: c.scope
            } else {
                c.scope
            }
        )
    }

    fun withAddedCondition(cmd: EventCommand.Conditional): EventCommand.Conditional =
        cmd.copy(extraConditions = cmd.extraConditions + newCondition(EventConditionType.SWITCH))

    fun withRemovedLastCondition(cmd: EventCommand.Conditional): EventCommand.Conditional =
        if (cmd.extraConditions.isEmpty()) cmd else cmd.copy(extraConditions = cmd.extraConditions.dropLast(1))

    private fun comparisonWords(c: Comparison): String = when (c) {
        Comparison.EQUAL -> "is"
        Comparison.NOT_EQUAL -> "is not"
        Comparison.AT_LEAST -> "is at least"
        Comparison.AT_MOST -> "is at most"
        Comparison.GREATER -> "is more than"
        Comparison.LESS -> "is less than"
    }

    /** What an If step checks, in words. */
    fun conditionText(c: EventCondition, catalog: WorldContentCatalogDto): String = when (c.type) {
        EventConditionType.SWITCH -> "${scopePrefix(c.scope)}switch ${c.key} is ${if (c.value != 0) "on" else "off"}"
        EventConditionType.VARIABLE -> "${scopePrefix(c.scope)}${c.key} ${comparisonWords(c.comparison)} ${c.value}"
        EventConditionType.HAS_ITEM -> "the player carries " + (if (c.value > 1) "${c.value} x " else "") + named(catalog, FieldRef.ITEM, c.targetId)
        EventConditionType.QUEST -> "quest ${named(catalog, FieldRef.QUEST, c.targetId)} is " + when (c.quest) {
            QuestCheck.NOT_STARTED -> "not started"
            QuestCheck.IN_PROGRESS -> "in progress"
            QuestCheck.READY_TO_COMPLETE -> "ready to hand in"
            QuestCheck.COMPLETED -> "completed"
        }
        EventConditionType.LEVEL -> "the player's level ${comparisonWords(c.comparison)} ${c.value}"
        EventConditionType.CLASS -> "the player's class is ${named(catalog, FieldRef.CLASS, c.targetId)}"
        EventConditionType.KNOWS_SPELL -> "the player knows ${named(catalog, FieldRef.SPELL, c.targetId)}"
        EventConditionType.CHANCE -> "a ${c.value} in 100 chance comes up"
        EventConditionType.GUILD_HOLDS_AREA -> "the player's guild ${if (c.value != 0) "holds" else "does not hold"} the guild area"
    }

    fun fields(kind: EventCommandKind): List<FormField> = when (kind) {
        EventCommandKind.DIALOGUE -> listOf(
            FormField("speaker", "Who is speaking", FormFieldType.TEXT),
            FormField("text", "What they say", FormFieldType.TEXT, required = true, hint = "Placeholders: {player}, {level}, {map}, {visits}, {world:name}, {item:1}"),
            FormField("portrait", "Portrait number (0 = none)", FormFieldType.INT, "0", min = 0)
        )
        EventCommandKind.GIVE_ITEM, EventCommandKind.TAKE_ITEM -> listOf(
            FormField("item", "Item", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM),
            FormField("amount", "How many", FormFieldType.INT, "1", min = 1)
        )
        EventCommandKind.GIVE_XP -> listOf(FormField("amount", "Experience points", FormFieldType.LONG, "50", min = 0))
        EventCommandKind.SET_SWITCH -> listOf(
            FormField("switch", "Switch name", FormFieldType.TEXT, required = true, hint = "Any name you like, such as met_miller. Page conditions check it by the same name."),
            FormField("scope", "Stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
            FormField("value", "Turn it on (untick to turn it off)", FormFieldType.BOOL, "true")
        )
        EventCommandKind.ADD_VARIABLE -> listOf(
            FormField("variable", "Number name", FormFieldType.TEXT, required = true),
            FormField("scope", "Stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
            FormField("delta", "Add (negative subtracts)", FormFieldType.INT, "1")
        )
        EventCommandKind.START_QUEST, EventCommandKind.COMPLETE_QUEST -> listOf(FormField("quest", "Quest", FormFieldType.INT, "1", min = 1, ref = FieldRef.QUEST))
        EventCommandKind.WARP -> listOf(
            FormField("map", "Map", FormFieldType.INT, "1", min = 1, ref = FieldRef.MAP),
            FormField("x", "Tile X", FormFieldType.INT, "0", min = 0),
            FormField("y", "Tile Y", FormFieldType.INT, "0", min = 0)
        )
        EventCommandKind.OPEN_SHOP -> listOf(FormField("shop", "Shop", FormFieldType.INT, "1", min = 1, ref = FieldRef.SHOP))
        EventCommandKind.OPEN_CRAFTING -> listOf(FormField("station", "Station", FormFieldType.CHOICE, CraftingStationType.ANVIL.name, choices = stations))
        EventCommandKind.PLAY_ANIMATION -> listOf(FormField("animation", "Animation", FormFieldType.ANIMATION, "1", min = 1, ref = FieldRef.ANIMATION))
        EventCommandKind.HOOK -> listOf(
            FormField("hook", "Hook name", FormFieldType.TEXT, required = true),
            FormField("payload", "Settings", FormFieldType.LINES, hint = "One per line: key=value")
        )
        EventCommandKind.CONDITIONAL -> conditionFields(EventConditionType.SWITCH)
        EventCommandKind.SET_VARIABLE -> listOf(
            FormField("variable", "Number name", FormFieldType.TEXT, required = true),
            FormField("scope", "Stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
            FormField("value", "Set it to", FormFieldType.INT, "0")
        )
        EventCommandKind.MESSAGE -> listOf(FormField("text", "Message", FormFieldType.TEXT, required = true, hint = "Placeholders: {player}, {level}, {map}, {visits}, {world:name}, {item:1}"))
        EventCommandKind.TEXT_INPUT -> listOf(
            FormField("prompt", "Prompt", FormFieldType.TEXT, required = true, hint = "Supports placeholders like {player} and {visits}"),
            FormField("variable", "Save into variable", FormFieldType.TEXT, required = true),
            FormField("scope", "Stored for", FormFieldType.CHOICE, VariableScope.PLAYER.name, choices = scopes),
            FormField("numeric", "Number only (store as a number)", FormFieldType.BOOL, "false"),
            FormField("defaultValue", "Initial text (optional)", FormFieldType.TEXT),
            FormField("maxLength", "Max length", FormFieldType.INT, "64", min = 1, max = 200)
        )
        EventCommandKind.UPGRADE_GEAR -> listOf(
            FormField("kind", "Upgrade", FormFieldType.CHOICE, GearUpgradeKind.ADD_SOCKET.name, GearUpgradeKind.entries.map { it.name }),
            FormField("slot", "Worn in slot (id)", FormFieldType.TEXT, EquipmentSlot.WEAPON.id, hint = "The slot id, such as WEAPON, ARMOR or a slot your game defines."),
            FormField("material", "Material item (0 = any gem)", FormFieldType.INT, "0", min = 0, ref = FieldRef.ITEM),
            FormField("amount", "Amount", FormFieldType.INT, "1", min = 1),
            FormField("success", "Success chance (%)", FormFieldType.INT, "100", min = 1, max = 100)
        )
        EventCommandKind.REBIRTH -> listOf(FormField("classId", "Class id (0 = keep class)", FormFieldType.INT, "0", min = 0))
        EventCommandKind.REPAIR_GEAR -> listOf(FormField("currency", "Currency item", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM))
        EventCommandKind.RESPEC_ATTRIBUTES -> listOf(
            FormField("currency", "Currency item", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM),
            FormField("price", "Price per point returned", FormFieldType.INT, "0", min = 0)
        )
        EventCommandKind.ARENA_QUEUE -> listOf(
            FormField("action", "Action", FormFieldType.CHOICE, "JOIN", choices = ArenaQueueAction.entries.map { it.name }),
            FormField("size", "Team size (0 = first arena size)", FormFieldType.INT, "0", min = 0)
        )
        EventCommandKind.TEACH_SPELL -> listOf(FormField("spell", "Spell", FormFieldType.INT, "1", min = 1, ref = FieldRef.SPELL))
        EventCommandKind.RUN_COMMON_EVENT -> listOf(FormField("commonEvent", "Common event", FormFieldType.INT, "1", min = 1, ref = FieldRef.COMMON_EVENT))
        EventCommandKind.WAIT -> listOf(
            FormField("ticks", "Wait ticks (20 = 1s)", FormFieldType.INT, "20", min = 1, max = 12000, hint = "20 ticks = 1 second; max 12000 (10 minutes).")
        )
        EventCommandKind.PLAY_SOUND -> listOf(
            FormField("sound", "Sound ID", FormFieldType.TEXT, required = true, hint = "Built-in: click, swing, hit, crit, spell, heal, level_up, pickup, warp")
        )
        EventCommandKind.SHOW_PICTURE -> listOf(
            FormField("picture", "Picture", FormFieldType.TEXT, required = true, hint = "assets/pictures/<name>.png (use <name>), or icon:1, sprite:1, anim:1"),
            FormField("title", "Title (optional)", FormFieldType.TEXT),
            FormField("duration", "Auto-close ticks (0 = manual)", FormFieldType.INT, "0", min = 0, max = 12000, hint = "0 stays open until the player closes it; 20 = 1 second.")
        )
        EventCommandKind.MOVE_NPC -> listOf(
            FormField("npc", "Creature", FormFieldType.INT, "1", min = 1, ref = FieldRef.NPC),
            FormField("direction", "Direction", FormFieldType.CHOICE, Direction.DOWN.name, choices = directions),
            FormField("steps", "Steps", FormFieldType.INT, "1", min = 1, max = 20),
            FormField("faceOnly", "Turn only (do not walk)", FormFieldType.BOOL, "false")
        )
        EventCommandKind.LABEL -> listOf(FormField("name", "Label name", FormFieldType.TEXT, required = true))
        EventCommandKind.JUMP_TO_LABEL -> listOf(FormField("name", "Target label", FormFieldType.TEXT, required = true))
        EventCommandKind.HEAL, EventCommandKind.OPEN_BANK, EventCommandKind.STOP, EventCommandKind.PROMOTE, EventCommandKind.LOOP, EventCommandKind.BREAK_LOOP -> emptyList()
    }

    fun valuesOf(cmd: EventCommand): Map<String, String> = when (cmd) {
        is EventCommand.ShowDialogue -> mapOf("speaker" to cmd.speakerName, "text" to cmd.text, "portrait" to cmd.portraitId.toString())
        is EventCommand.GiveItem -> mapOf("item" to cmd.itemId.toString(), "amount" to cmd.amount.toString())
        is EventCommand.TakeItem -> mapOf("item" to cmd.itemId.toString(), "amount" to cmd.amount.toString())
        is EventCommand.GiveXp -> mapOf("amount" to cmd.amount.toString())
        is EventCommand.SetSwitch -> mapOf("switch" to cmd.switchId, "scope" to cmd.scope.name, "value" to cmd.value.toString())
        is EventCommand.AddVariable -> mapOf("variable" to cmd.variableId, "scope" to cmd.scope.name, "delta" to cmd.delta.toString())
        is EventCommand.StartQuest -> mapOf("quest" to cmd.questId.toString())
        is EventCommand.CompleteQuest -> mapOf("quest" to cmd.questId.toString())
        is EventCommand.WarpPlayer -> mapOf("map" to cmd.mapId.toString(), "x" to cmd.tileX.toString(), "y" to cmd.tileY.toString())
        is EventCommand.OpenShop -> mapOf("shop" to cmd.shopId.toString())
        is EventCommand.OpenCrafting -> mapOf("station" to cmd.stationType.name)
        is EventCommand.PlayAnimation -> mapOf("animation" to cmd.animationId.toString())
        is EventCommand.InvokeKotlinHook -> mapOf("hook" to cmd.hookId, "payload" to cmd.payload.entries.joinToString("\n") { "${it.key}=${it.value}" })
        is EventCommand.Conditional -> if (cmd.extraConditions.isEmpty()) {
            conditionValues(cmd.condition)
        } else {
            cmd.extraConditions.withIndex().fold(
                mapOf("matchMode" to cmd.matchMode.name) + conditionValues(cmd.condition)
            ) { acc, (ei, extra) -> acc + conditionValues(extra, "e${ei}_") }
        }
        is EventCommand.SetVariable -> mapOf("variable" to cmd.variableId, "scope" to cmd.scope.name, "value" to cmd.value.toString())
        is EventCommand.ShowMessage -> mapOf("text" to cmd.text)
        is EventCommand.UpgradeGear -> mapOf(
            "kind" to cmd.kind.name, "slot" to cmd.slot.name, "material" to cmd.materialItemId.toString(),
            "amount" to cmd.amount.toString(), "success" to cmd.successPercent.toString()
        )
        is EventCommand.Rebirth -> mapOf("classId" to cmd.classId.toString())
        is EventCommand.RepairGear -> mapOf("currency" to cmd.currencyItemId.toString())
        is EventCommand.RespecAttributes -> mapOf("currency" to cmd.currencyItemId.toString(), "price" to cmd.pricePerPoint.toString())
        is EventCommand.ArenaQueue -> mapOf("action" to cmd.action.name, "size" to cmd.size.toString())
        is EventCommand.TeachSpell -> mapOf("spell" to cmd.spellId.toString())
        is EventCommand.RunCommonEvent -> mapOf("commonEvent" to cmd.commonEventId.toString())
        is EventCommand.Wait -> mapOf("ticks" to cmd.ticks.toString())
        is EventCommand.PlaySound -> mapOf("sound" to cmd.soundId)
        is EventCommand.ShowPicture -> mapOf("picture" to cmd.pictureId, "title" to cmd.title, "duration" to cmd.durationTicks.toString())
        is EventCommand.MoveNpc -> mapOf(
            "npc" to cmd.npcId.toString(),
            "direction" to cmd.direction.name,
            "steps" to cmd.steps.toString(),
            "faceOnly" to cmd.faceOnly.toString()
        )
        is EventCommand.TextInput -> mapOf(
            "prompt" to cmd.prompt,
            "variable" to cmd.variableId,
            "scope" to cmd.scope.name,
            "numeric" to cmd.isNumeric.toString(),
            "defaultValue" to cmd.defaultValue,
            "maxLength" to cmd.maxLength.toString()
        )
        is EventCommand.Label -> mapOf("name" to cmd.name)
        is EventCommand.JumpToLabel -> mapOf("name" to cmd.name)
        EventCommand.HealPlayerFull, EventCommand.OpenBank, EventCommand.Stop, EventCommand.Promote, is EventCommand.Loop, EventCommand.BreakLoop -> emptyMap()
    }

    /** [cmd] with the typed [v] applied. Forgiving: a bad or missing value keeps the step's current setting. A dialogue keeps its choices. */
    fun apply(cmd: EventCommand, v: Map<String, String>): EventCommand = when (cmd) {
        is EventCommand.ShowDialogue -> cmd.copy(
            speakerName = FormRules.text(v, "speaker", cmd.speakerName), text = FormRules.text(v, "text", cmd.text),
            portraitId = FormRules.int(v, "portrait", cmd.portraitId).coerceAtLeast(0)
        )
        is EventCommand.GiveItem -> cmd.copy(itemId = FormRules.int(v, "item", cmd.itemId), amount = FormRules.int(v, "amount", cmd.amount).coerceAtLeast(1))
        is EventCommand.TakeItem -> cmd.copy(itemId = FormRules.int(v, "item", cmd.itemId), amount = FormRules.int(v, "amount", cmd.amount).coerceAtLeast(1))
        is EventCommand.GiveXp -> cmd.copy(amount = FormRules.long(v, "amount", cmd.amount).coerceAtLeast(0))
        is EventCommand.SetSwitch -> cmd.copy(
            switchId = FormRules.text(v, "switch", cmd.switchId).ifBlank { cmd.switchId },
            value = FormRules.bool(v, "value", cmd.value),
            scope = VariableScope.entries.firstOrNull { it.name == v["scope"] } ?: cmd.scope
        )
        is EventCommand.AddVariable -> cmd.copy(
            variableId = FormRules.text(v, "variable", cmd.variableId).ifBlank { cmd.variableId },
            delta = FormRules.int(v, "delta", cmd.delta),
            scope = VariableScope.entries.firstOrNull { it.name == v["scope"] } ?: cmd.scope
        )
        is EventCommand.StartQuest -> cmd.copy(questId = FormRules.int(v, "quest", cmd.questId))
        is EventCommand.CompleteQuest -> cmd.copy(questId = FormRules.int(v, "quest", cmd.questId))
        is EventCommand.WarpPlayer -> cmd.copy(
            mapId = FormRules.int(v, "map", cmd.mapId), tileX = FormRules.int(v, "x", cmd.tileX).coerceAtLeast(0), tileY = FormRules.int(v, "y", cmd.tileY).coerceAtLeast(0)
        )
        is EventCommand.OpenShop -> cmd.copy(shopId = FormRules.int(v, "shop", cmd.shopId))
        is EventCommand.OpenCrafting -> cmd.copy(stationType = CraftingStationType.entries.firstOrNull { it.name == v["station"] } ?: cmd.stationType)
        is EventCommand.PlayAnimation -> cmd.copy(animationId = FormRules.int(v, "animation", cmd.animationId))
        is EventCommand.InvokeKotlinHook -> cmd.copy(
            hookId = FormRules.text(v, "hook", cmd.hookId).ifBlank { cmd.hookId },
            payload = v["payload"]?.let { parsePayload(it).first } ?: cmd.payload
        )
        is EventCommand.Conditional -> cmd.copy(
            condition = applyCondition(cmd.condition, v),
            extraConditions = cmd.extraConditions.mapIndexed { ei, extra -> applyCondition(extra, v, "e${ei}_") },
            matchMode = ConditionMatchMode.entries.firstOrNull { it.name == v["matchMode"] } ?: cmd.matchMode
        )
        is EventCommand.SetVariable -> cmd.copy(
            variableId = FormRules.text(v, "variable", cmd.variableId).ifBlank { cmd.variableId },
            value = FormRules.int(v, "value", cmd.value),
            scope = VariableScope.entries.firstOrNull { it.name == v["scope"] } ?: cmd.scope
        )
        is EventCommand.ShowMessage -> cmd.copy(text = FormRules.text(v, "text", cmd.text))
        is EventCommand.UpgradeGear -> cmd.copy(
            kind = GearUpgradeKind.entries.firstOrNull { it.name == v["kind"] } ?: cmd.kind,
            slot = v["slot"]?.trim()?.uppercase()?.takeIf { EquipmentSlot.ID_PATTERN.matches(it) }?.let { EquipmentSlot(it) } ?: cmd.slot,
            materialItemId = FormRules.int(v, "material", cmd.materialItemId).coerceAtLeast(0),
            amount = FormRules.int(v, "amount", cmd.amount).coerceAtLeast(1),
            successPercent = FormRules.int(v, "success", cmd.successPercent).coerceIn(1, 100)
        )
        is EventCommand.Rebirth -> cmd.copy(classId = FormRules.int(v, "classId", cmd.classId).coerceAtLeast(0))
        is EventCommand.RepairGear -> cmd.copy(currencyItemId = FormRules.int(v, "currency", cmd.currencyItemId))
        is EventCommand.RespecAttributes -> cmd.copy(currencyItemId = FormRules.int(v, "currency", cmd.currencyItemId), pricePerPoint = FormRules.int(v, "price", cmd.pricePerPoint))
        is EventCommand.ArenaQueue -> cmd.copy(action = ArenaQueueAction.entries.firstOrNull { it.name == v["action"] } ?: cmd.action, size = FormRules.int(v, "size", cmd.size).coerceAtLeast(0))
        is EventCommand.TeachSpell -> cmd.copy(spellId = FormRules.int(v, "spell", cmd.spellId))
        is EventCommand.RunCommonEvent -> cmd.copy(commonEventId = FormRules.int(v, "commonEvent", cmd.commonEventId))
        is EventCommand.Wait -> cmd.copy(ticks = FormRules.int(v, "ticks", cmd.ticks).coerceIn(1, 12000))
        is EventCommand.PlaySound -> cmd.copy(soundId = FormRules.text(v, "sound", cmd.soundId).ifBlank { cmd.soundId })
        is EventCommand.ShowPicture -> cmd.copy(
            pictureId = FormRules.text(v, "picture", cmd.pictureId).ifBlank { cmd.pictureId },
            title = v["title"]?.trim() ?: cmd.title,
            durationTicks = FormRules.int(v, "duration", cmd.durationTicks).coerceIn(0, 12000)
        )
        is EventCommand.MoveNpc -> cmd.copy(
            npcId = FormRules.int(v, "npc", cmd.npcId),
            direction = Direction.entries.firstOrNull { it.name == v["direction"] } ?: cmd.direction,
            steps = FormRules.int(v, "steps", cmd.steps).coerceIn(1, 20),
            faceOnly = FormRules.bool(v, "faceOnly", cmd.faceOnly)
        )
        is EventCommand.TextInput -> cmd.copy(
            prompt = FormRules.text(v, "prompt", cmd.prompt).ifBlank { cmd.prompt },
            variableId = FormRules.text(v, "variable", cmd.variableId).ifBlank { cmd.variableId },
            scope = VariableScope.entries.firstOrNull { it.name == v["scope"] } ?: cmd.scope,
            isNumeric = FormRules.bool(v, "numeric", cmd.isNumeric),
            defaultValue = v["defaultValue"] ?: cmd.defaultValue,
            maxLength = FormRules.int(v, "maxLength", cmd.maxLength).coerceIn(1, 200)
        )
        is EventCommand.Label -> cmd.copy(name = FormRules.text(v, "name", cmd.name).ifBlank { cmd.name })
        is EventCommand.JumpToLabel -> cmd.copy(name = FormRules.text(v, "name", cmd.name).ifBlank { cmd.name })
        EventCommand.HealPlayerFull, EventCommand.OpenBank, EventCommand.Stop, EventCommand.Promote, is EventCommand.Loop, EventCommand.BreakLoop -> cmd
    }

    /** `key=value` lines as a map, and a problem for each line that is not one. Blank lines are ignored. */
    fun parsePayload(text: String): Pair<Map<String, String>, List<String>> {
        val map = LinkedHashMap<String, String>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            val at = line.indexOf('=')
            if (at < 1) problems += "Settings line ${i + 1}: write key=value" else map[line.substring(0, at).trim()] = line.substring(at + 1).trim()
        }
        return map to problems
    }

    /** A new step of [kind] with defaults that point at things the game has, so it is valid straight away where possible. */
    fun create(kind: EventCommandKind, catalog: WorldContentCatalogDto): EventCommand = when (kind) {
        EventCommandKind.DIALOGUE -> EventCommand.ShowDialogue("", "Hello!")
        EventCommandKind.GIVE_ITEM -> EventCommand.GiveItem(catalog.items.minOfOrNull { it.id } ?: 1)
        EventCommandKind.TAKE_ITEM -> EventCommand.TakeItem(catalog.items.minOfOrNull { it.id } ?: 1)
        EventCommandKind.GIVE_XP -> EventCommand.GiveXp(50)
        EventCommandKind.SET_SWITCH -> EventCommand.SetSwitch("my_switch", true)
        EventCommandKind.ADD_VARIABLE -> EventCommand.AddVariable("my_number", 1)
        EventCommandKind.START_QUEST -> EventCommand.StartQuest(catalog.quests.minOfOrNull { it.id } ?: 1)
        EventCommandKind.COMPLETE_QUEST -> EventCommand.CompleteQuest(catalog.quests.minOfOrNull { it.id } ?: 1)
        EventCommandKind.WARP -> EventCommand.WarpPlayer(catalog.maps.minOfOrNull { it.id } ?: 1, 0, 0)
        EventCommandKind.HEAL -> EventCommand.HealPlayerFull
        EventCommandKind.OPEN_SHOP -> EventCommand.OpenShop(catalog.shops.minOfOrNull { it.id } ?: 1)
        EventCommandKind.OPEN_BANK -> EventCommand.OpenBank
        EventCommandKind.OPEN_CRAFTING -> EventCommand.OpenCrafting()
        EventCommandKind.PLAY_ANIMATION -> EventCommand.PlayAnimation(catalog.animations.minOfOrNull { it.id } ?: 1)
        EventCommandKind.HOOK -> EventCommand.InvokeKotlinHook("my_hook")
        EventCommandKind.CONDITIONAL -> EventCommand.Conditional(newCondition(EventConditionType.SWITCH))
        EventCommandKind.STOP -> EventCommand.Stop
        EventCommandKind.PROMOTE -> EventCommand.Promote
        EventCommandKind.SET_VARIABLE -> EventCommand.SetVariable("my_number", 0)
        EventCommandKind.MESSAGE -> EventCommand.ShowMessage("Something happens.")
        EventCommandKind.UPGRADE_GEAR -> EventCommand.UpgradeGear()
        EventCommandKind.REBIRTH -> EventCommand.Rebirth()
        EventCommandKind.REPAIR_GEAR -> EventCommand.RepairGear(catalog.items.minOfOrNull { it.id } ?: 1)
        EventCommandKind.RESPEC_ATTRIBUTES -> EventCommand.RespecAttributes(catalog.items.minOfOrNull { it.id } ?: 1, 0)
        EventCommandKind.ARENA_QUEUE -> EventCommand.ArenaQueue()
        EventCommandKind.TEACH_SPELL -> EventCommand.TeachSpell(catalog.spells.minOfOrNull { it.id } ?: 1)
        EventCommandKind.RUN_COMMON_EVENT -> EventCommand.RunCommonEvent(catalog.commonEvents.minOfOrNull { it.id } ?: 1)
        EventCommandKind.WAIT -> EventCommand.Wait(20)
        EventCommandKind.PLAY_SOUND -> EventCommand.PlaySound("click")
        EventCommandKind.SHOW_PICTURE -> EventCommand.ShowPicture("icon:1")
        EventCommandKind.MOVE_NPC -> EventCommand.MoveNpc(catalog.npcs.minOfOrNull { it.id } ?: 1)
        EventCommandKind.TEXT_INPUT -> EventCommand.TextInput("What is your name?", "player_note")
        EventCommandKind.LABEL -> EventCommand.Label("start")
        EventCommandKind.JUMP_TO_LABEL -> EventCommand.JumpToLabel("start")
        EventCommandKind.LOOP -> EventCommand.Loop()
        EventCommandKind.BREAK_LOOP -> EventCommand.BreakLoop
    }

    private fun named(catalog: WorldContentCatalogDto, ref: FieldRef, id: Int): String =
        ContentNames.nameOf(catalog, ref, id)?.let { "$it (#$id)" } ?: "#$id (missing)"

    private fun clip(text: String, max: Int = 48): String = if (text.length <= max) text else text.take(max - 3).trimEnd() + "..."

    /** One line describing a step in plain words, with the names of the things it points at. */
    fun summary(cmd: EventCommand, catalog: WorldContentCatalogDto): String = when (cmd) {
        is EventCommand.ShowDialogue -> (if (cmd.speakerName.isBlank()) "Say: " else "${cmd.speakerName} says: ") + "\"${clip(cmd.text)}\""
        is EventCommand.GiveItem -> "Give ${cmd.amount} x ${named(catalog, FieldRef.ITEM, cmd.itemId)}"
        is EventCommand.TakeItem -> "Take ${cmd.amount} x ${named(catalog, FieldRef.ITEM, cmd.itemId)}"
        is EventCommand.GiveXp -> "Give ${cmd.amount} experience"
        is EventCommand.SetSwitch -> "Turn ${scopePrefix(cmd.scope)}switch ${cmd.switchId} ${if (cmd.value) "on" else "off"}"
        is EventCommand.AddVariable -> if (cmd.delta < 0) "Subtract ${-cmd.delta} from ${scopePrefix(cmd.scope)}${cmd.variableId}" else "Add ${cmd.delta} to ${scopePrefix(cmd.scope)}${cmd.variableId}"
        is EventCommand.StartQuest -> "Start quest ${named(catalog, FieldRef.QUEST, cmd.questId)}"
        is EventCommand.CompleteQuest -> "Complete quest ${named(catalog, FieldRef.QUEST, cmd.questId)}"
        is EventCommand.WarpPlayer -> "Move the player to ${named(catalog, FieldRef.MAP, cmd.mapId)} at ${cmd.tileX}, ${cmd.tileY}"
        EventCommand.HealPlayerFull -> "Heal the player fully"
        is EventCommand.OpenShop -> "Open shop ${named(catalog, FieldRef.SHOP, cmd.shopId)}"
        EventCommand.OpenBank -> "Open the bank"
        is EventCommand.OpenCrafting -> "Open crafting (${cmd.stationType.name.lowercase().replace('_', ' ')})"
        is EventCommand.PlayAnimation -> "Play animation ${named(catalog, FieldRef.ANIMATION, cmd.animationId)}"
        is EventCommand.InvokeKotlinHook -> "Run plugin code ${cmd.hookId}"
        is EventCommand.Conditional -> if (cmd.extraConditions.isEmpty()) {
            "If ${conditionText(cmd.condition, catalog)}:"
        } else {
            val head = if (cmd.matchMode == ConditionMatchMode.ALL) "If all: " else "If any: "
            val joiner = if (cmd.matchMode == ConditionMatchMode.ALL) " and " else " or "
            head + cmd.allConditions.joinToString(joiner) { conditionText(it, catalog) } + ":"
        }
        EventCommand.Stop -> "Stop the event"
        EventCommand.Promote -> "Promote the player one tier"
        is EventCommand.SetVariable -> "Set ${scopePrefix(cmd.scope)}${cmd.variableId} to ${cmd.value}"
        is EventCommand.ShowMessage -> "Chat message: \"${clip(cmd.text)}\""
        is EventCommand.UpgradeGear -> "Upgrade worn ${cmd.slot.name.lowercase()}: ${cmd.kind.name.lowercase().replace('_', ' ')}" +
            (if (cmd.materialItemId > 0) " with ${cmd.amount} x ${named(catalog, FieldRef.ITEM, cmd.materialItemId)}" else "") +
            (if (cmd.successPercent < 100) " (${cmd.successPercent}%)" else "")
        is EventCommand.Rebirth -> if (cmd.classId == 0) "Rebirth (keep class)" else "Rebirth as class ${cmd.classId}"
        is EventCommand.RepairGear -> "Repair gear (pay with ${named(catalog, FieldRef.ITEM, cmd.currencyItemId)})"
        is EventCommand.RespecAttributes -> "Reset attributes" + if (cmd.pricePerPoint > 0) " (${cmd.pricePerPoint} x ${named(catalog, FieldRef.ITEM, cmd.currencyItemId)} per point)" else " (free)"
        is EventCommand.ArenaQueue -> "Arena: ${cmd.action.name.lowercase()}" + if (cmd.action == ArenaQueueAction.JOIN && cmd.size > 0) " ${cmd.size}v${cmd.size}" else ""
        is EventCommand.TeachSpell -> "Teach ${named(catalog, FieldRef.SPELL, cmd.spellId)}"
        is EventCommand.RunCommonEvent -> "Run common event ${named(catalog, FieldRef.COMMON_EVENT, cmd.commonEventId)}"
        is EventCommand.Wait -> "Wait ${cmd.ticks} ${if (cmd.ticks == 1) "tick" else "ticks"}"
        is EventCommand.PlaySound -> "Play sound ${cmd.soundId}"
        is EventCommand.ShowPicture -> "Show picture ${cmd.pictureId}" + if (cmd.title.isNotBlank()) " (${clip(cmd.title, 24)})" else ""
        is EventCommand.MoveNpc -> (if (cmd.faceOnly) "Turn " else "Move ") + "${named(catalog, FieldRef.NPC, cmd.npcId)} ${cmd.direction.name.lowercase()}" +
            if (!cmd.faceOnly) " (${cmd.steps} ${if (cmd.steps == 1) "step" else "steps"})" else ""
        is EventCommand.TextInput -> "Ask \"${clip(cmd.prompt, 28)}\" -> ${scopePrefix(cmd.scope)}${cmd.variableId}" + if (cmd.isNumeric) " (number)" else ""
        is EventCommand.Label -> "Label: ${cmd.name}"
        is EventCommand.JumpToLabel -> "Jump to label: ${cmd.name}"
        is EventCommand.Loop -> "Repeat:"
        EventCommand.BreakLoop -> "Break loop"
    }

    /** The steps of a page as a flat list for display: each step, then under a dialogue its choices and their steps, indented. */
    fun rows(commands: List<EventCommand>, catalog: WorldContentCatalogDto, base: List<Int> = emptyList(), depth: Int = 0): List<EventRow> {
        val out = ArrayList<EventRow>()
        commands.forEachIndexed { i, cmd ->
            val path = base + i
            out += EventRow(path, depth, false, "${i + 1}. ${summary(cmd, catalog)}")
            if (cmd is EventCommand.ShowDialogue) cmd.choices.forEachIndexed { ci, choice ->
                out += EventRow(path + ci, depth + 1, true, "If the player answers \"${clip(choice.label, 32)}\":")
                out += rows(choice.commands, catalog, path + ci, depth + 2)
            }
            if (cmd is EventCommand.Conditional) {
                out += EventRow(path + THEN, depth + 1, true, "Then:")
                out += rows(cmd.then, catalog, path + THEN, depth + 2)
                out += EventRow(path + OTHERWISE, depth + 1, true, "Otherwise:")
                out += rows(cmd.otherwise, catalog, path + OTHERWISE, depth + 2)
            }
            if (cmd is EventCommand.Loop) {
                out += EventRow(path + LOOP_BODY, depth + 1, true, "Do each pass:")
                out += rows(cmd.commands, catalog, path + LOOP_BODY, depth + 2)
            }
        }
        return out
    }

    /** The two branches of an If step, or the single body branch of a Repeat loop, as the second-to-last number of a path. */
    const val THEN = 0
    const val OTHERWISE = 1
    const val LOOP_BODY = 0

    /** Deepest nesting of answers and If branches; the same limit as the server's content rules. */
    const val MAX_NESTING = 8

    /** Where an event is started from, in words: its map tile and every creature that points at it. */
    fun usedBy(event: EventDefinition, catalog: WorldContentCatalogDto): String {
        val parts = ArrayList<String>()
        if (event.mapId != 0) parts += "tile ${event.tileX}, ${event.tileY} on ${named(catalog, FieldRef.MAP, event.mapId)}"
        catalog.npcs.filter { it.eventId == event.id }.sortedBy { it.id }.forEach { parts += "creature ${it.name} (#${it.id})" }
        return if (parts.isEmpty()) "Nothing starts this event yet. Place it on a map tile, or set it as a creature's Event." else "Started from: " + parts.joinToString("; ")
    }

    /** Everything wrong with [event] that the server would refuse or that would do nothing, in the editor's words. */
    fun problems(event: EventDefinition, catalog: WorldContentCatalogDto): List<String> {
        val out = ArrayList<String>()
        if (event.name.isBlank()) out += "Name cannot be empty"
        if (event.mapId != 0 && catalog.maps.isNotEmpty()) {
            val m = catalog.maps.firstOrNull { it.id == event.mapId }
            if (m == null) out += "Map ${event.mapId} does not exist"
            else if (event.tileX !in 0 until m.width || event.tileY !in 0 until m.height) out += "Tile ${event.tileX}, ${event.tileY} is outside ${m.name} (${m.width} x ${m.height})"
        }
        if (event.pages.isEmpty()) out += "An event needs at least one page"
        event.pages.forEachIndexed { pi, page ->
            val at = "Page ${pi + 1}"
            val c = page.condition
            if (c.requiredQuestId != 0 && missing(catalog.quests.map { it.id }, c.requiredQuestId)) out += "$at: quest ${c.requiredQuestId} does not exist"
            if (c.requiredItemId != 0 && missing(catalog.items.map { it.id }, c.requiredItemId)) out += "$at: item ${c.requiredItemId} does not exist"
            if (c.requiredQuestId != 0 && c.requiredQuestStatus == null) out += "$at: choose whether quest ${c.requiredQuestId} must be in progress or completed (without that it is not checked)"
            if (c.requiredQuestStatus != null && c.requiredQuestId == 0) out += "$at: choose which quest must be ${ c.requiredQuestStatus!!.name.lowercase().replace('_', ' ') }"
            commandProblems(page.commands, catalog, at, out, 0)
        }
        return out
    }

    private fun missing(ids: List<Int>, id: Int): Boolean = ids.isNotEmpty() && id !in ids

    private fun commandProblems(list: List<EventCommand>, catalog: WorldContentCatalogDto, at: String, out: MutableList<String>, depth: Int) {
        if (depth > MAX_NESTING) {
            if (list.isNotEmpty()) out += "$at: steps are nested more than $MAX_NESTING levels deep"
            return
        }
        list.forEachIndexed { i, cmd ->
            val here = "$at, step ${i + 1}"
            for (p in FormRules.fieldProblems(fieldsFor(cmd), valuesOf(cmd))) out += "$here: $p"
            when (cmd) {
                is EventCommand.Conditional -> {
                    for ((ci, c) in cmd.allConditions.withIndex()) {
                        val gone = when (c.type) {
                            EventConditionType.HAS_ITEM -> if (missing(catalog.items.map { it.id }, c.targetId)) "item" else null
                            EventConditionType.QUEST -> if (missing(catalog.quests.map { it.id }, c.targetId)) "quest" else null
                            EventConditionType.CLASS -> if (missing(catalog.classes.map { it.id }, c.targetId)) "class" else null
                            EventConditionType.KNOWS_SPELL -> if (missing(catalog.spells.map { it.id }, c.targetId)) "spell" else null
                            else -> null
                        }
                        val checkTag = if (cmd.extraConditions.isEmpty()) "" else " (check ${ci + 1})"
                        if (gone != null) out += "$here$checkTag: $gone ${c.targetId} does not exist"
                    }
                    commandProblems(cmd.then, catalog, "$here, then", out, depth + 1)
                    commandProblems(cmd.otherwise, catalog, "$here, otherwise", out, depth + 1)
                }
                is EventCommand.RepairGear -> if (missing(catalog.items.map { it.id }, cmd.currencyItemId)) out += "$here: item ${cmd.currencyItemId} does not exist"
                is EventCommand.RespecAttributes -> if (cmd.pricePerPoint > 0 && missing(catalog.items.map { it.id }, cmd.currencyItemId)) out += "$here: item ${cmd.currencyItemId} does not exist"
                is EventCommand.TeachSpell -> if (missing(catalog.spells.map { it.id }, cmd.spellId)) out += "$here: spell ${cmd.spellId} does not exist"
                is EventCommand.GiveItem -> if (missing(catalog.items.map { it.id }, cmd.itemId)) out += "$here: item ${cmd.itemId} does not exist"
                is EventCommand.TakeItem -> if (missing(catalog.items.map { it.id }, cmd.itemId)) out += "$here: item ${cmd.itemId} does not exist"
                is EventCommand.StartQuest -> if (missing(catalog.quests.map { it.id }, cmd.questId)) out += "$here: quest ${cmd.questId} does not exist"
                is EventCommand.CompleteQuest -> if (missing(catalog.quests.map { it.id }, cmd.questId)) out += "$here: quest ${cmd.questId} does not exist"
                is EventCommand.OpenShop -> if (missing(catalog.shops.map { it.id }, cmd.shopId)) out += "$here: shop ${cmd.shopId} does not exist"
                is EventCommand.PlayAnimation -> if (missing(catalog.animations.map { it.id }, cmd.animationId)) out += "$here: animation ${cmd.animationId} does not exist"
                is EventCommand.RunCommonEvent -> if (missing(catalog.commonEvents.map { it.id }, cmd.commonEventId)) out += "$here: common event ${cmd.commonEventId} does not exist"
                is EventCommand.MoveNpc -> if (missing(catalog.npcs.map { it.id }, cmd.npcId)) out += "$here: creature ${cmd.npcId} does not exist"
                is EventCommand.WarpPlayer -> if (catalog.maps.isNotEmpty()) {
                    val m = catalog.maps.firstOrNull { it.id == cmd.mapId }
                    if (m == null) out += "$here: map ${cmd.mapId} does not exist"
                    else if (cmd.tileX !in 0 until m.width || cmd.tileY !in 0 until m.height) out += "$here: tile ${cmd.tileX}, ${cmd.tileY} is outside ${m.name} (${m.width} x ${m.height})"
                }
                is EventCommand.InvokeKotlinHook -> Unit
                is EventCommand.ShowDialogue -> cmd.choices.forEachIndexed { ci, choice ->
                    if (choice.label.isBlank()) out += "$here: answer ${ci + 1} needs some text"
                    commandProblems(choice.commands, catalog, "$here, answer ${ci + 1}", out, depth + 1)
                }
                is EventCommand.Loop -> commandProblems(cmd.commands, catalog, "$here, repeat", out, depth + 1)
                else -> Unit
            }
        }
    }

    /** A copy of a choice list with one more answer, named so it does not repeat an existing one. */
    fun withNewChoice(choices: List<DialogueChoice>): List<DialogueChoice> {
        val defaults = listOf("Yes", "No")
        val label = defaults.firstOrNull { d -> choices.none { it.label == d } } ?: "Answer ${choices.size + 1}"
        return choices + DialogueChoice(label)
    }

    val commonEventFields: List<FormField> = listOf(
        FormField("name", "Name", FormFieldType.TEXT, required = true),
        FormField("trigger", "Runs automatically", FormFieldType.CHOICE, CommonEventTrigger.MANUAL.name, choices = commonTriggers),
        FormField("intervalTicks", "Timer interval (ticks, 20 = 1s)", FormFieldType.INT, "200", min = 1, max = 72000,
            hint = "Used when Runs automatically is On a timer (1..72000 ticks)."),
        FormField("description", "Notes (optional)", FormFieldType.LINES, hint = "Reminder of when and why events call this common event.")
    )

    fun commonEventValues(ce: CommonEventDefinition): Map<String, String> = mapOf(
        "name" to ce.name,
        "trigger" to ce.trigger.name,
        "intervalTicks" to ce.intervalTicks.toString(),
        "description" to ce.description
    )

    fun applyCommonEvent(ce: CommonEventDefinition, v: Map<String, String>): CommonEventDefinition = ce.copy(
        name = FormRules.text(v, "name", ce.name).ifBlank { ce.name },
        trigger = CommonEventTrigger.entries.firstOrNull { it.name == v["trigger"] } ?: ce.trigger,
        intervalTicks = FormRules.int(v, "intervalTicks", ce.intervalTicks).coerceIn(1, 72000),
        description = v["description"]?.trimEnd() ?: ce.description
    )

    private fun callsCommonEvent(list: List<EventCommand>, targetId: Int): Boolean = list.any { cmd ->
        when (cmd) {
            is EventCommand.RunCommonEvent -> cmd.commonEventId == targetId
            is EventCommand.ShowDialogue -> cmd.choices.any { callsCommonEvent(it.commands, targetId) }
            is EventCommand.Conditional -> callsCommonEvent(cmd.then, targetId) || callsCommonEvent(cmd.otherwise, targetId)
            is EventCommand.Loop -> callsCommonEvent(cmd.commands, targetId)
            else -> false
        }
    }

    /** Which events and other common events call [commonEvent] (and any automatic trigger), in plain words. */
    fun commonEventUsedBy(commonEvent: CommonEventDefinition, catalog: WorldContentCatalogDto): String {
        val callers = ArrayList<String>()
        catalog.events.filter { e -> e.pages.any { callsCommonEvent(it.commands, commonEvent.id) } }
            .sortedBy { it.id }
            .forEach { callers += "event ${it.name} (#${it.id})" }
        catalog.commonEvents.filter { ce -> ce.id != commonEvent.id && callsCommonEvent(ce.commands, commonEvent.id) }
            .sortedBy { it.id }
            .forEach { callers += "common event ${it.name} (#${it.id})" }
        val autoLabel = when (commonEvent.trigger) {
            CommonEventTrigger.MANUAL -> null
            CommonEventTrigger.ON_TIMER -> "Runs automatically every ${commonEvent.intervalTicks} ticks"
            CommonEventTrigger.ON_LOGIN -> "Runs automatically when a player logs in"
            CommonEventTrigger.ON_LEVEL_UP -> "Runs automatically when a player levels up"
        }
        val calledPart = if (callers.isEmpty()) null else "Called by: " + callers.joinToString("; ")
        return when {
            autoLabel != null && calledPart != null -> "$autoLabel; $calledPart"
            autoLabel != null -> autoLabel
            calledPart != null -> calledPart
            else -> "No event calls this yet. Use Run common event in any event step, or pick an automatic trigger above."
        }
    }

    /** Everything wrong with [commonEvent] that the server would refuse or that would do nothing. */
    fun commonEventProblems(commonEvent: CommonEventDefinition, catalog: WorldContentCatalogDto): List<String> {
        val out = ArrayList<String>()
        if (commonEvent.name.isBlank()) out += "Name cannot be empty"
        if (commonEvent.trigger == CommonEventTrigger.ON_TIMER && commonEvent.intervalTicks !in 1..72000) {
            out += "Timer interval must be between 1 and 72000 ticks"
        }
        commandProblems(commonEvent.commands, catalog, "Steps", out, 0)
        return out
    }
}
