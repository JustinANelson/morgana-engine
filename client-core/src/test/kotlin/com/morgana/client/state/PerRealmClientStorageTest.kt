package com.morgana.client.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PerRealmClientStorageTest {

    @Test
    fun `InMemoryPerRealmStore manages history and favorites`() {
        val store = InMemoryPerRealmStore()

        store.recordRealmVisit(
            realmId = "realm-classic",
            name = "Morgana Classic",
            host = "127.0.0.1",
            port = 7001,
            tls = false,
            tags = listOf("Classic", "Fantasy")
        )
        store.recordRealmVisit(
            realmId = "realm-havenwood",
            name = "Havenwood",
            host = "havenwood.org",
            port = 7001,
            tls = true,
            tags = listOf("PvP")
        )

        val doc = store.load()
        assertEquals(2, doc.recentRealms.size)
        assertEquals("realm-havenwood", doc.recentRealms[0].realmId) // Most recent first
        assertEquals("realm-classic", doc.recentRealms[1].realmId)

        assertFalse(store.isFavorite("realm-classic"))
        assertTrue(store.toggleFavorite("realm-classic"))
        assertTrue(store.isFavorite("realm-classic"))
        assertFalse(store.toggleFavorite("realm-classic"))
        assertFalse(store.isFavorite("realm-classic"))

        store.setLastUsername("Archmage")
        assertEquals("Archmage", store.load().lastUsername)
    }

    @Test
    fun `PerRealmStore isolates hotbar preferences across realms and characters`() {
        val store = InMemoryPerRealmStore()

        val classicHeroPrefs = PerRealmCharacterPreferences()
            .withHotbar(0, RealmHotbarEntry(slot = 0, actionType = "SPELL", actionId = "101"))
            .withHotbar(1, RealmHotbarEntry(slot = 1, actionType = "ITEM", actionId = "50"))

        val havenwoodHeroPrefs = PerRealmCharacterPreferences()
            .withHotbar(0, RealmHotbarEntry(slot = 0, actionType = "SPELL", actionId = "999"))

        store.updatePreferences("classic", "Arthur", classicHeroPrefs)
        store.updatePreferences("havenwood", "Arthur", havenwoodHeroPrefs)
        store.updatePreferences("classic", "Lancelot", PerRealmCharacterPreferences())

        val loadedClassic = store.getPreferences("classic", "Arthur")
        assertEquals("101", loadedClassic.getHotbar(0)?.actionId)
        assertEquals("50", loadedClassic.getHotbar(1)?.actionId)

        val loadedHavenwood = store.getPreferences("havenwood", "Arthur")
        assertEquals("999", loadedHavenwood.getHotbar(0)?.actionId)

        val loadedLancelot = store.getPreferences("classic", "Lancelot")
        assertTrue(loadedLancelot.hotbarBindings.isEmpty())
    }

    @Test
    fun `FilePerRealmStore persists and reloads correctly from disk`(@TempDir tempDir: File) {
        val file = File(tempDir, "realms_storage.json")
        val store1 = FilePerRealmStore(file)

        store1.setLastUsername("Valiant")
        store1.recordRealmVisit("realm-1", "Test Realm", "localhost", 7001, false)
        store1.toggleFavorite("realm-1")
        store1.updatePreferences(
            "realm-1",
            "Valiant",
            PerRealmCharacterPreferences().withHotbar(3, RealmHotbarEntry(3, "SPELL", "42"))
        )

        assertTrue(file.exists())
        assertTrue(file.length() > 0)

        // Read with a new store instance pointing to same file
        val store2 = FilePerRealmStore(file)
        val loadedDoc = store2.load()
        assertEquals("Valiant", loadedDoc.lastUsername)
        assertEquals(1, loadedDoc.recentRealms.size)
        assertTrue(store2.isFavorite("realm-1"))

        val prefs = store2.getPreferences("realm-1", "Valiant")
        val slot3 = prefs.getHotbar(3)
        assertNotNull(slot3)
        assertEquals("SPELL", slot3?.actionType)
        assertEquals("42", slot3?.actionId)
    }

    @Test
    fun `ClientWorldState imports and exports hotbar correctly`() {
        val worldState = ClientWorldState()
        worldState.hotbarSlots[0] = HotbarSlotAction.CastSpell(spellId = 1, spellName = "Fireball")
        worldState.hotbarSlots[4] = HotbarSlotAction.UseItem(itemId = 10, itemName = "Health Potion")

        val exported = worldState.exportHotbarToPreferences()
        assertEquals(2, exported.hotbarBindings.size)
        assertEquals("1", exported.getHotbar(0)?.actionId)
        assertEquals("SPELL", exported.getHotbar(0)?.actionType)
        assertEquals("10", exported.getHotbar(4)?.actionId)
        assertEquals("ITEM", exported.getHotbar(4)?.actionType)

        val newState = ClientWorldState()
        newState.loadHotbarFromPreferences(exported)
        val slot0 = newState.hotbarSlots[0]
        val slot4 = newState.hotbarSlots[4]

        assertTrue(slot0 is HotbarSlotAction.CastSpell)
        assertEquals(1, (slot0 as HotbarSlotAction.CastSpell).spellId)
        assertTrue(slot4 is HotbarSlotAction.UseItem)
        assertEquals(10, (slot4 as HotbarSlotAction.UseItem).itemId)
    }

    @Test
    fun `remembered credentials save and clear password cleanly`() {
        val store = InMemoryPerRealmStore()
        store.setRememberedCredentials("  Aria  ", "secret123", rememberPassword = true)
        val saved = store.load()
        assertEquals("Aria", saved.lastUsername)
        assertTrue(saved.rememberPassword)
        assertEquals("secret123", saved.savedPassword)

        store.setRememberedCredentials("Aria", "secret123", rememberPassword = false)
        val cleared = store.load()
        assertEquals("Aria", cleared.lastUsername)
        assertEquals(false, cleared.rememberPassword)
        assertEquals(null, cleared.savedPassword)
    }
}

