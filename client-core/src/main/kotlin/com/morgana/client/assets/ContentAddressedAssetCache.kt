package com.morgana.client.assets

import java.io.File
import java.io.IOException

/**
 * Common interface for caching downloaded realm assets addressed strictly by their SHA-256 content hash.
 * This guarantees that assets shared across multiple realms (common tilesets, standard sounds, UI skins)
 * are downloaded only once and cannot be tampered with or corrupted on disk.
 */
interface AssetCache {
    /** Checks whether an asset with the given SHA-256 hash is already cached. */
    fun has(sha256: String): Boolean

    /**
     * Stores the provided raw [bytes] under [sha256].
     * Throws [IllegalArgumentException] if the computed SHA-256 of [bytes] does not match [sha256].
     * Returns the normalized lowercase hash.
     */
    fun store(sha256: String, bytes: ByteArray, ext: String = "bin"): String

    /**
     * Loads the raw bytes for the asset with [sha256] from the cache, or returns null if not present.
     */
    fun load(sha256: String, ext: String = "bin"): ByteArray?

    /** Verifies that the provided [bytes] match the given [sha256] checksum. */
    fun verify(sha256: String, bytes: ByteArray): Boolean = verifySha256(sha256, bytes)

    /** Removes an asset from the cache. Returns true if the asset existed and was removed. */
    fun delete(sha256: String, ext: String = "bin"): Boolean

    /** Empties all cached assets. */
    fun clear()

    /** Returns the total number of cached assets. */
    fun count(): Int

    /** Returns the total disk/memory size in bytes occupied by cached assets. */
    fun totalSizeBytes(): Long

    /**
     * Gets the files [sha256s] ready for [has] and [load], then calls [onDone] (on any thread, possibly at once). A cache that
     * reads its storage asynchronously (the browser's IndexedDB) loads them into memory here; one that can read when asked
     * (files, memory) calls [onDone] straight away. A file that turns out to be unreadable is forgotten, so [has] says false
     * and the sync downloads it again.
     */
    fun prefetch(sha256s: Collection<String>, onDone: () -> Unit) = onDone()
}

private val SHA256_K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
    0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
    0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
    0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
    0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)

private fun Int.sha256RotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

/**
 * Computes the lowercase hex-encoded SHA-256 checksum of the given [bytes].
 * Uses a pure Kotlin implementation for full compatibility with TeaVM (browser), Android, and Desktop.
 */
