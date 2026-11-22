package com.morgana.client.ui.bag

import com.morgana.client.editor.forms.ChoiceText
import com.morgana.domain.content.AbilityTerminology
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType

/** The pure side of the Spellbook/Skills tooltip: what to say about a spell or skill. No graphics, so it is unit-tested. */
object SpellInfo {
    /** "Single target spell", "Area skill"...: the ability's kind in plain words. */
    fun kind(spell: SpellDefinition, terminology: AbilityTerminology = AbilityTerminology.SPELLS): String {
        val word = terminology.singularLabel.lowercase()
        return when (spell.targetType) {
            SpellTargetType.SELF -> if (spell.isHealing) "Healing $word, self" else "Self $word"
            SpellTargetType.SINGLE_TARGET -> if (spell.isHealing) "Healing $word, single target" else "Single target $word"
            SpellTargetType.DIRECTIONAL_PROJECTILE -> "Projectile $word"
            SpellTargetType.AOE_SELF_CENTERED -> "Area $word around you"
            SpellTargetType.AOE_TARGET_CENTERED -> "Area $word at a target"
            SpellTargetType.GROUND_AOE -> if (spell.isHealing) "Healing area $word at cursor" else "Ground area $word at cursor"
            SpellTargetType.WARP -> "Teleport $word"
        }
    }

    /** Ticks as seconds to a tenth, without String.format (browser build); the simulation runs 20 ticks per second. */
    private fun seconds(ticks: Int): String {
        val tenths = ticks * 10 / 20
        return "${tenths / 10}.${tenths % 10} s"
    }

    /**
     * One line for a quickbar slot's hover tip: "10 MP, cooldown 2.0 s, range 6 tiles", or "ready in 1.3 s" while it cools.
     * The full tooltip lives in the Spellbook; this is what a player needs mid-fight.
     */
    fun quickLine(spell: SpellDefinition, names: StatNames, cooldownLeftSeconds: Float = 0f): String {
        val parts = ArrayList<String>()
        parts += if (spell.costs.isEmpty()) "free" else spell.costs.entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${names.vital(it.key)}" }
        parts += "cooldown ${seconds(spell.cooldownTicks)}"
        if (spell.targetType != SpellTargetType.SELF && spell.targetType != SpellTargetType.AOE_SELF_CENTERED && spell.targetType != SpellTargetType.WARP) {
            parts += "range ${tiles(spell.rangeTiles)}"
        }
        if (cooldownLeftSeconds > 0f) {
            val tenths = (cooldownLeftSeconds * 10f).toInt().coerceAtLeast(1)
            parts += "ready in ${tenths / 10}.${tenths % 10} s"
        }
        return parts.joinToString(", ")
    }

    private fun tiles(n: Int) = if (n == 1) "1 tile" else "$n tiles"

