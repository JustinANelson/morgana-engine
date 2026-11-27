package com.morgana.client.input

import kotlin.math.hypot

/** One enemy the player could aim at: where it stands (map pixels, y down) and what makes it worth hitting. */
data class TargetCandidate(
    val id: String,
    val x: Float,
    val y: Float,
    val boss: Boolean = false,
    /** Life left, 0..1. */
    val hpFraction: Float = 1f,
    /** True while it is fighting the local player. */
    val targetsMe: Boolean = false
)

/**
 * How an attack or skill picks what to aim at when the player gives no cursor (a phone, or a button tap), kept pure so it is
 * unit-tested. Three layers: a locked target always wins while it is within reach; otherwise the most valuable enemy in range
 * (bosses, enemies attacking you and ones about to die count for more, nearness most of all); otherwise nothing, and the attack
 * goes the way the character faces.
 */
object CombatTargeting {
    /** How far (map pixels) an automatic pick reaches: seven tiles at the usual 32 px. */
    const val AUTO_RANGE = 224f

    /** A locked target is kept while it is within this multiple of [AUTO_RANGE], so a step back does not drop it. */
    const val LOCK_KEEP = 1.6f

    fun distance(px: Float, py: Float, c: TargetCandidate): Float = hypot(c.x - px, c.y - py)

    /** How worth hitting [c] is from ([px], [py]): higher is better. */
    fun value(px: Float, py: Float, c: TargetCandidate, range: Float = AUTO_RANGE): Float {
        val near = 100f - (distance(px, py, c) / range).coerceIn(0f, 1f) * 60f
        return near + (if (c.boss) 80f else 0f) + (if (c.targetsMe) 25f else 0f) + (if (c.hpFraction < 0.35f) 15f else 0f)
    }

    /** The enemy an attack should aim at: the locked one while reachable, else the most valuable in range, else null. */
    fun pickAuto(px: Float, py: Float, candidates: List<TargetCandidate>, lockedId: String?, range: Float = AUTO_RANGE): TargetCandidate? {
        if (lockedId != null) {
            val locked = candidates.firstOrNull { it.id == lockedId }
            if (locked != null && distance(px, py, locked) <= range * LOCK_KEEP) return locked
        }
        return candidates.filter { distance(px, py, it) <= range }.maxByOrNull { value(px, py, it, range) }
    }

    /** The next target to lock when the player cycles: enemies near to far (within reach), the one after [currentId], wrapping round. */
    fun cycle(px: Float, py: Float, candidates: List<TargetCandidate>, currentId: String?, range: Float = AUTO_RANGE, wrapToNone: Boolean = false): TargetCandidate? {
        val inReach = candidates.filter { distance(px, py, it) <= range * LOCK_KEEP }
            .sortedWith(compareBy<TargetCandidate> { distance(px, py, it) }.thenBy { it.id })
        if (inReach.isEmpty()) return null
        val at = inReach.indexOfFirst { it.id == currentId }
        if (wrapToNone && at == inReach.lastIndex) return null
        return inReach[(at + 1) % inReach.size]
    }

    /**
     * Where an area skill should land when the player gives no aim: the centre of the largest group of enemies that fit within
     * [radius] of one another, reachable within [reach] (nearer groups and groups with a boss win ties). Null when no enemy is in reach.
     */
    fun clusterCenter(px: Float, py: Float, candidates: List<TargetCandidate>, reach: Float, radius: Float): Pair<Float, Float>? {
        val near = candidates.filter { distance(px, py, it) <= reach + radius * 0.5f }
        if (near.isEmpty()) return null
        var bestScore = Float.NEGATIVE_INFINITY
        var best: Pair<Float, Float>? = null
        for (seed in near) {
            val members = near.filter { hypot(it.x - seed.x, it.y - seed.y) <= radius }
            val cx = members.map { it.x }.average().toFloat()
            val cy = members.map { it.y }.average().toFloat()
            val score = members.size * 100f + (if (members.any { it.boss }) 60f else 0f) - hypot(cx - px, cy - py) / reach.coerceAtLeast(1f) * 20f
            if (score > bestScore) { bestScore = score; best = cx to cy }
        }
        return best
    }

    /** The enemy nearest the map point ([x], [y]) within [radius] pixels (a tap on it), or null. */
    fun nearestTo(x: Float, y: Float, candidates: List<TargetCandidate>, radius: Float = 40f): TargetCandidate? =
        candidates.filter { hypot(it.x - x, it.y - y) <= radius }.minByOrNull { hypot(it.x - x, it.y - y) }

    /** The aim point for a drag of ([dx], [dy]) screen pixels (y up) from a player at ([px], [py]) in map pixels (y down), [reach] pixels out. */
    fun dragAim(px: Float, py: Float, dx: Float, dy: Float, reach: Float = 160f): Pair<Float, Float>? {
        val len = hypot(dx, dy)
        if (len < 1e-3f) return null
        return (px + dx / len * reach) to (py - dy / len * reach)
    }
}
