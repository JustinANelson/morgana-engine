package com.morgana.protocol

import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.entity.HotbarBindingType
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.entity.TradeSessionState
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.domain.chat.ChatStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multi-channel chat categories.
 */
@Serializable
enum class ChatChannel(val prefixLabel: String, val defaultColorHex: String) {
    MAP("Map", "#E2E8F0"),
    GLOBAL("Global", "#38BDF8"),
    PARTY("Party", "#4ADE80"),
    GUILD("Guild", "#A78BFA"),
    WHISPER("Whisper", "#F472B6"),
    EMOTE("Emote", "#FDE047"),
    BROADCAST("Broadcast", "#FACC15"),
    SYSTEM("System", "#FB923C")
}

/**
 * Visual floating action text styles rendered over map tiles/entities.
 */
/**
 * What a system message is, so a client can give it a matching on-screen notice (a "toast") without guessing from its
 * words: [INFO] neutral, [GOOD] something gained or done, [WARNING] a refusal or a failed attempt, [LEVEL] a level gained.
 */
@Serializable
enum class NoticeKind { INFO, GOOD, WARNING, LEVEL }

@Serializable
enum class FloatingTextCategory(val defaultColorHex: String) {
    DAMAGE("#EF4444"),
    CRIT_DAMAGE("#F97316"),
    HEAL("#22C55E"),
    MISS_DODGE("#94A3B8"),
    STATUS("#C084FC"),
    XP_GAIN("#38BDF8"),
    LEVEL_UP("#FACC15"),
    RESOURCE("#A3E635")
}

/** What a [ClientPacket.TipsControl] asks for. */
@Serializable
enum class TipsAction {
    /** Stop showing tips. */
    OFF,

    /** Show tips again (the ones already seen stay seen). */
    ON,

    /** Forget which tips were seen and turn tips on, so all of them come round again. */
    RESET
}

/**
 * Party management actions.
 */
@Serializable
enum class PartyActionType {
    INVITE,
    ACCEPT,
    LEAVE,
    TOGGLE_XP_SHARE
}

/**
 * Guild management actions.
 */
@Serializable
enum class GuildActionType {
    CREATE,
    INVITE,
    LEAVE,
    SET_MOTD,
    /** Join the guild that invited you. */
    ACCEPT,
    /** Remove a member (`primaryArg` is their name). */
    KICK,
    /** Founder: make a member an officer (`primaryArg` is their name). */
    PROMOTE,
    /** Founder: make an officer a plain member again. */
    DEMOTE,
    /** Ask for the guild and its roster again (opening the window). */
    REFRESH
}

/**
 * What one explored map looks like to its explorer: its name and size, the maps its borders lead to (ids only: a neighbour
 * is named once it is explored too) and which chunks have been seen.
 */
@Serializable
data class ExploredMapInfo(
    val mapId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val north: Int = 0,
    val south: Int = 0,
    val east: Int = 0,
    val west: Int = 0,
    val explored: com.morgana.domain.explore.ExploredMap = com.morgana.domain.explore.ExploredMap()
)

/** Where one monster (or other NPC) spawns on a map, for the world map. The client knows what [npcId] is from its catalog. */
@Serializable
data class WorldMapSpawn(val npcId: Int, val x: Int, val y: Int)

/**
 * A picture of one explored map for the world map window: [terrain] is [rows] rows of [cols] characters
 * (see [com.morgana.domain.explore.WorldMapTerrain]), each standing for [scale] x [scale] tiles, plus the map's NPC spawn points.
 */
@Serializable
data class WorldMapDetailEntry(
    val mapId: Int,
    val scale: Int,
    val cols: Int,
    val rows: Int,
    val terrain: String,
    val spawns: List<WorldMapSpawn> = emptyList()
)

/** What a player does with their ignore list. */
@Serializable
enum class IgnoreActionType {
    REFRESH,
    ADD,
    REMOVE
}

/** One line of a guild roster. [level] is only known for online members. */
@Serializable
data class GuildMemberEntry(val name: String, val rank: String, val online: Boolean, val level: Int = 0)

/** What a player does at the player market. */
@Serializable
enum class MarketActionType {
    /** Ask for the listings again (opening the window). */
    BROWSE,
    /** List [ClientPacket.MarketAction.amount] of the bag stack in `inventorySlotIndex` for `price`. */
    LIST,
    BUY,
    /** Take back one of your own listings. */
    CANCEL
}

/** What a player does with their friends list. */
@Serializable
enum class FriendActionType {
    /** Ask for the list again (opening the window). */
    REFRESH,
    ADD,
    REMOVE
}

/** One line of the friends list: [online] friends also carry their level and the map they stand on. */
@Serializable
data class FriendEntry(val name: String, val online: Boolean, val level: Int = 0, val mapId: Int = 0)

/** What a player does with a letter in their mailbox. */
@Serializable
enum class MailActionType {
    /** Ask for the mailbox again (opening the window). */
    REFRESH,
    /** Mark a letter read. */
    READ,
    /** Move the letter's attachment into the bag. */
    CLAIM,
    /** Remove a letter (refused while it still holds an unclaimed attachment). */
    DELETE
}

/**
 * Player-to-player trade actions.
 */
@Serializable
enum class TradeActionType {
    REQUEST,
    ADD_ITEM,
    LOCK,
    CONFIRM,
    CANCEL
}

/**
 * Client modal / window triggers instructed by the server.
 */
@Serializable
enum class ServerUiWindowType {
    SHOP,
    BANK,
    GUILD_BANK,
    CRAFTING,
    DIALOGUE,
    F1_ADMIN_PANEL
}

