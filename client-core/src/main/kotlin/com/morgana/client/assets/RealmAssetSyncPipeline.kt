package com.morgana.client.assets

import com.morgana.protocol.realms.AssetBundle
import com.morgana.protocol.realms.AssetManifest
import com.morgana.protocol.realms.AssetManifestEntry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-time progress snapshot of an in-flight asset synchronization.
 */
data class SyncProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val currentFile: String,
    val isFinished: Boolean = false,
    val error: String? = null
) {
    /** 0.0 to 1.0 download progress */
    val percent: Float
        get() {
            if (totalBytes > 0L) {
                return (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
            if (totalFiles > 0) {
                return (completedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            }
            return 1f
        }
}

/**
 * Result of the asset sync operation.
 */
sealed interface SyncResult {
    /** All required assets were already present in local cache. */
    data class UpToDate(val cachedCount: Int) : SyncResult

    /** Assets were successfully downloaded and verified into the cache. */
    data class Completed(val downloadedCount: Int, val cachedCount: Int, val totalBytes: Long) : SyncResult

    /** The user cancelled the asset download operation. */
    data class Cancelled(val completedCount: Int) : SyncResult

    /** A download error or SHA-256 verification failure occurred. */
    data class Failed(val error: String, val failedFile: String? = null) : SyncResult
}

/**
 * Content-addressed asset synchronization pipeline for Morgana Realms.
 * Automatically inspects the [AssetManifest], calculates the missing delta against [AssetCache],
 * streams files via [AssetDownloader], validates cryptographic SHA-256 integrity,
 * and atomically stores assets into local cache before entering the realm.
 */
class RealmAssetSyncPipeline(
    val assetCache: AssetCache,
    val downloader: AssetDownloader = HttpAssetDownloader()
) {

    /**
     * Determines which asset entries are missing from local cache.
     */
    fun calculateMissing(manifest: AssetManifest): List<AssetManifestEntry> {
        return manifest.assets.values.filter { entry ->
            !assetCache.has(entry.sha256)
        }
    }

    /**
     * Resolves the full download URL for an asset entry.
     */
    fun resolveUrl(baseUrl: String, entry: AssetManifestEntry): String {
        val base = baseUrl.trimEnd('/')
        val rel = entry.path.trimStart('/')
        return "$base/$rel"
    }

    private fun extractExtension(path: String): String {
        val fileName = File(path).name
        val idx = fileName.lastIndexOf('.')
        return if (idx >= 0 && idx < fileName.length - 1) fileName.substring(idx + 1).lowercase() else "bin"
    }

    /**
     * Synchronously runs the asset synchronization pipeline.
     */
    fun sync(
        manifest: AssetManifest,
        baseUrl: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (SyncProgress) -> Unit = {}
    ): SyncResult {
        val allEntries = manifest.assets.values.toList()
        val missing = calculateMissing(manifest)

        if (missing.isEmpty()) {
            val progress = SyncProgress(
                totalFiles = allEntries.size,
                completedFiles = allEntries.size,
                totalBytes = 0L,
                downloadedBytes = 0L,
                currentFile = "",
                isFinished = true
            )
            onProgress(progress)
            return SyncResult.UpToDate(cachedCount = allEntries.size)
        }

        val totalBytes = missing.sumOf { it.sizeBytes }
        var downloadedBytes = 0L
        var completedFiles = 0

        for (entry in missing) {
            if (isCancelled()) {
                return SyncResult.Cancelled(completedCount = completedFiles)
            }

            onProgress(
                SyncProgress(
                    totalFiles = missing.size,
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                    currentFile = entry.path,
                    isFinished = false
                )
            )

            val downloadUrl = resolveUrl(baseUrl, entry)
            val bytes = when (val checked = verifyAndStore(entry, downloadUrl, downloader.download(downloadUrl))) {
                is Stored -> checked.bytes
                is Rejected -> return checked.failure
            }

            downloadedBytes += bytes.size
            completedFiles++

            onProgress(
                SyncProgress(
                    totalFiles = missing.size,
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                    currentFile = entry.path,
                    isFinished = completedFiles == missing.size
                )
            )
        }

        return SyncResult.Completed(
            downloadedCount = completedFiles,
            cachedCount = allEntries.size,
            totalBytes = downloadedBytes
        )
    }

    private sealed interface Checked
    private class Stored(val bytes: ByteArray) : Checked
    private class Rejected(val failure: SyncResult.Failed) : Checked

    /** Checks a finished download against the manifest hash and caches it; shared by the blocking and async paths. */
    private fun verifyAndStore(entry: AssetManifestEntry, downloadUrl: String, downloadResult: Result<ByteArray>): Checked {
        if (downloadResult.isFailure) {
            val err = downloadResult.exceptionOrNull()?.message ?: "Download failed for $downloadUrl"
            return Rejected(SyncResult.Failed(error = err, failedFile = entry.path))
        }
        val bytes = downloadResult.getOrThrow()
        if (!verifySha256(entry.sha256, bytes)) {
            return Rejected(
                SyncResult.Failed(
                    error = "Checksum mismatch for ${entry.path} (expected ${entry.sha256}, got ${computeSha256(bytes)})",
                    failedFile = entry.path
                )
            )
        }
        try {
            assetCache.store(entry.sha256, bytes, extractExtension(entry.path))
        } catch (e: Exception) {
            return Rejected(SyncResult.Failed(error = "Failed to cache asset ${entry.path}: ${e.message}", failedFile = entry.path))
        }
        return Stored(bytes)
    }

    /**
     * True when the server can send several files per request ([com.morgana.protocol.ProtocolCapabilities.ASSET_BATCH]);
     * set per login. Small files then travel in [AssetBundle]s, which matters most behind a TLS proxy, where every request
     * is a new connection and handshake.
     */
    var batching: Boolean = false

    /** One request of a sync: a single file, or several small ones in one [AssetBundle]. */
    sealed interface Job {
        val url: String
        val entries: List<AssetManifestEntry>
        data class Single(val entry: AssetManifestEntry, override val url: String) : Job {
            override val entries: List<AssetManifestEntry> get() = listOf(entry)
        }
        data class Batch(override val entries: List<AssetManifestEntry>, override val url: String) : Job
    }

    /**
     * Splits [missing] into requests. With [batching], files up to [BATCH_FILE_MAX_BYTES] are grouped (at most
     * [BATCH_MAX_FILES] files, [BATCH_MAX_BYTES] bytes and a [BATCH_MAX_URL]-character URL per request); larger files, and a
     * group that would hold only one file, are fetched on their own. Batches come first so the many small files are done
     * in a few round trips.
     */
    fun planJobs(missing: List<AssetManifestEntry>, baseUrl: String, batching: Boolean = this.batching): List<Job> {
        if (!batching) return missing.map { Job.Single(it, resolveUrl(baseUrl, it)) }
        val base = baseUrl.trimEnd('/') + "/" + AssetBundle.BATCH_PATH + "?"
        val (small, large) = missing.partition { it.sizeBytes in 0..BATCH_FILE_MAX_BYTES }
        val jobs = mutableListOf<Job>()
        var group = mutableListOf<AssetManifestEntry>()
        var groupBytes = 0L
        var url = StringBuilder(base)
        fun close() {
            when (group.size) {
                0 -> {}
                1 -> jobs += Job.Single(group[0], resolveUrl(baseUrl, group[0]))
                else -> jobs += Job.Batch(group, url.toString())
            }
            group = mutableListOf()
            groupBytes = 0L
            url = StringBuilder(base)
        }
        for (entry in small) {
            val param = "p=" + AssetBundle.encodeQueryValue(entry.path.trimStart('/'))
            val full = group.size >= BATCH_MAX_FILES || groupBytes + entry.sizeBytes > BATCH_MAX_BYTES ||
                url.length + 1 + param.length > BATCH_MAX_URL
            if (group.isNotEmpty() && full) close()
            if (group.isNotEmpty()) url.append('&')
            url.append(param)
            group += entry
            groupBytes += entry.sizeBytes
        }
        close()
        return jobs + large.map { Job.Single(it, resolveUrl(baseUrl, it)) }
    }

    /**
     * Runs the pipeline without blocking the caller and without a thread of its own: requests go one after another
     * through [AssetDownloader.downloadAsync], so it works in a browser (one thread, asynchronous `fetch`) as well as on
     * the JVM (where the downloader supplies the worker thread). [onProgress] and [onComplete] run on whichever thread
     * the downloader finishes on, so callers hop to their UI thread. Returns an [AutoCloseable] that cancels the sync.
     *
     * Small files are fetched in batches when [batching] is on (see [planJobs]); a batch that fails or leaves files out
     * falls back to fetching those files one by one, so batching can only save requests, never lose files.
     */
    fun syncAsync(
        manifest: AssetManifest,
        baseUrl: String,
        onProgress: (SyncProgress) -> Unit = {},
        onComplete: (SyncResult) -> Unit = {}
    ): AutoCloseable {
        val cancelled = AtomicBoolean(false)
        val handle = AutoCloseable { cancelled.set(true) }

        val allEntries = manifest.assets.values.toList()
        val missing = calculateMissing(manifest)
        if (missing.isEmpty()) {
            onProgress(SyncProgress(allEntries.size, allEntries.size, 0L, 0L, "", isFinished = true))
            onComplete(SyncResult.UpToDate(cachedCount = allEntries.size))
            return handle
        }

        val totalBytes = missing.sumOf { it.sizeBytes }
        val queue = ArrayDeque(planJobs(missing, baseUrl))
        var completed = 0
        var downloadedBytes = 0L
        val finished = AtomicBoolean(false)

        fun progress(file: String, done: Boolean) = SyncProgress(
            totalFiles = missing.size,
            completedFiles = completed,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            currentFile = file,
            isFinished = done
        )

        fun finish(result: SyncResult) {
            finished.set(true)
            onComplete(result)
        }

        /** Verifies and stores one downloaded file; false (after finishing with the failure) when it was rejected. */
        fun accept(entry: AssetManifestEntry, url: String, result: Result<ByteArray>): Boolean =
            when (val checked = verifyAndStore(entry, url, result)) {
                is Rejected -> { finish(checked.failure); false }
                is Stored -> {
                    downloadedBytes += checked.bytes.size
                    completed++
                    onProgress(progress(entry.path, completed == missing.size))
                    true
                }
            }

        /** Handles one finished request: stores what arrived and queues single requests for anything a batch did not bring. */
        fun handleResult(job: Job, result: Result<ByteArray>) {
            when (job) {
                is Job.Single -> accept(job.entry, job.url, result)
                is Job.Batch -> {
                    val files = result.getOrNull()?.let { runCatching { AssetBundle.decode(it, job.entries.size) }.getOrNull() }
                    if (files == null) {
                        // The whole batch failed (a proxy refused it, a broken response): fetch its files one by one.
                        job.entries.forEach { queue.addLast(Job.Single(it, resolveUrl(baseUrl, it))) }
                        return
                    }
                    for ((entry, bytes) in job.entries.zip(files)) {
                        if (bytes == null) queue.addLast(Job.Single(entry, resolveUrl(baseUrl, entry)))
                        else if (!accept(entry, job.url, Result.success(bytes))) return
                    }
                }
            }
        }

        // Each pass starts one request. The callback may run before downloadAsync returns (a synchronous downloader), on
        // another thread, or much later; `state` decides who continues so the chain never recurses, stalls or runs twice.
        fun step() {
            while (!finished.get()) {
                if (cancelled.get()) return finish(SyncResult.Cancelled(completedCount = completed))
                val job = queue.removeFirstOrNull()
                    ?: return finish(SyncResult.Completed(downloadedCount = completed, cachedCount = allEntries.size, totalBytes = downloadedBytes))
                onProgress(progress(job.entries.first().path, false))
                val state = AtomicInteger(PENDING)
                downloader.downloadAsync(job.url) { result ->
                    handleResult(job, result)
                    // Finished before downloadAsync returned: the loop below carries on. Otherwise this callback continues the chain.
                    if (!state.compareAndSet(PENDING, CALLED_BACK) && !finished.get()) step()
                }
                // Callback not yet seen: it will continue the chain itself. Seen already: loop to the next request.
                if (state.compareAndSet(PENDING, RETURNED)) return
            }
        }

        step()
        return handle
    }

    companion object {
        private const val PENDING = 0
        private const val RETURNED = 1
        private const val CALLED_BACK = 2

        /** Files up to this size may share a request; larger ones are fetched alone. */
        const val BATCH_FILE_MAX_BYTES = 256L * 1024
        /** Most bytes one batch asks for (well below the server's [AssetBundle.MAX_BYTES]). */
        const val BATCH_MAX_BYTES = 1024L * 1024
        /** Most files one batch asks for (below the server's [AssetBundle.MAX_FILES]). */
        const val BATCH_MAX_FILES = 64
        /** Longest batch URL: the server's HTTP decoder refuses request lines over 4096 bytes. */
        const val BATCH_MAX_URL = 3000

        /**
         * Resolves the server's `assetBaseUrl` for this client connection.
         *
         * When the server hosts its own art, it sends a template containing `{host}` (e.g. `http://{host}:7002/assets`).
         * For a WebSocket (browser) client, the connection may pass through a TLS reverse proxy (such as Tailscale Funnel)
         * on a different public scheme and port than the server's internal listener, so `{host}` templates are resolved to
         * the scheme (`https` when [encrypted], else `http`), [host], and [port] that the WebSocket itself connected to.
         * Explicit operator CDN URLs (without `{host}`) are returned unchanged.
         */
        fun resolveClientAssetBaseUrl(
            template: String?,
            host: String,
            port: Int,
            encrypted: Boolean,
            webSocket: Boolean
        ): String? {
            val raw = template?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            if (!raw.contains("{host}")) return raw
            if (webSocket) {
                val scheme = if (encrypted) "https" else "http"
                val defaultPort = if (encrypted) 443 else 80
                val portSuffix = if (port == defaultPort) "" else ":$port"
                val pathIndex = raw.indexOf('/', raw.indexOf("{host}"))
                val path = if (pathIndex >= 0) raw.substring(pathIndex) else "/assets"
                return "$scheme://$formattedHost$portSuffix$path"
            }
            return raw.replace("{host}", formattedHost)
        }
    }
}
