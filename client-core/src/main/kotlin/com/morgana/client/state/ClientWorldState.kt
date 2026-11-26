package com.morgana.client.state

import com.morgana.client.assets.AnimationStripLayout
import com.morgana.client.editor.MapEditSession
import com.morgana.domain.combat.CombatModule
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.game.ClockTime
import com.morgana.domain.game.SpriteState
import com.morgana.domain.game.UiProfile
import com.morgana.domain.game.WeatherNow
import com.morgana.domain.game.WorldClock
import com.morgana.domain.game.WorldWeather
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.entity.TradeSessionState
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.BlankGame
import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.TileCoord
import com.morgana.client.ui.toToastKind
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PluginWindowDto
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.NeighborMapEntities
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import kotlin.math.abs
import kotlin.math.hypot

data class ActiveFloatingText(
    val mapId: Int,
    val worldX: Float,
    var worldY: Float,
    val text: String,
    val colorHex: String,
    var remainingSeconds: Float = 1.35f,
    val crit: Boolean = false,
    var ageSeconds: Float = 0f
)

data class ActiveChatBubble(
    val senderEntityId: String,
    val senderName: String,
    val text: String,
    var remainingSeconds: Float = 4.5f
)

data class ActiveSpriteAnimation(
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val animationId: Int,
    val spriteSheetId: Int = animationId,
    val frameCount: Int = AnimationStripLayout.DEFAULT_FRAME_COUNT,
    val frameDurationMs: Int = AnimationStripLayout.DEFAULT_FRAME_DURATION_MS,
    val renderAboveEntities: Boolean = true,
    var elapsedSeconds: Float = 0f,
    val totalDurationSeconds: Float = AnimationStripLayout.totalDurationSeconds(frameCount, frameDurationMs)
)

/**
 * Tracks smooth pixel interpolation and walk-cycle frame animation for an entity on the client.
 */
data class EntityVisualSmoothing(
    var renderPixelX: Float,
    var renderPixelY: Float,
    var targetPixelX: Float,
    var targetPixelY: Float,
    var direction: Direction = Direction.DOWN,
    var walkCycleTimer: Float = 0f,
    var isMoving: Boolean = false,
    var actionState: SpriteState? = null,
    var actionTimer: Float = 0f,
    var actionDuration: Float = 0f,
    /** Timed leap (a jump): while [leapDuration] > 0 the sprite eases from the leap start to [targetPixelX]/[targetPixelY]. */
    var leapTimer: Float = 0f,
    var leapDuration: Float = 0f,
    var leapStartX: Float = 0f,
    var leapStartY: Float = 0f,
    var leapPeakPixels: Float = 0f,
    /** A hop in place of the position easing (a skill's leap, whose position the server moves): a sine arc of [hopPeak] over [hopDuration]. */
    var hopTimer: Float = 0f,
    var hopDuration: Float = 0f,
    var hopPeak: Float = 0f
) {
    /** Height of the jump hop in pixels: a sine arc over the leap or the skill hop, 0 when neither. */
    val hopHeight: Float
        get() {
            val leap = if (leapDuration <= 0f) 0f else kotlin.math.sin(Math.PI * (leapTimer / leapDuration).coerceIn(0f, 1f)).toFloat() * leapPeakPixels
            val skillHop = if (hopDuration <= 0f) 0f else kotlin.math.sin(Math.PI * (hopTimer / hopDuration).coerceIn(0f, 1f)).toFloat() * hopPeak
            return maxOf(leap, skillHop)
        }

    val walkFrame: Int
        get() = if (!isMoving) 0 else if (((walkCycleTimer * 8f).toInt() % 2) == 0) 1 else 2

    val actionProgress: Float?
        get() = if (actionState != null && actionDuration > 0f) (actionTimer / actionDuration).coerceIn(0f, 1f) else null

    fun triggerAction(state: SpriteState, duration: Float = DEFAULT_ACTION_DURATION) {
        actionState = state
        actionTimer = 0f
        actionDuration = duration.coerceAtLeast(0.05f)
    }

    companion object {
        const val DEFAULT_ACTION_DURATION = 0.35f
    }
}

/**
 * Interactive tools in the F1 Live Map Editor.
 */
enum class MapEditorTool(val displayName: String) {
    BRUSH("Paint Brush"),
    ERASER("Eraser"),
    FLOOD_FILL("Flood Fill (Bucket)"),
    RECTANGLE("Rectangle Fill"),
    EYEDROPPER("Eyedropper (Pick)"),
    NPC_SPAWN("Place/Remove NPC Spawn"),
    EVENT("Place/Move Event")
}

/**
 * Action assigned to an on-screen Hotbar slot (Keys 1..0).
 */
sealed class HotbarSlotAction {
    abstract val label: String
    abstract val iconId: Int

    data class CastSpell(
        val spellId: Int,
        val spellName: String,
        override val iconId: Int = 1
    ) : HotbarSlotAction() {
        override val label: String get() = spellName
    }

    data class UseItem(
        val itemId: Int,
        val itemName: String,
        override val iconId: Int = 1
    ) : HotbarSlotAction() {
        override val label: String get() = itemName
    }
}

/** Milliseconds per simulation tick (the server runs 20 ticks per second). */
private const val MS_PER_TICK = 50L

/** Pixels one free-movement step covers along a cardinal direction (the server clamps a step to 16). */
private const val PIXEL_STEP = 8f

/** Unacknowledged steps kept for replay (about 3 s of free movement); older ones are assumed handled. */
private const val MAX_UNACKED_MOVES = 64

/** Largest same-map position change (pixels) that is animated as a leap rather than snapped; jumps stay well within it. */
private const val MAX_LEAP_PIXELS = 640.0

/** Seconds a hurt flinch lasts. */
private const val HURT_DURATION = 0.25f

/**
 * Authoritative-synced client world state + smooth entity interpolation + F1 Live Map Editor state.
 */
/** Shortest gap between two repeats of the same player action (a spell, an item) before the client lets another through. */
private const val ACTION_GAP_MS = 400L

class ClientWorldState {
    var authenticated: Boolean = false
    var statusMessage: String = "Disconnected"
    var localCharacter: CharacterState? = null
    var catalog: WorldContentCatalogDto = WorldContentCatalogDto()
    var compiledGame: CompiledGame? = null
        private set
    var gameDefinition: GameDefinition? = null
        set(value) {
            field = value
            compiledGame = value?.let { runCatching { CompiledGame.compile(it) }.getOrNull() }
        }

    var activeMap: MapDefinition? = null
    var neighborMaps: Map<Int, MapDefinition> = emptyMap()

    /**
     * Every map this connection has received, by id (the client half of the send-once map cache). A snapshot may name a map
     * only by id and revision; it is rebuilt from here. Cleared on every login, because the server's record of what it
     * sent is per connection too.
     */
    private val mapCache: MutableMap<Int, MapDefinition> = mutableMapOf()
    fun cachedMap(mapId: Int): MapDefinition? = mapCache[mapId] ?: activeMap?.takeIf { it.id == mapId }
    private var mapResyncWanted = false

    /** True once if a snapshot could not be rebuilt from cache; the game then sends [com.morgana.protocol.ClientPacket.RequestMapResync]. */
    fun takeMapResyncRequest(): Boolean = mapResyncWanted.also { mapResyncWanted = false }

    private fun cachedAt(ref: com.morgana.protocol.MapRef): MapDefinition? =
        mapCache[ref.id]?.takeIf { it.revision == ref.revision }

    /**
     * Throws away the editor's unsaved changes: the open map goes back to the version the server last sent (or, if this
     * connection no longer has it, a fresh copy is requested).
     */
    fun discardEditorChanges() {
        val draft = editor.draft ?: return
        val server = mapCache[draft.id]
        if (server == null) {
            mapResyncWanted = true
            return
        }
        editor.load(server)
        activeMap = server
    }

    /** The map a snapshot is about: carried in it, or (with map refs) the cached one; null when it cannot be rebuilt. */
    fun activeMapOf(packet: ServerPacket.MapStateSnapshot): MapDefinition? =
        packet.activeMap ?: packet.mapRefs.firstOrNull()?.let { cachedAt(it) }

    val playersOnMap: MutableMap<String, PublicPlayerSnapshot> = linkedMapOf()
    val npcsOnMap: MutableMap<String, MapNpcInstance> = linkedMapOf()
    val groundItemsOnMap: MutableList<MapGroundItem> = mutableListOf()
    val projectilesOnMap: MutableList<MapProjectile> = mutableListOf()
    val resourcesOnMap: MutableMap<TileCoord, MapResourceState> = linkedMapOf()

    /** The walls, chests and totems placed on the active map, by object id (see the `world_objects` capability). */
    val worldObjectsOnMap: MutableMap<String, com.morgana.domain.entity.WorldObject> = linkedMapOf()

    val playerVisuals: MutableMap<String, EntityVisualSmoothing> = linkedMapOf()
    val npcVisuals: MutableMap<String, EntityVisualSmoothing> = linkedMapOf()

    /**
     * Entities of the neighbouring maps near the shared border ("soft seams"), as last reported by the server. Their
     * smoothed positions live in [seamNpcVisuals] / [seamPlayerVisuals], already offset into the active map's pixel space
     * (NPC keys are "mapId:instanceId", player keys are the character id).
     */
    var seamMaps: List<NeighborMapEntities> = emptyList()
    val seamNpcVisuals: MutableMap<String, EntityVisualSmoothing> = linkedMapOf()
    val seamPlayerVisuals: MutableMap<String, EntityVisualSmoothing> = linkedMapOf()

    val floatingTexts: MutableList<ActiveFloatingText> = mutableListOf()
    val xpOrbTracker = XpOrbTracker()
    val chatBubbles: MutableMap<String, ActiveChatBubble> = linkedMapOf()
    val chatHistory: MutableList<ServerPacket.ChatBroadcast> = mutableListOf()

    /** Short-lived on-screen notices drawn by the toast overlay; fed by server system messages. */
    val toasts: com.morgana.client.ui.ToastQueue = com.morgana.client.ui.ToastQueue()

    /** New-player tips from the server, shown one at a time by the HUD. */
    val tips: com.morgana.client.ui.TipQueue = com.morgana.client.ui.TipQueue()

    /** Tracks the active dungeon delve and queues an end-of-run summary when returning to a safe map. */
    val runTracker = com.morgana.client.ui.RunSummaryModel.RunTracker()
    var pendingRunSummary: com.morgana.client.ui.RunSummaryModel.RunSummary? = null

    /** True once the server has sent an exact cooldown; from then on the client stops estimating them itself. */
    private var serverReportsCooldowns = false

    /** Quickbar cooldown sweeps; display only, the server enforces the real cooldown. */
    val spellCooldowns = SpellCooldowns()

    /** True when floating XP essence orbs are enabled by the game and not suppressed by reduced motion. */
    fun xpOrbsEnabled(): Boolean =
        gameDefinition?.ui?.xpOrbs == true && !com.morgana.client.access.Accessibility.reduceMotion

