package com.morgana.client.editor.events

import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.protocol.MapSummary
import com.morgana.protocol.WorldContentCatalogDto

/**
 * Contract for any editor holding a tree of [EventCommand] steps (an [EventDefinition] page or a [CommonEventDefinition]).
 * Used by the shared `StepTreePane` widget.
 */
interface StepSequenceSession {
    val selection: List<Int>?
    val canEditSelection: Boolean
    val canAddChoice: Boolean
    val selectedIsFixedBranch: Boolean
    val selectedCommand: EventCommand?
    val selectedChoice: DialogueChoice?
    fun rows(catalog: WorldContentCatalogDto): List<EventRow>
    fun select(path: List<Int>?)
    fun addCommand(command: EventCommand)
    fun addChoice()
    fun setSelectedValues(values: Map<String, String>)
    fun updateSelectedCommand(transform: (EventCommand) -> EventCommand)
    fun deleteSelected()
    fun duplicateSelected()
    fun canMove(delta: Int): Boolean
    fun moveSelected(delta: Int)
    fun commandsFromSelection(): List<EventCommand>
}

/**
 * Pure operations on a tree of [EventCommand] steps addressed by integer paths.
 *
 * A **path** names a place in the step list: `[2]` is the third step, `[2, 0]` is the first answer of that dialogue (or the
 * Then branch of an If step), `[2, 0, 1]` is the second step under that branch, and so on. An odd length is a step; an even
 * length is a branch.
 */
internal object EventStepTree {
    fun exists(commands: List<EventCommand>, path: List<Int>): Boolean =
        if (path.size % 2 == 1) commandAt(commands, path) != null else branchExists(commands, path)

    fun branchExists(commands: List<EventCommand>, path: List<Int>): Boolean {
        if (path.isEmpty()) return false
        return when (val parent = commandAt(commands, path.dropLast(1))) {
            is EventCommand.ShowDialogue -> path.last() in parent.choices.indices
            is EventCommand.Conditional -> path.last() == EventForms.THEN || path.last() == EventForms.OTHERWISE
            is EventCommand.Loop -> path.last() == EventForms.LOOP_BODY
            else -> false
        }
    }

    fun selectedIsFixedBranch(commands: List<EventCommand>, selection: List<Int>?): Boolean =
        selection?.let {
            it.isNotEmpty() && it.size % 2 == 0 &&
                commandAt(commands, it.dropLast(1)).let { p -> p is EventCommand.Conditional || p is EventCommand.Loop }
        } == true

    fun canEditSelection(commands: List<EventCommand>, selection: List<Int>?): Boolean =
        selection != null && !selectedIsFixedBranch(commands, selection)

    fun selectedCommand(commands: List<EventCommand>, selection: List<Int>?): EventCommand? =
        selection?.takeIf { it.size % 2 == 1 }?.let { commandAt(commands, it) }

    fun selectedChoice(commands: List<EventCommand>, selection: List<Int>?): DialogueChoice? =
        selection?.takeIf { it.isNotEmpty() && it.size % 2 == 0 }?.let { choiceAt(commands, it) }

    fun dialoguePath(commands: List<EventCommand>, selection: List<Int>?): List<Int>? {
        val s = selection ?: return null
        val p = if (s.size % 2 == 1) s else s.dropLast(1)
        return p.takeIf { commandAt(commands, it) is EventCommand.ShowDialogue }
    }

    fun commandAt(commands: List<EventCommand>, path: List<Int>): EventCommand? =
        if (path.size % 2 == 0) null else listAt(commands, path.dropLast(1))?.getOrNull(path.last())

    fun choiceAt(commands: List<EventCommand>, path: List<Int>): DialogueChoice? =
        if (path.isEmpty() || path.size % 2 == 1) null
        else (commandAt(commands, path.dropLast(1)) as? EventCommand.ShowDialogue)?.choices?.getOrNull(path.last())

    fun listAt(commands: List<EventCommand>, container: List<Int>): List<EventCommand>? {
        if (container.isEmpty()) return commands
        val inner = when (val parent = commands.getOrNull(container[0])) {
            is EventCommand.ShowDialogue -> parent.choices.getOrNull(container[1])?.commands
            is EventCommand.Conditional -> when (container[1]) {
                EventForms.THEN -> parent.then
                EventForms.OTHERWISE -> parent.otherwise
                else -> null
            }
            is EventCommand.Loop -> if (container[1] == EventForms.LOOP_BODY) parent.commands else null
            else -> null
        } ?: return null
        return listAt(inner, container.drop(2))
    }

