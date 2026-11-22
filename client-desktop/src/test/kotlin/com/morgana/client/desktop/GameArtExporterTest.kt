package com.morgana.client.desktop

import com.morgana.domain.content.NpcDefinition
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.WorldContentRepository
import com.morgana.server.assets.GameAssets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Exporting the built-in art gives a game valid, editable PNGs it owns. */
class GameArtExporterTest {

    /**
     * A throwaway scaffolded game with no art of its own: the NPC uses sprite 2 and the item icon 1. An extra NPC with
     * sprite 0 checks that "no sprite" is ignored. (Class sprites are a list, `spriteIds`, which the exporter does not read.)
     */
    private fun testGame(into: Path): Path {
        val game = GameScaffolder.scaffold(into.resolve("game"), "Test Game", "test_game")
        WorldContentRepository(game.resolve("data")).npcs.save(2, NpcDefinition(id = 2, name = "Ghost", spriteId = 0))
        return game
    }

    @Test
    fun `used sprite ids come from content sprite fields and ignore zero`(@TempDir dir: Path) {
        val game = testGame(dir)
        val ids = GameArtExporter.usedSpriteIds(game)
        assertEquals(setOf(2), ids)
        assertTrue(0 !in ids)
    }

    @Test
    fun `export writes the tileset and used sprites and the server accepts them`(@TempDir dir: Path) {
        val game = testGame(dir)
        val used = GameArtExporter.usedSpriteIds(game)
        val result = GameArtExporter.export(game)

        assertEquals(emptyList<String>(), result.errors)
        assertTrue(result.written.toSet().containsAll(setOf("tilesets/1.png") + used.map { "sprites/$it.png" }), result.written.toString())
        assertTrue("icons/1.png" in result.written, "the icons the items use are exported too: ${result.written}")
        assertTrue(result.written.all { it.substringBefore('/') in setOf("tilesets", "sprites", "icons", "paperdolls", "projectiles", "animations") })

        val scan = GameAssets.scan(game, "test_game")
        assertTrue(scan.errors.isEmpty(), scan.errors.toString())
        assertEquals(result.written.toSet(), scan.manifest.assets.keys)
        assertEquals(512 to 96, GameAssets.pngSize(Files.readAllBytes(game.resolve("assets/tilesets/1.png"))))
        assertEquals(96 to 128, GameAssets.pngSize(Files.readAllBytes(game.resolve("assets/sprites/2.png"))))
    }

    @Test
    fun `a second export keeps edited files unless forced`(@TempDir dir: Path) {
        val game = testGame(dir)
        GameArtExporter.export(game)
        val file = game.resolve("assets/tilesets/1.png")
        val original = Files.readAllBytes(file)
        Files.write(file, original + byteArrayOf(0))

        val again = GameArtExporter.export(game)
        assertTrue(again.written.isEmpty())
        assertTrue("tilesets/1.png" in again.skipped)
        assertEquals(original.size + 1, Files.size(file).toInt())

        assertTrue("tilesets/1.png" in GameArtExporter.export(game, force = true).written)
        assertEquals(original.size, Files.size(file).toInt())
    }
}
