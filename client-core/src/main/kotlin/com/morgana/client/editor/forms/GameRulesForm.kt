package com.morgana.client.editor.forms

import com.morgana.domain.game.AttributeDefinition
import com.morgana.domain.game.BestiaryDefinition
import com.morgana.domain.game.BoonDefinition
import com.morgana.domain.game.SkillNodeDefinition
import com.morgana.domain.game.SkillTreeDefinition
import com.morgana.domain.game.CombatDefinition
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.EnemyVariantDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.PermadeathMode
import com.morgana.domain.game.RunRulesDefinition
import com.morgana.domain.game.RunStateMode
import com.morgana.domain.game.JumpDefinition
import com.morgana.domain.game.ParagonDefinition
import com.morgana.domain.game.ProgressionDefinition
import com.morgana.domain.game.RegenDefinition
import com.morgana.domain.game.RestedDefinition
import com.morgana.domain.game.SprintDefinition
import com.morgana.domain.game.UiProfile
import com.morgana.domain.game.VitalDefinition
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.AffixDefinition
import com.morgana.domain.item.AffixPosition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.onboarding.OnboardingConfig
import com.morgana.domain.onboarding.TipDefinition
import com.morgana.domain.onboarding.TipTrigger

/**
 * The game's rules (`game.json`) as plain form data, for the F1 "Game rules" panel: identity, attributes, vitals, progression,
 * combat formulas, the HUD profile, the optional systems a roguelike uses (sprint, jump, ghost, bestiary, paragon, durability,
 * rested XP), loot rarities and affixes, and the new-player tips. Sections the form does not edit (guild war, nobility, arena,
 * promotion, ...) are left exactly as they are.
 *
 * Values are text keyed by field. Tables are one row per line with `|` between the columns, so names may contain spaces. Every
 * optional system has an `<name>.on` tick box: unticked removes the section from `game.json`.
 */
object GameRulesForm {

    private val GENERAL = listOf(
        FormField("name", "Game name", FormFieldType.TEXT, required = true),
        FormField("description", "Description", FormFieldType.TEXT),
        FormField("tags", "Tags", FormFieldType.TEXT, hint = "Comma-separated, for the realm directory (roguelike, co-op)."),
        FormField("inventorySlots", "Bag slots", FormFieldType.INT, "30", min = 8, max = 200)
    )

    private val STATS = listOf(
        FormField("attributes", "Attributes", FormFieldType.LINES, hint = "One per line: id | name | short | base | floor | cap or - | allocatable (yes/no). Example: str | Strength | STR | 10 | 1 | - | yes"),
        FormField("vitals", "Vitals", FormFieldType.LINES, hint = "One per line: id | name | short | #color | life or - | max formula | regen every (ticks) | regen amount formula. Exactly one vital is life. Example: hp | Health | HP | #dc2626 | life | 80 + level * 15 + vit * 10 + bonus | 40 | max(1, vit / 4)"),
        FormField("maxLevel", "Level cap", FormFieldType.INT, "50", min = 1, max = 1000),
        FormField("xpExpr", "XP to the next level (formula)", FormFieldType.TEXT, hint = "Uses level. Example: 100 * level * level. Leave empty to use the table below."),
        FormField("xpTable", "XP table (optional)", FormFieldType.TEXT, hint = "Comma-separated XP per level, instead of a formula.")
    )

    private val COMBAT = listOf(
        FormField("melee", "Melee damage formula", FormFieldType.TEXT, "1", required = true, hint = "a = attacker, d = defender; also weapon, armor, level. Example: max(1, a.str * 2 + weapon - d.vit)"),
        FormField("ranged", "Ranged damage formula", FormFieldType.TEXT, "1", required = true),
        FormField("spell", "Spell damage formula", FormFieldType.TEXT, "1", required = true, hint = "Also basePower, scales, heals."),
        FormField("critBp", "Crit chance (formula, out of 10000)", FormFieldType.TEXT, "0", required = true),
        FormField("dodgeBp", "Dodge chance (formula, out of 10000)", FormFieldType.TEXT, "0", required = true),
        FormField("module", "Combat module", FormFieldType.CHOICE, "realtime", listOf("realtime", "action", "noop", "lua_combat")),
        FormField("attackSpeed", "Default attack delay (ticks)", FormFieldType.INT, "12", min = 1),
        FormField("npcRespawn", "Default monster respawn (ticks)", FormFieldType.INT, "100", min = 0),
        FormField("lootProtect", "Drop owner protection (ticks)", FormFieldType.INT, "600", min = 0),
        FormField("groundDespawn", "Dropped items vanish after (ticks)", FormFieldType.INT, "2400", min = 20),
        FormField("partyXp", "Party XP bonus (%)", FormFieldType.INT, "20", min = 0, max = 1000),
        FormField("levelScaling", "Scale XP by level difference", FormFieldType.BOOL, "false"),
        FormField("variants", "Elite and rare monsters", FormFieldType.LINES, hint = "One per line: id | label | spawn chance (out of 10000) | always hunts you (yes/no) | life % | damage % | speed % (below 100 is faster) | XP % | drops % | bonus drop rolls | levels above normal | #color. Example: champion | Champion | 600 | yes | 220 | 135 | 95 | 250 | 500 | 2 | 3 | #c084fc")
    )

