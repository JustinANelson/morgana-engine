package com.morgana.server.assets

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.Base64Codec
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.realms.AssetKind
import com.morgana.server.ClientSession
import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.OwnerPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class GameAssetsImportTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
        fun messages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().map { it.message }
    }

    private fun png(w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    private fun setupCoordinator(dir: Path): Pair<MorganaWorldCoordinator, Path> {
        val gameDir = com.morgana.server.TestGames.blank(dir)
        return MorganaWorldCoordinator(
            MorganaDataStore(rootDir = dir, gameDir = gameDir),
            ownerPolicy = OwnerPolicy(dir) { "Owner" }
        ) to gameDir
    }

    @Test
    fun `importPng validates PNG bytes, writes atomically to assets, and refuses traversal or bad shapes`(@TempDir dir: Path) {
        val valid = png(160, 32)
        val ok = GameAssets.importPng(dir, "g", AssetKind.ANIMATION, "7", valid)
        assertTrue(ok is GameAssets.ImportOutcome.Accepted, ok.toString())
        val accepted = ok as GameAssets.ImportOutcome.Accepted
        assertEquals("animations/7.png", accepted.entry.path)
        assertTrue(Files.exists(dir.resolve("assets/animations/7.png")))
        assertEquals(valid.toList(), Files.readAllBytes(dir.resolve("assets/animations/7.png")).toList())

        val badTraversal = GameAssets.importPng(dir, "g", AssetKind.ICON, "../99", png(32, 32))
        assertTrue(badTraversal is GameAssets.ImportOutcome.Rejected)

        val badShape = GameAssets.importPng(dir, "g", AssetKind.ICON, "2", png(32, 16))
        assertTrue(badShape is GameAssets.ImportOutcome.Rejected)
        assertFalse(Files.exists(dir.resolve("assets/icons/2.png")))

        val forgedHeader = ByteArray(32).apply { png(32, 32).copyInto(this, 0, 0, 24) }
        val corrupt = GameAssets.importPng(dir, "g", AssetKind.ICON, "3", forgedHeader)
        assertTrue(corrupt is GameAssets.ImportOutcome.Rejected)
        assertFalse(Files.exists(dir.resolve("assets/icons/3.png")))
    }

    @Test
    fun `live ImportGameArt requires edit permission, persists to temp gameDir, and broadcasts updated bytes`(@TempDir dir: Path) {
        val (coordinator, gameDir) = setupCoordinator(dir)
        val owner = Session("owner")
        coordinator.onSessionConnected(owner)
        coordinator.handleClientPacket(
            owner,
            ClientPacket.Authenticate(
                "Owner",
                "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION,
                capabilities = listOf(ProtocolCapabilities.ART_V2)
            )
        )
        val player = Session("player")
        coordinator.onSessionConnected(player)
        coordinator.handleClientPacket(
            player,
            ClientPacket.Authenticate(
                "Adventurer",
                "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION,
                capabilities = listOf(ProtocolCapabilities.ART_V2)
            )
        )
        owner.sent.clear()
        player.sent.clear()

        val iconPng = png(32, 32)
        // Non-admin player cannot import art
        coordinator.handleClientPacket(player, ClientPacket.ImportGameArt(AssetKind.ICON, "42", Base64Codec.encode(iconPng)))
        assertFalse(Files.exists(gameDir.resolve("assets/icons/42.png")))
        assertTrue(player.messages().any { it.startsWith("Art import rejected:") }, player.messages().toString())

        player.sent.clear()
        // Owner import succeeds and broadcasts to online ART_V2 clients
        coordinator.handleClientPacket(owner, ClientPacket.ImportGameArt(AssetKind.ICON, "42", Base64Codec.encode(iconPng)))
        assertTrue(Files.exists(gameDir.resolve("assets/icons/42.png")))
        assertTrue("icons/42.png" in coordinator.gameAssets.manifest.assets)

        val ownerUpdate = owner.sent.filterIsInstance<ServerPacket.GameArtUpdated>().single()
        assertEquals("icons/42.png", ownerUpdate.entry.path)
        assertEquals(iconPng.toList(), Base64Codec.decodeOrNull(ownerUpdate.pngBase64)?.toList())
        assertTrue("icons/42.png" in ownerUpdate.manifest.assets)

        val playerUpdate = player.sent.filterIsInstance<ServerPacket.GameArtUpdated>().single()
        assertEquals(ownerUpdate.entry.sha256, playerUpdate.entry.sha256)
    }

    @Test
    fun `coordinator emits Paperdolling v2 layers in PublicPlayerSnapshot on live equip when opted in`(@TempDir dir: Path) {
        val gameDir = com.morgana.server.TestGames.blank(dir.resolve("pdv2"))
        val loaded = com.morgana.persistence.GameDefinitionLoader.load(gameDir).game.definition
        val optedIn = loaded.copy(sprites = com.morgana.domain.game.SpriteProfile(paperdollingV2 = true))
        Files.writeString(gameDir.resolve(com.morgana.persistence.GameDefinitionLoader.FILE_NAME), com.morgana.persistence.GameDefinitionLoader.encode(optedIn))

        val coordinator = MorganaWorldCoordinator(
            MorganaDataStore(rootDir = dir.resolve("data-pdv2"), gameDir = gameDir),
            ownerPolicy = OwnerPolicy(dir.resolve("data-pdv2")) { "Owner" }
        )
        val owner = Session("s1")
        val observer = Session("s2")
        coordinator.onSessionConnected(owner)
        coordinator.onSessionConnected(observer)
        coordinator.handleClientPacket(
            owner,
            ClientPacket.Authenticate("Owner", "Password123!", registerIfNew = true, protocolVersion = PROTOCOL_VERSION, capabilities = listOf(ProtocolCapabilities.ART_V2))
        )
        coordinator.handleClientPacket(
            observer,
            ClientPacket.Authenticate("Watcher", "Password123!", registerIfNew = true, protocolVersion = PROTOCOL_VERSION, capabilities = listOf(ProtocolCapabilities.ART_V2))
        )
        val ownerId = owner.characterId!!
        // Pick an equippable item from catalog or save a dyed helmet with hide rule and give+equip it
        val helm = com.morgana.domain.item.ItemDefinition(
            id = 9001,
            name = "Crimson Helm",
            type = com.morgana.domain.item.ItemType.HELMET,
            iconId = 1,
            paperdollSpriteId = 401,
            paperdollTintHex = "#ef4444",
            paperdollHidesSlots = setOf(com.morgana.domain.item.PaperdollSlot.HAIR)
        )
        coordinator.handleClientPacket(owner, ClientPacket.SaveItemDefinition(helm))
        coordinator.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/item 9001 1"))
        observer.sent.clear()

        val slotIndex = coordinator.simulation.players.getValue(ownerId).inventory.slots.indexOfFirst { it?.itemId == 9001 }
        assertTrue(slotIndex >= 0, "expected granted helm in inventory")
        coordinator.handleClientPacket(owner, ClientPacket.UseInventorySlot(slotIndex))

        val updated = observer.sent.filterIsInstance<ServerPacket.PlayerUpdated>().last { it.player.characterId == ownerId }
        assertEquals(listOf(401), updated.player.paperdollSpriteIds)
        assertEquals(
            listOf(
                com.morgana.domain.item.PaperdollLayer(
                    slot = com.morgana.domain.item.PaperdollSlot.HEAD,
                    spriteId = 401,
                    tintHex = "#ef4444",
                    hidesSlots = setOf(com.morgana.domain.item.PaperdollSlot.HAIR)
                )
            ),
            updated.player.paperdollLayers
        )
    }
}
