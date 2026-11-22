package com.morgana.client.ui.bag

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.EmptyStates
import com.morgana.client.ui.GameTheme
import com.morgana.client.ui.HudWindow
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.protocol.ClientPacket

/**
 * Crafting window styled like [InventoryPanel] and [BankWindow]:
 * - Left grid displays craftable recipe output items with filter chips, search, quality borders, and tooltips.
 * - Right detail pane shows the selected item's required materials with live available/required counts and Craft button.
 */
class CraftingWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets
) : HudWindow("Crafting") {

    private var filter = BagFilter.ALL
    private var query = ""
    private var selectedRecipeId: Int? = null

    private val cell = BagUi.cellSize
    private val grid = ItemGrid(assets, BagUi.font, cell)
    private val detail = VisTable()
    private val body = VisTable()
    private val summaryHolder = VisTable()
    private val searchField = VisTextField()
    private var lastSignature: List<Any?>? = null

    private val winW: Float
    private val winH: Float

    init {
        isMovable = true
        addCloseButton()
        defaults().pad(3f)
        val (w, h) = BagUi.fit(stage, 680f, 430f)
        winW = w
        winH = h
        grid.columns = (((winW * 0.56f) - 28f) / cell).toInt().coerceIn(4, 10)
        grid.onTap = { c -> onCellTapped(c) }
        searchField.messageText = "Search"
        searchField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (searchField.text != query) {
                    query = searchField.text
                    refresh()
                }
            }
        })

        add(body).grow().row()
        rebuildBody()
        refresh()
        setSize(winW, winH)
        centerWindow()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val ch = worldState.localCharacter ?: return
        val signature = listOf(ch.inventory, ch.level, worldState.catalog.recipes, worldState.catalog.items.size)
        if (signature != lastSignature) {
            lastSignature = signature
            refresh()
        }
    }

    private fun items(): Map<Int, ItemDefinition> = worldState.catalog.items.associateBy { it.id }

    private fun names(): StatNames {
        val game = (worldState.compiledGame ?: BlankGame.COMPILED).definition
        return StatNames(
            attributes = game.attributes.associate { it.id to it.short.ifBlank { it.name } },
            vitals = game.vitals.associate { it.id to it.short.ifBlank { it.name } }
        )
    }

    private fun wearer(ch: CharacterState): WearerInfo {
        val game = worldState.compiledGame ?: BlankGame.COMPILED
        return WearerInfo(ch.level, ch.effectiveAttributes(game, items()), ch.classId)
    }

    private fun canCraft(ch: CharacterState, recipe: RecipeDefinition): Boolean =
        ch.level >= recipe.levelReq &&
            recipe.ingredients.all { ing -> ch.inventory.totalCountOf(ing.itemId) >= ing.amount }

    private fun filteredRecipes(itemMap: Map<Int, ItemDefinition>): List<RecipeDefinition> {
        val q = query.trim().lowercase()
        return worldState.catalog.recipes.filter { recipe ->
            val outDef = itemMap[recipe.outputItemId]
            val matchesFilter = filter == BagFilter.ALL || (outDef != null && filter.matches(outDef.type))
            val matchesQuery = q.isEmpty() ||
                recipe.name.lowercase().contains(q) ||
                (outDef?.name?.lowercase()?.contains(q) == true)
            matchesFilter && matchesQuery
        }
    }

    private fun rebuildBody() {
        body.clearChildren()
        val toolbar = VisTable()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        for (f in BagFilter.entries) {
            val chip = BagUi.chip(f.label, filter == f) {
                filter = f
                refresh()
            }
            group.add(chip)
            toolbar.add(chip).height(BagUi.buttonHeight).padRight(3f)
        }
        toolbar.add(searchField).width(if (winW < 560f) 80f else 120f).height(BagUi.buttonHeight).padLeft(4f).padRight(4f)
        toolbar.add().growX()
        body.add(toolbar).growX().colspan(2).padBottom(4f).row()

        val gridW = winW * 0.56f
        val detailW = winW - gridW - 36f
        val bodyH = winH - 96f
        val leftCol = VisTable()
        leftCol.add(BagUi.scroll(grid, stage)).width(gridW).height(bodyH - 24f).top().row()
        leftCol.add(summaryHolder).left().padTop(4f).row()
        val detailCard = VisTable().apply {
            background = GameTheme.shared.cardBackground
            add(BagUi.scroll(detail, this@CraftingWindow.stage)).grow().top()
        }
        body.add(leftCol).width(gridW).height(bodyH).top()
        body.add(detailCard).width(detailW).height(bodyH).top().padLeft(6f).row()
    }

    private fun refresh() {
        val ch = worldState.localCharacter ?: return
        val itemMap = items()
        val recipes = filteredRecipes(itemMap)
        val loot = worldState.gameDefinition?.loot

        if (selectedRecipeId == null || recipes.none { it.id == selectedRecipeId }) {
            selectedRecipeId = recipes.firstOrNull()?.id
        }

        val minCells = (grid.columns * 5).coerceAtLeast(recipes.size)
        grid.cells = (0 until minCells).map { idx ->
            val recipe = recipes.getOrNull(idx)
            if (recipe == null) {
                GridCell(key = -1 - idx, iconId = 0, amount = 0)
            } else {
                val outDef = itemMap[recipe.outputItemId]
                GridCell(
                    key = recipe.id,
                    iconId = outDef?.iconId ?: recipe.outputItemId,
                    amount = recipe.outputAmount,
                    borderColor = BagUi.hexColor(ItemInfo.slotBorderHex(outDef, null, loot))
                )
            }
        }
        grid.selectedKey = selectedRecipeId
        grid.armedKey = null
        grid.tipFor = { cell ->
            val recipe = recipes.firstOrNull { it.id == cell.key }
            val def = recipe?.let { itemMap[it.outputItemId] }
            if (recipe == null || def == null) null
            else ItemInfo.tip(
                def, recipe.outputAmount,
                def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] },
                itemMap, names(), wearer(ch), null, loot, 0
            )
        }

        val craftableCount = recipes.count { canCraft(ch, it) }
        summaryHolder.clearChildren()
        summaryHolder.add(BagUi.label("Recipes: ${recipes.size} ($craftableCount ready)", Tone.MUTED))

        buildDetail(ch, itemMap, recipes)
    }

    private fun onCellTapped(cellTapped: GridCell) {
        if (cellTapped.key < 0) return
        selectedRecipeId = cellTapped.key
        refresh()
    }

    private fun buildDetail(ch: CharacterState, itemMap: Map<Int, ItemDefinition>, recipes: List<RecipeDefinition>) {
        detail.clearChildren()
        detail.top().left()
        val textW = winW - winW * 0.56f - 70f

        if (worldState.catalog.recipes.isEmpty()) {
            detail.add(BagUi.wrapped(EmptyStates.RECIPES, Tone.MUTED)).width(textW).pad(6f).left().row()
            return
        }
        val recipe = recipes.firstOrNull { it.id == selectedRecipeId }
        if (recipe == null) {
            detail.add(BagUi.wrapped("No recipes match your filter.", Tone.MUTED)).width(textW).pad(6f).left().row()
            return
        }

        val outDef = itemMap[recipe.outputItemId]
        val loot = worldState.gameDefinition?.loot

        val head = VisTable()
        if (outDef != null) {
            head.add(Image(TextureRegionDrawable(TextureRegion(assets.getItemIconRegion(outDef.iconId))))).size(40f).padRight(8f)
        }
        val title = VisTable()
        val titleText = if (recipe.outputAmount > 1) "${recipe.name} x${recipe.outputAmount}" else recipe.name
        title.add(BagUi.rarityTitle(titleText, if (outDef != null) ItemRollText.color(null, loot) else null))
            .width(textW - 56f).left().row()
        if (outDef != null) {
            val tip = ItemInfo.tip(
                outDef, recipe.outputAmount,
                outDef.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] },
                itemMap, names(), wearer(ch), null, loot, 0
            )
            title.add(BagUi.wrapped(tip.kind, Tone.MUTED)).width(textW - 56f).left().row()
            head.add(title).left()
            detail.add(head).pad(6f).left().row()
            if (tip.description.isNotBlank()) {
                detail.add(BagUi.wrapped(tip.description, Tone.MUTED)).width(textW).padLeft(6f).padBottom(4f).left().row()
            }
            for (line in tip.lines) {
                detail.add(BagUi.wrapped(line.text, line.tone)).width(textW).padLeft(6f).left().row()
            }
        } else {
            head.add(title).left()
            detail.add(head).pad(6f).left().row()
        }

        if (recipe.levelReq > 1) {
            val lvOk = ch.level >= recipe.levelReq
            detail.add(BagUi.wrapped("Requires Lv ${recipe.levelReq}", if (lvOk) Tone.MUTED else Tone.BAD))
                .width(textW).padLeft(6f).padTop(4f).left().row()
        }

        detail.add(BagUi.label("Required Materials:", Tone.NEUTRAL)).padLeft(6f).padTop(8f).padBottom(4f).left().row()
        for (ing in recipe.ingredients) {
            val ingDef = itemMap[ing.itemId]
            val have = ch.inventory.totalCountOf(ing.itemId)
            val enough = have >= ing.amount
            val row = VisTable()
            if (ingDef != null) {
                row.add(Image(TextureRegionDrawable(TextureRegion(assets.getItemIconRegion(ingDef.iconId))))).size(20f).padRight(6f)
            }
            val ingName = ingDef?.name ?: "Item #${ing.itemId}"
            row.add(BagUi.wrapped("$ingName: $have / ${ing.amount}", if (enough) Tone.GOOD else Tone.BAD))
                .width(textW - 30f).left()
            detail.add(row).padLeft(6f).padBottom(3f).left().row()
        }

        val craftable = canCraft(ch, recipe)
        val craftBtn = BagUi.button("Craft") {
            netClient.send(ClientPacket.CraftRecipe(recipe.id))
        }
        craftBtn.isDisabled = !craftable
        detail.add(craftBtn).padLeft(6f).padTop(8f).left().row()
    }
}