    private val UI = listOf(
        FormField("ui.combat", "Attack button and empty hotbar slots", FormFieldType.BOOL, "true"),
        FormField("ui.spells", "Spells button and window", FormFieldType.BOOL, "true"),
        FormField("ui.hotbar", "Quickbar", FormFieldType.BOOL, "true"),
        FormField("ui.minimap", "Minimap", FormFieldType.BOOL, "true"),
        FormField("ui.chat", "Chat", FormFieldType.BOOL, "true"),
        FormField("ui.party", "Party button and window", FormFieldType.BOOL, "true"),
        FormField("ui.xpOrbs", "XP orbs fly from slain enemies", FormFieldType.BOOL, "false"),
        FormField("ui.layout", "Action button layout", FormFieldType.CHOICE, "classic", com.morgana.domain.game.UiLayout.ALL,
            hint = "ring: a large Attack button with four skills, a potion and Dodge around it, on desktop and phones alike.")
    )

    private val SYSTEMS = listOf(
        FormField("sprint.on", "Sprint", FormFieldType.BOOL, "false", hint = "A faster step that drains a stamina vital."),
        FormField("sprint.speed", "Sprint speed (%)", FormFieldType.INT, "150", min = 100, max = 500),
        FormField("sprint.vital", "Sprint stamina vital id", FormFieldType.TEXT, "stamina"),
        FormField("sprint.steps", "Steps per stamina point", FormFieldType.INT, "2", min = 1),
        FormField("jump.on", "Jump (dodge)", FormFieldType.BOOL, "false", hint = "Leap a few tiles, over obstacles or out of a boss's marked ground."),
        FormField("jump.range", "Jump range (tiles)", FormFieldType.INT, "3", min = 1, max = 30),
        FormField("jump.cost", "Jump stamina cost", FormFieldType.INT, "15", min = 0),
        FormField("jump.vital", "Jump stamina vital id", FormFieldType.TEXT, "stamina"),
        FormField("jump.cooldown", "Jump cooldown (ticks)", FormFieldType.INT, "12", min = 0),
        FormField("ghost.on", "Ghost death (downed and revive)", FormFieldType.BOOL, "false", hint = "A dead player lies where they fell until a revive spell, /revive or going home."),
        FormField("ghost.release", "Sent home after (ticks, 0 = never)", FormFieldType.INT, "1200", min = 0),
        FormField("ghost.selfAfter", "/revive allowed after (ticks, 0 = off)", FormFieldType.INT, "0", min = 0),
        FormField("ghost.selfPercent", "/revive restores (% life)", FormFieldType.INT, "30", min = 1, max = 100),
        FormField("ghost.wear", "Gear wear on death (durability points)", FormFieldType.INT, "0", min = 0),
        FormField("ghost.wipe", "A wiped party is sent home (dungeon floors)", FormFieldType.BOOL, "false", hint = "When everyone on a generated floor is down, the run ends for them at once."),
        FormField("bestiary.on", "Bestiary", FormFieldType.BOOL, "false"),
        FormField("bestiary.stats", "Stats shown after (kills)", FormFieldType.INT, "5", min = 0),
        FormField("bestiary.drops", "Drops shown after (kills)", FormFieldType.INT, "10", min = 0),
        FormField("paragon.on", "Paragon levels after the cap", FormFieldType.BOOL, "false"),
        FormField("paragon.variable", "Paragon player variable", FormFieldType.TEXT, "paragon"),
        FormField("paragon.base", "XP for the first paragon level", FormFieldType.LONG, "100000", min = 1),
        FormField("paragon.step", "Extra XP per further level", FormFieldType.LONG, "10000", min = 0),
        FormField("paragon.points", "Attribute points per level", FormFieldType.INT, "1", min = 0),
        FormField("paragon.max", "Highest paragon level", FormFieldType.INT, "500", min = 1),
        FormField("durability.on", "Gear wear and repair", FormFieldType.BOOL, "false"),
        FormField("durability.weapon", "Weapon wear chance (%)", FormFieldType.INT, "10", min = 0, max = 100),
        FormField("durability.armor", "Armour wear chance (%)", FormFieldType.INT, "10", min = 0, max = 100),
        FormField("durability.perHit", "Wear per hit", FormFieldType.INT, "1", min = 1),
        FormField("durability.repair", "Repair price (% of item price per point)", FormFieldType.INT, "20", min = 0),
        FormField("rested.on", "Rested XP", FormFieldType.BOOL, "false"),
        FormField("rested.pool", "Rested pool per offline hour (%)", FormFieldType.INT, "10", min = 0),
        FormField("rested.cap", "Rested pool cap (%)", FormFieldType.INT, "150", min = 0),
        FormField("rested.bonus", "Rested bonus (%)", FormFieldType.INT, "100", min = 0),
        FormField("rested.below", "Catch-up below level", FormFieldType.INT, "0", min = 0),
        FormField("rested.catchUp", "Catch-up bonus (%)", FormFieldType.INT, "0", min = 0)
    )

