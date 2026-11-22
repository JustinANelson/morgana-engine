package com.morgana.protocol.realms

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealmsModelsTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Test
    fun `realm summary converts to and from invite codes losslessly`() {
        val original = RealmSummary(
            id = "havenwood",
            name = "Havenwood PvP",
            description = "Classic PvP server with open loot",
            host = "play.morganaorpg.com",
            port = 7001,
            tls = true,
            fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            gameTitle = "Morgana Classic",
            tags = listOf("pvp", "classic")
        )

        val inviteCode = original.toInviteCode()
        assertTrue(inviteCode.startsWith("morgana://play.morganaorpg.com:7001#fp="))

        val parsed = RealmSummary.fromInviteCode(inviteCode, id = original.id, name = original.name)
        assertNotNull(parsed)
        assertEquals(original.id, parsed!!.id)
        assertEquals(original.host, parsed.host)
        assertEquals(original.port, parsed.port)
        assertTrue(parsed.tls)
        assertEquals(original.fingerprint, parsed.fingerprint)
    }

    @Test
    fun `realm directory document serializes and deserializes accurately`() {
        val dir = RealmDirectoryDocument(
            realms = listOf(
                RealmSummary(
                    id = "classic",
                    name = "Morgana Classic Official",
                    description = "The classic reference realm",
                    host = "classic.morganaorpg.com",
                    port = 7001,
                    onlinePlayers = 42,
                    maxPlayers = 200,
                    tags = listOf("official", "rpg", "pve")
                )
            ),
            featuredRealmId = "classic",
            announcement = "Welcome to the Morgana Realms Universal Client!"
        )

        val encoded = json.encodeToString(RealmDirectoryDocument.serializer(), dir)
        val decoded = json.decodeFromString(RealmDirectoryDocument.serializer(), encoded)

        assertEquals(dir, decoded)
        assertEquals(1, decoded.realms.size)
        assertEquals("classic", decoded.featuredRealmId)
    }

    @Test
    fun `asset manifest tracks content addressed entries`() {
        val manifest = AssetManifest(
            gameId = "morgana-classic",
            version = "1.2.0",
            assets = mapOf(
                "sprites/warden.png" to AssetManifestEntry(
                    path = "sprites/warden.png",
                    sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    sizeBytes = 4096L,
                    kind = AssetKind.SPRITE
                )
            )
        )

        val encoded = json.encodeToString(AssetManifest.serializer(), manifest)
        val decoded = json.decodeFromString(AssetManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
        assertEquals(AssetKind.SPRITE, decoded.assets["sprites/warden.png"]?.kind)
    }
}

