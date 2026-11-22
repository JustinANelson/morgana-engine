package com.morgana.server

import com.morgana.domain.game.EngineApiVersion
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.script.ScriptCapability
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.validation.ContentValidator
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.script.luak.LuakScriptValidator
import com.morgana.script.luak.ScriptValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 5 Verification: Open-Engine Product.
 * Proves that a creator outside the project can scaffold, configure, and boot
 * a completely custom ORPG without editing a single line of engine Kotlin code.
 */
class Phase5OpenEngineTest {

    private class RecordingSession(override val sessionId: String = "open-engine-session") : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
    }

    @Test
    fun `engine API versioning rejects incompatible versions and validates capabilities`() {
        assertTrue(EngineApiVersion.isCompatible("1"))
        assertTrue(EngineApiVersion.isCompatible("1.0"))
        assertTrue(EngineApiVersion.isCompatible("1.0.0"))
        assertTrue(EngineApiVersion.isCompatible("^1.0"))
        assertTrue(EngineApiVersion.isCompatible("v1"))

        assertFalse(EngineApiVersion.isCompatible("2"))
        assertFalse(EngineApiVersion.isCompatible("99.0"))
        assertFalse(EngineApiVersion.isCompatible("0"))
        assertFalse(EngineApiVersion.isCompatible("invalid"))
    }

    @Test
    fun `scaffolded custom game is valid, boots, and runs full gameplay loop`(@TempDir gameDir: Path, @TempDir dataDir: Path) {
        // 1. Scaffold new game project
        val gameName = "Chronicles of Aethelgard"
        val gameId = "aethelgard"
        GameScaffolder.scaffold(gameDir, name = gameName, id = gameId)

        // Verify filesystem assets created
        assertTrue(Files.exists(gameDir.resolve("game.json")), "game.json must exist")
        assertTrue(Files.exists(gameDir.resolve("README.md")), "README.md must exist")
        assertTrue(Files.exists(gameDir.resolve("scripts/main.lua")), "scripts/main.lua must exist")
        assertTrue(Files.exists(gameDir.resolve("data/classes/class_001.json")), "class_001.json must exist")
        assertTrue(Files.exists(gameDir.resolve("data/items/item_001.json")), "item_001.json must exist")
        assertTrue(Files.exists(gameDir.resolve("data/npcs/npc_001.json")), "npc_001.json must exist")
        assertTrue(Files.exists(gameDir.resolve("data/maps/map_001.json")), "map_001.json must exist")

        // 2. Validate game definition
        val loadedGame = GameDefinitionLoader.load(gameDir)
        val gameDef = loadedGame.game.definition
        assertEquals(gameName, gameDef.name)
        assertEquals(gameId, gameDef.id)
        assertEquals(EngineApiVersion.CURRENT.toString(), gameDef.engineApi)
        val gameIssues = GameDefinitionValidator.validate(gameDef)
        assertTrue(gameIssues.isEmpty(), "game.json should have 0 validation issues: $gameIssues")

        // 3. Validate content collection
        val loadedContent = ContentLoader.load(gameDir.resolve("data"))
        assertTrue(loadedContent.fileIssues.isEmpty(), "Loaded content should have no file issues: ${loadedContent.fileIssues}")
        val contentReport = ContentValidator.validate(loadedContent.catalog, gameDef)
        assertTrue(contentReport.isValid, "Content files must have 0 errors: ${contentReport.errors}")

        // 4. Validate starter Lua script
        val scriptSource = Files.readString(gameDir.resolve("scripts/main.lua"))
        val scriptValidation = LuakScriptValidator.validate("main.lua", scriptSource)
        assertTrue(scriptValidation is ScriptValidationResult.Valid, "main.lua should be valid Lua syntax")

        // 5. Boot engine coordinator with the custom game
        val store = MorganaDataStore(rootDir = dataDir, gameDir = gameDir)
        val coordinator = MorganaWorldCoordinator(store)

        assertEquals(gameName, coordinator.simulation.game.definition.name)
        assertEquals(1, coordinator.simulation.classes.size)
        assertEquals("Adventurer", coordinator.simulation.classes[1]?.name)
        assertEquals(1, coordinator.simulation.items.size)
        assertEquals("Bronze Sword", coordinator.simulation.items[1]?.name)
        assertEquals(1, coordinator.simulation.npcs.size)
        assertEquals("Town Guide", coordinator.simulation.npcs[1]?.name)
        assertEquals(1, coordinator.simulation.maps.size)
        assertEquals("$gameName Town Square", coordinator.simulation.maps[1]?.name)

        // 6. Connect client session, register and spawn
        val session = RecordingSession()
        coordinator.onSessionConnected(session)
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate(
                username = "AethelWanderer",
                password = "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION
            )
        )
        val charId = session.characterId
        assertNotNull(charId, "Character must be authenticated and assigned an ID")
        coordinator.simulation.stepTick()

        val player = coordinator.simulation.players[charId]
        assertNotNull(player)
        assertEquals(1, player!!.position.mapId)
        assertEquals(5, player.position.tileX)
        assertEquals(5, player.position.tileY)
        assertEquals(10, player.attributes["str"])
        assertEquals(10, player.attributes["agi"])

        // 7. Test custom slash command registered in main.lua
        coordinator.handleClientPacket(session, ClientPacket.AdminSlashCommand("/hello"))
        coordinator.simulation.stepTick()

        val chatPackets = session.sent.filterIsInstance<ServerPacket.ChatBroadcast>()
        assertTrue(
            chatPackets.any { it.message.contains("greets the realm of $gameName") },
            "Chat should contain the broadcast from /hello defined in main.lua"
        )

        // 8. Test custom action registered in main.lua
        coordinator.handleClientPacket(session, ClientPacket.CustomAction("scout", emptyMap()))
        coordinator.simulation.stepTick()

        val scoutPackets = session.sent.filterIsInstance<ServerPacket.ChatBroadcast>()
        assertTrue(
            scoutPackets.any { it.message.contains("You scout your surroundings") },
            "Session should receive feedback from scout action"
        )
    }

    @Test
    fun `script capabilities enforce permissions during live simulation`(@TempDir gameDir: Path, @TempDir dataDir: Path) {
        GameScaffolder.scaffold(gameDir, name = "Restricted Realm", id = "restricted_realm")

        // Modify game.json to have restricted capabilities (only message and visual, no inventory or teleport)
        val gameFile = GameDefinitionLoader.fileIn(gameDir)
        val originalGame = GameDefinitionLoader.load(gameDir).game.definition
        val restrictedGame = originalGame.copy(
            capabilities = setOf(ScriptCapability.MESSAGE, ScriptCapability.VISUAL)
        )
        Files.writeString(gameFile, GameDefinitionLoader.encode(restrictedGame))

        val store = MorganaDataStore(rootDir = dataDir, gameDir = gameDir)
        val coordinator = MorganaWorldCoordinator(store)

        val session = RecordingSession()
        coordinator.onSessionConnected(session)
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate(
                username = "TestPlayer",
                password = "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION
            )
        )
        coordinator.simulation.stepTick()
        val charId = session.characterId!!

        // Teleport should be blocked because ScriptCapability.TELEPORT is missing
        val warpEvents = coordinator.simulation.applyScriptEffect(
            ScriptEffect.WarpPlayer(playerId = charId, mapId = 1, x = 15, y = 15)
        )
        assertTrue(warpEvents.isEmpty(), "WarpPlayer effect must be rejected when TELEPORT capability is not granted")
        assertEquals(5, coordinator.simulation.players[charId]?.position?.tileX)

        // GiveItem should be blocked because ScriptCapability.INVENTORY is missing
        val itemEvents = coordinator.simulation.applyScriptEffect(
            ScriptEffect.GiveItem(playerId = charId, itemId = 1, amount = 10)
        )
        assertTrue(itemEvents.isEmpty(), "GiveItem effect must be rejected when INVENTORY capability is not granted")

        // Broadcast should be permitted because ScriptCapability.MESSAGE is granted
        val msgEvents = coordinator.simulation.applyScriptEffect(
            ScriptEffect.BroadcastMessage("A permitted message")
        )
        assertTrue(msgEvents.any { it is WorldSimulationEvent.ChatEmitted }, "BroadcastMessage must be allowed")
    }

    @Test
    fun `standalone runtime boots scaffolded game on ephemeral port`(@TempDir gameDir: Path, @TempDir dataDir: Path) {
        GameScaffolder.scaffold(gameDir, name = "Ephemeral Realm", id = "ephemeral_realm")

        val runtime = MorganaServerRuntime.start(
            dataDir = dataDir,
            port = 0,
            watchContent = false,
            gameDir = gameDir
        )

        try {
            assertEquals("Ephemeral Realm", runtime.coordinator.simulation.game.definition.name)
            assertTrue(runtime.port > 0, "Runtime must bind to an ephemeral port")
        } finally {
            runtime.close()
        }
    }
}