    fun mapList(
        commands: List<EventCommand>,
        container: List<Int>,
        f: (List<EventCommand>) -> List<EventCommand>
    ): List<EventCommand> {
        if (container.isEmpty()) return f(commands)
        return commands.mapIndexed { i, c ->
            if (i != container[0]) c else when (c) {
                is EventCommand.ShowDialogue ->
                    c.copy(choices = c.choices.mapIndexed { j, ch ->
                        if (j == container[1]) ch.copy(commands = mapList(ch.commands, container.drop(2), f)) else ch
                    })
                is EventCommand.Conditional -> when (container[1]) {
                    EventForms.THEN -> c.copy(then = mapList(c.then, container.drop(2), f))
                    EventForms.OTHERWISE -> c.copy(otherwise = mapList(c.otherwise, container.drop(2), f))
                    else -> c
                }
                is EventCommand.Loop ->
                    if (container[1] == EventForms.LOOP_BODY) c.copy(commands = mapList(c.commands, container.drop(2), f)) else c
                else -> c
            }
        }
    }

    fun mapChoices(
        commands: List<EventCommand>,
        dialogue: List<Int>,
        f: (List<DialogueChoice>) -> List<DialogueChoice>
    ): List<EventCommand> = mapList(commands, dialogue.dropLast(1)) { list ->
        list.mapIndexed { i, c -> if (i == dialogue.last() && c is EventCommand.ShowDialogue) c.copy(choices = f(c.choices)) else c }
    }

    fun addCommand(commands: List<EventCommand>, selection: List<Int>?, command: EventCommand): Pair<List<EventCommand>, List<Int>>? =
        when {
            selection == null -> mapList(commands, emptyList()) { it + command } to listOf(commands.size)
            selection.size % 2 == 1 -> mapList(commands, selection.dropLast(1)) { l ->
                l.toMutableList().also { it.add(selection.last() + 1, command) }
            } to (selection.dropLast(1) + (selection.last() + 1))
            else -> {
                val at = listAt(commands, selection)?.size ?: return null
                mapList(commands, selection) { it + command } to (selection + at)
            }
        }

    fun addChoice(commands: List<EventCommand>, selection: List<Int>?): Pair<List<EventCommand>, List<Int>>? {
        val d = dialoguePath(commands, selection) ?: return null
        val count = (commandAt(commands, d) as EventCommand.ShowDialogue).choices.size
        return mapChoices(commands, d) { EventForms.withNewChoice(it) } to (d + count)
    }

    fun setSelectedValues(commands: List<EventCommand>, selection: List<Int>?, values: Map<String, String>): List<EventCommand>? {
        val s = selection ?: return null
        if (selectedIsFixedBranch(commands, s)) return null
        return if (s.size % 2 == 1) {
            mapList(commands, s.dropLast(1)) { l -> l.mapIndexed { i, c -> if (i == s.last()) EventForms.apply(c, values) else c } }
        } else {
            val label = values["label"]?.trim().orEmpty()
            mapChoices(commands, s.dropLast(1)) { l -> l.mapIndexed { i, ch -> if (i == s.last()) ch.copy(label = label) else ch } }
        }
    }

    fun updateSelectedCommand(
        commands: List<EventCommand>,
        selection: List<Int>?,
        transform: (EventCommand) -> EventCommand
    ): List<EventCommand>? {
        val s = selection?.takeIf { it.size % 2 == 1 } ?: return null
        return mapList(commands, s.dropLast(1)) { l -> l.mapIndexed { i, c -> if (i == s.last()) transform(c) else c } }
    }

    fun deleteSelected(commands: List<EventCommand>, selection: List<Int>?): Pair<List<EventCommand>, List<Int>?>? {
        val s = selection ?: return null
        if (selectedIsFixedBranch(commands, s)) return null
        return if (s.size % 2 == 1) {
            mapList(commands, s.dropLast(1)) { l -> l.filterIndexed { i, _ -> i != s.last() } } to null
        } else {
            mapChoices(commands, s.dropLast(1)) { l -> l.filterIndexed { i, _ -> i != s.last() } } to s.dropLast(1)
        }
    }

