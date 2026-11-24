package com.morgana.domain.sim

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import kotlin.math.max

/**
 * Player interaction with the world and inventory: interact, harvest, pickup, use/equip/drop, stat allocation.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.playerInteractFacingImpl(characterId: String): List<WorldSimulationEvent> {
    var player = players[characterId] ?: return emptyList()
    if (!player.isAlive(game.lifeVitalId)) return emptyList()

    val mapId = player.position.mapId
    val map = maps[mapId] ?: return emptyList()
    val facingTile = player.position.tileCoord.step(player.position.direction)
    val out = mutableListOf<WorldSimulationEvent>()

    // 1. Check facing tile attributes (KeyDoor, ResourceNode, ShopTile, BankTile)
    val cell = map.getCell(facingTile)
    if (cell != null) {
        when (val attr = cell.attribute) {
            is TileAttribute.KeyDoor -> {
                if (player.inventory.hasItem(attr.keyItemId, 1)) {
                    if (attr.consumeKey) {
                        val updatedInv = player.inventory.removeByItemId(attr.keyItemId, 1) ?: player.inventory
                        player = player.copy(inventory = updatedInv)
                        players[characterId] = player
                        out.add(WorldSimulationEvent.CharacterUpdated(player))
                    }
                    val mapDoors = unlockedDoors.getOrPut(mapId) { linkedMapOf() }
                    mapDoors[facingTile] = currentTick + max(20, attr.unlockDurationTicks)
                    out.add(
                        WorldSimulationEvent.FloatingTextEmitted(
                            mapId = mapId,
                            tileX = facingTile.x,
                            tileY = facingTile.y,
                            text = "Unlocked!",
                            category = "RESOURCE",
                            colorHex = "#4ADE80"
                        )
                    )
                } else {
                    val keyName = items[attr.keyItemId]?.name ?: "Key #${attr.keyItemId}"
                    out.add(
                        WorldSimulationEvent.ChatEmitted(
                            channel = "SYSTEM",
                            mapId = mapId,
                            senderId = "system",
                            senderName = "System",
                            targetCharacterId = characterId,
                            message = "Locked! Requires $keyName.",
                            showOverheadBubble = false,
                            notice = "WARNING"
                        )
                    )
                }
                return out
            }
            is TileAttribute.ResourceNode -> {
                harvestResourceAt(player, facingTile, attr.resourceId, out)
                return out
            }
            is TileAttribute.ShopTile -> {
                openShopFor(characterId, attr.shopId, out)
                return out
            }
            is TileAttribute.BankTile -> {
                out.add(WorldSimulationEvent.OpenUiRequested(characterId, "BANK"))
                return out
            }
            else -> {}
        }
    }

    // 2. Check facing NPC
    val facingNpc = mapNpcs[mapId]?.firstOrNull {
        it.isAlive && it.tileX == facingTile.x && it.tileY == facingTile.y
    }
    if (facingNpc != null) {
        val npcDef = npcs[facingNpc.npcId]
        if (npcDef != null) {
            advanceQuestProgress(characterId, QuestObjectiveType.TALK_TO_NPC, npcDef.id, 1, out)
            player = players[characterId] ?: player
            if (npcDef.chatGreeting.isNotBlank()) {
                out.add(
                    WorldSimulationEvent.ChatEmitted(
                        channel = "MAP",
                        mapId = mapId,
                        senderId = facingNpc.instanceId,
                        senderName = npcDef.name,
                        message = npcDef.chatGreeting,
                        showOverheadBubble = true
                    )
                )
            }
            when (npcDef.behavior) {
                NpcBehavior.SHOPKEEPER -> {
                    if (npcDef.shopId > 0) {
                        openShopFor(characterId, npcDef.shopId, out)
                        return out
                    }
                }
                NpcBehavior.BANKER -> {
                    out.add(WorldSimulationEvent.OpenUiRequested(characterId, "BANK"))
                    return out
                }
                NpcBehavior.GUILD_BANKER -> {
                    if (guilds[player.guildId ?: ""] == null) {
                        out.add(warningNotice(player, "The vault keepers serve guild members only."))
                    } else {
                        out.add(WorldSimulationEvent.OpenUiRequested(characterId, "GUILD_BANK"))
                    }
                    return out
                }
                else -> {}
            }
            if (npcDef.eventId > 0) {
                val ev = events[npcDef.eventId]
                if (ev != null) {
                    val page = resolveActiveEventPage(player, ev)
                    if (page != null) {
                        executeEventCommands(player.id, ev.id, page.commands, out)
                        return out
                    }
                }
            }
        }
    }

    // 3. Check facing or current tile EventDefinition (ACTION_INTERACT)
    val mapEvent = events.values.firstOrNull {
        it.mapId == contentMapId(mapId) &&
            ((it.tileX == facingTile.x && it.tileY == facingTile.y) ||
                (it.tileX == player.position.tileX && it.tileY == player.position.tileY))
    }
    if (mapEvent != null) {
        val page = resolveActiveEventPage(player, mapEvent)
        if (page != null) {
            executeEventCommands(player.id, mapEvent.id, page.commands, out)
        }
    }

    return out
}

/** The default minimum ticks between resource-node harvest swings (10 ticks = 0.5 s at 20 TPS); a game sets `tuning.harvestCooldownTicks`. */
const val HARVEST_COOLDOWN_TICKS: Long = com.morgana.domain.game.GameplayTuning.DEFAULT_HARVEST_COOLDOWN_TICKS.toLong()

