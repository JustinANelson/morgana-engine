package com.morgana.client.web

import com.morgana.client.state.AbstractPerRealmStore
import com.morgana.client.state.RealmsClientStorageDocument
import kotlinx.serialization.json.Json
import org.teavm.jso.JSBody

private const val REALMS_STORAGE_KEY = "morgana.realms"

@JSBody(params = ["k"], script = "try { return window.localStorage.getItem(k); } catch (e) { return null; }")
private external fun readRealmsItem(k: String): String?

@JSBody(params = ["k", "v"], script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
private external fun writeRealmsItem(k: String, v: String)

/**
 * Browser `localStorage`-backed [com.morgana.client.state.PerRealmStore], so saved login credentials,
 * recent/favorite realms, and per-character hotbar bindings persist across browser reloads.
 */
class BrowserPerRealmStore(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : AbstractPerRealmStore() {
    private var cached: RealmsClientStorageDocument? = null

    override fun load(): RealmsClientStorageDocument {
        cached?.let { return it }
        val raw = readRealmsItem(REALMS_STORAGE_KEY)
        val doc = if (raw.isNullOrBlank()) {
            RealmsClientStorageDocument()
        } else {
            runCatching { json.decodeFromString<RealmsClientStorageDocument>(raw) }.getOrDefault(RealmsClientStorageDocument())
        }
        cached = doc
        return doc
    }

    override fun save(doc: RealmsClientStorageDocument) {
        cached = doc
        val encoded = json.encodeToString(RealmsClientStorageDocument.serializer(), doc)
        writeRealmsItem(REALMS_STORAGE_KEY, encoded)
    }
}
