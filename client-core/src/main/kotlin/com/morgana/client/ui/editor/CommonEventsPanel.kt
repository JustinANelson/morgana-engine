package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.events.CommonEventEditSession
import com.morgana.client.editor.events.EventForms
import com.morgana.client.editor.forms.FormRules
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.EventCommand

/**
 * The Common events editor: a searchable library of reusable step sequences that any event can invoke via Run common event.
 * Shares [StepTreePane] with the Events editor; all rules live in [CommonEventEditSession] and [EventForms].
 */
class CommonEventsPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val save: (CommonEventDefinition) -> Unit,
    private val testCommands: (Int, List<EventCommand>) -> Unit = { _, _ -> }
) {
    val content: Actor

    // ---- state ----
    private var session: CommonEventEditSession? = null
    private var isNew = false
    private var pendingSwitch: Int? = null
    private var shownList: List<CommonEventDefinition>? = null
    private var shownCurrentId = -1
    private var filter = ""
    private var pendingSave: CommonEventDefinition? = null
    private var pendingSince = 0L
    private var rejected = false
    private var rejectionReason = ""
    private val typedDetails = linkedMapOf<String, String>()
    private var detailsView: FormFieldsView? = null

    // ---- widgets ----
    private val listRows = VisTable()
    private val settings = VisTable()
    private val usedBy = theme.muted("")
    private val problems = VisLabel("", theme.warningLabel)
    private val status = theme.muted("")
    private val saveButton = theme.textButton("Save common event") { saveCurrent() }
    private val testButton = theme.textButton("Test from here") { testFromHere() }
    private val undoButton = theme.textButton("Undo") { session?.undo(); rebuildAll() }
    private val redoButton = theme.textButton("Redo") { session?.redo(); rebuildAll() }
    private val stepPane = StepTreePane(stage, theme, assets, worldState, { session }) { refreshState() }

    init {
        for (l in listOf(usedBy, problems, status)) l.setWrap(true)

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
        left.add(theme.textButton("New common event") { startNew() }).row()

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
        columns.add(verticalScrollPane(stage, settings)).width(270f).growY().padRight(6f)
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
        val all = worldState.catalog.commonEvents.sortedBy { it.id }
        val s = session
        val currentId = s?.draft?.id ?: -1
        checkPendingSave(all)
        if (all != shownList || currentId != shownCurrentId) {
            shownList = all
            shownCurrentId = currentId
            rebuildList(all, currentId)
            val fresh = all.firstOrNull { it.id == currentId }
            if (s != null && fresh != null && pendingSave == null && !s.isDirty && !isNew && s.saved != fresh) load(fresh, newEntry = false)
            if (isNew && fresh != null) isNew = false
        }
        if (session == null && all.isNotEmpty()) load(all.first(), newEntry = false)
        refreshState()
    }

    private fun checkPendingSave(all: List<CommonEventDefinition>) {
        val sent = pendingSave ?: return
        val fresh = all.firstOrNull { it.id == sent.id }
        if (fresh == sent) {
            pendingSave = null
            rejected = false
            isNew = false
            session?.takeIf { it.draft.id == sent.id }?.markSaved(fresh)
            return
        }
        val refusal = worldState.lastSaveRejection?.takeIf { it.isFor("common_event", sent.id) && worldState.lastSaveRejectionAt >= pendingSince }
        if (refusal != null || System.currentTimeMillis() - pendingSince > CONFIRM_MILLIS) {
            pendingSave = null
            rejected = true
            rejectionReason = refusal?.reason.orEmpty()
        }
    }

    private fun rebuildList(all: List<CommonEventDefinition>, currentId: Int) {
        listRows.clearChildren()
        val shown = all.filter { filter.isEmpty() || it.name.lowercase().contains(filter) || it.id.toString() == filter }
        if (shown.isEmpty() && !isNew) listRows.add(theme.muted(if (all.isEmpty()) "None yet" else "No match")).row()
        for (ce in shown) {
            val tag = when (ce.trigger) {
                CommonEventTrigger.MANUAL -> ""
                CommonEventTrigger.ON_TIMER -> " [Timer ${ce.intervalTicks}t]"
                CommonEventTrigger.ON_LOGIN -> " [Login]"
                CommonEventTrigger.ON_LEVEL_UP -> " [Level up]"
            }
            listRows.add(theme.listButton("${ce.id}: ${ce.name}$tag", ce.id == currentId) { select(ce) }).growX().minWidth(0f).row()
        }
        val s = session
        if (isNew && s != null && all.none { it.id == currentId }) {
            listRows.add(theme.listButton("${s.draft.id}: ${s.draft.name} (new)", true) {}).growX().minWidth(0f).row()
        }
    }

    // ---- selecting and loading ----

    private fun unsaved(): Boolean = session?.isDirty == true || isNew

    private fun select(ce: CommonEventDefinition) {
        if (ce.id == session?.draft?.id) return
        if (unsaved() && pendingSwitch != ce.id) {
            pendingSwitch = ce.id
            return
        }
        load(ce, newEntry = false)
    }

    private fun startNew() {
        if (unsaved() && pendingSwitch != NEW) {
            pendingSwitch = NEW
            return
        }
        val id = (worldState.catalog.commonEvents.maxOfOrNull { it.id } ?: 0) + 1
        load(
            CommonEventDefinition(
                id = id,
                name = "New common event $id",
                commands = listOf(EventCommand.ShowDialogue("", "Hello!"))
            ),
            newEntry = true
        )
    }

    private fun load(ce: CommonEventDefinition, newEntry: Boolean) {
        pendingSwitch = null
        rejected = false
        isNew = newEntry
        session = CommonEventEditSession(ce, if (newEntry) null else ce)
        shownCurrentId = -2
        rebuildAll()
    }

    // ---- building ----

    private fun rebuildAll() {
        settings.clearChildren()
        val s = session
        if (s == null) {
            detailsView = null
            stepPane.rebuild()
            return
        }
        typedDetails.clear(); typedDetails.putAll(EventForms.commonEventValues(s.draft))
        val view = FormFieldsView(stage, theme, assets, worldState, typedDetails) { typedSettings() }
        detailsView = view

        settings.add(theme.heading("Common event ${s.draft.id}")).row()
        for (f in EventForms.commonEventFields) view.add(settings, f)
        settings.add(usedBy).minWidth(0f).row()
        settings.add().growY()

        stepPane.rebuild()
        refreshState()
    }

    // ---- editing ----

    private fun typedProblems(s: CommonEventEditSession): List<String> =
        FormRules.fieldProblems(EventForms.commonEventFields, typedDetails) + stepPane.typedProblems(s)

    private fun typedSettings() {
        val s = session ?: return
        if (FormRules.fieldProblems(EventForms.commonEventFields, typedDetails).isEmpty()) s.setCommonEventValues(typedDetails)
        shownList = null
        refreshState()
    }

    // ---- state shown under the editor ----

    private fun refreshState() {
        val s = session
        stepPane.refreshState(s)
        if (s == null) {
            for (b in listOf(saveButton, testButton, undoButton, redoButton)) b.isDisabled = true
            problems.setText("")
            status.setText(if (worldState.catalog.commonEvents.isEmpty()) "No common events yet. Press New common event." else "")
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
        usedBy.setText(s.usedBy(worldState.catalog))
        status.setText(
            when {
                pendingSwitch != null -> "Unsaved changes will be lost. Click again to switch anyway."
                pendingSave != null -> "Saving..."
                rejected -> if (rejectionReason.isNotEmpty()) "The server did not accept this save: $rejectionReason" else "The server did not accept this save. Check the values and save again."
                isNew -> "New common event: not saved yet."
                s.isDirty -> "Unsaved changes."
                else -> ""
            }
        )
        detailsView?.refreshHints()
    }

    private fun testFromHere() {
        val s = session ?: return
        if (typedProblems(s).isNotEmpty()) return
        val cmds = s.commandsFromSelection()
        if (cmds.isEmpty()) return
        testCommands(0, cmds)
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
        const val CONFIRM_MILLIS = 4000L
        const val MAX_PROBLEMS = 6
    }
}

