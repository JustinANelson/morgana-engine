package com.morgana.client.ui

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DungeonHudModelTest {

    private fun npcDef(id: Int, name: String, title: String = "", behavior: NpcBehavior = NpcBehavior.ATTACK_ON_SIGHT) =
        NpcDefinition(id = id, name = name, title = title, spriteId = 1, behavior = behavior)

    private fun npcInstance(instanceId: String, npcId: Int, curHp: Int, maxHp: Int, respawnAtTick: Long = 0L) =
        MapNpcInstance(
            instanceId = instanceId,
            spawnSlotIndex = 0,
            npcId = npcId,
            mapId = 2,
            tileX = 5,
            tileY = 5,
            homeX = 5,
            homeY = 5,
            vitals = mapOf("hp" to VitalState(cur = curHp, max = maxHp)),
            respawnAtTick = respawnAtTick
        )

    @Test
    fun `town map without generator or boss has no floor badge`() {
        assertNull(DungeonHudModel.floorBadge("Brightwater", hasGenerator = false, hasAliveBoss = false))
    }

    @Test
    fun `dungeon floor name parses floor number and marks generator boss floors`() {
        val floor1 = DungeonHudModel.floorBadge("The Sunken Ruins (floor 1)", hasGenerator = true, hasAliveBoss = false, hasGeneratorBoss = false)
        assertNotNull(floor1)
        assertEquals("Floor 1 · The Sunken Ruins", floor1!!.label)
        assertFalse(floor1.isBossFloor)

        val floor3NoBossFlag = DungeonHudModel.floorBadge("The Sunken Ruins (floor 3)", hasGenerator = true, hasAliveBoss = false, hasGeneratorBoss = false)
        assertFalse(floor3NoBossFlag!!.isBossFloor)

        val floor4Boss = DungeonHudModel.floorBadge("Frost Caverns (floor 4)", hasGenerator = true, hasAliveBoss = false, hasGeneratorBoss = true)
        assertNotNull(floor4Boss)
        assertEquals("Floor 4 · Frost Caverns", floor4Boss!!.label)
        assertTrue(floor4Boss.isBossFloor)
    }

    @Test
    fun `isBossNpc recognizes floorBossNpcId and title fallback without matching shopkeepers`() {
        val plain = npcDef(10, "Cave Slime")
        val legacyTitle = npcDef(12, "Ruin Gargoyle", title = "Guardian of the stairs")
        val shop = npcDef(13, "Mira", title = "Boss Quartermaster", behavior = NpcBehavior.SHOPKEEPER)

        assertFalse(DungeonHudModel.isBossNpc(10, plain, floorBossNpcId = 0))
        assertTrue(DungeonHudModel.isBossNpc(10, plain, floorBossNpcId = 10))
        assertTrue(DungeonHudModel.isBossNpc(12, legacyTitle, floorBossNpcId = 0))
        assertFalse(DungeonHudModel.isBossNpc(13, shop, floorBossNpcId = 0))
    }

    @Test
    fun `activeBoss picks alive guardian or boss and ignores regular mobs or dead bosses`() {
        val defs = mapOf(
            20 to npcDef(20, "Cave Slime"),
            24 to npcDef(24, "Ruin Gargoyle", title = "Guardian of the stairs"),
            38 to npcDef(38, "Vorlash, the Ember King", title = "The last guardian")
        )
        val regularOnly = listOf(npcInstance("m1", 20, 40, 40))
        assertNull(DungeonHudModel.activeBoss(regularOnly, defs))

        val deadBoss = listOf(npcInstance("b1", 24, 0, 600, respawnAtTick = 100L))
        assertNull(DungeonHudModel.activeBoss(deadBoss, defs))

        val aliveBoss = listOf(
            npcInstance("m1", 20, 40, 40),
            npcInstance("b1", 24, 320, 600),
            npcInstance("b2", 38, 2500, 3360)
        )
        val boss = DungeonHudModel.activeBoss(aliveBoss, defs)
        assertNotNull(boss)
        assertEquals("Vorlash, the Ember King", boss!!.name)
        assertEquals("The last guardian", boss.title)
        assertEquals(2500, boss.curHp)
        assertEquals(3360, boss.maxHp)
    }

    @Test
    fun `floorEntryToast formats normal dungeon floor, guardian floor, and town return`() {
        val normal = DungeonHudModel.floorEntryToast("The Sunken Ruins (floor 2)", hasGenerator = true, hasAliveBoss = false)
        assertEquals("Entered Floor 2 · The Sunken Ruins", normal.text)
        assertEquals(ToastKind.INFO, normal.kind)

        val boss = DungeonHudModel.floorEntryToast("The Sunken Ruins (floor 3)", hasGenerator = true, hasAliveBoss = true)
        assertEquals("Entered Floor 3 · The Sunken Ruins — Guardian Floor", boss.text)
        assertEquals(ToastKind.WARNING, boss.kind)

        val town = DungeonHudModel.floorEntryToast("Brightwater", hasGenerator = false, hasAliveBoss = false)
        assertEquals("Entered Brightwater", town.text)
        assertEquals(ToastKind.INFO, town.kind)
    }

    @Test
    fun `bossDefeatedToast fires only when an alive non-shopkeeper boss dies`() {
        val bossDef = npcDef(24, "Vorlash, the Ember King", title = "The last guardian")
        val mobDef = npcDef(20, "Ash Imp")
        val shopDef = npcDef(30, "Mira", title = "Boss Quartermaster", behavior = NpcBehavior.SHOPKEEPER)

        val killed = DungeonHudModel.bossDefeatedToast(wasAlive = true, isNowAlive = false, npcId = 24, npcDef = bossDef)
        assertNotNull(killed)
        assertEquals("Vorlash, the Ember King defeated!", killed!!.text)
        assertEquals(ToastKind.LEVEL, killed.kind)

        assertNull(DungeonHudModel.bossDefeatedToast(wasAlive = false, isNowAlive = false, npcId = 24, npcDef = bossDef))
        assertNull(DungeonHudModel.bossDefeatedToast(wasAlive = true, isNowAlive = true, npcId = 24, npcDef = bossDef))
        assertNull(DungeonHudModel.bossDefeatedToast(wasAlive = true, isNowAlive = false, npcId = 20, npcDef = mobDef))
        assertNull(DungeonHudModel.bossDefeatedToast(wasAlive = true, isNowAlive = false, npcId = 30, npcDef = shopDef))
    }

    @Test
    fun `depth names count as floors and any monster with an encounter is a boss`() {
        assertEquals("Floor 2 · The Crypt", DungeonHudModel.floorBadge("The Crypt (depth 2)", hasGenerator = true, hasAliveBoss = false)!!.label)
        val scripted = npcDef(40, "Bone Colossus").copy(encounter = com.morgana.domain.content.BossEncounter(phases = listOf(com.morgana.domain.content.BossPhase(100))))
        assertTrue(DungeonHudModel.isBossNpc(40, scripted), "a scripted encounter marks a boss without a guardian title")
        assertFalse(DungeonHudModel.isBossNpc(41, npcDef(41, "Cave Slime")))
    }
}