    fun duplicateSelected(commands: List<EventCommand>, selection: List<Int>?): Pair<List<EventCommand>, List<Int>>? {
        val s = selection ?: return null
        if (selectedIsFixedBranch(commands, s)) return null
        val next = s.dropLast(1) + (s.last() + 1)
        val updated = if (s.size % 2 == 1) {
            mapList(commands, s.dropLast(1)) { l -> l.toMutableList().also { it.add(s.last() + 1, l[s.last()]) } }
        } else {
            mapChoices(commands, s.dropLast(1)) { l -> l.toMutableList().also { it.add(s.last() + 1, l[s.last()]) } }
        }
        return updated to next
    }

    fun canMove(commands: List<EventCommand>, selection: List<Int>?, delta: Int): Boolean {
        val s = selection ?: return false
        if (selectedIsFixedBranch(commands, s)) return false
        val to = s.last() + delta
        val size = if (s.size % 2 == 1) listAt(commands, s.dropLast(1))?.size
        else (commandAt(commands, s.dropLast(1)) as? EventCommand.ShowDialogue)?.choices?.size
        return size != null && to in 0 until size
    }

    fun moveSelected(commands: List<EventCommand>, selection: List<Int>?, delta: Int): Pair<List<EventCommand>, List<Int>>? {
        if (!canMove(commands, selection, delta)) return null
        val s = selection!!
        val from = s.last()
        val to = from + delta
        val next = s.dropLast(1) + to
        val updated = if (s.size % 2 == 1) {
            mapList(commands, s.dropLast(1)) { l -> l.toMutableList().also { it.add(to, it.removeAt(from)) } }
        } else {
            mapChoices(commands, s.dropLast(1)) { l -> l.toMutableList().also { it.add(to, it.removeAt(from)) } }
        }
        return updated to next
    }

    fun commandsFromSelection(commands: List<EventCommand>, selection: List<Int>?): List<EventCommand> {
        fun slice(list: List<EventCommand>, path: List<Int>): List<EventCommand> {
            if (path.isEmpty()) return list
            val idx = path[0]
            if (idx !in list.indices) return emptyList()
            if (path.size == 1) return list.drop(idx)
            val branch = path[1]
            val childList = when (val cmd = list[idx]) {
                is EventCommand.ShowDialogue -> cmd.choices.getOrNull(branch)?.commands
                is EventCommand.Conditional -> when (branch) {
                    EventForms.THEN -> cmd.then
                    EventForms.OTHERWISE -> cmd.otherwise
                    else -> null
                }
                is EventCommand.Loop -> if (branch == EventForms.LOOP_BODY) cmd.commands else null
                else -> null
            } ?: return list.drop(idx)
            return slice(childList, path.drop(2)) + list.drop(idx + 1)
        }
        val sel = selection ?: return commands
        return slice(commands, sel)
    }
}

/**
 * The event being edited and everything that can be done to it: pages (add, copy, delete, reorder), the steps of a page as a
 * tree (a dialogue's answers each hold their own steps), a selection, and undo/redo. Pure, so every rule is unit-tested; the
 * Events panel only draws it. Nothing reaches the server until the panel sends [draft].
 */
class EventEditSession(initial: EventDefinition, saved: EventDefinition? = initial) : StepSequenceSession {
    var draft: EventDefinition = normalize(initial)
        private set

    /** The version the server has; null for an event that was never saved. */
    var saved: EventDefinition? = saved?.let { normalize(it) }
        private set

    var pageIndex: Int = 0
        private set

    /** The selected step or answer of the current page, or null. */
    override var selection: List<Int>? = null
        private set

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private data class Snapshot(val draft: EventDefinition, val pageIndex: Int, val selection: List<Int>?)

    val isDirty: Boolean get() = draft != saved
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val page: EventPage get() = draft.pages[pageIndex]

    /** Call when the server's catalog holds [fresh] (after a save, or a change made elsewhere). */
    fun markSaved(fresh: EventDefinition) { saved = normalize(fresh) }

