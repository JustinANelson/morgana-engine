package com.morgana.client.web

import com.morgana.client.assets.AssetDownloader
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject
import org.teavm.jso.typedarrays.Int8Array
import java.io.IOException

/** Receives the outcome of one `fetch`: [bytes] is set when [ok], [error] otherwise. */
@JSFunctor
fun interface FetchCallback : JSObject {
    fun done(ok: Boolean, bytes: Int8Array?, error: String?)
}

@JSBody(params = ["url", "cb"], script = """
    fetch(url, { cache: 'no-cache' }).then(function (r) {
        if (!r.ok) { cb(false, null, 'HTTP ' + r.status); return; }
        return r.arrayBuffer().then(function (b) { cb(true, new Int8Array(b), null); });
    }).catch(function (e) { cb(false, null, String(e)); });
""")
private external fun fetchBytes(url: String, cb: FetchCallback)

/**
 * [AssetDownloader] over the browser's `fetch`. A browser has one thread and no blocking HTTP, so only [downloadAsync] is
 * supported; the callback runs on that thread after the response arrives. Requests always revalidate (`no-cache`), so a
 * file edited since the browser last saw it is never served stale, while unchanged files cost only a `304`. The pipeline
 * verifies every file's SHA-256 regardless of source.
 * Cross-origin downloads need the host to allow it; the server's built-in art endpoint does.
 */
class BrowserAssetDownloader : AssetDownloader {
    override fun download(url: String): Result<ByteArray> =
        Result.failure(IOException("Blocking downloads are not available in a browser"))

    override fun downloadAsync(url: String, onDone: (Result<ByteArray>) -> Unit) {
        fetchBytes(url, FetchCallback { ok, bytes, error ->
            if (ok && bytes != null) onDone(Result.success(bytes.copyToJavaArray()))
            else onDone(Result.failure(IOException("Download failed for $url: ${error ?: "unknown error"}")))
        })
    }
}
