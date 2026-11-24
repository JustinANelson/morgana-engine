package com.morgana.domain.sim

/**
 * Genre tags for systems. A genre is a *tag* on a system, never a layer of its own: one system (instancing, say) can serve
 * several genres. A template picks the systems its genre needs; see ADR 0018. The tags are suggestions that group systems
 * in the editors and docs, not rules the engine enforces.
 */
object SystemTags {
    const val RPG = "rpg"
    const val ROGUELIKE = "roguelike"
    const val FARMING = "farming"
    const val ROLEPLAY = "roleplay"
    const val ACTION = "action"
    const val SURVIVAL = "survival"

    /** Every tag, with the name shown to people. */
    val TITLES: Map<String, String> = linkedMapOf(
        RPG to "Online RPG",
        ROGUELIKE to "Roguelike and dungeon crawler",
        FARMING to "Farming and life sim",
        ROLEPLAY to "Role-play",
        ACTION to "Action",
        SURVIVAL to "Survival and crafting"
    )
}

/**
 * The "Optional Module" tier from the feature tiers in `docs/DESIGN.md`: systems a server operator can switch
 * off without breaking the Core. Core code paths must never depend on these. In the project's vocabulary
 * (ADR 0018) an optional module is a **system**.
 *
 * [configKey] is the name used in `server.json` (`"modules": { "<configKey>": false }`).
 * [tags] are the genres the system serves ([SystemTags]); [requires] are the [configKey]s of systems that must also be on
 * for this one to work at all (a softer dependency, one that merely degrades, is a warning in the editors instead).
 */
