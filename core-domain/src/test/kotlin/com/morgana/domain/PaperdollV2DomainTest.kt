package com.morgana.domain

import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.PaperdollComposer
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.spatial.Direction
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperdollV2DomainTest {

    @Test
    fun `composeLayers applies direction-dependent slot ordering for down vs up`() {
        val raw = listOf(
            PaperdollLayer(PaperdollSlot.MAIN_HAND, spriteId = 101, tintHex = "#ff0000"),
            PaperdollLayer(PaperdollSlot.BACK, spriteId = 501),
            PaperdollLayer(PaperdollSlot.TORSO, spriteId = 301),
            PaperdollLayer(PaperdollSlot.BODY, spriteId = 1),
            PaperdollLayer(PaperdollSlot.HEAD, spriteId = 401)
        )

        val facingDown = PaperdollComposer.composeLayers(raw, Direction.DOWN).map { it.slot }
        assertEquals(
            listOf(PaperdollSlot.BACK, PaperdollSlot.BODY, PaperdollSlot.TORSO, PaperdollSlot.HEAD, PaperdollSlot.MAIN_HAND),
            facingDown
        )

        // When facing UP, weapon draws behind the body while cloak/back draws over torso.
        val facingUp = PaperdollComposer.composeLayers(raw, Direction.UP).map { it.slot }
        assertEquals(
            listOf(PaperdollSlot.MAIN_HAND, PaperdollSlot.BODY, PaperdollSlot.TORSO, PaperdollSlot.HEAD, PaperdollSlot.BACK),
            facingUp
        )
    }

    @Test
    fun `hide rules suppress covered slots without hiding the hiding slot itself`() {
        val helmet = ItemDefinition(
            id = 10,
            name = "Full Greathelm",
            type = ItemType.HELMET,
            paperdollSpriteId = 401,
            paperdollHidesSlots = setOf(PaperdollSlot.HAIR, PaperdollSlot.HEAD) // HEAD cannot hide itself
        )
        val tunic = ItemDefinition(
            id = 11,
            name = "Dyed Tunic",
            type = ItemType.ARMOR,
            paperdollSpriteId = 301,
            paperdollTintHex = "#22c55e"
        )
        val catalog = mapOf(10 to helmet, 11 to tunic)
        val base = listOf(
            PaperdollLayer(PaperdollSlot.BODY, spriteId = 1, tintHex = "#fde68a"),
            PaperdollLayer(PaperdollSlot.HAIR, spriteId = 2, tintHex = "#78350f")
        )
        val equip = EquipmentLoadout(
            mapOf(
                EquipmentSlot.HELMET to ItemStack(10, 1),
                EquipmentSlot.ARMOR to ItemStack(11, 1)
            )
        )

        val layers = PaperdollComposer.resolveCharacterLayers(base, equip, catalog, Direction.DOWN)
        assertEquals(
            listOf(
                PaperdollLayer(PaperdollSlot.BODY, spriteId = 1, tintHex = "#fde68a"),
                PaperdollLayer(PaperdollSlot.TORSO, spriteId = 301, tintHex = "#22c55e"),
                PaperdollLayer(PaperdollSlot.HEAD, spriteId = 401, hidesSlots = setOf(PaperdollSlot.HAIR, PaperdollSlot.HEAD))
            ),
            layers
        )
    }

    @Test
    fun `content validation checks item paperdollTintHex format`() {
        val game = MorganaClassic.definition

        val bad = ItemDefinition(id = 1, name = "Bad Dye", type = ItemType.ARMOR, paperdollTintHex = "red")
        val badReport = ContentValidator.validate(ContentCatalog(items = mapOf(1 to bad)), game)
        assertTrue(badReport.errors.any { it.pointer == "/paperdollTintHex" }, badReport.errors.toString())

        val good = ItemDefinition(id = 2, name = "Good Dye", type = ItemType.ARMOR, paperdollTintHex = "#38BDF8")
        val goodReport = ContentValidator.validate(ContentCatalog(items = mapOf(2 to good)), game)
        assertTrue(goodReport.errors.none { it.pointer == "/paperdollTintHex" }, goodReport.errors.toString())
    }

    @Test
    fun `a blade worn in the off-hand slot uses its off-hand picture and the far hand sits behind the body`() {
        val blade = ItemDefinition(
            id = 20, name = "Iron Blade", type = ItemType.WEAPON,
            paperdollSpriteId = 601, paperdollOffHandSpriteId = 602
        )
        val plain = ItemDefinition(id = 21, name = "Dirk", type = ItemType.WEAPON, paperdollSpriteId = 603)
        val catalog = mapOf(20 to blade, 21 to plain)
        val equip = EquipmentLoadout(
            mapOf(EquipmentSlot.WEAPON to ItemStack(20, 1), EquipmentSlot.SHIELD to ItemStack(20, 1))
        )
        val layers = equip.resolvedPaperdollLayers(catalog)
        assertEquals(601, layers.single { it.slot == PaperdollSlot.MAIN_HAND }.spriteId)
        assertEquals(602, layers.single { it.slot == PaperdollSlot.OFF_HAND }.spriteId)
        // With no off-hand picture the main picture is reused.
        val reuse = EquipmentLoadout(mapOf(EquipmentSlot.SHIELD to ItemStack(21, 1))).resolvedPaperdollLayers(catalog)
        assertEquals(603, reuse.single().spriteId)

        val withBody = layers + PaperdollLayer(PaperdollSlot.BODY, spriteId = 1) + PaperdollLayer(PaperdollSlot.BACK, spriteId = 9)
        assertEquals(
            listOf(PaperdollSlot.BACK, PaperdollSlot.MAIN_HAND, PaperdollSlot.BODY, PaperdollSlot.OFF_HAND),
            PaperdollComposer.composeLayers(withBody, Direction.LEFT).map { it.slot }
        )
        assertEquals(
            listOf(PaperdollSlot.BACK, PaperdollSlot.OFF_HAND, PaperdollSlot.BODY, PaperdollSlot.MAIN_HAND),
            PaperdollComposer.composeLayers(withBody, Direction.RIGHT).map { it.slot }
        )
    }

    @Test
    fun `one item is worn in each class's own colour and falls back to its plain tint`() {
        val armor = ItemDefinition(
            id = 30, name = "Tunic", type = ItemType.ARMOR, paperdollSpriteId = 700,
            paperdollTintHex = "#888888", paperdollClassTints = mapOf(1 to "#d9453a", 3 to "#5fae3f")
        )
        val equip = EquipmentLoadout(mapOf(EquipmentSlot.ARMOR to ItemStack(30, 1)))
        val catalog = mapOf(30 to armor)
        assertEquals("#d9453a", equip.resolvedPaperdollLayers(catalog, classId = 1).single().tintHex)
        assertEquals("#5fae3f", equip.resolvedPaperdollLayers(catalog, classId = 3).single().tintHex)
        assertEquals("#888888", equip.resolvedPaperdollLayers(catalog, classId = 2).single().tintHex)
        assertEquals("#888888", equip.resolvedPaperdollLayers(catalog).single().tintHex)
    }

    @Test
    fun `only gear of the game's highest rarity glows`() {
        val sword = ItemDefinition(id = 40, name = "Blade", type = ItemType.WEAPON, paperdollSpriteId = 800)
        val catalog = mapOf(40 to sword)
        fun layers(rarity: String?, top: String?) = EquipmentLoadout(
            mapOf(EquipmentSlot.WEAPON to ItemStack(40, 1, rolls = rarity?.let { com.morgana.domain.item.ItemRolls(it) }))
        ).resolvedPaperdollLayers(catalog, topRarityId = top)

        assertTrue(layers("super", "super").single().glow)
        assertEquals(false, layers("magic", "super").single().glow, "a lower rarity does not glow")
        assertEquals(false, layers(null, "super").single().glow, "a plain item does not glow")
        assertEquals(false, layers("super", null).single().glow, "no top rarity (not a rolling game) means no glow")
    }

    @Test
    fun `the top rarity is the last listed tier, and a single tier has none`() {
        fun tier(id: String) = com.morgana.domain.item.RarityDefinition(id = id, name = id, weight = 1)
        assertEquals("super", com.morgana.domain.item.LootRules(rarities = listOf(tier("normal"), tier("magic"), tier("super"))).topRarityId)
        assertEquals(null, com.morgana.domain.item.LootRules(rarities = listOf(tier("normal"))).topRarityId)
        assertEquals(null, com.morgana.domain.item.LootRules().topRarityId)
    }

    @Test
    fun `gloves have their own layer over the torso and under hair, headgear and held weapons in every facing`() {
        assertEquals(PaperdollSlot.HANDS, EquipmentSlot("GLOVES").defaultPaperdollSlot())
        assertEquals(PaperdollSlot.BACK, EquipmentSlot("CLOAK").defaultPaperdollSlot(), "a cloak hangs on the back like a cape")
        assertEquals(PaperdollSlot.ACCESSORY, EquipmentSlot("CHARM").defaultPaperdollSlot())
        for (facing in listOf(Direction.DOWN, Direction.UP, Direction.LEFT, Direction.RIGHT)) {
            val torso = PaperdollComposer.drawOrderRank(PaperdollSlot.TORSO, facing)
            val hands = PaperdollComposer.drawOrderRank(PaperdollSlot.HANDS, facing)
            assertTrue(hands > torso, "$facing: gloves over the torso's sleeves")
            for (above in listOf(PaperdollSlot.HAIR, PaperdollSlot.HEAD, PaperdollSlot.ACCESSORY)) {
                assertTrue(hands < PaperdollComposer.drawOrderRank(above, facing), "$facing: gloves under $above")
            }
            assertEquals(PaperdollSlot.entries.size, PaperdollSlot.entries.map { PaperdollComposer.drawOrderRank(it, facing) }.toSet().size, "$facing: every slot has its own rank")
        }
        val layers = listOf(
            PaperdollLayer(PaperdollSlot.HANDS, spriteId = 61), PaperdollLayer(PaperdollSlot.TORSO, spriteId = 31), PaperdollLayer(PaperdollSlot.BODY, spriteId = 1),
            PaperdollLayer(PaperdollSlot.MAIN_HAND, spriteId = 9)
        )
        assertEquals(
            listOf(PaperdollSlot.BODY, PaperdollSlot.TORSO, PaperdollSlot.HANDS, PaperdollSlot.MAIN_HAND),
            PaperdollComposer.composeLayers(layers, Direction.DOWN).map { it.slot }
        )
    }
}
