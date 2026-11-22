package com.morgana.persistence

import com.morgana.domain.chat.RolePlayChatSettings
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.ModuleRegistry
import com.morgana.domain.sim.OptionalModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Where player data (accounts, characters, guilds, mail, market) is stored. World content is always JSON files. */
enum class PlayerDataBackend(val configKey: String) {
    JSON("json"),
    SQLITE("sqlite");

    companion object {
        fun fromConfigKey(key: String): PlayerDataBackend? = entries.firstOrNull { it.configKey == key }
    }
}

/**
 * How the server treats TLS (encrypted) connections.
 * - `off`: plain TCP only (no certificate needed; the default for dedicated servers)
 * - `optional`: TLS and plain on the same port, detected per connection (for migrating existing clients)
 * - `required`: TLS only; plain clients are refused
 * - `remote`: TLS required for every client except ones connecting from this machine (the default for worlds hosted
 *   from the desktop client, so single-player and local play need no setup)
 */
enum class TlsMode(val configKey: String) {
    OFF("off"),
    OPTIONAL("optional"),
    REQUIRED("required"),
    REMOTE("remote");

    companion object {
        fun fromConfigKey(key: String): TlsMode? = entries.firstOrNull { it.configKey == key }
    }
}

/**
 * `tls` section of `server.json`. Without [keystore] the server creates and reuses a self-signed certificate in
 * `<data dir>/tls/` (players join with an invite code carrying its fingerprint). With [keystore] (a PKCS12 file, path
 * relative to the data directory) it uses your own certificate, e.g. one from a certificate authority; the file's
 * password is read from the environment variable named in [passwordEnv], never from this file.
 */
@Serializable
data class TlsConfig(
    val mode: String = TlsMode.OFF.configKey,
    val keystore: String = "",
    val passwordEnv: String = ""
)

/**
 * `limits` section of `server.json`: per-connection packet flood protection. On by default; [scale] multiplies every
 * budget (2.0 = twice as generous), e.g. for load tests or bots. Valid range 0.1 to 100.
 */
@Serializable
data class LimitsConfig(val enabled: Boolean = true, val scale: Double = 1.0)

/**
 * `connections` section of `server.json`: limits per client IP address (IPv6 clients count per /64 block).
 * Connections from this machine (loopback) are never limited. Anyone behind one shared address (a household, a school)
 * shares [maxPerIp].
 *
 * @property maxPerIp simultaneous connections from one address
 * @property maxTotal simultaneous connections server-wide
 * @property burst new connections one address may open at once
 * @property newPerMinute sustained new connections per minute per address
 * @property strikesToBan abusive events (flooding, repeated failed logins, connection spam) within 10 minutes that cause a temporary ban
 * @property banMinutes length of the first ban; each repeat doubles it up to [maxBanMinutes]
 * @property preLoginSeconds a connection that has not logged in by then is closed
 * @property allow addresses or CIDR blocks that are exempt (trusted friends, a reverse proxy, a load tester)
 */
@Serializable
data class ConnectionsConfig(
    val enabled: Boolean = true,
    val maxPerIp: Int = 8,
    val maxTotal: Int = 1000,
    val burst: Int = 10,
    val newPerMinute: Int = 30,
    val strikesToBan: Int = 5,
    val banMinutes: Int = 10,
    val maxBanMinutes: Int = 1440,
    val preLoginSeconds: Int = 30,
    val allow: List<String> = emptyList()
)

@Serializable
data class StorageConfig(val playerData: String = PlayerDataBackend.JSON.configKey)

/**
 * `session` section of `server.json`: how dropped connections and silent sockets are handled.
 *
 * @property resume whether a dropped client may return to its character without re-entering the password
 * @property graceMinutes how long a disconnected player's resume token stays valid (default 30 minutes)
 * @property idleSeconds seconds of total silence after which a connection is closed (default 75 seconds)
 * @property combatLogSeconds how long a player who disconnects within a few seconds of player-versus-player damage stays in the
 *   world, defenceless against the fight they left, before being removed (default 15; 0 removes them at once). It only ever
 *   applies after PvP damage, so it changes nothing in a realm without PvP. Returning within that time re-attaches to the same
 *   character instead of respawning.
 * @property maxCharacters how many characters one account may have (default 3). With 1 a login goes straight into the account's
 *   only character; with more, a client that can show a character list offers it after the password is accepted.
 */