    /** Player XP to display in the HUD, holding back XP carried by in-flight essence orbs until impact. */
    fun visualXp(actualXp: Long): Long =
        if (xpOrbsEnabled()) (actualXp - xpOrbTracker.pendingVisualXp).coerceAtLeast(0L) else actualXp

    /** The fallen player the local player last clicked: the next revive spell goes to them instead of the nearest ghost. */
    var selectedGhostId: String? = null
        private set

    /** Clicking a tile with a ghost on it (not yourself) picks that ghost for a revive spell; returns who, or null. */
    fun selectGhostAt(tileX: Int, tileY: Int): PublicPlayerSnapshot? {
        val ownId = localCharacter?.id
        val ghost = playersOnMap.values
            .filter { it.isGhost && it.characterId != ownId && it.position.tileX == tileX && it.position.tileY == tileY }
            .minByOrNull { it.characterId } ?: return null
        selectedGhostId = ghost.characterId
        toasts.add("Revive target: ${ghost.name}", com.morgana.client.ui.ToastKind.INFO, com.morgana.client.ui.ToastClock.now())
        return ghost
    }

    /** The enemy the player has locked on to (tap, click or the target button), or null. Attacks and skills aim at it while it is in reach. */
    var lockedNpcId: String? = null

    /** When each action input ("RMB", "SPACE", ...) last started its skill, in wall-clock ms, for the cooldown shown on its button. */
    val skillUsedAtMs: MutableMap<String, Long> = HashMap()

    /** Where a skill the player is aiming by dragging would go, in map pixels (y down), for the aim line; null when not aiming. */
    var aimPreview: Pair<Float, Float>? = null

    /** Radius (map pixels) of the area the aimed skill will hit around [aimPreview], or 0 for a skill with no area (just the line). */
    var aimRadius: Float = 0f

    /** The local player's centre in map pixels (y down), or null before they are on a map. */
    fun playerCentre(): Pair<Float, Float>? {
        val ch = localCharacter ?: return null
        val ts = activeMap?.tileSize ?: return null
        return (ch.position.pixelX + ts / 2f) to (ch.position.pixelY + ts / 2f)
    }

    /** The enemies that can be targeted: living monsters on this map (not pets or townsfolk), as map-pixel positions with what makes them worth hitting. */
    fun targetCandidates(): List<com.morgana.client.input.TargetCandidate> {
        val ts = activeMap?.tileSize ?: return emptyList()
        val me = localCharacter?.id
        val out = ArrayList<com.morgana.client.input.TargetCandidate>()
        for (npc in npcsOnMap.values) {
            if (!npc.isAlive || npc.ownerId != null) continue
            val def = catalog.npcs.firstOrNull { it.id == npc.npcId } ?: continue
            val monster = (def.behavior == com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT || def.behavior == com.morgana.domain.content.NpcBehavior.PASSIVE) &&
                def.eventId == 0 && def.shopId == 0
            if (!monster) continue
            val vis = npcVisuals[npc.instanceId]
            val x = (vis?.renderPixelX ?: npc.bodyX(ts)) + ts / 2f
            val y = (vis?.renderPixelY ?: npc.bodyY(ts)) + ts / 2f
            val hp = npc.vitals["hp"] ?: npc.vitals.values.firstOrNull()
            val frac = if (hp == null || hp.max <= 0) 1f else hp.cur.toFloat() / hp.max
            out += com.morgana.client.input.TargetCandidate(
                id = npc.instanceId, x = x, y = y, boss = def.encounter != null, hpFraction = frac,
                targetsMe = me != null && npc.targetEntityId == me
            )
        }
        return out
    }

    /** Drops the lock when its enemy is gone or dead. */
    fun refreshLock() {
        val id = lockedNpcId ?: return
        val npc = npcsOnMap[id]
        if (npc == null || !npc.isAlive) lockedNpcId = null
    }

    /**
     * Who to name as the target when casting [spellId]: the clicked ghost for a revive spell while they are still a ghost on
     * this map, otherwise nobody (the server then picks for itself, as it always did).
     */
    fun castTargetFor(spellId: Int): String? {
        val spell = catalog.spells.firstOrNull { it.id == spellId } ?: return null
        if (spell.revivePercent <= 0) return null
        val id = selectedGhostId ?: return null
        if (playersOnMap[id]?.isGhost == true) return id
        selectedGhostId = null
        return null
    }

    private val lastActionMs = HashMap<String, Long>()

    /**
     * True when the player's repeat of the action [key] (a spell, an item) may go ahead at [nowMs]: at least [gapMs] since
     * the last one that did. A refused repeat makes no sound, animation or packet, so holding or mashing a key does not
     * replay what the server would refuse anyway. Records the action when it returns true.
     */
    fun actionReady(key: String, nowMs: Long, gapMs: Long = ACTION_GAP_MS): Boolean {
        val last = lastActionMs[key]
        if (last != null && nowMs - last < gapMs) return false
        lastActionMs[key] = nowMs
        return true
    }

    /**
     * True when casting [spellId] at [nowMs] is worth sending: it is not cooling down, the caster can pay its costs, and a
     * cast was not just made. Mashing a spell on cooldown (or without the mana for it) is then silent instead of playing
     * the cast sound and animation each press. Records the cast when it returns true.
     */
    fun castReady(spellId: Int, nowMs: Long): Boolean {
        if (spellCooldowns.remaining(spellId, com.morgana.client.ui.ToastClock.now()) > 0f) return false
        val spell = catalog.spells.firstOrNull { it.id == spellId }
        val ch = localCharacter
        if (spell != null && ch != null && spell.costs.any { (vitalId, cost) -> (ch.vitals[vitalId]?.cur ?: 0) < cost }) return false
        return actionReady("spell:$spellId", nowMs)
    }

    /**
     * Starts a shot from the caster named by the server to the impact at [tileX],[tileY] when the spell is ranged (it names a
     * projectile sprite), and returns the seconds the impact animation should wait for it to land; 0 when no shot is due.
     */
    private fun launchShotFor(spellId: Int, casterId: String, tileX: Int, tileY: Int): Float {
        if (spellId == 0 || casterId.isEmpty()) return 0f
        val spell = catalog.spells.firstOrNull { it.id == spellId } ?: return 0f
        if (spell.projectileSpriteId <= 0) return 0f
        val ts = (activeMap?.tileSize ?: 32).toFloat()
        // The caster is a player or an NPC on this map; a caster who is not here (left, or on another map) fires no shot.
        val player = playersOnMap[casterId]
        val npc = if (player == null) npcsOnMap[casterId] else null
        val (vis, spriteId, tx, ty) = when {
            player != null -> Quad(playerVisuals[casterId], player.spriteId, player.position.tileX, player.position.tileY)
            npc != null -> Quad(npcVisuals[casterId], catalog.npcs.firstOrNull { it.id == npc.npcId }?.spriteId ?: 0, npc.tileX, npc.tileY)
            else -> return 0f
        }
        val sx = (vis?.renderPixelX ?: (tx * ts)) + ts * 0.5f
        // A tall caster's hands are above the middle of the tile, so the shot leaves from the height of its body.
        val lift = gameDefinition?.sprites?.let { (it.uiHeightOf(spriteId) * 0.5f - ts * 0.5f).coerceAtLeast(0f) } ?: 0f
        val sy = (vis?.renderPixelY ?: (ty * ts)) + ts * 0.5f - lift
        val ex = tileX * ts + ts * 0.5f
        val ey = tileY * ts + ts * 0.4f
        val dist = kotlin.math.hypot(ex - sx, ey - sy)
        if (dist < ts * 0.75f) return 0f
        val flight = SpellShot.flightSeconds(dist)
        // Alternate which side each shot bows to, so a volley fans out instead of stacking on one line.
        shotCounter++
        val bow = (if (shotCounter % 2 == 0) 1f else -1f) * dist * (0.04f + 0.03f * (shotCounter % 3))
        spellShots.add(SpellShot(spell.projectileSpriteId, sx, sy, ex, ey, flight, bow))
        return flight
    }
    private var shotCounter = 0
    private data class Quad(val vis: EntityVisualSmoothing?, val spriteId: Int, val tileX: Int, val tileY: Int)

    /** Call when the player asks to cast [spellId]; starts its cooldown sweep unless it is already cooling. */
    fun noteSpellCast(spellId: Int) {
        if (serverReportsCooldowns) return // the server will say exactly when this spell is ready
        val ticks = catalog.spells.firstOrNull { it.id == spellId }?.cooldownTicks ?: return
        spellCooldowns.start(spellId, ticks / SpellCooldowns.TICKS_PER_SECOND, com.morgana.client.ui.ToastClock.now())
    }

    /** The player's choice to hide out-of-character chat from the log (`/hideooc`, `/showooc`); kept for the session. */
    var hideOocChat: Boolean = false

    /** The latest live save the server refused, with when it arrived; the content editor panels show its reason. */
    var lastSaveRejection: com.morgana.client.editor.forms.SaveRejection? = null
    var lastSaveRejectionAt: Long = 0L
    /** Feedback text and error flag from the most recent live PNG import attempt in the F1 Art panel. */
    var lastArtImportMessage: String? = null
    var lastArtImportError: Boolean = false
    val activeAnimations: MutableList<ActiveSpriteAnimation> = mutableListOf()
    /** Arrows and bolts in flight for ranged spells (anyone's); visual only. */
    val spellShots: MutableList<SpellShot> = mutableListOf()

    /** The momentum meter (0..99) filled by successful dodges, and when the current empowerment ends (wall-clock ms; 0 = not empowered). */
    var momentum: Int = 0
    var empoweredUntilMs: Long = 0L

    fun isEmpowered(nowMs: Long = System.currentTimeMillis()): Boolean = com.morgana.client.render.ClientDebug.empower == "on" || empoweredUntilMs > nowMs

    /** Built-in skill visuals (arrow rain, dodge dust, spin whirl) in progress; see [SkillFx]. */
    val skillFx: MutableList<SkillFx> = mutableListOf()
    val playerCastProgress: MutableMap<String, Float> = linkedMapOf()

    /** Windows the server's plugins have opened (by window id); the HUD draws them and removes ones the server closed. */
    val pluginWindows: MutableMap<String, PluginWindowDto> = linkedMapOf()

    /** Called with (channel, payload) for every plugin message; a client script or the HUD can listen. Null ignores them. */
    var onPluginMessage: ((String, Map<String, String>) -> Unit)? = null

    var activeTrade: TradeSessionState? = null
    var activeParty: PartyState? = null
    var activeGuild: GuildDocument? = null

    /** A boss's danger zone the floor shows until [untilMs] (client clock). */
    data class Telegraph(val mapId: Int, val centerX: Int, val centerY: Int, val radius: Int, val startedMs: Long, val untilMs: Long, val label: String)