enum class OptionalModule(
    val configKey: String,
    val displayName: String,
    val description: String = "",
    val tags: Set<String> = emptySet(),
    val requires: Set<String> = emptySet()
) {
    PARTIES("parties", "Parties", "Group up with friends, share XP and chat privately.", setOf(SystemTags.RPG, SystemTags.ROGUELIKE)),
    GUILDS("guilds", "Guilds", "Player-run communities with ranks, tags and guild chat.", setOf(SystemTags.RPG)),
    TRADING("trading", "Player trading", "Safe two-sided item and gold trades between players.", setOf(SystemTags.RPG)),
    CRAFTING("crafting", "Crafting", "Recipes, crafting stations and gathering-to-gear progression.", setOf(SystemTags.RPG, SystemTags.ROGUELIKE, SystemTags.FARMING)),
    PROJECTILES("projectiles", "Projectiles (bows and bolt spells)", "Ranged weapons and spells that fly across the map.", setOf(SystemTags.RPG, SystemTags.ROGUELIKE, SystemTags.ACTION)),
    SUB_TILE_MOVEMENT("subTileMovement", "Free pixel movement", "Smooth pixel movement instead of stepping tile by tile.", setOf(SystemTags.ROGUELIKE, SystemTags.FARMING, SystemTags.ACTION)),
    SOFT_SEAMS("softSeams", "Soft map seams", "Show monsters, players and loot across shared map borders, and glide smoothly when crossing them.", setOf(SystemTags.RPG, SystemTags.ROLEPLAY)),
    INSTANCING("instancing", "Instanced maps", "Maps marked instanced become a private copy for each party or solo player, closed once empty.", setOf(SystemTags.ROGUELIKE, SystemTags.FARMING)),
    ROLE_PLAY_CHAT("rolePlayChat", "Role-play chat", "/me actions, out-of-character talk, shouts, quiet speech and staff narration, with optional speech range.", setOf(SystemTags.ROLEPLAY, SystemTags.RPG)),
    FRIENDS("friends", "Friends list", "Keep a list of friends and see when they come online or go offline.", setOf(SystemTags.RPG, SystemTags.ROLEPLAY)),
    EXPLORATION("exploration", "World map and fog of war", "Characters reveal the minimap as they explore, and a world map shows every area they have found.", setOf(SystemTags.RPG, SystemTags.ROGUELIKE)),
    IGNORE("ignore", "Ignore list", "Silence chat, whispers, mail and invitations from chosen players (staff cannot be ignored).", setOf(SystemTags.RPG, SystemTags.ROLEPLAY)),
    MARKET("market", "Player market", "List items for sale for the world currency and buy what other players list; sellers are paid by mail (needs the mail module).", setOf(SystemTags.RPG), setOf("mail")),
    MAIL("mail", "Mail", "Send letters with an item attached to any character, online or not, and collect them from a mailbox.", setOf(SystemTags.RPG)),

    // The engine's own rule systems. Each is installed through the kernel seams (see GameSystem) and is inert unless the game's
    // rules use it; switching one off here removes it from play, and a Lua plugin can replace it (register.plugin replaces).
    REBIRTH("rebirth", "Rebirth", "Reset a character to a lower level for lasting rewards (needs a rebirth section in the game rules).", setOf(SystemTags.RPG)),
    PROMOTION("promotion", "Promotion ranks", "Class ranks earned at set levels that unlock spells and gear (needs a promotion section in the game rules).", setOf(SystemTags.RPG)),
    ARENA("arena", "Arena", "Queue for rated team fights with no death penalty (needs an arena section in the game rules).", setOf(SystemTags.RPG, SystemTags.ACTION)),
    GUILD_WAR("guildWar", "Guild War", "Scheduled wars where guilds fight for an area (needs a guildWar section in the game rules).", setOf(SystemTags.RPG), setOf("guilds")),
    GUILD_BANK("guildBank", "Guild bank", "A shared vault for each guild (needs a guildBank section in the game rules).", setOf(SystemTags.RPG), setOf("guilds")),
    PETS("pets", "Pets", "Followers that fight beside their owner and level up (needs pet monsters in the content).", setOf(SystemTags.RPG, SystemTags.FARMING)),
    STALLS("stalls", "Player stalls", "Players set up a stall and sell from it while they stand still.", setOf(SystemTags.RPG)),
    DURABILITY("durability", "Gear wear", "Weapons and armour wear out with use and can be repaired (needs a durability section in the game rules).", setOf(SystemTags.RPG, SystemTags.ROGUELIKE)),
    GEAR_UPGRADES("gearUpgrades", "Gear upgrades", "Sockets, gems, tier and quality upgrades on worn gear (needs a gearUpgrade section in the game rules).", setOf(SystemTags.RPG)),
    RESPEC("respec", "Attribute respec", "Take back spent attribute points from an NPC.", setOf(SystemTags.RPG)),
    THREAT("threat", "Threat table", "Monsters follow whoever has done the most, not whoever hit last (needs threat settings in combat.npcs).", setOf(SystemTags.RPG, SystemTags.ROGUELIKE)),
    BOSS_ENCOUNTERS("bossEncounters", "Boss encounters", "Scripted boss fights with phases, ground attacks and called adds.", setOf(SystemTags.ROGUELIKE, SystemTags.RPG)),
    LUA_PLUGINS("luaPlugins", "Lua plugins", "Lua plugins in the game's scripts that add to or replace the engine's systems.", SystemTags.TITLES.keys),
    RUN_LIFECYCLE("runLifecycle", "Dungeon runs", "Track dungeon runs with timers, custom seeds, extraction or ephemeral runs, and optional permadeath (needs a runs section in the game rules).", setOf(SystemTags.ROGUELIKE), setOf("instancing")),
    BOONS("boons", "Roguelike boons", "Offer and stack roguelike boon upgrades (/boon) that can clear when a run ends (needs boons in the game rules).", setOf(SystemTags.ROGUELIKE)),
    SKILL_TREES("skillTrees", "Skill trees", "Per-class skill and talent trees (/skills) spending points earned on level-up (needs skillTree in the game rules).", setOf(SystemTags.ROGUELIKE, SystemTags.RPG)),
    WORLD_STATE("worldState", "World state", "Things placed on a map that stay there: walls, chests, signs, the base of base building and claims (needs worldObjects in the game rules).", setOf(SystemTags.SURVIVAL, SystemTags.FARMING, SystemTags.RPG, SystemTags.ROLEPLAY)),
    TERRITORY("territory", "Territory", "Claim land: only owners (and their guild or friends) build on it, claims keep apart and hold a limited number of pieces, safe zones and timed attack windows (needs territory and worldObjects in the game rules).", setOf(SystemTags.SURVIVAL, SystemTags.RPG), setOf("worldState")),
    COMBAT_STANCE("combatStance", "Combat stance", "A SAFE / PvE / PvP stance each character chooses (/stance), with per-map permission, arming and disarming times, and forced PvP in an attack window (needs combatStance in the game rules).", setOf(SystemTags.SURVIVAL, SystemTags.RPG)),
    SCHEDULER("scheduler", "Scheduler", "Real-world-time events: weekly resets, daily bonuses, timed windows with advance warnings in chat and for Lua plugins (needs schedules in the game rules).", setOf(SystemTags.SURVIVAL, SystemTags.RPG, SystemTags.ROLEPLAY));

    companion object {
        fun fromConfigKey(key: String): OptionalModule? = entries.firstOrNull { it.configKey == key }

        /** The systems that serve [tag] (a [SystemTags] value), in declaration order. */
        fun withTag(tag: String): List<OptionalModule> = entries.filter { tag in it.tags }

        /**
         * Pairs of (a system that is on, a system it requires that is off) for the systems whose config keys are in
         * [disabledKeys]. Empty means every requirement is met. Systems that are off need nothing.
         */
        fun unmetRequirements(disabledKeys: Collection<String>): List<Pair<OptionalModule, OptionalModule>> {
            val off = disabledKeys.toSet()
            return entries.filter { it.configKey !in off }.flatMap { system ->
                system.requires.filter { it in off }.mapNotNull { key -> fromConfigKey(key)?.let { system to it } }
            }
        }
    }
}

