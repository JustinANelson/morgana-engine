package com.morgana.server.plugins

import com.morgana.domain.sim.ModuleRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `morgana-plugin.json` at the root of a plugin jar:
 *
 * ```json
 * { "id": "stock-market", "name": "Stock Market", "version": "1.0.0", "apiVersion": 1,
 *   "main": "com.example.StockMarketPlugin", "depends": ["economy-core"] }
 * ```
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String = "",
    val version: String = "0.0.0",
    val apiVersion: Int,
    val main: String,
    val description: String = "",
    /** Ids of plugins that must be enabled first. */
    val depends: List<String> = emptyList()
) {
    val displayName: String get() = name.ifBlank { id }

    companion object {
        const val FILE_NAME = "morgana-plugin.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse and validate; the second value is a plain-language reason when the manifest is not usable. */
        fun parse(text: String): Pair<PluginManifest?, String?> {
            val manifest = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                return null to "$FILE_NAME is not valid: ${e.message?.lineSequence()?.first()}"
            }
            val problem = when {
                !ModuleRegistry.PLUGIN_ID.matches(manifest.id) -> "id '${manifest.id}' must be 2-32 characters: lowercase letters, digits, '-' or '_', starting with a letter"
                manifest.apiVersion != PLUGIN_API_VERSION -> "it was built for plugin API ${manifest.apiVersion}, this server offers $PLUGIN_API_VERSION"
                manifest.main.isBlank() -> "main (the plugin class) is missing"
                manifest.id in manifest.depends -> "a plugin cannot depend on itself"
                else -> null
            }
            return if (problem == null) manifest to null else null to problem
        }
    }
}
