package com.morgana.persistence

import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** Describes a world pack; stored as `pack.json` at the root of the pack folder/zip. */
@Serializable
data class WorldPackManifest(
    val formatVersion: Int = WorldPack.FORMAT_VERSION,
    val name: String,
    val description: String = "",
    val author: String = "",
    /** Number of documents per collection directory name, for quick inspection. */
    val counts: Map<String, Int> = emptyMap()
)

/** How [WorldPack.import] treats content that already exists in the target world. */
enum class PackImportMode {
    /** Refuse unless the target world has no content at all. */
    NEW_ONLY,

    /** Add the pack's documents; same-named files are overwritten, everything else is kept. */
    MERGE,

    /** Replace the target world's content entirely (the old world is kept as a backup folder). */
    REPLACE
}

class WorldPackException(message: String, val report: ContentReport? = null) : Exception(message)

data class PackImportResult(
    val manifest: WorldPackManifest,
    val filesWritten: Int,
    /** Where the previous world content was moved to, if any. */
    val backup: Path?,
    val report: ContentReport
)

/**
 * World packs: a whole world's content (the `world/` collections, never accounts, characters or guilds) as a
 * folder or `.zip` of schema-versioned JSON, shareable and diffable. Both export and import run the full
 * [com.morgana.domain.validation.ContentValidator], so a pack that is accepted is guaranteed loadable.
 *
 * Layout: `pack.json` plus `world/<collection>/<file>.json`. Import only accepts exactly those paths and
 * enforces size limits, so a hostile pack cannot write elsewhere or exhaust memory.
 */
object WorldPack {
    const val FORMAT_VERSION = 1
    const val MANIFEST = "pack.json"
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val allowedPath = Regex(
        "^world/(" + ContentCollection.entries.joinToString("|") { it.directoryName } + ")/[A-Za-z0-9._-]+\\.json$"
    )
    private val zipEpoch = FileTime.fromMillis(315_532_800_000L) // 1980-01-01, keeps zips byte-reproducible

    // ------------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------------

