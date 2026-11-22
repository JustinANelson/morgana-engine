package com.morgana.domain.game

/**
 * Tracks the version of the engine contract that [GameDefinition] targets.
 * Games specify `engineApi` (e.g., "1") to declare their compatibility requirements.
 */
object EngineApiVersion {
    /** Current engine API major version supported by this engine build. */
    const val CURRENT: Int = 1

    /**
     * Checks if the given [versionString] is compatible with this engine build.
     * Compatible formats:
     * - "1", "1.0", "1.0.0"
     * - "^1", "^1.0.0", "v1", "~1"
     * Returns true if the requested major version is between 1 and [CURRENT].
     */
    fun isCompatible(versionString: String): Boolean {
        val trimmed = versionString.trim().removePrefix("^").removePrefix("v").removePrefix("~")
        val major = trimmed.substringBefore('.').substringBefore('-').toIntOrNull() ?: return false
        return major in 1..CURRENT
    }
}

