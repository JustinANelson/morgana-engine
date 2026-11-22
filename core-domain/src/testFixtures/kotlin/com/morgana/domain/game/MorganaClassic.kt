package com.morgana.domain.game

/**
 * Test fixture: the rules the engine had hard-wired before stats became data (five attributes, three vitals, the `100 * level^1.65`
 * XP curve). It is not part of the engine and no shipped code may use it; tests that need a rich, known ruleset do.
 * `MorganaClassicOracleTest` checks every formula and XP value against the original code, so this must keep producing
 * exactly those numbers.
 */
object MorganaClassic {
    /** Experience to leave level 1..99: `round(100 * level^1.65)`, written out because rules have no floating point. */
    private val XP_TABLE: List<Long> = listOf(
        100L, 314L, 613L, 985L, 1423L, 1923L, 2480L, 3091L, 3754L,
        4467L, 5228L, 6035L, 6887L, 7782L, 8721L, 9701L, 10721L, 11781L,
        12881L, 14018L, 15194L, 16406L, 17654L, 18939L, 20258L, 21613L, 23001L,
        24424L, 25880L, 27368L, 28890L, 30444L, 32029L, 33647L, 35295L, 36974L,
        38684L, 40424L, 42195L, 43995L, 45824L, 47683L, 49570L, 51487L, 53432L,
        55405L, 57407L, 59436L, 61493L, 63577L, 65689L, 67827L, 69993L, 72185L,
        74404L, 76650L, 78921L, 81219L, 83542L, 85891L, 88266L, 90666L, 93092L,
        95543L, 98018L, 100519L, 103044L, 105594L, 108169L, 110767L, 113390L, 116038L,
        118709L, 121404L, 124123L, 126865L, 129631L, 132421L, 135234L, 138070L, 140929L,
        143811L, 146716L, 149645L, 152595L, 155569L, 158565L, 161583L, 164624L, 167687L,
        170773L, 173880L, 177010L, 180161L, 183334L, 186530L, 189746L, 192985L, 196245L
    )

    val definition: GameDefinition = GameDefinition(
        id = "morgana-classic",
        name = "Morgana Classic",
        attributes = listOf(
            AttributeDefinition("str", "Strength", "STR", base = 5, floor = 1, allocatable = true),
            AttributeDefinition("def", "Defense", "DEF", base = 5, floor = 1, allocatable = true),
            AttributeDefinition("int", "Intelligence", "INT", base = 5, floor = 1, allocatable = true),
            AttributeDefinition("agi", "Agility", "AGI", base = 5, floor = 1, allocatable = true),
            AttributeDefinition("vit", "Vitality", "VIT", base = 5, floor = 1, allocatable = true)
        ),
        vitals = listOf(
            VitalDefinition(
                id = "hp", name = "Health", short = "HP", color = "#dc2626", role = VitalRole.LIFE,
                max = "max(10, 80 + level*20 + vit*12 + str*3 + bonus)",
                regen = RegenDefinition(everyTicks = 40, amount = "max(1, vit/3)")
            ),
            VitalDefinition(
                id = "mp", name = "Mana", short = "MP", color = "#2563eb",
                max = "max(0, 30 + level*10 + int*10 + bonus)",
                regen = RegenDefinition(everyTicks = 40, amount = "max(1, int/3)")
            ),
            VitalDefinition(
                id = "sp", name = "Stamina", short = "SP", color = "#16a34a",
                max = "max(10, 80 + level*5 + agi*6 + vit*4 + bonus)",
                regen = RegenDefinition(everyTicks = 40, amount = "max(2, agi/2)")
            )
        ),
        progression = ProgressionDefinition(maxLevel = 100, xpTable = XP_TABLE),
        combat = CombatDefinition(
            melee = "raw = a.str*2 + weapon + a.agi/3; mit = d.def + armor/2 + d.vit/4; base = max(1, raw - mit); " +
                "crit ? max(2, (3*base + 1)/2) : base",
            ranged = "raw = a.agi*2 + a.str/2 + weapon + ammo; mit = d.def + armor/2; base = max(1, raw - mit); " +
                "crit ? max(2, (3*base + 1)/2) : base",
            spell = "raw = max(1, basePower + (scales ? a.int*2 + a.vit/4 : 0)); heals ? raw : max(1, raw - (d.int/2 + d.def/2))",
            critChanceBp = "clamp(250 + a.agi*35, 100, 5000)",
            dodgeChanceBp = "clamp(300 + (d.agi - a.agi)*30, 100, 4000)"
        )
    )

    val COMPILED: CompiledGame by lazy { CompiledGame.compile(definition) }
}
