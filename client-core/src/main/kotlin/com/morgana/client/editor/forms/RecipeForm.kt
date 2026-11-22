package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.protocol.WorldContentCatalogDto

/** The crafting recipe form. Ingredients are a text area with one `item amount` per line (the amount defaults to 1). */
object RecipeForm : ContentForm<RecipeDefinition> {
    override val title = "Recipes"
    override val noun = "recipe"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Recipe", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("station", "Crafted at", FormFieldType.CHOICE, "ANYWHERE", CraftingStationType.entries.map { it.name }),
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1, max = 1000),
            FormField("xp", "XP for crafting", FormFieldType.LONG, "20", min = 0)
        )),
        FormSection("Makes", listOf(
            FormField("output", "Item made", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM),
            FormField("outputAmount", "Amount made", FormFieldType.INT, "1", min = 1, max = 10_000)
        )),
        FormSection("Needs", listOf(
            FormField("ingredients", "Ingredients", FormFieldType.LINES, hint = "One per line: item amount. Only the item is needed. Example: 13 2")
        ))
    )

    override fun idOf(definition: RecipeDefinition) = definition.id
    override fun nameOf(definition: RecipeDefinition) = definition.name

    override fun create(id: Int, context: FormContext): RecipeDefinition {
        val first = context.catalog.items.minByOrNull { it.id }?.id ?: 1
        return RecipeDefinition(id = id, name = "New recipe $id", outputItemId = first, ingredients = listOf(RecipeIngredient(first, 1)))
    }

    override fun all(catalog: WorldContentCatalogDto) = catalog.recipes.sortedBy { it.id }

    override fun valuesOf(definition: RecipeDefinition, context: FormContext): Map<String, String> = mapOf(
        "name" to definition.name, "station" to definition.stationType.name, "levelReq" to definition.levelReq.toString(),
        "xp" to definition.xpReward.toString(), "output" to definition.outputItemId.toString(),
        "outputAmount" to definition.outputAmount.toString(), "ingredients" to formatIngredients(definition.ingredients)
    )

    override fun apply(definition: RecipeDefinition, values: Map<String, String>, context: FormContext): RecipeDefinition = definition.copy(
        name = FormRules.text(values, "name", definition.name).ifBlank { definition.name },
        stationType = CraftingStationType.entries.firstOrNull { it.name == values["station"] } ?: definition.stationType,
        levelReq = FormRules.int(values, "levelReq", definition.levelReq).coerceAtLeast(1),
        xpReward = FormRules.long(values, "xp", definition.xpReward).coerceAtLeast(0),
        outputItemId = FormRules.int(values, "output", definition.outputItemId).coerceAtLeast(1),
        outputAmount = FormRules.int(values, "outputAmount", definition.outputAmount).coerceAtLeast(1),
        ingredients = values["ingredients"]?.let { parseIngredients(it).items } ?: definition.ingredients
    )

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val items = context.catalog.items
        val output = values["output"]?.trim()?.toIntOrNull()
        if (output != null && items.isNotEmpty() && items.none { it.id == output }) out += "Item made $output does not exist"
        val parsed = parseIngredients(values["ingredients"].orEmpty())
        out += parsed.problems
        if (items.isNotEmpty()) for (i in parsed.items) if (items.none { it.id == i.itemId }) out += "Ingredient item ${i.itemId} does not exist"
        return out
    }

    class ParsedItems(val items: List<RecipeIngredient>, val problems: List<String>)

    /** One line per entry: `item [amount]`. Blank lines are ignored. [what] names the list in messages. */
    fun parseIngredients(text: String, what: String = "Ingredient"): ParsedItems {
        val items = ArrayList<RecipeIngredient>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val parts = line.split(Regex("\\s+"))
            val numbers = parts.map { it.toIntOrNull() }
            when {
                parts.size > 2 || numbers.any { it == null } -> problems += "$what line ${index + 1}: write 'item amount', whole numbers (only the item is needed)"
                numbers[0]!! < 1 -> problems += "$what line ${index + 1}: the item id must be a positive whole number"
                (numbers.getOrNull(1) ?: 1) < 1 -> problems += "$what line ${index + 1}: the amount must be at least 1"
                else -> items += RecipeIngredient(numbers[0]!!, numbers.getOrNull(1) ?: 1)
            }
        }
        return ParsedItems(items, problems)
    }

    fun formatIngredients(items: List<RecipeIngredient>): String = items.joinToString("\n") { "${it.itemId} ${it.amount}" }
}
