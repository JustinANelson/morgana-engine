package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.MapEditorTool
import com.morgana.client.editor.dock.DockZone
import com.morgana.client.editor.dock.LayoutLibrary
import com.morgana.client.editor.dock.LayoutPreset
import com.morgana.client.editor.dock.ViewInsets
import com.morgana.client.editor.forms.AnimationForm
import com.morgana.client.editor.forms.ItemForm
import com.morgana.client.ui.editor.PanelFinder
import com.morgana.client.ui.editor.PreferencesKeyValueStore
import com.morgana.client.editor.forms.NpcForm
import com.morgana.client.editor.forms.QuestForm
import com.morgana.client.editor.forms.ActionSkillForm
import com.morgana.client.editor.forms.ClassForm
import com.morgana.client.editor.forms.WeaponSetForm
import com.morgana.client.editor.forms.RecipeForm
import com.morgana.client.editor.forms.ResourceForm
import com.morgana.client.editor.forms.ShopForm
import com.morgana.client.editor.forms.SpellForm
import com.morgana.client.ui.editor.ContentEditorPanel
import com.morgana.client.ui.editor.EditorPanel
import com.morgana.client.ui.editor.EditorTheme
import com.morgana.client.ui.editor.EditorWorkspace
import com.morgana.client.ui.editor.AttributePanel
import com.morgana.client.ui.editor.LayersPanel
import com.morgana.client.ui.editor.MapListPanel
import com.morgana.client.ui.editor.MapPropertiesPanel
import com.morgana.client.ui.editor.LiveTable
import com.morgana.client.ui.editor.onChange
import com.morgana.client.ui.editor.TilesetPanel
import com.morgana.client.ui.editor.ArtPanel
import com.morgana.client.ui.editor.HelpPanel
import com.morgana.client.ui.editor.PreferencesLayoutStore
import com.morgana.domain.item.ItemDefinition
import com.morgana.protocol.ClientPacket
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisWindow

/**
 * Full-featured Morgana F1 Live In-Game Studio built with VisUI.
 * Provides interactive visual palettes, master-detail lists, live previews, and instant server broadcast.
 */
