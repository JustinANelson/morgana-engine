package com.morgana.client.assets

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Port decoupling HTTP/network asset streaming from the client UI and cache.
 */
interface AssetDownloader {
    /**
     * Downloads raw binary bytes from [url].
     * Returns [Result.success] with byte contents, or [Result.failure] on I/O or HTTP error.
     */
    fun download(url: String): Result<ByteArray>

    /**
     * Downloads [url] without blocking the caller where the platform needs that (a browser has one thread and no
     * blocking HTTP): [onDone] receives the result later, on any thread. The default runs [download] right away, so a
     * simple downloader (a test double) works unchanged; real network downloaders override this.
     */
    fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
        onDone(download(url))
    }
}

/**
 * JVM standard HTTP/HTTPS client for streaming binary game assets.
 */
class HttpAssetDownloader(
    val connectTimeoutMs: Int = 5000,
    val readTimeoutMs: Int = 15000,
    val userAgent: String = "MorganaAssetClient/1.0"
) : AssetDownloader {

    /** A blocking download must not run on the caller's (render) thread, so each one gets a short-lived daemon thread. */
    override fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
        Thread({ onDone(download(url)) }, "Morgana-AssetDownload").apply { isDaemon = true }.start()
    }

    override fun download(url: String): Result<ByteArray> {
        return try {
            val uri = URI.create(url)
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.instanceFollowRedirects = true

            val status = conn.responseCode
            if (status in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                Result.success(bytes)
            } else {
                Result.failure(IOException("HTTP download failed for $url (Status: $status)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * In-memory asset downloader for tests and simulated offline environments.
 */
class InMemoryAssetDownloader(
    private val files: Map<String, ByteArray> = emptyMap()
) : AssetDownloader {

    private val dynamicFiles = files.toMutableMap()

    fun registerMockAsset(url: String, bytes: ByteArray) {
        dynamicFiles[url] = bytes
    }

    override fun download(url: String): Result<ByteArray> {
        val found = dynamicFiles[url] ?: dynamicFiles.entries.firstOrNull { (k, _) -> url.endsWith(k) }?.value
        return if (found != null) {
            Result.success(found)
        } else {
            Result.failure(IOException("Mock asset not found: $url"))
        }
    }
}