    private val LOOT = listOf(
        FormField("loot.on", "Loot rarities and affixes", FormFieldType.BOOL, "false", hint = "Dropped gear rolls a rarity and affixes (Diablo-style)."),
        FormField("rarities", "Rarities", FormFieldType.LINES, hint = "Lowest first, one per line: id | name | #color | weight | min item level | affixes min | affixes max | price % | socket chance (out of 10000) | unlabeled (yes/no). Example: magic | Magic | #60a5fa | 300 | 1 | 1 | 2 | 250 | 0 | no"),
        FormField("affixes", "Affixes", FormFieldType.LINES, hint = "One per line: id | name | PREFIX or SUFFIX | ATTRIBUTE, VITAL_MAX, ATTACK_DAMAGE or ARMOR_DEFENSE | attribute or vital id (empty for damage and armour) | min | max | weight | min item level | max item level | slots (comma-separated, empty for any). Example: sharp | Sharp | PREFIX | ATTACK_DAMAGE | | 1 | 3 | 100 | 1 | 1000 | WEAPON")
    )

    private val RUNS = listOf(
        FormField("runs.on", "Dungeon run lifecycle", FormFieldType.BOOL, "false", hint = "Track runs on generated floors with timers, custom /seed, extraction/ephemeral state, and optional permadeath."),
        FormField("runs.stateMode", "Run state mode", FormFieldType.CHOICE, RunStateMode.PERSISTENT.name, RunStateMode.entries.map { it.name }),
        FormField("runs.permadeath", "Permadeath on death", FormFieldType.CHOICE, PermadeathMode.OFF.name, PermadeathMode.entries.map { it.name }),
        FormField("runs.timeLimit", "Run time limit (ticks, 0 = unlimited)", FormFieldType.INT, "0", min = 0, max = 1_000_000),
        FormField("runs.customSeed", "Allow /seed custom run seeds", FormFieldType.BOOL, "true"),
        FormField("runs.wipe", "Wiping ends the run", FormFieldType.BOOL, "true"),
        FormField("runs.metaItems", "Meta-currency item ids (kept in EPHEMERAL runs)", FormFieldType.ID_LIST, hint = "Comma-separated item ids preserved when an ephemeral run ends.")
    )

    private val BOONS_AND_SKILLS = listOf(
        FormField("boons", "Boons (roguelike upgrades)", FormFieldType.LINES, hint = "One per line: id | name | maxStacks | runOnly (yes/no) | weight | atkBonus | armBonus | critBp | leech% | description. Example: fury | Blood Fury | 2 | yes | 100 | 5 | 0 | 200 | 10 | Extra damage and leech"),
        FormField("skillTree.on", "Skill tree", FormFieldType.BOOL, "false", hint = "Per-class skill/talent nodes learned with /skills."),
        FormField("skillTree.points", "Skill points per level", FormFieldType.INT, "1", min = 0, max = 20),
        FormField("skillTree.nodes", "Skill tree nodes", FormFieldType.LINES, hint = "One per line: id | name | classIds (comma or empty) | maxRank | minLevel | requires (comma or empty) | grantsSpellId | atkBonus | armBonus | critBp | description")
    )

    private val TIPS = listOf(
        FormField("tips.defaults", "Include the engine's built-in tips", FormFieldType.BOOL, "true"),
        FormField("tips", "Your tips", FormFieldType.LINES, hint = "One per line: id | WELCOME, EQUIPMENT_FOUND, STAT_POINTS, SKILL_AVAILABLE, GEAR_WORN, GEAR_BROKEN, LOW_HEALTH, DEATH or LEVEL | level (0 unless LEVEL) | title | text. A tip with a built-in tip's id replaces it; use {key:INVENTORY} for a player's key.")
    )

    fun sections(): List<FormSection> = listOf(
        FormSection("Game", GENERAL), FormSection("Attributes, vitals and levels", STATS), FormSection("Combat", COMBAT),
        FormSection("HUD", UI), FormSection("Movement, death and progression systems", SYSTEMS),
        FormSection("Dungeon runs", RUNS),
        FormSection("Boons and skill tree", BOONS_AND_SKILLS),
        FormSection("Loot", LOOT), FormSection("New-player tips", TIPS)
    )

