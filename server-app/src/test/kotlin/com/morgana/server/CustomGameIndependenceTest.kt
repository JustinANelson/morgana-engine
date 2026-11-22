package com.morgana.server

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.game.AttributeDefinition
import com.morgana.domain.game.CombatDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.ProgressionDefinition
import com.morgana.domain.game.VitalDefinition
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CustomGameIndependenceTest {

    private val customGame = GameDefinition(
        id = "aetheria",
        name = "Aetheria ORPG",
        attributes = listOf(
            AttributeDefinition(id = "body", name = "Body", short = "BOD", base = 10, allocatable = true),
            AttributeDefinition(id = "mind", name = "Mind", short = "MND", base = 8, allocatable = true)
        ),
        vitals = listOf(
            VitalDefinition(id = "stamina", name = "Stamina", short = "STA", color = "#22c55e", role = VitalRole.LIFE, max = "50 + body * 5"),
            VitalDefinition(id = "focus", name = "Focus", short = "FOC", color = "#3b82f6", max = "20 + mind * 4")
        ),
        progression = ProgressionDefinition(maxLevel = 50, xpExpr = "level * 50"),
        combat = CombatDefinition(
            melee = "max(1, a.body * 2 - d.body)",
            ranged = "max(1, a.body - d.body)",
            spell = "max(1, a.mind * 3)",
            critChanceBp = "500",
            dodgeChanceBp = "500"
        )
    )

    private class RecordingSession(override val sessionId: String = "session-custom") : ClientSession {
        override var characterId: String? = null
        val packets = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { packets += packet }
        override fun close(reason: String) {}
    }

    @Test
    fun `server runs a completely custom game with custom attributes vitals and formulas`(@TempDir dir: Path) {
        // 1. Write custom game.json
        Files.writeString(GameDefinitionLoader.fileIn(dir), GameDefinitionLoader.encode(customGame))

        val store = MorganaDataStore(dir)

        // 2. Seed content tailored to Aetheria
        val sword = ItemDefinition(
            id = 1,
            name = "Rune Blade",
            type = ItemType.WEAPON,
            weaponStyle = WeaponStyle.MELEE,
            attributeReq = mapOf("body" to 8),
            attributeBonuses = mapOf("body" to 2),
            attackDamage = 15
        )
        val potion = ItemDefinition(
            id = 2,
            name = "Stamina Draught",
            type = ItemType.CONSUMABLE,
            restore = mapOf("stamina" to 25)
        )
        val spell = SpellDefinition(
            id = 1,
            name = "Mind Blast",
            targetType = SpellTargetType.SELF,
            costs = mapOf("focus" to 5),
            cooldownTicks = 1,
            basePower = 10
        )
        val mob = NpcDefinition(
            id = 1,
            name = "Aether Sprite",
            behavior = NpcBehavior.PASSIVE,
            attributes = mapOf("body" to 4, "mind" to 6),
            vitals = mapOf("stamina" to 30)
        )
        val map = MapDefinition(
            id = 1,
            name = "Shrine of Winds",
            width = 10,
            height = 10,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 5, spawnY = 5, respawnTicks = 50))
        )
        val cls = ClassDefinition(
            id = 1,
            name = "Mystic Knight",
            baseAttributes = mapOf("body" to 12, "mind" to 10),
            startMapId = 1,
            startX = 4,
            startY = 5,
            startingItems = listOf(ItemStack(1, 1), ItemStack(2, 2)),
            startingSpellIds = listOf(1)
        )

        store.world.items.save(sword.id, sword)
        store.world.items.save(potion.id, potion)
        store.world.spells.save(spell.id, spell)
        store.world.npcs.save(mob.id, mob)
        store.world.maps.save(map.id, map)
        store.world.classes.save(cls.id, cls)

        // 3. Start Coordinator
        val coordinator = MorganaWorldCoordinator(store)
        assertEquals("aetheria", coordinator.simulation.game.definition.id)
        assertEquals("stamina", coordinator.simulation.game.lifeVitalId)

        // 4. Authenticate new player
        val session = RecordingSession()
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate(
                username = "aether_hero",
                password = "Password123!",
                requestedClassId = 1,
                protocolVersion = PROTOCOL_VERSION
            )
        )

        val authRes = session.packets.filterIsInstance<ServerPacket.AuthResult>().single()
        assertTrue(authRes.success, authRes.message)
        assertEquals("aetheria", authRes.gameDefinition?.id)

        val hero = coordinator.simulation.players.getValue(session.characterId!!)
        // Attributes check
        assertEquals(12, hero.attributes["body"])
        assertEquals(10, hero.attributes["mind"])
        assertNull(hero.attributes["str"], "classic attributes must not exist")

        // Vitals check: 50 + body * 5 = 50 + 12 * 5 = 110; 20 + mind * 4 = 20 + 10 * 4 = 60
        assertEquals(110, hero.vitals["stamina"]?.cur)
        assertEquals(110, hero.vitals["stamina"]?.max)
        assertEquals(60, hero.vitals["focus"]?.cur)
        assertEquals(60, hero.vitals["focus"]?.max)
        assertNull(hero.vitals["hp"], "classic vitals must not exist")

        // 5. Stat allocation test
        coordinator.simulation.players[hero.id] = hero.copy(unspentStatPoints = 1)
        coordinator.handleClientPacket(session, ClientPacket.AllocateStatPoint("body"))
        val updatedHero = coordinator.simulation.players.getValue(hero.id)
        assertEquals(13, updatedHero.attributes["body"])
        assertEquals(0, updatedHero.unspentStatPoints)
        // Max stamina increased from 110 to 115 (50 + 13 * 5 = 115)
        assertEquals(115, updatedHero.vitals["stamina"]?.max)

        // 6. Spell cost test (deducts focus)
        coordinator.handleClientPacket(session, ClientPacket.CastSpell(1))
        val afterCast = coordinator.simulation.players.getValue(hero.id)
        assertEquals(55, afterCast.vitals["focus"]?.cur, "focus reduced by 5")

        // 7. Melee combat test against custom NPC
        // Equip sword from slot 0
        coordinator.handleClientPacket(session, ClientPacket.UseInventorySlot(0))
        // Hero at (4,5) facing DOWN -> face RIGHT towards (5,5)
        coordinator.handleClientPacket(session, ClientPacket.FaceDirection(Direction.RIGHT))
        repeat(20) { coordinator.stepServerTick() }
        coordinator.handleClientPacket(session, ClientPacket.Attack)
        val npc = coordinator.simulation.mapNpcs.getValue(1).single()
        // Melee formula: max(1, a.body * 2 - d.body)
        // Hero has body 13 + sword bonus 2 = 15; defender body = 4 -> 15 * 2 - 4 = 26 damage
        // NPC stamina started at 30, so 30 - 26 = 4
        assertEquals(4, npc.vitals["stamina"]?.cur)
    }
}
