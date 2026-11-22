package com.morgana.client.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.widget.VisImageButton
import com.morgana.client.ui.editor.IconArt
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox

import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisWindow
import com.morgana.client.LocalWorldDetails
import com.morgana.client.LocalWorldHost
import com.morgana.client.net.ConnectionPlanner
import com.morgana.client.realms.DEFAULT_CLASSES
import com.morgana.client.realms.InMemoryRealmDirectoryClient
import com.morgana.client.realms.LocalWorldsModel
import com.morgana.client.realms.DirectoryRefresher
import com.morgana.client.realms.RealmBrowserModel
import com.morgana.client.realms.RealmBrowserView
import com.morgana.client.realms.RealmDirectoryClient
import com.morgana.client.realms.RealmFilter
import com.morgana.client.realms.RealmRow
import com.morgana.client.realms.RealmSort
import com.morgana.client.realms.ServerAddress
import com.morgana.client.realms.defaultBundledDirectory
import com.morgana.client.state.InMemoryPerRealmStore
import com.morgana.client.state.PerRealmStore
import com.morgana.protocol.realms.RealmClassSummary
import com.morgana.protocol.realms.RealmDirectoryDocument
import com.morgana.protocol.realms.RealmSummary

/**
 * Universal client hub window for Morgana Realms.
 * Provides a Morgana Realms directory browser, direct invite/host connection,
 * and local world hosting management, while maintaining clean engine decoupling.
 */
