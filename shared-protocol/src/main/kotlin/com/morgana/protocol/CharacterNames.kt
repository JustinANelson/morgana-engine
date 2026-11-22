package com.morgana.protocol

/** The rules for a character's name, shared so the client can explain a bad one before asking and the server enforces the same. */
object CharacterNames {
    const val MIN = 2
    const val MAX = 20
    const val DEFAULT_MAX_CHARACTERS = 3

    private val allowed = Regex("[A-Za-z0-9 '_-]+")

    /** Why [raw] cannot be a character name, in plain words, or null when it can. Names are compared without regard to case. */
    fun problem(raw: String): String? {
        val name = raw.trim()
        return when {
            name.length < MIN || name.length > MAX -> "A name must be $MIN to $MAX characters."
            !allowed.matches(name) -> "A name can use letters, numbers, spaces, apostrophes, hyphens and underscores."
            name.contains("  ") -> "A name cannot have two spaces in a row."
            name.any { it.isLetterOrDigit() }.not() -> "A name needs at least one letter or number."
            else -> null
        }
    }

    /** The name as stored: trimmed. */
    fun clean(raw: String): String = raw.trim()
}
