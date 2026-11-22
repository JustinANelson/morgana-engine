package com.morgana.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Phase13WorldPackTest {

    private fun starterWorld(dir: Path): Path {
        val store = MorganaDataStore(dir)
        GameScaffolder.seedIfEmpty(store.world)
        return dir.resolve("world")
    }

    private fun zipOf(out: Path, entries: Map<String, String>): Path {
        ZipOutputStream(Files.newOutputStream(out)).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return out
    }

    private val manifest = """{"name":"Test"}"""

    @Test
    fun `export then import into an empty world reproduces identical, valid content`(@TempDir tmp: Path) {
        val src = starterWorld(tmp.resolve("src"))
        val pack = tmp.resolve("out/starter.zip")
        val m = WorldPack.export(src, pack, "Starter", "The starter world", "Tests")
        assertEquals("Starter", m.name)
        assertEquals(1, m.counts["maps"])
        assertEquals(m, WorldPack.inspect(pack))

        val target = tmp.resolve("dst/world")
        val result = WorldPack.import(pack, target)
        assertNull(result.backup)
        assertTrue(result.report.isValid)

        val a = ContentLoader.load(src).catalog
        val b = ContentLoader.load(target).catalog
        assertEquals(a, b)
    }

    @Test
    fun `zip export is byte-reproducible and folder packs also import`(@TempDir tmp: Path) {
        val src = starterWorld(tmp.resolve("src"))
        WorldPack.export(src, tmp.resolve("a.zip"), "S")
        WorldPack.export(src, tmp.resolve("b.zip"), "S")
        assertTrue(Files.readAllBytes(tmp.resolve("a.zip")).contentEquals(Files.readAllBytes(tmp.resolve("b.zip"))))

        WorldPack.export(src, tmp.resolve("folder"), "S")
        assertTrue(Files.exists(tmp.resolve("folder/pack.json")))
        WorldPack.import(tmp.resolve("folder"), tmp.resolve("t/world"))
        assertEquals(ContentLoader.load(src).catalog, ContentLoader.load(tmp.resolve("t/world")).catalog)
    }

    @Test
    fun `import refuses a non-empty target unless merging or replacing, and keeps a backup on replace`(@TempDir tmp: Path) {
        val pack = tmp.resolve("p.zip").also { WorldPack.export(starterWorld(tmp.resolve("src")), it, "S") }
        val target = starterWorld(tmp.resolve("dst"))

        val e = assertThrows(WorldPackException::class.java) { WorldPack.import(pack, target) }
        assertTrue(e.message!!.contains("already has content"))

        // Add a custom file so we can see merge keep it and replace move it away.
        val extra = target.resolve("items/item_900.json")
        Files.writeString(extra, Files.readString(target.resolve("items/item_001.json")).replaceFirst(Regex("\"id\": 1,"), "\"id\": 900,"))
        assertTrue(Files.readString(extra).contains("\"id\": 900"), "test setup: id rewritten")
        val merged = WorldPack.import(pack, target, PackImportMode.MERGE)
        assertNotNull(merged.backup)
        assertTrue(Files.exists(extra), "merge keeps documents the pack does not contain")

        val replaced = WorldPack.import(pack, target, PackImportMode.REPLACE)
        assertFalse(Files.exists(extra), "replace drops them")
        assertTrue(Files.exists(replaced.backup!!.resolve("items/item_900.json")), "old content is preserved in the backup")
    }

    @Test
    fun `invalid content is rejected before the target is touched`(@TempDir tmp: Path) {
        val src = starterWorld(tmp.resolve("src"))
        val broken = zipOf(
            tmp.resolve("broken.zip"),
            mapOf("pack.json" to manifest, "world/items/item_001.json" to "{ this is not json")
        )
        val target = starterWorld(tmp.resolve("dst"))
        val before = ContentLoader.load(target).catalog
        val e = assertThrows(WorldPackException::class.java) { WorldPack.import(broken, target, PackImportMode.MERGE) }
        assertNotNull(e.report)
        assertTrue(e.report!!.errors.isNotEmpty())
        assertEquals(before, ContentLoader.load(target).catalog)
        assertTrue(Files.list(target.parent).use { s -> s.noneMatch { it.fileName.toString().contains(".import-") } }, "staging cleaned up")

        // Exporting an invalid world is refused too.
        Files.writeString(src.resolve("items/item_001.json"), "{ nope")
        assertThrows(WorldPackException::class.java) { WorldPack.export(src, tmp.resolve("x.zip"), "X") }
    }

    @Test
    fun `hostile packs cannot escape the world folder or exceed limits`(@TempDir tmp: Path) {
        val target = tmp.resolve("dst/world")
        for (bad in listOf("../../evil.json", "world/../../evil.json", "world/items/../../x.json", "world/unknown/a.json", "accounts/a.json", "world/items/sub/a.json", "world/items/a.txt")) {
            val zip = zipOf(tmp.resolve("bad.zip"), mapOf("pack.json" to manifest, bad to "{}"))
            assertThrows(WorldPackException::class.java, { WorldPack.import(zip, target) }, bad)
            Files.delete(zip)
        }
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(tmp.resolve("evil.json")))

        val huge = "x".repeat((WorldPack.MAX_ENTRY_BYTES + 1).toInt())
        val big = zipOf(tmp.resolve("big.zip"), mapOf("pack.json" to manifest, "world/items/item_001.json" to huge))
        assertTrue(assertThrows(WorldPackException::class.java) { WorldPack.import(big, target) }.message!!.contains("exceeds"))
    }

    @Test
    fun `malformed packs give clear errors`(@TempDir tmp: Path) {
        val noManifest = zipOf(tmp.resolve("a.zip"), mapOf("world/items/item_001.json" to "{}"))
        assertTrue(assertThrows(WorldPackException::class.java) { WorldPack.import(noManifest, tmp.resolve("t1")) }.message!!.contains("missing pack.json"))

        val future = zipOf(tmp.resolve("b.zip"), mapOf("pack.json" to """{"formatVersion":99,"name":"x"}""", "world/items/item_001.json" to "{}"))
        assertTrue(assertThrows(WorldPackException::class.java) { WorldPack.import(future, tmp.resolve("t2")) }.message!!.contains("Unsupported pack format"))

        Files.writeString(tmp.resolve("c.zip"), "not a zip")
        assertThrows(WorldPackException::class.java) { WorldPack.import(tmp.resolve("c.zip"), tmp.resolve("t3")) }
        assertThrows(WorldPackException::class.java) { WorldPack.import(tmp.resolve("missing.zip"), tmp.resolve("t4")) }
    }
}
