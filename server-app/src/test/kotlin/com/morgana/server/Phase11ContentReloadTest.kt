package com.morgana.server

import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Phase11ContentReloadTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        override val isLoopback: Boolean = true
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox.add(packet) }
        override fun close(reason: String) {}
    }

    private fun ownerSession(coordinator: MorganaWorldCoordinator): FakeSession {
        val s = FakeSession("s1")
        coordinator.onSessionConnected(s)
        coordinator.handleClientPacket(s, ClientPacket.Authenticate("Creator", "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        s.outbox.clear()
        return s
    }

    private fun systemMessages(s: FakeSession) =
        s.outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }

    @Test
    fun `F1 save with a broken reference is rejected with a precise message and never persisted`(@TempDir dir: Path) {
        val store = MorganaDataStore(dir)
        val coordinator = MorganaWorldCoordinator(store)
        val owner = ownerSession(coordinator)

        val badShop = ShopDefinition(id = 90, name = "Ghost Shop", entries = listOf(ShopEntry(itemId = 9999)))
        coordinator.handleClientPacket(owner, ClientPacket.SaveShopDefinition(badShop))

        assertFalse(owner.outbox.any { it is ServerPacket.ContentCatalogUpdated })
        assertNull(store.world.shops.findById(90))
        assertNull(coordinator.simulation.shops[90])
        val msg = systemMessages(owner).single()
        assertTrue(msg.contains("Save rejected for shops #90"), msg)
        assertTrue(msg.contains("/entries/0/itemId"), msg)

        // A valid save of the same shop still goes through.
        val goodShop = badShop.copy(entries = listOf(ShopEntry(itemId = 1)))
        coordinator.handleClientPacket(owner, ClientPacket.SaveShopDefinition(goodShop))
        assertTrue(owner.outbox.any { it is ServerPacket.ContentCatalogUpdated })
        assertEquals("Ghost Shop", store.world.shops.findById(90)?.name)
    }

    @Test
    fun `file edits hot-reload into the live world and broadcast, while invalid edits leave it untouched`(@TempDir dir: Path) {
        val store = MorganaDataStore(dir)
        val coordinator = MorganaWorldCoordinator(store)
        val owner = ownerSession(coordinator)
        val world = dir.resolve("world")

        // 1. No-op reload changes nothing.
        val noop = coordinator.reloadContent()
        assertTrue(noop.applied)
        assertTrue(noop.changed.isEmpty(), noop.changed.toString())

        // 2. Hand-edit an item file, as a script or AI agent would.
        val itemFile = world.resolve("items/item_001.json")
        val originalName = coordinator.simulation.items.getValue(1).name
        Files.writeString(itemFile, Files.readString(itemFile).replaceFirst("\"name\": \"$originalName\"", "\"name\": \"Edited On Disk\""))
        val edited = coordinator.reloadContent()
        assertTrue(edited.applied, edited.summary)
        assertEquals(listOf("item#1"), edited.changed)
        assertEquals("Edited On Disk", coordinator.simulation.items.getValue(1).name)
        assertTrue(owner.outbox.any { it is ServerPacket.ContentCatalogUpdated })

        // 3. Hand-edit a map file: live map is hot-swapped with a bumped revision.
        val mapFile = world.resolve("maps/map_001.json")
        val oldRevision = coordinator.simulation.maps.getValue(1).revision
        Files.writeString(mapFile, Files.readString(mapFile).replaceFirst(Regex("\"bgmTrackId\": \"[^\"]*\""), "\"bgmTrackId\": \"edited_theme\""))
        owner.outbox.clear()
        val mapReload = coordinator.reloadContent()
        assertTrue(mapReload.applied, mapReload.summary)
        assertEquals(listOf("map#1"), mapReload.changed)
        assertEquals("edited_theme", coordinator.simulation.maps.getValue(1).bgmTrackId)
        assertTrue(coordinator.simulation.maps.getValue(1).revision > oldRevision)
        assertTrue(owner.outbox.any { it is ServerPacket.MapDefinitionUpdated })

        // 4. An invalid edit (dangling reference) is rejected wholesale; the live world is unchanged.
        val classFile = world.resolve("classes/class_001.json")
        Files.writeString(classFile, Files.readString(classFile).replaceFirst("\"itemId\": 1", "\"itemId\": 4242"))
        val rejected = coordinator.reloadContent()
        assertFalse(rejected.applied)
        assertTrue(rejected.report.errors.any { it.code == "missing_reference" })
        assertNotEquals(4242, coordinator.simulation.classes.getValue(1).startingItems.first().itemId)

        // 5. Malformed JSON is reported as a parse error, not an exception.
        Files.writeString(world.resolve("items/item_777.json"), "{ nope")
        val malformed = coordinator.reloadContent()
        assertFalse(malformed.applied)
        assertTrue(malformed.report.errors.any { it.code == "parse_error" })
    }

    @Test
    fun `slash reload lets an owner trigger the reload and reports the result`(@TempDir dir: Path) {
        val coordinator = MorganaWorldCoordinator(MorganaDataStore(dir))
        val owner = ownerSession(coordinator)
        coordinator.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/reload"))
        assertTrue(systemMessages(owner).any { it.startsWith("Content reloaded") }, systemMessages(owner).toString())
    }

    @Test
    fun `content watcher debounces file changes into a single callback`(@TempDir dir: Path) {
        val world = dir.resolve("world")
        Files.createDirectories(world.resolve("items"))
        val latch = CountDownLatch(1)
        var calls = 0
        ContentWatcher(world, debounceMs = 150) { calls++; latch.countDown() }.start().use {
            Files.writeString(world.resolve("items/item_001.json"), "{}")
            Files.writeString(world.resolve("items/item_001.json"), "{ }")
            assertTrue(latch.await(10, TimeUnit.SECONDS), "watcher should fire")
            Thread.sleep(500)
            assertEquals(1, calls)
        }
    }
}
