package com.morgana.server.admin

import kotlinx.serialization.Serializable

/** The result of an admin action: [ok] false means nothing was changed and [message] says why. */
@Serializable
data class AdminResult(val ok: Boolean, val message: String)

/** A player who is online right now. */
@Serializable
data class AdminPlayer(
    val characterId: String,
    val name: String,
    val accountId: String,
    val level: Int,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val access: String
)

/** One sample of how busy the server was. */
@Serializable
data class AdminMetricSample(val atMillis: Long, val players: Int, val tickAvgMs: Double)

/** The server at a glance. */
@Serializable
data class AdminStatus(
    val serverTick: Long,
    val uptimeSeconds: Long,
    val playersOnline: Int,
    val accounts: Int,
    val characters: Int,
    val maps: Int,
    val tickAvgMs: Double,
    val tickMaxMs: Double,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val protocolVersion: Int,
    val disabledModules: List<String>,
    val openReports: Int,
    val activeBans: Int,
    val activeMutes: Int
)

/** Request bodies. Every field has a default so a missing one is reported by the API, not by the JSON parser. */
@Serializable data class NameRequest(val name: String = "", val reason: String = "")
@Serializable data class SanctionRequest(val name: String = "", val duration: String = "", val reason: String = "")
@Serializable data class CloseReportRequest(val id: Int = 0, val note: String = "")
@Serializable data class BroadcastRequest(val message: String = "")

/** The error body for every refused request. */
@Serializable
data class AdminError(val error: String)

/** One response of [AdminApi]: status code, media type and body, ready to be written to the socket. */
class AdminResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())
