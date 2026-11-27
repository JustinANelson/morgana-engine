package com.morgana.client.realms

import com.morgana.client.RealmSpec
import com.morgana.client.RealmTemplateInfo
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SystemGroup
import com.morgana.domain.sim.SystemGroups
import com.morgana.domain.sim.SystemTags

/** A one-click selection of optional features. [genre] is the [SystemTags] value of a genre starter, or null for the other presets. */
data class FeaturePreset(
    val id: String,
    val title: String,
    val description: String,
    val disabledModuleKeys: Set<String>,
    val noCombat: Boolean = false,
    val modernSprites: Boolean = false,
    val genre: String? = null,
    val uiLayout: String? = null
)

/**
 * The New Realm Wizard's state and rules, with no GUI in it (so it is unit-tested). The window only binds widgets to this.
 *
 * The wizard exposes only what the engine already supports: a starting template and the optional-module switches.
 * Nothing here changes how the engine plays.
 */
class RealmWizardModel(
    val templates: List<RealmTemplateInfo>,
    val modules: List<OptionalModule> = OptionalModule.entries
) {
    enum class Step(val title: String) {
        BASICS("Name and starting point"),
        FEATURES("Features"),
        REVIEW("Review")
    }

    var step: Step = Step.BASICS
        private set

    var name: String = ""
    private val disabled = linkedSetOf<String>()

    /**
     * The starting point. Choosing one (it is chosen before the features step) resets the feature switches to what that template
     * starts with: its own `server.json` switches, or everything on.
     */
    var templateId: String = templates.firstOrNull()?.id.orEmpty()
        set(value) {
            if (field == value) return
            field = value
            applyTemplateDefaults()
        }
    var noCombat: Boolean = false
    var modernSprites: Boolean = false
    var uiLayout: String? = null

    init {
        applyTemplateDefaults()
    }

    /** The systems the chosen template switches off by itself (only those this wizard lists). */
    val templateDefaults: Set<String>
        get() = template?.disabledModuleKeys.orEmpty().filter { key -> modules.any { it.configKey == key } }.toSet()

    private fun applyTemplateDefaults() {
        disabled.clear()
        disabled.addAll(templateDefaults)
        uiLayout = null
    }

    val template: RealmTemplateInfo? get() = templates.firstOrNull { it.id == templateId }

    /** The listed systems grouped by genre, for headings in the features step. */
    val groups: List<SystemGroup> get() = SystemGroups.grouped(modules)

    /** Switches every system of [group] on or off at once. */
    fun setGroupEnabled(group: SystemGroup, enabled: Boolean) {
        for (m in group.systems) setEnabled(m, enabled)
    }

    /** True when every system of [group] is on, false when every one is off, null for a mix. */
    fun groupState(group: SystemGroup): Boolean? {
        val on = group.systems.count { isEnabled(it) }
        return when (on) {
            group.systems.size -> true
            0 -> false
            else -> null
        }
    }
    val disabledModuleKeys: Set<String> get() = disabled.toSet()

    fun isEnabled(module: OptionalModule): Boolean = module.configKey !in disabled

    fun setEnabled(module: OptionalModule, enabled: Boolean) {
        if (enabled) disabled.remove(module.configKey) else disabled.add(module.configKey)
    }

    fun applyPreset(preset: FeaturePreset) {
        disabled.clear()
        disabled.addAll(preset.disabledModuleKeys.filter { key -> modules.any { it.configKey == key } })
        noCombat = preset.noCombat
        modernSprites = preset.modernSprites
        uiLayout = preset.uiLayout
    }

    /** The preset whose switches match the current ones, if any (so the UI can show which is active). */
    fun activePreset(): FeaturePreset? =
        (PRESETS + GENRES).firstOrNull {
            it.disabledModuleKeys == disabled && it.noCombat == noCombat && it.modernSprites == modernSprites && it.uiLayout == uiLayout
        }

    /** Why [name] cannot be used, or null if it can. */
    fun nameProblem(): String? = when {
        name.isBlank() -> "Give the realm a name."
        name.trim().length > MAX_NAME -> "The name can be at most $MAX_NAME characters."
        else -> null
    }

    /** Why the wizard cannot move past the current step, or null if it can. */
    fun blockingProblem(): String? = when (step) {
        Step.BASICS -> nameProblem() ?: if (template == null) "Choose a starting point." else null
        Step.FEATURES, Step.REVIEW -> null
    }

    val canAdvance: Boolean get() = blockingProblem() == null
    val isLastStep: Boolean get() = step == Step.REVIEW
    val stepNumber: Int get() = step.ordinal + 1
    val stepCount: Int get() = Step.entries.size

    fun next(): Boolean {
        if (!canAdvance || isLastStep) return false
        step = Step.entries[step.ordinal + 1]
        return true
    }

    fun back(): Boolean {
        if (step == Step.BASICS) return false
        step = Step.entries[step.ordinal - 1]
        return true
    }

    /** Everything collected so far, ready to hand to [com.morgana.client.LocalWorldHost.createRealm]. */
    fun toSpec(): RealmSpec =
        RealmSpec(name.trim(), templateId, disabledModuleKeys, noCombat, modernSprites, enabledModuleKeys = templateDefaults - disabled, uiLayout = uiLayout)

    /** Plain-language summary for the review step. */
    fun summaryLines(): List<String> = buildList {
        add("Name: ${name.trim()}")
        add("Starting point: ${template?.title ?: "none"}")
        if (noCombat) {
            add("Combat: disabled (peaceful realm, combat and spell UI hidden)")
        }
        if (modernSprites) {
            add("Sprites: modern taller 32 x 64 multi-state characters (foot-anchored)")
        }
        if (uiLayout == com.morgana.domain.game.UiLayout.RING) {
            add("HUD & mobile controls: Ring action cluster and floating joystick")
        }
        activePreset()?.genre?.let { add("Genre: ${SystemTags.TITLES[it]}") }
        val off = modules.filter { !isEnabled(it) }
        if (off.isEmpty()) {
            add("Features: everything on")
        } else {
            add("Switched off: ${off.joinToString { it.displayName }}")
            add("On: ${modules.filter { isEnabled(it) }.joinToString { it.displayName }.ifEmpty { "none" }}")
        }
        add("You can change features later in server.json (restart to apply). The starting content is yours to edit with the F1 editors.")
    }

    companion object {
        const val MAX_NAME = 40

        /**
         * One starter per genre ([SystemTags.TITLES]): the systems tagged for the genre stay on and every other system is switched
         * off, so "Roguelike" is parties, instancing, boss encounters, threat and so on, without guilds, trading or the market.
         * Online RPG (`rpg`) and Roguelike / dungeon crawler (`roguelike`) default to the mobile-first `ring` HUD and controls.
         * The same choice is `world-new --genre <tag>` on the command line.
         */
        val GENRES: List<FeaturePreset> = SystemTags.TITLES.map { (tag, title) ->
            val off = SystemGroups.disabledKeysFor(tag)
            val kept = OptionalModule.entries.filter { it.configKey !in off }.map { it.displayName }
            val shown = kept.take(6).joinToString() + if (kept.size > 6) " and ${kept.size - 6} more" else ""
            val layout = com.morgana.domain.game.UiLayout.defaultForGenre(tag)
                .takeIf { it != com.morgana.domain.game.UiLayout.CLASSIC }
            FeaturePreset("genre:$tag", title, "Keeps $shown. Everything else is switched off.", off, genre = tag, uiLayout = layout)
        }

        val PRESETS: List<FeaturePreset> = listOf(
            FeaturePreset("full", "Full online world", "Every optional system on.", emptySet()),
            FeaturePreset(
                "coop", "Co-op adventure", "Parties and crafting, but no guilds or player trading.",
                setOf("guilds", "trading")
            ),
            FeaturePreset(
                "solo", "Solo story", "No parties, guilds or trading: a focused single-player style.",
                setOf("parties", "guilds", "trading")
            ),
            FeaturePreset(
                "peaceful", "No combat", "Peaceful realm: disables combat and hides combat and spell buttons.",
                emptySet(),
                noCombat = true
            ),
            FeaturePreset(
                "modern_sprites", "Modern sprites", "Uses taller 32 x 64 multi-state sprite sheets anchored at the character's feet.",
                emptySet(),
                modernSprites = true
            )
        )
    }
}
