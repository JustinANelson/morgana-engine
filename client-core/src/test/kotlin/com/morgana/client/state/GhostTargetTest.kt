package com.morgana.client.state

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GhostTargetTest {
    private fun snap(id: String, x: Int, y: Int, ghost: Boolean) = PublicPlayerSnapshot(
        characterId = id, name = "Name$id", level = 1, spriteId = 0, accessRank = 0, nameplateColorHex = "#FFFFFF",
        isGhost = ghost, position = WorldPosition(mapId = 1, tileX = x, tileY = y)
    )

    private fun state(): ClientWorldState = ClientWorldState().also {
        it.catalog = WorldContentCatalogDto(spells = listOf(
            SpellDefinition(id = 1, name = "Rekindle", revivePercent = 70),
            SpellDefinition(id = 2, name = "Bolt")
        ))
        it.playersOnMap["a"] = snap("a", 5, 5, ghost = true)
        it.playersOnMap["b"] = snap("b", 6, 5, ghost = false)
    }

    @Test
    fun `clicking a ghost picks it for a revive spell only`() {
        val s = state()
        assertNull(s.castTargetFor(1), "nothing is picked yet")
        assertEquals("a", s.selectGhostAt(5, 5)?.characterId)
        assertEquals("a", s.castTargetFor(1))
        assertNull(s.castTargetFor(2), "a damage spell never names the ghost")
    }

    @Test
    fun `clicking a living player or an empty tile picks nothing`() {
        val s = state()
        assertNull(s.selectGhostAt(6, 5))
        assertNull(s.selectGhostAt(9, 9))
        assertNull(s.castTargetFor(1))
    }

    @Test
    fun `a revived or departed ghost is dropped as the target`() {
        val s = state()
        s.selectGhostAt(5, 5)
        s.playersOnMap["a"] = snap("a", 5, 5, ghost = false)
        assertNull(s.castTargetFor(1))
        s.playersOnMap["a"] = snap("a", 5, 5, ghost = true)
        assertNull(s.castTargetFor(1), "the old pick is forgotten once it is invalid")
    }
}
