package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.spatial.Direction

/**
 * Visual modal dialog allowing creators to browse and select item/spell icons.
 */
class VisualIconPickerDialog(
    private val stage: Stage,
    private val assets: ProceduralRetroAssets,
    private val maxIcons: Int = 32,
    private val onSelect: (iconId: Int) -> Unit
) {
    fun show(currentIconId: Int = 1) {
        val win = VisWindow("Visual Icon Picker — Click an Icon to Select")
        win.addCloseButton()
        HudWindow.hardenLastTitleButton(win)
        win.isModal = true
        win.defaults().pad(4f)

        val grid = VisTable()
        grid.defaults().pad(3f)

        for (id in 1..maxIcons) {
            val cell = VisTable()
            cell.defaults().pad(2f)
            val img = Image(TextureRegionDrawable(assets.getItemIconRegion(id)))
            cell.add(img).size(32f, 32f).row()

            val isCurrent = id == currentIconId
            val btnText = if (isCurrent) "★ #$id" else "#$id"
            val selectBtn = VisTextButton(btnText)
            selectBtn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    onSelect(id)
                    win.remove()
                }
            })
            cell.add(selectBtn).width(60f).row()

            grid.add(cell)
            if (id % 6 == 0) grid.row()
        }

        val scroll = VisScrollPane(grid)
        scroll.setFadeScrollBars(false)
        win.add(scroll).width(440f).height(320f).row()

        val cancelBtn = VisTextButton("Cancel")
        cancelBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                win.remove()
            }
        })
        win.add(cancelBtn).padTop(4f).row()

        win.pack()
        win.centerWindow()
        stage.addActor(win)
    }
}

/**
 * Visual modal dialog allowing creators to browse and select character/NPC directional sprites.
 */
class VisualSpritePickerDialog(
    private val stage: Stage,
    private val assets: ProceduralRetroAssets,
    private val maxSprites: Int = 16,
    private val onSelect: (spriteId: Int) -> Unit
) {
    fun show(currentSpriteId: Int = 1) {
        val win = VisWindow("Visual Sprite Picker — Click a Sprite Sheet to Select")
        win.addCloseButton()
        HudWindow.hardenLastTitleButton(win)
        win.isModal = true
        win.defaults().pad(4f)

        val grid = VisTable()
        grid.defaults().pad(4f)

        for (id in 1..maxSprites) {
            val cell = VisTable()
            cell.defaults().pad(2f)

            // Render down-facing idle frame
            val region = assets.getSpriteRegion(id, Direction.DOWN, 0)
            val img = Image(TextureRegionDrawable(region)).apply {
                setScaling(com.badlogic.gdx.utils.Scaling.fit)
            }
            val thumbH = if (region.regionHeight > region.regionWidth) 48f else 32f
            cell.add(img).size(32f, thumbH).padBottom(2f).row()

            val isCurrent = id == currentSpriteId
            val btnText = if (isCurrent) "★ Sprite #$id" else "Sprite #$id"
            val selectBtn = VisTextButton(btnText)
            selectBtn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    onSelect(id)
                    win.remove()
                }
            })
            cell.add(selectBtn).width(80f).row()

            grid.add(cell)
            if (id % 4 == 0) grid.row()
        }

        val scroll = VisScrollPane(grid)
        scroll.setFadeScrollBars(false)
        win.add(scroll).width(420f).height(320f).row()

        val cancelBtn = VisTextButton("Cancel")
        cancelBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                win.remove()
            }
        })
        win.add(cancelBtn).padTop(4f).row()

        win.pack()
        win.centerWindow()
        stage.addActor(win)
    }
}

/**
 * Visual modal dialog allowing creators to browse and select animation definitions.
 */
class VisualAnimationPickerDialog(
    private val stage: Stage,
    private val animations: List<AnimationDefinition>,
    private val onSelect: (animationId: Int) -> Unit
) {
    fun show(currentAnimationId: Int = 1) {
        val win = VisWindow("Visual Animation Picker")
        win.addCloseButton()
        HudWindow.hardenLastTitleButton(win)
        win.isModal = true
        win.defaults().pad(4f)

        val listTable = VisTable()
        listTable.defaults().pad(3f).fillX()

        if (animations.isEmpty()) {
            listTable.add(VisLabel("No animations defined in catalog.")).pad(10f).row()
        } else {
            for (anim in animations) {
                val row = VisTable()
                val isCurrent = anim.id == currentAnimationId
                val labelText = if (isCurrent) "★ #${anim.id} ${anim.name}" else "#${anim.id} ${anim.name}"
                val selectBtn = VisTextButton(labelText)
                selectBtn.addListener(object : ChangeListener() {
                    override fun changed(event: ChangeEvent?, actor: Actor?) {
                        onSelect(anim.id)
                        win.remove()
                    }
                })
                row.add(selectBtn).expandX().fillX().padRight(4f)
                val infoLabel = VisLabel("${anim.frameCount} frames @ ${anim.frameDurationMs}ms")
                row.add(infoLabel).right()
                listTable.add(row).fillX().row()
            }
        }

        val scroll = VisScrollPane(listTable)
        scroll.setFadeScrollBars(false)
        win.add(scroll).width(360f).height(240f).row()

        val cancelBtn = VisTextButton("Cancel")
        cancelBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                win.remove()
            }
        })
        win.add(cancelBtn).padTop(4f).row()

        win.pack()
        win.centerWindow()
        stage.addActor(win)
    }
}

