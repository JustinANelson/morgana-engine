package com.morgana.client.ui

import com.morgana.client.ui.bag.CharacterMenu
import com.morgana.client.ui.bag.MenuSection
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.audio.RetroSoundEngine
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ChatFormatting
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.HotbarSlotAction
import com.morgana.domain.game.BlankGame
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.PluginWindowDto
import com.morgana.protocol.TipsAction
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.ServerUiWindowType
import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.entity.MarketListingDocument
import com.morgana.protocol.ExploredMapInfo
import com.morgana.client.access.Accessibility
import com.morgana.client.access.AccessibilityController
import com.morgana.client.access.ActionGroup
import com.morgana.client.access.AccessibilityStore
import com.morgana.client.access.ColourPalette
import com.morgana.client.access.GameAction
import com.morgana.client.access.KEYS_PER_ACTION
import com.morgana.client.access.KeyBindings
import com.morgana.client.access.PaletteRole
import com.morgana.client.access.UiScales
import com.morgana.client.editor.dock.MemoryKeyValueStore
import com.morgana.protocol.FriendActionType
import com.morgana.domain.explore.Exploration
import com.morgana.domain.explore.GridPos
import com.morgana.domain.explore.MapNode
import com.morgana.domain.explore.WorldMapLayout
import com.morgana.protocol.GuildActionType
import com.morgana.protocol.GuildMemberEntry
import com.morgana.protocol.IgnoreActionType
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.social.GuildRules
import com.morgana.protocol.FriendEntry
import com.morgana.protocol.MailActionType
import com.morgana.protocol.MarketActionType
import com.morgana.protocol.TradeActionType
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisWindow

/**
 * Scene2D + VisUI Player HUD, Minimap Radar, 10-Slot Hotbar (Keys 1..0), and interactive modal windows
 * (Inventory, Equipment/Stats, Spells, Quests, Crafting, Shop, Bank, Trade, Dialogue, and Multi-Channel Chat).
 */
