package com.morgana.domain.sim

import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.ConditionMatchMode
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.QuestCheck
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.event.VariableScope
import com.morgana.domain.spatial.WorldPosition
import kotlin.math.abs

/**
 * Page-based events, dialogue, and quest progression.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

private fun WorldSimulation.readSwitch(player: CharacterState, key: String, scope: VariableScope): Boolean =
    (if (scope == VariableScope.WORLD) worldSwitches[key] else player.switches[key]) ?: false

private fun WorldSimulation.writeSwitch(
    characterId: String,
    player: CharacterState,
    key: String,
    value: Boolean,
    scope: VariableScope,
    out: MutableList<WorldSimulationEvent>
) {
    if (scope == VariableScope.WORLD) {
        worldSwitches[key] = value
    } else {
        val updated = player.copy(switches = player.switches + (key to value))
        players[characterId] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
}

private fun WorldSimulation.readVariable(player: CharacterState, key: String, scope: VariableScope): Int =
    (if (scope == VariableScope.WORLD) worldVariables[key] else player.variables[key]) ?: 0

private fun WorldSimulation.writeVariable(
    characterId: String,
    player: CharacterState,
    key: String,
    value: Int,
    scope: VariableScope,
    out: MutableList<WorldSimulationEvent>
) {
    if (scope == VariableScope.WORLD) {
        worldVariables[key] = value
    } else {
        val updated = player.copy(variables = player.variables + (key to value))
        players[characterId] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
}

private fun WorldSimulation.writeStringVariable(
    characterId: String,
    player: CharacterState,
    key: String,
    value: String,
    scope: VariableScope,
    out: MutableList<WorldSimulationEvent>
) {
    if (scope == VariableScope.WORLD) {
        worldStringVariables[key] = value
    } else {
        val updated = player.copy(stringVariables = player.stringVariables + (key to value))
        players[characterId] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
}

private val PLACEHOLDER_REGEX = Regex("\\{([A-Za-z0-9_]+(?::[A-Za-z0-9_ -]+)?)\\}")

/** Expands dialogue and chat placeholders (`{player}`, `{level}`, `{visits}`, `{var:k}`, `{world:k}`, `{item:id}`, etc.). */
internal fun WorldSimulation.formatEventText(player: CharacterState, raw: String): String {
    if ('{' !in raw) return raw
    return PLACEHOLDER_REGEX.replace(raw) { match ->
        val token = match.groupValues[1].trim()
        val colon = token.indexOf(':')
        if (colon < 0) {
            when (token.lowercase()) {
                "player", "name" -> player.name
                "level" -> player.level.toString()
                "map" -> maps[player.position.mapId]?.name ?: "Map #${player.position.mapId}"
                "promotion", "rank" -> game.definition.promotion?.let { it.nameOf(it.tierOf(player), player.classId) } ?: ""
                "nobility" -> game.definition.nobility?.rankOf(player)?.name ?: "Commoner"
                "battlepower", "bp" -> (game.definition.battlePower?.of(player, game.definition, items) ?: 0).toString()
                "guild" -> player.guildId?.let { guilds[it]?.name } ?: "no guild"
                "guildtag" -> player.guildId?.let { guilds[it]?.tag }.orEmpty()
                "guildmotd" -> player.guildId?.let { guilds[it]?.motd }.orEmpty()
                "guildwins" -> (player.guildId?.let { guilds[it]?.warWins } ?: 0).toString()
                else -> player.stringVariables[token]
                    ?: worldStringVariables[token]
                    ?: player.variables[token]?.toString()
                    ?: worldVariables[token]?.toString()
                    ?: "0"
            }
        } else {
            val prefix = token.substring(0, colon).trim().lowercase()
            val arg = token.substring(colon + 1).trim()
            when (prefix) {
                "var" -> (player.variables[arg] ?: 0).toString()
                "world", "world_var" -> (worldVariables[arg] ?: 0).toString()
                "switch" -> if (player.switches[arg] == true) "on" else "off"
                "world_switch" -> if (worldSwitches[arg] == true) "on" else "off"
                "text", "str" -> player.stringVariables[arg].orEmpty()
                "world_text", "world_str" -> worldStringVariables[arg].orEmpty()
                "item" -> arg.toIntOrNull()?.let { player.inventory.totalCountOf(it).toString() } ?: match.value
                "item_name" -> arg.toIntOrNull()?.let { items[it]?.name ?: "#$it" } ?: match.value
                "vital" -> (player.vitals[arg]?.cur ?: 0).toString()
                "max_vital" -> (player.vitals[arg]?.max ?: 0).toString()
                "attr" -> (player.attributes[arg] ?: 0).toString()
                else -> match.value
            }
        }
    }
}

