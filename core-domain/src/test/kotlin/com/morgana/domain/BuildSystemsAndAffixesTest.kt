package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BoonDefinition
import com.morgana.domain.game.BuildBonuses
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.RunRulesDefinition
import com.morgana.domain.game.SkillNodeDefinition
import com.morgana.domain.game.SkillTreeDefinition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.RolledAffix
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSystemsAndAffixesTest {

    @Test
    fun `boons offer, stack, grant bonuses, and clear runOnly boons when run ends`() {
        val def = MorganaClassic.definition.copy(
            runs = RunRulesDefinition(enabled = true),
            boons = listOf(
                BoonDefinition("fury", "Blood Fury", "More STR and damage", maxStacks = 2, runOnly = true, attributeBonuses = mapOf("str" to 5), attackDamageBonus = 8, lifeLeechPercentBonus = 20),
                BoonDefinition("aegis", "Iron Aegis", "Extra armour", maxStacks = 1, runOnly = false, armorDefenseBonus = 10)
            )
        )
        val sim = WorldSimulation(SeededRandomSource(42L), game = CompiledGame.compile(def))
        sim.registerMap(
            MapDefinition(id = 1, name = "Hub", width = 10, height = 10, moral = MapMoral.SAFE_ZONE)
                .withCell(5, 4, MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 0, targetY = 0)))
        )
        sim.registerMap(
            MapDefinition(
                id = 2, name = "Crypt (floor 1)", width = 10, height = 10, instanced = true, moral = MapMoral.INSTANCED_DUNGEON,
                generator = FloorGenerator(width = 24, height = 20, roomsMin = 3, roomsMax = 4, nextMapId = 0, exitMapId = 1, exitX = 5, exitY = 5)
            )
        )
        val hero = CharacterState(id = "h1", accountId = "a1", name = "Hero", level = 5, classId = 1, position = WorldPosition.fromTile(1, 5, 5, Direction.UP))
        sim.spawnOrUpdatePlayer(hero)
        sim.movePlayer("h1", Direction.UP)
        sim.stepTick()

        // Offer and pick both boons
        sim.executeAdminSlashCommand("h1", "/boon offer")
        assertEquals(2, sim.pendingBoonOffers["h1"]?.size)
        sim.executeAdminSlashCommand("h1", "/boon pick 1")
        sim.executeAdminSlashCommand("h1", "/boon offer")
        sim.executeAdminSlashCommand("h1", "/boon pick 1")

        val pWithBoons = sim.players["h1"]!!
        assertTrue(BuildBonuses.boonStacks(pWithBoons, "fury") + BuildBonuses.boonStacks(pWithBoons, "aegis") >= 2)
        // Abandon run -> runOnly boon ('fury') is cleared, permanent boon ('aegis') stays if picked
        sim.executeAdminSlashCommand("h1", "/abandon")
        val after = sim.players["h1"]!!
        assertEquals(0, BuildBonuses.boonStacks(after, "fury"), "runOnly boon cleared on run end")
    }

    @Test
    fun `skill tree enforces prerequisites, spends points, grants spells, and boosts attributes`() {
        val def = MorganaClassic.definition.copy(
            skillTree = SkillTreeDefinition(
                enabled = true,
                pointsPerLevel = 1,
                nodes = listOf(
                    SkillNodeDefinition("might", "Might", maxRank = 2, minLevel = 1, attributeBonuses = mapOf("str" to 4), attackDamageBonus = 3),
                    SkillNodeDefinition("cleave", "Cleave", maxRank = 1, minLevel = 2, requires = setOf("might"), grantsSpellId = 9)
                )
            )
        )
        val sim = WorldSimulation(SeededRandomSource(11L), game = CompiledGame.compile(def))
        sim.spells[9] = SpellDefinition(id = 9, name = "Cleave", targetType = SpellTargetType.AOE_SELF_CENTERED, aoeRadiusTiles = 1)
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        val hero = CharacterState(id = "h1", accountId = "a1", name = "Hero", level = 3, classId = 1, position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN))
        sim.spawnOrUpdatePlayer(hero)
        val baseStr = hero.effectiveAttributes(sim.game, sim.items).getValue("str")

        // Cannot learn cleave before prerequisite 'might'
        sim.executeAdminSlashCommand("h1", "/skills learn cleave")
        assertEquals(0, BuildBonuses.skillRank(sim.players["h1"]!!, "cleave"))
        assertFalse(9 in sim.players["h1"]!!.knownSpellIds)

        // Learn might then cleave (2 points available at level 3)
        sim.executeAdminSlashCommand("h1", "/skills learn might")
        sim.executeAdminSlashCommand("h1", "/skills learn cleave")
        val updated = sim.players["h1"]!!
        assertEquals(1, BuildBonuses.skillRank(updated, "might"))
        assertEquals(1, BuildBonuses.skillRank(updated, "cleave"))
        assertEquals(0, BuildBonuses.availableSkillPoints(updated, def.skillTree!!))
        assertTrue(9 in updated.knownSpellIds, "skill node taught its spell")
        assertEquals(baseStr + 4, updated.effectiveAttributes(sim.game, sim.items).getValue("str"))
    }

    @Test
    fun `life leech affix heals attacker on melee hit and crit affix adds crit chance`() {
        val sim = WorldSimulation(SeededRandomSource(7L), game = MorganaClassic.COMPILED)
        val sword = ItemDefinition(id = 1, name = "Vampiric Blade", type = ItemType.WEAPON, attackDamage = 40)
        sim.items[1] = sword
        sim.npcs[10] = NpcDefinition(id = 10, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 500), attackDamage = 0, aggroRangeTiles = 0)
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 10, height = 10, npcSpawns = listOf(MapNpcSpawnSlot(0, 10, 6, 5))))

        val rolledBlade = ItemStack(
            itemId = 1,
            amount = 1,
            rolls = ItemRolls(
                rarity = "rare",
                itemLevel = 5,
                affixes = listOf(
                    RolledAffix("vamp", AffixStat.LIFE_LEECH_PERCENT, value = 25),
                    RolledAffix("keen", AffixStat.CRIT_CHANCE_BP, value = 1000)
                )
            )
        )
        val equip = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to rolledBlade))
        assertEquals(25, equip.totalLifeLeechPercent(sim.items))
        assertEquals(1000, equip.totalCritBonusBp(sim.items))

        val hero = CharacterState(id = "h1", accountId = "a1", name = "Hero", level = 5, classId = 1, position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT), equipment = equip)
        sim.spawnOrUpdatePlayer(hero)
        val wounded = sim.players["h1"]!!.let { p ->
            val hp = p.vitals.getValue("hp")
            p.copy(vitals = p.vitals + ("hp" to hp.copy(cur = 20)))
        }
        sim.spawnOrUpdatePlayer(wounded)
        repeat(15) { sim.stepTick() }

        sim.playerAttack("h1")
        val afterHp = sim.players["h1"]!!.vitals.getValue("hp").cur
        assertTrue(afterHp > 20, "life leech affix healed attacker on landed hit (hp=$afterHp)")
    }
}
