package com.morgana.server

import com.morgana.domain.combat.CombatActionResult
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.combat.LuaCombatModule
import com.morgana.domain.combat.RealtimeCombatModule
import com.morgana.domain.combat.NoOpCombatModule
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.replay.SimInput
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.script.luak.LuakScriptRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase3CombatModuleTest {

    private class RecordingSession(override val sessionId: String = "test-session") : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
    }

    private fun setupTestSimulation(runtime: LuakScriptRuntime, combatModule: com.morgana.domain.combat.CombatModule): WorldSimulation {
        val sim = WorldSimulation(scripts = runtime, combat = combatModule, game = MorganaClassic.COMPILED)

        val map = MapDefinition(id = 1, name = "Glade", width = 20, height = 20)
        sim.maps[1] = map

        val sword = ItemDefinition(
            id = 10,
            name = "Iron Blade",
            type = ItemType.WEAPON,
            attackDamage = 8,
            attackSpeedTicks = 5
        )
        sim.items[10] = sword

        val player = CharacterState(
            id = "hero-1",
            accountId = "acc-1",
            name = "Hero",
            position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT),
            vitals = mapOf("hp" to VitalState(100, 100), "mp" to VitalState(50, 50)),
            attributes = mapOf("str" to 12, "def" to 10, "agi" to 9, "vit" to 8, "int" to 5),
            inventory = Inventory(),
            equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(10, 1))),
            lastAttackTick = -100L
        )
        sim.spawnOrUpdatePlayer(player)

        val goblinDef = NpcDefinition(
            id = 1,
            name = "Goblin",
            spriteId = 1,
            vitals = mapOf("hp" to 50),
            attributes = mapOf("str" to 8, "def" to 5, "agi" to 5, "vit" to 5, "int" to 5),
            attackDamage = 6
        )
        sim.npcs[1] = goblinDef

        val goblinNpc = MapNpcInstance(
            instanceId = "goblin-1",
            spawnSlotIndex = 0,
            npcId = 1,
            mapId = 1,
            tileX = 6,
            tileY = 5,
            homeX = 6,
            homeY = 5,
            vitals = mapOf("hp" to VitalState(50, 50))
        )
        sim.mapNpcs.getOrPut(1) { mutableListOf() }.add(goblinNpc)

        return sim
    }

    @Test
    fun `Lua vs Kotlin combat differential test produces equivalent damage and vitals`() {
        val runtimeA = LuakScriptRuntime()
        val simKotlin = setupTestSimulation(runtimeA, RealtimeCombatModule())

        // Calculate expected damage from Classic combat
        val kotlinEvents = simKotlin.playerAttack("hero-1")
        val kotlinDamageEvent = kotlinEvents.filterIsInstance<WorldSimulationEvent.FloatingTextEmitted>()
            .firstOrNull { it.category.contains("DAMAGE") }
        assertNotNull(kotlinDamageEvent)
        val expectedDamageText = kotlinDamageEvent!!.text
        val goblinKotlin = simKotlin.mapNpcs[1]!!.first()
        val remainingHp = goblinKotlin.vitals["hp"]!!.cur

        // Setup Lua Combat module with identical damage calculation
        val runtimeB = LuakScriptRuntime()
        val luaCombatModule = LuaCombatModule(fallback = NoOpCombatModule())
        val simLua = setupTestSimulation(runtimeB, luaCombatModule)

        // Script implements Morgana Classic melee attack rules via Lua
        val luaScript = """
            register.combat({
                on_attack = function(char_id, world)
                    local player = world.get_player(char_id)
                    if not player then return end
                    
                    -- Check target in front
                    local target_x = player.x + (player.direction == "RIGHT" and 1 or 0)
                    local target_y = player.y
                    
                    local entities = world.get_entities_in_range(player.map_id, target_x, target_y, 0)
                    for _, e in ipairs(entities) do
                        if not e.is_player then
                            local weapon_dmg = 8
                            local str = player.effective_attributes["str"] or 5
                            local agi = player.effective_attributes["agi"] or 5

                            local goblin = world.get_npc(player.map_id, e.id)
                            local def = (goblin and goblin.attributes["def"]) or 5
                            local vit = (goblin and goblin.attributes["vit"]) or 5

                            local raw = str * 2 + weapon_dmg + math.floor(agi / 3)
                            local mit = def + math.floor(vit / 4)
                            local dmg = math.max(1, raw - mit)

                            world.damage_npc(player.map_id, e.id, dmg, "hp", char_id)
                            return
                        end
                    end
                end
            })
        """.trimIndent()

        runtimeB.loadModule("classic_combat.lua", luaScript, simLua.asScriptWorldView())

        val luaEvents = simLua.playerAttack("hero-1")
        val luaDamageEvent = luaEvents.filterIsInstance<WorldSimulationEvent.FloatingTextEmitted>()
            .firstOrNull { it.category.contains("DAMAGE") }
        assertNotNull(luaDamageEvent)

        val goblinLua = simLua.mapNpcs[1]!!.first()
        assertEquals(remainingHp, goblinLua.vitals["hp"]!!.cur, "NPC health after Lua attack should match Kotlin combat")
        assertEquals(expectedDamageText.replace("!", ""), luaDamageEvent!!.text.replace("!", ""), "Damage text should match between Lua and Kotlin")
    }

    @Test
    fun `test game with alternate peaceful combat runs on unmodified engine and routes custom action`(@TempDir tempDir: Path) {
        val scriptsDir = tempDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        // Script defining custom actions and disabling standard attacks
        val peacefulScript = """
            register.action("gather_herbs", function(char_id, params, world)
                world.floating_text(1, 10, 10, "+1 Herb", "GATHER", "#22C55E")
                world.award_xp(char_id, 25)
                world.give_item(char_id, 2, 1)
            end)

            register.combat({
                on_attack = function(char_id, world)
                    world.send_message(char_id, "Violence is not permitted in this realm.", "#94A3B8")
                end
            })
        """.trimIndent()
        Files.writeString(scriptsDir.resolve("peaceful.lua"), peacefulScript)

        val store = MorganaDataStore(tempDir)
        store.world.items.save(1, ItemDefinition(id = 1, name = "Twig", type = ItemType.MATERIAL))
        store.world.items.save(2, ItemDefinition(id = 2, name = "Sun Herb", type = ItemType.MATERIAL))
        store.world.maps.save(1, MapDefinition(id = 1, name = "Sanctuary", width = 20, height = 20))
        store.world.classes.save(1, ClassDefinition(id = 1, name = "Herbalist", startMapId = 1, startX = 10, startY = 10))

        val coordinator = MorganaWorldCoordinator(store)
        // Set simulation combat module to LuaCombatModule backed by the peaceful script
        coordinator.simulation.combat = LuaCombatModule(fallback = NoOpCombatModule())

        val session = RecordingSession()
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate(
                username = "Florist",
                password = "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION
            )
        )
        val charId = session.characterId
        assertNotNull(charId)

        // Attack is intercepted by peaceful Lua handler
        coordinator.handleClientPacket(session, ClientPacket.Attack)
        val chatPackets = session.sent.filterIsInstance<ServerPacket.ChatBroadcast>()
        assertTrue(chatPackets.any { it.message.contains("Violence is not permitted") })

        // Generic client action c_action triggers script action
        coordinator.handleClientPacket(
            session,
            ClientPacket.CustomAction(actionId = "gather_herbs", params = mapOf("tool" to "shears"))
        )

        // Verify player received the herb and experience from the custom action
        val updatedPlayer = coordinator.simulation.players[charId]
        assertNotNull(updatedPlayer)
        assertEquals(1, updatedPlayer!!.inventory.totalCountOf(2), "Player should have received 1 Sun Herb")
        assertTrue(updatedPlayer.xp >= 25L, "Player should have received 25 XP")
    }

    @Test
    fun `combat module is selected automatically from game json and validated`(@TempDir tempDir: Path) {
        val noopGame = com.morgana.domain.game.MorganaClassic.definition.copy(
            combat = com.morgana.domain.game.MorganaClassic.definition.combat.copy(module = "noop")
        )
        Files.writeString(tempDir.resolve("game.json"), com.morgana.persistence.GameDefinitionLoader.encode(noopGame))
        val store = MorganaDataStore(tempDir)
        val coordinator = MorganaWorldCoordinator(store)
        assertTrue(coordinator.simulation.combat is NoOpCombatModule, "combat.module='noop' in game.json selects NoOpCombatModule")

        val badGame = com.morgana.domain.game.MorganaClassic.definition.copy(
            combat = com.morgana.domain.game.MorganaClassic.definition.combat.copy(module = "turn_based")
        )
        val issues = com.morgana.domain.game.GameDefinitionValidator.validate(badGame)
        assertTrue(issues.any { it.pointer == "/combat/module" }, "unknown combat.module is rejected by validator")
    }
}

