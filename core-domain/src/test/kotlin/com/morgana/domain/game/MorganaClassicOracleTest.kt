package com.morgana.domain.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The proof that matters: Morgana Classic's integer formulas in `game.json` give exactly the numbers the hard-coded rules gave, for
 * generated inputs across the whole range the engine allows, and for every level of the XP curve.
 */
class MorganaClassicOracleTest {
    private val game = CompiledGame.compile(MorganaClassic.definition)
    private val random = Random(20261108)

    private fun stats(): OracleStats = OracleStats(random.nextInt(1, 3000), random.nextInt(1, 3000), random.nextInt(1, 3000), random.nextInt(1, 3000), random.nextInt(1, 3000))

    @Test
    fun `the built-in definition is valid`() {
        assertEquals(emptyList<GameIssue>(), GameDefinitionValidator.validate(MorganaClassic.definition))
        assertEquals("hp", game.lifeVitalId)
        assertEquals(listOf("str", "def", "int", "agi", "vit"), game.attributeIds)
        assertEquals(listOf("hp", "mp", "sp"), game.vitalIds)
    }

    @Test
    fun `experience needed matches the original curve at every level`() {
        for (level in 1..99) {
            assertEquals(ClassicOracle.xpRequiredForNextLevel(level), game.xpToNext(level), "level $level")
        }
        assertNull(game.xpToNext(100), "no further level after the maximum")
        assertNull(game.xpToNext(0))
        assertEquals(99, MorganaClassic.definition.progression.xpTable!!.size)
    }

    @Test
    fun `maximum vitals and regeneration match the original for generated inputs`() {
        repeat(20_000) {
            val s = stats()
            val level = random.nextInt(1, 101)
            val bonus = random.nextInt(0, 100_000)
            val attrs = s.asMap()
            assertEquals(ClassicOracle.maxHp(level, s, bonus).toLong(), game.vitalMax("hp", level, attrs, bonus.toLong()))
            assertEquals(ClassicOracle.maxMp(level, s, bonus).toLong(), game.vitalMax("mp", level, attrs, bonus.toLong()))
            assertEquals(ClassicOracle.maxSp(level, s, bonus).toLong(), game.vitalMax("sp", level, attrs, bonus.toLong()))
            assertEquals(ClassicOracle.hpRegen(s).toLong(), game.regenAmount("hp", level, attrs))
            assertEquals(ClassicOracle.mpRegen(s).toLong(), game.regenAmount("mp", level, attrs))
            assertEquals(ClassicOracle.spRegen(s).toLong(), game.regenAmount("sp", level, attrs))
        }
        assertEquals(40, game.regenEveryTicks("hp"))
    }

    @Test
    fun `damage, critical chance and dodge chance match the original for generated inputs`() {
        repeat(30_000) {
            val a = stats(); val d = stats()
            val weapon = random.nextInt(0, 5000); val ammo = random.nextInt(0, 5000); val armor = random.nextInt(0, 5000)
            val crit = random.nextBoolean(); val power = random.nextInt(1, 5000)
            val am = a.asMap(); val dm = d.asMap()
            assertEquals(ClassicOracle.melee(a, weapon, d, armor, crit).toLong(), game.meleeDamage(am, dm, weapon.toLong(), armor.toLong(), crit))
            assertEquals(ClassicOracle.ranged(a, weapon, ammo, d, armor, crit).toLong(), game.rangedDamage(am, dm, weapon.toLong(), ammo.toLong(), armor.toLong(), crit))
            assertEquals(ClassicOracle.spell(a, power, true, false, d).toLong(), game.spellOutput(am, dm, power.toLong(), scales = true, heals = false))
            assertEquals(ClassicOracle.spell(a, power, false, false, d).toLong(), game.spellOutput(am, dm, power.toLong(), scales = false, heals = false))
            assertEquals(ClassicOracle.spell(a, power, true, true, d).toLong(), game.spellOutput(am, dm, power.toLong(), scales = true, heals = true))
            assertEquals(ClassicOracle.critChanceBp(a).toLong(), game.critChanceBp(am))
            assertEquals(ClassicOracle.dodgeChanceBp(a, d).toLong(), game.dodgeChanceBp(am, dm))
        }
    }

    @Test
    fun `critical damage rounds exactly like the original for every small base damage`() {
        // The edge of the (3*base + 1)/2 rewrite: odd bases round the half up, even bases are exact, and the floor of 2 applies.
        // With every stat at 1 and no armor the base damage is exactly 1 + weapon, so sweeping the weapon sweeps the base damage.
        val one = OracleStats(1, 1, 1, 1, 1)
        for (weapon in 0..20_000) {
            for (crit in listOf(false, true)) {
                assertEquals(
                    ClassicOracle.melee(one, weapon, one, 0, crit).toLong(),
                    game.meleeDamage(one.asMap(), one.asMap(), weapon.toLong(), 0, crit),
                    "weapon $weapon crit $crit"
                )
            }
        }
    }

    @Test
    fun `extreme attribute values stay in range`() {
        val extreme = MorganaClassic.definition.attributes.associate { it.id to it.effectiveCap }
        val low = MorganaClassic.definition.attributes.associate { it.id to it.floor }
        assertTrue(game.vitalMax("hp", 100, extreme, 1_000_000L) > 0)
        assertTrue(game.meleeDamage(extreme, low, 100_000, 100_000, true) > 0)
        assertTrue(game.spellOutput(extreme, low, 100_000, scales = true, heals = false) > 0)
    }
}