@Serializable
data class SessionConfig(
    val resume: Boolean = true,
    val graceMinutes: Int = DEFAULT_GRACE_MINUTES,
    val idleSeconds: Int = DEFAULT_IDLE_SECONDS,
    val combatLogSeconds: Int = DEFAULT_COMBAT_LOG_SECONDS,
    val maxCharacters: Int = DEFAULT_MAX_CHARACTERS
) {
    companion object {
        const val DEFAULT_MAX_CHARACTERS = 3
        const val DEFAULT_GRACE_MINUTES = 30
        const val DEFAULT_IDLE_SECONDS = 75
        const val DEFAULT_COMBAT_LOG_SECONDS = 15
    }
}

/**
 * `assets` section of `server.json`: where clients download the game's own art (`<game>/assets/`).
 *
 * @property baseUrl absolute `http(s)` URL under which the game's `assets/` files are hosted, for example a CDN. Blank (the
 *   default) means this server serves them itself from its WebSocket port at `/assets/`. `{host}` is replaced by the
 *   host the player connected to.
 */
@Serializable
data class AssetsConfig(val baseUrl: String = "")

/**
 * `roleplay` section of `server.json`: how far role-play speech carries (module `rolePlayChat`).
 *
 * @property sayRadius tiles that ordinary speech, map chat and `/me` actions carry; 0 (the default) is the whole map
 * @property quietRadius tiles that `/quiet` speech carries (default 2)
 * @property shoutReachesNeighbors whether `/shout` is also heard on the four neighbouring maps (default true)
 */
@Serializable
data class RolePlayConfig(
    val sayRadius: Int = 0,
    val quietRadius: Int = RolePlayChatSettings.DEFAULT_QUIET_RADIUS,
    val shoutReachesNeighbors: Boolean = true
) {
    fun toSettings(): RolePlayChatSettings = RolePlayChatSettings(sayRadius, quietRadius, shoutReachesNeighbors)
}

/**
 * `areaOfInterest` section of `server.json`: send each player only the changes near them, not every change on a big
 * map. The map is cut into square cells; a player sees their own cell and [radiusCells] cells around it. Off by
 * default (small maps gain nothing); turn it on for worlds with large maps and many players.
 *
 * @property enabled whether the buckets are used
 * @property cellTiles side of a cell in tiles (default 30; keep it at least as wide as the screen)
 * @property radiusCells how many cells around the player's own are included (default 1)
 */
@Serializable
data class AreaOfInterestConfig(val enabled: Boolean = false, val cellTiles: Int = 30, val radiusCells: Int = 1)

/**
 * `instances` section of `server.json`: limits for instanced maps (module `instancing`).
 *
 * @property emptySeconds how long an instance with nobody inside is kept before it closes (default 60; 0 closes it on the next tick)
 * @property max how many instances may be open at once; players are turned away beyond it (default 200)
 */
@Serializable
data class InstancesConfig(val emptySeconds: Int = 60, val max: Int = InstanceSettings.DEFAULT_MAX_INSTANCES) {
    fun toSettings(ticksPerSecond: Int = 20): InstanceSettings = InstanceSettings(emptySeconds.toLong() * ticksPerSecond, max)
}

/**
 * `admin` section of `server.json`: the operator's web panel and REST API (see `AdminHttpServer`). Off by default.
 * It listens on its own port, only on [bindAddress] (this machine by default), and every API call needs the token in
 * `<data dir>/admin-token.txt` (or the `MORGANA_ADMIN_TOKEN` environment variable). The connection is plain HTTP, so reach it
 * from another computer through an SSH tunnel or a TLS-terminating reverse proxy; an address other than loopback is refused
 * unless [allowRemote] says you know that.
 *
 * @property enabled whether the panel runs at all
 * @property port TCP port (0 picks a free one, for tests)
 * @property bindAddress the local address to listen on (default `127.0.0.1`)
 * @property allowRemote permits a [bindAddress] that is not loopback
 */
@Serializable
data class WikiConfig(
    /** False hides the game wiki (the data-driven item, monster and map pages) from players. */
    val enabled: Boolean = true
)

@Serializable
data class AdminConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = "127.0.0.1",
    val allowRemote: Boolean = false
) {
    companion object {
        const val DEFAULT_PORT = 7003

        /** Whether [address] is a loopback address (literal IP or `localhost`). */
        fun isLoopback(address: String): Boolean {
            val a = address.trim().lowercase()
            return a == "localhost" || a == "::1" || a == "[::1]" || a.startsWith("127.")
        }
    }
}

/** Raised for an unreadable or invalid `server.json`; the message says exactly what to fix. */
class ServerConfigException(message: String) : Exception(message)

