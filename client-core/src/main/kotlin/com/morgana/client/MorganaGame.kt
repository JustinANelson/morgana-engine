package com.morgana.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.morgana.client.assets.AssetCache
import com.morgana.client.assets.AssetDownloader
import com.morgana.client.assets.HttpAssetDownloader
import com.morgana.client.assets.InMemoryAssetCache
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.assets.RealmAssetSyncPipeline
import com.morgana.client.assets.SyncResult
import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.audio.SoundLoader
import com.morgana.client.audio.TempFileSoundLoader
import com.morgana.client.net.ClientSecurity
import com.morgana.client.net.ConnectResult
import com.morgana.client.net.InMemoryResumeStore
import com.morgana.client.net.ReconnectController
import com.morgana.client.net.ResumeInfo
import com.morgana.client.net.ResumeStore
import com.morgana.client.net.ConnectionPlanner
import com.morgana.client.net.ClientTransport
import com.morgana.client.realms.InMemoryRealmDirectoryClient
import com.morgana.client.realms.RealmDirectoryClient
import com.morgana.client.realms.defaultBundledDirectory
import com.morgana.client.state.InMemoryPerRealmStore
import com.morgana.client.state.PerRealmStore
import com.morgana.protocol.tls.Fingerprint
import com.morgana.protocol.tls.InMemoryKnownHosts
import com.morgana.protocol.tls.KnownHostStore
import com.morgana.client.input.TapMoveController
import com.morgana.client.render.MorganaWorldRenderer
import com.morgana.client.render.WorldZoom
import com.morgana.client.state.MapEditorTool
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.AssetDownloadDialog
import com.morgana.client.ui.CharacterSelectWindow
import com.morgana.client.ui.ConnectionBanner
import com.morgana.client.ui.F1LiveAdminPanel
import com.morgana.client.ui.HudWindow
import com.morgana.client.ui.MorganaRealmsHubWindow
import com.morgana.client.ui.PlayerHudAndWindows
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.FloatingTextCategory
import com.morgana.protocol.ServerPacket
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow
import ktx.app.KtxApplicationAdapter

/**
 * Main LibGDX + KTX application adapter for Morgana ORPG.
 */
