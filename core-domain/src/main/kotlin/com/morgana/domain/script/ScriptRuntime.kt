package com.morgana.domain.script

/**
 * Result of compiling or validating a script module.
 */
sealed interface ScriptCompilationResult {
    data class Success(val moduleId: String) : ScriptCompilationResult
    data class Error(val moduleId: String, val message: String, val line: Int? = null) : ScriptCompilationResult
}

/**
 * Engine port for sandboxed scripting runtimes.
 *
 * Implemented by :script-luak using Luak, with zero Luak types leaking into domain or application layers.
 */
interface ScriptRuntime {
    val budget: ScriptBudget
    val registry: ScriptRegistry

    /**
     * Loads and executes a script chunk into the runtime environment.
     */
    fun loadModule(moduleId: String, source: String, world: ScriptWorldView): ScriptCompilationResult

    /**
     * Resets runtime state and clears all loaded modules and registered handlers.
     */
    fun reset()

    /**
     * Checks if a module is currently loaded.
     */
    fun hasModule(moduleId: String): Boolean

    /**
     * Returns the list of loaded module IDs.
     */
    fun loadedModules(): List<String>

    /** True when a module was switched off after repeated failures (its hooks do nothing and the systems it replaced come back). */
    fun isModuleDisabled(moduleId: String): Boolean = false
}

/**
 * Default no-op runtime for headless tests or runs where scripting is disabled.
 */
class NoOpScriptRuntime(override val budget: ScriptBudget = ScriptBudget()) : ScriptRuntime {
    override val registry = ScriptRegistry()
    private val modules = mutableSetOf<String>()

    override fun loadModule(moduleId: String, source: String, world: ScriptWorldView): ScriptCompilationResult {
        modules.add(moduleId)
        return ScriptCompilationResult.Success(moduleId)
    }

    override fun reset() {
        modules.clear()
        registry.clear()
    }

    override fun hasModule(moduleId: String): Boolean = modules.contains(moduleId)
    override fun loadedModules(): List<String> = modules.toList()
}