internal fun WorldSimulation.resolveActiveEventPageImpl(player: CharacterState, event: EventDefinition): EventPage? {
    // Evaluate pages from highest index to lowest (Morgana event page priority)
    return event.pages.sortedByDescending { it.pageIndex }.firstOrNull { page ->
        val cond = page.condition
        val switchOk = cond.requiredSwitchId == null ||
            readSwitch(player, cond.requiredSwitchId, cond.switchScope) == cond.requiredSwitchValue
        val varOk = cond.variableId == null ||
            readVariable(player, cond.variableId, cond.variableScope) >= cond.minVariableValue
        val questOk = if (cond.requiredQuestId > 0) {
            val qState = player.quests[cond.requiredQuestId]
            cond.requiredQuestStatus == null || qState?.status == cond.requiredQuestStatus
        } else {
            true
        }
        val itemOk = cond.requiredItemId <= 0 || player.inventory.hasItem(cond.requiredItemId, 1)
        val levelOk = player.level >= cond.minPlayerLevel
        switchOk && varOk && questOk && itemOk && levelOk
    }
}

/**
 * A dialogue the player has been shown and the event is waiting on: the answers it offered and the steps still to run once the
 * player answers (or, for a line with no answers, closes it). Transient, like [CraftingAnchor]: never saved.
 */
data class PendingDialogue(
    val eventId: Int,
    val choices: List<DialogueChoice>,
    val remaining: List<List<EventCommand>>,
    val root: List<EventCommand> = emptyList()
)

/**
 * An event paused on [EventCommand.Wait] until [resumeAtTick] (inclusive). Transient, cleared if the player logs out or starts a new event.
 */
data class PendingEventWait(
    val eventId: Int,
    val resumeAtTick: Long,
    val remaining: List<List<EventCommand>>,
    val root: List<EventCommand> = emptyList()
)

/**
 * An event paused on [EventCommand.TextInput] waiting for the player's typed value. Transient, never saved.
 */
data class PendingTextInput(
    val eventId: Int,
    val variableId: String,
    val scope: VariableScope,
    val isNumeric: Boolean,
    val maxLength: Int,
    val remaining: List<List<EventCommand>>,
    val root: List<EventCommand> = emptyList()
)

/** Resumes any waiting events whose delay tick has arrived. */
internal fun WorldSimulation.tickEventWaits(out: MutableList<WorldSimulationEvent>) {
    if (pendingEventWaits.isEmpty()) return
    val due = pendingEventWaits.entries.filter { currentTick >= it.value.resumeAtTick }.map { it.key to it.value }
    for ((charId, wait) in due) {
        pendingEventWaits.remove(charId)
        if (charId in players) {
            runEventSteps(charId, wait.eventId, wait.remaining, out, wait.root)
        }
    }
}

private fun WorldSimulation.moveNpcByEvent(
    player: CharacterState,
    cmd: EventCommand.MoveNpc,
    out: MutableList<WorldSimulationEvent>
) {
    val map = maps[player.position.mapId] ?: return
    val npcList = mapNpcs[map.id] ?: return
    val idx = npcList.indices
        .filter { npcList[it].npcId == cmd.npcId && npcList[it].isAlive }
        .minByOrNull { abs(npcList[it].tileX - player.position.tileX) + abs(npcList[it].tileY - player.position.tileY) }
        ?: return
    var npc = npcList[idx].copy(direction = cmd.direction)
    if (!cmd.faceOnly) {
        val count = cmd.steps.coerceIn(1, 20)
        repeat(count) {
            val nextTile = npc.tileCoord.step(cmd.direction)
            if (!canNpcStepTo(map, npc.tileCoord, nextTile, cmd.direction)) return@repeat
            npc = npc.copy(
                tileX = nextTile.x,
                tileY = nextTile.y,
                homeX = nextTile.x,
                homeY = nextTile.y,
                direction = cmd.direction,
                lastMoveTick = currentTick
            )
        }
    }
    npcList[idx] = npc
    out.add(WorldSimulationEvent.NpcUpdated(npc))
}

/** Most steps one run of an event may execute, so a page cannot stall the server. */
private const val MAX_EVENT_STEPS = 2000

