package com.morgana.server

import com.morgana.protocol.ClientPacket
import java.util.EnumMap

/**
 * Groups of client packets that share a rate budget. Each class is a token bucket: up to [burst] packets at once,
 * refilled at [perMinute]. Players can exceed neither by accident (a human cannot click 10 trades a second), while
 * a script or flood is cut off quickly. [kickAfterDrops] dropped packets inside one [PacketLimiter.WINDOW_MS] window
 * disconnect the connection.
 */
enum class PacketClass(val label: String, val burst: Int, val perMinute: Int, val kickAfterDrops: Int = 100) {
    /** Login attempts. Burst is above the 5-failure cap so that cap still applies first. Each attempt can cost a PBKDF2 hash. */
    AUTH("login", 6, 12, kickAfterDrops = 20),

    /** Anything except login/ping sent before logging in. */
    PRE_AUTH("pre-login", 5, 6, kickAfterDrops = 10),
    /** Roomy on purpose: the client probes every 3 s (20 a minute) plus keep-alives, and a refused ping reads as a dead link. */
    PING("ping", 20, 120),
    CHAT("chat", 6, 40),

    /** Movement and facing; the movement rules separately cap how fast a character may actually move. */
    MOVE("movement", 40, 1800),

    /** Combat and inventory actions: attacks, casts, item use, drops, equipment, hotbar, dialogue. */
    ACTION("action", 20, 600),

    /** Shops, bank, crafting, trading, parties, guilds. */
    ECONOMY("shop/trade/social", 10, 180),

    /** Slash commands (including those typed into chat). */
    COMMAND("command", 10, 120),

    /** F1 content saves and catalog requests: validated, written to disk and broadcast. */
    EDIT("content edit", 10, 60);
}

/** Operator settings for packet limiting (`limits` in `server.json`). */
data class PacketLimitSettings(val enabled: Boolean = true, val scale: Double = 1.0)

enum class LimitVerdict { ALLOW, DROP, KICK }

/** [notice] is a short message for the player, or null to stay silent. */
data class LimitDecision(val verdict: LimitVerdict, val packetClass: PacketClass, val notice: String? = null) {
    val allowed: Boolean get() = verdict == LimitVerdict.ALLOW
}

/**
 * Per-connection packet flood protection for the coordinator.
 *
 * Time comes from the caller ([timeMillis]; the coordinator passes game ticks x 50 ms), so behavior is deterministic and
 * testable and there is no wall clock. The limiter is thread-safe because logins are admitted from network threads
 * before the expensive password hashing.
 *
 * Policy: over-budget packets are dropped (the player gets a short notice at most every [NOTICE_EVERY_MS]); a connection
 * that keeps flooding past its class's [PacketClass.kickAfterDrops] is disconnected.
 */
