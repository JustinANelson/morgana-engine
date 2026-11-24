package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.ExplorationDocument
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerState
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Offloads periodic persistence writes (autosaves, exploration updates, and runtime server-state saves)
 * from [MorganaWorldCoordinator]'s `@Synchronized` tick loop onto a dedicated single-thread writer.
 *
 * Generation counters per character, exploration document, and server state guarantee that an older
 * queued background write can never overwrite a newer synchronous save (for example on disconnect or shutdown).
 */
class AsyncPersistenceWriter(private val dataStore: MorganaDataStore) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(AsyncPersistenceWriter::class.java)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "morgana-persistence-writer").apply { isDaemon = true }
    }

    private val charLocks = Array(64) { Any() }
    private val charGenerations = ConcurrentHashMap<String, Long>()
    private val explorationGenerations = ConcurrentHashMap<String, Long>()

    private val worldObjectGenerations = ConcurrentHashMap<Int, Long>()

    private val serverStateLock = Any()
    private var serverStateGeneration = 0L

    private data class PendingAutosave(
        val characters: List<Pair<Long, CharacterState>>,
        val serverStateGen: Long,
        val serverState: ServerState
    )

    private val pendingAutosave = AtomicReference<PendingAutosave?>()
    private val autosaveScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private fun charLock(id: String): Any = charLocks[(id.hashCode() and 0x7fffffff) % charLocks.size]

    private fun nextCharacterGen(id: String): Long =
        charGenerations.compute(id) { _, prev -> (prev ?: 0L) + 1L }!!

    private fun nextExplorationGen(id: String): Long =
        explorationGenerations.compute(id) { _, prev -> (prev ?: 0L) + 1L }!!

    /**
     * Queues a non-blocking periodic autosave of [characters] and [serverState].
     * If a previous autosave has not started yet, it is coalesced and replaced by this newer snapshot.
     */
    fun enqueueAutosave(characters: List<CharacterState>, serverState: ServerState) {
        if (closed.get()) return
        val batch = ArrayList<Pair<Long, CharacterState>>(characters.size)
        for (ch in characters) {
            val gen = synchronized(charLock(ch.id)) { nextCharacterGen(ch.id) }
            batch.add(gen to ch)
        }
        val ssGen = synchronized(serverStateLock) { ++serverStateGeneration }
        pendingAutosave.set(PendingAutosave(batch, ssGen, serverState))
        if (autosaveScheduled.compareAndSet(false, true)) {
            submitSafely { drainAutosave() }
        }
    }

    /** Queues a non-blocking save of map [mapId]'s world objects; a newer snapshot of the same map replaces an older one that has not been written. */
    fun enqueueWorldObjects(mapId: Int, objects: List<com.morgana.domain.entity.WorldObject>) {
        if (closed.get()) return
        val gen = worldObjectGenerations.compute(mapId) { _, prev -> (prev ?: 0L) + 1L }!!
        submitSafely {
            synchronized(worldObjectGenerations) {
                if (worldObjectGenerations[mapId] == gen) dataStore.worldObjects.save(mapId, objects)
            }
        }
    }

    /** Synchronously saves map [mapId]'s world objects, superseding any queued write for it. */
    fun saveWorldObjectsNow(mapId: Int, objects: List<com.morgana.domain.entity.WorldObject>) {
        synchronized(worldObjectGenerations) {
            worldObjectGenerations.compute(mapId) { _, prev -> (prev ?: 0L) + 1L }
            dataStore.worldObjects.save(mapId, objects)
        }
    }

    /** Queues a non-blocking save of an updated [ExplorationDocument]. */
    fun enqueueExploration(doc: ExplorationDocument) {
        if (closed.get()) return
        val gen = synchronized(charLock(doc.id)) {
            nextExplorationGen(doc.id)
        }
        submitSafely { writeExplorationIfCurrent(doc, gen) }
    }

    /** Synchronously saves [character], superseding any older queued autosave for that character. */
    fun saveCharacterNow(character: CharacterState) {
        // Acquire inTransaction before charLock to match drainAutosave's lock order (SqliteDatabase.lock -> charLock).
        dataStore.inTransaction {
            synchronized(charLock(character.id)) {
                nextCharacterGen(character.id)
                dataStore.characters.save(character)
            }
        }
    }

    /**
     * Marks any older queued autosave for [characterId] as stale so it will not resurrect or overwrite
     * a deleted or externally updated character.
     */
    fun invalidateCharacter(characterId: String) {
        synchronized(charLock(characterId)) {
            nextCharacterGen(characterId)
            nextExplorationGen(characterId)
        }
    }

    /** Synchronously saves [doc], superseding any older queued exploration write for that character. */
    fun saveExplorationNow(doc: ExplorationDocument) {
        synchronized(charLock(doc.id)) {
            nextExplorationGen(doc.id)
            dataStore.social.exploration.save(doc.id, doc)
        }
    }

    /** Synchronously saves [serverState], superseding any older queued autosave server state. */
    fun saveServerStateNow(serverState: ServerState) {
        synchronized(serverStateLock) {
            ++serverStateGeneration
            dataStore.saveServerState(serverState)
        }
    }

    /** Waits until all currently queued background writes have finished. */
    fun flush() {
        if (closed.get()) return
        if (pendingAutosave.get() != null && autosaveScheduled.compareAndSet(false, true)) {
            submitSafely { drainAutosave() }
        }
        try {
            executor.submit {}.get()
        } catch (e: Exception) {
            logger.warn("Error while flushing async persistence writer", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (pendingAutosave.get() != null && autosaveScheduled.compareAndSet(false, true)) {
            runCatching { executor.submit { drainAutosave() } }
        }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("AsyncPersistenceWriter did not drain within 10s; forcing shutdown")
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    private fun submitSafely(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.submit {
                try {
                    task()
                } catch (t: Throwable) {
                    logger.error("Async persistence write failed", t)
                }
            }
        } catch (t: Throwable) {
            logger.warn("Failed to submit async persistence task", t)
        }
    }

    private fun drainAutosave() {
        while (true) {
            autosaveScheduled.set(false)
            val batch = pendingAutosave.getAndSet(null) ?: return
            if (batch.characters.isNotEmpty()) {
                dataStore.inTransaction {
                    for ((gen, ch) in batch.characters) {
                        synchronized(charLock(ch.id)) {
                            if (charGenerations[ch.id] == gen) {
                                dataStore.characters.save(ch)
                            }
                        }
                    }
                }
            }
            synchronized(serverStateLock) {
                if (serverStateGeneration == batch.serverStateGen) {
                    dataStore.saveServerState(batch.serverState)
                }
            }
            if (pendingAutosave.get() == null || !autosaveScheduled.compareAndSet(false, true)) {
                return
            }
        }
    }

    private fun writeExplorationIfCurrent(doc: ExplorationDocument, gen: Long) {
        synchronized(charLock(doc.id)) {
            if (explorationGenerations[doc.id] == gen) {
                dataStore.social.exploration.save(doc.id, doc)
            }
        }
    }
}
