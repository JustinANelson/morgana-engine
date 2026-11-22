package com.morgana.persistence

import com.morgana.domain.entity.AccountDocument
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MailMessageDocument
import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.item.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The same behavior is required of the JSON-file and SQLite player-data backends. */
class Phase16SqliteTest {

    private fun account(i: Int, name: String = "User$i") =
        AccountDocument(id = "acc-%03d".format(i), username = name, passwordHash = "hash$i", characterIds = listOf("char-%03d".format(i)))

    private fun character(i: Int, name: String = "Hero$i", accountId: String = "acc-%03d".format(i)) =
        CharacterState(id = "char-%03d".format(i), accountId = accountId, name = name, attributes = mapOf("str" to 5, "def" to 5, "int" to 5, "agi" to 5, "vit" to 5))

    private fun withBothBackends(dir: Path, check: (MorganaDataStore, PlayerDataBackend) -> Unit) {
        for (backend in PlayerDataBackend.entries) {
            MorganaDataStore(dir.resolve(backend.configKey), backend).use { check(it, backend) }
        }
    }

    // ---- behavior contract ----------------------------------------------------------------------------

    @Test
    fun `both backends save, load, upsert, list in id order, count and delete identically`(@TempDir dir: Path) {
        withBothBackends(dir) { store, backend ->
            val accounts = store.accounts.documents
            assertEquals(0, accounts.count(), backend.name)
            assertNull(accounts.findById("nope"))
            for (i in listOf(3, 1, 2)) accounts.save(account(i).id, account(i))
            assertEquals(listOf("acc-001", "acc-002", "acc-003"), accounts.findAll().map { it.id }, "$backend: ordered by id")
            assertEquals(3, accounts.count())

            accounts.save("acc-002", account(2).copy(banned = true))
            assertEquals(3, accounts.count(), "$backend: upsert does not add a row")
            assertTrue(accounts.findById("acc-002")!!.banned)

            assertTrue(accounts.deleteById("acc-002"))
            assertFalse(accounts.deleteById("acc-002"))
            assertEquals(2, accounts.count())
        }
    }

    @Test
    fun `lookups by username, character name and account are case-insensitive where promised`(@TempDir dir: Path) {
        withBothBackends(dir) { store, backend ->
            store.accounts.save(account(1, "Alice"))
            store.accounts.save(account(2, "bob"))
            assertEquals("acc-001", store.accounts.findByUsername("ALICE")?.id, backend.name)
            assertEquals("acc-002", store.accounts.findByUsername("Bob")?.id)
            assertNull(store.accounts.findByUsername("carol"))

            store.characters.save(character(1, "Sir Alice"))
            store.characters.save(character(2, "Bobby", accountId = "acc-001"))
            assertEquals("char-001", store.characters.findByName("sir alice")?.id)
            assertNull(store.characters.findByName("nobody"))
            assertEquals(listOf("char-001", "char-002"), store.characters.findByAccountId("acc-001").map { it.id })
            assertTrue(store.characters.findByAccountId("acc-999").isEmpty())

            // An updated name must move the index with it.
            store.characters.save(character(1, "Renamed"))
            assertNull(store.characters.findByName("sir alice"))
            assertEquals("char-001", store.characters.findByName("RENAMED")?.id)
        }
    }

    @Test
    fun `hostile text is stored as data and never interpreted as SQL`(@TempDir dir: Path) {
        val nasty = "x'); DROP TABLE accounts; --\""
        withBothBackends(dir.resolve("a")) { store, _ ->
            store.accounts.save(account(1, nasty))
            store.accounts.save(account(2, "Normal"))
            assertEquals("acc-001", store.accounts.findByUsername(nasty)?.id)
            assertEquals(2, store.accounts.count())
            assertNull(store.accounts.findByUsername("x' OR '1'='1"))
        }
    }

    @Test
    fun `social collections store guilds, mail and listings`(@TempDir dir: Path) {
        withBothBackends(dir) { store, _ ->
            store.social.guilds.save("g1", GuildDocument("g1", "Heroes", "HRO", "char-001", mapOf("char-001" to "Founder")))
            store.social.mail.save("m1", MailMessageDocument("m1", "Alice", "char-002", "Hi", "Body", ItemStack(1, 2)))
            store.social.marketListings.save("l1", MarketListingDocument("l1", "char-001", "Alice", ItemStack(3, 1), 50))
            assertEquals("Heroes", store.social.guilds.findById("g1")?.name)
            assertEquals(ItemStack(1, 2), store.social.mail.findById("m1")?.attachedItem)
            assertEquals(50, store.social.marketListings.findAll().single().priceCurrencyAmount)
        }
    }

