package com.morgana.domain.content

import com.morgana.domain.stats.StatusEffectType
import kotlinx.serialization.Serializable

/*
 * Action-combat content (ADR 0017): skills timed in ticks and the combo graphs ("weapon sets") that chain them.
 *
 * Everything is integers. Time is in simulation ticks counted from the tick the skill starts (tick 0); distance is in
 * pixels along the character's facing ("forward") and to its right ("side"). An action-combat CombatModule steps these
 * definitions; the classic `realtime` module ignores them.
 */

/** Size limits shared by the validator and, later, the module, so a definition that validates is always cheap to step. */
object ActionCombatLimits {
    const val MAX_PHASE_TICKS = 200
    const val MAX_SKILL_TICKS = 400
    const val MAX_COOLDOWN_TICKS = 100_000
    const val MAX_HITBOXES = 16
    const val MAX_WINDOWS = 8
    const val MAX_REACH_PIXELS = 640
    const val MAX_HIT_GROUP = 15
    const val MAX_TARGETS = 64
    const val MAX_DAMAGE_PERCENT = 10_000
    const val MAX_REACTION_TICKS = 100
    const val MAX_KNOCKBACK_PIXELS = 320
    const val MAX_HIT_STOP_TICKS = 10
    const val MAX_PROJECTILE_SPEED = 64
    const val MAX_PROJECTILE_RANGE = 1280
    const val MAX_GUARD_HOLD_TICKS = 6000
    const val MAX_SET_SKILLS = 64
    const val MAX_EDGES = 256
    const val MAX_INPUT_BUFFER_TICKS = 20
    /** How long after a skill ends a combo edge may still be taken (the character is idle but the chain is alive). */
    const val MAX_COMBO_GRACE_TICKS = 20
}

/** The shape tested against targets while a hitbox is live. */
@Serializable
enum class HitboxShape {
    /** A disc of `radiusPixels` centred on the offset point. */
    CIRCLE,
    /** A box `lengthPixels` long (forward) and `widthPixels` wide, starting at the offset point. */
    RECT,
    /** A pie slice of `radiusPixels` and `arcDegrees`, centred on the facing, with its tip at the offset point. */
    ARC
}

/** What a hit does to the target's own state machine, beyond damage. */
@Serializable
enum class HitReaction {
    NONE,
    /** Brief flinch: the target's current skill is interrupted. Ignored by super armor. */
    STAGGER,
    /** The target is on the ground and cannot act. */
    KNOCKDOWN,
    /** The target is launched and can be juggled. */
    AIRBORNE,
    /** The target is held by the attacker. Ignores super armor. */
    GRAB
}

@Serializable
enum class SkillArmorType {
    /** Hits land and hurt, but stagger, knockdown and airborne do not interrupt. */
    SUPER_ARMOR,
    /** Hits pass through: no damage, no reaction (a dodge's invulnerable frames). */
    INVULNERABLE
}

/** Which of the game's damage formulas (`game.json` `combat`) a hit uses before `damagePercent` is applied. */
@Serializable
enum class SkillDamageKind { MELEE, RANGED, SPELL }

/** Where a weapon set sits on a character: the plan's main weapon, sub weapon and awakening. */
@Serializable
enum class WeaponSetSlot { MAIN, SUB, AWAKENING }

/** What one hitbox does to a target it touches. */
@Serializable
data class SkillHit(
    val damageKind: SkillDamageKind = SkillDamageKind.MELEE,
    /** Percent of the formula's damage; 0 for a hit that only applies a reaction or status. */
    val damagePercent: Int = 100,
    val reaction: HitReaction = HitReaction.NONE,
    /** How long the reaction holds the target. */
    val reactionTicks: Int = 0,
    /** Push along the attacker's facing. */
    val knockbackPixels: Int = 0,
    /** Ticks both sides freeze on impact, for weight. */
    val hitStopTicks: Int = 0,
    val statusEffectType: StatusEffectType? = null,
    val statusMagnitude: Int = 0,
    val statusDurationTicks: Int = 0
)

