package com.morgana.domain.stats

import kotlinx.serialization.Serializable

/**
 * Entity vital pool state (current and maximum values).
 */
@Serializable
data class VitalState(
    val cur: Int = 0,
    val max: Int = 0
) {
    val current: Int get() = cur
    val isAlive: Boolean get() = cur > 0

    fun applyDamage(amount: Int): VitalState =
        copy(cur = (cur - maxOf(0, amount)).coerceAtLeast(0))

    fun heal(amount: Int): VitalState =
        copy(cur = (cur + maxOf(0, amount)).coerceAtMost(max))
}

/**
 * Timed buffs, debuffs, DoTs, HoTs, and crowd-control status effects.
 */
@Serializable
enum class StatusEffectType(val isNegative: Boolean) {
    DOT_POISON(true),
    DOT_BURN(true),
    DOT_BLEED(true),
    HOT_REGEN(false),
    STUN(true),
    ROOT(true),
    SLOW(true),
    HASTE(false),
    SHIELD(false),
    STAT_BUFF(false),
    STAT_DEBUFF(true),

    /** Flight: walk over blocked tiles and cannot be struck in melee (`magnitude` unused). */
    FLY(false),

    /** Sends `magnitude` percent of the melee damage taken back at the attacker. */
    REFLECT(false),

    /**
     * Forces a monster to fight the one who applied it (`sourceEntityId`) for as long as it lasts, even if someone else stands
     * closer or hit it first. Ignored if the taunter is dead, on another map or out of reach. Only meaningful on monsters.
     */
    TAUNT(true)
}

/**
 * Active status instance on a player or NPC.
 */
@Serializable
data class ActiveStatusEffect(
    val id: String,
    val type: StatusEffectType,
    val sourceEntityId: String,
    val magnitude: Int = 0,
    val attributeModifiers: Map<String, Int> = emptyMap(),
    val remainingTicks: Int,
    val tickInterval: Int = 20,
    val ticksUntilNextPulse: Int = 20
) {
    val preventsMovement: Boolean
        get() = type == StatusEffectType.STUN || type == StatusEffectType.ROOT

    val preventsActions: Boolean
        get() = type == StatusEffectType.STUN
}
