package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.ClaimKindDefinition
import com.morgana.domain.game.ClaimLevel
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.TerritoryDefinition
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.game.WorldObjectsDefinition
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.TerritorySystem
import com.morgana.domain.systems.WorldObjectResult
import com.morgana.domain.systems.WorldStateSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Territory: claiming land, and what a claim allows. */
class TerritoryTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val kinds = listOf(
        WorldObjectKind("totem", "Claim Totem", maxHp = 500),
        WorldObjectKind("banner", "Guild Banner", maxHp = 500),
        WorldObjectKind("wall", "Wall", maxHp = 50)
    )
    private val territory = TerritoryDefinition(
        claimKinds = listOf(
            ClaimKindDefinition("totem", levels = listOf(ClaimLevel(5, 3), ClaimLevel(8, 10)), maxPerOwner = 1),
            ClaimKindDefinition("banner", levels = listOf(ClaimLevel(5, 50)), guild = true, maxPerOwner = 1)
        ),
        noBuildRadiusTiles = 2,
        minClaimSpacingTiles = 2
    )

    private fun definition(t: TerritoryDefinition? = territory) = MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(kinds), territory = t)

    private fun sim(t: TerritoryDefinition? = territory, modules: ModuleFlags = ModuleFlags()): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(4L), game = CompiledGame.compile(definition(t)))
        s.modules = modules
        s.npcs[1] = NpcDefinition(id = 1, name = "Wolf", behavior = NpcBehavior.PASSIVE)
        s.registerMap(
            MapDefinition(id = 1, name = "Wilds", width = 60, height = 60, npcSpawns = listOf(MapNpcSpawnSlot(0, 1, 35, 5)))
                .withCell(30, 30, MapCell(attribute = TileAttribute.ResourceNode(1)))
        )
        for ((id, name, guild) in listOf(Triple("p1", "Pat", "g1"), Triple("p2", "Quinn", null), Triple("p3", "Riley", "g1"))) {
            s.spawnOrUpdatePlayer(
                CharacterState(id = id, accountId = "a-$id", name = name, guildId = guild, position = WorldPosition.fromTile(1, 1 + id.last().digitToInt(), 1, Direction.DOWN))
            )
        }
        return s
    }

    private fun WorldSimulation.put(kind: String, x: Int, y: Int, by: String?) = placeWorldObject(kind, 1, x, y, by, players[by]?.guildId)
    private fun refusal(r: WorldObjectResult) = (r as WorldObjectResult.Refused).reason
    private fun WorldSimulation.swing(id: String): List<WorldSimulationEvent> {
        players[id] = players.getValue(id).copy(lastAttackTick = -1000)
        return playerAttack(id)
    }
    private fun WorldSimulation.stand(id: String, x: Int, y: Int) { players[id] = players.getValue(id).copy(position = WorldPosition.fromTile(1, x, y, Direction.DOWN)) }
    private fun List<WorldSimulationEvent>.chat() = filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }

    // ---- the definition -------------------------------------------------------------------------------------------------------

    @Test
    fun `territory is validated against the world object kinds with a pointer for each field`() {
        fun pointers(t: TerritoryDefinition) = GameDefinitionValidator.validate(definition(t)).map { it.pointer }
        assertTrue(GameDefinitionValidator.validate(definition()).isEmpty())
        assertTrue("/territory/claimKinds/0/kind" in pointers(TerritoryDefinition(listOf(ClaimKindDefinition("castle")))))
        assertTrue("/territory/claimKinds/1/kind" in pointers(TerritoryDefinition(listOf(ClaimKindDefinition("totem"), ClaimKindDefinition("totem")))), "listed twice")
        assertTrue("/territory/claimKinds/0/levels" in pointers(TerritoryDefinition(listOf(ClaimKindDefinition("totem", levels = emptyList())))))
        assertTrue("/territory/claimKinds/0/levels/0/radiusTiles" in pointers(TerritoryDefinition(listOf(ClaimKindDefinition("totem", levels = listOf(ClaimLevel(0, 5)))))))
        assertTrue("/territory/claimKinds/0/levels/0/maxPieces" in pointers(TerritoryDefinition(listOf(ClaimKindDefinition("totem", levels = listOf(ClaimLevel(5, 0)))))))
        assertTrue("/territory/noBuildRadiusTiles" in pointers(TerritoryDefinition(noBuildRadiusTiles = 99)))
        assertTrue("/territory/minClaimSpacingTiles" in pointers(TerritoryDefinition(minClaimSpacingTiles = -1)))
    }

    // ---- claiming -------------------------------------------------------------------------------------------------------------

    @Test
    fun `a character holds a limited number of claims, and claims keep apart`() {
        val s = sim()
        val claim = (s.put("totem", 10, 10, "p1") as WorldObjectResult.Placed).obj
        assertEquals(claim, TerritorySystem.claimAt(s, 1, 12, 8), "a tile inside its reach")
        assertNull(TerritorySystem.claimAt(s, 1, 16, 10), "one past its reach of 5")
        assertEquals("You already hold as many claims as you may here.", refusal(s.put("totem", 40, 40, "p1")))
        // Two radii of 5 and 2 more apart: centres need more than 12 between them.
        assertEquals("That is too close to another claim.", refusal(s.put("totem", 22, 10, "p2")))
        assertTrue(s.put("totem", 23, 10, "p2") is WorldObjectResult.Placed)
    }

    @Test
    fun `a guild claim needs a guild and is held by the guild`() {
        val s = sim()
        assertEquals("You must belong to a guild to claim land for it.", refusal(s.put("banner", 10, 10, "p2")))
        val banner = (s.put("banner", 10, 10, "p1") as WorldObjectResult.Placed).obj
        assertEquals("g1", banner.ownerGuildId)
        assertEquals("You already hold as many claims as you may here.", refusal(s.put("banner", 40, 40, "p3")), "p3 is in the same guild, so the guild already has one")
    }

    @Test
    fun `nothing is placed close to a resource node or a monster's lair, except by the game itself`() {
        val s = sim()
        assertEquals("You cannot build this close to a resource or a monster's lair.", refusal(s.put("wall", 30, 32, "p1")))
        assertEquals("You cannot build this close to a resource or a monster's lair.", refusal(s.put("wall", 35, 7, "p1")))
        assertTrue(s.put("wall", 30, 33, "p1") is WorldObjectResult.Placed, "just beyond 2 tiles")
        assertTrue(s.put("wall", 30, 31, null) is WorldObjectResult.Placed, "a placement the game makes is not held to the rule")
    }

    // ---- who may build --------------------------------------------------------------------------------------------------------

    @Test
    fun `inside a claim only its owner, an allowed player or, for a guild claim, a member may build`() {
        val s = sim()
        s.put("totem", 10, 10, "p1")
        assertEquals("That land belongs to Pat.", refusal(s.put("wall", 12, 12, "p2")))
        assertTrue(s.put("wall", 12, 12, "p1") is WorldObjectResult.Placed)
        assertTrue(s.put("wall", 8, 8, null) is WorldObjectResult.Placed, "the game itself may")

        s.stand("p1", 9, 10)
        s.executeAdminSlashCommand("p1", "/claim allow quinn")
        assertTrue(s.put("wall", 13, 12, "p2") is WorldObjectResult.Placed, "allowed")
        s.executeAdminSlashCommand("p1", "/claim deny Quinn")
        assertEquals("That land belongs to Pat.", refusal(s.put("wall", 14, 12, "p2")), "and no longer")

        val g = sim()
        g.put("banner", 10, 10, "p1")
        assertTrue(g.put("wall", 12, 12, "p3") is WorldObjectResult.Placed, "a guild member builds on the guild's claim")
        assertTrue(g.put("wall", 13, 12, "p2") is WorldObjectResult.Refused)
    }

    @Test
    fun `a claim holds a limited number of pieces until it is upgraded, and a new claim cannot swallow another's building`() {
        val s = sim()
        val claim = (s.put("totem", 10, 10, "p1") as WorldObjectResult.Placed).obj
        repeat(3) { assertTrue(s.put("wall", 7 + it, 13, "p1") is WorldObjectResult.Placed) }
        assertEquals("This claim cannot hold anything more.", refusal(s.put("wall", 11, 13, "p1")))
        assertEquals(3, TerritorySystem.piecesIn(s, claim))
        assertTrue(TerritorySystem.upgradeClaim(s, claim.id))
        assertEquals(2, TerritorySystem.levelOf(s.worldObject(claim.id)!!))
        assertEquals(8, TerritorySystem.radiusOf(s, s.worldObject(claim.id)!!))
        assertTrue(s.put("wall", 11, 13, "p1") is WorldObjectResult.Placed, "room after the upgrade")
        assertFalse(TerritorySystem.upgradeClaim(s, claim.id), "already at the top")

        val t = sim()
        t.put("wall", 20, 10, "p2")
        assertEquals("Someone else has built too close to claim here.", refusal(t.put("totem", 22, 10, "p1")))
    }

    // ---- safe zones and attack windows ----------------------------------------------------------------------------------------

    @Test
    fun `nobody can attack inside a claim, except while an attack window is open`() {
        val s = sim()
        val claim = (s.put("totem", 10, 10, "p1") as WorldObjectResult.Placed).obj
        val t0 = ms("2026-11-28T12:00:00Z")
        s.advanceWallClock(t0)
        s.stand("p1", 9, 9); s.stand("p2", 11, 11)
        fun mayHit() = s.canAttackPlayer(s.players.getValue("p2"), s.players.getValue("p1"))
        assertFalse(mayHit(), "both stand in the claim")
        s.stand("p2", 30, 30)
        assertFalse(mayHit(), "the victim is protected even if the attacker is outside")
        s.stand("p1", 40, 40)
        assertTrue(mayHit(), "away from any claim, as without the system")

        s.stand("p1", 9, 9)
        assertTrue(TerritorySystem.bookWindow(s, claim.id, t0 + 3_600_000L, 600, warningsSeconds = listOf(900, 60), label = "Siege window"))
        assertFalse(mayHit(), "booked, not open yet")
        // Walk the clock up to the start in one-second steps the way the server does; collect what players are told.
        val said = mutableListOf<String>()
        var t = t0
        while (t < t0 + 3_601_000L) { t += 1000; said += s.advanceWallClock(t).chat() }
        assertEquals(listOf("Siege window begins in 15 minutes.", "Siege window begins in 1 minute.", "Siege window begins now."), said)
        assertTrue(TerritorySystem.isWindowOpen(s.worldObject(claim.id)!!))
        assertTrue(mayHit(), "the window is open")

        while (t < t0 + 4_300_000L) { t += 1000; said += s.advanceWallClock(t).chat() }
        assertFalse(TerritorySystem.isWindowOpen(s.worldObject(claim.id)!!), "and it closes after its duration")
        assertEquals("Siege window closes.", said.last())
        assertFalse(mayHit())
        assertTrue(s.scheduleOneShots.isEmpty(), "both one-offs are used up")
    }

    @Test
    fun `a window cannot be booked for a claim that does not exist, or for a time already past`() {
        val s = sim()
        val claim = (s.put("totem", 10, 10, "p1") as WorldObjectResult.Placed).obj
        s.advanceWallClock(ms("2026-11-28T12:00:00Z"))
        assertFalse(TerritorySystem.bookWindow(s, "obj-999", ms("2026-11-29T12:00:00Z"), 600))
        assertFalse(TerritorySystem.bookWindow(s, claim.id, ms("2026-11-27T12:00:00Z"), 600), "in the past")
        assertFalse(TerritorySystem.bookWindow(s, claim.id, ms("2026-11-29T12:00:00Z"), 0), "no duration")
        assertTrue(s.scheduleOneShots.isEmpty(), "a refused booking leaves nothing behind")
    }

    // ---- /claim ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `slash claim shows the claim you stand on and lets its owner give it up`() {
        val s = sim()
        val claim = (s.put("totem", 10, 10, "p1") as WorldObjectResult.Placed).obj
        s.put("wall", 11, 11, "p1")
        s.stand("p1", 9, 9)
        s.stand("p2", 12, 12)
        assertEquals(listOf("Claim Totem (level 1) of Pat: 1/3 pieces, reach 5 tiles"), s.executeAdminSlashCommand("p1", "/claim").chat())
        assertEquals(listOf("Stand on your own claim to do that."), s.executeAdminSlashCommand("p2", "/claim abandon").chat(), "not yours")
        s.stand("p3", 50, 50)
        assertEquals(listOf("You are not standing on claimed land."), s.executeAdminSlashCommand("p3", "/claim").chat())

        assertEquals(listOf("You give up the claim. What you built stays where it is."), s.executeAdminSlashCommand("p1", "/claim abandon").chat())
        assertNull(s.worldObject(claim.id))
        assertTrue(s.worldObjectAt(1, 11, 11) != null, "the wall is still there")
        assertTrue(s.put("wall", 13, 12, "p2") is WorldObjectResult.Placed, "and the land is free again")
    }

    // ---- switching it off -----------------------------------------------------------------------------------------------------

    @Test
    fun `with the system off, or no territory section, claims are only objects and nothing is protected`() {
        for (s in listOf(sim(modules = ModuleFlags(disabled = setOf(OptionalModule.TERRITORY))), sim(t = null))) {
            assertTrue(s.put("totem", 10, 10, "p1") is WorldObjectResult.Placed)
            assertTrue(s.put("totem", 12, 10, "p2") is WorldObjectResult.Placed, "no spacing")
            assertTrue(s.put("wall", 11, 11, "p2") is WorldObjectResult.Placed, "no ownership")
            s.stand("p1", 10, 11); s.stand("p2", 11, 10)
            assertTrue(s.canAttackPlayer(s.players.getValue("p2"), s.players.getValue("p1")), "no safe zone")
        }
    }

    // ---- hurting structures ---------------------------------------------------------------------------------------------------

    private fun siegeSim(safe: Boolean = true): WorldSimulation {
        val kinds = listOf(
            WorldObjectKind("totem", "Totem", maxHp = 500),
            WorldObjectKind("wall", "Wall", maxHp = 1000, attackable = true, weaponDamagePercent = 10, siegeDamagePercent = 100),
            WorldObjectKind("sign", "Sign", maxHp = 10)
        )
        val t = TerritoryDefinition(listOf(ClaimKindDefinition("totem", levels = listOf(ClaimLevel(5, 50)), safeZone = safe)))
        val s = WorldSimulation(SeededRandomSource(4L), game = CompiledGame.compile(MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(kinds), territory = t)))
        s.registerMap(MapDefinition(id = 1, name = "Wilds", width = 60, height = 60))
        for (id in listOf("p1", "p2")) {
            s.spawnOrUpdatePlayer(CharacterState(id = id, accountId = "a-$id", name = id, position = WorldPosition.fromTile(1, 10, 10, Direction.RIGHT)))
        }
        return s
    }

    @Test
    fun `a blow on an attackable structure takes its weapon percentage, and a sign cannot be hurt`() {
        val s = siegeSim(safe = false)
        s.placeWorldObject("totem", 1, 12, 12, "p1")
        val wall = (s.placeWorldObject("wall", 1, 11, 10, "p1") as WorldObjectResult.Placed).obj
        s.placeWorldObject("sign", 1, 11, 11, "p1")
        val raw = WorldStateSystem.plainHitDamage(s, s.players.getValue("p2"))
        s.swing("p2")
        assertEquals(1000 - (raw * 10 / 100).coerceAtLeast(1), s.worldObject(wall.id)!!.hp, "10% of the blow")
        s.stand("p2", 10, 11)
        s.swing("p2")
        assertEquals(10, s.worldObjectAt(1, 11, 11)!!.hp, "a sign is not attackable")
    }

    @Test
    fun `structures in a protected claim shrug off blows from outsiders until a window opens, and the owner may always strike`() {
        val s = siegeSim()
        val t0 = ms("2026-11-27T12:00:00Z")
        s.advanceWallClock(t0)
        val claim = (s.placeWorldObject("totem", 1, 12, 10, "p1") as WorldObjectResult.Placed).obj
        val wall = (s.placeWorldObject("wall", 1, 11, 10, "p1") as WorldObjectResult.Placed).obj
        val before = s.worldObject(wall.id)!!.hp
        val events = s.swing("p2")
        assertEquals(before, s.worldObject(wall.id)!!.hp)
        assertTrue(events.chat().contains("This land is protected right now."))

        assertTrue(TerritorySystem.bookWindow(s, claim.id, t0 + 1000, 600))
        s.advanceWallClock(t0 + 2000)
        s.swing("p2")
        assertTrue(s.worldObject(wall.id)!!.hp < before, "open to attack")

        s.advanceWallClock(t0 + 700_000)
        val mid = s.worldObject(wall.id)!!.hp
        s.swing("p1")
        assertTrue(s.worldObject(wall.id)!!.hp < mid, "an owner can tear down their own wall")
    }

    @Test
    fun `siege weapon types take the siege percentage`() {
        val kinds = listOf(WorldObjectKind("wall", "Wall", maxHp = 1000, attackable = true, weaponDamagePercent = 5, siegeDamagePercent = 100))
        val def = MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(kinds, siegeWeaponTypes = listOf("ram")))
        assertTrue(GameDefinitionValidator.validate(def).isEmpty())
        assertTrue("/worldObjects/kinds/0/weaponDamagePercent" in GameDefinitionValidator.validate(
            def.copy(worldObjects = WorldObjectsDefinition(listOf(kinds[0].copy(weaponDamagePercent = 0))))
        ).map { it.pointer })
    }

    @Test
    fun `a player's projectile hurts an attackable structure and stops there`() {
        val s = siegeSim(safe = false)
        val wall = (s.placeWorldObject("wall", 1, 14, 10, "p1") as WorldObjectResult.Placed).obj
        val shot = com.morgana.domain.entity.MapProjectile(
            projectileId = "pr1", sourceEntityId = "p2", isFromPlayer = true, mapId = 1, currentTileX = 10, currentTileY = 10,
            direction = Direction.RIGHT, remainingTiles = 8, spriteId = 1, damage = 200, ticksPerTile = 1, nextStepTick = 0L
        )
        s.mapProjectiles.getOrPut(1) { mutableListOf() }.add(shot)
        repeat(8) { s.stepTick() }
        assertEquals(1000 - 20, s.worldObject(wall.id)!!.hp, "10% of 200")
        assertTrue(s.mapProjectiles[1].orEmpty().isEmpty(), "it was spent on the wall")
    }
}
