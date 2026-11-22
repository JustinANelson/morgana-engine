package com.morgana.client.realms

import com.morgana.protocol.realms.RealmClassSummary
import com.morgana.protocol.realms.RealmDirectoryDocument
import com.morgana.protocol.realms.RealmSummary
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/** Playable classes shown when a realm does not report its own list (the scaffolded game's single class). */
val DEFAULT_CLASSES: List<RealmClassSummary> = listOf(
    RealmClassSummary(1, "Adventurer", "A versatile wanderer.", 1)
)

/**
 * Port decoupling the realm discovery service from the client UI.
 * Allows retrieving the list of public Morgana Realms from a directory server,
 * a local file, or a bundled in-memory manifest.
 */
interface RealmDirectoryClient {
    /**
     * Fetches the current directory document containing active realms and announcements.
     */
    fun fetchDirectory(): Result<RealmDirectoryDocument>

    /** True when [fetchDirectory] may take a while (network), so the screen fetches it in the background. */
    val slow: Boolean get() = false
}

/**
 * The bundled directory used when no directory service is reachable: a single entry for a server on this computer.
 */
fun defaultBundledDirectory(defaultHost: String = "127.0.0.1", defaultPort: Int = 7001, tls: Boolean = false): RealmDirectoryDocument {
    val local = RealmSummary(
        id = LOCAL_REALM_ID,
        name = "Local Server",
        description = "A Morgana server running on this computer.",
        host = defaultHost,
        port = defaultPort,
        tls = tls,
        fingerprint = null,
        gameTitle = "",
        engineApi = 1,
        onlinePlayers = 0,
        maxPlayers = 100,
        tags = emptyList(),
        classes = DEFAULT_CLASSES
    )
    return RealmDirectoryDocument(
        realms = listOf(local),
        featuredRealmId = LOCAL_REALM_ID,
        announcement = "Welcome to Morgana Realms! Connect to a realm or host your own."
    )
}

/** Id of the bundled local-server entry. */
const val LOCAL_REALM_ID = "local"

/**
 * In-memory directory client with bundled realms.
 */
class InMemoryRealmDirectoryClient(
    var document: RealmDirectoryDocument = defaultBundledDirectory()
) : RealmDirectoryClient {
    override fun fetchDirectory(): Result<RealmDirectoryDocument> {
        return Result.success(document)
    }
}

/**
 * HTTP-based directory client that loads the JSON catalog from an HTTPS endpoint.
 * Automatically falls back to [fallback] on network failure.
 */
class HttpJsonRealmDirectoryClient(
    val directoryUri: URI,
    val connectTimeoutMs: Int = 4000,
    val readTimeoutMs: Int = 4000,
    private val fallback: RealmDirectoryClient = InMemoryRealmDirectoryClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RealmDirectoryClient {

    constructor(
        directoryUrl: String,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 4000,
        fallback: RealmDirectoryClient = InMemoryRealmDirectoryClient()
    ) : this(URI.create(directoryUrl), connectTimeoutMs, readTimeoutMs, fallback)

    override val slow: Boolean get() = true

    override fun fetchDirectory(): Result<RealmDirectoryDocument> {
        return try {
            val conn = directoryUri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MorganaRealmsClient/1.0")

            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val doc = json.decodeFromString<RealmDirectoryDocument>(text)
                Result.success(doc)
            } else {
                fallback.fetchDirectory()
            }
        } catch (_: Exception) {
            fallback.fetchDirectory()
        }
    }
}