class PlayerHudAndWindows(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    val audio: RetroSoundEngine = RetroSoundEngine(),
    private val onToggleF1Admin: () -> Unit,
    private val onHotbarChanged: () -> Unit = {},
    /** True on phones/tablets (Android or a touch browser): shows the touch pad, bigger buttons, and starts chat minimized. */
    private val touchDevice: Boolean = false,
    /** Cycles the UI zoom and returns the new button label. */
    private val onCycleUiScale: () -> String = { "Zoom: 100%" },
    private val uiScaleLabel: () -> String = { "Zoom: 100%" },
    /** Cycles the world zoom (Auto, 1x, 2x, 3x) and returns the new button label. */
    private val onCycleWorldZoom: () -> String = { "Auto" },
    private val worldZoomLabel: () -> String = { "Auto" },
    /** Ends the session and returns to the login screen. */
    private val onLogout: () -> Unit = {},
    /** The accessibility settings in force; the Accessibility window changes them through it. */
    private val accessibility: AccessibilityController = AccessibilityController(AccessibilityStore(MemoryKeyValueStore()))
) {
    private val theme = GameTheme.shared
    private val toastOverlay = ToastOverlay(stage, worldState.toasts)
    private val vitalsLabel = VisLabel("HP: --/-- | MP: --/-- | SP: --/--")
    private val vitalsBarRow = VisTable()
    private val statusEffectRow = VisTable()
    private var lastStatusSig: String = ""
    private val vitalBars = linkedMapOf<String, VitalBar>()
    private var xpBar = theme.vitalBar(UiColors.xp(), width = 444f, height = 12f)
    private val charSummaryLabel = VisLabel("Lv 1 | XP: 0/100 | Map: --")
    private val chatHistoryTable = VisTable()
    private var lastChatRenderKey: String = ""
    private val chatInputField = VisTextField("")
    private val chatChannelBox = VisSelectBox<ChatChannel>()
    private val minimapMetaLabel = VisLabel("Ping: -- / FPS: --", theme.mutedLabelStyle)
    private var gmQuickBar: VisTable? = null

    private var characterMenu: VisWindow? = null
    private var tipWindow: VisWindow? = null
    private var charTopButton: VisTextButton? = null
    private var spellbookWindow: VisWindow? = null
    private var questWindow: VisWindow? = null
    private var craftingWindow: VisWindow? = null
    private var shopWindow: VisWindow? = null
    private var bankWindow: VisWindow? = null
    private var dialogueWindow: VisWindow? = null
    private var textInputWindow: VisWindow? = null
    private var pictureWindow: VisWindow? = null
    private var tradeWindow: VisWindow? = null
    private var partyWindow: VisWindow? = null
    private var partyStatusTable: VisTable? = null
    private var partyShownKey: String? = null
    private var friendsWindow: VisWindow? = null
    private var friendsListTable: VisTable? = null
    private var friendsShown: List<FriendEntry>? = null
    private var guildWindow: VisWindow? = null
    private var guildShownGuild: GuildDocument? = null
    private var guildShownRoster: List<GuildMemberEntry>? = null
    private var ignoreWindow: VisWindow? = null
    private var ignoreListTable: VisTable? = null
    private var ignoreShown: List<String>? = null
    private var trackerWindow: HudWindow? = null
    private var trackerContent: VisTable? = null
    private var trackerShown: List<String>? = null
    private var accessibilityWindow: VisWindow? = null
    private var controlsWindow: com.badlogic.gdx.scenes.scene2d.ui.Window? = null
    private var controlsNote: String = ""
    private var controlsTopButton: VisTextButton? = null
    /** The quickbar contents the Controls window shows, to rebuild it only when they change. */
    private var controlsShownSlots: List<HotbarSlotAction?> = emptyList()
    private var worldMapWindow: VisWindow? = null
    private var worldMapContent: VisTable? = null
    private var worldMapShown: Any? = null
    private var marketWindow: VisWindow? = null
    private var marketListTable: VisTable? = null
    private var marketShown: List<MarketListingDocument>? = null
    private var mailWindow: VisWindow? = null
    private var mailListTable: VisTable? = null
    private var mailShown: List<MailMessageDocument>? = null
    private var mailSelectedId: String? = null
    private var touchControls: TouchControls? = null
    private var settingsWindow: VisWindow? = null
    private var menuWindow: VisWindow? = null
    private var compactStatus: CompactStatus? = null
    private var dungeonHudOverlay: DungeonHudOverlay? = null
    private var partyFramesOverlay: PartyFramesOverlay? = null
    private var lowHpVignetteOverlay: LowHpVignetteOverlay? = null
    private var runSummaryWindow: HudWindow? = null
    private var menuButton: VisTextButton? = null

    /** Tapping or clicking a tile walks the player there. On by default; Settings can turn it off. */
    var tapToMoveEnabled: Boolean = true
    var jumpEnabled: Boolean = true

    /** Whether the on-screen joystick/buttons are shown (on by default for touch devices; toggle in Settings). */
    var touchControlsEnabled: Boolean = touchDevice
        set(value) { field = value; updateHudVisibility() }
    private var minimapWindow: VisTable? = null
    private var hotbarWindow: VisTable? = null
    private var topFrameWindow: VisTable? = null
    private var menuDockWindow: VisTable? = null
    private var menuDockRow1: VisTable? = null
    private var menuDockRow2: VisTable? = null
    private var menuDockRow1Buttons: List<VisTextButton> = emptyList()
    private var menuDockRow2Buttons: List<VisTextButton> = emptyList()
    private var chatWindow: com.badlogic.gdx.scenes.scene2d.ui.Table? = null
    private var activeChatTab: ChatTab = ChatTab.ALL
    private enum class ChatTab(val label: String) { ALL("All"), GLOBAL("Global"), PARTY("Party"), GUILD("Guild"), SYSTEM("System") }
    private var charInvTopButton: VisTextButton? = null
    private var questsTopButton: VisTextButton? = null
    private var craftingTopButton: VisTextButton? = null
    private var spellsTopButton: VisTextButton? = null
    private var shownAbilityTerminology: com.morgana.domain.content.AbilityTerminology? = null
    private var partyTopButton: VisTextButton? = null
    private var guildTopButton: VisTextButton? = null
    private var pkModeTopButton: VisTextButton? = null
    private var f1AdminTopButton: VisTextButton? = null
    private var stallTopButton: VisTextButton? = null
    private val bestiaryWindow by lazy { BestiaryWindow(stage, worldState) }
    private val petsWindow by lazy { PetsWindow(stage, worldState) { netClient.send(ClientPacket.AdminSlashCommand(it)) } }
    private val stallWindow by lazy { StallWindow(stage, worldState) { netClient.send(ClientPacket.AdminSlashCommand(it)) } }
    /** Chat lines that arrived while the chat window was minimized, shown in its title. */
    private val chatUnread = UnreadCounter<ServerPacket.ChatBroadcast>()
    private var minimapTopButton: VisTextButton? = null
    private var touchHudInitialized = false
    private val minimapCoordLabel = VisLabel("")

    internal val touchControlsForTest: TouchControls?
        get() = touchControls

    val isDialogueWindowOpen: Boolean
        get() = dialogueWindow?.stage != null || textInputWindow?.stage != null

    private val hotbarTips = arrayOfNulls<com.kotcrab.vis.ui.widget.Tooltip>(10)
    /** The short name of the first key of quickbar slot [idx], for its button ("1", "Q", "Np1"). */
    private fun hotbarKey(idx: Int): String =
        Accessibility.keys.keysFor(GameAction.hotbar(idx)).firstOrNull()?.let { KeyBindings.shortKeyName(it) } ?: "-"

    private val hotbarButtons = Array(10) { idx ->
        CooldownButton("[${hotbarKey(idx)}] -").apply {
            fun spellId() = (worldState.hotbarSlots.getOrNull(idx) as? HotbarSlotAction.CastSpell)?.spellId
            fractionLeft = { spellId()?.let { worldState.spellCooldowns.fractionLeft(it, ToastClock.now()) } ?: 0f }
            secondsLeft = { spellId()?.let { worldState.spellCooldowns.remaining(it, ToastClock.now()) } ?: 0f }
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    triggerHotbar(idx)
                }
            })
        }
    }

    fun buildHud() {
        // Top-Left Player Unit Frame (frameless portrait + name/level + stacked vital bars)
        val topFrame = VisTable()
        topFrame.background = theme.dynamic { t, p -> t.beveledPanel(p.panel, highlight = p.border, padX = 7f, padY = 6f) }
        topFrame.defaults().pad(2f).left()

        val portraitBox = object : Actor() {
            private val frameBg = theme.dynamic { t, p -> t.recessedSlot(p.background, p.accent, padX = 2f, padY = 2f) }
            override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
                val ch = worldState.localCharacter
                batch.setColor(1f, 1f, 1f, parentAlpha * color.a)
                frameBg.draw(batch, x, y, width, height)
                if (ch != null) {
                    val layers = worldState.playersOnMap[ch.id]?.paperdollLayers.orEmpty()
                    val region = if (layers.isNotEmpty()) {
                        assets.getOrComposeCharacterRegion(
                            characterId = ch.id,
                            spriteId = ch.spriteId,
                            layers = layers,
                            direction = Direction.DOWN,
                            walkFrame = 0
                        ) ?: assets.getSpriteRegion(ch.spriteId, Direction.DOWN, 0)
                    } else {
                        assets.getSpriteRegion(ch.spriteId, Direction.DOWN, 0)
                    }
                    // Crop/center the upper portion of the sprite as a portrait bust
                    val pad = 4f
                    val drawW = (width - pad * 2f).coerceAtLeast(1f)
                    val drawH = (height - pad * 2f).coerceAtLeast(1f)
                    batch.draw(region, x + pad, y + pad, drawW, drawH)
                }
            }
        }
        val infoCol = VisTable()
        infoCol.defaults().left().padBottom(2f)
        infoCol.add(charSummaryLabel).row()
        infoCol.add(vitalsBarRow).row()
        infoCol.add(statusEffectRow).row()
        infoCol.add(vitalsLabel).row()

        topFrame.add(portraitBox).size(48f, 48f).top().padRight(8f)
        topFrame.add(infoCol).top()
        topFrame.pack()
        topFrame.setPosition(12f, stage.height - topFrame.height - 12f)
        stage.addActor(topFrame)
        topFrameWindow = topFrame

        // Bottom-Right System & Menu Dock
        menuDockWindow?.remove()
        val dock = VisTable()
        dock.background = theme.dynamic { t, p -> t.beveledPanel(p.panel, highlight = p.border, padX = 5f, padY = 5f) }
        dock.defaults().pad(2f)
        val row1 = VisTable()
        row1.defaults().padRight(3f)
        val cBtn = dockIconButton("hud_char", "Char (${keyHint(GameAction.CHARACTER)})", keyHint(GameAction.CHARACTER), "Character") { toggleCharacterWindow() }.also { charTopButton = it }
        val iBtn = dockIconButton("hud_bag", "Inv (${keyHint(GameAction.INVENTORY)})", keyHint(GameAction.INVENTORY), "Inventory") { toggleCharacterAndInventoryWindow() }.also { charInvTopButton = it }
        val term = worldState.abilityTerminology()
        shownAbilityTerminology = term
        val sBtn = dockIconButton("hud_skills", "${term.pluralLabel} (${keyHint(GameAction.SPELLBOOK)})", keyHint(GameAction.SPELLBOOK), term.pluralLabel) { toggleSpellbookWindow() }.also { spellsTopButton = it }
        val qBtn = dockIconButton("hud_quests", "Quests (${keyHint(GameAction.QUESTS)})", keyHint(GameAction.QUESTS), "Quests") { toggleQuestWindow() }.also { questsTopButton = it }
        val crBtn = dockIconButton("hud_craft", "Craft (${keyHint(GameAction.CRAFTING)})", keyHint(GameAction.CRAFTING), "Crafting") { toggleCraftingWindow() }.also { craftingTopButton = it }
        val pBtn = dockIconButton("hud_party", "Party (${keyHint(GameAction.PARTY)})", keyHint(GameAction.PARTY), "Party") { togglePartyWindow() }.also { partyTopButton = it }
        val gBtn = dockIconButton("hud_guild", "Guild", "G", "Guild") { toggleGuildWindow() }.also { guildTopButton = it }
        val mBtn = dockIconButton("hud_map", "Minimap (${keyHint(GameAction.MINIMAP)})", keyHint(GameAction.MINIMAP), "Minimap") { toggleMinimap() }.also { minimapTopButton = it }
        menuDockRow1Buttons = listOf(cBtn, iBtn, sBtn, qBtn, crBtn, pBtn, gBtn, mBtn)
        menuDockRow1 = row1
        dock.add(row1).right().row()

        val row2 = VisTable()
        row2.defaults().padRight(3f)
        val pkBtn = actionButton("Mode: Peace") { cyclePkMode() }.also { pkModeTopButton = it }
        val stBtn = actionButton("Stall") { stallWindow.toggle() }.also { stallTopButton = it }
        val kBtn = actionButton("Keys (${keyHint(GameAction.CONTROLS)})") { toggleControlsWindow() }.also { controlsTopButton = it }
        val sysBtn = dockIconButton("hud_settings", "Settings", "Sys", "Settings") { toggleSettingsWindow() }
        val f1Btn = actionButton("F1 Admin") { onToggleF1Admin() }.also { f1AdminTopButton = it }
        menuDockRow2Buttons = listOf(pkBtn, stBtn, kBtn, sysBtn, f1Btn)
        menuDockRow2 = row2
        dock.add(row2).right().row()
        menuDockWindow = dock
        refreshMenuDockLayout()
        stage.addActor(dock)

        // Console-style touch overlay lives at the very back so every window sits above it and wins touches.
        compactStatus?.let { it.remove(); it.dispose() }
        dungeonHudOverlay?.let { it.remove(); it.dispose() }
        partyFramesOverlay?.let { it.remove(); it.dispose() }
        menuButton?.remove()
        compactStatus = null
        dungeonHudOverlay = null
        partyFramesOverlay = null
        lowHpVignetteOverlay = null
        menuButton = null
        lowHpVignetteOverlay = LowHpVignetteOverlay(worldState).also { stage.addActor(it) }
        dungeonHudOverlay = DungeonHudOverlay(worldState) { VisUI.getSkin().getFont("default-font") }.also { stage.addActor(it) }
        partyFramesOverlay = PartyFramesOverlay(worldState, assets) { VisUI.getSkin().getFont("default-font") }.also { stage.addActor(it) }
        if (touchDevice) {
            // Phones get a frameless status strip plus a single Menu button instead of the wide top bar.
            compactStatus = CompactStatus(worldState) { VisUI.getSkin().getFont("default-font") }.also { stage.addActor(it) }
            menuButton = VisTextButton("Menu").also { b ->
                b.color.a = 0.85f
                b.setSize(78f, 42f)
                b.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) { toggleMenuWindow() }
                })
                stage.addActor(b)
            }
            layoutCompactHud()
        }
        touchControls?.let { it.remove(); it.dispose() }
        touchControls = buildTouchControls().also { stage.root.addActorAt(0, it) }

        // Top-Right Minimap Radar Window
        if (worldState.showMinimap && worldState.authenticated) {
            openMinimapWindow()
        }

        // Bottom-Center 10-Slot Quick Action Hotbar (Keys 1..0)
        buildHotbar()

        // Bottom-Left Multi-Channel Chat Box (Tabbed & Translucent on desktop; collapsible HudWindow on touch)
        chatHistoryTable.top().left()
        val scroll = VisScrollPane(chatHistoryTable)
        scroll.setFadeScrollBars(false)
        scroll.setScrollingDisabled(true, false)
        chatChannelBox.setItems(*ChatChannel.entries.toTypedArray())
        chatChannelBox.selected = ChatChannel.MAP

        // Top-Center Staff/GM Quick-Bar (hidden for non-staff and touch)
        gmQuickBar?.remove()
        val gmBar = VisTable()
        gmBar.background = theme.dynamic { t, p -> t.beveledPanel(p.panel, highlight = p.border, padX = 6f, padY = 3f) }
        gmBar.defaults().padRight(3f)
        gmBar.add(VisLabel("GM Tool", theme.accentLabelStyle)).padRight(6f)
        gmBar.add(actionButton("GM") { onToggleF1Admin() })
        gmBar.add(actionButton("Tiles") { onToggleF1Admin() })
        gmBar.add(actionButton("NPCs") { onToggleF1Admin() })
        gmBar.add(actionButton("Debug") { onToggleF1Admin() }).padRight(0f)
        gmBar.pack()
        gmBar.setPosition((stage.width - gmBar.width) * 0.5f, stage.height - gmBar.height - 22f)
        stage.addActor(gmBar)
        gmQuickBar = gmBar

        if (touchDevice) {
            val chatWin = HudWindow("Chat & Command Console", rememberAs = "Chat & Command Console")
            chatWin.isMovable = true
            chatWin.add(scroll).width(440f).height(130f).colspan(3).pad(4f).row()
            chatWin.add(chatChannelBox).width(95f).pad(3f)
            chatWin.add(chatInputField).width(265f).pad(3f)
            chatWin.add(actionButton("Send") { submitChatMessage() }).width(70f).pad(3f).row()
            chatWin.pack()
            chatWin.setPosition(12f, 12f)
            stage.addActor(chatWin)
            chatWindow = chatWin
        } else {
            val chatBox = VisTable()
            chatBox.background = theme.dynamic { t, p ->
                t.beveledPanel(Color(p.background.r, p.background.g, p.background.b, 0.78f), highlight = p.border, padX = 5f, padY = 4f)
            }
            val bodyTable = VisTable()
            bodyTable.add(scroll).width(410f).height(124f).colspan(3).pad(3f).row()
            bodyTable.add(chatChannelBox).width(88f).pad(2f)
            bodyTable.add(chatInputField).width(250f).pad(2f)
            bodyTable.add(actionButton("Send") { submitChatMessage() }).width(60f).pad(2f).row()

            val tabBar = VisTable()
            tabBar.defaults().padRight(2f)
            val tabGroup = com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup<VisTextButton>()
            tabGroup.setMinCheckCount(1)
            tabGroup.setMaxCheckCount(1)
            for (tab in ChatTab.entries) {
                val btn = VisTextButton(tab.label, theme.tabStyle).apply {
                    setProgrammaticChangeEvents(false)
                    isChecked = (tab == activeChatTab)
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: Actor?) {
                            if (isChecked) activeChatTab = tab
                        }
                    })
                }
                tabGroup.add(btn)
                tabBar.add(btn)
            }
            val collapseBtn = actionButton("-") {
                bodyTable.isVisible = !bodyTable.isVisible
                chatBox.pack()
            }
            tabBar.add().expandX()
            tabBar.add(collapseBtn).width(22f)
            chatBox.add(tabBar).growX().padBottom(2f).row()
            chatBox.add(bodyTable).row()
            chatBox.pack()
            chatBox.setPosition(12f, 10f)
            stage.addActor(chatBox)
            chatWindow = chatBox
        }

        updateHudVisibility()
    }

    /** Counts chat lines that arrive while the chat window is minimized and shows the total in its title ("Chat (3)"). */
    private fun updateChatBadge() {
        val win = chatWindow as? HudWindow ?: return
        val unread = chatUnread.update(worldState.chatHistory, win.collapsed)
        win.badge = if (unread > 0) unread.toString() else ""
    }

    /** Pulls every window back inside the stage (after a zoom or resize changes the stage size). */
    fun keepWindowsOnScreen() {
        stage.actors.forEach { a ->
            if (a is com.badlogic.gdx.scenes.scene2d.ui.Window) {
                a.x = a.x.coerceIn(0f, (stage.width - a.width).coerceAtLeast(0f))
                a.y = a.y.coerceIn(0f, (stage.height - a.height).coerceAtLeast(0f))
            }
        }
    }

    /** True while the F1 editor is on screen: the game's own windows (status, chat, hotbar, minimap) step aside so the map is clear. */
    var editorOpen = false

    /** True while the realm's art is still loading after login: the HUD stays hidden so no placeholder art flashes. */
    var holdBack: Boolean = false
        set(value) {
            if (field != value) { field = value; updateHudVisibility() }
        }

    fun updateHudVisibility() {
        val show = worldState.authenticated && !holdBack
        val playing = show && !editorOpen
        if (show && touchDevice && !touchHudInitialized) {
            // First login on a touch device: movement pad up, chat and minimap tucked away so the map stays visible.
            touchHudInitialized = true
            (chatWindow as? HudWindow)?.setCollapsed(true)
            (minimapWindow as? HudWindow)?.setCollapsed(true)
        }
        topFrameWindow?.isVisible = playing && !touchDevice
        menuDockWindow?.isVisible = playing && !touchDevice
        val isStaff = (worldState.localCharacter?.accessLevel?.rank ?: 0) > 0
        gmQuickBar?.isVisible = playing && !touchDevice && isStaff
        val term = worldState.abilityTerminology()
        if (term != shownAbilityTerminology) {
            shownAbilityTerminology = term
            spellsTopButton?.setText(keyHint(GameAction.SPELLBOOK))
        }
        spellsTopButton?.isVisible = worldState.showSpellsUi()
        craftingTopButton?.isVisible = worldState.showCraftingUi()
        partyTopButton?.isVisible = worldState.showPartyUi()
        guildTopButton?.isVisible = worldState.isModuleEnabled("guilds")
        refreshPkModeButton()
        // Staff open the editors from this button; players still have the F1 key but no button to clutter the bar.
        // The Stall button shows only where this game allows stalls (Halcyon Reach: the Bazaar).
        stallTopButton?.isVisible = StallCommands.availableOn(worldState.gameDefinition?.stall, worldState.localCharacter?.position?.mapId ?: -1)
        f1AdminTopButton?.isVisible = (worldState.localCharacter?.accessLevel?.rank ?: 0) > 0
        minimapTopButton?.isVisible = worldState.showMinimapUi()
        refreshMenuDockLayout()
        compactStatus?.isVisible = playing
        dungeonHudOverlay?.isVisible = playing
        partyFramesOverlay?.isVisible = playing
        menuButton?.isVisible = playing
        chatWindow?.isVisible = playing && worldState.showChatUi()
        hotbarWindow?.isVisible = playing && !touchDevice && worldState.showAnyHotbarSlot()
        if (playing && worldState.showMinimap && worldState.showMinimapUi() && minimapWindow == null) {
            openMinimapWindow()
        }
        minimapWindow?.isVisible = playing && worldState.showMinimap && worldState.showMinimapUi()
        val showTouch = shouldShowTouchControls(show, touchControlsEnabled, worldState.mapEditorActive)
        touchControls?.let { tc ->
            if (!showTouch && tc.isVisible) tc.releaseAll()
            tc.isVisible = showTouch
        }
    }

    private fun buildHotbar() {
        hotbarWindow?.remove()
        val dock = VisTable()
        dock.background = theme.dynamic { t, p -> t.beveledPanel(p.panel, highlight = p.accent, padX = 5f, padY = 5f) }
        dock.defaults().pad(2f)

        val table = VisTable()
        table.defaults().pad(2f).width(42f).height(42f)
        hotbarButtons.forEachIndexed { idx, btn ->
            if (!touchDevice) {
                hotbarTips[idx] = com.kotcrab.vis.ui.widget.Tooltip(theme.tooltipStyle).also { it.setTarget(btn) }
            }
            table.add(btn)
            if (touchDevice && idx == 4) table.row()
        }
        dock.add(table).row()
        xpBar = theme.vitalBar(UiColors.xp(), width = 444f, height = 14f)
        dock.add(xpBar).growX().height(14f).padTop(3f).row()
        dock.pack()
        dock.setPosition((stage.width - dock.width) * 0.5f, 10f)
        stage.addActor(dock)
        hotbarWindow = dock
        refreshHotbar()
    }

    fun refreshHotbar() {
        val invSlots = worldState.localCharacter?.inventory?.slots
        hotbarButtons.forEachIndexed { idx, btn ->
            val keyChar = hotbarKey(idx)
            val action = worldState.hotbarSlots.getOrNull(idx)
            btn.isVisible = worldState.showHotbarSlot(idx)
            btn.keyText = keyChar
            btn.icon = action?.let { assets.getItemIconRegion(it.iconId) }
            btn.countText = when (action) {
                is HotbarSlotAction.UseItem -> {
                    val total = invSlots?.filterNotNull()?.filter { it.itemId == action.itemId }?.sumOf { it.amount } ?: 0
                    if (total > 0) total.toString() else ""
                }
                is HotbarSlotAction.CastSpell -> {
                    val spell = worldState.catalog.spells.firstOrNull { it.id == action.spellId }
                    val cost = spell?.costs?.values?.firstOrNull { it > 0 } ?: 0
                    if (cost > 0) cost.toString() else ""
                }
                null -> ""
            }
            hotbarTips[idx]?.setText(
                when (action) {
                    null -> "Empty slot [$keyChar]. Keys: Controls (${keyHint(GameAction.CONTROLS)})"
                    is HotbarSlotAction.CastSpell -> "Cast ${action.spellName} [$keyChar]" + hotbarSpellDetail(action.spellId)
                    is HotbarSlotAction.UseItem -> "Use ${action.label} [$keyChar]"
                }
            )
            btn.setText("")
        }
        hotbarWindow?.isVisible = worldState.authenticated && !touchDevice && worldState.showAnyHotbarSlot()
        // The Controls window's quickbar rows show what each slot holds: rebuild it only when that changed (this runs every
        // frame, and rebuilding every frame would replace a button between the press and the release of a click).
        val slots = worldState.hotbarSlots.toList()
        if (controlsWindow != null && slots != controlsShownSlots) rebuildControlsWindow()
    }

    /** A new line of cost, cooldown and range under a quickbar spell's tip, or nothing when the spell is unknown. */
    private fun hotbarSpellDetail(spellId: Int): String {
        val spell = worldState.catalog.spells.firstOrNull { it.id == spellId } ?: return ""
        val game = (worldState.compiledGame ?: BlankGame.COMPILED).definition
        val names = com.morgana.client.ui.bag.StatNames(
            attributes = game.attributes.associate { it.id to it.short.ifBlank { it.name } },
            vitals = game.vitals.associate { it.id to it.short.ifBlank { it.name } }
        )
        val left = worldState.spellCooldowns.remaining(spellId, ToastClock.now())
        return "\n" + com.morgana.client.ui.bag.SpellInfo.quickLine(spell, names, left)
    }

    fun triggerHotbar(index: Int, fromKeyboard: Boolean = false) {
        if (index !in 0..9) return
        val action = worldState.hotbarSlots.getOrNull(index) ?: return
        when (action) {
            is HotbarSlotAction.CastSpell -> {
                val ch = worldState.localCharacter ?: return
                val spell = worldState.catalog.spells.firstOrNull { it.id == action.spellId }
                if (spell != null && (ch.level < spell.levelReq || (spell.classReqIds.isNotEmpty() && ch.classId !in spell.classReqIds))) {
                    return
                }
                if (!worldState.castReady(action.spellId, System.currentTimeMillis())) { stage.keyboardFocus = null; return }
                val hovered = worldState.hoveredTile
                if (spell?.targetType == com.morgana.domain.content.SpellTargetType.GROUND_AOE) {
                    if (fromKeyboard && hovered != null) {
                        worldState.pendingGroundSpellId = null
                        netClient.send(ClientPacket.CastSpell(spellId = action.spellId, targetTileX = hovered.x, targetTileY = hovered.y))
                        worldState.noteSpellCast(action.spellId)
                        audio.play(RetroSoundEngine.Sfx.SPELL)
                    } else {
                        worldState.pendingGroundSpellId = action.spellId
                        worldState.toasts.add("Click a tile to cast ${spell.name} (Right-click or Esc to cancel)", ToastKind.INFO, ToastClock.now())
                    }
                    stage.keyboardFocus = null
                    return
                }
                val aimAtCursor = fromKeyboard && hovered != null &&
                    (spell?.targetType == com.morgana.domain.content.SpellTargetType.DIRECTIONAL_PROJECTILE ||
                        spell?.targetType == com.morgana.domain.content.SpellTargetType.AOE_TARGET_CENTERED)
                netClient.send(
                    ClientPacket.CastSpell(
                        spellId = action.spellId,
                        targetEntityId = worldState.castTargetFor(action.spellId),
                        targetTileX = if (aimAtCursor) hovered!!.x else 0,
                        targetTileY = if (aimAtCursor) hovered!!.y else 0
                    )
                )
                worldState.noteSpellCast(action.spellId)
                audio.play(RetroSoundEngine.Sfx.SPELL)
            }
            is HotbarSlotAction.UseItem -> {
                val ch = worldState.localCharacter ?: return
                val invIdx = ch.inventory.slots.indexOfFirst { it != null && it.itemId == action.itemId }
                if (invIdx >= 0 && worldState.actionReady("item:${action.itemId}", System.currentTimeMillis())) {
                    netClient.send(ClientPacket.UseInventorySlot(invIdx))
                    audio.play(RetroSoundEngine.Sfx.CLICK)
                }
            }
        }
        stage.keyboardFocus = null
    }

    fun toggleMinimap() {
        if (minimapWindow != null) {
            minimapWindow?.remove()
            minimapWindow = null
            worldState.showMinimap = false
        } else {
            openMinimapWindow()
            worldState.showMinimap = true
        }
    }

    private fun openMinimapWindow() {
        minimapWindow?.remove()
        val radar = VisTable()
        radar.defaults().pad(2f)

        val canvas = MinimapCanvas(assets, worldState)
        canvas.setSize(136f, 136f)

        val radarStack = com.badlogic.gdx.scenes.scene2d.ui.Stack()
        radarStack.add(canvas)
        val zoomRow = VisTable()
        zoomRow.bottom()
        zoomRow.add(actionButton("-") { canvas.adjustZoom(6f) }).size(22f, 22f).left()
        zoomRow.add().expandX()
        zoomRow.add(actionButton("+") { canvas.adjustZoom(-6f) }).size(22f, 22f).right()
        radarStack.add(zoomRow)

        val headerBg = VisTable()
        headerBg.background = theme.dynamic { t, p ->
            t.beveledPanel(Color(p.background.r, p.background.g, p.background.b, 0.78f), highlight = p.border, padX = 6f, padY = 2f)
        }
        headerBg.add(minimapCoordLabel).right().row()
        headerBg.add(minimapMetaLabel).right()

        radar.add(headerBg).right().padBottom(3f).row()
        radar.add(radarStack).size(136f, 136f).right().row()
        radar.pack()
        radar.setPosition(stage.width - radar.width - 10f, stage.height - radar.height - 10f)
        stage.addActor(radar)
        minimapWindow = radar
    }

    private fun openRunSummaryWindow(summary: RunSummaryModel.RunSummary) {
        runSummaryWindow?.remove()
        val win = HudWindow("Run Summary")
        win.addCloseButton()
        win.isMovable = true
        win.defaults().pad(4f)

        val extracted = summary.outcome == RunSummaryModel.RunOutcome.EXTRACTED
        val header = VisLabel(if (extracted) "Extracted Safely!" else "Fallen in the Deep").apply {
            color = if (extracted) Color.valueOf("86EFAC") else Color.valueOf("F87171")
        }
        win.add(header).colspan(2).center().padBottom(4f).row()

        fun addRow(label: String, value: String) {
            win.add(VisLabel(label, theme.mutedLabelStyle)).left().padRight(14f)
            win.add(VisLabel(value)).right().row()
        }
        addRow("Deepest Floor", "Floor ${summary.deepestFloor}")
        addRow("Floors Visited", "${summary.floorsVisited}")
        addRow("Enemies Slain", "${summary.kills}")
        if (summary.bossesDefeated.isNotEmpty()) {
            addRow("Bosses Defeated", summary.bossesDefeated.joinToString(", "))
        }
        val xpText = buildString {
            append("+${summary.xpGained} XP")
            if (summary.levelsGained > 0) append(" (+${summary.levelsGained} Lv)")
        }
        addRow("Progress", xpText)
        addRow("Duration", RunSummaryModel.formatDuration(summary.durationSeconds))

        val okBtn = actionButton("Continue") {
            win.remove()
            if (runSummaryWindow === win) runSummaryWindow = null
        }
        win.add(okBtn).colspan(2).center().padTop(6f).minWidth(110f).row()

        win.pack()
        win.setPosition(
            ((stage.width - win.width) * 0.5f).coerceAtLeast(8f),
            ((stage.height - win.height) * 0.5f).coerceAtLeast(8f)
        )
        stage.addActor(win)
        runSummaryWindow = win
    }

    private fun refreshPkModeButton() {
        val hasPkModes = worldState.gameDefinition?.combat?.pk != null
        pkModeTopButton?.let { btn ->
            btn.isVisible = hasPkModes
            if (hasPkModes) {
                val mode = worldState.localCharacter?.pkMode ?: com.morgana.domain.entity.PkMode.PEACE
                btn.setText("Mode: ${mode.label}")
                btn.color = when (mode) {
                    com.morgana.domain.entity.PkMode.PEACE -> Color(0.55f, 0.95f, 0.65f, 1f)
                    com.morgana.domain.entity.PkMode.TEAM -> Color(0.55f, 0.85f, 1f, 1f)
                    com.morgana.domain.entity.PkMode.GUILD -> Color(1f, 0.82f, 0.45f, 1f)
                    com.morgana.domain.entity.PkMode.PK -> Color(1f, 0.55f, 0.55f, 1f)
                }
            }
        }
    }

    private fun cyclePkMode() {
        val modes = com.morgana.domain.entity.PkMode.entries
        val cur = worldState.localCharacter?.pkMode ?: com.morgana.domain.entity.PkMode.PEACE
        val next = modes[(cur.ordinal + 1) % modes.size]
        netClient.send(ClientPacket.SetPkMode(next))
    }

    /** Shows the next new-player tip as a card when none is up; closing it moves the queue on. */
    private fun syncTipWindow() {
        val showing = openOrNull(tipWindow)
        if (showing != null) return
        tipWindow = null
        if (!worldState.authenticated || editorOpen) return
        val tip = worldState.tips.current ?: return
        val abilities = worldState.abilityTerminology().pluralLabel
        val keyName = { action: String -> GameAction.entries.firstOrNull { it.name == action }?.let { keyHint(it) } }
        val win = TipWindow(
            stage,
            title = TipText.render(tip.title, keyName, abilities),
            text = TipText.render(tip.text, keyName, abilities),
            onClosed = { worldState.tips.dismiss(); tipWindow = null },
            onTurnOff = { netClient.send(ClientPacket.TipsControl(TipsAction.OFF)); worldState.tips.clear() }
        )
        stage.addActor(win)
        win.toFront()
        tipWindow = win
    }

    fun refreshHud() {
        toastOverlay.update()
        syncTipWindow()
        refreshPkModeButton()
        if (worldState.authenticated && !editorOpen) {
            worldState.pendingRunSummary?.let { summary ->
                worldState.pendingRunSummary = null
                openRunSummaryWindow(summary)
            }
        }
        val ch = worldState.localCharacter
        if (ch != null) {
            val gameDef = worldState.gameDefinition
            val vitalEntries = if (gameDef != null) {
                val shown = VitalBars.visibleIds(gameDef, worldState.catalog.spells, ch.classId).toSet()
                gameDef.vitals.filter { it.id in shown }.map { v -> v.id to (v.short to ch.vitals[v.id]) }
            } else {
                ch.vitals.entries.map { (k, v) -> k to (k.uppercase() to v) }
            }
            if (vitalBars.keys != vitalEntries.map { it.first }.toSet()) {
                vitalsBarRow.clearChildren()
                vitalBars.clear()
                vitalsBarRow.defaults().left().padBottom(2f)
                vitalEntries.forEachIndexed { idx, (id, _) ->
                    val color = UiColors.vital(id, gameDef?.vitals?.indexOfFirst { it.id == id }?.takeIf { it >= 0 } ?: idx)
                    val bar = theme.vitalBar(color, width = 152f, height = 14f)
                    vitalBars[id] = bar
                    vitalsBarRow.add(bar).row()
                }
                topFrameWindow?.let { win ->
                    win.pack()
                    win.setPosition(12f, stage.height - win.height - 12f)
                }
            }
            for ((id, pair) in vitalEntries) {
                val (shortName, vs) = pair
                val cur = vs?.cur ?: 0
                val max = vs?.max ?: 0
                vitalBars[id]?.update("$shortName $cur/$max", cur, max)
            }
            val game = worldState.compiledGame ?: BlankGame.COMPILED
            val nextXp = game.xpToNext(ch) ?: 0L
            val shownXp = worldState.visualXp(ch.xp)
            xpBar.update("XP $shownXp/$nextXp", shownXp, nextXp)
            vitalsLabel.isVisible = false
            vitalsBarRow.isVisible = true
            val mapName = worldState.activeMap?.name ?: "Map #${ch.position.mapId}"
            val summaryText = "Lv.${ch.level}  ${ch.name}"
            if (charSummaryLabel.text.toString() != summaryText) {
                charSummaryLabel.setText(summaryText)
                topFrameWindow?.let { win ->
                    win.pack()
                    win.setPosition(12f, stage.height - win.height - 12f)
                }
            }
            val moral = worldState.activeMap?.moral?.name ?: "SAFE"
            minimapCoordLabel.setText("$mapName [$moral] (${ch.position.tileX}, ${ch.position.tileY})")
            val pingStr = if (worldState.latestRttMs > 0L) "${worldState.latestRttMs}ms" else "--"
            val fpsStr = com.badlogic.gdx.Gdx.graphics.framesPerSecond
            minimapMetaLabel.setText("Ping: $pingStr / FPS: $fpsStr")
            minimapWindow?.let { m ->
                m.pack()
                m.setPosition(stage.width - m.width - 10f, stage.height - m.height - 10f)
            }
            refreshStatusEffects(ch)
        } else {
            vitalsBarRow.isVisible = false
            statusEffectRow.isVisible = false
            vitalsLabel.isVisible = true
            vitalsLabel.setText(worldState.statusMessage)
        }

        val visibleMessages = worldState.chatHistory
            .filter { ChatFormatting.isShown(it, worldState.hideOocChat) }
            .filter { msg ->
                when (activeChatTab) {
                    ChatTab.ALL -> true
                    ChatTab.GLOBAL -> msg.channel == ChatChannel.GLOBAL || msg.channel == ChatChannel.MAP
                    ChatTab.PARTY -> msg.channel == ChatChannel.PARTY
                    ChatTab.GUILD -> msg.channel == ChatChannel.GUILD
                    ChatTab.SYSTEM -> msg.channel == ChatChannel.SYSTEM
                }
            }
            .takeLast(12)
        val chatKey = "${activeChatTab.name}|" + visibleMessages.joinToString("\n") { "${it.channel}:${it.senderName}:${it.message}" }
        if (chatKey != lastChatRenderKey) {
            lastChatRenderKey = chatKey
            chatHistoryTable.clearChildren()
            chatHistoryTable.defaults().left().growX().padBottom(1f)
            for (msg in visibleMessages) {
                val lbl = VisLabel(ChatFormatting.line(msg), theme.labelStyle)
                lbl.setWrap(true)
                lbl.color = chatChannelColor(msg.channel)
                chatHistoryTable.add(lbl).growX().row()
            }
        }
        updateChatBadge()
        refreshHotbar()
        syncTradeWindow()
        syncPartyWindow()
        syncMailWindow()
        syncMarketWindow()
        syncWorldMapWindow()
        syncQuestTracker()
        syncGuildWindow()
        syncIgnoreWindow()
        syncFriendsWindow()
        syncPluginWindows()
        updateHudVisibility()
        layoutCompactHud()
    }

    private fun submitChatMessage() {
        val text = chatInputField.text.trim()
        if (text.isEmpty()) return
        chatInputField.text = ""
        val localCommand = text.lowercase()
        if (localCommand == "/hideooc" || localCommand == "/showooc") {
            // A display filter only: nothing is sent to the server.
            worldState.hideOocChat = localCommand == "/hideooc"
            worldState.chatHistory.add(
                ServerPacket.ChatBroadcast(
                    channel = ChatChannel.SYSTEM,
                    senderName = "Chat",
                    message = if (worldState.hideOocChat) "Out-of-character chat hidden (/showooc to show it)." else "Out-of-character chat shown.",
                    showOverheadBubble = false
                )
            )
            stage.keyboardFocus = null
            return
        }
        if (text.startsWith("/")) {
            netClient.send(ClientPacket.AdminSlashCommand(text))
        } else {
            netClient.send(
                ClientPacket.SendChat(
                    channel = chatChannelBox.selected ?: ChatChannel.MAP,
                    message = text
                )
            )
        }
        stage.keyboardFocus = null
    }

    fun handleServerWindowRequest(req: ServerPacket.OpenUiWindow) {
        when (req.windowType) {
            ServerUiWindowType.SHOP -> openShopWindow(req.shopId)
            ServerUiWindowType.BANK -> openBankWindow()
            ServerUiWindowType.GUILD_BANK -> openBankWindow(guildVault = true)
            ServerUiWindowType.CRAFTING -> openCraftingWindow()
            ServerUiWindowType.DIALOGUE -> openDialogueWindow(req)
            ServerUiWindowType.F1_ADMIN_PANEL -> onToggleF1Admin()
        }
    }

    /** The window in [w] if it is still open on the stage (a window closed with its X is not). */
    private fun openOrNull(w: com.badlogic.gdx.scenes.scene2d.ui.Window?): com.badlogic.gdx.scenes.scene2d.ui.Window? =
        w?.takeIf { it.stage != null && (it as? HudWindow)?.closedByUser != true }

    /** `I`: opens the character menu on its Inventory tab, switches an open menu to it, or closes it when already there. */
    fun toggleCharacterAndInventoryWindow() = toggleCharacterMenu(MenuSection.INVENTORY)

    /** `V`: opens the character menu on Stats, switches an open menu to Stats, or closes it when already there. */
    fun toggleCharacterWindow() = toggleCharacterMenu(MenuSection.STATS)

    /**
     * Character and inventory are one window ([CharacterMenu]) with tabs. The key for a tab opens the menu there; with
     * the menu already open it switches to that tab, and pressing it again on that tab closes the menu.
     */
    private fun toggleCharacterMenu(target: MenuSection) {
        val open = openOrNull(characterMenu) as? CharacterMenu
        if (open != null) {
            if (open.section == target) {
                open.remove()
                characterMenu = null
            } else {
                open.showSection(target)
                open.toFront()
            }
            return
        }
        characterMenu = null
        if (worldState.localCharacter == null) return
        val win = CharacterMenu(
            stage, worldState, netClient, assets,
            start = target,
            onAssignHotbar = { showHotbarAssignModal(it) }
        )
        stage.addActor(win)
        win.toFront()
        characterMenu = win
    }

    fun toggleSpellbookWindow() {
        openOrNull(spellbookWindow)?.let {
            it.remove()
            spellbookWindow = null
            return
        }
        spellbookWindow = null
        val ch = worldState.localCharacter ?: return
        val term = worldState.abilityTerminology()
        val win = HudWindow(term.windowTitle)
        win.addCloseButton()
        val table = VisTable()
        table.defaults().pad(4f).left()

        val items = worldState.catalog.items.associateBy { it.id }
        val equippedWeaponType = ch.equipment.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)
            ?.let { items[it.itemId]?.weaponType }.orEmpty()
        for (spellId in ch.knownSpellIds) {
            val spell = worldState.catalog.spells.firstOrNull { it.id == spellId } ?: continue
            val profOk = (spell.weaponTypeReqs.isEmpty() || equippedWeaponType in spell.weaponTypeReqs) &&
                (spell.weaponProficiencyLevelReq <= 0 ||
                    (equippedWeaponType.isNotBlank() && ch.weaponProficiencyLevel(equippedWeaponType) >= spell.weaponProficiencyLevelReq))
            val canUseSpell = ch.level >= spell.levelReq && (spell.classReqIds.isEmpty() || ch.classId in spell.classReqIds) && profOk
            val costStr = if (spell.costs.isEmpty()) "Free" else spell.costs.entries.joinToString { "${it.value} ${it.key.uppercase()}" }
            val reqStr = if (ch.level < spell.levelReq) " [Lv.${spell.levelReq}]" else ""
            val nameLabel = VisLabel("${spell.name} ($costStr)$reqStr").apply { touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled }
            com.morgana.client.ui.bag.attachHoverTip(nameLabel) {
                val live = worldState.localCharacter
                val game = worldState.compiledGame ?: BlankGame.COMPILED
                val liveItems = worldState.catalog.items.associateBy { it.id }
                val liveWeaponType = live?.equipment?.getEquipped(com.morgana.domain.item.EquipmentSlot.WEAPON)
                    ?.let { liveItems[it.itemId]?.weaponType }.orEmpty()
                val names = com.morgana.client.ui.bag.StatNames(
                    attributes = game.definition.attributes.associate { it.id to it.short.ifBlank { it.name } },
                    vitals = game.definition.vitals.associate { it.id to it.short.ifBlank { it.name } }
                )
                com.morgana.client.ui.bag.SpellInfo.tip(
                    spell, names,
                    live?.let {
                        com.morgana.client.ui.bag.WearerInfo(
                            it.level,
                            it.effectiveAttributes(game, liveItems),
                            it.classId,
                            equippedWeaponType = liveWeaponType,
                            weaponProficiencyLevels = it.weaponProficiencyLevels
                        )
                    },
                    vitals = live?.vitals?.mapValues { it.value.cur },
                    cooldownLeftSeconds = worldState.spellCooldowns.remaining(spell.id, ToastClock.now()),
                    spellLevel = live?.spellLevel(spell.id) ?: 1,
                    terminology = term
                )
            }
            table.add(nameLabel).width(160f)
            table.add(actionButton("+Bar") {
                showHotbarAssignModal(HotbarSlotAction.CastSpell(spell.id, spell.name, spell.iconId))
            }).padRight(3f)
            val castBtn = actionButton("Cast") {
                val live = worldState.localCharacter
                if (live != null && live.level >= spell.levelReq && (spell.classReqIds.isEmpty() || live.classId in spell.classReqIds) &&
                    worldState.castReady(spell.id, System.currentTimeMillis())
                ) {
                    if (spell.targetType == com.morgana.domain.content.SpellTargetType.GROUND_AOE) {
                        worldState.pendingGroundSpellId = spell.id
                        worldState.toasts.add("Click a tile to cast ${spell.name} (Right-click or Esc to cancel)", ToastKind.INFO, ToastClock.now())
                    } else {
                        netClient.send(ClientPacket.CastSpell(spellId = spell.id))
                        worldState.noteSpellCast(spell.id)
                    }
                }
            }
            castBtn.isDisabled = !canUseSpell
            table.add(castBtn).row()
        }
        if (table.cells.isEmpty) table.add(emptyHint(EmptyStates.abilities(term), 290f)).width(290f).row()
        win.add(VisScrollPane(table)).width(320f).height(200f)
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        spellbookWindow = win
    }

    fun toggleQuestWindow() {
        openOrNull(questWindow)?.let {
            it.remove()
            questWindow = null
            return
        }
        questWindow = null
        val ch = worldState.localCharacter ?: return
        val win = HudWindow("Quest Log")
        win.addCloseButton()
        val table = VisTable()
        table.defaults().pad(4f).left()

        if (ch.quests.isEmpty()) {
            table.add(emptyHint(EmptyStates.QUESTS, 400f)).colspan(2).width(400f).row()
        } else {
            for ((qid, qState) in ch.quests) {
                val qDef = worldState.catalog.quests.firstOrNull { it.id == qid } ?: continue
                val progress = qDef.objectives.mapIndexed { i, obj ->
                    "${obj.description}: ${qState.objectiveCounts.getOrElse(i) { 0 }}/${obj.requiredCount}"
                }.joinToString(", ")
                table.add(VisLabel("${qDef.name} [${qState.status}] - $progress").apply { setWrap(true) }).width(330f)
                if (qState.status == com.morgana.domain.event.QuestStatus.IN_PROGRESS) {
                    val tracked = qid !in worldState.untrackedQuests
                    table.add(actionButton(if (tracked) "Untrack" else "Track") {
                        if (tracked) worldState.untrackedQuests.add(qid) else {
                            worldState.untrackedQuests.remove(qid)
                            worldState.showQuestTracker = true
                        }
                        trackerShown = null
                        questWindow?.remove()
                        questWindow = null
                        toggleQuestWindow()
                    }).row()
                } else {
                    table.row()
                }
            }
        }

        win.add(VisScrollPane(table)).width(440f).height(240f)
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        questWindow = win
    }

    fun openCraftingWindow() {
        if (craftingWindow == null || craftingWindow?.stage == null) {
            craftingWindow = null
            toggleCraftingWindow()
        } else {
            craftingWindow?.toFront()
        }
    }

    fun toggleCraftingWindow() {
        openOrNull(craftingWindow)?.let {
            it.remove()
            craftingWindow = null
            return
        }
        craftingWindow = null
        if (worldState.localCharacter == null) return
        val win = com.morgana.client.ui.bag.CraftingWindow(stage, worldState, netClient, assets)
        stage.addActor(win)
        craftingWindow = win
    }

    private fun openShopWindow(shopId: Int) {
        shopWindow?.remove()
        val shop = worldState.catalog.shops.firstOrNull { it.id == shopId } ?: return
        val win = com.morgana.client.ui.bag.ShopWindow(stage, worldState, netClient, assets, shop)
        stage.addActor(win)
        win.toFront()
        shopWindow = win
    }

    private fun openBankWindow(guildVault: Boolean = false) {
        bankWindow?.remove()
        val win = com.morgana.client.ui.bag.BankWindow(stage, worldState, netClient, assets, guildVault)
        stage.addActor(win)
        bankWindow = win
    }

    /** Tells the server the open dialogue line was read (see [openDialogueWindow]); null when no line is open. */
    private var dialogueLineRead: (() -> Unit)? = null

    private fun openDialogueWindow(req: ServerPacket.OpenUiWindow) {
        dialogueWindow?.remove()
        dialogueLineRead = null
        val plain = req.choiceLabels.isEmpty()
        // Closing a line that offers no answers (the button or the X) tells the server, so the event can carry on: the next
        // line, or the steps that follow. An older server ignores it.
        var told = false
        fun lineRead() {
            if (plain && !told) {
                told = true
                netClient.send(ClientPacket.SelectDialogueChoice(req.eventId, -1))
            }
        }
        dialogueLineRead = ::lineRead
        val win = object : HudWindow(req.speakerName.ifBlank { "Dialogue" }) {
            override fun close() {
                lineRead()
                if (dialogueWindow === this) dialogueWindow = null
                super.close()
            }
        }
        win.addCloseButton()
        win.defaults().pad(6f).left()

        val textLabel = VisLabel(req.dialogueText)
        textLabel.setWrap(true)
        win.add(textLabel).width(380f).row()

        if (req.choiceLabels.isEmpty()) {
            win.add(actionButton("Close") {
                lineRead()
                win.remove()
                if (dialogueWindow === win) dialogueWindow = null
            }).fillX().row()
        } else {
            req.choiceLabels.forEachIndexed { idx, label ->
                win.add(actionButton(label) {
                    netClient.send(ClientPacket.SelectDialogueChoice(req.eventId, idx))
                    win.remove()
                    dialogueWindow = null
                }).fillX().row()
            }
        }

        win.pack()
        win.centerWindow()
        stage.addActor(win)
        win.toFront()
        dialogueWindow = win
    }

    /** Shows an event text or number input prompt and submits the answer back to the server. */
    fun showTextInputWindow(req: ServerPacket.TextInputPrompt) {
        textInputWindow?.remove()
        var submitted = false
        val win = object : HudWindow("Input") {
            override fun close() {
                if (!submitted) {
                    submitted = true
                    netClient.send(ClientPacket.SubmitTextInput(req.eventId, "", cancelled = true))
                }
                if (stage?.keyboardFocus != null) stage.keyboardFocus = null
                if (textInputWindow === this) textInputWindow = null
                super.close()
            }
        }
        win.addCloseButton()
        win.defaults().pad(6f).left()

        val promptLabel = VisLabel(req.prompt)
        promptLabel.setWrap(true)
        win.add(promptLabel).width(360f).colspan(2).row()

        val field = VisTextField(req.defaultValue)
        field.maxLength = req.maxLength.coerceIn(1, 200)
        if (req.isNumeric) {
            field.setTextFieldFilter { _, c -> c.isDigit() || (c == '-' && field.text.isEmpty()) }
        }
        fun submit() {
            if (submitted) return
            submitted = true
            netClient.send(ClientPacket.SubmitTextInput(req.eventId, field.text, cancelled = false))
            if (stage.keyboardFocus === field) stage.keyboardFocus = null
            win.remove()
            if (textInputWindow === win) textInputWindow = null
        }
        field.addListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
            override fun keyDown(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, keycode: Int): Boolean {
                if (keycode == com.badlogic.gdx.Input.Keys.ENTER || keycode == com.badlogic.gdx.Input.Keys.NUMPAD_ENTER) {
                    submit()
                    return true
                }
                return false
            }
        })
        win.add(field).width(360f).colspan(2).row()

        win.add(actionButton("OK") { submit() }).fillX()
        win.add(actionButton("Cancel") {
            if (!submitted) {
                submitted = true
                netClient.send(ClientPacket.SubmitTextInput(req.eventId, "", cancelled = true))
            }
            if (stage.keyboardFocus === field) stage.keyboardFocus = null
            win.remove()
            if (textInputWindow === win) textInputWindow = null
        }).fillX().row()

        win.pack()
        win.centerWindow()
        stage.addActor(win)
        win.toFront()
        stage.keyboardFocus = field
        textInputWindow = win
    }

    /** Shows an event picture popup centered on the stage, closing after [req.durationTicks] when positive. */
    fun showPictureWindow(req: ServerPacket.ShowPicture) {
        pictureWindow?.remove()
        val region = assets.getPictureRegion(req.pictureId)
        val win = object : HudWindow(req.title.ifBlank { "Picture" }) {
            override fun close() {
                if (pictureWindow === this) pictureWindow = null
                super.close()
            }
        }
        win.addCloseButton()
        win.defaults().pad(8f).center()
        if (region != null) {
            val img = com.badlogic.gdx.scenes.scene2d.ui.Image(region)
            img.setScaling(com.badlogic.gdx.utils.Scaling.fit)
            val w = region.regionWidth.toFloat().coerceAtLeast(32f)
            val h = region.regionHeight.toFloat().coerceAtLeast(32f)
            val scale = minOf(256f / maxOf(w, h), 4f).coerceAtLeast(1f)
            win.add(img).size(w * scale, h * scale).row()
        } else {
            win.add(VisLabel("Picture: ${req.pictureId}")).row()
        }
        win.add(actionButton("Close") {
            win.remove()
            if (pictureWindow === win) pictureWindow = null
        }).fillX().row()
        if (req.durationTicks > 0) {
            win.addAction(
                com.badlogic.gdx.scenes.scene2d.actions.Actions.sequence(
                    com.badlogic.gdx.scenes.scene2d.actions.Actions.delay(req.durationTicks * 0.05f),
                    com.badlogic.gdx.scenes.scene2d.actions.Actions.run {
                        win.remove()
                        if (pictureWindow === win) pictureWindow = null
                    }
                )
            )
        }
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        win.toFront()
        pictureWindow = win
    }

    /** Closes map-bound modal windows (dialogue, picture, shop, bank) when the player warps to a different map or logs out. */
    fun closeMapWindows() {
        // A line swept away by a map change still counts as read, so the steps after it are not lost.
        runCatching { dialogueLineRead?.invoke() }
        dialogueLineRead = null
        dialogueWindow?.remove()
        dialogueWindow = null
        textInputWindow?.remove()
        textInputWindow = null
        pictureWindow?.remove()
        pictureWindow = null
        shopWindow?.remove()
        shopWindow = null
        bankWindow?.remove()
        bankWindow = null
    }

    /** The plugin windows on screen, with the description each was built from (a changed description rebuilds the window). */
    private val pluginWindowViews: MutableMap<String, Pair<PluginWindowDto, HudWindow>> = linkedMapOf()

    /** Draws the windows the server's plugins described, updates ones that changed, and removes ones the server closed. */
    private fun syncPluginWindows() {
        val wanted = worldState.pluginWindows
        for ((id, view) in pluginWindowViews.toList()) {
            if (id !in wanted) {
                view.second.remove()
                pluginWindowViews.remove(id)
            }
        }
        for ((id, dto) in wanted.toMap()) {
            val shown = pluginWindowViews[id]
            if (shown != null && shown.first == dto && shown.second.stage != null) continue
            val oldWindow = shown?.second
            val x = oldWindow?.x
            val y = oldWindow?.y
            oldWindow?.remove()
            val win = buildPluginWindow(dto)
            if (x != null && y != null) win.setPosition(x, y) else win.centerWindow()
            stage.addActor(win)
            pluginWindowViews[id] = dto to win
        }
    }

    private fun buildPluginWindow(dto: PluginWindowDto): HudWindow {
        val win = object : HudWindow(dto.title.ifBlank { "Window" }) {
            override fun close() {
                // The player closed it: tell the server and forget it, so a later identical description reopens it.
                if (worldState.pluginWindows.remove(dto.id) != null) netClient.send(ClientPacket.PluginWindowClosed(dto.id))
                pluginWindowViews.remove(dto.id)
                super.close()
            }
        }
        win.addCloseButton()
        win.defaults().pad(4f).left()
        for (line in dto.lines) {
            val label = VisLabel(line)
            label.setWrap(true)
            win.add(label).width(380f).colspan(2).row()
        }
        if (dto.rows.isNotEmpty()) {
            val table = VisTable()
            table.defaults().pad(2f)
            for (row in dto.rows) {
                table.add(VisLabel(row.label)).left().padRight(16f).expandX().fillX()
                table.add(VisLabel(row.value)).right().row()
            }
            if (dto.rows.size > PLUGIN_ROWS_BEFORE_SCROLL) {
                // A long list scrolls inside the window instead of growing past the screen.
                val pane = com.kotcrab.vis.ui.widget.VisScrollPane(table)
                pane.setScrollingDisabled(true, false)
                pane.setFadeScrollBars(false)
                pane.setOverscroll(false, false)
                win.add(pane).width(380f).height(PLUGIN_ROWS_BEFORE_SCROLL * 24f).colspan(2).row()
            } else {
                win.add(table).width(380f).colspan(2).row()
            }
        }
        val fields = linkedMapOf<String, com.kotcrab.vis.ui.widget.VisTextField>()
        for (input in dto.inputs) {
            if (input.label.isNotBlank()) win.add(VisLabel(input.label)).left().colspan(2).row()
            val field = com.kotcrab.vis.ui.widget.VisTextField(input.initial)
            field.messageText = input.hint
            field.maxLength = input.maxLength
            fields[input.id] = field
            win.add(field).width(380f).colspan(2).row()
        }
        for (button in dto.buttons) {
            val b = actionButton(button.label) {
                netClient.send(ClientPacket.PluginAction(dto.id, button.id, fields.mapValues { it.value.text }))
            }
            b.isDisabled = !button.enabled
            win.add(b).fillX().colspan(2).row()
        }
        win.pack()
        return win
    }

    private fun syncTradeWindow() {
        val trade = worldState.activeTrade
        if (trade == null) {
            tradeWindow?.remove()
            tradeWindow = null
            return
        }

        val win = tradeWindow
        if (win != null && win.stage == null) {
            netClient.send(ClientPacket.TradeAction(TradeActionType.CANCEL))
            tradeWindow = null
            return
        }

        val ch = worldState.localCharacter ?: return
        val isInitiator = ch.id == trade.initiatorId
        val partnerId = if (isInitiator) trade.partnerId else trade.initiatorId
        val partnerName = worldState.playersOnMap[partnerId]?.name ?: "Partner"

        val myOffer = if (isInitiator) trade.initiatorOffer else trade.partnerOffer
        val theirOffer = if (isInitiator) trade.partnerOffer else trade.initiatorOffer
        val myLocked = if (isInitiator) trade.initiatorLocked else trade.partnerLocked
        val theirLocked = if (isInitiator) trade.partnerLocked else trade.initiatorLocked
        val myConfirmed = if (isInitiator) trade.initiatorConfirmed else trade.partnerConfirmed
        val theirConfirmed = if (isInitiator) trade.partnerConfirmed else trade.initiatorConfirmed

        val itemMap = worldState.catalog.items.associateBy { it.id }

        val activeWin = win ?: HudWindow("Trade Session with $partnerName").also {
            it.addCloseButton()
            stage.addActor(it)
            tradeWindow = it
        }

        activeWin.clearChildren()
        activeWin.titleLabel.setText("Trade Session with $partnerName")

        val mainTable = VisTable()
        mainTable.defaults().pad(4f)

        // Left Column: Your Offer
        val leftTable = VisTable()
        leftTable.defaults().left().pad(2f)
        val myStatus = when {
            myConfirmed -> "[CONFIRMED]"
            myLocked -> "[LOCKED]"
            else -> "[OFFERING]"
        }
        val myStatusCol = when {
            myConfirmed -> GameTheme.shared.palette.success
            myLocked -> GameTheme.shared.palette.accent
            else -> GameTheme.shared.palette.text
        }
        val myStatusLbl = VisLabel("You $myStatus")
        myStatusLbl.color = myStatusCol
        leftTable.add(myStatusLbl).padBottom(4f).row()

        if (myOffer.isEmpty()) {
            leftTable.add(VisLabel("(No items offered)")).padBottom(4f).row()
        } else {
            for (stack in myOffer) {
                val name = itemMap[stack.itemId]?.name ?: "Item #${stack.itemId}"
                leftTable.add(VisLabel("• $name x${stack.amount}")).row()
            }
        }

        if (!myLocked) {
            leftTable.add(VisLabel("--- Offer From Bag ---")).padTop(6f).row()
            val invItems = ch.inventory.slots.mapIndexedNotNull { idx, s -> if (s != null) idx to s else null }
            if (invItems.isEmpty()) {
                leftTable.add(VisLabel("(Bag is empty)")).row()
            } else {
                for ((slotIdx, stack) in invItems) {
                    val name = itemMap[stack.itemId]?.name ?: "Item #${stack.itemId}"
                    val rowTable = VisTable()
                    rowTable.add(VisLabel("$name x${stack.amount}")).width(120f)
                    rowTable.add(actionButton("+") {
                        netClient.send(ClientPacket.TradeAction(TradeActionType.ADD_ITEM, inventorySlotIndex = slotIdx, amount = 1))
                    })
                    leftTable.add(rowTable).row()
                }
            }
        }

        // Right Column: Partner Offer
        val rightTable = VisTable()
        rightTable.defaults().left().pad(2f)
        val theirStatus = when {
            theirConfirmed -> "[CONFIRMED]"
            theirLocked -> "[LOCKED]"
            else -> "[OFFERING]"
        }
        val theirStatusCol = when {
            theirConfirmed -> GameTheme.shared.palette.success
            theirLocked -> GameTheme.shared.palette.accent
            else -> GameTheme.shared.palette.text
        }
        val theirStatusLbl = VisLabel("$partnerName $theirStatus")
        theirStatusLbl.color = theirStatusCol
        rightTable.add(theirStatusLbl).padBottom(4f).row()

        if (theirOffer.isEmpty()) {
            rightTable.add(VisLabel("(No items offered)")).padBottom(4f).row()
        } else {
            for (stack in theirOffer) {
                val name = itemMap[stack.itemId]?.name ?: "Item #${stack.itemId}"
                rightTable.add(VisLabel("• $name x${stack.amount}")).row()
            }
        }

        val scrollLeft = VisScrollPane(leftTable)
        scrollLeft.setFadeScrollBars(false)
        val scrollRight = VisScrollPane(rightTable)
        scrollRight.setFadeScrollBars(false)

        mainTable.add(scrollLeft).width(200f).height(180f).top()
        mainTable.add(scrollRight).width(200f).height(180f).top().row()

        // Controls bar at bottom
        val controlsTable = VisTable()
        controlsTable.defaults().pad(4f)

        val lockBtn = actionButton(if (myLocked) "Locked" else "Lock Offer") {
            if (!myLocked) {
                netClient.send(ClientPacket.TradeAction(TradeActionType.LOCK))
            }
        }
        lockBtn.isDisabled = myLocked
        controlsTable.add(lockBtn)

        val canConfirm = myLocked && theirLocked && !myConfirmed
        val confirmBtn = actionButton(if (myConfirmed) "Confirmed" else "Confirm Trade") {
            if (canConfirm) {
                netClient.send(ClientPacket.TradeAction(TradeActionType.CONFIRM))
            }
        }
        confirmBtn.isDisabled = !canConfirm
        controlsTable.add(confirmBtn)

        val cancelBtn = actionButton("Cancel") {
            netClient.send(ClientPacket.TradeAction(TradeActionType.CANCEL))
            activeWin.remove()
            tradeWindow = null
        }
        controlsTable.add(cancelBtn)

        mainTable.add(controlsTable).colspan(2).padTop(8f).row()

        activeWin.add(mainTable)
        activeWin.pack()
        if (activeWin.x == 0f && activeWin.y == 0f) {
            activeWin.centerWindow()
        }
    }

    fun togglePartyWindow() {
        openOrNull(partyWindow)?.let {
            it.remove()
            partyWindow = null
            partyStatusTable = null
            partyShownKey = null
            return
        }
        partyWindow = null
        partyStatusTable = null
        partyShownKey = null
        val win = HudWindow("Party Management")
        win.addCloseButton()
        win.defaults().pad(4f).left()

        val statusTable = VisTable().apply { defaults().pad(4f).left() }
        partyStatusTable = statusTable
        partyShownKey = null
        win.add(VisScrollPane(statusTable)).width(320f).height(190f).colspan(2).row()

        val inviteField = VisTextField("")
        inviteField.messageText = "Player name"
        fun sendInvite() {
            val target = inviteField.text.trim()
            if (target.isNotBlank()) {
                netClient.send(ClientPacket.PartyAction(PartyActionType.INVITE, targetPlayerName = target))
                inviteField.text = ""
            }
        }
        inviteField.setTextFieldListener { _, c ->
            if (c == '\r' || c == '\n') sendInvite()
        }
        win.add(VisLabel("Invite Player:")).colspan(2).row()
        win.add(inviteField).width(210f)
        win.add(actionButton("Invite") { sendInvite() }).row()

        stage.addActor(win)
        partyWindow = win
        syncPartyWindow()
        win.pack()
        win.centerWindow()
    }

    /** Shows, updates or hides the on-screen quest tracker; it is rebuilt only when its words change. */
    private fun syncQuestTracker() {
        trackerWindow?.let { existing ->
            if (existing.stage == null) {
                trackerWindow = null
                trackerContent = null
                trackerShown = null
                worldState.showQuestTracker = false
                return
            }
        }
        val entries = if (worldState.authenticated && worldState.showQuestTracker && !editorOpen) worldState.trackedQuests() else emptyList()
        if (entries.isEmpty()) {
            trackerWindow?.isVisible = false
            return
        }
        val defaultTop = if (touchDevice) {
            ((compactStatus?.y ?: (stage.height - 56f)) - 8f).coerceAtLeast(160f)
        } else {
            ((topFrameWindow?.y ?: (stage.height - 230f)) - 12f).coerceAtLeast(160f)
        }
        val createdNow = trackerWindow == null
        val win = trackerWindow ?: HudWindow("Quest Tracker").also { w ->
            w.addCloseButton()
            w.isMovable = true
            w.setPosition(12f + insetLeft, defaultTop)
            // The lines live in their own table: clearing the window itself would also remove its title bar,
            // and with it the minimize and close buttons.
            val content = VisTable()
            content.defaults().pad(1f).left()
            w.add(content).left()
            trackerContent = content
            stage.addActor(w)
            trackerWindow = w
        }
        val content = trackerContent ?: return
        win.isVisible = true
        val key = entries.flatMap { e -> listOf("#${e.questId}|${e.name}|${e.ready}") + e.lines.map { QuestTracker.lineText(it) + "|${it.done}" } }
        if (key == trackerShown || win.collapsed) return
        trackerShown = key
        val unitBottom = topFrameWindow?.let { it.y - 12f }
        var top = if (!createdNow && win.height > 0f) win.y + win.height else defaultTop
        if (unitBottom != null && win.x < (topFrameWindow?.x ?: 0f) + (topFrameWindow?.width ?: 220f)) {
            top = minOf(top, unitBottom)
        }
        content.clearChildren()
        for (e in entries) {
            content.add(VisLabel(if (e.ready) "${e.name} (ready to hand in)" else e.name)).left().padTop(4f).row()
            for (l in e.lines) {
                val text = "  " + QuestTracker.lineText(l)
                val label = (if (l.done) VisLabel(text, theme.mutedLabelStyle) else VisLabel(text)).apply { setWrap(true) }
                content.add(label).width(240f).left().row()
            }
        }
        win.pack()
        win.setPosition(win.x, (top - win.height).coerceIn(0f, (stage.height - win.height).coerceAtLeast(0f)))
    }

    // --- Accessibility ---

    /** The text of the first key of [action], for button labels and window titles. */
    private fun keyHint(action: GameAction): String =
        KeyBindings.keyName(Accessibility.keys.keysFor(action).firstOrNull() ?: action.defaults.first())

    /** Called by the game after any accessibility setting changed. */
    fun onAccessibilityChanged(change: AccessibilityController.Change) {
        when (change) {
            AccessibilityController.Change.PALETTE -> vitalBars.clear() // rebuilt on the next refresh, in the new colours
            AccessibilityController.Change.KEYS -> refreshKeyHints()
            else -> {}
        }
        rebuildAccessibilityWindow()
        rebuildControlsWindow()
    }

    /** Puts the current keys on the top buttons and the minimap title. */
    private fun refreshKeyHints() {
        charTopButton?.setText("Char (${keyHint(GameAction.CHARACTER)})")
        charInvTopButton?.setText("Inv (${keyHint(GameAction.INVENTORY)})")
        val term = worldState.abilityTerminology()
        shownAbilityTerminology = term
        spellsTopButton?.setText("${term.pluralLabel} (${keyHint(GameAction.SPELLBOOK)})")
        questsTopButton?.setText("Quests (${keyHint(GameAction.QUESTS)})")
        craftingTopButton?.setText("Craft (${keyHint(GameAction.CRAFTING)})")
        partyTopButton?.setText("Party (${keyHint(GameAction.PARTY)})")
        minimapTopButton?.setText("Minimap (${keyHint(GameAction.MINIMAP)})")
        (minimapWindow as? VisWindow)?.titleLabel?.setText("Minimap (${keyHint(GameAction.MINIMAP)})")
        controlsTopButton?.setText("Controls (${keyHint(GameAction.CONTROLS)})")
        refreshHotbar()
    }

    /** Shows what a rebinding did ("Inventory: B.", or why a key was refused) in the Controls window and as a toast. */
    fun accessibilityMessage(text: String) {
        controlsNote = text
        worldState.toasts.add(text, ToastKind.INFO, ToastClock.now())
        rebuildControlsWindow()
    }

    fun toggleAccessibilityWindow() {
        openOrNull(accessibilityWindow)?.let {
            it.remove()
            accessibilityWindow = null
            return
        }
        accessibilityWindow = null
        val win = HudWindow("Accessibility")
        win.addCloseButton()
        stage.addActor(win)
        accessibilityWindow = win
        rebuildAccessibilityWindow()
        win.pack()
        win.centerWindow()
        win.toFront()
    }

    private fun rebuildAccessibilityWindow() {
        val win = accessibilityWindow ?: return
        if (win.stage == null) {
            accessibilityWindow = null
            return
        }
        win.clearChildren()
        val settings = accessibility.current
        val table = VisTable()
        table.top().left() // content starts at the top left; a plain Table centres it, which clipped the left edge
        table.defaults().pad(3f).left()
        fun heading(text: String) = table.add(VisLabel(text)).colspan(3).padTop(8f).row()
        fun note(text: String) = table.add(VisLabel(text, theme.mutedLabelStyle).apply { setWrap(true) }).colspan(3).width(370f).row()

        val buttons = VisTable().apply { defaults().padRight(4f) }
        buttons.add(actionButton("Keys and controls...") { toggleControlsWindow() })
        buttons.add(actionButton("Close") { toggleAccessibilityWindow() }).padLeft(12f)
        table.add(buttons).colspan(3).left().row()

        heading("Size")
        table.add(VisLabel("Text and interface size")).colspan(2)
        table.add(actionButton(UiScales.label(settings.uiScale)) { accessibility.cycleScale() }).width(90f).row()
        note("Makes everything larger or smaller, from 75% to 200%. Larger sizes suit low vision; windows stay on screen.")

        heading("Colours")
        table.add(VisLabel("Colour set")).colspan(2)
        table.add(actionButton(settings.palette.label) { accessibility.cyclePalette() }).width(150f).row()
        note(settings.palette.description)
        table.add(PaletteSwatches(assets, settings.palette)).colspan(3).size(370f, 22f).row()
        note("Left to right: health, second bar, third bar, fourth bar, good, bad. Bars also show their numbers, so colour is never the only cue.")

        heading("Motion")
        val motion = VisCheckBox("Reduce motion")
        motion.isChecked = settings.reduceMotion
        motion.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { accessibility.setReduceMotion(motion.isChecked) }
        })
        table.add(motion).colspan(3).row()
        note("Turns off easing and pulsing bars, fading windows, bobbing markers, rising damage text and falling rain and snow.")

        heading("Loot")
        val autoLoot = VisCheckBox("Auto-loot")
        autoLoot.isChecked = settings.autoLoot
        autoLoot.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { accessibility.setAutoLoot(autoLoot.isChecked) }
        })
        table.add(autoLoot).colspan(3).row()
        note("Picks up loot within reach as you walk over it, if it is yours and fits in the bag. Loot another player still owns waits for the pickup key (${keyHint(GameAction.PICK_UP)}).")

        heading("Keys")
        note("Every key, and changing them, is in the Controls window (${keyHint(GameAction.CONTROLS)}).")

        win.add(VisScrollPane(table).apply { setScrollingDisabled(true, false) }).width(420f).height(minOf(470f, stage.height - 80f))
        win.pack()
    }

    /** Opens or closes the Controls window: every key the game uses, what each quickbar slot holds, and changing the keys. */
    fun toggleControlsWindow() {
        openOrNull(controlsWindow)?.let {
            accessibility.cancelCapture()
            it.remove()
            controlsWindow = null
            return
        }
        controlsWindow = null
        val win = HudWindow("Controls")
        win.addCloseButton()
        controlsNote = ""
        stage.addActor(win)
        controlsWindow = win
        rebuildControlsWindow()
        win.pack()
        win.centerWindow()
        win.toFront()
    }

    private fun rebuildControlsWindow() {
        val win = controlsWindow ?: return
        if (win.stage == null) {
            accessibility.cancelCapture()
            controlsWindow = null
            return
        }
        win.clearChildren()
        val keys = accessibility.current.keys
        val capturing = accessibility.capture
        controlsShownSlots = worldState.hotbarSlots.toList()

        /** One column of the window: a heading per group, then a row per action (its name, its two keys, and for quickbar slots what the slot holds). */
        fun section(groups: List<ActionGroup>, withSlots: Boolean): VisTable {
            val table = VisTable()
            table.top().left()
            table.defaults().pad(2f).left()
            val columns = 1 + KEYS_PER_ACTION + if (withSlots) 1 else 0
            val width = CONTROLS_LABEL_W + KEYS_PER_ACTION * CONTROLS_KEY_W + if (withSlots) CONTROLS_SLOT_W else 0f
            for (group in groups) {
                table.add(VisLabel(group.label)).colspan(columns).padTop(8f).row()
                if (group == ActionGroup.QUICKBAR) {
                    table.add(VisLabel("To fill a slot: +Bar next to a spell in the Spellbook, or Quickbar on an item in the Inventory.", theme.mutedLabelStyle)
                        .apply { setWrap(true) }).colspan(columns).width(width).row()
                }
                for (action in GameAction.entries.filter { it.group == group }) {
                    table.add(VisLabel(action.label)).width(CONTROLS_LABEL_W)
                    for (slot in 0 until KEYS_PER_ACTION) {
                        val key = keys.keysFor(action).getOrNull(slot)
                        val label = when {
                            capturing == action to slot -> "Press a key..."
                            key != null -> KeyBindings.keyName(key)
                            else -> "(none)"
                        }
                        table.add(actionButton(label) {
                            stage.keyboardFocus = null
                            accessibility.beginCapture(action, slot)
                            controlsNote = "Press the new key for ${action.label}. Esc cancels, Backspace removes this key."
                            rebuildControlsWindow()
                        }).width(CONTROLS_KEY_W)
                    }
                    if (withSlots) {
                        val held = action.hotbarIndex?.let { worldState.hotbarSlots.getOrNull(it) }
                        table.add((if (held == null) VisLabel("(empty)", theme.mutedLabelStyle) else VisLabel(held.label)).apply { setEllipsis(true) })
                            .width(CONTROLS_SLOT_W)
                    }
                    table.row()
                }
            }
            return table
        }

        val left = section(listOf(ActionGroup.MOVEMENT, ActionGroup.ACTIONS, ActionGroup.WINDOWS), withSlots = false)
        val right = section(listOf(ActionGroup.QUICKBAR), withSlots = true)
        right.add(VisLabel("Fixed keys")).colspan(4).padTop(8f).row()
        for ((key, what) in listOf(
            "F1" to "Map and content editors (staff)",
            "Esc" to "Leave a text box; cancel choosing a key",
            "Mouse" to "Click the map to walk there"
        )) {
            right.add(VisLabel(key)).width(CONTROLS_LABEL_W)
            right.add(VisLabel(what, theme.mutedLabelStyle)).colspan(3).row()
        }

        val leftWidth = CONTROLS_LABEL_W + KEYS_PER_ACTION * CONTROLS_KEY_W + 16f
        val rightWidth = leftWidth + CONTROLS_SLOT_W
        // Side by side when the screen is wide enough (every key visible at once); one scrolling column otherwise.
        val sideBySide = stage.width >= leftWidth + rightWidth + 60f
        val maxViewportWidth = (stage.width - 48f).coerceAtLeast(260f)
        val rawBodyWidth = if (sideBySide) leftWidth + rightWidth + 16f else rightWidth
        val bodyWidth = minOf(rawBodyWidth, maxViewportWidth)
        val body = VisTable().apply { top().left() }

        val showTouchGuide = touchDevice || touchControlsEnabled
        if (showTouchGuide) {
            val touchSection = VisTable().apply {
                top().left()
                defaults().pad(2f).left()
            }
            touchSection.add(VisLabel("Touch Controls (Mobile)")).colspan(2).padTop(4f).row()
            val touchCheck = VisCheckBox("On-screen touch controls").apply {
                isChecked = touchControlsEnabled
                addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        touchControlsEnabled = isChecked
                        rebuildControlsWindow()
                    }
                })
            }
            touchSection.add(touchCheck).colspan(2).padBottom(4f).row()
            val guideRows = listOf(
                "Move" to "Drag anywhere on the left half of the screen (floating joystick)",
                "Tap map" to "Tap a tile or entity to walk or interact",
                "Attack" to "Red button: swing weapon or harvest facing node",
                "Use / Jump" to "Blue button: talk, shop, bank, craft, or leap",
                "Get" to "Gold button: pick up nearby ground items",
                "Skill / Spell" to "Purple button: cast quickbar slot 1 or your first skill",
                "Quickbar 1-10" to "Right-edge buttons (fill via +Bar in Spellbook or Quickbar in Inventory)",
                "Menu" to "Top-right button: Character, Inventory, Quests, Settings, and more"
            )
            val descWidth = (bodyWidth - CONTROLS_LABEL_W - 12f).coerceAtLeast(140f)
            for ((control, desc) in guideRows) {
                touchSection.add(VisLabel(control)).width(CONTROLS_LABEL_W).top()
                touchSection.add(VisLabel(desc, theme.mutedLabelStyle).apply { setWrap(true) }).width(descWidth).row()
            }
            body.add(touchSection).left().padBottom(10f).row()
        }

        val keysTable = VisTable().apply { top().left() }
        if (sideBySide) {
            keysTable.add(left).top().padRight(16f)
            keysTable.add(right).top()
        } else {
            keysTable.add(left).left().row()
            keysTable.add(right).left()
        }
        body.add(keysTable).left()

        val buttons = VisTable().apply { defaults().padRight(6f) }
        buttons.add(actionButton("Reset all keys") {
            accessibility.resetKeys()
            controlsNote = "All keys are back to the standard layout."
            rebuildControlsWindow()
        })
        buttons.add(actionButton("Close") { toggleControlsWindow() })
        val intro = if (showTouchGuide) {
            "On-screen touch controls are listed first. If you use a keyboard, tap any key below to rebind it."
        } else {
            "Click a key to change it, then press the new key. Esc cancels; Backspace removes that key."
        }
        win.add(VisLabel(intro, theme.mutedLabelStyle)
            .apply { setWrap(true) }).width(bodyWidth).pad(4f).row()
        win.add(buttons).left().pad(4f).row()
        if (controlsNote.isNotBlank()) win.add(VisLabel(controlsNote).apply { setWrap(true) }).width(bodyWidth).pad(4f).row()
        val scroll = VisScrollPane(body).apply {
            setScrollingDisabled(false, false)
            setFadeScrollBars(false)
        }
        body.pack()
        win.add(scroll).width(bodyWidth + 14f).height(minOf(body.prefHeight + 4f, (stage.height - 150f).coerceAtLeast(160f)))
        win.pack()
        // Keep the whole window on screen after it grew or shrank (a note appeared, the interface size changed).
        win.x = win.x.coerceIn(0f, (stage.width - win.width).coerceAtLeast(0f))
        win.y = win.y.coerceIn(0f, (stage.height - win.height).coerceAtLeast(0f))
    }

    fun toggleWorldMapWindow() {
        if (!worldState.isModuleEnabled("exploration")) return
        openOrNull(worldMapWindow)?.let {
            it.remove()
            worldMapWindow = null
            worldMapContent = null
            return
        }
        worldMapWindow = null
        worldMapContent = null
        val win = HudWindow("World Map")
        win.addCloseButton()
        val content = VisTable()
        worldMapContent = content
        worldMapShown = null
        win.add(VisScrollPane(content)).width(560f).height(440f)
        stage.addActor(win)
        worldMapWindow = win
        // The terrain and enemy spawns of the explored maps come from the server (the client only holds the maps it has visited).
        netClient.send(ClientPacket.RequestWorldMap)
        syncWorldMapWindow()
        win.pack()
        win.centerWindow()
    }

    /** Rebuilds the map cards when the server sent new exploration; the cards themselves redraw every frame. */
    private fun syncWorldMapWindow() {
        val win = worldMapWindow ?: return
        if (win.stage == null) {
            worldMapWindow = null
            worldMapContent = null
            return
        }
        val content = worldMapContent ?: return
        val explored = worldState.explored
        val key = listOf(explored, worldState.localCharacter?.level, worldState.activeMap?.id, worldState.worldMapDetail)
        if (key == worldMapShown) return
        worldMapShown = key
        content.clearChildren()
        if (explored.isEmpty()) {
            content.add(emptyHint("Nothing explored yet. Walk around and the maps you discover appear here.", 380f)).width(380f).row()
            return
        }
        val infos = explored.values.sortedBy { it.mapId }
        val fogged = infos.flatMap { listOf(it.north, it.south, it.east, it.west) }.filter { it != 0 && it !in explored }.distinct().sorted()
        val nodes = infos.map { MapNode(it.mapId, it.north, it.south, it.east, it.west) } + fogged.map { MapNode(it) }
        val layout = WorldMapLayout.layout(nodes)
        val at = layout.entries.associate { it.value to it.key }
        val columns = (layout.values.maxOfOrNull { it.x } ?: 0)
        val rows = (layout.values.maxOfOrNull { it.y } ?: 0)
        for (gy in 0..rows) {
            for (gx in 0..columns) {
                val id = at[GridPos(gx, gy)]
                if (id == null) {
                    content.add().size(WORLD_MAP_CARD_W, WORLD_MAP_CARD_H)
                    continue
                }
                val info = explored[id]
                val card = VisTable()
                card.add(if (info != null) VisLabel("${info.name} ${Exploration.percent(info.explored, info.width, info.height)}%") else VisLabel("?", theme.mutedLabelStyle)).center().row()
                val summary = if (info != null) {
                    com.morgana.client.render.TargetVisualModel.mapLevelSummary(
                        worldState.cachedMap(id),
                        worldState.catalog.npcs,
                        worldState.localCharacter?.level
                    )
                } else null
                if (summary != null) {
                    val (lvText, lvHex) = summary
                    card.add(VisLabel(lvText, theme.mutedLabelStyle).also { it.color = Color.valueOf(lvHex) }).center().row()
                }
                card.add(WorldMapCard(assets, worldState, id, info, worldState.worldMapDetail[id])).size(WORLD_MAP_CARD_W - 12f, WORLD_MAP_CARD_H - if (summary != null) 46f else 30f).pad(2f)
                content.add(card).size(WORLD_MAP_CARD_W, WORLD_MAP_CARD_H)
            }
            content.row()
        }
    }

    fun toggleGuildWindow() {
        openOrNull(guildWindow)?.let {
            it.remove()
            guildWindow = null
            return
        }
        guildWindow = null
        val win = HudWindow("Guild")
        win.addCloseButton()
        stage.addActor(win)
        guildWindow = win
        guildShownGuild = null
        guildShownRoster = null
        netClient.send(ClientPacket.GuildAction(GuildActionType.REFRESH))
        syncGuildWindow()
        win.pack()
        win.centerWindow()
    }

    /** Rebuilds the guild window when the server sent a new guild or roster. */
    private fun syncGuildWindow() {
        val win = guildWindow ?: return
        if (win.stage == null) {
            guildWindow = null
            return
        }
        val guild = worldState.activeGuild
        val roster = worldState.guildRoster
        if (guild === guildShownGuild && roster === guildShownRoster) return
        guildShownGuild = guild
        guildShownRoster = roster
        win.clearChildren()
        val table = VisTable()
        table.defaults().pad(3f).left()
        val me = worldState.localCharacter?.name.orEmpty()

        if (guild == null) {
            table.add(VisLabel("You are not in a guild.")).colspan(2).row()
            table.add(VisLabel("Guild name:")); val name = VisTextField(""); table.add(name).width(190f).row()
            table.add(VisLabel("Tag:")); val tag = VisTextField(""); table.add(tag).width(80f).row()
            table.add(actionButton("Found a guild") {
                if (name.text.isNotBlank()) netClient.send(ClientPacket.GuildAction(GuildActionType.CREATE, name.text.trim(), tag.text.trim()))
            }).colspan(2).row()
            table.add(actionButton("Accept an invitation") { netClient.send(ClientPacket.GuildAction(GuildActionType.ACCEPT)) }).colspan(2).padTop(8f).row()
            table.add(emptyHint("Another member invites you by name; you are told here and in chat.", 300f)).colspan(2).width(300f).row()
        } else {
            val myRank = roster.firstOrNull { it.name.equals(me, ignoreCase = true) }?.rank ?: guild.memberRanks[worldState.localCharacter?.id]
            table.add(VisLabel("${guild.name} [${guild.tag}]")).colspan(2).row()
            if (GuildRules.canSetMotd(myRank)) {
                val motd = VisTextField(guild.motd)
                table.add(VisLabel("Message:")); table.add(motd).width(240f).row()
                table.add(actionButton("Set message") { netClient.send(ClientPacket.GuildAction(GuildActionType.SET_MOTD, motd.text)) }).colspan(2).row()
            } else {
                table.add(VisLabel("Message: ${guild.motd}").apply { setWrap(true) }).colspan(2).width(320f).row()
            }
            table.add(VisLabel("Members (${roster.size}):")).colspan(2).padTop(6f).row()
            for (m in roster) {
                val row = VisTable().apply { defaults().pad(1f) }
                val tail = if (m.online) "  Lv.${m.level}" else "  (offline)"
                row.add(if (m.online) VisLabel("${m.name}  ${m.rank}$tail") else VisLabel("${m.name}  ${m.rank}$tail", theme.mutedLabelStyle)).width(220f).left()
                if (GuildRules.canChangeRanks(myRank) && m.rank == GuildRules.MEMBER) row.add(actionButton("+") { netClient.send(ClientPacket.GuildAction(GuildActionType.PROMOTE, m.name)) }).width(30f)
                if (GuildRules.canChangeRanks(myRank) && m.rank == GuildRules.OFFICER) row.add(actionButton("-") { netClient.send(ClientPacket.GuildAction(GuildActionType.DEMOTE, m.name)) }).width(30f)
                if (!m.name.equals(me, ignoreCase = true) && GuildRules.canKick(myRank, m.rank)) row.add(actionButton("X") { netClient.send(ClientPacket.GuildAction(GuildActionType.KICK, m.name)) }).width(30f)
                table.add(row).colspan(2).left().row()
            }
            if (GuildRules.canInvite(myRank)) {
                val invite = VisTextField("")
                val inviteRow = VisTable().apply { defaults().pad(2f) }
                inviteRow.add(invite).width(170f)
                inviteRow.add(actionButton("Invite") {
                    if (invite.text.isNotBlank()) { netClient.send(ClientPacket.GuildAction(GuildActionType.INVITE, invite.text.trim())); invite.text = "" }
                })
                table.add(inviteRow).colspan(2).padTop(6f).row()
            }
            table.add(actionButton("Leave guild") { netClient.send(ClientPacket.GuildAction(GuildActionType.LEAVE)) }).colspan(2).padTop(8f).row()
        }
        win.add(VisScrollPane(table)).width(360f).height(300f)
    }

    fun toggleIgnoreWindow() {
        openOrNull(ignoreWindow)?.let {
            it.remove()
            ignoreWindow = null
            ignoreListTable = null
            return
        }
        ignoreWindow = null
        ignoreListTable = null
        val win = HudWindow("Ignore list")
        win.addCloseButton()
        win.defaults().pad(4f).left()
        val list = VisTable().apply { defaults().pad(2f).left() }
        ignoreListTable = list
        ignoreShown = null
        netClient.send(ClientPacket.IgnoreAction(IgnoreActionType.REFRESH))
        val name = VisTextField("")
        val add = VisTable().apply { defaults().pad(2f) }
        add.add(name).width(190f)
        add.add(actionButton("Ignore") {
            val n = name.text.trim()
            if (n.isNotEmpty()) { netClient.send(ClientPacket.IgnoreAction(IgnoreActionType.ADD, n)); name.text = "" }
        })
        win.add(VisLabel("Chat, whispers, mail and invitations from these players are hidden. Staff cannot be ignored.").apply { setWrap(true) }).width(300f).row()
        win.add(VisScrollPane(list)).width(300f).height(160f).row()
        win.add(add).padTop(6f).row()
        stage.addActor(win)
        ignoreWindow = win
        syncIgnoreWindow()
        win.pack()
        win.centerWindow()
    }

    private fun syncIgnoreWindow() {
        val win = ignoreWindow ?: return
        if (win.stage == null) {
            ignoreWindow = null
            ignoreListTable = null
            return
        }
        val list = ignoreListTable ?: return
        val names = worldState.ignored
        if (names === ignoreShown) return
        ignoreShown = names
        list.clearChildren()
        if (names.isEmpty()) {
            list.add(emptyHint("You are not ignoring anyone. Type a name below, or use /ignore name in chat.", 280f)).width(280f).row()
            return
        }
        for (n in names) {
            list.add(VisLabel(n)).width(210f)
            list.add(actionButton("Stop") { netClient.send(ClientPacket.IgnoreAction(IgnoreActionType.REMOVE, n)) }).width(60f).row()
        }
    }

    fun toggleMarketWindow() {
        openOrNull(marketWindow)?.let {
            it.remove()
            marketWindow = null
            marketListTable = null
            return
        }
        marketWindow = null
        marketListTable = null
        val win = HudWindow("Market")
        win.addCloseButton()
        win.defaults().pad(4f).left()
        val list = VisTable().apply { defaults().pad(2f).left() }
        marketListTable = list
        marketShown = null
        netClient.send(ClientPacket.MarketAction(MarketActionType.BROWSE))

        val attach = VisSelectBox<String>()
        val slotOf = mutableListOf<Int>()
        val labels = mutableListOf<String>()
        worldState.localCharacter?.inventory?.slots?.forEachIndexed { idx, stack ->
            if (stack != null) {
                val name = worldState.catalog.items.firstOrNull { it.id == stack.itemId }?.name ?: "Item #${stack.itemId}"
                labels += "$name x${stack.amount}"
                slotOf += idx
            }
        }
        if (labels.isEmpty()) labels += "Nothing to sell"
        attach.setItems(*labels.toTypedArray())
        val amount = VisTextField("1")
        val price = VisTextField("")
        val sell = VisTable().apply { defaults().pad(3f).left() }
        sell.add(VisLabel("Sell an item")).colspan(2).row()
        sell.add(VisLabel("Item:")); sell.add(attach).width(220f).row()
        sell.add(VisLabel("Amount:")); sell.add(amount).width(80f).row()
        sell.add(VisLabel("Price:")); sell.add(price).width(120f).row()
        sell.add(actionButton("List for sale") {
            val slot = slotOf.getOrNull(attach.selectedIndex) ?: return@actionButton
            val p = price.text.trim().toIntOrNull() ?: return@actionButton
            netClient.send(ClientPacket.MarketAction(MarketActionType.LIST, inventorySlotIndex = slot, amount = amount.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1, price = p))
        }).colspan(2).padTop(4f).row()

        win.add(VisLabel("For sale")).row()
        win.add(VisScrollPane(list)).width(420f).height(220f).row()
        win.add(sell).padTop(8f).row()
        stage.addActor(win)
        marketWindow = win
        syncMarketWindow()
        win.pack()
        win.centerWindow()
    }

    /** Rebuilds the listing table when the server sent new listings; the sell form keeps what was typed. */
    private fun syncMarketWindow() {
        val win = marketWindow ?: return
        if (win.stage == null) {
            marketWindow = null
            marketListTable = null
            return
        }
        val list = marketListTable ?: return
        val all = worldState.marketListings
        if (all === marketShown) return
        marketShown = all
        list.clearChildren()
        if (all.isEmpty()) {
            list.add(emptyHint("Nothing is for sale. List an item below; sellers are paid by mail when it sells.", 400f)).width(400f).row()
            return
        }
        val me = worldState.localCharacter?.id
        for (l in all) {
            val name = worldState.catalog.items.firstOrNull { it.id == l.item.itemId }?.name ?: "Item #${l.item.itemId}"
            list.add(VisLabel("$name x${l.item.amount}")).width(160f)
            list.add(VisLabel("${l.priceCurrencyAmount}")).width(70f)
            list.add(VisLabel(l.sellerName, theme.mutedLabelStyle)).width(80f)
            if (l.sellerCharacterId == me) {
                list.add(actionButton("Cancel") { netClient.send(ClientPacket.MarketAction(MarketActionType.CANCEL, l.id)) }).width(70f).row()
            } else {
                list.add(actionButton("Buy") { netClient.send(ClientPacket.MarketAction(MarketActionType.BUY, l.id)) }).width(70f).row()
            }
        }
    }

    fun toggleFriendsWindow() {
        openOrNull(friendsWindow)?.let {
            it.remove()
            friendsWindow = null
            friendsListTable = null
            return
        }
        friendsWindow = null
        friendsListTable = null
        val win = HudWindow("Friends")
        win.addCloseButton()
        win.defaults().pad(4f).left()
        val list = VisTable().apply { defaults().pad(2f).left() }
        friendsListTable = list
        friendsShown = null
        netClient.send(ClientPacket.FriendAction(FriendActionType.REFRESH))
        val name = VisTextField("")
        val add = VisTable().apply { defaults().pad(2f) }
        add.add(name).width(190f)
        add.add(actionButton("Add") {
            val n = name.text.trim()
            if (n.isNotEmpty()) {
                netClient.send(ClientPacket.FriendAction(FriendActionType.ADD, n))
                name.text = ""
            }
        })
        win.add(VisScrollPane(list)).width(320f).height(200f).row()
        win.add(add).padTop(6f).row()
        stage.addActor(win)
        friendsWindow = win
        syncFriendsWindow()
        win.pack()
        win.centerWindow()
    }

    /** Rebuilds the list only when the server sent a new one, so typing in the add box is never lost. */
    private fun syncFriendsWindow() {
        val win = friendsWindow ?: return
        if (win.stage == null) {
            friendsWindow = null
            friendsListTable = null
            return
        }
        val list = friendsListTable ?: return
        val friends = worldState.friends
        if (friends === friendsShown) return
        friendsShown = friends
        list.clearChildren()
        if (friends.isEmpty()) {
            list.add(emptyHint("No friends yet. Type a character name below and press Add to be told when they come online.", 300f)).width(300f).row()
            return
        }
        for (f in friends) {
            val text = if (f.online) "${f.name}  Lv.${f.level}" else "${f.name}  (offline)"
            list.add((if (f.online) VisLabel(text) else VisLabel(text, theme.mutedLabelStyle))).width(210f)
            list.add(actionButton("Mail") { mailWindow?.remove(); mailWindow = null; toggleMailWindow(f.name) }).width(60f)
            list.add(actionButton("X") { netClient.send(ClientPacket.FriendAction(FriendActionType.REMOVE, f.name)) }).width(36f).row()
        }
    }

    fun toggleMailWindow(recipient: String = "") {
        openOrNull(mailWindow)?.let {
            it.remove()
            mailWindow = null
            mailListTable = null
            return
        }
        mailWindow = null
        mailListTable = null
        val win = HudWindow("Mail")
        win.addCloseButton()
        win.defaults().pad(4f).left()
        val list = VisTable().apply { defaults().pad(2f).left() }
        mailListTable = list
        mailShown = null
        netClient.send(ClientPacket.MailAction(MailActionType.REFRESH))

        val to = VisTextField(recipient)
        val subject = VisTextField("")
        val body = VisTextField("")
        val amount = VisTextField("1")
        val ch = worldState.localCharacter
        val attach = VisSelectBox<String>()
        val slotOf = mutableListOf(-1)
        val labels = mutableListOf("No attachment")
        ch?.inventory?.slots?.forEachIndexed { idx, stack ->
            if (stack != null) {
                val name = worldState.catalog.items.firstOrNull { it.id == stack.itemId }?.name ?: "Item #${stack.itemId}"
                labels += "$name x${stack.amount}"
                slotOf += idx
            }
        }
        attach.setItems(*labels.toTypedArray())

        val compose = VisTable().apply { defaults().pad(3f).left() }
        compose.add(VisLabel("Write a letter")).colspan(2).row()
        compose.add(VisLabel("To:")); compose.add(to).width(220f).row()
        compose.add(VisLabel("Subject:")); compose.add(subject).width(220f).row()
        compose.add(VisLabel("Text:")); compose.add(body).width(220f).row()
        compose.add(VisLabel("Attach:")); compose.add(attach).width(220f).row()
        compose.add(VisLabel("Amount:")); compose.add(amount).width(80f).row()
        compose.add(actionButton("Send") {
            val slot = slotOf.getOrElse(attach.selectedIndex) { -1 }
            netClient.send(ClientPacket.SendMail(to.text.trim(), subject.text.trim(), body.text, slot, amount.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1))
            subject.text = ""
            body.text = ""
        }).colspan(2).padTop(4f).row()

        win.add(VisLabel("Inbox")).row()
        win.add(VisScrollPane(list)).width(360f).height(180f).row()
        win.add(compose).padTop(8f).row()
        stage.addActor(win)
        mailWindow = win
        syncMailWindow()
        win.pack()
        win.centerWindow()
    }

    /** Rebuilds the inbox list when the server sent a new mailbox; the compose form is left alone so typing is not lost. */
    private fun syncMailWindow() {
        val win = mailWindow ?: return
        if (win.stage == null) {
            mailWindow = null
            mailListTable = null
            return
        }
        val list = mailListTable ?: return
        val box = worldState.mailbox
        if (box === mailShown) return
        mailShown = box
        list.clearChildren()
        if (box.isEmpty()) {
            list.add(emptyHint("No letters. Letters from other players, with any items they attach, arrive here.", 340f)).width(340f).row()
            return
        }
        for (m in box) {
            val attached = m.attachedItem
            val itemName = attached?.let { s -> worldState.catalog.items.firstOrNull { it.id == s.itemId }?.name ?: "Item #${s.itemId}" }
            val mark = if (m.read) "" else "* "
            list.add(actionButton("$mark${m.senderName}: ${m.subject}") {
                mailSelectedId = m.id
                netClient.send(ClientPacket.MailAction(MailActionType.READ, m.id))
                mailShown = null
            }).width(340f).row()
            if (m.id == mailSelectedId) {
                if (m.body.isNotBlank()) list.add(VisLabel(m.body).apply { setWrap(true) }).width(330f).padLeft(10f).row()
                val actions = VisTable().apply { defaults().pad(2f) }
                if (attached != null && !m.claimed) {
                    actions.add(actionButton("Take $itemName x${attached.amount}") {
                        netClient.send(ClientPacket.MailAction(MailActionType.CLAIM, m.id))
                    })
                } else if (attached != null) {
                    actions.add(VisLabel("Attachment taken"))
                }
                actions.add(actionButton("Delete") {
                    mailSelectedId = null
                    netClient.send(ClientPacket.MailAction(MailActionType.DELETE, m.id))
                })
                list.add(actions).padLeft(10f).row()
            }
        }
    }

    /** A muted, wrapped line for an empty list. */
    private fun emptyHint(text: String, width: Float): VisLabel =
        VisLabel(text, theme.mutedLabelStyle).apply { setWrap(true) }.also { it.width = width }

    private fun syncPartyWindow() {
        val win = partyWindow ?: return
        if (win.stage == null) {
            partyWindow = null
            partyStatusTable = null
            partyShownKey = null
            return
        }
        val table = partyStatusTable ?: return

        val party = worldState.activeParty
        val ch = worldState.localCharacter
        val renderKey = if (party == null) {
            "NONE"
        } else {
            val membersKey = party.memberCharacterIds.joinToString(",") { mId ->
                val snap = worldState.playersOnMap[mId]
                "$mId:${snap?.name ?: ""}:${snap?.level ?: 0}:${snap?.hp ?: 0}:${snap?.maxHp ?: 0}"
            }
            "${party.partyId}|${party.leaderCharacterId}|${party.shareXp}|$membersKey"
        }
        if (renderKey == partyShownKey) return
        partyShownKey = renderKey

        table.clearChildren()

        if (party == null) {
            table.add(VisLabel("You are not currently in a party.")).colspan(2).padBottom(6f).row()
            table.add(actionButton("Accept Pending Invite") {
                netClient.send(ClientPacket.PartyAction(PartyActionType.ACCEPT))
            }).colspan(2).padTop(4f).row()
        } else {
            val isLeader = ch?.id == party.leaderCharacterId
            val leaderName = if (isLeader) "${ch.name} (You)" else (worldState.playersOnMap[party.leaderCharacterId]?.name ?: party.leaderCharacterId)
            table.add(VisLabel("Party Leader: $leaderName")).colspan(2).padBottom(4f).row()
            table.add(VisLabel("Members (${party.memberCharacterIds.size}):")).colspan(2).row()

            for (mId in party.memberCharacterIds) {
                val mName = if (mId == ch?.id) "${ch.name} (You)" else (worldState.playersOnMap[mId]?.name ?: mId)
                val mSnap = worldState.playersOnMap[mId]
                val levelText = if (mSnap != null) " Lv.${mSnap.level} HP: ${mSnap.hp}/${mSnap.maxHp}" else ""
                table.add(VisLabel("• $mName$levelText")).colspan(2).row()
            }

            table.add(VisLabel("--- Settings & Actions ---")).colspan(2).padTop(6f).row()

            val xpShareBtnText = if (party.shareXp) "XP Share: ON (Split)" else "XP Share: OFF (Solo)"
            val xpBtn = actionButton(xpShareBtnText) {
                netClient.send(ClientPacket.PartyAction(PartyActionType.TOGGLE_XP_SHARE))
            }
            table.add(xpBtn).colspan(2).row()

            table.add(actionButton("Leave Party") {
                netClient.send(ClientPacket.PartyAction(PartyActionType.LEAVE))
                win.remove()
                partyWindow = null
                partyStatusTable = null
                partyShownKey = null
            }).colspan(2).padTop(6f).row()
        }
    }

    /** Direction held on the on-screen joystick, or null (also null while the overlay is hidden). Polled each frame. */
    fun heldTouchDirection(): Direction? = touchControls?.takeIf { it.isVisible }?.heldDirection

    fun dispose() {
        touchControls?.dispose()
        compactStatus?.dispose()
        dungeonHudOverlay?.dispose()
        partyFramesOverlay?.dispose()
        lowHpVignetteOverlay?.dispose()
    }

    private fun buildTouchControls(): TouchControls {
        fun slot(i: Int) = worldState.hotbarSlots.getOrNull(i)
        /** The blue face button is Jump while there is nothing to talk to, harvest, shop at or open; otherwise it names the action. */
        fun showJumpOnFaceButton(): Boolean =
            worldState.gameDefinition?.jump?.enabled == true && jumpEnabled && !worldState.hasInteractTarget()
        fun hasNearbyLoot(): Boolean {
            val ch = worldState.localCharacter ?: return false
            val pos = ch.position
            val range = com.morgana.domain.sim.GROUND_PICKUP_RANGE_TILES
            return worldState.groundItemsOnMap.any {
                it.mapId == pos.mapId && kotlin.math.abs(it.tileX - pos.tileX) <= range && kotlin.math.abs(it.tileY - pos.tileY) <= range
            }
        }
        fun slotCooldown(i: Int): Float {
            val spell = slot(i) as? HotbarSlotAction.CastSpell ?: return 0f
            return worldState.spellCooldowns.fractionLeft(spell.spellId, ToastClock.now())
        }
        fun slotDimmed(i: Int): Boolean {
            val spell = slot(i) as? HotbarSlotAction.CastSpell ?: return false
            if (worldState.spellCooldowns.fractionLeft(spell.spellId, ToastClock.now()) > 0f) return true
            val ch = worldState.localCharacter ?: return false
            val def = worldState.catalog.spells.firstOrNull { it.id == spell.spellId } ?: return false
            if (ch.level < def.levelReq || (def.classReqIds.isNotEmpty() && ch.classId !in def.classReqIds)) return true
            return def.costs.any { (vitalId, cost) -> (ch.vitals[vitalId]?.cur ?: 0) < cost }
        }
        val faces = listOf(
            TouchFaceButton(
                glyph = { worldState.attackButtonLabel() },
                color = Color(0.85f, 0.25f, 0.25f, 1f),
                visible = { worldState.showCombatUi() }
            ) {
                if (worldState.swingReady(System.currentTimeMillis())) {
                    netClient.send(ClientPacket.Attack)
                    worldState.triggerLocalAttackOrHarvest()
                    audio.play(RetroSoundEngine.Sfx.SWING)
                }
            },
            TouchFaceButton(
                glyph = { if (showJumpOnFaceButton()) "Jump" else worldState.interactButtonLabel() },
                color = Color(0.25f, 0.5f, 0.9f, 1f)
            ) {
                if (showJumpOnFaceButton()) {
                    worldState.findDirectionalJumpLanding()?.let { landing ->
                        netClient.send(ClientPacket.JumpTo(landing.x, landing.y))
                    }
                } else if (worldState.actionReady("interact", System.currentTimeMillis(), 250L)) {
                    netClient.send(ClientPacket.InteractFacing)
                    worldState.triggerLocalInteract()
                }
            },
            TouchFaceButton(
                glyph = { if (hasNearbyLoot()) "Get!" else "Get" },
                color = Color(0.9f, 0.75f, 0.2f, 1f),
                highlighted = { hasNearbyLoot() }
            ) {
                if (worldState.actionReady("pickup", System.currentTimeMillis(), 250L)) {
                    netClient.send(ClientPacket.PickupGroundItem)
                    audio.play(RetroSoundEngine.Sfx.PICKUP)
                }
            },
            TouchFaceButton(
                glyph = { worldState.abilityTerminology().singularLabel },
                color = Color(0.6f, 0.35f, 0.85f, 1f),
                visible = { worldState.showTouchSpellButton() }
            ) {
                if (slot(0) != null) {
                    triggerHotbar(0)
                } else {
                    val ch = worldState.localCharacter
                    ch?.knownSpellIds?.firstOrNull()?.let { spellId ->
                        val def = worldState.catalog.spells.firstOrNull { it.id == spellId }
                        if ((def == null || (ch.level >= def.levelReq && (def.classReqIds.isEmpty() || ch.classId in def.classReqIds))) &&
                            worldState.castReady(spellId, System.currentTimeMillis())
                        ) {
                            netClient.send(ClientPacket.CastSpell(spellId = spellId))
                            worldState.noteSpellCast(spellId)
                            audio.play(RetroSoundEngine.Sfx.SPELL)
                        }
                    }
                }
            }
        )
        val keys = (1..10).map { n ->
            TouchHotkey(
                caption = { "$n\n${slot(n - 1)?.label?.take(4) ?: "-"}" },
                visible = { worldState.showHotbarSlot(n - 1) },
                cooldownFraction = { slotCooldown(n - 1) },
                dimmed = { slotDimmed(n - 1) }
            ) { triggerHotbar(n - 1) }
        } + listOf(
            TouchHotkey(caption = { "Inv" }) { toggleCharacterAndInventoryWindow() },
            TouchHotkey(caption = { worldState.abilityTerminology().touchShortLabel }, visible = { worldState.showSpellsUi() }) { toggleSpellbookWindow() }
        )
        return TouchControls(
            font = { VisUI.getSkin().getFont("default-font") },
            faceButtons = faces,
            hotkeys = keys,
            allowDiagonals = { worldState.movementMode == com.morgana.domain.spatial.MovementMode.SUB_TILE }
        )
    }

    private fun layoutCompactHud() {
        compactStatus?.let { it.setPosition(10f + insetLeft, stage.height - it.height - 8f - insetTop) }
        menuButton?.let { it.setPosition(stage.width - it.width - 8f - insetRight, stage.height - it.height - 8f - insetTop) }
    }

    private var insetLeft = 0f
    private var insetRight = 0f
    private var insetTop = 0f

    /**
     * Keeps the top HUD (status, buttons, the touch Menu button) clear of the editor's docked panels: it moves in by the space
     * the panels cover, so the F1 toolbar does not hide it. Call every frame with the covered space in stage units; it only
     * moves things when the numbers change.
     */
    fun setViewInsets(left: Float, right: Float, top: Float) {
        if (left == insetLeft && right == insetRight && top == insetTop) return
        insetLeft = left
        insetRight = right
        insetTop = top
        topFrameWindow?.let { it.setPosition(12f + left, stage.height - it.height - 12f - top) }
        layoutCompactHud()
    }

    /** Touch-friendly launcher for everything the desktop top bar offers: one big button per window. */
    fun toggleMenuWindow() {
        openOrNull(menuWindow)?.let {
            it.remove()
            menuWindow = null
            return
        }
        menuWindow = null
        val win = HudWindow("Menu")
        win.addCloseButton()
        win.isMovable = true
        win.defaults().pad(4f)
        fun item(label: String, action: () -> Unit) = VisTextButton(label).also { b ->
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    menuWindow?.remove()
                    menuWindow = null
                    action()
                }
            })
        }
        val entries = buildList<Pair<String, () -> Unit>> {
            add("Character" to { toggleCharacterWindow() })
            add("Inventory" to { toggleCharacterAndInventoryWindow() })
            if (worldState.showSpellsUi()) add(worldState.abilityTerminology().pluralLabel to { toggleSpellbookWindow() })
            add("Quests" to { toggleQuestWindow() })
            if (worldState.gameDefinition?.bestiary?.enabled == true) add("Bestiary" to { bestiaryWindow.toggle() })
            if (PetsModel.gameHasPets(worldState.catalog.npcs)) add("Pets" to { petsWindow.toggle() })
            if (worldState.showCraftingUi()) add("Crafting" to { toggleCraftingWindow() })
            if (worldState.showPartyUi()) add("Party" to { togglePartyWindow() })
            if (worldState.isModuleEnabled("guilds")) add("Guild" to { toggleGuildWindow() })
            if (worldState.isModuleEnabled("ignore")) add("Ignore" to { toggleIgnoreWindow() })
            if (worldState.isModuleEnabled("friends")) add("Friends" to { toggleFriendsWindow() })
            if (worldState.isModuleEnabled("market")) add("Market" to { toggleMarketWindow() })
            if (worldState.isModuleEnabled("mail")) add("Mail" to { toggleMailWindow() })
            if (worldState.showMinimapUi()) add("Map" to { toggleMinimap() })
            if (worldState.isModuleEnabled("exploration")) add("World map" to { toggleWorldMapWindow() })
            if (worldState.showChatUi()) add("Chat" to {
                (chatWindow as? HudWindow)?.let { c ->
                    c.setCollapsed(!c.collapsed)
                    if (!c.collapsed) c.toFront()
                }
            })
            add("Controls" to { toggleControlsWindow() })
            add("Accessibility" to { toggleAccessibilityWindow() })
            add("Settings" to { toggleSettingsWindow() })
            add("Admin (F1)" to { onToggleF1Admin() })
        }
        // Two columns while the menu is short; three once the social entries (guild, friends, mail, market, ...) make it tall.
        val columns = if (entries.size > 8) 3 else 2
        entries.forEachIndexed { i, (label, action) ->
            win.add(item(label, action)).width(if (columns == 3) 118f else 130f).height(46f)
            if (i % columns == columns - 1) win.row()
        }
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        menuWindow = win
    }

    fun toggleSettingsWindow() {
        openOrNull(settingsWindow)?.let {
            it.remove()
            settingsWindow = null
            return
        }
        settingsWindow = null
        val win = HudWindow("Settings")
        win.addCloseButton()
        win.isMovable = true

        val body = VisTable()
        body.defaults().pad(5f).left()

        fun volumeRow(title: String, muted: () -> Boolean, setMuted: (Boolean) -> Unit, volume: () -> Float, setVolume: (Float) -> Unit) {
            val check = VisCheckBox(title)
            check.isChecked = !muted()
            check.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { setMuted(!check.isChecked) }
            })
            val slider = VisSlider(0f, 1f, 0.05f, false)
            slider.value = volume()
            slider.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    setVolume(slider.value)
                    if (slider.value > 0f && !check.isChecked) check.isChecked = true
                }
            })
            body.add(check).width(150f)
            body.add(slider).width(180f).row()
        }
        volumeRow("Master sound", { audio.muted }, { audio.muted = it; audio.bgmVolume = audio.bgmVolume }, { audio.masterVolume }, { audio.masterVolume = it; audio.bgmVolume = audio.bgmVolume })
        volumeRow("Sound effects", { audio.sfxMuted }, { audio.sfxMuted = it }, { audio.sfxVolume }, { audio.sfxVolume = it })
        volumeRow("Music", { audio.musicMuted }, { audio.musicMuted = it }, { audio.bgmVolume }, { audio.bgmVolume = it })

        val opacityLabel = VisLabel("Window opacity")
        val opacity = VisSlider(0.3f, 1f, 0.05f, false)
        opacity.value = HudWindow.opacity
        opacity.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { HudWindow.applyOpacity(stage, opacity.value) }
        })
        body.add(opacityLabel).width(150f)
        body.add(opacity).width(180f).row()

        val touchCheck = VisCheckBox("On-screen touch controls")
        touchCheck.isChecked = touchControlsEnabled
        touchCheck.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { touchControlsEnabled = touchCheck.isChecked }
        })
        body.add(touchCheck).colspan(2).row()

        val tapCheck = VisCheckBox("Tap or click to move")
        tapCheck.isChecked = tapToMoveEnabled
        tapCheck.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { tapToMoveEnabled = tapCheck.isChecked }
        })
        body.add(tapCheck).colspan(2).row()

        if (worldState.gameDefinition?.jump?.enabled == true) {
            val jumpCheck = VisCheckBox("Enable Jump (Ctrl+Click / F)")
            jumpCheck.isChecked = jumpEnabled
            jumpCheck.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { jumpEnabled = jumpCheck.isChecked }
            })
            body.add(jumpCheck).colspan(2).row()
        }

        val zoom = VisTextButton(uiScaleLabel())
        zoom.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { zoom.setText(onCycleUiScale()) }
        })
        body.add(VisLabel("Interface size"))
        body.add(zoom).width(110f).row()

        val worldZoom = VisTextButton(worldZoomLabel())
        worldZoom.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { worldZoom.setText(onCycleWorldZoom()) }
        })
        body.add(VisLabel("World zoom"))
        body.add(worldZoom).width(110f).row()

        body.add(actionButton("Controls...") { toggleControlsWindow() })
        body.add(actionButton("Accessibility...") { toggleAccessibilityWindow() }).row()

        body.add(actionButton("Reset window positions") {
            HudWindow.resetSavedPositions()
            worldState.toasts.add("Window positions reset. Each window returns to its default place the next time it opens.", ToastKind.INFO, ToastClock.now())
        }).colspan(2).row()

        body.add(actionButton("Log out") {
            win.remove()
            settingsWindow = null
            onLogout()
        }).colspan(2).width(150f).row()

        body.pack()
        val maxScrollWidth = (stage.width - win.padLeft - win.padRight - 16f).coerceAtLeast(240f)
        val maxScrollHeight = (stage.height - win.padTop - win.padBottom - 16f).coerceAtLeast(120f)
        val fitsWidth = body.prefWidth + 18f <= maxScrollWidth
        val scroll = VisScrollPane(body).apply {
            setScrollingDisabled(fitsWidth, false)
            setFadeScrollBars(false)
        }
        win.add(scroll)
            .width(minOf(body.prefWidth + 18f, maxScrollWidth))
            .height(minOf(body.prefHeight + 4f, maxScrollHeight))
            .row()
        win.pack()
        win.centerWindow()
        stage.addActor(win)
        win.x = win.x.coerceIn(0f, (stage.width - win.width).coerceAtLeast(0f))
        win.y = win.y.coerceIn(0f, (stage.height - win.height).coerceAtLeast(0f))
        settingsWindow = win
    }

    private fun refreshStatusEffects(ch: com.morgana.domain.entity.CharacterState) {
        val effects = ch.statusEffects.take(6)
        val sig = effects.joinToString(",") { "${it.type.name}:${it.remainingTicks / 20}" }
        if (sig == lastStatusSig) return
        lastStatusSig = sig
        statusEffectRow.clearChildren()
        statusEffectRow.isVisible = effects.isNotEmpty()
        if (effects.isEmpty()) {
            topFrameWindow?.let { it.pack(); it.setPosition(12f, stage.height - it.height - 12f) }
            return
        }
        statusEffectRow.defaults().padRight(3f)
        for (eff in effects) {
            val isBad = eff.type.isNegative
            val badge = VisTable()
            badge.background = theme.dynamic { t, p ->
                t.beveledPanel(p.panelRaised, highlight = if (isBad) p.danger else p.success, padX = 3f, padY = 1f)
            }
            val code = eff.type.name.take(2)
            val lbl = VisLabel(code, theme.labelStyle)
            lbl.color = if (isBad) theme.palette.danger else theme.palette.success
            badge.add(lbl)
            com.kotcrab.vis.ui.widget.Tooltip(theme.tooltipStyle).apply {
                setText("${eff.type.name.replace('_', ' ')} (${eff.remainingTicks / 20}s)")
                setTarget(badge)
            }
            statusEffectRow.add(badge).size(22f, 18f)
        }
        topFrameWindow?.let { it.pack(); it.setPosition(12f, stage.height - it.height - 12f) }
    }

    private fun chatChannelColor(channel: ChatChannel): Color = when (channel) {
        ChatChannel.SYSTEM -> theme.palette.warning
        ChatChannel.GUILD -> theme.palette.success
        ChatChannel.PARTY -> Color(0.45f, 0.78f, 1f, 1f)
        ChatChannel.GLOBAL, ChatChannel.BROADCAST -> theme.palette.accent
        ChatChannel.WHISPER -> Color(0.92f, 0.52f, 0.96f, 1f)
        ChatChannel.EMOTE -> theme.palette.textMuted
        ChatChannel.MAP -> theme.palette.text
    }

    private var lastDockSignature: String = ""

    private fun refreshMenuDockLayout() {
        val dock = menuDockWindow ?: return
        val r1 = menuDockRow1 ?: return
        val r2 = menuDockRow2 ?: return
        val sig = (menuDockRow1Buttons + menuDockRow2Buttons).joinToString(",") { if (it.isVisible) "1" else "0" }
        if (sig == lastDockSignature) return
        lastDockSignature = sig
        r1.clearChildren()
        menuDockRow1Buttons.filter { it.isVisible }.forEach { r1.add(it) }
        r2.clearChildren()
        menuDockRow2Buttons.filter { it.isVisible }.forEach { r2.add(it) }
        dock.pack()
        dock.setPosition(stage.width - dock.width - 10f, 10f)
    }

    private fun actionButton(label: String, onClick: () -> Unit): VisTextButton {
        val btn = VisTextButton(label, theme.buttonStyle)
        btn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onClick()
            }
        })
        return btn
    }

    private fun dockIconButton(iconName: String, probeText: String, hotkey: String, tip: String, onClick: () -> Unit): VisTextButton {
        val btn = VisTextButton(probeText, theme.buttonStyle)
        btn.clearChildren()
        btn.add(com.badlogic.gdx.scenes.scene2d.ui.Image(theme.dynamic { t, p -> t.icon(iconName, p.text) })).size(16f, 16f).row()
        btn.add(VisLabel(hotkey, theme.mutedLabelStyle)).padTop(1f)
        com.kotcrab.vis.ui.widget.Tooltip(theme.tooltipStyle).apply {
            setText("$tip ($hotkey)")
            setTarget(btn)
        }
        btn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onClick()
            }
        })
        return btn
    }

    private fun showHotbarAssignModal(action: HotbarSlotAction) {
        val win = HudWindow("Assign '${action.label}' to Quickbar")
        win.addCloseButton()
        win.defaults().pad(3f)

        val grid = VisTable()
        grid.defaults().pad(2f).width(74f).height(34f)
        for (idx in 0..9) {
            val keyChar = if (idx == 9) "0" else "${idx + 1}"
            grid.add(actionButton("Slot [$keyChar]") {
                worldState.hotbarSlots[idx] = action
                audio.play(RetroSoundEngine.Sfx.CLICK)
                refreshHotbar()
                onHotbarChanged()
                win.remove()
            })
            if (idx == 4) grid.row()
        }
        win.add(grid).row()
        win.pack()
        win.centerWindow()
        stage.addActor(win)
    }
}

