package com.morgana.client.assets

import com.morgana.protocol.realms.AssetBundle
import com.morgana.protocol.realms.AssetKind
import com.morgana.protocol.realms.AssetManifest
import com.morgana.protocol.realms.AssetManifestEntry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Small realm files travel several per request when the server can batch; nothing is lost when a batch falls short. */
class BatchedAssetSyncTest {

    private fun entry(path: String, bytes: ByteArray) = AssetManifestEntry(path, computeSha256(bytes), bytes.size.toLong(), AssetKind.SPRITE)

    private fun manifestOf(files: Map<String, ByteArray>) = AssetManifest(gameId = "g", assets = files.mapValues { (p, b) -> entry(p, b) })

    /**
     * Serves single files by URL and batches as [AssetBundle]s, like the server, and records every request. Paths in
     * [leaveOut] are marked as not included in batches; [failBatches] makes every batch request fail.
     */
    private class FakeServer(
        private val files: Map<String, ByteArray>,
        private val leaveOut: Set<String> = emptySet(),
        private val failBatches: Boolean = false
    ) : AssetDownloader {
        val requests = mutableListOf<String>()
        override fun download(url: String): Result<ByteArray> = error("unused")
        override fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
            requests += url
            if ("/_batch?" in url) {
                if (failBatches) return onDone(Result.failure(java.io.IOException("HTTP 502")))
                val paths = url.substringAfter('?').split('&').map { it.removePrefix("p=").replace("%20", " ") }
                return onDone(Result.success(AssetBundle.encode(paths.map { if (it in leaveOut) null else files[it] })))
            }
            val path = url.substringAfter("http://host/assets/")
            onDone(files[path]?.let { Result.success(it) } ?: Result.failure(java.io.IOException("404 $path")))
        }
    }

    private fun smallFiles(count: Int) = (1..count).associate { "icons/$it.png" to "icon-$it".toByteArray() }

    @Test
    fun `small files arrive in one request and are all verified and cached`() {
        val data = smallFiles(40)
        val server = FakeServer(data)
        val cache = InMemoryAssetCache()
        val pipeline = RealmAssetSyncPipeline(cache, server).apply { batching = true }
        var result: SyncResult? = null
        pipeline.syncAsync(manifestOf(data), "http://host/assets") { result = it }

        assertEquals(SyncResult.Completed(40, 40, data.values.sumOf { it.size.toLong() }), result)
        assertEquals(1, server.requests.size, "40 small files, one request")
        for ((path, bytes) in data) assertArrayEquals(bytes, cache.load(computeSha256(bytes)), path)
    }

    @Test
    fun `without batching every file is its own request, as before`() {
        val data = smallFiles(5)
        val server = FakeServer(data)
        var result: SyncResult? = null
        RealmAssetSyncPipeline(InMemoryAssetCache(), server).syncAsync(manifestOf(data), "http://host/assets") { result = it }
        assertTrue(result is SyncResult.Completed)
        assertEquals(5, server.requests.size)
        assertTrue(server.requests.none { "_batch" in it })
    }

    @Test
    fun `files a batch leaves out, or a failed batch, are fetched one by one`() {
        val data = smallFiles(6)
        val leftOut = FakeServer(data, leaveOut = setOf("icons/2.png", "icons/5.png"))
        var r1: SyncResult? = null
        RealmAssetSyncPipeline(InMemoryAssetCache(), leftOut).apply { batching = true }.syncAsync(manifestOf(data), "http://host/assets") { r1 = it }
        assertTrue(r1 is SyncResult.Completed, "$r1")
        assertEquals(listOf("http://host/assets/icons/2.png", "http://host/assets/icons/5.png"), leftOut.requests.drop(1))

        val broken = FakeServer(data, failBatches = true)
        var r2: SyncResult? = null
        val cache = InMemoryAssetCache()
        RealmAssetSyncPipeline(cache, broken).apply { batching = true }.syncAsync(manifestOf(data), "http://host/assets") { r2 = it }
        assertTrue(r2 is SyncResult.Completed, "$r2")
        assertEquals(1 + 6, broken.requests.size)
        assertEquals(6, cache.count())
    }

    @Test
    fun `a file whose bytes do not match the manifest still fails the sync when batched`() {
        val data = smallFiles(3)
        val manifest = manifestOf(data).let { m -> m.copy(assets = m.assets + ("icons/2.png" to m.assets.getValue("icons/2.png").copy(sha256 = "0".repeat(64)))) }
        var result: SyncResult? = null
        RealmAssetSyncPipeline(InMemoryAssetCache(), FakeServer(data)).apply { batching = true }.syncAsync(manifest, "http://host/assets") { result = it }
        assertTrue(result is SyncResult.Failed && (result as SyncResult.Failed).failedFile == "icons/2.png", "$result")
    }

    @Test
    fun `batches respect the file, byte and URL limits, and large files go alone`() {
        val pipeline = RealmAssetSyncPipeline(InMemoryAssetCache(), FakeServer(emptyMap()))
        fun e(path: String, size: Long) = AssetManifestEntry(path, "a".repeat(64), size, AssetKind.ICON)

        val many = (1..150).map { e("icons/$it.png", 100) }
        val jobs = pipeline.planJobs(many, "http://host/assets", batching = true)
        assertTrue(jobs.all { it is RealmAssetSyncPipeline.Job.Batch })
        assertTrue(jobs.all { it.entries.size <= RealmAssetSyncPipeline.BATCH_MAX_FILES })
        assertEquals(150, jobs.sumOf { it.entries.size })

        val heavy = (1..10).map { e("tilesets/$it.png", 200L * 1024) } + e("music/theme.ogg", 5L * 1024 * 1024)
        val heavyJobs = pipeline.planJobs(heavy, "http://host/assets", batching = true)
        assertTrue(heavyJobs.filterIsInstance<RealmAssetSyncPipeline.Job.Batch>().all { b -> b.entries.sumOf { it.sizeBytes } <= RealmAssetSyncPipeline.BATCH_MAX_BYTES })
        assertEquals("http://host/assets/music/theme.ogg", heavyJobs.last().url, "the large file goes alone, last")

        val longNames = (1..60).map { e("sprites/" + "x".repeat(80) + "$it.png", 10) }
        assertTrue(pipeline.planJobs(longNames, "http://host/assets", batching = true).all { it.url.length <= RealmAssetSyncPipeline.BATCH_MAX_URL })

        val one = pipeline.planJobs(listOf(e("icons/1.png", 10)), "http://host/assets", batching = true)
        assertEquals(listOf<RealmAssetSyncPipeline.Job>(RealmAssetSyncPipeline.Job.Single(e("icons/1.png", 10), "http://host/assets/icons/1.png")), one)
    }

    @Test
    fun `the bundle format round-trips and rejects damaged data`() {
        val files = listOf("a".toByteArray(), null, ByteArray(0), ByteArray(300) { it.toByte() })
        val decoded = AssetBundle.decode(AssetBundle.encode(files), files.size)
        assertArrayEquals(files[0], decoded[0])
        assertNull(decoded[1])
        assertArrayEquals(files[2], decoded[2])
        assertArrayEquals(files[3], decoded[3])
        val good = AssetBundle.encode(files)
        assertThrows(IllegalArgumentException::class.java) { AssetBundle.decode(good, 3) }
        assertThrows(IllegalArgumentException::class.java) { AssetBundle.decode(good.copyOf(good.size - 1), 4) }
        assertThrows(IllegalArgumentException::class.java) { AssetBundle.decode("nope1234".toByteArray(), 0) }
        assertEquals("icons/a%20b%C3%A9%26.png", AssetBundle.encodeQueryValue("icons/a bé&.png"))
    }

    @Test
    fun `the persistent cache index drops the least recently used files beyond its limit, never the current realm's`() {
        val index = AssetCacheIndex(capBytes = 100)
        fun sha(n: Int) = n.toString().padStart(64, '0')
        index.put(sha(1), 40, nowMillis = 10)
        index.put(sha(2), 40, nowMillis = 30)
        index.put(sha(3), 40, nowMillis = 20)
        assertEquals(listOf(sha(1)), index.evictionCandidates())
        assertEquals(listOf(sha(3)), index.evictionCandidates(keep = setOf(sha(1))))
        index.touch(sha(1), nowMillis = 50)
        assertEquals(listOf(sha(3)), index.evictionCandidates())

        val back = AssetCacheIndex.parse(index.format() + ";garbage;zz:1:2", capBytes = 100)
        assertEquals(3, back.size)
        assertEquals(120, back.totalBytes)
        assertTrue(sha(2) in back)
        assertFalse(AssetCacheIndex(100).evictionCandidates().isNotEmpty())
    }
}