class F1LiveAdminPanel(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    /** Zooms the world view in (+1) or out (-1); the host owns the camera. */
    private val onStepWorldZoom: (Int) -> Unit = {},
    /** Platform file picker for PNG art uploads in [ArtPanel]; null hides/disables desktop/browser picking. */
    private val onPickPngFile: (((ByteArray?) -> Unit) -> Unit)? = null,
    /** The world zoom as shown on the status bar ("125%"). */
    private val worldZoomLabel: () -> String = { "100%" }
) {
    private var workspace: EditorWorkspace? = null
    private var panelFinder: PanelFinder? = null
    private val contentContainer = VisTable()

    /** The developer's editor settings (the colour scheme), kept in the platform preferences. */
    private val settings = com.morgana.client.ui.editor.EditorSettings(PreferencesKeyValueStore("morgana-editor-settings"))

    /** The editor's look and icons; created on first use because it needs a loaded VisUI and graphics context. */
    private var themeOrNull: EditorTheme? = null
    private val theme: EditorTheme
        get() = themeOrNull ?: EditorTheme(settings.palette).also { themeOrNull = it }

    /** Frees the editor theme's textures (the icon sheet and panel drawables). */
    fun dispose() {
        themeOrNull?.dispose()
        themeOrNull = null
    }

    /**
     * Saves [scheme] and redraws the whole editor in it: every editor is rebuilt with a new theme, keeping the map being
     * edited (it lives in the world state) and reopening Settings where it was.
     */
    private fun applyColorScheme(scheme: com.morgana.client.ui.editor.EditorPalette) {
        settings.palette = scheme
        SkinThemer.apply(scheme)
        val old = themeOrNull
        panelFinder?.close()
        workspace?.hide()
        workspace = null
        themeOrNull = null
        open()
        workspace?.openPanel("settings")
        old?.dispose()
    }

    /** How much of the screen the docked editor panels cover, in stage units; the world is centred in the rest. */
    fun viewInsets(): ViewInsets = workspace?.insets() ?: ViewInsets.NONE

    /** Keeps the panels fitted to the screen; call every frame (cheap unless the size changed). */
    fun relayoutIfResized() {
        workspace?.relayoutIfResized()
    }

    fun toggle() {
        val existing = workspace
        if (existing != null && existing.isShown) {
            if (closePrompt?.stage != null) {
                dismissClosePrompt() // F1 again means "keep editing"
            } else if (worldState.editor.isDirty) {
                showClosePrompt()
            } else {
                closeEditor()
            }
            return
        }
        open()
    }

    /** For the automated smoke test (see [com.morgana.client.probe.GameProbe]): whether the F1 editor is on screen, and each panel's state. */
    fun isEditorOpen(): Boolean = workspace?.isShown == true

    fun panelStates(): List<com.morgana.client.editor.dock.PanelInfo> = workspace?.panelInfos().orEmpty()

    /** True while the Panels popup is open: its search box takes the keyboard, so the editor's single-key shortcuts stand down. */
    fun isPanelFinderOpen(): Boolean = panelFinder?.isOpen == true

    /** Closes the Panels popup if it is open (Escape). */
    fun dismissPanelFinder() { panelFinder?.close() }

    private fun closeEditor() {
        dismissClosePrompt()
        panelFinder?.close()
        panelFinder = null
        workspace?.hide()
        workspace = null
        worldState.mapEditorActive = false
        worldState.editor.onEventTileClick = null
        worldState.editingEventDraft = null
        stage.keyboardFocus = null
    }

    // ---- "unsaved changes" prompt ----

    private var closePrompt: com.kotcrab.vis.ui.widget.VisWindow? = null

    private fun dismissClosePrompt() {
        closePrompt?.remove()
        closePrompt = null
    }

    /** Asks what to do with unsaved map changes before the editor closes: save them, throw them away, or keep editing. */
    private fun showClosePrompt() {
        val window = theme.window("Unsaved changes")
        window.isModal = true
        window.addCloseButton()
        window.closeOnEscape()
        window.add(theme.text("This map has changes that are not saved.")).colspan(3).pad(10f).row()
        window.add(theme.textButton("Save and close") {
            saveDraft()
            closeEditor()
        }).pad(6f)
        window.add(theme.textButton("Discard changes") {
            worldState.discardEditorChanges()
            closeEditor()
        }).pad(6f)
        window.add(theme.textButton("Keep editing") { dismissClosePrompt() }).pad(6f).row()
        window.pack()
        window.centerWindow()
        stage.addActor(window.also { it.toFront() })
        closePrompt = window
    }

    /** Runs a keyboard shortcut from [com.morgana.client.editor.EditorShortcuts]; returns false if the editor is not open. */
    fun runShortcut(command: com.morgana.client.editor.EditorCommand): Boolean {
        if (workspace?.isShown != true) return false
        val editor = worldState.editor
        when (command) {
            is com.morgana.client.editor.EditorCommand.SelectTool -> editor.tool = command.tool
            com.morgana.client.editor.EditorCommand.Undo -> editor.undo()
            com.morgana.client.editor.EditorCommand.Redo -> editor.redo()
            com.morgana.client.editor.EditorCommand.Save -> saveDraft()
            com.morgana.client.editor.EditorCommand.ToggleGrid -> editor.showGrid = !editor.showGrid
            com.morgana.client.editor.EditorCommand.ToggleDimLayers -> editor.dimOtherLayers = !editor.dimOtherLayers
            com.morgana.client.editor.EditorCommand.TogglePanels -> panelFinder?.toggle()
            is com.morgana.client.editor.EditorCommand.Zoom -> onStepWorldZoom(command.delta)
        }
        return true
    }

    fun open() {
        workspace?.hide()
        netClient.send(ClientPacket.RequestAdminCatalog)

        // Admin shortcuts are the only thing left in this window; every editor has its own panel.
        val studio = VisTable()
        val scroll = VisScrollPane(contentContainer)
        giveScrollFocusOnHover(scroll)
        studio.add(scroll).grow().pad(6f).row()
        showAdminToolsTab()
        worldState.mapEditorActive = true // the map editor is always live while the editor is open; the Studio is only extra windows

        val eventsPanel = com.morgana.client.ui.editor.EventsPanel(
            stage, theme, assets, worldState,
            save = { netClient.send(ClientPacket.SaveEventDefinition(it)) },
            testCommands = { eventId, commands -> netClient.send(ClientPacket.TestEventCommands(eventId, commands)) },
            onRequestOpen = { workspace?.openPanel("events") }
        )
        val commonEventsPanel = com.morgana.client.ui.editor.CommonEventsPanel(
            stage, theme, assets, worldState,
            save = { netClient.send(ClientPacket.SaveCommonEventDefinition(it)) },
            testCommands = { eventId, commands -> netClient.send(ClientPacket.TestEventCommands(eventId, commands)) }
        )
        worldState.editor.onEventTileClick = { mapId, x, y, secondary ->
            eventsPanel.handleMapTileClick(mapId, x, y, secondary)
        }

        val ws = EditorWorkspace(
            stage, theme,
            describePanels(listOf(
                EditorPanel("toolbar", "Toolbar", buildToolbar(), DockZone.TOP, 800f, 40f, closable = false, collapsible = false, showHeader = false),
                EditorPanel("tileset", "Tileset", TilesetPanel(stage, theme, assets, worldState.editor).content, DockZone.LEFT, 288f, 300f, group = "Map"),
                EditorPanel("layers", "Layers", LayersPanel(stage, theme, worldState.editor).content, DockZone.LEFT, 288f, 330f, group = "Map"),
                EditorPanel("attributes", "Attributes", AttributePanel(stage, theme, worldState).content, DockZone.RIGHT, 360f, 340f, group = "Map"),
                EditorPanel("maps", "Maps", MapListPanel(stage, theme, worldState, netClient).content, DockZone.RIGHT, 360f, 200f, group = "Map"),
                EditorPanel("mapprops", "Map properties", MapPropertiesPanel(stage, theme, worldState).content, DockZone.RIGHT, 360f, 380f, group = "Map"),
                EditorPanel("gamerules", "Game rules", com.morgana.client.ui.editor.GameRulesPanel(stage, theme, assets, worldState,
                    save = { netClient.send(ClientPacket.SaveGameDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("modules", "Systems", com.morgana.client.ui.editor.ModulesPanel(stage, theme, assets, worldState,
                    save = { netClient.send(ClientPacket.SetModules(it)) }).content,
                    DockZone.RIGHT, 460f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("actionskills", "Action skills", ContentEditorPanel(stage, theme, assets, worldState, ActionSkillForm,
                    save = { netClient.send(ClientPacket.SaveActionSkillDefinition(it)) }).content,
                    DockZone.RIGHT, 640f, 560f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("weaponsets", "Weapon sets", ContentEditorPanel(stage, theme, assets, worldState, WeaponSetForm,
                    save = { netClient.send(ClientPacket.SaveWeaponSetDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("classes", "Classes", ContentEditorPanel(stage, theme, assets, worldState, ClassForm,
                    save = { netClient.send(ClientPacket.SaveClassDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("dungeon", "Dungeon", com.morgana.client.ui.editor.DungeonPanel(stage, theme, assets, worldState).content, DockZone.RIGHT, 360f, 420f, group = "Map"),
                EditorPanel("items", "Items", ContentEditorPanel(stage, theme, assets, worldState, ItemForm,
                    save = { netClient.send(ClientPacket.SaveItemDefinition(it)) },
                    extraActions = listOf("Give me 1" to { item: ItemDefinition -> netClient.send(ClientPacket.AdminSlashCommand("/item ${item.id} 1")) })).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("npcs", "NPCs", ContentEditorPanel(stage, theme, assets, worldState, NpcForm,
                    save = { netClient.send(ClientPacket.SaveNpcDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("spells", "Spells", ContentEditorPanel(stage, theme, assets, worldState, SpellForm,
                    save = { netClient.send(ClientPacket.SaveSpellDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("shops", "Shops", ContentEditorPanel(stage, theme, assets, worldState, ShopForm,
                    save = { netClient.send(ClientPacket.SaveShopDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("resources", "Resources", ContentEditorPanel(stage, theme, assets, worldState, ResourceForm,
                    save = { netClient.send(ClientPacket.SaveResourceDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("recipes", "Recipes", ContentEditorPanel(stage, theme, assets, worldState, RecipeForm,
                    save = { netClient.send(ClientPacket.SaveRecipeDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("quests", "Quests", ContentEditorPanel(stage, theme, assets, worldState, QuestForm,
                    save = { netClient.send(ClientPacket.SaveQuestDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 520f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("animations", "Animations", ContentEditorPanel(stage, theme, assets, worldState, AnimationForm,
                    save = { netClient.send(ClientPacket.SaveAnimationDefinition(it)) }).content,
                    DockZone.RIGHT, 560f, 480f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("events", "Events", eventsPanel.content,
                    DockZone.RIGHT, 900f, 580f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("commonevents", "Common events", commonEventsPanel.content,
                    DockZone.RIGHT, 900f, 540f, visibleByDefault = false, startFloating = true, group = "Content"),
                EditorPanel("studio", "Admin tools", studio, DockZone.RIGHT, 460f, 480f, visibleByDefault = false, startFloating = true, group = "Tools"),
                EditorPanel(
                    "art",
                    "Art",
                    ArtPanel(
                        stage = stage,
                        theme = theme,
                        assets = assets,
                        worldState = worldState,
                        onPickPngFile = onPickPngFile,
                        onUploadPng = { kind, assetId, pngBytes ->
                            netClient.send(
                                ClientPacket.ImportGameArt(
                                    kind = kind,
                                    assetId = assetId,
                                    pngBase64 = com.morgana.protocol.Base64Codec.encode(pngBytes)
                                )
                            )
                        }
                    ).content,
                    DockZone.RIGHT,
                    500f,
                    460f,
                    visibleByDefault = false,
                    startFloating = true,
                    group = "Content"
                ),
                EditorPanel("help", "Help", HelpPanel(stage, theme).content, DockZone.RIGHT, 460f, 480f, visibleByDefault = false, startFloating = true, group = "Tools"),
                EditorPanel("settings", "Settings", com.morgana.client.ui.editor.SettingsPanel(stage, theme, theme.palette) { applyColorScheme(it) }.content,
                    DockZone.RIGHT, 460f, 480f, visibleByDefault = false, startFloating = true, group = "Tools"),
                EditorPanel("status", "Status", buildStatusBar(), DockZone.BOTTOM, 800f, 26f, closable = false, collapsible = false, showHeader = false)
            )),
            PreferencesLayoutStore()
        )
        ws.show()
        workspace = ws
        val presets = listOf(
            LayoutPreset("Minimal", "Tileset and layers only", listOf("tileset", "layers")),
            LayoutPreset("Map editing", "Tileset, layers, attributes, maps and map properties (the default)", listOf("tileset", "layers", "attributes", "maps", "mapprops")),
            LayoutPreset("Content editing", "Items, NPCs, quests and events", listOf("items", "npcs", "quests", "events")),
            LayoutPreset("Everything", "Every editor", ws.panelInfos().map { it.id })
        )
        panelFinder = PanelFinder(stage, theme, ws, presets, LayoutLibrary(PreferencesKeyValueStore(), reserved = presets.map { it.name }))
    }

    private val helpStore = com.morgana.client.ui.editor.PreferencesKeyValueStore("morgana-editor-help")
    private var helpSeenCache: Boolean? = null

    private fun helpSeen(): Boolean = helpSeenCache ?: (helpStore.get("seen") == "yes").also { helpSeenCache = it }

    private fun openHelp() {
        workspace?.openPanel("help")
        helpSeenCache = true
        helpStore.put("seen", "yes")
    }

    /** The plain-language line the panel finder shows for each panel. */
    private fun describePanels(panels: List<EditorPanel>): List<EditorPanel> {
        val text = mapOf(
            "tileset" to "Pick the tiles you paint with",
            "layers" to "Choose the layer to paint on; show or hide layers",
            "attributes" to "Mark tiles: blocked, warp, shop, NPC spawn",
            "maps" to "Switch between maps, or make a new one",
            "mapprops" to "Name, rules, weather, borders and size",
            "dungeon" to "Generated dungeon floors: rooms, monsters, loot, boss, stairs, party scaling and difficulty tiers",
            "gamerules" to "The game's rules: stats, vitals, levels, combat formulas, loot rarities, death and movement systems, tips",
            "modules" to "Switch the realm's optional systems on or off: parties, instancing, projectiles, mail",
            "actionskills" to "Action combat skills: timing, hitboxes, armour, dashes and guards",
            "weaponsets" to "Which action skills a weapon offers and how they combo",
            "classes" to "Playable classes: stats, start point, starting gear and spells",
            "items" to "Weapons, armor, potions, tools and more",
            "npcs" to "Villagers, shopkeepers and monsters",
            "spells" to "Magic spells and what they cost",
            "shops" to "What each shop sells, and for how much",
            "resources" to "Trees, ore and crops players gather",
            "recipes" to "Crafting: ingredients and what they make",
            "quests" to "Quest steps and rewards",
            "animations" to "Effects like sparks and slashes: frames, speed, sound",
            "events" to "Scripted happenings: talk, choices, gifts, quests, warps",
            "commonevents" to "Reusable step sequences any event can call",
            "studio" to "Shortcuts for admins: heal, warp between maps",
            "art" to "See every icon, character and effect picture, and which are your own",
            "help" to "How the editor works: first steps, saving, layouts, shortcuts",
            "settings" to "The editor's colour scheme"
        )
        panels.forEach { it.description = text[it.id].orEmpty() }
        return panels
    }

    private companion object { const val MAX_WARP_BUTTONS = 12 }

    private fun toolHint(tool: MapEditorTool): String = when (tool) {
        MapEditorTool.BRUSH -> "Click or drag on the map to paint the selected tiles. Right-click erases."
        MapEditorTool.RECTANGLE -> "Drag a box on the map to fill it with the selected tiles."
        MapEditorTool.FLOOD_FILL -> "Click to fill the connected area of the same tile."
        MapEditorTool.ERASER -> "Click or drag on the map to erase on the selected layer."
        MapEditorTool.EYEDROPPER -> "Click a tile on the map to pick it up as your brush."
        MapEditorTool.NPC_SPAWN -> "Left-click places an NPC spawn, right-click removes it."
        MapEditorTool.EVENT -> "Left-click selects or moves an event; right-click places a new event or unplaces it."
    }

    private fun saveDraft() {
        val draft = worldState.editor.draft ?: return
        netClient.send(ClientPacket.SaveMapDefinition(draft))
        netClient.send(ClientPacket.RequestAdminCatalog)
        worldState.editor.markSaved()
        stage.keyboardFocus = null
    }

    private fun buildToolbar(): Actor {
        val editor = worldState.editor
        val save = theme.iconButton("save", "Save the map to the server (Ctrl+S)") { saveDraft() }
        val undo = theme.iconButton("undo", "Undo the last edit (Ctrl+Z)") { editor.undo() }
        val redo = theme.iconButton("redo", "Redo the edit you undid (Ctrl+Y)") { editor.redo() }
        val mapName = theme.text("")
        val dirty = VisLabel("", theme.warningLabel)

        val toolButtons = linkedMapOf<MapEditorTool, VisImageButton>()
        val grid = theme.iconButton("grid", "Show or hide the tile grid (H)", toggle = true)
        val dim = theme.iconButton("layers", "Dim the layers you are not painting on (D)", toggle = true)
        val bar = LiveTable {
            toolButtons[editor.tool]?.isChecked = true
            grid.isChecked = editor.showGrid
            dim.isChecked = editor.dimOtherLayers
            undo.isDisabled = !editor.canUndo
            redo.isDisabled = !editor.canRedo
            save.isDisabled = editor.draft == null
            val draft = editor.draft
            mapName.setText(if (draft == null) "No map" else "Map ${draft.id}: ${draft.name}")
            dirty.setText(if (editor.isDirty) "  unsaved changes" else "")
        }
        bar.defaults().pad(4f).padRight(0f).size(30f)
        bar.add(save)
        bar.add(undo)
        bar.add(redo)
        bar.add(separator()).width(1f).height(24f).padLeft(6f).padRight(2f)
        // Tools: exactly one is selected; the eyedropper switches back to the brush by itself, so the group follows the session.
        listOf(
            Triple(MapEditorTool.BRUSH, "brush", "Brush (B): click or drag to paint the selected tiles; right-click erases"),
            Triple(MapEditorTool.RECTANGLE, "rect", "Rectangle (R): drag a box and fill it with the selected tiles"),
            Triple(MapEditorTool.FLOOD_FILL, "fill", "Bucket (G): fill a connected area of the same tile"),
            Triple(MapEditorTool.ERASER, "eraser", "Eraser (E): clear the selected layer"),
            Triple(MapEditorTool.EYEDROPPER, "picker", "Eyedropper (I): copy the tile under the cursor"),
            Triple(MapEditorTool.NPC_SPAWN, "spawn", "NPC spawn (N): left-click places, right-click removes"),
            Triple(MapEditorTool.EVENT, "event", "Event (V): left-click selects or moves; right-click places new or unplaces")
        ).forEach { (tool, icon, tip) ->
            val b = theme.iconButton(icon, tip, toggle = true) { editor.tool = tool }
            toolButtons[tool] = b
            bar.add(b)
        }
        theme.selectGroup(*toolButtons.values.toTypedArray())
        bar.add(separator()).width(1f).height(24f).padLeft(6f).padRight(2f)
        grid.onChange { editor.showGrid = grid.isChecked }
        bar.add(grid)
        dim.onChange { editor.dimOtherLayers = dim.isChecked }
        bar.add(dim)
        bar.add(theme.iconButton("zoom_out", "Zoom the map out (-); 25% to 400% in small steps") { onStepWorldZoom(-1) })
        bar.add(theme.iconButton("zoom_in", "Zoom the map in (=); 25% to 400% in small steps") { onStepWorldZoom(1) })
        bar.defaults().reset()
        bar.add(mapName).padLeft(8f)
        bar.add(dirty).padLeft(4f)
        bar.add().growX()

        // One button opens the panel finder, which lists every panel and the saved layouts.
        val panelsButton = theme.textButton("Editors") { panelFinder?.toggle() }
        theme.tip(panelsButton, "Find and open editors, and save or load layouts (P)")
        bar.add(panelsButton).padRight(6f)
        val helpButton = theme.textButton("Help") { openHelp() }
        theme.tip(helpButton, "How the editor works: first steps, saving, layouts and shortcuts")
        bar.add(helpButton).padRight(6f)
        val closeButton = theme.textButton("Close editor") { toggle() }
        theme.tip(closeButton, "Close the editor and go back to the game (F1)")
        bar.add(closeButton).padRight(6f)
        // On a narrow window the toolbar is wider than the screen, so it scrolls sideways instead of cutting buttons off.
        val pane = VisScrollPane(bar)
        pane.setScrollingDisabled(false, true)
        pane.setScrollbarsOnTop(true)
        pane.setFadeScrollBars(true)
        pane.setOverscroll(false, false)
        com.morgana.client.ui.editor.giveScrollFocusOnHover(stage, pane)
        return pane
    }

    private fun separator(): Actor = Image(theme.solid(theme.palette.border))

    private fun buildStatusBar(): Actor {
        val editor = worldState.editor
        val tile = theme.muted("")
        val tool = theme.muted("")
        val layer = theme.muted("")
        val history = theme.muted("")
        val zoom = theme.muted("")
        val hint = theme.muted("")
        val bar = LiveTable {
            val hovered = worldState.hoveredTile
            tile.setText(if (hovered == null) "Tile -, -" else "Tile ${hovered.x}, ${hovered.y}")
            tool.setText("Tool: ${editor.tool.displayName}")
            layer.setText("Layer: ${editor.layer.name.replace('_', ' ')}")
            history.setText("Undo steps: ${editor.undoDepth}")
            zoom.setText("Zoom: ${worldZoomLabel()}")
            hint.setText(if (!helpSeen()) "First time here? Click Help for a quick tour." else toolHint(editor.tool))
        }
        bar.defaults().padLeft(12f).padRight(4f)
        bar.add(tile).width(90f).left()
        bar.add(tool).left()
        bar.add(layer).left()
        bar.add(history).left()
        bar.add(zoom).left()
        bar.add(hint).left().padLeft(24f).minWidth(0f)
        bar.add().growX()
        return bar
    }

    /**
     * Scene2D only gives a [VisScrollPane] the mouse wheel after a click inside it, so the editor would not scroll until
     * the user clicked somewhere in it. Hovering is enough here: the pane takes wheel focus when the pointer enters it
     * and gives it up when the pointer leaves for something outside the pane.
     */
    private fun giveScrollFocusOnHover(pane: VisScrollPane) = com.morgana.client.ui.editor.giveScrollFocusOnHover(stage, pane)

    // =========================================================================
    // TAB 7: QUICK ADMIN & MODERATION TOOLS
    // =========================================================================
    private fun showAdminToolsTab() {
        contentContainer.clearChildren()
        contentContainer.top().left()
        contentContainer.defaults().pad(4f).left()

        contentContainer.add(theme.heading("Admin shortcuts")).colspan(2).growX().row()
        contentContainer.add(btn("Heal myself fully (/heal)") {
            netClient.send(ClientPacket.AdminSlashCommand("/heal"))
        }).colspan(2).fillX().row()

        val maps = worldState.catalog.maps.sortedBy { it.id }
        if (maps.isEmpty()) contentContainer.add(theme.muted("No maps loaded yet.")).colspan(2).row()
        for (map in maps.take(MAX_WARP_BUTTONS)) {
            contentContainer.add(btn("Warp to ${map.name} (map ${map.id})") {
                netClient.send(ClientPacket.AdminSlashCommand("/warpto ${map.id}"))
            }).colspan(2).fillX().row()
        }

        val spawnItemIdField = VisTextField("15")
        val spawnQtyField = VisTextField("1")
        contentContainer.add(theme.muted("Give yourself an item:")).colspan(2).left().padTop(6f).row()
        contentContainer.add(VisLabel("Item id:")); contentContainer.add(spawnItemIdField).row()
        contentContainer.add(VisLabel("How many:")); contentContainer.add(spawnQtyField).row()
        contentContainer.add(btn("Give me the item (/item)") {
            netClient.send(ClientPacket.AdminSlashCommand("/item ${spawnItemIdField.text} ${spawnQtyField.text}"))
            stage.keyboardFocus = null
        }).colspan(2).fillX().row()
    }

    private fun btn(label: String, onClick: () -> Unit): VisTextButton = theme.textButton(label, onClick)
}
