package com.morgana.client.assets

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AssetCacheTest {

    @Test
    fun `computeSha256 produces expected checksum`() {
        val testBytes = "Hello Morgana Realms".toByteArray(Charsets.UTF_8)
        val hash = computeSha256(testBytes)
        // Verify deterministic lowercase 64-char hex
        assertEquals(64, hash.length)
        assertTrue(verifySha256(hash, testBytes))
        assertFalse(verifySha256(hash, "Tampered Content".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `InMemoryAssetCache stores and loads assets accurately`() {
        val cache = InMemoryAssetCache()
        val data = "TileData_42".toByteArray(Charsets.UTF_8)
        val hash = computeSha256(data)

        assertFalse(cache.has(hash))
        val storedHash = cache.store(hash, data, "png")
        assertEquals(hash, storedHash)
        assertTrue(cache.has(hash))
        assertEquals(1, cache.count())
        assertEquals(data.size.toLong(), cache.totalSizeBytes())

        val loaded = cache.load(hash, "png")
        assertArrayEquals(data, loaded)

        // Hash mismatch throws IllegalArgumentException
        assertThrows<IllegalArgumentException> {
            cache.store("0000000000000000000000000000000000000000000000000000000000000000", data)
        }

        assertTrue(cache.delete(hash))
        assertFalse(cache.has(hash))
        assertNull(cache.load(hash))
        assertEquals(0, cache.count())
    }

    @Test
    fun `FileAssetCache persists assets to disk and verifies hash`(@TempDir tempDir: File) {
        val cache = FileAssetCache(File(tempDir, "assets"))
        val dataA = "Asset_A_Tileset_Pack".toByteArray(Charsets.UTF_8)
        val hashA = computeSha256(dataA)

        val dataB = "Asset_B_Sound_Effect".toByteArray(Charsets.UTF_8)
        val hashB = computeSha256(dataB)

        cache.store(hashA, dataA, "png")
        cache.store(hashB, dataB, "wav")

        assertTrue(cache.has(hashA))
        assertTrue(cache.has(hashB))
        assertEquals(2, cache.count())
        assertEquals((dataA.size + dataB.size).toLong(), cache.totalSizeBytes())

        assertArrayEquals(dataA, cache.load(hashA, "png"))
        assertArrayEquals(dataB, cache.load(hashB, "wav"))

        // Re-storing identical content is idempotent
        cache.store(hashA, dataA, "png")
        assertEquals(2, cache.count())

        // Clearing removes all files
        cache.clear()
        assertEquals(0, cache.count())
        assertFalse(cache.has(hashA))
        assertFalse(cache.has(hashB))
    }
}

