package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.item.ItemType
import com.morgana.protocol.WorldContentCatalogDto

/**
 * The shop form. The stock is a text area with one entry per line: `item amount currency price`, where the last three are
 * optional (amount defaults to 1, currency to the shop's own, price to the item's base price modified by the shop's rates).
 * Example: `3 5` sells 5 of item 3; `3 1 2 40` sells one for 40 of currency item 2.
 */
object ShopForm : ContentForm<ShopDefinition> {
    override val title = "Shops"
    override val noun = "shop"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Shop", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("currency", "Currency item", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM, hint = "The item players pay with."),
            FormField("buyRate", "Price when players buy (%)", FormFieldType.INT, "100", min = 0, max = 10_000),
            FormField("sellRate", "Price when players sell (%)", FormFieldType.INT, "50", min = 0, max = 10_000)
        )),
        FormSection("Stock", listOf(
            FormField("stock", "For sale", FormFieldType.LINES, hint = "One per line: item amount currency price. Only the item is needed. Example: 3 5")
        ))
    )

    override fun idOf(definition: ShopDefinition) = definition.id
    override fun nameOf(definition: ShopDefinition) = definition.name

    override fun create(id: Int, context: FormContext): ShopDefinition {
        val items = context.catalog.items
        val currency = (items.firstOrNull { it.type == ItemType.CURRENCY } ?: items.firstOrNull())?.id ?: 1
        return ShopDefinition(id = id, name = "New shop $id", currencyItemId = currency)
    }

    override fun all(catalog: WorldContentCatalogDto) = catalog.shops.sortedBy { it.id }

    override fun valuesOf(definition: ShopDefinition, context: FormContext): Map<String, String> = mapOf(
        "name" to definition.name, "currency" to definition.currencyItemId.toString(),
        "buyRate" to definition.buyRatePercent.toString(), "sellRate" to definition.sellRatePercent.toString(),
        "stock" to formatStock(definition.entries)
    )

    override fun apply(definition: ShopDefinition, values: Map<String, String>, context: FormContext): ShopDefinition {
        val currency = FormRules.int(values, "currency", definition.currencyItemId).coerceAtLeast(1)
        return definition.copy(
            name = FormRules.text(values, "name", definition.name).ifBlank { definition.name },
            currencyItemId = currency,
            buyRatePercent = FormRules.int(values, "buyRate", definition.buyRatePercent).coerceAtLeast(0),
            sellRatePercent = FormRules.int(values, "sellRate", definition.sellRatePercent).coerceAtLeast(0),
            entries = values["stock"]?.let { parseStock(it, currency).entries } ?: definition.entries
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val currency = values["currency"]?.trim()?.toIntOrNull() ?: 1
        val items = context.catalog.items
        if (items.isNotEmpty() && items.none { it.id == currency }) out += "Currency item $currency does not exist"
        val parsed = parseStock(values["stock"].orEmpty(), currency)
        out += parsed.problems
        if (items.isNotEmpty()) {
            for (e in parsed.entries) {
                if (items.none { it.id == e.itemId }) out += "Stock item ${e.itemId} does not exist"
                if (items.none { it.id == e.currencyItemId }) out += "Stock currency item ${e.currencyItemId} does not exist"
            }
        }
        val seen = HashSet<Int>()
        for (e in parsed.entries) if (!seen.add(e.itemId)) { out += "Item ${e.itemId} is listed more than once"; break }
        return out
    }

    // ---- stock lines ----

    class ParsedStock(val entries: List<ShopEntry>, val problems: List<String>)

    /** One line per entry: `item [amount [currency [price]]]`. Blank lines are ignored; [shopCurrency] fills a missing currency. */
    fun parseStock(text: String, shopCurrency: Int): ParsedStock {
        val entries = ArrayList<ShopEntry>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Stock line ${index + 1}"
            val parts = line.split(Regex("\\s+"))
            val numbers = parts.map { it.toIntOrNull() }
            when {
                parts.size > 4 || numbers.any { it == null } -> problems += "$at: write 'item amount currency price', all whole numbers (only the item is needed)"
                numbers[0]!! < 1 -> problems += "$at: the item id must be a positive whole number"
                (numbers.getOrNull(1) ?: 1) < 1 -> problems += "$at: the amount must be at least 1"
                (numbers.getOrNull(2) ?: 1) < 1 -> problems += "$at: the currency item id must be a positive whole number"
                (numbers.getOrNull(3) ?: 0) < 0 -> problems += "$at: the price cannot be negative"
                else -> entries += ShopEntry(
                    itemId = numbers[0]!!, amount = numbers.getOrNull(1) ?: 1,
                    currencyItemId = numbers.getOrNull(2) ?: shopCurrency, priceOverride = numbers.getOrNull(3) ?: 0
                )
            }
        }
        return ParsedStock(entries, problems)
    }

    /** Every entry as four numbers, so an entry round-trips exactly. */
    fun formatStock(entries: List<ShopEntry>): String = entries.joinToString("\n") { "${it.itemId} ${it.amount} ${it.currencyItemId} ${it.priceOverride}" }
}
