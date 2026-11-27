package com.morgana.server.worlds

import com.morgana.domain.sim.OptionalModule
import java.nio.file.Path

/**
 * What a new realm starts from. Every template is just existing content: the wizard never invents engine behaviour, it
 * only picks among things the engine already supports. Game templates under `games/<folder>/game.json` are discovered
 * automatically alongside the built-in templates.
 */
data class RealmTemplate(
    val id: String,
    val title: String,
    val description: String,
    val gameFolder: String? = null
) {
    companion object {
        val BLANK = RealmTemplate(
            "blank", "Blank Game",
            "A tiny custom game project (own rules file, one map, one class, one script) to build from scratch."
        )
        val PACK = RealmTemplate(
            "pack", "Import a World Pack",
            "Start from a world pack (.zip or folder) someone shared."
        )

        val entries: List<RealmTemplate> = listOf(BLANK, PACK)

        fun fromId(id: String, extra: Iterable<RealmTemplate> = emptyList()): RealmTemplate? =
            (entries + extra).firstOrNull { it.id == id || it.gameFolder == id }
    }
}

/**
 * Everything the New Realm Wizard collects. [disabledModules] become `server.json` switches (see
 * [OptionalModule]); [noCombat] sets `combat.module = "noop"` and hides combat/spell UI in `game.json`;
 * [modernSprites] configures taller `32 x 64` multi-state sprite geometry in `game.json`;
 * [enabledModules] re-enables systems the template switches off in its own `server.json`;
 * [pack] is required for [RealmTemplate.PACK] and ignored otherwise.
 */
data class RealmBlueprint(
    val name: String,
    val template: RealmTemplate = RealmTemplate.BLANK,
    val disabledModules: Set<OptionalModule> = emptySet(),
    val pack: Path? = null,
    val noCombat: Boolean = false,
    val modernSprites: Boolean = false,
    /** Systems the template switches off that the creator wants on after all (a template's own switches are the starting point). */
    val enabledModules: Set<OptionalModule> = emptySet(),
    /** Optional HUD layout override (`classic` or `ring`, see [com.morgana.domain.game.UiLayout]). */
    val uiLayout: String? = null
) {
    /** Problems that stop the realm being created, empty when it is fine. Cheap and I/O free, so a UI can call it per keystroke. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("Give the realm a name.")
        if (name.trim().length > MAX_NAME) add("The name can be at most $MAX_NAME characters.")
        if (template == RealmTemplate.PACK && pack == null) add("Choose a world pack to import.")
    }

    companion object {
        const val MAX_NAME = 40
    }
}
