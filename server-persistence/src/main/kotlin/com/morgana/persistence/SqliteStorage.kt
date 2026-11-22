package com.morgana.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A [DocumentCollection] that can also answer "which document has this value in this indexed field" without
 * scanning every document. Only the SQLite backend implements it; callers fall back to scanning otherwise.
 */
interface IndexedDocumentCollection<ID, T> : DocumentCollection<ID, T> {
    /** Documents whose indexed field [index] equals [value] (exact match, ordered by id). */
    fun findByIndex(index: String, value: String): List<T>
}

/**
 * One SQLite file shared by all player-data collections (accounts, characters, guilds, mail, market).
 *
 * A single connection is used under a lock, so it is safe from any thread. Plain operations commit
 * immediately; [transaction] groups several into one all-or-nothing commit (re-entrant: inner calls join the
 * outer transaction). The database uses write-ahead logging with full synchronous commits, so a committed
 * change survives a crash or power loss and a half-written change is never visible.
 */
class SqliteDatabase private constructor(private val connection: Connection, val path: Path) : AutoCloseable {
    private val lock = Any()
    private var depth = 0
    private var closed = false

    fun <R> withConnection(block: (Connection) -> R): R = synchronized(lock) {
        check(!closed) { "Database $path is closed" }
        block(connection)
    }

    /** Runs [block] atomically: commits if it returns, rolls everything back if it throws. */
    fun <R> transaction(block: () -> R): R = synchronized(lock) {
        check(!closed) { "Database $path is closed" }
        if (depth > 0) {
            depth++
            try { return block() } finally { depth-- }
        }
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        depth = 1
        try {
            val result = block()
            connection.createStatement().use { it.execute("COMMIT") }
            result
        } catch (e: Throwable) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
            throw e
        } finally {
            depth = 0
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                connection.close()
            }
        }
    }

    companion object {
        fun open(path: Path): SqliteDatabase {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())
            conn.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA synchronous=FULL")
                it.execute("PRAGMA busy_timeout=5000")
            }
            return SqliteDatabase(conn, path)
        }
    }
}

/**
 * [DocumentCollection] stored as one SQLite table: `id TEXT PRIMARY KEY, doc TEXT` (the same JSON the file backend
 * writes, so documents stay schema-versioned and portable) plus one indexed column per entry of [indexes].
 *
 * Table and index names come from code, never from players; they are still validated against `[a-z_]+`.
 * Listing order is by id, matching the file backend's sorted file names.
 */
class SqliteDocumentCollection<ID : Any, T>(
    private val db: SqliteDatabase,
    private val table: String,
    private val serializer: KSerializer<T>,
    private val indexes: Map<String, (T) -> String> = emptyMap(),
    private val idToKey: (ID) -> String = { it.toString() }
) : IndexedDocumentCollection<ID, T> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_type"
    }
    private val indexNames = indexes.keys.toList()

    init {
        require(NAME.matches(table)) { "Invalid table name '$table'" }
        indexNames.forEach { require(NAME.matches(it)) { "Invalid index name '$it'" } }
        db.withConnection { conn ->
            conn.createStatement().use { it.execute("""CREATE TABLE IF NOT EXISTS "$table" (id TEXT PRIMARY KEY, doc TEXT NOT NULL)""") }
            val existing = mutableSetOf<String>()
            conn.createStatement().use { st ->
                st.executeQuery("""PRAGMA table_info("$table")""").use { rs -> while (rs.next()) existing += rs.getString("name") }
            }
            val added = indexNames.filter { column(it) !in existing }
            for (name in added) {
                conn.createStatement().use { it.execute("""ALTER TABLE "$table" ADD COLUMN "${column(name)}" TEXT""") }
            }
            for (name in indexNames) {
                conn.createStatement().use { it.execute("""CREATE INDEX IF NOT EXISTS "ix_${table}_$name" ON "$table"("${column(name)}")""") }
            }
            if (added.isNotEmpty()) backfill(conn)
        }
    }

    private fun column(index: String) = "ix_$index"

    /** Fills index columns of rows written before an index existed. */
    private fun backfill(conn: Connection) {
        val rows = conn.createStatement().use { st ->
            st.executeQuery("""SELECT id, doc FROM "$table"""").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
        for ((id, doc) in rows) writeRow(conn, id, json.decodeFromString(serializer, doc))
    }

    private fun writeRow(conn: Connection, key: String, document: T) {
        val cols = indexNames.joinToString("") { ""","${column(it)}"""" }
        val marks = indexNames.joinToString("") { ",?" }
        val updates = indexNames.joinToString("") { ""","${column(it)}"=excluded."${column(it)}"""" }
        val sql = """INSERT INTO "$table"(id,doc$cols) VALUES(?,?$marks) ON CONFLICT(id) DO UPDATE SET doc=excluded.doc$updates"""
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, key)
            ps.setString(2, json.encodeToString(serializer, document))
            indexNames.forEachIndexed { i, name -> ps.setString(3 + i, indexes.getValue(name)(document)) }
            ps.executeUpdate()
        }
    }

    override fun findById(id: ID): T? = db.withConnection { conn ->
        conn.prepareStatement("""SELECT doc FROM "$table" WHERE id=?""").use { ps ->
            ps.setString(1, idToKey(id))
            ps.executeQuery().use { rs -> if (rs.next()) json.decodeFromString(serializer, rs.getString(1)) else null }
        }
    }

    override fun findAll(): List<T> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("""SELECT doc FROM "$table" ORDER BY id""").use { rs ->
                buildList { while (rs.next()) add(json.decodeFromString(serializer, rs.getString(1))) }
            }
        }
    }

    override fun findByIndex(index: String, value: String): List<T> {
        require(index in indexes) { "No index '$index' on $table" }
        return db.withConnection { conn ->
            conn.prepareStatement("""SELECT doc FROM "$table" WHERE "${column(index)}"=? ORDER BY id""").use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(json.decodeFromString(serializer, rs.getString(1))) } }
            }
        }
    }

    override fun save(id: ID, document: T) {
        db.withConnection { conn -> writeRow(conn, idToKey(id), document) }
    }

    override fun deleteById(id: ID): Boolean = db.withConnection { conn ->
        conn.prepareStatement("""DELETE FROM "$table" WHERE id=?""").use { ps ->
            ps.setString(1, idToKey(id))
            ps.executeUpdate() > 0
        }
    }

    override fun count(): Int = db.withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery("""SELECT COUNT(*) FROM "$table"""").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private companion object {
        val NAME = Regex("[a-z_]+")
    }
}
