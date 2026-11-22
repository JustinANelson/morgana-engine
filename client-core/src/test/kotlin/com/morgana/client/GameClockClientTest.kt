package com.morgana.client

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.ClockDefinition
import com.morgana.domain.game.DayPhase
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The client derives the same game clock as the server from the tick it was last told, advancing it with its own clock. */
class GameClockClientTest {

    private var now = 1_000_000L
    private val hero = CharacterState(id = "h", accountId = "a", name = "H", position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN))

    private fun state(clock: ClockDefinition?, tick: Long): ClientWorldState {
        val s = ClientWorldState()
        s.nowMs = { now }
        s.applyServerPacket(
            ServerPacket.AuthResult(
                success = true, message = "ok", character = hero, catalog = WorldContentCatalogDto(),
                gameDefinition = MorganaClassic.definition.copy(clock = clock), serverTick = tick
            )
        )
        return s
    }

    private val noonDay = ClockDefinition(dayLengthTicks = 1000, startTimePermille = 0, dayNight = true)

    @Test
    fun `the clock starts from the server tick in the login result`() {
        val s = state(noonDay, tick = 500)
        val t = s.clockNow()
        assertNotNull(t)
        assertEquals("12:00", t!!.clockText)
        assertEquals(DayPhase.DAY, t.phase)
    }

    @Test
    fun `it keeps time between server updates at 20 ticks per second`() {
        val s = state(noonDay, tick = 500)
        now += 5_000 // five seconds later = 100 ticks
        assertEquals(600L, s.estimatedServerTick())
        now += 5_000
        assertEquals(700L, s.estimatedServerTick())
    }

    @Test
    fun `a map snapshot or pong re-syncs the estimate`() {
        val s = state(noonDay, tick = 500)
        now += 60_000
        s.applyServerPacket(ServerPacket.Pong(clientTimestampMs = 0, serverTick = 900))
        assertEquals(900L, s.estimatedServerTick())
        now += 1_000
        s.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = MapDefinition(id = 1, name = "T"), serverTick = 1100))
        assertEquals(1100L, s.estimatedServerTick())
    }

    @Test
    fun `a game without a clock has none, and day and night is opt in`() {
        assertNull(state(null, tick = 500).clockNow())
        val calendarOnly = state(ClockDefinition(dayLengthTicks = 1000, startTimePermille = 0, dayNight = false), tick = 500)
        assertNotNull(calendarOnly.clockNow(), "the calendar exists")
        assertNull(calendarOnly.dayNightClock(), "but the world does not darken unless the game opts in")
        assertNotNull(state(noonDay, tick = 500).dayNightClock())
    }

    @Test
    fun `no clock until a server tick is known`() {
        assertNull(state(noonDay, tick = 0).clockNow())
    }
}