    private val allFields get() = sections().flatMap { it.fields }

    // ---- definition -> values ----

    fun valuesOf(d: GameDefinition): Map<String, String> {
        val ui = d.ui ?: UiProfile()
        val sp = d.sprint ?: SprintDefinition(); val ju = d.jump ?: JumpDefinition(); val gh = d.ghost ?: GhostDefinition()
        val be = d.bestiary ?: BestiaryDefinition(); val pa = d.paragon ?: ParagonDefinition(); val du = d.durability ?: DurabilityDefinition()
        val re = d.rested ?: RestedDefinition(); val ru = d.runs ?: RunRulesDefinition(); val st = d.skillTree ?: SkillTreeDefinition(); val c = d.combat
        val loot = d.loot ?: LootRules()
        val tips = d.onboarding ?: OnboardingConfig()
        return mapOf(
            "name" to d.name, "description" to d.description, "tags" to d.tags.joinToString(", "), "inventorySlots" to d.inventorySlots.toString(),
            "attributes" to d.attributes.joinToString("\n") { a ->
                cols(a.id, a.name, a.short, a.base, a.floor, a.cap?.toString() ?: "-", if (a.allocatable) "yes" else "no")
            },
            "vitals" to d.vitals.joinToString("\n") { v ->
                cols(v.id, v.name, v.short, v.color, if (v.role == VitalRole.LIFE) "life" else "-", v.max, v.regen?.everyTicks ?: "", v.regen?.amount ?: "")
            },
            "maxLevel" to d.progression.maxLevel.toString(), "xpExpr" to d.progression.xpExpr.orEmpty(),
            "xpTable" to d.progression.xpTable?.joinToString(", ").orEmpty(),
            "melee" to c.melee, "ranged" to c.ranged, "spell" to c.spell, "critBp" to c.critChanceBp, "dodgeBp" to c.dodgeChanceBp,
            "module" to c.module, "attackSpeed" to c.defaultAttackSpeedTicks.toString(), "npcRespawn" to c.defaultNpcRespawnTicks.toString(),
            "lootProtect" to c.lootOwnerProtectionTicks.toString(), "groundDespawn" to c.groundItemDespawnTicks.toString(),
            "partyXp" to c.partyXpBonusPercent.toString(), "levelScaling" to c.levelOffsetXpScaling.toString(),
            "variants" to c.enemyVariants.joinToString("\n") { v -> cols(v.id, v.label, v.spawnBasisPoints, if (v.forceAggro) "yes" else "no", v.hpPercent, v.attackPercent, v.moveSpeedPercent, v.xpPercent, v.dropRatePercent, v.bonusDropRolls, v.levelBonus, "#" + v.colorHex) },
            "ui.combat" to ui.combat.toString(), "ui.spells" to ui.spells.toString(), "ui.hotbar" to ui.hotbar.toString(),
            "ui.minimap" to ui.minimap.toString(), "ui.chat" to ui.chat.toString(), "ui.party" to ui.party.toString(), "ui.xpOrbs" to ui.xpOrbs.toString(), "ui.layout" to ui.layout,
            "sprint.on" to on(d.sprint?.enabled), "sprint.speed" to sp.speedPercent.toString(), "sprint.vital" to sp.staminaVitalId, "sprint.steps" to sp.stepsPerStamina.toString(),
            "jump.on" to on(d.jump?.enabled), "jump.range" to ju.maxRangeTiles.toString(), "jump.cost" to ju.staminaCost.toString(),
            "jump.vital" to ju.staminaVitalId, "jump.cooldown" to ju.cooldownTicks.toString(),
            "ghost.on" to on(d.ghost?.enabled), "ghost.release" to gh.autoReleaseTicks.toString(), "ghost.selfAfter" to gh.selfReviveAfterTicks.toString(),
            "ghost.selfPercent" to gh.selfRevivePercent.toString(), "ghost.wear" to gh.deathWearPoints.toString(), "ghost.wipe" to gh.wipeEndsRun.toString(),
            "bestiary.on" to on(d.bestiary?.enabled), "bestiary.stats" to be.statsAfterKills.toString(), "bestiary.drops" to be.dropsAfterKills.toString(),
            "paragon.on" to on(d.paragon?.enabled), "paragon.variable" to pa.variable, "paragon.base" to pa.xpBase.toString(),
            "paragon.step" to pa.xpStep.toString(), "paragon.points" to pa.statPointsPerLevel.toString(), "paragon.max" to pa.maxLevels.toString(),
            "durability.on" to on(d.durability?.enabled), "durability.weapon" to du.weaponWearChancePercent.toString(),
            "durability.armor" to du.armorWearChancePercent.toString(), "durability.perHit" to du.wearPerHit.toString(), "durability.repair" to du.repairPricePercent.toString(),
            "rested.on" to on(d.rested?.enabled), "rested.pool" to re.poolPercentPerOfflineHour.toString(), "rested.cap" to re.poolCapPercent.toString(),
            "rested.bonus" to re.bonusPercent.toString(), "rested.below" to re.catchUpBelowLevel.toString(), "rested.catchUp" to re.catchUpPercent.toString(),
            "runs.on" to on(d.runs?.enabled), "runs.stateMode" to ru.stateMode.name, "runs.permadeath" to ru.permadeath.name,
            "runs.timeLimit" to ru.timeLimitTicks.toString(), "runs.customSeed" to ru.allowCustomSeed.toString(),
            "runs.wipe" to ru.wipeEndsRun.toString(), "runs.metaItems" to ru.metaCurrencyItemIds.sorted().joinToString(", "),
            "boons" to d.boons.joinToString("\n") { b ->
                cols(b.id, b.name, b.maxStacks, if (b.runOnly) "yes" else "no", b.weight, b.attackDamageBonus, b.armorDefenseBonus, b.critChanceBpBonus, b.lifeLeechPercentBonus, b.description)
            },
            "skillTree.on" to on(d.skillTree?.enabled),
            "skillTree.points" to st.pointsPerLevel.toString(),
            "skillTree.nodes" to st.nodes.joinToString("\n") { n ->
                cols(n.id, n.name, n.classIds.sorted().joinToString(","), n.maxRank, n.minLevel, n.requires.sorted().joinToString(","), n.grantsSpellId, n.attackDamageBonus, n.armorDefenseBonus, n.critChanceBpBonus, n.description)
            },
            "loot.on" to (d.loot != null).toString(),
            "rarities" to loot.rarities.joinToString("\n") { r ->
                cols(r.id, r.name, r.color, r.weight, r.minItemLevel, r.affixesMin, r.affixesMax, r.priceMultiplierPercent, r.socketChanceBasisPoints, if (r.unlabeled) "yes" else "no")
            },
            "affixes" to loot.affixes.joinToString("\n") { a ->
                cols(a.id, a.name, a.position.name, a.stat.name, a.statId, a.min, a.max, a.weight, a.minItemLevel, a.maxItemLevel, a.slots.joinToString(",") { it.id })
            },
            "tips.defaults" to tips.defaults.toString(),
            "tips" to tips.tips.joinToString("\n") { t -> cols(t.id, t.trigger.name, t.level, t.title, t.text) }
        )
    }

