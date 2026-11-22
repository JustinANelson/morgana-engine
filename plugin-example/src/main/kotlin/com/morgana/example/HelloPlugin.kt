package com.morgana.example

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import com.morgana.protocol.PluginWindowButton
import com.morgana.protocol.PluginWindowDto
import com.morgana.protocol.PluginWindowInput
import com.morgana.protocol.PluginWindowRow
import com.morgana.server.MorganaServerHook
import com.morgana.server.plugins.MorganaPlugin
import com.morgana.server.plugins.PluginContext

/**
 * The smallest useful plugin: when a player logs in it shows a window described with data, and the Wave button counts waves in
 * the plugin's own storage and answers with a message. Everything the player sees is drawn by the standard client.
 */
class HelloPlugin : MorganaPlugin {
    override fun onEnable(context: PluginContext) {
        context.registerHook(object : MorganaServerHook {
            override val pluginId = "hello"

            override fun onPlayerLogin(character: CharacterState, sim: WorldSimulation) {
                show(context, character, sim)
            }

            override fun onPluginAction(character: CharacterState, windowId: String, buttonId: String, values: Map<String, String>, sim: WorldSimulation) {
                if (windowId != WINDOW) return
                when (buttonId) {
                    "wave" -> {
                        val key = "waves:${character.id}"
                        context.storage.set(key, ((context.storage.get(key)?.toIntOrNull() ?: 0) + 1).toString())
                        val note = values["note"].orEmpty().trim()
                        if (note.isNotEmpty()) context.storage.set("note:${character.id}", note)
                        context.messagePlayer(character.id, if (note.isEmpty()) "You wave. The village waves back." else "You wave and say: $note")
                        show(context, character, sim)
                    }
                    "done" -> context.closeWindow(character.id, WINDOW)
                }
            }
        })
        context.logger.info("Hello plugin ready")
    }

    private fun show(context: PluginContext, character: CharacterState, sim: WorldSimulation) {
        val waves = context.storage.get("waves:${character.id}") ?: "0"
        context.openWindow(
            character.id,
            PluginWindowDto(
                id = WINDOW,
                title = "Welcome, ${character.name}",
                lines = listOf("This window was described by a server plugin and drawn by the standard client."),
                rows = listOf(
                    PluginWindowRow("Waves so far", waves),
                    PluginWindowRow("Your level", character.level.toString()),
                    PluginWindowRow("Last note", context.storage.get("note:${character.id}") ?: "none")
                ) + sim.npcs.values.sortedBy { it.id }.take(12).map { PluginWindowRow("Resident: ${it.name}", it.behavior.name.lowercase()) },
                inputs = listOf(PluginWindowInput("note", "Say something", hint = "Leave a note", maxLength = 60)),
                buttons = listOf(PluginWindowButton("wave", "Wave"), PluginWindowButton("done", "Goodbye"))
            )
        )
    }

    private companion object {
        const val WINDOW = "hello:welcome"
    }
}
