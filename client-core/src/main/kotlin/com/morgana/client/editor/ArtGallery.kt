package com.morgana.client.editor

import com.morgana.protocol.WorldContentCatalogDto
import com.morgana.protocol.realms.AssetKind

/** The kinds of art the Art panel shows, with where a game puts its own files for each. */
enum class ArtKind(val title: String, val folder: String, val assetKind: AssetKind, val about: String) {
    ICONS("Icons", "icons", AssetKind.ICON, "Item pictures in bags, shops and tooltips. A game's own icons go in assets/icons/<id>.png."),
    SPRITES("Characters", "sprites", AssetKind.SPRITE, "Walking sheets for players and NPCs (4 direction rows). Files go in assets/sprites/<id>.png."),
    PAPERDOLLS("Worn gear", "paperdolls", AssetKind.PAPERDOLL, "Layers drawn over a character for equipment. Files go in assets/paperdolls/<id>.png."),
    PROJECTILES("Projectiles", "projectiles", AssetKind.PROJECTILE, "Arrows, bolts and the like. Files go in assets/projectiles/<id>.png."),
    ANIMATIONS("Effects", "animations", AssetKind.ANIMATION, "Spell and combat effect strips (square frames side-by-side). Files go in assets/animations/<id>.png."),
    TILESETS("Tilesets", "tilesets", AssetKind.TILESET, "32 x 32 map tile sheets. Files go in assets/tilesets/<id>.png.")
}

/** Pure helpers for the Art panel: which art the game's content points at but nobody has drawn. */
object ArtGallery {

    /** The ids the content refers to for [kind] (0 means "none" and is ignored). */
    fun usedIds(kind: ArtKind, catalog: WorldContentCatalogDto): Set<Int> = when (kind) {
        ArtKind.ICONS -> catalog.items.map { it.iconId }
        ArtKind.SPRITES -> catalog.npcs.map { it.spriteId }
        ArtKind.PAPERDOLLS -> catalog.items.flatMap { listOf(it.paperdollSpriteId, it.paperdollOffHandSpriteId) }
        ArtKind.PROJECTILES -> catalog.items.map { it.projectileSpriteId }
        ArtKind.ANIMATIONS -> emptyList()
        ArtKind.TILESETS -> emptyList()
    }.filter { it > 0 }.toSortedSet()

    /** Ids the content uses that have no image at all, so the game draws a stand-in. */
    fun missing(kind: ArtKind, catalog: WorldContentCatalogDto, available: Collection<Int>): List<Int> =
        (usedIds(kind, catalog) - available.toSet()).toList()

    /** A one-line summary under the grid. */
    fun summary(kind: ArtKind, total: Int, custom: Int, missing: List<Int>): String {
        val own = if (custom == 0) "none from this game (all built-in)" else "$custom from this game"
        val gaps = if (missing.isEmpty()) "" else " Content uses ${kind.title.lowercase()} ${missing.take(8).joinToString(", ")}${if (missing.size > 8) "..." else ""} that have no image."
        return "$total ${kind.title.lowercase()}: $own.$gaps"
    }
}