    // ---- values -> definition ----

    /** [base] with every edited section replaced by what was typed; a bad value keeps the base's setting. */
    fun apply(base: GameDefinition, v: Map<String, String>): GameDefinition = build(base, v).first

    /** What is wrong with [v] for [base]: bad lines and values, then the game definition rules (`GameDefinitionValidator`). */
    fun problems(base: GameDefinition, v: Map<String, String>): List<String> {
        val (def, parseProblems) = build(base, v)
        val fieldProblems = FormRules.fieldProblems(allFields.filter { it.type != FormFieldType.LINES && v.containsKey(it.key) }, v)
        return (fieldProblems + parseProblems + GameDefinitionValidator.validate(def).map { it.toString() }).distinct()
    }

    private fun build(base: GameDefinition, v: Map<String, String>): Pair<GameDefinition, List<String>> {
        val problems = ArrayList<String>()
        fun text(k: String, fb: String) = v[k]?.trim() ?: fb
        fun int(k: String, fb: Int) = v[k]?.trim()?.toIntOrNull() ?: fb
        fun long(k: String, fb: Long) = v[k]?.trim()?.toLongOrNull() ?: fb
        fun bool(k: String, fb: Boolean) = v[k]?.let { it == "true" } ?: fb
        fun on(k: String) = v[k] == "true"

        val attributes = v["attributes"]?.let { parseAttributes(it, problems) } ?: base.attributes
        val vitals = v["vitals"]?.let { parseVitals(it, problems) } ?: base.vitals
        val table = v["xpTable"]?.trim().orEmpty()
        val xpTable = if (table.isEmpty()) null else table.split(',').map { it.trim().toLongOrNull() ?: run { problems += "XP table: '${it.trim()}' is not a whole number"; 0L } }
        val expr = v["xpExpr"]?.trim().orEmpty().ifEmpty { null }
        val b = base.combat
        val u = base.ui ?: UiProfile()
        val sp = base.sprint ?: SprintDefinition(); val ju = base.jump ?: JumpDefinition(); val gh = base.ghost ?: GhostDefinition()
        val be = base.bestiary ?: BestiaryDefinition(); val pa = base.paragon ?: ParagonDefinition(); val du = base.durability ?: DurabilityDefinition()
        val re = base.rested ?: RestedDefinition(); val ru = base.runs ?: RunRulesDefinition(); val st = base.skillTree ?: SkillTreeDefinition()
        val boons = v["boons"]?.let { parseBoons(it, problems) } ?: base.boons
        val skillNodes = v["skillTree.nodes"]?.let { parseSkillNodes(it, problems) } ?: st.nodes
        val rarities = v["rarities"]?.let { parseRarities(it, problems) } ?: base.loot?.rarities.orEmpty()
        val affixes = v["affixes"]?.let { parseAffixes(it, problems) } ?: base.loot?.affixes.orEmpty()
        val tips = v["tips"]?.let { parseTips(it, problems) } ?: base.onboarding?.tips.orEmpty()

        val def = base.copy(
            name = text("name", base.name).ifBlank { base.name },
            description = text("description", base.description),
            tags = v["tags"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: base.tags,
            inventorySlots = int("inventorySlots", base.inventorySlots),
            attributes = attributes, vitals = vitals,
            progression = ProgressionDefinition(int("maxLevel", base.progression.maxLevel), xpTable, expr),
            combat = b.copy(
                melee = text("melee", b.melee), ranged = text("ranged", b.ranged), spell = text("spell", b.spell),
                critChanceBp = text("critBp", b.critChanceBp), dodgeChanceBp = text("dodgeBp", b.dodgeChanceBp), module = text("module", b.module),
                defaultAttackSpeedTicks = int("attackSpeed", b.defaultAttackSpeedTicks), defaultNpcRespawnTicks = int("npcRespawn", b.defaultNpcRespawnTicks),
                lootOwnerProtectionTicks = int("lootProtect", b.lootOwnerProtectionTicks), groundItemDespawnTicks = int("groundDespawn", b.groundItemDespawnTicks),
                partyXpBonusPercent = int("partyXp", b.partyXpBonusPercent), levelOffsetXpScaling = bool("levelScaling", b.levelOffsetXpScaling),
                enemyVariants = v["variants"]?.let { parseVariants(it, problems) } ?: b.enemyVariants
            ),
            ui = UiProfile(bool("ui.combat", u.combat), bool("ui.spells", u.spells), bool("ui.hotbar", u.hotbar), bool("ui.minimap", u.minimap),
                bool("ui.chat", u.chat), bool("ui.party", u.party), bool("ui.xpOrbs", u.xpOrbs), v["ui.layout"] ?: u.layout).takeUnless { it == UiProfile() && base.ui == null } ?: base.ui,
            sprint = if (on("sprint.on")) sp.copy(enabled = true, speedPercent = int("sprint.speed", sp.speedPercent), staminaVitalId = text("sprint.vital", sp.staminaVitalId), stepsPerStamina = int("sprint.steps", sp.stepsPerStamina)) else base.sprint?.takeIf { !it.enabled },
            jump = if (on("jump.on")) ju.copy(enabled = true, maxRangeTiles = int("jump.range", ju.maxRangeTiles), staminaCost = int("jump.cost", ju.staminaCost), staminaVitalId = text("jump.vital", ju.staminaVitalId), cooldownTicks = int("jump.cooldown", ju.cooldownTicks)) else base.jump?.takeIf { !it.enabled },
            ghost = if (on("ghost.on")) gh.copy(enabled = true, autoReleaseTicks = int("ghost.release", gh.autoReleaseTicks), selfReviveAfterTicks = int("ghost.selfAfter", gh.selfReviveAfterTicks), selfRevivePercent = int("ghost.selfPercent", gh.selfRevivePercent), deathWearPoints = int("ghost.wear", gh.deathWearPoints), wipeEndsRun = bool("ghost.wipe", gh.wipeEndsRun)) else base.ghost?.takeIf { !it.enabled },
            bestiary = if (on("bestiary.on")) be.copy(enabled = true, statsAfterKills = int("bestiary.stats", be.statsAfterKills), dropsAfterKills = int("bestiary.drops", be.dropsAfterKills)) else base.bestiary?.takeIf { !it.enabled },
            paragon = if (on("paragon.on")) pa.copy(enabled = true, variable = text("paragon.variable", pa.variable), xpBase = long("paragon.base", pa.xpBase), xpStep = long("paragon.step", pa.xpStep), statPointsPerLevel = int("paragon.points", pa.statPointsPerLevel), maxLevels = int("paragon.max", pa.maxLevels)) else base.paragon?.takeIf { !it.enabled },
            durability = if (on("durability.on")) du.copy(enabled = true, weaponWearChancePercent = int("durability.weapon", du.weaponWearChancePercent), armorWearChancePercent = int("durability.armor", du.armorWearChancePercent), wearPerHit = int("durability.perHit", du.wearPerHit), repairPricePercent = int("durability.repair", du.repairPricePercent)) else base.durability?.takeIf { !it.enabled },
            rested = if (on("rested.on")) re.copy(enabled = true, poolPercentPerOfflineHour = int("rested.pool", re.poolPercentPerOfflineHour), poolCapPercent = int("rested.cap", re.poolCapPercent), bonusPercent = int("rested.bonus", re.bonusPercent), catchUpBelowLevel = int("rested.below", re.catchUpBelowLevel), catchUpPercent = int("rested.catchUp", re.catchUpPercent)) else base.rested?.takeIf { !it.enabled },
            runs = if (on("runs.on")) ru.copy(
                enabled = true,
                stateMode = RunStateMode.entries.firstOrNull { it.name == v["runs.stateMode"] } ?: ru.stateMode,
                permadeath = PermadeathMode.entries.firstOrNull { it.name == v["runs.permadeath"] } ?: ru.permadeath,
                timeLimitTicks = int("runs.timeLimit", ru.timeLimitTicks),
                allowCustomSeed = bool("runs.customSeed", ru.allowCustomSeed),
                wipeEndsRun = bool("runs.wipe", ru.wipeEndsRun),
                metaCurrencyItemIds = v["runs.metaItems"]?.let { FormRules.parseIds(it)?.toSet() } ?: ru.metaCurrencyItemIds
            ) else base.runs?.takeIf { !it.enabled },
            boons = boons,
            skillTree = if (on("skillTree.on")) st.copy(enabled = true, pointsPerLevel = int("skillTree.points", st.pointsPerLevel), nodes = skillNodes) else base.skillTree?.takeIf { !it.enabled },
            loot = if (on("loot.on")) LootRules(rarities, affixes) else null,
            onboarding = if (tips.isEmpty() && bool("tips.defaults", base.onboarding?.defaults ?: true) && base.onboarding == null) null
            else OnboardingConfig(base.onboarding?.enabled ?: true, bool("tips.defaults", base.onboarding?.defaults ?: true), tips)
        )
        return def to problems
    }

    // ---- tables ----

    private fun on(enabled: Boolean?) = (enabled == true).toString()

    private fun cols(vararg parts: Any) = parts.joinToString(" | ") { it.toString() }

    private fun rows(text: String): List<List<String>> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line -> line.split('|').map { it.trim() } }