internal fun WorldSimulation.harvestResourceAt(
    player: CharacterState,
    coord: TileCoord,
    resourceId: Int,
    out: MutableList<WorldSimulationEvent>
) {
    val mapId = player.position.mapId
    val resDef = resources[resourceId] ?: return
    val mapNodeStates = mapResources.getOrPut(mapId) { linkedMapOf() }
    val state = mapNodeStates[coord] ?: MapResourceState(
        mapId = mapId,
        tileX = coord.x,
        tileY = coord.y,
        resourceId = resourceId,
        currentHealth = resDef.maxHealth
    )

    if (state.depleted) {
        out.add(
            WorldSimulationEvent.ChatEmitted(
                channel = "SYSTEM",
                mapId = mapId,
                senderId = "system",
                senderName = "System",
                targetCharacterId = player.id,
                message = resDef.emptyMessage,
                showOverheadBubble = false,
                notice = "WARNING"
            )
        )
        return
    }

    // Check required tool in TOOL slot, WEAPON slot, or inventory
    if (resDef.requiredTool != ToolType.NONE) {
        val hasToolEquipped = listOf(EquipmentSlot.TOOL, EquipmentSlot.WEAPON).any { slot ->
            val itemId = player.equipment.getEquipped(slot)?.itemId ?: 0
            items[itemId]?.toolType == resDef.requiredTool
        }
        val hasToolInInv = player.inventory.slots.filterNotNull().any { stack ->
            items[stack.itemId]?.toolType == resDef.requiredTool
        }
        if (!hasToolEquipped && !hasToolInInv) {
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM",
                    mapId = mapId,
                    senderId = "system",
                    senderName = "System",
                    targetCharacterId = player.id,
                    message = "You need a ${resDef.requiredTool.name.lowercase().replace('_', ' ')} to harvest ${resDef.name}.",
                    showOverheadBubble = false
                )
            )
            return
        }
    }

    val lastHarvest = lastHarvestTick[player.id]
    if (lastHarvest != null && currentTick - lastHarvest < game.definition.gameplayTuning.harvestCooldownTicks) return
    lastHarvestTick[player.id] = currentTick

    val nextHealth = state.currentHealth - 1
    if (resDef.animationId > 0) {
        out.add(WorldSimulationEvent.AnimationTriggered(mapId, coord.x, coord.y, resDef.animationId))
    }

    if (nextHealth <= 0) {
        val depletedState = state.copy(
            currentHealth = 0,
            depleted = true,
            respawnAtTick = currentTick + max(10, resDef.respawnTicks)
        )
        mapNodeStates[coord] = depletedState
        out.add(WorldSimulationEvent.ResourceNodeChanged(depletedState))

        val rewardItem = items[resDef.rewardItemId]
        val yieldQty = rng.nextIntInclusive(resDef.minYield, resDef.maxYield)
        if (rewardItem != null && yieldQty > 0) {
            var bag = player.inventory.addItem(rewardItem, yieldQty).updatedInventory
            // Rare extra finds roll only for nodes that have them, so other resources draw no random numbers.
            for (bonus in resDef.bonusDrops) {
                val bonusItem = items[bonus.itemId] ?: continue
                if (!rng.rollBasisPoints(bonus.chanceBasisPoints)) continue
                val qty = rng.nextIntInclusive(bonus.minAmount, bonus.maxAmount)
                val added = bag.addItem(bonusItem, qty)
                if (added.overflowAmount > 0) continue
                bag = added.updatedInventory
                out.add(
                    WorldSimulationEvent.FloatingTextEmitted(mapId, coord.x, coord.y - 1, "+$qty ${bonusItem.name}!", "RESOURCE", "#FACC15")
                )
                advanceQuestProgress(player.id, QuestObjectiveType.COLLECT_ITEM, bonusItem.id, qty, out)
            }
            val updatedPlayer = player.copy(inventory = bag)
            players[player.id] = updatedPlayer
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = mapId,
                    tileX = coord.x,
                    tileY = coord.y,
                    text = "+$yieldQty ${rewardItem.name}",
                    category = "RESOURCE",
                    colorHex = "#A3E635"
                )
            )
        }
        grantXpToCharacter(player.id, resDef.xpReward, out)
        advanceQuestProgress(player.id, QuestObjectiveType.HARVEST_RESOURCE, resDef.id, 1, out)
        if (rewardItem != null && yieldQty > 0) {
            advanceQuestProgress(player.id, QuestObjectiveType.COLLECT_ITEM, rewardItem.id, yieldQty, out)
        }
    } else {
        val damagedState = state.copy(currentHealth = nextHealth)
        mapNodeStates[coord] = damagedState
        out.add(WorldSimulationEvent.ResourceNodeChanged(damagedState))
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = mapId,
                tileX = coord.x,
                tileY = coord.y,
                text = "${resDef.swingText} ($nextHealth/${resDef.maxHealth})",
                category = "RESOURCE",
                colorHex = "#A3E635"
            )
        )
    }
}

