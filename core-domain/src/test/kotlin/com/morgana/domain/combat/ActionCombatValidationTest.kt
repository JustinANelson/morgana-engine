package com.morgana.domain.combat

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.ComboEdge
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.InputTokens
import com.morgana.domain.content.SkillArmorType
import com.morgana.domain.content.SkillArmorWindow
import com.morgana.domain.content.SkillGuard
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillMove
import com.morgana.domain.content.SkillProjectile
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.game.BlankGame
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentIssue
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.IssueSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Roadmap phase A1 of White River Online (ADR 0017): action skills and weapon-set combo graphs are content, and a bad
 * timeline or graph is rejected with a JSON pointer to the field at fault.
 */
class ActionCombatValidationTest {

    private val game = BlankGame.definition // vitals: hp (life) and mp

    private fun arc(from: Int, to: Int, hit: SkillHit = SkillHit()) =
        SkillHitbox(fromTick = from, toTick = to, shape = HitboxShape.ARC, radiusPixels = 44, arcDegrees = 110, hit = hit)

    /** A 2 + 2 + 5 tick cut: active on ticks 2..3, cancellable from tick 4. */
    private fun cut(id: Int, name: String = "Cut $id", cooldown: Int = 0, costs: Map<String, Int> = emptyMap()) =
        ActionSkillDefinition(id = id, name = name, startupTicks = 2, activeTicks = 2, recoveryTicks = 5,
            cooldownTicks = cooldown, costs = costs, hitboxes = listOf(arc(2, 3)))

    private fun validate(skills: List<ActionSkillDefinition>, sets: List<WeaponSetDefinition> = emptyList(), classes: List<ClassDefinition> = emptyList()): List<ContentIssue> =
        ContentValidator.validate(
            ContentCatalog(classes = classes.associateBy { it.id }, actionSkills = skills.associateBy { it.id }, weaponSets = sets.associateBy { it.id }),
            game
        ).issues

    private fun List<ContentIssue>.at(code: String, pointer: String): ContentIssue {
        val found = firstOrNull { it.code == code && it.pointer == pointer }
        assertTrue(found != null, "expected $code at $pointer, got: ${map { "${it.code}@${it.pointer}" }}")
        return found!!
    }

    private fun chain(vararg edges: ComboEdge, skills: List<Int> = listOf(1, 2, 3), entries: List<ComboEntry> = listOf(ComboEntry("LMB", 1))) =
        WeaponSetDefinition(id = 1, name = "Sword", skillIds = skills, entries = entries, edges = edges.toList())

    @Test
    fun `a three-cut chain with a finisher, a dodge and a guard is valid`() {
        val finisher = ActionSkillDefinition(
            id = 3, name = "Finisher", startupTicks = 4, activeTicks = 3, recoveryTicks = 9, costs = mapOf("mp" to 10),
            moves = listOf(SkillMove(2, 4, forwardPixels = 20)),
            armor = listOf(SkillArmorWindow(0, 6, SkillArmorType.SUPER_ARMOR)),
            hitboxes = listOf(SkillHitbox(4, 6, HitboxShape.RECT, lengthPixels = 64, widthPixels = 40,
                hit = SkillHit(damagePercent = 160, reaction = HitReaction.KNOCKDOWN, reactionTicks = 30, knockbackPixels = 24, hitStopTicks = 3)))
        )
        val dodge = ActionSkillDefinition(id = 4, name = "Dodge", startupTicks = 0, activeTicks = 6, recoveryTicks = 3, costs = mapOf("mp" to 20),
            cancelFromTick = 6, moves = listOf(SkillMove(0, 5, 72)), armor = listOf(SkillArmorWindow(0, 4, SkillArmorType.INVULNERABLE)))
        val guard = ActionSkillDefinition(id = 5, name = "Guard", startupTicks = 1, activeTicks = 0, recoveryTicks = 2, aimLock = false,
            guard = SkillGuard(arcDegrees = 140, blockCosts = mapOf("mp" to 12), damageReductionPercent = 80))
        val shot = ActionSkillDefinition(id = 6, name = "Shot", startupTicks = 3, activeTicks = 1, recoveryTicks = 6,
            hitboxes = listOf(SkillHitbox(3, 3, HitboxShape.CIRCLE, radiusPixels = 8, projectile = SkillProjectile(spriteId = 1, speedPixelsPerTick = 16, rangePixels = 256),
                hit = SkillHit(statusEffectType = StatusEffectType.SLOW, statusMagnitude = 20, statusDurationTicks = 40))))
        val set = WeaponSetDefinition(
            id = 1, name = "Sword and Shield", classIds = setOf(1), skillIds = listOf(1, 2, 3, 4, 5, 6),
            entries = listOf(ComboEntry("LMB", 1), ComboEntry("SPACE", 4), ComboEntry("Q", 5), ComboEntry("RMB", 6)),
            edges = listOf(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), ComboEdge(1, "SPACE", 4, windowTicks = 10),
                ComboEdge(4, "LMB", 1), ComboEdge(2, "SHIFT+RMB", 3, requiresHit = true))
        )
        // The stub class has no start map; only the action-combat documents are under test here.
        val issues = validate(listOf(cut(1), cut(2), finisher, dodge, guard, shot), listOf(set), listOf(ClassDefinition(id = 1, name = "Warden")))
            .filter { it.collection != ContentCollection.CLASSES }
        assertEquals(emptyList<ContentIssue>(), issues)

