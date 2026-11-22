package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEdge
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.InputTokens
import com.morgana.domain.content.SkillArmorType
import com.morgana.domain.content.SkillArmorWindow
import com.morgana.domain.content.SkillDamageKind
import com.morgana.domain.content.SkillGuard
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillMove
import com.morgana.domain.content.SkillProjectile
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.content.WeaponSetSlot
import com.morgana.domain.stats.StatusEffectType
import com.morgana.protocol.WorldContentCatalogDto

private fun rows(text: String?): List<List<String>> =
    text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { l -> l.split('|').map { it.trim() } }

private fun yes(s: String) = s.equals("yes", true) || s.equals("true", true)

/**
 * The action-combat skill form: frame timings, costs, hitboxes, armour windows, dash moves and an optional guard. Tables are one
 * row per line with `|` between columns (every column is shown by the hint). The server's own action-combat rules check the rest
 * when you save.
 */
object ActionSkillForm : ContentForm<ActionSkillDefinition> {
    override val title = "Action skills"
    override val noun = "action skill"

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Basics", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("description", "Description", FormFieldType.TEXT),
            FormField("icon", "Icon", FormFieldType.ICON, "1", min = 1),
            FormField("animation", "Animation", FormFieldType.ANIMATION, "0", min = 0),
            FormField("sound", "Sound id", FormFieldType.TEXT),
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1)
        )),
        FormSection("Timing (ticks, 20 per second)", listOf(
            FormField("startup", "Start-up (before the hit)", FormFieldType.INT, "2", min = 0, max = 200),
            FormField("active", "Active (hitboxes live)", FormFieldType.INT, "2", min = 0, max = 200),
            FormField("recovery", "Recovery (after the hit)", FormFieldType.INT, "6", min = 0, max = 200),
            FormField("cancelFrom", "Can be cancelled from tick", FormFieldType.TEXT, hint = "Empty means when recovery starts."),
            FormField("cooldown", "Cooldown (ticks)", FormFieldType.INT, "0", min = 0, max = 100_000),
            FormField("aimLock", "Faces the cursor while it plays", FormFieldType.BOOL, "true")
        ) + context.vitalIds.filter { it != context.lifeVitalId }.map { FormField("cost.$it", "Costs $it", FormFieldType.INT, "0", min = 0) }),
        FormSection("Hitboxes", listOf(
            FormField("hitboxes", "Hitboxes", FormFieldType.LINES, hint = "One per line: from | to | CIRCLE/RECT/ARC | forward px | side px | radius px | arc degrees | length px | width px | hit group | max targets | MELEE/RANGED/SPELL | damage % | NONE/STAGGER/KNOCKDOWN/AIRBORNE/GRAB | reaction ticks | knockback px | hit-stop ticks | status or empty | status strength | status ticks | projectile (sprite/speed/range/pierce yes-no, or empty). Example: 2 | 4 | ARC | 0 | 0 | 48 | 120 | 0 | 0 | 0 | 0 | MELEE | 100 | STAGGER | 6 | 16 | 2 | | 0 | 0 |")
        )),
        FormSection("Armour, dash and guard", listOf(
            FormField("armor", "Armour windows", FormFieldType.LINES, hint = "One per line: from | to | SUPER_ARMOR or INVULNERABLE"),
            FormField("moves", "Movement", FormFieldType.LINES, hint = "One per line: from | to | forward px | side px (a dash or lunge)"),
            FormField("guard.on", "Is a guard (block)", FormFieldType.BOOL, "false"),
            FormField("guard.arc", "Guard arc (degrees)", FormFieldType.INT, "120", min = 1, max = 360),
            FormField("guard.hold", "Longest hold (ticks, 0 = no limit)", FormFieldType.INT, "0", min = 0, max = 6000),
            FormField("guard.reduction", "Damage blocked (%)", FormFieldType.INT, "100", min = 0, max = 100),
            FormField("guard.costs", "Cost of a blocked hit", FormFieldType.TEXT, hint = "vital=amount, comma-separated. Example: stamina=10")
        ))
    )

    override fun idOf(definition: ActionSkillDefinition) = definition.id
    override fun nameOf(definition: ActionSkillDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = ActionSkillDefinition(id = id, name = "New action skill $id")
    override fun all(catalog: WorldContentCatalogDto) = catalog.actionSkills.sortedBy { it.id }

    override fun valuesOf(definition: ActionSkillDefinition, context: FormContext): Map<String, String> {
        val d = definition; val g = d.guard ?: SkillGuard()
        return mapOf(
            "name" to d.name, "description" to d.description, "icon" to d.iconId.toString(), "animation" to d.animationId.toString(),
            "sound" to d.soundId, "levelReq" to d.levelReq.toString(), "startup" to d.startupTicks.toString(), "active" to d.activeTicks.toString(),
            "recovery" to d.recoveryTicks.toString(), "cancelFrom" to (d.cancelFromTick?.toString() ?: ""), "cooldown" to d.cooldownTicks.toString(),
            "aimLock" to d.aimLock.toString(),
            "hitboxes" to d.hitboxes.joinToString("\n") { formatHitbox(it) },
            "armor" to d.armor.joinToString("\n") { "${it.fromTick} | ${it.toTick} | ${it.type.name}" },
            "moves" to d.moves.joinToString("\n") { "${it.fromTick} | ${it.toTick} | ${it.forwardPixels} | ${it.sidePixels}" },
            "guard.on" to (d.guard != null).toString(), "guard.arc" to g.arcDegrees.toString(), "guard.hold" to g.maxHoldTicks.toString(),
            "guard.reduction" to g.damageReductionPercent.toString(), "guard.costs" to g.blockCosts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        ) + FormRules.mapValues(d.costs, "cost.", context.vitalIds)
    }

    override fun apply(definition: ActionSkillDefinition, values: Map<String, String>, context: FormContext): ActionSkillDefinition {
        val d = definition
        fun i(k: String, fb: Int, min: Int = Int.MIN_VALUE) = FormRules.int(values, k, fb).coerceAtLeast(min)
        val cancel = values["cancelFrom"]?.trim()
        val guard = if (values["guard.on"] == "true") {
            val g = d.guard ?: SkillGuard()
            g.copy(arcDegrees = i("guard.arc", g.arcDegrees, 1), maxHoldTicks = i("guard.hold", g.maxHoldTicks, 0), damageReductionPercent = i("guard.reduction", g.damageReductionPercent, 0),
                blockCosts = values["guard.costs"]?.let { parseCosts(it).first } ?: g.blockCosts)
        } else null
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name }, description = FormRules.text(values, "description", d.description),
            iconId = i("icon", d.iconId, 1), animationId = i("animation", d.animationId, 0), soundId = FormRules.text(values, "sound", d.soundId),
            levelReq = i("levelReq", d.levelReq, 1), startupTicks = i("startup", d.startupTicks, 0), activeTicks = i("active", d.activeTicks, 0),
            recoveryTicks = i("recovery", d.recoveryTicks, 0),
            cancelFromTick = if (cancel == null) d.cancelFromTick else if (cancel.isEmpty()) null else cancel.toIntOrNull() ?: d.cancelFromTick,
            cooldownTicks = i("cooldown", d.cooldownTicks, 0), aimLock = FormRules.bool(values, "aimLock", d.aimLock),
            costs = FormRules.mergeMap(d.costs, "cost.", context.vitalIds, values),
            hitboxes = values["hitboxes"]?.let { parseHitboxes(it).first } ?: d.hitboxes,
            armor = values["armor"]?.let { parseArmor(it).first } ?: d.armor,
            moves = values["moves"]?.let { parseMoves(it).first } ?: d.moves,
            guard = guard
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val cancel = values["cancelFrom"]?.trim().orEmpty()
        return (if (cancel.isNotEmpty() && cancel.toIntOrNull() == null) listOf("Can be cancelled from tick must be a whole number or empty") else emptyList()) +
            parseHitboxes(values["hitboxes"]).second + parseArmor(values["armor"]).second + parseMoves(values["moves"]).second + parseCosts(values["guard.costs"].orEmpty()).second
    }

    private fun formatHitbox(h: SkillHitbox): String {
        val p = h.projectile?.let { "${it.spriteId}/${it.speedPixelsPerTick}/${it.rangePixels}/${if (it.pierce) "yes" else "no"}" } ?: ""
        val hit = h.hit
        return listOf(h.fromTick, h.toTick, h.shape.name, h.forwardPixels, h.sidePixels, h.radiusPixels, h.arcDegrees, h.lengthPixels, h.widthPixels, h.hitGroup, h.maxTargets,
            hit.damageKind.name, hit.damagePercent, hit.reaction.name, hit.reactionTicks, hit.knockbackPixels, hit.hitStopTicks, hit.statusEffectType?.name ?: "",
            hit.statusMagnitude, hit.statusDurationTicks, p).joinToString(" | ")
    }

    fun parseHitboxes(text: String?): Pair<List<SkillHitbox>, List<String>> {
        val problems = ArrayList<String>()
        val out = rows(text).mapIndexedNotNull { i, c ->
            val at = "Hitbox ${i + 1}"
            val n = listOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 15, 16, 18, 19).associateWith { c.getOrNull(it)?.toIntOrNull() }
            val shape = HitboxShape.entries.firstOrNull { it.name == c.getOrNull(2) }
            val kind = SkillDamageKind.entries.firstOrNull { it.name == c.getOrNull(11) }
            val reaction = HitReaction.entries.firstOrNull { it.name == c.getOrNull(13) }
            val statusText = c.getOrNull(17).orEmpty()
            val status = if (statusText.isEmpty()) null else StatusEffectType.entries.firstOrNull { it.name == statusText }
            val pText = c.getOrNull(20).orEmpty()
            val pParts = pText.split('/')
            val projectile = if (pText.isEmpty()) null else if (pParts.size == 4 && pParts.take(3).all { it.toIntOrNull() != null }) SkillProjectile(pParts[0].toInt(), pParts[1].toInt(), pParts[2].toInt(), yes(pParts[3])) else Unit
            if (c.size != 21 || n.values.any { it == null } || shape == null || kind == null || reaction == null || (statusText.isNotEmpty() && status == null) || projectile == Unit) {
                problems += "$at: needs 21 columns (see the hint) with valid numbers, shape, damage kind, reaction, status and projectile"; null
            } else SkillHitbox(n[0]!!, n[1]!!, shape, n[3]!!, n[4]!!, n[5]!!, n[6]!!, n[7]!!, n[8]!!, n[9]!!, n[10]!!,
                SkillHit(kind, n[12]!!, reaction, n[14]!!, n[15]!!, n[16]!!, status, n[18]!!, n[19]!!), projectile as SkillProjectile?)
        }
        return out to problems
    }

    fun parseArmor(text: String?): Pair<List<SkillArmorWindow>, List<String>> {
        val problems = ArrayList<String>()
        val out = rows(text).mapIndexedNotNull { i, c ->
            val a = c.getOrNull(0)?.toIntOrNull(); val b = c.getOrNull(1)?.toIntOrNull(); val t = SkillArmorType.entries.firstOrNull { it.name == c.getOrNull(2) }
            if (c.size != 3 || a == null || b == null || t == null) { problems += "Armour window ${i + 1}: write 'from | to | SUPER_ARMOR or INVULNERABLE'"; null } else SkillArmorWindow(a, b, t)
        }
        return out to problems
    }

    fun parseMoves(text: String?): Pair<List<SkillMove>, List<String>> {
        val problems = ArrayList<String>()
        val out = rows(text).mapIndexedNotNull { i, c ->
            val n = (0..3).map { c.getOrNull(it)?.toIntOrNull() }
            if (c.size != 4 || n.any { it == null }) { problems += "Move ${i + 1}: write 'from | to | forward px | side px'"; null } else SkillMove(n[0]!!, n[1]!!, n[2]!!, n[3]!!)
        }
        return out to problems
    }

    fun parseCosts(text: String): Pair<Map<String, Int>, List<String>> {
        val problems = ArrayList<String>()
        val out = LinkedHashMap<String, Int>()
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val kv = part.split('=').map { it.trim() }
            val n = kv.getOrNull(1)?.toIntOrNull()
            if (kv.size != 2 || kv[0].isEmpty() || n == null || n < 0) problems += "Guard cost '$part': write vital=amount" else out[kv[0]] = n
        }
        return out to problems
    }
}

