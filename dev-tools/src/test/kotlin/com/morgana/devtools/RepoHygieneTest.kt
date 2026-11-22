package com.morgana.devtools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Guards against the pre-rename `com.fade` source tree reappearing (for example an editor re-saving stale buffers),
 * which otherwise surfaces as hundreds of confusing "Unresolved reference" compile errors.
 */
class RepoHygieneTest {

    private val repoRoot: Path = Paths.get("..").toAbsolutePath().normalize()

    private val skippedDirs = setOf("build", "bin", ".git", ".gradle", ".idea", "node_modules")

    /** Walked once for the whole class, pruning build output and dependency trees instead of filtering them afterwards. */
    private val cachedSources: List<Path> by lazy {
        val collected = ArrayList<Path>()
        Files.walkFileTree(repoRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != repoRoot && dir.fileName.toString() in skippedDirs) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (name.endsWith(".kt") || name.endsWith(".kts")) collected.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        collected
    }

    private fun sourceFiles(): List<Path> = cachedSources

    @Test
    fun `no source files live under the old com fade package directories`() {
        val stale = sourceFiles().map { repoRoot.relativize(it).toString().replace('\\', '/') }.filter { it.contains("/com/fade/") }
        assertTrue(
            stale.isEmpty(),
            "Stale pre-rename sources found (delete them; the current copies are under com/morgana):\n" + stale.joinToString("\n")
        )
    }

    @Test
    fun `no kotlin source still declares or imports the old com dot fade package`() {
        val offenders = sourceFiles().filter { f -> Files.readAllLines(f).any { it.startsWith("package com.fade") || it.startsWith("import com.fade") } }
            .map { repoRoot.relativize(it).toString() }
        assertTrue(offenders.isEmpty(), "Files still using the old package:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `no source file or type declaration uses legacy Mirage Fade or Jerrath names`() {
        val legacyPrefix = Regex("""^(data\s+|sealed\s+|enum\s+|value\s+|abstract\s+)?(class|interface|object)\s+(Mirage|Fade|Jerrath)\w*""")
        val badFiles = sourceFiles().filter { f ->
            val name = f.fileName.toString()
            name.startsWith("Mirage") || name.startsWith("Fade") || name.startsWith("Jerrath") ||
                Files.readAllLines(f).any { line -> legacyPrefix.containsMatchIn(line.trim()) }
        }.map { repoRoot.relativize(it).toString() }
        assertTrue(badFiles.isEmpty(), "Files or types still named Mirage/Fade/Jerrath:\n" + badFiles.joinToString("\n"))
    }
}
