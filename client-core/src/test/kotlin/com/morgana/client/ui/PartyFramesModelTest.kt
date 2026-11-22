package com.morgana.client.ui

import com.morgana.domain.entity.PartyState
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.protocol.PublicPlayerSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartyFramesModelTest {
    private fun snap(id: String, hp: Int, max: Int, ghost: Boolean = false) = PublicPlayerSnapshot(
        characterId = id, name = "N$id", level = 12, spriteId = 0, accessRank = 0, nameplateColorHex = "FFFFFF",
        isGhost = ghost, vitals = mapOf("hp" to VitalState(hp, max)), position = WorldPosition(1, 0, 0)
    )

    private val party = PartyState("p", leaderCharacterId = "a", memberCharacterIds = listOf("a", "b", "c"))

    @Test
    fun noPartyMeansNoFrames() {
        assertTrue(PartyFramesModel.frames(null, "a", emptyMap()).isEmpty())
    }

    @Test
    fun listsOtherMembersOnlyAndMarksTheLeader() {
        val frames = PartyFramesModel.frames(party, "b", mapOf("a" to snap("a", 50, 100), "c" to snap("c", 10, 40)))
        assertEquals(listOf("a", "c"), frames.map { it.characterId })
        assertTrue(frames[0].isLeader)
        assertFalse(frames[1].isLeader)
        assertEquals(0.5f, frames[0].fraction)
        assertEquals(0.25f, frames[1].fraction)
    }

    @Test
    fun membersOnOtherMapsAreAwayWithAnEmptyBar() {
        val frames = PartyFramesModel.frames(party, "a", mapOf("b" to snap("b", 5, 10)), namesById = mapOf("c" to "Cara"))
        val away = frames.first { it.characterId == "c" }
        assertFalse(away.nearby)
        assertEquals("Cara", away.name)
        assertEquals(0f, away.fraction)
    }

    @Test
    fun ghostsAreFlaggedAndZeroMaxIsSafe() {
        val frames = PartyFramesModel.frames(party, "a", mapOf("b" to snap("b", 0, 0, ghost = true)))
        val b = frames.first { it.characterId == "b" }
        assertTrue(b.ghost)
        assertEquals(0f, b.fraction)
    }

    @Test
    fun framesAreCapped() {
        val big = PartyState("p", "m0", (0..20).map { "m$it" })
        assertEquals(PartyFramesModel.MAX_FRAMES, PartyFramesModel.frames(big, null, emptyMap()).size)
    }
}
