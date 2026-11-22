package com.morgana.client.ui.bag

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.HotbarSlotAction
import com.morgana.client.ui.GameTheme
import com.morgana.client.ui.HudWindow

/** The tabs of the [CharacterMenu], in the order they are shown. Inventory sits next to Equipment. */
enum class MenuSection { STATS, EQUIPMENT, INVENTORY, SKILLS, PROFICIENCY }

/**
 * One window for everything about the hero: a single tab bar (Stats, Equipment, Inventory, Skills, Proficiency) over
 * the [CharacterPanel] and [InventoryPanel]. Switching tabs never moves or resizes the window, so there is no separate
 * Character and Inventory window to hop between. `V` opens it on Stats and `I` on Inventory; the keys switch tabs when
 * it is already open ([PlayerHud] decides). Tabs the game does not use (skills, proficiency) are left out.
 *
 * Both panels are kept once built, so a filter, search or selected spell survives a trip to another tab.
 */
class CharacterMenu(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    start: MenuSection = MenuSection.STATS,
    private val onAssignHotbar: (HotbarSlotAction) -> Unit
) : HudWindow("Character", "character") {

    var section: MenuSection = start
        private set

    private val tabBar = VisTable()
    private val body = VisTable()
    private val winW: Float
    private val winH: Float
    private val characterPanel: CharacterPanel
    private val inventoryPanel: InventoryPanel

    init {
        style = GameTheme.shared.windowStyle
        isMovable = true
        addCloseButton()
        defaults().pad(3f)
        val (w, h) = BagUi.fit(stage, 680f, 410f)
        winW = w
        winH = h
        characterPanel = CharacterPanel(stage, worldState, netClient, assets, winW, winH, CharacterTab.STATS, onAssignHotbar)
        inventoryPanel = InventoryPanel(stage, worldState, netClient, assets, onAssignHotbar, winW, winH)
        if (section !in sections()) section = MenuSection.STATS
        add(tabBar).growX().row()
        add(body).grow().row()
        showSection(section, force = true)
        setSize(winW, winH)
        centerWindow()
    }

    /** The tabs this game shows. */
    fun sections(): List<MenuSection> {
        val tabs = characterPanel.availableTabs()
        return MenuSection.entries.filter { s ->
            when (s) {
                MenuSection.STATS -> CharacterTab.STATS in tabs
                MenuSection.EQUIPMENT -> CharacterTab.EQUIPMENT in tabs
                MenuSection.INVENTORY -> true
                MenuSection.SKILLS -> CharacterTab.SKILLS in tabs
                MenuSection.PROFICIENCY -> CharacterTab.PROFICIENCY in tabs
            }
        }
    }

    /** Switches to [target] in place (the window keeps its position and size). A tab the game does not use is ignored. */
    fun showSection(target: MenuSection, force: Boolean = false) {
        if (target !in sections()) return
        if (target == section && !force) return
        section = target
        rebuildTabs()
        body.clearChildren()
        if (target == MenuSection.INVENTORY) {
            body.add(inventoryPanel).grow()
        } else {
            characterPanel.showTab(characterTab(target))
            body.add(characterPanel).grow()
        }
    }

    private fun characterTab(s: MenuSection): CharacterTab = when (s) {
        MenuSection.STATS -> CharacterTab.STATS
        MenuSection.EQUIPMENT -> CharacterTab.EQUIPMENT
        MenuSection.SKILLS -> CharacterTab.SKILLS
        MenuSection.PROFICIENCY -> CharacterTab.PROFICIENCY
        MenuSection.INVENTORY -> CharacterTab.STATS
    }

    private fun label(s: MenuSection): String = when (s) {
        MenuSection.INVENTORY -> "Inventory"
        else -> characterPanel.tabLabel(characterTab(s))
    }

    private fun rebuildTabs() {
        tabBar.clearChildren()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        for (s in sections()) {
            val chip = BagUi.chip(label(s), section == s) { showSection(s) }
            group.add(chip)
            tabBar.add(chip).height(BagUi.buttonHeight).padRight(3f)
        }
        tabBar.add().growX()
    }
}
