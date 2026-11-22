package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.AbilityTerminology
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.protocol.WorldContentCatalogDto

/**
 * The class form: a playable class's look, stats, start position, starting bag and known spells. Starting items are a text
 * area with one stack per line: `item amount` (or `item amount durability` when the durability is not 100).
 */
object ClassForm : ContentForm<ClassDefinition> {
    override val title = "Classes"
    override val noun = "class"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Identity", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("description", "Description", FormFieldType.TEXT, hint = "Shown when creating a character."),
            FormField("sprites", "Character sprites (ids)", FormFieldType.ID_LIST, "1", ref = FieldRef.NONE, hint = "The looks a player can pick for this class."),
            FormField("terminology", "Abilities are called", FormFieldType.CHOICE, "SPELLS", AbilityTerminology.entries.map { it.name })
        )),
        FormSection("Stats", context.attributeIds.map { FormField("attr.$it", "Starting $it", FormFieldType.INT, "0", min = 0) } +
            context.attributeIds.map { FormField("grow.$it", "$it gained per level", FormFieldType.INT, "0", min = 0) } +
            FormField("points", "Points to spend per level", FormFieldType.INT, "3", min = 0, max = 100)),
        FormSection("Start", listOf(
            FormField("startMap", "Start map", FormFieldType.INT, "1", min = 1, ref = FieldRef.MAP),
            FormField("startX", "Start tile X", FormFieldType.INT, "12", min = 0),
            FormField("startY", "Start tile Y", FormFieldType.INT, "9", min = 0),
            FormField("items", "Starting items", FormFieldType.LINES, hint = "One per line: item amount. Example: 2 1 (one of item 2)."),
            FormField("autoEquip", "Equip starting gear", FormFieldType.BOOL, "false"),
            FormField("spells", "Known spells (ids)", FormFieldType.ID_LIST, ref = FieldRef.SPELL)
        ))
    )

    override fun idOf(definition: ClassDefinition) = definition.id
    override fun nameOf(definition: ClassDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = ClassDefinition(
        id = id, name = "New class $id", baseAttributes = context.attributeIds.associateWith { 10 }
    )
    override fun all(catalog: WorldContentCatalogDto) = catalog.classes.sortedBy { it.id }

    override fun valuesOf(definition: ClassDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "description" to d.description, "sprites" to d.spriteIds.joinToString(", "),
            "terminology" to d.abilityTerminology.name, "points" to d.statPointsPerLevel.toString(),
            "startMap" to d.startMapId.toString(), "startX" to d.startX.toString(), "startY" to d.startY.toString(),
            "items" to formatItems(d.startingItems), "autoEquip" to d.autoEquipStartingItems.toString(),
            "spells" to d.startingSpellIds.joinToString(", ")
        ) + FormRules.mapValues(d.baseAttributes, "attr.", context.attributeIds) + FormRules.mapValues(d.autoStatGrowthPerLevel, "grow.", context.attributeIds)
    }

    override fun apply(definition: ClassDefinition, values: Map<String, String>, context: FormContext): ClassDefinition {
        val d = definition
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name },
            description = FormRules.text(values, "description", d.description),
            spriteIds = values["sprites"]?.let { FormRules.parseIds(it) }?.takeIf { it.isNotEmpty() } ?: d.spriteIds,
            abilityTerminology = AbilityTerminology.entries.firstOrNull { it.name == values["terminology"] } ?: d.abilityTerminology,
            baseAttributes = FormRules.mergeMap(d.baseAttributes, "attr.", context.attributeIds, values),
            autoStatGrowthPerLevel = FormRules.mergeMap(d.autoStatGrowthPerLevel, "grow.", context.attributeIds, values),
            statPointsPerLevel = FormRules.int(values, "points", d.statPointsPerLevel).coerceAtLeast(0),
            startMapId = FormRules.int(values, "startMap", d.startMapId).coerceAtLeast(1),
            startX = FormRules.int(values, "startX", d.startX).coerceAtLeast(0),
            startY = FormRules.int(values, "startY", d.startY).coerceAtLeast(0),
            startingItems = values["items"]?.let { parseItems(it).items } ?: d.startingItems,
            autoEquipStartingItems = FormRules.bool(values, "autoEquip", d.autoEquipStartingItems),
            startingSpellIds = values["spells"]?.let { FormRules.parseIds(it) } ?: d.startingSpellIds
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val parsed = parseItems(values["items"].orEmpty())
        out += parsed.problems
        val catalog = context.catalog
        for (stack in parsed.items) {
            if (catalog.items.isNotEmpty() && catalog.items.none { it.id == stack.itemId }) out += "Starting item ${stack.itemId} does not exist"
        }
        for (id in FormRules.parseIds(values["spells"].orEmpty()).orEmpty()) {
            if (catalog.spells.isNotEmpty() && catalog.spells.none { it.id == id }) out += "Spell $id does not exist"
        }
        val map = values["startMap"]?.trim()?.toIntOrNull()
        if (map != null && catalog.maps.isNotEmpty() && catalog.maps.none { it.id == map }) out += "Start map $map does not exist"
        return out
    }

    class ParsedItems(val items: List<ItemStack>, val problems: List<String>)

    fun parseItems(text: String): ParsedItems {
        val items = ArrayList<ItemStack>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Starting item ${index + 1}"
            val parts = line.split(Regex("\\s+"))
            val id = parts.getOrNull(0)?.toIntOrNull()
            val amount = parts.getOrNull(1)?.toIntOrNull()
            val durability = if (parts.size == 3) parts[2].toIntOrNull() else 100
            when {
                parts.size !in 2..3 -> problems += "$at: write 'item amount', for example '2 1'"
                id == null || id < 1 -> problems += "$at: the item id must be a positive whole number"
                amount == null || amount !in 1..9999 -> problems += "$at: the amount must be 1 to 9999"
                durability == null || durability !in 0..100 -> problems += "$at: the durability must be 0 to 100"
                else -> items += ItemStack(itemId = id, amount = amount, durability = durability)
            }
        }
        return ParsedItems(items, problems)
    }

    fun formatItems(items: List<ItemStack>): String = items.joinToString("\n") {
        "${it.itemId} ${it.amount}${if (it.durability != 100) " " + it.durability else ""}"
    }
}
