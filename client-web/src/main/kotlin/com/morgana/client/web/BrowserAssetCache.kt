package com.morgana.client.web

import com.morgana.client.assets.AssetCache
import com.morgana.client.assets.AssetCacheIndex
import com.morgana.client.assets.computeSha256
import org.teavm.jso.JSBody
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject
import org.teavm.jso.typedarrays.Int8Array

@JSFunctor
fun interface IndexCallback : JSObject {
    fun done(index: String)
}

@JSFunctor
fun interface BlobCallback : JSObject {
    fun got(sha: String, bytes: Int8Array)
}

@JSFunctor
fun interface DoneCallback : JSObject {
    fun done()
}

/**
 * Installs `window.__mAssets`: a small IndexedDB store with a `meta` object store (sha, size, last used) that is cheap to
 * list, and a `data` store with the bytes keyed by sha. Every failure (private mode, storage blocked or full) degrades to
 * "nothing stored": the game then simply downloads, as it did before there was a cache.
 */
@JSBody(script = """
    if (window.__mAssets) return;
    var dbp = null;
    function db() {
        if (!dbp) dbp = new Promise(function (res, rej) {
            try {
                var r = indexedDB.open('morgana-realm-assets', 1);
                r.onupgradeneeded = function () { var d = r.result; d.createObjectStore('meta', { keyPath: 'sha' }); d.createObjectStore('data'); };
                r.onsuccess = function () { res(r.result); };
                r.onerror = function () { rej(r.error); };
                r.onblocked = function () { rej('blocked'); };
            } catch (e) { rej(e); }
        });
        return dbp;
    }
    window.__mAssets = {
        index: function (cb) {
            db().then(function (d) {
                var out = [];
                var req = d.transaction('meta', 'readonly').objectStore('meta').openCursor();
                req.onsuccess = function () { var c = req.result; if (c) { var v = c.value; out.push(v.sha + ':' + v.size + ':' + v.used); c['continue'](); } else cb(out.join(';')); };
                req.onerror = function () { cb(''); };
            })['catch'](function () { cb(''); });
        },
        get: function (shas, cb, done) {
            var list = shas ? shas.split(',') : [];
            db().then(function (d) {
                var tx = d.transaction('data', 'readonly');
                var st = tx.objectStore('data');
                list.forEach(function (s) { var r = st.get(s); r.onsuccess = function () { if (r.result) cb(s, new Int8Array(r.result)); }; });
                tx.oncomplete = function () { done(); };
                tx.onerror = function () { done(); };
                tx.onabort = function () { done(); };
            })['catch'](function () { done(); });
        },
        put: function (sha, bytes, used) {
            db().then(function (d) {
                var tx = d.transaction(['meta', 'data'], 'readwrite');
                tx.objectStore('data').put(bytes.slice().buffer, sha);
                tx.objectStore('meta').put({ sha: sha, size: bytes.length, used: used });
            })['catch'](function () {});
        },
        touch: function (shas, used) {
            var list = shas ? shas.split(',') : [];
            db().then(function (d) {
                var st = d.transaction('meta', 'readwrite').objectStore('meta');
                list.forEach(function (s) { var r = st.get(s); r.onsuccess = function () { if (r.result) { r.result.used = used; st.put(r.result); } }; });
            })['catch'](function () {});
        },
        remove: function (shas) {
            var list = shas ? shas.split(',') : [];
            db().then(function (d) {
                var tx = d.transaction(['meta', 'data'], 'readwrite');
                list.forEach(function (s) { tx.objectStore('meta')['delete'](s); tx.objectStore('data')['delete'](s); });
            })['catch'](function () {});
        }
    };
""")
private external fun installAssetStore()

@JSBody(params = ["cb"], script = "window.__mAssets.index(cb);")
private external fun storeIndex(cb: IndexCallback)

@JSBody(params = ["shas", "cb", "done"], script = "window.__mAssets.get(shas, cb, done);")
private external fun storeGet(shas: String, cb: BlobCallback, done: DoneCallback)

@JSBody(params = ["sha", "bytes", "used"], script = "window.__mAssets.put(sha, bytes, used);")
private external fun storePut(sha: String, bytes: Int8Array, used: Double)

@JSBody(params = ["shas", "used"], script = "window.__mAssets.touch(shas, used);")
private external fun storeTouch(shas: String, used: Double)

@JSBody(params = ["shas"], script = "window.__mAssets.remove(shas);")
private external fun storeRemove(shas: String)