    /** Danger zones being shown right now ([ServerPacket.BossTelegraph]); expired ones are dropped as they are read. */
    val telegraphs: MutableList<Telegraph> = mutableListOf()

    /** The stall the player last looked at, or their own after they changed it ([ServerPacket.StallViewed]); the Stall window shows it. */
    var stallView: com.morgana.domain.game.StallView? = null

    /** The server's directions to quest targets on other maps ([ServerPacket.QuestGuides]). */
    var questGuides: List<com.morgana.domain.event.QuestGuide> = emptyList()

    /** What the character has explored, by map id, as last sent by the server ([ServerPacket.Exploration]); replaced on every change. */
    var explored: Map<Int, com.morgana.protocol.ExploredMapInfo> = emptyMap()

    /** Picture and enemy spawns of each explored map, as last sent for the world map window ([ServerPacket.WorldMapDetail]). */
    var worldMapDetail: Map<Int, com.morgana.protocol.WorldMapDetailEntry> = emptyMap()

    /** Quests the player stopped tracking in this session; the tracker shows every other quest in progress. */
    val untrackedQuests: MutableSet<Int> = linkedSetOf()

    /** Whether the on-screen quest tracker and its world markers are shown (the Menu toggles it). */
    var showQuestTracker: Boolean = true

    private var trackerNames: Pair<WorldContentCatalogDto, com.morgana.client.ui.QuestTracker.Names>? = null

    private fun questNames(): com.morgana.client.ui.QuestTracker.Names {
        trackerNames?.takeIf { it.first === catalog }?.let { return it.second }
        val names = com.morgana.client.ui.QuestTracker.Names(
            npcs = catalog.npcs.associate { it.id to it.name },
            items = catalog.items.associate { it.id to it.name },
            resources = catalog.resources.associate { it.id to it.name },
            recipes = catalog.recipes.associate { it.id to it.name },
            maps = catalog.maps.associate { it.id to it.name }
        )
        trackerNames = catalog to names
        return names
    }

    /** What the quest tracker needs to know about where the player is and what stands around them, or null before login. */
    fun questTrackerWorld(): com.morgana.client.ui.QuestTracker.World? {
        val ch = localCharacter ?: return null
        return com.morgana.client.ui.QuestTracker.World(
            mapId = activeMap?.id ?: ch.position.mapId,
            playerX = ch.position.tileX,
            playerY = ch.position.tileY,
            npcs = npcsOnMap.values.filter { it.isAlive }.map { com.morgana.client.ui.QuestTracker.Sighting(it.npcId, it.tileX, it.tileY) },
            resources = resourcesOnMap.values.filter { !it.depleted }.map { com.morgana.client.ui.QuestTracker.Sighting(it.resourceId, it.tileX, it.tileY) },
            names = questNames(),
            guides = questGuides
        )
    }

    /** The quests shown in the tracker: those in progress that the player has not untracked. */
    fun trackedQuests(): List<com.morgana.client.ui.QuestTracker.Entry> {
        val ch = localCharacter ?: return emptyList()
        if (ch.quests.isEmpty()) return emptyList()
        val world = questTrackerWorld() ?: return emptyList()
        return com.morgana.client.ui.QuestTracker.entries(ch.quests, catalog.quests.associateBy { it.id }, world, untrackedQuests)
    }

    /** The tiles of the current map to mark for the tracked quests; empty when the tracker is switched off. */
    fun questWaypoints(): List<com.morgana.client.ui.QuestTracker.Waypoint> {
        if (!showQuestTracker || !authenticated) return emptyList()
        val world = questTrackerWorld() ?: return emptyList()
        return com.morgana.client.ui.QuestTracker.waypoints(trackedQuests(), world)
    }

    /** The player's mailbox, newest first, as last sent by the server ([ServerPacket.MailInbox]). */
    var mailbox: List<com.morgana.domain.entity.MailMessageDocument> = emptyList()

    /** The market's open listings, newest first, as last sent by the server ([ServerPacket.MarketListings]). */
    var marketListings: List<com.morgana.domain.entity.MarketListingDocument> = emptyList()

    /** The friends list as last sent by the server ([ServerPacket.FriendList]), online friends first. */
    var friends: List<com.morgana.protocol.FriendEntry> = emptyList()

    /** The guild's members with ranks, as last sent by the server ([ServerPacket.GuildRoster]). Empty without a guild. */
    var guildRoster: List<com.morgana.protocol.GuildMemberEntry> = emptyList()

    /** The shop the buy-back list belongs to, and what the player sold there and can buy back ([ServerPacket.ShopBuyback]). */
    var shopBuybackShopId: Int = 0
    var shopBuyback: List<com.morgana.domain.sim.ShopBuybackEntry> = emptyList()

    /** The names this player ignores, sorted ([ServerPacket.IgnoreList]). */
    var ignored: List<String> = emptyList()

    /** `configKey`s of optional modules the connected server has switched off (from the login result). */
    var disabledModules: Set<String> = emptySet()

    /** How every player moves in this realm: free pixel movement when the `subTileMovement` module is on, tile steps otherwise. Not a player choice. */
    val movementMode: MovementMode
        get() = if (isModuleEnabled("subTileMovement")) MovementMode.SUB_TILE else MovementMode.GRID_LOCKED

    /** Capabilities advertised by the connected server. */
    var serverCapabilities: Set<String> = emptySet()

    /** True unless the server switched this optional module off, e.g. `isModuleEnabled("trading")`. */
    fun isModuleEnabled(configKey: String): Boolean = configKey !in disabledModules

    /** Primary life vital id for this game (using `VitalRole.LIFE` first, then the first defined vital, else `"hp"`). */
    fun lifeVitalId(): String =
        gameDefinition?.vitals?.firstOrNull { it.role == com.morgana.domain.game.VitalRole.LIFE }?.id
            ?: gameDefinition?.vitals?.firstOrNull()?.id
            ?: "hp"

    /** Effective UI profile for the connected realm, falling back to all-enabled defaults. */
    val uiProfile: UiProfile
        get() = gameDefinition?.ui ?: UiProfile()

    /** True when the realm enables combat UI and its combat module is not `noop`. */
    fun showCombatUi(): Boolean =
        uiProfile.combat && CombatModule.isCombatEnabled(gameDefinition?.combat?.module ?: CombatModule.DEFAULT_ID)

    /** True when the realm enables spell UI, has combat enabled, and defines at least one spell. */
    fun showSpellsUi(): Boolean =
        showCombatUi() && uiProfile.spells && catalog.spells.isNotEmpty()

    /** Whether the local character's class frames its abilities as Spells or Skills. */
    fun abilityTerminology(): com.morgana.domain.content.AbilityTerminology {
        val cid = localCharacter?.classId ?: return com.morgana.domain.content.AbilityTerminology.SPELLS
        return catalog.classes.firstOrNull { it.id == cid }?.abilityTerminology
            ?: com.morgana.domain.content.AbilityTerminology.SPELLS
    }

    /** True when the crafting module is enabled and the realm defines at least one recipe. */
    fun showCraftingUi(): Boolean =
        isModuleEnabled("crafting") && catalog.recipes.isNotEmpty()

    /** True when the touch Spell face button should be shown (spells UI enabled and character knows a spell or bound slot 1). */
    fun showTouchSpellButton(): Boolean =
        showSpellsUi() && (localCharacter?.knownSpellIds?.isNotEmpty() == true || hotbarSlots.getOrNull(0) != null)

    /** True when the realm enables the hotbar strip. */
    fun showHotbarUi(): Boolean = uiProfile.hotbar

    /**
     * True when hotbar slot [index] should be shown: always shown in spell-enabled combat realms, or when the player
     * has assigned an item/spell to that slot in non-spell realms.
     */
    fun showHotbarSlot(index: Int): Boolean =
        showHotbarUi() && (hotbarSlots.getOrNull(index) != null || showSpellsUi())

    /** True when at least one hotbar slot is visible. */
    fun showAnyHotbarSlot(): Boolean = (0 until 10).any { showHotbarSlot(it) }

    /** True when the realm enables the minimap radar. */
    fun showMinimapUi(): Boolean = uiProfile.minimap

    /** True when the realm enables the chat window. */
    fun showChatUi(): Boolean = uiProfile.chat

    /** True when the realm enables the party UI and the `parties` server module is enabled. */
    fun showPartyUi(): Boolean = uiProfile.party && isModuleEnabled("parties")

    var showMinimap: Boolean = true
    var latestRttMs: Long = 0L
    val hotbarSlots: Array<HotbarSlotAction?> = Array(10) { null }

    fun loadHotbarFromPreferences(prefs: PerRealmCharacterPreferences) {
        for (i in 0 until 10) {
            val entry = prefs.getHotbar(i) ?: continue
            when (entry.actionType) {
                "SPELL" -> {
                    val spellId = entry.actionId.toIntOrNull() ?: continue
                    val sp = catalog.spells.firstOrNull { it.id == spellId }
                    hotbarSlots[i] = HotbarSlotAction.CastSpell(
                        spellId = spellId,
                        spellName = sp?.name ?: "Spell #$spellId",
                        iconId = sp?.iconId ?: 1
                    )
                }
                "ITEM" -> {
                    val itemId = entry.actionId.toIntOrNull() ?: continue
                    val item = catalog.items.firstOrNull { it.id == itemId }
                    hotbarSlots[i] = HotbarSlotAction.UseItem(
                        itemId = itemId,
                        itemName = item?.name ?: "Item #$itemId",
                        iconId = item?.iconId ?: 1
                    )
                }
            }
        }
    }

    fun exportHotbarToPreferences(): PerRealmCharacterPreferences {
        val bindings = mutableMapOf<String, RealmHotbarEntry>()
        for (i in 0 until 10) {
            val slot = hotbarSlots[i] ?: continue
            val entry = when (slot) {
                is HotbarSlotAction.CastSpell -> RealmHotbarEntry(slot = i, actionType = "SPELL", actionId = slot.spellId.toString())
                is HotbarSlotAction.UseItem -> RealmHotbarEntry(slot = i, actionType = "ITEM", actionId = slot.itemId.toString())
            }
            bindings[i.toString()] = entry
        }
        return PerRealmCharacterPreferences(hotbarBindings = bindings)
    }

    fun autoPopulateHotbarIfEmpty() {
        if (hotbarSlots.all { it == null }) {
            val ch = localCharacter ?: return
            ch.knownSpellIds.take(4).forEachIndexed { idx, spellId ->
                val sp = catalog.spells.firstOrNull { it.id == spellId }
                hotbarSlots[idx] = HotbarSlotAction.CastSpell(
                    spellId = spellId,
                    spellName = sp?.name ?: "Spell #$spellId",
                    iconId = sp?.iconId ?: (idx + 1)
                )
            }
            val pot = ch.inventory.slots.filterNotNull().firstOrNull { slot ->
                val itDef = catalog.items.firstOrNull { it.id == slot.itemId }
                itDef?.name?.contains("Potion", ignoreCase = true) == true
            }
            if (pot != null) {
                val itDef = catalog.items.firstOrNull { it.id == pot.itemId }
                hotbarSlots[4] = HotbarSlotAction.UseItem(
                    itemId = pot.itemId,
                    itemName = itDef?.name ?: "Potion #${pot.itemId}",
                    iconId = itDef?.iconId ?: pot.itemId
                )
            }
        }
    }

