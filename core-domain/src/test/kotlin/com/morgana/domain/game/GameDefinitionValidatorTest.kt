package com.morgana.domain.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameDefinitionValidatorTest {

    private val classic = MorganaClassic.definition

    private fun issues(definition: GameDefinition): List<GameIssue> = GameDefinitionValidator.validate(definition)

    private fun assertIssue(definition: GameDefinition, pointer: String, messagePart: String) {
        val found = issues(definition)
        assertTrue(
            found.any { it.pointer == pointer && messagePart in it.message },
            "expected an issue at $pointer containing '$messagePart' but got: $found"
        )
    }

    private fun withVital(index: Int, change: (VitalDefinition) -> VitalDefinition) =
        classic.copy(vitals = classic.vitals.mapIndexed { i, v -> if (i == index) change(v) else v })

    private fun withAttribute(index: Int, change: (AttributeDefinition) -> AttributeDefinition) =
        classic.copy(attributes = classic.attributes.mapIndexed { i, a -> if (i == index) change(a) else a })

    // ---- structure -------------------------------------------------------------------------------------------

    @Test
    fun `morgana classic has no issues`() {
        assertEquals(emptyList<GameIssue>(), issues(classic))
    }

    @Test
    fun `identity checks`() {
        assertIssue(classic.copy(schemaVersion = 2), "/schemaVersion", "this build reads 1")
        assertIssue(classic.copy(engineApi = "99"), "/engineApi", "requires engine API '99'")
        assertIssue(classic.copy(engineApi = "invalid"), "/engineApi", "requires engine API 'invalid'")
        assertIssue(classic.copy(id = "Bad Id"), "/id", "lowercase")
        assertIssue(classic.copy(name = " "), "/name", "1 to 60")
        assertIssue(classic.copy(capabilities = setOf("bad_cap")), "/capabilities", "unknown script capability 'bad_cap'")
    }

    @Test
    fun `attribute ids must be identifiers, unique, and not reserved`() {
        assertIssue(withAttribute(0) { it.copy(id = "Str") }, "/attributes/0/id", "lowercase")
        assertIssue(withAttribute(0) { it.copy(id = "9lives") }, "/attributes/0/id", "start with a letter")
        assertIssue(withAttribute(0) { it.copy(id = "level") }, "/attributes/0/id", "reserved")
        assertIssue(withAttribute(0) { it.copy(id = "max") }, "/attributes/0/id", "reserved")
        assertIssue(withAttribute(1) { it.copy(id = "str") }, "/attributes/1/id", "duplicate")
    }

    @Test
    fun `attributes and vitals share one id namespace`() {
        assertIssue(withVital(1) { it.copy(id = "str") }, "/vitals/1/id", "more than once")
    }

    @Test
    fun `attribute value rules`() {
        assertIssue(withAttribute(0) { it.copy(floor = -1) }, "/attributes/0/floor", "0 or more")
        assertIssue(withAttribute(0) { it.copy(cap = 0) }, "/attributes/0/cap", "below the floor")
        assertIssue(withAttribute(0) { it.copy(cap = 2_000_000) }, "/attributes/0/cap", "at most")
        assertIssue(withAttribute(0) { it.copy(base = -1) }, "/attributes/0/base", "between 0 and the cap")
        assertIssue(withAttribute(0) { it.copy(cap = 10, base = 11) }, "/attributes/0/base", "between 0 and the cap")
        assertIssue(withAttribute(0) { it.copy(short = "TOOLONGLABEL") }, "/attributes/0/short", "at most 8")
        assertIssue(withAttribute(0) { it.copy(name = "") }, "/attributes/0/name", "1 to 40")
    }

    @Test
    fun `a game may have no attributes`() {
        val noAttributes = classic.copy(
            attributes = emptyList(),
            vitals = listOf(VitalDefinition("hp", "Health", role = VitalRole.LIFE, max = "100 + level*10")),
            combat = CombatDefinition("max(1, weapon - armor)", "max(1, weapon + ammo - armor)", "max(1, basePower)", "500", "300")
        )
        assertEquals(emptyList<GameIssue>(), issues(noAttributes))
    }

    @Test
    fun `exactly one vital must be the life vital`() {
        assertIssue(classic.copy(vitals = emptyList()), "/vitals", "at least one vital")
        assertIssue(classic.copy(vitals = classic.vitals.map { it.copy(role = null) }), "/vitals", "found 0")
        assertIssue(classic.copy(vitals = classic.vitals.map { it.copy(role = VitalRole.LIFE) }), "/vitals", "found 3")
    }

    @Test
    fun `vital display and regeneration rules`() {
        assertIssue(withVital(0) { it.copy(color = "red") }, "/vitals/0/color", "#rrggbb")
        assertIssue(withVital(0) { it.copy(regen = RegenDefinition(0, "1")) }, "/vitals/0/regen/everyTicks", "between 1")
        assertIssue(withVital(0) { it.copy(regen = RegenDefinition(40, "2 +")) }, "/vitals/0/regen/amount", "at character")
    }

    // ---- progression -----------------------------------------------------------------------------------------

    @Test
    fun `progression needs exactly one of a table or an expression, of the right size`() {
        val p = classic.progression
        assertIssue(classic.copy(progression = p.copy(xpExpr = "100 * level")), "/progression", "not both")
        assertIssue(classic.copy(progression = p.copy(xpTable = null)), "/progression", "either xpTable or xpExpr")
        assertIssue(classic.copy(progression = p.copy(xpTable = p.xpTable!!.drop(1))), "/progression/xpTable", "needs maxLevel - 1 = 99")
        assertIssue(classic.copy(progression = p.copy(maxLevel = 0)), "/progression/maxLevel", "between 1 and")
        assertIssue(classic.copy(progression = p.copy(maxLevel = 5000)), "/progression/maxLevel", "between 1 and")
    }

    @Test
    fun `experience table entries must be positive and not decrease`() {
        val table = classic.progression.xpTable!!.toMutableList()
        table[10] = 0
        assertIssue(classic.copy(progression = classic.progression.copy(xpTable = table)), "/progression/xpTable/10", "at least 1")
        val dip = classic.progression.xpTable.orEmpty().toMutableList()
        dip[20] = dip[19] - 1
        assertIssue(classic.copy(progression = classic.progression.copy(xpTable = dip)), "/progression/xpTable/20", "lower than the previous")
    }

    @Test
    fun `an experience expression is evaluated at every level`() {
        val ok = classic.copy(progression = ProgressionDefinition(maxLevel = 50, xpExpr = "100 * level * level"))
        assertEquals(emptyList<GameIssue>(), issues(ok))
        assertIssue(classic.copy(progression = ProgressionDefinition(maxLevel = 50, xpExpr = "level - 3")), "/progression/xpExpr", "gives -2 at level 1")
        assertIssue(classic.copy(progression = ProgressionDefinition(maxLevel = 50, xpExpr = "100 * hp")), "/progression/xpExpr", "not available here")
    }

    // ---- formulas --------------------------------------------------------------------------------------------

    @Test
    fun `a formula that does not parse is reported with its position`() {
        assertIssue(withVital(0) { it.copy(max = "80 + * vit") }, "/vitals/0/max", "at character 5")
        assertIssue(classic.copy(combat = classic.combat.copy(melee = "")), "/combat/melee", "empty")
        assertIssue(classic.copy(combat = classic.combat.copy(spell = "frobnicate(1)")), "/combat/spell", "unknown function")
    }

    @Test
    fun `a formula may only use the names its slot provides`() {
        assertIssue(withVital(0) { it.copy(max = "a.str + level") }, "/vitals/0/max", "not available here")
        assertIssue(withVital(0) { it.copy(max = "luck") }, "/vitals/0/max", "'luck'")
        assertIssue(classic.copy(combat = classic.combat.copy(melee = "weapon + ammo")), "/combat/melee", "'ammo'")
        assertIssue(classic.copy(combat = classic.combat.copy(critChanceBp = "d.agi")), "/combat/critChanceBp", "'d.agi'")
        assertIssue(classic.copy(combat = classic.combat.copy(dodgeChanceBp = "level")), "/combat/dodgeChanceBp", "'level'")
    }

    @Test
    fun `step names may not shadow variables or reserved names`() {
        assertIssue(withVital(0) { it.copy(max = "vit = 3; vit * 2") }, "/vitals/0/max", "step name 'vit'")
        assertIssue(withVital(0) { it.copy(max = "level = 3; level") }, "/vitals/0/max", "step name 'level'")
        assertIssue(withVital(0) { it.copy(max = "crit = 3; crit") }, "/vitals/0/max", "reserved")
    }

    @Test
    fun `division by zero is found by trial evaluation at the extremes`() {
        // vit has floor 1, so vit - 1 reaches zero at the lowest allowed value.
        assertIssue(withVital(0) { it.copy(max = "100 / (vit - 1)") }, "/vitals/0/max", "division by zero")
        // A floor of 2 makes the same formula safe.
        val safe = withAttribute(4) { it.copy(floor = 2, base = 5) }.let { d ->
            d.copy(vitals = d.vitals.mapIndexed { i, v -> if (i == 0) v.copy(max = "100 / (vit - 1)") else v })
        }
        assertEquals(emptyList<GameIssue>(), issues(safe))
    }

    @Test
    fun `overflow is found by trial evaluation`() {
        assertIssue(withVital(0) { it.copy(max = "pow(vit, 5)") }, "/vitals/0/max", "overflow")
        // Lowering the cap keeps the same formula in range.
        val capped = withAttribute(4) { it.copy(cap = 1000) }.let { d ->
            d.copy(vitals = d.vitals.mapIndexed { i, v -> if (i == 0) v.copy(max = "pow(vit, 5)") else v })
        }
        assertEquals(emptyList<GameIssue>(), issues(capped))
    }

    @Test
    fun `the failing inputs are named so the author can see why`() {
        val message = issues(withVital(0) { it.copy(max = "100 / (vit - 1)") }).first { it.pointer == "/vitals/0/max" }.message
        assertTrue("vit=1" in message, message)
    }

    @Test
    fun `a definition with many inputs is still checked`() {
        val many = classic.copy(
            combat = classic.combat.copy(
                melee = "max(1, a.str + a.def + a.int + a.agi + a.vit + d.str + d.def + d.int + d.agi + d.vit + weapon + armor) / (a.str - 1)"
            )
        )
        assertIssue(many, "/combat/melee", "division by zero")
    }

    @Test
    fun `every problem is reported, not just the first`() {
        val broken = classic.copy(
            id = "BAD",
            vitals = classic.vitals.map { it.copy(max = "oops +") },
            combat = classic.combat.copy(melee = "", ranged = "")
        )
        val found = issues(broken)
        assertTrue(found.size >= 6, found.toString())
    }

    // ---- compiling -------------------------------------------------------------------------------------------

    @Test
    fun `compiling an invalid definition throws with every issue listed`() {
        val error = assertThrows(GameDefinitionException::class.java) {
            CompiledGame.compile(classic.copy(id = "BAD", combat = classic.combat.copy(melee = "")))
        }
        assertTrue(error.issues.size >= 2)
        assertTrue(error.message!!.contains("/id"), error.message)
        assertTrue(error.message!!.contains("/combat/melee"), error.message)
    }

    @Test
    fun `the compiled game answers questions about its definition`() {
        val game = CompiledGame.compile(classic)
        assertEquals(1, game.clampAttribute("str", 0), "raised to the floor")
        assertEquals(1_000_000, game.clampAttribute("str", Long.MAX_VALUE), "lowered to the hard cap")
        assertEquals(7, game.clampAttribute("str", 7))
        assertEquals(40, game.regenEveryTicks("hp"))
        assertEquals("Strength", game.attribute("str").name)
    }

    @Test
    fun `modern taller sprite profile is valid and computes state column ranges`() {
        val modern = SpriteProfile.MODERN_TALL
        assertTrue(issues(classic.copy(sprites = modern)).isEmpty())
        assertEquals(13, modern.totalFrames)
        assertEquals(416, modern.sheetWidth)
        assertEquals(256, modern.sheetHeight)
        assertEquals(0..0, modern.columnRange(SpriteState.IDLE))
        assertEquals(1..3, modern.columnRange(SpriteState.WALK))
        assertEquals(4..6, modern.columnRange(SpriteState.ATTACK))
        assertEquals(7..9, modern.columnRange(SpriteState.CAST))
        assertEquals(10..12, modern.columnRange(SpriteState.HARVEST))
    }

    @Test
    fun `jump and hurt columns follow harvest and hurt falls back to idle`() {
        val p = SpriteProfile(
            stateFrames = mapOf(
                SpriteState.IDLE to 2, SpriteState.WALK to 4, SpriteState.ATTACK to 4, SpriteState.CAST to 4,
                SpriteState.JUMP to 3, SpriteState.HURT to 2
            )
        )
        assertEquals(19, p.totalFrames)
        assertEquals(10..13, p.columnRange(SpriteState.CAST))
        assertEquals(14..16, p.columnRange(SpriteState.JUMP))
        assertEquals(17..18, p.columnRange(SpriteState.HURT))
        val noHurt = SpriteProfile(stateFrames = mapOf(SpriteState.IDLE to 2, SpriteState.WALK to 4))
        assertEquals(0..1, noHurt.columnRange(SpriteState.HURT))
        assertEquals(2..5, noHurt.columnRange(SpriteState.JUMP))
    }

    @Test
    fun `sprite profile falls back unconfigured states cleanly to walk frames`() {
        val walkOnly = SpriteProfile(cellWidth = 32, cellHeight = 64)
        assertEquals(3, walkOnly.totalFrames)
        assertEquals(96, walkOnly.sheetWidth)
        assertEquals(256, walkOnly.sheetHeight)
        assertEquals(0..0, walkOnly.columnRange(SpriteState.IDLE))
        assertEquals(0..2, walkOnly.columnRange(SpriteState.WALK))
        assertEquals(0..2, walkOnly.columnRange(SpriteState.ATTACK))
    }

    @Test
    fun `invalid sprite cell size, missing walk frames, and oversized sheet are rejected`() {
        assertIssue(
            classic.copy(sprites = SpriteProfile(cellWidth = 4, cellHeight = 64)),
            "/sprites/cellWidth",
            "between 8 and 256"
        )
        assertIssue(
            classic.copy(sprites = SpriteProfile(cellWidth = 32, cellHeight = 300)),
            "/sprites/cellHeight",
            "between 8 and 256"
        )
        assertIssue(
            classic.copy(sprites = SpriteProfile(stateFrames = mapOf(SpriteState.IDLE to 2))),
            "/sprites/stateFrames/walk",
            "at least 1 frame"
        )
        assertIssue(
            classic.copy(
                sprites = SpriteProfile(
                    cellWidth = 256,
                    cellHeight = 64,
                    stateFrames = mapOf(SpriteState.WALK to 16, SpriteState.ATTACK to 1)
                )
            ),
            "/sprites/stateFrames",
            "exceeds 4096"
        )
    }

    @Test
    fun `validates gear upgrade socket limits`() {
        val valid = classic.copy(gearUpgrade = GearUpgradeDefinition(maxSockets = 2))
        assertTrue(GameDefinitionValidator.validate(valid).isEmpty())
        assertIssue(valid.copy(gearUpgrade = GearUpgradeDefinition(maxSockets = 0)), "/gearUpgrade/maxSockets", "1 to 6")
        assertIssue(valid.copy(gearUpgrade = GearUpgradeDefinition(maxSockets = 7)), "/gearUpgrade/maxSockets", "1 to 6")
    }

    @Test
    fun `validates rebirth bounds`() {
        val maxLevel = classic.progression.maxLevel
        val valid = classic.copy(rebirth = RebirthDefinition(minLevel = maxLevel, resetLevel = 5, maxRebirths = 2, grantSpellIds = mapOf(1 to listOf(1))))
        assertTrue(GameDefinitionValidator.validate(valid).isEmpty())
        assertIssue(valid.copy(rebirth = valid.rebirth!!.copy(minLevel = maxLevel + 1)), "/rebirth/minLevel", "maximum level")
        assertIssue(valid.copy(rebirth = valid.rebirth!!.copy(resetLevel = maxLevel)), "/rebirth/resetLevel", "below minLevel")
        assertIssue(valid.copy(rebirth = valid.rebirth!!.copy(maxRebirths = 0)), "/rebirth/maxRebirths", "1 to 20")
        assertIssue(valid.copy(rebirth = valid.rebirth!!.copy(grantSpellIds = mapOf(3 to listOf(1)))), "/rebirth/grantSpellIds/3", "1 to 2")
    }

    @Test
    fun `validates durability bounds`() {
        val valid = classic.copy(durability = DurabilityDefinition())
        assertTrue(GameDefinitionValidator.validate(valid).isEmpty())
        assertIssue(valid.copy(durability = DurabilityDefinition(weaponWearChancePercent = 101)), "/durability/weaponWearChancePercent", "0 to 100")
        assertIssue(valid.copy(durability = DurabilityDefinition(wearPerHit = 0)), "/durability/wearPerHit", "1 to 100")
        assertIssue(valid.copy(durability = DurabilityDefinition(repairPricePercent = -1)), "/durability/repairPricePercent", "0 to 10000")
    }

    @Test
    fun `validates nobility ranks and battle power weights`() {
        val n = NobilityDefinition(ranks = listOf(NobilityRank("knight", "Knight", 100, 5), NobilityRank("baron", "Baron", 500, 9)))
        val valid = classic.copy(nobility = n, battlePower = BattlePowerDefinition())
        assertTrue(GameDefinitionValidator.validate(valid).isEmpty())
        assertIssue(valid.copy(nobility = n.copy(ranks = n.ranks + NobilityRank("knight", "Knight 2", 900))), "/nobility/ranks/2/id", "defined twice")
        assertIssue(valid.copy(nobility = n.copy(ranks = n.ranks + NobilityRank("earl", "Earl", 500))), "/nobility/ranks/2/minDonation", "two ranks")
        assertIssue(valid.copy(nobility = n.copy(ranks = listOf(NobilityRank("a", "A", 0)))), "/nobility/ranks/0/minDonation", "at least 1")
        assertIssue(valid.copy(battlePower = BattlePowerDefinition(perLevel = -1)), "/battlePower/perLevel", "0 to 100000")
    }

    @Test
    fun `validates weaponProficiency config bounds and duplicate ids`() {
        val wp = WeaponProficiencyConfig(
            enabled = true,
            maxLevel = 20,
            hitsPerLevel = 25,
            damageBonusPercentPerLevel = 5,
            weaponTypes = listOf(WeaponTypeDefinition("blade", "Blade"), WeaponTypeDefinition("bow", "Bow"))
        )
        val valid = classic.copy(weaponProficiency = wp)
        assertTrue(GameDefinitionValidator.validate(valid).isEmpty())

        assertIssue(
            valid.copy(weaponProficiency = wp.copy(maxLevel = 0)),
            "/weaponProficiency/maxLevel",
            "1 to 100"
        )
        assertIssue(
            valid.copy(weaponProficiency = wp.copy(hitsPerLevel = 0)),
            "/weaponProficiency/hitsPerLevel",
            "1 to 100000"
        )
        assertIssue(
            valid.copy(
                weaponProficiency = wp.copy(
                    weaponTypes = listOf(WeaponTypeDefinition("blade", "Blade"), WeaponTypeDefinition("blade", "Blade 2"))
                )
            ),
            "/weaponProficiency/weaponTypes/1/id",
            "defined twice"
        )
    }

    @Test
    fun `sprite scales must name real sprites and stay in range`() {
        assertTrue(issues(classic.copy(sprites = SpriteProfile(scales = mapOf(1 to 2f, 5 to 0.5f)))).none { it.pointer.startsWith("/sprites") })
        assertIssue(classic.copy(sprites = SpriteProfile(scales = mapOf(0 to 2f))), "/sprites/scales/0", "start at 1")
        assertIssue(classic.copy(sprites = SpriteProfile(scales = mapOf(1 to 0f))), "/sprites/scales/1", "between")
        assertIssue(classic.copy(sprites = SpriteProfile(scales = mapOf(1 to 9f))), "/sprites/scales/1", "between")
    }

    @Test
    fun `sprite ui heights must name real sprites and fit the cell`() {
        val p = { h: Map<Int, Int> -> classic.copy(sprites = SpriteProfile(cellHeight = 48, uiHeights = h)) }
        assertTrue(issues(p(mapOf(10 to 30))).none { it.pointer.startsWith("/sprites") })
        assertIssue(p(mapOf(0 to 30)), "/sprites/uiHeights/0", "start at 1")
        assertIssue(p(mapOf(10 to 0)), "/sprites/uiHeights/10", "between 1 and")
        assertIssue(p(mapOf(10 to 49)), "/sprites/uiHeights/10", "between 1 and")
    }
}