/** The weapon set form: which skills a weapon offers, which input starts each, and the combo links between them. */
object WeaponSetForm : ContentForm<WeaponSetDefinition> {
    override val title = "Weapon sets"
    override val noun = "weapon set"

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Basics", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("description", "Description", FormFieldType.TEXT),
            FormField("slot", "Slot", FormFieldType.CHOICE, "MAIN", WeaponSetSlot.entries.map { it.name }),
            FormField("classIds", "Classes allowed (ids)", FormFieldType.ID_LIST, ref = FieldRef.CLASS, hint = "Empty means every class."),
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1),
            FormField("buffer", "Input buffer (ticks)", FormFieldType.INT, "6", min = 0, max = 20)
        )),
        FormSection("Skills and combos", listOf(
            FormField("skillIds", "Skills in this set (ids)", FormFieldType.ID_LIST),
            FormField("entries", "Inputs that start a skill", FormFieldType.LINES, hint = "One per line: input | skill id. Inputs are a key (LMB, RMB, SPACE, Q, E, F, TAB) with an optional direction (FORWARD, BACK, LEFT, RIGHT) and modifier (SHIFT, CTRL) joined by +. Example: SHIFT+LMB | 2"),
            FormField("edges", "Combo links", FormFieldType.LINES, hint = "One per line: from skill | input | to skill | window ticks | needs a hit (yes/no). Example: 1 | LMB | 2 | 8 | yes")
        ))
    )

    override fun idOf(definition: WeaponSetDefinition) = definition.id
    override fun nameOf(definition: WeaponSetDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = WeaponSetDefinition(id = id, name = "New weapon set $id")
    override fun all(catalog: WorldContentCatalogDto) = catalog.weaponSets.sortedBy { it.id }

    override fun valuesOf(definition: WeaponSetDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "description" to d.description, "slot" to d.slot.name, "classIds" to d.classIds.sorted().joinToString(", "),
            "levelReq" to d.levelReq.toString(), "buffer" to d.inputBufferTicks.toString(), "skillIds" to d.skillIds.joinToString(", "),
            "entries" to d.entries.joinToString("\n") { "${it.input} | ${it.skillId}" },
            "edges" to d.edges.joinToString("\n") { "${it.fromSkillId} | ${it.input} | ${it.toSkillId} | ${it.windowTicks} | ${if (it.requiresHit) "yes" else "no"}" }
        )
    }

    override fun apply(definition: WeaponSetDefinition, values: Map<String, String>, context: FormContext): WeaponSetDefinition {
        val d = definition
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name }, description = FormRules.text(values, "description", d.description),
            slot = WeaponSetSlot.entries.firstOrNull { it.name == values["slot"] } ?: d.slot,
            classIds = values["classIds"]?.let { FormRules.parseIds(it)?.toSet() } ?: d.classIds,
            levelReq = FormRules.int(values, "levelReq", d.levelReq).coerceAtLeast(1),
            inputBufferTicks = FormRules.int(values, "buffer", d.inputBufferTicks).coerceAtLeast(0),
            skillIds = values["skillIds"]?.let { FormRules.parseIds(it) } ?: d.skillIds,
            entries = values["entries"]?.let { parseEntries(it).first } ?: d.entries,
            edges = values["edges"]?.let { parseEdges(it).first } ?: d.edges
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val entries = parseEntries(values["entries"]); val edges = parseEdges(values["edges"])
        out += entries.second + edges.second
        val inSet = FormRules.parseIds(values["skillIds"].orEmpty()).orEmpty().toSet()
        val known = context.catalog.actionSkills.map { it.id }.toSet()
        for (id in inSet) if (known.isNotEmpty() && id !in known) out += "Skill $id does not exist"
        for (e in entries.first) if (e.skillId !in inSet) out += "Input ${e.input} starts skill ${e.skillId}, which is not in the set"
        for (e in edges.first) {
            if (e.fromSkillId !in inSet) out += "Combo link from skill ${e.fromSkillId}: the skill is not in the set"
            if (e.toSkillId !in inSet) out += "Combo link to skill ${e.toSkillId}: the skill is not in the set"
        }
        return out
    }

    fun parseEntries(text: String?): Pair<List<ComboEntry>, List<String>> {
        val problems = ArrayList<String>()
        val out = rows(text).mapIndexedNotNull { i, c ->
            val input = c.getOrNull(0)?.let { InputTokens.canonical(it) }; val skill = c.getOrNull(1)?.toIntOrNull()
            if (c.size != 2 || input == null || skill == null || skill < 1) { problems += "Input ${i + 1}: write 'input | skill id' with a valid input like SHIFT+LMB"; null } else ComboEntry(input, skill)
        }
        return out to problems
    }

    fun parseEdges(text: String?): Pair<List<ComboEdge>, List<String>> {
        val problems = ArrayList<String>()
        val out = rows(text).mapIndexedNotNull { i, c ->
            val from = c.getOrNull(0)?.toIntOrNull(); val input = c.getOrNull(1)?.let { InputTokens.canonical(it) }
            val to = c.getOrNull(2)?.toIntOrNull(); val window = c.getOrNull(3)?.toIntOrNull()
            if (c.size != 5 || from == null || input == null || to == null || window == null || window < 1) {
                problems += "Combo link ${i + 1}: write 'from skill | input | to skill | window ticks | yes/no'"; null
            } else ComboEdge(from, input, to, window, yes(c[4]))
        }
        return out to problems
    }
}
