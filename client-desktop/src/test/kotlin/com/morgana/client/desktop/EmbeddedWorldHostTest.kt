package com.morgana.client.desktop

import com.morgana.client.RealmSpec
import com.morgana.client.net.ConnectResult
import com.morgana.client.net.MorganaNetClient
import com.morgana.server.worlds.WorldLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EmbeddedWorldHostTest {

    private fun host(tmp: Path) =
        EmbeddedWorldHost(WorldLibrary(tmp.resolve("worlds"), tmp.resolve("server-data")), port = 0)

    @Test
    fun `new world is created, selected, and hosted by an embedded server`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            assertEquals(listOf("default"), h.worlds().map { it.slug })
            assertNull(h.activeSlug)

            val created = h.createWorld("Fresh Start")
            assertEquals("fresh-start", created.slug)
            assertEquals("fresh-start", h.selectedSlug)
            assertEquals(listOf("default", "fresh-start"), h.worlds().map { it.slug })

            h.ensureRunning()
            assertEquals("fresh-start", h.activeSlug)
            assertTrue(Files.isDirectory(tmp.resolve("worlds/fresh-start/data/maps")))
        }
    }

    @Test
    fun `the wizard creates a realm from a template with features switched off, and hosts it`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            val ids = h.realmTemplates.map { it.id }
            assertTrue("blank" in ids && "starter" !in ids, "templates offered: $ids")
            assertTrue("pack" !in ids, "pack import needs a file chooser, so it is CLI only")

            val created = h.createRealm(RealmSpec("Wizard Camp", "blank", setOf("guilds", "trading")))
            assertEquals("wizard-camp", created.slug)
            assertEquals("wizard-camp", h.selectedSlug)
            val dir = tmp.resolve("worlds/wizard-camp")
            assertTrue(Files.exists(dir.resolve("game.json")))
            assertTrue(Files.readString(dir.resolve("server.json")).contains("\"guilds\": false"))

            h.ensureRunning()
            assertEquals("wizard-camp", h.activeSlug)

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                h.createRealm(RealmSpec("Bad", "castle"))
            }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                h.createRealm(RealmSpec("Bad", "blank", setOf("teleporting")))
            }
        }
    }

    @Test
    fun `switching the selected world restarts the server on the new world, and reselecting is a no-op`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            h.createWorld("One")
            h.ensureRunning()
            assertEquals("one", h.activeSlug)

            h.createWorld("Two")
            h.ensureRunning()
            assertEquals("two", h.activeSlug)

            h.ensureRunning() // same selection: nothing to do
            assertEquals("two", h.activeSlug)

            h.selectedSlug = WorldLibrary.DEFAULT_SLUG
            h.ensureRunning()
            assertEquals("default", h.activeSlug)
            assertTrue(Files.isDirectory(tmp.resolve("server-data/world")), "default world seeded in the legacy directory")
        }
    }

    @Test
    fun `a hosted world hands out an invite code that a client can use to join securely`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            h.createWorld("Friends")
            val code = h.inviteCode("127.0.0.1")
            assertTrue(code != null && code.startsWith("morgana://127.0.0.1:") && "#fp=" in code, code)

            // The code is exactly what the login window consumes.
            val plan = com.morgana.client.net.ConnectionPlanner.plan(code!!, "7001", false, com.morgana.protocol.tls.InMemoryKnownHosts())
            assertTrue(plan.security.tls)
            MorganaNetClient().use { client ->
                val result = client.connectSecure(plan.host, plan.port, plan.security)
                assertTrue(result is ConnectResult.Connected, result.toString())
            }
            // Asking again gives the same identity (the certificate is stored with the world).
            assertEquals(code, h.inviteCode("127.0.0.1"))
        }
    }

    @Test
    fun `hosting status, stopping, renaming and deleting`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            assertNull(h.hostingStatus())
            assertTrue(h.canManageWorlds)
            val a = h.createWorld("Alpha")
            val b = h.createWorld("Beta")

            h.selectedSlug = a.slug
            h.ensureRunning()
            val status = h.hostingStatus()!!
            assertEquals("alpha", status.slug)
            assertEquals("Alpha", status.name)
            assertEquals(0, status.onlinePlayers)
            assertTrue(status.encrypted)
            assertTrue(h.details("alpha")!!.lastPlayedAtMillis != null, "starting a world records the play time")
            assertTrue(h.details("alpha")!!.mapCount >= 1)

            h.stopHosting()
            assertNull(h.hostingStatus())
            assertNull(h.activeSlug)

            assertEquals("Renamed", h.renameWorld(b.slug, "Renamed").name)
            assertEquals(listOf("Default World", "Alpha", "Renamed"), h.worlds().map { it.name })

            // Deleting the world being hosted stops it first, and selection falls back to the default world.
            h.ensureRunning()
            h.deleteWorld(a.slug)
            assertNull(h.hostingStatus())
            assertEquals("default", h.selectedSlug)
            assertTrue(!Files.exists(tmp.resolve("worlds/alpha")))
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { h.deleteWorld("default") }
        }
    }

    @Test
    fun `an unknown selection falls back to the default world`(@TempDir tmp: Path) {
        host(tmp).use { h ->
            h.selectedSlug = "does-not-exist"
            h.ensureRunning()
            assertEquals("default", h.activeSlug)
        }
    }
}
