package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.events.EventCommandKind
import com.morgana.client.editor.events.EventForms
import com.morgana.client.editor.events.StepSequenceSession
import com.morgana.client.editor.forms.FormField
import com.morgana.client.editor.forms.FormRules
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.event.EventCommand

internal fun verticalScrollPane(stage: Stage, table: VisTable): VisScrollPane {
    val pane = VisScrollPane(table)
    pane.setScrollingDisabled(true, false)
    pane.setFadeScrollBars(false)
    pane.setOverscroll(false, false)
    giveScrollFocusOnHover(stage, pane)
    return pane
}

/**
 * Shared right-hand column for event and common-event editors: the step tree, ordering/duplicate/delete buttons,
 * step kind picker, and the selected step's form.
 */
internal class StepTreePane(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val sessionProvider: () -> StepSequenceSession?,
    private val onStateChanged: () -> Unit
) {
    val content: VisTable = VisTable()

    private val stepRows = VisTable()
    private val stepForm = VisTable()
    private val kindExplain = theme.muted("")
    private val kindBox = VisSelectBox<String>()
    private val kinds = EventCommandKind.entries
    private val typedStep = linkedMapOf<String, String>()
    private var stepView: FormFieldsView? = null

    private val addStepButton = theme.textButton("Add step") { addStep() }
    private val addAnswerButton = theme.textButton("Add answer") { structural { it.addChoice() } }
    private val upButton = theme.textButton("Up") { structural { it.moveSelected(-1) } }
    private val downButton = theme.textButton("Down") { structural { it.moveSelected(1) } }
    private val copyButton = theme.textButton("Copy") { structural { it.duplicateSelected() } }
    private val deleteButton = theme.textButton("Delete") { structural { it.deleteSelected() } }

    init {
        kindExplain.setWrap(true)
        kindBox.setItems(*kinds.map { "${it.group}: ${it.label}" }.toTypedArray())
        kindBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                onStateChanged()
            }
        })

        stepRows.top()
        stepRows.defaults().left().growX().pad(1f)
        stepForm.top()
        stepForm.defaults().left().growX().pad(2f)

        val addRow = VisTable()
        addRow.add(kindBox).growX().minWidth(0f).padRight(4f)
        addRow.add(addStepButton)
        val moveRow = VisTable()
        moveRow.defaults().padRight(4f)
        for (b in listOf(upButton, downButton, copyButton, deleteButton, addAnswerButton)) moveRow.add(b)
        moveRow.add().growX()

        content.defaults().growX().pad(2f)
        content.add(theme.heading("What happens, in order")).row()
        content.add(verticalScrollPane(stage, stepRows)).grow().minHeight(110f).row()
        content.add(moveRow).row()
        content.add(addRow).row()
        content.add(kindExplain).minWidth(0f).row()
        content.add(theme.heading("Selected step")).padTop(4f).row()
        content.add(verticalScrollPane(stage, stepForm)).growX().height(160f).row()
    }

    fun rebuild() {
        rebuildSteps()
        rebuildStepForm()
    }

    fun typedProblems(s: StepSequenceSession): List<String> {
        val out = ArrayList<String>()
        out += FormRules.fieldProblems(selectedFields(s), typedStep)
        if (s.selectedCommand is EventCommand.InvokeKotlinHook) {
            out += EventForms.parsePayload(typedStep["payload"].orEmpty()).second
        }
        return out
    }

    fun refreshState(s: StepSequenceSession?) {
        kindExplain.setText(kinds.getOrNull(kindBox.selectedIndex)?.explain.orEmpty())
        if (s == null) {
            for (b in listOf(addStepButton, addAnswerButton, upButton, downButton, copyButton, deleteButton)) {
                b.isDisabled = true
            }
            return
        }
        addStepButton.isDisabled = false
        addAnswerButton.isDisabled = !s.canAddChoice
        upButton.isDisabled = !s.canMove(-1)
        downButton.isDisabled = !s.canMove(1)
        copyButton.isDisabled = !s.canEditSelection
        deleteButton.isDisabled = !s.canEditSelection
        stepView?.refreshHints()
    }

    private fun rebuildSteps() {
        stepRows.clearChildren()
        val s = sessionProvider() ?: return
        val rows = s.rows(worldState.catalog)
        if (rows.isEmpty()) stepRows.add(theme.muted("No steps yet. Choose one below and press Add step.")).row()
        for (row in rows) {
            val b = theme.listButton(row.text, row.path == s.selection) {
                sessionProvider()?.select(row.path)
                rebuildSteps()
                rebuildStepForm()
                onStateChanged()
            }
            stepRows.add(b).growX().minWidth(0f).padLeft(1f + row.depth * INDENT).row()
        }
    }

    private fun selectedFields(s: StepSequenceSession): List<FormField> =
        s.selectedCommand?.let { EventForms.fieldsFor(it) } ?: if (s.selectedChoice != null) EventForms.choiceFields else emptyList()

    private fun rebuildStepForm() {
        stepForm.clearChildren()
        typedStep.clear()
        stepView = null
        val s = sessionProvider() ?: return
        val cmd = s.selectedCommand
        val choice = s.selectedChoice
        if (s.selectedIsFixedBranch) {
            val which = if (s.selection?.last() == EventForms.THEN) "when the check is true" else "when the check is not true"
            stepForm.add(theme.muted("These steps run $which. With this line selected, Add step puts the step under it.").also { it.setWrap(true) }).minWidth(0f).row()
            return
        }
        if (cmd == null && choice == null) {
            stepForm.add(theme.muted("Click a step above to change it. New steps go after the selected one, or at the end.").also { it.setWrap(true) }).minWidth(0f).row()
            return
        }
        if (cmd != null) typedStep.putAll(EventForms.valuesOf(cmd)) else typedStep["label"] = choice!!.label
        val view = FormFieldsView(stage, theme, assets, worldState, typedStep) { typedInStep() }
        stepView = view
        if (cmd != null) {
            val kind = EventForms.kindOf(cmd)
            stepForm.add(theme.text(kind.label)).row()
            val fields = EventForms.fieldsFor(cmd)
            if (fields.isEmpty()) stepForm.add(theme.muted("This step has nothing to set.")).row()
            for (f in fields) view.add(stepForm, f)
            if (cmd is EventCommand.Conditional) {
                val condButtons = VisTable()
                condButtons.defaults().padRight(4f)
                condButtons.add(theme.textButton("Add condition") {
                    structural { it.updateSelectedCommand { c -> if (c is EventCommand.Conditional) EventForms.withAddedCondition(c) else c } }
                })
                if (cmd.extraConditions.isNotEmpty()) {
                    condButtons.add(theme.textButton("Remove last condition") {
                        structural { it.updateSelectedCommand { c -> if (c is EventCommand.Conditional) EventForms.withRemovedLastCondition(c) else c } }
                    })
                }
                condButtons.add().growX()
                stepForm.add(condButtons).row()
                stepForm.add(theme.muted("Select Then or Otherwise in the list above to add steps to that side.").also { it.setWrap(true) }).minWidth(0f).row()
            }
            if (cmd is EventCommand.ShowDialogue) {
                stepForm.add(theme.muted("Press Add answer to let the player choose a reply. Steps added under an answer run only for that reply.").also { it.setWrap(true) }).minWidth(0f).row()
            }
        } else {
            stepForm.add(theme.text("Answer")).row()
            for (f in EventForms.choiceFields) view.add(stepForm, f)
            stepForm.add(theme.muted("With this answer selected, Add step puts the step under it.").also { it.setWrap(true) }).minWidth(0f).row()
        }
        view.refreshHints()
    }

    private fun typedInStep() {
        val s = sessionProvider() ?: return
        val ok = FormRules.fieldProblems(selectedFields(s), typedStep).isEmpty() &&
            (s.selectedCommand !is EventCommand.InvokeKotlinHook || EventForms.parsePayload(typedStep["payload"].orEmpty()).second.isEmpty())
        if (ok) {
            val before = selectedFields(s).map { it.key }
            s.setSelectedValues(typedStep)
            rebuildSteps()
            // An If step shows different fields for each kind of check: redraw the form when the kind changed.
            if (selectedFields(s).map { it.key } != before) rebuildStepForm()
        }
        onStateChanged()
    }

    private fun structural(action: (StepSequenceSession) -> Unit) {
        val s = sessionProvider() ?: return
        action(s)
        rebuild()
        onStateChanged()
    }

    private fun addStep() {
        val kind = kinds.getOrNull(kindBox.selectedIndex) ?: return
        structural { it.addCommand(EventForms.create(kind, worldState.catalog)) }
    }

    private companion object {
        const val INDENT = 14f
    }
}

