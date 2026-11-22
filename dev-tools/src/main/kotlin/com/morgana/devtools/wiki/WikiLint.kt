package com.morgana.devtools.wiki

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class WikiSeverity { ERROR, WARNING }

data class WikiIssue(val severity: WikiSeverity, val file: String, val message: String) {
    override fun toString(): String = "${severity.name} wiki/$file: $message"
}

/**
 * Lints an OKF bundle against the OKF v0.2 conformance rules plus Morgana's maintenance rules:
 *
 * - every concept has parseable frontmatter and a non-empty `type` (OKF conformance)
 * - `title`, `description`, `tags`, and `generated {by, at}` provenance are present and well-formed
 * - timestamps are ISO 8601 with an explicit offset; `status` is draft/stable/deprecated
 * - relative and bundle-absolute markdown links resolve
 * - `code:` paths exist in the repository and each `symbols:` entry still appears in them (drift detection)
 * - `index.md` is exactly what the generator produces; `log.md` headings are ISO dates, newest first
 *
 * Errors fail the build; warnings are advisory (stale pages, orphans, unknown types, very long pages).
 */
class WikiLint(
    private val wikiDir: Path,
    private val repoRoot: Path,
    private val today: LocalDate = LocalDate.now()
) {
    private val issues = mutableListOf<WikiIssue>()

    private fun error(file: String, msg: String) { issues += WikiIssue(WikiSeverity.ERROR, file, msg) }
    private fun warn(file: String, msg: String) { issues += WikiIssue(WikiSeverity.WARNING, file, msg) }

    fun run(): List<WikiIssue> {
        issues.clear()
        if (!Files.isDirectory(wikiDir)) {
            error("", "wiki directory '$wikiDir' not found")
            return issues.toList()
        }
        val concepts = Okf.concepts(wikiDir)
        concepts.forEach { lintConcept(it) }
        lintDuplicateTitles(concepts)
        lintOrphans(concepts)
        lintIndex(concepts)
        lintLog(concepts)
        return issues.toList()
    }

    // ---- Concepts -------------------------------------------------------------------------------

    private fun lintConcept(c: Concept) {
        val f = c.relPath
        if (c.parseError != null) { error(f, c.parseError); return }
        if (c.type.isBlank()) error(f, "frontmatter needs a non-empty `type` (the only field OKF requires)")
        if (c.type.isNotBlank() && c.type !in KNOWN_TYPES) warn(f, "unrecognized type '${c.type}' (known: ${KNOWN_TYPES.joinToString()})")
        if ((c.meta["title"] as? String).isNullOrBlank()) error(f, "missing `title`")
        val desc = c.description
        if (desc.isBlank()) error(f, "missing `description` (one sentence; used in the index)")
        else if (desc.contains('\n') || desc.length > 220) warn(f, "`description` should be a single sentence under 220 characters")
        if (c.stringList("tags").isEmpty()) warn(f, "missing `tags`")
        if (!Regex("^[a-z0-9][a-z0-9_-]*\\.md$").matches(f.substringAfterLast('/'))) warn(f, "file names should be lowercase snake_case or kebab-case")
        val status = c.meta["status"]?.toString()
        if (status != null && status !in setOf("draft", "stable", "deprecated")) error(f, "`status` must be draft, stable, or deprecated (got '$status')")

        lintProvenance(c)
        lintStaleness(c, status)
        lintCode(c)
        lintLinks(c)
        if (c.body.lines().size > 400) warn(f, "page exceeds 400 lines; consider splitting it")
    }

    private fun lintProvenance(c: Concept) {
        val f = c.relPath
        val gen = c.meta["generated"] as? Map<*, *>
        if (gen == null) { error(f, "missing `generated: {by, at}` provenance"); return }
        if ((gen["by"] as? String).isNullOrBlank()) error(f, "`generated.by` is required")
        checkTimestamp(f, "generated.at", gen["at"])
        val verified = c.meta["verified"]
        val entries: List<*> = when (verified) {
            null -> emptyList<Any?>()
            is List<*> -> verified
            is Map<*, *> -> listOf(verified)
            else -> { error(f, "`verified` must be a list of {by, at}"); emptyList<Any?>() }
        }
        entries.forEachIndexed { i, e ->
            val m = e as? Map<*, *>
            if (m == null || (m["by"] as? String).isNullOrBlank()) error(f, "`verified[$i].by` is required")
            else checkTimestamp(f, "verified[$i].at", m["at"])
        }
    }

    private fun checkTimestamp(file: String, field: String, value: Any?) {
        val s = value?.toString()
        if (s.isNullOrBlank()) { error(file, "`$field` is required"); return }
        try {
            OffsetDateTime.parse(s)
        } catch (_: DateTimeParseException) {
            try { Instant.parse(s) } catch (_: DateTimeParseException) {
                error(file, "`$field` must be ISO 8601 with an explicit offset, e.g. 2026-11-07T12:00:00Z (got '$s')")
            }
        }
    }

    private fun lintStaleness(c: Concept, status: String?) {
        val raw = c.meta["stale_after"]?.toString() ?: return
        val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.recoverCatching { Instant.parse(raw) }.getOrNull()
        if (instant == null) { error(c.relPath, "`stale_after` must be an ISO 8601 instant (got '$raw')"); return }
        val limit = today.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        if (status != "deprecated" && !instant.isAfter(limit)) warn(c.relPath, "page is past `stale_after` ($raw); re-verify it against the code and bump the date")
    }

    private fun lintCode(c: Concept) {
        val f = c.relPath
        val codePaths = c.stringList("code")
        val symbols = c.stringList("symbols")
        val resolved = mutableListOf<Path>()
        for (p in codePaths) {
            val path = repoRoot.resolve(p).normalize()
            if (!path.startsWith(repoRoot.normalize())) { error(f, "`code` path '$p' escapes the repository"); continue }
            if (!Files.exists(path)) error(f, "`code` path '$p' does not exist (renamed or deleted? update the page)") else resolved.add(path)
        }
        if (symbols.isNotEmpty() && codePaths.isEmpty()) error(f, "`symbols` requires `code` paths to search")
        if (resolved.isEmpty()) return
        val corpus = resolved.flatMap { textFiles(it) }.joinToString("\n") { runCatching { Files.readString(it) }.getOrDefault("") }
        for (sym in symbols) {
            if (!corpus.contains(sym)) error(f, "symbol '$sym' not found under ${codePaths.joinToString()} (renamed or removed? update the page)")
        }
    }

    private fun textFiles(path: Path): List<Path> {
        if (Files.isRegularFile(path)) return listOf(path)
        Files.walk(path).use { s ->
            return s.filter { p ->
                Files.isRegularFile(p) &&
                    p.toString().replace('\\', '/').let { !it.contains("/build/") && !it.contains("/.gradle/") } &&
                    p.fileName.toString().substringAfterLast('.', "") in TEXT_EXTENSIONS &&
                    runCatching { Files.size(p) < 2_000_000 }.getOrDefault(false)
            }.toList()
        }
    }

    // ---- Links ----------------------------------------------------------------------------------

    private fun lintLinks(c: Concept) = lintLinksIn(c.relPath, c.path, c.body)

    private fun lintLinksIn(rel: String, file: Path, body: String) {
        for (target in extractLinkTargets(body)) {
            val clean = target.substringBefore('#').substringBefore('?')
            if (clean.isEmpty() || SCHEME.containsMatchIn(clean)) continue
            val resolved = if (clean.startsWith("/")) wikiDir.resolve(clean.removePrefix("/")) else file.parent.resolve(clean)
            if (!Files.exists(resolved.normalize())) error(rel, "broken link '$target'")
        }
    }

    private fun lintOrphans(concepts: List<Concept>) {
        val inbound = mutableMapOf<String, Int>()
        for (c in concepts) {
            for (t in extractLinkTargets(c.body)) {
                val clean = t.substringBefore('#')
                if (clean.isEmpty() || SCHEME.containsMatchIn(clean)) continue
                val resolved = if (clean.startsWith("/")) wikiDir.resolve(clean.removePrefix("/")) else c.path.parent.resolve(clean)
                val rel = runCatching { wikiDir.toAbsolutePath().normalize().relativize(resolved.toAbsolutePath().normalize()).toString().replace('\\', '/') }.getOrNull() ?: continue
                if (rel != c.relPath) inbound.merge(rel, 1, Int::plus)
            }
        }
        for (c in concepts) {
            if (c.parseError == null && c.type != "Overview" && inbound[c.relPath] == null) {
                warn(c.relPath, "orphan: no other page links here (only the index does); link it from a related page")
            }
        }
    }

    private fun lintDuplicateTitles(concepts: List<Concept>) {
        concepts.filter { it.parseError == null }.groupBy { it.title.lowercase() }.filter { it.value.size > 1 }.forEach { (t, list) ->
            warn(list.first().relPath, "duplicate title '$t' also used by ${list.drop(1).joinToString { it.relPath }}")
        }
    }

    // ---- Index & log ----------------------------------------------------------------------------

    private fun lintIndex(concepts: List<Concept>) {
        val indexFile = wikiDir.resolve("index.md")
        if (!Files.exists(indexFile)) { error("index.md", "missing; generate with the wikiIndex task"); return }
        val expected = WikiIndex.generate(concepts.filter { it.parseError == null })
        val actual = Files.readString(indexFile).replace("\r\n", "\n")
        if (actual.trimEnd() != expected.trimEnd()) error("index.md", "out of date; regenerate with: gradlew :dev-tools:wikiIndex")
        lintLinksIn("index.md", indexFile, actual)
    }

    private fun lintLog(concepts: List<Concept>) {
        val logFile = wikiDir.resolve("log.md")
        if (!Files.exists(logFile)) { error("log.md", "missing; every change to the wiki is recorded here (newest first)"); return }
        val dates = Regex("^## (.+)$", RegexOption.MULTILINE).findAll(Files.readString(logFile)).map { it.groupValues[1].trim() }.toList()
        val parsed = dates.map { d -> runCatching { LocalDate.parse(d) }.getOrNull().also { if (it == null) error("log.md", "heading '## $d' is not an ISO date (YYYY-MM-DD)") } }
        val valid = parsed.filterNotNull()
        if (valid.isEmpty() && dates.isEmpty()) warn("log.md", "no dated entries")
        if (valid.zipWithNext().any { (a, b) -> a.isBefore(b) }) error("log.md", "entries must be newest first")
        val latest = valid.maxOrNull() ?: return
        val newestPage = concepts.flatMap { c ->
            val gen = (c.meta["generated"] as? Map<*, *>)?.get("at")?.toString()
            val ver = (c.meta["verified"].let { v -> if (v is Map<*, *>) listOf(v) else (v as? List<*>).orEmpty() }).mapNotNull { (it as? Map<*, *>)?.get("at")?.toString() }
            listOfNotNull(gen) + ver
        }.mapNotNull { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }.maxOrNull()
        if (newestPage != null && newestPage.isAfter(latest)) warn("log.md", "newest page timestamp ($newestPage) is after the latest log entry ($latest); add a log entry")
    }

    companion object {
        val KNOWN_TYPES = setOf(
            "Overview", "Glossary", "Architecture", "Module", "Concept", "Simulation System",
            "Content Type", "Protocol", "Decision", "Playbook", "Reference", "Known Issue"
        )
        private val TEXT_EXTENSIONS = setOf("kt", "kts", "java", "json", "md", "xml", "properties", "yml", "yaml", "txt", "toml")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val LINK = Regex("""\[[^\]]*]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
        private val FENCE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_CODE = Regex("`[^`\n]*`")

        /** Link targets in [markdown], ignoring fenced and inline code. */
        fun extractLinkTargets(markdown: String): List<String> =
            LINK.findAll(markdown.replace(FENCE, "").replace(INLINE_CODE, "")).map { it.groupValues[1] }.toList()
    }
}
