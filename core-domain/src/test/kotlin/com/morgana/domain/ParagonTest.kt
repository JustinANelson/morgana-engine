package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.ParagonDefinition
import com.morgana.domain.game.ProgressionDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParagonTest {
    private val paragon = ParagonDefinition(xpBase = 100, xpStep = 50, statPointsPerLevel = 2, maxLevels = 3)

    private fun game(p: ParagonDefinition?): CompiledGame =
        CompiledGame.compile(MorganaClassic.definition.copy(progression = ProgressionDefinition(maxLevel = 5, xpExpr = "10 * level"), paragon = p))

    private fun hero(level: Int, xp: Long = 0, variables: Map<String, Int> = emptyMap()) = CharacterState(
        id = "c", accountId = "a", name = "Hero", level = level, xp = xp, variables = variables,
        position = WorldPosition.fromTile(1, 1, 1, Direction.DOWN)
    )

    @Test
    fun atTheCapXpFillsParagonLevelsAndPaysStatPoints() {
        val g = game(paragon)
        val (after, levels) = hero(5).gainExperience(g, 260)
        assertEquals(0, levels)
        // 100 for the first level, 150 for the second: 250 used, 10 left over.
        assertEquals(2, after.variables["paragon"])
        assertEquals(10L, after.xp)
        assertEquals(4, after.unspentStatPoints - hero(5).unspentStatPoints)
        assertEquals(5, after.level)
    }

    @Test
    fun progressCarriesOverBetweenGains() {
        val g = game(paragon)
        val (a, _) = hero(5).gainExperience(g, 60)
        val (b, _) = a.gainExperience(g, 60)
        assertEquals(1, b.variables["paragon"])
        assertEquals(20L, b.xp)
    }

    @Test
    fun theTrackStopsAtMaxLevels() {
        val g = game(paragon)
        val (after, _) = hero(5).gainExperience(g, 1_000_000)
        assertEquals(3, after.variables["paragon"])
        assertEquals(0L, after.xp)
        assertNull(g.xpToNext(after))
    }

    @Test
    fun withoutATrackCapXpIsDroppedAsBefore() {
        val g = game(null)
        val (after, levels) = hero(5).gainExperience(g, 500)
        assertEquals(0, levels)
        assertEquals(hero(5), after)
        assertNull(g.xpToNext(hero(5)))
    }

    @Test
    fun theXpBarShowsTheParagonCostAtTheCap() {
        val g = game(paragon)
        assertEquals(10L * 4, g.xpToNext(hero(4)))
        assertEquals(100L, g.xpToNext(hero(5)))
        assertEquals(150L, g.xpToNext(hero(5, variables = mapOf("paragon" to 1))))
    }

    @Test
    fun belowTheCapParagonDoesNothing() {
        val g = game(paragon)
        val (after, _) = hero(2).gainExperience(g, 5)
        assertTrue(after.variables.isEmpty())
    }

    @Test
    fun validatorRejectsBadValues() {
        val bad = MorganaClassic.definition.copy(paragon = ParagonDefinition(xpBase = 0, maxLevels = 0, variable = "Bad Name"))
        val problems = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(problems.any { "/paragon/xpBase" in it }, problems.toString())
        assertTrue(problems.any { "/paragon/maxLevels" in it }, problems.toString())
        assertTrue(problems.any { "/paragon/variable" in it }, problems.toString())
    }
}
