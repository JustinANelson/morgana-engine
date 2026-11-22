package com.morgana.server

import com.morgana.persistence.AddressRange
import com.morgana.persistence.ConnectionsConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.EnumMap

/** Receives reports of abusive behavior by the address that caused it; repeated strikes lead to a temporary ban. */
fun interface AbuseReporter {
    fun strike(address: InetAddress, reason: String)

    companion object {
        val NONE = AbuseReporter { _, _ -> }
    }
}

enum class GateVerdict { ALLOW, BANNED, TOO_MANY_FROM_ADDRESS, TOO_FAST, SERVER_FULL }

/** A temporary ban. [key] is the address (IPv6: its /64 block, written `...:0:0:0:0/64`). */
@Serializable
data class IpBan(val key: String, val untilMillis: Long, val reason: String)

/** Resolved [ConnectionsConfig]. */
data class ConnectionGateSettings(
    val enabled: Boolean = true,
    val maxPerIp: Int = 8,
    val maxTotal: Int = 1000,
    val burst: Int = 10,
    val newPerMinute: Int = 30,
    val strikesToBan: Int = 5,
    val banMinutes: Int = 10,
    val maxBanMinutes: Int = 1440,
    val preLoginSeconds: Int = 30,
    val allow: List<AddressRange> = emptyList()
) {
    companion object {
        fun from(config: ConnectionsConfig?): ConnectionGateSettings {
            val c = config ?: ConnectionsConfig()
            return ConnectionGateSettings(
                c.enabled, c.maxPerIp, c.maxTotal, c.burst, c.newPerMinute, c.strikesToBan, c.banMinutes,
                c.maxBanMinutes, c.preLoginSeconds, c.allow.mapNotNull { AddressRange.parse(it) }
            )
        }
    }
}

/**
 * Admission control for new TCP connections, applied before TLS or any packet is read:
 * - **per-address cap** on simultaneous connections, and a **server-wide cap**
 * - **connection-rate bucket** per address (a script reconnecting in a loop is refused, and counts as abuse)
 * - **strikes**: other parts of the server report abuse (packet floods, repeated failed logins, silent connections);
 *   [ConnectionsConfig.strikesToBan] strikes within 10 minutes ban the address, with the ban doubling on each repeat
 * - **bans**, automatic or set by an operator, persisted in [banFile] so a restart does not clear them
 *
 * Loopback connections and the configured allow-list are never limited, struck, or banned. Behind a reverse proxy or
 * tunnel every player appears to come from the proxy's address, so add it to the allow-list (and rely on the proxy's
 * own limits) or the whole community would share one budget.
 *
 * IPv6 clients are tracked per /64 block, since a single subscriber controls a whole /64 and could otherwise rotate
 * addresses to dodge limits. Time is injected for tests. Thread-safe.
 */
