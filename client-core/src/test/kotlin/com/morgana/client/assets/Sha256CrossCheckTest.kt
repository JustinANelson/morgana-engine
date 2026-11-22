package com.morgana.client.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random

/**
 * The client hashes downloads with its own pure-Kotlin SHA-256 (the JDK's is not available in a browser). It must agree
 * with the reference implementation for every length and every byte value, or valid downloads are rejected.
 */
class Sha256CrossCheckTest {

    private fun reference(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `agrees with the JDK for every length around block boundaries with random bytes`() {
        val rnd = Random(42)
        for (length in 0..700) {
            val bytes = ByteArray(length).also { rnd.nextBytes(it) }
            assertEquals(reference(bytes), computeSha256(bytes), "length $length")
        }
    }

    @Test
    fun `agrees with the JDK for larger inputs`() {
        val rnd = Random(7)
        for (length in listOf(1_000, 4_096, 5_847, 65_535, 65_536, 65_537, 1_000_000)) {
            val bytes = ByteArray(length).also { rnd.nextBytes(it) }
            assertEquals(reference(bytes), computeSha256(bytes), "length $length")
        }
    }
}
