package com.morgana.persistence

import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentIssue
import com.morgana.domain.validation.ContentReport
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.IssueSeverity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** Result of reading every content file under a world directory. */
data class ContentLoadResult(
    val catalog: ContentCatalog,
    /** File-level problems (parse errors, unknown fields, duplicate ids, filename mismatches). */
    val fileIssues: List<ContentIssue>
) {
    /**
     * File-level issues plus cross-reference validation of the loaded catalog. Pass the game's own [game] definition: attribute
     * and vital ids in classes, items and NPCs are checked against it, so a game with its own attributes needs its own rules here.
     */
    fun fullReport(game: com.morgana.domain.game.GameDefinition = com.morgana.domain.game.BlankGame.definition): ContentReport =
        ContentReport(fileIssues + ContentValidator.validate(catalog, game).issues)
}

/**
 * Reads world content straight from `<worldDir>/<collection>/<id>.json` files without throwing on bad files,
 * so humans, scripts, and AI agents get a complete, machine-readable list of every problem at once.
 *
 * Unknown JSON fields (typically typos such as `maxHP`) are reported as warnings; the document is still
 * loaded using lenient decoding so the server remains usable.
 */
object ContentLoader {
    private val strictJson = Json { ignoreUnknownKeys = false; classDiscriminator = "_type" }
    private val lenientJson = Json { ignoreUnknownKeys = true; classDiscriminator = "_type" }

    fun load(worldDir: Path): ContentLoadResult {
        val issues = mutableListOf<ContentIssue>()
        fun <T> read(col: ContentCollection, ser: KSerializer<T>, idOf: (T) -> Int): Map<Int, T> =
            readCollection(worldDir, col, ser, idOf, issues)

        val catalog = ContentCatalog(
            maps = read(ContentCollection.MAPS, MapDefinition.serializer()) { it.id },
            classes = read(ContentCollection.CLASSES, ClassDefinition.serializer()) { it.id },
            items = read(ContentCollection.ITEMS, ItemDefinition.serializer()) { it.id },
            npcs = read(ContentCollection.NPCS, NpcDefinition.serializer()) { it.id },
            spells = read(ContentCollection.SPELLS, SpellDefinition.serializer()) { it.id },
            shops = read(ContentCollection.SHOPS, ShopDefinition.serializer()) { it.id },
            resources = read(ContentCollection.RESOURCES, ResourceDefinition.serializer()) { it.id },
            recipes = read(ContentCollection.RECIPES, RecipeDefinition.serializer()) { it.id },
            quests = read(ContentCollection.QUESTS, QuestDefinition.serializer()) { it.id },
            events = read(ContentCollection.EVENTS, EventDefinition.serializer()) { it.id },
            commonEvents = read(ContentCollection.COMMON_EVENTS, CommonEventDefinition.serializer()) { it.id },
            animations = read(ContentCollection.ANIMATIONS, AnimationDefinition.serializer()) { it.id },
            actionSkills = read(ContentCollection.ACTION_SKILLS, com.morgana.domain.content.ActionSkillDefinition.serializer()) { it.id },
            weaponSets = read(ContentCollection.WEAPON_SETS, com.morgana.domain.content.WeaponSetDefinition.serializer()) { it.id }
        )
        return ContentLoadResult(catalog, issues)
    }

    private fun <T> readCollection(
        worldDir: Path,
        col: ContentCollection,
        serializer: KSerializer<T>,
        idOf: (T) -> Int,
        issues: MutableList<ContentIssue>
    ): Map<Int, T> {
        val dir = worldDir.resolve(col.directoryName)
        if (!dir.exists()) return emptyMap()
        val result = linkedMapOf<Int, T>()
        val files = Files.list(dir).use { s ->
            s.filter { it.isRegularFile() && it.extension == "json" }.sorted().toList()
        }
        for (path in files) {
            val rel = "${col.directoryName}/${path.fileName}"
            val stemId = path.nameWithoutExtension.substringAfterLast('_').toIntOrNull() ?: 0
            val text = path.readText()
            val doc: T = try {
                strictJson.decodeFromString(serializer, text)
            } catch (strictFailure: Exception) {
                try {
                    val lenient = lenientJson.decodeFromString(serializer, text)
                    issues += ContentIssue(
                        IssueSeverity.WARNING, col, stemId, "", "unknown_field",
                        firstLine(strictFailure), rel
                    )
                    lenient
                } catch (failure: Exception) {
                    issues += ContentIssue(
                        IssueSeverity.ERROR, col, stemId, "", "parse_error", firstLine(failure), rel
                    )
                    continue
                }
            }
            val id = idOf(doc)
            if (result.containsKey(id)) {
                issues += ContentIssue(IssueSeverity.ERROR, col, id, "/id", "duplicate_id", "Another file already defines ${col.directoryName} id $id", rel)
                continue
            }
            if (path.nameWithoutExtension != col.fileStem(id)) {
                issues += ContentIssue(
                    IssueSeverity.WARNING, col, id, "/id", "filename_mismatch",
                    "File name should be ${col.fileStem(id)}.json for id $id", rel
                )
            }
            result[id] = doc
        }
        return result
    }

    private fun firstLine(e: Exception): String = (e.message ?: e::class.simpleName ?: "error").lineSequence().first()
}
