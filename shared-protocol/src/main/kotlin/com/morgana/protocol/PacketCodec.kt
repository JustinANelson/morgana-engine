package com.morgana.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.modules.SerializersModule

class UnknownServerPacketDeserializer(private val rawType: String) : DeserializationStrategy<ServerPacket.Unknown> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.morgana.protocol.ServerPacket.Unknown")

    override fun deserialize(decoder: Decoder): ServerPacket.Unknown {
        val payload = if (decoder is JsonDecoder) {
            decoder.decodeJsonElement().toString()
        } else {
            ""
        }
        return ServerPacket.Unknown(rawType = rawType, rawPayload = payload)
    }
}

class UnknownClientPacketDeserializer(private val rawType: String) : DeserializationStrategy<ClientPacket.Unknown> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.morgana.protocol.ClientPacket.Unknown")

    override fun deserialize(decoder: Decoder): ClientPacket.Unknown {
        val payload = if (decoder is JsonDecoder) {
            decoder.decodeJsonElement().toString()
        } else {
            ""
        }
        return ClientPacket.Unknown(rawType = rawType, rawPayload = payload)
    }
}

private val packetSerializersModule = SerializersModule {
    polymorphicDefaultDeserializer(ServerPacket::class) { className ->
        UnknownServerPacketDeserializer(className ?: "unknown")
    }
    polymorphicDefaultDeserializer(ClientPacket::class) { className ->
        UnknownClientPacketDeserializer(className ?: "unknown")
    }
}

/**
 * Deterministic JSON and CBOR codecs for [ClientPacket] and [ServerPacket].
 * Supports both compact binary CBOR frames (TCP) and UTF-8 JSON frames (WebSockets / debugging).
 */
@OptIn(ExperimentalSerializationApi::class)
object PacketCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "pkt"
        serializersModule = packetSerializersModule
    }

    /**
     * What the server writes for gameplay packets: the same codec without fields that hold their default value, which both
     * ends declare in the same class, so the reader fills them back in. About a third smaller on the wire, and cheaper to
     * encode. Packets that carry content definitions (see [keepsDefaults]) are still written in full, so tuning a
     * design value's default in a definition class never changes what an older client reads.
     */
    private val serverJson: Json = Json(from = json) { encodeDefaults = false }

    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = packetSerializersModule
    }

    fun encodeClientJson(packet: ClientPacket): String =
        json.encodeToString<ClientPacket>(packet)

    fun decodeClientJson(payload: String): ClientPacket =
        json.decodeFromString<ClientPacket>(payload)

    fun encodeServerJson(packet: ServerPacket): String =
        (if (keepsDefaults(packet)) json else serverJson).encodeToString<ServerPacket>(packet)

    /** True for the packets that carry content definitions (items, spells, the game rules): written with every field. */
    private fun keepsDefaults(packet: ServerPacket): Boolean =
        packet is ServerPacket.AuthResult || packet is ServerPacket.ContentCatalogUpdated

    fun decodeServerJson(payload: String): ServerPacket =
        json.decodeFromString<ServerPacket>(payload)

    fun encodeClientCbor(packet: ClientPacket): ByteArray =
        cbor.encodeToByteArray<ClientPacket>(packet)

    fun decodeClientCbor(bytes: ByteArray): ClientPacket =
        cbor.decodeFromByteArray<ClientPacket>(bytes)

    fun encodeServerCbor(packet: ServerPacket): ByteArray =
        cbor.encodeToByteArray<ServerPacket>(packet)

    fun decodeServerCbor(bytes: ByteArray): ServerPacket =
        cbor.decodeFromByteArray<ServerPacket>(bytes)
}
