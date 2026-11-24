package com.morgana.domain.validation

import kotlinx.serialization.Serializable

/** One document the salvage left out of a reload, with what was wrong with it. */
@Serializable
data class RejectedDocument(
    val collection: ContentCollection,
    val id: Int,
    /** The file, relative to the content directory, when known. */
    val file: String,
    /** True when the version already live was kept; false when the document is new and so is simply absent. */
    val keptPrevious: Boolean,
    val errors: List<ContentIssue>
)

/** What [ContentSalvage.salvage] made of a load. [catalog] is safe to apply when [applicable]. */
data class SalvageResult(
    val catalog: ContentCatalog,
    val report: ContentReport,
    val rejected: List<RejectedDocument>,
    /** False when errors remained that salvaging could not remove (apply nothing in that case). */
    val applicable: Boolean
)

/**
 * Per-document tolerance for content reloads. A reload used to apply nothing when any file had an error, so one half-written
 * or wrong file from a script or an LLM blocked every other change. Salvage instead puts the version that is already live
 * back in place of each document that has an error (or leaves a brand-new bad document out), validates again, and repeats until
 * the rest is clean, so every good file applies and every bad one is reported with its problems.
 */
object ContentSalvage {
    private const val MAX_ROUNDS = 25

    fun salvage(
        incoming: ContentCatalog,
        fileIssues: List<ContentIssue>,
        live: ContentCatalog,
        game: com.morgana.domain.game.GameDefinition
    ): SalvageResult {
        var catalog = incoming
        val rejected = linkedMapOf<Pair<ContentCollection, Int>, MutableList<ContentIssue>>()
        val files = linkedMapOf<Pair<ContentCollection, Int>, String>()
        fun note(issue: ContentIssue) {
            val key = issue.collection to issue.id
            rejected.getOrPut(key) { mutableListOf() }.let { if (issue !in it) it += issue }
            if (issue.file.isNotBlank()) files.putIfAbsent(key, issue.file)
        }
        // A file that cannot be read or repeats an id: reported, and the version already live stays (it is not in the incoming catalog).
        for (issue in fileIssues.filter { it.severity == IssueSeverity.ERROR }) {
            note(issue)
            if (issue.code == "parse_error" && catalog.documentOf(issue.collection, issue.id) == null) {
                live.documentOf(issue.collection, issue.id)?.let { catalog = catalog.with(issue.collection, issue.id, it) }
            }
        }
        fun blocking(): List<ContentIssue> = ContentValidator.validate(catalog, game).issues.filter { it.severity == IssueSeverity.ERROR }
        var errors = blocking()
        var rounds = 0
        while (errors.isNotEmpty() && rounds++ < MAX_ROUNDS) {
            var changed = false
            for (issue in errors) {
                note(issue)
                val before = catalog.documentOf(issue.collection, issue.id)
                val replacement = live.documentOf(issue.collection, issue.id)
                catalog = if (replacement != null) catalog.with(issue.collection, issue.id, replacement) else catalog.without(issue.collection, issue.id)
                if (catalog.documentOf(issue.collection, issue.id) != before) changed = true
            }
            if (!changed) break
            errors = blocking()
        }
        val finalIssues = ContentValidator.validate(catalog, game).issues
        val rejectedList = rejected.map { (key, problems) ->
            RejectedDocument(key.first, key.second, files[key] ?: "${key.first.directoryName}/${key.first.fileStem(key.second)}.json", live.documentOf(key.first, key.second) != null, problems)
        }
        // The report keeps the errors of the documents that were set aside, so the author sees what was wrong with them.
        val everything = (fileIssues + rejectedList.flatMap { it.errors } + finalIssues).distinct()
        return SalvageResult(catalog, ContentReport(everything), rejectedList, errors.isEmpty())
    }
}

