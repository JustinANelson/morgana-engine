package com.morgana.script.luak

/**
 * Result of statically validating a Lua script.
 */
sealed interface ScriptValidationResult {
    data class Valid(val moduleId: String) : ScriptValidationResult
    data class Invalid(val moduleId: String, val message: String, val line: Int? = null) : ScriptValidationResult
}

/**
 * Static validator for Lua scripts to verify syntax and sandbox constraints ahead of runtime.
 */
object LuakScriptValidator {
    private val FORBIDDEN_TOKENS = listOf(
        "io.", "os.", "package.", "debug.", "dofile", "loadfile", "loadstring", "collectgarbage"
    )

    fun validate(moduleId: String, source: String): ScriptValidationResult {
        // Token check for obvious sandbox violations
        for (token in FORBIDDEN_TOKENS) {
            if (source.contains(token)) {
                return ScriptValidationResult.Invalid(
                    moduleId = moduleId,
                    message = "Forbidden sandbox symbol '$token' found in script source"
                )
            }
        }

        // Compilation check for syntax errors
        val g = LuakSandbox.createGlobals()
        return try {
            g.compile(source, moduleId)
            ScriptValidationResult.Valid(moduleId)
        } catch (e: Exception) {
            val msg = e.message ?: "Syntax error"
            val line = Regex(":(\\d+):").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            ScriptValidationResult.Invalid(moduleId, msg, line)
        }
    }
}

