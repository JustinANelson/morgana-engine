package com.morgana.client.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * An individual hotbar slot binding (e.g. slot 0 bound to spell "heal_1" or item "potion_hp").
 */
@Serializable
data class RealmHotbarEntry(
    val slot: Int,
    val actionType: String,
    val actionId: String
)

/**
 * Client-side preferences and state isolated to a specific character within a specific realm.
 */
@Serializable
data class PerRealmCharacterPreferences(
    val hotbarBindings: Map<String, RealmHotbarEntry> = emptyMap(),
    val customSettings: Map<String, String> = emptyMap()
) {
    fun getHotbar(slot: Int): RealmHotbarEntry? = hotbarBindings[slot.toString()]

    fun withHotbar(slot: Int, entry: RealmHotbarEntry): PerRealmCharacterPreferences {
        val updated = hotbarBindings.toMutableMap()
        updated[slot.toString()] = entry
        return copy(hotbarBindings = updated)
    }

    fun withoutHotbar(slot: Int): PerRealmCharacterPreferences {
        val updated = hotbarBindings.toMutableMap()
        updated.remove(slot.toString())
        return copy(hotbarBindings = updated)
    }
}

/**
 * Historical record of a realm the player has connected to.
 */
@Serializable
data class RecentRealmEntry(
    val realmId: String,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val lastPlayedEpochMs: Long,
    val tags: List<String> = emptyList()
)

/**
 * Persistent document storing the player's realm history, favorite realms, last entered username,
 * and per-realm character hotbar configurations.
 */
@Serializable
data class RealmsClientStorageDocument(
    val recentRealms: List<RecentRealmEntry> = emptyList(),
    val favoriteRealmIds: Set<String> = emptySet(),
    val lastUsername: String? = null,
    val rememberPassword: Boolean = false,
    val savedPassword: String? = null,
    val characterPreferences: Map<String, PerRealmCharacterPreferences> = emptyMap(),
    val schemaVersion: Int = 1
)

/**
 * Storage port decoupling client persistence from the UI and network layers.
 */
interface PerRealmStore {
    fun load(): RealmsClientStorageDocument
    fun save(doc: RealmsClientStorageDocument)

    fun getPreferences(realmKey: String, characterName: String): PerRealmCharacterPreferences
    fun updatePreferences(realmKey: String, characterName: String, preferences: PerRealmCharacterPreferences)

    fun recordRealmVisit(
        realmId: String,
        name: String,
        host: String,
        port: Int,
        tls: Boolean,
        tags: List<String> = emptyList(),
        maxHistory: Int = 20
    )

    fun toggleFavorite(realmId: String): Boolean
    fun isFavorite(realmId: String): Boolean
    fun setLastUsername(username: String)
    fun setRememberedCredentials(username: String, password: String?, rememberPassword: Boolean)
}

/** Shared [PerRealmStore] operations backed by subclasses' [load] and [save]. */
abstract class AbstractPerRealmStore : PerRealmStore {
    @Synchronized
    override fun getPreferences(realmKey: String, characterName: String): PerRealmCharacterPreferences {
        val key = buildCharacterKey(realmKey, characterName)
        return load().characterPreferences[key] ?: PerRealmCharacterPreferences()
    }

    @Synchronized
    override fun updatePreferences(realmKey: String, characterName: String, preferences: PerRealmCharacterPreferences) {
        val current = load()
        val key = buildCharacterKey(realmKey, characterName)
        val updated = current.characterPreferences.toMutableMap()
        updated[key] = preferences
        save(current.copy(characterPreferences = updated))
    }

    @Synchronized
    override fun recordRealmVisit(
        realmId: String,
        name: String,
        host: String,
        port: Int,
        tls: Boolean,
        tags: List<String>,
        maxHistory: Int
    ) {
        val current = load()
        val cleanId = realmId.trim()
        val filtered = current.recentRealms.filterNot { it.realmId.equals(cleanId, ignoreCase = true) }
        val newEntry = RecentRealmEntry(
            realmId = cleanId,
            name = name.trim(),
            host = host.trim(),
            port = port,
            tls = tls,
            lastPlayedEpochMs = System.currentTimeMillis(),
            tags = tags
        )
        val updatedRecent = (listOf(newEntry) + filtered).take(maxHistory)
        save(current.copy(recentRealms = updatedRecent))
    }

    @Synchronized
    override fun toggleFavorite(realmId: String): Boolean {
        val current = load()
        val cleanId = realmId.trim()
        val updated = current.favoriteRealmIds.toMutableSet()
        val nowFavorite = if (updated.contains(cleanId)) {
            updated.remove(cleanId)
            false
        } else {
            updated.add(cleanId)
            true
        }
        save(current.copy(favoriteRealmIds = updated))
        return nowFavorite
    }

    override fun isFavorite(realmId: String): Boolean = load().favoriteRealmIds.contains(realmId.trim())

    @Synchronized
    override fun setLastUsername(username: String) {
        val current = load()
        val clean = username.trim()
        if (current.lastUsername != clean) {
            save(current.copy(lastUsername = clean))
        }
    }

    @Synchronized
    override fun setRememberedCredentials(username: String, password: String?, rememberPassword: Boolean) {
        val current = load()
        val cleanUser = username.trim()
        val nextPass = if (rememberPassword) password else null
        if (current.lastUsername != cleanUser || current.rememberPassword != rememberPassword || current.savedPassword != nextPass) {
            save(current.copy(lastUsername = cleanUser, rememberPassword = rememberPassword, savedPassword = nextPass))
        }
    }
}

/**
 * Helper to build standard cache/isolation keys for character preferences.
 */
fun buildCharacterKey(realmKey: String, characterName: String): String {
    return "${realmKey.trim().lowercase()}:${characterName.trim().lowercase()}"
}

/**
 * File-backed [PerRealmStore] storing settings as formatted JSON.
 */
class FilePerRealmStore(
    val storageFile: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : AbstractPerRealmStore() {

    @Volatile
    private var cachedDocument: RealmsClientStorageDocument? = null

    init {
        storageFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
    }

    @Synchronized
    override fun load(): RealmsClientStorageDocument {
        cachedDocument?.let { return it }
        if (!storageFile.exists() || storageFile.length() == 0L) {
            val empty = RealmsClientStorageDocument()
            cachedDocument = empty
            return empty
        }
        return try {
            val doc = json.decodeFromString<RealmsClientStorageDocument>(storageFile.readText())
            cachedDocument = doc
            doc
        } catch (e: Exception) {
            val fallback = RealmsClientStorageDocument()
            cachedDocument = fallback
            fallback
        }
    }

    @Synchronized
    override fun save(doc: RealmsClientStorageDocument) {
        cachedDocument = doc
        val tmp = File(storageFile.parentFile, "${storageFile.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(json.encodeToString(RealmsClientStorageDocument.serializer(), doc))
            if (!tmp.renameTo(storageFile)) {
                tmp.copyTo(storageFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("Failed to save realms client storage: ${e.message}", e)
        }
    }
}

/**
 * In-memory [PerRealmStore] for unit testing and headless environments.
 */
class InMemoryPerRealmStore(
    initialDocument: RealmsClientStorageDocument = RealmsClientStorageDocument()
) : AbstractPerRealmStore() {
    private var state: RealmsClientStorageDocument = initialDocument

    @Synchronized
    override fun load(): RealmsClientStorageDocument = state

    @Synchronized
    override fun save(doc: RealmsClientStorageDocument) {
        state = doc
    }
}