/**
 * A window a server plugin describes with data, so a game can show its own screen (a stock market, a crop-breeding bench)
 * with the standard client. The client draws [title], the [lines] of text, a two-column table of [rows] and the [buttons]; a
 * button press goes back as [ClientPacket.PluginAction]. Sending a window with an id that is already open replaces it.
 * Needs the [ProtocolCapabilities.PLUGIN_UI] capability.
 */
@Serializable
data class PluginWindowDto(
    /** `<pluginId>:<name>`; the server refuses ids outside the plugin's own namespace. */
    val id: String,
    val title: String,
    val lines: List<String> = emptyList(),
    val rows: List<PluginWindowRow> = emptyList(),
    val buttons: List<PluginWindowButton> = emptyList(),
    /** Text fields shown above the buttons; their current text travels with every [ClientPacket.PluginAction]. */
    val inputs: List<PluginWindowInput> = emptyList()
) {
    companion object {
        const val MAX_ID = 64
        const val MAX_TITLE = 60
        const val MAX_LINES = 30
        const val MAX_LINE = 300
        const val MAX_ROWS = 60
        const val MAX_CELL = 80
        const val MAX_BUTTONS = 12
        const val MAX_LABEL = 40
        const val MAX_INPUTS = 6
        const val MAX_INPUT_TEXT = 500
    }

    /** A plain-language reason this window is too big or malformed, or null when it is acceptable. */
    fun problem(): String? = when {
        id.isEmpty() || id.length > MAX_ID -> "window id must be 1-$MAX_ID characters"
        title.length > MAX_TITLE -> "title is longer than $MAX_TITLE characters"
        lines.size > MAX_LINES || lines.any { it.length > MAX_LINE } -> "at most $MAX_LINES lines of $MAX_LINE characters"
        rows.size > MAX_ROWS || rows.any { it.label.length > MAX_CELL || it.value.length > MAX_CELL } -> "at most $MAX_ROWS rows with cells up to $MAX_CELL characters"
        buttons.size > MAX_BUTTONS || buttons.any { it.id.isEmpty() || it.id.length > MAX_ID || it.label.length > MAX_LABEL } -> "at most $MAX_BUTTONS buttons with labels up to $MAX_LABEL characters"
        buttons.map { it.id }.toSet().size != buttons.size -> "button ids must be unique"
        inputs.size > MAX_INPUTS || inputs.any { it.id.isEmpty() || it.id.length > MAX_ID || it.label.length > MAX_LABEL || it.hint.length > MAX_LABEL || it.initial.length > MAX_INPUT_TEXT || it.maxLength !in 1..MAX_INPUT_TEXT } -> "at most $MAX_INPUTS inputs, with a text limit between 1 and $MAX_INPUT_TEXT"
        inputs.map { it.id }.toSet().size != inputs.size -> "input ids must be unique"
        else -> null
    }
}

@Serializable
data class PluginWindowRow(val label: String, val value: String = "")

/** A text field in a plugin window. [hint] is the grey text shown while it is empty; the player can type at most [maxLength] characters. */
@Serializable
data class PluginWindowInput(
    val id: String,
    val label: String = "",
    val hint: String = "",
    val initial: String = "",
    val maxLength: Int = 100
)

@Serializable
data class PluginWindowButton(val id: String, val label: String, val enabled: Boolean = true)

/**
 * Public summary of another player visible on the map.
 */
@Serializable
data class PublicPlayerSnapshot(
    val characterId: String,
    val name: String,
    val level: Int,
    val spriteId: Int,
    val accessRank: Int,
    val nameplateColorHex: String,
    val guildTag: String = "",
    val isRoguePk: Boolean = false,
    /** True while the player is a ghost (dead, walking until revived or released); drawn faded. */
    val isGhost: Boolean = false,
    /** The title of the player's open stall (see `StallDefinition`), shown by their name; blank when they have none. */
    val stallTitle: String = "",
    val pkPoints: Int = 0,
    val vitals: Map<String, VitalState> = emptyMap(),
    val position: WorldPosition,
    val paperdollSpriteIds: List<Int> = emptyList(),
    /** Opt-in Paperdolling v2 slot layers (with tint & hide rules); empty when `sprites.paperdollingV2` is false. */
    val paperdollLayers: List<PaperdollLayer> = emptyList(),
    val castingSpellId: Int? = null,
    val castFinishTick: Long = 0L,
    val castTotalTicks: Int = 0,
    val castRemainingTicks: Int = 0
) {
    val hp: Int get() = vitals["hp"]?.cur ?: 0
    val maxHp: Int get() = vitals["hp"]?.max ?: 0
}

/**
 * The entities of one neighbouring map that are near the border it shares with the viewer's map.
 */
@Serializable
data class NeighborMapEntities(
    val mapId: Int,
    val npcs: List<MapNpcInstance> = emptyList(),
    val players: List<PublicPlayerSnapshot> = emptyList(),
    val groundItems: List<MapGroundItem> = emptyList()
)

/**
 * Complete catalog of world content definitions synced to clients and F1 Live Editors.
 */
@Serializable
data class WorldContentCatalogDto(
    val classes: List<ClassDefinition> = emptyList(),
    val items: List<ItemDefinition> = emptyList(),
    val npcs: List<NpcDefinition> = emptyList(),
    val spells: List<SpellDefinition> = emptyList(),
    val shops: List<ShopDefinition> = emptyList(),
    val resources: List<ResourceDefinition> = emptyList(),
    val recipes: List<RecipeDefinition> = emptyList(),
    val quests: List<QuestDefinition> = emptyList(),
    val events: List<EventDefinition> = emptyList(),
    val commonEvents: List<CommonEventDefinition> = emptyList(),
    val animations: List<AnimationDefinition> = emptyList(),
    val actionSkills: List<com.morgana.domain.content.ActionSkillDefinition> = emptyList(),
    val weaponSets: List<com.morgana.domain.content.WeaponSetDefinition> = emptyList(),
    /** One line per map in the world, for the editor's map list. */
    val maps: List<MapSummary> = emptyList()
)

