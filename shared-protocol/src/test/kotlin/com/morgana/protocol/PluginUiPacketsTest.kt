package com.morgana.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginUiPacketsTest {
    private val window = PluginWindowDto(
        id = "stocks:board",
        title = "Stock board",
        lines = listOf("Prices change every minute."),
        rows = listOf(PluginWindowRow("ACME", "12 gold"), PluginWindowRow("Globex", "40 gold")),
        buttons = listOf(PluginWindowButton("buy-acme", "Buy ACME"), PluginWindowButton("sell-acme", "Sell ACME", enabled = false))
    )

    @Test
    fun `plugin packets round-trip through JSON and CBOR`() {
        val server = listOf<ServerPacket>(
            ServerPacket.PluginWindow(window),
            ServerPacket.PluginWindowClose("stocks:board"),
            ServerPacket.PluginMessage("stocks:tick", mapOf("acme" to "12"))
        )
        for (p in server) {
            assertEquals(p, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(p)))
            assertEquals(p, PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(p)))
        }
        val client = listOf<ClientPacket>(
            ClientPacket.PluginAction("stocks:board", "buy-acme", mapOf("qty" to "3")),
            ClientPacket.PluginWindowClosed("stocks:board"),
            ClientPacket.PluginMessage("stocks:order", mapOf("qty" to "3"))
        )
        for (p in client) {
            assertEquals(p, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(p)))
            assertEquals(p, PacketCodec.decodeClientCbor(PacketCodec.encodeClientCbor(p)))
        }
    }

    @Test
    fun `an older client decodes the new server packets as Unknown instead of failing`() {
        // A client that does not know "s_plugin_window" sees an unrecognised discriminator.
        val json = PacketCodec.encodeServerJson(ServerPacket.PluginWindow(window)).replace("s_plugin_window", "s_from_the_future")
        assertTrue(PacketCodec.decodeServerJson(json) is ServerPacket.Unknown)
    }

    @Test
    fun `window limits are checked in plain words`() {
        assertNull(window.problem())
        assertNotNull(window.copy(id = "").problem())
        assertNotNull(window.copy(inputs = List(PluginWindowDto.MAX_INPUTS + 1) { PluginWindowInput("i$it") }).problem())
        assertNotNull(window.copy(inputs = listOf(PluginWindowInput("a", maxLength = 0))).problem())
        assertTrue(window.copy(inputs = listOf(PluginWindowInput("a"), PluginWindowInput("a"))).problem()!!.contains("unique"))
        assertNotNull(window.copy(title = "t".repeat(PluginWindowDto.MAX_TITLE + 1)).problem())
        assertNotNull(window.copy(lines = List(PluginWindowDto.MAX_LINES + 1) { "x" }).problem())
        assertNotNull(window.copy(rows = List(PluginWindowDto.MAX_ROWS + 1) { PluginWindowRow("a") }).problem())
        assertNotNull(window.copy(buttons = List(PluginWindowDto.MAX_BUTTONS + 1) { PluginWindowButton("b$it", "x") }).problem())
        assertTrue(window.copy(buttons = listOf(PluginWindowButton("a", "x"), PluginWindowButton("a", "y"))).problem()!!.contains("unique"))
    }

    @Test
    fun `standard clients advertise plugin ui`() {
        assertTrue(ProtocolCapabilities.PLUGIN_UI in ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
    }
}