    // ---- SQLite specifics -----------------------------------------------------------------------------

    @Test
    fun `sqlite data survives closing and reopening, and no JSON player folders are created`(@TempDir dir: Path) {
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use { it.accounts.save(account(1, "Persist")); it.characters.save(character(1)) }
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use {
            assertEquals("Persist", it.accounts.findByUsername("persist")?.username)
            assertNotNull(it.characters.findById("char-001"))
        }
        assertTrue(Files.exists(dir.resolve(MorganaDataStore.PLAYER_DB_FILE)))
        assertFalse(Files.exists(dir.resolve("accounts")) || Files.exists(dir.resolve("characters")) || Files.exists(dir.resolve("social")))
    }

    @Test
    fun `a failed transaction leaves nothing behind, nested ones join the outer, and success commits`(@TempDir dir: Path) {
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use { store ->
            assertThrows(IllegalStateException::class.java) {
                store.inTransaction {
                    store.accounts.save(account(1))
                    store.characters.save(character(1))
                    error("crash before commit")
                }
            }
            assertEquals(0, store.accounts.documents.count())
            assertEquals(0, store.characters.documents.count())

            assertThrows(IllegalStateException::class.java) {
                store.inTransaction {
                    store.inTransaction { store.accounts.save(account(2)) }
                    error("outer fails after inner succeeded")
                }
            }
            assertEquals(0, store.accounts.documents.count(), "inner work is part of the outer transaction")

            store.inTransaction { store.accounts.save(account(3)); store.characters.save(character(3)) }
            assertEquals(1, store.accounts.documents.count())
            assertEquals(1, store.characters.documents.count())
        }
    }

    @Test
    fun `a transaction that never commits is not visible after a hard stop`(@TempDir dir: Path) {
        // Simulate a crash: write inside a transaction, then close the connection without committing.
        val db = SqliteDatabase.open(dir.resolve("raw.db"))
        val col = SqliteDocumentCollection<String, AccountDocument>(db, "accounts", AccountDocument.serializer())
        col.save("acc-001", account(1))
        db.withConnection { it.createStatement().use { st -> st.execute("BEGIN IMMEDIATE") } }
        col.save("acc-002", account(2))
        db.close() // the open transaction is discarded

        val reopened = SqliteDatabase.open(dir.resolve("raw.db"))
        val again = SqliteDocumentCollection<String, AccountDocument>(reopened, "accounts", AccountDocument.serializer())
        assertEquals(listOf("acc-001"), again.findAll().map { it.id })
        reopened.close()
    }

    @Test
    fun `an index added later is created and back-filled for existing rows`(@TempDir dir: Path) {
        val db = SqliteDatabase.open(dir.resolve("migrate.db"))
        val plain = SqliteDocumentCollection<String, AccountDocument>(db, "accounts", AccountDocument.serializer())
        plain.save("acc-001", account(1, "Early"))
        val indexed = SqliteDocumentCollection<String, AccountDocument>(
            db, "accounts", AccountDocument.serializer(), mapOf("username" to { a: AccountDocument -> a.username.lowercase() })
        )
        assertEquals("acc-001", indexed.findByIndex("username", "early").single().id)
        assertThrows(IllegalArgumentException::class.java) { indexed.findByIndex("unknown", "x") }
        db.close()
    }

    @Test
    fun `invalid table names are rejected`(@TempDir dir: Path) {
        val db = SqliteDatabase.open(dir.resolve("x.db"))
        assertThrows(IllegalArgumentException::class.java) {
            SqliteDocumentCollection<String, AccountDocument>(db, "accounts; DROP TABLE x", AccountDocument.serializer())
        }
        db.close()
    }

    @Test
    fun `concurrent writers do not lose or corrupt documents`(@TempDir dir: Path) {
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use { store ->
            val pool = Executors.newFixedThreadPool(8)
            val tasks = (0 until 8).map { t ->
                pool.submit {
                    for (n in 0 until 40) {
                        val i = t * 100 + n
                        store.characters.save(character(i))
                        if (n % 10 == 0) store.inTransaction { store.accounts.save(account(i)) }
                    }
                }
            }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            assertEquals(8 * 40, store.characters.documents.count())
            assertEquals(8 * 4, store.accounts.documents.count())
            assertEquals("Hero305", store.characters.findByName("hero305")?.name)
        }
    }

    // ---- configuration and migration --------------------------------------------------------------------