/** Whether [condition] holds for [player] right now. [EventConditionType.CHANCE] draws from the simulation's seeded generator. */
internal fun WorldSimulation.eventConditionHolds(player: CharacterState, condition: EventCondition): Boolean = when (condition.type) {
    EventConditionType.SWITCH -> readSwitch(player, condition.key, condition.scope) == (condition.value != 0)
    EventConditionType.VARIABLE -> condition.comparison.holds(readVariable(player, condition.key, condition.scope), condition.value)
    EventConditionType.HAS_ITEM -> player.inventory.hasItem(condition.targetId, condition.value.coerceAtLeast(1))
    EventConditionType.GUILD_HOLDS_AREA -> (player.guildId?.let { guilds[it]?.holdsGuildArea } == true) == (condition.value != 0)
    EventConditionType.QUEST -> {
        val state = player.quests[condition.targetId]
        when (condition.quest) {
            QuestCheck.NOT_STARTED -> state == null || questReady(quests[condition.targetId], state)
            QuestCheck.IN_PROGRESS -> state?.status == QuestStatus.IN_PROGRESS
            QuestCheck.COMPLETED -> state?.status == QuestStatus.COMPLETED
            QuestCheck.READY_TO_COMPLETE -> {
                val def = quests[condition.targetId]
                state?.status == QuestStatus.IN_PROGRESS && def != null &&
                    def.objectives.indices.all { state.objectiveCounts.getOrElse(it) { 0 } >= def.objectives[it].requiredCount }
            }
        }
    }
    EventConditionType.LEVEL -> condition.comparison.holds(player.level, condition.value)
    EventConditionType.CLASS -> player.classId == condition.targetId
    EventConditionType.KNOWS_SPELL -> condition.targetId in player.knownSpellIds
    EventConditionType.CHANCE -> rng.nextIntInclusive(1, 100) <= condition.value
}

internal fun WorldSimulation.executeEventCommandsImpl(
    characterId: String,
    eventId: Int,
    commands: List<EventCommand>,
    out: MutableList<WorldSimulationEvent>
) {
    pendingEventWaits.remove(characterId)
    pendingTextInputs.remove(characterId)
    runEventSteps(characterId, eventId, listOf(commands), out, commands)
}