    // --- F1 Live Map Editor State: the editing model is [MapEditSession]; these properties are thin views of it ---
    var mapEditorActive: Boolean = false
    var showAttributeOverlay: Boolean = true
    var hoveredTile: TileCoord? = null
    /** Non-null while waiting for the player to click a tile for a GROUND_AOE spell. */
    var pendingGroundSpellId: Int? = null
    /** True while the player holds Alt to display all ground item labels. */
    var showAllGroundLabels: Boolean = false
    /** Ground item label boxes laid out on the last rendered frame (for click-to-loot hit testing). */
    var lastGroundLootLabels: List<com.morgana.client.ui.GroundLootLabels.LabelEntry> = emptyList()
    /** Currently open event in the Events panel, so the renderer highlights it at its live position. */
    var editingEventDraft: EventDefinition? = null

    /** The map editor's model: draft, tool, brush, layers and undo/redo. Every change shows live as the active map. */
    val editor: MapEditSession = MapEditSession().also { session -> session.onDraftChanged = { activeMap = it } }

    var selectedTool: MapEditorTool
        get() = editor.tool
        set(value) { editor.tool = value }
    var selectedLayer: MapLayer
        get() = editor.layer
        set(value) { editor.layer = value }
    var selectedTilesetId: Int
        get() = editor.tilesetId
        set(value) { editor.tilesetId = value }
    var selectedSrcTileX: Int
        get() = editor.brushX
        set(value) { editor.selectTile(editor.tilesetId, value, editor.brushY) }
    var selectedSrcTileY: Int
        get() = editor.brushY
        set(value) { editor.selectTile(editor.tilesetId, editor.brushX, value) }
    var selectedAutotileMode: AutotileMode
        get() = editor.autotileMode
        set(value) { editor.autotileMode = value }
    var selectedAttribute: TileAttribute
        get() = editor.attribute
        set(value) { editor.attribute = value }
    var paintModeAttributeOnly: Boolean
        get() = editor.attributeOnly
        set(value) { editor.attributeOnly = value }
    var selectedNpcSpawnId: Int
        get() = editor.npcSpawnId
        set(value) { editor.npcSpawnId = value }

    /** The map being edited. Assigning it (a new map, or the server's version) replaces the draft and clears undo history. */
    var workingMapDraft: MapDefinition?
        get() = editor.draft
        set(value) { editor.load(value) }

    // ---- Game clock: the server tick it last told us, advanced by our own clock (20 ticks per second) ----
    private var serverTick: Long = 0L
    private var serverTickAtMs: Long = 0L

    /** Source of wall-clock milliseconds; replaceable so tests are deterministic. */
    var nowMs: () -> Long = System::currentTimeMillis

    private fun syncServerTick(tick: Long) {
        if (tick <= 0L) return
        serverTick = tick
        serverTickAtMs = nowMs()
    }

    /** Best estimate of the server's current tick. */
    fun estimatedServerTick(): Long = serverTick + (nowMs() - serverTickAtMs).coerceAtLeast(0L) / MS_PER_TICK

    /** The game's calendar right now, or null when this game has no clock (or the server tick is not known yet). */
    fun clockNow(): ClockTime? {
        val definition = gameDefinition?.clock ?: return null
        if (serverTick <= 0L) return null
        return WorldClock(definition).at(estimatedServerTick())
    }

    /** The clock only if the game opted in to the visual day and night cycle; the renderer darkens outdoor maps by its light level. */
    fun dayNightClock(): ClockTime? = if (gameDefinition?.clock?.dayNight == true) clockNow() else null

    /**
     * The weather [map] shows right now: its zone's weather for an exterior map, clear for an interior one (see [WorldWeather]).
     * Games without weather zones keep each map's own fixed weather.
     */
    fun weatherNow(map: MapDefinition): WeatherNow = WorldWeather.effective(map, gameDefinition?.weather, estimatedServerTick())

    /** What the local player is facing right now, so the action buttons can say what they will do. */
    enum class FacingTarget { NOTHING, RESOURCE, FRIENDLY_NPC, HOSTILE_NPC, SHOP, BANK, DOOR }

    fun facingTarget(): FacingTarget {
        val map = activeMap ?: return FacingTarget.NOTHING
        val pos = localCharacter?.position ?: return FacingTarget.NOTHING
        if (pos.mapId != map.id) return FacingTarget.NOTHING
        val tx = pos.tileX + pos.direction.dx
        val ty = pos.tileY + pos.direction.dy
        val npc = npcsOnMap.values.firstOrNull { it.isAlive && it.tileX == tx && it.tileY == ty }
        if (npc != null) {
            val behavior = catalog.npcs.firstOrNull { it.id == npc.npcId }?.behavior
            return if (behavior == NpcBehavior.ATTACK_ON_SIGHT || behavior == NpcBehavior.GUARD) FacingTarget.HOSTILE_NPC
            else FacingTarget.FRIENDLY_NPC
        }
        return when (map.getCell(tx, ty)?.attribute) {
            is TileAttribute.ResourceNode -> FacingTarget.RESOURCE
            is TileAttribute.ShopTile -> FacingTarget.SHOP
            is TileAttribute.BankTile -> FacingTarget.BANK
            is TileAttribute.KeyDoor -> FacingTarget.DOOR
            else -> FacingTarget.NOTHING
        }
    }

    /** Label for the touch pad's attack button: swinging at a resource is working it, not fighting. */
    fun attackButtonLabel(): String = if (facingTarget() == FacingTarget.RESOURCE) "Work" else "Atk"

    /** Label for the touch pad's interact button. */
    fun interactButtonLabel(): String = when (facingTarget()) {
        FacingTarget.RESOURCE -> "Harvest"
        FacingTarget.FRIENDLY_NPC -> "Talk"
        FacingTarget.SHOP -> "Shop"
        FacingTarget.BANK -> "Bank"
        FacingTarget.DOOR -> "Use"
        else -> "Use"
    }

    /** True when pressing the touch interact button would do something to what the hero faces (talk, harvest, shop, bank, door). */
    fun hasInteractTarget(): Boolean = facingTarget().let { it != FacingTarget.NOTHING && it != FacingTarget.HOSTILE_NPC }

    /** Forgets everything tied to the logged-in session (used on logout and when a session cannot be resumed). */
    fun resetSession() {
        authenticated = false
        statusMessage = "Disconnected"
        pluginWindows.clear()
        localCharacter = null
        activeMap = null
        neighborMaps = emptyMap()
        mapCache.clear()
        mapResyncWanted = false
        playersOnMap.clear()
        npcsOnMap.clear()
        groundItemsOnMap.clear()
        projectilesOnMap.clear()
        resourcesOnMap.clear()
        worldObjectsOnMap.clear()
        playerVisuals.clear()
        npcVisuals.clear()
        seamMaps = emptyList()
        seamNpcVisuals.clear()
        seamPlayerVisuals.clear()
        floatingTexts.clear()
        xpOrbTracker.clear()
        chatBubbles.clear()
        chatHistory.clear()
        toasts.clear()
        tips.clear()
        runTracker.reset()
        pendingRunSummary = null
        spellCooldowns.clear()
        activeAnimations.clear()
        spellShots.clear()
        skillFx.clear()
        momentum = 0
        empoweredUntilMs = 0L
        playerCastProgress.clear()
        activeTrade = null
        activeParty = null
        activeGuild = null
        mailbox = emptyList()
        marketListings = emptyList()
        untrackedQuests.clear()
        explored = emptyMap()
        worldMapDetail = emptyMap()
        friends = emptyList()
        guildRoster = emptyList()
        ignored = emptyList()
        shopBuyback = emptyList()
        shopBuybackShopId = 0
        mapEditorActive = false
        hotbarSlots.fill(null)
    }

