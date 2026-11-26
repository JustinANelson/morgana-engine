package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.chat.ChatStyle
import com.morgana.domain.chat.Listener
import com.morgana.domain.chat.RolePlayChat
import com.morgana.domain.chat.RolePlayChatSettings
import com.morgana.domain.chat.RolePlayLine
import com.morgana.persistence.InstancesConfig
import com.morgana.persistence.RolePlayConfig
import com.morgana.domain.sim.contentMaps
import com.morgana.domain.entity.AccountDocument
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.item.ItemStack
import com.morgana.domain.mail.MailRules
import com.morgana.domain.market.MarketRules
import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.social.FriendRules
import com.morgana.domain.sim.contentMaps
import com.morgana.server.admin.AdminMetricSample
import com.morgana.server.admin.AdminPlayer
import com.morgana.server.admin.AdminResult
import com.morgana.server.admin.AdminStatus
import com.morgana.domain.entity.ExplorationDocument
import com.morgana.domain.explore.Exploration
import com.morgana.domain.explore.ExploredMap
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.social.GuildRules
import com.morgana.domain.social.IgnoreRules
import com.morgana.domain.entity.HotbarSlot
import com.morgana.domain.entity.PartyState
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.Inventory
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.settlePlayersOn
import com.morgana.domain.sim.unloadContentMap
import com.morgana.persistence.ServerConfig
import com.morgana.server.assets.GameAssets
import com.morgana.server.moderation.ModerationService
import com.morgana.server.moderation.SanctionKind
import com.morgana.persistence.SessionConfig
import com.morgana.domain.replay.ReplayLog
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentReport
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.toContentCatalog
import com.morgana.persistence.AccountRepository
import com.morgana.persistence.AddressRange
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerState
import com.morgana.persistence.GameScaffolder
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.TipsAction
import com.morgana.protocol.FloatingTextCategory
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.ExploredMapInfo
import com.morgana.protocol.FriendActionType
import com.morgana.protocol.GuildMemberEntry
import com.morgana.protocol.IgnoreActionType
import com.morgana.protocol.FriendEntry
import com.morgana.protocol.MailActionType
import com.morgana.protocol.MarketActionType
import com.morgana.protocol.MapSummary
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.MapRef
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.PluginWindowDto
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.ServerUiWindowType
import com.morgana.protocol.TradeActionType
import com.morgana.protocol.WorldContentCatalogDto
import com.morgana.script.luak.LuakScriptRuntime
import java.util.UUID

/**
 * Abstraction for an active client connection managed by the server.
 */
interface ClientSession {
    val sessionId: String
    var characterId: String?

    /** True when the client connected from the machine running the server (used only for the first-Owner bootstrap). */
    val isLoopback: Boolean get() = false

    /** The peer's network address, or null for in-process sessions. */
    val remoteInetAddress: java.net.InetAddress? get() = null

    /** True when this connection runs over TLS. */
    val isEncrypted: Boolean get() = false

    fun send(packet: ServerPacket)

    /**
     * Sends [packet] when the caller already has (or will share) its wire form: a broadcast encodes the packet once and every
     * recipient reuses the text. [encoded] is only called by sessions that put text on a wire; in-process sessions ignore it.
     */
    fun sendEncoded(packet: ServerPacket, encoded: () -> String) = send(packet)

    fun close(reason: String = "")
}

private val MODERATION_COMMANDS = setOf("kick", "mute", "unmute", "mutes", "ban", "unban", "bans", "report", "reports", "modlog")

/** Population and tick-time samples kept for the admin panel: one a minute, so three hours. */
private const val MAX_METRIC_SAMPLES = 180

private val MODERATION_USAGE = mapOf(
    "kick" to "/kick <player> [reason]",
    "mute" to "/mute <player> <30m|12h|7d|perm> [reason]",
    "unmute" to "/unmute <player>",
    "ban" to "/ban <player> [30m|12h|7d|perm] [reason]",
    "unban" to "/unban <player>",
    "mutes" to "/mutes", "bans" to "/bans", "report" to "/report <player> <what happened>",
    "reports" to "/reports", "modlog" to "/modlog [player]"
)

/** The server ticks every 50 ms. */
private const val TICKS_PER_SECOND = 20

/** How often (in ticks; 20 is one second) the simulation is told the real-world time for the scheduler. */
private const val WALL_CLOCK_INTERVAL_TICKS = 20L

/** Failed logins allowed per connection before it is closed. */
private const val MAX_AUTH_FAILURES = 5

/** Outcome of a content hot-reload attempt. */
data class ContentReloadResult(
    val applied: Boolean,
    val changed: List<String>,
    val report: ContentReport,
    val summary: String
)

/**
 * Single-threaded authoritative coordinator connecting [MorganaDataStore], [WorldSimulation], and [ClientSession]s.
 */