    /**
     * The tooltip for [spell]: effect, costs (red when the player cannot pay, when [vitals] is known), timing, reach, any
     * status it applies and what it needs. [cooldownLeftSeconds] adds a line while it is cooling down.
     */
    fun tip(
        spell: SpellDefinition,
        names: StatNames,
        wearer: WearerInfo?,
        vitals: Map<String, Int>? = null,
        cooldownLeftSeconds: Float = 0f,
        spellLevel: Int = 1,
        terminology: AbilityTerminology = AbilityTerminology.SPELLS
    ): ItemTip {
        val out = ArrayList<InfoLine>()
        if (spell.baseDamagePercent > 0) {
            val pct = spell.baseDamagePercent + (spellLevel - 1) * spell.baseDamagePercentPerLevel
            val growth = if (spell.baseDamagePercentPerLevel > 0) " (+${spell.baseDamagePercentPerLevel}%/lv)" else ""
            val hits = spell.effectiveHitCount(spellLevel)
            val hitText = when {
                spell.maxHitCount > spell.hitCount -> " x$hits hits (${spell.hitCount}..${spell.maxHitCount})"
                hits > 1 -> " x$hits hits"
                else -> ""
            }
            out += InfoLine("Deals $pct% base damage$hitText$growth", Tone.NEUTRAL)
            if (spell.baseDamagePercentPerLevel > 0 || spell.maxHitCount > spell.hitCount) {
                out += InfoLine("${terminology.levelPrefix} $spellLevel / ${spell.maxSpellLevel}", Tone.GOOD)
            }
        } else if (spell.targetType != SpellTargetType.WARP && spell.basePower > 0) {
            val verb = if (spell.isHealing) "Heals" else "Damage"
            out += InfoLine("$verb ${spell.basePower}" + if (spell.scales) ", grows with your stats" else "", if (spell.isHealing) Tone.GOOD else Tone.NEUTRAL)
        }
        if (spell.targetType == SpellTargetType.WARP) out += InfoLine("Teleports you to another place", Tone.NEUTRAL)
        if (spell.costs.isEmpty()) {
            out += InfoLine("Costs nothing", Tone.MUTED)
        } else {
            for ((id, n) in spell.costs.entries.sortedBy { it.key }) {
                val short = vitals != null && (vitals[id] ?: 0) < n
                out += InfoLine("Costs $n ${names.vital(id)}", if (short) Tone.BAD else Tone.NEUTRAL)
            }
        }
        out += InfoLine(if (spell.castTimeTicks > 0) "Cast time ${seconds(spell.castTimeTicks)}" else "Instant cast", Tone.MUTED)
        out += InfoLine("Cooldown ${seconds(spell.cooldownTicks)}", Tone.MUTED)
        if (spell.targetType != SpellTargetType.SELF && spell.targetType != SpellTargetType.AOE_SELF_CENTERED && spell.targetType != SpellTargetType.WARP) {
            out += InfoLine("Range ${tiles(spell.rangeTiles)}", Tone.MUTED)
        }
        if (spell.aoeRadiusTiles > 0) {
            out += InfoLine(
                if (spell.maxTargets > 0) "Auto-targets up to ${spell.maxTargets} enemies within ${tiles(spell.aoeRadiusTiles)}"
                else "Hits everything within ${tiles(spell.aoeRadiusTiles)}",
                Tone.MUTED
            )
        }
        spell.statusEffectType?.let {
            val magnitude = if (spell.statusMagnitude != 0) " (${spell.statusMagnitude})" else ""
            val duration = if (spell.statusDurationTicks > 0) " for ${seconds(spell.statusDurationTicks)}" else ""
            out += InfoLine("Applies ${ChoiceText.label(it.name)}$magnitude$duration", Tone.NEUTRAL)
        }
        if (spell.levelReq > 1) out += InfoLine("Needs level ${spell.levelReq}", if (wearer != null && wearer.level < spell.levelReq) Tone.BAD else Tone.MUTED)
        if (spell.promotionReq > 0) out += InfoLine("Needs promotion tier ${spell.promotionReq}", Tone.MUTED)
        if (spell.rebirthReq > 0) out += InfoLine("Needs ${spell.rebirthReq} rebirth(s)", Tone.MUTED)
        if (spell.classReqIds.isNotEmpty()) out += InfoLine("Only some classes can cast this", if (wearer != null && wearer.classId !in spell.classReqIds) Tone.BAD else Tone.MUTED)
        if (spell.weaponTypeReqs.isNotEmpty()) {
            val needed = spell.weaponTypeReqs.sorted().joinToString(" or ") { ChoiceText.label(it) }
            val missing = wearer != null && wearer.equippedWeaponType !in spell.weaponTypeReqs
            out += InfoLine("Needs weapon: $needed", if (missing) Tone.BAD else Tone.MUTED)
        }
        if (spell.weaponProficiencyLevelReq > 0) {
            val curProf = wearer?.let { w ->
                if (w.equippedWeaponType.isNotBlank()) (w.weaponProficiencyLevels[w.equippedWeaponType] ?: 1) else 0
            } ?: 0
            val missing = wearer != null && (wearer.equippedWeaponType.isBlank() || curProf < spell.weaponProficiencyLevelReq)
            out += InfoLine("Needs proficiency Lv ${spell.weaponProficiencyLevelReq}", if (missing) Tone.BAD else Tone.MUTED)
        }
        if (cooldownLeftSeconds > 0f) {
            val tenths = (cooldownLeftSeconds * 10f).toInt().coerceAtLeast(1)
            out += InfoLine("Ready in ${tenths / 10}.${tenths % 10} s", Tone.BAD)
        }
        return ItemTip(title = spell.name, kind = kind(spell, terminology), description = spell.description, lines = out)
    }
}
