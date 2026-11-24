package com.morgana.domain.sim

import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition

/**
 * Player movement: grid / sub-tile stepping, collision, tile attributes, warps and map-border crossing.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.movePlayerImpl(
    characterId: String,
    direction: Direction,
    mode: MovementMode = MovementMode.GRID_LOCKED,
    deltaPixelsX: Float = 0f,
    deltaPixelsY: Float = 0f
): List<WorldSimulationEvent> {
    var player = players[characterId] ?: return emptyList()
    if (!player.isAlive(game.lifeVitalId) && !player.isGhost) return emptyList()
    if (player.statusEffects.any { it.preventsMovement }) return emptyList()
    if (actionBlocked(characterId, PlayerActivity.MOVE)) return emptyList()

    val out = mutableListOf<WorldSimulationEvent>()
    if (player.isCasting) {
        val interruptedSpellId = player.castingSpellId ?: 0
        player = player.copy(
            castingSpellId = null,
            castFinishTick = 0L,
            castTotalTicks = 0,
            castTargetEntityId = null,
            castTargetTileX = 0,
            castTargetTileY = 0
        )
        players[characterId] = player
        out.add(WorldSimulationEvent.SpellCastInterrupted(characterId, interruptedSpellId, player.position.mapId, "moved"))
        out.add(WorldSimulationEvent.CharacterUpdated(player))
    }

    val currentMap = maps[player.position.mapId] ?: return out
    // Authoritative rate limit: a too-fast move is dropped and the client is resynced to its true position.
    if (MovementRules.isTooSoon(player.lastMoveTick, currentTick, mode)) {
        return if (out.isNotEmpty()) out else listOf(WorldSimulationEvent.CharacterUpdated(player))
    }

    var sprintDrainVitalId: String? = null
    val targetCoord: TileCoord
    val newPosCandidate: WorldPosition

    if (mode == MovementMode.SUB_TILE && (deltaPixelsX != 0f || deltaPixelsY != 0f)) {
        val sprintRules = game.definition.sprint?.takeIf { it.enabled }
        val hasStamina = sprintRules == null || (player.vitals[sprintRules.staminaVitalId]?.cur ?: 0) > 0
        val (clampedDx, clampedDy, sprinting) = MovementRules.limitStep(deltaPixelsX, deltaPixelsY, sprintRules?.speedPercent, hasStamina, player.equipment.mountSpeedPercent(items).let { mount -> com.morgana.domain.game.ClassPassive.scale(mount, game.definition.passiveOf(player.classId).moveSpeedPercent) })
        if (sprinting && sprintRules != null && currentTick % sprintRules.stepsPerStamina == 0L) sprintDrainVitalId = sprintRules.staminaVitalId
        val nextPx = player.position.pixelX + clampedDx
        val nextPy = player.position.pixelY + clampedDy
        val tileSize = currentMap.tileSize
        val nextTileX = kotlin.math.floor((nextPx + tileSize * 0.5f) / tileSize).toInt()
        val nextTileY = kotlin.math.floor((nextPy + tileSize * 0.5f) / tileSize).toInt()
        targetCoord = TileCoord(nextTileX, nextTileY)
        newPosCandidate = player.position.copy(
            tileX = nextTileX,
            tileY = nextTileY,
            pixelX = nextPx,
            pixelY = nextPy,
            direction = direction
        )
    } else {
        targetCoord = player.position.tileCoord.step(direction)
        newPosCandidate = player.position.withTile(
            newTileX = targetCoord.x,
            newTileY = targetCoord.y,
            tileSize = currentMap.tileSize,
            newDirection = direction
        )
    }

    // 1. Check seamless N/S/E/W map border crossing
    if (!currentMap.isInBounds(targetCoord)) {
        val borderDirection = when {
            targetCoord.y < 0 -> Direction.UP
            targetCoord.y >= currentMap.height -> Direction.DOWN
            targetCoord.x < 0 -> Direction.LEFT
            targetCoord.x >= currentMap.width -> Direction.RIGHT
            else -> direction.primaryCardinal()
        }
        val neighborMapId = currentMap.neighbors.neighborFor(borderDirection)
        val neighborMap = maps[neighborMapId]
        if (neighborMap != null) {
            val destX = when (borderDirection) {
                Direction.LEFT -> neighborMap.width - 1
                Direction.RIGHT -> 0
                else -> targetCoord.x.coerceIn(0, neighborMap.width - 1)
            }
            val destY = when (borderDirection) {
                Direction.UP -> neighborMap.height - 1
                Direction.DOWN -> 0
                else -> targetCoord.y.coerceIn(0, neighborMap.height - 1)
            }
            val destCoord = TileCoord(destX, destY)
            if (neighborMap.getCell(destCoord)?.attribute !is TileAttribute.Blocked) {
                val movedPlayer = player.copy(
                    position = WorldPosition.fromTile(
                        mapId = neighborMap.id,
                        tileX = destX,
                        tileY = destY,
                        direction = direction,
                        tileSize = neighborMap.tileSize
                    ),
                    movementMode = mode,
                    lastMoveTick = currentTick
                )
                players[characterId] = movedPlayer
                out.add(WorldSimulationEvent.PlayerMoved(movedPlayer, currentMap.id, neighborMap.id))
                out.add(WorldSimulationEvent.CharacterUpdated(movedPlayer))
                checkQuestVisitMap(movedPlayer, neighborMap.id, out)
                fireMapEntryEvents(characterId, out)
                return out
            }
        }
        return emptyList()
    }

    // 2. Check traversal invariants on current map
    if (targetCoord != player.position.tileCoord) {
        val unlocked = unlockedDoors[currentMap.id]?.keys ?: emptySet()
        val depleted = mapResources[currentMap.id]?.filterValues { it.depleted }?.keys ?: emptySet()
        val npcAtTarget = mapNpcs[currentMap.id]?.any {
            it.isAlive && it.tileX == targetCoord.x && it.tileY == targetCoord.y
        } ?: false
        val traversable = if (player.isFlying && mayFlyOver(currentMap)) {
            currentMap.isInBounds(targetCoord)
        } else currentMap.canTraverse(
            from = player.position.tileCoord,
            to = targetCoord,
            direction = direction,
            isNpc = false,
            unlockedDoors = unlocked,
            depletedResources = depleted
        )
        // Free pixel movement: a blocked diagonal step slides along whichever single axis is open.
        if ((!traversable || npcAtTarget) && mode == MovementMode.SUB_TILE && deltaPixelsX != 0f && deltaPixelsY != 0f) {
            val horizontal = movePlayerImpl(characterId, if (deltaPixelsX < 0f) Direction.LEFT else Direction.RIGHT, mode, deltaPixelsX, 0f)
            if (players[characterId]?.lastMoveTick == currentTick) return out + horizontal
            val vertical = movePlayerImpl(characterId, if (deltaPixelsY < 0f) Direction.UP else Direction.DOWN, mode, 0f, deltaPixelsY)
            return out + vertical
        }
        if (!traversable) {
            // Still update facing direction if blocked
            val turned = player.copy(position = player.position.copy(direction = direction))
            players[characterId] = turned
            out.add(WorldSimulationEvent.CharacterUpdated(turned))
            return out
        }

        // Check live NPC collision
        if (npcAtTarget) {
            val turned = player.copy(position = player.position.copy(direction = direction))
            players[characterId] = turned
            out.add(WorldSimulationEvent.CharacterUpdated(turned))
            return out
        }
    }

    var updatedPlayer = player.copy(
        position = newPosCandidate,
        movementMode = mode,
        lastMoveTick = currentTick
    )
    sprintDrainVitalId?.let { id ->
        val vital = updatedPlayer.vitals[id]
        if (vital != null && vital.cur > 0) updatedPlayer = updatedPlayer.copy(vitals = updatedPlayer.vitals + (id to vital.copy(cur = vital.cur - 1)))
    }
    var oldMapId = currentMap.id

    // 3. Evaluate destination tile attributes (Warp, Slide, Shop, Bank)
    val destAttr = currentMap.getCell(targetCoord)?.attribute ?: TileAttribute.None
    when (destAttr) {
        is TileAttribute.Warp -> {
            val warped = warpedPlayer(updatedPlayer, destAttr.targetMapId, destAttr.targetX, destAttr.targetY, destAttr.targetDirection ?: direction, out)
            if (warped != null) {
                updatedPlayer = warped
                val destMapId = warped.position.mapId
                players[characterId] = updatedPlayer
                out.add(WorldSimulationEvent.PlayerMoved(updatedPlayer, oldMapId, destMapId))
                out.add(WorldSimulationEvent.CharacterUpdated(updatedPlayer))
                checkQuestVisitMap(updatedPlayer, destMapId, out)
                fireMapEntryEvents(characterId, out)
                return out
            }
        }
        is TileAttribute.Slide -> {
            val slideCoord = targetCoord.step(destAttr.direction)
            if (currentMap.isInBounds(slideCoord) && currentMap.getCell(slideCoord)?.attribute !is TileAttribute.Blocked) {
                updatedPlayer = updatedPlayer.copy(
                    position = WorldPosition.fromTile(
                        mapId = currentMap.id,
                        tileX = slideCoord.x,
                        tileY = slideCoord.y,
                        direction = destAttr.direction,
                        tileSize = currentMap.tileSize
                    )
                )
            }
        }
        is TileAttribute.ShopTile -> {
            // The player is stored just below, so record the shop session at the tile they are stepping onto.
            shopSessions[characterId] = ShopSession(destAttr.shopId, updatedPlayer.position.mapId, updatedPlayer.position.tileX, updatedPlayer.position.tileY)
            out.add(
                WorldSimulationEvent.OpenUiRequested(
                    characterId = characterId,
                    windowType = "SHOP",
                    shopId = destAttr.shopId
                )
            )
        }
        is TileAttribute.BankTile -> {
            out.add(
                WorldSimulationEvent.OpenUiRequested(
                    characterId = characterId,
                    windowType = "BANK"
                )
            )
        }
        else -> {}
    }

    players[characterId] = updatedPlayer
    out.add(WorldSimulationEvent.PlayerMoved(updatedPlayer, oldMapId, updatedPlayer.position.mapId))
    out.add(WorldSimulationEvent.CharacterUpdated(updatedPlayer))

    // 4. Trigger PLAYER_TOUCH events on the destination tile
    val touchEvent = events.values.firstOrNull {
        it.mapId == contentMapId(updatedPlayer.position.mapId) &&
            it.tileX == updatedPlayer.position.tileX &&
            it.tileY == updatedPlayer.position.tileY
    }
    if (touchEvent != null) {
        val activePage = resolveActiveEventPage(updatedPlayer, touchEvent)
        if (activePage != null && activePage.trigger == EventTriggerType.PLAYER_TOUCH) {
            executeEventCommands(updatedPlayer.id, touchEvent.id, activePage.commands, out)
        }
    }

    return out
}
