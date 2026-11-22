package com.morgana.server.admin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The secret the admin API asks for. It comes from the `MORGANA_ADMIN_TOKEN` environment variable when that is set (for
 * hosts that inject secrets), otherwise from `<data dir>/admin-token.txt`, which is created with 256 random bits the first
 * time the panel is enabled. The token is never logged.
 */
object AdminToken {
    const val ENV = "MORGANA_ADMIN_TOKEN"
    const val FILE_NAME = "admin-token.txt"
    const val MIN_LENGTH = 16

    fun fileIn(dataDir: Path): Path = dataDir.resolve(FILE_NAME)

    fun loadOrCreate(dataDir: Path, env: String? = System.getenv(ENV)): String {
        env?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            require(value.length >= MIN_LENGTH) { "$ENV must be at least $MIN_LENGTH characters" }
            return value
        }
        val file = fileIn(dataDir)
        if (Files.exists(file)) {
            val existing = Files.readString(file).trim()
            require(existing.length >= MIN_LENGTH) { "$file holds a token shorter than $MIN_LENGTH characters; delete it to get a new one" }
            return existing
        }
        Files.createDirectories(dataDir)
        val created = generate()
        Files.writeString(file, created + "\n")
        // Owner-only where the file system supports it (not Windows, where the user's profile folder already protects it).
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
        return created
    }

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
