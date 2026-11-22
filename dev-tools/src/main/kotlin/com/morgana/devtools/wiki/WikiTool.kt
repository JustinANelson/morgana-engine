package com.morgana.devtools.wiki

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Wiki maintenance CLI (run through Gradle: `wikiLint`, `wikiIndex`, `wikiImpact`).
 *
 * - `lint [--wiki wiki]`: validate the bundle; exit 1 on errors
 * - `index [--wiki wiki]`: regenerate `index.md`
 * - `impact [--git <ref>] [paths...]`: list wiki pages whose `code:` entries cover the changed paths
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val rest = args.drop(1)
    val wikiDir = Paths.get(optionValue(rest, "--wiki") ?: "wiki")
    val repoRoot = Paths.get("").toAbsolutePath()
    val code = when (command) {
        "lint" -> lint(wikiDir, repoRoot)
        "index" -> writeIndex(wikiDir)
        "impact" -> impact(wikiDir, rest)
        else -> {
            System.err.println("Usage: lint | index | impact [--git <ref>] [paths...]   (option --wiki <dir>)")
            2
        }
    }
    exitProcess(code)
}

private fun optionValue(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

internal fun lint(wikiDir: Path, repoRoot: Path): Int {
    val issues = WikiLint(wikiDir, repoRoot).run()
    issues.forEach { println(it) }
    val errors = issues.count { it.severity == WikiSeverity.ERROR }
    println("wiki: ${Okf.concepts(wikiDir).size} page(s), $errors error(s), ${issues.size - errors} warning(s)")
    return if (errors > 0) 1 else 0
}

internal fun writeIndex(wikiDir: Path): Int {
    val concepts = Okf.concepts(wikiDir).filter { it.parseError == null }
    Files.writeString(wikiDir.resolve("index.md"), WikiIndex.generate(concepts))
    println("Wrote ${wikiDir.resolve("index.md")} (${concepts.size} pages)")
    return 0
}

internal fun impact(wikiDir: Path, args: List<String>): Int {
    val paths = mutableListOf<String>()
    val gitRef = optionValue(args, "--git")
    if (gitRef != null) paths += gitChangedFiles(gitRef)
    var i = 0
    while (i < args.size) {
        if (args[i] == "--git" || args[i] == "--wiki") i += 2 else { paths += args[i]; i++ }
    }
    val affected = pagesAffectedBy(Okf.concepts(wikiDir), paths)
    if (affected.isEmpty()) println("No wiki pages reference the ${paths.size} changed path(s).")
    affected.forEach { (c, hits) -> println("${c.relPath}  <-  ${hits.joinToString()}") }
    return 0
}

/** Pages whose `code:` entries equal or contain any of [changedPaths] (repo-relative, `/` separators). */
fun pagesAffectedBy(concepts: List<Concept>, changedPaths: List<String>): List<Pair<Concept, List<String>>> {
    val normalized = changedPaths.map { it.replace('\\', '/').removePrefix("./") }
    return concepts.mapNotNull { c ->
        val codeEntries = c.stringList("code").map { it.replace('\\', '/').removeSuffix("/") }
        val hits = normalized.filter { changed -> codeEntries.any { changed == it || changed.startsWith("$it/") } }
        if (hits.isEmpty()) null else c to hits
    }
}

private fun gitChangedFiles(ref: String): List<String> {
    val process = ProcessBuilder("git", "diff", "--name-only", ref).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
