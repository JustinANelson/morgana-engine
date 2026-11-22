package com.morgana.client.editor.forms

import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SystemGroups

/**
 * The optional server systems (`modules` in `server.json`) as tick boxes: unticked means the system is switched off for the whole
 * realm. Plain data, so the rules are unit-tested; the panel draws it with the shared field view.
 */
object ModulesForm {

    /** One section per genre heading (see [SystemGroups.grouped]), each system once, under the genre it belongs to first. */
    fun sections(): List<FormSection> = SystemGroups.grouped().map { group ->
        FormSection(group.title, group.systems.map {
            FormField("mod.${it.configKey}", it.displayName, FormFieldType.BOOL, "true", hint = it.description)
        })
    }

    /** The tick boxes of a game of [genre]: the systems tagged for it on, every other off (the same choice as `world-new --genre`). */
    fun valuesForGenre(genre: String): Map<String, String> =
        valuesOf(SystemGroups.disabledKeysFor(genre))

    /** The tick boxes for a server that has [disabled] (config keys) switched off. */
    fun valuesOf(disabled: Collection<String>): Map<String, String> =
        OptionalModule.entries.associate { "mod.${it.configKey}" to (it.configKey !in disabled).toString() }

    /** The config keys of the systems [values] switch off, in a stable order. */
    fun disabledFrom(values: Map<String, String>): List<String> =
        OptionalModule.entries.filter { values["mod.${it.configKey}"] == "false" }.map { it.configKey }.sorted()

    /** Warnings about combinations that do not make sense together. They do not block saving. */
    fun warnings(values: Map<String, String>): List<String> {
        val off = disabledFrom(values).toSet()
        val out = ArrayList<String>()
        for ((system, needed) in OptionalModule.unmetRequirements(off)) {
            out += "${system.displayName} needs ${needed.displayName} switched on."
        }
        if ("instancing" !in off && "parties" in off) out += "Instanced dungeons work, but only for solo players, while Parties is off."
        if ("instancing" in off) out += "With Instanced maps off, a dungeon floor is one shared map and generated floors are not private to a party."
        if ("projectiles" in off) out += "With Projectiles off, bows and bolt spells do nothing."
        return out
    }
}
