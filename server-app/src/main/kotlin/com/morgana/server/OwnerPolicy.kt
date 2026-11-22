package com.morgana.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides who receives the Owner access level (rank 4) at registration or login.
 *
 * Rules (checked in this order):
 * 1. **Configured owners**: usernames listed in the environment variable `MORGANA_OWNERS` (comma separated) or in
 *    `<data dir>/owners.txt` (one per line, `#` comments allowed) are Owners. The file is re-read on every check, so
 *    an operator can add a name without restarting. Add one with `.\gradlew.bat :server-app:grantOwner -PcontentArgs="--user Alice"`.
 * 2. **Local bootstrap**: when *no* owners are configured, the very first account on a fresh server becomes Owner **only if it
 *    registers over a loopback connection** (the machine running the server, including the desktop client's embedded server).
 *
 * There is deliberately no username-based rule: a name like `admin` never grants elevated access by itself.
 */
class OwnerPolicy(
    private val dataDir: Path,
    private val environment: () -> String? = { System.getenv("MORGANA_OWNERS") }
) {
    private val ownersFile: Path get() = dataDir.resolve(OWNERS_FILE)

    fun configuredOwners(): Set<String> {
        val fromEnv = environment().orEmpty().split(',')
        val fromFile = if (Files.isRegularFile(ownersFile)) Files.readAllLines(ownersFile) else emptyList()
        return (fromEnv + fromFile)
            .map { it.substringBefore('#').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun isConfiguredOwner(username: String): Boolean = username.trim().lowercase() in configuredOwners()

    /** True when [username] should be Owner for a registration (`firstAccount` = no accounts exist yet). */
    fun grantsOwnerOnRegistration(username: String, firstAccount: Boolean, loopbackConnection: Boolean): Boolean {
        val owners = configuredOwners()
        if (owners.isNotEmpty()) return username.trim().lowercase() in owners
        return firstAccount && loopbackConnection
    }

    companion object {
        const val OWNERS_FILE = "owners.txt"

        /** Appends [username] to `<dataDir>/owners.txt` unless already present. Returns true if it was added. */
        fun grant(dataDir: Path, username: String): Boolean {
            val name = username.trim()
            require(name.length in 2..20) { "Username must be 2-20 characters" }
            Files.createDirectories(dataDir)
            val file = dataDir.resolve(OWNERS_FILE)
            val existing = if (Files.isRegularFile(file)) Files.readAllLines(file) else emptyList()
            if (existing.any { it.substringBefore('#').trim().equals(name, ignoreCase = true) }) return false
            Files.writeString(file, (existing + name).joinToString("\n") + "\n")
            return true
        }
    }
}