internal fun WorldSimulation.testEventCommandsImpl(
    editorCharacterId: String,
    eventId: Int,
    commands: List<EventCommand>
): List<WorldSimulationEvent> {
    val editor = players[editorCharacterId] ?: return emptyList()
    if (!editor.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return emptyList()
    if (commands.isEmpty()) return emptyList()
    pendingDialogues.remove(editorCharacterId)
    pendingEventWaits.remove(editorCharacterId)
    pendingTextInputs.remove(editorCharacterId)
    val out = mutableListOf<WorldSimulationEvent>()
    runEventSteps(editorCharacterId, eventId, listOf(commands), out, commands)
    return out
}

/** Finds the continuation frames starting right after the first [EventCommand.Label] named [target] in [list]. */
private fun findLabelContinuation(list: List<EventCommand>, target: String): List<List<EventCommand>>? {
    list.forEachIndexed { i, cmd ->
        val tail = list.subList(i + 1, list.size)
        when (cmd) {
            is EventCommand.Label -> if (cmd.name == target) return listOf(tail)
            is EventCommand.Conditional -> {
                findLabelContinuation(cmd.then, target)?.let { return it + listOf(tail) }
                findLabelContinuation(cmd.otherwise, target)?.let { return it + listOf(tail) }
            }
            is EventCommand.ShowDialogue -> {
                for (choice in cmd.choices) {
                    findLabelContinuation(choice.commands, target)?.let { return it + listOf(tail) }
                }
            }
            is EventCommand.Loop -> {
                findLabelContinuation(cmd.commands, target)?.let { return it + listOf(listOf(cmd), tail) }
            }
            else -> {}
        }
    }
    return null
}

/**
 * Finishes non-interactive trailing steps when a player disconnects mid-dialogue (a monologue with no choices) or mid-[EventCommand.Wait].
 * Unanswered dialogues with choices and [EventCommand.TextInput] prompts are discarded instead of guessing user input.
 */
internal fun WorldSimulation.finishPendingEventsOnDisconnect(
    characterId: String,
    out: MutableList<WorldSimulationEvent>
) {
    val dialogue = pendingDialogues.remove(characterId)
    val wait = pendingEventWaits.remove(characterId)
    pendingTextInputs.remove(characterId)
    when {
        dialogue != null && dialogue.choices.isEmpty() && dialogue.remaining.isNotEmpty() ->
            runEventSteps(characterId, dialogue.eventId, dialogue.remaining, out, dialogue.root, disconnectDrain = true)
        wait != null && wait.remaining.isNotEmpty() ->
            runEventSteps(characterId, wait.eventId, wait.remaining, out, wait.root, disconnectDrain = true)
    }
}

private data class StepFrame(val cmds: List<EventCommand>, val scopeId: Int, val scopeRoot: List<EventCommand>)

/**
 * Runs an event's steps in order. [lists] is what is left to do, innermost first: the steps of the branch being run, then the
 * steps that follow it in the enclosing list, and so on. An `if` pushes the branch it chose. A dialogue that offers answers,
 * or that has steps after it, **pauses** the run: what is left is remembered in [WorldSimulation.pendingDialogues] and continues
 * from [selectDialogueChoiceImpl] when the player answers or closes the line.
 */
internal fun WorldSimulation.runEventSteps(
    characterId: String,
    eventId: Int,
    lists: List<List<EventCommand>>,
    out: MutableList<WorldSimulationEvent>,
    root: List<EventCommand> = emptyList(),
    disconnectDrain: Boolean = false
) {
    var nextScopeId = 1
    val rootScopeId = 0
    val stack = ArrayDeque(lists.map { StepFrame(it, rootScopeId, root) })
    var steps = 0
    while (stack.isNotEmpty()) {
        val frame = stack.removeFirst()
        val top = frame.cmds
        if (top.isEmpty()) continue
        val cmd = top.first()
        if (top.size > 1) stack.addFirst(StepFrame(top.subList(1, top.size), frame.scopeId, frame.scopeRoot))
        if (++steps > MAX_EVENT_STEPS) return
        val player = players[characterId] ?: return
        when (cmd) {
            is EventCommand.ShowDialogue -> {
                if (disconnectDrain) {
                    if (cmd.choices.isNotEmpty()) return
                    continue
                }
                pendingTextInputs.remove(characterId)
                out.add(
                    WorldSimulationEvent.OpenUiRequested(
                        characterId = characterId,
                        windowType = "DIALOGUE",
                        eventId = eventId,
                        speakerName = formatEventText(player, cmd.speakerName),
                        dialogueText = formatEventText(player, cmd.text),
                        choiceLabels = cmd.choices.map { formatEventText(player, it.label) }
                    )
                )
                val remaining = stack.map { it.cmds }.filter { it.isNotEmpty() }
                if (cmd.choices.isEmpty() && remaining.isEmpty()) {
                    pendingDialogues.remove(characterId) // the last line: nothing waits on it
                } else {
                    pendingDialogues[characterId] = PendingDialogue(eventId, cmd.choices, remaining, root)
                }
                return
            }
            is EventCommand.TextInput -> {
                if (disconnectDrain) return
                val maxLen = cmd.maxLength.coerceIn(1, 200)
                val prompt = formatEventText(player, cmd.prompt)
                val initial = formatEventText(player, cmd.defaultValue).take(maxLen)
                out.add(
                    WorldSimulationEvent.TextInputRequested(
                        characterId = characterId,
                        eventId = eventId,
                        prompt = prompt,
                        isNumeric = cmd.isNumeric,
                        defaultValue = initial,
                        maxLength = maxLen
                    )
                )
                pendingDialogues.remove(characterId)
                pendingTextInputs[characterId] = PendingTextInput(
                    eventId = eventId,
                    variableId = cmd.variableId,
                    scope = cmd.scope,
                    isNumeric = cmd.isNumeric,
                    maxLength = maxLen,
                    remaining = stack.map { it.cmds }.filter { it.isNotEmpty() },
                    root = root
                )
                return
            }
            is EventCommand.Conditional -> {
                val matched = when (cmd.matchMode) {
                    ConditionMatchMode.ALL -> cmd.allConditions.all { eventConditionHolds(player, it) }
                    ConditionMatchMode.ANY -> cmd.allConditions.any { eventConditionHolds(player, it) }
                }
                stack.addFirst(StepFrame(if (matched) cmd.then else cmd.otherwise, frame.scopeId, frame.scopeRoot))
            }
            is EventCommand.RunCommonEvent -> {
                val ce = commonEvents[cmd.commonEventId]
                if (ce != null && ce.commands.isNotEmpty()) {
                    stack.addFirst(StepFrame(ce.commands, nextScopeId++, ce.commands))
                }
            }
            is EventCommand.Label -> {}
            is EventCommand.JumpToLabel -> {
                val localCont = if (frame.scopeRoot.isNotEmpty()) findLabelContinuation(frame.scopeRoot, cmd.name) else null
                if (localCont != null) {
                    while (stack.firstOrNull()?.scopeId == frame.scopeId) stack.removeFirst()
                    for (cont in localCont.asReversed()) {
                        if (cont.isNotEmpty()) stack.addFirst(StepFrame(cont, frame.scopeId, frame.scopeRoot))
                    }
                } else if (frame.scopeId != rootScopeId && root.isNotEmpty()) {
                    val rootCont = findLabelContinuation(root, cmd.name)
                    if (rootCont != null) {
                        stack.clear()
                        for (cont in rootCont.asReversed()) {
                            if (cont.isNotEmpty()) stack.addFirst(StepFrame(cont, rootScopeId, root))
                        }
                    }
                }
            }
            is EventCommand.Loop -> {
                if (cmd.commands.isNotEmpty()) {
                    stack.addFirst(StepFrame(listOf(cmd), frame.scopeId, frame.scopeRoot))
                    stack.addFirst(StepFrame(cmd.commands, frame.scopeId, frame.scopeRoot))
                }
            }
            EventCommand.BreakLoop -> {
                val loopIdx = stack.indexOfFirst { it.cmds.firstOrNull() is EventCommand.Loop }
                if (loopIdx >= 0) {
                    repeat(loopIdx + 1) { stack.removeFirst() }
                }
            }
            is EventCommand.Wait -> {
                if (disconnectDrain) continue
                val remaining = stack.map { it.cmds }.filter { it.isNotEmpty() }
                if (remaining.isNotEmpty()) {
                    pendingDialogues.remove(characterId)
                    pendingTextInputs.remove(characterId)
                    pendingEventWaits[characterId] = PendingEventWait(
                        eventId = eventId,
                        resumeAtTick = currentTick + cmd.ticks.coerceAtLeast(1),
                        remaining = remaining,
                        root = root
                    )
                }
                return
            }
            is EventCommand.PlaySound -> {
                if (!disconnectDrain) out.add(WorldSimulationEvent.SoundPlayed(characterId, player.position.mapId, cmd.soundId))
            }
            is EventCommand.ShowPicture -> {
                if (!disconnectDrain) out.add(WorldSimulationEvent.PictureShown(characterId, cmd.pictureId, cmd.title, cmd.durationTicks))
            }
            is EventCommand.MoveNpc -> moveNpcByEvent(player, cmd, out)
            is EventCommand.Stop -> return
            is EventCommand.SetVariable -> writeVariable(characterId, player, cmd.variableId, cmd.value, cmd.scope, out)
            is EventCommand.ShowMessage -> {
                out.add(
                    WorldSimulationEvent.ChatEmitted(
                        channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
                        targetCharacterId = characterId, message = formatEventText(player, cmd.text), showOverheadBubble = false
                    )
                )
            }
            is EventCommand.Rebirth, EventCommand.Promote, is EventCommand.ArenaQueue,
            is EventCommand.UpgradeGear, is EventCommand.RepairGear, is EventCommand.RespecAttributes -> runSystemCommand(characterId, cmd, out)
            is EventCommand.TeachSpell -> {
                val spell = spells[cmd.spellId]
                if (spell != null && cmd.spellId !in player.knownSpellIds && player.level >= spell.levelReq && (spell.classReqIds.isEmpty() || player.classId in spell.classReqIds) && spellRefusal(player, spell) == null) {
                    val updated = player.copy(knownSpellIds = player.knownSpellIds + cmd.spellId)
                    players[characterId] = updated
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                }
            }
            is EventCommand.GiveItem -> {
                val def = items[cmd.itemId]
                if (def != null) {
                    val mut = player.inventory.addItem(def, cmd.amount)
                    val updated = player.copy(inventory = mut.updatedInventory)
                    players[characterId] = updated
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                }
            }
            is EventCommand.TakeItem -> {
                val after = player.inventory.removeByItemId(cmd.itemId, cmd.amount)
                if (after != null) {
                    val updated = player.copy(inventory = after)
                    players[characterId] = updated
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                }
            }
            is EventCommand.GiveXp -> {
                grantXpToCharacter(characterId, cmd.amount, out)
            }
            is EventCommand.SetSwitch -> writeSwitch(characterId, player, cmd.switchId, cmd.value, cmd.scope, out)
            is EventCommand.AddVariable -> {
                val cur = readVariable(player, cmd.variableId, cmd.scope)
                writeVariable(characterId, player, cmd.variableId, cur + cmd.delta, cmd.scope, out)
            }
            is EventCommand.StartQuest -> {
                val qDef = quests[cmd.questId]
                val previous = player.quests[cmd.questId]
                val coolingDown = qDef != null && qDef.cooldownTicks > 0L && previous != null && previous.status == QuestStatus.COMPLETED && !questReady(qDef, previous)
                if (qDef != null && !coolingDown && (previous == null || qDef.repeatable)) {
                    val qState = PlayerQuestState(
                        questId = qDef.id,
                        status = QuestStatus.IN_PROGRESS,
                        objectiveCounts = List(qDef.objectives.size) { 0 }
                    )
                    val updated = player.copy(quests = player.quests + (qDef.id to qState))
                    players[characterId] = updated
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                }
            }
            is EventCommand.CompleteQuest -> {
                completeQuestIfEligible(characterId, cmd.questId, out)
            }
            is EventCommand.WarpPlayer -> {
                val updated = warpedPlayer(player, cmd.mapId, cmd.tileX, cmd.tileY, player.position.direction, out)
                if (updated != null) {
                    val oldMap = player.position.mapId
                    players[characterId] = updated
                    out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, updated.position.mapId))
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                    checkQuestVisitMap(updated, updated.position.mapId, out)
                    if (!disconnectDrain) fireMapEntryEvents(characterId, out)
                }
            }
            is EventCommand.HealPlayerFull -> {
                val fullVitals = player.vitals.mapValues { (_, v) -> v.copy(cur = v.max) }
                val healed = player.copy(vitals = fullVitals)
                players[characterId] = healed
                out.add(WorldSimulationEvent.CharacterUpdated(healed))
            }
            is EventCommand.OpenShop -> {
                if (!disconnectDrain) openShopFor(characterId, cmd.shopId, out)
            }
            is EventCommand.OpenBank -> {
                if (!disconnectDrain) out.add(WorldSimulationEvent.OpenUiRequested(characterId, "BANK"))
            }
            is EventCommand.OpenCrafting -> {
                if (!disconnectDrain) {
                    craftingAnchors[characterId] = CraftingAnchor(cmd.stationType, player.position.mapId, player.position.tileX, player.position.tileY)
                    out.add(WorldSimulationEvent.OpenUiRequested(characterId, "CRAFTING", stationType = cmd.stationType))
                }
            }
            is EventCommand.PlayAnimation -> {
                out.add(
                    WorldSimulationEvent.AnimationTriggered(
                        mapId = player.position.mapId,
                        tileX = player.position.tileX,
                        tileY = player.position.tileY,
                        animationId = cmd.animationId
                    )
                )
            }
            is EventCommand.InvokeKotlinHook -> {
                val worldView = asScriptWorldView()
                val snapshot = worldView.getPlayer(characterId)
                val scriptEffects = if (snapshot != null) {
                    scripts.registry.executeEventCommand(cmd.hookId, snapshot, eventId, cmd.payload, worldView)
                } else null

                if (scriptEffects != null) {
                    scriptEffects.forEach { eff ->
                        out.addAll(applyScriptEffect(eff))
                    }
                } else {
                    out.add(WorldSimulationEvent.CustomHookRequested(characterId, eventId, cmd.hookId, cmd.payload))
                }
            }
        }
    }
}

