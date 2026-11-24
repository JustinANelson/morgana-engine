package com.morgana.domain.item

/**
 * The game's rules for how worn gear counts, in one value so the stat totals of [EquipmentLoadout] take a single parameter. Built
 * from the game's `durability` and `tuning` sections by `GameDefinition.gearRules`; the defaults are the engine's long-standing
 * values, so a game that sets nothing is unchanged.
 */
data class GearRules(
    /** The percent of its bonuses a broken piece still gives (0 to 100). 0 means a broken piece adds nothing. */
    val brokenPercent: Int = 0,
    /** Damage of a bare-handed swing, when no weapon is worn. */
    val unarmedDamage: Int = DEFAULT_UNARMED_DAMAGE,
    /** Weapon damage one `+` level adds, flat. */
    val plusWeaponDamage: Int = DEFAULT_PLUS_WEAPON_DAMAGE,
    /** Armour defence one `+` level adds to armour, shields, helmets and boots, flat. */
    val plusArmorDefense: Int = DEFAULT_PLUS_ARMOR_DEFENSE,
    /** Maximum health one `+` level adds to rings and amulets. */
    val plusRingHp: Int = DEFAULT_PLUS_RING_HP,
    /** Percent of a weapon's own damage, or armour's own defence, one `+` level adds on top of the flat amount (3 = +3% per level). */
    val plusPercentPerLevel: Int = 0
) {
    /** Damage the `+` levels of a weapon with [baseDamage] add. */
    fun plusDamage(baseDamage: Int, plus: Int): Int = plus * plusWeaponDamage + percentOf(baseDamage, plus)

    /** Defence the `+` levels of armour with [baseDefense] add. */
    fun plusDefense(baseDefense: Int, plus: Int): Int = plus * plusArmorDefense + percentOf(baseDefense, plus)

    /** Maximum health the `+` levels of a ring or amulet add. */
    fun plusHp(plus: Int): Long = plus.toLong() * plusRingHp

    private fun percentOf(base: Int, plus: Int): Int = if (plusPercentPerLevel == 0) 0 else base * plus * plusPercentPerLevel / 100

    companion object {
        const val DEFAULT_UNARMED_DAMAGE = 2
        const val DEFAULT_PLUS_WEAPON_DAMAGE = 3
        const val DEFAULT_PLUS_ARMOR_DEFENSE = 2
        const val DEFAULT_PLUS_RING_HP = 15

        /** A game with no gear settings. */
        val DEFAULT = GearRules()
    }
}
