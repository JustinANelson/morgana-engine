package com.morgana.client.editor.forms

import com.morgana.domain.content.BossAbility
import com.morgana.domain.content.BossEncounter
import com.morgana.domain.content.BossPhase
import com.morgana.domain.content.BossZoneShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestReward
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.stats.StatusEffectType
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentFormsTest {

    private val ctx = FormContext()

    private fun <T> roundTrip(form: ContentForm<T>, d: T, context: FormContext = ctx): T =
        form.apply(d, form.valuesOf(d, context), context)

    private fun <T> problems(form: ContentForm<T>, d: T, context: FormContext = ctx) =
        FormRules.problems(form, form.valuesOf(d, context), context)

    // ---- items ----

    private val sword = ItemDefinition(
        id = 5, name = "Iron Sword", description = "Sharp.", type = ItemType.WEAPON, iconId = 4, paperdollSpriteId = 101, price = 120,
        levelReq = 3, classReqIds = setOf(1, 2), attributeReq = mapOf("str" to 6), attributeBonuses = mapOf("str" to 2, "agi" to 1),
        vitalMaxBonuses = mapOf("hp" to 10), attackDamage = 9, attackSpeedTicks = 10, rangeTiles = 1, weaponStyle = WeaponStyle.MELEE,
        toolType = ToolType.NONE
    )

    @Test
    fun `an item round trips through its form`() {
        assertEquals(sword, roundTrip(ItemForm, sword))
        assertEquals(emptyList<String>(), problems(ItemForm, sword))
        val potion = ItemDefinition(
            id = 6, name = "Potion", type = ItemType.CONSUMABLE, restore = mapOf("hp" to 40), stackable = true, maxStack = 20,
            statusEffectToApply = StatusEffectType.entries.first(), statusEffectMagnitude = 2, statusEffectDurationTicks = 100, teachSpellId = 0
        )
        assertEquals(potion, roundTrip(ItemForm, potion))
    }

    @Test
    fun `editing an item changes only what was typed and keeps hand-authored map entries`() {
        val odd = sword.copy(attributeBonuses = mapOf("str" to 2, "luck" to 5))
        val values = ItemForm.valuesOf(odd, ctx) + ("name" to "  Steel Sword ") + ("bonus.str" to "0") + ("bonus.int" to "3")
        val changed = ItemForm.apply(odd, values, ctx)
        assertEquals("Steel Sword", changed.name)
        assertEquals(mapOf("luck" to 5, "int" to 3), changed.attributeBonuses)
        assertEquals(odd.attackDamage, changed.attackDamage)
    }

    @Test
    fun `item fields follow the game's own attributes and vitals`() {
        val rpg = FormContext(attributeIds = listOf("grit", "thumb"), vitalIds = listOf("hp", "stamina"))
        val keys = ItemForm.sections(rpg).flatMap { it.fields }.map { it.key }
        assertTrue("bonus.grit" in keys && "bonus.thumb" in keys && "vmax.stamina" in keys && "restore.stamina" in keys)
        assertFalse("bonus.str" in keys)
        val item = ItemDefinition(id = 1, name = "Hoe", attributeBonuses = mapOf("thumb" to 2), restore = mapOf("stamina" to 5))
        assertEquals(item, roundTrip(ItemForm, item, rpg))
    }

    @Test
    fun `bad item values are explained and building falls back`() {
        val values = ItemForm.valuesOf(sword, ctx) + ("name" to " ") + ("price" to "-4") + ("attackDamage" to "lots") + ("classReq" to "1, x")
        val p = FormRules.problems(ItemForm, values, ctx)
        assertTrue(p.any { it.startsWith("Name") })
        assertTrue(p.any { it.startsWith("Base price") })
        assertTrue(p.any { it.startsWith("Attack damage") })
        assertTrue(p.any { it.startsWith("Classes allowed") })
        val built = ItemForm.apply(sword, values, ctx)
        assertEquals("Iron Sword", built.name)
        assertEquals(0, built.price)
        assertEquals(9, built.attackDamage)
        assertEquals(setOf(1, 2), built.classReqIds)
    }

    @Test
    fun `item cross checks use the catalog`() {
        val catalog = WorldContentCatalogDto(items = listOf(sword))
        val context = FormContext(catalog = catalog)
        val values = ItemForm.valuesOf(sword, context) + ("ammo" to "99") + ("teach" to "3")
        val p = FormRules.problems(ItemForm, values, context)
        assertTrue(p.any { it.contains("Ammunition item 99") })
        val noSpells = FormRules.problems(ItemForm, ItemForm.valuesOf(sword, context) + ("teach" to "3"), context)
        assertTrue(noSpells.none { it.startsWith("Spell") }, "an empty spell list is not checked")
        val status = FormRules.problems(ItemForm, ItemForm.valuesOf(sword, ctx) + ("statusMagnitude" to "3"), ctx)
        assertTrue(status.any { it.contains("no status is chosen") })
    }

    // ---- NPCs ----

    private val wolf = NpcDefinition(
        id = 3, name = "Wolf", title = "Beast", spriteId = 9, behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 4, vitals = mapOf("hp" to 80),
        attributes = mapOf("str" to 4), attackDamage = 12, attackSpeedTicks = 14, moveSpeedTicks = 8, aggroRangeTiles = 6, leashRangeTiles = 12,
        xpReward = 60L, drops = listOf(NpcDropEntry(2, 1, 1, 5000), NpcDropEntry(3, 1, 3, 250)), spellIds = listOf(1), shopId = 0,
        chatGreeting = "Grr"
    )

    @Test
    fun `an NPC round trips through its form`() {
        assertEquals(wolf, roundTrip(NpcForm, wolf))
        assertEquals(emptyList<String>(), problems(NpcForm, wolf))
    }

    @Test
    fun `loot drop lines parse and are checked`() {
        val ok = NpcForm.parseDrops("2 1 5000\n\n3 1-3 250\n")
        assertEquals(listOf(NpcDropEntry(2, 1, 1, 5000), NpcDropEntry(3, 1, 3, 250)), ok.drops)
        assertTrue(ok.problems.isEmpty())
        assertEquals("2 1 5000\n3 1-3 250", NpcForm.formatDrops(ok.drops))

        val bad = NpcForm.parseDrops("x 1 5\n2 3-1 5\n2 1 0\n2 1\n2 1-2-3 5")
        assertTrue(bad.drops.isEmpty())
        assertEquals(5, bad.problems.size, bad.problems.toString())
        assertTrue(bad.problems[0].startsWith("Drop 1"))
    }

    private val boss = wolf.copy(
        id = 14, name = "Bone Colossus",
        encounter = BossEncounter(
            lockoutTicks = 1_728_000L,
            phases = listOf(
                BossPhase(100, abilities = listOf(BossAbility(6, BossZoneShape.AROUND_TARGET, 2, 30, 220, "Bone Slam!"))),
                BossPhase(60, announce = "The Colossus rattles!", attackPercent = 120, summonNpcIds = listOf(11, 11),
                    abilities = listOf(BossAbility(6, BossZoneShape.AROUND_SELF, 3, 40, 300)))
            )
        )
    )

    @Test
    fun `a boss encounter round trips through the NPC form as lines`() {
        assertEquals(boss, roundTrip(NpcForm, boss))
        assertEquals(emptyList<String>(), problems(NpcForm, boss))
        val values = NpcForm.valuesOf(boss, ctx)
        assertEquals("100 100 100 -\n60 120 100 11,11 The Colossus rattles!", values["bossPhases"])
        assertEquals("1 6 AROUND_TARGET 2 30 220 Bone Slam!\n2 6 AROUND_SELF 3 40 300", values["bossAttacks"])
        assertEquals(wolf, roundTrip(NpcForm, wolf), "an ordinary monster stays one")
    }

    @Test
    fun `a boss can be authored from nothing and bad lines are explained`() {
        val values = NpcForm.valuesOf(wolf, ctx) + mapOf("bossPhases" to "100 100 100 -\n50 130 90 15 Enraged!", "bossAttacks" to "2 7 AROUND_SELF 3 40 260 Burst!", "bossLockout" to "0")
        val made = NpcForm.apply(wolf, values, ctx).encounter!!
        assertEquals(listOf(100, 50), made.phases.map { it.fromLifePercent })
        assertEquals(listOf(15), made.phases[1].summonNpcIds)
        assertEquals("Burst!", made.phases[1].abilities.single().warning)
        assertEquals(null, NpcForm.apply(boss, NpcForm.valuesOf(boss, ctx) + mapOf("bossPhases" to "", "bossAttacks" to "", "bossLockout" to "0"), ctx).encounter, "clearing the boxes makes it an ordinary monster")

        val bad = NpcForm.parseEncounter("60 100 100 -\n70 100 100 -\n50 x 100 -\n40 100 100 a,b", "9 6 AROUND_TARGET 2 30 220\n1 6 ROUND 2 30 220\n1 6 AROUND_SELF 2 2 220", 0)
        assertTrue(bad.problems.any { it.startsWith("Phase 2") && "lower" in it })
        assertTrue(bad.problems.any { it.startsWith("Phase 3") })
        assertTrue(bad.problems.any { it.startsWith("Phase 4") })
        assertTrue(bad.problems.any { it.startsWith("Attack 1") }, "phase 9 does not exist")
        assertTrue(bad.problems.any { it.startsWith("Attack 2") && "shape" in it })
        assertTrue(bad.problems.any { it.startsWith("Attack 3") && "wind-up" in it })

        val catalog = WorldContentCatalogDto(npcs = listOf(boss), spells = emptyList())
        val missing = FormRules.problems(NpcForm, NpcForm.valuesOf(boss, ctx) + ("bossPhases" to "100 100 100 99"), FormContext(catalog = catalog))
        assertTrue(missing.any { "Boss add 99 does not exist" in it })
    }

    private val delver = com.morgana.domain.content.ClassDefinition(
        id = 1, name = "Delver", description = "Tough.", spriteIds = listOf(1, 2), baseAttributes = mapOf("str" to 12, "agi" to 9),
        autoStatGrowthPerLevel = mapOf("str" to 1), statPointsPerLevel = 3, startMapId = 1, startX = 13, startY = 11,
        startingItems = listOf(com.morgana.domain.item.ItemStack(2, 1), com.morgana.domain.item.ItemStack(15, 3, 60)),
        autoEquipStartingItems = true, startingSpellIds = listOf(8)
    )

    @Test
    fun `a class round trips through its form and bad lines are explained`() {
        assertEquals(delver, roundTrip(ClassForm, delver))
        assertEquals(emptyList<String>(), problems(ClassForm, delver))
        assertEquals("2 1\n15 3 60", ClassForm.valuesOf(delver, ctx)["items"])
        val made = ClassForm.create(4, ctx)
        assertEquals(emptyList<String>(), problems(ClassForm, made))
        val bad = ClassForm.parseItems("x 1\n2\n3 0\n4 1 200")
        assertEquals(4, bad.problems.size)
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(2, "Blade")), spells = emptyList())
        val p = FormRules.problems(ClassForm, ClassForm.valuesOf(delver, ctx), FormContext(catalog = catalog))
        assertTrue(p.any { "Starting item 15 does not exist" in it })
    }

    private val dungeonMap = com.morgana.domain.map.MapDefinition(
        id = 3, name = "The Crypt (floor 2)", width = 5, height = 5, instanced = true,
        cells = List(25) { com.morgana.domain.map.MapCell() },
        generator = com.morgana.domain.map.FloorGenerator(
            floorTile = com.morgana.domain.map.TilePlacement(1, 1, 0, animationFrames = 3), wallTile = com.morgana.domain.map.TilePlacement(1, 11, 0),
            stairsTile = com.morgana.domain.map.TilePlacement(1, 10, 0),
            decorations = listOf(com.morgana.domain.map.WeightedTile(com.morgana.domain.map.TilePlacement(1, 9, 0), 2)),
            monsters = listOf(com.morgana.domain.map.WeightedSpawn(11, 3), com.morgana.domain.map.WeightedSpawn(12, 2)),
            loot = listOf(com.morgana.domain.map.WeightedLoot(1, 5, 12, 6), com.morgana.domain.map.WeightedLoot(27, 1, 1, 3)),
            bossNpcId = 14, nextMapId = 4, itemLevel = 3
        ),
        scaling = com.morgana.domain.map.InstanceScaling(
            levelFollowsMembers = true, maxMembers = 4,
            tiers = listOf(com.morgana.domain.map.DifficultyTier("Normal"), com.morgana.domain.map.DifficultyTier("Hard Mode", 150, 125, 150, 130, 6))
        )
    )

    @Test
    fun `a dungeon floor recipe and its scaling round trip through the dungeon form`() {
        val values = DungeonForm.valuesOf(dungeonMap)
        assertEquals(emptyList<String>(), DungeonForm.problems(values, instanced = true))
        assertEquals(dungeonMap, DungeonForm.apply(dungeonMap, values), "an unedited apply changes nothing, even animated tiles")
        assertEquals("1 5-12 6\n27 1 6".replace("27 1 6", "27 1 3"), values["loot"])
        assertEquals("150 125 150 130 6 Hard Mode", values["tiers"]!!.lines()[1])
    }

    @Test
    fun `the dungeon form builds a floor from nothing, removes sections, and explains bad lines`() {
        val blank = com.morgana.domain.map.MapDefinition(id = 9, name = "Floor", width = 5, height = 5, instanced = true, cells = List(25) { com.morgana.domain.map.MapCell() })
        val v = DungeonForm.valuesOf(blank) + mapOf("floor" to "true", "monsters" to "11 2", "loot" to "1 3-6 4", "exitMap" to "1", "exitX" to "13", "exitY" to "7", "scaling" to "true", "tiers" to "100 100 100 100 1 Normal")
        assertEquals(emptyList<String>(), DungeonForm.problems(v, true))
        val made = DungeonForm.apply(blank, v)
        assertEquals(listOf(11), made.generator!!.monsters.map { it.npcId })
        assertEquals(1, made.generator!!.exitMapId)
        assertEquals("Normal", made.scaling!!.tiers.single().name)
        assertEquals(null, DungeonForm.apply(made, v + ("floor" to "false") + ("scaling" to "false")).let { it.generator ?: it.scaling })

        val bad = DungeonForm.problems(v + mapOf("monsters" to "x 1", "loot" to "1 6-3 4", "tiers" to "150 125 Hard", "exitMap" to "0"), instanced = false)
        assertTrue(bad.any { it.startsWith("Monster 1") })
        assertTrue(bad.any { it.startsWith("Loot 1") })
        assertTrue(DungeonForm.problems(v + ("floorTile" to "1 2"), true).any { it.startsWith("Floor tile") })
        assertTrue(bad.any { it.startsWith("Tier 1") })
        assertTrue(bad.any { "Instanced" in it })
        assertTrue(bad.any { "exitMapId" in it }, "a last floor needs an exit: $bad")
        assertTrue(DungeonForm.problems(v + ("boss" to "99"), true, npcIds = setOf(11)).any { "Boss 99 does not exist" in it })
    }

    private val slash = com.morgana.domain.content.ActionSkillDefinition(
        id = 2, name = "Slash", startupTicks = 3, activeTicks = 4, recoveryTicks = 8, cancelFromTick = 9, costs = mapOf("mp" to 5), cooldownTicks = 20,
        hitboxes = listOf(
            com.morgana.domain.content.SkillHitbox(3, 6, com.morgana.domain.content.HitboxShape.ARC, radiusPixels = 48, arcDegrees = 120,
                hit = com.morgana.domain.content.SkillHit(reaction = com.morgana.domain.content.HitReaction.STAGGER, reactionTicks = 6, knockbackPixels = 16, statusEffectType = StatusEffectType.SLOW, statusMagnitude = 20, statusDurationTicks = 40)),
            com.morgana.domain.content.SkillHitbox(4, 5, com.morgana.domain.content.HitboxShape.RECT, lengthPixels = 96, widthPixels = 32,
                projectile = com.morgana.domain.content.SkillProjectile(3, 12, 200, true))
        ),
        armor = listOf(com.morgana.domain.content.SkillArmorWindow(0, 5, com.morgana.domain.content.SkillArmorType.INVULNERABLE)),
        moves = listOf(com.morgana.domain.content.SkillMove(0, 3, 24, 0)),
        guard = com.morgana.domain.content.SkillGuard(90, 100, mapOf("mp" to 2), 80)
    )
    private val swordSet = com.morgana.domain.content.WeaponSetDefinition(
        id = 1, name = "Sword", classIds = setOf(1), skillIds = listOf(2, 3),
        entries = listOf(com.morgana.domain.content.ComboEntry("LMB", 2), com.morgana.domain.content.ComboEntry("SHIFT+LMB", 3)),
        edges = listOf(com.morgana.domain.content.ComboEdge(2, "LMB", 3, 8, true))
    )

    @Test
    fun `action skills and weapon sets round trip and their lines are checked`() {
        assertEquals(slash, roundTrip(ActionSkillForm, slash))
        assertEquals(emptyList<String>(), problems(ActionSkillForm, slash))
        assertEquals(swordSet, roundTrip(WeaponSetForm, swordSet))
        assertEquals(slash.copy(guard = null), ActionSkillForm.apply(slash, ActionSkillForm.valuesOf(slash, ctx) + ("guard.on" to "false"), ctx))
        assertTrue(ActionSkillForm.parseHitboxes("1 | 2 | BLOB").second.isNotEmpty())
        assertTrue(ActionSkillForm.parseArmor("0 | 5 | SHINY").second.isNotEmpty())
        val catalog = WorldContentCatalogDto(actionSkills = listOf(slash))
        val p = FormRules.problems(WeaponSetForm, WeaponSetForm.valuesOf(swordSet, ctx) + ("entries" to "LMB+RMB | 2\nCTRL+Q | 9"), FormContext(catalog = catalog))
        assertTrue(p.any { it.startsWith("Input 1") }, "two keys at once is not an input: $p")
        assertTrue(p.any { "skill 9, which is not in the set" in it }, p.toString())
        assertTrue(p.any { "Skill 3 does not exist" in it }, p.toString())
    }

    @Test
    fun `the systems form lists every optional system and warns about odd combinations`() {
        val v = ModulesForm.valuesOf(listOf("mail", "projectiles"))
        assertEquals(com.morgana.domain.sim.OptionalModule.entries.size, v.size)
        assertEquals(listOf("mail", "projectiles"), ModulesForm.disabledFrom(v))
        assertEquals(emptyList<String>(), ModulesForm.disabledFrom(ModulesForm.valuesOf(emptyList())))
        val w = ModulesForm.warnings(v)
        assertTrue(w.any { "market" in it.lowercase() && "Mail" in it })
        assertTrue(w.any { "Projectiles" in it })
        assertEquals(emptyList<String>(), ModulesForm.warnings(ModulesForm.valuesOf(emptyList())))
    }

    @Test
    fun `the systems form groups systems under their genre, each once, and can start from a genre`() {
        val sections = ModulesForm.sections()
        val ids = sections.flatMap { s -> s.fields.map { it.key } }
        assertEquals(com.morgana.domain.sim.OptionalModule.entries.map { "mod.${it.configKey}" }.toSet(), ids.toSet())
        assertEquals(ids.size, ids.toSet().size, "no system is listed twice")
        assertEquals("Online RPG", sections.first().title)
        assertTrue(sections.any { it.title == "Roguelike and dungeon crawler" && it.fields.any { f -> f.key == "mod.instancing" } })

        val roguelike = ModulesForm.valuesForGenre("roguelike")
        assertEquals("true", roguelike["mod.parties"])
        assertEquals("true", roguelike["mod.bossEncounters"])
        assertEquals("false", roguelike["mod.guilds"])
        assertEquals("false", roguelike["mod.market"])
        assertEquals(com.morgana.domain.sim.SystemGroups.disabledKeysFor("roguelike").sorted(), ModulesForm.disabledFrom(roguelike))
        // a genre is a starting point: the form can still be edited afterwards
        assertTrue(listOf("guilds", "market", "trading").all { it in ModulesForm.disabledFrom(roguelike) })
    }

    @Test
    fun `NPC cross checks`() {
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(2, "Pelt")), shops = emptyList(), npcs = listOf(wolf))
        val context = FormContext(catalog = catalog)
        val values = NpcForm.valuesOf(wolf, context) + ("leash" to "3")
        val p = FormRules.problems(NpcForm, values, context)
        assertTrue(p.any { it.contains("Drop item 3 does not exist") })
        assertTrue(p.any { it.contains("Gives up beyond") })
    }

    @Test
    fun `a new NPC has a name sprite and hit points`() {
        val npc = NpcForm.create(12, ctx)
        assertEquals(12, npc.id)
        assertEquals(50, npc.vitals["hp"])
        assertEquals(emptyList<String>(), problems(NpcForm, npc))
    }

    // ---- spells ----

    private val bolt = SpellDefinition(
        id = 2, name = "Firebolt", description = "Burns.", iconId = 3, targetType = SpellTargetType.SINGLE_TARGET, levelReq = 2,
        costs = mapOf("mp" to 8), castTimeTicks = 6, cooldownTicks = 30, rangeTiles = 7, basePower = 20, statusEffectType = StatusEffectType.entries.first(),
        statusMagnitude = 2, statusDurationTicks = 60, animationId = 2
    )

    @Test
    fun `a spell round trips through its form`() {
        assertEquals(bolt, roundTrip(SpellForm, bolt))
        assertEquals(emptyList<String>(), problems(SpellForm, bolt))
    }

    @Test
    fun `spell targeting rules`() {
        val area = SpellForm.valuesOf(bolt, ctx) + ("target" to "AOE_SELF_CENTERED") + ("aoe" to "0")
        assertTrue(FormRules.problems(SpellForm, area, ctx).any { it.contains("area radius of at least 1") })
        val stray = SpellForm.valuesOf(bolt, ctx) + ("aoe" to "3")
        assertTrue(FormRules.problems(SpellForm, stray, ctx).any { it.contains("not an area") })
        val warp = SpellForm.valuesOf(bolt, ctx) + ("target" to "WARP")
        assertTrue(FormRules.problems(SpellForm, warp, ctx).any { it.contains("destination map") })
        val noDuration = SpellForm.valuesOf(bolt, ctx) + ("statusDuration" to "0")
        assertTrue(FormRules.problems(SpellForm, noDuration, ctx).any { it.contains("duration of at least 1") })
    }

    @Test
    fun `a new spell is valid`() {
        assertEquals(emptyList<String>(), problems(SpellForm, SpellForm.create(9, ctx)))
        assertEquals(emptyList<String>(), problems(ItemForm, ItemForm.create(9, ctx)))
        // In a game whose health vital is not called hp and with a different resource, new content uses the game's own vitals.
        val rpg = FormContext(vitalIds = listOf("life", "stamina"), lifeVitalId = "life")
        assertEquals(mapOf("stamina" to 10), SpellForm.create(9, rpg).costs)
        assertEquals(mapOf("life" to 50), NpcForm.create(9, rpg).vitals)
        assertEquals(emptyList<String>(), problems(SpellForm, SpellForm.create(9, rpg), rpg))
    }

    // ---- shops ----

    private val shop = ShopDefinition(
        id = 2, name = "Market Stall", buyRatePercent = 120, sellRatePercent = 40, currencyItemId = 1,
        entries = listOf(ShopEntry(3, 5, 1, 0), ShopEntry(4, 1, 2, 75))
    )

    @Test
    fun `a shop round trips with every stock detail`() {
        assertEquals(shop, roundTrip(ShopForm, shop))
        assertEquals(emptyList<String>(), problems(ShopForm, shop))
        assertEquals("3 5 1 0\n4 1 2 75", ShopForm.formatStock(shop.entries))
    }

    @Test
    fun `stock lines accept short forms and explain mistakes`() {
        val ok = ShopForm.parseStock("3\n4 2\n5 1 7\n6 1 7 90", shopCurrency = 9)
        assertEquals(listOf(ShopEntry(3, 1, 9, 0), ShopEntry(4, 2, 9, 0), ShopEntry(5, 1, 7, 0), ShopEntry(6, 1, 7, 90)), ok.entries)
        assertTrue(ok.problems.isEmpty())
        val bad = ShopForm.parseStock("x\n0\n3 0\n3 1 0\n3 1 1 -5\n1 2 3 4 5", shopCurrency = 1)
        assertTrue(bad.entries.isEmpty())
        assertEquals(6, bad.problems.size, bad.problems.toString())
    }

    @Test
    fun `shop cross checks use the catalog`() {
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(1, "Coin"), ItemDefinition(3, "Bread")))
        val context = FormContext(catalog = catalog)
        val values = ShopForm.valuesOf(shop, context) + ("stock" to "3 1\n3 2\n8 1")
        val p = FormRules.problems(ShopForm, values, context)
        assertTrue(p.any { it.contains("Stock item 8 does not exist") }, p.toString())
        assertTrue(p.any { it.contains("listed more than once") }, p.toString())
        assertTrue(FormRules.problems(ShopForm, values + ("currency" to "77"), context).any { it.contains("Currency item 77") })
        assertTrue(FormRules.problems(ShopForm, values + ("buyRate" to "-1"), context).any { it.startsWith("Price when players buy") })
    }

    @Test
    fun `a new shop uses the currency item of the game`() {
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(4, "Rock"), ItemDefinition(7, "Gold", type = ItemType.CURRENCY)))
        assertEquals(7, ShopForm.create(3, FormContext(catalog = catalog)).currencyItemId)
        assertEquals(1, ShopForm.create(3, ctx).currencyItemId)
    }

    // ---- resources ----

    private val oak = ResourceDefinition(
        id = 4, name = "Oak", category = ResourceCategory.TREE, intactSpriteId = 3, depletedSpriteId = 4, requiredTool = ToolType.HATCHET,
        rewardItemId = 5, minYield = 1, maxYield = 3, maxHealth = 4, respawnTicks = 300, xpReward = 20L, successMessage = "Timber!", animationId = 0
    )

    @Test
    fun `a resource round trips through its form`() {
        assertEquals(oak, roundTrip(ResourceForm, oak))
        assertEquals(emptyList<String>(), problems(ResourceForm, oak))
    }

    @Test
    fun `resource yields and references are checked`() {
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(5, "Log")))
        val context = FormContext(catalog = catalog)
        val values = ResourceForm.valuesOf(oak, context) + ("minYield" to "4") + ("maxYield" to "2") + ("reward" to "9")
        val p = FormRules.problems(ResourceForm, values, context)
        assertTrue(p.any { it.contains("Most items per gather is below") }, p.toString())
        assertTrue(p.any { it.contains("Item gained 9 does not exist") }, p.toString())
        assertTrue(FormRules.problems(ResourceForm, ResourceForm.valuesOf(oak, ctx) + ("health" to "0"), ctx).any { it.startsWith("Gathers before") })
    }

    @Test
    fun `a new resource is valid`() {
        assertEquals(emptyList<String>(), problems(ResourceForm, ResourceForm.create(8, ctx)))
        assertEquals(emptyList<String>(), problems(ShopForm, ShopForm.create(8, ctx)))
    }

    // ---- recipes ----

    private val bread = RecipeDefinition(
        id = 3, name = "Bread", stationType = CraftingStationType.COOKING_FIRE, levelReq = 2,
        ingredients = listOf(RecipeIngredient(5, 2), RecipeIngredient(6, 1)), outputItemId = 7, outputAmount = 3, xpReward = 30L
    )

    @Test
    fun `a recipe round trips and its ingredients are lines`() {
        assertEquals(bread, roundTrip(RecipeForm, bread))
        assertEquals("5 2\n6 1", RecipeForm.formatIngredients(bread.ingredients))
        val parsed = RecipeForm.parseIngredients("5\n6 4\n\n")
        assertEquals(listOf(RecipeIngredient(5, 1), RecipeIngredient(6, 4)), parsed.items)
        assertEquals(3, RecipeForm.parseIngredients("x\n0\n5 0").problems.size)
    }

    @Test
    fun `recipe items must exist`() {
        val catalog = WorldContentCatalogDto(items = listOf(ItemDefinition(5, "Flour"), ItemDefinition(7, "Bread")))
        val context = FormContext(catalog = catalog)
        val p = FormRules.problems(RecipeForm, RecipeForm.valuesOf(bread, context), context)
        assertEquals(listOf("Ingredient item 6 does not exist"), p)
        assertTrue(FormRules.problems(RecipeForm, RecipeForm.valuesOf(bread, context) + ("output" to "99"), context).any { it.startsWith("Item made 99") })
    }

    // ---- quests ----

    private val slimes = QuestDefinition(
        id = 2, name = "Slimes", summary = "Clear them", levelReq = 3, prerequisiteQuestIds = setOf(1), repeatable = true,
        objectives = listOf(
            QuestObjective(QuestObjectiveType.KILL_NPC, 4, 3, "Slay the slimes"),
            QuestObjective(QuestObjectiveType.COLLECT_ITEM, 9, 1, "")
        ),
        startText = "Help!", inProgressText = "Still there?", completionText = "Thanks.",
        reward = QuestReward(xp = 80L, currencyAmount = 10, items = listOf(RecipeIngredient(5, 2)), teachSpellId = 3)
    )

    @Test
    fun `a quest round trips with every field`() {
        assertEquals(slimes, roundTrip(QuestForm, slimes))
        assertEquals("KILL_NPC 4 3 Slay the slimes\nCOLLECT_ITEM 9 1", QuestForm.formatObjectives(slimes.objectives))
    }

    @Test
    fun `objective lines accept short forms and explain mistakes`() {
        val ok = QuestForm.parseObjectives("kill_npc 4\nTALK_TO_NPC 2 1\nVISIT_MAP 5 1 Reach the farm")
        assertEquals(listOf(
            QuestObjective(QuestObjectiveType.KILL_NPC, 4, 1, ""), QuestObjective(QuestObjectiveType.TALK_TO_NPC, 2, 1, ""),
            QuestObjective(QuestObjectiveType.VISIT_MAP, 5, 1, "Reach the farm")
        ), ok.objectives)
        assertTrue(ok.problems.isEmpty())
        assertEquals(4, QuestForm.parseObjectives("FLY 1\nKILL_NPC\nKILL_NPC x\nKILL_NPC 4 0").problems.size)
    }

    @Test
    fun `quest targets and rewards are checked against the catalog`() {
        val catalog = WorldContentCatalogDto(
            items = listOf(ItemDefinition(5, "Flour")), npcs = listOf(com.morgana.domain.content.NpcDefinition(4, "Slime")),
            quests = listOf(slimes.copy(id = 1)), spells = listOf(com.morgana.domain.content.SpellDefinition(3, "Spark"))
        )
        val context = FormContext(catalog = catalog)
        val p = FormRules.problems(QuestForm, QuestForm.valuesOf(slimes, context), context)
        assertEquals(listOf("Objective target 9 does not exist for COLLECT_ITEM"), p)
        val bad = QuestForm.valuesOf(slimes, context) + ("prerequisites" to "8") + ("teachSpell" to "6") + ("rewardItems" to "77")
        val q = FormRules.problems(QuestForm, bad, context)
        assertTrue(q.any { it == "Required quest 8 does not exist" } && q.any { it == "Spell taught 6 does not exist" } && q.any { it == "Reward item 77 does not exist" }, q.toString())
    }

    @Test
    fun `new recipes and quests are valid`() {
        assertEquals(emptyList<String>(), problems(RecipeForm, RecipeForm.create(8, ctx)))
        assertEquals(emptyList<String>(), problems(QuestForm, QuestForm.create(8, ctx)))
    }

    // ---- generic rules ----

    @Test
    fun `id lists`() {
        assertEquals(emptyList<Int>(), FormRules.parseIds(""))
        assertEquals(listOf(1, 2, 3), FormRules.parseIds("1, 2,3"))
        assertEquals(null, FormRules.parseIds("1, a"))
        assertEquals(null, FormRules.parseIds("0"))
    }

    @Test
    fun `map merging removes zero entries and keeps unknown keys`() {
        val merged = FormRules.mergeMap(mapOf("a" to 1, "z" to 9), "x.", listOf("a", "b"), mapOf("x.a" to "0", "x.b" to "4"))
        assertEquals(mapOf("z" to 9, "b" to 4), merged)
        assertEquals(mapOf("a" to 1, "z" to 9), FormRules.mergeMap(mapOf("a" to 1, "z" to 9), "x.", listOf("a"), mapOf("x.a" to "bad")))
    }

    @Test
    fun `an unedited save leaves explicit zero entries exactly as authored`() {
        val spell = bolt.copy(costs = mapOf("mp" to 0))
        assertEquals(spell, roundTrip(SpellForm, spell))
        val edited = SpellForm.apply(spell, SpellForm.valuesOf(spell, ctx) + ("cost.mp" to "4"), ctx)
        assertEquals(mapOf("mp" to 4), edited.costs)
    }

    @Test
    fun `every form's fields have unique keys`() {
        for (form in listOf<ContentForm<*>>(ItemForm, NpcForm, SpellForm, ShopForm, ResourceForm, RecipeForm, QuestForm)) {
            val keys = form.sections(ctx).flatMap { it.fields }.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "${form.title}: duplicate keys")
        }
    }

    @Test
    fun `choice fields only offer real values`() {
        for (form in listOf<ContentForm<*>>(ItemForm, NpcForm, SpellForm, ShopForm, ResourceForm, RecipeForm, QuestForm)) {
            for (f in form.sections(ctx).flatMap { it.fields }.filter { it.type == FormFieldType.CHOICE }) {
                assertTrue(f.choices.isNotEmpty(), "${form.title}/${f.key} has no choices")
                if (!f.optional) assertTrue(f.default in f.choices, "${form.title}/${f.key} default '${f.default}' is not a choice")
            }
        }
    }

    // ---- animations ----

    @Test
    fun `an animation round trips and its limits are checked`() {
        val anim = com.morgana.domain.content.AnimationDefinition(id = 3, name = "Spark", spriteSheetId = 2, frameCount = 8, frameDurationMs = 60, soundId = "zap", renderAboveEntities = false)
        assertEquals(anim, roundTrip(AnimationForm, anim))
        assertEquals(emptyList<String>(), problems(AnimationForm, anim))
        val bad = FormRules.problems(AnimationForm, mapOf("name" to "", "sheet" to "0", "frames" to "99", "duration" to "5"), ctx)
        assertEquals(4, bad.size, bad.toString())
    }
}
