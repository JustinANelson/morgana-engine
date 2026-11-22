package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.events.EventEditSession
import com.morgana.client.editor.events.EventForms
import com.morgana.client.editor.forms.FormRules
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage

/**
 * The Events editor: the list of events on the left, the event's and the current page's settings in the middle, and on the right
 * the page's steps via [StepTreePane]. All rules live in [EventEditSession] and [EventForms]; this class only draws them.
 *
 * Nothing reaches the server until Save. Typed values that are not valid yet (a half-typed number) stay in the boxes and are
 * listed as problems; the event itself only ever takes valid values.
 */
class EventsPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val save: (EventDefinition) -> Unit,
    private val testCommands: (Int, List<EventCommand>) -> Unit = { _, _ -> },
    private val onRequestOpen: () -> Unit = {}
) {
    val content: Actor

    // ---- state ----
    private var session: EventEditSession? = null
    private var isNew = false
    private var pendingSwitch: Int? = null
    private var shownList: List<EventDefinition>? = null
    private var shownCurrentId = -1
    private var filter = ""
    private var pendingSave: EventDefinition? = null
    private var pendingSince = 0L
    private var rejected = false
    private var rejectionReason = ""
    private val typedEvent = linkedMapOf<String, String>()
    private val typedPage = linkedMapOf<String, String>()
    private var views: List<FormFieldsView> = emptyList()

    // ---- widgets ----
    private val listRows = VisTable()
    private val settings = VisTable()
    private val pageNote = theme.muted("")
    private val usedBy = theme.muted("")
    private val problems = VisLabel("", theme.warningLabel)
    private val status = theme.muted("")
    private val saveButton = theme.textButton("Save event") { saveCurrent() }
    private val testButton = theme.textButton("Test from here") { testFromHere() }
    private val undoButton = theme.textButton("Undo") { session?.undo(); rebuildAll() }
    private val redoButton = theme.textButton("Redo") { session?.redo(); rebuildAll() }
    private val stepPane = StepTreePane(stage, theme, assets, worldState, { session }) { refreshState() }
    private var pageButtons: List<VisTextButton> = emptyList()
    private var deletePageButton: VisTextButton? = null
    private var earlierButton: VisTextButton? = null
    private var laterButton: VisTextButton? = null

    init {
        for (l in listOf(pageNote, usedBy, problems, status)) l.setWrap(true)

        val search = VisTextField()
        search.messageText = "Search"
        search.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                filter = search.text.trim().lowercase()
                shownList = null
            }
        })
        listRows.top()
        listRows.defaults().left().growX().pad(1f)
        val left = VisTable()
        left.defaults().growX().pad(2f)
        left.add(search).row()
        left.add(verticalScrollPane(stage, listRows)).grow().row()
        left.add(theme.textButton("New event") { startNew() }).row()

        settings.top()
        settings.defaults().left().growX().pad(2f)

        val footer = VisTable()
        footer.defaults().growX().pad(2f)
        footer.add(problems).minWidth(0f).row()
        footer.add(status).minWidth(0f).row()
        val buttons = VisTable()
        buttons.add(saveButton).growX().padRight(4f)
        buttons.add(testButton).padRight(4f)
        buttons.add(undoButton).padRight(4f)
        buttons.add(redoButton)
        footer.add(buttons).row()

        val columns = VisTable()
        columns.add(verticalScrollPane(stage, settings)).width(290f).growY().padRight(6f)
        columns.add(stepPane.content).grow()

        val detail = VisTable()
        detail.add(columns).grow().row()
        detail.add(footer).growX()

        val root = LiveTable { follow() }
        root.add(left).width(160f).growY().padRight(4f)
        root.add(detail).grow()
        content = root
    }

    // ---- following the catalog ----

    private fun follow() {
        val all = worldState.catalog.events.sortedBy { it.id }
        val s = session
        val currentId = s?.draft?.id ?: -1
        checkPendingSave(all)
        if (all != shownList || currentId != shownCurrentId) {
            shownList = all
            shownCurrentId = currentId
            rebuildList(all, currentId)
            val fresh = all.firstOrNull { it.id == currentId }
            // The catalog changed under us (another editor, a hot reload): refresh a clean editor from it.
            if (s != null && fresh != null && pendingSave == null && !s.isDirty && !isNew && s.saved != EventEditSession(fresh).saved) load(fresh, newEntry = false)
            if (isNew && fresh != null) isNew = false
        }
        if (session == null && all.isNotEmpty()) load(all.first(), newEntry = false)
        refreshState()
    }

    private fun checkPendingSave(all: List<EventDefinition>) {
        val sent = pendingSave ?: return
        val fresh = all.firstOrNull { it.id == sent.id }
        if (fresh == sent) {
            pendingSave = null
            rejected = false
            isNew = false
            session?.takeIf { it.draft.id == sent.id }?.markSaved(fresh)
            return
        }
        val refusal = worldState.lastSaveRejection?.takeIf { it.isFor("event", sent.id) && worldState.lastSaveRejectionAt >= pendingSince }
        if (refusal != null || System.currentTimeMillis() - pendingSince > CONFIRM_MILLIS) {
            pendingSave = null
            rejected = true
            rejectionReason = refusal?.reason.orEmpty()
        }
    }

    private fun rebuildList(all: List<EventDefinition>, currentId: Int) {
        listRows.clearChildren()
        val shown = all.filter { filter.isEmpty() || it.name.lowercase().contains(filter) || it.id.toString() == filter }
        if (shown.isEmpty() && !isNew) listRows.add(theme.muted(if (all.isEmpty()) "None yet" else "No match")).row()
        for (e in shown) listRows.add(theme.listButton("${e.id}: ${e.name}", e.id == currentId) { select(e) }).growX().minWidth(0f).row()
        val s = session
        if (isNew && s != null && all.none { it.id == currentId }) listRows.add(theme.listButton("${s.draft.id}: ${s.draft.name} (new)", true) {}).growX().minWidth(0f).row()
    }

    // ---- selecting and loading ----

    private fun unsaved(): Boolean = session?.isDirty == true || isNew

    private fun select(e: EventDefinition) {
        if (e.id == session?.draft?.id) return
        if (unsaved() && pendingSwitch != e.id) {
            pendingSwitch = e.id
            return
        }
        load(e, newEntry = false)
    }

    private fun startNew(mapId: Int = 0, tileX: Int = 0, tileY: Int = 0) {
        if (unsaved() && pendingSwitch != NEW) {
            pendingSwitch = NEW
            return
        }
        val id = (worldState.catalog.events.maxOfOrNull { it.id } ?: 0) + 1
        val starter = EventPage(commands = listOf(EventCommand.ShowDialogue("", "Hello!")))
        load(EventDefinition(id = id, name = "New event $id", mapId = mapId, tileX = tileX, tileY = tileY, pages = listOf(starter)), newEntry = true)
    }

    private fun load(e: EventDefinition, newEntry: Boolean) {
        pendingSwitch = null
        rejected = false
        isNew = newEntry
        session = EventEditSession(e, if (newEntry) null else e)
        shownCurrentId = -2
        rebuildAll()
    }

    /**
     * Handles a map click when the Event tool is active:
     * - Left-click on an event marker selects that event in the panel.
     * - Left-click on an empty tile moves the selected event there (or places a new event if none is selected).
     * - Right-click on an event marker unplaces it (`mapId = 0, tileX = 0, tileY = 0`).
     * - Right-click on an empty tile starts a new event at that tile.
     */
    fun handleMapTileClick(mapId: Int, tileX: Int, tileY: Int, secondary: Boolean) {
        val hit = EventEditSession.findAtTile(
            worldState.catalog.events.sortedBy { it.id },
            session?.draft,
            isNew,
            mapId,
            tileX,
            tileY
        )

        if (!secondary) {
            if (hit != null) {
                if (hit.id != session?.draft?.id) select(hit)
                onRequestOpen()
            } else if (session != null) {
                session!!.moveToTile(mapId, tileX, tileY)
                rebuildAll()
                onRequestOpen()
            } else {
                startNew(mapId, tileX, tileY)
                onRequestOpen()
            }
        } else {
            if (hit != null) {
                if (hit.id != session?.draft?.id) select(hit)
                if (session?.draft?.id == hit.id) {
                    session!!.moveToTile(0, 0, 0)
                    rebuildAll()
                }
                onRequestOpen()
            } else {
                startNew(mapId, tileX, tileY)
                onRequestOpen()
            }
        }
    }

    // ---- building ----

    private fun rebuildAll() {
        worldState.editingEventDraft = session?.draft
        settings.clearChildren()
        val s = session
        if (s == null) {
            views = emptyList()
            stepPane.rebuild()
            return
        }
        typedEvent.clear(); typedEvent.putAll(EventForms.eventValues(s.draft))
        typedPage.clear(); typedPage.putAll(EventForms.pageValues(s.page))
        val eventView = FormFieldsView(stage, theme, assets, worldState, typedEvent) { typedSettings() }
        val pageView = FormFieldsView(stage, theme, assets, worldState, typedPage) { typedSettings() }
        views = listOf(eventView, pageView)

        settings.add(theme.heading("Event ${s.draft.id}")).row()
        for (f in EventForms.eventFields) eventView.add(settings, f)
        settings.add(theme.textButton("Use where I am standing") { useMyPosition() }).fill(false, false).left().row()
        settings.add(theme.textButton("Place or move on map") { worldState.editor.tool = com.morgana.client.state.MapEditorTool.EVENT }).fill(false, false).left().row()
        settings.add(theme.textButton("Copy to another map...") { openCopyToMapDialog() }).fill(false, false).left().row()
        settings.add(usedBy).minWidth(0f).row()

        settings.add(theme.heading("Pages")).padTop(6f).row()
        val pagesRow = VisTable()
        pagesRow.left()
        pageButtons = s.draft.pages.indices.map { i ->
            val b = theme.listButton("Page ${i + 1}", i == s.pageIndex) { session?.selectPage(i); rebuildAll() }
            b.label.setAlignment(Align.center)
            pagesRow.add(b).padRight(2f).padBottom(2f)
            if (i % 3 == 2) pagesRow.row()
            b
        }
        settings.add(pagesRow).row()
        val pageOps = VisTable()
        pageOps.left().defaults().padRight(2f).padBottom(2f)
        pageOps.add(theme.textButton("Add page") { session?.addPage(); rebuildAll() })
        pageOps.add(theme.textButton("Copy page") { session?.duplicatePage(); rebuildAll() })
        pageOps.row()
        earlierButton = theme.textButton("Move earlier") { session?.movePage(-1); rebuildAll() }.also { pageOps.add(it) }
        laterButton = theme.textButton("Move later") { session?.movePage(1); rebuildAll() }.also { pageOps.add(it) }
        pageOps.row()
        deletePageButton = theme.textButton("Delete page") { session?.deletePage(); rebuildAll() }.also { pageOps.add(it) }
        settings.add(pageOps).row()
        settings.add(pageNote).minWidth(0f).row()

        settings.add(theme.heading("Page ${s.pageIndex + 1}")).padTop(6f).row()
        for (f in EventForms.pageFields) pageView.add(settings, f)
        settings.add(theme.heading("Use this page only when")).padTop(6f).row()
        for (f in EventForms.conditionFields) pageView.add(settings, f)
        settings.add().growY()

        stepPane.rebuild()
        refreshState()
    }

    // ---- editing ----

    /** What is wrong with the text in the boxes right now (before it can be put into the event). */
    private fun typedProblems(s: EventEditSession): List<String> {
        val out = ArrayList<String>()
        out += FormRules.fieldProblems(EventForms.eventFields, typedEvent)
        out += FormRules.fieldProblems(EventForms.pageFields + EventForms.conditionFields, typedPage)
        out += stepPane.typedProblems(s)
        return out
    }

    private fun typedSettings() {
        val s = session ?: return
        if (FormRules.fieldProblems(EventForms.eventFields, typedEvent).isEmpty()) s.setEventValues(typedEvent)
        if (FormRules.fieldProblems(EventForms.pageFields + EventForms.conditionFields, typedPage).isEmpty()) s.setPageValues(typedPage)
        shownList = null // the name may have changed
        refreshState()
    }

    private fun useMyPosition() {
        val pos = worldState.localCharacter?.position ?: return
        val s = session ?: return
        s.setEventValues(typedEvent + mapOf("mapId" to pos.mapId.toString(), "tileX" to pos.tileX.toString(), "tileY" to pos.tileY.toString()))
        rebuildAll()
    }

    private fun openCopyToMapDialog() {
        val s = session ?: return
        if (unsaved() && pendingSwitch != COPY_TO_MAP) {
            pendingSwitch = COPY_TO_MAP
            refreshState()
            return
        }
        pendingSwitch = null
        val source = s.draft
        val maps = worldState.catalog.maps.sortedBy { it.id }
        val options = buildList {
            for (m in maps) add(m.id to "${m.id}: ${m.name} (${m.width}x${m.height})")
            add(0 to "0: Not placed on a map")
        }
        val currentPlayerMap = worldState.localCharacter?.position?.mapId ?: 0
        val defaultMapId = maps.firstOrNull { it.id != source.mapId && it.id == currentPlayerMap }?.id
            ?: maps.firstOrNull { it.id != source.mapId }?.id
            ?: maps.firstOrNull()?.id
            ?: 0

        val dialog = theme.window("Copy event ${source.id} to map")
        dialog.isModal = true
        dialog.isMovable = true
        dialog.addCloseButton()
        com.morgana.client.ui.HudWindow.hardenLastTitleButton(dialog)
        dialog.closeOnEscape()
        val nameField = VisTextField("${source.name} (copy)")
        val mapBox = VisSelectBox<String>()
        mapBox.setItems(*options.map { it.second }.toTypedArray())
        val defaultIdx = options.indexOfFirst { it.first == defaultMapId }.coerceAtLeast(0)
        mapBox.selectedIndex = defaultIdx
        val xField = VisTextField(source.tileX.toString())
        val yField = VisTextField(source.tileY.toString())
        val errLabel = VisLabel("", theme.warningLabel)

        val hereButton = theme.textButton("Use my tile") {
            val pos = worldState.localCharacter?.position ?: return@textButton
            val idx = options.indexOfFirst { it.first == pos.mapId }
            if (idx >= 0) mapBox.selectedIndex = idx
            xField.text = pos.tileX.toString()
            yField.text = pos.tileY.toString()
        }
        hereButton.isDisabled = worldState.localCharacter == null

        dialog.defaults().left().pad(4f)
        dialog.add(VisLabel("New event name:", theme.label)).row()
        dialog.add(nameField).width(280f).growX().row()
        dialog.add(VisLabel("Target map:", theme.label)).row()
        dialog.add(mapBox).width(280f).growX().row()
        val coords = VisTable()
        coords.defaults().left().padRight(6f)
        coords.add(VisLabel("X:", theme.label))
        coords.add(xField).width(64f)
        coords.add(VisLabel("Y:", theme.label))
        coords.add(yField).width(64f)
        coords.add(hereButton)
        dialog.add(coords).row()
        dialog.add(errLabel).growX().row()

        val btns = VisTable()
        btns.defaults().pad(4f)
        btns.add(theme.textButton("Create copy") {
            val chosenMapId = options.getOrNull(mapBox.selectedIndex)?.first ?: 0
            val parsedX = xField.text.trim().toIntOrNull()
            val parsedY = yField.text.trim().toIntOrNull()
            if (chosenMapId != 0 && (parsedX == null || parsedX < 0 || parsedY == null || parsedY < 0)) {
                errLabel.setText("Enter non-negative tile coordinates.")
                return@textButton
            }
            val nextId = (maxOf(worldState.catalog.events.maxOfOrNull { it.id } ?: 0, source.id)) + 1
            val copied = EventEditSession.duplicateToMap(
                source = source,
                newId = nextId,
                targetMapId = chosenMapId,
                tileX = parsedX ?: 0,
                tileY = parsedY ?: 0,
                catalogMaps = worldState.catalog.maps,
                newName = nameField.text
            )
            dialog.remove()
            load(copied, newEntry = true)
        })
        btns.add(theme.textButton("Cancel") { dialog.remove() })
        dialog.add(btns).right().row()

        dialog.pack()
        dialog.centerWindow()
        stage.addActor(dialog)
        dialog.toFront()
    }

    // ---- state shown under the editor ----

    private fun refreshState() {
        val s = session
        worldState.editingEventDraft = s?.draft
        stepPane.refreshState(s)
        if (s == null) {
            for (b in listOf(saveButton, testButton, undoButton, redoButton)) b.isDisabled = true
            problems.setText("")
            status.setText(if (worldState.catalog.events.isEmpty()) "No events yet. Press New event." else "")
            return
        }
        val typed = typedProblems(s)
        val list = (typed + s.problems(worldState.catalog)).distinct()
        val commandsToTest = s.commandsFromSelection()
        problems.setText(list.take(MAX_PROBLEMS).joinToString("\n") + if (list.size > MAX_PROBLEMS) "\n...and ${list.size - MAX_PROBLEMS} more" else "")
        saveButton.isDisabled = list.isNotEmpty() || !unsaved() || pendingSave != null
        testButton.isDisabled = typed.isNotEmpty() || commandsToTest.isEmpty()
        undoButton.isDisabled = !s.canUndo
        redoButton.isDisabled = !s.canRedo
        deletePageButton?.isDisabled = s.draft.pages.size <= 1
        earlierButton?.isDisabled = s.pageIndex == 0
        laterButton?.isDisabled = s.pageIndex == s.draft.pages.size - 1
        pageNote.setText(
            "Page ${s.pageIndex + 1} is used ${EventForms.conditionSummary(s.page, worldState.catalog)}." +
                if (s.draft.pages.size > 1) " When several pages fit, the last one wins." else ""
        )
        usedBy.setText(EventForms.usedBy(s.draft, worldState.catalog))
        status.setText(
            when {
                pendingSwitch != null -> "Unsaved changes will be lost. Click again to switch anyway."
                pendingSave != null -> "Saving..."
                rejected -> if (rejectionReason.isNotEmpty()) "The server did not accept this save: $rejectionReason" else "The server did not accept this save. Check the values and save again."
                isNew -> "New event: not saved yet."
                s.isDirty -> "Unsaved changes."
                else -> ""
            }
        )
        views.forEach { it.refreshHints() }
    }

    private fun testFromHere() {
        val s = session ?: return
        if (typedProblems(s).isNotEmpty()) return
        val cmds = s.commandsFromSelection()
        if (cmds.isEmpty()) return
        testCommands(s.draft.id, cmds)
        content.stage?.keyboardFocus = null
    }

    private fun saveCurrent() {
        val s = session ?: return
        if (typedProblems(s).isNotEmpty() || s.problems(worldState.catalog).isNotEmpty()) return
        save(s.draft)
        pendingSave = s.draft
        pendingSince = System.currentTimeMillis()
        rejected = false
        shownList = null
        content.stage?.keyboardFocus = null
    }

    private companion object {
        const val NEW = -2
        const val COPY_TO_MAP = -3
        const val CONFIRM_MILLIS = 4000L
        const val MAX_PROBLEMS = 6
    }
}