    fun applyServerPacket(packet: ServerPacket, onUiWindowRequested: (ServerPacket.OpenUiWindow) -> Unit = {}) {
        when (packet) {
            is ServerPacket.AuthResult -> {
                syncServerTick(packet.serverTick)
                authenticated = packet.success
                if (packet.success) mapCache.clear()
                resetMovePrediction() // a new connection: the server numbers this session's steps from scratch
                statusMessage = packet.message
                disabledModules = packet.disabledModules.toSet()
                serverCapabilities = packet.capabilities.toSet()
                if (packet.character != null) {
                    localCharacter = packet.character
                    syncPlayerVisualTarget(
                        id = packet.character!!.id,
                        px = packet.character!!.position.pixelX,
                        py = packet.character!!.position.pixelY,
                        dir = packet.character!!.position.direction,
                        snapImmediately = true
                    )
                }
                if (packet.catalog != null) {
                    catalog = packet.catalog!!
                }
                if (packet.gameDefinition != null) {
                    gameDefinition = packet.gameDefinition
                }
                autoPopulateHotbarIfEmpty()
            }
            is ServerPacket.Pong -> syncServerTick(packet.serverTick)
            is ServerPacket.RealmInfo -> {} // handled by MorganaGame before login
            is ServerPacket.CharacterList -> {} // handled by MorganaGame (the character screen)
            is ServerPacket.SessionEnded -> {} // handled by MorganaGame (it must stop auto-reconnecting)
            is ServerPacket.MapStateSnapshot -> {
                syncServerTick(packet.serverTick)
                // Learn the maps this snapshot carries, then rebuild the active map and neighbours (from cache when sent by reference).
                packet.activeMap?.let { mapCache[it.id] = it }
                mapCache.putAll(packet.neighborMaps)
                val snapshotMap = activeMapOf(packet)
                if (snapshotMap == null) {
                    // The server believes we hold a map we do not: ask for everything again and ignore this snapshot.
                    mapResyncWanted = true
                    return
                }
                val snapshotNeighbors = if (packet.mapRefs.isEmpty()) packet.neighborMaps
                    else packet.mapRefs.drop(1).mapNotNull { cachedAt(it) }.associateBy { it.id }
                if (packet.mapRefs.size - 1 > snapshotNeighbors.size) mapResyncWanted = true
                val previousMap = activeMap
                val mapChanged = previousMap?.id != snapshotMap.id
                // Walking over a border into the neighbouring map: keep everything where it is on screen by shifting it into
                // the new map's coordinates, so the camera and sprites glide on instead of jumping. Warps clear instead.
                val shift = if (mapChanged && previousMap != null && isModuleEnabled("softSeams")) crossingShift(previousMap, snapshotMap) else null
                if (mapChanged) {
                    xpOrbTracker.clear()
                    if (shift != null) {
                        val (dx, dy) = shift
                        shiftVisuals(playerVisuals, dx, dy)
                        shiftVisuals(seamPlayerVisuals, dx, dy)
                        shiftVisuals(npcVisuals, dx, dy)
                        shiftVisuals(seamNpcVisuals, dx, dy)
                        // Everything that was on the old map (except us) is now across the border.
                        val localId = localCharacter?.id
                        for ((id, v) in playerVisuals) if (id != localId) seamPlayerVisuals[id] = v
                        playerVisuals.keys.retainAll { it == localId }
                        for ((id, v) in npcVisuals) seamNpcVisuals["${previousMap!!.id}:$id"] = v
                        npcVisuals.clear()
                    } else {
                        playerVisuals.clear()
                        npcVisuals.clear()
                        seamPlayerVisuals.clear()
                        seamNpcVisuals.clear()
                    }
                }
                seamMaps = emptyList() // the server resends the view across the borders right after this snapshot
                activeMap = snapshotMap
                if (workingMapDraft == null || mapChanged) {
                    workingMapDraft = snapshotMap
                }
                neighborMaps = snapshotNeighbors
                playersOnMap.clear()
                packet.players.forEach { snap ->
                    // On the same map our own entry is a round trip old; keep the predicted position (see PlayerUpdated).
                    val own = localCharacter?.takeIf { !mapChanged && it.id == snap.characterId && it.position.mapId == snapshotMap.id }
                    val p = if (own != null) snap.copy(position = own.position) else snap
                    playersOnMap[p.characterId] = p
                    if (p.characterId !in playerVisuals) seamPlayerVisuals.remove(p.characterId)?.let { playerVisuals[p.characterId] = it }
                    syncPlayerVisualTarget(
                        id = p.characterId,
                        px = p.position.pixelX,
                        py = p.position.pixelY,
                        dir = p.position.direction,
                        snapImmediately = p.characterId !in playerVisuals
                    )
                }
                npcsOnMap.clear()
                val ts = snapshotMap.tileSize.toFloat()
                packet.npcs.forEach { n ->
                    npcsOnMap[n.instanceId] = n
                    if (n.instanceId !in npcVisuals) {
                        seamNpcVisuals.remove("${snapshotMap.id}:${n.instanceId}")?.let { npcVisuals[n.instanceId] = it }
                    }
                    syncNpcVisualTarget(
                        id = n.instanceId,
                        px = n.bodyX(snapshotMap.tileSize),
                        py = n.bodyY(snapshotMap.tileSize),
                        dir = n.direction,
                        snapImmediately = n.instanceId !in npcVisuals
                    )
                }
                if (mapChanged) {
                    val floorBossNpcId = snapshotMap.generator?.bossNpcId ?: 0
                    val lifeVitalId = lifeVitalId()
                    val hasAliveBoss = com.morgana.client.ui.DungeonHudModel.activeBoss(
                        npcsOnMap = npcsOnMap.values,
                        npcDefsById = catalog.npcs.associateBy { it.id },
                        lifeVitalId = lifeVitalId,
                        floorBossNpcId = floorBossNpcId
                    ) != null
                    val ch = localCharacter
                    runTracker.onMapEntered(
                        mapName = snapshotMap.name,
                        hasGenerator = snapshotMap.generator != null,
                        currentLevel = ch?.level ?: 1,
                        currentXp = ch?.xp ?: 0L,
                        nowMs = nowMs()
                    )?.let { summary ->
                        pendingRunSummary = summary
                    }
                    if (previousMap != null) {
                        val notice = com.morgana.client.ui.DungeonHudModel.floorEntryToast(
                            mapName = snapshotMap.name,
                            hasGenerator = snapshotMap.generator != null,
                            hasAliveBoss = hasAliveBoss,
                            hasGeneratorBoss = floorBossNpcId > 0
                        )
                        toasts.add(notice.text, notice.kind, com.morgana.client.ui.ToastClock.now())
                    }
                }
                groundItemsOnMap.clear()
                groundItemsOnMap.addAll(packet.groundItems)
                projectilesOnMap.clear()
                projectilesOnMap.addAll(packet.projectiles)
                resourcesOnMap.clear()
                packet.resources.forEach { resourcesOnMap[TileCoord(it.tileX, it.tileY)] = it }
                worldObjectsOnMap.clear()
                packet.worldObjects.forEach { worldObjectsOnMap[it.id] = it }
            }
            is ServerPacket.SeamView -> applySeamView(packet)
            is ServerPacket.CharacterSync -> {
                val prevChar = localCharacter
                val prevMapId = prevChar?.position?.mapId
                localCharacter = reconcileLocalCharacter(packet.character, packet.ackMoveSeq)
                if (prevChar != null && prevMapId == packet.character.position.mapId) {
                    xpOrbTracker.onLocalXpChange(
                        prevLevel = prevChar.level,
                        prevXp = prevChar.xp,
                        newLevel = packet.character.level,
                        newXp = packet.character.xp,
                        enabled = xpOrbsEnabled()
                    )
                }
                val curHp = packet.character.vitals[lifeVitalId()]?.cur ?: packet.character.vitals.values.firstOrNull()?.cur
                if (curHp != null) runTracker.onPlayerVitals(curHp)
                // On a map change the accompanying map snapshot places the sprite (gliding across a border, snapping on a warp).
                if (prevMapId == null || prevMapId == packet.character.position.mapId) {
                    val pos = localCharacter!!.position
                    playersOnMap[packet.character.id]?.let { playersOnMap[packet.character.id] = it.copy(position = pos) }
                    syncPlayerVisualTarget(
                        id = packet.character.id,
                        px = pos.pixelX,
                        py = pos.pixelY,
                        dir = pos.direction,
                        snapImmediately = false
                    )
                }
            }
            is ServerPacket.PlayerUpdated -> {
                // Our own position comes from CharacterSync, which says which of our steps it includes. This public copy
                // is a round trip old and would pull the predicted player back, so the predicted position is kept.
                val own = localCharacter?.takeIf { it.id == packet.player.characterId && it.position.mapId == packet.player.position.mapId }
                val p = if (own != null) packet.player.copy(position = own.position) else packet.player
                playersOnMap[p.characterId] = p
                syncPlayerVisualTarget(
                    id = p.characterId,
                    px = p.position.pixelX,
                    py = p.position.pixelY,
                    dir = p.position.direction,
                    snapImmediately = p.characterId !in playerVisuals
                )
            }
            is ServerPacket.PlayerLeftMap -> {
                playersOnMap.remove(packet.characterId)
                playerVisuals.remove(packet.characterId)
            }
            is ServerPacket.NpcUpdated -> {
                val n = packet.npc
                val wasAlive = npcsOnMap[n.instanceId]?.isAlive == true
                npcsOnMap[n.instanceId] = n
                if (wasAlive && !n.isAlive) {
                    val npcDef = catalog.npcs.firstOrNull { it.id == n.npcId }
                    val floorBossNpcId = activeMap?.generator?.bossNpcId ?: 0
                    runTracker.onNpcKilled(
                        wasAlive = true,
                        isNowAlive = false,
                        npcDef = npcDef,
                        floorBossNpcId = floorBossNpcId
                    )
                    com.morgana.client.ui.DungeonHudModel.bossDefeatedToast(
                        wasAlive = true,
                        isNowAlive = false,
                        npcId = n.npcId,
                        npcDef = npcDef,
                        floorBossNpcId = floorBossNpcId
                    )?.let { notice ->
                        toasts.add(notice.text, notice.kind, com.morgana.client.ui.ToastClock.now())
                    }
                }
                val ts = (activeMap?.tileSize ?: 32).toFloat()
                if (wasAlive && !n.isAlive && xpOrbsEnabled()) {
                    val npcDef = catalog.npcs.firstOrNull { it.id == n.npcId }
                    if ((npcDef?.xpReward ?: 0L) > 0L) {
                        val spriteH = (gameDefinition?.sprites?.cellHeight ?: activeMap?.tileSize ?: 32).toFloat()
                        xpOrbTracker.onNpcKilled(n.mapId, n.tileX * ts + ts * 0.5f, n.tileY * ts + ts - spriteH * 0.5f)
                    }
                }
                syncNpcVisualTarget(
                    id = n.instanceId,
                    px = n.bodyX(ts.toInt()),
                    py = n.bodyY(ts.toInt()),
                    dir = n.direction,
                    snapImmediately = n.instanceId !in npcVisuals || !n.isAlive
                )
            }
            is ServerPacket.GroundItemsUpdated -> {
                if (activeMap?.id == packet.mapId) {
                    groundItemsOnMap.clear()
                    groundItemsOnMap.addAll(packet.items)
                }
            }
            is ServerPacket.ProjectilesUpdated -> {
                if (activeMap?.id == packet.mapId) {
                    projectilesOnMap.clear()
                    projectilesOnMap.addAll(packet.projectiles)
                }
            }
            is ServerPacket.WorldObjectsUpdated -> {
                if (activeMap?.id == packet.mapId) {
                    packet.removedIds.forEach { worldObjectsOnMap.remove(it) }
                    packet.upserted.forEach { worldObjectsOnMap[it.id] = it }
                }
            }
            is ServerPacket.ResourceNodeUpdated -> {
                if (activeMap?.id == packet.resource.mapId) {
                    resourcesOnMap[TileCoord(packet.resource.tileX, packet.resource.tileY)] = packet.resource
                }
            }
            is ServerPacket.ChatBroadcast -> {
                if (packet.channel == com.morgana.protocol.ChatChannel.BROADCAST) {
                    toasts.add(packet.message, com.morgana.client.ui.ToastKind.BROADCAST, com.morgana.client.ui.ToastClock.now())
                } else if (packet.channel == com.morgana.protocol.ChatChannel.SYSTEM) {
                    runTracker.onSystemNotice(packet.message)
                    val notice = packet.notice
                    // A server that types its notices decides; an older one is guessed at from the words.
                    if (notice != null) toasts.add(packet.message, notice.toToastKind(), com.morgana.client.ui.ToastClock.now())
                    else toasts.offer(packet.message, com.morgana.client.ui.ToastClock.now())
                    com.morgana.client.editor.forms.SaveRejection.parse(packet.message)?.let {
                        lastSaveRejection = it
                        lastSaveRejectionAt = System.currentTimeMillis()
                    }
                    if (packet.message.startsWith("Art import rejected:", ignoreCase = true)) {
                        lastArtImportMessage = packet.message
                        lastArtImportError = true
                    }
                }
                chatHistory.add(packet)
                if (chatHistory.size > 150) {
                    chatHistory.removeAt(0)
                }
                if (packet.showOverheadBubble && packet.senderEntityId.isNotEmpty()) {
                    chatBubbles[packet.senderEntityId] = ActiveChatBubble(
                        senderEntityId = packet.senderEntityId,
                        senderName = packet.senderName,
                        text = ChatFormatting.bubble(packet)
                    )
                }
            }
            is ServerPacket.SpellCooldown -> {
                serverReportsCooldowns = true
                spellCooldowns.set(packet.spellId, packet.durationTicks / SpellCooldowns.TICKS_PER_SECOND, com.morgana.client.ui.ToastClock.now())
            }
            is ServerPacket.FloatingText -> {
                if (activeMap?.id == packet.mapId) {
                    if (packet.category == com.morgana.protocol.FloatingTextCategory.DAMAGE ||
                        packet.category == com.morgana.protocol.FloatingTextCategory.CRIT_DAMAGE
                    ) flinchAt(packet.tileX, packet.tileY)
                    val tileSize = activeMap?.tileSize ?: 32
                    val profile = gameDefinition?.sprites
                    // Text rises above whoever stands on the tile: their visible height, with any draw scale.
                    val onTile = playersOnMap.values
                        .filter { it.position.tileX == packet.tileX && it.position.tileY == packet.tileY }
                        .map { it.spriteId } +
                        npcsOnMap.values.filter { it.isAlive && it.tileX == packet.tileX && it.tileY == packet.tileY }
                            .map { n -> catalog.npcs.firstOrNull { it.id == n.npcId }?.spriteId ?: 0 }
                    val spriteHeight = if (profile == null) tileSize
                    else (onTile.maxOfOrNull { profile.uiHeightOf(it) } ?: profile.cellHeight.toFloat()).toInt()
                    val wx = packet.tileX * tileSize + tileSize * 0.5f
                    val wy = packet.tileY * tileSize + spriteHeight.toFloat()
                    val captured = packet.category == com.morgana.protocol.FloatingTextCategory.XP_GAIN &&
                        xpOrbTracker.onXpFloatingText(packet.mapId, wx, wy, packet.text, packet.colorHex, xpOrbsEnabled())
                    if (!captured) {
                        floatingTexts.add(
                            ActiveFloatingText(
                                mapId = packet.mapId,
                                worldX = wx,
                                worldY = wy,
                                text = packet.text,
                                colorHex = packet.colorHex,
                                crit = packet.category == com.morgana.protocol.FloatingTextCategory.CRIT_DAMAGE
                            )
                        )
                    }
                }
            }
            is ServerPacket.Momentum -> {
                momentum = packet.momentum
                empoweredUntilMs = if (packet.empoweredTicks > 0) System.currentTimeMillis() + packet.empoweredTicks * 50L else 0L
            }
            is ServerPacket.SkillEffect -> {
                if (activeMap?.id == packet.mapId) {
                    skillFx.add(
                        SkillFx(
                            effect = packet.effect, fromX = packet.fromX, fromY = packet.fromY, toX = packet.toX, toY = packet.toY, radius = packet.radius,
                            delaySeconds = packet.delayTicks * SkillFx.TICK_SECONDS, spanSeconds = packet.spanTicks * SkillFx.TICK_SECONDS
                        )
                    )
                    while (skillFx.size > 40) skillFx.removeAt(0)
                    // A leaping skill (the spin) makes its character hop through the air while the server carries them to the spot.
                    if (packet.effect == SkillFx.SPIN && packet.sourceId.isNotEmpty()) {
                        playerVisuals[packet.sourceId]?.let { v ->
                            v.hopTimer = 0f
                            v.hopDuration = maxOf(0.12f, (packet.delayTicks - 1) * SkillFx.TICK_SECONDS)
                            v.hopPeak = 38f
                        }
                    }
                }
            }
            is ServerPacket.TriggerAnimation -> {
                if (activeMap?.id == packet.mapId) {
                    val def = catalog.animations.firstOrNull { it.id == packet.animationId }
                    val flight = launchShotFor(packet.spellId, packet.casterEntityId, packet.tileX, packet.tileY)
                    activeAnimations.add(
                        ActiveSpriteAnimation(
                            mapId = packet.mapId,
                            tileX = packet.tileX,
                            tileY = packet.tileY,
                            animationId = packet.animationId,
                            spriteSheetId = def?.spriteSheetId ?: packet.animationId,
                            frameCount = def?.frameCount ?: AnimationStripLayout.DEFAULT_FRAME_COUNT,
                            frameDurationMs = def?.frameDurationMs ?: AnimationStripLayout.DEFAULT_FRAME_DURATION_MS,
                            renderAboveEntities = def?.renderAboveEntities ?: true,
                            elapsedSeconds = -flight
                        )
                    )
                }
            }
            is ServerPacket.GameArtUpdated -> {
                lastArtImportMessage = "Imported assets/${packet.entry.path}."
                lastArtImportError = false
            }
            is ServerPacket.OpenUiWindow -> onUiWindowRequested(packet)
            is ServerPacket.PluginWindow -> if (packet.window.problem() == null) pluginWindows[packet.window.id] = packet.window
            is ServerPacket.PluginWindowClose -> pluginWindows.remove(packet.windowId)
            is ServerPacket.PluginMessage -> onPluginMessage?.invoke(packet.channel, packet.payload)
            is ServerPacket.Tip -> tips.add(packet)
            is ServerPacket.TradeUpdated -> activeTrade = packet.session
            is ServerPacket.PartyUpdated -> activeParty = packet.party
            is ServerPacket.GuildUpdated -> activeGuild = packet.guild
            is ServerPacket.QuestGuides -> questGuides = packet.guides
            is ServerPacket.StallViewed -> stallView = packet.view
            is ServerPacket.BossTelegraph -> {
                val now = System.currentTimeMillis()
                telegraphs.add(Telegraph(packet.mapId, packet.centerX, packet.centerY, packet.radius, now, now + packet.windUpTicks * 50L, packet.label))
            }
            is ServerPacket.MailInbox -> mailbox = packet.messages
            is ServerPacket.MarketListings -> marketListings = packet.listings
            is ServerPacket.FriendList -> friends = packet.friends
            is ServerPacket.GuildRoster -> guildRoster = packet.members
            is ServerPacket.Exploration -> explored = packet.maps.associateBy { it.mapId }
            is ServerPacket.WorldMapDetail -> worldMapDetail = packet.maps.associateBy { it.mapId }
            is ServerPacket.ExplorationUpdate -> explored = explored + (packet.map.mapId to packet.map)
            is ServerPacket.IgnoreList -> ignored = packet.names
            is ServerPacket.ShopBuyback -> { shopBuybackShopId = packet.shopId; shopBuyback = packet.entries }
            is ServerPacket.MapDefinitionUpdated -> {
                mapCache[packet.map.id] = packet.map
                if (activeMap?.id == packet.map.id) {
                    activeMap = packet.map
                    workingMapDraft = packet.map
                } else if (neighborMaps.containsKey(packet.map.id)) {
                    neighborMaps = neighborMaps + (packet.map.id to packet.map)
                }
            }
            is ServerPacket.ContentCatalogUpdated -> {
                catalog = packet.catalog
                statusMessage = packet.summaryMessage
            }
            is ServerPacket.PlaySound, is ServerPacket.ShowPicture, is ServerPacket.TextInputPrompt -> {} // Handled directly by audio and HUD layers.
            is ServerPacket.Unknown -> {
                // Forward compatibility: packet from a newer server version is safely ignored
            }
        }
    }