/** The default pickup reach in tiles; a game sets `tuning.pickupRangeTiles`. */
const val GROUND_PICKUP_RANGE_TILES = com.morgana.domain.game.GameplayTuning.DEFAULT_PICKUP_RANGE_TILES

internal fun WorldSimulation.pickupGroundItemImpl(characterId: String): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    if (!player.isAlive(game.lifeVitalId)) return emptyList()

    val mapId = player.position.mapId
    val groundList = mapGroundItems[mapId] ?: return emptyList()
    val itemIdx = groundList.indices
        .filter { idx ->
            val it = groundList[idx]
            val dx = kotlin.math.abs(it.tileX - player.position.tileX)
            val dy = kotlin.math.abs(it.tileY - player.position.tileY)
            maxOf(dx, dy) <= game.definition.gameplayTuning.pickupRangeTiles &&
                (it.ownerCharacterId == null || it.ownerCharacterId == characterId || currentTick >= it.ownerProtectedUntilTick)
        }
        .minByOrNull { idx ->
            val it = groundList[idx]
            val dx = kotlin.math.abs(it.tileX - player.position.tileX)
            val dy = kotlin.math.abs(it.tileY - player.position.tileY)
            maxOf(dx, dy) * 10 + dx + dy
        } ?: return emptyList()

    val groundItem = groundList[itemIdx]
    val itemDef = items[groundItem.stack.itemId] ?: return emptyList()
    val mut = player.inventory.addStack(itemDef, groundItem.stack)
    if (mut.addedAmount <= 0) return emptyList()

    val out = mutableListOf<WorldSimulationEvent>()
    if (mut.overflowAmount <= 0) {
        groundList.removeAt(itemIdx)
    } else {
        groundList[itemIdx] = groundItem.copy(stack = groundItem.stack.copy(amount = mut.overflowAmount))
    }

    val updatedPlayer = player.copy(inventory = mut.updatedInventory)
    players[characterId] = updatedPlayer
    out.add(WorldSimulationEvent.GroundItemsChanged(mapId, groundList.toList()))
    out.add(WorldSimulationEvent.CharacterUpdated(updatedPlayer))
    advanceQuestProgress(characterId, QuestObjectiveType.COLLECT_ITEM, itemDef.id, mut.addedAmount, out)
    return out
}

