package com.morgana.server

import com.morgana.domain.validation.ContentIssue
import com.morgana.domain.validation.RejectedDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What the last content reload did, as written to `<game>/.reload-status.json` for a script or an LLM author to read. */
@Serializable
data class ReloadStatus(
    /** Epoch milliseconds (UTC) the reload finished. */
    val atMillis: Long,
    /** False when nothing was applied (errors that could not be set aside). */
    val applied: Boolean,
    val summary: String,
    /** What changed, e.g. `item#12`, `map#3`. */
    val changed: List<String>,
    /** Files left as they were because of errors, each with its problems (JSON pointer, code, message). */
    val rejected: List<RejectedDocument>,
    val warnings: List<ContentIssue>
)

/**
 * Writes the outcome of every content reload next to `game.json`, so the author of generated content gets an answer without
 * reading a server log. The file starts with a dot and lives outside `data/` on purpose: the content watcher must not see it
 * and reload again.
 */
object ReloadStatusFile {
    const val NAME = ".reload-status.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(gameDir: Path, result: ContentReloadResult, rejected: List<RejectedDocument>, now: Long = System.currentTimeMillis()) {
        try {
            val status = ReloadStatus(now, result.applied, result.summary, result.changed, rejected, result.report.warnings)
            Files.createDirectories(gameDir)
            val tmp = gameDir.resolve("$NAME.tmp")
            Files.writeString(tmp, json.encodeToString(status))
            Files.move(tmp, gameDir.resolve(NAME), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The status file is a convenience; a read-only folder must not break a reload.
        }
    }
}