    private fun yes(s: String) = s.equals("yes", true) || s.equals("true", true)

    private fun parseAttributes(text: String, problems: MutableList<String>): List<AttributeDefinition> = rows(text).mapIndexedNotNull { i, c ->
        val base = c.getOrNull(3)?.toIntOrNull(); val floor = c.getOrNull(4)?.toIntOrNull()
        val cap = c.getOrNull(5)?.let { if (it == "-" || it.isEmpty()) null else it.toIntOrNull() ?: Int.MIN_VALUE }
        if (c.size != 7 || c[0].isEmpty() || c[1].isEmpty() || base == null || floor == null || cap == Int.MIN_VALUE) {
            problems += "Attribute ${i + 1}: write 'id | name | short | base | floor | cap or - | yes/no'"; null
        } else AttributeDefinition(c[0], c[1], c[2], base, floor, cap, yes(c[6]))
    }

    private fun parseVitals(text: String, problems: MutableList<String>): List<VitalDefinition> = rows(text).mapIndexedNotNull { i, c ->
        val every = c.getOrNull(6)?.toIntOrNull()
        val hasRegen = !c.getOrNull(6).isNullOrEmpty() || !c.getOrNull(7).isNullOrEmpty()
        if (c.size != 8 || c[0].isEmpty() || c[1].isEmpty() || c[5].isEmpty() || (hasRegen && (every == null || c[7].isEmpty()))) {
            problems += "Vital ${i + 1}: write 'id | name | short | #color | life or - | max formula | regen ticks | regen amount' (the last two empty for no regeneration)"; null
        } else VitalDefinition(c[0], c[1], c[2], c[3], if (c[4].equals("life", true)) VitalRole.LIFE else null, c[5], if (hasRegen) RegenDefinition(every!!, c[7]) else null)
    }

