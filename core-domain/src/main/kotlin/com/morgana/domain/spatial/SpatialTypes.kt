package com.morgana.domain.spatial

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 8-way direction enum supporting both 4-direction Morgana grid movement and 8-direction sub-tile movement.
 */
@Serializable
enum class Direction(val dx: Int, val dy: Int, val isDiagonal: Boolean = false) {
    UP(0, -1, false),
    DOWN(0, 1, false),
    LEFT(-1, 0, false),
    RIGHT(1, 0, false),
    UP_LEFT(-1, -1, true),
    UP_RIGHT(1, -1, true),
    DOWN_LEFT(-1, 1, true),
    DOWN_RIGHT(1, 1, true);

    fun opposite(): Direction = when (this) {
        UP -> DOWN
        DOWN -> UP
        LEFT -> RIGHT
        RIGHT -> LEFT
        UP_LEFT -> DOWN_RIGHT
        UP_RIGHT -> DOWN_LEFT
        DOWN_LEFT -> UP_RIGHT
        DOWN_RIGHT -> UP_LEFT
    }

    fun primaryCardinal(): Direction = when (this) {
        UP, DOWN, LEFT, RIGHT -> this
        UP_LEFT, UP_RIGHT -> UP
        DOWN_LEFT, DOWN_RIGHT -> DOWN
    }

    companion object {
        val CARDINALS: List<Direction> = listOf(UP, DOWN, LEFT, RIGHT)

        fun fromDelta(dx: Int, dy: Int): Direction? = when {
            dx == 0 && dy < 0 -> UP
            dx == 0 && dy > 0 -> DOWN
            dx < 0 && dy == 0 -> LEFT
            dx > 0 && dy == 0 -> RIGHT
            dx < 0 && dy < 0 -> UP_LEFT
            dx > 0 && dy < 0 -> UP_RIGHT
            dx < 0 && dy > 0 -> DOWN_LEFT
            dx > 0 && dy > 0 -> DOWN_RIGHT
            else -> null
        }
    }
}

/**
 * Hybrid movement mode supported by the engine.
 */
@Serializable
enum class MovementMode {
    /** Classic Morgana grid-locked tile-by-tile movement. */
    GRID_LOCKED,
    /** Smooth sub-tile pixel-step movement validated against tile collision and directional blocking. */
    SUB_TILE
}

/**
 * Discrete tile coordinates on a map grid (0-indexed from top-left).
 */
@Serializable
data class TileCoord(val x: Int, val y: Int) {
    fun step(direction: Direction, steps: Int = 1): TileCoord =
        TileCoord(x + direction.dx * steps, y + direction.dy * steps)

    fun manhattanDistanceTo(other: TileCoord): Int =
        abs(x - other.x) + abs(y - other.y)

    fun chebyshevDistanceTo(other: TileCoord): Int =
        maxOf(abs(x - other.x), abs(y - other.y))

    fun euclideanDistanceTo(other: TileCoord): Double =
        hypot((x - other.x).toDouble(), (y - other.y).toDouble())
}

/**
 * Authoritative world position supporting both tile-grid and sub-tile pixel coordinates.
 */
@Serializable
data class WorldPosition(
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val pixelX: Float = tileX * DEFAULT_TILE_SIZE.toFloat(),
    val pixelY: Float = tileY * DEFAULT_TILE_SIZE.toFloat(),
    val direction: Direction = Direction.DOWN
) {
    val tileCoord: TileCoord
        get() = TileCoord(tileX, tileY)

    fun withTile(newTileX: Int, newTileY: Int, tileSize: Int = DEFAULT_TILE_SIZE, newDirection: Direction = direction): WorldPosition =
        copy(
            tileX = newTileX,
            tileY = newTileY,
            pixelX = (newTileX * tileSize).toFloat(),
            pixelY = (newTileY * tileSize).toFloat(),
            direction = newDirection
        )

    fun withPixels(newPixelX: Float, newPixelY: Float, tileSize: Int = DEFAULT_TILE_SIZE, newDirection: Direction = direction): WorldPosition {
        val centerTileX = ((newPixelX + tileSize * 0.5f) / tileSize).toInt().coerceAtLeast(0)
        val centerTileY = ((newPixelY + tileSize * 0.5f) / tileSize).toInt().coerceAtLeast(0)
        return copy(
            tileX = centerTileX,
            tileY = centerTileY,
            pixelX = newPixelX,
            pixelY = newPixelY,
            direction = newDirection
        )
    }

    companion object {
        const val DEFAULT_TILE_SIZE: Int = 32

        fun fromTile(mapId: Int, tileX: Int, tileY: Int, direction: Direction = Direction.DOWN, tileSize: Int = DEFAULT_TILE_SIZE): WorldPosition =
            WorldPosition(
                mapId = mapId,
                tileX = tileX,
                tileY = tileY,
                pixelX = (tileX * tileSize).toFloat(),
                pixelY = (tileY * tileSize).toFloat(),
                direction = direction
            )
    }
}