/**
 * The player answered the dialogue they were shown ([choiceIndex] is the answer), or closed a line that offered no answers
 * ([choiceIndex] below 0). Runs the answer's steps, then whatever the event still had to do.
 */
internal fun WorldSimulation.selectDialogueChoiceImpl(characterId: String, eventId: Int, choiceIndex: Int): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val pending = pendingDialogues[characterId]
    if (pending != null && pending.eventId == eventId) {
        val out = mutableListOf<WorldSimulationEvent>()
        if (pending.choices.isEmpty()) {
            pendingDialogues.remove(characterId)
            runEventSteps(characterId, eventId, pending.remaining, out, pending.root)
        } else {
            val choice = pending.choices.getOrNull(choiceIndex) ?: return emptyList() // not an answer: keep waiting
            pendingDialogues.remove(characterId)
            runEventSteps(characterId, eventId, listOf(choice.commands) + pending.remaining, out, pending.root)
        }
        return out
    }
    // Nothing is waiting (a recording made before dialogues paused the run): answer the page's first dialogue, as before.
    val ev = events[eventId] ?: return emptyList()
    val page = resolveActiveEventPage(player, ev) ?: return emptyList()
    val dialogueCmd = page.commands.filterIsInstance<EventCommand.ShowDialogue>().firstOrNull() ?: return emptyList()
    val choice = dialogueCmd.choices.getOrNull(choiceIndex) ?: return emptyList()
    val out = mutableListOf<WorldSimulationEvent>()
    executeEventCommands(characterId, eventId, choice.commands, out)
    return out
}

