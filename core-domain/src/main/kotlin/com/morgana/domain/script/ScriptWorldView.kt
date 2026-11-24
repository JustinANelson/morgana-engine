package com.morgana.domain.script

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.stats.VitalState

/**
 * Immutable projection of a player character exposed to scripts.
 */
data class ScriptPlayerSnapshot(
    val id: String,
    val name: String,
    val mapId: Int,
    val x: Int,
    val y: Int,
    val direction: Direction = Direction.DOWN,
    val level: Int,
    val attributes: Map<String, Int>,
    val effectiveAttributes: Map<String, Int> = attributes,
    val vitals: Map<String, VitalState>,
    val accessLevel: Int,
    val classId: Int = 1,
    val lastAttackTick: Long = 0L,
    val weaponItemId: Int? = null,
    val ammoItemId: Int? = null,
    /** Centre of the character in world pixels (a tile is 32). Action combat measures hitboxes in pixels. */
    val pixelX: Int = x * PIXELS_PER_TILE + PIXELS_PER_TILE / 2,
    val pixelY: Int = y * PIXELS_PER_TILE + PIXELS_PER_TILE / 2
)

/** Pixels in a tile, as scripts see the world. */
const val PIXELS_PER_TILE: Int = 32

/**
 * Immutable projection of an NPC instance exposed to scripts.
 */
data class ScriptNpcSnapshot(
    val instanceId: String,
    val npcId: Int,
    val name: String,
    val mapId: Int,
    val x: Int,
    val y: Int,
    val direction: Direction = Direction.DOWN,
    val vitals: Map<String, VitalState>,
    val attributes: Map<String, Int> = emptyMap(),
    val isAlive: Boolean,
    val pixelX: Int = x * PIXELS_PER_TILE + PIXELS_PER_TILE / 2,
    val pixelY: Int = y * PIXELS_PER_TILE + PIXELS_PER_TILE / 2,
    /** The character the monster is fighting right now, or null. */
    val targetId: String? = null,
    /** Threat by character id (empty unless the game keeps a threat table). */
    val threat: Map<String, Int> = emptyMap()
)

/**
 * Generic entity snapshot for spatial proximity queries.
 */
data class ScriptEntitySnapshot(
    val isPlayer: Boolean,
    val id: String,
    val mapId: Int,
    val x: Int,
    val y: Int,
    val name: String,
    val pixelX: Int = x * PIXELS_PER_TILE + PIXELS_PER_TILE / 2,
    val pixelY: Int = y * PIXELS_PER_TILE + PIXELS_PER_TILE / 2
)

/**
 * Immutable projection of a map exposed to scripts.
 */
data class ScriptMapSnapshot(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val moral: String
)

/**
 * Pure read-only view of world state exposed to scripts during execution.
 *
 * All functions return immutable snapshots. Randomness is deterministic and backed
 * by the simulation's seeded [com.morgana.domain.sim.RandomSource].
 */
interface ScriptWorldView {
    val currentTick: Long

    /** The real-world time (epoch milliseconds, UTC) the server last reported, 0 before the first report. Exposed to Lua as `world.now_ms`. */
    val wallClockMillis: Long get() = 0L

    /** The world object with [id], or null. Exposed to Lua as `world.get_object(id)`. */
    fun getWorldObject(id: String): ScriptObjectSnapshot? = null

    /** World objects within [radius] tiles of ([x], [y]) on [mapId], nearest first. Exposed to Lua as `world.objects_near(map_id, x, y, radius)`. */
    fun worldObjectsNear(mapId: Int, x: Int, y: Int, radius: Int): List<ScriptObjectSnapshot> = emptyList()

    /** The game's calendar right now, or null when the game declares no clock. Exposed to Lua as `world.clock`. */
    val clock: com.morgana.domain.game.ClockTime? get() = null

    fun random(min: Int, max: Int): Int

    fun getPlayer(playerId: String): ScriptPlayerSnapshot?

    fun getNpc(mapId: Int, instanceId: String): ScriptNpcSnapshot?

    fun getMap(mapId: Int): ScriptMapSnapshot?

    fun getItem(itemId: Int): ItemDefinition?

    fun getSpell(spellId: Int): SpellDefinition?

    fun getEntitiesInRange(mapId: Int, x: Int, y: Int, radius: Int): List<ScriptEntitySnapshot>

    /**
     * A content definition as a tree of maps, lists, numbers, strings and booleans, or null when it does not exist.
     * [kind] is `action_skill` or `weapon_set`. Exposed to Lua as `world.get_action_skill(id)` / `world.get_weapon_set(id)`.
     */
    fun getContent(kind: String, id: Int): Any? = null

    /** Every player on [mapId], by id. Exposed to Lua as `world.players_on_map(map_id)`. */
    fun playersOnMap(mapId: Int): List<ScriptPlayerSnapshot> = emptyList()

    /** Every monster instance on [mapId] (alive or not), by instance id. Exposed to Lua as `world.npcs_on_map(map_id)`. */
    fun npcsOnMap(mapId: Int): List<ScriptNpcSnapshot> = emptyList()

    /** The script state stored under [key] (see [ScriptEffect.SetState]), or null. */
    fun getState(key: String): Map<String, String>? = null

    /** Keys of script state that start with [prefix], in sorted order. */
    fun stateKeys(prefix: String): List<String> = emptyList()
}
