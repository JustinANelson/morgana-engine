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
    val warnings: List<ContentIssue>,
    /** Art files (manifest paths such as `tilesets/7.png`) new or changed in the last art refresh and sent to connected clients. */
    val assetsUpdated: List<String> = emptyList(),
    /** Problems with art files in the last refresh (a file left out of the manifest, and why). */
    val assetErrors: List<String> = emptyList(),
    /** True when `game.json` changed on disk: game rules are read at start, so the change takes effect after a restart. */
    val restartRequired: Boolean = false
)

/**
 * Writes the outcome of every content reload next to `game.json`, so the author of generated content gets an answer without
 * reading a server log. The file starts with a dot and lives outside `data/` on purpose: the content watcher must not see it
 * and reload again.
 */
object ReloadStatusFile {
    const val NAME = ".reload-status.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(gameDir: Path, status: ReloadStatus) {
        try {
            Files.createDirectories(gameDir)
            val tmp = gameDir.resolve("$NAME.tmp")
            Files.writeString(tmp, json.encodeToString(status))
            Files.move(tmp, gameDir.resolve(NAME), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The status file is a convenience; a read-only folder must not break a reload.
        }
    }
}
