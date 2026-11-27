package com.morgana.protocol

import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The compact movement and projectile packets that keep a crowded fight's bandwidth down. */
class MovePacketsTest {
    private fun roundTrip(p: ServerPacket): ServerPacket = PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(p))

    @Test
    fun `a batch of monster and player moves survives the wire`() {
        val batch = ServerPacket.EntityMoves(
            npcs = listOf(ServerPacket.NpcMoved("npc-3-7", 4, 5, Direction.DOWN, subPixel = true, pixelX = 128f, pixelY = 155.7f, wanderX = 4, wanderY = 5)),
            players = listOf(ServerPacket.PlayerMoved("char-1", WorldPosition(3, 20, 38, direction = Direction.UP_LEFT)))
        )
        assertEquals(batch, roundTrip(batch))
    }

    @Test
    fun `a move is much smaller than the whole monster it replaces`() {
        val text = PacketCodec.encodeServerJson(ServerPacket.EntityMoves(npcs = listOf(ServerPacket.NpcMoved("npc-3-7", 4, 5, Direction.DOWN, subPixel = true, pixelX = 128f, pixelY = 155.7f))))
        assertTrue(text.length < 140, text)
    }

    @Test
    fun `a projectile delta carries the shots that changed and the ones that ended`() {
        val shot = MapProjectile("aproj-1", "char-1", true, 3, 4, 5, Direction.RIGHT, 6, 1, 14, free = true, px = 140f, py = 170f, vx = 16f, vy = 6f, remainingPixels = 220f)
        val delta = ServerPacket.ProjectilesDelta(3, listOf(shot), listOf("aproj-0"))
        assertEquals(delta, roundTrip(delta))
    }
}