class MorganaGame(
    /** How this platform reaches the server (Netty TCP on desktop and Android, WebSocket in the browser). */
    transport: ClientTransport,
    /** How sound effects become playable on this platform; the browser passes its own. */
    soundLoader: SoundLoader = TempFileSoundLoader(),
    private val defaultHost: String = "127.0.0.1",
    private val defaultPort: Int = 7001,
    private val onEnsureLocalServer: () -> Unit = {},
    private val localWorlds: LocalWorldHost? = null,
    /** Fingerprints of self-signed servers the player trusted; pass a file-backed store to remember them between runs. */
    private val knownHosts: KnownHostStore = InMemoryKnownHosts(),
    val directoryClient: RealmDirectoryClient = InMemoryRealmDirectoryClient(defaultBundledDirectory(defaultHost, defaultPort)),
    val perRealmStore: PerRealmStore = InMemoryPerRealmStore(),
    val assetCache: AssetCache = InMemoryAssetCache(),
    val assetDownloader: AssetDownloader = HttpAssetDownloader(),
    /** True on phones/tablets (Android, or a browser reporting touch input): enlarges the UI and shows the on-screen pad. */
    private val touchDevice: Boolean = false,
    /** Shows the character screen with this made-up list at start, and answers its buttons locally (`?fakeCharacters=1`; for screenshots). */
    private val previewCharacters: ServerPacket.CharacterList? = null,
    /** Remembers the resume token so the app can return to the character after being closed or reloaded. */
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    /** Optional platform PNG chooser used by the F1 Art panel's Import PNG button. */
    private val pickPngFile: (((ByteArray?) -> Unit) -> Unit)? = null,
    /** The platform's own text box for touch devices whose keyboard the game cannot raise (the browser); see [TextEntryBridge]. */
    private val textEntry: com.morgana.client.ui.TextEntryBridge? = null,
    /** True when the operating system or browser asks for less motion; the starting value of Reduce motion until the player chooses. */
    private val systemReducedMotion: Boolean = false,
    /** Phone conveniences the platform may offer: a buzz for hits, keeping the screen awake, a last-resort error screen. */
    private val device: com.morgana.client.net.DeviceFeatures = com.morgana.client.net.DeviceFeatures.NONE,
    /** The interface size the "Auto" setting stands for on this platform; desktop picks one from the monitor (see [com.morgana.client.access.UiScales.auto]). */
    private val autoUiScale: () -> Float = { 1f },
    /** A one-time login ticket to redeem at launch instead of showing the password screen (see [com.morgana.client.net.DirectConnect]). */
    private val directLogin: ResumeInfo? = null
) : KtxApplicationAdapter {

    /** Accessibility settings (keys, colours, motion, interface size): loaded in [create], saved on every change. */
    private val accessibility = com.morgana.client.access.AccessibilityController(
        com.morgana.client.access.AccessibilityStore(com.morgana.client.ui.editor.PreferencesKeyValueStore("morgana-accessibility")),
        systemReducedMotion
    )

    /** Mirrors the focused text field into [textEntry] on touch devices; null elsewhere. */
    private var textEntrySync: com.morgana.client.ui.TextEntrySync? = null

    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var camera: OrthographicCamera
    private lateinit var stage: Stage
    private lateinit var assets: ProceduralRetroAssets
    private lateinit var worldRenderer: MorganaWorldRenderer
    private lateinit var hud: PlayerHudAndWindows
    private lateinit var adminPanel: F1LiveAdminPanel
    private lateinit var realmsHub: MorganaRealmsHubWindow
    private lateinit var characterSelect: CharacterSelectWindow
    private var lastSelectPingMs = 0L
    private lateinit var downloadDialog: AssetDownloadDialog
    private lateinit var banner: ConnectionBanner
    private lateinit var linkIndicator: com.morgana.client.ui.LinkQualityIndicator
    private val haptics = com.morgana.client.feedback.HapticModel()
    private var screenKeptOn = false

    /** Consecutive frames that threw; a lone failure is skipped, a repeating one means the frame loop is broken. */
    private var renderFailures = 0
    private var fatalErrorShown = false

    /** Packets the client could not understand since login; a few mean it is older than the server. */
    private var undecodablePackets = 0

    /** Keeps the connection alive and brings it back after the app was backgrounded or the network dropped. */
    private val reconnect = ReconnectController()

    /** Outcome of a reconnect attempt's connect step; written by the transport (any thread), consumed in [render]. */
    @Volatile
    private var reconnectResult: ConnectResult? = null
    private var resumeToken: String? = null
    private var resumeSecurity: ClientSecurity = ClientSecurity.PLAIN
    private var resumeTls = false
    private var lastFrameMs = 0L

    /** Keeps a slow or stalled link from building a backlog of steps and attacks (see [SendGate]). */
    val sendGate = com.morgana.client.net.SendGate()
    val netClient: ClientTransport = com.morgana.client.net.GatedTransport(transport, sendGate) { TimeUtils.millis() }
    val worldState = ClientWorldState()
    val audio = RetroSoundEngine(loader = soundLoader)
    val syncPipeline: RealmAssetSyncPipeline = RealmAssetSyncPipeline(assetCache, assetDownloader)

    private var activeRealmId: String? = null
    private var connectedHost: String = defaultHost
    private var connectedPort: Int = defaultPort
    private var moveRepeatTimer: Float = 0f
    private val tapMove = TapMoveController()
    private var tapDownX = 0
    private var tapDownY = 0
    private var tapDownAt = 0L
    private var tapCandidate = false
    private var activeSyncHandle: AutoCloseable? = null
    private var editorWasActive = false

    /** Counts logins that bring realm art, so callbacks from an older login's art sync do nothing. */
    private var artGeneration = 0

    /** True from login until the realm's art is ready (or has failed/been cancelled): the world and HUD stay hidden meanwhile. */
    private var artLoading = false
        set(value) {
            field = value
            if (::hud.isInitialized) hud.holdBack = value
        }

    /** Runs [block] on the render thread (at once when there is no libGDX application, as in tests). */
    private fun onRenderThread(block: () -> Unit) {
        Gdx.app?.postRunnable(block) ?: block()
    }

    /** Shows the realm's art and hands its music to the sound engine, then re-picks the current map's music in case it shipped a track for it. */
    private fun applyRealmArt(manifest: com.morgana.protocol.realms.AssetManifest) {
        assets.applyManifest(manifest, assetCache)
        var music = false
        for (entry in manifest.assets.values) {
            if (entry.kind != com.morgana.protocol.realms.AssetKind.MUSIC) continue
            val bytes = assetCache.load(entry.sha256) ?: continue
            audio.registerCustomTrack(entry.path.substringAfterLast('/').substringBeforeLast('.'), bytes)
            music = true
        }
        if (music) worldState.activeMap?.bgmTrackId?.takeIf { it.isNotBlank() }?.let { audio.playBgm(it) }
    }

    /** Downloads what the cache lacks of [manifest], then shows the realm's art; [generation] guards against a newer login. */
    private fun syncRealmArt(manifest: com.morgana.protocol.realms.AssetManifest, baseUrl: String, generation: Int) {
        if (syncPipeline.calculateMissing(manifest).isEmpty()) {
            applyRealmArt(manifest)
            artLoading = false
            return
        }
        downloadDialog.show(activeRealmId ?: "Custom Realm")
        activeSyncHandle = syncPipeline.syncAsync(
            manifest = manifest,
            baseUrl = baseUrl,
            onProgress = { progress ->
                onRenderThread { if (generation == artGeneration) downloadDialog.updateProgress(progress) }
            },
            onComplete = { result ->
                onRenderThread {
                    if (generation != artGeneration) return@onRenderThread
                    when (result) {
                        is SyncResult.Completed, is SyncResult.UpToDate -> {
                            downloadDialog.close()
                            applyRealmArt(manifest)
                            artLoading = false
                        }
                        is SyncResult.Failed -> {
                            // The dialog stays up with the error; the player can cancel to continue with the built-in art.
                            downloadDialog.setError(result.error)
                        }
                        is SyncResult.Cancelled -> {
                            downloadDialog.close()
                            artLoading = false
                        }
                    }
                }
            }
        )
    }

    /**
     * What the client shows right now, as JSON, for the automated browser smoke test ([com.morgana.client.probe.GameProbe]).
     * Safe to call at any time: before the game has started it just says it is not ready.
     */
    fun probeSnapshot(): String {
        val ready = this::stage.isInitialized && this::adminPanel.isInitialized
        return com.morgana.client.probe.GameProbe.snapshot(
            stage = if (ready) stage else null,
            worldState = worldState,
            editorOpen = ready && adminPanel.isEditorOpen(),
            panels = if (ready) adminPanel.panelStates() else emptyList(),
            finderOpen = ready && adminPanel.isPanelFinderOpen()
        )
    }

    private var postFx: com.morgana.client.render.WorldPostProcessor? = null

    override fun create() {
        if (!VisUI.isLoaded()) {
            VisUI.load()
        }
        com.morgana.client.ui.SkinThemer.apply()
        batch = SpriteBatch()
        font = BitmapFont()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        HudWindow.touchMode = touchDevice
        stage = Stage(ScreenViewport())
        accessibility.load()
        applyUiScaleToViewport(accessibility.current.uiScale)
        if (touchDevice && textEntry != null) textEntrySync = com.morgana.client.ui.TextEntrySync(stage, textEntry)

        assets = ProceduralRetroAssets().also { it.loadAll() }
        worldRenderer = MorganaWorldRenderer(assets, font)
        postFx = com.morgana.client.render.WorldPostProcessor()
        adminPanel = F1LiveAdminPanel(stage, worldState, netClient, assets, onStepWorldZoom = ::stepWorldZoom, onPickPngFile = pickPngFile,
            worldZoomLabel = { WorldZoom.percentLabel(effectiveWorldScale()) })
        hud = PlayerHudAndWindows(
            stage = stage,
            worldState = worldState,
            netClient = netClient,
            assets = assets,
            audio = audio,
            onToggleF1Admin = { adminPanel.toggle() },
            onHotbarChanged = { saveHotbarPreferences() },
            touchDevice = touchDevice,
            onCycleUiScale = { cycleUiScale() },
            onCycleWorldZoom = { cycleWorldZoom() },
            worldZoomLabel = { WorldZoom.label(worldZoomSetting) },
            onLogout = { logout() },
            uiScaleLabel = { "Zoom: ${uiScaleText()}" },
            uiScaleText = ::uiScaleText,
            accessibility = accessibility,
            onCombatInput = { button ->
                if (button == com.morgana.client.input.ActionCombatInput.Button.ATTACK) holdAttackRepeat.markFired()
                sendActionInput(button.token, null)
            },
            onCombatRelease = { netClient.send(com.morgana.client.input.ActionCombatInput.release()) }
        )
        accessibility.onChanged = { change ->
            if (change == com.morgana.client.access.AccessibilityController.Change.SCALE) {
                applyUiScaleToViewport(accessibility.current.uiScale)
                hud.keepWindowsOnScreen()
            }
            hud.onAccessibilityChanged(change)
        }
        hud.buildHud()

        realmsHub = MorganaRealmsHubWindow(
            stage = stage,
            defaultHost = defaultHost,
            defaultPort = defaultPort,
            directoryClient = directoryClient,
            perRealmStore = perRealmStore,
            localWorlds = localWorlds
        ) { host, port, tls, username, password, classId, selectedRealm ->
            activeRealmId = selectedRealm?.id ?: "$host:$port"
            connectAndLogin(host, port, tls, username, password, classId)
        }
        characterSelect = CharacterSelectWindow(
            stage = stage,
            onChoose = { sendCharacterRequest(ClientPacket.ChooseCharacter(it.id), "would play ${it.name}") },
            onCreate = { name, classId -> sendCharacterRequest(ClientPacket.CreateCharacter(name, classId), "would create $name") },
            onDelete = { slot, confirm -> sendCharacterRequest(ClientPacket.DeleteCharacter(slot.id, confirm), "would delete ${slot.name}") },
            onBack = { returnToLogin("") }
        )
        downloadDialog = AssetDownloadDialog(stage) {
            activeSyncHandle?.close()
            activeSyncHandle = null
            artLoading = false
        }
        banner = ConnectionBanner(stage, onRetry = { reconnect.retryNow(TimeUtils.millis()) }, onLogout = { logout() })
        linkIndicator = com.morgana.client.ui.LinkQualityIndicator(stage)
        showLoginWindow()
        previewCharacters?.let { realmsHub.close(); characterSelect.show(it) }
        resumeSavedSession()

        val worldInput = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                // While the Controls window waits for a key, every key goes to it (Esc cancels) and does nothing else.
                if (accessibility.isCapturing) {
                    accessibility.captureKey(keycode)?.let { hud.accessibilityMessage(it) }
                    return true
                }
                if (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) {
                    val fx = com.morgana.client.render.VisualFx
                    val note: String? = when (keycode) {
                        Input.Keys.F6 -> { fx.lighting = !fx.lighting; "Lighting and mist: " + if (fx.lighting) "on" else "off" }
                        Input.Keys.F7 -> { fx.shadows = !fx.shadows; "Cast shadows: " + if (fx.shadows) "on" else "off" }
                        Input.Keys.F8 -> { fx.hd2d = !fx.hd2d; "HD-2D look: " + if (fx.hd2d) "on" else "off" }
                        else -> null
                    }
                    if (note != null) {
                        worldState.toasts.add(note, com.morgana.client.ui.ToastKind.INFO, com.morgana.client.ui.ToastClock.now())
                        return true
                    }
                }
                if (keycode == Input.Keys.F1) {
                    // The editor edits the world you are standing in, so it needs a login first.
                    if (worldState.authenticated || adminPanel.isEditorOpen()) adminPanel.toggle()
                    return true
                }
                if (keycode == Input.Keys.ESCAPE) {
                    if (worldState.pendingGroundSpellId != null) {
                        worldState.pendingGroundSpellId = null
                        return true
                    }
                    adminPanel.dismissPanelFinder()
                    stage.keyboardFocus = null
                    return true
                }
                if (worldState.mapEditorActive) {
                    // Map editor shortcuts take priority over the game's keys (E, G and I are interact, pick up and inventory).
                    val command = com.morgana.client.editor.EditorShortcuts.resolve(
                        keycode,
                        ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT),
                        shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT),
                        alt = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT),
                        typing = isTypingInTextField() || adminPanel.isPanelFinderOpen()
                    )
                    if (command != null && adminPanel.runShortcut(command)) return true
                }
                if (isTypingInTextField() || adminPanel.isPanelFinderOpen()) return false
                if (actionCombatActive() && actionCombatKey(keycode)) return true
                val gameAction = com.morgana.client.access.Accessibility.keys.actionFor(keycode)
                if (gameAction != null) {
                    when (gameAction) {
                        com.morgana.client.access.GameAction.MINIMAP -> hud.toggleMinimap()
                        com.morgana.client.access.GameAction.WORLD_MAP -> hud.toggleWorldMapWindow()
                        com.morgana.client.access.GameAction.CHARACTER -> hud.toggleCharacterWindow()
                        com.morgana.client.access.GameAction.INVENTORY -> hud.toggleCharacterAndInventoryWindow()
                        com.morgana.client.access.GameAction.SPELLBOOK -> hud.toggleSpellbookWindow()
                        com.morgana.client.access.GameAction.QUESTS -> hud.toggleQuestWindow()
                        com.morgana.client.access.GameAction.CRAFTING -> hud.toggleCraftingWindow()
                        com.morgana.client.access.GameAction.PARTY -> hud.togglePartyWindow()
                        com.morgana.client.access.GameAction.ATTACK -> if (worldState.swingReady(System.currentTimeMillis())) {
                            netClient.send(ClientPacket.Attack)
                            worldState.triggerLocalAttackOrHarvest()
                            audio.play(RetroSoundEngine.Sfx.SWING)
                        }
                        com.morgana.client.access.GameAction.JUMP -> {
                            if (hud.jumpEnabled) {
                                worldState.findDirectionalJumpLanding()?.let { landing ->
                                    tapMove.cancel()
                                    netClient.send(ClientPacket.JumpTo(landing.x, landing.y))
                                }
                            }
                        }
                        com.morgana.client.access.GameAction.INTERACT -> if (worldState.actionReady("interact", System.currentTimeMillis(), 250L)) {
                            netClient.send(ClientPacket.InteractFacing)
                            worldState.triggerLocalInteract()
                        }
                        com.morgana.client.access.GameAction.PICK_UP -> if (worldState.actionReady("pickup", System.currentTimeMillis(), 250L)) {
                            sendPickup()
                        }
                        com.morgana.client.access.GameAction.STANCE -> hud.cycleStance()
                        com.morgana.client.access.GameAction.MORE_ACTIONS -> hud.toggleActionList()
                        com.morgana.client.access.GameAction.TARGET_NEXT -> hud.cycleTarget()
                        com.morgana.client.access.GameAction.CONTROLS -> hud.toggleControlsWindow()
                        else -> {
                            // Quickbar slots; the movement keys are read every frame (see render), not as key events.
                            val slot = gameAction.hotbarIndex ?: return false
                            hud.triggerHotbar(slot, fromKeyboard = true)
                        }
                    }
                    return true
                }
                return false
            }

            override fun keyUp(keycode: Int): Boolean {
                // A guard is held for as long as its key is down.
                if (actionCombatActive() && com.morgana.client.input.ActionCombatInput.buttonForKey(keycode, jumpEnabled = true)?.holdToRelease == true) {
                    netClient.send(com.morgana.client.input.ActionCombatInput.release())
                    return true
                }
                return false
            }

            override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
                val map = worldState.activeMap ?: return false
                val coord = worldRenderer.screenToTileCoord(
                    camera = camera,
                    screenX = screenX,
                    screenY = screenY,
                    viewportWidth = Gdx.graphics.width,
                    viewportHeight = Gdx.graphics.height,
                    map = map
                )
                worldState.hoveredTile = if (map.isInBounds(coord)) coord else null
                return false
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                // Clicking the map canvas releases text field focus so WASD movement works immediately. Only the keyboard focus is
                // cleared: unfocusAll() would also drop the scroll focus, and the F1 editor panes would stop wheel-scrolling.
                stage.keyboardFocus = null
                if (worldState.pendingGroundSpellId != null && worldState.authenticated && !worldState.mapEditorActive) {
                    if (button == Input.Buttons.RIGHT) {
                        worldState.pendingGroundSpellId = null
                        return true
                    }
                    if (button == Input.Buttons.LEFT) {
                        val spellId = worldState.pendingGroundSpellId!!
                        val map = worldState.activeMap
                        if (map != null) {
                            val at = worldRenderer.screenToTileCoord(camera, screenX, screenY, Gdx.graphics.width, Gdx.graphics.height, map)
                            if (map.isInBounds(at) && worldState.castReady(spellId, System.currentTimeMillis())) {
                                netClient.send(ClientPacket.CastSpell(spellId = spellId, targetTileX = at.x, targetTileY = at.y))
                                worldState.noteSpellCast(spellId)
                                audio.play(RetroSoundEngine.Sfx.SPELL)
                            }
                        }
                        worldState.pendingGroundSpellId = null
                        return true
                    }
                }
                if (button == Input.Buttons.LEFT && worldState.authenticated && !worldState.mapEditorActive) {
                    // Clicking a fallen player picks them as the target of the next revive spell (the walk-to-tap still happens).
                    worldState.activeMap?.let { map ->
                        val at = worldRenderer.screenToTileCoord(camera, screenX, screenY, Gdx.graphics.width, Gdx.graphics.height, map)
                        if (map.isInBounds(at)) worldState.selectGhostAt(at.x, at.y)
                        val worldX = camera.position.x - (Gdx.graphics.width * 0.5f * camera.zoom) + (screenX * camera.zoom)
                        val worldY = camera.position.y + (Gdx.graphics.height * 0.5f * camera.zoom) - (screenY * camera.zoom)
                        val clickedLoot = com.morgana.client.ui.GroundLootLabels.hitTest(
                            worldX = worldX,
                            worldY = worldY,
                            tileX = at.x,
                            tileY = at.y,
                            labels = worldState.lastGroundLootLabels,
                            groundItems = worldState.groundItemsOnMap
                        )
                        if (clickedLoot != null) {
                            val ch = worldState.localCharacter
                            if (ch != null) {
                                val dist = maxOf(kotlin.math.abs(ch.position.tileX - clickedLoot.tileX), kotlin.math.abs(ch.position.tileY - clickedLoot.tileY))
                                if (dist <= 1) {
                                    if (worldState.actionReady("pickup", System.currentTimeMillis(), 250L)) {
                                        sendPickup()
                                    }
                                    pendingLootTile = null
                                } else {
                                    val goal = com.morgana.domain.spatial.TileCoord(clickedLoot.tileX, clickedLoot.tileY)
                                    tapMove.setGoal(map.id, goal)
                                    pendingLootTile = goal
                                    moveRepeatTimer = 0f
                                }
                                tapCandidate = false
                                return true
                            }
                        }
                    }
                }
                if (actionCombatActive() && (button == Input.Buttons.LEFT || button == Input.Buttons.RIGHT)) {
                    // In action combat a click attacks toward the cursor instead of walking there.
                    tapCandidate = false
                    if (button == Input.Buttons.LEFT) {
                        lmbHeldOnWorld = true
                        holdAttackRepeat.markFired()
                    }
                    // Clicking or tapping an enemy locks on to it.
                    cursorAim(screenX, screenY)?.let { a ->
                        com.morgana.client.input.CombatTargeting.nearestTo(a.first, a.second, worldState.targetCandidates())?.let { worldState.lockedNpcId = it.id }
                    }
                    sendActionInput(if (button == Input.Buttons.LEFT) "LMB" else "RMB", cursorAim(screenX, screenY))
                    return true
                }
                val ctrlHeld = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
                val jumpReady = ctrlHeld && hud.jumpEnabled && worldState.gameDefinition?.jump?.enabled == true
                tapCandidate = worldState.authenticated && !worldState.mapEditorActive &&
                    button == Input.Buttons.LEFT && pointer == 0 && (hud.tapToMoveEnabled || jumpReady)
                if (tapCandidate) {
                    tapDownX = screenX
                    tapDownY = screenY
                    tapDownAt = TimeUtils.millis()
                }
                if (!worldState.mapEditorActive) return false
                val map = worldState.activeMap ?: return false
                val coord = worldRenderer.screenToTileCoord(
                    camera = camera,
                    screenX = screenX,
                    screenY = screenY,
                    viewportWidth = Gdx.graphics.width,
                    viewportHeight = Gdx.graphics.height,
                    map = map
                )
                worldState.hoveredTile = if (map.isInBounds(coord)) coord else null
                if (map.isInBounds(coord)) {
                    // One mouse press-to-release is one undo step, however many tiles the drag paints.
                    worldState.editor.beginStroke()
                    if (worldState.editor.tool == MapEditorTool.RECTANGLE) {
                        // The rectangle tool fills a box when the button is released, so a press only marks the first corner.
                        worldState.editor.beginRect(coord.x, coord.y, erase = (button == Input.Buttons.RIGHT))
                    } else {
                        worldState.applyEditorActionAt(coord.x, coord.y, isRightClick = (button == Input.Buttons.RIGHT))
                    }
                    return true
                }
                return false
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.LEFT) lmbHeldOnWorld = false
                if (worldState.mapEditorActive) {
                    val editor = worldState.editor
                    if (editor.rectAnchor != null) {
                        val map = worldState.activeMap
                        val coord = map?.let { worldRenderer.screenToTileCoord(camera, screenX, screenY, Gdx.graphics.width, Gdx.graphics.height, it) }
                        if (coord != null) editor.finishRect(coord.x, coord.y) else editor.cancelRect()
                    }
                    editor.endStroke()
                }
                if (!tapCandidate || pointer != 0) return false
                tapCandidate = false
                // A tap, not a drag or a long press.
                val moved = kotlin.math.abs(screenX - tapDownX) + kotlin.math.abs(screenY - tapDownY)
                if (moved > 12 || TimeUtils.millis() - tapDownAt > 500) return false
                val map = worldState.activeMap ?: return false
                val coord = worldRenderer.screenToTileCoord(camera, screenX, screenY, Gdx.graphics.width, Gdx.graphics.height, map)
                if (!map.isInBounds(coord)) return false
                val ctrlHeld = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
                if (ctrlHeld && hud.jumpEnabled && worldState.gameDefinition?.jump?.enabled == true) {
                    tapMove.cancel()
                    netClient.send(ClientPacket.JumpTo(coord.x, coord.y))
                    return true
                }
                if (!hud.tapToMoveEnabled) return false
                tapMove.setGoal(map.id, coord)
                moveRepeatTimer = 0f
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (!worldState.mapEditorActive) return false
                val map = worldState.activeMap ?: return false
                val coord = worldRenderer.screenToTileCoord(
                    camera = camera,
                    screenX = screenX,
                    screenY = screenY,
                    viewportWidth = Gdx.graphics.width,
                    viewportHeight = Gdx.graphics.height,
                    map = map
                )
                worldState.hoveredTile = if (map.isInBounds(coord)) coord else null
                if (map.isInBounds(coord)) {
                    if (worldState.editor.rectAnchor != null) return true
                    if (worldState.editor.tool == MapEditorTool.EVENT || worldState.editor.tool == MapEditorTool.EYEDROPPER) return true
                    val rightDown = Gdx.input.isButtonPressed(Input.Buttons.RIGHT)
                    worldState.applyEditorActionAt(coord.x, coord.y, isRightClick = rightDown)
                    return true
                }
                return false
            }
        }

        Gdx.input.inputProcessor = InputMultiplexer(stage, worldInput)
    }

    // ---- action combat ----

    private val holdAttackRepeat = com.morgana.client.input.HoldAttackRepeat(0.15f)
    private var lmbHeldOnWorld: Boolean = false
    private var pendingLootTile: com.morgana.domain.spatial.TileCoord? = null

    private fun actionCombatActive(): Boolean =
        worldState.authenticated && !worldState.mapEditorActive && com.morgana.client.input.ActionCombatInput.isActive(worldState.gameDefinition)

    /** The map pixel under the screen point ([screenX], [screenY]), in the simulation's y-down pixels, or null off a map. */
    private fun cursorAim(screenX: Int, screenY: Int): Pair<Float, Float>? {
        val map = worldState.activeMap ?: return null
        val worldX = camera.position.x - (Gdx.graphics.width * 0.5f * camera.zoom) + (screenX * camera.zoom)
        val worldY = camera.position.y + (Gdx.graphics.height * 0.5f * camera.zoom) - (screenY * camera.zoom)
        return com.morgana.client.input.ActionCombatInput.aimPixels(worldX, worldY, map.height * map.tileSize)
    }

    /** Sends an action-combat input token for [key] with the Shift and movement keys currently held. */
    private fun sendActionInput(key: String, aim: Pair<Float, Float>?) {
        val keys = com.morgana.client.access.Accessibility.keys
        fun held(action: com.morgana.client.access.GameAction) = keys.isDown(action) { Gdx.input.isKeyPressed(it) }
        var moveX = (if (held(com.morgana.client.access.GameAction.MOVE_RIGHT)) 1 else 0) - (if (held(com.morgana.client.access.GameAction.MOVE_LEFT)) 1 else 0)
        var moveY = (if (held(com.morgana.client.access.GameAction.MOVE_DOWN)) 1 else 0) - (if (held(com.morgana.client.access.GameAction.MOVE_UP)) 1 else 0)
        if (moveX == 0 && moveY == 0) {
            // No movement key held: the touch joystick gives the direction, as the keys would.
            val (tx, ty) = com.morgana.client.input.ActionCombatInput.moveAxes(hud.heldTouchDirection())
            moveX = tx
            moveY = ty
        }
        val shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT) || hud.touchShift
        val facing = worldState.localCharacter?.position?.direction ?: com.morgana.domain.spatial.Direction.DOWN
        val token = com.morgana.client.input.ActionCombatInput.token(key, shift, moveX, moveY, facing)
        netClient.send(com.morgana.client.input.ActionCombatInput.packet(token, resolveAim(key, aim, moveX, moveY)))
        audio.play(RetroSoundEngine.Sfx.SWING)
    }

    /**
     * Where an attack or skill aims, the same on a phone and a keyboard: a dodge goes the way the player is moving; a skill the player
     * is dragging goes along the drag; a cursor aim (a mouse click or key) is kept; otherwise the locked target, else the most
     * valuable enemy in reach ([com.morgana.client.input.CombatTargeting]); with none the character's facing is used.
     */
    private fun resolveAim(key: String, cursor: Pair<Float, Float>?, moveX: Int, moveY: Int): Pair<Float, Float>? {
        val centre = worldState.playerCentre()
        if (key == "SPACE") {
            return if (centre != null && (moveX != 0 || moveY != 0)) (centre.first + moveX * 100f) to (centre.second + moveY * 100f) else null
        }
        hud.manualAim()?.let { return it }
        if (cursor != null) return cursor
        if (key == "Q" || centre == null) return null
        val t = com.morgana.client.input.CombatTargeting.pickAuto(centre.first, centre.second, worldState.targetCandidates(), worldState.lockedNpcId) ?: return null
        return t.x to t.y
    }

    /** The action-combat keys (see [com.morgana.client.input.ActionCombatInput.buttonForKey]). Returns true when [keycode] was one. */
    private fun actionCombatKey(keycode: Int): Boolean {
        val button = com.morgana.client.input.ActionCombatInput.buttonForKey(keycode, jumpEnabled = worldState.gameDefinition?.jump?.enabled == true) ?: return false
        if (button == com.morgana.client.input.ActionCombatInput.Button.ATTACK) holdAttackRepeat.markFired()
        sendActionInput(button.token, cursorAim(Gdx.input.x, Gdx.input.y))
        return true
    }

    /**
     * Returns true ONLY when the user is actively typing in an attached Scene2D [TextField].
     * Prevents VisWindow/VisSelectBox/VisTextButton focus from blocking WASD or Arrow movement!
     */
    private fun isTypingInTextField(): Boolean {
        val focused = stage.keyboardFocus ?: return false
        // VisUI's VisTextField is its own widget, not a subclass of TextField, so both kinds count as typing.
        return (focused is TextField || focused is com.kotcrab.vis.ui.widget.VisTextField) && focused.stage != null
    }

    private fun castKnownSpellAtIndex(idx: Int) {
        val ch = worldState.localCharacter ?: return
        val spellId = ch.knownSpellIds.getOrNull(idx) ?: return
        val spell = worldState.catalog.spells.firstOrNull { it.id == spellId }
        if (spell != null && (ch.level < spell.levelReq || (spell.classReqIds.isNotEmpty() && ch.classId !in spell.classReqIds))) {
            return
        }
        if (!worldState.castReady(spellId, System.currentTimeMillis())) return
        netClient.send(ClientPacket.CastSpell(spellId = spellId, targetEntityId = worldState.castTargetFor(spellId)))
        worldState.noteSpellCast(spellId)
        worldState.triggerLocalAction(com.morgana.domain.game.SpriteState.CAST)
    }

    private var connectAgain: () -> Unit = {}

    /** If an earlier run left a resume token, try to return to that character straight away. */
    private fun resumeSavedSession() {
        val info = directLogin ?: resumeStore.load() ?: return
        val plan = ConnectionPlanner.plan(info.host, info.port.toString(), info.tls, knownHosts, defaultPort)
        if (plan.error != null) {
            resumeStore.clear()
            return
        }
        connectedHost = plan.host
        connectedPort = plan.port
        resumeSecurity = plan.security
        resumeTls = info.tls
        resumeToken = info.token
        activeRealmId = info.realmId
        realmsHub.setStatus("Resuming your last session...")
        reconnect.resumeAtLaunch(TimeUtils.millis())
    }

    private fun saveResume() {
        val token = resumeToken
        if (token == null) {
            resumeStore.clear()
        } else {
            resumeStore.save(ResumeInfo(connectedHost, connectedPort, resumeTls, activeRealmId, token))
        }
    }

    /** Back to the login screen with no session to resume (logout, evicted, expired token, changed server identity). */
    private fun sendCharacterRequest(packet: ClientPacket, previewNote: String) {
        val preview = previewCharacters
        if (preview != null) characterSelect.show(preview.copy(message = "Preview only: $previewNote.")) else netClient.send(packet)
    }

    private fun returnToLogin(message: String) {
        artGeneration++
        activeSyncHandle?.close()
        activeSyncHandle = null
        if (::downloadDialog.isInitialized) downloadDialog.close()
        artLoading = false
        reconnect.stop()
        resumeToken = null
        resumeStore.clear()
        reconnectResult = null
        netClient.close()
        undecodablePackets = 0
        haptics.reset()
        worldState.resetSession()
        if (::characterSelect.isInitialized) characterSelect.close()
        if (::hud.isInitialized) hud.closeMapWindows()
        if (::banner.isInitialized) banner.update(null)
        if (::linkIndicator.isInitialized) linkIndicator.update(null)
        showLoginWindow()
        if (message.isNotBlank()) realmsHub.setStatus(message)
    }

    fun logout() = returnToLogin("")

    private fun beginReconnectAttempt() {
        if (resumeToken == null) {
            returnToLogin("Your session ended. Please log in again.")
            return
        }
        netClient.close()
        reconnectResult = null
        netClient.connectAsync(connectedHost, connectedPort, resumeSecurity) { reconnectResult = it }
    }

    private fun onReconnectConnectResult(result: ConnectResult, nowMs: Long) {
        when (result) {
            is ConnectResult.Connected -> resumeToken?.let {
                netClient.send(ClientPacket.ResumeSession(it, PROTOCOL_VERSION, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW))
            }
            is ConnectResult.Failed -> reconnect.attemptFailed(nowMs)
            is ConnectResult.NeedsTrust, is ConnectResult.IdentityChanged, is ConnectResult.InviteMismatch ->
                returnToLogin("The server's identity could not be verified. Please reconnect from the login screen.")
        }
    }

    override fun resume() {
        reconnect.appResumed(TimeUtils.millis())
    }

    /** The page or app became visible again (a browser's `visibilitychange`/`pageshow`): do not trust the connection. */
    fun onAppForeground() = reconnect.appResumed(TimeUtils.millis())

    /** The device regained its network: probe the connection, or retry at once instead of waiting out the back-off. */
    fun onNetworkOnline() = reconnect.networkReturned(TimeUtils.millis())

    /** The device lost its network: drop the connection now and start reconnecting rather than waiting for silence. */
    fun onNetworkOffline() = reconnect.networkLost(TimeUtils.millis())

    /**
     * The page is being hidden or unloaded. Closing the socket cleanly lets the server save and remove the character at
     * once instead of after its idle timeout; the resume token is kept, so coming back resumes where the player was.
     */
    fun onPageHidden() {
        if (reconnect.state == ReconnectController.State.ONLINE) netClient.close()
    }

    /** How many queued packets to apply this frame: a backgrounded tab piles them up, and a large backlog is caught up in a few frames. */
    private fun drainBudget(): Int = (128 + netClient.pendingPackets / 8).coerceAtMost(2048)

    private val autoLoot = com.morgana.client.state.AutoLootModel()

    /** Asks the server to pick up nearby loot, plays the pickup sound and, when something is in reach, the hero's gather pose. */
    private fun sendPickup() {
        netClient.send(ClientPacket.PickupGroundItem)
        audio.play(RetroSoundEngine.Sfx.PICKUP)
        worldState.triggerLocalPickup()
    }

    /** Asks for a pickup when the Auto-loot setting is on and something takeable is within reach (see [com.morgana.client.state.AutoLootModel]). */
    private fun tickAutoLoot(nowMs: Long) {
        if (!com.morgana.client.access.Accessibility.settings.autoLoot) return
        val ch = worldState.localCharacter
        val send = autoLoot.shouldPickUp(
            enabled = com.morgana.client.access.Accessibility.settings.autoLoot && worldState.authenticated,
            alive = ch != null && !ch.isGhost && (ch.vitals[worldState.lifeVitalId()]?.cur ?: 1) > 0,
            characterId = ch?.id,
            mapId = ch?.position?.mapId ?: 0,
            tileX = ch?.position?.tileX ?: 0,
            tileY = ch?.position?.tileY ?: 0,
            ground = worldState.groundItemsOnMap,
            inventory = ch?.inventory,
            items = worldState.catalog.items.associateBy { it.id },
            nowMs = nowMs,
            pickupRange = worldState.gameDefinition?.gameplayTuning?.pickupRangeTiles ?: com.morgana.domain.sim.GROUND_PICKUP_RANGE_TILES
        )
        if (send && worldState.actionReady("pickup", nowMs, 250L)) {
            sendPickup()
        }
    }

    /** Buzzes the phone for hits, death and level-ups; does nothing off touch devices or with Reduce motion on. */
    private fun updateHaptics(nowMs: Long) {
        val ch = worldState.localCharacter
        if (!touchDevice || !worldState.authenticated || ch == null) {
            haptics.reset()
            return
        }
        val hp = (ch.vitals[worldState.lifeVitalId()] ?: ch.vitals.values.firstOrNull())?.cur ?: return
        val buzz = haptics.update(ch.id, hp, ch.level, nowMs)
        if (buzz > 0 && !com.morgana.client.access.Accessibility.reduceMotion) device.vibrate(buzz)
    }

    /** One frame's failure: skip it and carry on, but hand a repeating one to the platform so the player is not left with a frozen page. */
    private fun onRenderFailure(t: Throwable) {
        renderFailures++
        Gdx.app.error("Morgana", "Frame failed ($renderFailures in a row)", t)
        if (renderFailures >= 5 && !fatalErrorShown) {
            fatalErrorShown = device.showFatalError("${t::class.simpleName ?: "Error"}: ${t.message ?: "unexpected error"}")
        }
    }

    /** Steps the interface size (saved with the other accessibility settings) and returns the new button label. */
    private fun cycleUiScale(): String {
        accessibility.cycleScale()
        return "Zoom: ${uiScaleText()}"
    }

    /** The interface size setting as shown on buttons: "Auto (175%)" or "150%". */
    private fun uiScaleText(): String {
        val setting = accessibility.current.uiScale
        return com.morgana.client.access.UiScales.describe(setting, effectiveUiScale(setting))
    }

    private fun effectiveUiScale(setting: Float): Float = com.morgana.client.access.UiScales.resolve(setting, autoUiScale())

    private fun applyUiScaleToViewport(setting: Float) {
        (stage.viewport as ScreenViewport).unitsPerPixel = 1f / effectiveUiScale(setting)
        stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    /** World zoom setting: 0 = auto (from screen density), otherwise a fixed 1x..3x. See [WorldZoom]. */
    private var worldZoomSetting = 0

    private fun worldScale(): Int =
        WorldZoom.resolve(worldZoomSetting, Gdx.graphics.width, Gdx.graphics.height, Gdx.graphics.density)

    /**
     * The map editor's own zoom ([WorldZoom.EDITOR_LEVELS]), or null until the editor's zoom buttons are used. It applies
     * only while the editor is open, so closing the editor returns to the game's whole-step zoom.
     */
    private var editorScale: Float? = null

    /** Screen pixels per world pixel right now: the editor's zoom while editing (once set), else the game's. */
    private fun effectiveWorldScale(): Float {
        val editing = editorScale
        return if (editing != null && worldState.mapEditorActive) editing else worldScale().toFloat()
    }

    private fun applyWorldZoom() {
        camera.zoom = 1f / effectiveWorldScale()
    }

    /** Zooms the world in (+1) or out (-1) from the editor toolbar, one editor level at a time. */
    private fun stepWorldZoom(delta: Int) {
        editorScale = WorldZoom.stepEditor(effectiveWorldScale(), delta)
        applyWorldZoom()
    }

    private fun cycleWorldZoom(): String {
        worldZoomSetting = WorldZoom.nextSetting(worldZoomSetting)
        applyWorldZoom()
        return WorldZoom.label(worldZoomSetting)
    }

    /** Result of the in-flight connection attempt; written by the transport (any thread), consumed in [render]. */
    @Volatile
    private var connectResult: ConnectResult? = null
    private var connectHandler: ((ConnectResult) -> Unit)? = null
    private var connecting = false
    private var trustWindow: VisWindow? = null

    private fun connectAndLoginAgain() = connectAgain()

    /** Modal-style confirmation for unknown or changed server identities; Cancel is the safe default. */
    private fun showTrustWindow(title: String, message: String, acceptLabel: String, onAccept: () -> Unit) {
        trustWindow?.remove()
        val win = VisWindow(title)
        win.defaults().pad(6f)
        val text = VisLabel(message)
        win.add(text).colspan(2).row()
        val cancel = VisTextButton("Cancel")
        val accept = VisTextButton(acceptLabel)
        cancel.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { win.remove(); trustWindow = null }
        })
        accept.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { win.remove(); trustWindow = null; onAccept() }
        })
        win.add(cancel).fillX(); win.add(accept).fillX()
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        trustWindow = win
    }

    fun showLoginWindow() {
        audio.stopBgm()
        realmsHub.show()
        if (::hud.isInitialized) {
            hud.updateHudVisibility()
        }
    }

    private fun saveHotbarPreferences() {
        val charName = worldState.localCharacter?.name ?: return
        val realmKey = activeRealmId ?: "$connectedHost:$connectedPort"
        val prefs = worldState.exportHotbarToPreferences()
        perRealmStore.updatePreferences(realmKey, charName, prefs)
    }

    private fun connectAndLogin(
        targetHost: String,
        targetPort: Int,
        targetTls: Boolean,
        username: String,
        password: String,
        classId: Int
    ) {
        val plan = ConnectionPlanner.plan(targetHost, targetPort.toString(), targetTls, knownHosts, defaultPort)
        if (plan.error != null) {
            realmsHub.setStatus(plan.error)
            return
        }
        val host = plan.host
        val port = plan.port
        connectedHost = host
        connectedPort = port
        resumeSecurity = plan.security
        resumeTls = targetTls
        reconnect.stop() // a manual login supersedes any automatic resume in progress
        resumeToken = null
        val isLocal = ConnectionPlanner.isLoopbackHost(host)
        if (isLocal && localWorlds != null) localWorlds.ensureRunning()
        if (connecting) return
        connecting = true
        realmsHub.setStatus("Connecting to $host:$port ...")

        fun sendLogin() {
            netClient.send(
                ClientPacket.Authenticate(
                    username = username.trim(),
                    password = password,
                    registerIfNew = true,
                    requestedClassId = classId,
                    requestedSpriteId = classId,
                    protocolVersion = PROTOCOL_VERSION,
                    // This client can show the character list (see CharacterSelectWindow).
                    capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.CHARACTER_SELECT + ProtocolCapabilities.DIALOGUE_FLOW
                )
            )
        }

        fun handle(result: ConnectResult) {
            connecting = false
            when (result) {
                is ConnectResult.Connected -> {}
                is ConnectResult.NeedsTrust -> {
                    showTrustWindow(
                        title = "Trust this server?",
                        message = "First time connecting to ${result.host}:${result.port}.\n" +
                            "Its certificate fingerprint is:\n${Fingerprint.display(result.fingerprint)}\n\n" +
                            "Only continue if the host gave you this fingerprint or an invite code.\nAnyone able to intercept your connection could present a different one.",
                        acceptLabel = "Trust & Connect"
                    ) {
                        knownHosts.remember(result.host, result.port, result.fingerprint)
                        connectAndLoginAgain()
                    }
                    realmsHub.setStatus("Waiting for you to confirm the server's identity.")
                    return
                }
                is ConnectResult.IdentityChanged -> {
                    showTrustWindow(
                        title = "WARNING: server identity changed",
                        message = "${result.host}:${result.port} no longer matches the identity you trusted.\n" +
                            "Before:  ${Fingerprint.display(result.expected)}\nNow:     ${Fingerprint.display(result.actual)}\n\n" +
                            "This happens if the host recreated their world or lost its data folder.\n" +
                            "It can also mean someone is intercepting your connection.\nAsk the host for a fresh invite code before continuing.",
                        acceptLabel = "Trust new identity"
                    ) {
                        knownHosts.remember(result.host, result.port, result.actual)
                        connectAndLoginAgain()
                    }
                    realmsHub.setStatus("Server identity changed. Not connected.")
                    return
                }
                is ConnectResult.InviteMismatch -> {
                    realmsHub.setStatus("This server does not match the invite code. Not connected.")
                    return
                }
                is ConnectResult.Failed -> {
                    realmsHub.setStatus(result.message)
                    return
                }
            }
            sendLogin()
        }

        fun attempt(retryLocal: Boolean) {
            connectHandler = { result ->
                if (result is ConnectResult.Failed && retryLocal && isLocal && !plan.security.tls) {
                    onEnsureLocalServer()
                    attempt(false)
                } else {
                    handle(result)
                }
            }
            netClient.connectAsync(host, port, plan.security) { connectResult = it }
        }

        connectAgain = {
            connectAndLogin(targetHost, targetPort, targetTls, username, password, classId)
        }

        attempt(retryLocal = true)
    }

    override fun render() {
        try {
            renderFrame()
            renderFailures = 0
        } catch (t: Throwable) {
            onRenderFailure(t)
        }
    }

    private fun renderFrame() {
        val delta = Gdx.graphics.deltaTime.coerceAtMost(0.1f)
        Gdx.gl.glClearColor(0.06f, 0.08f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        connectResult?.let { result ->
            connectResult = null
            connectHandler?.invoke(result)
        }

        val nowMs = TimeUtils.millis()
        // Timers freeze while a phone sleeps or a tab is hidden: a long gap between frames means the socket may be dead.
        if (lastFrameMs != 0L && nowMs - lastFrameMs > 3_000) reconnect.appResumed(nowMs)
        lastFrameMs = nowMs
        reconnectResult?.let { result ->
            reconnectResult = null
            onReconnectConnectResult(result, nowMs)
        }

        // Drain authoritative server packets
        netClient.drainPackets(drainBudget()) { packet ->
            reconnect.heard(nowMs)
            if (packet is ServerPacket.SessionEnded && !packet.resumable) {
                returnToLogin(packet.reason)
                return@drainPackets
            }
            if (packet is ServerPacket.RealmInfo) {
                return@drainPackets
            }
            if (packet is ServerPacket.CharacterList) {
                // The password was accepted; the player now picks (or makes) a character before entering the world.
                realmsHub.close()
                stage.keyboardFocus = null
                characterSelect.show(packet)
                return@drainPackets
            }
            if (packet is ServerPacket.FloatingText) {
                when (packet.category) {
                    FloatingTextCategory.CRIT_DAMAGE -> audio.play(RetroSoundEngine.Sfx.CRIT)
                    FloatingTextCategory.DAMAGE -> audio.play(RetroSoundEngine.Sfx.HIT)
                    FloatingTextCategory.HEAL -> audio.play(RetroSoundEngine.Sfx.HEAL)
                    FloatingTextCategory.LEVEL_UP -> audio.play(RetroSoundEngine.Sfx.LEVEL_UP)
                    else -> {}
                }
            } else if (packet is ServerPacket.PlaySound) {
                audio.playById(packet.soundId)
            } else if (packet is ServerPacket.ChatBroadcast && packet.channel == com.morgana.protocol.ChatChannel.BROADCAST) {
                audio.play(RetroSoundEngine.Sfx.DRAGON_BALL_DROP)
            } else if (packet is ServerPacket.TriggerAnimation && packet.soundId.isNotBlank()) {
                audio.playById(packet.soundId)
            } else if (packet is ServerPacket.ShowPicture) {
                hud.showPictureWindow(packet)
            } else if (packet is ServerPacket.TextInputPrompt) {
                hud.showTextInputWindow(packet)
            } else if (packet is ServerPacket.GameArtUpdated && packet.pngBase64.isNotEmpty()) {
                val decoded = com.morgana.protocol.Base64Codec.decodeOrNull(packet.pngBase64)
                if (decoded != null) {
                    assetCache.store(packet.entry.sha256, decoded)
                    assets.applyAssetEntry(packet.entry, decoded)
                }
            } else if (packet is ServerPacket.MapStateSnapshot) {
                // With the map cache the snapshot may name its map only by reference; null means it could not be rebuilt
                // (the world state then asks the server to resend), so there is nothing to announce yet.
                val snapshotMap = worldState.activeMapOf(packet)
                if (snapshotMap != null && worldState.activeMap?.id != snapshotMap.id) {
                    worldState.pendingGroundSpellId = null
                    pendingLootTile = null
                    if (worldState.activeMap != null) {
                        hud.closeMapWindows()
                    }
                    audio.play(RetroSoundEngine.Sfx.WARP)
                }
                if (snapshotMap != null) {
                    val bgmTrack = snapshotMap.bgmTrackId
                    if (!bgmTrack.isNullOrBlank()) {
                        audio.playBgm(bgmTrack)
                    } else {
                        audio.fadeOutBgm()
                    }
                }
            }
            worldState.applyServerPacket(packet) { openUiReq ->
                hud.handleServerWindowRequest(openUiReq)
            }
            if (packet is ServerPacket.AuthResult) {
                characterSelect.close()
                if (packet.success) {
                    realmsHub.close()
                    stage.keyboardFocus = null
                    hud.updateHudVisibility()
                    resumeToken = packet.resumeToken
                    saveResume()
                    reconnect.loggedIn(nowMs)
                    val realmKey = activeRealmId ?: "$connectedHost:$connectedPort"
                    val prefs = perRealmStore.getPreferences(realmKey, packet.character?.name ?: "Hero")
                    worldState.loadHotbarFromPreferences(prefs)
                    worldState.autoPopulateHotbarIfEmpty()

                    // Art belongs to the realm just joined: drop the previous realm's before applying this one's (also on resume).
                    activeSyncHandle?.close()
                    activeSyncHandle = null
                    assets.resetCustomArt(packet.gameDefinition?.sprites)
                    audio.clearCustomTracks()
                    // Synthesize every sound and music track now, while nothing is on screen: left lazy, the first visit to a
                    // town (its theme) or the first warp built them mid-frame and the game visibly hitched. No-op after the first login.
                    audio.loadAll()
                    artLoading = false
                    val manifest = packet.assetManifest
                    // A server that hosts its own art cannot know the name (or proxy scheme/port) players reach it by, so it sends "{host}".
                    val baseUrl = RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                        template = packet.assetBaseUrl,
                        host = connectedHost,
                        port = connectedPort,
                        encrypted = netClient.isEncrypted,
                        webSocket = netClient.isWebSocket
                    )
                    // Several small files per request when this server hosts its own art (see ProtocolCapabilities.ASSET_BATCH).
                    syncPipeline.batching = com.morgana.protocol.ProtocolCapabilities.ASSET_BATCH in packet.capabilities
                    if (manifest != null && !baseUrl.isNullOrBlank()) {
                        val generation = ++artGeneration
                        // Show nothing of the world until this realm's art is in place (no placeholders swapping out later).
                        artLoading = true
                        // A cache that reads its storage asynchronously (the browser's) loads this realm's files first, so
                        // files kept from an earlier visit count as present and are not downloaded again.
                        assetCache.prefetch(manifest.assets.values.map { it.sha256 }) {
                            onRenderThread { if (generation == artGeneration) syncRealmArt(manifest, baseUrl, generation) }
                        }
                    }
                } else if (reconnect.state != ReconnectController.State.OFFLINE) {
                    // The server refused to resume (token expired, banned, ...): nothing more to retry automatically.
                    returnToLogin(packet.message)
                } else {
                    realmsHub.show()
                    realmsHub.setStatus(packet.message)
                    hud.updateHudVisibility()
                }
            }
            if (worldState.authenticated && realmsHub.isOpen()) {
                realmsHub.close()
                stage.keyboardFocus = null
                hud.updateHudVisibility()
            }
        }

        val skipped = netClient.takeUndecodableCount()
        if (skipped > 0) undecodablePackets += skipped
        updateHaptics(nowMs)
        tickAutoLoot(nowMs)
        worldState.showAllGroundLabels = worldState.authenticated && !worldState.mapEditorActive &&
            (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT))
        pendingLootTile?.let { target ->
            val ch = worldState.localCharacter
            if (ch == null || !worldState.authenticated) {
                pendingLootTile = null
            } else {
                val dist = maxOf(kotlin.math.abs(ch.position.tileX - target.x), kotlin.math.abs(ch.position.tileY - target.y))
                if (dist <= 1) {
                    if (worldState.actionReady("pickup", nowMs, 250L)) {
                        sendPickup()
                    }
                    tapMove.cancel()
                    pendingLootTile = null
                } else if (!tapMove.isActive) {
                    pendingLootTile = null
                }
            }
        }
        if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT) || worldState.mapEditorActive || !worldState.authenticated) {
            lmbHeldOnWorld = false
        }
        if (actionCombatActive() && !isTypingInTextField() && !adminPanel.isPanelFinderOpen()) {
            val heldAttack = lmbHeldOnWorld || hud.touchAttackHeld || Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
            if (holdAttackRepeat.update(heldAttack, delta)) {
                sendActionInput("LMB", cursorAim(Gdx.input.x, Gdx.input.y))
            }
        } else {
            holdAttackRepeat.reset()
            if (worldState.authenticated && !worldState.mapEditorActive && !isTypingInTextField() && !adminPanel.isPanelFinderOpen()) {
                val attackHeld = com.morgana.client.access.Accessibility.keys.isDown(com.morgana.client.access.GameAction.ATTACK) { Gdx.input.isKeyPressed(it) }
                if ((attackHeld || hud.touchAttackHeld) && worldState.swingReady(nowMs)) {
                    netClient.send(ClientPacket.Attack)
                    worldState.triggerLocalAttackOrHarvest()
                    audio.play(RetroSoundEngine.Sfx.SWING)
                }
            }
        }
        if (worldState.authenticated != screenKeptOn) {
            screenKeptOn = worldState.authenticated
            device.keepScreenOn(screenKeptOn)
        }
        sendGate.bufferedBytes = netClient.bufferedBytes

        // The character screen is shown before the world, where nothing pings: keep the connection alive while the player decides.
        if (characterSelect.isOpen() && previewCharacters == null) {
            if (!netClient.isConnected) returnToLogin("The connection to the server was lost.")
            else if (nowMs - lastSelectPingMs >= 10_000) { lastSelectPingMs = nowMs; netClient.send(ClientPacket.Ping(nowMs)) }
        }

        // A snapshot that could not be rebuilt from the map cache: ask the server to send the maps again.
        if (worldState.takeMapResyncRequest() && netClient.isConnected) netClient.send(ClientPacket.RequestMapResync)

        when (val action = reconnect.update(nowMs, netClient.isConnected)) {
            ReconnectController.Action.None -> {}
            ReconnectController.Action.SendPing -> netClient.send(ClientPacket.Ping(nowMs))
            ReconnectController.Action.DropConnection -> netClient.close()
            is ReconnectController.Action.StartAttempt -> beginReconnectAttempt()
        }
        // While playing, probe the link every couple of seconds: a probe that goes unanswered pauses attacks and spells.
        if (worldState.authenticated && netClient.isConnected && sendGate.pingDue(nowMs)) netClient.send(ClientPacket.Ping(nowMs))
        banner.update(
            when {
                !worldState.authenticated -> null
                reconnect.isReconnecting -> reconnect.statusText(nowMs) +
                    if (reconnect.state == ReconnectController.State.GAVE_UP) "" else " Your character is safe."
                undecodablePackets >= 3 -> "Some server messages were not understood. Reload the page to update your client."
                sendGate.isStalled(nowMs) -> "Poor connection: attacks and spells are paused until the server answers."
                reconnect.isStalled(nowMs) -> "Waiting for the server..."
                else -> null
            }
        )

        worldState.latestRttMs = sendGate.rttMs
        linkIndicator.update(
            if (worldState.authenticated) com.morgana.client.net.LinkQuality.of(
                sendGate.rttMs, sendGate.isStalled(nowMs), netClient.isConnected && !reconnect.isReconnecting
            ) else null
        )

        // Poll continuous movement keys if authenticated and not actively typing in a TextField
        // A movement key that types nothing (an arrow, a modifier) also works while a text field has focus and takes the focus away.
        val moveKeys = com.morgana.client.access.Accessibility.keys
        fun held(action: com.morgana.client.access.GameAction) = moveKeys.isDown(action) { Gdx.input.isKeyPressed(it) }
        val arrowPressed = com.morgana.client.access.GameAction.entries.any { a ->
            a.isMovement && moveKeys.keysFor(a).any { k -> com.morgana.client.access.KeyBindings.isNonTextKey(k) && Gdx.input.isKeyPressed(k) }
        }
        val touchDir = if (worldState.authenticated) hud.heldTouchDirection() else null
        if (worldState.authenticated && (!isTypingInTextField() || arrowPressed || touchDir != null)) {
            if ((arrowPressed || touchDir != null) && isTypingInTextField()) {
                stage.keyboardFocus = null
            }
            moveRepeatTimer -= delta
            if (moveRepeatTimer <= 0f) {
                val up = held(com.morgana.client.access.GameAction.MOVE_UP)
                val down = held(com.morgana.client.access.GameAction.MOVE_DOWN)
                val left = held(com.morgana.client.access.GameAction.MOVE_LEFT)
                val right = held(com.morgana.client.access.GameAction.MOVE_RIGHT)
                val freeMovement = worldState.movementMode == MovementMode.SUB_TILE
                // Opposite keys cancel. Free pixel movement combines two held keys into a diagonal; tile movement stays cardinal.
                val keyDy = (if (down) 1 else 0) - (if (up) 1 else 0)
                val keyDx = (if (right) 1 else 0) - (if (left) 1 else 0)
                val keyDir = if (freeMovement) {
                    Direction.fromDelta(keyDx, keyDy)
                } else when {
                    keyDy < 0 -> Direction.UP
                    keyDy > 0 -> Direction.DOWN
                    keyDx < 0 -> Direction.LEFT
                    keyDx > 0 -> Direction.RIGHT
                    else -> null
                }
                val manualDir = keyDir ?: touchDir
                if (manualDir != null) tapMove.cancel()
                val moveDir = manualDir ?: nextTapDirection()
                if (moveDir != null && !sendGate.movesAllowed(worldState.unacknowledgedMoveCount, nowMs)) {
                    // Too many steps are still on the wire: wait for the server to catch up instead of queueing more.
                    moveRepeatTimer = 0.05f
                } else if (moveDir != null) {
                    val movePkt = worldState.predictAndCreateMovePacket(moveDir, sprint = held(com.morgana.client.access.GameAction.SPRINT) || hud.touchSprint)
                    netClient.send(movePkt)
                    // Free movement sends one step per server tick (50 ms) on average: the leftover time carries over, so the
                    // frame rate cannot push the client above the server's one-step-per-tick limit (or below it). After a
                    // pause (a released key, a stalled frame) the timer restarts instead of firing a burst of catch-up steps.
                    val interval = if (worldState.movementMode == MovementMode.SUB_TILE) 0.05f else 0.13f
                    moveRepeatTimer = if (moveRepeatTimer > -interval) moveRepeatTimer + interval else interval
                }
            }
        }

        audio.update(delta)
        worldState.updateVisualTimers(delta)
        applyWorldZoom()
        // Docked editor panels cover part of the screen; show the world in the rest (stage units -> screen pixels).
        adminPanel.relayoutIfResized()
        // The touch joystick must not cover the map while it is being edited, so refresh the HUD when the editor opens or closes.
        if (worldState.mapEditorActive != editorWasActive) {
            editorWasActive = worldState.mapEditorActive
            hud.updateHudVisibility()
        }
        val covered = adminPanel.viewInsets()
        hud.setViewInsets(covered.left, covered.right, covered.top)
        hud.editorOpen = adminPanel.isEditorOpen()
        worldRenderer.viewInsets = if (covered.isNone) com.morgana.client.editor.dock.ViewInsets.NONE else {
            val kx = Gdx.graphics.width / stage.width
            val ky = Gdx.graphics.height / stage.height
            com.morgana.client.editor.dock.ViewInsets(covered.left * kx, covered.right * kx, covered.top * ky, covered.bottom * ky)
        }
        if (!artLoading) {
            val fxMap = worldState.activeMap
            val clockDef = worldState.gameDefinition?.clock
            val clock = worldState.dayNightClock()
            com.morgana.client.render.VisualFx.sky =
                if (clock != null && clockDef != null && fxMap != null && !fxMap.isIndoors) {
                    com.morgana.client.render.VisualSky.at(clockDef, clock.timeOfDayPermille)
                } else null
            val fx = postFx
            if (fx != null && fxMap != null && com.morgana.client.render.VisualFx.postProcessing) {
                fx.render(batch, camera, fxMap, com.morgana.client.render.VisualFx.sky, delta) {
                    worldRenderer.renderWorld(batch, camera, worldState, delta)
                }
            } else {
                worldRenderer.renderWorld(batch, camera, worldState, delta)
            }
        }

        hud.refreshHud()
        stage.act(delta)
        textEntrySync?.sync()
        stage.draw()
    }

    /** Next step of an active tap-to-move walk, or null when there is none (or it just ended). */
    private fun nextTapDirection(): Direction? {
        if (!tapMove.isActive) return null
        val ch = worldState.localCharacter ?: return null
        val map = worldState.activeMap ?: return null
        return tapMove.nextDirection(
            currentMapId = ch.position.mapId,
            tile = ch.position.tileCoord,
            pixelX = ch.position.pixelX,
            pixelY = ch.position.pixelY,
            tileSize = map.tileSize,
            subTile = ch.movementMode == MovementMode.SUB_TILE,
            width = map.width,
            height = map.height,
            canStep = worldState::canTapStep
        )
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        // Auto follows the monitor, which can change when the window moves to another screen.
        (stage.viewport as ScreenViewport).unitsPerPixel = 1f / effectiveUiScale(accessibility.current.uiScale)
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        if (::adminPanel.isInitialized) adminPanel.dispose()
        netClient.close()
        if (::hud.isInitialized) hud.dispose()
        audio.dispose()
        postFx?.dispose()
        assets.dispose()
        batch.dispose()
        font.dispose()
        stage.dispose()
        com.morgana.client.ui.SkinThemer.dispose()
        if (VisUI.isLoaded()) {
            VisUI.dispose()
        }
    }
}