internal fun WorldSimulation.useInventorySlotImpl(characterId: String, slotIndex: Int): List<WorldSimulationEvent> {
    var player = players[characterId] ?: return emptyList()
    if (actionBlocked(characterId, PlayerActivity.USE_ITEM)) return emptyList()
    val stack = player.inventory.getSlot(slotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    val taughtSpell = if (itemDef.type == ItemType.SPELL_SCROLL && itemDef.teachSpellId > 0) spells[itemDef.teachSpellId] else null
    val requiredLevel = maxOf(itemDef.levelReq, taughtSpell?.levelReq ?: 1)
    if (player.level < requiredLevel) {
        return if (taughtSpell != null) {
            listOf(tellPlayer(player, "${taughtSpell.name} requires Lv $requiredLevel.", "WARN"))
        } else {
            emptyList()
        }
    }
    if (itemDef.classReqIds.isNotEmpty() && player.classId !in itemDef.classReqIds) return emptyList()
    itemRefusal(player, itemDef)?.let { return listOf(tellPlayer(player, it, "WARN")) }
    if (taughtSpell != null && taughtSpell.classReqIds.isNotEmpty() && player.classId !in taughtSpell.classReqIds) {
        return listOf(tellPlayer(player, classRestrictionMessage(taughtSpell.name, taughtSpell.classReqIds), "WARN"))
    }
    if (taughtSpell != null) spellRefusal(player, taughtSpell)?.let { return listOf(tellPlayer(player, it, "WARN")) }

    val out = mutableListOf<WorldSimulationEvent>()

    // Equip if equipment item
    var equipSlot = itemDef.equipmentSlotOrNull()
    if (equipSlot == EquipmentSlot.WEAPON && wantsOffHand(player, itemDef)) equipSlot = EquipmentSlot.SHIELD
    if (equipSlot != null && !game.definition.hasSlot(equipSlot)) {
        return listOf(tellPlayer(player, "This game has no ${equipSlot.id.lowercase()} slot for ${itemDef.name}.", "WARN"))
    }
    if (equipSlot != null) {
        if (itemDef.attributeReq.isNotEmpty()) {
            val eff = player.effectiveAttributes(game, items)
            for ((reqAttr, reqVal) in itemDef.attributeReq) {
                if ((eff[reqAttr] ?: 0) < reqVal) return emptyList()
            }
        }
        val previouslyEquipped = player.equipment.getEquipped(equipSlot)
        val extraUnequipSlot = when {
            equipSlot == EquipmentSlot.WEAPON && itemDef.twoHanded ->
                EquipmentSlot.SHIELD.takeIf { player.equipment.getEquipped(it) != null }
            equipSlot == EquipmentSlot.SHIELD ->
                EquipmentSlot.WEAPON.takeIf {
                    val main = player.equipment.getEquipped(it)
                    main != null && items[main.itemId]?.twoHanded == true
                }
            else -> null
        }
        val extraUnequipped = extraUnequipSlot?.let { player.equipment.getEquipped(it) }
        val quantityToEquip = if (equipSlot == EquipmentSlot.AMMO) stack.amount else 1
        val (afterRemoveInv, equippedStack) = player.inventory.removeFromSlot(slotIndex, quantityToEquip) ?: return emptyList()
        var finalInv = afterRemoveInv
        if (previouslyEquipped != null) {
            val prevDef = items[previouslyEquipped.itemId]
            if (prevDef != null) {
                val res = finalInv.addStack(prevDef, previouslyEquipped)
                if (res.overflowAmount > 0) return emptyList()
                finalInv = res.updatedInventory
            }
        }
        if (extraUnequipped != null) {
            val extraDef = items[extraUnequipped.itemId]
            if (extraDef != null) {
                val res = finalInv.addStack(extraDef, extraUnequipped)
                if (res.overflowAmount > 0) return emptyList()
                finalInv = res.updatedInventory
            }
        }
        val baseEquipped = if (extraUnequipSlot != null) player.equipment.equipped - extraUnequipSlot else player.equipment.equipped
        val nextEquip = player.equipment.copy(
            equipped = baseEquipped + (equipSlot to equippedStack)
        )
        player = player.copy(inventory = finalInv, equipment = nextEquip).recalculateVitals(game, items, refill = false)
        players[characterId] = player
        out.add(WorldSimulationEvent.CharacterUpdated(player))
        out.add(WorldSimulationEvent.ItemUsed(characterId, itemDef.id, slotIndex))
        return out
    }

    // A pet item adopts its pet instead of being drunk or eaten.
    if (runItemUse(player, slotIndex, itemDef, out)) return out

    // Consumable or Spell Scroll
    if (itemDef.type == ItemType.CONSUMABLE) {
        val oldMap = player.position.mapId
        val town = if (itemDef.townGate) townForGate(player) else null
        if (itemDef.townGate && town == null && itemDef.restore.isEmpty()) {
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
                    targetCharacterId = characterId, message = "The portal fizzles: it cannot be used here.",
                    showOverheadBubble = false, notice = "WARNING"
                )
            )
            return out
        }
        val warpMapId = town?.mapId ?: itemDef.warpMapId
        val fromX = player.position.tileX
        val fromY = player.position.tileY
        val warped = if (warpMapId > 0) {
            warpedPlayer(player, warpMapId, town?.x ?: itemDef.warpX, town?.y ?: itemDef.warpY, Direction.DOWN, out)
        } else null
        if (warpMapId > 0 && warped == null && itemDef.restore.isEmpty()) {
            return out
        }
        if (itemDef.townGate && warped != null && game.definition.townGate?.leaveReturnPortal == true && MapInstances.isInstanceId(oldMap)) {
            val ownerKey = MapInstances.ownerKey(player)
            returnPortals[ownerKey] = ReturnPortal(ownerKey, oldMap, fromX, fromY, currentTick)
            out.add(tellPlayer(warped, "A return portal shimmers open behind you. Use /return in town to step back into the dungeon.", "INFO"))
        }
        val basePlayer = warped ?: player
        val (afterRemove, _) = basePlayer.inventory.removeFromSlot(slotIndex, 1) ?: return emptyList()
        val newVitals = basePlayer.vitals.toMutableMap()
        for ((vitalId, gain) in itemDef.restore) {
            val cur = newVitals[vitalId]
            if (cur != null) {
                newVitals[vitalId] = cur.heal(gain)
            }
        }
        player = basePlayer.copy(inventory = afterRemove, vitals = newVitals)
        players[characterId] = player
        for ((vitalId, gain) in itemDef.restore) {
            if (gain > 0) {
                out.add(
                    WorldSimulationEvent.FloatingTextEmitted(
                        mapId = player.position.mapId,
                        tileX = player.position.tileX,
                        tileY = player.position.tileY,
                        text = "+$gain ${vitalId.uppercase()}",
                        category = "HEAL",
                        colorHex = "#22C55E"
                    )
                )
            }
        }
        if (itemDef.animationId > 0) {
            out.add(
                WorldSimulationEvent.AnimationTriggered(
                    mapId = player.position.mapId,
                    tileX = player.position.tileX,
                    tileY = player.position.tileY,
                    animationId = itemDef.animationId
                )
            )
        }
        if (warped != null) {
            out.add(WorldSimulationEvent.PlayerMoved(player, oldMap, player.position.mapId))
            fireMapEntryEvents(characterId, out)
            player = players[characterId] ?: player
        }
        out.add(WorldSimulationEvent.CharacterUpdated(player))
        out.add(WorldSimulationEvent.ItemUsed(characterId, itemDef.id, slotIndex))
        return out
    }

    if (itemDef.type == ItemType.SPELL_SCROLL && itemDef.teachSpellId > 0) {
        val spell = spells[itemDef.teachSpellId]
        if (itemDef.teachSpellId !in player.knownSpellIds) {
            val (afterRemove, _) = player.inventory.removeFromSlot(slotIndex, 1) ?: return emptyList()
            player = player.copy(
                inventory = afterRemove,
                knownSpellIds = player.knownSpellIds + itemDef.teachSpellId
            )
            players[characterId] = player
            out.add(WorldSimulationEvent.CharacterUpdated(player))
            out.add(WorldSimulationEvent.ItemUsed(characterId, itemDef.id, slotIndex))
            if (spell != null) {
                out.add(tellPlayer(player, "Learned ${spell.name}!", "GOOD"))
            }
        } else if (spell != null) {
            out.add(tellPlayer(player, "You already know ${spell.name}.", "INFO"))
        }
        return out
    }

    return out
}