/**
 * Renders a circular 2D minimap radar centered on the player with scaled terrain, walls, water, warps, NPCs,
 * fog of war, and pulsing player blips.
 */
class MinimapCanvas(
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState
) : Actor() {
    private var blinkTimer = 0f
    private var radiusTiles = 18f
    private var bezelSize = 0
    private var bezelTexture: com.badlogic.gdx.graphics.Texture? = null

    fun adjustZoom(deltaTiles: Float) {
        radiusTiles = (radiusTiles + deltaTiles).coerceIn(10f, 38f)
    }

    override fun act(delta: Float) {
        super.act(delta)
        blinkTimer += delta
    }

    private fun ensureBezel(size: Int): com.badlogic.gdx.graphics.Texture {
        if (bezelTexture != null && bezelSize == size) return bezelTexture!!
        bezelTexture?.dispose()
        bezelSize = size
        val pm = com.badlogic.gdx.graphics.Pixmap(size, size, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        val c = size / 2f
        val outerR = c - 0.5f
        val innerR = outerR - 2.5f
        val discR = innerR - 1f
        for (py in 0 until size) for (px in 0 until size) {
            val dx = px + 0.5f - c
            val dy = py + 0.5f - c
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val color = when {
                dist > outerR -> 0x00000000
                dist > innerR -> 0x101318FF
                dist > discR -> 0x5A6475FF
                else -> 0x00000000
            }
            pm.drawPixel(px, py, color)
        }
        val tex = com.badlogic.gdx.graphics.Texture(pm)
        pm.dispose()
        bezelTexture = tex
        return tex
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val map = worldState.activeMap ?: return
        val w = width
        val h = height
        val diameter = minOf(w, h)
        val radiusPx = diameter * 0.5f
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f
        val maxClipR2 = (radiusPx - 3f) * (radiusPx - 3f)

        val local = worldState.localCharacter
        val centerTx = if (local != null && local.position.mapId == map.id) local.position.tileX.toFloat() else map.width * 0.5f
        val centerTy = if (local != null && local.position.mapId == map.id) local.position.tileY.toFloat() else map.height * 0.5f
        val pxPerTile = diameter / (radiusTiles * 2f)

        fun insideCircle(sx: Float, sy: Float): Boolean {
            val dx = sx - cx
            val dy = sy - cy
            return dx * dx + dy * dy <= maxClipR2
        }

        // Circular dark backdrop
        val step = 3f
        batch.setColor(0.06f, 0.08f, 0.12f, 0.92f * parentAlpha)
        var ry = -radiusPx + 3f
        while (ry <= radiusPx - 3f) {
            val halfSpan = kotlin.math.sqrt(((radiusPx - 3f) * (radiusPx - 3f) - ry * ry).coerceAtLeast(0f).toDouble()).toFloat()
            batch.draw(assets.whitePixel, cx - halfSpan, cy + ry, halfSpan * 2f, step)
            ry += step
        }

        val minTx = (centerTx - radiusTiles - 1).toInt().coerceAtLeast(0)
        val maxTx = (centerTx + radiusTiles + 1).toInt().coerceAtMost(map.width - 1)
        val minTy = (centerTy - radiusTiles - 1).toInt().coerceAtLeast(0)
        val maxTy = (centerTy + radiusTiles + 1).toInt().coerceAtMost(map.height - 1)
        val fog = if (worldState.isModuleEnabled("exploration")) worldState.explored[map.id] else null

        // Draw terrain within circular viewport
        for (ty in minTy..maxTy) {
            val cellY = cy + (centerTy - ty) * pxPerTile
            for (tx in minTx..maxTx) {
                val cellX = cx + (tx - centerTx) * pxPerTile
                if (!insideCircle(cellX, cellY)) continue
                if (fog != null && !Exploration.isTileExplored(fog.explored, map.width, tx, ty)) continue
                val cell = map.getCell(tx, ty)
                val isWater = cell?.layers?.get(MapLayer.GROUND)?.srcTileX == 3
                val isBlocked = cell?.attribute is TileAttribute.Blocked
                val isWarp = cell?.attribute is TileAttribute.Warp
                when {
                    isBlocked -> batch.setColor(0.35f, 0.38f, 0.45f, 0.9f * parentAlpha)
                    isWater -> batch.setColor(0.15f, 0.35f, 0.75f, 0.9f * parentAlpha)
                    isWarp -> batch.setColor(0.2f, 0.85f, 0.9f, 0.95f * parentAlpha)
                    else -> batch.setColor(0.20f, 0.42f, 0.24f, 0.88f * parentAlpha)
                }
                batch.draw(assets.whitePixel, cellX - pxPerTile * 0.5f, cellY - pxPerTile * 0.5f, pxPerTile + 0.5f, pxPerTile + 0.5f)
            }
        }

        // Points of interest within circular viewport
        val npcDefsById = worldState.catalog.npcs.associateBy { it.id }
        val pois = MinimapPoiModel.collectPois(
            map = map,
            npcsOnMap = worldState.npcsOnMap.values,
            npcDefsById = npcDefsById,
            groundItems = worldState.groundItemsOnMap,
            isTileExplored = { tx, ty -> fog == null || Exploration.isTileExplored(fog.explored, map.width, tx, ty) }
        )
        val pulse = if (Accessibility.reduceMotion || ((blinkTimer * 3f).toInt() % 2) == 0) 0.95f else 0.5f
        for (poi in pois) {
            val px = cx + (poi.tileX - centerTx) * pxPerTile
            val py = cy + (centerTy - poi.tileY) * pxPerTile
            if (!insideCircle(px, py)) continue
            val size = when (poi.kind) {
                MinimapPoiModel.PoiKind.WARP -> 5f
                MinimapPoiModel.PoiKind.SERVICE -> 4f
                MinimapPoiModel.PoiKind.GROUND_LOOT -> 4f
                MinimapPoiModel.PoiKind.BOSS -> 7f
            }
            when (poi.kind) {
                MinimapPoiModel.PoiKind.WARP -> batch.setColor(0.25f, 0.9f, 0.98f, 0.95f * parentAlpha)
                MinimapPoiModel.PoiKind.SERVICE -> batch.setColor(0.35f, 0.9f, 0.55f, 0.95f * parentAlpha)
                MinimapPoiModel.PoiKind.GROUND_LOOT -> batch.setColor(0.98f, 0.8f, 0.25f, 0.95f * parentAlpha)
                MinimapPoiModel.PoiKind.BOSS -> batch.setColor(1f, 0.84f, 0.2f, pulse * parentAlpha)
            }
            batch.draw(assets.whitePixel, px - size * 0.5f, py - size * 0.5f, size, size)
        }

        // Draw NPCs
        for (npc in worldState.npcsOnMap.values) {
            if (!npc.isAlive) continue
            if (fog != null && !Exploration.isTileExplored(fog.explored, map.width, npc.tileX, npc.tileY)) continue
            val nx = cx + (npc.tileX - centerTx) * pxPerTile
            val ny = cy + (centerTy - npc.tileY) * pxPerTile
            if (!insideCircle(nx, ny)) continue
            val npcDef = worldState.catalog.npcs.firstOrNull { it.id == npc.npcId }
            val isHostile = npcDef?.behavior?.name?.contains("ATTACK") == true || npcDef?.behavior?.name?.contains("GUARD") == true
            val blip = Accessibility.colour(if (isHostile) PaletteRole.HOSTILE else PaletteRole.FRIENDLY)
            batch.setColor(blip.r, blip.g, blip.b, 1f * parentAlpha)
            val blipSize = if (isHostile) 5f else 3.5f
            batch.draw(assets.whitePixel, nx - blipSize * 0.5f, ny - blipSize * 0.5f, blipSize, blipSize)
        }

        // Draw Other Players
        for (player in worldState.playersOnMap.values) {
            if (fog != null && !Exploration.isTileExplored(fog.explored, map.width, player.position.tileX, player.position.tileY)) continue
            val px = cx + (player.position.tileX - centerTx) * pxPerTile
            val py = cy + (centerTy - player.position.tileY) * pxPerTile
            if (!insideCircle(px, py)) continue
            val other = Accessibility.colour(PaletteRole.OTHER_PLAYER)
            batch.setColor(other.r, other.g, other.b, 1f * parentAlpha)
            batch.draw(assets.whitePixel, px - 2f, py - 2f, 4f, 4f)
        }

        // Draw Local Player at center
        if (local != null && local.position.mapId == map.id) {
            val p = if (Accessibility.reduceMotion || ((blinkTimer * 4f).toInt() % 2) == 0) 1f else 0.45f
            batch.setColor(1f, 1f, 1f, p * parentAlpha)
            batch.draw(assets.whitePixel, cx - 2.5f, cy - 2.5f, 5f, 5f)
        }

        // Draw pixel-art circular bezel ring on top
        val bezel = ensureBezel(diameter.toInt().coerceAtLeast(32))
        batch.setColor(1f, 1f, 1f, parentAlpha)
        batch.draw(bezel, cx - radiusPx, cy - radiusPx, diameter, diameter)
        batch.color = Color.WHITE
    }
}


/** A plugin window shows this many table rows before the list starts to scroll. */
private const val PLUGIN_ROWS_BEFORE_SCROLL = 8


/** Size of one map card on the world map window, in stage units. */
const val WORLD_MAP_CARD_W = 230f

// Column widths of the Controls window: action name, each key button, and what a quickbar slot holds.
private const val CONTROLS_LABEL_W = 130f
private const val CONTROLS_KEY_W = 100f
private const val CONTROLS_SLOT_W = 120f
const val WORLD_MAP_CARD_H = 230f

/** A row of boxes showing what [palette] makes of health, the other bars, good and bad, so a choice can be judged before it is made. */
class PaletteSwatches(private val assets: ProceduralRetroAssets, private val palette: ColourPalette) : Actor() {
    override fun draw(batch: Batch, parentAlpha: Float) {
        val roles = listOf(PaletteRole.HEALTH, PaletteRole.MANA, PaletteRole.VITAL_THREE, PaletteRole.VITAL_FOUR, PaletteRole.GOOD, PaletteRole.BAD)
        val gap = 6f
        val w = (width - gap * (roles.size - 1)) / roles.size
        roles.forEachIndexed { i, role ->
            val c = Accessibility.colour(role, palette)
            batch.setColor(0f, 0f, 0f, 0.7f * parentAlpha)
            batch.draw(assets.whitePixel, x + i * (w + gap) - 1f, y - 1f, w + 2f, height + 2f)
            batch.setColor(c.r, c.g, c.b, parentAlpha)
            batch.draw(assets.whitePixel, x + i * (w + gap), y, w, height)
        }
        batch.color = Color.WHITE
    }
}