/**
 * Which optional modules are switched off. The default is everything enabled.
 *
 * [disabled] holds the built-in modules; [disabledCustom] holds the namespaced ids (`plugin:module`, see [ModuleRegistry])
 * of modules a plugin registered.
 */
data class ModuleFlags(val disabled: Set<OptionalModule> = emptySet(), val disabledCustom: Set<String> = emptySet()) {
    fun isEnabled(module: OptionalModule): Boolean = module !in disabled

    /** Whether the module with this id (a built-in `configKey` or a plugin's `plugin:module` id) is on. */
    fun isEnabled(moduleId: String): Boolean {
        val builtIn = OptionalModule.fromConfigKey(moduleId)
        return if (builtIn != null) isEnabled(builtIn) else moduleId !in disabledCustom
    }

    /** Stable, sorted ids of the disabled modules (also what the server tells clients). */
    fun disabledKeys(): List<String> = (disabled.map { it.configKey } + disabledCustom).sorted()

    companion object {
        val ALL_ENABLED = ModuleFlags()

        /** Built-in keys become [disabled]; keys that [ModuleRegistry.isCustomId] accepts become [disabledCustom]; others are dropped. */
        fun fromDisabledKeys(keys: Collection<String>): ModuleFlags = ModuleFlags(
            keys.mapNotNull { OptionalModule.fromConfigKey(it) }.toSet(),
            keys.filter { ModuleRegistry.isCustomId(it) }.toSet()
        )
    }
}

/** A module a game or plugin offers, as shown to operators (the built-ins and a plugin's own use the same shape). */
data class ModuleDescriptor(
    val id: String,
    val displayName: String,
    val description: String = "",
    val builtIn: Boolean = false,
    val tags: Set<String> = emptySet(),
    val requires: Set<String> = emptySet()
)

/**
 * Every module the running server knows: the built-in [OptionalModule]s plus whatever plugins register.
 *
 * A plugin's module id is always `<pluginId>:<name>`, so two plugins cannot collide with each other or with a built-in
 * (built-in ids have no colon). Switching a module on or off uses the same `server.json` `modules` map as the built-ins.
 */
class ModuleRegistry {
    private val modules = LinkedHashMap<String, ModuleDescriptor>()

