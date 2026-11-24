package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.ClassPassive
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.armorOf
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `game.json` `classPassives`: a small standing bonus per class, so soft class templates still feel different. */
class ClassPassiveTest {
    private fun game(vararg passives: Pair<Int, ClassPassive>) =
        CompiledGame.compile(MorganaClassic.definition.copy(classPassives = passives.toMap()))

    private val plate = ItemDefinition(id = 3, name = "Plate", type = ItemType.ARMOR, armorDefense = 100)
    private val blade = ItemDefinition(id = 2, name = "Blade", type = ItemType.WEAPON, attackDamage = 30)
    private val dummy = NpcDefinition(id = 1, name = "Dummy", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 50_000), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1))

    private fun hero(classId: Int, sim: WorldSimulation) = CharacterState(
        id = "p$classId", accountId = "a$classId", name = "Hero$classId", classId = classId, level = 5,
        position = WorldPosition.fromTile(1, 2, 5, Direction.RIGHT),
        equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2), EquipmentSlot.ARMOR to ItemStack(3)))
    ).recalculateVitals(sim.game, sim.items, refill = true)

    private fun sim(vararg passives: Pair<Int, ClassPassive>): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(9L), game = game(*passives))
        s.registerMap(MapDefinition(id = 1, name = "Yard", width = 12, height = 8))
        s.items[2] = blade; s.items[3] = plate
        s.npcs[1] = dummy
        s.spells[5] = SpellDefinition(id = 5, name = "Cure", targetType = SpellTargetType.SELF, isHealing = true, basePower = 30, cooldownTicks = 0)
        return s
    }

    @Test
    fun `scaling is a rounded percentage and does nothing at zero`() {
        assertEquals(105, ClassPassive.scale(100, 5))
        assertEquals(95, ClassPassive.scale(100, -5))
        assertEquals(3, ClassPassive.scale(3, 5), "small numbers round to the nearest whole")
        assertEquals(7, ClassPassive.scale(7, 0))
        assertEquals(0, ClassPassive.scale(0, 50))
    }

    @Test
    fun `a class passive raises maximum vitals, and a class without one is unchanged`() {
        val s = sim(2 to ClassPassive(vitalMaxPercent = mapOf("hp" to 10)))
        val plain = s.spawnOrUpdatePlayer(hero(1, s)).let { s.players.getValue("p1").vitals.getValue("hp").max }
        s.spawnOrUpdatePlayer(hero(2, s))
        val boosted = s.players.getValue("p2").vitals.getValue("hp").max
        assertEquals(ClassPassive.scale(plain, 10), boosted)
        assertTrue(boosted > plain)
        assertEquals(plain, sim().also { it.spawnOrUpdatePlayer(hero(1, it)) }.players.getValue("p1").vitals.getValue("hp").max, "no passives at all: the same as class 1 here")
    }

    @Test
    fun `a class passive raises damage dealt to monsters, so it reaches every attack path`() {
        fun swing(passive: ClassPassive?): Int {
            val s = if (passive == null) sim() else sim(1 to passive)
            s.spawnOrUpdatePlayer(hero(1, s))
            s.mapNpcs[1] = mutableListOf(MapNpcInstance("n1", 0, 1, 1, 3, 5, 3, 5, vitals = mapOf("hp" to VitalState(50_000, 50_000))))
            repeat(30) { s.stepTick() }
            val before = s.mapNpcs.getValue(1).single().vitals.getValue("hp").cur
            s.playerAttack("p1")
            return before - s.mapNpcs.getValue(1).single().vitals.getValue("hp").cur
        }
        val plain = swing(null)
        assertTrue(plain > 0)
        assertEquals(ClassPassive.scale(plain, 50), swing(ClassPassive(damagePercent = 50)).let { it }, "+50% damage")
        assertEquals(plain, swing(ClassPassive()), "an empty passive changes nothing")
    }

    @Test
    fun `a class passive raises the armour gear gives`() {
        val s = sim(1 to ClassPassive(armorPercent = 10))
        s.spawnOrUpdatePlayer(hero(1, s))
        s.spawnOrUpdatePlayer(hero(2, s))
        assertEquals(110, s.armorOf(s.players.getValue("p1")))
        assertEquals(100, s.armorOf(s.players.getValue("p2")), "another class is unaffected")
    }

    @Test
    fun `a class passive raises the healing a character casts`() {
        fun healed(passive: ClassPassive?): Int {
            val s = if (passive == null) sim() else sim(1 to passive)
            val caster = hero(1, s).copy(knownSpellIds = listOf(5))
            s.spawnOrUpdatePlayer(caster)
            val life = s.game.lifeVitalId
            val wounded = s.players.getValue("p1").let { it.copy(vitals = it.vitals + (life to it.vitals.getValue(life).copy(cur = 1))) }
            s.players["p1"] = wounded
            s.playerCastSpell("p1", 5)
            return s.players.getValue("p1").vitals.getValue(life).cur - 1
        }
        val plain = healed(null)
        assertTrue(plain > 0)
        assertTrue(healed(ClassPassive(healingPercent = 50)) > plain)
        assertEquals(plain, healed(ClassPassive(damagePercent = 50)), "a damage passive does not change healing")
    }

    @Test
    fun `passives are validated against the game's vitals and percent range`() {
        fun pointers(vararg p: Pair<Int, ClassPassive>) =
            GameDefinitionValidator.validate(MorganaClassic.definition.copy(classPassives = p.toMap())).map { it.pointer }
        assertTrue(pointers(1 to ClassPassive(vitalMaxPercent = mapOf("hp" to 5), damagePercent = 5)).isEmpty())
        assertTrue("/classPassives/1/vitalMaxPercent/mana" in pointers(1 to ClassPassive(vitalMaxPercent = mapOf("mana" to 5))))
        assertTrue("/classPassives/1/damagePercent" in pointers(1 to ClassPassive(damagePercent = 501)))
        assertTrue("/classPassives/1/armorPercent" in pointers(1 to ClassPassive(armorPercent = -91)))
        assertTrue("/classPassives/0" in pointers(0 to ClassPassive()))
    }
}
