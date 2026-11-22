package com.morgana.client

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.PublicPlayerSnapshot
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * With network latency every server update about the local player describes where it was one round trip ago. The client
 * must replay the steps the server had not handled yet instead of snapping back to that old position (which made
 * movement jitter on internet connections).
 */
class MovePredictionReconcileTest {

    private val start = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT)
    private val hero = CharacterState(id = "c1", accountId = "a1", name = "Hero", position = start)

    private fun loggedIn(): ClientWorldState {
        val state = ClientWorldState()
        state.applyServerPacket(ServerPacket.AuthResult(success = true, message = "", character = hero, catalog = WorldContentCatalogDto()))
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = MapDefinition(id = 1, name = "Field", width = 20, height = 20)))
        return state
    }

    private fun at(x: Float) = hero.copy(position = start.copy(pixelX = x))

    private fun snapshot(x: Float) = PublicPlayerSnapshot(
        characterId = "c1", name = "Hero", level = 1, spriteId = 1, accessRank = 0, nameplateColorHex = "FFFFFF",
        position = start.copy(pixelX = x)
    )

    @Test
    fun `a late sync replays the steps the server had not handled yet`() {
        val state = loggedIn()
        val seqs = (1..4).map { state.predictAndCreateMovePacket(Direction.RIGHT).seq }
        assertEquals(listOf(1, 2, 3, 4), seqs)
        val predicted = start.pixelX + 32f
        assertEquals(predicted, state.localCharacter!!.position.pixelX)

        // The server's answer to step 1 arrives while steps 2 to 4 are still on their way.
        state.applyServerPacket(ServerPacket.CharacterSync(at(start.pixelX + 8f), ackMoveSeq = 1))
        assertEquals(predicted, state.localCharacter!!.position.pixelX, "no pull back")
        assertEquals(predicted, state.playerVisuals.getValue("c1").targetPixelX, "the sprite keeps heading for the predicted spot")
        assertEquals(3, state.unacknowledgedMoveCount)

        state.applyServerPacket(ServerPacket.CharacterSync(at(start.pixelX + 32f), ackMoveSeq = 4))
        assertEquals(predicted, state.localCharacter!!.position.pixelX)
        assertEquals(0, state.unacknowledgedMoveCount)
    }

    @Test
    fun `a server correction is kept and later steps are replayed from it`() {
        val state = loggedIn()
        repeat(3) { state.predictAndCreateMovePacket(Direction.RIGHT) }
        // The server refused step 1 (say a stun): it is still at the start when it has handled step 1.
        state.applyServerPacket(ServerPacket.CharacterSync(at(start.pixelX), ackMoveSeq = 1))
        assertEquals(start.pixelX + 16f, state.localCharacter!!.position.pixelX, "steps 2 and 3 replayed on the corrected position")
    }

    @Test
    fun `the public copy of our own position does not move the predicted player`() {
        val state = loggedIn()
        repeat(3) { state.predictAndCreateMovePacket(Direction.RIGHT) }
        val predicted = start.pixelX + 24f
        state.applyServerPacket(ServerPacket.PlayerUpdated(snapshot(start.pixelX + 8f)))
        assertEquals(predicted, state.playerVisuals.getValue("c1").targetPixelX)
        assertEquals(predicted, state.playersOnMap.getValue("c1").position.pixelX)
    }

    @Test
    fun `an older server without acknowledgements is still followed`() {
        val state = loggedIn()
        repeat(2) { state.predictAndCreateMovePacket(Direction.RIGHT) }
        state.applyServerPacket(ServerPacket.CharacterSync(at(start.pixelX + 8f)))
        assertEquals(start.pixelX + 8f, state.localCharacter!!.position.pixelX)
    }

    @Test
    fun `a new login starts the step numbers again`() {
        val state = loggedIn()
        repeat(3) { state.predictAndCreateMovePacket(Direction.RIGHT) }
        state.applyServerPacket(ServerPacket.AuthResult(success = true, message = "", character = hero, catalog = WorldContentCatalogDto()))
        assertEquals(0, state.unacknowledgedMoveCount)
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = MapDefinition(id = 1, name = "Field", width = 20, height = 20)))
        assertEquals(1, state.predictAndCreateMovePacket(Direction.RIGHT).seq)
    }
}
