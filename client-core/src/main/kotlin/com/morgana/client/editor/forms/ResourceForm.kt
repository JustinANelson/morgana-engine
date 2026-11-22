package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.item.ToolType
import com.morgana.protocol.WorldContentCatalogDto

/** The gathering resource form (trees, ore veins, fishing spots, herb patches). */
object ResourceForm : ContentForm<ResourceDefinition> {
    override val title = "Resources"
    override val noun = "resource"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Resource", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("category", "Kind", FormFieldType.CHOICE, "TREE", ResourceCategory.entries.map { it.name }),
            FormField("tool", "Tool needed", FormFieldType.CHOICE, "HATCHET", ToolType.entries.map { it.name }),
            FormField("intactSprite", "Sprite when full", FormFieldType.INT, "1", min = 1),
            FormField("depletedSprite", "Sprite when used up", FormFieldType.INT, "2", min = 1),
            FormField("animation", "Animation", FormFieldType.ANIMATION, "0", min = 0)
        )),
        FormSection("Gathering", listOf(
            FormField("reward", "Item gained", FormFieldType.INT, "1", min = 1, ref = FieldRef.ITEM),
            FormField("minYield", "Fewest items per gather", FormFieldType.INT, "1", min = 1, max = 1000),
            FormField("maxYield", "Most items per gather", FormFieldType.INT, "2", min = 1, max = 1000),
            FormField("health", "Gathers before it is used up", FormFieldType.INT, "3", min = 1, max = 10_000),
            FormField("respawn", "Grows back after (ticks)", FormFieldType.INT, "160", min = 1),
            FormField("xp", "XP per gather", FormFieldType.LONG, "15", min = 0)
        )),
        FormSection("Messages", listOf(
            FormField("successMessage", "When gathered", FormFieldType.TEXT),
            FormField("swingText", "Text for each swing", FormFieldType.TEXT, "Chop!"),
            FormField("bonusDrops", "Rare extra finds", FormFieldType.LINES, hint = "One per line: item min-max chance (chance out of 10000). Example: 17 1 300"),
            FormField("emptyMessage", "When there is nothing left", FormFieldType.TEXT)
        ))
    )

    override fun idOf(definition: ResourceDefinition) = definition.id
    override fun nameOf(definition: ResourceDefinition) = definition.name

    override fun create(id: Int, context: FormContext): ResourceDefinition =
        ResourceDefinition(id = id, name = "New resource $id", rewardItemId = context.catalog.items.firstOrNull()?.id ?: 1)

    override fun all(catalog: WorldContentCatalogDto) = catalog.resources.sortedBy { it.id }

    override fun valuesOf(definition: ResourceDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "category" to d.category.name, "tool" to d.requiredTool.name,
            "intactSprite" to d.intactSpriteId.toString(), "depletedSprite" to d.depletedSpriteId.toString(), "animation" to d.animationId.toString(),
            "reward" to d.rewardItemId.toString(), "minYield" to d.minYield.toString(), "maxYield" to d.maxYield.toString(),
            "health" to d.maxHealth.toString(), "respawn" to d.respawnTicks.toString(), "xp" to d.xpReward.toString(),
            "successMessage" to d.successMessage, "emptyMessage" to d.emptyMessage,
            "swingText" to d.swingText, "bonusDrops" to NpcForm.formatDrops(d.bonusDrops)
        )
    }

    override fun apply(definition: ResourceDefinition, values: Map<String, String>, context: FormContext): ResourceDefinition {
        val d = definition
        fun i(key: String, fallback: Int, min: Int) = FormRules.int(values, key, fallback).coerceAtLeast(min)
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name },
            category = ResourceCategory.entries.firstOrNull { it.name == values["category"] } ?: d.category,
            requiredTool = ToolType.entries.firstOrNull { it.name == values["tool"] } ?: d.requiredTool,
            intactSpriteId = i("intactSprite", d.intactSpriteId, 1),
            depletedSpriteId = i("depletedSprite", d.depletedSpriteId, 1),
            animationId = i("animation", d.animationId, 0),
            rewardItemId = i("reward", d.rewardItemId, 1),
            minYield = i("minYield", d.minYield, 1),
            maxYield = i("maxYield", d.maxYield, 1),
            maxHealth = i("health", d.maxHealth, 1),
            respawnTicks = i("respawn", d.respawnTicks, 1),
            xpReward = FormRules.long(values, "xp", d.xpReward).coerceAtLeast(0),
            successMessage = FormRules.text(values, "successMessage", d.successMessage),
            swingText = FormRules.text(values, "swingText", d.swingText).ifBlank { d.swingText },
            bonusDrops = values["bonusDrops"]?.let { NpcForm.parseDrops(it).drops } ?: d.bonusDrops,
            emptyMessage = FormRules.text(values, "emptyMessage", d.emptyMessage)
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val min = values["minYield"]?.trim()?.toIntOrNull()
        val max = values["maxYield"]?.trim()?.toIntOrNull()
        if (min != null && max != null && max < min) out += "Most items per gather is below the fewest"
        val reward = values["reward"]?.trim()?.toIntOrNull()
        if (reward != null && context.catalog.items.isNotEmpty() && context.catalog.items.none { it.id == reward }) out += "Item gained $reward does not exist"
        val bonus = NpcForm.parseDrops(values["bonusDrops"].orEmpty())
        out += bonus.problems
        for (drop in bonus.drops) {
            if (context.catalog.items.isNotEmpty() && context.catalog.items.none { it.id == drop.itemId }) out += "Extra find item ${drop.itemId} does not exist"
        }
        val animation = values["animation"]?.trim()?.toIntOrNull() ?: 0
        if (animation != 0 && context.catalog.animations.isNotEmpty() && context.catalog.animations.none { it.id == animation }) out += "Animation $animation does not exist"
        return out
    }
}
