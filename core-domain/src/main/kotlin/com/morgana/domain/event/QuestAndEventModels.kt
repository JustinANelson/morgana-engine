package com.morgana.domain.event

import com.morgana.domain.content.ContentSchema
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.spatial.Direction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Types of trackable objectives in a [QuestDefinition].
 */
@Serializable
enum class QuestObjectiveType {
    KILL_NPC,
    COLLECT_ITEM,
    TALK_TO_NPC,
    HARVEST_RESOURCE,
    CRAFT_RECIPE,
    VISIT_MAP
}

/**
 * A direction for a quest objective whose target is on another map: the tile of the player's own map ([mapId], [tileX],
 * [tileY]) to head for, which leads toward [targetMapName] by way of [viaMapName]. Computed by the server (`QuestGuides`).
 */
@Serializable
data class QuestGuide(
    val questId: Int,
    val objectiveIndex: Int,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val targetMapName: String,
    val viaMapName: String
)

/**
 * Single objective inside a quest.
 */
@Serializable
data class QuestObjective(
    val type: QuestObjectiveType,
    val targetId: Int,
    val requiredCount: Int = 1,
    val description: String = ""
)

/**
 * Rewards granted upon completing a [QuestDefinition].
 */
@Serializable
data class QuestReward(
    val xp: Long = 50L,
    val currencyAmount: Int = 25,
    val items: List<RecipeIngredient> = emptyList(),
    val teachSpellId: Int = 0
)

/**
 * Authoritative quest definition editable live via the F1 Quest Editor.
 */