@JSBody(script = "return Date.now();")
private external fun nowMillis(): Double

/**
 * [AssetCache] that keeps a realm's art in the browser's IndexedDB, so a returning player whose files have not changed
 * downloads nothing (before, the browser client's cache lived in memory and every page load fetched every file again).
 *
 * IndexedDB only answers asynchronously, while [has] and [load] are synchronous: at start the cache reads the small list of
 * what it holds ([AssetCacheIndex]), and [prefetch] loads one realm's files into memory before the sync and the art use
 * them. Files are addressed by SHA-256 and checked on [store], and the pipeline checks downloads too. Past [capBytes] the
 * least recently used files are dropped (never those of the realm being played).
 */
class BrowserAssetCache(private val capBytes: Long = DEFAULT_CAP_BYTES) : AssetCache {
    private var index = AssetCacheIndex(capBytes)
    private val memory = HashMap<String, ByteArray>()
    private var indexLoaded = false
    private val waiting = mutableListOf<() -> Unit>()

    init {
        installAssetStore()
        storeIndex(IndexCallback { text ->
            val loaded = AssetCacheIndex.parse(text, capBytes)
            // Anything stored while the list was loading is newer than what the list says.
            for (sha in memory.keys) loaded.put(sha, memory.getValue(sha).size.toLong(), nowMillis().toLong())
            index = loaded
            drop(index.evictionCandidates())
            indexLoaded = true
            val run = waiting.toList()
            waiting.clear()
            run.forEach { it() }
        })
    }

    private fun norm(sha: String) = sha.trim().lowercase()

    override fun has(sha256: String): Boolean = norm(sha256).let { it in memory || it in index }

    override fun store(sha256: String, bytes: ByteArray, ext: String): String {
        val sha = norm(sha256)
        require(computeSha256(bytes).equals(sha, ignoreCase = true)) { "Asset content SHA-256 did not match expected checksum ($sha)" }
        memory[sha] = bytes
        val now = nowMillis()
        index.put(sha, bytes.size.toLong(), now.toLong())
        storePut(sha, Int8Array.fromJavaArray(bytes), now)
        drop(index.evictionCandidates(keep = memory.keys))
        return sha
    }

    override fun load(sha256: String, ext: String): ByteArray? = memory[norm(sha256)]

    override fun prefetch(sha256s: Collection<String>, onDone: () -> Unit) {
        if (!indexLoaded) {
            waiting += { prefetch(sha256s, onDone) }
            return
        }
        val wanted = sha256s.map { norm(it) }.distinct().filter { it !in memory && it in index }
        if (wanted.isEmpty()) return onDone()
        storeGet(wanted.joinToString(","), BlobCallback { sha, bytes ->
            val data = bytes.copyToJavaArray()
            // Never trust stored bytes blindly: a damaged entry is forgotten and downloaded again.
            if (computeSha256(data).equals(sha, ignoreCase = true)) memory[sha] = data
        }, DoneCallback {
            val missing = wanted.filter { it !in memory }
            if (missing.isNotEmpty()) {
                missing.forEach { index.remove(it) }
                storeRemove(missing.joinToString(","))
            }
            val found = wanted.filter { it in memory }
            if (found.isNotEmpty()) {
                val now = nowMillis()
                found.forEach { index.touch(it, now.toLong()) }
                storeTouch(found.joinToString(","), now)
            }
            onDone()
        })
    }

    override fun delete(sha256: String, ext: String): Boolean {
        val sha = norm(sha256)
        val had = (memory.remove(sha) != null) or index.remove(sha)
        if (had) storeRemove(sha)
        return had
    }

    override fun clear() {
        val all = (memory.keys + index.format().split(';').map { it.substringBefore(':') }).filter { it.isNotBlank() }.distinct()
        memory.clear()
        index.clear()
        if (all.isNotEmpty()) storeRemove(all.joinToString(","))
    }

    override fun count(): Int = index.size

    override fun totalSizeBytes(): Long = index.totalBytes

    private fun drop(shas: List<String>) {
        if (shas.isEmpty()) return
        shas.forEach { memory.remove(it); index.remove(it) }
        storeRemove(shas.joinToString(","))
    }

    companion object {
        /** Art kept between visits, across all realms; the least recently used files go first beyond it. */
        const val DEFAULT_CAP_BYTES = 128L * 1024 * 1024
    }
}