    init {
        OptionalModule.entries.forEach { modules[it.configKey] = ModuleDescriptor(it.configKey, it.displayName, it.description, builtIn = true, tags = it.tags, requires = it.requires) }
    }

    /** Add a plugin's module. Throws [IllegalArgumentException] for a malformed or already registered id. */
    @Synchronized
    fun register(pluginId: String, name: String, displayName: String, description: String = ""): ModuleDescriptor {
        require(PLUGIN_ID.matches(pluginId)) { "Plugin id '$pluginId' must match ${PLUGIN_ID.pattern}" }
        require(MODULE_NAME.matches(name)) { "Module name '$name' must match ${MODULE_NAME.pattern}" }
        val id = "$pluginId:$name"
        require(id !in modules) { "Module '$id' is already registered" }
        return ModuleDescriptor(id, displayName.ifBlank { id }, description).also { modules[id] = it }
    }

    /** Remove one plugin module (built-ins cannot be removed). Returns whether it existed. */
    @Synchronized
    fun unregister(id: String): Boolean = modules[id]?.takeUnless { it.builtIn }?.let { modules.remove(id) != null } ?: false

    @Synchronized
    fun get(id: String): ModuleDescriptor? = modules[id]

    @Synchronized
    fun all(): List<ModuleDescriptor> = modules.values.toList()

    companion object {
        val PLUGIN_ID = Regex("[a-z][a-z0-9_-]{1,31}")
        val MODULE_NAME = Regex("[a-z][a-zA-Z0-9_.-]{0,47}")

        /** Whether [id] has the shape of a plugin module id (`plugin:module`); says nothing about whether it is registered. */
        fun isCustomId(id: String): Boolean {
            val parts = id.split(':')
            return parts.size == 2 && PLUGIN_ID.matches(parts[0]) && MODULE_NAME.matches(parts[1])
        }
    }
}

/** One heading in a list of systems: [tag] is the [SystemTags] value (or [SystemGroups.GENERAL]) and [systems] are listed under it. */
data class SystemGroup(val tag: String, val title: String, val systems: List<OptionalModule>)

/**
 * How the editors present the system list and what a genre starter switches (ADR 0018). Both are derived from each system's
 * genre [OptionalModule.tags], so tagging a new system is all it takes for it to appear in the right place.
 */
object SystemGroups {
    /** The group for systems that serve every genre (the Lua plugins system, for one). */
    const val GENERAL = "general"
    private const val GENERAL_TITLE = "Works with every genre"

    /**
     * Every system once, under its home genre, which is the first tag it lists; a system tagged with every genre goes under
     * [GENERAL]. Groups follow the order of [SystemTags.TITLES] with [GENERAL] last, and empty groups are left out.
     */
    fun grouped(modules: Collection<OptionalModule> = OptionalModule.entries): List<SystemGroup> {
        fun home(m: OptionalModule): String = when {
            m.tags.containsAll(SystemTags.TITLES.keys) -> GENERAL
            else -> m.tags.firstOrNull() ?: GENERAL
        }
        val byHome = modules.groupBy(::home)
        return (SystemTags.TITLES.keys + GENERAL).mapNotNull { tag ->
            byHome[tag]?.let { SystemGroup(tag, SystemTags.TITLES[tag] ?: GENERAL_TITLE, it) }
        }
    }

    /** Whether [tag] names a genre (one of [SystemTags.TITLES]). */
    fun isGenre(tag: String): Boolean = tag in SystemTags.TITLES

    /**
     * The config keys a game of [genre] does not use: every system not tagged with it. A genre starter switches exactly these
     * off and leaves the genre's own systems on. Throws [IllegalArgumentException] for a name that is not a genre.
     */
    fun disabledKeysFor(genre: String, modules: Collection<OptionalModule> = OptionalModule.entries): Set<String> {
        require(isGenre(genre)) { "'$genre' is not a genre (use ${SystemTags.TITLES.keys.joinToString()})" }
        return modules.filter { genre !in it.tags }.map { it.configKey }.toSet()
    }
}
