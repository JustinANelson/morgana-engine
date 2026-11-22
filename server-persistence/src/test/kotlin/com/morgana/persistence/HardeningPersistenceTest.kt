package com.morgana.persistence

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.VitalType
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class HardeningPersistenceTest {

    // ---- Password hashing -------------------------------------------------------------------------------

    @Test
    fun `password hashes are salted, verifiable, and tamper-resistant`() {
        val a = PasswordHasher.hash("correct horse")
        val b = PasswordHasher.hash("correct horse")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("pbkdf2-sha256$${PasswordHasher.ITERATIONS}$"))
        assertTrue(PasswordHasher.verify("correct horse", a))
        assertFalse(PasswordHasher.verify("Correct horse", a))
        assertFalse(PasswordHasher.verify("correct horse", a.dropLast(2) + "xx"))
        assertFalse(PasswordHasher.verify("x", "garbage"))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256${'$'}notanumber${'$'}abc${'$'}def"))
        assertFalse(PasswordHasher.needsUpgrade(a))
    }

    @Test
    fun `legacy sha256 and low-iteration hashes verify but are flagged for upgrade`() {
        val legacy = MessageDigest.getInstance("SHA-256").digest("pw".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(PasswordHasher.verify("pw", legacy))
        assertFalse(PasswordHasher.verify("other", legacy))
        assertTrue(PasswordHasher.needsUpgrade(legacy))

        val salt = ByteArray(16) { it.toByte() }
        val weakKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("pw".toCharArray(), salt, 1000, 256)).encoded
        val enc = Base64.getUrlEncoder().withoutPadding()
        val weak = "pbkdf2-sha256$1000$${enc.encodeToString(salt)}$${enc.encodeToString(weakKey)}"
        assertTrue(PasswordHasher.verify("pw", weak))
        assertTrue(PasswordHasher.needsUpgrade(weak))
    }

    // ---- Tiled round trip ---------------------------------------------------------------------------------

    private val everyAttribute: List<TileAttribute> = listOf(
        TileAttribute.Blocked, // 0
        TileAttribute.DirectionalBlock(blockUp = true, blockRight = true), // 1
        TileAttribute.Warp(targetMapId = 2, targetX = 7, targetY = 4, targetDirection = Direction.LEFT), // 2
        TileAttribute.ItemSpawn(itemId = 3, amount = 5, respawnTicks = 77), // 3
        TileAttribute.NpcAvoid, // 4
        TileAttribute.KeyDoor(keyItemId = 4, consumeKey = true, unlockDurationTicks = 55), // 5
        TileAttribute.ResourceNode(resourceId = 2), // 6
        TileAttribute.ShopTile(shopId = 3), // 7
        TileAttribute.BankTile, // 8
        TileAttribute.HealTrap(VitalType.MP, amount = 7, isTrapDamage = true, intervalTicks = 33, animationId = 2), // 9
        TileAttribute.Slide(Direction.UP), // 10
        TileAttribute.LightSource(radiusPixels = 99f, colorHex = "#112233", intensity = 0.5f, flicker = false), // 11
        TileAttribute.SoundEmitter(soundId = "rain", radiusTiles = 4, intervalTicks = 10) // 12
    )

    private fun everythingMap(): MapDefinition {
        val base = MapDefinition(id = 7, name = "Everything", width = 8, height = 6)
        val cells = base.cells.toMutableList()
        everyAttribute.forEachIndexed { i, attr -> cells[i] = MapCell(attribute = attr) }
        return base.copy(
            cells = cells,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 4, spawnX = 3, spawnY = 3, respawnTicks = 120)),
            eventIds = listOf(1, 2)
        )
    }

    @Test
    fun `tiled round trip preserves every tile attribute with its parameters, npc spawns, and event ids`() {
        val map = everythingMap()
        val back = TiledMapConverter.importFromTiledJson(TiledMapConverter.exportToTiledJson(map))
        everyAttribute.forEachIndexed { i, expected -> assertEquals(expected, back.cells[i].attribute, "cell $i") }
        assertEquals(map.cells.map { it.attribute }, back.cells.map { it.attribute })
        assertEquals(map.npcSpawns, back.npcSpawns)
        assertEquals(map.eventIds, back.eventIds)
    }

    @Test
    fun `cells edited in Tiled take the new simple code while untouched cells keep their parameters`() {
        val doc = TiledMapConverter.exportToTiled(everythingMap())
        val collision = doc.layers.first { it.name == "COLLISION" }
        val edited = collision.data.toMutableList()
        edited[0] = 0 // Blocked -> cleared
        edited[5] = 6 // KeyDoor -> Bank by hand
        edited[9] = 3 // HealTrap -> Warp code, but a hand-typed Warp has no parameters
        edited[13] = 1 // empty cell -> Blocked
        val newDoc = doc.copy(layers = doc.layers.map { if (it.name == "COLLISION") it.copy(data = edited) else it })

        val back = TiledMapConverter.importFromTiled(newDoc)
        assertEquals(TileAttribute.None, back.cells[0].attribute)
        assertEquals(TileAttribute.BankTile, back.cells[5].attribute)
        assertEquals(TileAttribute.None, back.cells[9].attribute)
        assertEquals(TileAttribute.Blocked, back.cells[13].attribute)
        assertEquals(everyAttribute[2], back.cells[2].attribute, "untouched warp keeps target map, tile, and direction")
        assertEquals(everyAttribute[12], back.cells[12].attribute)
    }

    @Test
    fun `maps authored purely in Tiled import only the parameter-free codes instead of inventing parameters`() {
        val doc = TiledMapConverter.exportToTiled(everythingMap())
        val bare = doc.copy(properties = doc.properties.filterNot { it.name == "morganaAttributes" })
        val back = TiledMapConverter.importFromTiled(bare)
        assertEquals(TileAttribute.Blocked, back.cells[0].attribute)
        assertEquals(TileAttribute.BankTile, back.cells[8].attribute)
        assertEquals(TileAttribute.None, back.cells[2].attribute, "no fabricated Warp(2,1,1)")
        assertEquals(TileAttribute.None, back.cells[7].attribute, "no fabricated ShopTile(1)")
    }
}
