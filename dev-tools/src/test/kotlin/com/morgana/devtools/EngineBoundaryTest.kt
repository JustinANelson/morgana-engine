package com.morgana.devtools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Keeps the engine separate from game content (ADR 0013, `docs/ARCHITECTURE.md`). Engine modules (everything but `games/`) must
 * not name a shipped game, must not reach into a game folder, and `:core-domain` must stay pure.
 *
 * Known leaks that have not been fixed yet are listed in the `KNOWN_*` sets with the step that removes them. The sets may only
 * shrink: a stale entry (the leak is gone) fails the test, so fixing a leak forces its entry to be deleted.
 */
class EngineBoundaryTest {

    private val repoRoot: Path = Paths.get("..").toAbsolutePath().normalize()

    private val engineModules = listOf(
        "core-domain", "shared-protocol", "server-persistence", "server-app", "tilegen", "client-core",
        "client-net-netty", "script-luak", "client-web", "client-desktop", "client-android", "plugin-example"
    )

    /** Names of games and of game-specific content: an engine source file mentioning one is coupled to that game. */
    private val gameNames = listOf(
        "Lanternfall", "Hearthvale", "MorganaClassic", "morgana-classic", "Conquer", "Emberdeep", "White River", "WhiteRiver", "white-river",
        "Trevana", "tutorial-village", "Tutorial Village"
    )

    /** Files that may name a game today. Empty means the engine is clean. */
    private val knownGameNameLeaks: Set<String> = emptySet()

    /** Files that may build a `games/<folder>` path. Tooling and the realm templates need the default `games/blank`. */
    private val knownGamePathUsers: Set<String> = setOf(
        "client-desktop/src/main/kotlin/com/morgana/client/desktop/GenerateTilesetTool.kt",
        "client-desktop/src/main/kotlin/com/morgana/client/desktop/GameArtExporter.kt",
        "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt",
        "server-app/src/main/kotlin/com/morgana/server/worlds/RealmBlueprint.kt",
        "server-app/src/main/kotlin/com/morgana/server/worlds/WorldLibrary.kt",
        "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt"
    )

    private fun mainSources(modules: List<String> = engineModules): List<Pair<String, List<String>>> =
        modules.flatMap { module ->
            val dir = repoRoot.resolve(module).resolve("src/main")
            if (!Files.isDirectory(dir)) emptyList() else Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
            }.map { repoRoot.relativize(it).toString().replace('\\', '/') to Files.readAllLines(it) }
        }

    private fun filesMatching(sources: List<Pair<String, List<String>>>, test: (String) -> Boolean): Set<String> =
        sources.filter { (_, lines) -> lines.any(test) }.map { it.first }.toSet()

    private fun assertOnly(found: Set<String>, known: Set<String>, what: String) {
        val unexpected = found - known
        val stale = known - found
        assertTrue(unexpected.isEmpty(), "$what in files that must not have it:\n" + unexpected.sorted().joinToString("\n"))
        assertTrue(stale.isEmpty(), "These files no longer have it, delete them from the known-leak list:\n" + stale.sorted().joinToString("\n"))
    }

    @Test
    fun `engine sources do not name a shipped game`() {
        val found = filesMatching(mainSources()) { line -> gameNames.any { line.contains(it) } }
        assertOnly(found, knownGameNameLeaks, "A shipped game is named")
    }

    @Test
    fun `engine sources only build game folder paths in the known tooling files`() {
        val found = filesMatching(mainSources()) { it.contains("games/") }
        assertOnly(found, knownGamePathUsers, "A games/ path is built")
    }

    @Test
    fun `core-domain is pure and does not know any other module`() {
        val allowed = listOf("com.morgana.domain.", "kotlin.", "kotlinx.serialization.", "java.math.")
        val offenders = mainSources(listOf("core-domain")).flatMap { (file, lines) ->
            lines.filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                .filter { imp -> allowed.none { imp.startsWith(it) } }
                .map { "$file: $it" }
        }
        assertTrue(offenders.isEmpty(), ":core-domain may only import itself, the Kotlin stdlib, kotlinx.serialization and java.math:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `core-domain does not touch the file system or the clock`() {
        val banned = listOf("java.io", "java.nio", "System.currentTimeMillis", "Instant.now", "Math.random", "kotlin.random.Random.Default")
        val offenders = mainSources(listOf("core-domain")).flatMap { (file, lines) ->
            lines.filter { l -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && banned.any { l.contains(it) } }.map { "$file: ${it.trim()}" }
        }
        assertTrue(offenders.isEmpty(), "Wall clock, unseeded randomness or I/O in :core-domain:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `no module below the clients depends on a client module`() {
        val client = Regex("""^import com\.morgana\.(client|server)\.""")
        val offenders = mainSources(listOf("core-domain", "shared-protocol", "server-persistence", "tilegen", "script-luak")).flatMap { (file, lines) ->
            lines.filter { client.containsMatchIn(it) }.map { "$file: $it" }
        }
        assertTrue(offenders.isEmpty(), "Lower layers import client or server code:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `preview and demo data is only used by the browser launcher`() {
        val allowedUsers = setOf("client-web/src/main/kotlin/com/morgana/client/web/WebLauncher.kt")
        val found = filesMatching(mainSources()) { it.startsWith("import com.morgana.client.preview.") }
            .filterNot { it.startsWith("client-core/src/main/kotlin/com/morgana/client/preview/") }.toSet()
        assertOnly(found, allowedUsers, "Preview (made-up demo) data is imported")
    }

    @Test
    fun `no engine module depends on the game art tooling`() {
        val offenders = engineModules.filter { module ->
            val build = repoRoot.resolve(module).resolve("build.gradle.kts")
            Files.isRegularFile(build) && Files.readString(build).contains(":game-art")
        }
        assertTrue(offenders.isEmpty(), "Engine modules depend on :game-art (game tooling): $offenders")
    }
}
