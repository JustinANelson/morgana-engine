package com.morgana.protocol

/**
 * Pure-Kotlin standard Base64 encoder/decoder shared across JVM, Android, and TeaVM web targets without JVM-only APIs.
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xFF else 0
            val triplet = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triplet ushr 18) and 0x3F])
            out.append(ALPHABET[(triplet ushr 12) and 0x3F])
            out.append(if (has1) ALPHABET[(triplet ushr 6) and 0x3F] else '=')
            out.append(if (has2) ALPHABET[triplet and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    fun decodeOrNull(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        if (input.length % 4 != 0) return null
        val len = input.length
        val pad = when {
            input.endsWith("==") -> 2
            input.endsWith("=") -> 1
            else -> 0
        }
        val outSize = (len / 4) * 3 - pad
        val out = ByteArray(outSize)
        var outPos = 0
        var i = 0
        while (i < len) {
            val c0 = decodeChar(input[i])
            val c1 = decodeChar(input[i + 1])
            if (c0 < 0 || c1 < 0) return null
            val isLast = i + 4 == len
            val ch2 = input[i + 2]
            val ch3 = input[i + 3]
            if (!isLast && (ch2 == '=' || ch3 == '=')) return null
            if (ch2 == '=' && ch3 != '=') return null
            val c2 = if (ch2 == '=') 0 else decodeChar(ch2).also { if (it < 0) return null }
            val c3 = if (ch3 == '=') 0 else decodeChar(ch3).also { if (it < 0) return null }
            val triplet = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[outPos++] = ((triplet ushr 16) and 0xFF).toByte()
            if (ch2 != '=') out[outPos++] = ((triplet ushr 8) and 0xFF).toByte()
            if (ch3 != '=') out[outPos++] = (triplet and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun decodeChar(c: Char): Int {
        val code = c.code
        return if (code in 0..255) DECODE_TABLE[code] else -1
    }
}

