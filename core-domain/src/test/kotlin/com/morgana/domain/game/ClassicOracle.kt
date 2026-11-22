package com.morgana.domain.game

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Plain five-stat holder for the oracle (the production `Stats` class goes away when stats become data). */
data class OracleStats(val str: Int, val def: Int, val intStat: Int, val agi: Int, val vit: Int) {
    fun asMap(): Map<String, Int> = mapOf("str" to str, "def" to def, "int" to intStat, "agi" to agi, "vit" to vit)
}

/**
 * The rules the engine had hard-wired before stats became data, copied here **unchanged** (floating point and all) so Morgana Classic's
 * integer formulas can be proven equal to them. The hard-wired originals (`StatFormulas` and the regeneration lines in `CombatSystem`)
 * no longer exist; this copy is the only record of them.
 */
object ClassicOracle {
    fun maxHp(level: Int, s: OracleStats, bonus: Int = 0): Int = max(10, 80 + (level * 20) + (s.vit * 12) + (s.str * 3) + bonus)

    fun maxMp(level: Int, s: OracleStats, bonus: Int = 0): Int = max(0, 30 + (level * 10) + (s.intStat * 10) + bonus)

    fun maxSp(level: Int, s: OracleStats, bonus: Int = 0): Int = max(10, 80 + (level * 5) + (s.agi * 6) + (s.vit * 4) + bonus)

    fun xpRequiredForNextLevel(currentLevel: Int): Long {
        val safeLevel = currentLevel.coerceAtLeast(1)
        return (100.0 * safeLevel.toDouble().pow(1.65)).roundToInt().toLong()
    }

    fun melee(a: OracleStats, weapon: Int, d: OracleStats, armor: Int, crit: Boolean): Int {
        val rawOffense = (a.str * 2) + weapon + (a.agi / 3)
        val mitigation = d.def + (armor / 2) + (d.vit / 4)
        val baseDamage = max(1, rawOffense - mitigation)
        return if (crit) (baseDamage * 1.5).roundToInt().coerceAtLeast(2) else baseDamage
    }

    fun ranged(a: OracleStats, weapon: Int, ammo: Int, d: OracleStats, armor: Int, crit: Boolean): Int {
        val rawOffense = (a.agi * 2) + (a.str / 2) + weapon + ammo
        val mitigation = d.def + (armor / 2)
        val baseDamage = max(1, rawOffense - mitigation)
        return if (crit) (baseDamage * 1.5).roundToInt().coerceAtLeast(2) else baseDamage
    }

    fun spell(caster: OracleStats, basePower: Int, scales: Boolean, heals: Boolean, target: OracleStats): Int {
        val scaling = if (scales) (caster.intStat * 2) + (caster.vit / 4) else 0
        val rawPower = max(1, basePower + scaling)
        if (heals) return rawPower
        val magicResist = (target.intStat / 2) + (target.def / 2)
        return max(1, rawPower - magicResist)
    }

    fun critChanceBp(a: OracleStats): Int = (250 + a.agi * 35).coerceIn(100, 5000)

    fun dodgeChanceBp(a: OracleStats, d: OracleStats): Int = (300 + (d.agi - a.agi) * 30).coerceIn(100, 4000)

    // Natural regeneration, as written inline in CombatSystem (every 40 ticks).
    fun hpRegen(s: OracleStats): Int = max(1, s.vit / 3)

    fun mpRegen(s: OracleStats): Int = max(1, s.intStat / 3)

    fun spRegen(s: OracleStats): Int = max(2, s.agi / 2)
}