/**
 * Receives a player's submitted text (or cancellation) for a pending [EventCommand.TextInput],
 * saves the value into the configured player/world variable, and resumes the remaining steps.
 */
internal fun WorldSimulation.submitTextInputImpl(
    characterId: String,
    eventId: Int,
    value: String,
    cancelled: Boolean
): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val pending = pendingTextInputs[characterId] ?: return emptyList()
    if (pending.eventId != eventId) return emptyList()
    pendingTextInputs.remove(characterId)
    if (cancelled) return emptyList()

    val out = mutableListOf<WorldSimulationEvent>()
    val clean = value.trim().take(pending.maxLength)
    if (pending.isNumeric) {
        val number = clean.toIntOrNull() ?: 0
        writeVariable(characterId, player, pending.variableId, number, pending.scope, out)
    } else {
        writeStringVariable(characterId, player, pending.variableId, clean, pending.scope, out)
    }
    if (pending.remaining.isNotEmpty()) {
        runEventSteps(characterId, eventId, pending.remaining, out, pending.root)
    }
    return out
}

internal fun WorldSimulation.checkQuestVisitMap(player: CharacterState, mapId: Int, out: MutableList<WorldSimulationEvent>) {
    advanceQuestProgress(player.id, QuestObjectiveType.VISIT_MAP, contentMapId(mapId), 1, out)
}

