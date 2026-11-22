package com.morgana.persistence

import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.AccountDocument
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.ExplorationDocument
import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import kotlinx.serialization.KSerializer
import java.nio.file.Path

/**
 * Account repository port & JSON/Mongo-ready adapter.
 */
class AccountRepository(
    private val collection: DocumentCollection<String, AccountDocument>
) {
    /** The underlying collection (used by storage migration). */
    val documents: DocumentCollection<String, AccountDocument> get() = collection

    fun findById(id: String): AccountDocument? = collection.findById(id)

    fun findByUsername(username: String): AccountDocument? =
        if (collection is IndexedDocumentCollection<String, AccountDocument>) {
            collection.findByIndex(INDEX_USERNAME, username.lowercase()).firstOrNull()
        } else {
            collection.findAll().firstOrNull { it.username.equals(username, ignoreCase = true) }
        }

    fun findAll(): List<AccountDocument> = collection.findAll()

    fun save(account: AccountDocument) {
        collection.save(account.id, account)
    }

    fun count(): Int = collection.count()

    companion object {
        /** Case-insensitive username index used by the SQLite backend. */
        const val INDEX_USERNAME = "username"

        /** Salted PBKDF2 hash (see [PasswordHasher]); a different string every call. */
        fun hashPassword(rawPassword: String): String = PasswordHasher.hash(rawPassword)

        fun verifyPassword(rawPassword: String, storedHash: String): Boolean = PasswordHasher.verify(rawPassword, storedHash)

        fun passwordNeedsUpgrade(storedHash: String): Boolean = PasswordHasher.needsUpgrade(storedHash)
    }
}

/**
 * Character repository port & JSON/Mongo-ready adapter.
 */
