package com.morgana.client.ui.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.AnimationStripLayout
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.ArtGallery
import com.morgana.client.editor.ArtImportValidator
import com.morgana.client.editor.ArtKind
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.game.SpriteState
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.spatial.Direction
import com.morgana.protocol.realms.AssetKind

/**
 * The Art panel: a gallery of every picture the game has, by kind, with live animated Sprite & Effect previews and
 * optional in-editor PNG import (`onPickPngFile` + `onUploadPng`). Pictures the game supplied itself carry a gold corner
 * mark; the line under the grid reports custom counts and missing IDs referenced by content.
 */
class ArtPanel(
    stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val onPickPngFile: (((ByteArray?) -> Unit) -> Unit)? = null,
    private val onUploadPng: ((AssetKind, String, ByteArray) -> Unit)? = null
) {
    val content: Actor

    private var kind = ArtKind.ICONS
    private val grid = VisTable()
    private val summary = theme.muted("")
    private val about = theme.muted("")
    private val previewPane = VisTable()
    private val previewControls = VisTable()
    private val previewImage = Image().apply { setScaling(Scaling.fit) }
    private val previewInfo = theme.muted("")
    private val importIdField = VisTextField("1")
    private val importStatus = theme.muted("")
    private var localImportStatus: Pair<String, Boolean>? = null
    private var lastServerStatusSeen: String? = null
    private var selectedSpriteId: Int = 1
    private var selectedAnimationId: Int = 1
    private var selectedPaperdollId: Int = 101
    private var previewDir: Direction = Direction.DOWN
    private var previewState: SpriteState = SpriteState.WALK
    private var previewElapsed: Float = 0f
    private var shown: List<Any?>? = null
    private val kindButtons = ArtKind.entries.associateWith { k -> VisTextButton(k.title, theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) } }

    init {
        about.setWrap(true)
        summary.setWrap(true)
        previewInfo.setWrap(true)
        importStatus.setWrap(true)
        val tabs = VisTable()
        for ((k, b) in kindButtons) {
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (b.isChecked) {
                        kind = k
                        shown = null
                        importIdField.setText((assets.artIds(k).firstOrNull() ?: 1).toString())
                    }
                }
            })
            tabs.add(b).padRight(2f)
        }
        theme.selectGroup(*kindButtons.values.toTypedArray())
        kindButtons.getValue(kind).isChecked = true

        buildSpritePreviewPane()

        val importBar = VisTable()
        importBar.left()
        val pick = onPickPngFile
        val upload = onUploadPng
        if (pick != null && upload != null) {
            importBar.add(theme.muted("Target ID:")).padRight(4f)
            importBar.add(importIdField).width(64f).padRight(6f)
            val importBtn = theme.textButton("Import PNG...") {
                val targetId = importIdField.text.trim()
                val targetKind = kind
                val idError = ArtImportValidator.validateAssetId(targetKind.assetKind, targetId)
                if (idError != null) {
                    localImportStatus = idError to true
                    return@textButton
                }
                pick { picked ->
                    val bytes = picked ?: return@pick
                    val problem = ArtImportValidator.validatePngUpload(
                        targetKind.assetKind,
                        targetId,
                        bytes,
                        worldState.gameDefinition?.sprites
                    )
                    if (problem != null) {
                        localImportStatus = problem to true
                    } else {
                        localImportStatus = "Uploading ${targetKind.folder}/$targetId.png..." to false
                        upload(targetKind.assetKind, targetId, bytes)
                    }
                }
            }
            importBar.add(importBtn).left()
        }

        grid.top().left()
        val pane = VisScrollPane(grid)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)

        val root = LiveTable { follow() }
        root.add(tabs).left().row()
        root.add(about).growX().minWidth(0f).pad(2f).row()
        root.add(pane).grow().row()
        root.add(previewPane).growX().minWidth(0f).padTop(4f).row()
        if (pick != null && upload != null) {
            root.add(importBar).growX().minWidth(0f).padTop(4f).row()
            root.add(importStatus).growX().minWidth(0f).pad(2f).row()
        }
        root.add(summary).growX().minWidth(0f).pad(2f).row()
        content = root
    }

    private fun buildSpritePreviewPane() {
        previewPane.left().defaults().left().pad(2f)
        val dirRow = VisTable()
        val dirButtons = mapOf(
            Direction.DOWN to VisTextButton("Down", theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) },
            Direction.LEFT to VisTextButton("Left", theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) },
            Direction.RIGHT to VisTextButton("Right", theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) },
            Direction.UP to VisTextButton("Up", theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) }
        )
        theme.selectGroup(*dirButtons.values.toTypedArray())
        dirButtons.getValue(previewDir).isChecked = true
        for ((dir, btn) in dirButtons) {
            btn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { if (btn.isChecked) previewDir = dir }
            })
            dirRow.add(btn).padRight(2f)
        }

        val stateRow = VisTable()
        val stateButtons = SpriteState.entries.associateWith { st ->
            VisTextButton(st.name.lowercase(), theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) }
        }
        theme.selectGroup(*stateButtons.values.toTypedArray())
        stateButtons.getValue(previewState).isChecked = true
        for ((st, btn) in stateButtons) {
            btn.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { if (btn.isChecked) previewState = st }
            })
            stateRow.add(btn).padRight(2f)
        }

        val controls = VisTable()
        controls.defaults().left().padBottom(2f)
        previewControls.defaults().left().padBottom(2f)
        previewControls.add(dirRow).row()
        previewControls.add(stateRow).row()
        controls.add(previewControls).row()
        controls.add(previewInfo).growX().minWidth(0f).row()

        previewPane.add(previewImage).size(48f, 64f).padRight(6f)
        previewPane.add(controls).growX().minWidth(0f)
    }

    private fun follow() {
        val ids = assets.artIds(kind)
        if (kind == ArtKind.SPRITES || kind == ArtKind.ANIMATIONS || kind == ArtKind.PAPERDOLLS) {
            previewPane.isVisible = true
            previewElapsed += (Gdx.graphics?.deltaTime ?: (1f / 60f)).coerceIn(0f, 0.1f)
            when (kind) {
                ArtKind.SPRITES -> {
                    previewControls.isVisible = true
                    if (selectedSpriteId !in ids && ids.isNotEmpty()) selectedSpriteId = ids.first()
                    val walkStep = 1 + (((previewElapsed * 6f).toInt()) % 2)
                    val region = assets.getSpriteRegion(
                        spriteId = selectedSpriteId,
                        direction = previewDir,
                        walkFrame = walkStep,
                        state = previewState,
                        elapsedSeconds = previewElapsed
                    )
                    previewImage.drawable = TextureRegionDrawable(region)
                    val profile = assets.spriteProfile ?: SpriteProfile.CLASSIC
                    previewInfo.setText(
                        "Sprite #$selectedSpriteId — cell ${profile.cellWidth}x${profile.cellHeight}, sheet ${profile.sheetWidth}x${profile.sheetHeight} (${profile.totalFrames}x4)"
                    )
                }
                ArtKind.PAPERDOLLS -> {
                    previewControls.isVisible = true
                    if (selectedPaperdollId !in ids && ids.isNotEmpty()) selectedPaperdollId = ids.first()
                    val matchingItem = worldState.catalog.items.firstOrNull { it.paperdollSpriteId == selectedPaperdollId }
                    val slot = matchingItem?.paperdollSlot
                        ?: matchingItem?.equipmentSlotOrNull()?.defaultPaperdollSlot()
                        ?: PaperdollSlot.MAIN_HAND
                    val walkStep = 1 + (((previewElapsed * 6f).toInt()) % 2)
                    val previewLayer = PaperdollLayer(
                        slot = slot,
                        spriteId = selectedPaperdollId,
                        tintHex = matchingItem?.paperdollTintHex,
                        hidesSlots = matchingItem?.paperdollHidesSlots ?: emptySet()
                    )
                    val region = assets.getOrComposeCharacterRegion(
                        characterId = "__art_preview__",
                        spriteId = 1,
                        layers = listOf(previewLayer),
                        direction = previewDir,
                        walkFrame = walkStep,
                        state = previewState,
                        elapsedSeconds = previewElapsed
                    ) ?: assets.getSpriteRegion(1, previewDir, walkStep, previewState, previewElapsed)
                    previewImage.drawable = TextureRegionDrawable(region)
                    val frameInfo = assets.getPaperdollLayerFrame(selectedPaperdollId, previewDir, walkStep, previewState, previewElapsed)
                    val kindNote = if (frameInfo?.second == true) "directional sheet" else "square overlay"
                    previewInfo.setText(
                        "Paperdoll #$selectedPaperdollId ($slot, $kindNote) over Sprite #1 (${matchingItem?.name ?: "unlinked"})"
                    )
                }
                else -> {
                    previewControls.isVisible = false
                    if (selectedAnimationId !in ids && ids.isNotEmpty()) selectedAnimationId = ids.first()
                    val available = assets.animationFrameCount(selectedAnimationId)
                    val matchingDef = worldState.catalog.animations.firstOrNull { it.spriteSheetId == selectedAnimationId }
                    val frameDurationMs = matchingDef?.frameDurationMs ?: AnimationStripLayout.DEFAULT_FRAME_DURATION_MS
                    val defFrames = matchingDef?.frameCount ?: available
                    val frameIdx = AnimationStripLayout.resolveFrameIndex(
                        elapsedSeconds = previewElapsed,
                        frameDurationMs = frameDurationMs,
                        definitionFrames = defFrames,
                        availableFrames = available,
                        loop = true
                    )
                    val region = assets.getAnimationFrame(selectedAnimationId, frameIdx)
                    previewImage.drawable = TextureRegionDrawable(region)
                    previewInfo.setText(
                        "Effect #$selectedAnimationId — $available frame(s) (${region.regionWidth}x${region.regionHeight}), ${frameDurationMs}ms/frame"
                    )
                }
            }
        } else {
            previewPane.isVisible = false
        }

        val serverMsg = worldState.lastArtImportMessage
        if (serverMsg != null && serverMsg != lastServerStatusSeen) {
            lastServerStatusSeen = serverMsg
            localImportStatus = serverMsg to worldState.lastArtImportError
        }
        val statusPair = localImportStatus
        if (statusPair != null) {
            importStatus.setText(statusPair.first)
            importStatus.color = if (statusPair.second) theme.palette.danger else theme.palette.success
        }

        val custom = ids.filter { assets.isCustomArt(kind, it) }
        val signature = listOf(kind, ids, custom, worldState.catalog.items.size, worldState.catalog.npcs.size, worldState.catalog.animations.size, worldState.lastArtImportMessage, assets.spriteProfile)
        if (signature == shown) return
        shown = signature
        about.setText(kind.about)
        grid.clearChildren()
        val perRow = 8
        ids.forEachIndexed { i, id ->
            val region = assets.artRegion(kind, id) ?: return@forEachIndexed
            val cell = VisTable()
            val img = Image(TextureRegionDrawable(region)).apply { setScaling(Scaling.fit) }
            val frame = Stack(img)
            if (id in custom) {
                val corner = VisTable()
                corner.top().right()
                corner.add(Image(theme.solid(Color(0.98f, 0.8f, 0.2f, 1f)))).size(8f)
                frame.add(corner)
            }
            frame.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    importIdField.setText(id.toString())
                    when (kind) {
                        ArtKind.SPRITES -> selectedSpriteId = id
                        ArtKind.PAPERDOLLS -> selectedPaperdollId = id
                        ArtKind.ANIMATIONS -> selectedAnimationId = id
                        else -> {}
                    }
                }
            })
            val thumbH = if (kind == ArtKind.SPRITES && region.regionHeight > region.regionWidth) 64f else 48f
            cell.add(frame).size(48f, thumbH).row()
            cell.add(theme.text(id.toString())).row()
            grid.add(cell).pad(3f)
            if ((i + 1) % perRow == 0) grid.row()
        }
        if (ids.isEmpty()) grid.add(theme.muted("No pictures of this kind yet."))
        summary.setText(ArtGallery.summary(kind, ids.size, custom.size, ArtGallery.missing(kind, worldState.catalog, ids)))
    }
}
