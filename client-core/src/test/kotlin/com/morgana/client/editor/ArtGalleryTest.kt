package com.morgana.client.editor

import com.morgana.client.assets.ReplaceableArt
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtGalleryTest {

    private val catalog = WorldContentCatalogDto(
        items = listOf(
            ItemDefinition(id = 1, name = "Coin", iconId = 1),
            ItemDefinition(id = 2, name = "Sword", iconId = 40, paperdollSpriteId = 101, projectileSpriteId = 0),
            ItemDefinition(id = 3, name = "Bow", iconId = 40, paperdollSpriteId = 0, projectileSpriteId = 9)
        ),
        npcs = listOf(NpcDefinition(id = 1, name = "Hen", spriteId = 15), NpcDefinition(id = 2, name = "Cow", spriteId = 16))
    )

    @Test
    fun `used ids come from the content and zero means none`() {
        assertEquals(setOf(1, 40), ArtGallery.usedIds(ArtKind.ICONS, catalog))
        assertEquals(setOf(101), ArtGallery.usedIds(ArtKind.PAPERDOLLS, catalog))
        assertEquals(setOf(9), ArtGallery.usedIds(ArtKind.PROJECTILES, catalog))
        assertEquals(setOf(15, 16), ArtGallery.usedIds(ArtKind.SPRITES, catalog))
        assertTrue(ArtGallery.usedIds(ArtKind.ANIMATIONS, catalog).isEmpty())
    }

    @Test
    fun `missing art is what content uses that nobody drew`() {
        assertEquals(listOf(40), ArtGallery.missing(ArtKind.ICONS, catalog, listOf(1, 2, 3)))
        assertEquals(emptyList<Int>(), ArtGallery.missing(ArtKind.ICONS, catalog, listOf(1, 40)))
        val text = ArtGallery.summary(ArtKind.ICONS, 3, 1, listOf(40))
        assertTrue("1 from this game" in text && "40" in text, text)
        assertTrue("all built-in" in ArtGallery.summary(ArtKind.SPRITES, 5, 0, emptyList()))
    }

    @Test
    fun `replaced ids are reported and cleared on restore`() {
        val live = mutableMapOf(1 to "a", 2 to "b")
        val art = ReplaceableArt(live)
        art.replace(2, "x"); art.replace(7, "y")
        assertEquals(setOf(2, 7), art.replacedIds)
        art.restore()
        assertEquals(emptySet<Int>(), art.replacedIds)
        assertEquals(mapOf(1 to "a", 2 to "b"), live)
    }
}
