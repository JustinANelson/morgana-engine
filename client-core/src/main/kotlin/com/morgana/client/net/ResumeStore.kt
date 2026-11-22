package com.morgana.client.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything needed to return to a character after the app was closed or the page reloaded: where it was, how it was
 * secured, and the server's single-use resume token (never the password).
 */
@Serializable
data class ResumeInfo(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val realmId: String?,
    val token: String
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(text: String?): ResumeInfo? =
            if (text.isNullOrBlank()) null else runCatching { Json.decodeFromString<ResumeInfo>(text) }.getOrNull()
    }
}

/** Remembers the latest [ResumeInfo] across app restarts. Implementations must never throw. */
interface ResumeStore {
    fun load(): ResumeInfo?
    fun save(info: ResumeInfo)
    fun clear()
}

/** Keeps the info only for this run (the default; enough to survive a dropped connection but not a restart). */
class InMemoryResumeStore : ResumeStore {
    private var info: ResumeInfo? = null
    override fun load(): ResumeInfo? = info
    override fun save(info: ResumeInfo) { this.info = info }
    override fun clear() { info = null }
}
