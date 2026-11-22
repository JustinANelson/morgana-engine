package com.morgana.domain.access

import kotlinx.serialization.Serializable

/**
 * Granular permissions backing the 5-tier Morgana access system.
 */
@Serializable
enum class PermissionFlag {
    CHAT,
    PARTY,
    GUILD,
    TRADE,
    KICK_MUTE,
    WARP_TO_PLAYER,
    SUMMON_PLAYER,
    BAN_PLAYER,
    WARP_TO_MAP,
    EDIT_MAP,
    EDIT_CONTENT,
    SPAWN_ITEM,
    SET_ACCESS,
    SERVER_MAINTENANCE,

    /** Describe a scene as an unnamed narrator (`/narrate`, role-play chat). */
    NARRATE
}

/**
 * 5-tier Morgana Access Level hierarchy (0..4).
 */
@Serializable
enum class AccessLevel(
    val rank: Int,
    val title: String,
    val nameplateColorHex: String,
    val defaultPermissions: Set<PermissionFlag>
) {
    PLAYER(
        rank = 0,
        title = "Player",
        nameplateColorHex = "#FFFFFF",
        defaultPermissions = setOf(
            PermissionFlag.CHAT,
            PermissionFlag.PARTY,
            PermissionFlag.GUILD,
            PermissionFlag.TRADE
        )
    ),
    MONITOR(
        rank = 1,
        title = "Monitor",
        nameplateColorHex = "#38BDF8",
        defaultPermissions = PLAYER.defaultPermissions + setOf(
            PermissionFlag.KICK_MUTE,
            PermissionFlag.WARP_TO_PLAYER,
            PermissionFlag.SUMMON_PLAYER,
            PermissionFlag.NARRATE
        )
    ),
    MAPPER(
        rank = 2,
        title = "Mapper",
        nameplateColorHex = "#4ADE80",
        defaultPermissions = MONITOR.defaultPermissions + setOf(
            PermissionFlag.WARP_TO_MAP,
            PermissionFlag.EDIT_MAP
        )
    ),
    DEVELOPER(
        rank = 3,
        title = "Developer",
        nameplateColorHex = "#C084FC",
        defaultPermissions = MAPPER.defaultPermissions + setOf(
            PermissionFlag.EDIT_CONTENT,
            PermissionFlag.SPAWN_ITEM,
            PermissionFlag.BAN_PLAYER
        )
    ),
    OWNER(
        rank = 4,
        title = "Owner",
        nameplateColorHex = "#FACC15",
        defaultPermissions = PermissionFlag.entries.toSet()
    );

    fun hasAtLeast(other: AccessLevel): Boolean = this.rank >= other.rank

    fun hasPermission(
        flag: PermissionFlag,
        extraPermissions: Set<PermissionFlag> = emptySet(),
        revokedPermissions: Set<PermissionFlag> = emptySet()
    ): Boolean {
        if (flag in revokedPermissions && this != OWNER) return false
        return flag in defaultPermissions || flag in extraPermissions
    }

    fun canOpenAdminPanel(extraPermissions: Set<PermissionFlag> = emptySet()): Boolean =
        hasAtLeast(MAPPER) || PermissionFlag.EDIT_MAP in extraPermissions || PermissionFlag.EDIT_CONTENT in extraPermissions

    companion object {
        fun fromRank(rank: Int): AccessLevel =
            entries.firstOrNull { it.rank == rank }
                ?: if (rank > OWNER.rank) OWNER else PLAYER
    }
}

