package com.morgana.domain.combat

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEdge
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.content.WeaponSetSlot
import com.morgana.domain.spatial.Direction

/**
 * One character's action-combat state: the skill in progress, what it has hit, a buffered key press and cooldowns. It lives in
 * `WorldSimulation.actionStates` (hashed for replays) and changes only through [ActionCombatModule].
 */
data class ActionState(
    /** The weapon set in use, or 0 until the first input picks one. */
    val setId: Int = 0,
    /** The skill in progress (or whose combo window is still open), 0 when idle. */
    val skillId: Int = 0,
    /** The tick the skill's timeline began; hit-stop and holds push it forward. */
    val start: Long = 0L,
    /** The last skill tick whose hitboxes have been tested. */
    val done: Int = -1,
    /** Whether the skill has connected (needed by `requiresHit` edges). */
    val hit: Boolean = false,
    /** `group:targetId` pairs already hit, so a target is hit once per hit group. */
    val hits: Set<String> = emptySet(),
    val groupCounts: Map<Int, Int> = emptyMap(),
    /** Ticks of hit-stop still to wait; the timeline is frozen while this is above 0. */
    val freeze: Int = 0,
    /** Unit vector the skill is aimed along, fixed when it starts. */
    val fx: Double = 0.0,
    val fy: Double = 1.0,
    val bufferedToken: String? = null,
    val bufferedAt: Long = 0L,
    /** First tick each skill may start again. */
    val cooldowns: Map<Int, Long> = emptyMap(),
    /** A guard skill holds its timeline until this tick unless released. */
    val holdUntil: Long = 0L,
    /** The last skill tick whose movement has been applied. */
    val movedTo: Int = -1,
    /** The point the skill was aimed at (world pixels), when the press carried one; used by ground-targeted hitboxes. */
    val aimX: Double = 0.0,
    val aimY: Double = 0.0,
    val aimSet: Boolean = false
) {
    val idle: Boolean get() = skillId == 0
}

/** The pure combo-graph and geometry rules of action combat: no simulation state, so every rule is unit-tested. */
object ActionCombatRules {
    /** The default size of a body, a disc this wide in pixels; a game sets `tuning.actionTargetRadiusPixels`. */
    const val TARGET_RADIUS = com.morgana.domain.game.GameplayTuning.DEFAULT_ACTION_TARGET_RADIUS_PIXELS
    const val DEFAULT_WINDOW = 8

    /** Ticks after a skill ends that its combo chain stays alive (`ActionCombatLimits.MAX_COMBO_GRACE_TICKS`). */
    const val COMBO_GRACE = 20

    /** The first MAIN weapon set (by id) whose class and level requirements fit, or null. */
    fun chooseSet(sets: Map<Int, WeaponSetDefinition>, classId: Int, level: Int): WeaponSetDefinition? =
        sets.values.sortedBy { it.id }.firstOrNull { it.slot == WeaponSetSlot.MAIN && it.levelReq <= level && (it.classIds.isEmpty() || classId in it.classIds) }

    /**
     * The edge out of [from] for [token]. With [hit] null any edge counts (to decide whether to buffer an early press); otherwise
     * an edge that needs a hit is taken only when the skill connected, and is preferred when it did.
     */
    fun findEdge(set: WeaponSetDefinition, from: Int, token: String, hit: Boolean?): ComboEdge? {
        var plain: ComboEdge? = null
        for (e in set.edges) {
            if (e.fromSkillId == from && e.input == token) {
                if (e.requiresHit) {
                    if (hit == null || hit) return e
                } else plain = e
            }
        }
        return plain
    }

    fun findEntry(set: WeaponSetDefinition, token: String): ComboEntry? = set.entries.firstOrNull { it.input == token }

    fun skillLength(skill: ActionSkillDefinition): Int = skill.startupTicks + skill.activeTicks + skill.recoveryTicks

    fun cancelTick(skill: ActionSkillDefinition): Int = skill.cancelFromTick ?: (skill.startupTicks + skill.activeTicks)

