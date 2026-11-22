package com.morgana.client.assets

import com.morgana.protocol.realms.AssetKind
import com.morgana.protocol.realms.AssetManifest
import com.morgana.protocol.realms.AssetManifestEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The async sync path must work however the downloader reports back: immediately, from another thread, or later from
 * the caller's own loop (the browser, where `fetch` completes on the single thread after the call returned).
 */
class AsyncAssetSyncTest {

    private fun files(count: Int): Map<String, ByteArray> =
        (1..count).associate { "f$it.png" to "data-$it".toByteArray() }

    private fun manifestOf(files: Map<String, ByteArray>, corrupt: Set<String> = emptySet()) = AssetManifest(
        gameId = "g",
        assets = files.mapValues { (path, bytes) ->
            AssetManifestEntry(path, if (path in corrupt) "0".repeat(64) else computeSha256(bytes), bytes.size.toLong(), AssetKind.SPRITE)
        }
    )

    /** Holds every request until the test calls [pump], like `fetch` finishing after the call returned. */
    private class DeferredDownloader(private val files: Map<String, ByteArray>) : AssetDownloader {
        val pending = ArrayDeque<() -> Unit>()
        override fun download(url: String): Result<ByteArray> = error("blocking download must not be used")
        override fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
            val bytes = files.entries.first { url.endsWith(it.key) }.value
            pending.addLast { onDone(Result.success(bytes)) }
        }
        fun pump() { while (pending.isNotEmpty()) pending.removeFirst()() }
    }

    /** Answers from a worker thread, as the JVM downloader does. */
    private class ThreadedDownloader(private val files: Map<String, ByteArray>) : AssetDownloader {
        val pool = Executors.newFixedThreadPool(4)
        override fun download(url: String): Result<ByteArray> = error("unused")
        override fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
            pool.execute { onDone(Result.success(files.entries.first { url.endsWith(it.key) }.value)) }
        }
    }

    @Test
    fun `a deferred downloader completes only after the callbacks arrive and caches every file`() {
        val data = files(3)
        val cache = InMemoryAssetCache()
        val downloader = DeferredDownloader(data)
        var result: SyncResult? = null
        val progress = mutableListOf<SyncProgress>()

        RealmAssetSyncPipeline(cache, downloader).syncAsync(manifestOf(data), "http://x", { progress += it }, { result = it })

        assertEquals(null, result, "nothing finishes before the downloads do")
        assertEquals(1, downloader.pending.size, "files are fetched one at a time")
        downloader.pump()

        val done = result as SyncResult.Completed
        assertEquals(3, done.downloadedCount)
        assertTrue(data.values.all { cache.has(computeSha256(it)) })
        assertEquals(1f, progress.last().percent)
        assertTrue(progress.last().isFinished)
    }

    @Test
    fun `a synchronous downloader finishes before syncAsync returns and thousands of files do not overflow the stack`() {
        val data = files(5000)
        var result: SyncResult? = null
        RealmAssetSyncPipeline(InMemoryAssetCache(), InMemoryAssetDownloader(data.mapKeys { "http://x/${it.key}" }))
            .syncAsync(manifestOf(data), "http://x", onComplete = { result = it })
        assertEquals(5000, (result as SyncResult.Completed).downloadedCount)
    }

    @Test
    fun `callbacks from worker threads complete exactly once`() {
        val data = files(40)
        val downloader = ThreadedDownloader(data)
        val latch = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<SyncResult>())
        RealmAssetSyncPipeline(InMemoryAssetCache(), downloader).syncAsync(manifestOf(data), "http://x") {
            results += it
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        downloader.pool.shutdown()
        assertEquals(1, results.size)
        assertEquals(40, (results.single() as SyncResult.Completed).downloadedCount)
    }

    @Test
    fun `a corrupt file fails the sync once and stops fetching`() {
        val data = files(4)
        val downloader = DeferredDownloader(data)
        val cache = InMemoryAssetCache()
        val results = mutableListOf<SyncResult>()
        RealmAssetSyncPipeline(cache, downloader).syncAsync(manifestOf(data, corrupt = setOf("f2.png")), "http://x", onComplete = { results += it })
        downloader.pump()

        val failed = results.single() as SyncResult.Failed
        assertEquals("f2.png", failed.failedFile)
        assertTrue("Checksum mismatch" in failed.error)
        assertEquals(1, cache.count(), "only the good file before the bad one was cached")
    }

    @Test
    fun `closing the handle cancels before the next file`() {
        val data = files(3)
        val downloader = DeferredDownloader(data)
        val results = mutableListOf<SyncResult>()
        val handle = RealmAssetSyncPipeline(InMemoryAssetCache(), downloader).syncAsync(manifestOf(data), "http://x", onComplete = { results += it })
        handle.close()
        downloader.pump()

        assertEquals(SyncResult.Cancelled(completedCount = 1), results.single())
        assertEquals(0, downloader.pending.size, "no further download was started")
    }

    @Test
    fun `an up to date cache completes immediately without downloading`() {
        val data = files(2)
        val cache = InMemoryAssetCache()
        data.forEach { (path, bytes) -> cache.store(computeSha256(bytes), bytes, "png") }
        var result: SyncResult? = null
        RealmAssetSyncPipeline(cache, DeferredDownloader(data)).syncAsync(manifestOf(data), "http://x", onComplete = { result = it })
        assertEquals(SyncResult.UpToDate(2), result)
    }
}