internal fun WorldSimulation.unequipSlotImpl(characterId: String, slot: EquipmentSlot): List<WorldSimulationEvent> {
    var player = players[characterId] ?: return emptyList()
    val equippedStack = player.equipment.getEquipped(slot) ?: return emptyList()
    val itemDef = items[equippedStack.itemId] ?: return emptyList()

    val mut = player.inventory.addStack(itemDef, equippedStack)
    if (mut.overflowAmount > 0) return emptyList() // Inventory full

    val nextEquip = player.equipment.copy(equipped = player.equipment.equipped - slot)
    player = player.copy(inventory = mut.updatedInventory, equipment = nextEquip).recalculateVitals(game, items, refill = false)
    players[characterId] = player
    return listOf(WorldSimulationEvent.CharacterUpdated(player))
}

internal fun WorldSimulation.dropInventorySlotImpl(characterId: String, slotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val stack = player.inventory.getSlot(slotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    if (itemDef.boundOnPickup) return emptyList()

    val actualAmount = amount.coerceIn(1, stack.amount)
    val (updatedInv, removedStack) = player.inventory.removeFromSlot(slotIndex, actualAmount) ?: return emptyList()
    val updatedPlayer = player.copy(inventory = updatedInv)
    players[characterId] = updatedPlayer

    val mapId = player.position.mapId
    val groundList = mapGroundItems.getOrPut(mapId) { mutableListOf() }
    groundList.add(
        MapGroundItem(
            instanceId = nextId("drop"),
            mapId = mapId,
            tileX = player.position.tileX,
            tileY = player.position.tileY,
            stack = removedStack,
            despawnAtTick = currentTick + game.definition.combat.groundItemDespawnTicks
        )
    )
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updatedPlayer),
        WorldSimulationEvent.GroundItemsChanged(mapId, groundList.toList())
    )
}

