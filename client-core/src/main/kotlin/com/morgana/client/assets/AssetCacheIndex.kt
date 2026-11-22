package com.morgana.client.assets

/**
 * What a persistent asset cache holds (hash, size, when last used), without the bytes, and which files to drop when it
 * grows past its size limit. Pure, so the eviction rules are unit-tested; the browser cache (`BrowserAssetCache`) keeps
 * one of these in memory and the bytes in IndexedDB.
 */
class AssetCacheIndex(private val capBytes: Long) {
    data class Entry(val sizeBytes: Long, val lastUsedMillis: Long)

    private val entries = LinkedHashMap<String, Entry>()

    operator fun contains(sha256: String): Boolean = norm(sha256) in entries
    val size: Int get() = entries.size
    val totalBytes: Long get() = entries.values.sumOf { it.sizeBytes }

    fun put(sha256: String, sizeBytes: Long, nowMillis: Long) {
        entries[norm(sha256)] = Entry(sizeBytes, nowMillis)
    }

    fun touch(sha256: String, nowMillis: Long) {
        val key = norm(sha256)
        entries[key]?.let { entries[key] = it.copy(lastUsedMillis = nowMillis) }
    }

    fun remove(sha256: String): Boolean = entries.remove(norm(sha256)) != null

    fun clear() = entries.clear()

    /**
     * The files to drop, least recently used first, until the total is at most the limit. Files in [keep] (the realm being
     * played) are never chosen, even if that leaves the cache over its limit.
     */
    fun evictionCandidates(keep: Set<String> = emptySet()): List<String> {
        var total = totalBytes
        if (total <= capBytes) return emptyList()
        val keepNorm = keep.mapTo(HashSet()) { norm(it) }
        val out = mutableListOf<String>()
        for ((sha, e) in entries.entries.sortedBy { it.value.lastUsedMillis }) {
            if (total <= capBytes) break
            if (sha in keepNorm) continue
            out += sha
            total -= e.sizeBytes
        }
        return out
    }

    /** `sha:size:lastUsed;...`, the form the browser's storage hands back (see [parse]). */
    fun format(): String = entries.entries.joinToString(";") { "${it.key}:${it.value.sizeBytes}:${it.value.lastUsedMillis}" }

    companion object {
        /** Reads [format]'s text; malformed parts are skipped (they are then simply downloaded again). */
        fun parse(text: String, capBytes: Long): AssetCacheIndex {
            val index = AssetCacheIndex(capBytes)
            for (part in text.split(';')) {
                val bits = part.split(':')
                if (bits.size != 3) continue
                val sha = bits[0].trim().lowercase()
                if (sha.length != 64 || !sha.all { it.isDigit() || it in 'a'..'f' }) continue
                val size = bits[1].toLongOrNull() ?: continue
                val used = bits[2].toDoubleOrNull()?.toLong() ?: continue
                index.put(sha, size, used)
            }
            return index
        }

        private fun norm(sha: String) = sha.trim().lowercase()
    }
}