/** The document with [id] in [collection], or null. */
fun ContentCatalog.documentOf(collection: ContentCollection, id: Int): Any? = when (collection) {
    ContentCollection.MAPS -> maps[id]
    ContentCollection.CLASSES -> classes[id]
    ContentCollection.ITEMS -> items[id]
    ContentCollection.NPCS -> npcs[id]
    ContentCollection.SPELLS -> spells[id]
    ContentCollection.SHOPS -> shops[id]
    ContentCollection.RESOURCES -> resources[id]
    ContentCollection.RECIPES -> recipes[id]
    ContentCollection.QUESTS -> quests[id]
    ContentCollection.EVENTS -> events[id]
    ContentCollection.COMMON_EVENTS -> commonEvents[id]
    ContentCollection.ANIMATIONS -> animations[id]
    ContentCollection.ACTION_SKILLS -> actionSkills[id]
    ContentCollection.WEAPON_SETS -> weaponSets[id]
}

/** This catalog without the document [id] of [collection]. */
fun ContentCatalog.without(collection: ContentCollection, id: Int): ContentCatalog = when (collection) {
    ContentCollection.MAPS -> copy(maps = maps - id)
    ContentCollection.CLASSES -> copy(classes = classes - id)
    ContentCollection.ITEMS -> copy(items = items - id)
    ContentCollection.NPCS -> copy(npcs = npcs - id)
    ContentCollection.SPELLS -> copy(spells = spells - id)
    ContentCollection.SHOPS -> copy(shops = shops - id)
    ContentCollection.RESOURCES -> copy(resources = resources - id)
    ContentCollection.RECIPES -> copy(recipes = recipes - id)
    ContentCollection.QUESTS -> copy(quests = quests - id)
    ContentCollection.EVENTS -> copy(events = events - id)
    ContentCollection.COMMON_EVENTS -> copy(commonEvents = commonEvents - id)
    ContentCollection.ANIMATIONS -> copy(animations = animations - id)
    ContentCollection.ACTION_SKILLS -> copy(actionSkills = actionSkills - id)
    ContentCollection.WEAPON_SETS -> copy(weaponSets = weaponSets - id)
}

/** This catalog with [document] as the document [id] of [collection] (the type must match the collection). */
@Suppress("UNCHECKED_CAST")
fun ContentCatalog.with(collection: ContentCollection, id: Int, document: Any): ContentCatalog = when (collection) {
    ContentCollection.MAPS -> copy(maps = maps + (id to document as com.morgana.domain.map.MapDefinition))
    ContentCollection.CLASSES -> copy(classes = classes + (id to document as com.morgana.domain.content.ClassDefinition))
    ContentCollection.ITEMS -> copy(items = items + (id to document as com.morgana.domain.item.ItemDefinition))
    ContentCollection.NPCS -> copy(npcs = npcs + (id to document as com.morgana.domain.content.NpcDefinition))
    ContentCollection.SPELLS -> copy(spells = spells + (id to document as com.morgana.domain.content.SpellDefinition))
    ContentCollection.SHOPS -> copy(shops = shops + (id to document as com.morgana.domain.content.ShopDefinition))
    ContentCollection.RESOURCES -> copy(resources = resources + (id to document as com.morgana.domain.content.ResourceDefinition))
    ContentCollection.RECIPES -> copy(recipes = recipes + (id to document as com.morgana.domain.content.RecipeDefinition))
    ContentCollection.QUESTS -> copy(quests = quests + (id to document as com.morgana.domain.event.QuestDefinition))
    ContentCollection.EVENTS -> copy(events = events + (id to document as com.morgana.domain.event.EventDefinition))
    ContentCollection.COMMON_EVENTS -> copy(commonEvents = commonEvents + (id to document as com.morgana.domain.event.CommonEventDefinition))
    ContentCollection.ANIMATIONS -> copy(animations = animations + (id to document as com.morgana.domain.content.AnimationDefinition))
    ContentCollection.ACTION_SKILLS -> copy(actionSkills = actionSkills + (id to document as com.morgana.domain.content.ActionSkillDefinition))
    ContentCollection.WEAPON_SETS -> copy(weaponSets = weaponSets + (id to document as com.morgana.domain.content.WeaponSetDefinition))
}
