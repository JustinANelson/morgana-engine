package com.morgana.client.assets

import com.morgana.protocol.realms.AssetKind
import com.morgana.protocol.realms.AssetManifest
import com.morgana.protocol.realms.AssetManifestEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealmAssetSyncPipelineTest {

    private fun sampleManifest(entries: List<AssetManifestEntry>): AssetManifest {
        return AssetManifest(
            gameId = "test-game",
            version = "1.0.0",
            assets = entries.associateBy { it.path }
        )
    }

    @Test
    fun `calculateMissing identifies un-cached files accurately`() {
        val cache = InMemoryAssetCache()
        val downloader = InMemoryAssetDownloader()
        val pipeline = RealmAssetSyncPipeline(cache, downloader)

        val entryA = AssetManifestEntry(
            path = "tilesets/forest.png",
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sizeBytes = 1024,
            kind = AssetKind.TILESET
        )
        val entryB = AssetManifestEntry(
            path = "sprites/hero.png",
            sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            sizeBytes = 2048,
            kind = AssetKind.SPRITE
        )
        val manifest = sampleManifest(listOf(entryA, entryB))

        // Initially both missing
        val missing1 = pipeline.calculateMissing(manifest)
        assertEquals(2, missing1.size)

        // Pre-cache entryA
        val dummyBytesA = ByteArray(1024) { 0x41 }
        val hashA = computeSha256(dummyBytesA)
        val validEntryA = entryA.copy(sha256 = hashA)
        cache.store(hashA, dummyBytesA, "png")

        val manifestWithValidA = sampleManifest(listOf(validEntryA, entryB))
        val missing2 = pipeline.calculateMissing(manifestWithValidA)
        assertEquals(1, missing2.size)
        assertEquals("sprites/hero.png", missing2[0].path)
    }

    @Test
    fun `sync successfully downloads, verifies, and caches remote assets`() {
        val cache = InMemoryAssetCache()
        val downloader = InMemoryAssetDownloader()
        val pipeline = RealmAssetSyncPipeline(cache, downloader)

        val dataTileset = "PNG_TILESET_BYTES".toByteArray(Charsets.UTF_8)
        val hashTileset = computeSha256(dataTileset)
        downloader.registerMockAsset("https://cdn.example.com/assets/tilesets/dungeon.png", dataTileset)

        val dataSound = "WAV_SOUND_BYTES".toByteArray(Charsets.UTF_8)
        val hashSound = computeSha256(dataSound)
        downloader.registerMockAsset("https://cdn.example.com/assets/sounds/ambient.wav", dataSound)

        val entry1 = AssetManifestEntry(
            path = "tilesets/dungeon.png",
            sha256 = hashTileset,
            sizeBytes = dataTileset.size.toLong(),
            kind = AssetKind.TILESET
        )
        val entry2 = AssetManifestEntry(
            path = "sounds/ambient.wav",
            sha256 = hashSound,
            sizeBytes = dataSound.size.toLong(),
            kind = AssetKind.SOUND
        )
        val manifest = sampleManifest(listOf(entry1, entry2))

        val progressUpdates = AtomicInteger(0)
        val result = pipeline.sync(
            manifest = manifest,
            baseUrl = "https://cdn.example.com/assets",
            onProgress = { progressUpdates.incrementAndGet() }
        )

        assertTrue(result is SyncResult.Completed)
        val completed = result as SyncResult.Completed
        assertEquals(2, completed.downloadedCount)
        assertEquals(2, completed.cachedCount)
        assertEquals((dataTileset.size + dataSound.size).toLong(), completed.totalBytes)

        assertTrue(cache.has(hashTileset))
        assertTrue(cache.has(hashSound))
        assertTrue(progressUpdates.get() > 0)

        // Second sync is immediate UpToDate
        val secondResult = pipeline.sync(manifest, "https://cdn.example.com/assets")
        assertTrue(secondResult is SyncResult.UpToDate)
        assertEquals(2, (secondResult as SyncResult.UpToDate).cachedCount)
    }

    @Test
    fun `sync detects checksum mismatch and fails without poisoning cache`() {
        val cache = InMemoryAssetCache()
        val downloader = InMemoryAssetDownloader()
        val pipeline = RealmAssetSyncPipeline(cache, downloader)

        val data = "Corrupted_Or_Tampered_Content".toByteArray(Charsets.UTF_8)
        downloader.registerMockAsset("https://cdn.example.com/assets/sprites/boss.png", data)

        val entry = AssetManifestEntry(
            path = "sprites/boss.png",
            sha256 = "1111111111111111111111111111111111111111111111111111111111111111", // Wrong hash
            sizeBytes = data.size.toLong(),
            kind = AssetKind.SPRITE
        )
        val manifest = sampleManifest(listOf(entry))

        val result = pipeline.sync(manifest, "https://cdn.example.com/assets")
        assertTrue(result is SyncResult.Failed)
        val failed = result as SyncResult.Failed
        assertEquals("sprites/boss.png", failed.failedFile)
        assertTrue(failed.error.contains("Checksum mismatch"))
        assertFalse(cache.has(entry.sha256))
    }

    @Test
    fun `sync handles user cancellation gracefully`() {
        val cache = InMemoryAssetCache()
        val downloader = InMemoryAssetDownloader()
        val pipeline = RealmAssetSyncPipeline(cache, downloader)

        val entry = AssetManifestEntry(
            path = "music/town.ogg",
            sha256 = "2222222222222222222222222222222222222222222222222222222222222222",
            sizeBytes = 5000000L,
            kind = AssetKind.MUSIC
        )
        val manifest = sampleManifest(listOf(entry))

        val result = pipeline.sync(
            manifest = manifest,
            baseUrl = "https://cdn.example.com/assets",
            isCancelled = { true }
        )

        assertTrue(result is SyncResult.Cancelled)
        assertEquals(0, (result as SyncResult.Cancelled).completedCount)
    }

    @Test
    fun `syncAsync executes in background thread and notifies completion`() {
        val cache = InMemoryAssetCache()
        val downloader = InMemoryAssetDownloader()
        val pipeline = RealmAssetSyncPipeline(cache, downloader)

        val data = "ASYNC_TEST_BYTES".toByteArray(Charsets.UTF_8)
        val hash = computeSha256(data)
        downloader.registerMockAsset("http://mock/file.png", data)

        val entry = AssetManifestEntry(
            path = "file.png",
            sha256 = hash,
            sizeBytes = data.size.toLong(),
            kind = AssetKind.MISC
        )
        val manifest = sampleManifest(listOf(entry))

        val latch = CountDownLatch(1)
        var finalResult: SyncResult? = null

        val handle = pipeline.syncAsync(
            manifest = manifest,
            baseUrl = "http://mock",
            onProgress = {},
            onComplete = {
                finalResult = it
                latch.countDown()
            }
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(finalResult is SyncResult.Completed)
        assertTrue(cache.has(hash))
        handle.close()
    }

    @Test
    fun `resolveClientAssetBaseUrl adapts host templates for proxied WebSocket connections`() {
        assertEquals(
            "https://legion-5-laptop.tail4e16.ts.net/assets",
            RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                template = "http://{host}:7002/assets",
                host = "legion-5-laptop.tail4e16.ts.net",
                port = 443,
                encrypted = true,
                webSocket = true
            )
        )
        assertEquals(
            "https://example.com:8443/assets",
            RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                template = "http://{host}:7002/assets",
                host = "example.com",
                port = 8443,
                encrypted = true,
                webSocket = true
            )
        )
        assertEquals(
            "http://127.0.0.1:7002/assets",
            RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                template = "http://{host}:7002/assets",
                host = "127.0.0.1",
                port = 7002,
                encrypted = false,
                webSocket = true
            )
        )
        assertEquals(
            "http://[::1]:7002/assets",
            RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                template = "http://{host}:7002/assets",
                host = "::1",
                port = 7002,
                encrypted = false,
                webSocket = false
            )
        )
        assertEquals(
            "https://cdn.example.com/assets",
            RealmAssetSyncPipeline.resolveClientAssetBaseUrl(
                template = "https://cdn.example.com/assets",
                host = "legion-5-laptop.tail4e16.ts.net",
                port = 443,
                encrypted = true,
                webSocket = true
            )
        )
    }
}