class ConnectionGate(
    private val settings: ConnectionGateSettings = ConnectionGateSettings(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val banFile: Path? = null
) : AbuseReporter {
    private val logger = LoggerFactory.getLogger("Security")
    private val json = Json { prettyPrint = true }

    /** Called (outside the gate's lock) with the ban key whenever an address becomes banned, e.g. to close its live connections. */
    @Volatile
    var onBan: ((String) -> Unit)? = null

    private class Entry(var tokens: Double, var lastRefill: Long) {
        var connections = 0
        val strikes = ArrayDeque<Long>()
        var banLevel = 0
        var lastBanAt = Long.MIN_VALUE / 2
        var touched = lastRefill
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val bans = LinkedHashMap<String, IpBan>()
    private var total = 0
    private var calls = 0
    private val refused = EnumMap<GateVerdict, Long>(GateVerdict::class.java)

    init {
        loadBans()
    }

    // ------------------------------------------------------------------------------------------------
    // Admission
    // ------------------------------------------------------------------------------------------------

    /** Decides about a new connection. Every [GateVerdict.ALLOW] must later be paired with one [release]. */
    fun admit(address: InetAddress): GateVerdict {
        var newlyBanned: String? = null
        val verdict = synchronized(lock) { admitLocked(address) { newlyBanned = it } }
        newlyBanned?.let { onBan?.invoke(it) } // outside the lock: listeners may call back into the server
        return verdict
    }

    private fun admitLocked(address: InetAddress, onBanned: (String) -> Unit): GateVerdict {
        if (!settings.enabled || isExempt(address)) {
            total++
            return GateVerdict.ALLOW
        }
        val now = nowMillis()
        maybePrune(now)
        val key = keyOf(address)
        if (activeBan(key, now) != null) return refuse(GateVerdict.BANNED)
        if (total >= settings.maxTotal) return refuse(GateVerdict.SERVER_FULL)
        val entry = entries.getOrPut(key) { Entry(settings.burst.toDouble(), now) }
        entry.touched = now
        if (entry.connections >= settings.maxPerIp) return refuse(GateVerdict.TOO_MANY_FROM_ADDRESS)

        val elapsed = (now - entry.lastRefill).coerceAtLeast(0)
        entry.lastRefill = now
        entry.tokens = minOf(settings.burst.toDouble(), entry.tokens + elapsed * settings.newPerMinute / 60_000.0)
        if (entry.tokens < 1.0) {
            strikeLocked(key, entry, now, "connection flood")?.let(onBanned)
            return refuse(GateVerdict.TOO_FAST)
        }
        entry.tokens -= 1.0
        entry.connections++
        total++
        return GateVerdict.ALLOW
    }

    /** True when [address] is banned right now (exempt addresses never are). Counts nothing and costs no tokens. */
    fun isBanned(address: InetAddress): Boolean = synchronized(lock) {
        settings.enabled && !isExempt(address) && activeBan(keyOf(address), nowMillis()) != null
    }

    fun release(address: InetAddress) {
        synchronized(lock) {
            total = (total - 1).coerceAtLeast(0)
            if (!settings.enabled || isExempt(address)) return
            entries[keyOf(address)]?.let { it.connections = (it.connections - 1).coerceAtLeast(0) }
        }
    }

    private fun refuse(verdict: GateVerdict): GateVerdict {
        refused[verdict] = (refused[verdict] ?: 0L) + 1
        return verdict
    }

    // ------------------------------------------------------------------------------------------------
    // Strikes and bans
    // ------------------------------------------------------------------------------------------------

    override fun strike(address: InetAddress, reason: String) {
        if (!settings.enabled) return
        val banned = synchronized(lock) {
            if (isExempt(address)) return
            val now = nowMillis()
            val key = keyOf(address)
            val entry = entries.getOrPut(key) { Entry(settings.burst.toDouble(), now) }
            entry.touched = now
            strikeLocked(key, entry, now, reason)
        }
        if (banned != null) onBan?.invoke(banned)
    }

    /** Bans [address] for [minutes] (operator action). Returns null if the address is exempt (loopback or allow-listed). */
    fun ban(address: InetAddress, minutes: Long, reason: String): IpBan? {
        val ban = synchronized(lock) {
            if (isExempt(address)) return null
            val now = nowMillis()
            val key = keyOf(address)
            val b = IpBan(key, now + minutes.coerceIn(1, MAX_MANUAL_MINUTES) * 60_000L, reason)
            bans[key] = b
            saveBans()
            logger.warn("Banned {} until {}: {}", key, b.untilMillis, reason)
            b
        }
        onBan?.invoke(ban.key)
        return ban
    }

    /** Lifts a ban and forgives the address's strikes. Returns false if it was not banned. */
    fun unban(address: InetAddress): Boolean = synchronized(lock) {
        val key = keyOf(address)
        entries[key]?.let { it.strikes.clear(); it.banLevel = 0 }
        val removed = bans.remove(key) != null
        if (removed) saveBans()
        removed
    }

    /** Currently active bans, soonest to expire first. */
    fun bans(): List<IpBan> = synchronized(lock) {
        val now = nowMillis()
        if (bans.values.removeAll { it.untilMillis <= now }) saveBans()
        bans.values.sortedBy { it.untilMillis }
    }

    /** Records a strike; returns the ban key if this strike caused a ban. Must be called with [lock] held. */
    private fun strikeLocked(key: String, entry: Entry, now: Long, reason: String): String? {
        while (entry.strikes.isNotEmpty() && now - entry.strikes.first() > STRIKE_WINDOW_MS) entry.strikes.removeFirst()
        entry.strikes.addLast(now)
        if (entry.strikes.size < settings.strikesToBan) return null

        if (now - entry.lastBanAt > LEVEL_DECAY_MS) entry.banLevel = 0
        val minutes = minOf(settings.banMinutes.toLong() shl entry.banLevel.coerceAtMost(20), settings.maxBanMinutes.toLong())
        entry.banLevel++
        entry.lastBanAt = now
        entry.strikes.clear()
        bans[key] = IpBan(key, now + minutes * 60_000L, reason)
        saveBans()
        logger.warn("Temporarily banned {} for {} minutes: {} (repeated abuse)", key, minutes, reason)
        return key
    }

    private fun activeBan(key: String, now: Long): IpBan? {
        val ban = bans[key] ?: return null
        if (ban.untilMillis > now) return ban
        bans.remove(key)
        saveBans()
        return null
    }

    // ------------------------------------------------------------------------------------------------
    // Bookkeeping
    // ------------------------------------------------------------------------------------------------

    /** Seconds a connection may stay without logging in before it is closed. */
    val preLoginSeconds: Int get() = settings.preLoginSeconds

    fun isExempt(address: InetAddress): Boolean =
        address.isLoopbackAddress || settings.allow.any { it.contains(address) }

    /** Number of addresses currently tracked (bounded; idle ones are pruned). */
    fun trackedAddresses(): Int = synchronized(lock) { entries.size }

    /** Connections refused since startup, by reason. */
    fun refusedCounts(): Map<GateVerdict, Long> = synchronized(lock) { refused.toMap() }

    fun activeConnections(): Int = synchronized(lock) { total }

    /** Forgets idle addresses so the table cannot grow without bound. */
    private fun maybePrune(now: Long) {
        if (++calls % PRUNE_EVERY != 0 && entries.size < MAX_TRACKED) return
        entries.entries.removeAll { (key, e) ->
            e.connections == 0 && now - e.touched > IDLE_FORGET_MS && key !in bans
        }
        if (entries.size >= MAX_TRACKED) { // still too many: drop the longest idle ones, never active connections
            entries.entries.filter { it.value.connections == 0 && it.key !in bans }
                .sortedBy { it.value.touched }.take(entries.size - MAX_TRACKED / 2)
                .forEach { entries.remove(it.key) }
        }
        if (bans.values.removeAll { it.untilMillis <= now }) saveBans()
    }

    // ------------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------------

    private fun loadBans() {
        val file = banFile ?: return
        if (!Files.isRegularFile(file)) return
        try {
            val now = nowMillis()
            json.decodeFromString(ListSerializer(IpBan.serializer()), Files.readString(file))
                .filter { it.untilMillis > now }
                .forEach { bans[it.key] = it }
        } catch (e: Exception) {
            logger.warn("Ignoring unreadable ban list {}: {}", file, e.message)
        }
    }

    private fun saveBans() {
        val file = banFile ?: return
        try {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(ListSerializer(IpBan.serializer()), bans.values.toList()))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.warn("Could not save ban list {}: {}", file, e.message)
        }
    }

    companion object {
        const val STRIKE_WINDOW_MS = 10 * 60_000L
        const val LEVEL_DECAY_MS = 24 * 3_600_000L
        const val IDLE_FORGET_MS = 15 * 60_000L
        const val MAX_MANUAL_MINUTES = 43_200L
        private const val PRUNE_EVERY = 256
        private const val MAX_TRACKED = 50_000

        /** The identity limits apply to: the address itself for IPv4, the /64 block for IPv6. */
        fun keyOf(address: InetAddress): String {
            if (address !is Inet6Address) return address.hostAddress
            val block = ByteArray(16)
            System.arraycopy(address.address, 0, block, 0, 8)
            return InetAddress.getByAddress(block).hostAddress.substringBefore('%') + "/64"
        }
    }
}