    /**
     * How far everything on [old] moves, in pixels, when the player walks into the neighbouring map [new] (so a sprite keeps
     * its place on screen); null if [new] is not a neighbour of [old] (a warp).
     */
    private fun crossingShift(old: MapDefinition, new: MapDefinition): Pair<Float, Float>? {
        val ts = old.tileSize.toFloat()
        val n = old.neighbors
        return when (new.id) {
            n.eastMapId -> -old.width * ts to 0f
            n.westMapId -> new.width * ts to 0f
            n.northMapId -> 0f to new.height * ts
            n.southMapId -> 0f to -old.height * ts
            else -> null
        }
    }

    private fun shiftVisuals(visuals: MutableMap<String, EntityVisualSmoothing>, dx: Float, dy: Float) {
        for (v in visuals.values) {
            v.renderPixelX += dx
            v.targetPixelX += dx
            v.renderPixelY += dy
            v.targetPixelY += dy
        }
    }

    /** Where a neighbouring map's pixel (0,0) sits in the active map's pixel space; null if [mapId] is not a neighbour. */
    private fun seamOffset(active: MapDefinition, mapId: Int): Pair<Float, Float>? {
        if (mapId <= 0) return null
        val ts = active.tileSize.toFloat()
        val n = active.neighbors
        return when (mapId) {
            n.eastMapId -> active.width * ts to 0f
            n.westMapId -> -(neighborMaps[mapId]?.width ?: return null) * ts to 0f
            n.northMapId -> 0f to -(neighborMaps[mapId]?.height ?: return null) * ts
            n.southMapId -> 0f to active.height * ts
            else -> null
        }
    }

    /** Where neighbouring map [mapId]'s pixel (0,0) sits in the active map's pixel space, for drawing its seam entities. */
    fun seamOffsetOf(mapId: Int): Pair<Float, Float>? = activeMap?.let { seamOffset(it, mapId) }

    private fun applySeamView(packet: ServerPacket.SeamView) {
        if (!isModuleEnabled("softSeams")) {
            seamMaps = emptyList()
            seamNpcVisuals.clear()
            seamPlayerVisuals.clear()
            return
        }
        val active = activeMap ?: return
        seamMaps = packet.maps
        val ts = active.tileSize.toFloat()
        val liveNpcs = HashSet<String>()
        val livePlayers = HashSet<String>()
        for (m in packet.maps) {
            val (ox, oy) = seamOffset(active, m.mapId) ?: continue
            for (n in m.npcs) {
                val key = "${m.mapId}:${n.instanceId}"
                liveNpcs.add(key)
                syncSeamVisual(seamNpcVisuals, key, n.bodyX(active.tileSize) + ox, n.bodyY(active.tileSize) + oy, n.direction)
            }
            for (p in m.players) {
                livePlayers.add(p.characterId)
                syncSeamVisual(seamPlayerVisuals, p.characterId, p.position.pixelX + ox, p.position.pixelY + oy, p.position.direction)
            }
        }
        seamNpcVisuals.keys.retainAll(liveNpcs)
        seamPlayerVisuals.keys.retainAll(livePlayers)
    }

    private fun syncSeamVisual(visuals: MutableMap<String, EntityVisualSmoothing>, key: String, px: Float, py: Float, dir: Direction) {
        val existing = visuals[key]
        if (existing == null || hypot((existing.renderPixelX - px).toDouble(), (existing.renderPixelY - py).toDouble()) > 96.0) {
            visuals[key] = EntityVisualSmoothing(px, py, px, py, dir, isMoving = false)
        } else {
            existing.targetPixelX = px
            existing.targetPixelY = py
            existing.direction = dir
        }
    }

