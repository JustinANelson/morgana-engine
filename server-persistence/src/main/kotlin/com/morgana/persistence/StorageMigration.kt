package com.morgana.persistence

import java.nio.file.Path

class StorageMigrationException(message: String) : Exception(message)

data class StorageMigrationReport(
    val from: PlayerDataBackend,
    val to: PlayerDataBackend,
    /** Documents copied per collection. */
    val counts: Map<String, Int>
)

/**
 * Moves player data (accounts, characters, guilds, mail, market) between the JSON-file and SQLite backends of one
 * data directory, then points `server.json` at the new backend. World content is never touched.
 *
 * Safe by construction: the target must be empty, every copied document is read back and compared before the
 * switch is recorded, and the source data is left in place (delete it yourself once you are satisfied). The server
 * must be stopped while this runs.
 */
object StorageMigration {

    fun migrate(dataDir: Path, target: PlayerDataBackend): StorageMigrationReport {
        val config = ServerConfig.load(dataDir)
        val source = config.playerDataBackend()
        if (source == target) throw StorageMigrationException("Player data already uses ${target.configKey}; nothing to do.")

        val counts: Map<String, Int>
        MorganaDataStore(dataDir, source).use { src ->
            MorganaDataStore(dataDir, target).use { dst ->
                val existing = collections(dst).filter { it.second.count() > 0 }.map { it.first }
                if (existing.isNotEmpty()) {
                    throw StorageMigrationException(
                        "The ${target.configKey} target already contains ${existing.joinToString()}; remove it first so data is not mixed."
                    )
                }
                val pairs = collections(src).zip(collections(dst))
                counts = linkedMapOf<String, Int>().also { out ->
                    try {
                        dst.inTransaction {
                            for ((from, to) in pairs) out[from.first] = copyAndVerify(from.first, from.second, to.second)
                        }
                    } catch (e: Throwable) {
                        // SQLite rolled back by itself; JSON has no transactions, so remove what was written.
                        pairs.forEach { (from, to) -> removeCopied(from.second, to.second) }
                        throw e
                    }
                }
            }
        }
        ServerConfig.save(dataDir, config.withPlayerData(target))
        return StorageMigrationReport(source, target, counts)
    }

    /** The player-data collections in a fixed order, type-erased for copying. */
    @Suppress("UNCHECKED_CAST")
    private fun collections(store: MorganaDataStore): List<Pair<String, DocumentCollection<String, Any>>> = listOf(
        "accounts" to store.accounts.documents,
        "characters" to store.characters.documents,
        "guilds" to store.social.guilds,
        "mail" to store.social.mail,
        "market" to store.social.marketListings,
        "exploration" to store.social.exploration
    ).map { (name, col) -> name to (col as DocumentCollection<String, Any>) }

    private fun idOf(doc: Any): String = when (doc) {
        is com.morgana.domain.entity.AccountDocument -> doc.id
        is com.morgana.domain.entity.CharacterState -> doc.id
        is com.morgana.domain.entity.GuildDocument -> doc.id
        is com.morgana.domain.entity.MailMessageDocument -> doc.id
        is com.morgana.domain.entity.MarketListingDocument -> doc.id
        is com.morgana.domain.entity.ExplorationDocument -> doc.id
        else -> throw StorageMigrationException("Unsupported document type ${doc::class.simpleName}")
    }

    private fun copyAndVerify(name: String, from: DocumentCollection<String, Any>, to: DocumentCollection<String, Any>): Int {
        val docs = from.findAll()
        for (doc in docs) to.save(idOf(doc), doc)
        val copied = to.findAll()
        if (copied.size != docs.size) {
            throw StorageMigrationException("Verification failed for $name: copied ${copied.size} of ${docs.size} documents.")
        }
        val byId = copied.associateBy { idOf(it) }
        for (doc in docs) {
            if (byId[idOf(doc)] != doc) throw StorageMigrationException("Verification failed for $name: document ${idOf(doc)} differs after copy.")
        }
        return docs.size
    }

    private fun removeCopied(from: DocumentCollection<String, Any>, to: DocumentCollection<String, Any>) {
        runCatching { from.findAll().forEach { to.deleteById(idOf(it)) } }
    }
}
