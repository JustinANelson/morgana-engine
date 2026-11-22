package com.morgana.server.assets

import com.morgana.protocol.realms.AssetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GameAssetsMusicTest {
    private val ogg = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0, 2, 0, 0)

    private fun write(dir: Path, name: String, bytes: ByteArray) {
        val file = dir.resolve("assets/music/$name")
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    @Test
    fun `an ogg track named by its track id enters the manifest as music`(@TempDir dir: Path) {
        write(dir, "grass.ogg", ogg)
        val scan = GameAssets.scan(dir, "g")
        assertEquals(emptyList<String>(), scan.errors)
        val entry = scan.manifest.assets.getValue("music/grass.ogg")
        assertEquals(AssetKind.MUSIC, entry.kind)
        assertEquals(ogg.size.toLong(), entry.sizeBytes)
        assertTrue(scan.fileFor("music/grass.ogg") != null)
    }

    @Test
    fun `bad names and non-ogg bytes are reported and left out`(@TempDir dir: Path) {
        write(dir, "Grass Theme.ogg", ogg)
        write(dir, "song.mp3", ogg)
        write(dir, "fake.ogg", byteArrayOf(1, 2, 3, 4, 5))
        val scan = GameAssets.scan(dir, "g")
        assertEquals(3, scan.errors.size, scan.errors.toString())
        assertTrue(scan.manifest.assets.isEmpty())
    }

    @Test
    fun `live png import refuses the music kind`(@TempDir dir: Path) {
        val outcome = GameAssets.importPng(dir, "g", AssetKind.MUSIC, "1", ogg)
        assertTrue(outcome is GameAssets.ImportOutcome.Rejected)
    }
}