/** What the editor's map list needs to know about a map without sending its cells. */
@Serializable
data class MapSummary(val id: Int, val name: String, val width: Int, val height: Int, val revision: Int)

/** Identifies one revision of a map; two refs with the same id and revision always mean identical map content. */
@Serializable
data class MapRef(val id: Int, val revision: Int)

/**
 * Wire protocol version. Bump it on every breaking packet change; the server rejects clients that do not
 * send the same number in [ClientPacket.Authenticate.protocolVersion].
 */
const val PROTOCOL_VERSION: Int = 4

/**
 * Client -> Server packets.
 */
@Serializable
sealed interface ClientPacket {
    /** Fallback representation for unrecognized client packets (forward compatibility). */
    @Serializable
    @SerialName("c_unknown")
    data class Unknown(
        val rawType: String = "",
        val rawPayload: String = ""
    ) : ClientPacket

    @Serializable
    @SerialName("c_auth")
    data class Authenticate(
        val username: String,
        val password: String,
        val registerIfNew: Boolean = true,
        val requestedClassId: Int = 1,
        val requestedSpriteId: Int = 1,
        /** Must equal [PROTOCOL_VERSION]. Defaults to 0 ("unknown") so clients that predate versioning are rejected clearly. */
        val protocolVersion: Int = 0,
        /** Protocol capabilities supported by the client for feature negotiation. */
        val capabilities: List<String> = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES
    ) : ClientPacket

    /**
     * Returns to an existing character after the connection dropped, using the token from the last
     * [ServerPacket.AuthResult.resumeToken]. The server answers with a normal `s_auth_res` (and a fresh token).
     */
    @Serializable
    @SerialName("c_resume")
    data class ResumeSession(
        val token: String,
        val protocolVersion: Int = 0,
        val capabilities: List<String> = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES
    ) : ClientPacket

    @Serializable
    @SerialName("c_ping")
    data class Ping(val clientTimestampMs: Long) : ClientPacket

    /** Requests pre-login realm metadata (such as the realm's playable classes) for the character creation dropdown. */
    @Serializable
    @SerialName("c_realm_info")
    data object RequestRealmInfo : ClientPacket

    /**
     * The client could not rebuild a snapshot's maps from its cache (see [MapStateSnapshot.mapRefs]). The server forgets
     * which maps it has sent on this connection and resends a full snapshot of the player's current map.
     */
    @Serializable
    @SerialName("c_map_resync")
    data object RequestMapResync : ClientPacket

    /** Opening the world map: asks for a picture and the enemy spawns of every map the character has explored. */
    @Serializable
    @SerialName("c_world_map")
    data object RequestWorldMap : ClientPacket

    /**
     * One movement step. [seq] numbers the steps of a connection from 1 upwards so the server can say which steps its
     * [ServerPacket.CharacterSync.ackMoveSeq] already includes; 0 (older clients) means unnumbered.
     */
    @Serializable
    @SerialName("c_move")
    data class MoveInput(
        val direction: Direction,
        val mode: MovementMode = MovementMode.GRID_LOCKED,
        val deltaPixelsX: Float = 0f,
        val deltaPixelsY: Float = 0f,
        val seq: Int = 0
    ) : ClientPacket

    @Serializable
    @SerialName("c_face")
    data class FaceDirection(val direction: Direction) : ClientPacket

    @Serializable
    @SerialName("c_attack")
    data object Attack : ClientPacket

    @Serializable
    @SerialName("c_cast")
    data class CastSpell(
        val spellId: Int,
        val targetEntityId: String? = null,
        val targetTileX: Int = 0,
        val targetTileY: Int = 0
    ) : ClientPacket

    @Serializable
    @SerialName("c_action")
    data class CustomAction(
        val actionId: String,
        val params: Map<String, String> = emptyMap()
    ) : ClientPacket

    @Serializable
    @SerialName("c_interact")
    data object InteractFacing : ClientPacket

    @Serializable
    @SerialName("c_pickup")
    data object PickupGroundItem : ClientPacket

    @Serializable
    @SerialName("c_use_slot")
    data class UseInventorySlot(val slotIndex: Int) : ClientPacket

    @Serializable
    @SerialName("c_drop_slot")
    data class DropInventorySlot(val slotIndex: Int, val amount: Int = 1) : ClientPacket

    @Serializable
    @SerialName("c_swap_slots")
    data class SwapInventorySlots(val fromSlot: Int, val toSlot: Int) : ClientPacket

    /** Merges two pieces of gear of the same slot to increase the target's +N level. An older server ignores it. */
    @Serializable
    @SerialName("c_merge_gear")
    data class MergeGear(val targetSlotIndex: Int, val materialSlotIndex: Int) : ClientPacket

    /** Merges stacks and packs the bag by item type and name. An older server ignores it. */
    @Serializable
    @SerialName("c_sort_inventory")
    data object SortInventory : ClientPacket

    @Serializable
    @SerialName("c_unequip")
    data class UnequipSlot(val slot: EquipmentSlot) : ClientPacket

    @Serializable
    @SerialName("c_alloc_stat")
    data class AllocateStatPoint(val attributeId: String) : ClientPacket

    @Serializable
    @SerialName("c_set_pk_mode")
    data class SetPkMode(val mode: com.morgana.domain.entity.PkMode) : ClientPacket

