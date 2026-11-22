package com.morgana.client.ui

import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.LootRules

/**
 * Pure layout and hit-testing for ground item nameplates (shown when holding `Alt`, or always for highlighted drops).
 * Stacks multiple labels on the same tile upward so piles of loot stay readable and clickable.
 */
object GroundLootLabels {
    const val ROW_HEIGHT_PX = 16f
    const val PAD_X = 5f
    const val PAD_Y = 3f
    private const val APPROX_CHAR_WIDTH = 6.5f

    data class LabelEntry(
        val item: MapGroundItem,
        val text: String,
        val colorHex: String,
        val highlighted: Boolean,
        val centerX: Float,
        val baselineY: Float,
        val boxX: Float,
        val boxY: Float,
        val boxW: Float,
        val boxH: Float
    ) {
        fun contains(worldX: Float, worldY: Float): Boolean =
            worldX >= boxX && worldX <= boxX + boxW && worldY >= boxY && worldY <= boxY + boxH
    }

    /** Formats a ground drop's display text, honoring rolled rarity prefixes/suffixes, `+N` level, and stack amount. */
    fun formatLabel(def: ItemDefinition, item: MapGroundItem, lootRules: LootRules?): String {
        val stack = item.stack
        if (def.type == ItemType.CURRENCY) {
            return if (stack.amount > 1) "${stack.amount} ${def.name}" else def.name
        }
        val name = ItemRollText.displayName(def, stack.rolls, lootRules, stack.plus)
        return if (stack.amount > 1) "$name x${stack.amount}" else name
    }

    /** Picks the `#rrggbb` color for a ground item label (rarity color, highlight color, gold for currency, or neutral gray). */
    fun colorHex(def: ItemDefinition, item: MapGroundItem, lootRules: LootRules?, highlighted: Boolean): String {
        val rarity = item.stack.rolls?.let { r -> lootRules?.rarity(r.rarity) }
        if (rarity != null && rarity.color.isNotBlank()) return rarity.color
        if (highlighted) return "#FDE047"
        if (def.type == ItemType.CURRENCY) return "#FACC15"
        return "#E5E7EB"
    }

    /**
     * Builds stacked label boxes in LibGDX world coordinates (y-up, matching `MorganaWorldRenderer`).
     * [measureWidth] defaults to a pure character-width estimate for unit tests; the renderer passes `GlyphLayout`.
     */
    fun layout(
        groundItems: List<MapGroundItem>,
        itemsById: Map<Int, ItemDefinition>,
        lootRules: LootRules?,
        mapHeightTiles: Int,
        tileSize: Int,
        showAll: Boolean,
        isHighlighted: (ItemDefinition) -> Boolean,
        measureWidth: (String) -> Float = { it.length * APPROX_CHAR_WIDTH },
        textHeight: Float = 11f
    ): List<LabelEntry> {
        val out = ArrayList<LabelEntry>()
        val stackCounts = HashMap<Long, Int>()
        for (ground in groundItems) {
            val def = itemsById[ground.stack.itemId] ?: continue
            val hl = isHighlighted(def)
            if (!showAll && !hl) continue
            val tileKey = (ground.tileX.toLong() shl 32) or (ground.tileY.toLong() and 0xffffffffL)
            val stackIdx = stackCounts[tileKey] ?: 0
            stackCounts[tileKey] = stackIdx + 1

            val text = formatLabel(def, ground, lootRules)
            val col = colorHex(def, ground, lootRules, hl)
            val cx = ground.tileX * tileSize + tileSize * 0.5f
            val renderY = (mapHeightTiles - 1 - ground.tileY) * tileSize.toFloat()
            val baselineY = renderY + tileSize + 14f + stackIdx * ROW_HEIGHT_PX
            val textW = measureWidth(text)
            val boxW = textW + PAD_X * 2f
            val boxH = textHeight + PAD_Y * 2f
            val boxX = cx - boxW * 0.5f
            val boxY = baselineY - textHeight - PAD_Y
            out += LabelEntry(ground, text, col, hl, cx, baselineY, boxX, boxY, boxW, boxH)
        }
        return out
    }

    /** Returns the topmost ground item whose label box contains `(worldX, worldY)`, or on that tile when no label matched. */
    fun hitTest(
        worldX: Float,
        worldY: Float,
        tileX: Int,
        tileY: Int,
        labels: List<LabelEntry>,
        groundItems: List<MapGroundItem>
    ): MapGroundItem? {
        labels.lastOrNull { it.contains(worldX, worldY) }?.let { return it.item }
        return groundItems.firstOrNull { it.tileX == tileX && it.tileY == tileY }
    }
}