    private fun syncPlayerVisualTarget(id: String, px: Float, py: Float, dir: Direction, snapImmediately: Boolean) {
        val existing = playerVisuals[id]
        if (existing != null && !snapImmediately && existing.leapDuration > 0f) {
            // Already mid-leap (e.g. held-direction steps keep arriving): follow the new target without restarting the arc.
            existing.targetPixelX = px
            existing.targetPixelY = py
            existing.direction = dir
            return
        }
        if (existing != null && !snapImmediately) {
            val gap =hypot((existing.renderPixelX - px).toDouble(), (existing.renderPixelY - py).toDouble())
            if (gap > 96.0 && gap <= MAX_LEAP_PIXELS) {
                // A jump on the same map: ease across the gap in a short, fixed time instead of teleporting.
                existing.leapStartX = existing.renderPixelX
                existing.leapStartY = existing.renderPixelY
                existing.leapTimer = 0f
                existing.leapPeakPixels = (10f + gap.toFloat() * 0.1f).coerceAtMost(28f)
                existing.leapDuration = (0.18f + gap.toFloat() / 1000f).coerceAtMost(0.4f)
                existing.targetPixelX = px
                existing.targetPixelY = py
                existing.direction = dir
                return
            }
        }
        if (existing == null || snapImmediately || hypot((existing.renderPixelX - px).toDouble(), (existing.renderPixelY - py).toDouble()) > 96.0) {
            playerVisuals[id] = EntityVisualSmoothing(
                renderPixelX = px,
                renderPixelY = py,
                targetPixelX = px,
                targetPixelY = py,
                direction = dir,
                isMoving = false
            )
        } else {
            existing.targetPixelX = px
            existing.targetPixelY = py
            existing.direction = dir
        }
    }

    private fun syncNpcVisualTarget(id: String, px: Float, py: Float, dir: Direction, snapImmediately: Boolean) {
        val existing = npcVisuals[id]
        if (existing == null || snapImmediately || hypot((existing.renderPixelX - px).toDouble(), (existing.renderPixelY - py).toDouble()) > 96.0) {
            npcVisuals[id] = EntityVisualSmoothing(
                renderPixelX = px,
                renderPixelY = py,
                targetPixelX = px,
                targetPixelY = py,
                direction = dir,
                isMoving = false
            )
        } else {
            existing.targetPixelX = px
            existing.targetPixelY = py
            existing.direction = dir
        }
    }

    /**
     * Whether the local player could step from [from] to [to] on the active map, using the same rules as
     * [predictAndCreateMovePacket]. Used by tap-to-move route planning.
     */
    fun canTapStep(from: TileCoord, to: TileCoord, direction: Direction): Boolean {
        val map = activeMap ?: return false
        if (!map.isInBounds(to)) return false
        val depleted = resourcesOnMap.filterValues { it.depleted }.keys
        val npcBlocked = npcsOnMap.values.any { it.isAlive && it.tileX == to.x && it.tileY == to.y }
        return !npcBlocked && map.canTraverse(from, to, direction, isNpc = false, depletedResources = depleted)
    }

    /** Next [ClientPacket.MoveInput.seq] to send; restarts at 1 for every connection (see [resetMovePrediction]). */
    private var nextMoveSeq = 1

    /** Steps sent but not yet acknowledged by a [ServerPacket.CharacterSync.ackMoveSeq], oldest first. */
    private val unackedMoves = ArrayDeque<ClientPacket.MoveInput>()

    /** Number of sent steps the server has not acknowledged yet (for tests and diagnostics). */
    val unacknowledgedMoveCount: Int get() = unackedMoves.size

    /** Forgets move numbering and unacknowledged steps; call on every new connection (the server's count starts over too). */
    fun resetMovePrediction() {
        nextMoveSeq = 1
        unackedMoves.clear()
    }

    /**
     * Predicts local player movement immediately on the client for zero-latency responsiveness
     * while returning the authoritative [ClientPacket.MoveInput] to dispatch to the server.
     *
     * Each step is numbered and remembered until the server acknowledges it; when a [ServerPacket.CharacterSync]
     * arrives, the client starts from the server's position and replays the steps the server had not handled yet, so
     * network latency does not pull the player back (see `wiki/client/client_state_and_prediction.md`).
     */
    fun predictAndCreateMovePacket(direction: Direction, sprint: Boolean = false): ClientPacket.MoveInput {
        val ch = localCharacter
        val map = activeMap
        // The server turns free pixel movement into grid steps when that module is off; predict the same.
        val mode = movementMode
        // A diagonal covers the same ground per step as a cardinal one (each axis is scaled by 1/sqrt 2).
        val mountPercent = ch?.equipment?.mountSpeedPercent(catalog.items.associateBy { it.id }) ?: 100
        val baseStep = PIXEL_STEP * (mountPercent / 100f) * (if (sprint && canSprint()) (gameDefinition?.sprint?.speedPercent ?: 100) / 100f else 1f)
        val stepPx = if (direction.isDiagonal) baseStep * 0.70710677f else baseStep
        val dxPx = if (mode == MovementMode.SUB_TILE) direction.dx * stepPx else 0f
        val dyPx = if (mode == MovementMode.SUB_TILE) direction.dy * stepPx else 0f
        val packet = ClientPacket.MoveInput(direction = direction, mode = mode, deltaPixelsX = dxPx, deltaPixelsY = dyPx, seq = nextMoveSeq++)

        if (ch != null && map != null && ch.position.mapId == map.id) {
            val nextPos = predictStep(ch.position, packet, map)
            val updatedChar = ch.copy(position = nextPos, movementMode = mode)
            localCharacter = updatedChar
            playersOnMap[ch.id]?.let { snap ->
                playersOnMap[ch.id] = snap.copy(position = nextPos)
            }
            syncPlayerVisualTarget(
                id = ch.id,
                px = nextPos.pixelX,
                py = nextPos.pixelY,
                dir = direction,
                snapImmediately = false
            )
            playerVisuals[ch.id]?.let { vis ->
                vis.isMoving = true
                vis.walkCycleTimer += 0.12f
            }
        }
        unackedMoves.addLast(packet)
        while (unackedMoves.size > MAX_UNACKED_MOVES) unackedMoves.removeFirst()
        return packet
    }

    /** True when the game has sprint rules, free movement is on and the local character has stamina left. */
    fun canSprint(): Boolean {
        val rules = gameDefinition?.sprint?.takeIf { it.enabled } ?: return false
        if (movementMode != MovementMode.SUB_TILE) return false
        return (localCharacter?.vitals?.get(rules.staminaVitalId)?.cur ?: 0) > 0
    }

    /** Where one [step] from [from] lands on [map] under the server's rules (a blocked step only turns the player). */
    private fun predictStep(from: WorldPosition, step: ClientPacket.MoveInput, map: MapDefinition): WorldPosition {
        val ts = map.tileSize
        val direction = step.direction
        val depleted = resourcesOnMap.filterValues { it.depleted }.keys
        return if (step.mode == MovementMode.SUB_TILE) {
            val dxPx = step.deltaPixelsX
            val dyPx = step.deltaPixelsY
            // The moved position for one pixel offset, or null when the tile it lands in cannot be entered.
            fun attempt(ox: Float, oy: Float, dir: Direction): WorldPosition? {
                val candPx = from.pixelX + ox
                val candPy = from.pixelY + oy
                val candTx = kotlin.math.floor((candPx + ts * 0.5f) / ts).toInt()
                val candTy = kotlin.math.floor((candPy + ts * 0.5f) / ts).toInt()
                val candCoord = TileCoord(candTx, candTy)
                val npcBlocked = npcsOnMap.values.any { it.isAlive && it.tileX == candTx && it.tileY == candTy }
                val ok = map.isInBounds(candCoord) &&
                    (candCoord == from.tileCoord ||
                        (map.canTraverse(from.tileCoord, candCoord, dir, isNpc = false, depletedResources = depleted) && !npcBlocked))
                return if (ok) from.copy(tileX = candTx, tileY = candTy, pixelX = candPx, pixelY = candPy, direction = direction) else null
            }
            // Same rule as the server: a blocked diagonal slides along whichever single axis is open.
            attempt(dxPx, dyPx, direction)
                ?: (if (dxPx != 0f && dyPx != 0f) {
                    attempt(dxPx, 0f, if (dxPx < 0f) Direction.LEFT else Direction.RIGHT)
                        ?: attempt(0f, dyPx, if (dyPx < 0f) Direction.UP else Direction.DOWN)
                } else null)
                ?: from.copy(direction = direction)
        } else {
            val targetCoord = from.tileCoord.step(direction)
            val npcBlocked = npcsOnMap.values.any { it.isAlive && it.tileX == targetCoord.x && it.tileY == targetCoord.y }
            if (map.isInBounds(targetCoord) &&
                map.canTraverse(from.tileCoord, targetCoord, direction, isNpc = false, depletedResources = depleted) &&
                !npcBlocked
            ) {
                from.withTile(targetCoord.x, targetCoord.y, ts, direction)
            } else {
                from.copy(direction = direction)
            }
        }
    }

    /**
     * The server's [character] with the steps it had not handled yet (those after [ackMoveSeq]) replayed on top, so the
     * local player stays where the client has already shown it. With no acknowledgement (an older server) the server's
     * state is taken as it is.
     */
    private fun reconcileLocalCharacter(character: CharacterState, ackMoveSeq: Int): CharacterState {
        if (ackMoveSeq <= 0) return character
        while (unackedMoves.isNotEmpty() && unackedMoves.first().seq <= ackMoveSeq) unackedMoves.removeFirst()
        val map = activeMap
        if (unackedMoves.isEmpty() || map == null || map.id != character.position.mapId) return character
        var pos = character.position
        for (step in unackedMoves) pos = predictStep(pos, step, map)
        return character.copy(position = pos)
    }

    /**
     * Plays the [SpriteState.HURT] flinch on whoever stands on the tile where damage text appeared. A character already
     * in an attack, cast or harvest pose keeps it, so a flinch never cuts off its own swing.
     */
    private fun flinchAt(tileX: Int, tileY: Int) {
        for (p in playersOnMap.values) {
            if (p.position.tileX == tileX && p.position.tileY == tileY) {
                playerVisuals[p.characterId]?.takeIf { it.actionState == null }?.triggerAction(SpriteState.HURT, HURT_DURATION)
            }
        }
        for (n in npcsOnMap.values) {
            if (n.tileX == tileX && n.tileY == tileY) {
                npcVisuals[n.instanceId]?.takeIf { it.actionState == null }?.triggerAction(SpriteState.HURT, HURT_DURATION)
            }
        }
    }

    /** Plays a short client-side visual action pose ([SpriteState.ATTACK], [SpriteState.HARVEST], [SpriteState.CAST]) on the local hero. */
    fun triggerLocalAction(state: SpriteState, duration: Float = EntityVisualSmoothing.DEFAULT_ACTION_DURATION) {
        val id = localCharacter?.id ?: return
        playerVisuals[id]?.triggerAction(state, duration)
    }

    private var lastSwingMs = Long.MIN_VALUE / 2

