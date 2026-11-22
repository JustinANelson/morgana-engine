package com.morgana.server

import com.morgana.server.worlds.WorldLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Summary, rename, delete and "last played" for the Local Worlds screen. */
class WorldLibraryManagementTest {
    private var now = 1_000L
    private fun lib(tmp: Path) = WorldLibrary(tmp.resolve("worlds"), tmp.resolve("server-data"), clock = { now })

    @Test
    fun `a summary describes a world`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val info = library.create("Camp Alpha")
        val s = library.summary(info.slug)!!
        assertEquals("Camp Alpha", s.name)
        assertEquals("blank", s.origin)
        assertEquals(1_000L, s.createdAtMillis)
        assertNull(s.lastPlayedAtMillis)
        assertTrue(s.mapCount >= 1, "maps: ${s.mapCount}")
        assertTrue(s.classCount >= 1, "classes: ${s.classCount}")
        assertEquals(emptyList<String>(), s.disabledModules)
        assertFalse(s.isDefault)
        assertNull(library.summary("nope"))
        assertTrue(library.summary("default")!!.isDefault)
    }

    @Test
    fun `playing a world records when`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val slug = library.create("Camp").slug
        now = 5_000L
        library.markPlayed(slug)
        assertEquals(5_000L, library.summary(slug)!!.lastPlayedAtMillis)
        assertEquals(1_000L, library.summary(slug)!!.createdAtMillis)
        library.markPlayed("default") // the legacy world has no record; this must not create one or fail
        assertFalse(Files.exists(tmp.resolve("server-data/${WorldLibrary.META_FILE}")))
        library.markPlayed("not-a-world")
    }

    @Test
    fun `renaming changes the name and keeps the folder`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val slug = library.create("Camp").slug
        val renamed = library.rename(slug, "  Fort Beta ")
        assertEquals("Fort Beta", renamed.name)
        assertEquals(slug, renamed.slug)
        assertEquals("Fort Beta", library.find(slug)!!.name)
        assertTrue(Files.isDirectory(tmp.resolve("worlds/$slug/data")))
        assertThrows(IllegalArgumentException::class.java) { library.rename(slug, "   ") }
        assertThrows(IllegalArgumentException::class.java) { library.rename(slug, "x".repeat(200)) }
        assertThrows(IllegalArgumentException::class.java) { library.rename("default", "Mine") }
        assertThrows(IllegalArgumentException::class.java) { library.rename("ghost", "Mine") }
        assertEquals("Fort Beta", library.find(slug)!!.name)
    }

    @Test
    fun `deleting removes only that world`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val keep = library.create("Keep").slug
        val drop = library.create("Drop").slug
        library.delete(drop)
        assertEquals(listOf("default", keep), library.list().map { it.slug })
        assertFalse(Files.exists(tmp.resolve("worlds/$drop")))
        assertTrue(Files.isDirectory(tmp.resolve("worlds/$keep/data")))
    }

    @Test
    fun `the default world and anything outside the library cannot be deleted`(@TempDir tmp: Path) {
        val library = lib(tmp)
        Files.createDirectories(tmp.resolve("server-data/accounts"))
        assertThrows(IllegalArgumentException::class.java) { library.delete("default") }
        assertTrue(Files.isDirectory(tmp.resolve("server-data/accounts")))

        // A folder in worlds/ that this library did not make (no meta file) is not its to delete.
        Files.createDirectories(tmp.resolve("worlds/stranger"))
        assertThrows(IllegalStateException::class.java) { library.delete("stranger") }
        assertTrue(Files.isDirectory(tmp.resolve("worlds/stranger")))

        // Paths are refused, never followed.
        Files.createDirectories(tmp.resolve("precious"))
        for (bad in listOf("..", ".", "../precious", "..\\precious", "", "a/b")) {
            assertThrows(IllegalArgumentException::class.java, { library.delete(bad) }, "'$bad'")
        }
        assertTrue(Files.isDirectory(tmp.resolve("precious")))
    }
}