class CharacterRepository(
    private val collection: DocumentCollection<String, CharacterState>
) {
    /** The underlying collection (used by storage migration). */
    val documents: DocumentCollection<String, CharacterState> get() = collection

    fun findById(id: String): CharacterState? = collection.findById(id)

    fun findByName(name: String): CharacterState? =
        if (collection is IndexedDocumentCollection<String, CharacterState>) {
            collection.findByIndex(INDEX_NAME, name.lowercase()).firstOrNull()
        } else {
            collection.findAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

    fun findByAccountId(accountId: String): List<CharacterState> =
        if (collection is IndexedDocumentCollection<String, CharacterState>) {
            collection.findByIndex(INDEX_ACCOUNT_ID, accountId)
        } else {
            collection.findAll().filter { it.accountId == accountId }
        }

    fun findAll(): List<CharacterState> = collection.findAll()

    fun save(character: CharacterState) {
        collection.save(character.id, character)
    }

    /** Removes a character for good. Returns false when there was none with that id. */
    fun delete(id: String): Boolean = collection.deleteById(id)

    companion object {
        const val INDEX_NAME = "name"
        const val INDEX_ACCOUNT_ID = "account_id"
    }
}

/**
 * Repository managing all live-editable World Content definitions (Maps, Items, NPCs, Spells, Shops, Resources, Recipes, Quests, Events, Animations).
 */
class WorldContentRepository(baseDir: Path) {
    val maps: DocumentCollection<Int, MapDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("maps"),
        serializer = MapDefinition.serializer(),
        idToFileStem = { "map_%03d".format(it) }
    )
    val classes: DocumentCollection<Int, ClassDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("classes"),
        serializer = ClassDefinition.serializer(),
        idToFileStem = { "class_%03d".format(it) }
    )
    val items: DocumentCollection<Int, ItemDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("items"),
        serializer = ItemDefinition.serializer(),
        idToFileStem = { "item_%03d".format(it) }
    )
    val npcs: DocumentCollection<Int, NpcDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("npcs"),
        serializer = NpcDefinition.serializer(),
        idToFileStem = { "npc_%03d".format(it) }
    )
    val spells: DocumentCollection<Int, SpellDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("spells"),
        serializer = SpellDefinition.serializer(),
        idToFileStem = { "spell_%03d".format(it) }
    )
    val shops: DocumentCollection<Int, ShopDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("shops"),
        serializer = ShopDefinition.serializer(),
        idToFileStem = { "shop_%03d".format(it) }
    )
    val resources: DocumentCollection<Int, ResourceDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("resources"),
        serializer = ResourceDefinition.serializer(),
        idToFileStem = { "resource_%03d".format(it) }
    )
    val recipes: DocumentCollection<Int, RecipeDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("recipes"),
        serializer = RecipeDefinition.serializer(),
        idToFileStem = { "recipe_%03d".format(it) }
    )
    val quests: DocumentCollection<Int, QuestDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("quests"),
        serializer = QuestDefinition.serializer(),
        idToFileStem = { "quest_%03d".format(it) }
    )
    val events: DocumentCollection<Int, EventDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("events"),
        serializer = EventDefinition.serializer(),
        idToFileStem = { "event_%03d".format(it) }
    )
    val commonEvents: DocumentCollection<Int, CommonEventDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("common_events"),
        serializer = CommonEventDefinition.serializer(),
        idToFileStem = { "common_event_%03d".format(it) }
    )
    val animations: DocumentCollection<Int, AnimationDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("animations"),
        serializer = AnimationDefinition.serializer(),
        idToFileStem = { "anim_%03d".format(it) }
    )
    val actionSkills: DocumentCollection<Int, com.morgana.domain.content.ActionSkillDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("action_skills"),
        serializer = com.morgana.domain.content.ActionSkillDefinition.serializer(),
        idToFileStem = { "action_skill_%03d".format(it) }
    )
    val weaponSets: DocumentCollection<Int, com.morgana.domain.content.WeaponSetDefinition> = JsonFileDocumentCollection(
        directory = baseDir.resolve("weapon_sets"),
        serializer = com.morgana.domain.content.WeaponSetDefinition.serializer(),
        idToFileStem = { "weapon_set_%03d".format(it) }
    )

    fun isEmpty(): Boolean = maps.count() == 0

    fun loadIntoSimulation(sim: WorldSimulation) {
        classes.findAll().sortedBy { it.id }.forEach { sim.classes[it.id] = it }
        items.findAll().sortedBy { it.id }.forEach { sim.items[it.id] = it }
        npcs.findAll().sortedBy { it.id }.forEach { sim.npcs[it.id] = it }
        spells.findAll().sortedBy { it.id }.forEach { sim.spells[it.id] = it }
        shops.findAll().sortedBy { it.id }.forEach { sim.shops[it.id] = it }
        resources.findAll().sortedBy { it.id }.forEach { sim.resources[it.id] = it }
        recipes.findAll().sortedBy { it.id }.forEach { sim.recipes[it.id] = it }
        quests.findAll().sortedBy { it.id }.forEach { sim.quests[it.id] = it }
        events.findAll().sortedBy { it.id }.forEach { sim.events[it.id] = it }
        commonEvents.findAll().sortedBy { it.id }.forEach { sim.commonEvents[it.id] = it }
        animations.findAll().sortedBy { it.id }.forEach { sim.animations[it.id] = it }
        actionSkills.findAll().sortedBy { it.id }.forEach { sim.actionSkills[it.id] = it }
        weaponSets.findAll().sortedBy { it.id }.forEach { sim.weaponSets[it.id] = it }
        // Register maps last so NPC spawns and resource nodes resolve against loaded catalogs
        maps.findAll().sortedBy { it.id }.forEach { sim.registerMap(it) }
    }
}

/**
 * Social & economy document stores (Guilds, Mail, Offline Market/Auction House).
 */
