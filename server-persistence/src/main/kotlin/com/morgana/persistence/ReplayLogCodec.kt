package com.morgana.persistence

import com.morgana.domain.replay.ReplayLog
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes [ReplayLog]s as pretty-printed JSON so recorded sessions can be committed as golden
 * masters or attached to bug reports. Output is stable (defaults always encoded) to keep diffs reviewable.
 */
object ReplayLogCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        classDiscriminator = "_type"
    }

    fun encode(log: ReplayLog): String = json.encodeToString(ReplayLog.serializer(), log)

    fun decode(text: String): ReplayLog = json.decodeFromString(ReplayLog.serializer(), text)

    fun write(path: Path, log: ReplayLog) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, encode(log) + "\n")
    }

    fun read(path: Path): ReplayLog = decode(Files.readString(path))
}
