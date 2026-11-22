package com.morgana.server

import com.morgana.persistence.ContentLoader
import com.morgana.persistence.WorldPack
import com.morgana.server.tools.listWorlds
import com.morgana.server.tools.newWorld
import com.morgana.server.tools.packExport
import com.morgana.server.tools.packImport
import com.morgana.server.worlds.WorldLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase13WorldLibraryTest {

    private fun lib(tmp: Path) = WorldLibrary(tmp.resolve("worlds"), tmp.resolve("server-data"), clock = { 1_000L })

    @Test
    fun `slugs are safe and never collide with the reserved default`() {
        assertEquals("my-camp", WorldLibrary.slugify("My Camp!"))
        assertEquals("world", WorldLibrary.slugify("///"))
        assertEquals("world-default", WorldLibrary.slugify("Default"))
        assertEquals("a-b", WorldLibrary.slugify("../A  B.."))
    }

    @Test
    fun `new worlds are seeded, listed after the default, and independent`(@TempDir tmp: Path) {
        val library = lib(tmp)
        assertEquals(listOf("default"), library.list().map { it.slug })

        val a = library.create("Camp Alpha")
        val b = library.create("Camp Alpha")
        assertEquals("camp-alpha", a.slug)
        assertEquals("camp-alpha-2", b.slug)
        assertEquals(listOf("default", "camp-alpha", "camp-alpha-2"), library.list().map { it.slug })
        assertEquals("blank", a.origin)
        assertEquals(1, ContentLoader.load(a.dataDir.resolve("data")).catalog.maps.size)

        Files.delete(a.dataDir.resolve("data/items/item_001.json"))
        assertTrue(Files.exists(b.dataDir.resolve("data/items/item_001.json")), "worlds are independent")
    }

    @Test
    fun `a world can be created from a pack, and a bad pack leaves nothing behind`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val source = library.create("Source")
        val pack = tmp.resolve("src.zip")
        WorldPack.export(source.dataDir.resolve("data"), pack, "Shared Realm")

        val fromPack = library.create("Copy", pack)
        assertEquals("pack:Shared Realm", fromPack.origin)
        assertEquals(
            ContentLoader.load(source.dataDir.resolve("data")).catalog,
            ContentLoader.load(fromPack.dataDir.resolve("world")).catalog
        )

        Files.writeString(tmp.resolve("junk.zip"), "junk")
        assertThrows(Exception::class.java) { library.create("Broken", tmp.resolve("junk.zip")) }
        assertFalse(Files.exists(tmp.resolve("worlds/broken")))
        assertEquals(setOf("default", "source", "copy"), library.list().map { it.slug }.toSet())

        assertThrows(IllegalArgumentException::class.java) { library.create("   ") }
    }

    @Test
    fun `command line tools export, import and create worlds with exit codes`(@TempDir tmp: Path) {
        val root = tmp.resolve("worlds")
        assertEquals(0, newWorld(root, "Tool World", null))
        val world = root.resolve("tool-world/data")
        val pack = tmp.resolve("tool.zip")
        assertEquals(0, packExport(world, pack.toString(), "Tool Pack", "", ""))
        assertEquals(2, packExport(world, "", "", "", ""), "missing args")

        val target = tmp.resolve("other/world")
        assertEquals(0, packImport(target, pack.toString(), "new"))
        assertEquals(1, packImport(target, pack.toString(), "new"), "non-empty target refused")
        assertEquals(0, packImport(target, pack.toString(), "replace"))
        assertEquals(2, packImport(target, pack.toString(), "bogus"))
        assertEquals(1, packImport(target, tmp.resolve("missing.zip").toString(), "merge"))
        assertEquals(0, listWorlds(root))
        assertEquals(2, newWorld(root, "", null))
    }
}
