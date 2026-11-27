package com.morgana.domain.validation

import com.morgana.domain.content.ActionCombatLimits
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.InputTokens
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.VitalRole

/** Reports one issue: collection, document id, JSON pointer, code, message. */
internal typealias IssueSink = (ContentCollection, Int, String, String, String) -> Unit

/**
 * Rules for action-combat content: skill timelines (windows inside their phase, shapes with real sizes, costs the game
 * can pay) and weapon-set combo graphs (known skills, unambiguous inputs, no unreachable node, no chain that can be
 * repeated forever for free). Called by [ContentValidator]; every problem carries a pointer to the offending field.
 */
internal class ActionCombatValidation(
    private val cat: ContentCatalog,
    private val game: GameDefinition,
    private val error: IssueSink,
    private val warn: IssueSink
) {
    private val L = ActionCombatLimits

    private fun costs(col: ContentCollection, id: Int, pointer: String, costs: Map<String, Int>) {
        val life = game.vitals.firstOrNull { it.role == VitalRole.LIFE }?.id
        for ((vital, amount) in costs) {
            if (game.vitals.none { it.id == vital }) error(col, id, "$pointer/$vital", "unknown_vital", "Unknown vital '$vital'")
            else if (vital == life) error(col, id, "$pointer/$vital", "invalid_cost", "A cost may not name the life vital")
            if (amount < 0) error(col, id, "$pointer/$vital", "out_of_range", "Cost must be >= 0 (got $amount)")
        }
    }

    private fun range(col: ContentCollection, id: Int, pointer: String, value: Int, lo: Int, hi: Int): Boolean {
        if (value in lo..hi) return true
        error(col, id, pointer, "out_of_range", "${pointer.substringAfterLast('/')} must be $lo..$hi (got $value)")
        return false
    }

    /** Checks `fromTick <= toTick` inside `lo..hi`, and that the windows of one list do not overlap. */
    private fun windows(col: ContentCollection, id: Int, pointer: String, spans: List<Pair<Int, Int>>, lo: Int, hi: Int, phase: String) {
        if (spans.size > L.MAX_WINDOWS) error(col, id, pointer, "too_many", "At most ${L.MAX_WINDOWS} entries (got ${spans.size})")
        spans.forEachIndexed { i, (from, to) ->
            if (from > to) error(col, id, "$pointer/$i/toTick", "inverted_window", "toTick $to is before fromTick $from")
            else if (from < lo || to > hi) error(col, id, "$pointer/$i/fromTick", "outside_skill", "Ticks $from..$to are outside $phase ($lo..$hi)")
        }
        val ordered = spans.withIndex().filter { it.value.first <= it.value.second }.sortedBy { it.value.first }
        for ((a, b) in ordered.zipWithNext()) {
            if (b.value.first <= a.value.second) {
                error(col, id, "$pointer/${b.index}/fromTick", "overlapping_windows",
                    "Ticks ${b.value.first}..${b.value.second} overlap entry ${a.index} (${a.value.first}..${a.value.second})")
            }
        }
    }

    fun skill(s: ActionSkillDefinition) {
        val col = ContentCollection.ACTION_SKILLS
        val id = s.id
        range(col, id, "/levelReq", s.levelReq, 1, game.progression.maxLevel)
        val phases = range(col, id, "/startupTicks", s.startupTicks, 0, L.MAX_PHASE_TICKS) and
            range(col, id, "/activeTicks", s.activeTicks, 0, L.MAX_PHASE_TICKS) and
            range(col, id, "/recoveryTicks", s.recoveryTicks, 0, L.MAX_PHASE_TICKS)
        range(col, id, "/cooldownTicks", s.cooldownTicks, 0, L.MAX_COOLDOWN_TICKS)
        costs(col, id, "/costs", s.costs)
        if (s.animationId != 0 && s.animationId !in cat.animations) {
            error(col, id, "/animationId", "missing_reference", "References animations id ${s.animationId} which does not exist")
        }
        if (!phases) return // the timeline checks below need sane phase lengths
        val total = s.totalTicks
        if (total < 1) {
            error(col, id, "/activeTicks", "empty_skill", "A skill must last at least one tick (startup + active + recovery is 0)")
            return
        }
        if (total > L.MAX_SKILL_TICKS) error(col, id, "/recoveryTicks", "out_of_range", "A skill may last at most ${L.MAX_SKILL_TICKS} ticks (got $total)")
        s.cancelFromTick?.let { range(col, id, "/cancelFromTick", it, 0, total) }

        if (s.hitboxes.size > L.MAX_HITBOXES) error(col, id, "/hitboxes", "too_many", "At most ${L.MAX_HITBOXES} hitboxes (got ${s.hitboxes.size})")
        val activeLast = s.recoveryFromTick - 1
        s.hitboxes.forEachIndexed { i, h ->
            val p = "/hitboxes/$i"
            if (h.fromTick > h.toTick) error(col, id, "$p/toTick", "inverted_window", "toTick ${h.toTick} is before fromTick ${h.fromTick}")
            else if (s.activeTicks == 0) error(col, id, "$p/fromTick", "outside_active_window", "The skill has no active ticks, so a hitbox can never be live")
            else if (h.fromTick < s.activeFromTick || h.toTick > activeLast) {
                error(col, id, "$p/fromTick", "outside_active_window", "Ticks ${h.fromTick}..${h.toTick} are outside the active window (${s.activeFromTick}..$activeLast)")
            }
            hitbox(col, id, p, h)
        }
        windows(col, id, "/armor", s.armor.map { it.fromTick to it.toTick }, 0, total - 1, "the skill")
        windows(col, id, "/moves", s.moves.map { it.fromTick to it.toTick }, 0, total - 1, "the skill")
        s.moves.forEachIndexed { i, m ->
            range(col, id, "/moves/$i/forwardPixels", m.forwardPixels, -L.MAX_REACH_PIXELS, L.MAX_REACH_PIXELS)
            range(col, id, "/moves/$i/sidePixels", m.sidePixels, -L.MAX_REACH_PIXELS, L.MAX_REACH_PIXELS)
        }
        s.guard?.let { g ->
            range(col, id, "/guard/arcDegrees", g.arcDegrees, 1, 360)
            range(col, id, "/guard/maxHoldTicks", g.maxHoldTicks, 0, L.MAX_GUARD_HOLD_TICKS)
            range(col, id, "/guard/damageReductionPercent", g.damageReductionPercent, 1, 100)
            costs(col, id, "/guard/blockCosts", g.blockCosts)
        }
        if (s.hitboxes.isEmpty() && s.armor.isEmpty() && s.moves.isEmpty() && s.guard == null) {
            warn(col, id, "/hitboxes", "no_effect", "The skill has no hitbox, armor, movement or guard, so using it does nothing")
        }
    }

    private fun hitbox(col: ContentCollection, id: Int, p: String, h: SkillHitbox) {
        range(col, id, "$p/forwardPixels", h.forwardPixels, -L.MAX_REACH_PIXELS, L.MAX_REACH_PIXELS)
        range(col, id, "$p/sidePixels", h.sidePixels, -L.MAX_REACH_PIXELS, L.MAX_REACH_PIXELS)
        range(col, id, "$p/hitGroup", h.hitGroup, 0, L.MAX_HIT_GROUP)
        range(col, id, "$p/maxTargets", h.maxTargets, 0, L.MAX_TARGETS)
        fun unused(field: String, value: Int) {
            if (value != 0) warn(col, id, "$p/$field", "unused_field", "$field is ignored by a ${h.shape} hitbox")
        }
        when (h.shape) {
            HitboxShape.CIRCLE -> {
                range(col, id, "$p/radiusPixels", h.radiusPixels, 1, L.MAX_REACH_PIXELS)
                unused("arcDegrees", h.arcDegrees); unused("lengthPixels", h.lengthPixels); unused("widthPixels", h.widthPixels)
            }
            HitboxShape.ARC -> {
                range(col, id, "$p/radiusPixels", h.radiusPixels, 1, L.MAX_REACH_PIXELS)
                range(col, id, "$p/arcDegrees", h.arcDegrees, 1, 360)
                unused("lengthPixels", h.lengthPixels); unused("widthPixels", h.widthPixels)
            }
            HitboxShape.RECT -> {
                range(col, id, "$p/lengthPixels", h.lengthPixels, 1, L.MAX_REACH_PIXELS)
                range(col, id, "$p/widthPixels", h.widthPixels, 1, L.MAX_REACH_PIXELS)
                unused("radiusPixels", h.radiusPixels); unused("arcDegrees", h.arcDegrees)
            }
        }
        val hit = h.hit
        range(col, id, "$p/hit/damagePercent", hit.damagePercent, 0, L.MAX_DAMAGE_PERCENT)
        range(col, id, "$p/hit/knockbackPixels", hit.knockbackPixels, 0, L.MAX_KNOCKBACK_PIXELS)
        range(col, id, "$p/hit/hitStopTicks", hit.hitStopTicks, 0, L.MAX_HIT_STOP_TICKS)
        if (hit.reaction == HitReaction.NONE) {
            if (hit.reactionTicks != 0) warn(col, id, "$p/hit/reactionTicks", "unused_field", "reactionTicks is ignored when reaction is NONE")
        } else {
            range(col, id, "$p/hit/reactionTicks", hit.reactionTicks, 1, L.MAX_REACTION_TICKS)
        }
        if (hit.statusEffectType == null) {
            if (hit.statusMagnitude != 0 || hit.statusDurationTicks != 0) {
                warn(col, id, "$p/hit/statusEffectType", "unused_field", "statusMagnitude and statusDurationTicks are ignored without a statusEffectType")
            }
        } else if (hit.statusDurationTicks < 1) {
            error(col, id, "$p/hit/statusDurationTicks", "out_of_range", "A status effect needs statusDurationTicks >= 1")
        }
        if (hit.damagePercent == 0 && hit.reaction == HitReaction.NONE && hit.statusEffectType == null && hit.knockbackPixels == 0) {
            warn(col, id, "$p/hit", "no_effect", "The hit does no damage and applies no reaction, status or knockback")
        }
        h.projectile?.let { pr ->
            if (h.shape != HitboxShape.CIRCLE) error(col, id, "$p/shape", "invalid_projectile", "A projectile hitbox must be a CIRCLE (got ${h.shape})")
            if (h.fromTick != h.toTick) warn(col, id, "$p/toTick", "unused_field", "A projectile spawns on fromTick; toTick is ignored")
            range(col, id, "$p/projectile/speedPixelsPerTick", pr.speedPixelsPerTick, 1, L.MAX_PROJECTILE_SPEED)
            range(col, id, "$p/projectile/rangePixels", pr.rangePixels, 1, L.MAX_PROJECTILE_RANGE)
            if (pr.spriteId < 0) error(col, id, "$p/projectile/spriteId", "out_of_range", "spriteId must be >= 0")
        }
    }

    fun weaponSet(w: WeaponSetDefinition) {
        val col = ContentCollection.WEAPON_SETS
        val id = w.id
        range(col, id, "/levelReq", w.levelReq, 1, game.progression.maxLevel)
        range(col, id, "/inputBufferTicks", w.inputBufferTicks, 0, L.MAX_INPUT_BUFFER_TICKS)
        for (classId in w.classIds) {
            if (classId !in cat.classes) error(col, id, "/classIds", "missing_reference", "References classes id $classId which does not exist")
        }

        if (w.skillIds.isEmpty()) error(col, id, "/skillIds", "empty_set", "A weapon set needs at least one skill")
        if (w.skillIds.size > L.MAX_SET_SKILLS) error(col, id, "/skillIds", "too_many", "At most ${L.MAX_SET_SKILLS} skills (got ${w.skillIds.size})")
        val nodes = LinkedHashSet<Int>()
        w.skillIds.forEachIndexed { i, skillId ->
            if (!nodes.add(skillId)) error(col, id, "/skillIds/$i", "duplicate_skill", "Skill $skillId is listed twice")
            else if (skillId !in cat.actionSkills) error(col, id, "/skillIds/$i", "missing_reference", "References action_skills id $skillId which does not exist")
        }

        fun input(pointer: String, text: String) {
            if (InputTokens.isValid(text)) return
            val fix = InputTokens.canonical(text)
            error(col, id, pointer, "invalid_input",
                if (fix != null) "Input '$text' must be written '$fix'"
                else "Input '$text' is not a token: use one of ${InputTokens.KEYS}, optionally after ${InputTokens.MODIFIERS} and one of ${InputTokens.DIRECTIONS}, joined by '+'")
        }
        fun member(pointer: String, skillId: Int): Boolean {
            if (skillId in nodes) return true
            error(col, id, pointer, "not_in_set", "Skill $skillId is not one of this set's skillIds")
            return false
        }

        if (w.entries.isEmpty() && w.skillIds.isNotEmpty()) error(col, id, "/entries", "no_entry", "No input starts a skill from neutral, so the set can never be used")
        val entryInputs = HashMap<String, Int>()
        w.entries.forEachIndexed { i, e ->
            input("/entries/$i/input", e.input)
            member("/entries/$i/skillId", e.skillId)
            entryInputs.put(e.input, i)?.let { first ->
                error(col, id, "/entries/$i/input", "ambiguous_input", "Input '${e.input}' already starts a skill in entry $first")
            }
        }

        if (w.edges.size > L.MAX_EDGES) error(col, id, "/edges", "too_many", "At most ${L.MAX_EDGES} edges (got ${w.edges.size})")
        val edgeKeys = HashMap<Triple<Int, String, Boolean>, Int>()
        val out = HashMap<Int, MutableList<Int>>() // skill -> indices of its valid outgoing edges
        w.edges.forEachIndexed { i, e ->
            val p = "/edges/$i"
            input("$p/input", e.input)
            val known = member("$p/fromSkillId", e.fromSkillId) and member("$p/toSkillId", e.toSkillId)
            edgeKeys.put(Triple(e.fromSkillId, e.input, e.requiresHit), i)?.let { first ->
                error(col, id, "$p/input", "ambiguous_input", "Skill ${e.fromSkillId} already chains on '${e.input}' in edge $first")
            }
            val from = cat.actionSkills[e.fromSkillId]
            if (e.windowTicks < 1) {
                error(col, id, "$p/windowTicks", "out_of_range", "windowTicks must be >= 1 (got ${e.windowTicks})")
            } else if (from != null) {
                val longest = (from.totalTicks - from.effectiveCancelFromTick).coerceAtLeast(0) + L.MAX_COMBO_GRACE_TICKS
                if (e.windowTicks > longest) {
                    error(col, id, "$p/windowTicks", "window_too_long",
                        "'${from.name}' can be cancelled from tick ${from.effectiveCancelFromTick} of ${from.totalTicks}, so a window may be at most $longest ticks (got ${e.windowTicks})")
                }
            }
            if (e.requiresHit && from != null && from.hitboxes.isEmpty()) {
                error(col, id, "$p/requiresHit", "never_hits", "'${from.name}' has no hitbox, so an edge that requires a hit can never be taken")
            }
            if (known) out.getOrPut(e.fromSkillId) { mutableListOf() } += i
        }

        // Every skill must be reachable from neutral.
        val reached = HashSet<Int>()
        // The skill an empowered character uses in place of a plain one is reached through the momentum meter, not an entry.
        if (w.empowerSkillId != 0 && w.empoweredSkillId != 0) {
            if (w.entries.none { it.skillId == w.empowerSkillId }) {
                error(col, id, "/empowerSkillId", "unknown_reference", "empowerSkillId ${w.empowerSkillId} is not the skill of any entry")
            }
            if (w.empoweredSkillId !in cat.actionSkills) {
                error(col, id, "/empoweredSkillId", "missing_reference", "empoweredSkillId ${w.empoweredSkillId} is not an action skill")
            }
        }
        val queue = ArrayDeque((w.entries.map { it.skillId } + listOf(w.empoweredSkillId).filter { w.empowerSkillId != 0 }).filter { it in nodes })
        while (queue.isNotEmpty()) {
            val skillId = queue.removeFirst()
            if (!reached.add(skillId)) continue
            out[skillId]?.forEach { queue += w.edges[it].toSkillId }
        }
        w.skillIds.forEachIndexed { i, skillId ->
            if (skillId in nodes && skillId !in reached && w.skillIds.indexOf(skillId) == i) {
                error(col, id, "/skillIds/$i", "unreachable_skill", "No entry or chain of edges leads to skill $skillId (${cat.actionSkills[skillId]?.name ?: "unknown"})")
            }
        }

        freeCycles(w, out)
    }

    /**
     * A cycle of edges whose skills all have no cooldown and no cost can be repeated forever without returning to
     * neutral. One error per such cycle group, on its first edge.
     */
    private fun freeCycles(w: WeaponSetDefinition, out: Map<Int, List<Int>>) {
        val free = w.skillIds.filter { cat.actionSkills[it]?.isFree == true }.toSet()
        fun next(skillId: Int) = out[skillId].orEmpty().filter { w.edges[it].toSkillId in free }

        // Tarjan's strongly connected components over the free skills.
        var counter = 0
        val index = HashMap<Int, Int>()
        val low = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        val onStack = HashSet<Int>()
        val component = HashMap<Int, Int>()
        fun visit(v: Int) {
            index[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack += v
            for (e in next(v)) {
                val t = w.edges[e].toSkillId
                if (t !in index) { visit(t); low[v] = minOf(low.getValue(v), low.getValue(t)) }
                else if (t in onStack) low[v] = minOf(low.getValue(v), index.getValue(t))
            }
            if (low[v] == index[v]) {
                while (true) {
                    val x = stack.removeLast()
                    onStack -= x
                    component[x] = v
                    if (x == v) break
                }
            }
        }
        for (v in w.skillIds) if (v in free && v !in index) visit(v)

        val reported = HashSet<Int>()
        w.edges.forEachIndexed { i, e ->
            val a = component[e.fromSkillId] ?: return@forEachIndexed
            if (e.toSkillId !in free || component[e.toSkillId] != a || !reported.add(a)) return@forEachIndexed
            val names = w.skillIds.distinct().filter { component[it] == a }.joinToString(", ") { cat.actionSkills[it]?.name ?: it.toString() }
            error(ContentCollection.WEAPON_SETS, w.id, "/edges/$i", "free_cycle",
                "These skills chain into each other forever with no cooldown and no cost: $names. Give one of them a cooldown or a cost, or remove an edge")
        }
    }
}