class PacketLimiter(
    settings: PacketLimitSettings = PacketLimitSettings(),
    private val timeMillis: () -> Long
) {
    private val enabled = settings.enabled
    private val scale = settings.scale.coerceIn(0.1, 100.0)

    private class Bucket(var tokens: Double, var lastMillis: Long)

    private class SessionState(now: Long) {
        val buckets = EnumMap<PacketClass, Bucket>(PacketClass::class.java)
        var windowStart = now
        var windowDrops = 0
        var lastNotice = Long.MIN_VALUE / 2
        /** Set once the connection has been told to leave; later packets from it are ignored without more kicks or logs. */
        var kicked = false
    }

    private val sessions = HashMap<String, SessionState>()
    private val dropped = EnumMap<PacketClass, Long>(PacketClass::class.java)
    private var kicks = 0L

    @Synchronized
    fun admit(sessionId: String, authenticated: Boolean, packet: ClientPacket): LimitDecision {
        val cls = classify(packet, authenticated)
        if (!enabled) return LimitDecision(LimitVerdict.ALLOW, cls)
        val now = timeMillis()
        val state = sessions.getOrPut(sessionId) { SessionState(now) }
        if (state.kicked) {
            dropped[cls] = (dropped[cls] ?: 0L) + 1
            return LimitDecision(LimitVerdict.DROP, cls)
        }
        val bucket = state.buckets.getOrPut(cls) { Bucket(capacity(cls), now) }

        val elapsed = (now - bucket.lastMillis).coerceAtLeast(0)
        bucket.lastMillis = now
        bucket.tokens = minOf(capacity(cls), bucket.tokens + elapsed * refillPerMs(cls))
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return LimitDecision(LimitVerdict.ALLOW, cls)
        }

        dropped[cls] = (dropped[cls] ?: 0L) + 1
        if (now - state.windowStart >= WINDOW_MS) {
            state.windowStart = now
            state.windowDrops = 0
        }
        state.windowDrops++
        if (state.windowDrops >= cls.kickAfterDrops) {
            kicks++
            state.kicked = true
            // Strangers who never logged in get no courtesy message.
            return LimitDecision(
                LimitVerdict.KICK, cls,
                if (authenticated) "Disconnected for flooding the server with ${cls.label} requests." else null
            )
        }
        val notice = if (authenticated && now - state.lastNotice >= NOTICE_EVERY_MS) {
            state.lastNotice = now
            "Slow down: too many ${cls.label} requests."
        } else null
        return LimitDecision(LimitVerdict.DROP, cls, notice)
    }

    @Synchronized
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** Total packets dropped per class since startup (for monitoring and tests). */
    @Synchronized
    fun droppedCounts(): Map<PacketClass, Long> = dropped.toMap()

    @Synchronized
    fun kickCount(): Long = kicks

    private fun capacity(cls: PacketClass): Double = maxOf(1.0, cls.burst * scale)
    private fun refillPerMs(cls: PacketClass): Double = cls.perMinute * scale / 60_000.0

    companion object {
        const val WINDOW_MS = 10_000L
        const val NOTICE_EVERY_MS = 5_000L

        /**
         * The budget a packet draws from. Exhaustive on purpose: adding a client packet fails to compile until it is
         * classified, so no packet type can silently bypass the limiter.
         */
        fun classify(packet: ClientPacket, authenticated: Boolean): PacketClass = when (packet) {
            is ClientPacket.Authenticate, is ClientPacket.ResumeSession -> PacketClass.AUTH
            // Picking, making and deleting characters happens between the password and the world, and is rare.
            is ClientPacket.ChooseCharacter, is ClientPacket.CreateCharacter, is ClientPacket.DeleteCharacter -> PacketClass.AUTH
            is ClientPacket.Ping, is ClientPacket.RequestRealmInfo -> PacketClass.PING
            else -> if (!authenticated) PacketClass.PRE_AUTH else classifyAuthenticated(packet)
        }

        private fun classifyAuthenticated(packet: ClientPacket): PacketClass = when (packet) {
            is ClientPacket.Authenticate, is ClientPacket.ResumeSession -> PacketClass.AUTH
            is ClientPacket.Ping, is ClientPacket.RequestRealmInfo -> PacketClass.PING
            is ClientPacket.ChooseCharacter, is ClientPacket.CreateCharacter, is ClientPacket.DeleteCharacter -> PacketClass.AUTH
            is ClientPacket.MoveInput, is ClientPacket.FaceDirection, is ClientPacket.JumpTo -> PacketClass.MOVE
            is ClientPacket.SendChat -> PacketClass.CHAT
            is ClientPacket.AdminSlashCommand -> PacketClass.COMMAND
            is ClientPacket.Attack, is ClientPacket.CastSpell, is ClientPacket.CustomAction, is ClientPacket.InteractFacing,
            is ClientPacket.PickupGroundItem, is ClientPacket.UseInventorySlot, is ClientPacket.DropInventorySlot,
            is ClientPacket.SwapInventorySlots, is ClientPacket.MergeGear, is ClientPacket.SortInventory, is ClientPacket.UnequipSlot, is ClientPacket.AllocateStatPoint,
            is ClientPacket.SetPkMode, is ClientPacket.SetHotbarSlot, is ClientPacket.SelectDialogueChoice, is ClientPacket.SubmitTextInput -> PacketClass.ACTION
            is ClientPacket.ShopBuy, is ClientPacket.ShopSell, is ClientPacket.ShopBuyback, is ClientPacket.BankDeposit, is ClientPacket.BankWithdraw,
            is ClientPacket.GuildBankDeposit, is ClientPacket.GuildBankWithdraw, is ClientPacket.GuildBankSetAccess, is ClientPacket.GuildBankUpgrade,
            is ClientPacket.CraftRecipe, is ClientPacket.PartyAction, is ClientPacket.GuildAction,
            is ClientPacket.TradeAction, is ClientPacket.SendMail, is ClientPacket.MailAction,
            is ClientPacket.FriendAction, is ClientPacket.MarketAction,
            is ClientPacket.IgnoreAction -> PacketClass.ECONOMY
            is ClientPacket.RequestAdminCatalog, is ClientPacket.SaveMapDefinition, is ClientPacket.SaveItemDefinition,
            is ClientPacket.SaveNpcDefinition, is ClientPacket.SaveSpellDefinition, is ClientPacket.SaveShopDefinition,
            is ClientPacket.SaveResourceDefinition, is ClientPacket.SaveClassDefinition, is ClientPacket.SaveActionSkillDefinition, is ClientPacket.SaveWeaponSetDefinition, is ClientPacket.SetModules, is ClientPacket.SaveGameDefinition, is ClientPacket.SaveRecipeDefinition, is ClientPacket.SaveQuestDefinition,
            is ClientPacket.SaveEventDefinition, is ClientPacket.SaveCommonEventDefinition, is ClientPacket.TestEventCommands,
            is ClientPacket.SaveAnimationDefinition, is ClientPacket.ImportGameArt -> PacketClass.EDIT
            is ClientPacket.Unknown, is ClientPacket.RequestMapResync, is ClientPacket.RequestWorldMap, is ClientPacket.PluginMessage,
            is ClientPacket.PluginAction, is ClientPacket.PluginWindowClosed, is ClientPacket.TipsControl -> PacketClass.ACTION
        }
    }
}

