package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.protocol.WorldContentCatalogDto

/**
 * The quest form. Objectives are a text area with one per line: `TYPE target count description`, where the count (default 1)
 * and the description are optional. Example: `KILL_NPC 4 3 Slay the slimes`. The types are [QuestObjectiveType]; the target is
 * the id of the NPC, item, resource, recipe or map the type names. Reward items are `item amount` lines.
 */
object QuestForm : ContentForm<QuestDefinition> {
    override val title = "Quests"
    override val noun = "quest"
    override val firstId = 1

    private val typeNames = QuestObjectiveType.entries.joinToString(", ") { it.name }
    private val typeHelp = QuestObjectiveType.entries.joinToString("; ") { "${it.name} (${ChoiceText.label(it.name).lowercase()})" }

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Quest", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("summary", "Summary", FormFieldType.TEXT),
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1, max = 1000),
            FormField("prerequisites", "Quests to finish first (ids)", FormFieldType.ID_LIST),
            FormField("repeatable", "Can be repeated", FormFieldType.BOOL, "false")
        )),
        FormSection("Objectives", listOf(
            FormField("objectives", "Objectives", FormFieldType.LINES, hint = "One step per line: TYPE target count description. The target is the id of the creature, item, resource, recipe or map. Types: $typeHelp. Example: KILL_NPC 4 3 Slay the slimes")
        )),
        FormSection("Text", listOf(
            FormField("startText", "When offered", FormFieldType.TEXT),
            FormField("inProgressText", "While in progress", FormFieldType.TEXT),
            FormField("completionText", "When complete", FormFieldType.TEXT)
        )),
        FormSection("Reward", listOf(
            FormField("rewardXp", "XP", FormFieldType.LONG, "50", min = 0),
            FormField("rewardCurrency", "Currency", FormFieldType.INT, "25", min = 0),
            FormField("rewardItems", "Items", FormFieldType.LINES, hint = "One per line: item amount. Example: 13 2"),
            FormField("teachSpell", "Spell taught (0 = none)", FormFieldType.INT, "0", min = 0, ref = FieldRef.SPELL)
        ))
    )

    override fun idOf(definition: QuestDefinition) = definition.id
    override fun nameOf(definition: QuestDefinition) = definition.name

    override fun create(id: Int, context: FormContext): QuestDefinition {
        val npc = context.catalog.npcs.minByOrNull { it.id }
        val objectives = if (npc != null) listOf(QuestObjective(QuestObjectiveType.KILL_NPC, npc.id, 1, "Defeat ${npc.name}")) else emptyList()
        return QuestDefinition(id = id, name = "New quest $id", objectives = objectives)
    }

    override fun all(catalog: WorldContentCatalogDto) = catalog.quests.sortedBy { it.id }

    override fun valuesOf(definition: QuestDefinition, context: FormContext): Map<String, String> = mapOf(
        "name" to definition.name, "summary" to definition.summary, "levelReq" to definition.levelReq.toString(),
        "prerequisites" to definition.prerequisiteQuestIds.sorted().joinToString(", "), "repeatable" to definition.repeatable.toString(),
        "objectives" to formatObjectives(definition.objectives),
        "startText" to definition.startText, "inProgressText" to definition.inProgressText, "completionText" to definition.completionText,
        "rewardXp" to definition.reward.xp.toString(), "rewardCurrency" to definition.reward.currencyAmount.toString(),
        "rewardItems" to RecipeForm.formatIngredients(definition.reward.items), "teachSpell" to definition.reward.teachSpellId.toString()
    )

    override fun apply(definition: QuestDefinition, values: Map<String, String>, context: FormContext): QuestDefinition {
        val r = definition.reward
        return definition.copy(
            name = FormRules.text(values, "name", definition.name).ifBlank { definition.name },
            summary = FormRules.text(values, "summary", definition.summary),
            levelReq = FormRules.int(values, "levelReq", definition.levelReq).coerceAtLeast(1),
            prerequisiteQuestIds = values["prerequisites"]?.let { FormRules.parseIds(it)?.toSet() } ?: definition.prerequisiteQuestIds,
            repeatable = FormRules.bool(values, "repeatable", definition.repeatable),
            objectives = values["objectives"]?.let { parseObjectives(it).objectives } ?: definition.objectives,
            startText = FormRules.text(values, "startText", definition.startText),
            inProgressText = FormRules.text(values, "inProgressText", definition.inProgressText),
            completionText = FormRules.text(values, "completionText", definition.completionText),
            reward = r.copy(
                xp = FormRules.long(values, "rewardXp", r.xp).coerceAtLeast(0),
                currencyAmount = FormRules.int(values, "rewardCurrency", r.currencyAmount).coerceAtLeast(0),
                items = values["rewardItems"]?.let { RecipeForm.parseIngredients(it, "Reward item").items } ?: r.items,
                teachSpellId = FormRules.int(values, "teachSpell", r.teachSpellId).coerceAtLeast(0)
            )
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val c = context.catalog
        val parsed = parseObjectives(values["objectives"].orEmpty())
        out += parsed.problems
        for (o in parsed.objectives) {
            val exists = when (o.type) {
                QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC -> c.npcs.isEmpty() || c.npcs.any { it.id == o.targetId }
                QuestObjectiveType.COLLECT_ITEM -> c.items.isEmpty() || c.items.any { it.id == o.targetId }
                QuestObjectiveType.HARVEST_RESOURCE -> c.resources.isEmpty() || c.resources.any { it.id == o.targetId }
                QuestObjectiveType.CRAFT_RECIPE -> c.recipes.isEmpty() || c.recipes.any { it.id == o.targetId }
                QuestObjectiveType.VISIT_MAP -> c.maps.isEmpty() || c.maps.any { it.id == o.targetId }
            }
            if (!exists) out += "Objective target ${o.targetId} does not exist for ${o.type.name}"
        }
        val rewards = RecipeForm.parseIngredients(values["rewardItems"].orEmpty(), "Reward item")
        out += rewards.problems
        if (c.items.isNotEmpty()) for (i in rewards.items) if (c.items.none { it.id == i.itemId }) out += "Reward item ${i.itemId} does not exist"
        val prereqs = FormRules.parseIds(values["prerequisites"].orEmpty()).orEmpty()
        if (c.quests.isNotEmpty()) for (p in prereqs) if (c.quests.none { it.id == p }) out += "Required quest $p does not exist"
        val spell = values["teachSpell"]?.trim()?.toIntOrNull() ?: 0
        if (spell != 0 && c.spells.isNotEmpty() && c.spells.none { it.id == spell }) out += "Spell taught $spell does not exist"
        return out
    }

    class ParsedObjectives(val objectives: List<QuestObjective>, val problems: List<String>)

    /** One line per objective: `TYPE target [count [description...]]`. Blank lines are ignored. */
    fun parseObjectives(text: String): ParsedObjectives {
        val list = ArrayList<QuestObjective>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Objective line ${index + 1}"
            val parts = line.split(Regex("\\s+"), limit = 4)
            val type = QuestObjectiveType.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) }
            val target = parts.getOrNull(1)?.toIntOrNull()
            val count = if (parts.size > 2) parts[2].toIntOrNull() else 1
            when {
                type == null -> problems += "$at: start with a type ($typeNames)"
                target == null || target < 1 -> problems += "$at: the target must be a positive whole number"
                count == null || count < 1 -> problems += "$at: the count must be a whole number of at least 1"
                else -> list += QuestObjective(type, target, count, parts.getOrNull(3).orEmpty())
            }
        }
        return ParsedObjectives(list, problems)
    }

    fun formatObjectives(objectives: List<QuestObjective>): String =
        objectives.joinToString("\n") { "${it.type.name} ${it.targetId} ${it.requiredCount}" + if (it.description.isNotEmpty()) " ${it.description}" else "" }
}
