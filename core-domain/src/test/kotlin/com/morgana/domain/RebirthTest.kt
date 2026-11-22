package com.morgana.domain

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.RebirthDefinition
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RebirthTest {
    private val cfg = RebirthDefinition(minLevel = 50, resetLevel = 5, maxRebirths = 2, bonusStatPointsPerRebirth = 4, grantSpellIds = mapOf(1 to listOf(9)))
    private val sword = ItemDefinition(id = 2, name = "Big Sword", type = ItemType.WEAPON, levelReq = 40, attackDamage = 9)
    private val dagger = ItemDefinition(id = 3, name = "Dagger", type = ItemType.WEAPON, levelReq = 1, attackDamage = 3)

    private fun sim(): WorldSimulation {
        val classic = MorganaClassic.definition
        val maxLevel = classic.progression.maxLevel.coerceAtLeast(60)
        val game = CompiledGame.compile(classic.copy(rebirth = cfg, progression = classic.progression.copy(maxLevel = maxLevel)))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.classes[1] = ClassDefinition(id = 1, name = "Fighter", baseAttributes = mapOf("str" to 12), autoStatGrowthPerLevel = mapOf("str" to 2), startingSpellIds = listOf(1))
        s.classes[2] = ClassDefinition(id = 2, name = "Mage", baseAttributes = mapOf("int" to 12), statPointsPerLevel = 3, startingSpellIds = listOf(2))
        s.items[2] = sword; s.items[3] = dagger
        s.spells[1] = SpellDefinition(id = 1, name = "Slash", targetType = SpellTargetType.SELF)
        s.spells[2] = SpellDefinition(id = 2, name = "Bolt", targetType = SpellTargetType.SELF, classReqIds = setOf(2))
        s.spells[9] = SpellDefinition(id = 9, name = "Aura", targetType = SpellTargetType.SELF, rebirthReq = 1)
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", classId = 1, level = 55, xp = 123L,
                knownSpellIds = listOf(1),
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        return s
    }

    private fun WorldSimulation.rebirth(classId: Int = 0): List<WorldSimulationEvent> =
        mutableListOf<WorldSimulationEvent>().also { executeEventCommands("p1", 1, listOf(EventCommand.Rebirth(classId)), it) }

    private fun WorldSimulation.p() = players.getValue("p1")

    @Test
    fun `rebirth resets level, rebuilds attributes, counts the rebirth, grants spells and shelves unfit gear`() {
        val s = sim()
        s.rebirth()
        val p = s.p()
        assertEquals(5, p.level)
        assertEquals(0L, p.xp)
        assertEquals(1, p.variables["rebirths"])
        assertEquals(12 + 2 * 4, p.attributes["str"], "class base plus auto growth for 4 level-ups")
        assertEquals(4, p.unspentStatPoints, "bonus points per rebirth")
        assertTrue(9 in p.knownSpellIds && 1 in p.knownSpellIds, "keeps Slash and gains the rebirth spell")
        assertEquals(null, p.equipment.getEquipped(EquipmentSlot.WEAPON), "the level 40 sword no longer fits")
        assertEquals(1, p.inventory.totalCountOf(2), "it moved to the bag")
        assertEquals(p.vitals.getValue(s.game.lifeVitalId).max, p.vitals.getValue(s.game.lifeVitalId).cur)
    }

    @Test
    fun `rebirth can switch class and drops spells the new class cannot use`() {
        val s = sim()
        s.rebirth(classId = 2)
        val p = s.p()
        assertEquals(2, p.classId)
        assertEquals(12, p.attributes["int"])
        assertEquals(3 * 4 + 4, p.unspentStatPoints, "class points per level for 4 levels plus the bonus")
        assertTrue(2 in p.knownSpellIds, "the new class's starting spell")
    }

    @Test
    fun `rebirth is refused below the minimum level and past the maximum`() {
        val s = sim()
        s.players["p1"] = s.p().copy(level = 49)
        val refused = s.rebirth()
        assertEquals(49, s.p().level)
        assertTrue(refused.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { it.message.contains("50") })

        s.players["p1"] = s.p().copy(level = 60)
        s.rebirth()
        s.players["p1"] = s.p().copy(level = 60)
        s.rebirth()
        assertEquals(2, s.p().variables["rebirths"])
        s.players["p1"] = s.p().copy(level = 60)
        s.rebirth()
        assertEquals(60, s.p().level, "a third rebirth is refused")
        assertEquals(2, s.p().variables["rebirths"])
        assertEquals(8, s.p().unspentStatPoints, "bonus points scale with rebirths held (4 then 8 after the second)")
    }

    @Test
    fun `rebirth-gated spells cannot be cast or taught before the rebirth`() {
        val s = sim()
        s.players["p1"] = s.p().copy(knownSpellIds = listOf(1, 9))
        val before = s.playerCastSpell("p1", 9)
        assertFalse(before.any { it is WorldSimulationEvent.SpellCast }, "not yet reborn")
        s.rebirth()
        assertTrue(s.playerCastSpell("p1", 9).any { it is WorldSimulationEvent.SpellCast })
    }

    @Test
    fun `without a rebirth section nothing happens`() {
        val s = WorldSimulation(SeededRandomSource(5L), game = MorganaClassic.COMPILED)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", level = 99))
        s.rebirth()
        assertEquals(99, s.p().level)
    }
}
