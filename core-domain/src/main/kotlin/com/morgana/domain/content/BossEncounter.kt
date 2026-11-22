package com.morgana.domain.content

import kotlinx.serialization.Serializable

/**
 * A scripted boss fight (`encounter` on an [NpcDefinition]): the boss moves through [phases] as its life drops, each
 * phase can toughen it, announce itself, call adds and use telegraphed ground attacks, and a kill can lock the players
 * who took part out of its rewards for [lockoutTicks].
 *
 * Phases are listed from the start of the fight: the first phase usually has `fromLifePercent` 100, and every later
 * one a lower number. A phase begins when the boss's life is at or below its `fromLifePercent`; a boss never goes back
 * to an earlier phase except when it resets (its fight is abandoned and it returns home at full life).
 */
@Serializable
data class BossEncounter(
    val phases: List<BossPhase> = emptyList(),
    /**
     * World ticks a participant stays "saved" to this boss after its kill (1,728,000 is one real day; 12,096,000 a week).
     * While saved they get no XP and no drops from it. 0 means no lockout.
     */
    val lockoutTicks: Long = 0L
)

/** One stage of a boss fight. */
@Serializable
data class BossPhase(
    /** The phase starts when the boss's life is at or below this percent (100 for the opening phase). */
    val fromLifePercent: Int = 100,
    /** Said to everyone on the map when the phase begins; blank for silence. */
    val announce: String = "",
    /** The boss's melee and spell damage in percent of normal during this phase. */
    val attackPercent: Int = 100,
    /** The boss's step time in percent of normal during this phase (below 100 is faster). */
    val moveSpeedPercent: Int = 100,
    /** Monsters called once when the phase begins, placed on free tiles around the boss. */
    val summonNpcIds: List<Int> = emptyList(),
    /** Telegraphed ground attacks the boss uses during this phase, tried in order. */
    val abilities: List<BossAbility> = emptyList()
)

/**
 * A ground attack the boss warns about: a [shape] of [radiusTiles] is marked on the floor for [windUpTicks], and whoever
 * is still standing in it when the time is up takes the spell's damage (and status effect).
 */
@Serializable
data class BossAbility(
    /** The spell that does the damage; its `basePower` and status effect are used, its range and targeting are not. */
    val spellId: Int,
    val shape: BossZoneShape = BossZoneShape.AROUND_TARGET,
    val radiusTiles: Int = 2,
    val windUpTicks: Int = 40,
    /** Ticks before the boss may use this ability again, counted from when it starts. */
    val cooldownTicks: Int = 160,
    /** Written over the boss's head when it starts the attack, blank for the spell's name. */
    val warning: String = ""
)

/** Where a [BossAbility]'s danger zone is centred. */
@Serializable
enum class BossZoneShape {
    /** On the tile the boss's target stands on when the attack starts. */
    AROUND_TARGET,
    /** On the boss itself. */
    AROUND_SELF
}