class MorganaWorldCoordinator(
    val dataStore: MorganaDataStore,
    val simulation: WorldSimulation = WorldSimulation(
        game = GameDefinitionLoader.loadFor(dataStore.gameDir, dataStore.rootDir),
        scripts = LuakScriptRuntime()
    ),
    val hookRegistry: EventHookRegistry = EventHookRegistry(),
    val ownerPolicy: OwnerPolicy = OwnerPolicy(dataStore.rootDir),
    /** Optional-module switches; null reads them from `server.json` in the data directory (default: all on). */
    modules: ModuleFlags? = null,
    /** Packet flood limits; null reads `limits` from `server.json` (default: on). */
    packetLimits: PacketLimitSettings? = null,
    /** The real-world clock the simulation is told about (see [SimInput.WallClock]); tests pass a fixed or stepped one. */
    private val wallClock: java.time.Clock = java.time.Clock.systemUTC()
) : AutoCloseable {
    /**
     * Per-connection flood protection. Time is measured in game ticks (20 per second), so limits are deterministic
     * and tests need no wall clock.
     */
    val limiter: PacketLimiter = PacketLimiter(
        packetLimits ?: ServerConfig.load(dataStore.rootDir).limits?.let { PacketLimitSettings(it.enabled, it.scale) } ?: PacketLimitSettings()
    ) { simulation.currentTick * 50L }

    /** Where abuse (packet floods, repeated failed logins) is reported; the connection gate turns repeats into bans. */
    @Volatile
    var connectionGate: ConnectionGate? = null

    private fun reportAbuse(session: ClientSession, reason: String) {
        val address = session.remoteInetAddress ?: return
        connectionGate?.strike(address, reason)
    }

    /** Closes every live connection from the given ban key (an address, or an IPv6 /64 block). */
    @Synchronized
    fun disconnectBanned(key: String) {
        sessionsById.values.toList()
            .filter { s -> s.remoteInetAddress?.let { ConnectionGate.keyOf(it) } == key }
            .forEach { s ->
                sendSystemMessage(s, "Your address has been temporarily banned for abusive behavior.")
                s.close("IP ban")
            }
    }

    /** Consecutive failed logins per connection; the connection is dropped after [MAX_AUTH_FAILURES]. */
    private val authFailures: MutableMap<String, Int> = linkedMapOf()

    /** Active input recorder (see [startRecording]); null when not recording. */
    private var recorder: InputRecorder? = null

    private val sessionsById: MutableMap<String, ClientSession> = linkedMapOf()
    private val sessionByCharacterId: MutableMap<String, ClientSession> = linkedMapOf()
    /** Movement steps waiting for the rate limit, and the last step number handled, per session id. */
    private val moveQueues: MutableMap<String, MoveInputQueue> = linkedMapOf()
    private val clientCapabilitiesBySession: MutableMap<String, Set<String>> = linkedMapOf()

    /** How many players are logged in right now. A plain size read for status displays, so it may lag a tick behind. */
    val onlineCount: Int get() = sessionByCharacterId.size

    /** Connections that gave the right password and are choosing a character (see [ServerPacket.CharacterList]): session id to account id. */
    private val pendingAccountBySession: MutableMap<String, String> = linkedMapOf()

    /**
     * Per connection, the revision of each map already sent (send-once map cache, see [ProtocolCapabilities.MAP_CACHE]).
     * Only connections that negotiated the capability have an entry. It dies with the connection: a reconnect or resume is a
     * new session, so the client's matching cache is rebuilt from scratch.
     */
    private val mapRevisionsSent: MutableMap<String, MutableMap<Int, Int>> = mutableMapOf()

    /** Which entities across each shared border a player should see (soft seams). */
    private val seamViews: SeamViews by lazy { SeamViews(simulation) { toPublicSnapshot(it) } }

    /** Session resume and timeout settings from `server.json` (defaults when absent). */
    val sessionConfig: SessionConfig = ServerConfig.load(dataStore.rootDir).session ?: SessionConfig()

    /** How far role-play speech carries, from the `roleplay` section of `server.json` (defaults when absent). */
    val rolePlayChat: RolePlayChatSettings = (ServerConfig.load(dataStore.rootDir).roleplay ?: RolePlayConfig()).toSettings()

    /** Bans, mutes, the report queue and the audit log (see [ModerationService]). */
    val moderation: ModerationService = ModerationService(dataStore.rootDir)

    /** New-player tips: once per account, state-driven, sent only to clients that advertise the tips capability. */
    val tips: TipService = TipService(dataStore.accounts, simulation) { charId, packet ->
        val session = sessionByCharacterId[charId]
        val caps = session?.let { clientCapabilitiesBySession[it.sessionId] }
        if (session != null && caps != null && ProtocolCapabilities.TIPS in caps) { session.send(packet); true } else false
    }

    /** The game's own art (`<game>/assets/`), scanned at start and refreshed when an admin imports art live in F1. */
    @Volatile
    var gameAssets: GameAssets = GameAssets.scan(
        dataStore.gameDir,
        simulation.game.definition.id,
        sprites = simulation.game.definition.sprites
    ).also { scan ->
        val log = org.slf4j.LoggerFactory.getLogger("GameAssets")
        scan.errors.forEach { log.warn("{}", it) }
        if (!scan.isEmpty) log.info("Game art: {} file(s) from {}", scan.manifest.assets.size, dataStore.gameDir.resolve(GameAssets.FOLDER))
    }

    /**
     * Where clients download [gameAssets] from (may contain `{host}`), set by the runtime once the HTTP address is known.
     * Null means the art cannot be served, so no manifest is sent and clients use their built-in art.
     */
    @Volatile
    var assetBaseUrl: String? = null

    /** One-time login tickets for automation (see [DevTickets]); null unless the server was started with `--dev-login`. */
    @Volatile
    var devTickets: DevTickets? = null

    /** Tokens that let a dropped client resume its character without the password (see [ResumeTokens]). */
    val resumeTokens: ResumeTokens = ResumeTokens(graceMs = sessionConfig.graceMinutes * 60_000L)

    /** Offloads periodic autosaves and exploration writes from the synchronized tick loop. */
    private val persistenceWriter = AsyncPersistenceWriter(dataStore)

    /** Waits for any queued background persistence writes to finish (useful in tests and shutdown). */
    fun flushPersistence() = persistenceWriter.flush()

    init {
        val serverState = dataStore.loadServerState()
        simulation.worldEpochTicks = serverState.worldEpochTicks
        simulation.worldSwitches.putAll(serverState.worldSwitches)
        simulation.worldVariables.putAll(serverState.worldVariables)
        simulation.worldStringVariables.putAll(serverState.worldStringVariables)
        simulation.scheduleCalendar = com.morgana.persistence.JavaTimeScheduleCalendar
        for (def in simulation.game.definition.schedules) {
            for ((field, message) in simulation.scheduleCalendar.problems(def)) {
                org.slf4j.LoggerFactory.getLogger("Scheduler").warn("Schedule '{}' will never run: {} {}", def.id, field, message)
            }
        }
        simulation.scheduleCursorMillis = serverState.scheduleCursorMillis
        simulation.scheduleLastFired.putAll(serverState.scheduleLastFired)
        simulation.scheduleOneShots.putAll(serverState.scheduleOneShots)
        if (dataStore.gameDir == dataStore.rootDir) {
            GameScaffolder.seedIfEmpty(dataStore.world)
        }
        dataStore.world.loadIntoSimulation(simulation)
        simulation.loadWorldObjects(dataStore.worldObjects.loadAll { org.slf4j.LoggerFactory.getLogger("WorldState").warn(it) })
        dataStore.social.guilds.findAll().forEach { simulation.guilds[it.id] = it }
        simulation.modules = modules ?: ServerConfig.load(dataStore.rootDir).moduleFlags()
        simulation.instanceSettings = (ServerConfig.load(dataStore.rootDir).instances ?: InstancesConfig()).toSettings(TICKS_PER_SECOND)
        loadScripts()
    }

    fun loadScripts() {
        val scriptDirs = listOf(
            dataStore.gameDir.resolve("scripts"),
            dataStore.rootDir.resolve("scripts")
        ).distinct()

        val worldView = simulation.asScriptWorldView()
        for (scriptsDir in scriptDirs) {
            if (java.nio.file.Files.isDirectory(scriptsDir)) {
                java.nio.file.Files.walk(scriptsDir).use { stream ->
                    stream.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".lua") }.forEach { file ->
                        val relPath = scriptsDir.relativize(file).toString().replace('\\', '/')
                        if (!simulation.scripts.hasModule(relPath)) {
                            val source = java.nio.file.Files.readString(file)
                            simulation.scripts.loadModule(relPath, source, worldView)
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts recording every simulation input into a replay log. Only possible on a pristine world (tick 0,
     * nobody online) because a replay must start from the freshly loaded content.
     */
    @Synchronized
    fun startRecording(seed: Long = WorldSimulation.DEFAULT_SEED) {
        check(recorder == null) { "Already recording" }
        check(simulation.currentTick == 0L && simulation.players.isEmpty()) { "Recording must start before the first tick or login" }
        recorder = InputRecorder(simulation, seed)
    }

    /** Stops recording and returns the log, or null if not recording. */
    @Synchronized
    fun stopRecording(): ReplayLog? = recorder?.finish().also { recorder = null }

    /** Applies an input to the simulation, recording it when a recorder is active. */
    private fun runInput(input: SimInput): List<WorldSimulationEvent> =
        recorder?.apply(input) ?: input.applyTo(simulation)

    /**
     * Places a world object as the game would (recorded, announced to the players on the map who draw objects, saved with the map). False when
     * the placement was refused (see `WorldStateSystem`). For tools and tests; players place objects with items.
     */
    @Synchronized
    fun placeWorldObject(
        kind: String, mapId: Int, tileX: Int, tileY: Int,
        ownerCharacterId: String? = null, ownerGuildId: String? = null, data: Map<String, String> = emptyMap()
    ): Boolean {
        val events = runInput(SimInput.PlaceWorldObject(kind, mapId, tileX, tileY, ownerCharacterId, ownerGuildId, data))
        dispatchEvents(events)
        return events.any { it is WorldSimulationEvent.WorldObjectsChanged }
    }

    /** Takes a world object away (recorded and announced). False when there is no such object. */
    @Synchronized
    fun removeWorldObject(id: String, reason: String = "removed"): Boolean {
        val events = runInput(SimInput.RemoveWorldObject(id, reason))
        dispatchEvents(events)
        return events.any { it is WorldSimulationEvent.WorldObjectsChanged }
    }

    /** Replaces a player's state wholesale (recorded so replays reproduce coordinator-side edits). */
    private fun setPlayerState(character: CharacterState) {
        runInput(SimInput.SetPlayerState(character))
    }

    @Synchronized
    fun onSessionConnected(session: ClientSession) {
        sessionsById[session.sessionId] = session
    }

    @Synchronized
    fun onSessionDisconnected(session: ClientSession) {
        sessionsById.remove(session.sessionId)
        moveQueues.remove(session.sessionId)
        clientCapabilitiesBySession.remove(session.sessionId)
        pendingAccountBySession.remove(session.sessionId)
        mapRevisionsSent.remove(session.sessionId)
        authFailures.remove(session.sessionId)
        limiter.forget(session.sessionId)
        resumeTokens.sessionEnded(session.sessionId)
        val charId = session.characterId ?: return
        seamViews.forget(charId)
        // A session that was superseded by a newer login for the same character must not remove that character.
        if (sessionByCharacterId[charId] !== session) return
        sessionByCharacterId.remove(charId)
        questGuideKeys.remove(charId)
        viewerAreas.remove(charId)
        lastEntityTile.remove("p:$charId")
        tips.forget(charId)
        pluginWindows.remove(charId)
        val player = simulation.players[charId]
        if (player != null) {
            persistenceWriter.saveCharacterNow(player)
            val combatTicks = sessionConfig.combatLogSeconds * TICKS_PER_SECOND.toLong()
            if (combatTicks > 0 && simulation.inPvpCombat(charId, combatTicks)) {
                // Left a PvP fight: stay in the world as a ghost (no session) so the other side can finish it.
                ghostRemoveAtTick[charId] = simulation.currentTick + combatTicks
                org.slf4j.LoggerFactory.getLogger("CombatLog").info("{} disconnected in PvP combat; kept in the world for {} s", player.name, sessionConfig.combatLogSeconds)
            } else {
                removeFromWorld(player)
            }
        }
    }

    /** Characters whose player disconnected in PvP combat and who are still standing in the world, with the tick they are removed at. */
    private val ghostRemoveAtTick: MutableMap<String, Long> = linkedMapOf()

    /** The ids of characters currently kept in the world after their player disconnected in PvP combat. */
    fun ghostCharacterIds(): Set<String> = ghostRemoveAtTick.keys.toSet()

    /** Saves a character, tells plugins they left, and removes them from the world (flushing any non-choice pending event). */
    private fun removeFromWorld(player: CharacterState) {
        val live = simulation.players[player.id] ?: return
        val events = runInput(SimInput.RemovePlayer(live.id))
        val finalState = events.filterIsInstance<WorldSimulationEvent.CharacterUpdated>()
            .lastOrNull { it.character.id == live.id }?.character ?: live
        persistenceWriter.saveCharacterNow(finalState)
        hookRegistry.dispatchLogout(finalState, simulation)
        notifyFriendWatchers(finalState.name, false, finalState.id)
        exploration.remove(finalState.id)?.let { persistenceWriter.saveExplorationNow(it) }
        dispatchEvents(events)
        broadcastToMap(live.position.mapId, ServerPacket.PlayerLeftMap(live.id, live.position.mapId))
        if (finalState.position.mapId != live.position.mapId) {
            broadcastToMap(finalState.position.mapId, ServerPacket.PlayerLeftMap(live.id, finalState.position.mapId))
        }
    }

    /** Removes ghosts whose time is up (or all of them with [all], at shutdown). */
    private fun expireGhosts(all: Boolean = false) {
        if (ghostRemoveAtTick.isEmpty()) return
        val due = ghostRemoveAtTick.filterValues { all || it <= simulation.currentTick }.keys.toList()
        for (id in due) {
            ghostRemoveAtTick.remove(id)
            simulation.players[id]?.let { removeFromWorld(it) }
        }
    }

    @Synchronized
    fun stepServerTick(deltaMs: Int = 50) {
        val startedNanos = System.nanoTime()
        val events = runInput(SimInput.Tick(deltaMs))
        dispatchEvents(events)
        if (simulation.currentTick % WALL_CLOCK_INTERVAL_TICKS == 0L) reportWallClock()
        applyQueuedMoves()
        expireGhosts()
        if (simulation.currentTick % SeamViews.INTERVAL_TICKS == 0L) sendSeamViews()

        // Periodically auto-save online characters and server state every 200 ticks (10 seconds) off-lock.
        if (simulation.currentTick % 200L == 0L) {
            persistenceWriter.enqueueAutosave(
                simulation.players.values.toList(),
                currentServerStateSnapshot()
            )
        }
        saveChangedWorldObjects(everyTicks = 200L)
        if (simulation.currentTick % 1200L == 0L) expireMarketListings()
        if (simulation.currentTick % 10L == 0L) updateExploration()
        recordTick(startedNanos)
    }

    /** Saves the maps whose world objects changed, off the tick thread (checked every [everyTicks] ticks; 0 saves now, synchronously). */
    private fun saveChangedWorldObjects(everyTicks: Long, synchronous: Boolean = false) {
        if (simulation.dirtyWorldObjectMaps.isEmpty()) return
        if (!synchronous && simulation.currentTick % everyTicks != 0L) return
        for (mapId in simulation.dirtyWorldObjectMaps.toList()) {
            val snapshot = simulation.worldObjectsOn(mapId).toList()
            if (synchronous) persistenceWriter.saveWorldObjectsNow(mapId, snapshot) else persistenceWriter.enqueueWorldObjects(mapId, snapshot)
        }
        simulation.dirtyWorldObjectMaps.clear()
    }

    /**
     * Tells the simulation the real-world time, as a recorded input (time is an input, so a replay raises the same scheduled events).
     * When the scheduler raised something, the server state is saved at once so a crash cannot start the same occurrence twice.
     */
    private fun reportWallClock() {
        val events = runInput(SimInput.WallClock(wallClock.millis()))
        if (events.isEmpty()) return
        dispatchEvents(events)
        if (events.any { it is WorldSimulationEvent.ScheduleTriggered }) saveServerState()
    }

    /** Applies one waiting movement step per session whose rate limit allows it this tick (oldest session first). */
    private fun applyQueuedMoves() {
        if (moveQueues.isEmpty()) return
        for ((sessionId, queue) in moveQueues.entries.toList()) {
            val head = queue.peek() ?: continue
            val charId = sessionsById[sessionId]?.characterId
            if (charId == null || simulation.players[charId] == null) {
                queue.clear() // logged out or left the world: the steps no longer have a player to move
                continue
            }
            queue.pollReady(simulation.moveAllowedNow(charId, head.mode))?.let { applyMove(charId, queue, it) }
        }
    }

    private fun applyMove(charId: String, queue: MoveInputQueue, step: ClientPacket.MoveInput) {
        // Marked first, so the CharacterSync this step produces already acknowledges it.
        queue.markHandled(step)
        dispatchEvents(runInput(SimInput.Move(charId, step.direction, step.mode, step.deltaPixelsX, step.deltaPixelsY)))
    }

    /** The owner's character sync, carrying the last movement step its session has had handled. */
    private fun characterSync(character: CharacterState): ServerPacket.CharacterSync =
        ServerPacket.CharacterSync(
            character,
            ackMoveSeq = sessionByCharacterId[character.id]?.let { moveQueues[it.sessionId]?.lastHandledSeq } ?: 0
        )

    private fun currentServerStateSnapshot(): ServerState =
        ServerState(
            worldEpochTicks = simulation.effectiveWorldTick(),
            worldSwitches = simulation.worldSwitches.toMap(),
            worldVariables = simulation.worldVariables.toMap(),
            worldStringVariables = simulation.worldStringVariables.toMap(),
            scheduleCursorMillis = simulation.scheduleCursorMillis,
            scheduleLastFired = simulation.scheduleLastFired.toMap(),
            scheduleOneShots = simulation.scheduleOneShots.toMap()
        )

    /** Saves runtime server state (e.g. cumulative world epoch ticks) to `<data dir>/server-state.json`. */
    @Synchronized
    fun saveServerState() {
        persistenceWriter.saveServerStateNow(currentServerStateSnapshot())
    }

    @Synchronized
    override fun close() {
        expireGhosts(all = true)
        saveChangedWorldObjects(everyTicks = 0L, synchronous = true)
        saveServerState()
        persistenceWriter.close()
    }

    /**
     * Result of the expensive PBKDF2 work for a login, computed outside the coordinator lock.
     * [storedHash] is the hash it was verified against; it is only trusted if the account still holds it.
     */
    class CredentialCheck(val storedHash: String?, val verified: Boolean, val freshHash: String?)

    /**
     * Entry point for transport threads: does password hashing/verification WITHOUT holding the
     * coordinator lock (only a brief locked lookup), then applies the packet under the lock.
     */
    fun handleClientPacketOffLock(session: ClientSession, packet: ClientPacket) {
        // Admit before any password hashing: a flood of logins must not be able to burn CPU.
        if (!admit(session, packet)) return
        if (packet !is ClientPacket.Authenticate || packet.protocolVersion != PROTOCOL_VERSION ||
            packet.password.length > InputLimits.MAX_PASSWORD
        ) {
            handleClientPacket(session, packet, null)
            return
        }
        val storedHash = lookupPasswordHash(packet.username.trim())
        val check = if (storedHash != null) {
            val ok = AccountRepository.verifyPassword(packet.password, storedHash)
            val fresh = if (ok && AccountRepository.passwordNeedsUpgrade(storedHash)) {
                AccountRepository.hashPassword(packet.password)
            } else null
            CredentialCheck(storedHash, ok, fresh)
        } else {
            CredentialCheck(null, false, if (packet.registerIfNew) AccountRepository.hashPassword(packet.password) else null)
        }
        handleClientPacket(session, packet, check)
    }

    @Synchronized
    private fun lookupPasswordHash(username: String): String? =
        if (username.length in 2..20) dataStore.accounts.findByUsername(username)?.passwordHash else null

    /** Entry point for tests and in-process callers; network threads use [handleClientPacketOffLock]. */
    fun handleClientPacket(session: ClientSession, packet: ClientPacket) {
        if (admit(session, packet)) handleClientPacket(session, packet, null)
    }

    /** Applies the flood limits; returns false (after notifying or disconnecting the player) if the packet must be ignored. */
    private fun admit(session: ClientSession, packet: ClientPacket): Boolean {
        val decision = limiter.admit(session.sessionId, session.characterId != null, packet)
        if (decision.allowed) return true
        decision.notice?.let { sendSystemMessage(session, it) }
        if (decision.verdict == LimitVerdict.KICK) {
            org.slf4j.LoggerFactory.getLogger("Security").warn(
                "Disconnecting session {} ({}): {} flood", session.sessionId, session.characterId ?: "not logged in", decision.packetClass.label
            )
            reportAbuse(session, "packet flood")
            session.close("Packet flood")
        }
        return false
    }

    @Synchronized
    private fun handleClientPacket(session: ClientSession, packet: ClientPacket, check: CredentialCheck?) {
        when (packet) {
            is ClientPacket.Authenticate -> handleAuthentication(session, packet, check)
            is ClientPacket.ResumeSession -> handleResume(session, packet)
            is ClientPacket.ChooseCharacter, is ClientPacket.CreateCharacter, is ClientPacket.DeleteCharacter -> handleCharacterRequest(session, packet)
            is ClientPacket.Ping -> session.send(ServerPacket.Pong(packet.clientTimestampMs, simulation.effectiveWorldTick()))
            is ClientPacket.RequestMapResync -> resyncMaps(session)
            is ClientPacket.RequestWorldMap -> sendWorldMapDetail(session)
            is ClientPacket.RequestRealmInfo -> session.send(
                ServerPacket.RealmInfo(
                    gameId = simulation.game.definition.id,
                    gameName = simulation.game.definition.name,
                    classes = realmClassSummaries()
                )
            )
            else -> {
                val charId = session.characterId ?: return
                handleAuthenticatedPacket(session, charId, packet)
            }
        }
    }

    /** Sends a failed-login result and drops the connection after too many consecutive failures. */
    private fun rejectLogin(session: ClientSession, message: String) {
        val failures = (authFailures[session.sessionId] ?: 0) + 1
        authFailures[session.sessionId] = failures
        if (failures >= MAX_AUTH_FAILURES) {
            session.send(ServerPacket.AuthResult(false, "Too many failed login attempts. Disconnecting."))
            reportAbuse(session, "repeated failed logins")
            session.close("Too many failed login attempts")
        } else {
            session.send(ServerPacket.AuthResult(false, message))
        }
    }

    private fun handleAuthentication(session: ClientSession, packet: ClientPacket.Authenticate, check: CredentialCheck? = null) {
        clientCapabilitiesBySession[session.sessionId] = packet.capabilities.toSet()
        if (packet.protocolVersion != PROTOCOL_VERSION) {
            val theirs = if (packet.protocolVersion == 0) "no protocol version" else "protocol ${packet.protocolVersion}"
            session.send(
                ServerPacket.AuthResult(
                    success = false,
                    message = "Protocol mismatch: this server speaks protocol $PROTOCOL_VERSION but your client sent $theirs. Please update your client.",
                    serverProtocolVersion = PROTOCOL_VERSION
                )
            )
            return
        }
        if (session.characterId != null) {
            // One login per connection: otherwise a single socket could register accounts endlessly.
            session.send(ServerPacket.AuthResult(false, "Already logged in on this connection."))
            return
        }
        if (packet.password.length > InputLimits.MAX_PASSWORD) {
            session.send(ServerPacket.AuthResult(false, "Password must be at most ${InputLimits.MAX_PASSWORD} characters."))
            return
        }
        val cleanUser = packet.username.trim()
        if (cleanUser.length !in 2..20) {
            session.send(ServerPacket.AuthResult(false, "Username must be 2â€“20 characters."))
            return
        }
        var account = dataStore.accounts.findByUsername(cleanUser)
        val supportsCharacterSelect =
            sessionConfig.maxCharacters > 1 &&
                clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.CHARACTER_SELECT) == true

        if (account == null) {
            if (!packet.registerIfNew) {
                rejectLogin(session, "Account not found.")
                return
            }
            val grantOwner = ownerPolicy.grantsOwnerOnRegistration(
                username = cleanUser,
                firstAccount = dataStore.accounts.count() == 0,
                loopbackConnection = session.isLoopback
            )
            val initialAccess = if (grantOwner) AccessLevel.OWNER else AccessLevel.PLAYER
            val newAccId = "acc-${UUID.randomUUID().toString().take(8)}"
            val passwordHash = check?.takeIf { it.storedHash == null }?.freshHash
                ?: AccountRepository.hashPassword(packet.password)

            if (supportsCharacterSelect) {
                account = AccountDocument(
                    id = newAccId,
                    username = cleanUser,
                    passwordHash = passwordHash,
                    accessLevel = initialAccess,
                    characterIds = emptyList()
                )
                dataStore.accounts.save(account)
            } else {
                val newCharId = "char-${UUID.randomUUID().toString().take(8)}"
                val classDef = simulation.classes[packet.requestedClassId] ?: simulation.classes.values.first()
                val newChar = buildNewCharacter(newCharId, newAccId, cleanUser, classDef, packet.requestedSpriteId, initialAccess)

                account = AccountDocument(
                    id = newAccId,
                    username = cleanUser,
                    passwordHash = passwordHash,
                    accessLevel = initialAccess,
                    characterIds = listOf(newCharId)
                )
                // Account and first character are created together or not at all.
                val newAccount = account
                dataStore.inTransaction {
                    dataStore.accounts.save(newAccount)
                    dataStore.characters.save(newChar)
                }
            }
        } else {
            // Reuse the off-lock result only if the account's hash is unchanged since it was computed.
            val trusted = check?.takeIf { it.storedHash == account.passwordHash }
            val verified = trusted?.verified ?: AccountRepository.verifyPassword(packet.password, account.passwordHash)
            if (!verified) {
                rejectLogin(session, "Invalid password.")
                return
            }
            banMessage(account)?.let { why ->
                session.send(ServerPacket.AuthResult(false, why))
                return
            }
            var updated = account
            // Transparently upgrade legacy (unsalted SHA-256) or weaker hashes now that we hold the plaintext.
            if (AccountRepository.passwordNeedsUpgrade(account.passwordHash)) {
                updated = updated.copy(
                    passwordHash = trusted?.freshHash ?: AccountRepository.hashPassword(packet.password)
                )
            }
            // Operators can promote an existing account by listing it as an owner.
            if (updated.accessLevel != AccessLevel.OWNER && ownerPolicy.isConfiguredOwner(cleanUser)) {
                updated = updated.copy(accessLevel = AccessLevel.OWNER)
            }
            if (updated != account) {
                dataStore.accounts.save(updated)
                account = updated
            }
        }
        // Clients that support character select pick or create a character (including right after registration).
        if (supportsCharacterSelect) {
            authFailures.remove(session.sessionId)
            offerCharacters(session, account)
            return
        }
        completeLogin(session, account)
    }

    /** The classes a new character can have, as the realm info and the character list show them. */
    private fun realmClassSummaries(): List<com.morgana.protocol.realms.RealmClassSummary> =
        simulation.classes.values.sortedBy { it.id }.map {
            com.morgana.protocol.realms.RealmClassSummary(
                id = it.id,
                name = it.name,
                description = it.description,
                spriteId = it.spriteIds.firstOrNull() ?: 1
            )
        }

    /** A fresh level-1 character of [classDef] with the class's starting items, spells and position. */
    private fun buildNewCharacter(
        id: String, accountId: String, name: String, classDef: com.morgana.domain.content.ClassDefinition,
        requestedSpriteId: Int, access: AccessLevel
    ): CharacterState {
        var inv = Inventory().grownTo(simulation.game.definition.inventorySlots)
        val equippedMap = mutableMapOf<com.morgana.domain.item.EquipmentSlot, ItemStack>()
        for (stack in classDef.startingItems) {
            val itemDef = simulation.items[stack.itemId] ?: continue
            val equipSlot = itemDef.equipmentSlotOrNull()
            val canAutoEquip = classDef.autoEquipStartingItems &&
                equipSlot != null && simulation.game.definition.hasSlot(equipSlot) && equipSlot !in equippedMap &&
                (!itemDef.twoHanded || com.morgana.domain.item.EquipmentSlot.SHIELD !in equippedMap) &&
                (equipSlot != com.morgana.domain.item.EquipmentSlot.SHIELD || equippedMap[com.morgana.domain.item.EquipmentSlot.WEAPON]?.let { simulation.items[it.itemId]?.twoHanded != true } != false)
            if (canAutoEquip) {
                equippedMap[equipSlot] = stack.copy(amount = 1)
                if (stack.amount > 1) {
                    inv = inv.addItem(itemDef, stack.amount - 1).updatedInventory
                }
            } else {
                inv = inv.addItem(itemDef, stack.amount).updatedInventory
            }
        }
        val baseAttrs = simulation.game.definition.attributes.associate { a ->
            a.id to (classDef.baseAttributes[a.id] ?: a.base)
        }
        return CharacterState(
            id = id,
            accountId = accountId,
            name = name,
            classId = classDef.id,
            spriteId = if (requestedSpriteId > 0) requestedSpriteId else classDef.spriteIds.firstOrNull() ?: 1,
            accessLevel = access,
            attributes = baseAttrs,
            position = WorldPosition.fromTile(classDef.startMapId, classDef.startX, classDef.startY, Direction.DOWN),
            inventory = inv,
            equipment = EquipmentLoadout(equippedMap),
            knownSpellIds = classDef.startingSpellIds,
            hotbar = List(10) { HotbarSlot(it) }
        ).recalculateVitals(simulation.game, simulation.items, refill = true)
    }

    /** Tells a signed-in-but-not-yet-playing connection which characters the account has. [message] explains a refused request. */
    private fun offerCharacters(session: ClientSession, account: AccountDocument, message: String = "") {
        pendingAccountBySession[session.sessionId] = account.id
        val slots = account.characterIds.mapNotNull { dataStore.characters.findById(it) }.map {
            com.morgana.protocol.CharacterSlot(it.id, it.name, it.classId, simulation.classes[it.classId]?.name ?: "Adventurer", it.level, it.spriteId)
        }
        session.send(ServerPacket.CharacterList(slots, sessionConfig.maxCharacters, realmClassSummaries(), message))
    }

    /** Choose, create or delete a character of the account this connection just signed in to. */
    private fun handleCharacterRequest(session: ClientSession, packet: ClientPacket) {
        val accountId = pendingAccountBySession[session.sessionId]
        val account = accountId?.let { dataStore.accounts.findById(it) }
        if (account == null || session.characterId != null) {
            session.send(ServerPacket.AuthResult(false, "Sign in first."))
            return
        }
        banMessage(account)?.let { why ->
            pendingAccountBySession.remove(session.sessionId)
            session.send(ServerPacket.AuthResult(false, why))
            return
        }
        when (packet) {
            is ClientPacket.ChooseCharacter -> {
                val chosen = if (packet.characterId in account.characterIds) dataStore.characters.findById(packet.characterId) else null
                when {
                    chosen == null -> offerCharacters(session, account, "That character was not found.")
                    chosen.fallen -> offerCharacters(session, account, "${chosen.name} has fallen permanently and cannot re-enter the world.")
                    else -> completeLogin(session, account, packet.characterId)
                }
            }
            is ClientPacket.CreateCharacter -> {
                val name = com.morgana.protocol.CharacterNames.clean(packet.name)
                val classDef = simulation.classes[packet.classId]
                val problem = com.morgana.protocol.CharacterNames.problem(name) ?: when {
                    account.characterIds.size >= sessionConfig.maxCharacters ->
                        "You already have the most characters this realm allows (${sessionConfig.maxCharacters})."
                    dataStore.characters.findByName(name) != null ||
                        dataStore.accounts.findByUsername(name)?.let { it.id != account.id } == true -> "That name is taken."
                    classDef == null -> "Pick a class."
                    else -> null
                }
                if (problem != null || classDef == null) {
                    offerCharacters(session, account, problem ?: "Pick a class.")
                    return
                }
                val newId = "char-${UUID.randomUUID().toString().take(8)}"
                val character = buildNewCharacter(newId, account.id, name, classDef, packet.spriteId, account.accessLevel)
                val updated = account.copy(characterIds = account.characterIds + newId)
                dataStore.inTransaction {
                    dataStore.characters.save(character)
                    dataStore.accounts.save(updated)
                }
                completeLogin(session, updated, newId)
            }
            is ClientPacket.DeleteCharacter -> {
                val stored = if (packet.characterId in account.characterIds) dataStore.characters.findById(packet.characterId) else null
                val problem = when {
                    stored == null -> "That character was not found."
                    !stored.name.equals(packet.confirmName.trim(), ignoreCase = true) -> "Type the character's name to confirm."
                    sessionByCharacterId.containsKey(stored.id) || simulation.players.containsKey(stored.id) -> "That character is in the world right now."
                    stored.guildId != null -> "Leave the guild before deleting this character."
                    else -> null
                }
                if (problem != null || stored == null) {
                    offerCharacters(session, account, problem ?: "That character was not found.")
                    return
                }
                val updated = account.copy(characterIds = account.characterIds - stored.id)
                persistenceWriter.invalidateCharacter(stored.id)
                dataStore.inTransaction {
                    dataStore.characters.delete(stored.id)
                    dataStore.social.exploration.deleteById(stored.id)
                    dataStore.accounts.save(updated)
                }
                org.slf4j.LoggerFactory.getLogger("Accounts").info("{} deleted character {} ({})", account.username, stored.name, stored.id)
                offerCharacters(session, updated, "Deleted ${stored.name}.")
            }
            else -> {}
        }
    }

    /** Lets a client whose connection dropped return to its character with the token from its last login or resume. */
    private fun handleResume(session: ClientSession, packet: ClientPacket.ResumeSession) {
        clientCapabilitiesBySession[session.sessionId] = packet.capabilities.toSet()
        if (packet.protocolVersion != PROTOCOL_VERSION) {
            session.send(ServerPacket.AuthResult(false, "Protocol mismatch: please update your client.", serverProtocolVersion = PROTOCOL_VERSION))
            return
        }
        if (session.characterId != null) {
            session.send(ServerPacket.AuthResult(false, "Already logged in on this connection."))
            return
        }
        val tickets = devTickets
        if (tickets != null && packet.token.startsWith(DevTickets.PREFIX)) {
            // A one-time dev ticket stands in for the password; it is never accepted unless the operator started the server with --dev-login.
            val ticketAccount = tickets.redeem(packet.token)?.let { dataStore.accounts.findByUsername(it) }
            if (ticketAccount == null) {
                rejectLogin(session, "That login ticket is invalid, expired or already used.")
                return
            }
            banMessage(ticketAccount)?.let { why ->
                session.send(ServerPacket.AuthResult(false, why))
                return
            }
            org.slf4j.LoggerFactory.getLogger("Security").warn("Dev ticket login for account '{}' from {}", ticketAccount.username, session.sessionId)
            // Automation and developers get no tip windows (they only cover the screen).
            tips.quietAccount(ticketAccount.id)
            completeLogin(session, ticketAccount, null)
            return
        }
        if (!sessionConfig.resume) {
            rejectLogin(session, "Session resume is disabled on this server. Please log in again.")
            return
        }
        val redeemed = resumeTokens.redeemEntry(packet.token)
        val account = redeemed?.let { dataStore.accounts.findById(it.accountId) }
        if (account == null) {
            rejectLogin(session, "Your session expired. Please log in again.")
            return
        }
        banMessage(account)?.let { why ->
            session.send(ServerPacket.AuthResult(false, why))
            return
        }
        completeLogin(session, account, redeemed.characterId)
    }

    /** Ends any older session still holding [characterId] (a dead connection nobody has noticed yet, or another device). */
    private fun evictExistingSession(characterId: String, replacement: ClientSession) {
        val old = sessionByCharacterId[characterId] ?: return
        if (old === replacement) return
        old.send(ServerPacket.SessionEnded("Signed in from another location.", resumable = false))
        // Save and remove the character exactly as a normal disconnect would, then detach the old session from it.
        onSessionDisconnected(old)
        old.characterId = null
        old.close("Signed in from another location")
    }

    /** Spawns the character for an authenticated [account] on [session] and sends the login result. */
    private fun completeLogin(session: ClientSession, account: AccountDocument, requestedCharacterId: String? = null) {
        authFailures.remove(session.sessionId)
        pendingAccountBySession.remove(session.sessionId)

        val characterId = requestedCharacterId?.takeIf { it in account.characterIds } ?: account.characterIds.firstOrNull()
        val storedChar = characterId?.let { dataStore.characters.findById(it) }
        if (storedChar == null) {
            session.send(ServerPacket.AuthResult(false, "This account has no character to play. Sign in with a client that can create one."))
            return
        }
        val syncedChar = storedChar.copy(accessLevel = account.accessLevel)

        // One character per account in the world at a time: a second device playing another character replaces the first.
        for (otherId in sessionByCharacterId.keys.toList()) {
            if (otherId != syncedChar.id && simulation.players[otherId]?.accountId == account.id) evictExistingSession(otherId, session)
        }
        evictExistingSession(syncedChar.id, session)
        session.characterId = syncedChar.id
        sessionByCharacterId[syncedChar.id] = session

        // A player who dropped out of a PvP fight is still standing in the world: pick that character up where it is.
        val reattached = ghostRemoveAtTick.remove(syncedChar.id) != null && simulation.players.containsKey(syncedChar.id)
        val spawnEvents = if (reattached) emptyList() else runInput(SimInput.SpawnPlayer(syncedChar))
        val liveChar = simulation.players[syncedChar.id] ?: syncedChar

        val activeCapabilities = buildList {
            if (simulation.modules.isEnabled(OptionalModule.SOFT_SEAMS)) add(ProtocolCapabilities.SOFT_SEAMS)
            if (sessionConfig.resume) add(ProtocolCapabilities.SESSION_RESUME)
            add(ProtocolCapabilities.REALM_INFO)
            add(ProtocolCapabilities.MAP_CACHE)
            if (simulation.modules.isEnabled(OptionalModule.ROLE_PLAY_CHAT)) add(ProtocolCapabilities.RP_CHAT)
            // Only this server's own art endpoint can batch; a "{host}" template means the art is served from here.
            if (assetBaseUrl?.contains("{host}") == true) add(ProtocolCapabilities.ASSET_BATCH)
        }

        val artV2 = clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.ART_V2) == true
        val clientManifest = gameAssets.manifestFor(artV2)
        session.send(
            ServerPacket.AuthResult(
                success = true,
                message = "Welcome to Morgana ORPG, ${liveChar.name}! (Access: ${liveChar.accessLevel.title})" +
                    if (session.isEncrypted) " [encrypted]" else "",
                character = liveChar,
                catalog = buildCatalogDto(),
                gameDefinition = simulation.game.definition,
                disabledModules = simulation.modules.disabledKeys(),
                assetManifest = assetBaseUrl?.let { clientManifest.takeIf { it.assets.isNotEmpty() } },
                assetBaseUrl = assetBaseUrl?.takeIf { clientManifest.assets.isNotEmpty() },
                resumeToken = if (sessionConfig.resume) resumeTokens.issue(account.id, session.sessionId, syncedChar.id) else null,
                serverTick = simulation.effectiveWorldTick(),
                capabilities = activeCapabilities
            )
        )
        if (!session.isEncrypted && !session.isLoopback) {
            org.slf4j.LoggerFactory.getLogger("Security").warn(
                "{} logged in over an UNENCRYPTED connection; their password crossed the network in clear text. Enable tls in server.json.",
                liveChar.name
            )
        }
        sendFullMapSnapshotTo(session, liveChar.position.mapId)
        dispatchEvents(spawnEvents)
        if (reattached) sendSystemMessage(session, "You returned to the world while still in a fight: your character stayed where it was.")
        hookRegistry.dispatchLogin(liveChar, simulation)
        if (!reattached) tips.onLogin(liveChar.id)
        announceMailOnLogin(session, liveChar.id)
        announceFriendsOnLogin(session, liveChar)
        announceGuildOnLogin(session, liveChar)
        questGuideKeys.remove(liveChar.id)
        sendQuestGuides(liveChar)
        announceExplorationOnLogin(session, liveChar)
    }

    /** The optional module a packet belongs to, or null for Core packets. */
    private fun moduleOf(packet: ClientPacket): OptionalModule? = when (packet) {
        is ClientPacket.PartyAction -> OptionalModule.PARTIES
        is ClientPacket.GuildAction -> OptionalModule.GUILDS
        is ClientPacket.SendMail, is ClientPacket.MailAction -> OptionalModule.MAIL
        is ClientPacket.FriendAction -> OptionalModule.FRIENDS
        is ClientPacket.MarketAction -> OptionalModule.MARKET
        is ClientPacket.IgnoreAction -> OptionalModule.IGNORE
        is ClientPacket.TradeAction -> OptionalModule.TRADING
        is ClientPacket.CraftRecipe -> OptionalModule.CRAFTING
        is ClientPacket.SendChat -> when (packet.channel) {
            ChatChannel.PARTY -> OptionalModule.PARTIES
            ChatChannel.GUILD -> OptionalModule.GUILDS
            else -> null
        }
        else -> null
    }

    /** The request as a plugin sees it, or null for packets plugins are not asked about (movement, UI plumbing, slash commands). */
    private fun playerActionOf(player: CharacterState, packet: ClientPacket): PlayerAction? {
        fun itemIn(slot: Int): Int? = player.inventory.slots.getOrNull(slot)?.itemId
        return when (packet) {
            is ClientPacket.SendChat -> packet.message.trim().takeIf { it.isNotEmpty() }?.let { text ->
                if (text.startsWith("/")) rolePlayCommandOf(text)?.let { PlayerAction.Chat(channelFor(it.style), it.text) }
                else PlayerAction.Chat(packet.channel, text)
            }
            is ClientPacket.AdminSlashCommand -> rolePlayCommandOf(packet.commandLine)?.let { PlayerAction.Chat(channelFor(it.style), it.text) }
            is ClientPacket.UseInventorySlot -> PlayerAction.UseItem(packet.slotIndex, itemIn(packet.slotIndex))
            is ClientPacket.DropInventorySlot -> PlayerAction.DropItem(packet.slotIndex, itemIn(packet.slotIndex), packet.amount)
            is ClientPacket.CastSpell -> PlayerAction.CastSpell(packet.spellId)
            is ClientPacket.Attack -> PlayerAction.Attack
            is ClientPacket.ShopBuy -> PlayerAction.ShopBuy(packet.shopId, packet.entryIndex, packet.quantity)
            is ClientPacket.ShopSell -> PlayerAction.ShopSell(packet.shopId, packet.inventorySlotIndex, itemIn(packet.inventorySlotIndex), packet.quantity)
            is ClientPacket.CraftRecipe -> PlayerAction.Craft(packet.recipeId)
            is ClientPacket.CustomAction -> PlayerAction.Custom(packet.actionId, packet.params)
            else -> null
        }
    }

    /** Show a server message to one online player (used by plugins). Returns false if they are not online. */
    fun messagePlayer(characterId: String, text: String): Boolean {
        val session = sessionByCharacterId[characterId] ?: return false
        sendSystemMessage(session, text)
        return true
    }

    private fun handleAuthenticatedPacket(session: ClientSession, charId: String, packet: ClientPacket) {
        InputLimits.violation(packet)?.let {
            sendSystemMessage(session, it)
            return
        }
        // Refuse packets for switched-off optional modules up front, with feedback, before anything is recorded.
        val module = moduleOf(packet)
        if (module != null && !simulation.modules.isEnabled(module)) {
            sendSystemMessage(session, "${module.displayName} is switched off on this server.")
            return
        }
        // Plugins may refuse a request before the server acts on it (nothing is run or recorded).
        val player = simulation.players[charId]
        if (player != null && packet is ClientPacket.ShopSell && packet.slotIndices.isNotEmpty()) {
            for (slotIdx in packet.slotIndices) {
                val itemId = player.inventory.slots.getOrNull(slotIdx)?.itemId
                val qty = player.inventory.slots.getOrNull(slotIdx)?.amount ?: 1
                val decision = hookRegistry.dispatchBeforeAction(player, PlayerAction.ShopSell(packet.shopId, slotIdx, itemId, qty), simulation)
                if (decision is HookDecision.Deny) {
                    if (decision.reason.isNotBlank()) sendSystemMessage(session, decision.reason)
                    return
                }
            }
        } else {
            val action = if (player != null) playerActionOf(player, packet) else null
            if (player != null && action != null) {
                val decision = hookRegistry.dispatchBeforeAction(player, action, simulation)
                if (decision is HookDecision.Deny) {
                    if (decision.reason.isNotBlank()) sendSystemMessage(session, decision.reason)
                    return
                }
            }
        }
        when (packet) {
            is ClientPacket.MoveInput -> {
                // Steps bunched by the network wait for a later tick instead of being dropped (see MoveInputQueue).
                val queue = moveQueues.getOrPut(session.sessionId) { MoveInputQueue() }
                queue.offer(packet, simulation.moveAllowedNow(charId, packet.mode))?.let { applyMove(charId, queue, it) }
            }
            is ClientPacket.FaceDirection -> {
                val cur = simulation.players[charId] ?: return
                val updated = cur.copy(position = cur.position.copy(direction = packet.direction))
                setPlayerState(updated)
                broadcastPlayerUpdate(updated)
            }
            is ClientPacket.Attack -> dispatchEvents(runInput(SimInput.Attack(charId)))
            is ClientPacket.CastSpell -> dispatchEvents(
                runInput(SimInput.CastSpell(charId, packet.spellId, packet.targetEntityId, packet.targetTileX, packet.targetTileY))
            )
            is ClientPacket.CustomAction -> dispatchEvents(
                runInput(SimInput.CustomAction(charId, packet.actionId, packet.params))
            )
            is ClientPacket.InteractFacing -> dispatchEvents(runInput(SimInput.InteractFacing(charId)))
            is ClientPacket.PickupGroundItem -> dispatchEvents(runInput(SimInput.PickupGroundItem(charId)))
            is ClientPacket.UseInventorySlot -> dispatchEvents(runInput(SimInput.UseInventorySlot(charId, packet.slotIndex)))
            is ClientPacket.DropInventorySlot -> dispatchEvents(runInput(SimInput.DropInventorySlot(charId, packet.slotIndex, packet.amount)))
            is ClientPacket.SortInventory -> dispatchEvents(runInput(SimInput.SortInventory(charId)))
            is ClientPacket.SwapInventorySlots -> {
                val cur = simulation.players[charId] ?: return
                val updated = cur.copy(inventory = cur.inventory.swapSlots(packet.fromSlot, packet.toSlot))
                setPlayerState(updated)
                session.send(characterSync(updated))
            }
            is ClientPacket.MergeGear -> dispatchEvents(runInput(SimInput.MergeGear(charId, packet.targetSlotIndex, packet.materialSlotIndex)))
            is ClientPacket.UnequipSlot -> dispatchEvents(runInput(SimInput.UnequipSlot(charId, packet.slot)))
            is ClientPacket.AllocateStatPoint -> dispatchEvents(runInput(SimInput.AllocateStatPoint(charId, packet.attributeId)))
            is ClientPacket.SetPkMode -> dispatchEvents(runInput(SimInput.SetPkMode(charId, packet.mode)))
            is ClientPacket.JumpTo -> dispatchEvents(runInput(SimInput.JumpTo(charId, packet.targetX, packet.targetY)))
            is ClientPacket.SetHotbarSlot -> {
                val cur = simulation.players[charId] ?: return
                if (packet.hotbarIndex !in 0..9) return
                val mutableHotbar = cur.hotbar.toMutableList()
                mutableHotbar[packet.hotbarIndex] = HotbarSlot(packet.hotbarIndex, packet.type, packet.targetId)
                val updated = cur.copy(hotbar = mutableHotbar)
                setPlayerState(updated)
                session.send(characterSync(updated))
            }
            is ClientPacket.SendChat -> handleChat(session, charId, packet)
            is ClientPacket.ShopBuy -> dispatchEvents(runInput(SimInput.ShopBuy(charId, packet.shopId, packet.entryIndex, packet.quantity)))
            is ClientPacket.ShopSell -> dispatchEvents(runInput(SimInput.ShopSell(charId, packet.shopId, packet.inventorySlotIndex, packet.quantity, packet.slotIndices)))
            is ClientPacket.ShopBuyback -> dispatchEvents(runInput(SimInput.ShopBuyback(charId, packet.shopId, packet.entryIndex)))
            is ClientPacket.BankDeposit -> dispatchEvents(runInput(SimInput.BankDeposit(charId, packet.inventorySlotIndex, packet.amount)))
            is ClientPacket.BankWithdraw -> dispatchEvents(runInput(SimInput.BankWithdraw(charId, packet.bankSlotIndex, packet.amount)))
            is ClientPacket.GuildBankDeposit -> dispatchEvents(runInput(SimInput.GuildBankDeposit(charId, packet.inventorySlotIndex, packet.amount, packet.tab)))
            is ClientPacket.GuildBankUpgrade -> dispatchEvents(runInput(SimInput.UpgradeGuildBank(charId)))
            is ClientPacket.GuildBankWithdraw -> dispatchEvents(runInput(SimInput.GuildBankWithdraw(charId, packet.bankSlotIndex, packet.amount)))
            is ClientPacket.GuildBankSetAccess -> dispatchEvents(runInput(SimInput.SetGuildBankAccess(charId, packet.rank)))
            is ClientPacket.CraftRecipe -> dispatchEvents(runInput(SimInput.CraftRecipe(charId, packet.recipeId)))
            is ClientPacket.SelectDialogueChoice -> dispatchEvents(runInput(SimInput.SelectDialogueChoice(charId, packet.eventId, packet.choiceIndex)))
            is ClientPacket.SubmitTextInput -> dispatchEvents(runInput(SimInput.SubmitTextInput(charId, packet.eventId, packet.value, packet.cancelled)))
            is ClientPacket.PartyAction -> handlePartyAction(charId, packet)
            is ClientPacket.GuildAction -> handleGuildAction(charId, packet)
            is ClientPacket.SendMail -> handleSendMail(charId, packet)
            is ClientPacket.FriendAction -> handleFriendAction(charId, packet)
            is ClientPacket.MarketAction -> handleMarketAction(charId, packet)
            is ClientPacket.IgnoreAction -> handleIgnoreAction(charId, packet)
            is ClientPacket.MailAction -> handleMailAction(charId, packet)
            is ClientPacket.TradeAction -> handleTradeAction(charId, packet)
            is ClientPacket.AdminSlashCommand -> {
                if (packet.commandLine.trim().removePrefix("/").trim().equals("reload", ignoreCase = true)) {
                    handleReloadCommand(session, charId)
                } else if (handleServerCommand(session, charId, packet.commandLine)) {
                    // handled by the server itself: IP bans, plugins, moderation
                } else {
                    dispatchEvents(runInput(SimInput.AdminCommand(charId, packet.commandLine)))
                }
            }
            // --- Live F1 Admin Editor Handlers ---
            is ClientPacket.RequestAdminCatalog -> {
                session.send(ServerPacket.ContentCatalogUpdated(buildCatalogDto(), "Catalog loaded."))
            }
            is ClientPacket.SaveMapDefinition -> {
                if (!acceptEdit(session, ContentCollection.MAPS, packet.map.id) { it.copy(maps = it.maps + (packet.map.id to packet.map)) }) return
                recorder?.markGap("live Map edit")
                val events = simulation.applyLiveMapEdit(charId, packet.map)
                if (events.isNotEmpty()) {
                    val savedMap = simulation.maps[packet.map.id] ?: packet.map
                    dataStore.world.maps.save(savedMap.id, savedMap)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveItemDefinition -> {
                if (!acceptEdit(session, ContentCollection.ITEMS, packet.item.id) { it.copy(items = it.items + (packet.item.id to packet.item)) }) return
                recorder?.markGap("live Item edit")
                val events = simulation.applyLiveItemEdit(charId, packet.item)
                if (events.isNotEmpty()) {
                    dataStore.world.items.save(packet.item.id, packet.item)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveNpcDefinition -> {
                if (!acceptEdit(session, ContentCollection.NPCS, packet.npc.id) { it.copy(npcs = it.npcs + (packet.npc.id to packet.npc)) }) return
                recorder?.markGap("live Npc edit")
                val events = simulation.applyLiveNpcEdit(charId, packet.npc)
                if (events.isNotEmpty()) {
                    dataStore.world.npcs.save(packet.npc.id, packet.npc)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveSpellDefinition -> {
                if (!acceptEdit(session, ContentCollection.SPELLS, packet.spell.id) { it.copy(spells = it.spells + (packet.spell.id to packet.spell)) }) return
                recorder?.markGap("live Spell edit")
                val events = simulation.applyLiveSpellEdit(charId, packet.spell)
                if (events.isNotEmpty()) {
                    dataStore.world.spells.save(packet.spell.id, packet.spell)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveShopDefinition -> {
                if (!acceptEdit(session, ContentCollection.SHOPS, packet.shop.id) { it.copy(shops = it.shops + (packet.shop.id to packet.shop)) }) return
                recorder?.markGap("live Shop edit")
                val events = simulation.applyLiveShopEdit(charId, packet.shop)
                if (events.isNotEmpty()) {
                    dataStore.world.shops.save(packet.shop.id, packet.shop)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveResourceDefinition -> {
                if (!acceptEdit(session, ContentCollection.RESOURCES, packet.resource.id) { it.copy(resources = it.resources + (packet.resource.id to packet.resource)) }) return
                recorder?.markGap("live Resource edit")
                val events = simulation.applyLiveResourceEdit(charId, packet.resource)
                if (events.isNotEmpty()) {
                    dataStore.world.resources.save(packet.resource.id, packet.resource)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveGameDefinition -> handleSaveGame(session, charId, packet.game)
            is ClientPacket.SaveActionSkillDefinition -> {
                val def = packet.skill
                if (!acceptEdit(session, ContentCollection.ACTION_SKILLS, def.id) { it.copy(actionSkills = it.actionSkills + (def.id to def)) }) return
                recorder?.markGap("live Action skill edit")
                val events = simulation.applyLiveActionSkillEdit(charId, def)
                if (events.isNotEmpty()) {
                    dataStore.world.actionSkills.save(def.id, def)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveWeaponSetDefinition -> {
                val def = packet.weaponSet
                if (!acceptEdit(session, ContentCollection.WEAPON_SETS, def.id) { it.copy(weaponSets = it.weaponSets + (def.id to def)) }) return
                recorder?.markGap("live Weapon set edit")
                val events = simulation.applyLiveWeaponSetEdit(charId, def)
                if (events.isNotEmpty()) {
                    dataStore.world.weaponSets.save(def.id, def)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SetModules -> handleSetModules(session, charId, packet.disabled)
            is ClientPacket.SaveClassDefinition -> {
                val def = packet.classDefinition
                if (!acceptEdit(session, ContentCollection.CLASSES, def.id) { it.copy(classes = it.classes + (def.id to def)) }) return
                recorder?.markGap("live Class edit")
                val events = simulation.applyLiveClassEdit(charId, def)
                if (events.isNotEmpty()) {
                    dataStore.world.classes.save(def.id, def)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveRecipeDefinition -> {
                if (!acceptEdit(session, ContentCollection.RECIPES, packet.recipe.id) { it.copy(recipes = it.recipes + (packet.recipe.id to packet.recipe)) }) return
                recorder?.markGap("live Recipe edit")
                val events = simulation.applyLiveRecipeEdit(charId, packet.recipe)
                if (events.isNotEmpty()) {
                    dataStore.world.recipes.save(packet.recipe.id, packet.recipe)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveQuestDefinition -> {
                if (!acceptEdit(session, ContentCollection.QUESTS, packet.quest.id) { it.copy(quests = it.quests + (packet.quest.id to packet.quest)) }) return
                recorder?.markGap("live Quest edit")
                val events = simulation.applyLiveQuestEdit(charId, packet.quest)
                if (events.isNotEmpty()) {
                    dataStore.world.quests.save(packet.quest.id, packet.quest)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveEventDefinition -> {
                if (!acceptEdit(session, ContentCollection.EVENTS, packet.event.id) { it.copy(events = it.events + (packet.event.id to packet.event)) }) return
                recorder?.markGap("live Event edit")
                val events = simulation.applyLiveEventEdit(charId, packet.event)
                if (events.isNotEmpty()) {
                    dataStore.world.events.save(packet.event.id, packet.event)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.SaveCommonEventDefinition -> {
                if (!acceptEdit(session, ContentCollection.COMMON_EVENTS, packet.commonEvent.id) {
                        it.copy(commonEvents = it.commonEvents + (packet.commonEvent.id to packet.commonEvent))
                    }) return
                recorder?.markGap("live Common Event edit")
                val events = simulation.applyLiveCommonEventEdit(charId, packet.commonEvent)
                if (events.isNotEmpty()) {
                    dataStore.world.commonEvents.save(packet.commonEvent.id, packet.commonEvent)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.TestEventCommands -> {
                val admin = simulation.players[charId] ?: return
                if (!admin.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) return
                val synthetic = EventDefinition(
                    id = packet.eventId.coerceAtLeast(1),
                    name = "Event test",
                    pages = listOf(EventPage(commands = packet.commands))
                )
                if (!acceptEdit(session, ContentCollection.EVENTS, synthetic.id, action = "Test") {
                        it.copy(events = it.events + (synthetic.id to synthetic))
                    }) return
                recorder?.markGap("live Event test")
                val events = simulation.testEventCommands(charId, packet.eventId, packet.commands)
                if (events.isNotEmpty()) dispatchEvents(events)
            }
            is ClientPacket.SaveAnimationDefinition -> {
                if (!acceptEdit(session, ContentCollection.ANIMATIONS, packet.animation.id) { it.copy(animations = it.animations + (packet.animation.id to packet.animation)) }) return
                recorder?.markGap("live Animation edit")
                val events = simulation.applyLiveAnimationEdit(charId, packet.animation)
                if (events.isNotEmpty()) {
                    dataStore.world.animations.save(packet.animation.id, packet.animation)
                    dispatchEvents(events)
                }
            }
            is ClientPacket.ImportGameArt -> {
                val admin = simulation.players[charId] ?: return
                if (!admin.accessLevel.hasPermission(PermissionFlag.EDIT_CONTENT)) {
                    sendSystemMessage(session, "Art import rejected: EDIT_CONTENT permission required.")
                    return
                }
                val bytes = com.morgana.protocol.Base64Codec.decodeOrNull(packet.pngBase64)
                if (bytes == null) {
                    sendSystemMessage(session, "Art import rejected: invalid PNG payload encoding.")
                    return
                }
                when (val outcome = GameAssets.importPng(
                    gameDir = dataStore.gameDir,
                    gameId = simulation.game.definition.id,
                    kind = packet.kind,
                    assetId = packet.assetId,
                    bytes = bytes,
                    sprites = simulation.game.definition.sprites
                )) {
                    is GameAssets.ImportOutcome.Rejected -> {
                        sendSystemMessage(session, "Art import rejected: ${outcome.reason}")
                    }
                    is GameAssets.ImportOutcome.Accepted -> {
                        gameAssets = outcome.updated
                        recorder?.markGap("live Art import ${outcome.entry.path}")
                        for (target in sessionByCharacterId.values) {
                            val artV2 = clientCapabilitiesBySession[target.sessionId]?.contains(ProtocolCapabilities.ART_V2) == true
                            if (artV2 || outcome.entry.kind == com.morgana.protocol.realms.AssetKind.TILESET ||
                                outcome.entry.kind == com.morgana.protocol.realms.AssetKind.SPRITE
                            ) {
                                target.send(
                                    ServerPacket.GameArtUpdated(
                                        entry = outcome.entry,
                                        manifest = outcome.updated.manifestFor(artV2),
                                        pngBase64 = packet.pngBase64
                                    )
                                )
                            }
                        }
                        sendSystemMessage(session, "Updated art assets/${outcome.entry.path}.")
                    }
                }
            }
            is ClientPacket.TipsControl -> {
                val text = tips.control(charId, packet.action)
                if (text.isNotEmpty()) sendSystemMessage(session, text)
            }
            is ClientPacket.PluginMessage -> {
                val me = simulation.players[charId] ?: return
                hookRegistry.dispatchPluginMessage(me, packet.channel, packet.payload, simulation)
            }
            is ClientPacket.PluginAction -> {
                val me = simulation.players[charId] ?: return
                // Only buttons of a window this server opened for this player, and only enabled ones.
                val window = pluginWindows[charId]?.get(packet.windowId)
                val button = window?.buttons?.firstOrNull { it.id == packet.buttonId }
                if (window != null && button != null && button.enabled) {
                    // Keep only the window's own inputs, each cut to its limit.
                    val values = window.inputs.mapNotNull { input -> packet.values[input.id]?.let { input.id to it.take(input.maxLength) } }.toMap()
                    hookRegistry.dispatchPluginAction(me, packet.windowId, packet.buttonId, values, simulation)
                }
            }
            is ClientPacket.PluginWindowClosed -> {
                val me = simulation.players[charId] ?: return
                if (pluginWindows[charId]?.remove(packet.windowId) != null) hookRegistry.dispatchPluginWindowClosed(me, packet.windowId, simulation)
            }
            else -> {}
        }
    }

    /** The plugin windows currently open for each character (what the server sent and has not closed), keyed by character id. */
    private val pluginWindows: MutableMap<String, MutableMap<String, PluginWindowDto>> = HashMap()

    private fun supportsPluginUi(characterId: String): Boolean {
        val session = sessionByCharacterId[characterId] ?: return false
        return clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.PLUGIN_UI) == true
    }

    /** Send a plugin message to one online player. False when they are offline or their client lacks `plugin_ui`. */
    @Synchronized
    fun sendPluginMessage(characterId: String, channel: String, payload: Map<String, String>): Boolean {
        if (!supportsPluginUi(characterId)) return false
        sessionByCharacterId[characterId]?.send(ServerPacket.PluginMessage(channel, payload))
        return true
    }

    /** Open (or replace) a plugin window for one player. False when they are offline or their client lacks `plugin_ui`. */
    @Synchronized
    fun openPluginWindow(characterId: String, window: PluginWindowDto): Boolean {
        if (!supportsPluginUi(characterId)) return false
        pluginWindows.getOrPut(characterId) { linkedMapOf() }[window.id] = window
        sessionByCharacterId[characterId]?.send(ServerPacket.PluginWindow(window))
        return true
    }

    /** Close a plugin window for one player (no-op if it is not open). */
    @Synchronized
    fun closePluginWindow(characterId: String, windowId: String): Boolean {
        if (pluginWindows[characterId]?.remove(windowId) == null) return false
        sessionByCharacterId[characterId]?.send(ServerPacket.PluginWindowClose(windowId))
        return true
    }

    /** What the plugin loader uses to reach players' clients. */
    val pluginUi: com.morgana.server.plugins.PluginUiBridge = object : com.morgana.server.plugins.PluginUiBridge {
        override fun sendMessage(characterId: String, channel: String, payload: Map<String, String>) = sendPluginMessage(characterId, channel, payload)
        override fun openWindow(characterId: String, window: PluginWindowDto) = openPluginWindow(characterId, window)
        override fun closeWindow(characterId: String, windowId: String) = closePluginWindow(characterId, windowId)
    }

    /** The ids of the plugin windows open for a player. */
    fun openPluginWindowIds(characterId: String): Set<String> = pluginWindows[characterId]?.keys?.toSet().orEmpty()

    /**
     * Validates a candidate F1 edit (schema ranges + cross-document references) against the live catalog.
     * Rejects the save and tells the editor exactly what is wrong; only issues on the edited document block it.
     */
    private fun acceptEdit(
        session: ClientSession,
        collection: ContentCollection,
        id: Int,
        action: String = "Save",
        withCandidate: (ContentCatalog) -> ContentCatalog
    ): Boolean {
        val report = ContentValidator.validate(withCandidate(simulation.toContentCatalog()), simulation.game.definition).forDocument(collection, id)
        if (report.isValid) return true
        val shown = report.errors.take(3).joinToString("; ") { "${it.pointer.ifEmpty { "/" }}: ${it.message}" }
        val more = if (report.errors.size > 3) " (+${report.errors.size - 3} more)" else ""
        sendSystemMessage(session, "$action rejected for ${collection.directoryName} #$id: $shown$more")
        return false
    }

    private fun sendSystemMessage(session: ClientSession, text: String) {
        session.send(
            ServerPacket.ChatBroadcast(
                channel = ChatChannel.SYSTEM,
                senderName = "Server",
                message = text,
                showOverheadBubble = false
            )
        )
    }

    /**
     * `/ipbans`, `/ipban <ip> [minutes] [reason]` and `/ipunban <ip>` (permission BAN_PLAYER). Returns true if the line
     * was one of these commands, whether or not it succeeded.
     */
    private fun handleIpCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parts = line.trim().removePrefix("/").trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.lowercase() ?: return false
        if (command != "ipban" && command != "ipunban" && command != "ipbans") return false
        val admin = simulation.players[charId] ?: return true
        if (!admin.accessLevel.hasPermission(PermissionFlag.BAN_PLAYER)) {
            sendSystemMessage(session, "You do not have permission to manage IP bans.")
            return true
        }
        val gate = connectionGate
        if (gate == null) {
            sendSystemMessage(session, "Connection limits are not active on this server.")
            return true
        }
        when (command) {
            "ipbans" -> {
                val bans = gate.bans()
                if (bans.isEmpty()) sendSystemMessage(session, "No active IP bans.")
                bans.take(10).forEach {
                    val minutes = ((it.untilMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    sendSystemMessage(session, "${it.key}: $minutes min left (${it.reason})")
                }
                if (bans.size > 10) sendSystemMessage(session, "... and ${bans.size - 10} more.")
            }
            "ipban" -> {
                val address = parts.getOrNull(1)?.let { AddressRange.literal(it) }
                if (address == null) {
                    sendSystemMessage(session, "Usage: /ipban <ip> [minutes] [reason]")
                    return true
                }
                val minutes = parts.getOrNull(2)?.toLongOrNull() ?: 1440L
                val reason = parts.drop(if (parts.getOrNull(2)?.toLongOrNull() != null) 3 else 2).joinToString(" ").ifBlank { "banned by ${admin.name}" }
                val ban = gate.ban(address, minutes, reason.take(100))
                moderation.audit(admin.name, "ipban", address.hostAddress, "$minutes min: ${reason.take(100)}")
                sendSystemMessage(
                    session,
                    if (ban == null) "${address.hostAddress} is exempt (this machine or the allow-list) and cannot be banned."
                    else "Banned ${ban.key} for ${minutes.coerceIn(1, ConnectionGate.MAX_MANUAL_MINUTES)} minutes."
                )
            }
            "ipunban" -> {
                val address = parts.getOrNull(1)?.let { AddressRange.literal(it) }
                if (address == null) sendSystemMessage(session, "Usage: /ipunban <ip>")
                else sendSystemMessage(session, if (gate.unban(address).also { if (it) moderation.audit(admin.name, "ipunban", address.hostAddress) }) "Lifted the ban on ${ConnectionGate.keyOf(address)}." else "${ConnectionGate.keyOf(address)} is not banned.")
            }
        }
        return true
    }

    /**
     * The slash commands the server handles itself (IP bans, plugins, moderation), whether the client sent them as chat or as an
     * admin command packet (the game's chat box sends the latter). Returns true when the line was one of them.
     */
    private fun handleServerCommand(session: ClientSession, charId: String, line: String): Boolean =
        handleIpCommand(session, charId, line) || handlePluginCommand(session, charId, line) || handleModerationCommand(session, charId, line) ||
            handleIgnoreCommand(session, charId, line) || handleRolePlayCommand(session, charId, line) || handleTipsCommand(session, charId, line)

    /** `/tips off`, `/tips on` and `/tips reset` (show every tip again). */
    private fun handleTipsCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parts = line.trim().removePrefix("/").trim().split(Regex("\\s+"))
        if (parts.firstOrNull()?.lowercase() != "tips") return false
        val action = when (parts.getOrNull(1)?.lowercase()) {
            "off" -> TipsAction.OFF
            "on" -> TipsAction.ON
            "reset" -> TipsAction.RESET
            else -> { sendSystemMessage(session, "Usage: /tips off | on | reset"); return true }
        }
        sendSystemMessage(session, tips.control(charId, action))
        return true
    }

    /** The role-play chat command on this line (`/me`, `/ooc`, ...), or null if it is none or the module is switched off. */
    private fun rolePlayCommandOf(line: String): RolePlayLine? =
        if (simulation.modules.isEnabled(OptionalModule.ROLE_PLAY_CHAT)) RolePlayChat.parseCommand(line) else null

    /** Actions go out on the emote channel; every other style is local (map) speech. */
    private fun channelFor(style: ChatStyle): ChatChannel = if (style == ChatStyle.ACTION) ChatChannel.EMOTE else ChatChannel.MAP

    /** `/me`, `/ooc`, `/say`, `/shout`, `/quiet`, `/narrate` and their aliases. Returns true if the line was one of them. */
    private fun handleRolePlayCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parsed = rolePlayCommandOf(line) ?: return false
        if (parsed.text.isEmpty()) {
            sendSystemMessage(session, RolePlayChat.usage(parsed.style))
            return true
        }
        val sender = simulation.players[charId] ?: return true
        deliverSpeech(session, sender, parsed)
        return true
    }

    /** Whether the sender's account is muted; if so they are told why and for how long. */
    private fun refuseIfMuted(session: ClientSession, sender: CharacterState): Boolean {
        val mute = moderation.active(SanctionKind.MUTE, sender.accountId) ?: return false
        sendSystemMessage(session, "You are muted (${ModerationService.describeDuration(mute.untilMillis, System.currentTimeMillis())}): ${mute.reason}")
        return true
    }

    /**
     * Sends a line of local role-play speech to everyone who can hear it ([RolePlayChat.recipients]). Clients that know
     * chat styles get the style; older clients get it folded into the text.
     */
    private fun deliverSpeech(session: ClientSession, sender: CharacterState, line: RolePlayLine) {
        if (line.style == ChatStyle.NARRATION && !sender.accessLevel.hasPermission(PermissionFlag.NARRATE)) {
            sendSystemMessage(session, "Only staff can narrate.")
            return
        }
        if (refuseIfMuted(session, sender)) return
        val channel = channelFor(line.style)
        hookRegistry.dispatchPlayerChat(sender, channel, line.text, simulation)
        if (line.style == ChatStyle.NARRATION) {
            org.slf4j.LoggerFactory.getLogger("Narration").info("{} narrated on map {}: {}", sender.name, sender.position.mapId, line.text)
        }

        val speaker = Listener(sender.id, sender.position.mapId, sender.position.tileX, sender.position.tileY)
        val everyone = simulation.players.values.map { Listener(it.id, it.position.mapId, it.position.tileX, it.position.tileY) }
        val hearers = RolePlayChat.recipients(speaker, everyone, line.style, rolePlayChat) { mapId ->
            simulation.maps[mapId]?.neighbors?.let { setOf(it.northMapId, it.southMapId, it.eastMapId, it.westMapId) } ?: emptySet()
        }
        val narration = line.style == ChatStyle.NARRATION
        val bubble = line.style != ChatStyle.OOC && !narration
        val styled = ServerPacket.ChatBroadcast(
            channel = channel,
            senderEntityId = if (narration) "" else sender.id,
            senderName = if (narration) RolePlayChat.NARRATOR_NAME else sender.name,
            message = line.text,
            showOverheadBubble = bubble,
            style = line.style
        )
        val (plainName, plainText) = RolePlayChat.plainFallback(line.style, sender.name, line.text)
        val plain = styled.copy(senderName = plainName, message = plainText, style = ChatStyle.NORMAL)
        hearers.filter { !isIgnoredBy(it, sender) }.forEach { id ->
            val target = sessionByCharacterId[id] ?: return@forEach
            val knowsStyles = clientCapabilitiesBySession[target.sessionId]?.contains(ProtocolCapabilities.RP_CHAT) == true
            target.send(if (knowsStyles || line.style == ChatStyle.NORMAL) styled else plain)
        }
    }

    /** Why this account may not log in (shown to the player), or null if it may. */
    private fun banMessage(account: AccountDocument): String? {
        moderation.active(SanctionKind.BAN, account.id)?.let { ban ->
            val length = if (ban.untilMillis == 0L) "permanently" else "for another ${ModerationService.describeDuration(ban.untilMillis, System.currentTimeMillis())}"
            return "This account is banned $length: ${ban.reason}"
        }
        return if (account.banned) "This account is banned." else null
    }

    private class ModTarget(val accountId: String, val name: String, val rank: Int, val onlineCharId: String?)

    // --- Web admin operations (see AdminApi): called from the panel's threads; the coordinator lock serialises them with the game ---

    private val startedAtMillis = System.currentTimeMillis()
    private var tickAvgMicros = 0.0
    private var tickMaxMicros = 0L
    private val metricSamples = ArrayDeque<AdminMetricSample>()

    private fun recordTick(startedNanos: Long) {
        val micros = (System.nanoTime() - startedNanos) / 1000L
        tickAvgMicros = if (tickAvgMicros == 0.0) micros.toDouble() else tickAvgMicros * 0.99 + micros * 0.01
        if (micros > tickMaxMicros) tickMaxMicros = micros
        if (simulation.currentTick % 1200L == 0L) {
            metricSamples.addLast(AdminMetricSample(System.currentTimeMillis(), sessionByCharacterId.size, tickAvgMicros / 1000.0))
            while (metricSamples.size > MAX_METRIC_SAMPLES) metricSamples.removeFirst()
            tickMaxMicros = 0L
        }
    }

    @Synchronized
    fun adminStatus(): AdminStatus {
        val rt = Runtime.getRuntime()
        return AdminStatus(
            serverTick = simulation.effectiveWorldTick(),
            uptimeSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000L,
            playersOnline = sessionByCharacterId.size,
            accounts = dataStore.accounts.documents.count(),
            characters = dataStore.characters.documents.count(),
            maps = simulation.contentMaps().size,
            tickAvgMs = tickAvgMicros / 1000.0,
            tickMaxMs = tickMaxMicros / 1000.0,
            heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
            heapMaxMb = rt.maxMemory() / (1024 * 1024),
            protocolVersion = PROTOCOL_VERSION,
            disabledModules = simulation.modules.disabledKeys(),
            openReports = moderation.openReports().size,
            activeBans = moderation.activeList(SanctionKind.BAN).size,
            activeMutes = moderation.activeList(SanctionKind.MUTE).size
        )
    }

    @Synchronized
    fun adminMetrics(): List<AdminMetricSample> = metricSamples.toList()

    @Synchronized
    fun adminPlayers(): List<AdminPlayer> = simulation.players.values.filter { sessionByCharacterId.containsKey(it.id) }
        .sortedBy { it.name.lowercase() }
        .map { AdminPlayer(it.id, it.name, it.accountId, it.level, it.position.mapId, it.position.tileX, it.position.tileY, it.accessLevel.title) }

    @Synchronized
    fun adminKick(name: String, reason: String, actor: String): AdminResult {
        val target = findModTarget(name) ?: return AdminResult(false, "No player named $name.")
        val online = target.onlineCharId ?: return AdminResult(false, "${target.name} is not online.")
        val why = reason.ifBlank { "no reason given" }
        moderation.audit(actor, "kick", target.name, why)
        disconnectWithReason(online, "You were kicked: $why")
        return AdminResult(true, "Kicked ${target.name}.")
    }

    @Synchronized
    fun adminSanction(kind: SanctionKind, name: String, durationMillis: Long, reason: String, actor: String): AdminResult {
        val target = findModTarget(name) ?: return AdminResult(false, "No player named $name.")
        val s = moderation.sanction(kind, target.accountId, target.name, reason.ifBlank { "no reason given" }, actor, durationMillis)
        val length = ModerationService.describeDuration(s.untilMillis, System.currentTimeMillis())
        val online = target.onlineCharId
        if (kind == SanctionKind.BAN) {
            online?.let { disconnectWithReason(it, "You were banned ($length): ${s.reason}") }
            return AdminResult(true, "Banned ${target.name} ($length).")
        }
        online?.let { id -> sessionByCharacterId[id]?.let { sendSystemMessage(it, "You have been muted ($length): ${s.reason}") } }
        return AdminResult(true, "Muted ${target.name} ($length).")
    }

    @Synchronized
    fun adminLift(kind: SanctionKind, name: String, actor: String): AdminResult {
        val target = findModTarget(name) ?: return AdminResult(false, "No player named $name.")
        if (!moderation.lift(kind, target.accountId, target.name, actor)) {
            return AdminResult(false, "${target.name} is not ${if (kind == SanctionKind.BAN) "banned" else "muted"}.")
        }
        if (kind == SanctionKind.MUTE) target.onlineCharId?.let { id -> sessionByCharacterId[id]?.let { sendSystemMessage(it, "You can chat again.") } }
        return AdminResult(true, "${if (kind == SanctionKind.BAN) "Lifted the ban on" else "Unmuted"} ${target.name}.")
    }

    @Synchronized
    fun adminBroadcast(message: String, actor: String): AdminResult {
        moderation.audit(actor, "broadcast", "", message.take(200))
        broadcastToAll(ServerPacket.ChatBroadcast(channel = ChatChannel.BROADCAST, senderEntityId = "", senderName = "Server", message = message))
        return AdminResult(true, "Sent to ${sessionByCharacterId.size} online player(s).")
    }

    fun adminReload(actor: String): AdminResult {
        val result = reloadContent()
        moderation.audit(actor, "reload", "", result.summary.take(200))
        return AdminResult(result.applied, result.summary)
    }

    // ---- Admin API: accounts, characters, tickets, live player changes and content (see AdminApi) ----

    private fun accountByName(name: String): AccountDocument? = dataStore.accounts.findByUsername(name.trim())

    @Synchronized
    fun adminAccounts(): List<com.morgana.server.admin.AdminAccount> = dataStore.accounts.findAll().sortedBy { it.username.lowercase() }
        .map { com.morgana.server.admin.AdminAccount(it.id, it.username, it.accessLevel.name, it.characterIds.size, it.banned) }

    /**
     * Creates an account without anyone typing a password. With a blank [password] a random one nobody knows is set, so the account
     * can only be entered with a dev ticket (or a password an operator sets later). An account named in the owner list starts as Owner.
     */
    @Synchronized
    fun adminCreateAccount(username: String, password: String, actor: String): AdminResult {
        val name = username.trim()
        if (name.length !in 2..20) return AdminResult(false, "Username must be 2-20 characters.")
        if (accountByName(name) != null) return AdminResult(false, "An account named $name already exists.")
        val secret = password.ifBlank { com.morgana.server.admin.AdminToken.generate() }
        val access = if (ownerPolicy.isConfiguredOwner(name)) AccessLevel.OWNER else AccessLevel.PLAYER
        dataStore.accounts.save(
            AccountDocument(
                id = "acc-${UUID.randomUUID().toString().take(8)}",
                username = name,
                passwordHash = AccountRepository.hashPassword(secret),
                accessLevel = access
            )
        )
        moderation.audit(actor, "create_account", name, access.name)
        val how = if (password.isBlank()) " with no usable password: enter it with a dev ticket" else ""
        return AdminResult(true, "Created account $name ($access)$how.")
    }

    /** Sets an account's access level. Owner is never granted here (that is the owner list's job) and an Owner is never changed. */
    @Synchronized
    fun adminSetAccess(username: String, access: String, actor: String): AdminResult {
        val account = accountByName(username) ?: return AdminResult(false, "No account named $username.")
        val level = AccessLevel.values().firstOrNull { it.name.equals(access, ignoreCase = true) }
            ?: return AdminResult(false, "access must be one of: ${AccessLevel.values().filter { it != AccessLevel.OWNER }.joinToString { it.name }}.")
        if (level == AccessLevel.OWNER) return AdminResult(false, "Owner is granted only through the owner list (owners.txt or MORGANA_OWNERS).")
        if (account.accessLevel == AccessLevel.OWNER) return AdminResult(false, "${account.username} is an Owner; edit the owner list instead.")
        if (account.characterIds.any { sessionByCharacterId.containsKey(it) }) return AdminResult(false, "${account.username} is online; kick them first so the change applies cleanly.")
        dataStore.inTransaction {
            dataStore.accounts.save(account.copy(accessLevel = level))
            account.characterIds.mapNotNull { dataStore.characters.findById(it) }.forEach { dataStore.characters.save(it.copy(accessLevel = level)) }
        }
        moderation.audit(actor, "set_access", account.username, level.name)
        return AdminResult(true, "${account.username} is now ${level.name}.")
    }

    @Synchronized
    fun adminCharacters(account: String?): List<com.morgana.server.admin.AdminCharacter> {
        val owner = account?.let { accountByName(it) ?: dataStore.accounts.findById(it) ?: return emptyList() }
        val chars = if (owner == null) dataStore.characters.findAll() else owner.characterIds.mapNotNull { dataStore.characters.findById(it) }
        return chars.sortedBy { it.name.lowercase() }.map {
            val live = simulation.players[it.id] ?: it
            com.morgana.server.admin.AdminCharacter(
                it.id, it.name, it.accountId, it.classId, live.level, live.position.mapId, live.position.tileX, live.position.tileY,
                online = sessionByCharacterId.containsKey(it.id)
            )
        }
    }

    /** Makes a character on an existing account, by the same rules as the character screen (name, class, character limit). */
    @Synchronized
    fun adminCreateCharacter(account: String, name: String, classId: Int, spriteId: Int, actor: String): AdminResult {
        val acc = accountByName(account) ?: return AdminResult(false, "No account named $account.")
        val clean = com.morgana.protocol.CharacterNames.clean(name)
        val classDef = simulation.classes[if (classId > 0) classId else simulation.classes.keys.minOrNull() ?: 0]
        val problem = com.morgana.protocol.CharacterNames.problem(clean) ?: when {
            acc.characterIds.size >= sessionConfig.maxCharacters -> "${acc.username} already has the most characters this realm allows (${sessionConfig.maxCharacters})."
            dataStore.characters.findByName(clean) != null || dataStore.accounts.findByUsername(clean)?.let { it.id != acc.id } == true -> "That name is taken."
            classDef == null -> "No such class. Known classes: ${simulation.classes.keys.sorted().joinToString()}."
            else -> null
        }
        if (problem != null || classDef == null) return AdminResult(false, problem ?: "No such class.")
        val newId = "char-${UUID.randomUUID().toString().take(8)}"
        val character = buildNewCharacter(newId, acc.id, clean, classDef, spriteId, acc.accessLevel)
        dataStore.inTransaction {
            dataStore.characters.save(character)
            dataStore.accounts.save(acc.copy(characterIds = acc.characterIds + newId))
        }
        moderation.audit(actor, "create_character", acc.username, "$clean (class ${classDef.id})")
        return AdminResult(true, "Created $clean (class ${classDef.id}) on ${acc.username}.")
    }

    /** A one-time login ticket for an existing account, or null when dev login is off or the account does not exist. */
    @Synchronized
    fun adminMintTicket(username: String, ttlSeconds: Long, actor: String): com.morgana.server.admin.AdminTicket? {
        val tickets = devTickets ?: return null
        val account = accountByName(username) ?: return null
        val ticket = tickets.mint(account.username, ttlSeconds)
        moderation.audit(actor, "mint_ticket", account.username, "${ttlSeconds}s")
        return com.morgana.server.admin.AdminTicket(ticket, account.username, ttlSeconds)
    }

    private fun onlinePlayerByName(name: String) = simulation.players.values
        .firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && sessionByCharacterId.containsKey(it.id) }

    @Synchronized
    fun adminWarp(name: String, mapId: Int, x: Int, y: Int, actor: String): AdminResult {
        val player = onlinePlayerByName(name) ?: return AdminResult(false, "$name is not online.")
        val map = simulation.maps[mapId] ?: return AdminResult(false, "No map $mapId.")
        if (x !in 0 until map.width || y !in 0 until map.height) return AdminResult(false, "($x, $y) is outside map $mapId (${map.width} x ${map.height}).")
        recorder?.markGap("admin warp")
        dispatchEvents(simulation.applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer(player.id, mapId, x, y)))
        moderation.audit(actor, "warp", player.name, "map $mapId ($x, $y)")
        return AdminResult(true, "Warped ${player.name} to map $mapId ($x, $y).")
    }

    @Synchronized
    fun adminGive(name: String, itemId: Int, amount: Int, actor: String): AdminResult {
        val player = onlinePlayerByName(name) ?: return AdminResult(false, "$name is not online.")
        val item = simulation.items[itemId] ?: return AdminResult(false, "No item $itemId.")
        recorder?.markGap("admin give")
        dispatchEvents(simulation.applyScriptEffect(com.morgana.domain.script.ScriptEffect.GiveItem(player.id, itemId, amount)))
        moderation.audit(actor, "give", player.name, "$amount x item $itemId")
        return AdminResult(true, "Gave ${player.name} $amount x ${item.name}.")
    }

    private fun collectionNamed(name: String?): ContentCollection? = ContentCollection.values().firstOrNull { it.directoryName == name }

    private fun contentFile(collection: ContentCollection, id: Int): java.nio.file.Path {
        val root = dataStore.contentDir.toAbsolutePath().normalize()
        val file = root.resolve(collection.directoryName).resolve(collection.fileStem(id) + ".json").normalize()
        check(file.startsWith(root)) { "path escapes the content folder" }
        return file
    }

    @Synchronized
    fun adminContentList(collection: String?): List<com.morgana.server.admin.AdminContentEntry> {
        val wanted = if (collection == null) ContentCollection.values().toList() else listOfNotNull(collectionNamed(collection))
        val out = mutableListOf<com.morgana.server.admin.AdminContentEntry>()
        for (col in wanted) {
            val dir = dataStore.contentDir.resolve(col.directoryName)
            if (!java.nio.file.Files.isDirectory(dir)) continue
            java.nio.file.Files.list(dir).use { files ->
                files.map { it.fileName.toString() }
                    .filter { it.startsWith(col.filePrefix + "_") && it.endsWith(".json") }
                    .forEach { f -> f.removePrefix(col.filePrefix + "_").removeSuffix(".json").toIntOrNull()?.let { out += com.morgana.server.admin.AdminContentEntry(col.directoryName, it, "${col.directoryName}/$f") } }
            }
        }
        return out.sortedWith(compareBy({ it.collection }, { it.id }))
    }

    @Synchronized
    fun adminContentGet(collection: String, id: Int): String? {
        val col = collectionNamed(collection) ?: return null
        val file = contentFile(col, id)
        return if (java.nio.file.Files.isRegularFile(file)) java.nio.file.Files.readString(file) else null
    }

    /**
     * Writes one content document through the same pipeline as a hand edit or the F1 editor: the file is saved, the whole set is
     * validated and hot-swapped, and if that refuses the document the previous file is put back (or the new one removed) and the
     * world is reloaded from it again, so a bad edit never stays on disk.
     */
    @Synchronized
    fun adminContentPut(collection: String, id: Int, json: String, actor: String): AdminResult {
        val col = collectionNamed(collection) ?: return AdminResult(false, "Unknown collection '$collection'. Known: ${ContentCollection.values().joinToString { it.directoryName }}.")
        val parsed = try { kotlinx.serialization.json.Json.parseToJsonElement(json) } catch (e: Exception) { return AdminResult(false, "Not valid JSON: ${e.message?.take(120)}") }
        val obj = parsed as? kotlinx.serialization.json.JsonObject ?: return AdminResult(false, "A document must be a JSON object.")
        val declaredId = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        if (declaredId != null && declaredId != id) return AdminResult(false, "The document's id ($declaredId) does not match the address ($id).")
        val file = contentFile(col, id)
        val previous = if (java.nio.file.Files.isRegularFile(file)) java.nio.file.Files.readAllBytes(file) else null
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, json)
        val result = reloadContent()
        val errors = result.report.forDocument(col, id).errors
        if (!result.applied || errors.isNotEmpty()) {
            if (previous != null) java.nio.file.Files.write(file, previous) else java.nio.file.Files.deleteIfExists(file)
            reloadContent()
            val why = errors.take(5).joinToString("; ") { "${it.pointer} [${it.code}] ${it.message}" }.ifBlank { result.summary }
            moderation.audit(actor, "content_put_rejected", "${col.directoryName}/$id", why.take(200))
            return AdminResult(false, "Rejected, nothing changed: $why")
        }
        moderation.audit(actor, "content_put", "${col.directoryName}/$id", result.summary.take(200))
        return AdminResult(true, "Saved ${col.directoryName}/${col.fileStem(id)}.json. ${result.summary}")
    }

    /** Finds a player by character name, online or not. */
    private fun findModTarget(name: String): ModTarget? {
        simulation.players.values.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { p ->
            val account = dataStore.accounts.findById(p.accountId)
            return ModTarget(p.accountId, p.name, (account?.accessLevel ?: p.accessLevel).rank, p.id)
        }
        val stored = dataStore.characters.findByName(name) ?: return null
        val account = dataStore.accounts.findById(stored.accountId) ?: return null
        return ModTarget(account.id, stored.name, account.accessLevel.rank, null)
    }

    /** Ends a player's connection with a reason (the same sequence as being signed in elsewhere), saving the character. */
    private fun disconnectWithReason(characterId: String, text: String) {
        val s = sessionByCharacterId[characterId] ?: return
        s.send(ServerPacket.SessionEnded(text, resumable = false))
        onSessionDisconnected(s)
        s.characterId = null
        s.close(text)
    }

    /**
     * Moderation commands typed in chat. Returns true when the line was one of them (successful or not).
     *
     * - `/kick <name> [reason]`, `/mute <name> <30m|12h|7d|perm> [reason]`, `/unmute <name>`, `/mutes`, `/reports`,
     *   `/report close <id> [note]`, `/modlog [name]`: permission KICK_MUTE.
     * - `/ban <name> [duration] [reason]`, `/unban <name>`, `/bans`: permission BAN_PLAYER (no duration = permanent).
     * - `/report <name> <reason>`: any player.
     * A moderator cannot act on themselves or on anyone of the same or higher access rank. Every action is audited.
     */
    private fun handleModerationCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parts = line.trim().removePrefix("/").trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.lowercase() ?: return false
        if (command !in MODERATION_COMMANDS) return false
        val me = simulation.players[charId] ?: return true
        fun say(text: String) = sendSystemMessage(session, text)
        fun need(flag: PermissionFlag): Boolean {
            if (me.accessLevel.hasPermission(flag)) return true
            say("You do not have permission to use /$command.")
            return false
        }
        fun resolve(index: Int): ModTarget? {
            val name = parts.getOrNull(index)
            if (name == null) { say("Usage: " + MODERATION_USAGE.getValue(command)); return null }
            val target = findModTarget(name)
            if (target == null) { say("No player named $name."); return null }
            return target
        }
        fun allowed(target: ModTarget): Boolean {
            if (target.accountId == me.accountId) { say("You cannot do that to yourself."); return false }
            if (target.rank >= me.accessLevel.rank) { say("${target.name} has the same or a higher access level than you."); return false }
            return true
        }
        val now = System.currentTimeMillis()

        when (command) {
            "report" -> {
                if (parts.getOrNull(1)?.lowercase() == "close") {
                    if (!need(PermissionFlag.KICK_MUTE)) return true
                    val id = parts.getOrNull(2)?.toIntOrNull()
                    if (id == null) { say("Usage: /report close <id> [note]"); return true }
                    val closed = moderation.closeReport(id, me.name, parts.drop(3).joinToString(" "))
                    say(if (closed == null) "No open report #$id." else "Closed report #$id.")
                    return true
                }
                val targetName = parts.getOrNull(1)
                val reason = parts.drop(2).joinToString(" ").trim()
                if (targetName == null || reason.isEmpty()) { say("Usage: /report <player> <what happened>"); return true }
                val target = findModTarget(targetName)
                if (target == null) { say("No player named $targetName."); return true }
                if (target.accountId == me.accountId) { say("You cannot report yourself."); return true }
                when (val result = moderation.report(me.accountId, me.name, target.name, reason)) {
                    is ModerationService.ReportResult.Refused -> say(result.why)
                    is ModerationService.ReportResult.Filed -> {
                        say("Report #${result.report.id} sent. A moderator will look at it.")
                        for ((cid, s) in sessionByCharacterId) {
                            if (simulation.players[cid]?.accessLevel?.hasPermission(PermissionFlag.KICK_MUTE) == true) {
                                sendSystemMessage(s, "New report #${result.report.id}: ${me.name} reported ${target.name}: ${result.report.reason.take(80)}")
                            }
                        }
                    }
                }
            }
            "reports" -> {
                if (!need(PermissionFlag.KICK_MUTE)) return true
                val open = moderation.openReports()
                if (open.isEmpty()) say("No open reports.")
                open.take(10).forEach { say("#${it.id} ${it.reporterName} about ${it.targetName}, ${ModerationService.describeDuration(now + (now - it.atMillis), now)} ago: ${it.reason}") }
                if (open.size > 10) say("... and ${open.size - 10} more.")
            }
            "modlog" -> {
                if (!need(PermissionFlag.KICK_MUTE)) return true
                val entries = moderation.recentAudit(parts.getOrNull(1), 10)
                if (entries.isEmpty()) say("Nothing logged.")
                entries.forEach { say("${java.time.Instant.ofEpochMilli(it.atMillis).toString().take(16).replace('T', ' ')} ${it.actor} ${it.action} ${it.target} ${it.detail}".trim()) }
            }
            "kick" -> {
                if (!need(PermissionFlag.KICK_MUTE)) return true
                val target = resolve(1) ?: return true
                if (!allowed(target)) return true
                val reason = parts.drop(2).joinToString(" ").ifBlank { "no reason given" }.take(ModerationService.MAX_REASON)
                val online = target.onlineCharId
                if (online == null) { say("${target.name} is not online."); return true }
                moderation.audit(me.name, "kick", target.name, reason)
                disconnectWithReason(online, "You were kicked by ${me.name}: $reason")
                say("Kicked ${target.name}.")
            }
            "mute" -> {
                if (!need(PermissionFlag.KICK_MUTE)) return true
                val target = resolve(1) ?: return true
                val duration = parts.getOrNull(2)?.let { ModerationService.parseDuration(it) }
                if (duration == null) { say("Usage: " + MODERATION_USAGE.getValue("mute")); return true }
                if (!allowed(target)) return true
                val reason = parts.drop(3).joinToString(" ").ifBlank { "no reason given" }
                val s = moderation.sanction(SanctionKind.MUTE, target.accountId, target.name, reason, me.name, duration)
                target.onlineCharId?.let { id -> sessionByCharacterId[id]?.let { sendSystemMessage(it, "You have been muted (${ModerationService.describeDuration(s.untilMillis, now)}): ${s.reason}") } }
                say("Muted ${target.name} (${ModerationService.describeDuration(s.untilMillis, now)}).")
            }
            "unmute" -> {
                if (!need(PermissionFlag.KICK_MUTE)) return true
                val target = resolve(1) ?: return true
                val lifted = moderation.lift(SanctionKind.MUTE, target.accountId, target.name, me.name)
                if (lifted) target.onlineCharId?.let { id -> sessionByCharacterId[id]?.let { sendSystemMessage(it, "You can chat again.") } }
                say(if (lifted) "Unmuted ${target.name}." else "${target.name} is not muted.")
            }
            "mutes", "bans" -> {
                val kind = if (command == "mutes") SanctionKind.MUTE else SanctionKind.BAN
                if (!need(if (kind == SanctionKind.MUTE) PermissionFlag.KICK_MUTE else PermissionFlag.BAN_PLAYER)) return true
                val list = moderation.activeList(kind)
                if (list.isEmpty()) say("No active ${command}.")
                list.take(10).forEach { say("${it.accountName}: ${ModerationService.describeDuration(it.untilMillis, now)}, by ${it.byName}: ${it.reason}") }
                if (list.size > 10) say("... and ${list.size - 10} more.")
            }
            "ban" -> {
                if (!need(PermissionFlag.BAN_PLAYER)) return true
                val target = resolve(1) ?: return true
                if (!allowed(target)) return true
                val parsed = parts.getOrNull(2)?.let { ModerationService.parseDuration(it) }
                val duration = parsed ?: 0L
                val reason = parts.drop(if (parsed != null) 3 else 2).joinToString(" ").ifBlank { "no reason given" }
                val s = moderation.sanction(SanctionKind.BAN, target.accountId, target.name, reason, me.name, duration)
                target.onlineCharId?.let { disconnectWithReason(it, "You were banned by ${me.name} (${ModerationService.describeDuration(s.untilMillis, now)}): ${s.reason}") }
                say("Banned ${target.name} (${ModerationService.describeDuration(s.untilMillis, now)}).")
            }
            "unban" -> {
                if (!need(PermissionFlag.BAN_PLAYER)) return true
                val target = resolve(1) ?: return true
                say(if (moderation.lift(SanctionKind.BAN, target.accountId, target.name, me.name)) "Lifted the ban on ${target.name}." else "${target.name} is not banned.")
            }
        }
        return true
    }

    /** The plugin loader of this server (set by [MorganaServerRuntime]); null when it loads no plugins. */
    @Volatile
    var plugins: com.morgana.server.plugins.PluginManager? = null

    /**
     * `/plugins` lists plugins; `/plugin enable|disable|reload <id>` manages one (permission SERVER_MAINTENANCE).
     * Returns true if the line was one of these commands.
     */
    private fun handlePluginCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parts = line.trim().removePrefix("/").trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.lowercase() ?: return false
        if (command != "plugins" && command != "plugin") return false
        val admin = simulation.players[charId] ?: return true
        if (!admin.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) {
            sendSystemMessage(session, "You do not have permission to manage plugins.")
            return true
        }
        val manager = plugins
        if (manager == null) {
            sendSystemMessage(session, "This server does not load plugins.")
            return true
        }
        val action = parts.getOrNull(1)?.lowercase()
        val id = parts.getOrNull(2)
        if (command == "plugins" || action == null || action == "list") {
            val all = manager.list()
            if (all.isEmpty()) sendSystemMessage(session, "No plugins installed.")
            all.take(15).forEach {
                sendSystemMessage(session, "${it.id} ${it.version}: ${it.status.name.lowercase()}${it.problem?.let { p -> " ($p)" } ?: ""}")
            }
            if (all.size > 15) sendSystemMessage(session, "... and ${all.size - 15} more.")
            return true
        }
        if (id == null || action !in setOf("enable", "disable", "reload")) {
            sendSystemMessage(session, "Usage: /plugins | /plugin enable|disable|reload <id>")
            return true
        }
        try {
            val message = when (action) {
                "enable" -> manager.enable(id).let { "$id is ${it.status.name.lowercase()}${it.problem?.let { p -> " ($p)" } ?: ""}" }
                "reload" -> manager.reload(id).let { "$id reloaded: ${it.status.name.lowercase()}${it.problem?.let { p -> " ($p)" } ?: ""}" }
                else -> manager.disable(id).let { off -> if (off.isEmpty()) "$id was not running." else "Disabled ${off.joinToString()}." }
            }
            sendSystemMessage(session, message)
        } catch (e: IllegalArgumentException) {
            sendSystemMessage(session, e.message ?: "No such plugin.")
        }
        return true
    }

    /**
     * Saves the game's rules from the F1 Game rules panel. Needs server maintenance rights. The definition must pass the game
     * rules checks, and the content already on the server must still be valid under it. It is written to `game.json` and takes
     * effect after a restart: the running simulation keeps the rules it started with.
     */
    private fun handleSaveGame(session: ClientSession, charId: String, game: com.morgana.domain.game.GameDefinition) {
        val admin = simulation.players[charId] ?: return
        if (!admin.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) {
            sendSystemMessage(session, "Save rejected for game rules: changing the game's rules needs server maintenance rights.")
            return
        }
        val issues = GameDefinitionLoader.issues(game)
        if (issues.isNotEmpty()) {
            sendSystemMessage(session, "Save rejected for game rules: " + issues.take(3).joinToString("; ") + if (issues.size > 3) " (+${issues.size - 3} more)" else "")
            return
        }
        val report = ContentValidator.validate(simulation.toContentCatalog(), game)
        if (!report.isValid) {
            val shown = report.errors.take(3).joinToString("; ") { "${it.collection.directoryName} #${it.id}: ${it.message}" }
            sendSystemMessage(session, "Save rejected for game rules: existing content would break (${report.errors.size} problem(s)): $shown")
            return
        }
        val dir = listOf(dataStore.gameDir, dataStore.rootDir).firstOrNull { java.nio.file.Files.exists(GameDefinitionLoader.fileIn(it)) } ?: dataStore.gameDir
        java.nio.file.Files.createDirectories(dir)
        val file = GameDefinitionLoader.fileIn(dir)
        val tmp = file.resolveSibling("${GameDefinitionLoader.FILE_NAME}.tmp")
        java.nio.file.Files.writeString(tmp, GameDefinitionLoader.encode(game))
        java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        recorder?.markGap("game rules saved")
        sendSystemMessage(session, "Game rules saved to ${GameDefinitionLoader.FILE_NAME}. They take effect after the server restarts.")
    }

    /**
     * Switches the optional systems from the F1 Systems panel: [disabled] are the built-in `configKey`s to turn off and every
     * other built-in system is turned on (plugin modules are left as `server.json` has them). The choice is saved to
     * `server.json` and used by the running simulation at once; clients see it in their HUD after their next sign-in.
     */
    private fun handleSetModules(session: ClientSession, charId: String, disabled: List<String>) {
        val admin = simulation.players[charId] ?: return
        if (!admin.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) {
            sendSystemMessage(session, "Save rejected for systems: switching systems on or off needs server maintenance rights.")
            return
        }
        val unknown = disabled.filter { OptionalModule.fromConfigKey(it) == null }
        if (unknown.isNotEmpty()) {
            sendSystemMessage(session, "Save rejected for systems: unknown system(s) ${unknown.joinToString()}.")
            return
        }
        val current = ServerConfig.load(dataStore.rootDir)
        val builtIn = OptionalModule.entries.associate { it.configKey to (it.configKey !in disabled) }
        val updated = current.copy(modules = current.modules.filterKeys { OptionalModule.fromConfigKey(it) == null } + builtIn)
        ServerConfig.save(dataStore.rootDir, updated)
        simulation.modules = updated.moduleFlags()
        recorder?.markGap("optional systems changed")
        sendSystemMessage(session, "Systems saved to ${ServerConfig.FILE_NAME}: " + (if (disabled.isEmpty()) "all on." else "off: ${disabled.sorted().joinToString()}."))
    }

    // ---- reload status (what an author of generated content reads) ------------------------------------------------------------

    private var lastContentResult: ContentReloadResult? = null
    private var lastRejected: List<com.morgana.domain.validation.RejectedDocument> = emptyList()
    private var lastAssetsUpdated: List<String> = emptyList()
    private var lastAssetErrors: List<String> = emptyList()
    private var gameJsonChanged: Boolean = false

    private fun publishReloadStatus(result: ContentReloadResult? = null, rejected: List<com.morgana.domain.validation.RejectedDocument>? = null) {
        if (result != null) { lastContentResult = result; lastRejected = rejected.orEmpty() }
        val shown = lastContentResult
        ReloadStatusFile.write(
            dataStore.gameDir,
            ReloadStatus(
                atMillis = System.currentTimeMillis(), applied = shown?.applied ?: true, summary = shown?.summary ?: "No content reload yet.",
                changed = shown?.changed.orEmpty(), rejected = lastRejected, warnings = shown?.report?.warnings.orEmpty(),
                assetsUpdated = lastAssetsUpdated, assetErrors = lastAssetErrors, restartRequired = gameJsonChanged
            )
        )
    }

    /**
     * Rescans `<game>/assets/` after files were added, changed or removed on disk, and sends every new or changed file to the players
     * who are connected (the same `GameArtUpdated` packet an `F1` art import uses), so generated art shows up without a relog. Files that
     * fail the art rules are left out and reported in the status file. Returns the manifest paths that were sent.
     */
    @Synchronized
    fun refreshAssets(): List<String> {
        recorder?.markGap("game art refreshed")
        val fresh = GameAssets.scan(dataStore.gameDir, simulation.game.definition.id, sprites = simulation.game.definition.sprites)
        val before = gameAssets.manifest.assets
        val changed = fresh.manifest.assets.values.filter { before[it.path]?.sha256 != it.sha256 }.sortedBy { it.path }
        gameAssets = fresh
        val sent = mutableListOf<String>()
        for (entry in changed) {
            val file = fresh.fileFor(entry.path) ?: continue
            val bytes = try { java.nio.file.Files.readAllBytes(file) } catch (_: Exception) { continue }
            val encoded = com.morgana.protocol.Base64Codec.encode(bytes)
            for (target in sessionByCharacterId.values) {
                val artV2 = clientCapabilitiesBySession[target.sessionId]?.contains(ProtocolCapabilities.ART_V2) == true
                if (artV2 || entry.kind == com.morgana.protocol.realms.AssetKind.TILESET || entry.kind == com.morgana.protocol.realms.AssetKind.SPRITE) {
                    target.send(ServerPacket.GameArtUpdated(entry = entry, manifest = fresh.manifestFor(artV2), pngBase64 = encoded))
                }
            }
            sent += entry.path
        }
        lastAssetsUpdated = sent
        lastAssetErrors = fresh.errors
        publishReloadStatus()
        return sent
    }

    /**
     * Called when `game.json` changed on disk. Game rules are compiled when the server starts, so a change cannot be swapped into the
     * running world; this records that a restart is needed (in the status file) when the file now differs from what is running.
     * Returns true when it differs.
     */
    @Synchronized
    fun noteGameFileChanged(): Boolean {
        val onDisk = try { GameDefinitionLoader.loadFor(dataStore.gameDir, dataStore.rootDir) } catch (_: Exception) { return false }
        gameJsonChanged = onDisk.definition != simulation.game.definition
        publishReloadStatus()
        return gameJsonChanged
    }

    private fun handleReloadCommand(session: ClientSession, charId: String) {
        val admin = simulation.players[charId] ?: return
        if (!admin.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) return
        val result = reloadContent()
        sendSystemMessage(session, result.summary)
        result.report.errors.take(5).forEach { sendSystemMessage(session, it.toString()) }
    }

    /**
     * Re-reads every content file under `<data>/world`, validates the complete set, and, only if it is
     * error-free, hot-swaps changed definitions into the live simulation and broadcasts them.
     * This is the same pipeline used by the `F1` editor, so hand/script/agent edits behave identically.
     * A map deleted from disk is unloaded and its players sent to the start; a changed map keeps its monsters and resource nodes
     * where their spawn slots did not change, and players on tiles that became blocked are moved to the nearest open tile.
     */
    @Synchronized
    fun reloadContent(): ContentReloadResult {
        recorder?.markGap("content hot reload")
        // Reload what the store actually serves (a game's data/ folder or the legacy world/), judged by this game's own rules.
        val loaded = ContentLoader.load(dataStore.contentDir)
        // Every file that is fine is applied; one that is not keeps its live version (or, if new, is left out) and is reported.
        val liveCatalog = com.morgana.domain.validation.ContentCatalog(
            maps = simulation.maps, classes = simulation.classes, items = simulation.items, npcs = simulation.npcs, spells = simulation.spells,
            shops = simulation.shops, resources = simulation.resources, recipes = simulation.recipes, quests = simulation.quests,
            events = simulation.events, commonEvents = simulation.commonEvents, animations = simulation.animations,
            actionSkills = simulation.actionSkills, weaponSets = simulation.weaponSets
        )
        val salvage = com.morgana.domain.validation.ContentSalvage.salvage(loaded.catalog, loaded.fileIssues, liveCatalog, simulation.game.definition)
        val report = salvage.report
        if (!salvage.applicable) {
            val result = ContentReloadResult(false, emptyList(), report, "Reload rejected: ${report.errors.size} error(s) that could not be set aside; live world unchanged.")
            publishReloadStatus(result, salvage.rejected)
            return result
        }
        val changed = mutableListOf<String>()
        fun <T> sync(label: String, live: MutableMap<Int, T>, incoming: Map<Int, T>) {
            val keys = live.keys + incoming.keys
            for (id in keys.sorted()) {
                val new = incoming[id]
                if (new == null) { live.remove(id); changed += "$label#$id removed" }
                else if (live[id] != new) { live[id] = new; changed += "$label#$id" }
            }
        }
        val c = salvage.catalog
        sync("class", simulation.classes, c.classes)
        sync("item", simulation.items, c.items)
        sync("npc", simulation.npcs, c.npcs)
        sync("spell", simulation.spells, c.spells)
        sync("shop", simulation.shops, c.shops)
        sync("resource", simulation.resources, c.resources)
        sync("recipe", simulation.recipes, c.recipes)
        sync("quest", simulation.quests, c.quests)
        sync("event", simulation.events, c.events)
        sync("commonEvent", simulation.commonEvents, c.commonEvents)
        sync("animation", simulation.animations, c.animations)
        sync("actionSkill", simulation.actionSkills, c.actionSkills)
        sync("weaponSet", simulation.weaponSets, c.weaponSets)

        simulation.scripts.reset()
        loadScripts()

        val events = mutableListOf<WorldSimulationEvent>()
        if (changed.isNotEmpty()) events += WorldSimulationEvent.ContentCatalogHotSwapped("Content reloaded: ${changed.size} definition(s) changed.")
        for (map in c.maps.values.sortedBy { it.id }) {
            val live = simulation.maps[map.id]
            if (live != null && live.copy(revision = 0) == map.copy(revision = 0)) continue
            val bumped = map.copy(revision = maxOf(map.revision, (live?.revision ?: 0) + 1))
            simulation.registerMap(bumped)
            changed += "map#${map.id}"
            events += WorldSimulationEvent.MapHotSwapped(bumped)
            // Someone standing where a wall now is gets moved to the nearest open tile.
            events += simulation.settlePlayersOn(map.id)
        }
        // A map whose file was deleted is unloaded, with its players sent to the start. An empty maps folder is never taken to mean
        // "delete everything" (a missing or mid-copy directory looks the same).
        if (c.maps.isNotEmpty()) {
            for (id in simulation.maps.keys.filter { it !in c.maps }.sorted()) {
                if (simulation.unloadContentMap(id, events)) changed += "map#$id removed"
            }
        }
        dispatchEvents(events)
        val warnings = if (report.warnings.isNotEmpty()) " (${report.warnings.size} warning(s))" else ""
        val left = if (salvage.rejected.isNotEmpty()) "; ${salvage.rejected.size} file(s) kept as they were because of errors" else ""
        val result = ContentReloadResult(true, changed, report, "Content reloaded: ${changed.size} change(s)$warnings$left.")
        publishReloadStatus(result, salvage.rejected)
        return result
    }

    private fun handleChat(session: ClientSession, charId: String, packet: ClientPacket.SendChat) {
        val sender = simulation.players[charId] ?: return
        val text = packet.message.trim()
        if (text.isEmpty()) return

        // Support slash commands typed directly into chat
        if (text.startsWith("/")) {
            if (!handleServerCommand(session, charId, text)) dispatchEvents(runInput(SimInput.AdminCommand(charId, text)))
            return
        }
        if ((packet.channel == ChatChannel.MAP || packet.channel == ChatChannel.EMOTE) && simulation.modules.isEnabled(OptionalModule.ROLE_PLAY_CHAT)) {
            val line = RolePlayChat.parseText(text, emoteChannel = packet.channel == ChatChannel.EMOTE)
            if (line.text.isNotEmpty()) deliverSpeech(session, sender, line)
            return
        }
        if (refuseIfMuted(session, sender)) return
        hookRegistry.dispatchPlayerChat(sender, packet.channel, text, simulation)

        val chatPacket = ServerPacket.ChatBroadcast(
            channel = packet.channel,
            senderEntityId = sender.id,
            senderName = sender.name,
            message = text,
            showOverheadBubble = packet.channel in setOf(ChatChannel.MAP, ChatChannel.EMOTE)
        )
        when (packet.channel) {
            ChatChannel.MAP, ChatChannel.EMOTE -> simulation.players.values.filter { it.position.mapId == sender.position.mapId }.forEach { deliverChat(it.id, sender, chatPacket) }
            ChatChannel.GLOBAL -> simulation.players.keys.toList().forEach { deliverChat(it, sender, chatPacket) }
            ChatChannel.BROADCAST, ChatChannel.SYSTEM -> broadcastToAll(chatPacket)
            ChatChannel.PARTY -> {
                val party = sender.partyId?.let { simulation.parties[it] } ?: return
                party.memberCharacterIds.forEach { memberId -> deliverChat(memberId, sender, chatPacket) }
            }
            ChatChannel.GUILD -> {
                val guild = sender.guildId?.let { simulation.guilds[it] } ?: return
                guild.memberRanks.keys.forEach { memberId -> deliverChat(memberId, sender, chatPacket) }
            }
            ChatChannel.WHISPER -> {
                val target = simulation.players.values.firstOrNull {
                    it.name.equals(packet.targetPlayerName, ignoreCase = true)
                }
                if (target != null) {
                    deliverChat(target.id, sender, chatPacket)
                    session.send(chatPacket)
                }
            }
        }
    }

    private fun handlePartyAction(charId: String, packet: ClientPacket.PartyAction) {
        recorder?.markGap("party action")
        val player = simulation.players[charId] ?: return
        when (packet.action) {
            PartyActionType.INVITE -> {
                val target = simulation.players.values.firstOrNull {
                    it.name.equals(packet.targetPlayerName, ignoreCase = true)
                } ?: run { sessionByCharacterId[charId]?.let { sendSystemMessage(it, "No player named \"${packet.targetPlayerName}\" is online.") }; return }
                if (target.id == charId) return
                if (isIgnoredBy(target.id, player)) { sessionByCharacterId[charId]?.let { sendSystemMessage(it, "Invitation sent to ${target.name}.") }; return }
                val partyId = player.partyId ?: "party-${UUID.randomUUID().toString().take(6)}"
                val partyCap = simulation.game.definition.gameplayTuning.maxPartySize
                if (partyCap > 0 && (simulation.parties[partyId]?.memberCharacterIds?.size ?: 1) >= partyCap) {
                    sessionByCharacterId[charId]?.let { sendSystemMessage(it, "Your party is full ($partyCap members).") }
                    return
                }
                val party = simulation.parties.getOrPut(partyId) {
                    PartyState(partyId, player.id, listOf(player.id))
                }
                val updatedLeader = player.copy(partyId = partyId)
                setPlayerState(updatedLeader)
                simulation.pendingPartyInvites[target.id] = partyId
                sessionByCharacterId[target.id]?.let { sendSystemMessage(it, "${player.name} invited you to a party. Open Party (P) and press Accept.") }
                sessionByCharacterId[charId]?.let { sendSystemMessage(it, "Invitation sent to ${target.name}.") }
                sessionByCharacterId[player.id]?.send(ServerPacket.PartyUpdated(party))
            }
            PartyActionType.ACCEPT -> {
                val partyId = simulation.pendingPartyInvites.remove(charId) ?: run { sessionByCharacterId[charId]?.let { sendSystemMessage(it, "You have no party invitation.") }; return }
                val party = simulation.parties[partyId] ?: return
                val acceptCap = simulation.game.definition.gameplayTuning.maxPartySize
                if (acceptCap > 0 && charId !in party.memberCharacterIds && party.memberCharacterIds.size >= acceptCap) {
                    sessionByCharacterId[charId]?.let { sendSystemMessage(it, "That party is full ($acceptCap members).") }
                    return
                }
                val nextParty = party.copy(memberCharacterIds = (party.memberCharacterIds + charId).distinct())
                simulation.parties[partyId] = nextParty
                val updated = player.copy(partyId = partyId)
                setPlayerState(updated)
                nextParty.memberCharacterIds.forEach { mId ->
                    sessionByCharacterId[mId]?.send(ServerPacket.PartyUpdated(nextParty))
                }
            }
            PartyActionType.LEAVE -> {
                val partyId = player.partyId ?: return
                val party = simulation.parties[partyId] ?: return
                val rem = party.memberCharacterIds - charId
                setPlayerState(player.copy(partyId = null))
                sessionByCharacterId[charId]?.send(ServerPacket.PartyUpdated(null))
                if (rem.isEmpty()) {
                    simulation.parties.remove(partyId)
                } else {
                    val nextParty = party.copy(
                        leaderCharacterId = rem.first(),
                        memberCharacterIds = rem
                    )
                    simulation.parties[partyId] = nextParty
                    rem.forEach { mId -> sessionByCharacterId[mId]?.send(ServerPacket.PartyUpdated(nextParty)) }
                }
            }
            PartyActionType.TOGGLE_XP_SHARE -> {
                val partyId = player.partyId ?: return
                val party = simulation.parties[partyId] ?: return
                val nextParty = party.copy(shareXp = !party.shareXp)
                simulation.parties[partyId] = nextParty
                nextParty.memberCharacterIds.forEach { mId ->
                    sessionByCharacterId[mId]?.send(ServerPacket.PartyUpdated(nextParty))
                }
            }
        }
    }

    /** Guild invitations waiting for an answer: invited character id to guild id. Transient, like party invitations. */
    private val pendingGuildInvites = HashMap<String, String>()

    private fun guildRank(rank: String): Int = when (rank) { GuildRules.FOUNDER -> 0; GuildRules.OFFICER -> 1; else -> 2 }

    private fun guildRoster(guild: GuildDocument): List<GuildMemberEntry> = guild.memberRanks.map { (id, rank) ->
        val live = simulation.players[id]
        GuildMemberEntry((live ?: dataStore.characters.findById(id))?.name ?: id, rank, live != null, live?.level ?: 0)
    }.sortedWith(compareBy<GuildMemberEntry>({ guildRank(it.rank) }, { !it.online }, { it.name.lowercase() }))

    private fun saveGuild(guild: GuildDocument) {
        simulation.guilds[guild.id] = guild
        dataStore.social.guilds.save(guild.id, guild)
    }

    /** Sends the guild and its roster to every online member. */
    private fun pushGuild(guild: GuildDocument) {
        val roster = guildRoster(guild)
        for (id in guild.memberRanks.keys) {
            val s = sessionByCharacterId[id] ?: continue
            s.send(ServerPacket.GuildUpdated(guild))
            s.send(ServerPacket.GuildRoster(roster))
        }
    }

    /** Sets (or clears, with a null [guildId]) a character's guild, online or stored. */
    private fun setGuildMembership(characterId: String, guildId: String?, rank: String) {
        val live = simulation.players[characterId]
        if (live != null) {
            val updated = live.copy(guildId = guildId, guildRank = if (guildId == null) "" else rank)
            setPlayerState(updated)
            sessionByCharacterId[characterId]?.send(characterSync(updated))
            broadcastToMap(updated.position.mapId, ServerPacket.PlayerUpdated(toPublicSnapshot(updated)))
            if (guildId == null) sessionByCharacterId[characterId]?.let { it.send(ServerPacket.GuildUpdated(null)); it.send(ServerPacket.GuildRoster(emptyList())) }
            // Someone shown out of the guild keeps no place in its hall.
            dispatchEvents(runInput(SimInput.EjectFromGuildHall(characterId)))
        } else {
            dataStore.characters.findById(characterId)?.let { persistenceWriter.saveCharacterNow(it.copy(guildId = guildId, guildRank = if (guildId == null) "" else rank)) }
        }
    }

    /** On login: makes the character's guild fields match the guild (a kick or disband may have happened while offline) and sends it. */
    private fun announceGuildOnLogin(session: ClientSession, character: CharacterState) {
        val id = character.guildId ?: return
        val guild = simulation.guilds[id]
        val rank = guild?.memberRanks?.get(character.id)
        if (guild == null || rank == null) {
            setGuildMembership(character.id, null, "")
            sendSystemMessage(session, "You are no longer in a guild.")
            return
        }
        if (character.guildRank != rank) setGuildMembership(character.id, id, rank)
        session.send(ServerPacket.GuildUpdated(guild))
        session.send(ServerPacket.GuildRoster(guildRoster(guild)))
        if (guild.motd.isNotBlank()) sendSystemMessage(session, "[${guild.tag}] ${guild.motd}")
    }

    private fun handleGuildAction(charId: String, packet: ClientPacket.GuildAction) {
        recorder?.markGap("guild action")
        val session = sessionByCharacterId[charId] ?: return
        val player = simulation.players[charId] ?: return
        val guild = player.guildId?.let { simulation.guilds[it] }
        val arg = packet.primaryArg.trim()
        fun say(text: String) = sendSystemMessage(session, text)
        fun memberByName(g: GuildDocument, name: String): String? =
            g.memberRanks.keys.firstOrNull { id -> ((simulation.players[id] ?: dataStore.characters.findById(id))?.name ?: "").equals(name, ignoreCase = true) }
        fun tellMembers(g: GuildDocument, text: String) = g.memberRanks.keys.forEach { id -> sessionByCharacterId[id]?.let { sendSystemMessage(it, text) } }

        when (packet.action) {
            GuildActionType.REFRESH -> if (guild != null) pushGuild(guild)
            GuildActionType.CREATE -> {
                if (guild != null) { say("Leave your current guild before creating another."); return }
                val tag = packet.secondaryArg.trim().ifBlank { arg.take(3) }
                GuildRules.creationProblem(arg, tag, simulation.guilds.values.map { it.name })?.let { say(it); return }
                val created = GuildRules.create("guild-${UUID.randomUUID().toString().take(6)}", arg, tag, charId)
                saveGuild(created)
                setGuildMembership(charId, created.id, GuildRules.FOUNDER)
                pushGuild(created)
                say("You founded ${created.name} [${created.tag}].")
            }
            GuildActionType.INVITE -> {
                if (guild == null) { say("You are not in a guild."); return }
                if (!GuildRules.canInvite(guild.memberRanks[charId])) { say("Only the founder and officers can invite."); return }
                val target = simulation.players.values.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                if (target == null) { say("No player named \"$arg\" is online."); return }
                if (target.guildId != null) { say("${target.name} is already in a guild."); return }
                if (!isIgnoredBy(target.id, player)) {
                    pendingGuildInvites[target.id] = guild.id
                    sessionByCharacterId[target.id]?.let { sendSystemMessage(it, "${player.name} invited you to ${guild.name}. Open the Guild window and press Accept.") }
                }
                say("Invitation sent to ${target.name}.")
            }
            GuildActionType.ACCEPT -> {
                if (guild != null) { say("Leave your current guild first."); return }
                val invited = pendingGuildInvites.remove(charId)?.let { simulation.guilds[it] } ?: run { say("You have no guild invitation."); return }
                when (val change = GuildRules.join(invited, charId, simulation.game.definition.gameplayTuning.maxGuildMembers)) {
                    is GuildRules.Change.Refused -> say(change.reason)
                    is GuildRules.Change.Updated -> {
                        saveGuild(change.guild)
                        setGuildMembership(charId, invited.id, GuildRules.MEMBER)
                        pushGuild(change.guild)
                        tellMembers(change.guild, "${player.name} joined the guild.")
                    }
                    GuildRules.Change.Disbanded -> {}
                }
            }
            GuildActionType.LEAVE -> {
                if (guild == null) { say("You are not in a guild."); return }
                when (val change = GuildRules.leave(guild, charId)) {
                    is GuildRules.Change.Refused -> say(change.reason)
                    GuildRules.Change.Disbanded -> {
                        simulation.guilds.remove(guild.id)
                        dataStore.social.guilds.deleteById(guild.id)
                        setGuildMembership(charId, null, "")
                        say("You left ${guild.name}, and it was disbanded.")
                    }
                    is GuildRules.Change.Updated -> {
                        saveGuild(change.guild)
                        setGuildMembership(charId, null, "")
                        if (change.guild.founderCharacterId != guild.founderCharacterId) setGuildMembership(change.guild.founderCharacterId, guild.id, GuildRules.FOUNDER)
                        pushGuild(change.guild)
                        tellMembers(change.guild, "${player.name} left the guild.")
                        say("You left ${guild.name}.")
                    }
                }
            }
            GuildActionType.KICK, GuildActionType.PROMOTE, GuildActionType.DEMOTE -> {
                if (guild == null) { say("You are not in a guild."); return }
                val targetId = memberByName(guild, arg) ?: run { say("\"$arg\" is not in your guild."); return }
                val change = when (packet.action) {
                    GuildActionType.KICK -> if (targetId == charId) GuildRules.Change.Refused("Use Leave to leave the guild.") else GuildRules.kick(guild, charId, targetId)
                    else -> GuildRules.setRank(guild, charId, targetId, promote = packet.action == GuildActionType.PROMOTE)
                }
                when (change) {
                    is GuildRules.Change.Refused -> say(change.reason)
                    GuildRules.Change.Disbanded -> {}
                    is GuildRules.Change.Updated -> {
                        saveGuild(change.guild)
                        if (packet.action == GuildActionType.KICK) {
                            setGuildMembership(targetId, null, "")
                            sessionByCharacterId[targetId]?.let { sendSystemMessage(it, "You were removed from ${guild.name}.") }
                            tellMembers(change.guild, "$arg was removed from the guild.")
                        } else {
                            val rank = change.guild.memberRanks.getValue(targetId)
                            setGuildMembership(targetId, guild.id, rank)
                            tellMembers(change.guild, "$arg is now ${if (rank == GuildRules.OFFICER) "an officer" else "a member"}.")
                        }
                        pushGuild(change.guild)
                    }
                }
            }
            GuildActionType.SET_MOTD -> {
                if (guild == null) { say("You are not in a guild."); return }
                when (val change = GuildRules.setMotd(guild, charId, packet.primaryArg)) {
                    is GuildRules.Change.Refused -> say(change.reason)
                    GuildRules.Change.Disbanded -> {}
                    is GuildRules.Change.Updated -> { saveGuild(change.guild); pushGuild(change.guild) }
                }
            }
        }
    }

    // --- Ignore list ---

    /** Whether [recipientId] ignores [sender]: their chat, whispers, mail and invitations do not reach them. Staff cannot be ignored. */
    private fun isIgnoredBy(recipientId: String, sender: CharacterState): Boolean {
        if (!simulation.modules.isEnabled(OptionalModule.IGNORE) || sender.accessLevel.rank > 0) return false
        val recipient = simulation.players[recipientId] ?: return false
        return IgnoreRules.contains(recipient.ignoredNames, sender.name)
    }

    /** Sends a chat line to one recipient unless they ignore its sender. */
    private fun deliverChat(recipientId: String, sender: CharacterState, packet: ServerPacket) {
        if (isIgnoredBy(recipientId, sender)) return
        sessionByCharacterId[recipientId]?.send(packet)
    }

    private fun sendIgnoreList(characterId: String) {
        val p = simulation.players[characterId] ?: return
        sessionByCharacterId[characterId]?.send(ServerPacket.IgnoreList(p.ignoredNames.sortedBy { it.lowercase() }))
    }

    private fun changeIgnore(charId: String, name: String, add: Boolean) {
        val session = sessionByCharacterId[charId] ?: return
        val player = simulation.players[charId] ?: return
        val typed = name.trim()
        val change = if (add) {
            val target = simulation.players.values.firstOrNull { it.name.equals(typed, ignoreCase = true) } ?: dataStore.characters.findByName(typed)
            when {
                target == null -> IgnoreRules.Change.Refused("No character named \"$typed\" exists.")
                target.accessLevel.rank > 0 -> IgnoreRules.Change.Refused("Staff cannot be ignored. Use Report if someone is bothering you.")
                else -> IgnoreRules.add(player.ignoredNames, player.name, target.name)
            }
        } else IgnoreRules.remove(player.ignoredNames, typed)
        when (change) {
            is IgnoreRules.Change.Refused -> sendSystemMessage(session, change.reason)
            is IgnoreRules.Change.Updated -> {
                recorder?.markGap("ignore")
                val updated = player.copy(ignoredNames = change.names)
                setPlayerState(updated)
                session.send(characterSync(updated))
                sendSystemMessage(session, if (add) "You now ignore ${typed.replaceFirstChar { it.uppercase() }}." else "You no longer ignore $typed.")
            }
        }
        sendIgnoreList(charId)
    }

    private fun handleIgnoreAction(charId: String, packet: ClientPacket.IgnoreAction) {
        when (packet.action) {
            IgnoreActionType.REFRESH -> sendIgnoreList(charId)
            IgnoreActionType.ADD -> changeIgnore(charId, packet.name, add = true)
            IgnoreActionType.REMOVE -> changeIgnore(charId, packet.name, add = false)
        }
    }

    /** `/ignore <name>`, `/unignore <name>` and `/ignorelist`. */
    private fun handleIgnoreCommand(session: ClientSession, charId: String, line: String): Boolean {
        val parts = line.trim().removePrefix("/").trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.lowercase() ?: return false
        if (command != "ignore" && command != "unignore" && command != "ignorelist") return false
        if (!simulation.modules.isEnabled(OptionalModule.IGNORE)) { sendSystemMessage(session, "Ignore list is switched off on this server."); return true }
        val name = parts.getOrNull(1).orEmpty()
        when {
            command == "ignorelist" -> {
                val names = simulation.players[charId]?.ignoredNames.orEmpty().sortedBy { it.lowercase() }
                sendSystemMessage(session, if (names.isEmpty()) "You are not ignoring anyone." else "Ignoring: ${names.joinToString(", ")}.")
            }
            name.isBlank() -> sendSystemMessage(session, "Usage: /$command <name>")
            else -> changeIgnore(charId, name, add = command == "ignore")
        }
        return true
    }

    /** The friends list of [owner] as clients show it: online friends (with level and map) first. */
    private fun friendEntries(owner: CharacterState): List<FriendEntry> {
        fun live(name: String) = simulation.players.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return FriendRules.sorted(owner.friendNames) { live(it) != null }.map { name ->
            val p = live(name)
            if (p != null) FriendEntry(p.name, true, p.level, p.position.mapId) else FriendEntry(name, false)
        }
    }

    private fun sendFriends(characterId: String) {
        val owner = simulation.players[characterId] ?: return
        sessionByCharacterId[characterId]?.send(ServerPacket.FriendList(friendEntries(owner)))
    }

    /** Tells every online player who has [name] on their list that it came online or went offline, and refreshes their list. */
    private fun notifyFriendWatchers(name: String, online: Boolean, exceptId: String) {
        if (!simulation.modules.isEnabled(OptionalModule.FRIENDS)) return
        for (watcher in simulation.players.values) {
            if (watcher.id == exceptId || !FriendRules.contains(watcher.friendNames, name)) continue
            val s = sessionByCharacterId[watcher.id] ?: continue
            sendSystemMessage(s, "$name is now ${if (online) "online" else "offline"}.")
            s.send(ServerPacket.FriendList(friendEntries(watcher)))
        }
    }

    /** On login: sends the player their list and lets the friends who list them know they are here. */
    private fun announceFriendsOnLogin(session: ClientSession, character: CharacterState) {
        if (!simulation.modules.isEnabled(OptionalModule.FRIENDS)) return
        if (character.friendNames.isNotEmpty()) {
            session.send(ServerPacket.FriendList(friendEntries(character)))
            val here = character.friendNames.filter { f -> simulation.players.values.any { it.name.equals(f, ignoreCase = true) } }
            if (here.isNotEmpty()) sendSystemMessage(session, "Friends online: ${here.joinToString(", ")}.")
        }
        notifyFriendWatchers(character.name, true, character.id)
    }

    private fun handleFriendAction(charId: String, packet: ClientPacket.FriendAction) {
        val session = sessionByCharacterId[charId] ?: return
        val player = simulation.players[charId] ?: return
        if (packet.action == FriendActionType.REFRESH) { sendFriends(charId); return }
        val typed = packet.name.trim()
        val change = when (packet.action) {
            FriendActionType.ADD -> {
                val target = simulation.players.values.firstOrNull { it.name.equals(typed, ignoreCase = true) }
                    ?: dataStore.characters.findByName(typed)
                if (target == null) FriendRules.Change.Refused("No character named \"$typed\" exists.")
                else FriendRules.add(player.friendNames, player.name, target.name)
            }
            else -> FriendRules.remove(player.friendNames, typed)
        }
        when (change) {
            is FriendRules.Change.Refused -> sendSystemMessage(session, change.reason)
            is FriendRules.Change.Updated -> {
                recorder?.markGap("friends")
                val updated = player.copy(friendNames = change.friends)
                setPlayerState(updated)
                session.send(characterSync(updated))
            }
        }
        sendFriends(charId)
    }

    private fun sendMarket(characterId: String) {
        val open = dataStore.social.marketListings.findAll()
            .sortedWith(compareByDescending<MarketListingDocument> { it.createdAtTick }.thenByDescending { it.id })
            .take(MarketRules.MAX_VISIBLE)
        sessionByCharacterId[characterId]?.send(ServerPacket.MarketListings(open))
    }

    /** A letter from the market itself (sale proceeds, returned items); it ignores the mailbox cap so nothing is ever lost. */
    private fun postMarketMail(recipientId: String, subject: String, body: String, attached: ItemStack) {
        val message = MailMessageDocument(
            id = "mail-${UUID.randomUUID().toString().take(10)}",
            senderName = "Market",
            recipientCharacterId = recipientId,
            subject = subject,
            body = body,
            attachedItem = attached,
            sentAtTick = simulation.effectiveWorldTick()
        )
        dataStore.social.mail.save(message.id, message)
        sessionByCharacterId[recipientId]?.let { s ->
            sendSystemMessage(s, subject)
            sendInbox(recipientId)
        }
    }

    /** Returns listings whose time ran out to their sellers by mail. */
    private fun expireMarketListings() {
        if (!simulation.modules.isEnabled(OptionalModule.MARKET)) return
        val now = simulation.effectiveWorldTick()
        for (listing in dataStore.social.marketListings.findAll()) {
            if (!MarketRules.isExpired(listing, now)) continue
            dataStore.social.marketListings.deleteById(listing.id)
            postMarketMail(listing.sellerCharacterId, "Your market listing expired", "Nobody bought it in time, so it came back to you.", listing.item)
        }
    }

    private fun handleMarketAction(charId: String, packet: ClientPacket.MarketAction) {
        recorder?.markGap("market")
        val session = sessionByCharacterId[charId] ?: return
        val player = simulation.players[charId] ?: return
        val currency = MarketRules.currencyItem(simulation.items)
        when (packet.action) {
            MarketActionType.BROWSE -> {}
            MarketActionType.LIST -> {
                if (!simulation.modules.isEnabled(OptionalModule.MAIL)) {
                    sendSystemMessage(session, "The market pays sellers by mail, and mail is switched off on this server.")
                    sendMarket(charId)
                    return
                }
                val stack = player.inventory.getSlot(packet.inventorySlotIndex)
                if (stack == null) { sendSystemMessage(session, "There is nothing in that slot."); return }
                val mine = dataStore.social.marketListings.findAll().count { it.sellerCharacterId == charId }
                MarketRules.listingProblem(simulation.items[stack.itemId], currency, packet.price, mine)?.let { sendSystemMessage(session, it); return }
                val (bag, taken) = player.inventory.removeFromSlot(packet.inventorySlotIndex, packet.amount)
                    ?: run { sendSystemMessage(session, "You do not have that many."); return }
                val listing = MarketListingDocument(
                    id = "mkt-${UUID.randomUUID().toString().take(10)}",
                    sellerCharacterId = charId,
                    sellerName = player.name,
                    item = taken,
                    priceCurrencyAmount = packet.price,
                    createdAtTick = simulation.effectiveWorldTick()
                )
                dataStore.social.marketListings.save(listing.id, listing)
                val updated = player.copy(inventory = bag)
                setPlayerState(updated)
                session.send(characterSync(updated))
                sendSystemMessage(session, "Listed ${simulation.items[taken.itemId]?.name ?: "item"} x${taken.amount} for ${packet.price}. The market keeps ${MarketRules.TAX_PERCENT}% when it sells.")
            }
            MarketActionType.BUY -> {
                val listing = dataStore.social.marketListings.findById(packet.listingId)
                if (listing == null || MarketRules.isExpired(listing, simulation.effectiveWorldTick())) {
                    sendSystemMessage(session, "That listing is gone.")
                } else {
                    when (val result = MarketRules.buy(listing, charId, player.inventory, simulation.items[listing.item.itemId], currency)) {
                        is MarketRules.Purchase.Refused -> sendSystemMessage(session, result.reason)
                        is MarketRules.Purchase.Bought -> {
                            dataStore.social.marketListings.deleteById(listing.id)
                            val updated = player.copy(inventory = result.bag)
                            setPlayerState(updated)
                            session.send(characterSync(updated))
                            val itemName = simulation.items[listing.item.itemId]?.name ?: "item"
                            sendSystemMessage(session, "You bought $itemName x${listing.item.amount} for ${listing.priceCurrencyAmount}.")
                            val payout = MarketRules.proceeds(listing.priceCurrencyAmount)
                            postMarketMail(
                                listing.sellerCharacterId,
                                "Your $itemName sold for ${listing.priceCurrencyAmount}",
                                "${player.name} bought it. The market kept ${MarketRules.tax(listing.priceCurrencyAmount)}.",
                                ItemStack(currency!!.id, payout)
                            )
                        }
                    }
                }
            }
            MarketActionType.CANCEL -> {
                val listing = dataStore.social.marketListings.findById(packet.listingId)?.takeIf { it.sellerCharacterId == charId }
                val def = listing?.let { simulation.items[it.item.itemId] }
                if (listing == null || def == null) {
                    sendSystemMessage(session, "That listing is gone.")
                } else if (player.inventory.roomFor(def) < listing.item.amount) {
                    sendSystemMessage(session, "Your bag is too full to take ${def.name} x${listing.item.amount} back.")
                } else {
                    val bag = player.inventory.addStack(def, listing.item).updatedInventory
                    dataStore.social.marketListings.deleteById(listing.id)
                    val updated = player.copy(inventory = bag)
                    setPlayerState(updated)
                    session.send(characterSync(updated))
                    sendSystemMessage(session, "You took ${def.name} x${listing.item.amount} off the market.")
                }
            }
        }
        sendMarket(charId)
    }

    // --- Game wiki ---

    private var wikiCache: Map<String, ByteArray> = emptyMap()
    private var wikiBuiltAtMs = 0L

    /**
     * The game wiki's files, built from the content now in memory (so live edits and reloads show up) and kept for
     * [WIKI_CACHE_MS] so a busy page does not rebuild it per request. See [com.morgana.server.tools.wiki.GameWiki].
     */
    @Synchronized
    fun gameWikiPages(): Map<String, ByteArray> {
        val now = System.currentTimeMillis()
        if (wikiCache.isEmpty() || now - wikiBuiltAtMs > WIKI_CACHE_MS) {
            wikiCache = com.morgana.server.tools.wiki.GameWiki(simulation.game, simulation.toContentCatalog()).pages()
            wikiBuiltAtMs = now
        }
        return wikiCache
    }

    // --- Exploration (fog of war and the world map) ---

    /** What each online character has explored, loaded at login and written back when it changes. */
    private val exploration = HashMap<String, ExplorationDocument>()

    private fun exploredInfo(map: com.morgana.domain.map.MapDefinition, explored: ExploredMap) = ExploredMapInfo(
        map.id, map.name, map.width, map.height,
        map.neighbors.northMapId, map.neighbors.southMapId, map.neighbors.eastMapId, map.neighbors.westMapId, explored
    )

    /** On login: loads the character's exploration and sends every explored map that still exists. */
    private fun announceExplorationOnLogin(session: ClientSession, character: CharacterState) {
        if (!simulation.modules.isEnabled(OptionalModule.EXPLORATION)) return
        val doc = dataStore.social.exploration.findById(character.id) ?: ExplorationDocument(character.id)
        exploration[character.id] = doc
        session.send(ServerPacket.Exploration(doc.maps.mapNotNull { (mapId, explored) -> simulation.maps[mapId]?.let { exploredInfo(it, explored) } }))
    }

    /** Answers [ClientPacket.RequestWorldMap]: a picture and the spawn points of each map the character has explored. */
    private fun sendWorldMapDetail(session: ClientSession) {
        val charId = session.characterId ?: return
        if (!simulation.modules.isEnabled(OptionalModule.EXPLORATION)) {
            session.send(ServerPacket.WorldMapDetail(emptyList()))
            return
        }
        val doc = exploration[charId] ?: dataStore.social.exploration.findById(charId) ?: ExplorationDocument(charId)
        val entries = doc.maps.keys.sorted().mapNotNull { mapId ->
            val map = simulation.maps[mapId] ?: return@mapNotNull null
            val thumb = com.morgana.domain.explore.WorldMapTerrain.thumbnail(map)
            com.morgana.protocol.WorldMapDetailEntry(
                mapId, thumb.scale, thumb.cols, thumb.rows, thumb.cells,
                map.npcSpawns.map { com.morgana.protocol.WorldMapSpawn(it.npcId, it.spawnX, it.spawnY) }
            )
        }
        session.send(ServerPacket.WorldMapDetail(entries))
    }

    /** Reveals the ground around every online character; saves and sends only the maps that changed. */
    private fun updateExploration() {
        if (!simulation.modules.isEnabled(OptionalModule.EXPLORATION)) return
        for (player in simulation.players.values) {
            val session = sessionByCharacterId[player.id] ?: continue
            val mapId = player.position.mapId
            if (MapInstances.isInstanceId(mapId)) continue
            val map = simulation.maps[mapId] ?: continue
            val doc = exploration[player.id] ?: continue
            val revealed = Exploration.reveal(doc.maps[mapId], map.width, map.height, player.position.tileX, player.position.tileY) ?: continue
            val next = doc.copy(maps = doc.maps + (mapId to revealed))
            exploration[player.id] = next
            persistenceWriter.enqueueExploration(next)
            session.send(ServerPacket.ExplorationUpdate(exploredInfo(map, revealed)))
        }
    }

    private fun sendInbox(characterId: String) {
        sessionByCharacterId[characterId]?.send(ServerPacket.MailInbox(dataStore.social.mailFor(characterId)))
    }

    /** On login: sends the mailbox when it holds anything and says how many letters are unread. */
    private fun announceMailOnLogin(session: ClientSession, characterId: String) {
        if (!simulation.modules.isEnabled(OptionalModule.MAIL)) return
        val box = dataStore.social.mailFor(characterId)
        if (box.isEmpty()) return
        session.send(ServerPacket.MailInbox(box))
        val unread = box.count { !it.read }
        if (unread > 0) sendSystemMessage(session, "You have $unread unread letter${if (unread == 1) "" else "s"}.")
    }

    private fun handleSendMail(charId: String, packet: ClientPacket.SendMail) {
        recorder?.markGap("mail")
        val session = sessionByCharacterId[charId] ?: return
        val sender = simulation.players[charId] ?: return
        val recipientName = packet.recipientName.trim()
        MailRules.textProblem(packet.subject.trim(), packet.body)?.let { sendSystemMessage(session, it); return }
        val recipient = simulation.players.values.firstOrNull { it.name.equals(recipientName, ignoreCase = true) }
            ?: dataStore.characters.findByName(recipientName)
        if (recipient == null) { sendSystemMessage(session, "No character named \"$recipientName\" exists."); return }
        if (recipient.id == charId) { sendSystemMessage(session, "You cannot mail yourself."); return }
        if (simulation.modules.isEnabled(OptionalModule.IGNORE) && sender.accessLevel.rank == 0 && IgnoreRules.contains(recipient.ignoredNames, sender.name)) {
            sendSystemMessage(session, "${recipient.name} is not accepting letters from you.")
            return
        }
        if (!MailRules.hasRoom(dataStore.social.mailFor(recipient.id))) {
            sendSystemMessage(session, "${recipient.name}'s mailbox is full.")
            return
        }
        var attached: ItemStack? = null
        var senderAfter = sender
        if (packet.inventorySlotIndex >= 0) {
            val stack = sender.inventory.getSlot(packet.inventorySlotIndex)
            if (stack == null) { sendSystemMessage(session, "There is nothing in that slot."); return }
            MailRules.attachmentProblem(simulation.items[stack.itemId])?.let { sendSystemMessage(session, it); return }
            val (bag, taken) = MailRules.takeAttachment(sender.inventory, packet.inventorySlotIndex, packet.amount)
                ?: run { sendSystemMessage(session, "You do not have that many."); return }
            attached = taken
            senderAfter = sender.copy(inventory = bag)
        }
        val message = MailMessageDocument(
            id = "mail-${UUID.randomUUID().toString().take(10)}",
            senderName = sender.name,
            recipientCharacterId = recipient.id,
            subject = packet.subject.trim(),
            body = packet.body,
            attachedItem = attached,
            sentAtTick = simulation.effectiveWorldTick()
        )
        dataStore.social.mail.save(message.id, message)
        if (attached != null) {
            setPlayerState(senderAfter)
            session.send(characterSync(senderAfter))
        }
        sendSystemMessage(session, "Letter sent to ${recipient.name}.")
        sessionByCharacterId[recipient.id]?.let { target ->
            sendSystemMessage(target, "New letter from ${sender.name}: ${message.subject}")
            sendInbox(recipient.id)
        }
    }

    private fun handleMailAction(charId: String, packet: ClientPacket.MailAction) {
        recorder?.markGap("mail")
        val session = sessionByCharacterId[charId] ?: return
        if (packet.action == MailActionType.REFRESH) { sendInbox(charId); return }
        val message = dataStore.social.mail.findById(packet.mailId)?.takeIf { it.recipientCharacterId == charId } ?: run {
            sendSystemMessage(session, "That letter is gone.")
            sendInbox(charId)
            return
        }
        when (packet.action) {
            MailActionType.READ -> if (!message.read) dataStore.social.mail.save(message.id, message.copy(read = true))
            MailActionType.DELETE -> {
                if (!MailRules.canDelete(message)) sendSystemMessage(session, "Take the attachment before deleting this letter.")
                else dataStore.social.mail.deleteById(message.id)
            }
            MailActionType.CLAIM -> {
                val player = simulation.players[charId] ?: return
                val itemDef = message.attachedItem?.let { simulation.items[it.itemId] }
                when (val result = MailRules.claim(message, player.inventory, itemDef)) {
                    is MailRules.Claim.Refused -> sendSystemMessage(session, result.reason)
                    is MailRules.Claim.Taken -> {
                        val updated = player.copy(inventory = result.bag)
                        setPlayerState(updated)
                        dataStore.social.mail.save(message.id, result.message)
                        session.send(characterSync(updated))
                        sendSystemMessage(session, "You took ${itemDef?.name ?: "the item"} from the letter.")
                    }
                }
            }
            MailActionType.REFRESH -> {}
        }
        sendInbox(charId)
    }

    private fun handleTradeAction(charId: String, packet: ClientPacket.TradeAction) {
        when (packet.action) {
            TradeActionType.REQUEST -> {
                val target = simulation.players.values.firstOrNull {
                    it.name.equals(packet.targetPlayerName, ignoreCase = true)
                } ?: return
                dispatchEvents(runInput(SimInput.InitiateTrade(charId, target.id)))
            }
            TradeActionType.ADD_ITEM -> {
                dispatchEvents(runInput(SimInput.AddTradeOfferItem(charId, packet.inventorySlotIndex, packet.amount)))
            }
            TradeActionType.LOCK -> {
                dispatchEvents(runInput(SimInput.ConfirmTrade(charId, false)))
            }
            TradeActionType.CONFIRM -> {
                dispatchEvents(runInput(SimInput.ConfirmTrade(charId, true)))
            }
            TradeActionType.CANCEL -> {
                val session = simulation.tradeSessions.values.firstOrNull {
                    it.initiatorId == charId || it.partnerId == charId
                } ?: return
                simulation.tradeSessions.remove(session.sessionId)
                dispatchEvents(
                    listOf(WorldSimulationEvent.TradeUpdated(listOf(session.initiatorId, session.partnerId), null))
                )
            }
        }
    }

    /**
     * Indexes of events whose public `PlayerUpdated` broadcast is dropped because a later event of the same batch tells the
     * same map about the same player again (one step of walking produces both a move and a character update, and each used
     * to send the whole snapshot to everyone on the map). The last one carries the final state.
     */
    private fun supersededPlayerBroadcasts(events: List<WorldSimulationEvent>): Set<Int> {
        if (events.size < 2) return emptySet()
        val laterSeen = HashSet<Pair<String, Int>>()
        var skipped: MutableSet<Int>? = null
        for (i in events.indices.reversed()) {
            val key = when (val ev = events[i]) {
                is WorldSimulationEvent.CharacterUpdated -> ev.character.id to ev.character.position.mapId
                is WorldSimulationEvent.PlayerMoved -> ev.character.id to ev.currentMapId
                else -> continue
            }
            if (!laterSeen.add(key)) (skipped ?: HashSet<Int>().also { skipped = it }).add(i)
        }
        return skipped ?: emptySet()
    }

    /** Area-of-interest buckets from the `areaOfInterest` section of `server.json`; null (the default) sends every map change to everyone on the map. */
    private val areaOfInterest: AreaOfInterest? =
        ServerConfig.load(dataStore.rootDir).areaOfInterest?.takeIf { it.enabled }?.let { AreaOfInterest(it.cellTiles, it.radiusCells) }

    /** Where each broadcast entity (`p:<character id>`, `n:<npc instance id>`) was when it was last announced, so a move out of view still reaches the viewers who saw it leave. */
    private val lastEntityTile = HashMap<String, Pair<Int, Int>>()

    /** Each online player's map and tile when their area of interest was last brought up to date. */
    private val viewerAreas = HashMap<String, Triple<Int, Int, Int>>()

    /** What each online character's quest guides were last worked out from (map and quest progress), so they are re-sent only on a change. */
    private val questGuideKeys = HashMap<String, Int>()

    /** Sends [character] directions to quest targets on other maps when their map or quest progress changed since the last send. */
    private fun sendQuestGuides(character: CharacterState) {
        val session = sessionByCharacterId[character.id] ?: return
        val active = character.quests.values.filter { it.status == com.morgana.domain.event.QuestStatus.IN_PROGRESS }
        val key = 31 * character.position.mapId + active.map { it.questId to it.objectiveCounts }.hashCode()
        if (questGuideKeys[character.id] == key) return
        questGuideKeys[character.id] = key
        val fixed = simulation.maps.filterKeys { !com.morgana.domain.sim.MapInstances.isInstanceId(it) }
        val guides = com.morgana.domain.map.QuestGuides.compute(
            fixed, character.position.mapId, character.position.tileX, character.position.tileY, active.associateBy { it.questId }, simulation.quests
        )
        session.send(ServerPacket.QuestGuides(guides))
    }

    private fun dispatchEvents(events: List<WorldSimulationEvent>) {
        val superseded = supersededPlayerBroadcasts(events)
        for ((index, ev) in events.withIndex()) {
            when (ev) {
                is WorldSimulationEvent.CharacterUpdated -> {
                    sessionByCharacterId[ev.character.id]?.send(characterSync(ev.character))
                    sendQuestGuides(ev.character)
                    if (index !in superseded) broadcastPlayerUpdate(ev.character)
                    syncViewerArea(ev.character)
                    tips.onCharacterUpdated(ev.character.id)
                }
                is WorldSimulationEvent.PlayerMoved -> {
                    if (ev.previousMapId != ev.currentMapId) {
                        broadcastToMap(ev.previousMapId, ServerPacket.PlayerLeftMap(ev.character.id, ev.previousMapId))
                        lastEntityTile.remove("p:${ev.character.id}")
                        sessionByCharacterId[ev.character.id]?.let { sendFullMapSnapshotTo(it, ev.currentMapId) }
                        viewerAreas[ev.character.id] = Triple(ev.currentMapId, ev.character.position.tileX, ev.character.position.tileY)
                    }
                    if (index !in superseded) broadcastPlayerUpdate(ev.character)
                    syncViewerArea(ev.character)
                }
                // For the server and systems only: players hear a schedule through the chat announcement it also emitted.
                is WorldSimulationEvent.ScheduleTriggered -> Unit
                is WorldSimulationEvent.MapRuntimeReset -> {
                    simulation.players.values.filter { it.position.mapId == ev.mapId }
                        .forEach { p -> sessionByCharacterId[p.id]?.let { sendFullMapSnapshotTo(it, ev.mapId) } }
                }
                is WorldSimulationEvent.WorldObjectsChanged -> broadcastWorldObjects(ev.mapId, ServerPacket.WorldObjectsUpdated(ev.mapId, ev.upserted, ev.removedIds))
                is WorldSimulationEvent.BossTelegraph -> {
                    broadcastNear(ev.mapId, ev.centerX, ev.centerY, ServerPacket.BossTelegraph(ev.mapId, ev.centerX, ev.centerY, ev.radius, ev.windUpTicks, ev.label))
                }
                is WorldSimulationEvent.NpcUpdated -> {
                    broadcastNpcUpdate(ev.npc)
                }
                is WorldSimulationEvent.GroundItemsChanged -> {
                    broadcastToMap(ev.mapId, ServerPacket.GroundItemsUpdated(ev.mapId, ev.items))
                }
                is WorldSimulationEvent.OrbsChanged -> {
                    broadcastToMap(ev.mapId, ServerPacket.HealthOrbsUpdated(ev.mapId, ev.orbs))
                }
                is WorldSimulationEvent.ProjectilesChanged -> {
                    broadcastToMap(ev.mapId, ServerPacket.ProjectilesUpdated(ev.mapId, ev.projectiles))
                }
                is WorldSimulationEvent.ResourceNodeChanged -> {
                    broadcastNear(ev.resource.mapId, ev.resource.tileX, ev.resource.tileY, ServerPacket.ResourceNodeUpdated(ev.resource))
                }
                is WorldSimulationEvent.FloatingTextEmitted -> {
                    val cat = runCatching { FloatingTextCategory.valueOf(ev.category) }.getOrDefault(FloatingTextCategory.DAMAGE)
                    val pkt = ServerPacket.FloatingText(ev.mapId, ev.tileX, ev.tileY, ev.text, cat, ev.colorHex)
                    val targetId = ev.targetCharacterId
                    if (!targetId.isNullOrEmpty()) {
                        sessionByCharacterId[targetId]?.send(pkt)
                    } else {
                        broadcastNear(ev.mapId, ev.tileX, ev.tileY, pkt)
                    }
                }
                is WorldSimulationEvent.MomentumChanged -> {
                    sessionByCharacterId[ev.characterId]?.send(ServerPacket.Momentum(ev.momentum, ev.empoweredTicks))
                }
                is WorldSimulationEvent.SkillEffect -> {
                    broadcastNear(
                        ev.mapId, ev.tileX, ev.tileY,
                        ServerPacket.SkillEffect(ev.mapId, ev.effect, ev.fromX, ev.fromY, ev.toX, ev.toY, ev.radius, ev.delayTicks, ev.spanTicks, ev.sourceId)
                    )
                }
                is WorldSimulationEvent.AnimationTriggered -> {
                    broadcastNear(
                        ev.mapId, ev.tileX, ev.tileY,
                        ServerPacket.TriggerAnimation(ev.mapId, ev.tileX, ev.tileY, ev.animationId, ev.soundId, ev.casterEntityId, ev.spellId)
                    )
                }
                is WorldSimulationEvent.SoundPlayed -> {
                    val pkt = ServerPacket.PlaySound(ev.soundId)
                    if (ev.characterId.isNotEmpty()) {
                        sessionByCharacterId[ev.characterId]?.send(pkt)
                    } else if (ev.mapId > 0) {
                        broadcastToMap(ev.mapId, pkt)
                    }
                }
                is WorldSimulationEvent.PictureShown -> {
                    sessionByCharacterId[ev.characterId]?.send(
                        ServerPacket.ShowPicture(ev.pictureId, ev.title, ev.durationTicks)
                    )
                }
                is WorldSimulationEvent.ChatEmitted -> {
                    val ch = runCatching { ChatChannel.valueOf(ev.channel) }.getOrDefault(ChatChannel.SYSTEM)
                    val pkt = ServerPacket.ChatBroadcast(
                        channel = ch,
                        senderEntityId = ev.senderId,
                        senderName = ev.senderName,
                        message = ev.message,
                        showOverheadBubble = ev.showOverheadBubble,
                        colorHex = ev.colorHex,
                        notice = ev.notice?.let { n -> runCatching { com.morgana.protocol.NoticeKind.valueOf(n) }.getOrNull() }
                    )
                    if (ev.targetCharacterId != null) {
                        sessionByCharacterId[ev.targetCharacterId]?.send(pkt)
                    } else if (ev.mapId <= 0) {
                        broadcastToAll(pkt)
                    } else {
                        broadcastToMap(ev.mapId, pkt)
                    }
                }
                is WorldSimulationEvent.GuildBankChanged -> simulation.guilds[ev.guildId]?.let { guild ->
                    // Save the guild, then show the new vault to every online member.
                    dataStore.social.guilds.save(guild.id, guild)
                    for (id in guild.memberRanks.keys) sessionByCharacterId[id]?.send(ServerPacket.GuildUpdated(guild))
                }
                is WorldSimulationEvent.OpenUiRequested -> {
                    val winType = runCatching { ServerUiWindowType.valueOf(ev.windowType) }.getOrDefault(ServerUiWindowType.DIALOGUE)
                    sessionByCharacterId[ev.characterId]?.send(
                        ServerPacket.OpenUiWindow(
                            windowType = winType,
                            shopId = ev.shopId,
                            stationType = ev.stationType,
                            eventId = ev.eventId,
                            speakerName = ev.speakerName,
                            dialogueText = ev.dialogueText,
                            choiceLabels = ev.choiceLabels
                        )
                    )
                    // A line with no answers pauses the event until the client says it was closed. A client that cannot say so
                    // (an older one, a script, or nobody connected) gets the rest of the event at once, as before.
                    if (winType == ServerUiWindowType.DIALOGUE && ev.choiceLabels.isEmpty() &&
                        simulation.pendingDialogues[ev.characterId]?.eventId == ev.eventId
                    ) {
                        val sessionId = sessionByCharacterId[ev.characterId]?.sessionId
                        val waits = sessionId != null && clientCapabilitiesBySession[sessionId]?.contains(ProtocolCapabilities.DIALOGUE_FLOW) == true
                        if (!waits) dispatchEvents(runInput(SimInput.SelectDialogueChoice(ev.characterId, ev.eventId, -1)))
                    }
                }
                is WorldSimulationEvent.TextInputRequested -> {
                    val session = sessionByCharacterId[ev.characterId]
                    val waits = session != null && clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.DIALOGUE_FLOW) == true
                    if (waits) {
                        session.send(
                            ServerPacket.TextInputPrompt(
                                eventId = ev.eventId,
                                prompt = ev.prompt,
                                isNumeric = ev.isNumeric,
                                defaultValue = ev.defaultValue,
                                maxLength = ev.maxLength
                            )
                        )
                    } else {
                        dispatchEvents(runInput(SimInput.SubmitTextInput(ev.characterId, ev.eventId, ev.defaultValue)))
                    }
                }
                is WorldSimulationEvent.ShopBuybackChanged ->
                    sessionByCharacterId[ev.characterId]?.send(ServerPacket.ShopBuyback(ev.shopId, ev.entries))
                is WorldSimulationEvent.TradeUpdated -> {
                    ev.participantIds.forEach { pid ->
                        sessionByCharacterId[pid]?.send(ServerPacket.TradeUpdated(ev.session))
                    }
                }
                is WorldSimulationEvent.StallViewed -> sessionByCharacterId[ev.viewerId]?.send(ServerPacket.StallViewed(ev.view))
                is WorldSimulationEvent.PartyUpdated -> {
                    ev.memberIds.forEach { pid ->
                        sessionByCharacterId[pid]?.send(ServerPacket.PartyUpdated(ev.party))
                    }
                }
                is WorldSimulationEvent.GuildWarResult -> {
                    ev.guilds.forEach { saveGuild(it); pushGuild(it) }
                }
                is WorldSimulationEvent.GuildUpdated -> {
                    ev.memberIds.forEach { pid ->
                        sessionByCharacterId[pid]?.send(ServerPacket.GuildUpdated(ev.guild))
                    }
                }
                is WorldSimulationEvent.PlayerModerated -> {
                    sessionByCharacterId[ev.targetCharacterId]?.close(ev.reason)
                }
                is WorldSimulationEvent.MapHotSwapped -> {
                    broadcastToAll(ServerPacket.MapDefinitionUpdated(ev.map))
                    // Every connection just received this revision, so later snapshots need not resend it.
                    mapRevisionsSent.values.forEach { it[ev.map.id] = ev.map.revision }
                    // Also send fresh snapshots to players on the edited map
                    simulation.players.values
                        .filter { it.position.mapId == ev.map.id }
                        .forEach { p -> sessionByCharacterId[p.id]?.let { sendFullMapSnapshotTo(it, ev.map.id) } }
                }
                is WorldSimulationEvent.CustomHookRequested -> {
                    simulation.players[ev.characterId]?.let { hookRegistry.dispatchCustomHook(it, ev.hookId, ev.payload, simulation) }
                }
                is WorldSimulationEvent.ItemUsed -> {
                    simulation.players[ev.characterId]?.let { hookRegistry.dispatchItemUsed(it, ev.itemId, simulation) }
                }
                is WorldSimulationEvent.SpellCast -> {
                    simulation.players[ev.characterId]?.let { hookRegistry.dispatchSpellCast(it, ev.spellId, simulation) }
                }
                is WorldSimulationEvent.SpellCooldownStarted -> {
                    sessionByCharacterId[ev.characterId]?.send(ServerPacket.SpellCooldown(ev.spellId, ev.durationTicks))
                }
                is WorldSimulationEvent.SpellCastStarted -> {
                    // Casting state is already broadcast via CharacterUpdated
                }
                is WorldSimulationEvent.SpellCastInterrupted -> {
                    // Interrupted state is already broadcast via CharacterUpdated
                }
                is WorldSimulationEvent.NpcKilled -> {
                    hookRegistry.dispatchNpcKilled(ev.npcInstanceId, ev.npcId, ev.mapId, ev.killerCharacterId?.let { simulation.players[it] }, simulation)
                }
                is WorldSimulationEvent.PlayerDied -> {
                    simulation.players[ev.characterId]?.let { hookRegistry.dispatchPlayerDeath(it, simulation) }
                    tips.onDeath(ev.characterId)
                }
                is WorldSimulationEvent.ContentCatalogHotSwapped -> {
                    broadcastToAll(ServerPacket.ContentCatalogUpdated(buildCatalogDto(), ev.summary))
                }
                is WorldSimulationEvent.InstanceClosed -> {
                    // Nobody is inside a closed instance, so there is nothing to tell clients.
                }
                is WorldSimulationEvent.CharacterPermadeath -> {
                    val fallenChar = simulation.players.remove(ev.characterId)
                    val session = sessionByCharacterId.remove(ev.characterId)
                    if (session != null) {
                        session.characterId = null
                    }
                    if (ev.mode == com.morgana.domain.game.PermadeathMode.DELETE) {
                        persistenceWriter.invalidateCharacter(ev.characterId)
                        dataStore.inTransaction {
                            dataStore.characters.delete(ev.characterId)
                            dataStore.social.exploration.deleteById(ev.characterId)
                            val accId = fallenChar?.accountId
                            if (accId != null) {
                                dataStore.accounts.findById(accId)?.let { acc ->
                                    dataStore.accounts.save(acc.copy(characterIds = acc.characterIds - ev.characterId))
                                }
                            }
                        }
                    } else if (fallenChar != null) {
                        persistenceWriter.saveCharacterNow(fallenChar)
                    }
                    if (session != null) {
                        val reason = if (ev.mode == com.morgana.domain.game.PermadeathMode.DELETE) {
                            "${ev.characterName} has fallen permanently and been erased."
                        } else {
                            "${ev.characterName} has fallen permanently and entered the Hall of the Fallen."
                        }
                        session.send(ServerPacket.SessionEnded(reason, resumable = false))
                    }
                }
                is WorldSimulationEvent.RunEnded -> {
                    // Summary notice was emitted via ChatEmitted; future leaderboard persistence hooks in here.
                }
            }
        }
    }

    /** Sends each player near a border the entities just across it, but only when they changed. */
    private fun sendSeamViews() {
        if (!simulation.modules.isEnabled(OptionalModule.SOFT_SEAMS)) return
        for ((charId, session) in sessionByCharacterId) {
            val caps = clientCapabilitiesBySession[session.sessionId]
            if (caps != null && ProtocolCapabilities.SOFT_SEAMS !in caps) continue
            val player = simulation.players[charId] ?: continue
            val views = seamViews.compute(player)
            if (seamViews.changed(charId, views)) session.send(ServerPacket.SeamView(views))
        }
    }

    /** The client could not rebuild its maps from cache: forget what was sent and send the current map in full. */
    private fun resyncMaps(session: ClientSession) {
        mapRevisionsSent.remove(session.sessionId)
        val charId = session.characterId ?: return
        val mapId = simulation.players[charId]?.position?.mapId ?: return
        sendFullMapSnapshotTo(session, mapId)
    }

    internal fun sendFullMapSnapshotTo(session: ClientSession, mapId: Int) {
        val map = simulation.maps[mapId] ?: return
        val neighborMaps = mutableMapOf<Int, MapDefinition>()
        listOf(
            map.neighbors.northMapId,
            map.neighbors.southMapId,
            map.neighbors.eastMapId,
            map.neighbors.westMapId
        ).filter { it > 0 }.forEach { nId ->
            simulation.maps[nId]?.let { neighborMaps[nId] = it }
        }

        val playersOnMap = simulation.players.values
            .filter { it.position.mapId == mapId }
            .map { toPublicSnapshot(it) }
        val npcsOnMap = simulation.mapNpcs[mapId]?.toList().orEmpty()
        val groundOnMap = simulation.mapGroundItems[mapId]?.toList().orEmpty()
        val projOnMap = simulation.mapProjectiles[mapId]?.toList().orEmpty()
        val resOnMap = simulation.mapResources[mapId]?.values?.toList().orEmpty()
        val objectsOnMap = simulation.worldObjectsOn(mapId).sortedBy { it.id }

        // With the map cache, maps this connection already holds at the current revision are left out and only referenced.
        val caps = clientCapabilitiesBySession[session.sessionId]
        val cached = caps != null && ProtocolCapabilities.MAP_CACHE in caps
        val sent = if (cached) mapRevisionsSent.getOrPut(session.sessionId) { mutableMapOf() } else null
        fun needed(m: MapDefinition): Boolean = sent == null || sent[m.id] != m.revision
        val mapRefs = if (cached) listOf(MapRef(map.id, map.revision)) + neighborMaps.values.map { MapRef(it.id, it.revision) } else emptyList()
        val activeToSend = map.takeIf { needed(it) }
        val neighborsToSend = neighborMaps.filterValues { needed(it) }
        if (sent != null) {
            activeToSend?.let { sent[it.id] = it.revision }
            neighborsToSend.values.forEach { sent[it.id] = it.revision }
        }

        session.send(
            ServerPacket.MapStateSnapshot(
                activeMap = activeToSend,
                neighborMaps = neighborsToSend,
                mapRefs = mapRefs,
                players = playersOnMap,
                npcs = npcsOnMap,
                groundItems = groundOnMap,
                projectiles = projOnMap,
                resources = resOnMap,
                worldObjects = if (clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.WORLD_OBJECTS) == true) objectsOnMap else emptyList(),
                serverTick = simulation.effectiveWorldTick()
            )
        )
        // The client starts from nothing on a new snapshot, so resend the view across the borders straight away.
        if (simulation.modules.isEnabled(OptionalModule.SOFT_SEAMS)) {
            val caps = clientCapabilitiesBySession[session.sessionId]
            if (caps == null || ProtocolCapabilities.SOFT_SEAMS in caps) {
                session.characterId?.let { charId ->
                    seamViews.forget(charId)
                    simulation.players[charId]?.let { player ->
                        val views = seamViews.compute(player)
                        if (seamViews.changed(charId, views)) session.send(ServerPacket.SeamView(views))
                    }
                }
            }
        }
    }

    private fun toPublicSnapshot(player: CharacterState): PublicPlayerSnapshot {
        val guildTag = player.guildId?.let { simulation.guilds[it]?.tag }.orEmpty()
        return PublicPlayerSnapshot(
            characterId = player.id,
            name = player.name,
            level = player.level,
            spriteId = player.spriteId,
            accessRank = player.accessLevel.rank,
            nameplateColorHex = player.accessLevel.nameplateColorHex,
            guildTag = guildTag,
            isRoguePk = player.isRoguePk,
            isGhost = player.isGhost,
            stallTitle = simulation.stalls[player.id]?.takeIf { it.open }?.title.orEmpty(),
            pkPoints = player.pkPoints,
            vitals = player.vitals,
            position = player.position,
            paperdollSpriteIds = player.equipment.paperdollLayers(simulation.items),
            paperdollLayers = if (simulation.game.definition.sprites?.paperdollingV2 == true) {
                com.morgana.domain.item.PaperdollComposer.resolveCharacterLayers(
                    baseLayers = player.paperdollBaseLayers,
                    equipment = player.equipment,
                    itemCatalog = simulation.items,
                    direction = player.position.direction,
                    classId = player.classId,
                    topRarityId = simulation.game.definition.loot?.topRarityId
                )
            } else emptyList(),
            castingSpellId = player.castingSpellId,
            castFinishTick = player.castFinishTick,
            castTotalTicks = player.castTotalTicks,
            castRemainingTicks = if (player.isCasting) (player.castFinishTick - simulation.currentTick).toInt().coerceAtLeast(0) else 0
        )
    }

    fun buildCatalogDto(): WorldContentCatalogDto = WorldContentCatalogDto(
        classes = simulation.classes.values.toList(),
        items = simulation.items.values.toList(),
        npcs = simulation.npcs.values.toList(),
        spells = simulation.spells.values.toList(),
        shops = simulation.shops.values.toList(),
        resources = simulation.resources.values.toList(),
        recipes = simulation.recipes.values.toList(),
        quests = simulation.quests.values.toList(),
        events = simulation.events.values.toList(),
        commonEvents = simulation.commonEvents.values.toList(),
        animations = simulation.animations.values.toList(),
        actionSkills = simulation.actionSkills.values.toList(),
        weaponSets = simulation.weaponSets.values.toList(),
        maps = simulation.contentMaps().values.sortedBy { it.id }.map { MapSummary(it.id, it.name, it.width, it.height, it.revision) }
    )

    /** Tells the players on [mapId] whose client draws world objects (the `world_objects` capability). */
    private fun broadcastWorldObjects(mapId: Int, packet: ServerPacket) {
        val encoded = lazy(LazyThreadSafetyMode.NONE) { PacketCodec.encodeServerJson(packet) }
        for (p in simulation.players.values) {
            if (p.position.mapId != mapId) continue
            val session = sessionByCharacterId[p.id] ?: continue
            if (clientCapabilitiesBySession[session.sessionId]?.contains(ProtocolCapabilities.WORLD_OBJECTS) == true) session.sendEncoded(packet) { encoded.value }
        }
    }

    private fun broadcastToMap(mapId: Int, packet: ServerPacket) {
        // Encoded at most once, however many players are on the map (encoding per recipient was most of a busy tick).
        val encoded = lazy(LazyThreadSafetyMode.NONE) { PacketCodec.encodeServerJson(packet) }
        for (p in simulation.players.values) {
            if (p.position.mapId == mapId) sessionByCharacterId[p.id]?.sendEncoded(packet) { encoded.value }
        }
    }

    /**
     * Sends [packet] to the players on [mapId] who can see tile ([tileX], [tileY]) or ([alsoX], [alsoY]) (an entity that has
     * just moved is announced to the viewers of both its old and new places), plus [alwaysTo]. With area-of-interest buckets
     * off this is [broadcastToMap].
     */
    private fun broadcastNear(mapId: Int, tileX: Int, tileY: Int, packet: ServerPacket, alsoX: Int = tileX, alsoY: Int = tileY, alwaysTo: String? = null) {
        val aoi = areaOfInterest ?: return broadcastToMap(mapId, packet)
        val encoded = lazy(LazyThreadSafetyMode.NONE) { PacketCodec.encodeServerJson(packet) }
        for (p in simulation.players.values) {
            if (p.position.mapId != mapId) continue
            val px = p.position.tileX
            val py = p.position.tileY
            if (p.id == alwaysTo || aoi.sees(px, py, tileX, tileY) || aoi.sees(px, py, alsoX, alsoY)) {
                sessionByCharacterId[p.id]?.sendEncoded(packet) { encoded.value }
            }
        }
    }

    private fun broadcastPlayerUpdate(character: CharacterState) {
        val pkt = ServerPacket.PlayerUpdated(toPublicSnapshot(character))
        val x = character.position.tileX
        val y = character.position.tileY
        val before = lastEntityTile.put("p:${character.id}", x to y) ?: (x to y)
        broadcastNear(character.position.mapId, x, y, pkt, before.first, before.second, alwaysTo = character.id)
    }

    private fun broadcastNpcUpdate(npc: com.morgana.domain.entity.MapNpcInstance) {
        val before = lastEntityTile.put("n:${npc.instanceId}", npc.tileX to npc.tileY) ?: (npc.tileX to npc.tileY)
        broadcastNear(npc.mapId, npc.tileX, npc.tileY, ServerPacket.NpcUpdated(npc), before.first, before.second)
    }

    /**
     * When a player's area of interest moves to new cells, tells them the current state of everything in the cells it now
     * covers, because changes there were not sent while they were out of range. Does nothing with the buckets off, when the
     * player has only just arrived on the map (the full snapshot covers that), or while they stay in the same cell.
     */
    private fun syncViewerArea(character: CharacterState) {
        val aoi = areaOfInterest ?: return
        val session = sessionByCharacterId[character.id] ?: return
        val mapId = character.position.mapId
        val x = character.position.tileX
        val y = character.position.tileY
        val old = viewerAreas.put(character.id, Triple(mapId, x, y))
        if (old == null || old.first != mapId || !aoi.cellChanged(old.second, old.third, x, y)) return
        for (npc in simulation.mapNpcs[mapId].orEmpty()) {
            if (aoi.newlyCovered(old.second, old.third, x, y, npc.tileX, npc.tileY)) session.send(ServerPacket.NpcUpdated(npc))
        }
        for (other in simulation.players.values) {
            if (other.id != character.id && other.position.mapId == mapId &&
                aoi.newlyCovered(old.second, old.third, x, y, other.position.tileX, other.position.tileY)
            ) session.send(ServerPacket.PlayerUpdated(toPublicSnapshot(other)))
        }
        for (res in simulation.mapResources[mapId].orEmpty().values) {
            if (aoi.newlyCovered(old.second, old.third, x, y, res.tileX, res.tileY)) session.send(ServerPacket.ResourceNodeUpdated(res))
        }
    }

    private fun broadcastToAll(packet: ServerPacket) {
        val encoded = lazy(LazyThreadSafetyMode.NONE) { PacketCodec.encodeServerJson(packet) }
        sessionsById.values.forEach { it.sendEncoded(packet) { encoded.value } }
    }
}


/** How long the generated game wiki is reused before it is rebuilt from the live content. */
private const val WIKI_CACHE_MS = 10_000L
