package com.morgana.client.realms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealmDirectoryClientTest {

    @Test
    fun `defaultBundledDirectory lists one local server on the given address`() {
        val doc = defaultBundledDirectory("10.0.0.5", 7100)
        assertEquals(LOCAL_REALM_ID, doc.featuredRealmId)
        assertNotNull(doc.announcement)
        val local = doc.realms.single()
        assertEquals(LOCAL_REALM_ID, local.id)
        assertEquals("10.0.0.5", local.host)
        assertEquals(7100, local.port)
        assertEquals(DEFAULT_CLASSES, local.classes)
    }

    @Test
    fun `InMemoryRealmDirectoryClient returns bundled document`() {
        val result = InMemoryRealmDirectoryClient().fetchDirectory()
        assertTrue(result.isSuccess)
        assertEquals(defaultBundledDirectory(), result.getOrThrow())
    }

    @Test
    fun `HttpJsonRealmDirectoryClient falls back gracefully on unreachable endpoint`() {
        val client = HttpJsonRealmDirectoryClient(
            directoryUrl = "http://127.0.0.1:59999/unreachable_directory.json",
            connectTimeoutMs = 500,
            readTimeoutMs = 500
        )
        val result = client.fetchDirectory()
        assertTrue(result.isSuccess) // Success via bundled fallback
        assertEquals(LOCAL_REALM_ID, result.getOrThrow().featuredRealmId)
    }

    @Test
    fun `wizard peaceful preset configures noCombat`() {
        val model = RealmWizardModel(listOf(com.morgana.client.RealmTemplateInfo("cozy", "Cozy Island", "A sunny island")))
        model.name = "Sunny Farm"
        val peaceful = RealmWizardModel.PRESETS.first { it.id == "peaceful" }
        model.applyPreset(peaceful)
        assertTrue(model.noCombat)
        assertEquals(peaceful, model.activePreset())
        assertTrue(model.toSpec().noCombat)
        assertTrue(model.summaryLines().any { it.contains("peaceful", ignoreCase = true) })
    }
}
