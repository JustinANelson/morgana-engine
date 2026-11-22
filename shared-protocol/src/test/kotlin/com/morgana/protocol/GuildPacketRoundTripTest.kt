package com.morgana.protocol

import com.morgana.domain.social.GuildRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuildPacketRoundTripTest {
    @Test
    fun `a new guild and its roster survive every wire format`() {
        val guild = GuildRules.create("guild-abc123", "Heroes", "HRO", "char-1")
        val packets = listOf<ServerPacket>(
            ServerPacket.GuildUpdated(guild),
            ServerPacket.GuildRoster(listOf(GuildMemberEntry("Alpha", GuildRules.FOUNDER, true, 5)))
        )
        for (p in packets) {
            assertEquals(p, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(p)))
            assertEquals(p, PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(p)))
        }
    }
}
