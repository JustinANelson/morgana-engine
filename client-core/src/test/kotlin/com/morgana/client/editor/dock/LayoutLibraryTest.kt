package com.morgana.client.editor.dock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayoutLibraryTest {

    private fun library(store: MemoryKeyValueStore = MemoryKeyValueStore()) = LayoutLibrary(store, reserved = listOf("Minimal", "Everything"))

    @Test
    fun `a saved layout can be listed loaded and deleted`() {
        val lib = library()
        assertNull(lib.save("Map work", "layout-text-1"))
        assertNull(lib.save("Quests", "layout-text-2"))
        assertEquals(listOf("Map work", "Quests"), lib.names())
        assertEquals("layout-text-1", lib.load("Map work"))
        lib.delete("Map work")
        assertEquals(listOf("Quests"), lib.names())
        assertNull(lib.load("Map work"))
    }

    @Test
    fun `saving under an existing name replaces it without adding a second entry`() {
        val lib = library()
        lib.save("Mine", "one")
        lib.save("  mine  ", "two")
        assertEquals(listOf("Mine"), lib.names())
        assertEquals("two", lib.load("Mine"))
    }

    @Test
    fun `names are tidied and checked`() {
        val lib = library()
        assertNotNull(lib.save("", "x"))
        assertNotNull(lib.save("   ", "x"))
        assertNotNull(lib.save("a".repeat(LayoutLibrary.MAX_NAME + 1), "x"))
        assertNotNull(lib.save("bad|name", "x"))
        assertNotNull(lib.save("bell\u0007name", "x"))
        assertNotNull(lib.save("minimal", "x"), "a built-in name is refused whatever its case")
        assertNull(lib.save("  Two   words  ", "x"))
        assertEquals(listOf("Two words"), lib.names())
        assertEquals("x", lib.load("two   words"))
    }

    @Test
    fun `the number of layouts is limited`() {
        val lib = library()
        for (i in 1..LayoutLibrary.MAX_LAYOUTS) assertNull(lib.save("Layout $i", "x"))
        assertNotNull(lib.save("One too many", "x"))
        assertNull(lib.save("Layout 3", "replaced"), "replacing an existing one is still allowed")
        lib.delete("Layout 1")
        assertNull(lib.save("One too many", "x"))
    }

    @Test
    fun `a damaged index lists only layouts that exist`() {
        val store = MemoryKeyValueStore()
        val lib = library(store)
        lib.save("Real", "x")
        store.put("layout-names", "Real\nGhost\n\nReal\n   ")
        assertEquals(listOf("Real"), lib.names())
        lib.delete("Ghost")
        assertEquals(listOf("Real"), lib.names())
    }

    @Test
    fun `a saved layout loads back into a dock layout exactly`() {
        val specs = listOf(
            PanelSpec("toolbar", DockZone.TOP, 800f, 40f),
            PanelSpec("tileset", DockZone.LEFT, 240f, 300f),
            PanelSpec("items", DockZone.RIGHT, 500f, 400f, visibleByDefault = false, startFloating = true)
        )
        val a = DockLayout(specs)
        a.show("items")
        a.moveFloating("items", 400f, 150f)
        a.minimize("tileset")
        val lib = library()
        lib.save("Content", a.serialize())

        val b = DockLayout(specs)
        b.restore(lib.load("Content")!!)
        assertEquals(a.ids.map { a.placement(it) }, b.ids.map { b.placement(it) })
    }

    @Test
    fun `panel search needs every word and puts titles that start with it first`() {
        val panels = listOf(
            PanelInfo("tileset", "Tileset", "Map", PanelState.SHOWN),
            PanelInfo("quests", "Quests", "Content", PanelState.HIDDEN),
            PanelInfo("recipes", "Recipes", "Content", PanelState.MINIMIZED),
            PanelInfo("mapprops", "Map properties", "Map", PanelState.HIDDEN)
        )
        assertEquals(panels, PanelSearch.filter(panels, "  "))
        assertEquals(listOf("mapprops", "tileset"), PanelSearch.filter(panels, "map").map { it.id }, "the title starting with it comes first")
        assertEquals(listOf("mapprops"), PanelSearch.filter(panels, "map prop").map { it.id })
        assertEquals(listOf("quests", "recipes"), PanelSearch.filter(panels, "CONTENT").map { it.id })
        assertTrue(PanelSearch.filter(panels, "zzz").isEmpty())
        val described = panels + PanelInfo("recipes2", "Crafting", "Content", PanelState.HIDDEN, "Ingredients and what they make")
        assertEquals(listOf("recipes2"), PanelSearch.filter(described, "ingredients").map { it.id }, "the description is searched too")
    }
}