/**
 * Operator settings stored in `<data dir>/server.json`. A missing file means defaults (every optional module on),
 * so existing installs keep working unchanged.
 *
 * ```json
 * { "schemaVersion": 1, "modules": { "trading": false, "guilds": false } }
 * ```
 * An optional `"storage": { "playerData": "sqlite" }` section selects the player-data backend (default `json`).
 *
 * Only modules mentioned are changed; keys are [OptionalModule.configKey]s. Unknown keys and unknown fields are
 * rejected rather than ignored, so a typo like `"tradng": false` cannot silently leave a module on.
 */
@Serializable
data class ServerConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val modules: Map<String, Boolean> = emptyMap(),
    val storage: StorageConfig = StorageConfig(),
    /** Null when the file has no `tls` section; the caller then picks its default mode. */
    val tls: TlsConfig? = null,
    /** Null when absent: limits are on with the default budgets. */
    val limits: LimitsConfig? = null,
    /** Null when absent: per-IP connection limits are on with the defaults. */
    val connections: ConnectionsConfig? = null,
    /** Null when absent: session resume is on with the default 30-minute grace period and 75-second idle timeout. */
    val session: SessionConfig? = null,
    /** Null when absent: the server hosts the game's art itself. */
    val assets: AssetsConfig? = null,
    /** Null when absent: role-play speech carries across the whole map, shouts reach neighbouring maps. */
    val roleplay: RolePlayConfig? = null,
    /** Null when absent: empty instances close after 60 seconds, at most 200 open at once. */
    val instances: InstancesConfig? = null,
    /** Null when absent: the web admin panel is off. */
    val admin: AdminConfig? = null,
    /** Null when absent: the game wiki is served at `/wiki/` on the web port (when there is one). */
    val wiki: WikiConfig? = null,
    /** Null when absent: area-of-interest buckets are off and every change on a map goes to everyone on it. */
    val areaOfInterest: AreaOfInterestConfig? = null
) {
    /** The configured player-data backend (validated by [ServerConfig.load]). */
    fun playerDataBackend(): PlayerDataBackend = PlayerDataBackend.fromConfigKey(storage.playerData) ?: PlayerDataBackend.JSON

    fun withPlayerData(backend: PlayerDataBackend): ServerConfig = copy(storage = StorageConfig(backend.configKey))

    /** The module switches this config describes. */
    fun moduleFlags(): ModuleFlags =
        ModuleFlags.fromDisabledKeys(modules.filterValues { !it }.keys)

    /** Copy with the given modules switched on or off, keeping everything else. */
    fun withModules(enable: Collection<OptionalModule> = emptyList(), disable: Collection<OptionalModule> = emptyList()): ServerConfig =
        copy(modules = modules + enable.associate { it.configKey to true } + disable.associate { it.configKey to false })

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "server.json"

        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

        fun fileIn(dataDir: Path): Path = dataDir.resolve(FILE_NAME)

        fun load(dataDir: Path): ServerConfig {
            val file = fileIn(dataDir)
            if (!Files.exists(file)) return ServerConfig()
            val config = try {
                json.decodeFromString(serializer(), Files.readString(file))
            } catch (e: Exception) {
                throw ServerConfigException("Invalid $file: ${e.message?.lineSequence()?.first()}")
            }
            if (config.schemaVersion != SCHEMA_VERSION) {
                throw ServerConfigException("$file has schemaVersion ${config.schemaVersion}; this build reads $SCHEMA_VERSION")
            }
            // Built-in keys are checked here; a `plugin:module` key is accepted by shape (the plugin may load later).
            val unknown = config.modules.keys.filter { OptionalModule.fromConfigKey(it) == null && !ModuleRegistry.isCustomId(it) }
            if (unknown.isNotEmpty()) {
                throw ServerConfigException(
                    "$file lists unknown module(s) ${unknown.joinToString()}; valid: ${OptionalModule.entries.joinToString { it.configKey }}, or a plugin module written plugin:module"
                )
            }
            if (PlayerDataBackend.fromConfigKey(config.storage.playerData) == null) {
                throw ServerConfigException(
                    "$file has unknown storage.playerData '${config.storage.playerData}'; valid: ${PlayerDataBackend.entries.joinToString { it.configKey }}"
                )
            }
            config.connections?.let { c ->
                fun check(name: String, value: Int, range: IntRange) {
                    if (value !in range) throw ServerConfigException("$file has connections.$name $value; it must be between ${range.first} and ${range.last}")
                }
                check("maxPerIp", c.maxPerIp, 1..1000)
                check("maxTotal", c.maxTotal, 1..100_000)
                check("burst", c.burst, 1..1000)
                check("newPerMinute", c.newPerMinute, 1..100_000)
                check("strikesToBan", c.strikesToBan, 1..1000)
                check("banMinutes", c.banMinutes, 1..10_080)
                check("maxBanMinutes", c.maxBanMinutes, c.banMinutes..43_200)
                check("preLoginSeconds", c.preLoginSeconds, 5..600)
                c.allow.firstOrNull { AddressRange.parse(it) == null }?.let {
                    throw ServerConfigException("$file has connections.allow entry '$it'; use an IP address or CIDR block such as 10.0.0.0/8")
                }
            }
            config.limits?.let { limits ->
                if (!(limits.scale >= 0.1 && limits.scale <= 100.0)) {
                    throw ServerConfigException("$file has limits.scale ${limits.scale}; it must be between 0.1 and 100")
                }
            }
            config.tls?.let { tls ->
                if (TlsMode.fromConfigKey(tls.mode) == null) {
                    throw ServerConfigException(
                        "$file has unknown tls.mode '${tls.mode}'; valid: ${TlsMode.entries.joinToString { it.configKey }}"
                    )
                }
                if (tls.keystore.isNotBlank() && tls.passwordEnv.isBlank()) {
                    throw ServerConfigException("$file sets tls.keystore but not tls.passwordEnv (the environment variable holding the keystore password)")
                }
            }
            config.session?.let { s ->
                if (s.graceMinutes !in 1..1440) {
                    throw ServerConfigException("$file has session.graceMinutes ${s.graceMinutes}; it must be between 1 and 1440")
                }
                if (s.idleSeconds !in 10..3600) {
                    throw ServerConfigException("$file has session.idleSeconds ${s.idleSeconds}; it must be between 10 and 3600")
                }
                if (s.maxCharacters !in 1..10) {
                    throw ServerConfigException("$file has session.maxCharacters ${s.maxCharacters}; it must be between 1 and 10")
                }
                if (s.combatLogSeconds !in 0..300) {
                    throw ServerConfigException("$file has session.combatLogSeconds ${s.combatLogSeconds}; it must be between 0 and 300")
                }
            }
            config.instances?.let { i ->
                if (i.emptySeconds !in 0..3600) {
                    throw ServerConfigException("$file has instances.emptySeconds ${i.emptySeconds}; it must be between 0 and 3600")
                }
                if (i.max !in 1..10_000) {
                    throw ServerConfigException("$file has instances.max ${i.max}; it must be between 1 and 10000")
                }
            }
            config.roleplay?.let { r ->
                val max = RolePlayChatSettings.MAX_RADIUS
                if (r.sayRadius !in 0..max) {
                    throw ServerConfigException("$file has roleplay.sayRadius ${r.sayRadius}; it must be between 0 (whole map) and $max")
                }
                if (r.quietRadius !in 1..max) {
                    throw ServerConfigException("$file has roleplay.quietRadius ${r.quietRadius}; it must be between 1 and $max")
                }
            }
            config.areaOfInterest?.let { a ->
                if (a.cellTiles !in 8..200) throw ServerConfigException("$file has areaOfInterest.cellTiles ${a.cellTiles}; it must be between 8 and 200")
                if (a.radiusCells !in 1..5) throw ServerConfigException("$file has areaOfInterest.radiusCells ${a.radiusCells}; it must be between 1 and 5")
            }
            config.admin?.let { a ->
                if (a.port !in 0..65535) throw ServerConfigException("$file has admin.port ${a.port}; it must be between 0 and 65535")
                if (a.bindAddress.isBlank()) throw ServerConfigException("$file has an empty admin.bindAddress")
                if (a.enabled && !AdminConfig.isLoopback(a.bindAddress) && !a.allowRemote) {
                    throw ServerConfigException(
                        "$file has admin.bindAddress '${a.bindAddress}', which is not this machine. The panel is plain HTTP; use an SSH tunnel or " +
                            "a TLS reverse proxy, or set admin.allowRemote to true if you accept sending the token unencrypted"
                    )
                }
            }
            config.assets?.let { a ->
                if (a.baseUrl.isNotBlank() && !(a.baseUrl.startsWith("http://") || a.baseUrl.startsWith("https://"))) {
                    throw ServerConfigException("$file has assets.baseUrl '${a.baseUrl}'; it must be blank or start with http:// or https://")
                }
            }
            return config
        }

        fun save(dataDir: Path, config: ServerConfig) {
            Files.createDirectories(dataDir)
            Files.writeString(fileIn(dataDir), json.encodeToString(serializer(), config) + "\n")
        }
    }
}