    /** How long the gather pose plays: four frames at about the pace of the attack column. */
    private val PICKUP_POSE_SECONDS = 0.5f

    /**
     * True when a swing may start at [nowMs]: the equipped weapon's attack delay (the same one the server enforces, 20 ticks
     * a second) has passed since the last accepted swing. Mashing Attack inside the delay is then ignored, so the hero does
     * not replay the swing animation for attacks the server will refuse. Records the swing when it returns true.
     */
    fun swingReady(nowMs: Long): Boolean {
        val ch = localCharacter
        val weaponId = ch?.equipment?.getActive(com.morgana.domain.item.EquipmentSlot.WEAPON)?.itemId
        val ticks = weaponId?.let { id -> catalog.items.firstOrNull { it.id == id }?.attackSpeedTicks }
            ?: gameDefinition?.combat?.defaultAttackSpeedTicks ?: 12
        if (nowMs - lastSwingMs < ticks * 50L) return false
        lastSwingMs = nowMs
        return true
    }

    /**
     * Plays the gather pose ([SpriteState.HARVEST]: crouch, reach to the ground, grab, rise) on the hero for a pickup, but only
     * when loot they may take is in reach and no other action pose is already playing. Returns true when it started.
     */
    fun triggerLocalPickup(): Boolean {
        val ch = localCharacter ?: return false
        val vis = playerVisuals[ch.id] ?: return false
        if (vis.actionState != null) return false
        // Only games whose sprite sheets have a harvest column: without one the pose would fall back to a walk cycle.
        if ((gameDefinition?.sprites?.stateFrames?.get(SpriteState.HARVEST) ?: 0) <= 0) return false
        if (!AutoLootModel.lootInReach(groundItemsOnMap, ch.id, ch.position.mapId, ch.position.tileX, ch.position.tileY, gameDefinition?.gameplayTuning?.pickupRangeTiles ?: com.morgana.domain.sim.GROUND_PICKUP_RANGE_TILES)) return false
        vis.triggerAction(SpriteState.HARVEST, PICKUP_POSE_SECONDS)
        return true
    }

    /** Triggers [SpriteState.HARVEST] when facing a resource node, otherwise [SpriteState.ATTACK]. */
    fun triggerLocalAttackOrHarvest() {
        val state = if (facingTarget() == FacingTarget.RESOURCE) SpriteState.HARVEST else SpriteState.ATTACK
        triggerLocalAction(state)
    }

    /** Triggers [SpriteState.HARVEST] if the player is currently interacting with a resource node. */
    fun triggerLocalInteract() {
        if (facingTarget() == FacingTarget.RESOURCE) {
            triggerLocalAction(SpriteState.HARVEST)
        }
    }

    fun updateVisualTimers(deltaSeconds: Float) {
        // Smoothly interpolate player & NPC pixel positions
        val glideSpeed = 220f * deltaSeconds
        for (vis in playerVisuals.values) {
            stepSmoothing(vis, glideSpeed, deltaSeconds)
        }
        for (vis in npcVisuals.values) {
            stepSmoothing(vis, glideSpeed * 0.85f, deltaSeconds)
        }
        for (vis in seamPlayerVisuals.values) {
            stepSmoothing(vis, glideSpeed, deltaSeconds)
        }
        for (vis in seamNpcVisuals.values) {
            stepSmoothing(vis, glideSpeed * 0.85f, deltaSeconds)
        }

        val ftIter = floatingTexts.iterator()
        while (ftIter.hasNext()) {
            val ft = ftIter.next()
            ft.remainingSeconds -= deltaSeconds
            ft.ageSeconds += deltaSeconds
            if (!com.morgana.client.access.Accessibility.reduceMotion) ft.worldY += deltaSeconds * 22f
            if (ft.remainingSeconds <= 0f) ftIter.remove()
        }

        xpOrbTracker.update(
            deltaSeconds = deltaSeconds,
            onOrbArrived = { orb ->
                val text = orb.floatingText ?: return@update
                val ch = localCharacter
                val vis = ch?.let { playerVisuals[it.id] }
                val ts = (activeMap?.tileSize ?: 32).toFloat()
                val spriteH = (gameDefinition?.sprites?.cellHeight ?: activeMap?.tileSize ?: 32).toFloat()
                val px = (vis?.renderPixelX ?: ch?.position?.pixelX ?: orb.startX) + ts * 0.5f
                val py = (vis?.renderPixelY ?: ch?.position?.pixelY ?: orb.startY) + spriteH
                floatingTexts.add(
                    ActiveFloatingText(
                        mapId = orb.mapId,
                        worldX = px,
                        worldY = py,
                        text = text,
                        colorHex = orb.colorHex
                    )
                )
            },
            onFallbackPopup = { mapId, wx, wy, text, colorHex ->
                floatingTexts.add(
                    ActiveFloatingText(
                        mapId = mapId,
                        worldX = wx,
                        worldY = wy,
                        text = text,
                        colorHex = colorHex
                    )
                )
            }
        )

        val cbIter = chatBubbles.entries.iterator()
        while (cbIter.hasNext()) {
            val cb = cbIter.next().value
            cb.remainingSeconds -= deltaSeconds
            if (cb.remainingSeconds <= 0f) cbIter.remove()
        }

        val fxIter = skillFx.iterator()
        while (fxIter.hasNext()) {
            val fx = fxIter.next()
            fx.elapsed += deltaSeconds
            if (fx.finished) fxIter.remove()
        }

        val shotIter = spellShots.iterator()
        while (shotIter.hasNext()) {
            val shot = shotIter.next()
            shot.elapsedSeconds += deltaSeconds
            if (shot.finished) shotIter.remove()
        }

        val animIter = activeAnimations.iterator()
        while (animIter.hasNext()) {
            val anim = animIter.next()
            anim.elapsedSeconds += deltaSeconds
            if (anim.elapsedSeconds >= anim.totalDurationSeconds) animIter.remove()
        }

        // Update player spell cast progress bars
        for (player in playersOnMap.values) {
            if (player.castingSpellId != null && player.castTotalTicks > 0) {
                val totalSec = player.castTotalTicks * 0.05f
                val cur = playerCastProgress.getOrPut(player.characterId) {
                    val initialElapsed = (player.castTotalTicks - player.castRemainingTicks) * 0.05f
                    (initialElapsed / totalSec.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                }
                playerCastProgress[player.characterId] = (cur + (deltaSeconds / totalSec.coerceAtLeast(0.01f))).coerceIn(0f, 1f)
            } else {
                playerCastProgress.remove(player.characterId)
            }
        }
    }

    private fun stepSmoothing(vis: EntityVisualSmoothing, maxStep: Float, deltaSeconds: Float) {
        if (vis.actionState != null) {
            vis.actionTimer += deltaSeconds
            if (vis.actionTimer >= vis.actionDuration) {
                vis.actionState = null
                vis.actionTimer = 0f
                vis.actionDuration = 0f
            }
        }
        if (vis.hopDuration > 0f) {
            vis.hopTimer += deltaSeconds
            if (vis.hopTimer >= vis.hopDuration) { vis.hopDuration = 0f; vis.hopTimer = 0f; vis.hopPeak = 0f }
        }
        if (vis.leapDuration > 0f) {
            vis.leapTimer += deltaSeconds
            val t = (vis.leapTimer / vis.leapDuration).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            vis.renderPixelX = vis.leapStartX + (vis.targetPixelX - vis.leapStartX) * eased
            vis.renderPixelY = vis.leapStartY + (vis.targetPixelY - vis.leapStartY) * eased
            vis.isMoving = true
            vis.walkCycleTimer += deltaSeconds
            if (t >= 1f) {
                vis.leapDuration = 0f
                vis.leapTimer = 0f
                vis.leapPeakPixels = 0f
            }
            return
        }
        val dx = vis.targetPixelX - vis.renderPixelX
        val dy = vis.targetPixelY - vis.renderPixelY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist <= 0.5f) {
            vis.renderPixelX = vis.targetPixelX
            vis.renderPixelY = vis.targetPixelY
            vis.isMoving = false
        } else {
            vis.isMoving = true
            vis.walkCycleTimer += deltaSeconds
            // While a skill's leap hop is in the air the sprite keeps up with the server (which carries the character across in a few
            // ticks) instead of crawling at walking speed, so it arrives with the jump rather than rushing in after it.
            val step = if (vis.hopDuration > 0f) maxOf(maxStep, dist * (deltaSeconds * 28f).coerceAtMost(1f)) else maxStep
            if (dist <= step) {
                vis.renderPixelX = vis.targetPixelX
                vis.renderPixelY = vis.targetPixelY
            } else {
                vis.renderPixelX += (dx / dist) * step
                vis.renderPixelY += (dy / dist) * step
            }
        }
    }

    // -------------------------------------------------------------------------
    // F1 Map Editor Operations: kept as the stable entry points; the rules live in [MapEditSession]
    // -------------------------------------------------------------------------

    private fun editorReady(): Boolean {
        if (editor.draft == null) activeMap?.let { editor.load(it) }
        return editor.draft != null
    }

    fun applyEditorActionAt(tileX: Int, tileY: Int, isRightClick: Boolean = false) {
        if (editorReady()) editor.apply(tileX, tileY, secondary = isRightClick)
    }

    fun paintOnWorkingMap(tileX: Int, tileY: Int, eraseLayer: Boolean = false) {
        if (!editorReady()) return
        if (eraseLayer) editor.eraseAt(tileX, tileY) else editor.paintAt(tileX, tileY)
    }

    fun floodFillWorkingMap(startX: Int, startY: Int) {
        if (editorReady()) editor.floodFillAt(startX, startY)
    }

    /**
     * Returns the furthest non-blocked tile along the local player's facing direction within `jump.maxRangeTiles`,
     * or `null` if jumping is disabled or no valid landing tile exists.
     */
    fun findDirectionalJumpLanding(): TileCoord? {
        val jumpDef = gameDefinition?.jump?.takeIf { it.enabled } ?: return null
        val ch = localCharacter ?: return null
        val map = activeMap ?: return null
        val dir = ch.position.direction
        if (dir.dx == 0 && dir.dy == 0) return null
        return (jumpDef.maxRangeTiles downTo 1).firstNotNullOfOrNull { step ->
            val c = TileCoord(ch.position.tileX + dir.dx * step, ch.position.tileY + dir.dy * step)
            if (map.isInBounds(c) && map.getCell(c.x, c.y)?.attribute !is com.morgana.domain.map.TileAttribute.Blocked) c else null
        }
    }

    /** True when jumping is enabled in the game definition and the local player has enough stamina to jump. */
    fun canAffordJump(): Boolean {
        val jumpDef = gameDefinition?.jump?.takeIf { it.enabled } ?: return false
        val ch = localCharacter ?: return false
        if (jumpDef.staminaCost <= 0) return true
        return (ch.vitals[jumpDef.staminaVitalId]?.cur ?: 0) >= jumpDef.staminaCost
    }
}