    @Serializable
    @SerialName("c_jump_to")
    data class JumpTo(val targetX: Int, val targetY: Int) : ClientPacket

    @Serializable
    @SerialName("c_hotbar")
    data class SetHotbarSlot(
        val hotbarIndex: Int,
        val type: HotbarBindingType,
        val targetId: Int
    ) : ClientPacket

    @Serializable
    @SerialName("c_chat")
    data class SendChat(
        val channel: ChatChannel = ChatChannel.MAP,
        val message: String,
        val targetPlayerName: String = ""
    ) : ClientPacket

    @Serializable
    @SerialName("c_shop_buy")
    data class ShopBuy(val shopId: Int, val entryIndex: Int, val quantity: Int = 1) : ClientPacket

    @Serializable
    @SerialName("c_shop_sell")
    data class ShopSell(
        val shopId: Int,
        val inventorySlotIndex: Int = -1,
        val quantity: Int = 1,
        val slotIndices: List<Int> = emptyList()
    ) : ClientPacket

    /** Buy back entry [entryIndex] of the list the server last sent in `s_shop_buyback` for shop [shopId], at the price the shop paid. */
    @Serializable
    @SerialName("c_shop_buyback")
    data class ShopBuyback(val shopId: Int, val entryIndex: Int) : ClientPacket

    @Serializable
    @SerialName("c_bank_deposit")
    data class BankDeposit(val inventorySlotIndex: Int, val amount: Int = 1) : ClientPacket

    @Serializable
    @SerialName("c_bank_withdraw")
    data class BankWithdraw(val bankSlotIndex: Int, val amount: Int = 1) : ClientPacket

    /** Moves a bag stack into the guild vault; only at the guild's Quartermaster. */
    @Serializable
    @SerialName("c_guild_bank_deposit")
    data class GuildBankDeposit(val inventorySlotIndex: Int, val amount: Int = 1, val tab: Int = -1) : ClientPacket

    /** The founder buys the next vault upgrade (more slots) with Marks held in the vault. */
    @Serializable
    @SerialName("c_guild_bank_upgrade")
    data object GuildBankUpgrade : ClientPacket

    /** Moves a vault stack into the bag; needs the rank the founder allows. */
    @Serializable
    @SerialName("c_guild_bank_withdraw")
    data class GuildBankWithdraw(val bankSlotIndex: Int, val amount: Int = 1) : ClientPacket

    /** The founder sets the lowest rank that may withdraw (`Founder`, `Officer` or `Member`). */
    @Serializable
    @SerialName("c_guild_bank_access")
    data class GuildBankSetAccess(val rank: String) : ClientPacket

    @Serializable
    @SerialName("c_craft")
    data class CraftRecipe(val recipeId: Int) : ClientPacket

    @Serializable
    @SerialName("c_dialogue_choice")
    data class SelectDialogueChoice(val eventId: Int, val choiceIndex: Int) : ClientPacket

    @Serializable
    @SerialName("c_text_input")
    data class SubmitTextInput(val eventId: Int, val value: String = "", val cancelled: Boolean = false) : ClientPacket

    @Serializable
    @SerialName("c_party")
    data class PartyAction(val action: PartyActionType, val targetPlayerName: String = "") : ClientPacket

    @Serializable
    @SerialName("c_guild")
    data class GuildAction(
        val action: GuildActionType,
        val primaryArg: String = "",
        val secondaryArg: String = ""
    ) : ClientPacket

    /** Sends a letter to a character (online or not), optionally with [amount] of the stack in [inventorySlotIndex] (-1 = nothing attached). */
    @Serializable
    @SerialName("c_mail_send")
    data class SendMail(
        val recipientName: String,
        val subject: String,
        val body: String = "",
        val inventorySlotIndex: Int = -1,
        val amount: Int = 1
    ) : ClientPacket

    @Serializable
    @SerialName("c_market")
    data class MarketAction(
        val action: MarketActionType,
        val listingId: String = "",
        val inventorySlotIndex: Int = -1,
        val amount: Int = 1,
        val price: Int = 0
    ) : ClientPacket

    @Serializable
    @SerialName("c_ignore")
    data class IgnoreAction(val action: IgnoreActionType, val name: String = "") : ClientPacket

    @Serializable
    @SerialName("c_friend")
    data class FriendAction(val action: FriendActionType, val name: String = "") : ClientPacket

    @Serializable
    @SerialName("c_mail")
    data class MailAction(val action: MailActionType, val mailId: String = "") : ClientPacket

    @Serializable
    @SerialName("c_trade")
    data class TradeAction(
        val action: TradeActionType,
        val targetPlayerName: String = "",
        val inventorySlotIndex: Int = 0,
        val amount: Int = 1
    ) : ClientPacket

    @Serializable
    @SerialName("c_admin_cmd")
    data class AdminSlashCommand(val commandLine: String) : ClientPacket

    // --- Live F1 Admin Editor Packets ---
    @Serializable
    @SerialName("c_admin_req_catalog")
    data object RequestAdminCatalog : ClientPacket

