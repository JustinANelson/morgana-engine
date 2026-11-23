package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.BossAbility
import com.morgana.domain.content.BossEncounter
import com.morgana.domain.content.BossPhase
import com.morgana.domain.content.BossZoneShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.protocol.WorldContentCatalogDto

/** The NPC form. Loot drops are a text area with one drop per line: `item min-max chance`, for example `3 1-2 5000`. */
object NpcForm : ContentForm<NpcDefinition> {
    override val title = "NPCs"
    override val noun = "NPC"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Identity", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("title", "Title above the head", FormFieldType.TEXT),
            FormField("sprite", "Sprite", FormFieldType.SPRITE, "7", min = 1),
            FormField("behavior", "Behaviour", FormFieldType.CHOICE, "PASSIVE", NpcBehavior.entries.map { it.name }),
            FormField("level", "Level", FormFieldType.INT, "1", min = 1),
            FormField("animation", "Animation", FormFieldType.ANIMATION, "0", min = 0)
        )),
        FormSection("Stats", context.vitalIds.map { FormField("vital.$it", "Max $it", FormFieldType.INT, if (it == context.lifeVitalId) "50" else "0", min = 0) } +
            context.attributeIds.map { FormField("attr.$it", "$it", FormFieldType.INT, "0", min = 0) }),
        FormSection("Combat", listOf(
            FormField("attackDamage", "Attack damage", FormFieldType.INT, "6", min = 0),
            FormField("attackSpeed", "Attack delay (ticks)", FormFieldType.INT, "16", min = 1),
            FormField("moveSpeed", "Move delay (ticks)", FormFieldType.INT, "10", min = 1),
            FormField("aggro", "Notices you within (tiles)", FormFieldType.INT, "5", min = 0, max = 60),
            FormField("leash", "Gives up beyond (tiles)", FormFieldType.INT, "10", min = 1, max = 120),
            FormField("xp", "XP reward", FormFieldType.LONG, "25", min = 0),
            FormField("spells", "Spells (ids)", FormFieldType.ID_LIST, ref = FieldRef.SPELL)
        )),
        FormSection("Talking and trading", listOf(
            FormField("shop", "Shop", FormFieldType.INT, "0", min = 0, ref = FieldRef.SHOP, hint = "0 means no shop."),
            FormField("event", "Dialogue event", FormFieldType.INT, "0", min = 0, ref = FieldRef.EVENT, hint = "0 means none."),
            FormField("greeting", "Greeting", FormFieldType.TEXT)
        )),
        FormSection("Loot", listOf(
            FormField("drops", "Drops", FormFieldType.LINES, hint = "One per line: item min-max chance (chance out of 10000). Example: 3 1-2 5000")
        )),
        FormSection("Boss encounter", listOf(
            FormField("bossPhases", "Phases", FormFieldType.LINES, hint = "One per line, from the start of the fight: life% attack% speed% adds announcement. Adds are comma-separated NPC ids or -. Example: 60 120 100 11,11 The Colossus rattles! Leave empty for an ordinary monster."),
            FormField("bossAttacks", "Telegraphed attacks", FormFieldType.LINES, hint = "One per line: phase spell AROUND_TARGET|AROUND_SELF radius windup cooldown warning. Phase is the line number above (1 = first). Example: 1 6 AROUND_TARGET 2 30 220 Bone Slam!"),
            FormField("bossLockout", "Lockout (ticks)", FormFieldType.LONG, "0", min = 0, hint = "How long a killer is saved to this boss (1728000 = a day); 0 means none.")
        ))
    )

    override fun idOf(definition: NpcDefinition) = definition.id
    override fun nameOf(definition: NpcDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = NpcDefinition(id = id, name = "New NPC $id", spriteId = 7, vitals = mapOf(context.lifeVitalId to 50))
    override fun all(catalog: WorldContentCatalogDto) = catalog.npcs.sortedBy { it.id }

    override fun valuesOf(definition: NpcDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "title" to d.title, "sprite" to d.spriteId.toString(), "behavior" to d.behavior.name,
            "level" to d.level.toString(), "animation" to d.animationId.toString(),
            "attackDamage" to d.attackDamage.toString(), "attackSpeed" to d.attackSpeedTicks.toString(),
            "moveSpeed" to d.moveSpeedTicks.toString(), "aggro" to d.aggroRangeTiles.toString(), "leash" to d.leashRangeTiles.toString(),
            "xp" to d.xpReward.toString(), "spells" to d.spellIds.joinToString(", "),
            "shop" to d.shopId.toString(), "event" to d.eventId.toString(), "greeting" to d.chatGreeting,
            "drops" to formatDrops(d.drops),
            "bossPhases" to formatPhases(d.encounter), "bossAttacks" to formatAttacks(d.encounter),
            "bossLockout" to (d.encounter?.lockoutTicks ?: 0L).toString()
        ) + FormRules.mapValues(d.vitals, "vital.", context.vitalIds) + FormRules.mapValues(d.attributes, "attr.", context.attributeIds)
    }

    override fun apply(definition: NpcDefinition, values: Map<String, String>, context: FormContext): NpcDefinition {
        val d = definition
        fun i(key: String, fallback: Int, min: Int = Int.MIN_VALUE) = FormRules.int(values, key, fallback).coerceAtLeast(min)
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name },
            title = FormRules.text(values, "title", d.title),
            spriteId = i("sprite", d.spriteId, 1),
            behavior = NpcBehavior.entries.firstOrNull { it.name == values["behavior"] } ?: d.behavior,
            level = i("level", d.level, 1),
            animationId = i("animation", d.animationId, 0),
            vitals = FormRules.mergeMap(d.vitals, "vital.", context.vitalIds, values),
            attributes = FormRules.mergeMap(d.attributes, "attr.", context.attributeIds, values),
            attackDamage = i("attackDamage", d.attackDamage, 0),
            attackSpeedTicks = i("attackSpeed", d.attackSpeedTicks, 1),
            moveSpeedTicks = i("moveSpeed", d.moveSpeedTicks, 1),
            aggroRangeTiles = i("aggro", d.aggroRangeTiles, 0),
            leashRangeTiles = i("leash", d.leashRangeTiles, 1),
            xpReward = FormRules.long(values, "xp", d.xpReward).coerceAtLeast(0),
            spellIds = values["spells"]?.let { FormRules.parseIds(it) } ?: d.spellIds,
            shopId = i("shop", d.shopId, 0),
            eventId = i("event", d.eventId, 0),
            chatGreeting = FormRules.text(values, "greeting", d.chatGreeting),
            drops = values["drops"]?.let { parseDrops(it).drops } ?: d.drops,
            encounter = if ("bossPhases" in values || "bossAttacks" in values || "bossLockout" in values) {
                parseEncounter(values["bossPhases"].orEmpty(), values["bossAttacks"].orEmpty(), FormRules.long(values, "bossLockout", d.encounter?.lockoutTicks ?: 0L)).encounter
            } else d.encounter
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val parsed = parseDrops(values["drops"].orEmpty())
        out += parsed.problems
        for (drop in parsed.drops) {
            if (context.catalog.items.isNotEmpty() && context.catalog.items.none { it.id == drop.itemId }) out += "Drop item ${drop.itemId} does not exist"
        }
        val boss = parseEncounter(values["bossPhases"].orEmpty(), values["bossAttacks"].orEmpty(), 0L)
        out += boss.problems
        for (ability in boss.encounter?.phases?.flatMap { it.abilities }.orEmpty()) {
            if (context.catalog.spells.isNotEmpty() && context.catalog.spells.none { it.id == ability.spellId }) out += "Boss attack spell ${ability.spellId} does not exist"
        }
        for (add in boss.encounter?.phases?.flatMap { it.summonNpcIds }.orEmpty()) {
            if (context.catalog.npcs.isNotEmpty() && context.catalog.npcs.none { it.id == add }) out += "Boss add $add does not exist"
        }
        val aggro = values["aggro"]?.trim()?.toIntOrNull()
        val leash = values["leash"]?.trim()?.toIntOrNull()
        if (aggro != null && leash != null && leash < aggro) out += "Gives up beyond ($leash) should not be less than notices you within ($aggro)"
        val shop = values["shop"]?.trim()?.toIntOrNull() ?: 0
        if (shop != 0 && context.catalog.shops.isNotEmpty() && context.catalog.shops.none { it.id == shop }) out += "Shop $shop does not exist"
        val event = values["event"]?.trim()?.toIntOrNull() ?: 0
        if (event != 0 && context.catalog.events.isNotEmpty() && context.catalog.events.none { it.id == event }) out += "Event $event does not exist"
        return out
    }

    // ---- loot lines ----

    class ParsedDrops(val drops: List<NpcDropEntry>, val problems: List<String>)

    /** One line per drop: `item min-max chance` (`item min chance` for a fixed amount). Blank lines are ignored. */
    fun parseDrops(text: String): ParsedDrops {
        val drops = ArrayList<NpcDropEntry>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Drop ${index + 1}"
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 3) {
                problems += "$at: write 'item min-max chance', for example '3 1-2 5000'"
                return@forEachIndexed
            }
            val item = parts[0].toIntOrNull()
            val range = parts[1].split('-')
            val min = range.getOrNull(0)?.toIntOrNull()
            val max = if (range.size == 2) range[1].toIntOrNull() else min
            val perMillion = parts[2].endsWith("ppm", ignoreCase = true)
            val chance = (if (perMillion) parts[2].dropLast(3) else parts[2]).toIntOrNull()
            when {
                item == null || item < 1 -> problems += "$at: the item id must be a positive whole number"
                range.size > 2 || min == null || max == null || min < 1 -> problems += "$at: the amount must be like 2 or 1-3"
                max < min -> problems += "$at: the largest amount is below the smallest"
                chance == null || chance !in 1..(if (perMillion) 1_000_000 else 10_000) -> problems += "$at: the chance must be between 1 and 10000, or like 20ppm for parts per million"
                perMillion -> drops += NpcDropEntry(itemId = item, minAmount = min, maxAmount = max, chanceBasisPoints = 0, chancePerMillion = chance)
                else -> drops += NpcDropEntry(itemId = item, minAmount = min, maxAmount = max, chanceBasisPoints = chance)
            }
        }
        return ParsedDrops(drops, problems)
    }

    fun formatDrops(drops: List<NpcDropEntry>): String = drops.joinToString("\n") { d ->
        val amount = if (d.minAmount == d.maxAmount) d.minAmount.toString() else "${d.minAmount}-${d.maxAmount}"
        "${d.itemId} $amount ${if (d.chancePerMillion > 0) "${d.chancePerMillion}ppm" else d.chanceBasisPoints.toString()}"
    }

    // ---- boss encounter lines ----

    class ParsedEncounter(val encounter: BossEncounter?, val problems: List<String>)

    /**
     * Phases are one line each (`life% attack% speed% adds announcement`), attacks one line each
     * (`phase spell SHAPE radius windup cooldown warning`, the phase being a 1-based line number of the phases). Nothing typed
     * and no lockout means an ordinary monster (null).
     */
    fun parseEncounter(phaseText: String, attackText: String, lockoutTicks: Long): ParsedEncounter {
        val problems = ArrayList<String>()
        val phases = ArrayList<BossPhase>()
        phaseText.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Phase ${index + 1}"
            val parts = line.split(Regex("\\s+"), limit = 5)
            if (parts.size < 4) { problems += "$at: write 'life% attack% speed% adds announcement', for example '60 120 100 - The boss is angry'"; return@forEachIndexed }
            val life = parts[0].toIntOrNull(); val attack = parts[1].toIntOrNull(); val speed = parts[2].toIntOrNull()
            val adds = if (parts[3] == "-") emptyList() else FormRules.parseIds(parts[3])
            when {
                life == null || life !in 1..100 -> problems += "$at: life% must be between 1 and 100"
                attack == null || attack !in 10..1000 -> problems += "$at: attack% must be between 10 and 1000"
                speed == null || speed !in 10..1000 -> problems += "$at: speed% must be between 10 and 1000"
                adds == null -> problems += "$at: adds must be NPC ids separated by commas, or -"
                phases.isNotEmpty() && life >= phases.last().fromLifePercent -> problems += "$at: life% must be lower than the phase before it"
                else -> phases += BossPhase(fromLifePercent = life, announce = parts.getOrNull(4).orEmpty(), attackPercent = attack, moveSpeedPercent = speed, summonNpcIds = adds)
            }
        }
        val abilities = HashMap<Int, MutableList<BossAbility>>()
        attackText.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            val at = "Attack ${index + 1}"
            val parts = line.split(Regex("\\s+"), limit = 7)
            if (parts.size < 6) { problems += "$at: write 'phase spell AROUND_TARGET|AROUND_SELF radius windup cooldown warning'"; return@forEachIndexed }
            val phase = parts[0].toIntOrNull(); val spell = parts[1].toIntOrNull()
            val shape = BossZoneShape.entries.firstOrNull { it.name == parts[2] }
            val radius = parts[3].toIntOrNull(); val windUp = parts[4].toIntOrNull(); val cooldown = parts[5].toIntOrNull()
            when {
                phase == null || phases.isEmpty() || phase !in 1..phases.size -> problems += "$at: the phase must be one of the phase lines above"
                spell == null || spell < 1 -> problems += "$at: the spell must be a spell id"
                shape == null -> problems += "$at: the shape must be AROUND_TARGET or AROUND_SELF"
                radius == null || radius !in 1..10 -> problems += "$at: the radius must be 1 to 10 tiles"
                windUp == null || windUp !in 5..600 -> problems += "$at: the wind-up must be 5 to 600 ticks"
                cooldown == null || cooldown < 20 -> problems += "$at: the cooldown must be at least 20 ticks"
                else -> abilities.getOrPut(phase - 1) { ArrayList() } += BossAbility(spell, shape, radius, windUp, cooldown, parts.getOrNull(6).orEmpty())
            }
        }
        if (phases.isEmpty() && lockoutTicks == 0L) return ParsedEncounter(null, problems)
        return ParsedEncounter(BossEncounter(phases.mapIndexed { i, p -> p.copy(abilities = abilities[i].orEmpty()) }, lockoutTicks), problems)
    }

    fun formatPhases(encounter: BossEncounter?): String = encounter?.phases.orEmpty().joinToString("\n") { p ->
        val adds = if (p.summonNpcIds.isEmpty()) "-" else p.summonNpcIds.joinToString(",")
        "${p.fromLifePercent} ${p.attackPercent} ${p.moveSpeedPercent} $adds${if (p.announce.isEmpty()) "" else " " + p.announce}"
    }

    fun formatAttacks(encounter: BossEncounter?): String = encounter?.phases.orEmpty().withIndex().flatMap { (i, p) ->
        p.abilities.map { a -> "${i + 1} ${a.spellId} ${a.shape.name} ${a.radiusTiles} ${a.windUpTicks} ${a.cooldownTicks}${if (a.warning.isEmpty()) "" else " " + a.warning}" }
    }.joinToString("\n")
}