class SocialMarketRepository(
    val guilds: DocumentCollection<String, GuildDocument>,
    val mail: DocumentCollection<String, MailMessageDocument>,
    val marketListings: DocumentCollection<String, MarketListingDocument>,
    val exploration: DocumentCollection<String, ExplorationDocument>
) {
    /** A character's letters, newest first. */
    fun mailFor(characterId: String): List<MailMessageDocument> =
        mail.findAll().filter { it.recipientCharacterId == characterId }
            .sortedWith(compareByDescending<MailMessageDocument> { it.sentAtTick }.thenByDescending { it.id })

    /** JSON-file backed repositories under [baseDir]. */
    constructor(baseDir: Path) : this(
        JsonFileDocumentCollection(baseDir.resolve("guilds"), GuildDocument.serializer()),
        JsonFileDocumentCollection(baseDir.resolve("mail"), MailMessageDocument.serializer()),
        JsonFileDocumentCollection(baseDir.resolve("market"), MarketListingDocument.serializer()),
        JsonFileDocumentCollection(baseDir.resolve("exploration"), ExplorationDocument.serializer())
    )
}

/**
 * Top-level persistence facade bundling all server repositories.
 */
class MorganaDataStore(
    val rootDir: Path,
    /** Where player data lives. Defaults to the `storage.playerData` setting of `server.json` (JSON files if absent). */
    val playerDataBackend: PlayerDataBackend = ServerConfig.load(rootDir).playerDataBackend(),
    val gameDir: Path = rootDir
) : AutoCloseable {
    /** Open database when [playerDataBackend] is SQLite; world content never uses it. */
    private val sqlite: SqliteDatabase? =
        if (playerDataBackend == PlayerDataBackend.SQLITE) SqliteDatabase.open(rootDir.resolve(PLAYER_DB_FILE)) else null

    private fun <T> playerCollection(
        name: String,
        serializer: KSerializer<T>,
        jsonDir: Path,
        indexes: Map<String, (T) -> String> = emptyMap()
    ): DocumentCollection<String, T> =
        if (sqlite != null) SqliteDocumentCollection(sqlite, name, serializer, indexes)
        else JsonFileDocumentCollection(jsonDir, serializer)

    val accounts = AccountRepository(
        playerCollection(
            "accounts", AccountDocument.serializer(), rootDir.resolve("accounts"),
            mapOf(AccountRepository.INDEX_USERNAME to { a: AccountDocument -> a.username.lowercase() })
        )
    )
    val characters = CharacterRepository(
        playerCollection(
            "characters", CharacterState.serializer(), rootDir.resolve("characters"),
            mapOf(
                CharacterRepository.INDEX_NAME to { c: CharacterState -> c.name.lowercase() },
                CharacterRepository.INDEX_ACCOUNT_ID to { c: CharacterState -> c.accountId }
            )
        )
    )
    val contentDir: Path = run {
        if (gameDir != rootDir) {
            val gameData = gameDir.resolve("data")
            if (java.nio.file.Files.isDirectory(gameData)) gameData
            else {
                val gameWorld = gameDir.resolve("world")
                if (java.nio.file.Files.isDirectory(gameWorld)) gameWorld
                else gameData
            }
        } else {
            val gameData = rootDir.resolve("data")
            if (java.nio.file.Files.isDirectory(gameData)) gameData
            else rootDir.resolve("world")
        }
    }
    val world = WorldContentRepository(contentDir)
    val social = SocialMarketRepository(
        playerCollection("guilds", GuildDocument.serializer(), rootDir.resolve("social/guilds")),
        playerCollection("mail", MailMessageDocument.serializer(), rootDir.resolve("social/mail")),
        playerCollection("market", MarketListingDocument.serializer(), rootDir.resolve("social/market")),
        playerCollection("exploration", ExplorationDocument.serializer(), rootDir.resolve("social/exploration"))
    )

    /** Loads runtime server state (such as [ServerState.worldEpochTicks]) from `<rootDir>/server-state.json`. */
    fun loadServerState(): ServerState = ServerState.load(rootDir)

    /** Persists runtime server state to `<rootDir>/server-state.json`. */
    fun saveServerState(state: ServerState) {
        ServerState.save(rootDir, state)
    }

    /**
     * Runs [block] so that all player-data writes inside it commit together (SQLite) or not at all if it throws.
     * With JSON files there are no transactions, so this simply runs the block.
     */
    fun <R> inTransaction(block: () -> R): R = sqlite?.transaction(block) ?: block()

    override fun close() {
        sqlite?.close()
    }

    companion object {
        const val PLAYER_DB_FILE = "player-data.db"
    }
}