    /** The unit vector of a facing direction (diagonals are normalised). */
    fun facingVector(direction: Direction): Pair<Double, Double> {
        val len = StrictMath.sqrt((direction.dx * direction.dx + direction.dy * direction.dy).toDouble())
        return direction.dx / len to direction.dy / len
    }

    /** The unit vector from ([fromX], [fromY]) to ([toX], [toY]); null when they are the same point. */
    fun aimVector(fromX: Double, fromY: Double, toX: Double, toY: Double): Pair<Double, Double>? {
        val dx = toX - fromX
        val dy = toY - fromY
        val len = StrictMath.sqrt(dx * dx + dy * dy)
        return if (len < 1e-6) null else dx / len to dy / len
    }

    /** The eight-way facing nearest to a vector, for the character's sprite. */
    fun nearestDirection(fx: Double, fy: Double): Direction =
        Direction.entries.maxByOrNull { d ->
            val len = StrictMath.sqrt((d.dx * d.dx + d.dy * d.dy).toDouble())
            (d.dx * fx + d.dy * fy) / len
        } ?: Direction.DOWN

    /**
     * Whether a body centred at ([tx], [ty]) is inside [hb] for a character centred at ([cx], [cy]) facing ([fx], [fy]).
     * Integer-friendly arithmetic with `StrictMath` for the one cosine, so the answer is the same on every machine.
     */
    fun inShape(hb: SkillHitbox, cx: Double, cy: Double, fx: Double, fy: Double, tx: Double, ty: Double, targetRadius: Int = TARGET_RADIUS): Boolean {
        val rx = -fy
        val ry = fx
        val ox = cx + fx * hb.forwardPixels + rx * hb.sidePixels
        val oy = cy + fy * hb.forwardPixels + ry * hb.sidePixels
        val dx = tx - ox
        val dy = ty - oy
        if (hb.shape == HitboxShape.RECT) {
            val l = dx * fx + dy * fy
            val s = dx * rx + dy * ry
            return l >= -targetRadius && l <= hb.lengthPixels + targetRadius && StrictMath.abs(s) <= hb.widthPixels / 2.0 + targetRadius
        }
        val reach = (hb.radiusPixels + targetRadius).toDouble()
        val d2 = dx * dx + dy * dy
        if (d2 > reach * reach) return false
        if (hb.shape == HitboxShape.CIRCLE || hb.arcDegrees >= 360 || d2 == 0.0) return true
        val dist = StrictMath.sqrt(d2)
        return dx * fx + dy * fy >= dist * StrictMath.cos(StrictMath.toRadians(hb.arcDegrees / 2.0))
    }

    /** How many tiles around the character a hitbox can reach, for picking candidates. */
    fun reachTiles(hb: SkillHitbox, tileSize: Int, targetRadius: Int = TARGET_RADIUS): Int {
        val reach = maxOf(hb.radiusPixels, hb.lengthPixels, hb.widthPixels) + StrictMath.abs(hb.forwardPixels) + StrictMath.abs(hb.sidePixels) + targetRadius
        return reach / tileSize + 1
    }

    /** True while [skill] has an armour window of [type] covering skill tick [t]. */
    fun hasArmor(skill: ActionSkillDefinition, t: Int, type: com.morgana.domain.content.SkillArmorType): Boolean =
        skill.armor.any { it.type == type && t >= it.fromTick && t <= it.toTick }

    /** Whether a hit arriving from ([sx], [sy]) lies inside a guard's arc for a character at ([cx], [cy]) facing ([fx], [fy]). */
    fun inGuardArc(arcDegrees: Int, cx: Double, cy: Double, fx: Double, fy: Double, sx: Double, sy: Double): Boolean {
        val dx = sx - cx
        val dy = sy - cy
        val dist = StrictMath.sqrt(dx * dx + dy * dy)
        if (dist < 1e-6 || arcDegrees >= 360) return true
        return dx * fx + dy * fy >= dist * StrictMath.cos(StrictMath.toRadians(arcDegrees / 2.0))
    }
}
