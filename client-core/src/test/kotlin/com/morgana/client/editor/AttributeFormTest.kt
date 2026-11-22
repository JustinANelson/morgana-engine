package com.morgana.client.editor

import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.VitalType
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttributeFormTest {

    private val samples: List<TileAttribute> = listOf(
        TileAttribute.None,
        TileAttribute.Blocked,
        TileAttribute.DirectionalBlock(blockUp = true, blockRight = true),
        TileAttribute.Warp(3, 12, 7, Direction.LEFT),
        TileAttribute.Warp(2, 0, 0, null),
        TileAttribute.ItemSpawn(5, 3, 90),
        TileAttribute.NpcAvoid,
        TileAttribute.KeyDoor(9, consumeKey = true, unlockDurationTicks = 40),
        TileAttribute.ResourceNode(4),
        TileAttribute.ShopTile(2),
        TileAttribute.BankTile,
        TileAttribute.HealTrap(VitalType.MP, 25, isTrapDamage = true, intervalTicks = 10, animationId = 3),
        TileAttribute.Slide(Direction.RIGHT),
        TileAttribute.LightSource(200f, "#112233", 0.5f, flicker = false),
        TileAttribute.SoundEmitter("wind", 6, 30)
    )

    @Test
    fun `every attribute round trips through its form values`() {
        for (a in samples) {
            val kind = AttributeForm.kindOf(a)
            assertEquals(a, AttributeForm.build(kind, AttributeForm.valuesOf(a)), "round trip of $a")
            assertTrue(AttributeForm.problems(kind, AttributeForm.valuesOf(a)).isEmpty(), "problems for $a")
        }
    }

    @Test
    fun `every kind has a sample and a distinct label`() {
        assertEquals(AttributeKind.entries.toSet(), samples.map { AttributeForm.kindOf(it) }.toSet())
        assertEquals(AttributeKind.entries.size, AttributeKind.entries.map { it.label }.toSet().size)
    }

    @Test
    fun `defaults build a valid attribute for every kind`() {
        for (kind in AttributeKind.entries) {
            val values = AttributeForm.defaults(kind)
            val problems = AttributeForm.problems(kind, values)
            // The one-way block's default blocks the top edge, so it is valid too.
            assertTrue(problems.isEmpty(), "${kind.label}: $problems")
            assertEquals(kind, AttributeForm.kindOf(AttributeForm.build(kind, values)))
        }
    }

    @Test
    fun `bad numbers are reported and building falls back to the default`() {
        val values = mapOf("map" to "abc", "x" to "-4", "y" to "9")
        val problems = AttributeForm.problems(AttributeKind.WARP, values)
        assertEquals(2, problems.size)
        assertTrue(problems.any { it.startsWith("Target map") })
        assertTrue(problems.any { it.startsWith("Target X") })
        assertEquals(TileAttribute.Warp(1, 0, 9, null), AttributeForm.build(AttributeKind.WARP, values))
    }

    @Test
    fun `numbers are clamped to the field minimum`() {
        val built = AttributeForm.build(AttributeKind.LIGHT_SOURCE, mapOf("radius" to "2", "intensity" to "7")) as TileAttribute.LightSource
        assertEquals(16f, built.radiusPixels)
        assertEquals(1f, built.intensity)
    }

    @Test
    fun `colour must be a hex colour and sound cannot be empty`() {
        assertEquals(1, AttributeForm.problems(AttributeKind.LIGHT_SOURCE, mapOf("color" to "orange")).size)
        assertEquals("#FFAA44", (AttributeForm.build(AttributeKind.LIGHT_SOURCE, mapOf("color" to "orange")) as TileAttribute.LightSource).colorHex)
        assertEquals(1, AttributeForm.problems(AttributeKind.SOUND_EMITTER, mapOf("sound" to "  ")).size)
    }

    @Test
    fun `a one-way block needs at least one edge`() {
        val none = mapOf("up" to "false", "down" to "false", "left" to "false", "right" to "false")
        assertEquals(1, AttributeForm.problems(AttributeKind.DIRECTIONAL_BLOCK, none).size)
    }

    @Test
    fun `descriptions are short and name the details`() {
        assertEquals("Warp to map 3 (12, 7)", AttributeForm.describe(TileAttribute.Warp(3, 12, 7)))
        assertEquals("One-way block (up, right)", AttributeForm.describe(TileAttribute.DirectionalBlock(blockUp = true, blockRight = true)))
        assertEquals("None", AttributeForm.describe(TileAttribute.None))
    }
}
