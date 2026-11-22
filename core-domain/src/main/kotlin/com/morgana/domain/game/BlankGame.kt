package com.morgana.domain.game

import com.morgana.domain.script.ScriptCapability

/**
 * The engine's neutral default game: four attributes, health and mana, simple linear formulas and no theme. It is what a data
 * directory without a `game.json` runs, what a new game project (`GameScaffolder` and the blank game template) starts from, and what a client
 * shows before the server has said which game it is. It is a starting point, not a ruleset anyone is expected to keep; real games
 * ship their own `game.json`. The engine's own rule tests that need a specific ruleset build it themselves.
 */
object BlankGame {
    const val ID = "blank"
    const val NAME = "Blank Game"

    val definition: GameDefinition = GameDefinition(
        id = ID,
        name = NAME,
        capabilities = ScriptCapability.ALL,
        attributes = listOf(
            AttributeDefinition(id = "str", name = "Strength", short = "STR", base = 10, floor = 1, allocatable = true),
            AttributeDefinition(id = "agi", name = "Agility", short = "AGI", base = 10, floor = 1, allocatable = true),
            AttributeDefinition(id = "int", name = "Intelligence", short = "INT", base = 10, floor = 1, allocatable = true),
            AttributeDefinition(id = "vit", name = "Vitality", short = "VIT", base = 10, floor = 1, allocatable = true)
        ),
        vitals = listOf(
            VitalDefinition(
                id = "hp", name = "Health", short = "HP", color = "#dc2626", role = VitalRole.LIFE,
                max = "max(10, 80 + level * 15 + vit * 10 + str * 2 + bonus)",
                regen = RegenDefinition(everyTicks = 40, amount = "max(1, vit / 4)")
            ),
            VitalDefinition(
                id = "mp", name = "Mana", short = "MP", color = "#2563eb",
                max = "max(0, 20 + level * 8 + int * 8 + bonus)",
                regen = RegenDefinition(everyTicks = 40, amount = "max(1, int / 4)")
            )
        ),
        progression = ProgressionDefinition(maxLevel = 50, xpExpr = "100 * level * level"),
        combat = CombatDefinition(
            melee = "max(1, a.str * 2 + weapon - d.vit)",
            ranged = "max(1, a.agi * 2 + weapon + ammo - d.agi)",
            spell = "raw = max(1, basePower + (scales ? a.int * 2 : 0)); heals ? raw : max(1, raw - d.int / 2)",
            critChanceBp = "clamp(a.agi * 50, 500, 5000)",
            dodgeChanceBp = "clamp(d.agi * 50, 0, 4000)"
        )
    )

    val COMPILED: CompiledGame by lazy { CompiledGame.compile(definition) }
}
