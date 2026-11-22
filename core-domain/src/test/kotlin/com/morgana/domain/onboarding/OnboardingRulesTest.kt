package com.morgana.domain.onboarding

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.UiProfile
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingRulesTest {

    private val game: GameDefinition = MorganaClassic.definition.copy(
        durability = DurabilityDefinition(),
        ghost = GhostDefinition(autoReleaseTicks = 1200),
        ui = UiProfile()
    )
    private val lifeId = game.vitals.first { it.role == VitalRole.LIFE }.id

    private fun hero(
        level: Int = 1,
        points: Int = 0,
        hp: Int = 100,
        inventory: Inventory = Inventory(),
        equipment: EquipmentLoadout = EquipmentLoadout(),
        ghost: Boolean = false,
        known: List<Int> = emptyList(),
        classId: Int = 1
    ) = CharacterState(
        id = "c1", accountId = "a1", name = "Hero", classId = classId, level = level, unspentStatPoints = points,
        vitals = mapOf(lifeId to VitalState(hp, 100)), inventory = inventory, equipment = equipment, isGhost = ghost,
        knownSpellIds = known
    )

    private fun triggers(c: CharacterState, items: Map<Int, ItemDefinition> = emptyMap(), spells: List<SpellDefinition> = emptyList()) =
        OnboardingRules.triggersFor(c, game, items, spells)

    @Test
    fun `a game with no onboarding section gets built-in tips shaped by what it enables`() {
        val ids = OnboardingRules.tips(game).map { it.id }
        assertTrue(ids.containsAll(listOf("welcome", "gear", "skills", "gear_worn", "gear_broken", "low_health", "death")), ids.toString())

        val bare = game.copy(durability = null, ghost = null, ui = UiProfile(spells = false))
        val bareIds = OnboardingRules.tips(bare).map { it.id }
        assertFalse("gear_worn" in bareIds, "no durability, so no repair tip")
        assertFalse("skills" in bareIds, "no spells window, so no skills tip")
        val death = OnboardingRules.tips(bare).first { it.id == "death" }
        assertFalse("ghost" in death.text.lowercase(), death.text)
        val ghostDeath = OnboardingRules.tips(game).first { it.id == "death" }
        assertTrue("ghost" in ghostDeath.text.lowercase())
        assertTrue("/release" in ghostDeath.text)
    }

    @Test
    fun `a game can rewrite, remove, add and switch off tips`() {
        val custom = game.copy(
            onboarding = OnboardingConfig(
                tips = listOf(
                    TipDefinition("death", TipTrigger.DEATH, "Fallen", "Find the shrine."),
                    TipDefinition("low_health", TipTrigger.LOW_HEALTH, text = ""),
                    TipDefinition("promotion", TipTrigger.LEVEL, "Promotion", "See the Calling Master.", level = 30)
                )
            )
        )
        val tips = OnboardingRules.tips(custom)
        assertEquals("Find the shrine.", tips.first { it.id == "death" }.text)
        assertTrue(tips.none { it.id == "low_health" })
        assertEquals(30, tips.first { it.id == "promotion" }.level)
        assertTrue(GameDefinitionValidator.validate(custom).none { it.pointer.startsWith("/onboarding") })

        assertTrue(OnboardingRules.tips(custom.copy(onboarding = OnboardingConfig(enabled = false))).isEmpty())
        val only = OnboardingConfig(defaults = false, tips = listOf(TipDefinition("only", TipTrigger.WELCOME, text = "Hi")))
        assertEquals(listOf("only"), OnboardingRules.tips(custom.copy(onboarding = only)).map { it.id })
    }

    @Test
    fun `bad tips are rejected`() {
        fun bad(vararg tips: TipDefinition) =
            GameDefinitionValidator.validate(game.copy(onboarding = OnboardingConfig(tips = tips.toList()))).filter { it.pointer.startsWith("/onboarding") }
        assertTrue(bad(TipDefinition("has space", TipTrigger.WELCOME, text = "x")).isNotEmpty())
        assertTrue(bad(TipDefinition("a", TipTrigger.WELCOME, text = "x"), TipDefinition("a", TipTrigger.WELCOME, text = "y")).any { "twice" in it.message })
        assertTrue(bad(TipDefinition("nothing", TipTrigger.WELCOME, text = "")).any { it.pointer.endsWith("/text") })
        assertTrue(bad(TipDefinition("lv", TipTrigger.LEVEL, text = "x", level = 0)).any { it.pointer.endsWith("/level") })
        assertTrue(bad(TipDefinition("long", TipTrigger.WELCOME, text = "x".repeat(TipDefinition.MAX_TEXT + 1))).isNotEmpty())
    }

    @Test
    fun `state triggers read the character`() {
        assertEquals(emptySet<TipTrigger>(), triggers(hero()))
        assertTrue(TipTrigger.STAT_POINTS in triggers(hero(points = 3)))
        assertTrue(TipTrigger.LOW_HEALTH in triggers(hero(hp = 20)))
        assertFalse(TipTrigger.LOW_HEALTH in triggers(hero(hp = 0)), "a dead character is not low on health")
        assertFalse(TipTrigger.LOW_HEALTH in triggers(hero(hp = 60)))

        val sword = ItemStack(7, 1, durability = 80)
        val worn = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to sword))
        assertTrue(TipTrigger.GEAR_WORN in triggers(hero(equipment = worn)))
        assertFalse(TipTrigger.GEAR_BROKEN in triggers(hero(equipment = worn)))
        val broken = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to sword.copy(durability = 0)))
        assertTrue(TipTrigger.GEAR_BROKEN in triggers(hero(equipment = broken)))
        val fresh = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(7, 1)))
        assertFalse(TipTrigger.GEAR_WORN in triggers(hero(equipment = fresh)))
    }

    @Test
    fun `a ghost gets no state tips`() {
        assertEquals(emptySet<TipTrigger>(), triggers(hero(points = 5, hp = 10, ghost = true)))
    }

    @Test
    fun `skills are offered once the level and class allow one the character does not know`() {
        val fire = SpellDefinition(id = 1, name = "Fire", levelReq = 5, classReqIds = setOf(2))
        val spells = listOf(fire)
        assertFalse(TipTrigger.SKILL_AVAILABLE in triggers(hero(level = 4, classId = 2), spells = spells))
        assertTrue(TipTrigger.SKILL_AVAILABLE in triggers(hero(level = 5, classId = 2), spells = spells))
        assertFalse(TipTrigger.SKILL_AVAILABLE in triggers(hero(level = 9, classId = 3), spells = spells), "another class has this spell")
        assertFalse(TipTrigger.SKILL_AVAILABLE in triggers(hero(level = 9, classId = 2, known = listOf(1)), spells = spells), "already known")
    }

    @Test
    fun `unworn gear in the bag triggers the equip tip only for a free slot the character may use`() {
        val sword = ItemDefinition(id = 7, name = "Sword", type = ItemType.WEAPON, levelReq = 3)
        val items = mapOf(7 to sword)
        val bag = Inventory().let { inv -> inv.copy(slots = inv.slots.toMutableList().also { it[0] = ItemStack(7, 1) }) }
        assertFalse(TipTrigger.EQUIPMENT_FOUND in triggers(hero(level = 1, inventory = bag), items), "too low a level")
        assertTrue(TipTrigger.EQUIPMENT_FOUND in triggers(hero(level = 3, inventory = bag), items))
        val wielding = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(7, 1)))
        assertFalse(TipTrigger.EQUIPMENT_FOUND in triggers(hero(level = 3, inventory = bag, equipment = wielding), items), "the slot is taken")
    }

    @Test
    fun `next picks urgent tips first, skips what was seen and honours level tips`() {
        val tips = listOf(
            TipDefinition("welcome", TipTrigger.WELCOME, text = "w"),
            TipDefinition("stat_points", TipTrigger.STAT_POINTS, text = "s"),
            TipDefinition("death", TipTrigger.DEATH, text = "d"),
            TipDefinition("promo", TipTrigger.LEVEL, text = "p", level = 30)
        )
        val c = hero(level = 1)
        val both = setOf(TipTrigger.WELCOME, TipTrigger.STAT_POINTS)
        assertNull(OnboardingRules.next(c, tips, emptySet(), emptySet()))
        assertEquals("welcome", OnboardingRules.next(c, tips, both, emptySet())?.id)
        assertEquals("stat_points", OnboardingRules.next(c, tips, both, setOf("welcome"))?.id)
        assertEquals("death", OnboardingRules.next(c, tips, setOf(TipTrigger.WELCOME, TipTrigger.DEATH), emptySet())?.id, "urgent first")
        assertEquals("promo", OnboardingRules.next(hero(level = 30), tips, emptySet(), emptySet())?.id)
        assertNull(OnboardingRules.next(hero(level = 30), tips, emptySet(), setOf("promo")))
        assertNotNull(OnboardingRules.next(hero(level = 31), tips, emptySet(), emptySet()))
    }
}