/** Longest accepted text fields. Anything longer is refused before it is stored, hashed or broadcast to other players. */
object InputLimits {
    const val MAX_PASSWORD = 128
    const val MAX_CHAT = 300
    const val MAX_COMMAND = 300
    const val MAX_PLAYER_NAME_ARG = 32
    const val MAX_GUILD_TEXT = 200
    const val MAX_GUILD_TAG = 8

    /** Human-readable reason if a packet carries an over-long field, else null. */
    fun violation(packet: ClientPacket): String? = when (packet) {
        is ClientPacket.SendChat -> when {
            packet.message.length > MAX_CHAT -> "Message too long (max $MAX_CHAT characters)."
            packet.targetPlayerName.length > MAX_PLAYER_NAME_ARG -> "Invalid recipient."
            else -> null
        }
        is ClientPacket.AdminSlashCommand -> if (packet.commandLine.length > MAX_COMMAND) "Command too long (max $MAX_COMMAND characters)." else null
        is ClientPacket.PartyAction -> if (packet.targetPlayerName.length > MAX_PLAYER_NAME_ARG) "Invalid player name." else null
        is ClientPacket.TradeAction -> if (packet.targetPlayerName.length > MAX_PLAYER_NAME_ARG) "Invalid player name." else null
        is ClientPacket.SendMail -> when {
            packet.recipientName.length > MAX_PLAYER_NAME_ARG -> "Invalid recipient."
            packet.subject.length > 200 || packet.body.length > 2000 -> "Letter too long."
            else -> null
        }
        is ClientPacket.IgnoreAction -> if (packet.name.length > MAX_PLAYER_NAME_ARG) "Invalid player name." else null
        is ClientPacket.MarketAction -> if (packet.listingId.length > 64) "Invalid listing." else null
        is ClientPacket.FriendAction -> if (packet.name.length > MAX_PLAYER_NAME_ARG) "Invalid player name." else null
        is ClientPacket.MailAction -> if (packet.mailId.length > 64) "Invalid letter." else null
        is ClientPacket.GuildAction -> when {
            packet.primaryArg.length > MAX_GUILD_TEXT -> "Text too long (max $MAX_GUILD_TEXT characters)."
            packet.secondaryArg.length > MAX_GUILD_TAG -> "Guild tag too long (max $MAX_GUILD_TAG characters)."
            else -> null
        }
        is ClientPacket.CustomAction -> if (packet.actionId.length > 64 || packet.params.size > 32) "Action payload too large." else null
        is ClientPacket.PluginMessage -> if (
            packet.channel.length > 64 || packet.payload.size > 32 ||
            packet.payload.any { (k, v) -> k.length > 64 || v.length > 1000 }
        ) "Plugin message too large." else null
        is ClientPacket.PluginAction -> if (
            packet.windowId.length > 64 || packet.buttonId.length > 64 || packet.values.size > 6 ||
            packet.values.any { (k, v) -> k.length > 64 || v.length > 500 }
        ) "Plugin action too large." else null
        is ClientPacket.PluginWindowClosed -> if (packet.windowId.length > 64) "Plugin action too large." else null
        is ClientPacket.CreateCharacter -> if (packet.name.length > 64) "Name too long." else null
        is ClientPacket.ChooseCharacter -> if (packet.characterId.length > 64) "Invalid character." else null
        is ClientPacket.DeleteCharacter -> if (packet.characterId.length > 64 || packet.confirmName.length > 64) "Invalid character." else null
        is ClientPacket.SubmitTextInput -> if (packet.value.length > 200) "Input too long (max 200 characters)." else null
        is ClientPacket.ShopSell -> if (packet.slotIndices.size > 200) "Too many slots." else null
        else -> null
    }
}
