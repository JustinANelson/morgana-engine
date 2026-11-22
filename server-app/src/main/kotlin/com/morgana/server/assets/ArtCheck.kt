package com.morgana.server.assets

import com.morgana.protocol.realms.AssetKind
import java.nio.file.Files
import java.nio.file.Path

/** What [ArtCheck] found: how many files of each kind the game ships, what is wrong (errors) and what is only worth a look (warnings). */
data class ArtReport(val counts: Map<AssetKind, Int>, val errors: List<String>, val warnings: List<String>) {
    val isClean: Boolean get() = errors.isEmpty()
}

/**
 * The creator's art check: everything [GameAssets.scan] enforces, plus a look at how the art lines up with the game's content.
 *
 * Warnings (not errors, because falling back to the built-in art is allowed): a file no content refers to, so it is never
 * drawn (an icon no item uses, a sprite no NPC or class uses...). Pure: reads files only.
 */
object ArtCheck {

    private fun field(name: String) = Regex("\"$name\"\\s*:\\s*(\\d+)")
    private val SPRITE = Regex("\"(?:spriteId|spriteSheetId)\"\\s*:\\s*(\\d+)")
    private val ID = field("id")

    fun check(gameDir: Path, gameId: String = gameDir.fileName.toString()): ArtReport {
        val sprites = runCatching {
            val file = com.morgana.persistence.GameDefinitionLoader.fileIn(gameDir)
            if (Files.exists(file)) com.morgana.persistence.GameDefinitionLoader.load(gameDir).game.definition.sprites else null
        }.getOrNull()
        val scan = GameAssets.scan(gameDir, gameId, sprites = sprites)
        val counts = scan.manifest.assets.values.groupingBy { it.kind }.eachCount()
        val warnings = ArrayList<String>()
        fun unused(kind: AssetKind, folder: String, label: String, used: Set<Int>) {
            for (entry in scan.manifest.assets.values.filter { it.kind == kind }) {
                val id = entry.path.substringAfter('/').removeSuffix(".png").toIntOrNull() ?: continue
                if (id !in used) warnings += "assets/${entry.path}: no $label uses $folder id $id, so this file is never drawn"
            }
        }
        unused(AssetKind.SPRITE, "sprite", "NPC or class", usedIds(gameDir, SPRITE, null))
        // Spells draw an icon too (the spellbook and quickbar), so their icon ids count as used.
        unused(AssetKind.ICON, "icon", "item", usedIds(gameDir, field("iconId"), "items") + usedIds(gameDir, field("iconId"), "spells") + usedIds(gameDir, field("iconId"), "action_skills"))
        unused(AssetKind.PAPERDOLL, "paperdoll", "item", usedIds(gameDir, Regex("\"paperdoll(?:OffHand)?SpriteId\"\\s*:\\s*(\\d+)"), "items"))
        unused(AssetKind.PROJECTILE, "projectile", "item", usedIds(gameDir, field("projectileSpriteId"), "items") + usedIds(gameDir, field("projectileSpriteId"), "spells"))
        unused(AssetKind.ANIMATION, "animation", "animation", usedIds(gameDir, ID, "animations"))
        return ArtReport(counts, scan.errors, warnings)
    }

    /** Ids matched by [field] in the game's `data/` JSON files (`sub` limits it to one collection folder); 0 means none. */
    fun usedIds(gameDir: Path, field: Regex, sub: String?): Set<Int> {
        val data = gameDir.resolve("data").let { if (sub != null) it.resolve(sub) else it }
        if (!Files.isDirectory(data)) return emptySet()
        val ids = sortedSetOf<Int>()
        Files.walk(data).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.forEach { file ->
                field.findAll(Files.readString(file)).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.forEach { ids += it }
            }
        }
        return ids
    }
}