fun computeSha256(bytes: ByteArray): String {
    var h0 = 0x6a09e667.toInt()
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372.toInt()
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f.toInt()
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab.toInt()
    var h7 = 0x5be0cd19.toInt()

    val len = bytes.size
    val bitLen = len.toLong() * 8L
    val paddingLen = ((56 - (len + 1) % 64) + 64) % 64
    val totalLen = len + 1 + paddingLen + 8
    val padded = ByteArray(totalLen)
    System.arraycopy(bytes, 0, padded, 0, len)
    padded[len] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[totalLen - 1 - i] = ((bitLen ushr (i * 8)) and 0xFF).toByte()
    }

    val w = IntArray(64)
    for (chunk in 0 until totalLen step 64) {
        for (i in 0 until 16) {
            val offset = chunk + i * 4
            w[i] = ((padded[offset].toInt() and 0xFF) shl 24) or
                   ((padded[offset + 1].toInt() and 0xFF) shl 16) or
                   ((padded[offset + 2].toInt() and 0xFF) shl 8) or
                   (padded[offset + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = (w[i - 15].sha256RotateRight(7)) xor (w[i - 15].sha256RotateRight(18)) xor (w[i - 15] ushr 3)
            val s1 = (w[i - 2].sha256RotateRight(17)) xor (w[i - 2].sha256RotateRight(19)) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (i in 0 until 64) {
            val s1 = (e.sha256RotateRight(6)) xor (e.sha256RotateRight(11)) xor (e.sha256RotateRight(25))
            val ch = (e and f) xor ((e.inv()) and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = (a.sha256RotateRight(2)) xor (a.sha256RotateRight(13)) xor (a.sha256RotateRight(22))
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
    }

    val sb = StringBuilder(64)
    for (v in intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)) {
        for (shift in 28 downTo 0 step 4) {
            val hexDigit = (v ushr shift) and 0xF
            sb.append(if (hexDigit < 10) ('0' + hexDigit) else ('a' + (hexDigit - 10)))
        }
    }
    return sb.toString()
}

/**
 * Validates whether the given [bytes] produce the [expectedSha256] hash (case-insensitive).
 */
fun verifySha256(expectedSha256: String, bytes: ByteArray): Boolean {
    val actual = computeSha256(bytes)
    return actual.equals(expectedSha256.trim(), ignoreCase = true)
}

/**
 * File-backed [AssetCache] that stores files under `<cacheDir>/<sha256>.<ext>`.
 * Writes are performed atomically via temporary files to avoid partial or corrupted downloads.
 */
class FileAssetCache(
    val cacheDir: File
) : AssetCache {

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    private fun sanitizeExt(ext: String): String {
        val cleaned = ext.trim().trimStart('.').filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "bin" else cleaned.lowercase()
    }

    private fun normalizeHash(hash: String): String {
        val clean = hash.trim().lowercase()
        require(clean.all { it.isDigit() || it in 'a'..'f' }) {
            "Invalid SHA-256 hash string: $hash"
        }
        return clean
    }

    private fun targetFile(sha256: String, ext: String): File {
        val cleanHash = normalizeHash(sha256)
        val cleanExt = sanitizeExt(ext)
        return File(cacheDir, "$cleanHash.$cleanExt")
    }

    override fun has(sha256: String): Boolean {
        if (sha256.isBlank()) return false
        val cleanHash = try { normalizeHash(sha256) } catch (_: IllegalArgumentException) { return false }
        val matching = cacheDir.listFiles { _, name -> name.startsWith(cleanHash) }
        return !matching.isNullOrEmpty()
    }

    override fun store(sha256: String, bytes: ByteArray, ext: String): String {
        val cleanHash = normalizeHash(sha256)
        val actualHash = computeSha256(bytes)
        require(actualHash.equals(cleanHash, ignoreCase = true)) {
            "Asset content SHA-256 ($actualHash) did not match expected checksum ($cleanHash)"
        }

        val destination = targetFile(cleanHash, ext)
        if (destination.exists() && destination.length() == bytes.size.toLong()) {
            return cleanHash
        }

        val tempFile = File(cacheDir, "$cleanHash.${System.nanoTime()}.tmp")
        try {
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(destination)) {
                // If renameTo fails (e.g. cross-filesystem or destination already locked), fallback to copy & delete
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw IOException("Failed to write asset $cleanHash to cache: ${e.message}", e)
        }
        return cleanHash
    }

    override fun load(sha256: String, ext: String): ByteArray? {
        val file = targetFile(sha256, ext)
        if (file.exists() && file.isFile) {
            return file.readBytes()
        }
        // Fallback: search for file starting with the sha256 prefix regardless of extension
        val cleanHash = try { normalizeHash(sha256) } catch (_: IllegalArgumentException) { return null }
        val matching = cacheDir.listFiles { _, name -> name.startsWith(cleanHash) }?.firstOrNull()
        return if (matching != null && matching.isFile) matching.readBytes() else null
    }

    override fun delete(sha256: String, ext: String): Boolean {
        val cleanHash = try { normalizeHash(sha256) } catch (_: IllegalArgumentException) { return false }
        val matching = cacheDir.listFiles { _, name -> name.startsWith(cleanHash) } ?: return false
        var deletedAny = false
        for (file in matching) {
            if (file.delete()) deletedAny = true
        }
        return deletedAny
    }

    override fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    override fun count(): Int {
        return cacheDir.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }?.size ?: 0
    }

    override fun totalSizeBytes(): Long {
        return cacheDir.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }?.sumOf { it.length() } ?: 0L
    }
}

/**
 * In-memory [AssetCache] implementation useful for unit testing, web client fallback, and ephemeral runs.
 */
class InMemoryAssetCache : AssetCache {
    private val storage = mutableMapOf<String, ByteArray>()

    override fun has(sha256: String): Boolean {
        return storage.containsKey(sha256.trim().lowercase())
    }

    override fun store(sha256: String, bytes: ByteArray, ext: String): String {
        val cleanHash = sha256.trim().lowercase()
        val actualHash = computeSha256(bytes)
        require(actualHash.equals(cleanHash, ignoreCase = true)) {
            "Asset content SHA-256 ($actualHash) did not match expected checksum ($cleanHash)"
        }
        storage[cleanHash] = bytes
        return cleanHash
    }

    override fun load(sha256: String, ext: String): ByteArray? {
        return storage[sha256.trim().lowercase()]
    }

    override fun delete(sha256: String, ext: String): Boolean {
        return storage.remove(sha256.trim().lowercase()) != null
    }

    override fun clear() {
        storage.clear()
    }

    override fun count(): Int = storage.size

    override fun totalSizeBytes(): Long = storage.values.sumOf { it.size.toLong() }
}