        assertEquals(9, cut(1).totalTicks)
        assertEquals(2, cut(1).activeFromTick)
        assertEquals(4, cut(1).recoveryFromTick)
        assertEquals(4, cut(1).effectiveCancelFromTick)
        assertEquals(6, dodge.effectiveCancelFromTick)
        assertTrue(cut(1).isFree && !finisher.isFree && !cut(1, cooldown = 20).isFree)
    }

    @Test
    fun `input tokens have exactly one spelling`() {
        for (ok in listOf("LMB", "RMB", "SPACE", "Q", "E", "F", "TAB", "SHIFT+LMB", "CTRL+Q", "SHIFT+CTRL+F", "BACK+SPACE", "SHIFT+LEFT+RMB")) {
            assertTrue(InputTokens.isValid(ok), ok)
        }
        assertEquals("SHIFT+RMB", InputTokens.canonical("rmb + shift"))
        assertEquals("SHIFT+CTRL+BACK+SPACE", InputTokens.canonical("SPACE+BACK+CTRL+SHIFT"))
        for (bad in listOf("", "SHIFT", "LMB+RMB", "FORWARD+BACK+LMB", "SHIFT+SHIFT+LMB", "X", "LMB+", "Q then F")) {
            assertNull(InputTokens.canonical(bad), bad)
        }

        val issues = validate(listOf(cut(1), cut(2, cooldown = 10)), listOf(WeaponSetDefinition(id = 1, name = "S", skillIds = listOf(1, 2),
            entries = listOf(ComboEntry("lmb", 1)), edges = listOf(ComboEdge(1, "RMB+SHIFT", 2), ComboEdge(2, "DOUBLE_TAP", 1)))))
        assertTrue("'LMB'" in issues.at("invalid_input", "/entries/0/input").message)
        assertTrue("'SHIFT+RMB'" in issues.at("invalid_input", "/edges/0/input").message)
        assertTrue("not a token" in issues.at("invalid_input", "/edges/1/input").message)
    }

    @Test
    fun `a skill timeline is checked field by field`() {
        fun one(s: ActionSkillDefinition) = validate(listOf(s))
        val base = cut(1)

        one(base.copy(startupTicks = -1)).at("out_of_range", "/startupTicks")
        one(base.copy(recoveryTicks = 201)).at("out_of_range", "/recoveryTicks")
        one(ActionSkillDefinition(id = 1, name = "Nothing", startupTicks = 0, activeTicks = 0, recoveryTicks = 0)).at("empty_skill", "/activeTicks")
        one(base.copy(startupTicks = 200, activeTicks = 200, recoveryTicks = 1, hitboxes = emptyList(), guard = SkillGuard())).at("out_of_range", "/recoveryTicks")
        one(base.copy(cancelFromTick = 10)).at("out_of_range", "/cancelFromTick")
        one(base.copy(cooldownTicks = -5)).at("out_of_range", "/cooldownTicks")
        one(base.copy(levelReq = 0)).at("out_of_range", "/levelReq")
        one(base.copy(animationId = 7)).at("missing_reference", "/animationId")
        one(base.copy(name = " ")).at("blank_name", "/name")

        // Costs: a vital the game has, never the life vital, never negative.
        one(base.copy(costs = mapOf("stamina" to 5))).at("unknown_vital", "/costs/stamina")
        one(base.copy(costs = mapOf("hp" to 5))).at("invalid_cost", "/costs/hp")
        one(base.copy(costs = mapOf("mp" to -1))).at("out_of_range", "/costs/mp")

        // Hitboxes live only inside the active window (ticks 2..3 here).
        one(base.copy(hitboxes = listOf(arc(1, 3)))).at("outside_active_window", "/hitboxes/0/fromTick")
        one(base.copy(hitboxes = listOf(arc(2, 3), arc(3, 4)))).at("outside_active_window", "/hitboxes/1/fromTick")
        one(base.copy(hitboxes = listOf(arc(3, 2)))).at("inverted_window", "/hitboxes/0/toTick")
        one(base.copy(activeTicks = 0, hitboxes = listOf(arc(2, 2)))).at("outside_active_window", "/hitboxes/0/fromTick")
        one(base.copy(hitboxes = List(17) { arc(2, 3) })).at("too_many", "/hitboxes")

        // Shapes need the sizes they use, and say so about the ones they do not.
        one(base.copy(hitboxes = listOf(arc(2, 3).copy(radiusPixels = 0)))).at("out_of_range", "/hitboxes/0/radiusPixels")
        one(base.copy(hitboxes = listOf(arc(2, 3).copy(arcDegrees = 361)))).at("out_of_range", "/hitboxes/0/arcDegrees")
        one(base.copy(hitboxes = listOf(SkillHitbox(2, 3, HitboxShape.RECT, lengthPixels = 40)))).at("out_of_range", "/hitboxes/0/widthPixels")
        one(base.copy(hitboxes = listOf(SkillHitbox(2, 3, HitboxShape.CIRCLE, radiusPixels = 700)))).at("out_of_range", "/hitboxes/0/radiusPixels")
        val unused = one(base.copy(hitboxes = listOf(SkillHitbox(2, 3, HitboxShape.CIRCLE, radiusPixels = 20, lengthPixels = 40)))).at("unused_field", "/hitboxes/0/lengthPixels")
        assertEquals(IssueSeverity.WARNING, unused.severity)
        one(base.copy(hitboxes = listOf(arc(2, 3).copy(hitGroup = 16)))).at("out_of_range", "/hitboxes/0/hitGroup")

        // What a hit does.
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(damagePercent = 10_001))))).at("out_of_range", "/hitboxes/0/hit/damagePercent")
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(reaction = HitReaction.KNOCKDOWN))))).at("out_of_range", "/hitboxes/0/hit/reactionTicks")
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(reactionTicks = 5))))).at("unused_field", "/hitboxes/0/hit/reactionTicks")
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(hitStopTicks = 11))))).at("out_of_range", "/hitboxes/0/hit/hitStopTicks")
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(statusEffectType = StatusEffectType.STUN))))).at("out_of_range", "/hitboxes/0/hit/statusDurationTicks")
        one(base.copy(hitboxes = listOf(arc(2, 3, SkillHit(damagePercent = 0))))).at("no_effect", "/hitboxes/0/hit")

        // Projectiles are circles with a speed and a range.
        one(base.copy(hitboxes = listOf(arc(2, 2).copy(projectile = SkillProjectile())))).at("invalid_projectile", "/hitboxes/0/shape")
        one(base.copy(hitboxes = listOf(SkillHitbox(2, 2, HitboxShape.CIRCLE, radiusPixels = 8, projectile = SkillProjectile(speedPixelsPerTick = 0)))))
            .at("out_of_range", "/hitboxes/0/projectile/speedPixelsPerTick")

        // Armor and movement windows stay inside the skill (ticks 0..8) and do not overlap one another.
        one(base.copy(armor = listOf(SkillArmorWindow(0, 9)))).at("outside_skill", "/armor/0/fromTick")
        one(base.copy(armor = listOf(SkillArmorWindow(0, 4), SkillArmorWindow(4, 6, SkillArmorType.INVULNERABLE)))).at("overlapping_windows", "/armor/1/fromTick")
        one(base.copy(moves = listOf(SkillMove(5, 2, 10)))).at("inverted_window", "/moves/0/toTick")
        one(base.copy(moves = listOf(SkillMove(0, 2, 641)))).at("out_of_range", "/moves/0/forwardPixels")

        // Guard.
        one(base.copy(guard = SkillGuard(arcDegrees = 0))).at("out_of_range", "/guard/arcDegrees")
        one(base.copy(guard = SkillGuard(damageReductionPercent = 101))).at("out_of_range", "/guard/damageReductionPercent")
        one(base.copy(guard = SkillGuard(blockCosts = mapOf("hp" to 3)))).at("invalid_cost", "/guard/blockCosts/hp")

        val idle = one(base.copy(hitboxes = emptyList())).at("no_effect", "/hitboxes")
        assertEquals(IssueSeverity.WARNING, idle.severity)
    }

    @Test
    fun `a combo graph must name real skills that belong to the set`() {
        val skills = listOf(cut(1), cut(2), cut(3, cooldown = 20))
        validate(skills, listOf(chain(skills = emptyList(), entries = emptyList()))).at("empty_set", "/skillIds")
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), ComboEdge(3, "LMB", 9), skills = listOf(1, 2, 3, 9)))).at("missing_reference", "/skillIds/3")
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), skills = listOf(1, 2, 2)))).at("duplicate_skill", "/skillIds/2")
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), entries = listOf(ComboEntry("LMB", 4))))).at("not_in_set", "/entries/0/skillId")
        val both = validate(skills, listOf(chain(ComboEdge(5, "LMB", 6))))
        both.at("not_in_set", "/edges/0/fromSkillId")
        both.at("not_in_set", "/edges/0/toSkillId")
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), entries = emptyList()))).at("no_entry", "/entries")
        validate(skills, listOf(chain().copy(classIds = setOf(4)))).at("missing_reference", "/classIds")
        validate(skills, listOf(chain().copy(inputBufferTicks = 21))).at("out_of_range", "/inputBufferTicks")
    }

    @Test
    fun `an input may mean only one thing at a time`() {
        val skills = listOf(cut(1), cut(2), cut(3, cooldown = 20))
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), entries = listOf(ComboEntry("LMB", 1), ComboEntry("LMB", 2)))))
            .at("ambiguous_input", "/entries/1/input")
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(1, "LMB", 3)))).at("ambiguous_input", "/edges/1/input")
        // The same input may lead two ways when one of them needs the hit to have landed.
        val split = validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(1, "LMB", 3, requiresHit = true))))
        assertTrue(split.none { it.code == "ambiguous_input" }, split.toString())
    }

    @Test
    fun `chain windows fit the skill they leave`() {
        val skills = listOf(cut(1), cut(2, cooldown = 10), ActionSkillDefinition(id = 3, name = "Step", startupTicks = 0, activeTicks = 4, recoveryTicks = 2, cooldownTicks = 30,
            moves = listOf(SkillMove(0, 3, 48))))
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2, windowTicks = 0), ComboEdge(2, "LMB", 3)))).at("out_of_range", "/edges/0/windowTicks")
        // Cut 1 is cancellable from tick 4 of 9, so 5 ticks remain, plus 20 of grace after it ends.
        val long = validate(skills, listOf(chain(ComboEdge(1, "LMB", 2, windowTicks = 26), ComboEdge(2, "LMB", 3)))).at("window_too_long", "/edges/0/windowTicks")
        assertTrue("at most 25" in long.message, long.message)
        assertTrue(validate(skills, listOf(chain(ComboEdge(1, "LMB", 2, windowTicks = 25), ComboEdge(2, "LMB", 3)))).isEmpty())
        // The step has no hitbox, so nothing can ever require it to have hit.
        validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), ComboEdge(3, "LMB", 1, requiresHit = true)))).at("never_hits", "/edges/2/requiresHit")
    }

    @Test
    fun `every skill in a set must be reachable from neutral`() {
        val skills = listOf(cut(1), cut(2), cut(3, cooldown = 20), cut(4, cooldown = 20))
        val issues = validate(skills, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(3, "LMB", 4), ComboEdge(4, "LMB", 3), skills = listOf(1, 2, 3, 4))))
        // 3 and 4 only lead to each other; nothing leads to them.
        assertTrue("Cut 3" in issues.at("unreachable_skill", "/skillIds/2").message)
        issues.at("unreachable_skill", "/skillIds/3")
        assertEquals(2, issues.count { it.code == "unreachable_skill" })
    }

    @Test
    fun `a chain that can repeat forever for free is rejected`() {
        val free = listOf(cut(1), cut(2), cut(3))
        // 1 -> 2 -> 3 -> 1 with no cooldown and no cost anywhere.
        val loop = validate(free, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), ComboEdge(3, "LMB", 1))))
        val cycle = loop.at("free_cycle", "/edges/0")
        assertEquals(1, loop.count { it.code == "free_cycle" }, "one report per loop")
        assertTrue("Cut 1" in cycle.message && "Cut 2" in cycle.message && "Cut 3" in cycle.message, cycle.message)
        assertEquals(ContentCollection.WEAPON_SETS, cycle.collection)

        // A skill that chains into itself.
        validate(free, listOf(chain(ComboEdge(1, "LMB", 1), skills = listOf(1)))).at("free_cycle", "/edges/0")

        // A cooldown or a cost on any one skill of the loop bounds it.
        for (bounded in listOf(cut(3, cooldown = 30), cut(3, costs = mapOf("mp" to 5)))) {
            val ok = validate(listOf(cut(1), cut(2), bounded), listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3), ComboEdge(3, "LMB", 1))))
            assertTrue(ok.isEmpty(), ok.toString())
        }
        // A straight chain is not a loop: going back through neutral is always allowed.
        assertTrue(validate(free, listOf(chain(ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 3)))).isEmpty())

        // Two separate free loops are two reports.
        val two = validate(listOf(cut(1), cut(2), cut(3), cut(4)), listOf(chain(
            ComboEdge(1, "LMB", 2), ComboEdge(2, "LMB", 1), ComboEdge(3, "LMB", 4), ComboEdge(4, "LMB", 3),
            skills = listOf(1, 2, 3, 4), entries = listOf(ComboEntry("LMB", 1), ComboEntry("RMB", 3)))))
        assertEquals(listOf("/edges/0", "/edges/2"), two.filter { it.code == "free_cycle" }.map { it.pointer })
    }

    @Test
    fun `a game without action content keeps its replay fingerprint, and one with it gets its own`() {
        val plain = WorldSimulation(game = BlankGame.COMPILED)
        val before = StateHasher.contentFingerprint(plain)
        val withSkills = WorldSimulation(game = BlankGame.COMPILED).also { it.actionSkills[1] = cut(1) }
        assertEquals(before, StateHasher.contentFingerprint(WorldSimulation(game = BlankGame.COMPILED)))
        assertNotEquals(before, StateHasher.contentFingerprint(withSkills))
        val withSet = WorldSimulation(game = BlankGame.COMPILED).also { it.actionSkills[1] = cut(1); it.weaponSets[1] = chain(skills = listOf(1)) }
        assertNotEquals(StateHasher.contentFingerprint(withSkills), StateHasher.contentFingerprint(withSet))
    }
}
