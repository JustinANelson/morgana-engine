package com.morgana.client.preview

import com.morgana.client.LocalHostingStatus
import com.morgana.client.LocalWorldChoice
import com.morgana.client.LocalWorldDetails
import com.morgana.client.LocalWorldHost
import com.morgana.client.RealmTemplateInfo

/**
 * A pretend [LocalWorldHost] for previewing the Local Worlds tab where nothing can really be hosted (the web client:
 * a page opened with `?fakeWorlds=1`, see [fakeWorldsFromQuery]) and for tests. It keeps everything in memory.
 */
class DemoLocalWorldHost(private val nowMs: () -> Long = System::currentTimeMillis) : LocalWorldHost {
    private val details = linkedMapOf<String, LocalWorldDetails>()
    private var hostingSlug: String? = null
    private var players = 0

    init {
        val now = nowMs()
        details["default"] = LocalWorldDetails("default", "Default World", "server-data", "C:\\Morgana\\server-data", mapCount = 3, classCount = 4, isDefault = true)
        details["dungeon-test"] = LocalWorldDetails(
            "dungeon-test", "Dungeon Test", "game:blank", "C:\\Morgana\\worlds\\dungeon-test", createdAtMillis = now - 9 * DAY,
            lastPlayedAtMillis = now - 2 * HOUR, mapCount = 7, classCount = 3, disabledFeatures = listOf("guilds")
        )
        details["friday-night"] = LocalWorldDetails(
            "friday-night", "Friday Night Crew", "blank", "C:\\Morgana\\worlds\\friday-night", createdAtMillis = now - 30 * DAY,
            lastPlayedAtMillis = now - 3 * DAY, mapCount = 8, classCount = 5
        )
        details["sandbox"] = LocalWorldDetails("sandbox", "Scratch Sandbox", "blank", "C:\\Morgana\\worlds\\sandbox", createdAtMillis = now - DAY, mapCount = 1, classCount = 1)
        hostingSlug = "dungeon-test"
        players = 2
    }

    override var selectedSlug: String = "dungeon-test"

    override fun worlds(): List<LocalWorldChoice> = details.values.map { LocalWorldChoice(it.slug, it.name) }

    override fun createWorld(name: String): LocalWorldChoice {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "world" }
        details[slug] = LocalWorldDetails(slug, name.trim(), "blank", "C:\\Morgana\\worlds\\$slug", createdAtMillis = nowMs(), mapCount = 1, classCount = 1)
        selectedSlug = slug
        return LocalWorldChoice(slug, name.trim())
    }

    override val realmTemplates: List<RealmTemplateInfo>
        get() = listOf(
            RealmTemplateInfo("blank", "Blank", "An empty starting realm with one map and basic defaults, ready to build from scratch."),
            RealmTemplateInfo("roguelike", "Ashen Keep", "Reference co-op dungeon crawler & roguelike: a multiplayer hub and procedural Sunken Crypt floor with action combat, paperdoll gear, and boss fight."),
            RealmTemplateInfo("demo-roguelike", "Roguelike demo", "A bigger demo co-op roguelike: a town and twelve generated floors down to the final boss.")
        )

    override fun inviteCode(publicHost: String): String? = "morgana://${publicHost.ifBlank { "192.168.1.20" }}:7001#fp=demo"

    override fun ensureRunning() {
        if (hostingSlug != selectedSlug) { hostingSlug = selectedSlug; players = 0 }
    }

    override fun details(slug: String): LocalWorldDetails? = details[slug]

    override fun hostingStatus(): LocalHostingStatus? {
        val slug = hostingSlug ?: return null
        return LocalHostingStatus(slug, details[slug]?.name ?: slug, 7001, players, "192.168.1.20", encrypted = true)
    }

    override fun stopHosting() { hostingSlug = null; players = 0 }

    override val canManageWorlds: Boolean get() = true

    override fun renameWorld(slug: String, newName: String): LocalWorldChoice {
        val d = details[slug] ?: throw IllegalArgumentException("No such world '$slug'.")
        require(!d.isDefault) { "The default world cannot be renamed." }
        require(newName.isNotBlank()) { "Give the realm a name." }
        details[slug] = d.copy(name = newName.trim())
        return LocalWorldChoice(slug, newName.trim())
    }

    override fun deleteWorld(slug: String) {
        require(details[slug]?.isDefault == false) { "The default world cannot be deleted." }
        if (hostingSlug == slug) stopHosting()
        details.remove(slug)
        if (selectedSlug == slug) selectedSlug = "default"
    }

    override fun openFolder(slug: String): Boolean = false

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

/** True when a page address asks for the pretend local worlds (`?fakeWorlds=1`). */
fun fakeWorldsFromQuery(search: String): Boolean =
    search.removePrefix("?").split('&').any { it == "fakeWorlds=1" }
