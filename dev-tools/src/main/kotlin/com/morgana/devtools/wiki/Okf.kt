package com.morgana.devtools.wiki

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import java.nio.file.Files
import java.nio.file.Path

/**
 * One Open Knowledge Format (OKF) concept: a markdown file with YAML frontmatter.
 * [id] is the bundle-absolute path without `.md` (e.g. `/systems/combat`).
 */
data class Concept(
    val path: Path,
    val relPath: String,
    val meta: Map<String, Any?>,
    val body: String,
    val parseError: String?
) {
    val id: String get() = "/" + relPath.removeSuffix(".md")
    val title: String get() = (meta["title"] as? String)?.takeIf { it.isNotBlank() } ?: relPath.substringAfterLast('/').removeSuffix(".md")
    val description: String get() = (meta["description"] as? String).orEmpty().trim()
    val type: String get() = (meta["type"] as? String).orEmpty().trim()

    @Suppress("UNCHECKED_CAST")
    fun stringList(key: String): List<String> = (meta[key] as? List<Any?>)?.mapNotNull { it?.toString() }.orEmpty()
}

/** Reserved OKF file names (never concepts). */
val RESERVED_FILES = setOf("index.md", "log.md")

object Okf {
    /** YAML loader that keeps timestamps as strings so the linter can enforce explicit UTC offsets. */
    private class StringTimestampConstructor : SafeConstructor(LoaderOptions()) {
        init {
            yamlConstructors[Tag.TIMESTAMP] = ConstructYamlStr()
        }
    }

    fun parseFile(root: Path, file: Path): Concept {
        val rel = root.relativize(file).toString().replace('\\', '/')
        val text = Files.readString(file).replace("\r\n", "\n")
        return parse(file, rel, text)
    }

    fun parse(file: Path, rel: String, text: String): Concept {
        val trimmed = text.removePrefix("﻿")
        if (!trimmed.startsWith("---\n")) return Concept(file, rel, emptyMap(), trimmed, "missing YAML frontmatter (file must start with ---)")
        val end = trimmed.indexOf("\n---", 4)
        if (end < 0) return Concept(file, rel, emptyMap(), trimmed, "unterminated YAML frontmatter")
        val yamlText = trimmed.substring(4, end)
        val body = trimmed.substring(end + 4).removePrefix("\n")
        return try {
            val loaded = Yaml(StringTimestampConstructor()).load<Any?>(yamlText)
            @Suppress("UNCHECKED_CAST")
            val map = (loaded as? Map<String, Any?>) ?: emptyMap()
            Concept(file, rel, map, body, if (loaded is Map<*, *> || loaded == null) null else "frontmatter is not a YAML mapping")
        } catch (e: Exception) {
            Concept(file, rel, emptyMap(), body, "invalid YAML: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    /** All concepts (non-reserved `.md` files) under [root], sorted by path. */
    fun concepts(root: Path): List<Concept> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { s ->
            return s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") && it.fileName.toString() !in RESERVED_FILES }
                .sorted()
                .map { parseFile(root, it) }
                .toList()
        }
    }
}
