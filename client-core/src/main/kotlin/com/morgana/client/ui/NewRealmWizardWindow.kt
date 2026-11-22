package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisRadioButton
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisWindow
import com.morgana.client.LocalWorldChoice
import com.morgana.client.LocalWorldHost
import com.morgana.client.realms.RealmWizardModel

/**
 * The New Realm Wizard: three steps (name and starting point, features, review) that end in a ready-to-play realm.
 * All rules live in [RealmWizardModel]; this window only draws it and calls [LocalWorldHost.createRealm].
 */
class NewRealmWizardWindow(
    private val stage: Stage,
    private val host: LocalWorldHost,
    private val onCreated: (LocalWorldChoice) -> Unit
) {
    private val model = RealmWizardModel(host.realmTemplates)
    private var window: VisWindow? = null
    private var backdrop: Image? = null
    private var error: String = ""

    private fun ensureTheme(): GameTheme = GameTheme.shared

    fun show() {
        close()
        val uiTheme = ensureTheme()
        val dim = uiTheme.backdrop()
        backdrop = dim
        stage.addActor(dim)

        val win = VisWindow("New Realm Wizard", uiTheme.modalWindowStyle)
        win.addCloseButton()
        win.isMovable = true
        win.isModal = true
        window = win
        // Keyboard: Esc cancels, Enter goes on (Create Realm on the last step) when that button is enabled.
        win.addCaptureListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
            override fun keyDown(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, keycode: Int): Boolean {
                when (keycode) {
                    com.badlogic.gdx.Input.Keys.ESCAPE -> close()
                    com.badlogic.gdx.Input.Keys.ENTER, com.badlogic.gdx.Input.Keys.NUMPAD_ENTER -> forwardAction?.invoke() ?: return false
                    else -> return false
                }
                return true
            }
        })
        render()
        stage.addActor(win)
        win.centerWindow()
    }

    fun close() {
        window?.remove()
        window = null
        backdrop?.remove()
        backdrop = null
    }

    /** What Enter does right now: the forward button's action, or null while it is disabled. */
    private var forwardAction: (() -> Unit)? = null

    private fun render() {
        val win = window ?: return
        val uiTheme = ensureTheme()
        win.clearChildren()
        win.defaults().pad(4f).left()

        val steps = VisTable()
        RealmWizardModel.Step.entries.forEachIndexed { idx, s ->
            if (idx > 0) steps.add(VisLabel("  >  ", uiTheme.mutedLabelStyle))
            val style = if (s == model.step) uiTheme.accentLabelStyle else uiTheme.mutedLabelStyle
            steps.add(VisLabel("${idx + 1}. ${s.title}", style))
        }
        win.add(steps).colspan(3).padBottom(2f).row()
        win.add(uiTheme.divider()).colspan(3).fillX().padBottom(4f).row()
        val body = VisTable()
        body.defaults().left().pad(3f)
        when (model.step) {
            RealmWizardModel.Step.BASICS -> basics(body)
            RealmWizardModel.Step.FEATURES -> features(body)
            RealmWizardModel.Step.REVIEW -> review(body)
        }
        win.add(body).colspan(3).width(520f).row()

        val problem = if (error.isNotEmpty()) error else model.blockingProblem().orEmpty()
        val problemLabel = VisLabel(problem)
        problemLabel.setWrap(true)
        win.add(problemLabel).colspan(3).width(520f).row()

        val back = button("Back", uiTheme.buttonStyle) { if (model.back()) { error = ""; render() } }
        back.isDisabled = model.step == RealmWizardModel.Step.BASICS
        val cancel = button("Cancel", uiTheme.buttonStyle) { close() }
        val forward = if (model.isLastStep) {
            button("Create Realm", uiTheme.primaryButtonStyle) { create() }
        } else {
            button("Next", uiTheme.primaryButtonStyle) { if (model.next()) { error = ""; render() } }
        }
        forward.isDisabled = !model.canAdvance
        forwardAction = if (model.canAdvance) ({ if (model.isLastStep) create() else if (model.next()) { error = ""; render() } }) else null
        win.add(cancel).left()
        win.add(back).right().expandX()
        win.add(forward).right()
        win.pack()
        win.centerWindow()
    }

    private fun basics(body: VisTable) {
        body.add(VisLabel("Realm name:")).left()
        val nameField = VisTextField(model.name)
        nameField.setMaxLength(RealmWizardModel.MAX_NAME)
        nameField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                model.name = nameField.text
                error = ""
                refreshButtonsOnly()
            }
        })
        body.add(nameField).width(300f).row()

        body.add(VisLabel("Start from:")).colspan(2).padTop(8f).row()
        val group = ButtonGroup<VisRadioButton>()
        group.setMaxCheckCount(1)
        group.setMinCheckCount(1)
        for (t in model.templates) {
            val radio = VisRadioButton(t.title)
            radio.isChecked = t.id == model.templateId
            group.add(radio)
            radio.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (radio.isChecked) model.templateId = t.id
                }
            })
            body.add(radio).left().colspan(2).row()
            val description = VisLabel(t.description)
            description.setWrap(true)
            body.add(description).colspan(2).width(500f).padLeft(24f).padBottom(4f).row()
        }
    }

    private fun features(body: VisTable) {
        val uiTheme = ensureTheme()
        val note = VisLabel("Start from a genre or a preset, then adjust individual systems. Switched-off systems are hidden from players and refused by the server.")
        note.setWrap(true)
        body.add(note).colspan(2).width(500f).row()

        val genreRow = HorizontalGroup().apply {
            wrap(true)
            space(4f)
            wrapSpace(4f)
            align(com.badlogic.gdx.utils.Align.left)
        }
        for (g in RealmWizardModel.GENRES) {
            genreRow.addActor(button(g.title, uiTheme.buttonStyle) { model.applyPreset(g); render() })
        }
        body.add(VisLabel("Genre:", uiTheme.mutedLabelStyle)).colspan(2).left().row()
        body.add(genreRow).colspan(2).width(500f).left().row()
        body.add(VisLabel("Or a preset:", uiTheme.mutedLabelStyle)).colspan(2).left().row()

        val presetRow = HorizontalGroup().apply {
            wrap(true)
            space(4f)
            wrapSpace(4f)
            align(com.badlogic.gdx.utils.Align.left)
        }
        for (p in RealmWizardModel.PRESETS) {
            presetRow.addActor(button(p.title, uiTheme.buttonStyle) { model.applyPreset(p); render() })
        }
        body.add(presetRow).colspan(2).width(500f).left().row()
        val active = model.activePreset()
        if (active != null) {
            val label = VisLabel("${active.title}: ${active.description}")
            label.setWrap(true)
            body.add(label).colspan(2).width(500f).row()
        }

        val list = VisTable()
        list.defaults().left().pad(3f)

        val peacefulBox = VisCheckBox("Peaceful realm (no combat or spells)")
        peacefulBox.isChecked = model.noCombat
        peacefulBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                model.noCombat = peacefulBox.isChecked
            }
        })
        list.add(peacefulBox).left().colspan(2).row()

        val modernSpritesBox = VisCheckBox("Modern taller sprites (32 x 64 multi-state characters)")
        modernSpritesBox.isChecked = model.modernSprites
        modernSpritesBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                model.modernSprites = modernSpritesBox.isChecked
            }
        })
        list.add(modernSpritesBox).left().colspan(2).row()

        for (group in model.groups) {
            val heading = VisTable()
            heading.add(VisLabel(group.title, uiTheme.accentLabelStyle)).left().padRight(8f)
            heading.add(button("All on", uiTheme.buttonStyle) { model.setGroupEnabled(group, true); render() }).padRight(4f)
            heading.add(button("All off", uiTheme.buttonStyle) { model.setGroupEnabled(group, false); render() })
            list.add(heading).left().colspan(2).padTop(8f).row()
            for (module in group.systems) {
                val box = VisCheckBox(module.displayName)
                box.isChecked = model.isEnabled(module)
                box.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        model.setEnabled(module, box.isChecked)
                    }
                })
                list.add(box).left().colspan(2).row()
                if (module.description.isNotEmpty()) {
                    val d = VisLabel(module.description)
                    d.setWrap(true)
                    list.add(d).colspan(2).width(460f).padLeft(24f).row()
                }
            }
        }

        val scroll = VisScrollPane(list)
        scroll.setFadeScrollBars(false)
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        val maxScrollH = (stage.height - 280f).coerceIn(160f, 340f)
        body.add(scroll).colspan(2).width(512f).height(maxScrollH).row()
    }

    private fun review(body: VisTable) {
        for (line in model.summaryLines()) {
            val l = VisLabel(line)
            l.setWrap(true)
            body.add(l).width(500f).row()
        }
    }

    private fun create() {
        try {
            val created = host.createRealm(model.toSpec())
            close()
            onCreated(created)
        } catch (e: Exception) {
            error = "Could not create the realm: ${e.message}"
            render()
        }
    }

    /** Re-evaluates the Next button after typing, without rebuilding the whole window (which would drop text focus). */
    private fun refreshButtonsOnly() {
        val win = window ?: return
        for (cell in win.cells) {
            val actor = cell.actor
            if (actor is VisTextButton && actor.text.toString() == "Next") actor.isDisabled = !model.canAdvance
        }
    }

    private fun button(text: String, style: VisTextButton.VisTextButtonStyle? = null, action: () -> Unit) =
        (if (style != null) VisTextButton(text, style) else VisTextButton(text)).also { b ->
        b.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (!b.isDisabled) action()
            }
        })
    }
}