    @Serializable
    @SerialName("c_admin_save_map")
    data class SaveMapDefinition(val map: @Serializable(with = PackedMapSerializer::class) MapDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_item")
    data class SaveItemDefinition(val item: ItemDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_npc")
    data class SaveNpcDefinition(val npc: NpcDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_spell")
    data class SaveSpellDefinition(val spell: SpellDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_shop")
    data class SaveShopDefinition(val shop: ShopDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_resource")
    data class SaveResourceDefinition(val resource: ResourceDefinition) : ClientPacket

    /** Saves the game's rules (`game.json`) from the F1 Game rules panel; needs server maintenance rights and applies after a restart. */
    @Serializable
    @SerialName("c_admin_save_game")
    data class SaveGameDefinition(val game: com.morgana.domain.game.GameDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_action_skill")
    data class SaveActionSkillDefinition(val skill: com.morgana.domain.content.ActionSkillDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_weapon_set")
    data class SaveWeaponSetDefinition(val weaponSet: com.morgana.domain.content.WeaponSetDefinition) : ClientPacket

    /** Switches the server's optional systems: [disabled] are the `configKey`s to turn off, every other built-in system is turned on. Needs server maintenance rights. */
    @Serializable
    @SerialName("c_admin_set_modules")
    data class SetModules(val disabled: List<String>) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_class")
    data class SaveClassDefinition(val classDefinition: com.morgana.domain.content.ClassDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_recipe")
    data class SaveRecipeDefinition(val recipe: RecipeDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_quest")
    data class SaveQuestDefinition(val quest: QuestDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_event")
    data class SaveEventDefinition(val event: EventDefinition) : ClientPacket

    /** Run unsaved event steps once for the editing admin, without changing the saved event catalog. */
    @Serializable
    @SerialName("c_admin_test_event")
    data class TestEventCommands(val eventId: Int, val commands: List<EventCommand>) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_common_event")
    data class SaveCommonEventDefinition(val commonEvent: CommonEventDefinition) : ClientPacket

    @Serializable
    @SerialName("c_admin_save_animation")
    data class SaveAnimationDefinition(val animation: AnimationDefinition) : ClientPacket

    /** Uploads a PNG into the realm's `assets/` folder from the F1 Art panel, validated with `GameAssets` rules. */
    @Serializable
    @SerialName("c_admin_import_art")
    data class ImportGameArt(
        val kind: com.morgana.protocol.realms.AssetKind,
        val assetId: String,
        val pngBase64: String
    ) : ClientPacket

    /** The player turns new-player tips off or on, or has them all shown again (needs [ProtocolCapabilities.TIPS]). */
    @Serializable
    @SerialName("c_tips")
    data class TipsControl(val action: TipsAction) : ClientPacket

    /** A free-form message for a server plugin (needs [ProtocolCapabilities.PLUGIN_UI]); [channel] is `<pluginId>:<name>`. */
    @Serializable
    @SerialName("c_plugin_msg")
    data class PluginMessage(val channel: String, val payload: Map<String, String> = emptyMap()) : ClientPacket

    /** The player pressed a button of a plugin window; [values] is the text of the window's inputs (input id to text). */
    @Serializable
    @SerialName("c_plugin_action")
    data class PluginAction(val windowId: String, val buttonId: String, val values: Map<String, String> = emptyMap()) : ClientPacket

    /** The player closed a plugin window. */
    @Serializable
    @SerialName("c_plugin_window_closed")
    data class PluginWindowClosed(val windowId: String) : ClientPacket

    /** After [ServerPacket.CharacterList]: play this character of the signed-in account. */
    @Serializable
    @SerialName("c_choose_character")
    data class ChooseCharacter(val characterId: String) : ClientPacket

    /** After [ServerPacket.CharacterList]: make a new character (see [CharacterNames] for the name rules) and play it. */
    @Serializable
    @SerialName("c_create_character")
    data class CreateCharacter(val name: String, val classId: Int = 1, val spriteId: Int = 0) : ClientPacket

    /** After [ServerPacket.CharacterList]: delete a character for good. [confirmName] must be the character's name. */
    @Serializable
    @SerialName("c_delete_character")
    data class DeleteCharacter(val characterId: String, val confirmName: String) : ClientPacket
}

/**
 * Server -> Client packets.
 */
@Serializable
sealed interface ServerPacket {
    /** Fallback representation for unrecognized server packets (forward compatibility). */
    @Serializable
    @SerialName("s_unknown")
    data class Unknown(
        val rawType: String = "",
        val rawPayload: String = ""
    ) : ServerPacket

    @Serializable
    @SerialName("s_auth_res")
    data class AuthResult(
        val success: Boolean,
        val message: String,
        val character: CharacterState? = null,
        val catalog: WorldContentCatalogDto? = null,
        val gameDefinition: GameDefinition? = null,
        val serverProtocolVersion: Int = PROTOCOL_VERSION,
        /** `configKey`s of optional modules this server has switched off (e.g. "trading"); clients hide those features. */
        val disabledModules: List<String> = emptyList(),
        val assetManifest: com.morgana.protocol.realms.AssetManifest? = null,
        val assetBaseUrl: String? = null,
        /** Single-use token for [ClientPacket.ResumeSession]; null when the server did not issue one. */
        val resumeToken: String? = null,
        /** The server's simulation tick at login; the client derives the game clock from it (see `WorldClock`). */
        val serverTick: Long = 0L,
        /** Protocol capabilities enabled on this server. */
        val capabilities: List<String> = emptyList()
    ) : ServerPacket

    /**
     * The server is about to end this session. When [resumable] is false (e.g. the account signed in elsewhere) the
     * client must not reconnect on its own, or the two clients would keep evicting each other.
     */
    @Serializable
    @SerialName("s_session_end")
    data class SessionEnded(val reason: String, val resumable: Boolean = false) : ServerPacket

    @Serializable
    @SerialName("s_pong")
    data class Pong(val clientTimestampMs: Long, val serverTick: Long) : ServerPacket

    /** Pre-login metadata about the connected realm, including its playable character classes. */
    @Serializable
    @SerialName("s_realm_info")
    data class RealmInfo(
        val gameId: String,
        val gameName: String,
        val classes: List<com.morgana.protocol.realms.RealmClassSummary> = emptyList()
    ) : ServerPacket

    @Serializable
    @SerialName("s_map_snapshot")
    data class MapStateSnapshot(
        /**
         * The map the player is on. Null only when [mapRefs] is used and this connection already holds the map at that
         * revision; clients without the `map_cache` capability always receive it.
         */
        val activeMap: @Serializable(with = PackedMapSerializer::class) MapDefinition? = null,
        /** Neighbouring maps, or (with [mapRefs]) only those this connection does not hold yet. */
        val neighborMaps: Map<Int, @Serializable(with = PackedMapSerializer::class) MapDefinition> = emptyMap(),
        /**
         * With the `map_cache` capability: the id and revision of every map this snapshot refers to, the active map first
         * and then its neighbours, so the client can rebuild them from maps it already holds. Empty for other clients.
         */
        val mapRefs: List<MapRef> = emptyList(),
        /**
         * In a seamless world (`worldLayout`): where each neighbouring map's top left tile sits from the active map's top left tile, in
         * tiles. The client draws the neighbours there and places what the server shows across a border; empty for the older N/S/E/W
         * neighbour links, which keep their fixed arrangement.
         */
        val neighborOffsets: Map<Int, com.morgana.domain.map.TileOffset> = emptyMap(),
        val players: List<PublicPlayerSnapshot> = emptyList(),
        val npcs: List<MapNpcInstance> = emptyList(),
        val groundItems: List<MapGroundItem> = emptyList(),
        val projectiles: List<MapProjectile> = emptyList(),
        val resources: List<MapResourceState> = emptyList(),
        /** The world objects (walls, chests, totems) on the map; sent only to clients with the `world_objects` capability. */
        val worldObjects: List<com.morgana.domain.entity.WorldObject> = emptyList(),
        /** The server's simulation tick when this was sent, to keep the client's game clock in step. */
        val serverTick: Long = 0L
    ) : ServerPacket

    /**
     * Entities on the neighbouring maps close to the shared border, in their own map's tile coordinates. Lets the client
     * draw the monsters, players and loot just across the edge ("soft seams"). Sent only to players near a border, and
     * only when something changed; an empty list clears the previous view.
     */
    @Serializable
    @SerialName("s_seam_view")
    data class SeamView(val maps: List<NeighborMapEntities> = emptyList()) : ServerPacket

    /**
     * The owner's full character. [ackMoveSeq] is the highest [ClientPacket.MoveInput.seq] the server had dealt with
     * (applied, or dropped) when it sent this, so a predicting client can replay only the steps after it; 0 = none yet.
     */
    @Serializable
    @SerialName("s_char_sync")
    data class CharacterSync(val character: CharacterState, val ackMoveSeq: Int = 0) : ServerPacket

    @Serializable
    @SerialName("s_player_moved")
    data class PlayerUpdated(val player: PublicPlayerSnapshot) : ServerPacket

    @Serializable
    @SerialName("s_player_left")
    data class PlayerLeftMap(val characterId: String, val mapId: Int) : ServerPacket

    @Serializable
    @SerialName("s_npc_sync")
    data class NpcUpdated(val npc: MapNpcInstance) : ServerPacket

    /**
     * A monster that only moved since its last [NpcUpdated]: the client copies these fields onto the monster it already has (and ignores the
     * packet for one it does not know; the server sends the whole monster to anyone who has not seen it). About a third of the size.
     */
    @Serializable
    @SerialName("s_npc_pos")
    data class NpcMoved(
        @SerialName("i") val instanceId: String,
        @SerialName("x") val tileX: Int,
        @SerialName("y") val tileY: Int,
        @SerialName("d") val direction: Direction,
        @SerialName("t") val lastMoveTick: Long = 0L,
        @SerialName("s") val subPixel: Boolean = false,
        @SerialName("px") val pixelX: Float = 0f,
        @SerialName("py") val pixelY: Float = 0f,
        @SerialName("wx") val wanderX: Int = -1,
        @SerialName("wy") val wanderY: Int = -1,
        /** 0 for a monster on the viewer's own map; otherwise the map it is on (a monster across a border in a seamless world). */
        @SerialName("m") val mapId: Int = 0
    ) : ServerPacket

    /** A player who only moved since their last [PlayerUpdated]: the client sets [position] on the player it already has. */
    @Serializable
    @SerialName("s_player_pos")
    data class PlayerMoved(
        @SerialName("c") val characterId: String,
        @SerialName("p") val position: com.morgana.domain.spatial.WorldPosition,
        /** 0 for a player on the viewer's own map; otherwise the map they are on (a player across a border in a seamless world). */
        @SerialName("m") val mapId: Int = 0
    ) : ServerPacket

    /** Every [NpcMoved] and [PlayerMoved] one tick produced for what this player can see, in a single packet. */
    @Serializable
    @SerialName("s_moves")
    data class EntityMoves(@SerialName("n") val npcs: List<NpcMoved> = emptyList(), @SerialName("p") val players: List<PlayerMoved> = emptyList()) : ServerPacket

    @Serializable
    @SerialName("s_ground_items")
    data class GroundItemsUpdated(val mapId: Int, val items: List<MapGroundItem>) : ServerPacket

    /** The health orbs on [mapId] (the whole list; an empty list clears them). */
    @Serializable
    @SerialName("s_orbs")
    data class HealthOrbsUpdated(val mapId: Int, val orbs: List<com.morgana.domain.entity.HealthOrb>) : ServerPacket

    /**
     * What changed in the projectiles on [mapId] since the last [ProjectilesUpdated] or this packet: [upserted] shots are new or changed (replace by
     * `projectileId`), [removedIds] are gone. A free-flight shot that merely flew on is not listed; the client flies it on from its last position.
     */
    @Serializable
    @SerialName("s_proj_delta")
    data class ProjectilesDelta(val mapId: Int, val upserted: List<com.morgana.domain.entity.MapProjectile> = emptyList(), val removedIds: List<String> = emptyList()) : ServerPacket

    @Serializable
    @SerialName("s_projectiles")
    data class ProjectilesUpdated(val mapId: Int, val projectiles: List<MapProjectile>) : ServerPacket

    /**
     * World objects placed or changed ([upserted], the whole object each time) and taken away ([removedIds]) on [mapId]. Sent to clients
     * with the `world_objects` capability, to the players on the map.
     */
    @Serializable
    @SerialName("s_world_objects")
    data class WorldObjectsUpdated(
        val mapId: Int,
        val upserted: List<com.morgana.domain.entity.WorldObject> = emptyList(),
        val removedIds: List<String> = emptyList()
    ) : ServerPacket

    @Serializable
    @SerialName("s_resource_sync")
    data class ResourceNodeUpdated(val resource: MapResourceState) : ServerPacket

    @Serializable
    @SerialName("s_chat")
    data class ChatBroadcast(
        val channel: ChatChannel,
        val senderEntityId: String = "",
        val senderName: String,
        val message: String,
        val showOverheadBubble: Boolean = true,
        val colorHex: String = channel.defaultColorHex,
        /**
         * How role-play speech should be shown (action, out of character, shout, ...). Only sent as something other than
         * [ChatStyle.NORMAL] to clients with [ProtocolCapabilities.RP_CHAT]; others get the style folded into the text.
         */
        val style: ChatStyle = ChatStyle.NORMAL,
        /** Set on system messages that deserve an on-screen notice; null for everything else (older servers never set it). */
        val notice: NoticeKind? = null
    ) : ServerPacket

    @Serializable
    @SerialName("s_float_text")
    data class FloatingText(
        val mapId: Int,
        val tileX: Int,
        val tileY: Int,
        val text: String,
        val category: FloatingTextCategory = FloatingTextCategory.DAMAGE,
        val colorHex: String = category.defaultColorHex
    ) : ServerPacket

    /** The local player's momentum meter (0..99) and the ticks of empowerment left (0 when not empowered). */
    @Serializable
    @SerialName("s_momentum")
    data class Momentum(val momentum: Int, val empoweredTicks: Int) : ServerPacket

    /** A built-in skill visual (arrow rain, dodge dust, spin whirl) the client draws with no art; positions are world pixels. */
    @Serializable
    @SerialName("s_skill_fx")
    data class SkillEffect(
        val mapId: Int,
        val effect: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val radius: Float = 0f,
        val delayTicks: Int = 0,
        val spanTicks: Int = 1,
        val sourceId: String = ""
    ) : ServerPacket

    @Serializable
    @SerialName("s_play_anim")
    data class TriggerAnimation(
        val mapId: Int,
        val tileX: Int,
        val tileY: Int,
        val animationId: Int,
        val soundId: String = "",
        /** Who cast the spell this impact shows (character id or NPC instance id); "" when it is not a spell's. Lets clients draw the shot. */
        val casterEntityId: String = "",
        /** The spell this impact shows; 0 when it is not a spell's. */
        val spellId: Int = 0
    ) : ServerPacket

    @Serializable
    @SerialName("s_open_ui")
    data class OpenUiWindow(
        val windowType: ServerUiWindowType,
        val shopId: Int = 0,
        val stationType: CraftingStationType = CraftingStationType.ANYWHERE,
        val eventId: Int = 0,
        val speakerName: String = "",
        val dialogueText: String = "",
        val choiceLabels: List<String> = emptyList()
    ) : ServerPacket

    /**
     * The password was right; now pick a character. Sent instead of [AuthResult] to clients that advertised
     * [ProtocolCapabilities.CHARACTER_SELECT]. The client answers with [ClientPacket.ChooseCharacter],
     * [ClientPacket.CreateCharacter] or [ClientPacket.DeleteCharacter]; a refused request comes back as this packet again
     * with the reason in [message]. [classes] are the classes a new character can have.
     */
    @Serializable
    @SerialName("s_character_list")
    data class CharacterList(
        val characters: List<CharacterSlot>,
        val maxCharacters: Int,
        val classes: List<com.morgana.protocol.realms.RealmClassSummary> = emptyList(),
        val message: String = ""
    ) : ServerPacket

    /**
     * A new-player tip to show once (sent only to clients that advertised [ProtocolCapabilities.TIPS]). [id] names the tip,
     * [title] and [text] may hold `{key:ACTION}` and `{abilities}` placeholders that the client fills in.
     */
    @Serializable
    @SerialName("s_tip")
    data class Tip(val id: String, val title: String, val text: String) : ServerPacket

    /** A free-form message from a server plugin (sent only to clients that advertised [ProtocolCapabilities.PLUGIN_UI]). */
    @Serializable
    @SerialName("s_plugin_msg")
    data class PluginMessage(val channel: String, val payload: Map<String, String> = emptyMap()) : ServerPacket

    /** Open (or replace) a plugin-described window. */
    @Serializable
    @SerialName("s_plugin_window")
    data class PluginWindow(val window: PluginWindowDto) : ServerPacket

    /** Close a plugin window. */
    @Serializable
    @SerialName("s_plugin_window_close")
    data class PluginWindowClose(val windowId: String) : ServerPacket

    @Serializable
    @SerialName("s_play_sound")
    data class PlaySound(val soundId: String) : ServerPacket

    /**
     * A spell the receiving player just cast is cooling down for [durationTicks] (20 per second) from now. Lets the client
     * draw an exact quickbar sweep instead of estimating it. New packet type: older clients decode it as `Unknown` and ignore it.
     */
    @Serializable
    @SerialName("s_spell_cooldown")
    data class SpellCooldown(val spellId: Int, val durationTicks: Int) : ServerPacket

    /**
     * An action skill the receiving player just executed (or attempted while cooling down) is cooling down for
     * [durationTicks] (20 per second) from now, keyed by [skillId] rather than input token so empowered upgrades
     * never corrupt the base skill's timer.
     */
    @Serializable
    @SerialName("s_skill_cooldown")
    data class ActionSkillCooldown(val skillId: Int, val durationTicks: Int) : ServerPacket

    @Serializable
    @SerialName("s_show_picture")
    data class ShowPicture(
        val pictureId: String,
        val title: String = "",
        val durationTicks: Int = 0
    ) : ServerPacket

    @Serializable
    @SerialName("s_text_input")
    data class TextInputPrompt(
        val eventId: Int,
        val prompt: String,
        val isNumeric: Boolean = false,
        val defaultValue: String = "",
        val maxLength: Int = 64
    ) : ServerPacket

    @Serializable
    @SerialName("s_trade_sync")
    data class TradeUpdated(val session: TradeSessionState?) : ServerPacket

    @Serializable
    @SerialName("s_party_sync")
    data class PartyUpdated(val party: PartyState?) : ServerPacket

    @Serializable
    @SerialName("s_guild_sync")
    data class GuildUpdated(val guild: GuildDocument?) : ServerPacket

    /** A boss's danger zone: the tiles within [radius] of ([centerX], [centerY]) are marked for [windUpTicks] ticks, then hit. */
    @Serializable
    @SerialName("s_boss_telegraph")
    data class BossTelegraph(val mapId: Int, val centerX: Int, val centerY: Int, val radius: Int, val windUpTicks: Int, val label: String = "") : ServerPacket

    /** The goods of a stall the player looked at, or of their own stall, for the Stall window. */
    @Serializable
    @SerialName("s_stall_view")
    data class StallViewed(val view: com.morgana.domain.game.StallView) : ServerPacket

    /** Directions for quest objectives that lie on other maps, valid while the player stays on the map they name. */
    @Serializable
    @SerialName("s_quest_guides")
    data class QuestGuides(val guides: List<com.morgana.domain.event.QuestGuide>) : ServerPacket

    /** Every map the character has explored, sent at login (when the `exploration` module is on). */
    @Serializable
    @SerialName("s_explored")
    data class Exploration(val maps: List<ExploredMapInfo>) : ServerPacket

    /** Answer to [ClientPacket.RequestWorldMap]: terrain and spawns of the explored maps only (empty when exploration is off). */
    @Serializable
    @SerialName("s_world_map")
    data class WorldMapDetail(val maps: List<WorldMapDetailEntry>) : ServerPacket

    /** One map's exploration changed (the character saw new ground, or entered a new map). */
    @Serializable
    @SerialName("s_explored_map")
    data class ExplorationUpdate(val map: ExploredMapInfo) : ServerPacket

    /** The guild's members with ranks, officers first then online members. Empty when the player has no guild. */
    @Serializable
    @SerialName("s_guild_roster")
    data class GuildRoster(val members: List<GuildMemberEntry>) : ServerPacket

    /**
     * What the player has sold to shop [shopId] and can still buy back, oldest first (at most `SHOP_BUYBACK_LIMIT`). Sent when a
     * shop opens and after every sale or buy-back; an empty list clears it. New packet type: older clients ignore it.
     */
    @Serializable
    @SerialName("s_shop_buyback")
    data class ShopBuyback(val shopId: Int, val entries: List<com.morgana.domain.sim.ShopBuybackEntry>) : ServerPacket

    /** The names the player ignores, sorted. */
    @Serializable
    @SerialName("s_ignore")
    data class IgnoreList(val names: List<String>) : ServerPacket

    /** The market's open listings, newest first (at most `MarketRules.MAX_VISIBLE`). Sent on request and after every change the player makes. */
    @Serializable
    @SerialName("s_market")
    data class MarketListings(val listings: List<MarketListingDocument>) : ServerPacket

    /** The player's friends list, online friends first. Sent on login, on request and whenever a friend's state or the list changes. */
    @Serializable
    @SerialName("s_friends")
    data class FriendList(val friends: List<FriendEntry>) : ServerPacket

    /** The player's whole mailbox, newest first. Sent on login (when there is mail), when it is requested and after every change. */
    @Serializable
    @SerialName("s_mail_inbox")
    data class MailInbox(val messages: List<MailMessageDocument>) : ServerPacket

    // --- Live F1 Admin Sync Packets ---
    @Serializable
    @SerialName("s_admin_map_updated")
    data class MapDefinitionUpdated(val map: @Serializable(with = PackedMapSerializer::class) MapDefinition) : ServerPacket

    @Serializable
    @SerialName("s_admin_catalog_updated")
    data class ContentCatalogUpdated(
        val catalog: WorldContentCatalogDto,
        val summaryMessage: String = "World content updated."
    ) : ServerPacket

    @Serializable
    @SerialName("s_admin_art_updated")
    data class GameArtUpdated(
        val entry: com.morgana.protocol.realms.AssetManifestEntry,
        val manifest: com.morgana.protocol.realms.AssetManifest,
        val pngBase64: String = ""
    ) : ServerPacket
}


/** One entry of [ServerPacket.CharacterList]. */
@Serializable
data class CharacterSlot(
    val id: String,
    val name: String,
    val classId: Int,
    val className: String,
    val level: Int,
    val spriteId: Int = 1
)
