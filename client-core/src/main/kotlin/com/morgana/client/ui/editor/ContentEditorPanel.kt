package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.forms.ContentForm
import com.morgana.client.editor.forms.FormContext
import com.morgana.client.editor.forms.FormRules
import com.morgana.client.state.ClientWorldState

/**
 * A master-detail editor for one kind of content (items, NPCs, spells): the list on the left, a form on the right built from a
 * [ContentForm]'s typed fields, and below the form the problems with what was typed and a Save button. The same panel serves
 * every kind, so a new kind of content needs only its form.
 *
 * Nothing reaches the server until Save: [save] is called with the definition built from the typed values. Switching to another
 * entry while there are unsaved edits asks first (click the entry again to discard them).
 */
class ContentEditorPanel<T>(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val form: ContentForm<T>,
    private val save: (T) -> Unit,
    /** Extra buttons under Save, such as "Give me one" for items. */
    private val extraActions: List<Pair<String, (T) -> Unit>> = emptyList()
) {
    val content: Actor

    // ---- state ----
    private var current: T? = null
    private var isNew = false
    private var typed: MutableMap<String, String> = linkedMapOf()
    private var loaded: Map<String, String> = emptyMap()
    private var pendingSwitch: Int? = null
    private var shownList: List<T>? = null
    private var shownCurrentId = -1
    private var filter = ""

    /** The definition just sent to the server, until the catalog confirms it (or a few seconds pass without it). */
    private var pendingSave: T? = null
    private var pendingSince = 0L
    private var rejected = false
    private var rejectionReason = ""

    // ---- widgets ----
    private val listRows = VisTable()
    private val formTable = VisTable()
    private val problems = VisLabel("", theme.warningLabel)
    private val status = theme.muted("")
    private val saveButton: VisTextButton
    private val extraButtons = ArrayList<VisTextButton>()
    private var fields: FormFieldsView? = null

    init {
        problems.setWrap(true)
        status.setWrap(true)
        saveButton = theme.textButton("Save") { saveCurrent() }

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
        val listPane = VisScrollPane(listRows)
        listPane.setScrollingDisabled(true, false)
        listPane.setFadeScrollBars(false)
        listPane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, listPane)

        val left = VisTable()
        left.defaults().growX().pad(2f)
        left.add(search).row()
        left.add(listPane).grow().row()
        left.add(theme.textButton("New ${form.noun}") { startNew() }).row()

        formTable.defaults().left().growX().pad(2f)
        val formPane = VisScrollPane(formTable)
        formPane.setScrollingDisabled(true, false)
        formPane.setFadeScrollBars(false)
        formPane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, formPane)

        val footer = VisTable()
        footer.defaults().growX().pad(2f)
        footer.add(problems).minWidth(0f).row()
        footer.add(status).minWidth(0f).row()
        val buttons = VisTable()
        buttons.add(saveButton).growX().padRight(4f)
        for ((label, action) in extraActions) {
            val b = theme.textButton(label) { current?.let { c -> action(form.apply(c, typed, context())) } }
            extraButtons += b
            buttons.add(b).padRight(4f)
        }
        footer.add(buttons).row()

        val right = VisTable()
        right.add(formPane).grow().row()
        right.add(footer).growX()

        val root = LiveTable { follow() }
        root.add(left).width(150f).growY().padRight(4f)
        root.add(right).grow()
        content = root
    }

    // ---- following the catalog ----

    private fun context(): FormContext {
        val game = worldState.gameDefinition
        return FormContext(
            attributeIds = game?.attributes?.map { it.id }?.takeIf { it.isNotEmpty() } ?: FormContext.DEFAULT_ATTRIBUTES,
            vitalIds = game?.vitals?.map { it.id }?.takeIf { it.isNotEmpty() } ?: FormContext.DEFAULT_VITALS,
            lifeVitalId = game?.vitals?.firstOrNull { it.role == com.morgana.domain.game.VitalRole.LIFE }?.id ?: FormContext.DEFAULT_LIFE_VITAL,
            catalog = worldState.catalog,
            slotIds = game?.slots?.map { it.id } ?: com.morgana.domain.item.EquipmentSlot.CLASSIC.map { it.id }
        )
    }

    private fun follow() {
        val all = form.all(worldState.catalog)
        val currentId = current?.let { form.idOf(it) } ?: -1
        checkPendingSave(all, currentId)
        if (all != shownList || currentId != shownCurrentId) {
            shownList = all
            shownCurrentId = currentId
            rebuildList(all, currentId)
            // The catalog changed under us (another editor, or a hot reload): refresh a clean form from it.
            if (current != null && pendingSave == null && !isDirty() && !isNew) all.firstOrNull { form.idOf(it) == currentId }?.let { fresh -> if (fresh != current) load(fresh, newEntry = false) }
            // A new entry that has now arrived from the server is no longer "new".
            if (isNew && all.any { form.idOf(it) == currentId }) isNew = false
        }
        if (current == null && all.isNotEmpty()) load(all.first(), newEntry = false)
        refreshState()
    }

    /** Waits for the server to confirm a save: the catalog arrives with the new definition, or, if it never does, the save was refused. */
    private fun checkPendingSave(all: List<T>, currentId: Int) {
        val sent = pendingSave ?: return
        val fresh = all.firstOrNull { form.idOf(it) == form.idOf(sent) }
        if (fresh == sent) {
            pendingSave = null
            rejected = false
            return
        }
        val refusal = worldState.lastSaveRejection?.takeIf { it.isFor(form.noun, form.idOf(sent)) && worldState.lastSaveRejectionAt >= pendingSince }
        if (refusal != null || System.currentTimeMillis() - pendingSince > CONFIRM_MILLIS) {
            // Refused, or never confirmed. Keep what was typed so it can be fixed and resent.
            pendingSave = null
            rejected = true
            rejectionReason = refusal?.reason.orEmpty()
            loaded = fresh?.let { form.valuesOf(it, context()) } ?: emptyMap()
            if (fresh == null) isNew = true
            shownCurrentId = -2
        }
    }

    private fun rebuildList(all: List<T>, currentId: Int) {
        listRows.clearChildren()
        val shown = all.filter { filter.isEmpty() || form.nameOf(it).lowercase().contains(filter) || form.idOf(it).toString() == filter }
        if (shown.isEmpty()) listRows.add(theme.muted(if (all.isEmpty()) "None yet" else "No match")).row()
        for (d in shown) {
            val id = form.idOf(d)
            listRows.add(theme.listButton("$id: ${form.nameOf(d)}", id == currentId) { select(d) }).growX().minWidth(0f).row()
        }
        if (isNew && current != null && all.none { form.idOf(it) == currentId }) {
            if (all.isEmpty()) listRows.clearChildren()
            listRows.add(theme.listButton("${currentId}: ${form.nameOf(current!!)} (new)", true) {}).growX().minWidth(0f).row()
        }
    }

    // ---- selecting and loading ----

    private fun isDirty(): Boolean = current != null && typed != loaded

    private fun select(definition: T) {
        val id = form.idOf(definition)
        if (id == current?.let { form.idOf(it) }) return
        if ((isDirty() || isNew) && pendingSwitch != id) {
            pendingSwitch = id
            return
        }
        load(definition, newEntry = false)
    }

    private fun startNew() {
        if ((isDirty() || isNew) && pendingSwitch != NEW) {
            pendingSwitch = NEW
            return
        }
        val id = (form.all(worldState.catalog).maxOfOrNull { form.idOf(it) } ?: (form.firstId - 1)) + 1
        load(form.create(id, context()), newEntry = true)
    }

    private fun load(definition: T, newEntry: Boolean) {
        pendingSwitch = null
        current = definition
        isNew = newEntry
        val values = form.valuesOf(definition, context())
        typed = values.toMutableMap()
        loaded = if (newEntry) emptyMap() else values
        shownCurrentId = -2
        buildForm()
    }

    // ---- the form ----

    private fun buildForm() {
        formTable.clearChildren()
        fields = null
        val d = current ?: return
        val view = FormFieldsView(stage, theme, assets, worldState, typed) { refreshState() }
        fields = view
        formTable.add(theme.text("${form.noun.replaceFirstChar { it.uppercase() }} ${form.idOf(d)}")).row()
        for (section in form.sections(context())) {
            if (section.fields.isEmpty()) continue
            formTable.add(theme.heading(section.title)).growX().padTop(6f).row()
            for (f in section.fields) view.add(formTable, f)
        }
        formTable.add().growY()
        refreshState()
    }

    // ---- state shown under the form ----

    private fun refreshState() {
        val d = current
        if (d == null) {
            saveButton.isDisabled = true
            problems.setText("")
            status.setText(if (form.all(worldState.catalog).isEmpty()) "No ${form.noun}s yet. Press New ${form.noun}." else "")
            extraButtons.forEach { it.isDisabled = true }
            return
        }
        val list = FormRules.problems(form, typed, context())
        problems.setText(list.joinToString("\n"))
        saveButton.isDisabled = list.isNotEmpty() || !(isDirty() || isNew)
        extraButtons.forEach { it.isDisabled = isNew || list.isNotEmpty() }
        status.setText(
            when {
                pendingSwitch != null -> "Unsaved changes will be lost. Click again to switch anyway."
                pendingSave != null -> "Saving..."
                rejected -> if (rejectionReason.isNotEmpty()) "The server did not accept this save: $rejectionReason" else "The server did not accept this save. Check the values and save again."
                isNew -> "New ${form.noun}: not saved yet."
                isDirty() -> "Unsaved changes."
                else -> ""
            }
        )
        fields?.refreshHints()
    }

    private fun saveCurrent() {
        val d = current ?: return
        if (FormRules.problems(form, typed, context()).isNotEmpty()) return
        val built = form.apply(d, typed, context())
        save(built)
        current = built
        // Changed in place: the field widgets write into this same map.
        val fresh = form.valuesOf(built, context())
        typed.clear()
        typed.putAll(fresh)
        loaded = typed.toMap()
        pendingSave = built
        pendingSince = System.currentTimeMillis()
        rejected = false
        // Keep it listed until the server's catalog arrives with it.
        shownList = null
        content.stage?.keyboardFocus = null
    }

    private companion object {
        const val NEW = -2
        const val CONFIRM_MILLIS = 4000L
    }
}
