package com.morgana.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TipPacketsTest {
    @Test
    fun `tip packets round-trip through JSON and CBOR`() {
        val tip = ServerPacket.Tip("welcome", "Welcome!", "Press {key:INTERACT} to talk.")
        assertEquals(tip, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(tip)))
        assertEquals(tip, PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(tip)))
        for (action in TipsAction.entries) {
            val p = ClientPacket.TipsControl(action)
            assertEquals(p, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(p)))
            assertEquals(p, PacketCodec.decodeClientCbor(PacketCodec.encodeClientCbor(p)))
        }
    }

    @Test
    fun `the standard client advertises tips`() {
        assertTrue(ProtocolCapabilities.TIPS in ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
    }
}