    /**
     * Exports [worldDir] to [out] (a `.zip` file, or otherwise a new/empty folder). Refuses to export a world
     * that fails validation, so shared packs are always clean.
     */
    fun export(worldDir: Path, out: Path, name: String, description: String = "", author: String = ""): WorldPackManifest {
        if (!worldDir.isDirectory()) throw WorldPackException("World directory '$worldDir' not found")
        val loaded = ContentLoader.load(worldDir)
        val report = loaded.fullReport()
        if (!report.isValid) {
            throw WorldPackException("World has ${report.errors.size} validation error(s); fix them before exporting.", report)
        }
        val files = collectFiles(worldDir)
        if (files.isEmpty()) throw WorldPackException("World '$worldDir' has no content to export")
        val counts = ContentCollection.entries
            .associate { col -> col.directoryName to files.keys.count { it.startsWith("world/${col.directoryName}/") } }
            .filterValues { it > 0 }
        val manifest = WorldPackManifest(name = name.trim().ifBlank { "Untitled World" }, description = description, author = author, counts = counts)
        val entries = linkedMapOf(MANIFEST to (json.encodeToString(WorldPackManifest.serializer(), manifest) + "\n").toByteArray())
        entries.putAll(files)

        if (out.fileName.toString().endsWith(".zip", ignoreCase = true)) {
            out.parent?.let { Files.createDirectories(it) }
            ZipOutputStream(Files.newOutputStream(out)).use { zip ->
                for ((path, bytes) in entries) {
                    val entry = ZipEntry(path).apply { setLastModifiedTime(zipEpoch) }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } else {
            if (out.exists() && (!out.isDirectory() || Files.list(out).use { it.findAny().isPresent })) {
                throw WorldPackException("Output '$out' exists and is not an empty folder")
            }
            for ((path, bytes) in entries) {
                val target = out.resolve(path)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
        }
        return manifest
    }

    private fun collectFiles(worldDir: Path): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        for (col in ContentCollection.entries) {
            val dir = worldDir.resolve(col.directoryName)
            if (!dir.isDirectory()) continue
            val names = Files.list(dir).use { s -> s.filter { it.isRegularFile() && it.extension == "json" }.map { it.fileName.toString() }.sorted().toList() }
            for (n in names) out["world/${col.directoryName}/$n"] = Files.readAllBytes(dir.resolve(n))
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------------------------------------

    /** Reads and sanity-checks only the manifest. */
    fun inspect(pack: Path): WorldPackManifest = readPack(pack).first

    /**
     * Imports [pack] into [targetWorldDir] (a world content directory such as `server-data/world`).
     * The merged result is validated in a staging folder first; the target is only touched if that passes,
     * and any replaced content is preserved as a sibling `*.bak-<millis>` folder.
     */
    fun import(pack: Path, targetWorldDir: Path, mode: PackImportMode = PackImportMode.NEW_ONLY): PackImportResult {
        val (manifest, files) = readPack(pack)
        val targetHasContent = targetWorldDir.isDirectory() && collectFiles(targetWorldDir).isNotEmpty()
        if (mode == PackImportMode.NEW_ONLY && targetHasContent) {
            throw WorldPackException("Target world already has content; import with MERGE or REPLACE instead.")
        }
        val parent = targetWorldDir.toAbsolutePath().parent ?: throw WorldPackException("Invalid target '$targetWorldDir'")
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, targetWorldDir.fileName.toString() + ".import-")
        try {
            if (mode == PackImportMode.MERGE && targetWorldDir.isDirectory()) {
                for ((rel, bytes) in collectFiles(targetWorldDir)) writeStaged(staging, rel.removePrefix("world/"), bytes)
            }
            for ((rel, bytes) in files) writeStaged(staging, rel.removePrefix("world/"), bytes)

            val report = ContentLoader.load(staging).fullReport()
            if (!report.isValid) {
                throw WorldPackException("Pack failed validation with ${report.errors.size} error(s); nothing was imported.", report)
            }
            var backup: Path? = null
            if (targetWorldDir.exists()) {
                if (targetHasContent) {
                    backup = parent.resolve(targetWorldDir.fileName.toString() + ".bak-" + System.currentTimeMillis())
                    Files.move(targetWorldDir, backup)
                } else {
                    deleteRecursively(targetWorldDir)
                }
            }
            Files.move(staging, targetWorldDir)
            return PackImportResult(manifest, files.size, backup, report)
        } catch (e: Exception) {
            if (staging.exists()) deleteRecursively(staging)
            throw e
        }
    }

    private fun writeStaged(staging: Path, relUnderWorld: String, bytes: ByteArray) {
        val target = staging.resolve(relUnderWorld)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private fun readPack(pack: Path): Pair<WorldPackManifest, Map<String, ByteArray>> {
        if (!pack.exists()) throw WorldPackException("Pack '$pack' not found")
        val raw: Map<String, ByteArray> = if (pack.isDirectory()) readFolder(pack) else readZip(pack)
        val manifestBytes = raw[MANIFEST] ?: throw WorldPackException("Not a world pack: missing $MANIFEST")
        val manifest = try {
            json.decodeFromString(WorldPackManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            throw WorldPackException("Invalid $MANIFEST: ${e.message?.lineSequence()?.first()}")
        }
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw WorldPackException("Unsupported pack format ${manifest.formatVersion} (this build reads $FORMAT_VERSION)")
        }
        val files = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in raw.toSortedMap()) {
            if (name == MANIFEST) continue
            if (!allowedPath.matches(name)) throw WorldPackException("Pack contains a disallowed entry: '$name'")
            files[name] = bytes
        }
        if (files.isEmpty()) throw WorldPackException("Pack contains no world content")
        return manifest to files
    }

    private fun readZip(pack: Path): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipFile(pack.toFile()).use { zip ->
                val entries = zip.entries().toList().filterNot { it.isDirectory }
                if (entries.size > MAX_ENTRIES) throw WorldPackException("Pack has too many entries (${entries.size})")
                for (e in entries) {
                    val name = e.name
                    if (name != MANIFEST && !allowedPath.matches(name)) throw WorldPackException("Pack contains a disallowed entry: '$name'")
                    val bytes = zip.getInputStream(e).use { readBounded(it, name) }
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw WorldPackException("Pack is too large when extracted")
                    out[name] = bytes
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw WorldPackException("Not a valid zip: ${e.message}")
        }
        return out
    }

    private fun readFolder(root: Path): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        val all = Files.walk(root).use { s -> s.filter { it.isRegularFile() }.toList() }
        if (all.size > MAX_ENTRIES) throw WorldPackException("Pack has too many files (${all.size})")
        for (f in all) {
            val name = root.relativize(f).joinToString("/") { it.toString() }
            if (name != MANIFEST && !allowedPath.matches(name)) throw WorldPackException("Pack contains a disallowed entry: '$name'")
            val bytes = Files.newInputStream(f).use { readBounded(it, name) }
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw WorldPackException("Pack is too large")
            out[name] = bytes
        }
        return out
    }

    private fun readBounded(input: java.io.InputStream, name: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            size += n
            if (size > MAX_ENTRY_BYTES) throw WorldPackException("Entry '$name' exceeds the ${MAX_ENTRY_BYTES / 1024 / 1024} MB limit")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
