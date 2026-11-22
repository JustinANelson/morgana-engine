package com.morgana.protocol.realms

/**
 * Several realm art files in one HTTP response, so a client fetching many small files does not pay one request (and,
 * behind a TLS proxy, one TLS handshake) per file. Served by the server's `GET <assetBaseUrl>/_batch?p=<path>&p=<path>...`
 * when it advertises [com.morgana.protocol.ProtocolCapabilities.ASSET_BATCH].
 *
 * Format (all integers big-endian): the four bytes `MAB1`, the number of entries, then for each requested path in request
 * order its length followed by its bytes. A length of [NOT_INCLUDED] means the server left the file out (unknown path, or
 * the response reached [MAX_BYTES]); the client then fetches that file on its own. Every file is still checked against
 * its manifest SHA-256 by the client, so the bundle needs no checksums of its own.
 *
 * Pure Kotlin with no JVM-only APIs, so the browser client (TeaVM) decodes it too.
 */
object AssetBundle {
    const val BATCH_PATH = "_batch"
    const val NOT_INCLUDED = -1

    /** Most paths one request may name; the server ignores any beyond it. */
    const val MAX_FILES = 128

    /** The server stops adding files once the response holds this many bytes. */
    const val MAX_BYTES = 4 * 1024 * 1024

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    /** Encodes [files] (null = not included) in request order. */
    fun encode(files: List<ByteArray?>): ByteArray {
        val size = 8 + files.sumOf { 4 + (it?.size ?: 0) }
        val out = ByteArray(size)
        var at = 0
        fun putInt(v: Int) {
            out[at] = (v ushr 24).toByte(); out[at + 1] = (v ushr 16).toByte(); out[at + 2] = (v ushr 8).toByte(); out[at + 3] = v.toByte()
            at += 4
        }
        MAGIC.copyInto(out, 0); at = 4
        putInt(files.size)
        for (f in files) {
            if (f == null) { putInt(NOT_INCLUDED); continue }
            putInt(f.size)
            f.copyInto(out, at); at += f.size
        }
        return out
    }

    /**
     * Decodes a bundle of [expected] entries: one element per requested path, null where the server left the file out.
     * Throws [IllegalArgumentException] for anything malformed (wrong magic, wrong count, lengths past the end).
     */
    fun decode(bytes: ByteArray, expected: Int): List<ByteArray?> {
        require(bytes.size >= 8 && (0 until 4).all { bytes[it] == MAGIC[it] }) { "not an asset bundle" }
        var at = 4
        fun getInt(): Int {
            require(at + 4 <= bytes.size) { "asset bundle is truncated" }
            val v = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            at += 4
            return v
        }
        val count = getInt()
        require(count == expected) { "asset bundle holds $count files, expected $expected" }
        return List(count) {
            val len = getInt()
            if (len == NOT_INCLUDED) null
            else {
                require(len >= 0 && at + len <= bytes.size) { "asset bundle entry $it is truncated" }
                bytes.copyOfRange(at, at + len).also { at += len }
            }
        }
    }

    /** Percent-encodes [text] (UTF-8) for a query value, leaving only unreserved characters and `/` as they are. */
    fun encodeQueryValue(text: String): String {
        val sb = StringBuilder()
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '/') sb.append(ch)
            else sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