class MorganaRealmsHubWindow(
    private val stage: Stage,
    private val defaultHost: String = "127.0.0.1",
    private val defaultPort: Int = 7001,
    private val directoryClient: RealmDirectoryClient = InMemoryRealmDirectoryClient(defaultBundledDirectory(defaultHost, defaultPort)),
    private val perRealmStore: PerRealmStore = InMemoryPerRealmStore(),
    private val localWorlds: LocalWorldHost? = null,
    private val onConnectRequest: (
        host: String,
        port: Int,
        tls: Boolean,
        username: String,
        password: String,
        classId: Int,
        selectedRealm: RealmSummary?
    ) -> Unit
) {
    private var window: VisWindow? = null

    private fun ensureTheme(): GameTheme = GameTheme.shared

    // Common fields
    private val hostField = VisTextField(defaultHost)

    /** The field the current tab wants focused so typing, arrow keys and Enter work at once; null on touch screens (it would raise the soft keyboard). */
    private var tabFocus: Actor? = null

    /** Moves to the next (or previous) tab; set by [buildContent], used by the Ctrl+Tab handler. */
    private var cycleTab: ((Boolean) -> Unit)? = null
    private val portField = VisTextField(defaultPort.toString())
    private val tlsBox = VisCheckBox("Encrypted TLS").apply {
        isChecked = false
    }
    private val initialStoreDoc = perRealmStore.load()
    private val usernameField = VisTextField(initialStoreDoc.lastUsername ?: "Admin")
    private val passwordField = VisTextField(
        if (initialStoreDoc.rememberPassword && initialStoreDoc.savedPassword != null) initialStoreDoc.savedPassword else "admin"
    ).apply {
        isPasswordMode = true
        setPasswordCharacter('*')
    }
    private val rememberPasswordBox = VisCheckBox("Remember login").apply {
        isChecked = initialStoreDoc.rememberPassword
    }
    private val statusLabel = VisLabel("Select a realm or enter connection details below.")

    init {
        // Enter in the name or password box signs in, the way every login screen works.
        val submit = VisTextField.TextFieldListener { _, c -> if (c == '\r' || c == '\n') triggerConnect() }
        usernameField.setTextFieldListener(submit)
        passwordField.setTextFieldListener(submit)
        hostField.setTextFieldListener(submit)
        portField.setTextFieldListener(submit)
    }

    // Active realm selection
    private var activeRealm: RealmSummary? = null

    /** The favourite star, drawn from the editor's pixel art (the UI font has no star glyph); gold when favourited, dim when not. */
    private val starTexture: Texture by lazy {
        val pm = Pixmap(IconArt.SIZE, IconArt.SIZE, Pixmap.Format.RGBA8888)
        IconArt.icons.getValue("star").forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> val a = IconArt.alphaOf(c) ?: 0; if (a > 0) pm.drawPixel(x, y, 0xffffff00.toInt() or a) }
        }
        Texture(pm).also { pm.dispose(); it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
    }
    private fun starDrawable(on: Boolean): Drawable =
        TextureRegionDrawable(com.badlogic.gdx.graphics.g2d.TextureRegion(starTexture)).tint(if (on) STAR_GOLD else STAR_DIM)

    // Directory browser state (kept across redraws so typing and scrolling are not lost)
    private var filter = RealmFilter()
    private val connectingLabel = VisLabel("")
    private var refreshList: () -> Unit = {}
    private var refreshDetails: () -> Unit = {}
    // A network directory is fetched in the background so a slow or dead server never freezes the screen.
    private val refresher = DirectoryRefresher(
        directoryClient,
        runInBackground = { job -> if (directoryClient.slow) Thread(job, "Morgana-Directory").apply { isDaemon = true }.start() else job() },
        postToUi = { job -> if (directoryClient.slow) Gdx.app.postRunnable(job) else job() }
    )
    private var directoryDoc: RealmDirectoryDocument =
        if (directoryClient.slow) RealmDirectoryDocument() else directoryClient.fetchDirectory().getOrDefault(RealmDirectoryDocument())
    private var directoryNote: String = if (directoryClient.slow) "Loading realms..." else ""
    private var currentTab: Tab = Tab.DIRECTORY
    private var startedFirstFetch = false

    /** Fetches the directory again and redraws the screen when it arrives (the typed search, filters and selection are kept). */
    private fun refreshDirectory() {
        val started = refresher.refresh { result ->
            result.onSuccess { directoryDoc = it; directoryNote = "" }
                .onFailure { directoryNote = "Could not reach the realm directory. Showing the last list." }
            if (isOpen() && currentTab == Tab.DIRECTORY) buildContent()
        }
        if (started && directoryClient.slow) {
            directoryNote = "Refreshing..."
            if (isOpen() && currentTab == Tab.DIRECTORY) refreshList()
        }
    }

    private enum class Tab {
        DIRECTORY,
        DIRECT_JOIN,
        LOCAL_WORLDS
    }

    fun isOpen(): Boolean = window != null && window?.stage != null

    fun show() {
        if (isOpen()) {
            window?.toFront()
            return
        }

        val uiTheme = ensureTheme()
        val win = VisWindow("Morgana Realms - Universal Client Hub", uiTheme.windowStyle)
        win.isResizable = false
        win.isMovable = true
        window = win

        buildContent()

        win.pack()
        win.centerWindow()
        win.addCaptureListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode != Input.Keys.TAB) return false
                val input = Gdx.input
                val backwards = input.isKeyPressed(Input.Keys.SHIFT_LEFT) || input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
                if (input.isKeyPressed(Input.Keys.CONTROL_LEFT) || input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) {
                    cycleTab?.invoke(backwards)
                    return true
                }
                // Tab moves between the connection and sign-in boxes (Shift+Tab goes back); the event is stopped so the
                // text field does not also act on it.
                if (tabBetweenFields(backwards)) {
                    event?.stop()
                    return true
                }
                return false
            }

            override fun keyTyped(event: InputEvent?, character: Char): Boolean {
                // The typed tab character that follows the key press must not reach (and be inserted by) a text field.
                if (character == '\t' && focusedFormFieldIndex() >= 0) {
                    event?.stop()
                    return true
                }
                return false
            }
        })
        stage.addActor(win)
        focusTab()
        if (directoryClient.slow && !startedFirstFetch) {
            startedFirstFetch = true
            refreshDirectory()
        }
    }

    /** The connection and sign-in boxes on screen, in the order Tab visits them. */
    private fun formFields(): List<VisTextField> =
        listOf(hostField, portField, usernameField, passwordField).filter { it.stage != null }

    /** Index in [formFields] of the box that has the keyboard focus, or -1 when it is elsewhere. */
    private fun focusedFormFieldIndex(): Int = formFields().indexOf(stage.keyboardFocus)

    /** Moves the focus to the next (or previous) sign-in box, wrapping round. False when no such box has the focus. */
    private fun tabBetweenFields(backwards: Boolean): Boolean {
        val fields = formFields()
        val at = fields.indexOf(stage.keyboardFocus)
        if (at < 0) return false
        val next = fields[KeyNav.cycle(at, fields.size, backwards)]
        stage.keyboardFocus = next
        next.selectAll()
        return true
    }

    private fun focusTab() {
        val target = tabFocus ?: return
        if (!HudWindow.touchMode && target.stage != null) stage.keyboardFocus = target
    }

    fun close() {
        window?.remove()
        window = null
    }

    fun setStatus(message: String) {
        statusLabel.setText(message)
    }

    private fun buildContent() {
        val win = window ?: return
        win.clearChildren()
        val compact = shortScreen()
        win.defaults().pad(if (compact) 2f else 4f)

        // Title and Announcement Banner (skip on short landscape screens to preserve vertical space)
        val announcement = directoryDoc.announcement
        if (!compact) {
            val header = VisTable()
            header.add(VisLabel("MORGANA REALMS", ensureTheme().accentLabelStyle).apply { setFontScale(1.3f) }).left().padRight(10f)
            header.add(muted("Universal Client Hub")).left().bottom().padBottom(2f).expandX().left().row()
            win.add(header).colspan(2).left().padLeft(4f).padBottom(4f).row()
            if (!announcement.isNullOrBlank()) {
                val banner = VisLabel(announcement)
                banner.setWrap(true)
                val card = VisTable()
                card.background = ensureTheme().cardBackground
                card.add(banner).growX()
                win.add(card).colspan(2).fillX().width(contentWidth()).padBottom(6f).row()
            }
        }

        // Tab Bar (left-aligned at the top of the single tabbed pane)
        val uiTheme = ensureTheme()
        val tabTable = VisTable()
        val dirTabBtn = VisTextButton("Realm Directory", uiTheme.tabStyle).apply { setProgrammaticChangeEvents(false) }
        val directTabBtn = VisTextButton("Direct Join", uiTheme.tabStyle).apply { setProgrammaticChangeEvents(false) }
        val localTabBtn = VisTextButton("Local Worlds", uiTheme.tabStyle).apply { setProgrammaticChangeEvents(false) }

        tabTable.add(dirTabBtn).padRight(4f)
        tabTable.add(directTabBtn).padRight(4f)
        if (localWorlds != null) {
            tabTable.add(localTabBtn)
        }
        win.add(tabTable).colspan(2).left().padBottom(0f).row()
        win.add(uiTheme.divider()).colspan(2).fillX().padBottom(6f).row()

        // Single fixed-size pane shared by all tabs so switching tabs never resizes or moves the window.
        val paneTable = VisTable()
        paneTable.top().left()
        val narrowCredTable = if (narrow()) buildCredentials() else null

        fun syncTabButtons() {
            dirTabBtn.isChecked = currentTab == Tab.DIRECTORY
            directTabBtn.isChecked = currentTab == Tab.DIRECT_JOIN
            localTabBtn.isChecked = currentTab == Tab.LOCAL_WORLDS
        }

        fun selectTab(tab: Tab) {
            currentTab = tab
            syncTabButtons()
            paneTable.clearChildren()
            paneTable.clearActions()
            tabFocus = null
            val cred = if (!narrow()) buildCredentials() else null
            when (currentTab) {
                Tab.DIRECTORY -> buildDirectoryTab(paneTable, cred)
                Tab.DIRECT_JOIN -> buildDirectJoinTab(paneTable, cred)
                Tab.LOCAL_WORLDS -> buildLocalWorldsTab(paneTable, cred)
            }
            focusTab()
        }

        cycleTab = { backwards ->
            val tabs = Tab.entries.filter { it != Tab.LOCAL_WORLDS || localWorlds != null }
            val next = KeyNav.cycle(tabs.indexOf(currentTab), tabs.size, backwards)
            if (next >= 0) selectTab(tabs[next])
        }

        dirTabBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = selectTab(Tab.DIRECTORY)
        })
        directTabBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = selectTab(Tab.DIRECT_JOIN)
        })
        localTabBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = selectTab(Tab.LOCAL_WORLDS)
        })

        selectTab(currentTab)
        if (narrow() && compact) {
            val body = VisTable().apply {
                top().left()
                add(paneTable).width(contentWidth()).row()
                if (narrowCredTable != null) add(narrowCredTable).fillX().padTop(4f).row()
            }
            val bodyScroll = VisScrollPane(body).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            win.add(bodyScroll).colspan(2).width(contentWidth()).height((stage.height - 115f).coerceAtLeast(180f)).padBottom(4f).row()
        } else {
            win.add(paneTable).colspan(2).width(contentWidth()).height(paneHeight()).padBottom(if (compact) 2f else 6f).row()
            if (narrowCredTable != null) win.add(narrowCredTable).colspan(2).fillX().row()
        }
        win.add(uiTheme.divider()).colspan(2).fillX().padTop(if (compact) 2f else 4f).row()
        statusLabel.color = uiTheme.palette.textMuted
        win.add(statusLabel).colspan(2).left().padTop(2f).padLeft(4f).row()
        win.pack()
        if (win.stage != null) win.centerWindow()
    }

    /** Selected realm, username, password, class and the Enter Realm button. */
    private fun buildCredentials(): VisTable {
        val uiTheme = ensureTheme()
        val credTable = VisTable()
        credTable.background = uiTheme.cardBackground
        credTable.defaults().pad(if (shortScreen()) 2f else 3f).left()
        updateConnectingLabel()
        connectingLabel.setWrap(true)
        credTable.add(connectingLabel).colspan(2).width(260f).padBottom(2f).row()
        credTable.add(VisLabel("Username:")).width(90f)
        credTable.add(usernameField).width(160f).row()
        credTable.add(VisLabel("Password:")).width(90f)
        credTable.add(passwordField).width(160f).row()
        credTable.add(rememberPasswordBox).colspan(2).left().row()
        val enterBtn = VisTextButton("Enter Realm", uiTheme.primaryButtonStyle)
        enterBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                triggerConnect()
            }
        })
        credTable.add(enterBtn).colspan(2).fillX().padTop(4f).row()
        return credTable
    }

    /** The hub uses two columns (list/form and details/sign-in) when there is room, one column on a narrow screen. */
    private fun narrow(): Boolean = stage.width < NARROW_SCREEN

    /** True on phone-landscape / short viewports where vertical space is tight. */
    private fun shortScreen(): Boolean = stage.height < 520f

    private fun contentWidth(): Float =
        if (!narrow()) minOf(760f, stage.width - 24f) else minOf(480f, stage.width - 40f)

    private fun listHeight(): Float = when {
        narrow() -> (stage.height - 620f).coerceIn(140f, 400f)
        shortScreen() -> (stage.height - 215f).coerceIn(90f, 300f)
        else -> (stage.height - 330f).coerceIn(160f, 480f)
    }

    private fun paneHeight(): Float = when {
        narrow() -> (stage.height - 320f).coerceIn(280f, 510f)
        shortScreen() -> (stage.height - 115f).coerceIn(190f, 380f)
        else -> listHeight() + 100f
    }

    private fun updateConnectingLabel() {
        val title = activeRealm?.name ?: "Custom Server (${hostField.text}:${portField.text})"
        connectingLabel.setText("Connecting to: $title")
    }

    private fun browserModel() = RealmBrowserModel(
        directoryDoc.realms, directoryDoc.featuredRealmId, { perRealmStore.isFavorite(it) }, perRealmStore.load().recentRealms
    )

    /** A small rounded-look tag: [text] in [color] on a dark chip with a thin border. */
    private fun pill(text: String, color: Color): VisTable {
        val l = VisLabel(text)
        l.color = color
        return VisTable().apply {
            background = ensureTheme().dynamic { t, p -> t.bordered(p.background, p.border, padX = 5f, padY = 1f) }
            add(l)
        }
    }

    /** A thin bar filled to [fraction] in [color] on a dark track (the population meter). */
    private fun meter(fraction: Float, width: Float, height: Float, color: Color): VisTable {
        val th = ensureTheme()
        return VisTable().apply {
            background = th.dynamic { t, p -> t.bordered(p.background, p.border, padX = 1f, padY = 1f) }
            left()
            add(Image(th.dynamic { t, _ -> t.solid(color) })).width((width - 2f) * fraction.coerceIn(0f, 1f)).height(height - 2f)
            add().expandX()
        }.also { it.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.disabled }
    }

    /** A list heading: accent text, with a rule running out to the edge. */
    private fun sectionHeader(text: String): VisTable = VisTable().apply {
        add(VisLabel(text, ensureTheme().accentLabelStyle)).padRight(6f)
        add(ensureTheme().divider()).growX()
    }

    private fun fillColor(fraction: Float): Color {
        val p = GameTheme.shared.palette
        return when { fraction >= 0.95f -> p.danger; fraction >= 0.7f -> p.warning; else -> p.success }
    }

    private fun muted(text: String, wrapWidth: Float = 0f): VisLabel {
        val l = VisLabel(text)
        l.color = GameTheme.shared.palette.textMuted
        if (wrapWidth > 0f) { l.setWrap(true); l.width = wrapWidth }
        return l
    }

    private fun buildDirectoryTab(container: VisTable, credentials: VisTable?) {
        // Start on the realm the player used last, so the common case is one click.
        if (activeRealm == null) {
            val lastId = perRealmStore.load().recentRealms.firstOrNull()?.realmId
            directoryDoc.realms.firstOrNull { it.id.equals(lastId, ignoreCase = true) }?.let { selectRealmQuietly(it) }
        }
        val listWidth = if (narrow()) contentWidth() - 10f else 440f
        val rowWidth = listWidth - 22f // room for the scroll bar

        val searchField = VisTextField(filter.text)
        searchField.messageText = "Search realms by name, genre, or tag..."

        val favButton = VisTextButton("Favorites", ensureTheme().chipStyle)
        val fullButton = VisTextButton("Hide full", ensureTheme().chipStyle)
        favButton.isChecked = filter.favoritesOnly
        fullButton.isChecked = filter.hideFull
        val sortBox = VisSelectBox<String>()
        sortBox.setItems(*RealmSort.entries.map { it.label }.toTypedArray())
        sortBox.selectedIndex = filter.sort.ordinal

        val chips = HorizontalGroup().wrap().rowLeft().left().space(3f).wrapSpace(3f)
        val countLabel = muted("")
        val listTable = VisTable()
        listTable.top().left()
        listTable.defaults().left()
        val scrollPane = VisScrollPane(listTable)
        scrollPane.setFadeScrollBars(false)
        scrollPane.setScrollingDisabled(true, false)
        val detailsTable = VisTable()
        detailsTable.top().left()
        detailsTable.defaults().left().pad(2f)

        fun apply(change: (RealmFilter) -> RealmFilter) {
            filter = change(filter)
            refreshList()
        }

        refreshList = {
            val keepScroll = scrollPane.scrollY
            val view: RealmBrowserView = browserModel().view(filter)
            val canEnter = view.matched
            val count = if (filter.isFiltering) "$canEnter of ${view.total} realms match" else "${view.total} realms"
            countLabel.setText(if (directoryNote.isBlank()) count else "$count - $directoryNote")

            chips.clearChildren()
            for (facet in view.facets) {
                val chip = VisTextButton("${facet.tag} (${facet.count})", ensureTheme().chipStyle)
                chip.isChecked = facet.selected
                chip.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        apply { f -> f.copy(tags = if (facet.selected) f.tags.filterNot { it.equals(facet.tag, true) }.toSet() else f.tags + facet.tag) }
                    }
                })
                chips.addActor(chip)
            }

            listTable.clearChildren()
            if (view.shown == 0) {
                listTable.add(muted(EmptyStates.realms(filter.isFiltering), rowWidth - 16f)).width(rowWidth - 16f).pad(10f).row()
                if (filter.isFiltering) {
                    val clear = VisTextButton("Clear filters")
                    clear.addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: Actor?) {
                            filter = RealmFilter(sort = filter.sort)
                            searchField.text = ""
                            favButton.isChecked = false
                            fullButton.isChecked = false
                            refreshList()
                        }
                    })
                    listTable.add(clear).pad(4f).row()
                }
            }
            for (section in view.sections) {
                if (view.sections.size > 1 || section.title != "Results") {
                    listTable.add(sectionHeader("${section.title} (${section.rows.size})")).width(rowWidth).padTop(8f).padBottom(4f).row()
                }
                for (row in section.rows) listTable.add(buildRealmRow(row, rowWidth)).width(rowWidth).padBottom(2f).row()
            }
            if (view.hidden > 0) {
                listTable.add(muted("${view.hidden} more realms. Type in the search box or pick a tag to narrow the list.", rowWidth - 16f))
                    .width(rowWidth - 16f).pad(8f).row()
            }
            scrollPane.layout()
            scrollPane.scrollY = keepScroll
        }

        refreshDetails = { fillDetails(detailsTable, if (narrow()) contentWidth() - 20f else 290f, ::refreshListAndDetails) }

        searchField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { apply { it.copy(text = searchField.text) } }
        })
        favButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { apply { it.copy(favoritesOnly = favButton.isChecked) } }
        })
        fullButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { apply { it.copy(hideFull = fullButton.isChecked) } }
        })
        sortBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { apply { it.copy(sort = RealmSort.entries[sortBox.selectedIndex]) } }
        })

        searchField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                val delta = when (keycode) { Input.Keys.DOWN -> 1; Input.Keys.UP -> -1; else -> return false }
                val next = RealmBrowserModel.step(browserModel().view(filter), activeRealm?.id, delta) ?: return true
                selectRealm(next)
                scrollSelectedIntoView(scrollPane, listTable)
                return true
            }
        })
        searchField.setTextFieldListener { _, c ->
            if (c == '\r' || c == '\n') {
                val realm = activeRealm ?: RealmBrowserModel.step(browserModel().view(filter), null, 1)
                if (realm != null) {
                    if (activeRealm?.id != realm.id) selectRealm(realm)
                    if (realm.engineApi <= SUPPORTED_ENGINE_API) triggerConnect()
                }
            }
        }

        tabFocus = searchField
        refreshList()
        refreshDetails()

        val left = VisTable()
        left.defaults().left()
        left.add(searchField).fillX().padBottom(4f).row()
        val filterRow = VisTable()
        filterRow.add(favButton).padRight(4f)
        filterRow.add(fullButton).padRight(8f)
        filterRow.add(VisLabel("Sort:")).padRight(4f)
        filterRow.add(sortBox).width(130f).padRight(8f)
        val refreshButton = VisTextButton("Refresh", ensureTheme().chipStyle)
        refreshButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { refreshDirectory() }
        })
        filterRow.add(refreshButton)
        left.add(filterRow).padBottom(4f).row()
        if (chips.children.size > 0) left.add(chips).width(listWidth).padBottom(4f).row()
        left.add(countLabel).padBottom(2f).row()
        if (narrow()) {
            left.add(scrollPane).width(listWidth).expandY().fillY().minHeight(100f).row()
            container.add(left).expandY().fillY().row()
            container.add(VisScrollPane(detailsTable).also { it.setFadeScrollBars(false); it.setScrollingDisabled(true, false) }).width(contentWidth() - 10f).height(120f).row()
        } else {
            left.add(scrollPane).width(listWidth).expandY().fillY().minHeight(if (shortScreen()) 60f else 140f).row()
            container.add(left).top().left().width(listWidth).height(paneHeight()).padRight(10f)
            val right = VisTable()
            right.top().left()
            val detailsScroll = VisScrollPane(detailsTable).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            right.add(detailsScroll).top().left().width(300f).expandY().fillY().row()
            if (credentials != null) right.add(credentials).left().bottom().width(300f).padTop(4f).row()
            container.add(right).top().left().width(300f).height(paneHeight())
        }
    }

    /** Brings the selected row into view after the arrow keys moved the selection. */
    private fun scrollSelectedIntoView(pane: VisScrollPane, list: VisTable) {
        val id = activeRealm?.id ?: return
        list.validate()
        val row = list.children.firstOrNull { it.userObject == id } ?: return
        pane.scrollTo(0f, row.y, row.width, row.height)
    }

    private fun refreshListAndDetails() {
        refreshList()
        refreshDetails()
    }

    /** One realm in the list: favourite star, name, a tag line and a short description, and the population on the right. */
    private fun buildRealmRow(row: RealmRow, width: Float): Actor {
        val realm = row.realm
        val selected = activeRealm?.id == realm.id
        val compatible = row.compatible(SUPPORTED_ENGINE_API)
        val card = VisTable()
        card.userObject = realm.id
        card.defaults().left()
        card.background = if (selected) ensureTheme().selectedCardBackground else ensureTheme().cardBackground

        val star = VisImageButton(starDrawable(row.favorite))
        star.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                perRealmStore.toggleFavorite(realm.id)
                refreshListAndDetails()
            }
        })

        val name = VisLabel(realm.name)
        if (selected) name.color = GameTheme.shared.palette.accent
        if (!compatible) name.color = GameTheme.shared.palette.danger
        val population = VisLabel(
            when {
                !compatible -> "needs newer client"
                row.full -> "FULL"
                else -> "${realm.onlinePlayers}/${realm.maxPlayers}"
            }
        )
        population.color = if (row.full || !compatible) GameTheme.shared.palette.warning else GameTheme.shared.palette.textMuted
        val bar = meter(row.fillFraction, 72f, 8f, if (compatible) fillColor(row.fillFraction) else GameTheme.shared.palette.danger)

        val textWidth = width - 34f - 100f - 16f
        name.setEllipsis(true)

        val text = VisTable()
        text.defaults().left()
        text.add(name).width(textWidth).left().row()
        val pills = HorizontalGroup().space(4f).left()
        if (!realm.tls) pills.addActor(pill("unencrypted", GameTheme.shared.palette.warning))
        for (tag in realm.tags.take(if (realm.tls) 3 else 2)) pills.addActor(pill(tag, GameTheme.shared.palette.textMuted))
        if (pills.children.size > 0) text.add(pills).width(textWidth).left().padTop(2f).row()

        val right = VisTable()
        right.add(population).right().row()
        right.add(bar).width(72f).height(8f).padTop(3f).row()

        card.add(star).size(26f).top().padRight(4f).padLeft(2f).padTop(3f)
        card.add(text).width(textWidth).left().pad(3f)
        card.add(right).width(88f).top().right().pad(3f).padRight(6f)

        // One click selects; a double click selects and enters.
        card.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                var t: Actor? = event?.target
                while (t != null && t !== card) { if (t === star) return; t = t.parent }
                if (tapCount >= 2 && compatible) {
                    selectRealm(realm)
                    triggerConnect()
                } else {
                    selectRealm(realm)
                }
            }
        })
        return card
    }

    /** The right-hand pane: everything the list leaves out about the selected realm. */
    private fun fillDetails(table: VisTable, width: Float, onChanged: () -> Unit) {
        table.clearChildren()
        val realm = activeRealm
        if (realm == null) {
            table.add(muted("Select a realm to see its details.\nDouble-click a realm to enter it.", width)).width(width).pad(6f).row()
            return
        }
        val favorite = perRealmStore.isFavorite(realm.id)
        val lastPlayed = perRealmStore.load().recentRealms.firstOrNull { it.realmId.equals(realm.id, ignoreCase = true) }?.lastPlayedEpochMs
        val compatible = realm.engineApi <= SUPPORTED_ENGINE_API

        val head = VisTable()
        head.background = ensureTheme().selectedCardBackground
        head.defaults().left()
        head.add(VisLabel(realm.name, ensureTheme().accentLabelStyle).apply { setFontScale(1.2f); setWrap(true) }).width(width - 24f).row()
        if (realm.gameTitle.isNotBlank() && !realm.gameTitle.equals(realm.name, ignoreCase = true)) head.add(muted(realm.gameTitle)).row()
        val players = if (realm.maxPlayers > 0) "${realm.onlinePlayers} / ${realm.maxPlayers} players" else "${realm.onlinePlayers} players"
        head.add(VisLabel(players + if (realm.maxPlayers > 0 && realm.onlinePlayers >= realm.maxPlayers) "  (full)" else "")).padTop(4f).row()
        if (realm.maxPlayers > 0) {
            val f = realm.onlinePlayers.toFloat() / realm.maxPlayers
            head.add(meter(f, width - 24f, 8f, fillColor(f))).width(width - 24f).height(8f).padTop(3f).row()
        }
        table.add(head).width(width).padBottom(2f).row()
        if (realm.tags.isNotEmpty()) {
            val pills = HorizontalGroup().wrap().rowLeft().left().space(4f).wrapSpace(4f)
            realm.tags.forEach { pills.addActor(pill(it, GameTheme.shared.palette.textMuted)) }
            table.add(pills).width(width).padTop(6f).row()
        }
        if (realm.description.isNotBlank()) {
            val desc = VisLabel(realm.description)
            desc.setWrap(true)
            table.add(desc).width(width).padTop(6f).row()
        }
        if (realm.classes.isNotEmpty()) table.add(muted("Classes: " + realm.classes.joinToString(", ") { it.name }, width)).width(width).padTop(6f).row()
        table.add(muted("Address: ${realm.host}:${realm.port}", width)).width(width).padTop(6f).row()
        val security = VisLabel(if (realm.tls) "Encrypted connection" else "Not encrypted: your password is sent as plain text")
        security.setWrap(true)
        security.color = if (realm.tls) GameTheme.shared.palette.success else GameTheme.shared.palette.warning
        table.add(security).width(width).row()
        if (!compatible) {
            val warn = VisLabel("This realm needs a newer client (engine API ${realm.engineApi}).")
            warn.setWrap(true); warn.color = GameTheme.shared.palette.danger
            table.add(warn).width(width).row()
        }
        if (lastPlayed != null) table.add(muted("You last played here " + RealmBrowserModel.agoText(System.currentTimeMillis(), lastPlayed))).row()
        if (realm.websiteUrl.isNotBlank()) table.add(muted(realm.websiteUrl, width)).width(width).row()

        val actions = VisTable()
        val favButton = VisTextButton(if (favorite) "Remove favorite" else "Add to favorites")
        favButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                perRealmStore.toggleFavorite(realm.id)
                onChanged()
            }
        })
        val copyButton = VisTextButton("Copy invite")
        copyButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                Gdx.app.clipboard.contents = realm.toInviteCode()
                statusLabel.setText("Invite code for ${realm.name} copied to the clipboard.")
            }
        })
        actions.add(favButton).padRight(4f)
        actions.add(copyButton)
        table.add(actions).padTop(8f).row()
    }

    private fun buildDirectJoinTab(container: VisTable, credentials: VisTable?) {
        val wide = !narrow()
        val formWidth = if (wide) 440f else contentWidth() - 10f
        val left = VisTable()
        left.top().left()
        left.defaults().left().pad(3f)

        val inviteField = VisTextField("")
        tabFocus = inviteField
        inviteField.messageText = "Paste invite code (morgana://...) or address"

        val applyInviteBtn = VisTextButton("Use This")
        fun applyInvite() {
            val text = inviteField.text.trim()
            if (text.startsWith("morgana://", ignoreCase = true)) {
                val summary = RealmSummary.fromInviteCode(text)
                if (summary != null) {
                    selectRealm(summary)
                    hostField.text = summary.host
                    portField.text = summary.port.toString()
                    statusLabel.setText("Loaded realm invite: ${summary.name} (${summary.host}:${summary.port})")
                } else {
                    statusLabel.setText("That invite code is not valid.")
                }
                return
            }
            val parsed = ServerAddress.parse(text)
            if (parsed == null) {
                statusLabel.setText("Enter a server address like play.example.com:7001, or paste a morgana:// invite.")
                return
            }
            hostField.text = parsed.host
            if (parsed.port != null) portField.text = parsed.port.toString()
            activeRealm = null
            statusLabel.setText("Server set to ${hostField.text}:${portField.text}")
            updateConnectingLabel()
        }
        applyInviteBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) = applyInvite()
        })
        inviteField.setTextFieldListener { _, c -> if (c == '\r' || c == '\n') applyInvite() }

        val fieldWidth = if (wide) 220f else (formWidth - 200f).coerceAtLeast(150f)
        val inviteRow = VisTable()
        inviteRow.add(VisLabel("Invite Link / Host:")).width(130f)
        inviteRow.add(inviteField).width(fieldWidth).padRight(4f)
        inviteRow.add(applyInviteBtn).row()
        left.add(inviteRow).colspan(2).row()

        left.add(VisLabel("Server Host:")).width(130f)
        left.add(hostField).width(fieldWidth).row()

        left.add(VisLabel("Server Port:")).width(130f)
        left.add(portField).width(fieldWidth).row()
        left.add(tlsBox).colspan(2).row()

        // Servers played before, one click to fill the address back in.
        val recent = perRealmStore.load().recentRealms.take(5)
        if (recent.isNotEmpty()) {
            left.add(VisLabel("Recent servers:")).colspan(2).padTop(8f).row()
            for (r in recent) {
                val b = VisTextButton("${r.name}  (${r.host}:${r.port})")
                b.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        hostField.text = r.host
                        portField.text = r.port.toString()
                        tlsBox.isChecked = r.tls
                        activeRealm = directoryDoc.realms.firstOrNull { it.id.equals(r.realmId, ignoreCase = true) }
                        statusLabel.setText("Server set to ${r.host}:${r.port}")
                        updateConnectingLabel()
                    }
                })
                left.add(b).colspan(2).fillX().padBottom(2f).row()
            }
        }

        if (wide) {
            val leftScroll = VisScrollPane(left).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            container.add(leftScroll).top().left().width(formWidth).height(paneHeight()).padRight(10f)
            val right = VisTable()
            right.top().left()
            val info = VisTable()
            info.top().left()
            info.defaults().left().pad(2f)
            info.add(VisLabel("Direct Connection")).row()
            info.add(muted("Join any Morgana realm by host name, IP address, or a morgana:// invite link.", 280f)).width(280f).padTop(4f).row()
            info.add(muted("Invite links automatically configure the host, port, and TLS certificate fingerprint.", 280f)).width(280f).padTop(4f).row()
            val infoScroll = VisScrollPane(info).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            right.add(infoScroll).top().left().width(300f).expandY().fillY().row()
            if (credentials != null) right.add(credentials).left().bottom().width(300f).padTop(4f).row()
            container.add(right).top().left().width(300f).height(paneHeight())
        } else {
            val scroll = VisScrollPane(left).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            container.add(scroll).top().left().width(contentWidth() - 4f).expandY().fillY().row()
        }
    }

    private enum class WorldMode { VIEW, RENAME, CONFIRM_DELETE }

    /**
     * The Local Worlds tab: the worlds on this machine as a list, a details pane with what you can do to the selected one
     * (play, share, stop hosting, rename, delete, open its folder), and a line saying what is being hosted right now.
     */
    private fun buildLocalWorldsTab(container: VisTable, credentials: VisTable?) {
        val host = localWorlds
        if (host == null) {
            container.add(VisLabel("Local world hosting is not supported in this client environment.")).row()
            return
        }
        val wide = !narrow()
        val listWidth = if (wide) 440f else contentWidth() - 10f
        val detailWidth = if (wide) 300f else contentWidth() - 10f

        var worlds = host.worlds()
        var selected = host.selectedSlug.takeIf { s -> worlds.any { it.slug == s } } ?: worlds.firstOrNull()?.slug ?: ""
        var mode = WorldMode.VIEW
        var lastHostingSlug: String? = host.hostingStatus()?.slug
        var refreshAll: () -> Unit = {}

        val hostingLabel = VisLabel("").apply { setWrap(true) }
        val friendsLabel = muted("", listWidth - 6f)
        fun refreshStatus() {
            val s = host.hostingStatus()
            hostingLabel.setText(LocalWorldsModel.hostingLine(s))
            friendsLabel.setText(LocalWorldsModel.friendsLine(s))
        }

        val listTable = VisTable()
        listTable.top().left()
        listTable.defaults().left()
        val scroll = VisScrollPane(listTable)
        scroll.setFadeScrollBars(false)
        scroll.setScrollingDisabled(true, false)
        val detailsTable = VisTable()
        detailsTable.top().left()
        detailsTable.defaults().left().pad(2f)

        fun detailsFor(slug: String): LocalWorldDetails {
            val choice = worlds.firstOrNull { it.slug == slug }
            return host.details(slug) ?: LocalWorldDetails(slug, choice?.name ?: slug, origin = "", folder = "", isDefault = slug == "default")
        }

        fun selectWorld(slug: String) {
            selected = slug
            mode = WorldMode.VIEW
            host.selectedSlug = slug
            // Playing a local world means connecting to this machine, so point the sign-in at it.
            hostField.text = "127.0.0.1"
            portField.text = defaultPort.toString()
            tlsBox.isChecked = false
            activeRealm = null
            updateConnectingLabel()
            refreshAll()
        }

        fun play() {
            host.selectedSlug = selected
            hostField.text = "127.0.0.1"
            portField.text = defaultPort.toString()
            tlsBox.isChecked = false
            activeRealm = null
            updateConnectingLabel()
            triggerConnect()
        }

        fun button(text: String, action: () -> Unit): VisTextButton {
            val b = VisTextButton(text)
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = action()
            })
            return b
        }

        // Keyboard: Up/Down move through the worlds, Enter plays the selected one.
        tabFocus = scroll
        scroll.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                when (keycode) {
                    Input.Keys.UP, Input.Keys.DOWN -> {
                        if (worlds.isEmpty()) return true
                        val at = worlds.indexOfFirst { it.slug == selected }
                        val next = KeyNav.step(at, worlds.size, if (keycode == Input.Keys.DOWN) 1 else -1)
                        if (next != at) {
                            selectWorld(worlds[next].slug)
                            listTable.validate()
                            listTable.children.firstOrNull { it.userObject == worlds[next].slug }?.let { scroll.scrollTo(0f, it.y, it.width, it.height) }
                        }
                        return true
                    }
                    Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> {
                        if (selected.isNotEmpty()) play()
                        return true
                    }
                }
                return false
            }
        })

        fun fillList() {
            val keep = scroll.scrollY
            listTable.clearChildren()
            val hosting = host.hostingStatus()?.slug
            val now = System.currentTimeMillis()
            if (worlds.isEmpty()) listTable.add(muted(EmptyStates.LOCAL_WORLDS, listWidth - 40f)).width(listWidth - 40f).pad(10f).row()
            for (w in worlds) {
                val d = detailsFor(w.slug)
                val isSel = w.slug == selected
                val card = VisTable().also { it.userObject = w.slug }
                card.defaults().left()
                card.background = if (isSel) ensureTheme().selectedCardBackground else ensureTheme().cardBackground
                val name = VisLabel(w.name)
                name.setEllipsis(true)
                card.add(name).width(listWidth - 40f).row()
                val sub = muted(LocalWorldsModel.subtitle(d, now, w.slug == hosting))
                sub.setEllipsis(true)
                card.add(sub).width(listWidth - 40f)
                card.addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) = selectWorld(w.slug)
                })
                listTable.add(card).width(listWidth - 22f).pad(2f).row()
            }
            scroll.layout()
            scroll.scrollY = keep
        }

        fun fillDetails() {
            detailsTable.clearChildren()
            if (selected.isEmpty()) {
                detailsTable.add(muted("No worlds yet. Create one with the New Realm Wizard.")).row()
                return
            }
            val d = detailsFor(selected)
            val status = host.hostingStatus()
            val isHosting = status?.slug == d.slug
            val textWidth = detailWidth - 20f
            detailsTable.add(VisLabel(d.name).also { it.setEllipsis(true) }).width(textWidth).row()
            detailsTable.add(muted("${LocalWorldsModel.originText(d.origin)}" +
                (if (d.mapCount > 0) " - ${d.mapCount} maps" else "") +
                (if (d.classCount > 0) " - ${d.classCount} classes" else ""), textWidth)).width(textWidth).row()
            detailsTable.add(muted(LocalWorldsModel.featuresText(d), textWidth)).width(textWidth).row()
            if (isHosting) detailsTable.add(VisLabel("Hosting now").also { it.color = GameTheme.shared.palette.success }).row()
            if (d.folder.isNotEmpty()) detailsTable.add(muted(d.folder, textWidth).also { it.setEllipsis(true) }).width(textWidth).padBottom(6f).row()

            when (mode) {
                WorldMode.RENAME -> {
                    val nameField = VisTextField(d.name)
                    fun save() {
                        try {
                            val renamed = host.renameWorld(d.slug, nameField.text)
                            worlds = host.worlds()
                            mode = WorldMode.VIEW
                            statusLabel.setText("Renamed to '${renamed.name}'.")
                            refreshAll()
                        } catch (e: Exception) {
                            statusLabel.setText(e.message ?: "Could not rename the world.")
                        }
                    }
                    nameField.setTextFieldListener { _, c -> if (c == '\r' || c == '\n') save() }
                    detailsTable.add(VisLabel("New name:")).row()
                    detailsTable.add(nameField).width(textWidth).row()
                    val row = VisTable()
                    row.add(button("Save") { save() }).padRight(4f)
                    row.add(button("Cancel") { mode = WorldMode.VIEW; refreshAll() })
                    detailsTable.add(row).padTop(4f).row()
                    stage.keyboardFocus = nameField
                }
                WorldMode.CONFIRM_DELETE -> {
                    detailsTable.add(muted(LocalWorldsModel.deleteWarning(d, isHosting), textWidth).also { it.color = GameTheme.shared.palette.danger })
                        .width(textWidth).padTop(4f).row()
                    val row = VisTable()
                    row.add(button("Yes, delete it forever") {
                        try {
                            host.deleteWorld(d.slug)
                            worlds = host.worlds()
                            selected = host.selectedSlug.takeIf { s -> worlds.any { it.slug == s } } ?: worlds.firstOrNull()?.slug ?: ""
                            mode = WorldMode.VIEW
                            statusLabel.setText("Deleted '${d.name}'.")
                            refreshAll()
                        } catch (e: Exception) {
                            statusLabel.setText(e.message ?: "Could not delete the world.")
                        }
                    }).padRight(4f)
                    row.add(button("Keep it") { mode = WorldMode.VIEW; refreshAll() })
                    detailsTable.add(row).padTop(6f).row()
                }
                WorldMode.VIEW -> {
                    if (status != null && !isHosting && status.onlinePlayers > 0) {
                        detailsTable.add(muted("Playing this stops ${status.name}, where ${status.onlinePlayers} player(s) are online.", textWidth)
                            .also { it.color = GameTheme.shared.palette.warning }).width(textWidth).row()
                    }
                    val main = VisTable()
                    main.add(button("Play") { play() }).padRight(4f).fillX()
                    main.add(button("Copy Invite Code") {
                        val publicHost = if (ConnectionPlanner.isLoopbackHost(hostField.text)) "" else hostField.text
                        val code = try { host.inviteCode(publicHost) } catch (e: Exception) { null }
                        if (code == null) {
                            statusLabel.setText("World cannot be joined remotely (encryption is disabled).")
                        } else {
                            Gdx.app.clipboard.contents = code
                            statusLabel.setText("Invite code for '${d.name}' copied to the clipboard.")
                        }
                        refreshStatus()
                        if (host.hostingStatus()?.slug != lastHostingSlug) { lastHostingSlug = host.hostingStatus()?.slug; refreshAll() }
                    })
                    detailsTable.add(main).padTop(4f).row()
                    val more = HorizontalGroup().wrap().left().space(4f).wrapSpace(4f)
                    if (isHosting) more.addActor(button("Stop hosting") {
                        host.stopHosting()
                        statusLabel.setText("Stopped hosting '${d.name}'. Everything was saved.")
                        refreshAll()
                    })
                    if (host.canManageWorlds) {
                        more.addActor(button("Open folder") {
                            if (!host.openFolder(d.slug)) statusLabel.setText("Could not open the folder. It is at ${d.folder}")
                        })
                        if (!d.isDefault) {
                            more.addActor(button("Rename") { mode = WorldMode.RENAME; refreshAll() })
                            more.addActor(button("Delete...") { mode = WorldMode.CONFIRM_DELETE; refreshAll() })
                        }
                    }
                    if (more.children.size > 0) detailsTable.add(more).width(textWidth).padTop(4f).row()
                }
            }
        }

        refreshAll = {
            fillList()
            fillDetails()
            refreshStatus()
            lastHostingSlug = host.hostingStatus()?.slug
        }

        // Left column: hosting status, the list, and the ways to make a world.
        val left = VisTable()
        left.top().left()
        left.defaults().left()
        left.add(hostingLabel).width(listWidth).row()
        left.add(friendsLabel).width(listWidth).padBottom(4f).row()
        left.add(scroll).width(listWidth).expandY().fillY().minHeight(if (wide) 140f else 80f).row()
        if (host.realmTemplates.isNotEmpty()) {
            // The wizard picks the starting content and which optional systems are on.
            left.add(button("New Realm Wizard...") {
                NewRealmWizardWindow(stage, host) { created ->
                    worlds = host.worlds()
                    selectWorld(created.slug)
                    statusLabel.setText("Created realm '${created.name}'. Press Play to start it.")
                }.show()
            }).width(listWidth).padTop(4f).row()
        } else {
            val newWorldField = VisTextField("My World")
            val row = VisTable()
            row.add(newWorldField).width(listWidth - 110f).padRight(4f)
            row.add(button("Create World") {
                try {
                    val created = host.createWorld(newWorldField.text)
                    worlds = host.worlds()
                    selectWorld(created.slug)
                    statusLabel.setText("Created world '${created.name}'!")
                } catch (e: Exception) {
                    statusLabel.setText("Failed to create world: ${e.message}")
                }
            })
            left.add(row).padTop(4f).row()
        }

        val detailsScroll = VisScrollPane(detailsTable).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(true, false)
        }
        if (wide) {
            container.add(left).top().left().width(listWidth).height(paneHeight()).padRight(10f)
            val right = VisTable()
            right.top().left()
            right.add(detailsScroll).top().left().width(detailWidth).expandY().fillY().row()
            if (credentials != null) right.add(credentials).left().bottom().width(detailWidth).padTop(6f).row()
            container.add(right).top().left().width(detailWidth).height(paneHeight())
        } else {
            container.add(left).width(listWidth).expandY().fillY().row()
            container.add(detailsScroll).width(detailWidth).height(195f).padTop(6f).row()
        }
        refreshAll()

        // Player counts and hosting change while the tab is open (a friend joins, a server stops): keep the status line fresh.
        container.addAction(Actions.forever(Actions.sequence(Actions.delay(2f), Actions.run {
            refreshStatus()
            if (host.hostingStatus()?.slug != lastHostingSlug) refreshAll()
        })))
    }

    internal fun selectRealmForTest(realm: RealmSummary) = selectRealm(realm)

    /** Selects without touching any widgets that may not exist yet (used while the directory tab is being built). */
    private fun selectRealmQuietly(realm: RealmSummary) {
        activeRealm = realm
        hostField.text = realm.host
        portField.text = realm.port.toString()
        tlsBox.isChecked = realm.tls
        updateConnectingLabel()
    }

    private fun selectRealm(realm: RealmSummary) {
        selectRealmQuietly(realm)
        statusLabel.setText("Selected realm: ${realm.name}")
        if (currentTab == Tab.DIRECTORY) refreshListAndDetails()
    }

    private fun triggerConnect() {
        val host = hostField.text.trim()
        ServerAddress.portError(portField.text)?.let { statusLabel.setText(it); return }
        if (ServerAddress.parse(host)?.port != null || ServerAddress.parse(host) == null) {
            statusLabel.setText("Enter the server address in the host box (a name or IP address, without the port).")
            return
        }
        val port = portField.text.trim().toInt()
        val tls = tlsBox.isChecked
        val user = usernameField.text.trim()
        val pass = passwordField.text
        val classId = 1

        if (user.isBlank()) {
            statusLabel.setText("Please enter a username.")
            return
        }

        perRealmStore.setRememberedCredentials(user, pass, rememberPasswordBox.isChecked)
        val realm = activeRealm
        perRealmStore.recordRealmVisit(
            realmId = realm?.id ?: "$host:$port",
            name = realm?.name ?: "$host:$port",
            host = host,
            port = port,
            tls = tls,
            tags = realm?.tags ?: emptyList()
        )

        onConnectRequest(host, port, tls, user, pass, classId, activeRealm)
    }
}

/** The engine API this client understands; realms that need a newer one are marked instead of failing at login. */
private const val SUPPORTED_ENGINE_API = 1

/** Below this stage width the realm browser stacks the list and the details instead of showing them side by side. */
private const val NARROW_SCREEN = 760f

private val STAR_GOLD get() = GameTheme.shared.palette.accent
private val STAR_DIM get() = GameTheme.shared.palette.textDisabled
