package com.morgana.client

import com.morgana.protocol.realms.RealmClassSummary

/** A world the local machine can host, along with its playable classes for the login dropdown. */
data class LocalWorldChoice(
    val slug: String,
    val name: String,
    val classes: List<RealmClassSummary> = emptyList()
)

/** What the Local Worlds screen shows about one world. */
data class LocalWorldDetails(
    val slug: String,
    val name: String,
    /** `starter`, `blank`, `game:<folder>`, `pack:<name>` or `server-data`. */
    val origin: String,
    val folder: String,
    val createdAtMillis: Long? = null,
    val lastPlayedAtMillis: Long? = null,
    val mapCount: Int = 0,
    val classCount: Int = 0,
    /** Config keys of optional systems that are switched off. */
    val disabledFeatures: List<String> = emptyList(),
    val isDefault: Boolean = false
)

/** The world this machine is hosting right now. [address] is what friends on the same network would type, when known. */
data class LocalHostingStatus(
    val slug: String,
    val name: String,
    val port: Int,
    val onlinePlayers: Int,
    val address: String?,
    val encrypted: Boolean
)

/**
 * One starting point the New Realm Wizard offers (a template of ready-made content). [disabledModuleKeys] are the systems the
 * template itself switches off in its `server.json`: the wizard starts its feature list from them.
 */
data class RealmTemplateInfo(val id: String, val title: String, val description: String, val disabledModuleKeys: Set<String> = emptySet())

/**
 * What the New Realm Wizard collects. [disabledModuleKeys] are optional-module config keys (`trading`, `guilds`, ...) to
 * switch off; everything not listed stays on. [noCombat] configures a peaceful realm (`combat.module = "noop"`, combat/spells UI off).
 * [enabledModuleKeys] are systems the chosen template switches off that the creator turned back on.
 * [modernSprites] configures taller `32 x 64` multi-state character/NPC sprite sheets anchored bottom-center.
 */
data class RealmSpec(
    val name: String,
    val templateId: String,
    val disabledModuleKeys: Set<String> = emptySet(),
    val noCombat: Boolean = false,
    val modernSprites: Boolean = false,
    val enabledModuleKeys: Set<String> = emptySet(),
    val uiLayout: String? = null
)

/**
 * Lets a client that can run an embedded server (desktop) offer "New World / Play" and a world picker.
 * `:client-core` only knows this interface; the desktop launcher implements it on top of the server's world library.
 */
interface LocalWorldHost {
    /** Worlds available on this machine, the default world first. */
    fun worlds(): List<LocalWorldChoice>

    /** The world the embedded server will host. */
    var selectedSlug: String

    /** Creates a fresh starter world with this name and selects it. Throws on invalid names or I/O errors. */
    fun createWorld(name: String): LocalWorldChoice

    /** Starting points the New Realm Wizard can offer on this machine (empty: the wizard is not available). */
    val realmTemplates: List<RealmTemplateInfo> get() = emptyList()

    /** Creates a realm from the wizard's choices and selects it. Throws on invalid input or I/O errors. */
    fun createRealm(spec: RealmSpec): LocalWorldChoice = createWorld(spec.name)

    /**
     * A join code for the selected world (`morgana://host:port#fp=...`) to share with friends, or null when the
     * world cannot be joined remotely. [publicHost] is the address friends should use (blank: this machine's LAN address).
     */
    fun inviteCode(publicHost: String): String?

    /** Makes sure the embedded server is running for [selectedSlug] (restarting it if another world was running). */
    fun ensureRunning()

    /** Details of one world, or null when this host cannot describe it. */
    fun details(slug: String): LocalWorldDetails? = null

    /** The world being hosted now, or null when nothing is running (or this host does not report it). */
    fun hostingStatus(): LocalHostingStatus? = null

    /** Stops the embedded server (saving the world through its normal shutdown); the world stays selected. */
    fun stopHosting() {}

    /** Whether [renameWorld], [deleteWorld] and [openFolder] work on this host. */
    val canManageWorlds: Boolean get() = false

    /** Changes a world's display name. Throws on an invalid name or the default world. */
    fun renameWorld(slug: String, newName: String): LocalWorldChoice = throw UnsupportedOperationException("Renaming worlds is not supported here.")

    /** Permanently deletes a world and its saved characters, stopping the server first if it is hosting that world. Throws for the default world. */
    fun deleteWorld(slug: String): Unit = throw UnsupportedOperationException("Deleting worlds is not supported here.")

    /** Opens the world's folder in the system file manager. Returns false when that is not possible. */
    fun openFolder(slug: String): Boolean = false
}