internal fun WorldSimulation.advanceQuestProgress(
    characterId: String,
    objectiveType: QuestObjectiveType,
    targetId: Int,
    delta: Int,
    out: MutableList<WorldSimulationEvent>
) {
    val player = players[characterId] ?: return
    var modified = false
    val updatedQuests = player.quests.toMutableMap()

    for ((questId, state) in player.quests) {
        if (state.status != QuestStatus.IN_PROGRESS) continue
        val qDef = quests[questId] ?: continue
        val counts = state.objectiveCounts.toMutableList()
        while (counts.size < qDef.objectives.size) counts.add(0)

        var questChanged = false
        for (i in qDef.objectives.indices) {
            val obj = qDef.objectives[i]
            if (obj.type == objectiveType && obj.targetId == targetId && counts[i] < obj.requiredCount) {
                counts[i] = (counts[i] + delta).coerceAtMost(obj.requiredCount)
                questChanged = true
            }
        }
        if (questChanged) {
            updatedQuests[questId] = state.copy(objectiveCounts = counts)
            modified = true
        }
    }

    if (modified) {
        val nextPlayer = player.copy(quests = updatedQuests)
        players[characterId] = nextPlayer
        out.add(WorldSimulationEvent.CharacterUpdated(nextPlayer))
    }
}

internal fun WorldSimulation.completeQuestIfEligible(
    characterId: String,
    questId: Int,
    out: MutableList<WorldSimulationEvent>
) {
    var player = players[characterId] ?: return
    val qDef = quests[questId] ?: return
    val qState = player.quests[questId] ?: return
    if (qState.status == QuestStatus.COMPLETED && !qDef.repeatable) return

    // Verify all objectives satisfied
    for (i in qDef.objectives.indices) {
        val required = qDef.objectives[i].requiredCount
        val current = qState.objectiveCounts.getOrElse(i) { 0 }
        if (current < required) return
    }

    var workingInv = player.inventory
    // The quest's gold is the game's currency: item 1 when that is one, else the lowest-numbered currency item.
    val goldDef = items[1]?.takeIf { it.type == com.morgana.domain.item.ItemType.CURRENCY }
        ?: items.values.filter { it.type == com.morgana.domain.item.ItemType.CURRENCY }.minByOrNull { it.id }
    if (goldDef != null && qDef.reward.currencyAmount > 0) {
        workingInv = workingInv.addItem(goldDef, qDef.reward.currencyAmount).updatedInventory
    }
    for (rewardItem in qDef.reward.items) {
        val def = items[rewardItem.itemId] ?: continue
        workingInv = workingInv.addItem(def, rewardItem.amount).updatedInventory
    }
    val taughtSpell = if (qDef.reward.teachSpellId > 0) spells[qDef.reward.teachSpellId] else null
    val nextSpells = if (
        taughtSpell != null &&
        qDef.reward.teachSpellId !in player.knownSpellIds &&
        player.level >= taughtSpell.levelReq &&
        (taughtSpell.classReqIds.isEmpty() || player.classId in taughtSpell.classReqIds)
    ) {
        player.knownSpellIds + qDef.reward.teachSpellId
    } else {
        player.knownSpellIds
    }

    player = player.copy(
        inventory = workingInv,
        knownSpellIds = nextSpells,
        quests = player.quests + (questId to qState.copy(status = QuestStatus.COMPLETED, completedAtTick = effectiveWorldTick()))
    )
    players[characterId] = player
    grantXpToCharacter(characterId, qDef.reward.xp, out)
}

