package com.morgana.domain.combat

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.Inventory
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The combat rules that used to be constants in the engine are now fields of the game's `combat` definition. */
class CombatRulesAsDataTest {

    private fun unarmedSim(combat: com.morgana.domain.game.CombatDefinition): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(combat = combat))
        val sim = WorldSimulation(game = game)
        sim.maps[1] = MapDefinition(id = 1, name = "Arena", width = 10, height = 10)
        sim.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "Hero",
                position = WorldPosition.fromTile(1, 2, 2, Direction.RIGHT),
                vitals = mapOf("hp" to VitalState(100, 100)),
                attributes = mapOf("str" to 10),
                inventory = Inventory(), equipment = EquipmentLoadout(), lastAttackTick = -1000L
            )
        )
        sim.npcs[1] = NpcDefinition(id = 1, name = "Dummy", spriteId = 1, vitals = mapOf("hp" to 5000), attackDamage = 0)
        sim.mapNpcs.getOrPut(1) { mutableListOf() }.add(
            MapNpcInstance(
                instanceId = "n1", spawnSlotIndex = 0, npcId = 1, mapId = 1, tileX = 3, tileY = 2, homeX = 3, homeY = 2,
                vitals = mapOf("hp" to VitalState(5000, 5000))
            )
        )
        return sim
    }

    @Test
    fun `the default attack interval comes from the game definition`() {
        val fast = unarmedSim(MorganaClassic.definition.combat.copy(defaultAttackSpeedTicks = 2))
        assertTrue(fast.playerAttack("p1").isNotEmpty())
        repeat(2) { fast.stepTick() }
        assertTrue(fast.playerAttack("p1").isNotEmpty(), "a 2 tick interval allows another attack after 2 ticks")

        val slow = unarmedSim(MorganaClassic.definition.combat.copy(defaultAttackSpeedTicks = 40))
        assertTrue(slow.playerAttack("p1").isNotEmpty())
        repeat(20) { slow.stepTick() }
        assertTrue(slow.playerAttack("p1").isEmpty(), "a 40 tick interval still blocks the next attack after 20 ticks")
    }

    @Test
    fun `the defaults equal the values that were hard-coded before`() {
        val c = MorganaClassic.definition.combat
        assertEquals(12, c.defaultAttackSpeedTicks)
        assertEquals(100, c.defaultNpcRespawnTicks)
        assertEquals(600, c.lootOwnerProtectionTicks)
        assertEquals(2400, c.groundItemDespawnTicks)
        assertEquals(20, c.partyXpBonusPercent)
        // The party bonus used `(xp * 1.20).toLong()`; the integer form must agree wherever XP can plausibly be.
        for (xp in 0L..2_000_000L) assertEquals((xp * 1.20).toLong(), xp * (100 + c.partyXpBonusPercent) / 100, "xp=$xp")
    }

    @Test
    fun `the validator rejects out-of-range combat rule values`() {
        val classic = MorganaClassic.definition
        fun pointers(c: com.morgana.domain.game.CombatDefinition) =
            GameDefinitionValidator.validate(classic.copy(combat = c)).map { it.pointer }
        assertTrue(pointers(classic.combat).none { it.startsWith("/combat") })
        assertTrue("/combat/defaultAttackSpeedTicks" in pointers(classic.combat.copy(defaultAttackSpeedTicks = 0)))
        assertTrue("/combat/defaultNpcRespawnTicks" in pointers(classic.combat.copy(defaultNpcRespawnTicks = -1)))
        assertTrue("/combat/lootOwnerProtectionTicks" in pointers(classic.combat.copy(lootOwnerProtectionTicks = -5)))
        assertTrue("/combat/groundItemDespawnTicks" in pointers(classic.combat.copy(groundItemDespawnTicks = 0)))
        assertTrue("/combat/partyXpBonusPercent" in pointers(classic.combat.copy(partyXpBonusPercent = 5_000)))
    }

    @Test
    fun `an unknown combat module is an error and never a silent fallback`() {
        assertThrows(IllegalArgumentException::class.java) { CombatModule.forId("typo_module") }
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(combat = MorganaClassic.definition.combat.copy(module = "typo_module")))
            .any { it.pointer == "/combat/module" })
    }

    @Test
    fun `the real-time module has a neutral id and keeps its older names`() {
        assertEquals("realtime", CombatModule.forId("realtime").id)
        assertTrue(CombatModule.forId("morgana_classic") is RealtimeCombatModule)
        assertTrue(CombatModule.forId("classic") is RealtimeCombatModule)
        assertFalse(CombatModule.isCombatEnabled("noop"))
        assertEquals(CombatModule.DEFAULT_ID, com.morgana.domain.game.CombatDefinition().module)
    }
}