    private fun normalize(e: EventDefinition): EventDefinition =
        e.copy(pages = e.pages.ifEmpty { listOf(EventPage()) }.mapIndexed { i, p -> if (p.pageIndex == i) p else p.copy(pageIndex = i) })

    /** Typing in one form is one undo step: consecutive changes with the same [typingKey] are merged. */
    private var lastTypingKey: String? = null

    private fun change(newDraft: EventDefinition, newPage: Int = pageIndex, newSelection: List<Int>? = selection, typingKey: String? = null) {
        val next = normalize(newDraft)
        if (next != draft) {
            if (typingKey == null || typingKey != lastTypingKey) {
                undoStack.addLast(Snapshot(draft, pageIndex, selection))
                if (undoStack.size > HISTORY) undoStack.removeFirst()
            }
            lastTypingKey = typingKey
            redoStack.clear()
            draft = next
        }
        pageIndex = newPage.coerceIn(0, draft.pages.size - 1)
        selection = newSelection?.takeIf { EventStepTree.exists(page.commands, it) }
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(draft, pageIndex, selection))
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(draft, pageIndex, selection))
        restore(s)
    }

    private fun restore(s: Snapshot) {
        lastTypingKey = null
        draft = s.draft
        pageIndex = s.pageIndex.coerceIn(0, draft.pages.size - 1)
        selection = s.selection?.takeIf { EventStepTree.exists(page.commands, it) }
    }

    // ---- the event and its pages ----

    fun setEventValues(values: Map<String, String>) = change(EventForms.applyEvent(draft, values), typingKey = "event")

    /** Moves the event to ([mapId], [tileX], [tileY]) as its own undo step. */
    fun moveToTile(mapId: Int, tileX: Int, tileY: Int) = change(draft.copy(mapId = mapId, tileX = tileX, tileY = tileY))

    fun setPageValues(values: Map<String, String>) = changePage(typingKey = "page $pageIndex") { EventForms.applyPage(it, values) }

    private fun changePage(newSelection: List<Int>? = selection, typingKey: String? = null, f: (EventPage) -> EventPage) =
        change(draft.copy(pages = draft.pages.mapIndexed { i, p -> if (i == pageIndex) f(p) else p }), newSelection = newSelection, typingKey = typingKey)

    fun selectPage(index: Int) {
        if (index !in draft.pages.indices || index == pageIndex) return
        pageIndex = index
        selection = null
        lastTypingKey = null
    }

    /** Adds an empty page after the current one and shows it. */
    fun addPage() = insertPage(EventPage())

    /** Adds a copy of the current page after it and shows it. */
    fun duplicatePage() = insertPage(page)

    private fun insertPage(p: EventPage) {
        val pages = draft.pages.toMutableList().also { it.add(pageIndex + 1, p) }
        change(draft.copy(pages = pages), newPage = pageIndex + 1, newSelection = null)
    }

    /** Deletes the current page. An event always keeps one page. */
    fun deletePage() {
        if (draft.pages.size <= 1) return
        change(draft.copy(pages = draft.pages.filterIndexed { i, _ -> i != pageIndex }), newPage = (pageIndex - 1).coerceAtLeast(0), newSelection = null)
    }

    /** Moves the current page earlier (-1) or later (+1). Later pages win when several match, so order matters. */
    fun movePage(delta: Int) {
        val to = pageIndex + delta
        if (to !in draft.pages.indices) return
        val pages = draft.pages.toMutableList()
        pages.add(to, pages.removeAt(pageIndex))
        change(draft.copy(pages = pages), newPage = to)
    }

    // ---- steps ----

    override fun rows(catalog: WorldContentCatalogDto): List<EventRow> = EventForms.rows(page.commands, catalog)

    override fun select(path: List<Int>?) {
        selection = path?.takeIf { EventStepTree.exists(page.commands, it) }
        lastTypingKey = null
    }

    override val selectedIsFixedBranch: Boolean
        get() = EventStepTree.selectedIsFixedBranch(page.commands, selection)

    override val canEditSelection: Boolean
        get() = EventStepTree.canEditSelection(page.commands, selection)

    override val selectedCommand: EventCommand?
        get() = EventStepTree.selectedCommand(page.commands, selection)

    override val selectedChoice: DialogueChoice?
        get() = EventStepTree.selectedChoice(page.commands, selection)

    override val canAddChoice: Boolean
        get() = EventStepTree.dialoguePath(page.commands, selection) != null

    fun commandAt(path: List<Int>): EventCommand? = EventStepTree.commandAt(page.commands, path)

    fun choiceAt(path: List<Int>): DialogueChoice? = EventStepTree.choiceAt(page.commands, path)

    private fun applySteps(updated: Pair<List<EventCommand>, List<Int>?>?, typingKey: String? = null) {
        val (commands, newSel) = updated ?: return
        changePage(newSelection = newSel, typingKey = typingKey) { it.copy(commands = commands) }
    }

    override fun addCommand(command: EventCommand) = applySteps(EventStepTree.addCommand(page.commands, selection, command))

    override fun addChoice() = applySteps(EventStepTree.addChoice(page.commands, selection))

    override fun setSelectedValues(values: Map<String, String>) {
        val updated = EventStepTree.setSelectedValues(page.commands, selection, values) ?: return
        applySteps(updated to selection, typingKey = "step $pageIndex $selection")
    }

    override fun updateSelectedCommand(transform: (EventCommand) -> EventCommand) {
        val updated = EventStepTree.updateSelectedCommand(page.commands, selection, transform) ?: return
        applySteps(updated to selection)
    }

    override fun deleteSelected() = applySteps(EventStepTree.deleteSelected(page.commands, selection))

    override fun duplicateSelected() = applySteps(EventStepTree.duplicateSelected(page.commands, selection))

    override fun canMove(delta: Int): Boolean = EventStepTree.canMove(page.commands, selection, delta)

    override fun moveSelected(delta: Int) = applySteps(EventStepTree.moveSelected(page.commands, selection, delta))

    fun problems(catalog: WorldContentCatalogDto): List<String> = EventForms.problems(draft, catalog)

    override fun commandsFromSelection(): List<EventCommand> = EventStepTree.commandsFromSelection(page.commands, selection)

    companion object {
        internal const val HISTORY = 100

        /**
         * Returns the effective list of events (replacing any catalog entry with [editing] when it shares the same id, or
         * appending [editing] if it is brand new) and finds the first one placed at ([mapId], [tileX], [tileY]).
         */
        fun findAtTile(
            catalogEvents: List<EventDefinition>,
            editing: EventDefinition?,
            isNew: Boolean,
            mapId: Int,
            tileX: Int,
            tileY: Int
        ): EventDefinition? {
            val effective = if (editing == null) catalogEvents
            else catalogEvents.map { if (it.id == editing.id) editing else it } +
                if (isNew && catalogEvents.none { it.id == editing.id }) listOf(editing) else emptyList()
            return effective.firstOrNull { it.mapId == mapId && it.tileX == tileX && it.tileY == tileY }
        }

        /**
         * Returns a deep copy of [source] assigned [newId], moved onto [targetMapId] (clamping ([tileX], [tileY]) to
         * [targetMapId]'s bounds from [catalogMaps] when placed on a map, or resetting to `(0, 0)` when `targetMapId == 0`).
         */
        fun duplicateToMap(
            source: EventDefinition,
            newId: Int,
            targetMapId: Int,
            tileX: Int = source.tileX,
            tileY: Int = source.tileY,
            catalogMaps: List<MapSummary> = emptyList(),
            newName: String = "${source.name} (copy)"
        ): EventDefinition {
            val safeMapId = targetMapId.coerceAtLeast(0)
            val safeX: Int
            val safeY: Int
            if (safeMapId == 0) {
                safeX = 0
                safeY = 0
            } else {
                val targetMap = catalogMaps.firstOrNull { it.id == safeMapId }
                safeX = if (targetMap != null) tileX.coerceIn(0, (targetMap.width - 1).coerceAtLeast(0)) else tileX.coerceAtLeast(0)
                safeY = if (targetMap != null) tileY.coerceIn(0, (targetMap.height - 1).coerceAtLeast(0)) else tileY.coerceAtLeast(0)
            }
            return source.copy(
                id = newId,
                name = newName.trim().ifBlank { "${source.name} (copy)" },
                mapId = safeMapId,
                tileX = safeX,
                tileY = safeY
            )
        }
    }
}
