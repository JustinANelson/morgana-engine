package com.morgana.client.editor.forms

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.stats.StatusEffectType
import com.morgana.protocol.WorldContentCatalogDto

/** The spell form. The warp fields matter only for the WARP target type and the area radius only for area spells. */
object SpellForm : ContentForm<SpellDefinition> {
    override val title = "Spells"
    override val noun = "spell"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Basics", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("description", "Description", FormFieldType.TEXT),
            FormField("icon", "Icon", FormFieldType.ICON, "1", min = 1),
            FormField("animation", "Animation", FormFieldType.ANIMATION, "1", min = 0),
            FormField("projectile", "Projectile sprite", FormFieldType.INT, "0", min = 0),
            FormField("pierce", "Pierces targets (projectile)", FormFieldType.BOOL, "false")
        )),
        FormSection("Casting", listOf(
            FormField("target", "Targeting", FormFieldType.CHOICE, "SINGLE_TARGET", SpellTargetType.entries.map { it.name }),
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1),
            FormField("classReq", "Classes allowed (ids)", FormFieldType.ID_LIST, hint = "Empty means every class."),
            FormField("rebirthReq", "Rebirths needed", FormFieldType.INT, "0", min = 0),
            FormField("promotionReq", "Promotion tier needed", FormFieldType.INT, "0", min = 0)
        ) + context.costVitalIds.map { FormField("cost.$it", "Costs $it", FormFieldType.INT, "0", min = 0) } + listOf(
            FormField("castTime", "Cast time (ticks)", FormFieldType.INT, "0", min = 0),
            FormField("cooldown", "Cooldown (ticks)", FormFieldType.INT, "40", min = 0),
            FormField("range", "Range (tiles)", FormFieldType.INT, "6", min = 0, max = 40),
            FormField("aoe", "Area radius (tiles)", FormFieldType.INT, "0", min = 0, max = 20)
        )),
        FormSection("Effect", listOf(
            FormField("power", "Base power", FormFieldType.INT, "15", min = 0),
            FormField("scales", "Grows with attributes", FormFieldType.BOOL, "true"),
            FormField("healing", "Heals instead of harming", FormFieldType.BOOL, "false"),
            FormField("revive", "Revives a fallen ghost (% life)", FormFieldType.INT, "0", min = 0, max = 100, hint = "0 means no revive. Needs the Ghost death system in Game rules; a party mate casts it on a ghost."),
            FormField("allyTarget", "Can be cast on an ally", FormFieldType.BOOL, "false"),
            FormField("status", "Applies status", FormFieldType.CHOICE, "", StatusEffectType.entries.map { it.name }, optional = true),
            FormField("statusMagnitude", "Status strength", FormFieldType.INT, "0", min = 0),
            FormField("statusDuration", "Status duration (ticks)", FormFieldType.INT, "0", min = 0)
        )),
        FormSection("Warp (only for the WARP target)", listOf(
            FormField("warpMap", "Destination map", FormFieldType.INT, "0", min = 0),
            FormField("warpX", "Destination X", FormFieldType.INT, "0", min = 0),
            FormField("warpY", "Destination Y", FormFieldType.INT, "0", min = 0)
        ))
    )

    override fun idOf(definition: SpellDefinition) = definition.id
    override fun nameOf(definition: SpellDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = SpellDefinition(
        id = id, name = "New spell $id", costs = context.costVitalIds.firstOrNull()?.let { mapOf(it to 10) } ?: emptyMap()
    )
    override fun all(catalog: WorldContentCatalogDto) = catalog.spells.sortedBy { it.id }

    override fun valuesOf(definition: SpellDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "description" to d.description, "icon" to d.iconId.toString(), "animation" to d.animationId.toString(),
            "projectile" to d.projectileSpriteId.toString(), "pierce" to d.pierce.toString(), "target" to d.targetType.name, "levelReq" to d.levelReq.toString(),
            "classReq" to d.classReqIds.sorted().joinToString(", "),
            "rebirthReq" to d.rebirthReq.toString(),
            "promotionReq" to d.promotionReq.toString(),
            "castTime" to d.castTimeTicks.toString(), "cooldown" to d.cooldownTicks.toString(), "range" to d.rangeTiles.toString(),
            "aoe" to d.aoeRadiusTiles.toString(), "power" to d.basePower.toString(), "scales" to d.scales.toString(),
            "healing" to d.isHealing.toString(), "revive" to d.revivePercent.toString(), "allyTarget" to d.allyTargetable.toString(), "status" to (d.statusEffectType?.name ?: ""),
            "statusMagnitude" to d.statusMagnitude.toString(), "statusDuration" to d.statusDurationTicks.toString(),
            "warpMap" to d.warpMapId.toString(), "warpX" to d.warpX.toString(), "warpY" to d.warpY.toString()
        ) + FormRules.mapValues(d.costs, "cost.", context.costVitalIds)
    }

    override fun apply(definition: SpellDefinition, values: Map<String, String>, context: FormContext): SpellDefinition {
        val d = definition
        fun i(key: String, fallback: Int, min: Int = Int.MIN_VALUE) = FormRules.int(values, key, fallback).coerceAtLeast(min)
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name },
            description = FormRules.text(values, "description", d.description),
            iconId = i("icon", d.iconId, 1),
            animationId = i("animation", d.animationId, 0),
            projectileSpriteId = i("projectile", d.projectileSpriteId, 0),
            pierce = FormRules.bool(values, "pierce", d.pierce),
            targetType = SpellTargetType.entries.firstOrNull { it.name == values["target"] } ?: d.targetType,
            levelReq = i("levelReq", d.levelReq, 1),
            rebirthReq = i("rebirthReq", d.rebirthReq, 0),
            promotionReq = i("promotionReq", d.promotionReq, 0),
            classReqIds = values["classReq"]?.let { FormRules.parseIds(it)?.toSet() } ?: d.classReqIds,
            costs = FormRules.mergeMap(d.costs, "cost.", context.costVitalIds, values),
            castTimeTicks = i("castTime", d.castTimeTicks, 0),
            cooldownTicks = i("cooldown", d.cooldownTicks, 0),
            rangeTiles = i("range", d.rangeTiles, 0),
            aoeRadiusTiles = i("aoe", d.aoeRadiusTiles, 0),
            basePower = i("power", d.basePower, 0),
            scales = FormRules.bool(values, "scales", d.scales),
            isHealing = FormRules.bool(values, "healing", d.isHealing),
            revivePercent = i("revive", d.revivePercent, 0),
            allyTargetable = FormRules.bool(values, "allyTarget", d.allyTargetable),
            statusEffectType = if (values.containsKey("status")) StatusEffectType.entries.firstOrNull { it.name == values["status"] } else d.statusEffectType,
            statusMagnitude = i("statusMagnitude", d.statusMagnitude, 0),
            statusDurationTicks = i("statusDuration", d.statusDurationTicks, 0),
            warpMapId = i("warpMap", d.warpMapId, 0),
            warpX = i("warpX", d.warpX, 0),
            warpY = i("warpY", d.warpY, 0)
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val target = values["target"].orEmpty()
        val aoe = values["aoe"]?.trim()?.toIntOrNull() ?: 0
        val aoeTarget = target == SpellTargetType.AOE_SELF_CENTERED.name || target == SpellTargetType.AOE_TARGET_CENTERED.name || target == SpellTargetType.GROUND_AOE.name
        if (aoeTarget && aoe < 1) out += "An area spell needs an area radius of at least 1"
        if (!aoeTarget && aoe > 0) out += "Area radius is set but the targeting is not an area"
        if (target == SpellTargetType.WARP.name) {
            val map = values["warpMap"]?.trim()?.toIntOrNull() ?: 0
            if (map < 1) out += "A warp spell needs a destination map"
            else if (context.catalog.maps.isNotEmpty() && context.catalog.maps.none { it.id == map }) out += "Destination map $map does not exist"
        }
        val status = values["status"].orEmpty()
        val magnitude = values["statusMagnitude"]?.trim()?.toIntOrNull() ?: 0
        val duration = values["statusDuration"]?.trim()?.toIntOrNull() ?: 0
        if (status.isEmpty() && (magnitude != 0 || duration != 0)) out += "Status strength and duration are set but no status is chosen"
        if (status.isNotEmpty() && duration < 1) out += "A status needs a duration of at least 1 tick"
        return out
    }
}