    private fun parseVariants(text: String, problems: MutableList<String>): List<EnemyVariantDefinition> = rows(text).mapIndexedNotNull { i, c ->
        val n = listOf(2, 4, 5, 6, 7, 8, 9, 10).map { c.getOrNull(it)?.toIntOrNull() }
        val color = c.getOrNull(11)?.removePrefix("#").orEmpty()
        if (c.size != 12 || c[0].isEmpty() || c[1].isEmpty() || n.any { it == null } || !Regex("[0-9a-fA-F]{6}").matches(color)) {
            problems += "Elite monster ${i + 1}: write 'id | label | spawn chance | yes/no | life % | damage % | speed % | XP % | drops % | bonus rolls | levels | #color'"; null
        } else EnemyVariantDefinition(c[0], c[1], n[0]!!, yes(c[3]), n[1]!!, n[2]!!, n[3]!!, n[4]!!, n[5]!!, n[6]!!, n[7]!!, color)
    }

    private fun parseRarities(text: String, problems: MutableList<String>): List<RarityDefinition> = rows(text).mapIndexedNotNull { i, c ->
        val n = (3..8).map { c.getOrNull(it)?.toIntOrNull() }
        if (c.size != 10 || c[0].isEmpty() || c[1].isEmpty() || n.any { it == null }) {
            problems += "Rarity ${i + 1}: write 'id | name | #color | weight | min item level | affixes min | affixes max | price % | socket chance | yes/no'"; null
        } else RarityDefinition(c[0], c[1], c[2], n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!, yes(c[9]), n[5]!!)
    }

