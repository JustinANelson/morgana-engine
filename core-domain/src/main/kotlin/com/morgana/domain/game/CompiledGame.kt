package com.morgana.domain.game

/** A game definition that failed validation; [issues] lists every problem found. */
class GameDefinitionException(val issues: List<GameIssue>) : Exception(
    "Invalid game definition:" + issues.joinToString(separator = "") { "\n  $it" }
)

/**
 * A validated [GameDefinition] with its formulas parsed once, ready to be called by the rules. Everything is integer arithmetic and
 * pure, so the same inputs always give the same result.
 *
 * Attribute maps hold the **effective** values (base plus equipment plus status modifiers, already clamped to each attribute's floor
 * and cap by the caller); every declared attribute id must be present.
 */
class CompiledGame private constructor(
    val definition: GameDefinition,
    private val maxFormulas: Map<String, Formula>,
    private val regenFormulas: Map<String, Formula>,
    private val xpFormula: Formula?,
    private val melee: Formula,
    private val ranged: Formula,
    private val spell: Formula,
    private val critChance: Formula,
    private val dodgeChance: Formula
) {
    /** Id of the vital whose reaching zero kills (exactly one exists). */
    val lifeVitalId: String = definition.vitals.single { it.role == VitalRole.LIFE }.id

    val attributeIds: List<String> = definition.attributes.map { it.id }
    val vitalIds: List<String> = definition.vitals.map { it.id }

    private val attributesById = definition.attributes.associateBy { it.id }

    fun attribute(id: String): AttributeDefinition = attributesById.getValue(id)

    /** Clamps a raw effective value to the attribute's floor and cap. */
    fun clampAttribute(id: String, value: Long): Int {
        val a = attribute(id)
        return value.coerceIn(a.floor.toLong(), a.effectiveCap.toLong()).toInt()
    }

    /** Maximum of a vital for a character of [level]; [bonus] is the sum of equipment max-bonuses for that vital. */
    fun vitalMax(vitalId: String, level: Int, attributes: Map<String, Int>, bonus: Long = 0): Long {
        val formula = maxFormulas[vitalId] ?: throw IllegalArgumentException("unknown vital '$vitalId'")
        return formula.evaluate { name ->
            when (name) {
                "level" -> level.toLong()
                "bonus" -> bonus
                else -> attributes[name]?.toLong()
            }
        }
    }

    /** Ticks between regeneration steps for a vital, or null when it does not regenerate. */
    fun regenEveryTicks(vitalId: String): Int? = definition.vitals.first { it.id == vitalId }.regen?.everyTicks

    /** How much a vital regenerates per step, or null when it does not regenerate. */
    fun regenAmount(vitalId: String, level: Int, attributes: Map<String, Int>): Long? {
        val formula = regenFormulas[vitalId] ?: return null
        return formula.evaluate { name -> if (name == "level") level.toLong() else attributes[name]?.toLong() }
    }

    /** Experience needed to go from [level] to the next level, or null at the maximum level (or below 1). */
    fun xpToNext(level: Int): Long? {
        val p = definition.progression
        if (level < 1 || level >= p.maxLevel) return null
        p.xpTable?.let { return it[level - 1] }
        return xpFormula!!.evaluate { name -> if (name == "level") level.toLong() else null }
    }

    /**
     * Experience for the character's current bar: the next level's cost, or at the maximum level the next paragon
     * level's cost when the game has a paragon track (null when there is nothing left to earn).
     */
    fun xpToNext(character: com.morgana.domain.entity.CharacterState): Long? {
        xpToNext(character.level)?.let { return it }
        val paragon = definition.paragon?.takeIf { it.enabled } ?: return null
        if (character.level < definition.progression.maxLevel) return null
        val tier = paragon.level(character)
        return if (tier >= paragon.maxLevels) null else paragon.costToNext(tier)
    }

    fun meleeDamage(attacker: Map<String, Int>, defender: Map<String, Int>, weapon: Long, armor: Long, crit: Boolean): Long =
        melee.evaluate(combatEnv(attacker, defender, "weapon" to weapon, "armor" to armor, "crit" to flag(crit)))

    fun rangedDamage(attacker: Map<String, Int>, defender: Map<String, Int>, weapon: Long, ammo: Long, armor: Long, crit: Boolean): Long =
        ranged.evaluate(combatEnv(attacker, defender, "weapon" to weapon, "ammo" to ammo, "armor" to armor, "crit" to flag(crit)))

    fun spellOutput(caster: Map<String, Int>, target: Map<String, Int>, basePower: Long, scales: Boolean, heals: Boolean): Long =
        spell.evaluate(combatEnv(caster, target, "basePower" to basePower, "scales" to flag(scales), "heals" to flag(heals)))

    /** Critical hit chance in basis points (100 = 1.00%). */
    fun critChanceBp(attacker: Map<String, Int>): Long = critChance.evaluate(combatEnv(attacker, null))

    /** Chance for the defender to dodge, in basis points. */
    fun dodgeChanceBp(attacker: Map<String, Int>, defender: Map<String, Int>): Long = dodgeChance.evaluate(combatEnv(attacker, defender))

    private fun flag(value: Boolean): Long = if (value) 1L else 0L

    private fun combatEnv(attacker: Map<String, Int>, defender: Map<String, Int>?, vararg extras: Pair<String, Long>): (String) -> Long? {
        val extra = extras.toMap()
        return { name ->
            when {
                name.startsWith("a.") -> attacker[name.substring(2)]?.toLong()
                name.startsWith("d.") -> defender?.get(name.substring(2))?.toLong()
                else -> extra[name]
            }
        }
    }

    companion object {
        /** Validates and compiles. Throws [GameDefinitionException] listing every problem when the definition is not valid. */
        fun compile(definition: GameDefinition): CompiledGame {
            val issues = GameDefinitionValidator.validate(definition)
            if (issues.isNotEmpty()) throw GameDefinitionException(issues)
            return CompiledGame(
                definition = definition,
                maxFormulas = definition.vitals.associate { it.id to Formula.parse(it.max) },
                regenFormulas = definition.vitals.mapNotNull { v -> v.regen?.let { v.id to Formula.parse(it.amount) } }.toMap(),
                xpFormula = definition.progression.xpExpr?.let { Formula.parse(it) },
                melee = Formula.parse(definition.combat.melee),
                ranged = Formula.parse(definition.combat.ranged),
                spell = Formula.parse(definition.combat.spell),
                critChance = Formula.parse(definition.combat.critChanceBp),
                dodgeChance = Formula.parse(definition.combat.dodgeChanceBp)
            )
        }
    }
}