/** A hitbox that leaves the character: it spawns on the hitbox's `fromTick` and flies forward. */
@Serializable
data class SkillProjectile(
    val spriteId: Int = 0,
    val speedPixelsPerTick: Int = 12,
    val rangePixels: Int = 224,
    /** Keeps flying after its first hit. */
    val pierce: Boolean = false,
    /** True: flies along the exact aim, at any angle, and hits whatever its body touches. False (the default): one of the eight directions, tile by tile. */
    val freeAngle: Boolean = false
)

/** One hit area, live from [fromTick] to [toTick] inclusive; both must lie inside the skill's active window. */
@Serializable
data class SkillHitbox(
    val fromTick: Int,
    val toTick: Int,
    val shape: HitboxShape = HitboxShape.ARC,
    /** Offset of the shape's origin from the character's centre, along the facing and to its right. */
    val forwardPixels: Int = 0,
    val sidePixels: Int = 0,
    /** CIRCLE and ARC. */
    val radiusPixels: Int = 0,
    /** ARC: full angle of the slice, 1..360. */
    val arcDegrees: Int = 0,
    /** RECT. */
    val lengthPixels: Int = 0,
    val widthPixels: Int = 0,
    /** A skill hits a given target at most once per hit group, however many ticks or hitboxes overlap it. */
    val hitGroup: Int = 0,
    /** Most targets this hitbox may hit; 0 = no limit. */
    val maxTargets: Int = 0,
    val hit: SkillHit = SkillHit(),
    val projectile: SkillProjectile? = null,
    /**
     * True: the shape is centred on the point the player aimed at (a tap's auto-target or a drag), not in front of the character,
     * so an area skill lands where it is aimed. [maxRangePixels] limits how far from the character the point may be (0 = no limit).
     * With no aim point (a keyboard press with no cursor) the shape sits in front as usual.
     */
    val groundTarget: Boolean = false,
    val maxRangePixels: Int = 0
)

/** Ticks [fromTick]..[toTick] (inclusive) during which the character has [type] armor. */
@Serializable
data class SkillArmorWindow(
    val fromTick: Int,
    val toTick: Int,
    val type: SkillArmorType = SkillArmorType.SUPER_ARMOR
)

/** A lunge or dash: the character is displaced by this much, spread evenly over [fromTick]..[toTick] (inclusive). */
@Serializable
data class SkillMove(
    val fromTick: Int,
    val toTick: Int,
    val forwardPixels: Int,
    val sidePixels: Int = 0
)

/** Makes the skill a held guard: hits from inside the arc are reduced and cost [blockCosts] each. */
@Serializable
data class SkillGuard(
    /** Full angle of the guarded arc in front of the character, 1..360. */
    val arcDegrees: Int = 120,
    /** Longest the guard can be held; 0 = until released or the cost cannot be paid. */
    val maxHoldTicks: Int = 0,
    /** Paid from these vitals for every hit blocked. */
    val blockCosts: Map<String, Int> = emptyMap(),
    /** Percent of a blocked hit's damage that is removed, 1..100. */
    val damageReductionPercent: Int = 100
)

/**
 * One action-combat skill (`action_skills/action_skill_NNN.json`).
 *
 * The timeline is [startupTicks] of wind-up, [activeTicks] in which hitboxes may be live, then [recoveryTicks]. A combo
 * edge or a dodge may interrupt it from [cancelFromTick] on.
 */