@Serializable
data class QuestDefinition(
    val id: Int,
    val name: String,
    val summary: String = "",
    val levelReq: Int = 1,
    val prerequisiteQuestIds: Set<Int> = emptySet(),
    val startText: String = "",
    val inProgressText: String = "",
    val completionText: String = "",
    val objectives: List<QuestObjective> = emptyList(),
    val reward: QuestReward = QuestReward(),
    val repeatable: Boolean = false,
    /**
     * For a repeatable quest: world ticks that must pass after a hand-in before it can be taken again (0 = at once).
     * 20 ticks are a second, so a daily quest is 1,728,000 and a weekly one 12,096,000. The world clock is saved with
     * the world, so the wait survives a restart.
     */
    val cooldownTicks: Long = 0L,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Progress of a player on a specific quest.
 */
@Serializable
enum class QuestStatus {
    IN_PROGRESS,
    COMPLETED
}

@Serializable
data class PlayerQuestState(
    val questId: Int,
    val status: QuestStatus = QuestStatus.IN_PROGRESS,
    val objectiveCounts: List<Int> = emptyList(),
    /** World tick ([WorldSimulation.effectiveWorldTick]) of the last hand-in; 0 when never handed in. */
    val completedAtTick: Long = 0L
)

/** Whether a switch or number belongs to each character or is shared across the whole world. */
@Serializable
enum class VariableScope {
    PLAYER,
    WORLD
}

/**
 * How an [EventPage] is triggered on a map or NPC.
 */
@Serializable
enum class EventTriggerType {
    ACTION_INTERACT,
    PLAYER_TOUCH,
    AUTORUN_ON_ENTER
}

/**
 * When a [CommonEventDefinition] runs automatically (rather than only via [EventCommand.RunCommonEvent]).
 */
@Serializable
enum class CommonEventTrigger {
    /** Runs only when invoked by a `RunCommonEvent` step. */
    MANUAL,
    /** Runs every [CommonEventDefinition.intervalTicks] ticks for each online player. */
    ON_TIMER,
    /** Runs once when a player enters the simulation on login. */
    ON_LOGIN,
    /** Runs whenever a player gains one or more levels. */
    ON_LEVEL_UP
}

/**
 * Preconditions evaluated top-to-bottom to pick the active [EventPage] for a player.
 */
@Serializable
data class EventPageCondition(
    val requiredSwitchId: String? = null,
    val requiredSwitchValue: Boolean = true,
    val switchScope: VariableScope = VariableScope.PLAYER,
    val variableId: String? = null,
    val minVariableValue: Int = 0,
    val variableScope: VariableScope = VariableScope.PLAYER,
    val requiredQuestId: Int = 0,
    val requiredQuestStatus: QuestStatus? = null,
    val requiredItemId: Int = 0,
    val minPlayerLevel: Int = 1
)

/** What an `if` step ([EventCommand.Conditional]) checks about the player. */
@Serializable
enum class EventConditionType {
    /** The switch [EventCondition.key] is on ([EventCondition.value] not 0) or off (0). */
    SWITCH,
    /** The number [EventCondition.key] compared with [EventCondition.value]. */
    VARIABLE,
    /** The player carries at least [EventCondition.value] of item [EventCondition.targetId]. */
    HAS_ITEM,
    /** Quest [EventCondition.targetId] is in the state [EventCondition.quest]. */
    QUEST,
    /** The player's level compared with [EventCondition.value]. */
    LEVEL,
    /** The player's class is [EventCondition.targetId]. */
    CLASS,
    /** The player knows spell [EventCondition.targetId]. */
    KNOWS_SPELL,
    /** True [EventCondition.value] times in 100, from the simulation's seeded generator. */
    CHANCE,
    /** The player's guild holds the guild area (won the last Guild War) when [EventCondition.value] is 1, or does not when it is 0. */
    GUILD_HOLDS_AREA
}

@Serializable
enum class Comparison {
    EQUAL, NOT_EQUAL, AT_LEAST, AT_MOST, GREATER, LESS;

    fun holds(left: Int, right: Int): Boolean = when (this) {
        EQUAL -> left == right
        NOT_EQUAL -> left != right
        AT_LEAST -> left >= right
        AT_MOST -> left <= right
        GREATER -> left > right
        LESS -> left < right
    }
}

/** The quest states an `if` step can ask about. */
@Serializable
enum class QuestCheck {
    NOT_STARTED,
    IN_PROGRESS,
    /** In progress with every objective met, so it can be handed in. */
    READY_TO_COMPLETE,
    COMPLETED
}

/** One check an `if` step makes. Which fields matter depends on [type]. */
@Serializable
data class EventCondition(
    val type: EventConditionType = EventConditionType.SWITCH,
    /** Switch or number name. */
    val key: String = "",
    /** Item, quest, class or spell id. */
    val targetId: Int = 0,
    val comparison: Comparison = Comparison.AT_LEAST,
    val value: Int = 1,
    val quest: QuestCheck = QuestCheck.COMPLETED,
    val scope: VariableScope = VariableScope.PLAYER
)

/**
 * Visual event commands executable by the page-based Event Engine (editable in F1 Event Editor).
 */
/** Whether multiple conditions in one [EventCommand.Conditional] all need to hold or any single one suffices. */
@Serializable
enum class ConditionMatchMode { ALL, ANY }

/** What an [EventCommand.UpgradeGear] step does to worn gear. */
@Serializable
enum class GearUpgradeKind {
    /** Opens one more (empty) socket, up to the game's `gearUpgrade.maxSockets`. */
    ADD_SOCKET,
    /** Puts a gem from the bag into the first empty socket. */
    SOCKET_GEM,
    /** Turns the gear into its next tier (`nextTierItemId`), keeping `+` level, sockets and rolls. */
    UPGRADE_LEVEL,
    /** Re-rolls the gear at the next rarity of the game's loot rules, keeping `+` level and sockets. */
    UPGRADE_QUALITY,
    /**
     * One attempt at the next `+` level of the worn gear, by the game's `gearUpgrade.enhancement` ladder: the material, the
     * chance and the pity come from the ladder, so `materialItemId`, `amount` and `successPercent` are ignored.
     */
    ENHANCE
}

@Serializable
sealed interface EventCommand {
    @Serializable
    @SerialName("dialogue")
    data class ShowDialogue(
        val speakerName: String,
        val text: String,
        val portraitId: Int = 0,
        val choices: List<DialogueChoice> = emptyList()
    ) : EventCommand

    @Serializable
    @SerialName("give_item")
    data class GiveItem(
        val itemId: Int,
        val amount: Int = 1
    ) : EventCommand

    @Serializable
    @SerialName("take_item")
    data class TakeItem(
        val itemId: Int,
        val amount: Int = 1
    ) : EventCommand

    @Serializable
    @SerialName("give_xp")
    data class GiveXp(
        val amount: Long
    ) : EventCommand

    @Serializable
    @SerialName("set_switch")
    data class SetSwitch(
        val switchId: String,
        val value: Boolean,
        val scope: VariableScope = VariableScope.PLAYER
    ) : EventCommand

    @Serializable
    @SerialName("add_variable")
    data class AddVariable(
        val variableId: String,
        val delta: Int,
        val scope: VariableScope = VariableScope.PLAYER
    ) : EventCommand

    @Serializable
    @SerialName("start_quest")
    data class StartQuest(
        val questId: Int
    ) : EventCommand

    @Serializable
    @SerialName("complete_quest")
    data class CompleteQuest(
        val questId: Int
    ) : EventCommand

    @Serializable
    @SerialName("warp_player")
    data class WarpPlayer(
        val mapId: Int,
        val tileX: Int,
        val tileY: Int
    ) : EventCommand

    @Serializable
    @SerialName("heal_player")
    data object HealPlayerFull : EventCommand

    @Serializable
    @SerialName("open_shop")
    data class OpenShop(
        val shopId: Int
    ) : EventCommand

    @Serializable
    @SerialName("open_bank")
    data object OpenBank : EventCommand

    @Serializable
    @SerialName("open_crafting")
    data class OpenCrafting(
        val stationType: CraftingStationType = CraftingStationType.ANVIL
    ) : EventCommand

    @Serializable
    @SerialName("play_animation")
    data class PlayAnimation(
        val animationId: Int
    ) : EventCommand

    @Serializable
    @SerialName("kotlin_hook")
    data class InvokeKotlinHook(
        val hookId: String,
        val payload: Map<String, String> = emptyMap()
    ) : EventCommand

    /**
     * Runs [then] when the conditions hold for the player (all of them when [matchMode] is [ConditionMatchMode.ALL],
     * or at least one when [matchMode] is [ConditionMatchMode.ANY]) and [otherwise] when they do not, then carries on with the next step.
     */
    @Serializable
    @SerialName("if")
    data class Conditional(
        val condition: EventCondition = EventCondition(),
        val then: List<EventCommand> = emptyList(),
        val otherwise: List<EventCommand> = emptyList(),
        val extraConditions: List<EventCondition> = emptyList(),
        val matchMode: ConditionMatchMode = ConditionMatchMode.ALL
    ) : EventCommand {
        val allConditions: List<EventCondition> get() = listOf(condition) + extraConditions
    }

    /** Sets a named number to an exact value ([AddVariable] changes it by an amount). */
    @Serializable
    @SerialName("set_variable")
    data class SetVariable(
        val variableId: String,
        val value: Int,
        val scope: VariableScope = VariableScope.PLAYER
    ) : EventCommand

    /** A line in the player's chat window, with no window to dismiss. */
    @Serializable
    @SerialName("message")
    data class ShowMessage(
        val text: String
    ) : EventCommand

    /**
     * Works on the gear the player wears in [slot], paying with [materialItemId] from the bag (see [GearUpgradeKind]).
     * [successPercent] below 100 makes the attempt chancy; a failed attempt still uses up the materials. Refused with a
     * chat notice when the game has no `gearUpgrade`, the slot is empty, or the gear cannot take the upgrade.
     */
    @Serializable
    @SerialName("upgrade_gear")
    data class UpgradeGear(
        val kind: GearUpgradeKind = GearUpgradeKind.ADD_SOCKET,
        val slot: com.morgana.domain.item.EquipmentSlot = com.morgana.domain.item.EquipmentSlot.WEAPON,
        /** The item consumed: a Dragon Ball for sockets and quality, a Meteor for a level upgrade, a gem for socketing (0 = any gem carried). */
        val materialItemId: Int = 0,
        val amount: Int = 1,
        val successPercent: Int = 100
    ) : EventCommand

    /**
     * Rebirths the player (see `RebirthDefinition`): back to the reset level, as class [classId] (0 = keep their class).
     * Refused with a chat notice if the game has no rebirth, the player is below the minimum level or has no rebirths left.
     */
    @Serializable
    @SerialName("rebirth")
    data class Rebirth(
        val classId: Int = 0
    ) : EventCommand

    /**
     * Promotes the player one tier (see `PromotionDefinition`): needs the next tier's minimum level, then grants its
     * attribute points and spells. Refused with a chat notice if the game has no promotions, they are at the top or too low.
     */
    @Serializable
    @SerialName("promote")
    data object Promote : EventCommand

    /**
     * Repairs every worn and carried piece of gear for [currencyItemId] (Silver in most games), priced from each item's
     * price and the game's `durability.repairPricePercent`. If the player cannot afford all of it, nothing is repaired.
     */
    @Serializable
    @SerialName("repair_gear")
    data class RepairGear(
        val currencyItemId: Int = 1
    ) : EventCommand

    /**
     * Takes back every attribute point the player has spent and puts them in their unspent points to place again.
     * The price is [pricePerPoint] of [currencyItemId] for each point returned (0 is free); nothing happens if they cannot pay.
     */
    @Serializable
    @SerialName("respec_attributes")
    data class RespecAttributes(
        val currencyItemId: Int = 1,
        val pricePerPoint: Int = 0
    ) : EventCommand

    /**
     * Puts the player in (or takes them out of) the arena queue, or shows their rating or the top ratings online, the same as
     * the `/arena` chat command, so a game can offer the arena from an NPC without the player typing. [size] is the team size for
     * [ArenaQueueAction.JOIN] (0 uses the arena's first size).
     */
    @Serializable
    @SerialName("arena_queue")
    data class ArenaQueue(
        val action: ArenaQueueAction = ArenaQueueAction.JOIN,
        val size: Int = 0
    ) : EventCommand

    @Serializable
    @SerialName("teach_spell")
    data class TeachSpell(
        val spellId: Int
    ) : EventCommand

    /** Runs the steps of a shared [CommonEventDefinition] from the common-events library, then continues. */
    @Serializable
    @SerialName("run_common_event")
    data class RunCommonEvent(
        val commonEventId: Int
    ) : EventCommand

    /** Pauses the running event for [ticks] simulation ticks (20 TPS), then continues. */
    @Serializable
    @SerialName("wait")
    data class Wait(
        val ticks: Int = 20
    ) : EventCommand

    /** Plays a sound effect by id ([soundId]) for the triggering player. */
    @Serializable
    @SerialName("play_sound")
    data class PlaySound(
        val soundId: String = "click"
    ) : EventCommand

    /** Displays a picture overlay ([pictureId]) with optional [title] and auto-close [durationTicks] (0 = manual close). */
    @Serializable
    @SerialName("show_picture")
    data class ShowPicture(
        val pictureId: String = "icon:1",
        val title: String = "",
        val durationTicks: Int = 0
    ) : EventCommand

    /** Moves or turns a spawned NPC matching [npcId] on the player's current map. */
    @Serializable
    @SerialName("move_npc")
    data class MoveNpc(
        val npcId: Int = 1,
        val direction: Direction = Direction.DOWN,
        val steps: Int = 1,
        val faceOnly: Boolean = false
    ) : EventCommand

    /** Prompts the player to enter text (or a number) and stores the answer into [variableId]. */
    @Serializable
    @SerialName("text_input")
    data class TextInput(
        val prompt: String = "Enter text:",
        val variableId: String = "input_text",
        val scope: VariableScope = VariableScope.PLAYER,
        val isNumeric: Boolean = false,
        val defaultValue: String = "",
        val maxLength: Int = 64
    ) : EventCommand

    /** Marks a named target for [JumpToLabel] within the current event or common event. */
    @Serializable
    @SerialName("label")
    data class Label(
        val name: String = "start"
    ) : EventCommand

    /** Jumps execution to the first matching [Label] with [name] in the active event or common event. */
    @Serializable
    @SerialName("jump_to_label")
    data class JumpToLabel(
        val name: String = "start"
    ) : EventCommand

    /** Repeats [commands] until [BreakLoop] or [Stop] runs (bounded by the event step limit). */
    @Serializable
    @SerialName("loop")
    data class Loop(
        val commands: List<EventCommand> = emptyList()
    ) : EventCommand

    /** Exits the innermost enclosing [Loop] and continues with the step after it. */
    @Serializable
    @SerialName("break_loop")
    data object BreakLoop : EventCommand

    /** Ends the event here: no later step runs, including steps after an enclosing `if` or answer. */
    @Serializable
    @SerialName("stop")
    data object Stop : EventCommand
}

/**
 * Branching choice inside a [EventCommand.ShowDialogue].
 */
@Serializable
data class DialogueChoice(
    val label: String,
    val commands: List<EventCommand> = emptyList()
)

/**
 * Single conditional page inside an [EventDefinition].
 */
@Serializable
data class EventPage(
    val pageIndex: Int = 0,
    val condition: EventPageCondition = EventPageCondition(),
    val trigger: EventTriggerType = EventTriggerType.ACTION_INTERACT,
    val spriteId: Int = 0,
    val commands: List<EventCommand> = emptyList()
)

/**
 * Authoritative Event definition attached to a map coordinate or an NPC.
 */
@Serializable
data class EventDefinition(
    val id: Int,
    val name: String,
    val mapId: Int = 0,
    val tileX: Int = 0,
    val tileY: Int = 0,
    val pages: List<EventPage> = listOf(EventPage()),
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Reusable library event editable live, invoked via [EventCommand.RunCommonEvent] or run automatically by [trigger].
 */
@Serializable
data class CommonEventDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val commands: List<EventCommand> = emptyList(),
    val trigger: CommonEventTrigger = CommonEventTrigger.MANUAL,
    val intervalTicks: Int = 200,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)


/** What an `arena_queue` event step does; each maps to an `/arena` sub-command. */
@Serializable
enum class ArenaQueueAction(val command: String) { JOIN("join"), LEAVE("leave"), RATING("rating"), STANDINGS("top") }
