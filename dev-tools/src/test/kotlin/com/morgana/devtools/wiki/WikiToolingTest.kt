package com.morgana.devtools.wiki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class WikiToolingTest {

    private val today = LocalDate.of(2026, 11, 7)

    private fun page(
        type: String = "Concept",
        title: String = "Alpha",
        extra: String = "",
        body: String = "Body text.",
        generatedAt: String = "2026-11-07T00:00:00Z"
    ) = """
        |---
        |type: $type
        |title: $title
        |description: A page about $title.
        |tags: [test]
        |generated: { by: test/agent, at: $generatedAt }
        |$extra
        |---
        |$body
        |""".trimMargin()

    /** Builds a repo with a source file and a wiki containing the given pages; index and log are generated. */
    private fun repo(dir: Path, pages: Map<String, String>, log: String? = "# Directory Update Log\n\n## 2026-11-07\n\n* **Creation** - test\n"): Pair<Path, Path> {
        val wiki = dir.resolve("wiki")
        Files.createDirectories(dir.resolve("src"))
        Files.createDirectories(dir.resolve("docs"))
        Files.writeString(dir.resolve("docs/WIKI_GUIDE.md"), "# guide")
        Files.writeString(dir.resolve("src/Thing.kt"), "class Thing { fun doWork() {} }")
        pages.forEach { (name, text) ->
            val f = wiki.resolve(name)
            Files.createDirectories(f.parent)
            Files.writeString(f, text)
        }
        Files.writeString(wiki.resolve("index.md"), WikiIndex.generate(Okf.concepts(wiki).filter { it.parseError == null }))
        if (log != null) Files.writeString(wiki.resolve("log.md"), log)
        return dir to wiki
    }

    private fun lint(root: Path, wiki: Path) = WikiLint(wiki, root, today).run()
    private fun errors(i: List<WikiIssue>) = i.filter { it.severity == WikiSeverity.ERROR }.map { it.message }

    @Test
    fun `a well-formed wiki produces no errors`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "overview.md" to page(type = "Overview", title = "Overview", body = "See [alpha](/systems/alpha.md)."),
            "systems/alpha.md" to page(title = "Alpha", extra = "code: [src/Thing.kt]\nsymbols: [doWork]", body = "Back to [overview](../overview.md).")
        ))
        assertEquals(emptyList<String>(), errors(lint(root, wiki)))
    }

    @Test
    fun `missing frontmatter, type, and provenance are errors`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "a.md" to "no frontmatter at all",
            "b.md" to "---\ntitle: B\ndescription: d\ntags: [x]\n---\nbody",
            "c.md" to "---\ntype: Concept\ntitle: C\ndescription: d\ntags: [x]\n---\nbody"
        ))
        val msgs = errors(lint(root, wiki))
        assertTrue(msgs.any { it.contains("missing YAML frontmatter") }, msgs.toString())
        assertTrue(msgs.any { it.contains("non-empty `type`") }, msgs.toString())
        assertTrue(msgs.any { it.contains("missing `generated") }, msgs.toString())
    }

    @Test
    fun `timestamps need an explicit offset and status must be valid`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "a.md" to page(generatedAt = "2026-11-07", extra = "status: shiny")
        ))
        val msgs = errors(lint(root, wiki))
        assertTrue(msgs.any { it.contains("generated.at") && it.contains("explicit offset") }, msgs.toString())
        assertTrue(msgs.any { it.contains("`status` must be") }, msgs.toString())
    }

    @Test
    fun `broken links are errors but links inside code are ignored`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "a.md" to page(body = "Bad [link](/nope.md) and ok `[x](/ignored.md)` and https://example.com [web](https://example.com/x)\n```\n[y](/also_ignored.md)\n```")
        ))
        val msgs = errors(lint(root, wiki))
        assertEquals(1, msgs.count { it.contains("broken link") }, msgs.toString())
        assertTrue(msgs.single { it.contains("broken link") }.contains("/nope.md"))
    }

    @Test
    fun `code drift is detected - missing paths and vanished symbols`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "a.md" to page(title = "A", extra = "code: [src/Gone.kt]"),
            "b.md" to page(title = "B", extra = "code: [src/Thing.kt]\nsymbols: [doWork, renamedAway]"),
            "c.md" to page(title = "C", extra = "symbols: [x]")
        ))
        val msgs = errors(lint(root, wiki))
        assertTrue(msgs.any { it.contains("src/Gone.kt") && it.contains("does not exist") }, msgs.toString())
        assertTrue(msgs.any { it.contains("renamedAway") }, msgs.toString())
        assertFalse(msgs.any { it.contains("'doWork'") })
        assertTrue(msgs.any { it.contains("`symbols` requires `code`") }, msgs.toString())
    }

    @Test
    fun `index must match the generator and log must be ISO dated newest first`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf("a.md" to page()), log = "# Log\n\n## 2026-01-01\n\n* old\n\n## 2026-02-01\n\n* newer\n")
        Files.writeString(wiki.resolve("index.md"), "---\nokf_version: \"0.2\"\n---\n# stale\n")
        val msgs = errors(lint(root, wiki))
        assertTrue(msgs.any { it.contains("out of date") }, msgs.toString())
        assertTrue(msgs.any { it.contains("newest first") }, msgs.toString())

        Files.writeString(wiki.resolve("log.md"), "# Log\n\n## yesterday\n")
        assertTrue(errors(lint(root, wiki)).any { it.contains("not an ISO date") })
    }

    @Test
    fun `stale pages, orphans, and unknown types are warnings not errors`(@TempDir dir: Path) {
        val (root, wiki) = repo(dir, mapOf(
            "a.md" to page(title = "A", type = "Mystery", extra = "stale_after: 2026-06-01T00:00:00Z")
        ))
        val issues = lint(root, wiki)
        assertEquals(emptyList<String>(), errors(issues))
        val warnings = issues.filter { it.severity == WikiSeverity.WARNING }.map { it.message }
        assertTrue(warnings.any { it.contains("stale_after") }, warnings.toString())
        assertTrue(warnings.any { it.contains("orphan") }, warnings.toString())
        assertTrue(warnings.any { it.contains("unrecognized type") }, warnings.toString())
    }

    @Test
    fun `index generation is deterministic, grouped, and carries okf_version`(@TempDir dir: Path) {
        val (_, wiki) = repo(dir, mapOf(
            "overview.md" to page(type = "Overview", title = "Overview"),
            "systems/b.md" to page(title = "Beta"),
            "systems/a.md" to page(title = "Alpha"),
            "zeta/z.md" to page(title = "Zed")
        ))
        val concepts = Okf.concepts(wiki)
        val first = WikiIndex.generate(concepts)
        assertEquals(first, WikiIndex.generate(concepts.reversed()))
        assertTrue(first.startsWith("---\nokf_version: \"0.2\"\n---\n"))
        assertTrue(first.indexOf("## Start Here") < first.indexOf("## Simulation Systems"))
        assertTrue(first.indexOf("[Alpha]") < first.indexOf("[Beta]"))
        assertTrue(first.indexOf("## Simulation Systems") < first.indexOf("## Zeta"))
        assertTrue(first.contains("* [Alpha](/systems/a.md) - A page about Alpha."))
    }

    @Test
    fun `impact maps changed source paths to pages that cover them`(@TempDir dir: Path) {
        val (_, wiki) = repo(dir, mapOf(
            "a.md" to page(title = "A", extra = "code: [src]"),
            "b.md" to page(title = "B", extra = "code: [other/File.kt]")
        ))
        val hits = pagesAffectedBy(Okf.concepts(wiki), listOf("src\\Thing.kt", "unrelated.txt"))
        assertEquals(listOf("a.md"), hits.map { it.first.relPath })
        assertEquals(listOf("src/Thing.kt"), hits.single().second)
    }
}