/** Maximum nesting of map-entry events (an autorun page that warps into another autorun map). */
private const val MAX_AUTORUN_DEPTH = 3

/**
 * Runs `AUTORUN_ON_ENTER` pages for events on the player's current map, in event-id order.
 * Called after login, border crossings, warp tiles, event warp commands, and warp spells.
 * Admin teleports and death respawns intentionally do not fire entry events.
 */
internal fun WorldSimulation.fireMapEntryEvents(characterId: String, out: MutableList<WorldSimulationEvent>) {
    if (autorunDepth >= MAX_AUTORUN_DEPTH) return
    val mapId = players[characterId]?.position?.mapId ?: return
    val contentId = contentMapId(mapId)
    autorunDepth++
    try {
        for (event in events.values.filter { it.mapId == contentId }.sortedBy { it.id }) {
            val current = players[characterId] ?: return
            if (current.position.mapId != mapId) return // an earlier page warped the player away
            val page = resolveActiveEventPage(current, event) ?: continue
            if (page.trigger == EventTriggerType.AUTORUN_ON_ENTER) {
                executeEventCommands(characterId, event.id, page.commands, out)
            }
        }
    } finally {
        autorunDepth--
    }
}

/** Maximum nesting of automatic common events (e.g. an ON_LEVEL_UP event that grants XP causing another level-up). */
private const val MAX_COMMON_EVENT_DEPTH = 4

private fun commonEventRuntimeId(commonEventId: Int): Int = -commonEventId

/**
 * Runs all common events matching [trigger] ([CommonEventTrigger.ON_LOGIN] or [CommonEventTrigger.ON_LEVEL_UP])
 * for [characterId], ordered deterministically by common event id.
 */
internal fun WorldSimulation.fireCommonEvents(
    characterId: String,
    trigger: CommonEventTrigger,
    out: MutableList<WorldSimulationEvent>
) {
    if (trigger == CommonEventTrigger.MANUAL || commonEventDepth >= MAX_COMMON_EVENT_DEPTH) return
    val matching = commonEvents.values
        .filter { it.trigger == trigger && it.commands.isNotEmpty() }
        .sortedBy { it.id }
    if (matching.isEmpty()) return
    commonEventDepth++
    try {
        for (ce in matching) {
            if (characterId !in players) return
            executeEventCommands(characterId, commonEventRuntimeId(ce.id), ce.commands, out)
        }
    } finally {
        commonEventDepth--
    }
}

/**
 * Runs [CommonEventTrigger.ON_TIMER] common events whose [com.morgana.domain.event.CommonEventDefinition.intervalTicks]
 * divides [WorldSimulation.currentTick] evenly, once per online player in deterministic id order.
 */
internal fun WorldSimulation.tickTimedCommonEvents(out: MutableList<WorldSimulationEvent>) {
    if (currentTick <= 0L || players.isEmpty() || commonEvents.isEmpty()) return
    val timed = commonEvents.values
        .filter { it.trigger == CommonEventTrigger.ON_TIMER && it.intervalTicks > 0 && currentTick % it.intervalTicks == 0L }
        .sortedBy { it.id }
    if (timed.isEmpty()) return
    val playerIds = players.keys.sorted()
    for (ce in timed) {
        if (ce.commands.isEmpty()) continue
        for (charId in playerIds) {
            if (charId !in players) continue
            executeEventCommands(charId, commonEventRuntimeId(ce.id), ce.commands, out)
        }
    }
}

/**
 * True when a quest that was handed in can be taken again as a new one: it is repeatable with a [QuestDefinition.cooldownTicks],
 * and that many world ticks have passed since the hand-in. Quests with no cooldown keep the old rule (never "not started" again).
 */
internal fun WorldSimulation.questReady(def: com.morgana.domain.event.QuestDefinition?, state: PlayerQuestState): Boolean =
    def != null && def.repeatable && def.cooldownTicks > 0L && state.status == QuestStatus.COMPLETED &&
        effectiveWorldTick() - state.completedAtTick >= def.cooldownTicks
