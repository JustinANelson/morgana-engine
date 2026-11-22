package com.morgana.server.plugins

import com.morgana.protocol.PluginWindowDto
import com.morgana.server.MorganaServerHook
import org.slf4j.Logger
import java.nio.file.Path

/** The plugin API version this server offers. A plugin's manifest `apiVersion` must equal it; it changes only for breaking changes. */
const val PLUGIN_API_VERSION = 1

/**
 * The entry point of a server plugin jar (named by `main` in its `morgana-plugin.json`). It needs a public no-argument constructor.
 *
 * Plugins are trusted Kotlin/JVM code with full access to the server process: only install jars you trust. They are loaded from
 * the operator's `plugins/` folder, never from a world pack.
 */
interface MorganaPlugin {
    /** Called once when the plugin starts. Register hooks and modules here. Throwing disables the plugin and undoes its registrations. */
    fun onEnable(context: PluginContext)

    /** Called when the plugin is switched off or the server stops. Its hooks and modules are removed afterwards by the loader. */
    fun onDisable() {}
}

/** How the loader reaches players' clients; the coordinator provides the real one. The default reaches nobody. */
interface PluginUiBridge {
    fun sendMessage(characterId: String, channel: String, payload: Map<String, String>): Boolean
    fun openWindow(characterId: String, window: PluginWindowDto): Boolean
    fun closeWindow(characterId: String, windowId: String): Boolean

    object None : PluginUiBridge {
        override fun sendMessage(characterId: String, channel: String, payload: Map<String, String>) = false
        override fun openWindow(characterId: String, window: PluginWindowDto) = false
        override fun closeWindow(characterId: String, windowId: String) = false
    }
}

/** What the server gives a plugin. Everything registered here is removed again when the plugin is disabled. */
interface PluginContext {
    val pluginId: String
    val version: String
    val logger: Logger

    /** This plugin's own folder (`plugins/<id>/`) for configuration and data; created on first use. */
    val dataDir: Path

    /** This plugin's own saved key-value store (`plugins/<id>/storage.json`), private to it. */
    val storage: PluginStorage

    /** Receive game callbacks ([MorganaServerHook]). */
    fun registerHook(hook: MorganaServerHook)

    /**
     * Offer an optional module an operator can switch off with `"modules": { "<pluginId>:<name>": false }` in `server.json`.
     * Returns the full id. Check it with [isModuleEnabled] before acting.
     */
    fun registerModule(name: String, displayName: String, description: String = ""): String

    /** Show a server message in one online player's chat. Returns false when that character is not online. */
    fun messagePlayer(characterId: String, text: String): Boolean

    /**
     * Send a message to one player's client (`plugin_ui` capability). [channel] must be `<pluginId>:<name>`; the payload is
     * text. Returns false when the player is offline or their client does not support plugin UI.
     */
    fun sendMessage(characterId: String, channel: String, payload: Map<String, String> = emptyMap()): Boolean

    /**
     * Show a data-described window ([PluginWindowDto]) to one player, or replace it if one with the same id is open. The window
     * id must be `<pluginId>:<name>`. Throws [IllegalArgumentException] for a window outside the size limits. Returns false when
     * the player is offline or their client does not support plugin UI. Button presses arrive as `onPluginAction`.
     */
    fun openWindow(characterId: String, window: PluginWindowDto): Boolean

    /** Close one of this plugin's windows for a player. */
    fun closeWindow(characterId: String, windowId: String): Boolean

    /** Whether the module is on right now (the operator's `server.json` setting). */
    fun isModuleEnabled(moduleId: String): Boolean
}
