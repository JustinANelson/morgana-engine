package com.morgana.domain.sim

import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.entity.TradeSessionState
import com.morgana.domain.map.MapDefinition

/**
 * Pure domain events emitted by [WorldSimulation] operations and 20 TPS ticks.
 */
sealed interface WorldSimulationEvent {
    data class CharacterUpdated(val character: CharacterState) : WorldSimulationEvent

    data class PlayerMoved(
        val character: CharacterState,
        val previousMapId: Int,
        val currentMapId: Int
    ) : WorldSimulationEvent

    data class NpcUpdated(val npc: MapNpcInstance) : WorldSimulationEvent

    data class GroundItemsChanged(
        val mapId: Int,
        val items: List<MapGroundItem>
    ) : WorldSimulationEvent

    data class ProjectilesChanged(
        val mapId: Int,
        val projectiles: List<MapProjectile>
    ) : WorldSimulationEvent

    data class ResourceNodeChanged(
        val resource: MapResourceState
    ) : WorldSimulationEvent

    data class FloatingTextEmitted(
        val mapId: Int,
        val tileX: Int,
        val tileY: Int,
        val text: String,
        val category: String,
        val colorHex: String,
        val targetCharacterId: String? = null
    ) : WorldSimulationEvent

    data class AnimationTriggered(
        val mapId: Int,
        val tileX: Int,
        val tileY: Int,
        val animationId: Int,
        val soundId: String = "",
        /** Who cast the spell this animation shows (a character id or NPC instance id), "" when it is not a spell's. */
        val casterEntityId: String = "",
        /** The spell this animation shows, 0 when it is not a spell's. */
        val spellId: Int = 0
    ) : WorldSimulationEvent

    data class SoundPlayed(
        val characterId: String,
        val mapId: Int,
        val soundId: String
    ) : WorldSimulationEvent

    data class PictureShown(
        val characterId: String,
        val pictureId: String,
        val title: String = "",
        val durationTicks: Int = 0
    ) : WorldSimulationEvent

    data class TextInputRequested(
        val characterId: String,
        val eventId: Int,
        val prompt: String,
        val isNumeric: Boolean = false,
        val defaultValue: String = "",
        val maxLength: Int = 64
    ) : WorldSimulationEvent

    /** A boss has marked a danger zone on the floor (see `BossEncounter`); clients draw it until [windUpTicks] have passed. */
    data class BossTelegraph(
        val mapId: Int,
        val centerX: Int,
        val centerY: Int,
        val radius: Int,
        val windUpTicks: Int,
        val label: String = ""
    ) : WorldSimulationEvent

    data class ChatEmitted(
        val channel: String,
        val mapId: Int,
        val senderId: String,
        val senderName: String,
        val targetCharacterId: String? = null,
        val message: String,
        val showOverheadBubble: Boolean = true,
        val colorHex: String = "#E2E8F0",
        /** A `NoticeKind` name (INFO, GOOD, WARNING, LEVEL) when the message deserves an on-screen notice; null otherwise. */
        val notice: String? = null
    ) : WorldSimulationEvent

    /** The scheduler raised a warning or started an occurrence (see [ScheduleTrigger]); for the server and systems, not sent to clients. */
    data class ScheduleTriggered(val trigger: ScheduleTrigger) : WorldSimulationEvent

    data class OpenUiRequested(
        val characterId: String,
        val windowType: String,
        val shopId: Int = 0,
        val stationType: CraftingStationType = CraftingStationType.ANYWHERE,
        val eventId: Int = 0,
        val speakerName: String = "",
        val dialogueText: String = "",
        val choiceLabels: List<String> = emptyList()
    ) : WorldSimulationEvent

    /** The goods [characterId] has sold to shop [shopId] and may still buy back (newest last); sent as `s_shop_buyback`. Empty clears the list. */
    data class ShopBuybackChanged(
        val characterId: String,
        val shopId: Int,
        val entries: List<ShopBuybackEntry>
    ) : WorldSimulationEvent

    data class TradeUpdated(
        val participantIds: List<String>,
        val session: TradeSessionState?
    ) : WorldSimulationEvent

    /** Show [viewerId] the goods of a stall (their own, or one they looked at); the server sends it as `s_stall_view`. */
    data class StallViewed(val viewerId: String, val view: com.morgana.domain.game.StallView) : WorldSimulationEvent

    data class PartyUpdated(
        val memberIds: List<String>,
        val party: PartyState?
    ) : WorldSimulationEvent

    /** A Guild War ended and these guilds changed hands (the new holder and the old one); the server saves them. */
    data class GuildWarResult(val guilds: List<GuildDocument>) : WorldSimulationEvent

    data class GuildUpdated(
        val memberIds: List<String>,
        val guild: GuildDocument?
    ) : WorldSimulationEvent

    data class PlayerModerated(
        val targetCharacterId: String,
        val action: String,
        val reason: String
    ) : WorldSimulationEvent

    /** A page event ran a `kotlin_hook` command; the server forwards it to registered plugins. */
    data class CustomHookRequested(
        val characterId: String,
        val eventId: Int,
        val hookId: String,
        val payload: Map<String, String>
    ) : WorldSimulationEvent

    /** A player equipped or consumed an item (or learned a spell from a scroll). */
    data class ItemUsed(val characterId: String, val itemId: Int, val slotIndex: Int) : WorldSimulationEvent

    /** A player successfully began casting a spell (resources spent, cooldown started). */
    data class SpellCast(val characterId: String, val spellId: Int, val mapId: Int) : WorldSimulationEvent

    /** A player's [spellId] is now cooling down for [durationTicks] (the client draws the quickbar sweep from this). */
    data class SpellCooldownStarted(val characterId: String, val spellId: Int, val durationTicks: Int) : WorldSimulationEvent

    /** A player began channeling / casting a spell with a non-zero castTimeTicks. */
    data class SpellCastStarted(val characterId: String, val spellId: Int, val mapId: Int, val castTimeTicks: Int) : WorldSimulationEvent

    /** A player's spell cast was interrupted (e.g. by movement). */
    data class SpellCastInterrupted(val characterId: String, val spellId: Int, val mapId: Int, val reason: String) : WorldSimulationEvent

    /** An NPC instance died; [killerCharacterId] is null when no player landed the killing blow. */
    data class NpcKilled(val npcInstanceId: String, val npcId: Int, val mapId: Int, val killerCharacterId: String?) : WorldSimulationEvent

    /** A player died and was respawned; emitted before the respawn [PlayerMoved]. */
    data class PlayerDied(val characterId: String, val mapId: Int) : WorldSimulationEvent

    data class MapHotSwapped(val map: MapDefinition) : WorldSimulationEvent

    /** An instance of [templateMapId] stood empty long enough and was closed; map [mapId] no longer exists. */
    /** A guild's vault, withdrawal rank or log changed; the server saves the guild and tells the members nearby. */
    data class GuildBankChanged(val guildId: String) : WorldSimulationEvent

    data class InstanceClosed(val mapId: Int, val templateMapId: Int) : WorldSimulationEvent

    data class CharacterPermadeath(
        val characterId: String,
        val characterName: String,
        val mode: com.morgana.domain.game.PermadeathMode
    ) : WorldSimulationEvent

    data class RunEnded(
        val runId: String,
        val ownerKey: String,
        val memberIds: Set<String>,
        val outcome: String,
        val seed: Long,
        val depth: Int,
        val kills: Int,
        val deaths: Int,
        val durationTicks: Long
    ) : WorldSimulationEvent

    data class ContentCatalogHotSwapped(val summary: String) : WorldSimulationEvent
}