    private fun parseAffixes(text: String, problems: MutableList<String>): List<AffixDefinition> = rows(text).mapIndexedNotNull { i, c ->
        val position = AffixPosition.entries.firstOrNull { it.name == c.getOrNull(2) }
        val stat = AffixStat.entries.firstOrNull { it.name == c.getOrNull(3) }
        val n = (5..9).map { c.getOrNull(it)?.toIntOrNull() }
        if (c.size != 11 || c[0].isEmpty() || c[1].isEmpty() || position == null || stat == null || n.any { it == null }) {
            problems += "Affix ${i + 1}: write 'id | name | PREFIX/SUFFIX | ATTRIBUTE/VITAL_MAX/ATTACK_DAMAGE/ARMOR_DEFENSE | stat id | min | max | weight | min level | max level | slots'"; null
        } else AffixDefinition(c[0], c[1], position, stat, c[4], n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!, c[10].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { EquipmentSlot(it) }.toSet())
    }

    private fun parseTips(text: String, problems: MutableList<String>): List<TipDefinition> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexedNotNull { i, line ->
        val c = line.split('|', limit = 5).map { it.trim() }
        val trigger = TipTrigger.entries.firstOrNull { it.name == c.getOrNull(1) }
        val level = c.getOrNull(2)?.toIntOrNull()
        if (c.size != 5 || !TipDefinition.ID_PATTERN.matches(c[0]) || trigger == null || level == null) {
            problems += "Tip ${i + 1}: write 'id | TRIGGER | level | title | text' (id letters, digits and _)"; null
        } else TipDefinition(c[0], trigger, c[3], c[4], level)
    }

    private fun parseBoons(text: String, problems: MutableList<String>): List<BoonDefinition> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexedNotNull { i, line ->
        val c = line.split('|', limit = 10).map { it.trim() }
        val maxStacks = c.getOrNull(2)?.toIntOrNull()
        val weight = c.getOrNull(4)?.toIntOrNull()
        val atk = c.getOrNull(5)?.toIntOrNull()
        val arm = c.getOrNull(6)?.toIntOrNull()
        val crit = c.getOrNull(7)?.toIntOrNull()
        val leech = c.getOrNull(8)?.toIntOrNull()
        if (c.size < 9 || c[0].isEmpty() || c[1].isEmpty() || maxStacks == null || weight == null || atk == null || arm == null || crit == null || leech == null) {
            problems += "Boon ${i + 1}: write 'id | name | maxStacks | yes/no | weight | atk | arm | critBp | leech% | description'"; null
        } else BoonDefinition(c[0], c[1], c.getOrNull(9).orEmpty(), maxStacks, yes(c[3]), weight, attackDamageBonus = atk, armorDefenseBonus = arm, critChanceBpBonus = crit, lifeLeechPercentBonus = leech)
    }

    private fun parseSkillNodes(text: String, problems: MutableList<String>): List<SkillNodeDefinition> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexedNotNull { i, line ->
        val c = line.split('|', limit = 11).map { it.trim() }
        val classIds = c.getOrNull(2).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }.toSet()
        val maxRank = c.getOrNull(3)?.toIntOrNull()
        val minLevel = c.getOrNull(4)?.toIntOrNull()
        val reqs = c.getOrNull(5).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val spellId = c.getOrNull(6)?.toIntOrNull()
        val atk = c.getOrNull(7)?.toIntOrNull()
        val arm = c.getOrNull(8)?.toIntOrNull()
        val crit = c.getOrNull(9)?.toIntOrNull()
        if (c.size < 10 || c[0].isEmpty() || c[1].isEmpty() || maxRank == null || minLevel == null || spellId == null || atk == null || arm == null || crit == null) {
            problems += "Skill node ${i + 1}: write 'id | name | classIds | maxRank | minLevel | requires | spellId | atk | arm | critBp | description'"; null
        } else SkillNodeDefinition(c[0], c[1], c.getOrNull(10).orEmpty(), classIds, maxRank, minLevel, reqs, spellId, attackDamageBonus = atk, armorDefenseBonus = arm, critChanceBpBonus = crit)
    }
}