@Serializable
data class ActionSkillDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val iconId: Int = 1,
    val levelReq: Int = 1,
    val startupTicks: Int = 2,
    val activeTicks: Int = 2,
    val recoveryTicks: Int = 6,
    /** First tick at which another skill may interrupt this one; null = when recovery starts. */
    val cancelFromTick: Int? = null,
    /** Paid from these vitals when the skill starts. */
    val costs: Map<String, Int> = emptyMap(),
    val cooldownTicks: Int = 0,
    /** True: the facing is fixed when the skill starts. False: it follows the player's aim throughout. */
    val aimLock: Boolean = true,
    val hitboxes: List<SkillHitbox> = emptyList(),
    val armor: List<SkillArmorWindow> = emptyList(),
    val moves: List<SkillMove> = emptyList(),
    val guard: SkillGuard? = null,
    val animationId: Int = 0,
    val soundId: String = "",
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
) {
    val totalTicks: Int get() = startupTicks + activeTicks + recoveryTicks
    /** First tick of the active window. */
    val activeFromTick: Int get() = startupTicks
    /** First tick of recovery (one past the last active tick). */
    val recoveryFromTick: Int get() = startupTicks + activeTicks
    val effectiveCancelFromTick: Int get() = cancelFromTick ?: recoveryFromTick
    /** No cooldown and no cost: nothing limits how often it can be used. */
    val isFree: Boolean get() = cooldownTicks <= 0 && costs.values.none { it > 0 }
}

/** A skill started from neutral (no skill running, or after one has ended) by [input]. */
@Serializable
data class ComboEntry(
    val input: String,
    val skillId: Int
)

/**
 * A chain or cancel: while [fromSkillId] is running, [input] starts [toSkillId]. The input is accepted for
 * [windowTicks] ticks counted from the from-skill's cancel point, which may run past its end by up to
 * [ActionCombatLimits.MAX_COMBO_GRACE_TICKS].
 */
@Serializable
data class ComboEdge(
    val fromSkillId: Int,
    val input: String,
    val toSkillId: Int,
    val windowTicks: Int = 8,
    /** Only when the from-skill has hit something (a "flow" skill or finisher). */
    val requiresHit: Boolean = false
)

/**
 * A weapon set (`weapon_sets/weapon_set_NNN.json`): the skills a weapon gives and the combo graph over them.
 * Nodes are [skillIds]; [entries] lead in from neutral and [edges] connect skills.
 */
@Serializable
data class WeaponSetDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val slot: WeaponSetSlot = WeaponSetSlot.MAIN,
    /** Classes that may use the set; empty = any. */
    val classIds: Set<Int> = emptySet(),
    val levelReq: Int = 1,
    /** How long a press made too early is remembered and fired on the first legal tick. */
    val inputBufferTicks: Int = 6,
    val skillIds: List<Int> = emptyList(),
    val entries: List<ComboEntry> = emptyList(),
    val edges: List<ComboEdge> = emptyList(),
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Input tokens for combo graphs. A token is one key with optional modifiers and an optional movement direction,
 * joined by `+` in a fixed order: `SHIFT`, `CTRL`, a direction, then the key. `LMB`, `SHIFT+RMB` and
 * `SHIFT+BACK+SPACE` are tokens; `RMB+SHIFT` is not (wrong order), so every chord has exactly one spelling.
 */
object InputTokens {
    val MODIFIERS: List<String> = listOf("SHIFT", "CTRL")
    /** Movement held with the key, relative to the character's facing. */
    val DIRECTIONS: List<String> = listOf("FORWARD", "BACK", "LEFT", "RIGHT")
    val KEYS: List<String> = listOf("LMB", "RMB", "SPACE", "Q", "E", "F", "TAB")

    /** The one accepted spelling of [text] (upper case, parts in order), or null when it is not a token. */
    fun canonical(text: String): String? {
        val parts = text.trim().uppercase().split('+').map { it.trim() }
        if (parts.isEmpty() || parts.any { it.isEmpty() } || parts.toSet().size != parts.size) return null
        val key = parts.filter { it in KEYS }
        val direction = parts.filter { it in DIRECTIONS }
        if (key.size != 1 || direction.size > 1) return null
        if (parts.any { it !in KEYS && it !in DIRECTIONS && it !in MODIFIERS }) return null
        return (MODIFIERS.filter { it in parts } + direction + key).joinToString("+")
    }

    fun isValid(text: String): Boolean = canonical(text) == text
}