    @Test
    fun `server json selects the backend and rejects unknown ones`(@TempDir dir: Path) {
        MorganaDataStore(dir.resolve("default")).use { assertEquals(PlayerDataBackend.JSON, it.playerDataBackend) }

        val sql = dir.resolve("sql")
        ServerConfig.save(sql, ServerConfig().withPlayerData(PlayerDataBackend.SQLITE))
        MorganaDataStore(sql).use { assertEquals(PlayerDataBackend.SQLITE, it.playerDataBackend) }

        val bad = dir.resolve("bad")
        Files.createDirectories(bad)
        Files.writeString(ServerConfig.fileIn(bad), """{ "storage": { "playerData": "postgres" } }""")
        val e = assertThrows(ServerConfigException::class.java) { ServerConfig.load(bad) }
        assertTrue(e.message!!.contains("postgres") && e.message!!.contains("sqlite"))
    }

    private fun seed(store: MorganaDataStore) {
        for (i in 1..5) { store.accounts.save(account(i)); store.characters.save(character(i)) }
        store.social.guilds.save("g1", GuildDocument("g1", "Heroes", "HRO", "char-001"))
        store.social.mail.save("m1", MailMessageDocument("m1", "A", "char-002", "s", "b"))
        store.social.marketListings.save("l1", MarketListingDocument("l1", "char-001", "A", ItemStack(1, 1), 5))
        store.social.exploration.save("char-001", com.morgana.domain.entity.ExplorationDocument("char-001", mapOf(1 to com.morgana.domain.explore.ExploredMap(listOf(5L)))))
    }

    @Test
    fun `migration json to sqlite and back preserves every document and switches server json`(@TempDir dir: Path) {
        MorganaDataStore(dir, PlayerDataBackend.JSON).use { seed(it) }

        val toSql = StorageMigration.migrate(dir, PlayerDataBackend.SQLITE)
        assertEquals(mapOf("accounts" to 5, "characters" to 5, "guilds" to 1, "mail" to 1, "market" to 1, "exploration" to 1), toSql.counts)
        assertEquals(PlayerDataBackend.SQLITE, ServerConfig.load(dir).playerDataBackend())
        assertTrue(Files.exists(dir.resolve("accounts")), "the source JSON is left in place")
        MorganaDataStore(dir).use { sql ->
            assertEquals(PlayerDataBackend.SQLITE, sql.playerDataBackend)
            assertEquals(account(3), sql.accounts.findByUsername("USER3"))
            assertEquals(character(4), sql.characters.findByName("hero4"))
            assertEquals("Heroes", sql.social.guilds.findById("g1")?.name)
            assertEquals(listOf(5L), sql.social.exploration.findById("char-001")?.maps?.get(1)?.chunkBits)
            sql.accounts.save(account(6)) // post-migration progress that must come back too
        }

        // Going back requires an empty JSON side (data would be mixed otherwise).
        val mixed = assertThrows(StorageMigrationException::class.java) { StorageMigration.migrate(dir, PlayerDataBackend.JSON) }
        assertTrue(mixed.message!!.contains("already contains"), mixed.message)
        assertEquals(PlayerDataBackend.SQLITE, ServerConfig.load(dir).playerDataBackend(), "failed migration changes nothing")

        listOf("accounts", "characters", "social").forEach { name ->
            Files.walk(dir.resolve(name)).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
        val back = StorageMigration.migrate(dir, PlayerDataBackend.JSON)
        assertEquals(6, back.counts["accounts"])
        MorganaDataStore(dir).use { json ->
            assertEquals(PlayerDataBackend.JSON, json.playerDataBackend)
            assertEquals(6, json.accounts.documents.count())
            assertEquals(account(6), json.accounts.findByUsername("user6"))
        }
    }

    @Test
    fun `migrating to the backend already in use is refused`(@TempDir dir: Path) {
        MorganaDataStore(dir).use { seed(it) }
        assertThrows(StorageMigrationException::class.java) { StorageMigration.migrate(dir, PlayerDataBackend.JSON) }
    }

    @Test
    fun `a migration into a non-empty sqlite database is refused and leaves it unchanged`(@TempDir dir: Path) {
        MorganaDataStore(dir, PlayerDataBackend.JSON).use { seed(it) }
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use { it.accounts.save(account(99, "Existing")) }
        assertThrows(StorageMigrationException::class.java) { StorageMigration.migrate(dir, PlayerDataBackend.SQLITE) }
        MorganaDataStore(dir, PlayerDataBackend.SQLITE).use { assertEquals(listOf("acc-099"), it.accounts.documents.findAll().map { a -> a.id }) }
        assertEquals(PlayerDataBackend.JSON, ServerConfig.load(dir).playerDataBackend())
    }
}