internal fun WorldSimulation.allocateStatPointImpl(characterId: String, statName: String): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    if (player.unspentStatPoints <= 0) return emptyList()

    val attrId = statName.lowercase()
    val attrDef = game.definition.attributes.firstOrNull { it.id == attrId && it.allocatable } ?: return emptyList()
    val curVal = player.attributes[attrId] ?: attrDef.base
    val nextAttributes = player.attributes + (attrId to (curVal + 1))
    val updated = player.copy(
        attributes = nextAttributes,
        unspentStatPoints = player.unspentStatPoints - 1
    ).recalculateVitals(game, items, refill = false)
    players[characterId] = updated
    return listOf(WorldSimulationEvent.CharacterUpdated(updated))
}

/** "Fireball is only for Fire Taoist." - the notice a player gets for a spell their class cannot use. */
internal fun WorldSimulation.classRestrictionMessage(spellName: String, classReqIds: Set<Int>): String {
    val names = classReqIds.sorted().mapNotNull { classes[it]?.name }
    return if (names.isEmpty()) "Your class cannot use $spellName." else "$spellName is only for ${names.joinToString(" / ")}."
}

/**
 * True when [itemDef], a one-handed melee weapon, should go in the off-hand slot because the character may dual-wield, already
 * holds a one-handed main-hand weapon and has nothing in the off-hand yet. Otherwise a weapon swaps with the main hand as usual,
 * so a dual-wielder takes the off-hand weapon (or shield) off first to change the main hand's pairing.
 */
internal fun WorldSimulation.wantsOffHand(player: CharacterState, itemDef: com.morgana.domain.item.ItemDefinition): Boolean {
    val dw = game.definition.dualWield ?: return false
    if (!dw.allows(player.classId) || !game.definition.hasSlot(EquipmentSlot.SHIELD) || !itemDef.canDualWield) return false
    val main = player.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { items[it.itemId] } ?: return false
    return main.canDualWield && player.equipment.getEquipped(EquipmentSlot.SHIELD) == null
}

/** The town a Town Portal would take [player] to, or null when the game has none or the map forbids it. */
internal fun WorldSimulation.townForGate(player: com.morgana.domain.entity.CharacterState): com.morgana.domain.game.TownGateTown? {
    val rules = game.definition.townGate?.takeIf { it.enabled } ?: return null
    if (player.position.mapId in rules.disabledMapIds) return null
    return com.morgana.domain.map.TownGates.nearest(maps, player.position.mapId, rules.towns)
}
