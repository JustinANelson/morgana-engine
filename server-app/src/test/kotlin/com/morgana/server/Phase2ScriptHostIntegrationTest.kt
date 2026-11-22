package com.morgana.server

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase2ScriptHostIntegrationTest {

    private class RecordingSession(override val sessionId: String = "test-session") : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
    }

    @Test
    fun `custom lua scripts load on startup and execute commands producing deterministic script effects`(@TempDir tempDir: Path) {
        val scriptsDir = tempDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        val customCommandScript = """
            register.command("spark", "Cast spark", 0, function(player, args, world)
                world.send_message(player.id, "You invoked spark!")
                world.damage_player(player.id, 5, "hp")
                world.give_item(player.id, 1, 1)
                return "Spark casted"
            end)

            register.formula("aether_dmg", function(params, world)
                local str = params["a.strength"] or 10
                return str * 4
            end)
        """.trimIndent()
        Files.writeString(scriptsDir.resolve("magic.lua"), customCommandScript)

        val store = MorganaDataStore(tempDir)
        store.world.items.save(1, ItemDefinition(id = 1, name = "Mana Shard", type = ItemType.CONSUMABLE))
        store.world.maps.save(1, MapDefinition(id = 1, name = "Sanctum", width = 20, height = 20))
        store.world.classes.save(1, ClassDefinition(id = 1, name = "Mage", startMapId = 1, startX = 10, startY = 10))

        val coordinator = MorganaWorldCoordinator(store)

        // Verify module loaded
        assertTrue(coordinator.simulation.scripts.hasModule("magic.lua"))
        assertTrue(coordinator.simulation.scripts.registry.hasFormula("aether_dmg"))
        val dmg = coordinator.simulation.scripts.registry.evaluateFormula(
            "aether_dmg",
            mapOf("a.strength" to 12L),
            coordinator.simulation.asScriptWorldView()
        )
        assertEquals(48L, dmg)

        // Authenticate new player
        val session = RecordingSession()
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate(
                username = "Merlin",
                password = "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION
            )
        )
        val charId = session.characterId
        assertNotNull(charId)

        // Execute /spark custom slash command
        coordinator.handleClientPacket(session, ClientPacket.AdminSlashCommand("/spark"))

        // Verify player state mutated deterministically via ScriptEffect
        val updatedPlayer = coordinator.simulation.players[charId]
        assertNotNull(updatedPlayer)
        val curHp = updatedPlayer!!.vitals["hp"]?.cur ?: 0
        val maxHp = updatedPlayer.vitals["hp"]?.max ?: 0
        assertEquals(maxHp - 5, curHp) // Damaged by 5
        assertEquals(1, updatedPlayer.inventory.totalCountOf(1)) // Given 1 Mana Shard
    }

    @Test
    fun `hot reload refreshes script modules and registries live`(@TempDir tempDir: Path) {
        val scriptsDir = tempDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        val scriptV1 = """
            register.formula("level_bonus", function(params, world)
                return 10
            end)
        """.trimIndent()
        val scriptFile = scriptsDir.resolve("progression.lua")
        Files.writeString(scriptFile, scriptV1)

        val store = MorganaDataStore(tempDir)
        val coordinator = MorganaWorldCoordinator(store)
        assertEquals(10L, coordinator.simulation.scripts.registry.evaluateFormula("level_bonus", emptyMap(), coordinator.simulation.asScriptWorldView()))

        // Update script on disk
        val scriptV2 = """
            register.formula("level_bonus", function(params, world)
                return 50
            end)
        """.trimIndent()
        Files.writeString(scriptFile, scriptV2)

        // Trigger reload
        coordinator.reloadContent()

        assertEquals(50L, coordinator.simulation.scripts.registry.evaluateFormula("level_bonus", emptyMap(), coordinator.simulation.asScriptWorldView()))
    }
}
