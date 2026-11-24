package com.morgana.domain.entity

import kotlinx.serialization.Serializable

/**
 * A thing placed on a map that stays there: a wall, a chest, a totem, a sign. It is the building block of every system that lets
 * players or the game leave something in the world (base building, claims, farm plots). It occupies one tile, may block movement,
 * has hit points, and carries free-form [data] a system or Lua plugin reads and writes (an upkeep deadline, a guild's colour).
 *
 * World objects belong to the simulation (they are hashed for replay and saved by the server), so every change goes through
 * `WorldSimulation.placeWorldObject`, `removeWorldObject`, `damageWorldObject` and `setWorldObjectData`, which are driven by recorded
 * inputs. The kinds of object a game has are declared in `game.json` under `worldObjects`.
 */
@Serializable
data class WorldObject(
    /** Unique id, made by the simulation (`obj-17`). */
    val id: String,
    /** The `worldObjects.kinds[].id` this is an instance of. */
    val kind: String,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    /** The character who placed it, or null for one the game placed. */
    val ownerCharacterId: String? = null,
    /** The guild it belongs to, if any. */
    val ownerGuildId: String? = null,
    /** Hit points left; it is removed at 0. */
    val hp: Int = 0,
    /** The world tick (`effectiveWorldTick`) it was placed on. */
    val createdAtTick: Long = 0L,
    /** What a system keeps on it, by key. Values are strings, like script state. */
    val data: Map<String, String> = emptyMap()
)
