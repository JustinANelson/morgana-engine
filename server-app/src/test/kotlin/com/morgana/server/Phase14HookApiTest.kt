package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.item.ItemType
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase14HookApiTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        override fun send(packet: ServerPacket) {}
        override fun close(reason: String) {}
    }

    private class RecordingHook(override val pluginId: String = "rec") : MorganaServerHook {
        val items = mutableListOf<Int>()
        val spells = mutableListOf<Int>()
        val kills = mutableListOf<Triple<Int, Int, String?>>()
        val deaths = mutableListOf<String>()
        val chats = mutableListOf<Triple<String, ChatChannel, String>>()
        override fun onItemUsed(character: CharacterState, itemId: Int, sim: WorldSimulation) { items += itemId }
        override fun onSpellCast(character: CharacterState, spellId: Int, sim: WorldSimulation) { spells += spellId }
        override fun onNpcKilled(npcInstanceId: String, npcId: Int, mapId: Int, killer: CharacterState?, sim: WorldSimulation) {
            kills += Triple(npcId, mapId, killer?.name)
        }
        override fun onPlayerDeath(character: CharacterState, sim: WorldSimulation) { deaths += character.name }
        override fun onPlayerChat(character: CharacterState, channel: ChatChannel, message: String, sim: WorldSimulation) {
            chats += Triple(character.name, channel, message)
        }
    }

    private fun isHostile(sim: WorldSimulation, npcId: Int) = sim.npcs[npcId]?.behavior !in
        setOf(NpcBehavior.SHOPKEEPER, NpcBehavior.BANKER, NpcBehavior.QUEST_GIVER, NpcBehavior.FRIENDLY_ROAM)

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        .also { TestGames.addCombatKit(it.simulation) }

    private fun login(c: MorganaWorldCoordinator, name: String = "Scripter"): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `item use, spell cast and chat reach plugins, while commands and failing plugins do not`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val hook = RecordingHook()
        c.hookRegistry.register(object : MorganaServerHook {
            override val pluginId = "boom"
            override fun onItemUsed(character: CharacterState, itemId: Int, sim: WorldSimulation) = error("bug")
            override fun onSpellCast(character: CharacterState, spellId: Int, sim: WorldSimulation) = error("bug")
            override fun onPlayerChat(character: CharacterState, channel: ChatChannel, message: String, sim: WorldSimulation) = error("bug")
        })
        c.hookRegistry.register(hook)
        val s = login(c)
        val me = c.simulation.players.values.single()

        val slot = me.inventory.slots.indexOfFirst { it != null && c.simulation.items[it.itemId]?.type == ItemType.CONSUMABLE }
        assertTrue(slot >= 0, "starter kit contains a consumable")
        val itemId = me.inventory.slots[slot]!!.itemId
        c.handleClientPacket(s, ClientPacket.UseInventorySlot(slot))
        assertEquals(listOf(itemId), hook.items)

        // Cast any known spell until one succeeds (level/MP requirements vary by spell).
        for (spellId in me.knownSpellIds) {
            c.handleClientPacket(s, ClientPacket.CastSpell(spellId))
            repeat(100) { c.stepServerTick() }
            if (hook.spells.isNotEmpty()) break
        }
        assertTrue(hook.spells.isNotEmpty(), "at least one starter spell is castable")

        c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "hello world"))
        c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, "/help"))
        assertEquals(listOf(Triple("Scripter", ChatChannel.MAP, "hello world")), hook.chats, "slash commands are not chat")
    }

    @Test
    fun `NPC kills and player deaths are delivered with the killer resolved`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val hook = RecordingHook()
        c.hookRegistry.register(hook)
        val s = login(c)
        val sim = c.simulation
        val me = sim.players.values.single()

        // Put a weakened NPC directly in front of the player and attack it.
        val list = sim.mapNpcs.getValue(me.position.mapId)
        val npc = list.first { it.isAlive && isHostile(sim, it.npcId) }
        list[list.indexOf(npc)] = npc.copy(vitals = npc.vitals.mapValues { (k, v) -> if (k == "hp") v.copy(cur = 1) else v })
        sim.players[me.id] = me.copy(
            position = WorldPosition.fromTile(me.position.mapId, (npc.tileX - 1).coerceAtLeast(0), npc.tileY, Direction.RIGHT)
        )
        repeat(5) { c.stepServerTick() }
        repeat(10) {
            c.handleClientPacket(s, ClientPacket.Attack)
            repeat(5) { c.stepServerTick() }
        }
        assertTrue(hook.kills.any { it.first == npc.npcId && it.third == "Scripter" }, "kills: ${hook.kills}")
    }

    @Test
    fun `a player killed by an NPC is reported to plugins exactly once`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val hook = RecordingHook()
        c.hookRegistry.register(hook)
        login(c)
        val sim = c.simulation
        val me = sim.players.values.single()

        val list = sim.mapNpcs.getValue(me.position.mapId)
        val npc = list.first { it.isAlive && isHostile(sim, it.npcId) }
        val def = sim.npcs.getValue(npc.npcId)
        sim.npcs[def.id] = def.copy(attackDamage = 5_000, attributes = mapOf("str" to 50, "def" to 50, "int" to 50, "agi" to 50, "vit" to 50))
        list[list.indexOf(npc)] = npc.copy(targetEntityId = me.id)
        sim.players[me.id] = me.copy(
            position = WorldPosition.fromTile(me.position.mapId, (npc.tileX - 1).coerceAtLeast(0), npc.tileY, Direction.RIGHT)
        )
        repeat(300) { if (hook.deaths.isEmpty()) c.stepServerTick() }
        // The player respawns on the same small map, so take the killer away before letting time run on: one death,
        // one report, however long the server keeps ticking.
        list.removeAll { it.instanceId == npc.instanceId }
        repeat(100) { c.stepServerTick() }
        assertEquals(listOf("Scripter"), hook.deaths)
    }
}
