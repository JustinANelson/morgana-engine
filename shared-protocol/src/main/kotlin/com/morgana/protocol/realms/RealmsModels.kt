package com.morgana.protocol.realms

import com.morgana.protocol.tls.InviteCode
import kotlinx.serialization.Serializable

/**
 * Kind of game asset for manifest categorization and client loading.
 */
@Serializable
enum class AssetKind {
    TILESET,
    SPRITE,
    PAPERDOLL,
    SOUND,
    MUSIC,
    FONT,
    MISC,
    /** A single item icon image. Needs the [com.morgana.protocol.ProtocolCapabilities.ART_V2] capability to be advertised to a client. */
    ICON,
    /** A single projectile image (needs ART_V2). */
    PROJECTILE,
    /** A single spell or combat animation image (needs ART_V2). */
    ANIMATION
}

/**
 * An individual content-addressed asset entry in a realm's [AssetManifest].
 */
@Serializable
data class AssetManifestEntry(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: AssetKind = AssetKind.MISC
)

/**
 * Content-addressed manifest of a realm game's visual and audio assets.
 */
@Serializable
data class AssetManifest(
    val gameId: String,
    val version: String = "1.0.0",
    val assets: Map<String, AssetManifestEntry> = emptyMap(),
    val schemaVersion: Int = 1
)

/**
 * Lightweight summary of a playable character class offered by a realm (shown on the login screen before authentication).
 */
@Serializable
data class RealmClassSummary(
    val id: Int,
    val name: String,
    val description: String = "",
    val spriteId: Int = 1
)

/**
 * Public summary of a game server / Realm listed in the Morgana Realms directory.
 */
@Serializable
data class RealmSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val host: String,
    val port: Int = InviteCode.DEFAULT_PORT,
    val tls: Boolean = false,
    val fingerprint: String? = null,
    val gameTitle: String = "Morgana ORPG",
    val engineApi: Int = 1,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 100,
    val tags: List<String> = emptyList(),
    val websiteUrl: String = "",
    val bannerAssetHash: String? = null,
    val assetBaseUrl: String = "",
    val assetManifestUrl: String = "",
    val classes: List<RealmClassSummary> = emptyList()
) {
    /** Generates the standard `morgana://host:port#fp=...` URI for this realm. */
    fun toInviteCode(): String {
        return InviteCode(host, port, if (tls) fingerprint else null).format()
    }

    companion object {
        /**
         * Parses a standard `morgana://` invite code or plain `host:port` string into a [RealmSummary].
         */
        fun fromInviteCode(
            code: String,
            id: String = "",
            name: String = "",
            description: String = ""
        ): RealmSummary? {
            val invite = InviteCode.parse(code) ?: return null
            val effectiveId = id.ifBlank { "realm-${invite.host.replace('.', '-')}-${invite.port}" }
            val effectiveName = name.ifBlank { "${invite.host}:${invite.port}" }
            return RealmSummary(
                id = effectiveId,
                name = effectiveName,
                description = description,
                host = invite.host,
                port = invite.port,
                tls = invite.fingerprint != null,
                fingerprint = invite.fingerprint
            )
        }
    }
}

/**
 * A directory catalog of Morgana Realms, typically fetched by the universal client.
 */
@Serializable
data class RealmDirectoryDocument(
    val realms: List<RealmSummary> = emptyList(),
    val featuredRealmId: String? = null,
    val announcement: String = "",
    val schemaVersion: Int = 1
)

