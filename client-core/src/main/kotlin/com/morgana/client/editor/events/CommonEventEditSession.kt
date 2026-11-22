package com.morgana.client.editor.events

import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.DialogueChoice
import com.morgana.domain.event.EventCommand
import com.morgana.protocol.WorldContentCatalogDto

/**
 * Pure editor state for one [CommonEventDefinition]: details, the step tree, selection, and undo/redo.
 * Nothing reaches the server until the Common events panel sends [draft].
 */
class CommonEventEditSession(initial: CommonEventDefinition, saved: CommonEventDefinition? = initial) : StepSequenceSession {
    var draft: CommonEventDefinition = initial
        private set

    /** The version the server has; null for a common event that was never saved. */
    var saved: CommonEventDefinition? = saved
        private set

    /** The selected step or answer path, or null. */
    override var selection: List<Int>? = null
        private set

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastTypingKey: String? = null

    private data class Snapshot(val draft: CommonEventDefinition, val selection: List<Int>?)

    val isDirty: Boolean get() = draft != saved
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call when the server's catalog holds [fresh] (after a save, or a change made elsewhere). */
    fun markSaved(fresh: CommonEventDefinition) { saved = fresh }

    private fun change(newDraft: CommonEventDefinition, newSelection: List<Int>? = selection, typingKey: String? = null) {
        if (newDraft != draft) {
            if (typingKey == null || typingKey != lastTypingKey) {
                undoStack.addLast(Snapshot(draft, selection))
                if (undoStack.size > EventEditSession.HISTORY) undoStack.removeFirst()
            }
            lastTypingKey = typingKey
            redoStack.clear()
            draft = newDraft
        }
        selection = newSelection?.takeIf { EventStepTree.exists(draft.commands, it) }
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(draft, selection))
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(draft, selection))
        restore(s)
    }

    private fun restore(s: Snapshot) {
        lastTypingKey = null
        draft = s.draft
        selection = s.selection?.takeIf { EventStepTree.exists(draft.commands, it) }
    }

    fun setCommonEventValues(values: Map<String, String>) =
        change(EventForms.applyCommonEvent(draft, values), typingKey = "common_event")

    // ---- steps ----

    override fun rows(catalog: WorldContentCatalogDto): List<EventRow> = EventForms.rows(draft.commands, catalog)

    override fun select(path: List<Int>?) {
        selection = path?.takeIf { EventStepTree.exists(draft.commands, it) }
        lastTypingKey = null
    }

    override val selectedIsFixedBranch: Boolean
        get() = EventStepTree.selectedIsFixedBranch(draft.commands, selection)

    override val canEditSelection: Boolean
        get() = EventStepTree.canEditSelection(draft.commands, selection)

    override val selectedCommand: EventCommand?
        get() = EventStepTree.selectedCommand(draft.commands, selection)

    override val selectedChoice: DialogueChoice?
        get() = EventStepTree.selectedChoice(draft.commands, selection)

    override val canAddChoice: Boolean
        get() = EventStepTree.dialoguePath(draft.commands, selection) != null

    fun commandAt(path: List<Int>): EventCommand? = EventStepTree.commandAt(draft.commands, path)

    fun choiceAt(path: List<Int>): DialogueChoice? = EventStepTree.choiceAt(draft.commands, path)

    private fun applySteps(updated: Pair<List<EventCommand>, List<Int>?>?, typingKey: String? = null) {
        val (commands, newSel) = updated ?: return
        change(draft.copy(commands = commands), newSelection = newSel, typingKey = typingKey)
    }

    override fun addCommand(command: EventCommand) = applySteps(EventStepTree.addCommand(draft.commands, selection, command))

    override fun addChoice() = applySteps(EventStepTree.addChoice(draft.commands, selection))

    override fun setSelectedValues(values: Map<String, String>) {
        val updated = EventStepTree.setSelectedValues(draft.commands, selection, values) ?: return
        applySteps(updated to selection, typingKey = "step $selection")
    }

    override fun updateSelectedCommand(transform: (EventCommand) -> EventCommand) {
        val updated = EventStepTree.updateSelectedCommand(draft.commands, selection, transform) ?: return
        applySteps(updated to selection)
    }

    override fun deleteSelected() = applySteps(EventStepTree.deleteSelected(draft.commands, selection))

    override fun duplicateSelected() = applySteps(EventStepTree.duplicateSelected(draft.commands, selection))

    override fun canMove(delta: Int): Boolean = EventStepTree.canMove(draft.commands, selection, delta)

    override fun moveSelected(delta: Int) = applySteps(EventStepTree.moveSelected(draft.commands, selection, delta))

    fun problems(catalog: WorldContentCatalogDto): List<String> = EventForms.commonEventProblems(draft, catalog)

    fun usedBy(catalog: WorldContentCatalogDto): String = EventForms.commonEventUsedBy(draft, catalog)

    override fun commandsFromSelection(): List<EventCommand> = EventStepTree.commandsFromSelection(draft.commands, selection)
}
