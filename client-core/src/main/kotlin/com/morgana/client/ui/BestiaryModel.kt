package com.morgana.client.ui

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.game.BestiaryDefinition
import com.morgana.domain.item.ItemDefinition

/**
 * What the Bestiary window shows: one row per monster kind the character has killed, and how much of it is revealed
 * (more kills, more detail; see [BestiaryDefinition]). Pure, so the stages are unit-tested.
 */
object BestiaryModel {
    enum class Stage { SEEN, STATS, DROPS }

    data class Row(val npcId: Int, val name: String, val level: Int, val kills: Int, val stage: Stage)

    /** The kinds the character has killed, by name. Kinds the catalog does not know (removed content) are left out; non-monsters too. */
    fun rows(kills: Map<Int, Int>, npcs: List<NpcDefinition>, rules: BestiaryDefinition): List<Row> {
        val byId = npcs.associateBy { it.id }
        return kills.filter { it.value > 0 }.mapNotNull { (id, n) ->
            val def = byId[id] ?: return@mapNotNull null
            Row(id, def.name, def.level, n, stageFor(n, rules))
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.npcId }))
    }

    fun stageFor(kills: Int, rules: BestiaryDefinition): Stage = when {
        kills >= rules.dropsAfterKills -> Stage.DROPS
        kills >= rules.statsAfterKills -> Stage.STATS
        else -> Stage.SEEN
    }

    /** The lines for one monster: always its level and kill count, then as much as the stage allows, with a hint about what comes next. */
    fun detail(row: Row, def: NpcDefinition, items: Map<Int, ItemDefinition>, attributeNames: Map<String, String>, rules: BestiaryDefinition): List<String> {
        val lines = mutableListOf("Level ${row.level}", "Defeated ${row.kills} time${if (row.kills == 1) "" else "s"}")
        if (row.stage >= Stage.STATS) {
            lines.add("Life ${def.vitals.values.firstOrNull() ?: 0}, damage ${def.attackDamage}")
            val attrs = def.attributes.entries.sortedBy { it.key }.joinToString(", ") { "${attributeNames[it.key] ?: it.key} ${it.value}" }
            if (attrs.isNotEmpty()) lines.add(attrs)
        } else {
            lines.add("Defeat ${rules.statsAfterKills - row.kills} more to learn how it fights.")
        }
        if (row.stage >= Stage.DROPS) {
            if (def.drops.isEmpty()) lines.add("Drops nothing.")
            else for (d in def.drops) lines.add("Drops ${items[d.itemId]?.name ?: "item ${d.itemId}"} (${chanceText(d)})")
        } else if (row.stage == Stage.STATS) {
            lines.add("Defeat ${rules.dropsAfterKills - row.kills} more to learn what it drops.")
        }
        return lines
    }

    /** "25%" for common drops, "1 in 2,500" for rare ones. */
    private fun chanceText(d: com.morgana.domain.content.NpcDropEntry): String {
        val ppm = d.chancePpm
        return if (ppm >= 10_000L || ppm <= 0L) "${ppm / 10_000L}%" else "1 in ${String.format(java.util.Locale.ROOT, "%,d", Math.round(1_000_000.0 / ppm))}"
    }
}
