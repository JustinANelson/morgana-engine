package com.morgana.client.net

import java.io.File

/**
 * Keeps the resume token in a small file so a desktop or Android client can return to its character after being closed.
 * The file holds a single-use token that expires 30 minutes after disconnect, never the password. Failures are swallowed:
 * resuming is a convenience, and a broken file just means the player logs in normally.
 */
class FileResumeStore(private val file: File) : ResumeStore {
    override fun load(): ResumeInfo? =
        runCatching { if (file.isFile) ResumeInfo.fromJson(file.readText()) else null }.getOrNull()

    override fun save(info: ResumeInfo) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(info.toJson())
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}
