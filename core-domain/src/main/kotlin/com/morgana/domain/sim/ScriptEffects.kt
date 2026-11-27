package com.morgana.domain.sim

import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.script.ScriptCapability
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState

internal fun WorldSimulation.applyScriptEffectImpl(effect: ScriptEffect): List<WorldSimulationEvent> {
    val allowed = game.definition.capabilities?.contains(ScriptCapability.requiredCapability(effect)) ?: true
    if (!allowed) {
        return emptyList()
    }
    val out = mutableListOf<WorldSimulationEvent>()
    when (effect) {
        is ScriptEffect.DamagePlayer -> {
            val player = players[effect.targetId] ?: return emptyList()
            val vital = player.vitals[effect.vitalId] ?: return emptyList()
            val newCur = (vital.cur - effect.amount).coerceAtLeast(0)
            val updatedVitals = player.vitals + (effect.vitalId to vital.copy(cur = newCur))
            val updated = player.copy(vitals = updatedVitals)
            players[player.id] = updated
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = player.position.mapId,
                    tileX = player.position.tileX,
                    tileY = player.position.tileY,
                    text = "-${effect.amount}",
                    category = "DAMAGE",
                    colorHex = "#EF4444"
                )
            )
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
            if (effect.vitalId == game.lifeVitalId && newCur <= 0) {
                handlePlayerDeath(updated, maps[player.position.mapId], out)
            }
        }
        is ScriptEffect.DamageNpc -> {
            val list = mapNpcs[effect.mapId] ?: return emptyList()
            val idx = list.indexOfFirst { it.instanceId == effect.npcInstanceId }
            if (idx < 0) return emptyList()
            val npc = list[idx]
            val vital = npc.vitals[effect.vitalId] ?: return emptyList()
            val newCur = (vital.cur - effect.amount).coerceAtLeast(0)
            val updatedVitals = npc.vitals + (effect.vitalId to vital.copy(cur = newCur))
            var updatedNpc = npc.copy(vitals = updatedVitals)
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = effect.mapId,
                    tileX = npc.tileX,
                    tileY = npc.tileY,
                    text = "-${effect.amount}",
                    category = "DAMAGE",
                    colorHex = "#EF4444"
                )
            )
            if (effect.vitalId == game.lifeVitalId && newCur <= 0) {
                val npcDef = npcs[npc.npcId]
                if (npcDef != null) {
                    val killer = effect.sourcePlayerId?.let { players[it] }
                    updatedNpc = handleNpcKilled(updatedNpc, npcDef, killer, out)
                }
            }
            list[idx] = updatedNpc
            out.add(WorldSimulationEvent.NpcUpdated(updatedNpc))
        }
        is ScriptEffect.HealPlayer -> {
            val player = players[effect.targetId] ?: return emptyList()
            val vital = player.vitals[effect.vitalId] ?: return emptyList()
            val newCur = (vital.cur + effect.amount).coerceAtMost(vital.max)
            val updatedVitals = player.vitals + (effect.vitalId to vital.copy(cur = newCur))
            val updated = player.copy(vitals = updatedVitals)
            players[player.id] = updated
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = player.position.mapId,
                    tileX = player.position.tileX,
                    tileY = player.position.tileY,
                    text = "+${effect.amount}",
                    category = "HEAL",
                    colorHex = "#22C55E"
                )
            )
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        is ScriptEffect.HealNpc -> {
            val list = mapNpcs[effect.mapId] ?: return emptyList()
            val idx = list.indexOfFirst { it.instanceId == effect.npcInstanceId }
            if (idx < 0) return emptyList()
            val npc = list[idx]
            val vital = npc.vitals[effect.vitalId] ?: return emptyList()
            val newCur = (vital.cur + effect.amount).coerceAtMost(vital.max)
            val updatedVitals = npc.vitals + (effect.vitalId to vital.copy(cur = newCur))
            val updatedNpc = npc.copy(vitals = updatedVitals)
            list[idx] = updatedNpc
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = effect.mapId,
                    tileX = npc.tileX,
                    tileY = npc.tileY,
                    text = "+${effect.amount}",
                    category = "HEAL",
                    colorHex = "#22C55E"
                )
            )
            out.add(WorldSimulationEvent.NpcUpdated(updatedNpc))
        }
        is ScriptEffect.GiveItem -> {
            val player = players[effect.playerId] ?: return emptyList()
            val def = items[effect.itemId] ?: return emptyList()
            val mut = player.inventory.addItem(def, effect.amount)
            val updated = player.copy(inventory = mut.updatedInventory)
            players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        is ScriptEffect.RemoveItem -> {
            val player = players[effect.playerId] ?: return emptyList()
            val after = player.inventory.removeByItemId(effect.itemId, effect.amount) ?: return emptyList()
            val updated = player.copy(inventory = after)
            players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        is ScriptEffect.WarpPlayer -> {
            val player = players[effect.playerId] ?: return emptyList()
            val oldMap = player.position.mapId
            val updated = warpedPlayer(player, effect.mapId, effect.x, effect.y, Direction.DOWN, out) ?: return out
            players[player.id] = updated
            out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, updated.position.mapId))
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
            checkQuestVisitMap(updated, updated.position.mapId, out)
            fireMapEntryEvents(player.id, out)
        }
        is ScriptEffect.ApplyStatus -> {
            val player = players[effect.targetPlayerId] ?: return emptyList()
            val filtered = player.statusEffects.filterNot { it.id == effect.effect.id }
            val updated = player.copy(statusEffects = filtered + effect.effect)
            players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        is ScriptEffect.RemoveStatus -> {
            val player = players[effect.targetPlayerId] ?: return emptyList()
            val filtered = player.statusEffects.filterNot { it.type == effect.type }
            val updated = player.copy(statusEffects = filtered)
            players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        is ScriptEffect.BroadcastMessage -> {
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "GLOBAL",
                    mapId = effect.mapId ?: 0,
                    senderId = "SYSTEM",
                    senderName = "SYSTEM",
                    targetCharacterId = null,
                    message = effect.message,
                    showOverheadBubble = false,
                    colorHex = effect.colorHex
                )
            )
        }
        is ScriptEffect.SendPlayerMessage -> {
            val player = players[effect.playerId] ?: return emptyList()
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM",
                    mapId = player.position.mapId,
                    senderId = "SYSTEM",
                    senderName = "SYSTEM",
                    targetCharacterId = effect.playerId,
                    message = effect.message,
                    showOverheadBubble = false,
                    colorHex = effect.colorHex
                )
            )
        }
        is ScriptEffect.EmitFloatingText -> {
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = effect.mapId,
                    tileX = effect.x,
                    tileY = effect.y,
                    text = effect.text,
                    category = effect.category,
                    colorHex = effect.colorHex
                )
            )
        }
        is ScriptEffect.TriggerAnimation -> {
            out.add(
                WorldSimulationEvent.AnimationTriggered(
                    mapId = effect.mapId,
                    tileX = effect.x,
                    tileY = effect.y,
                    animationId = effect.animationId,
                    soundId = effect.soundId
                )
            )
        }
        is ScriptEffect.SpawnProjectile -> {
            val proj = MapProjectile(
                projectileId = nextId("proj"),
                sourceEntityId = effect.sourceEntityId,
                isFromPlayer = effect.isFromPlayer,
                mapId = effect.mapId,
                currentTileX = effect.currentTileX,
                currentTileY = effect.currentTileY,
                direction = effect.direction,
                remainingTiles = effect.remainingTiles.coerceAtLeast(1),
                spriteId = effect.spriteId.coerceAtLeast(1),
                damage = effect.damage.coerceAtLeast(1),
                spellId = effect.spellId,
                ticksPerTile = effect.ticksPerTile.coerceAtLeast(1),
                nextStepTick = currentTick + effect.ticksPerTile.coerceAtLeast(1)
            )
            val list = mapProjectiles.getOrPut(effect.mapId) { mutableListOf() }
            list.add(proj)
            out.add(WorldSimulationEvent.ProjectilesChanged(effect.mapId, list.toList()))
        }
        is ScriptEffect.AwardExperience -> {
            awardPartyOrSoloExperience(players[effect.playerId] ?: return emptyList(), effect.amount, out)
        }
        is ScriptEffect.ScheduleOnce ->
            scheduleOnce(effect.id, effect.atMillis, effect.name, effect.warningsSeconds, effect.data, effect.warningMessage, effect.fireMessage)
        is ScriptEffect.CancelSchedule -> cancelSchedule(effect.id)
        is ScriptEffect.DespawnNpcs -> despawnNpcs(effect.mapId, effect.npcId, out)
        is ScriptEffect.ResetMap -> resetMapRuntime(effect.mapId, out)
        is ScriptEffect.PlaceObject -> placeWorldObject(effect.kind, effect.mapId, effect.x, effect.y, effect.ownerId, null, effect.data, out)
        is ScriptEffect.RemoveObject -> removeWorldObject(effect.id, effect.reason, out)
        is ScriptEffect.DamageObject -> damageWorldObject(effect.id, effect.amount, out)
        is ScriptEffect.HealObject -> healWorldObject(effect.id, effect.amount, out)
        is ScriptEffect.SetObjectData -> setWorldObjectData(effect.id, effect.data, out)
        is ScriptEffect.SetState -> {
            if (effect.values.isEmpty()) scriptState.remove(effect.key) else scriptState[effect.key] = effect.values.toSortedMap()
        }
        is ScriptEffect.ConsumeVital -> {
            val player = players[effect.playerId] ?: return emptyList()
            val cur = player.vitals[effect.vitalId] ?: return emptyList()
            val newCur = (cur.cur - effect.amount).coerceAtLeast(0)
            val updated = player.copy(vitals = player.vitals + (effect.vitalId to cur.copy(cur = newCur)))
            players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
    }
    return out
}
