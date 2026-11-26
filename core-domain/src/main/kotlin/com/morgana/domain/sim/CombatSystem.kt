package com.morgana.domain.sim

import com.morgana.domain.combat.actionDefence
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.VitalType
import com.morgana.domain.game.VitalRole
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import kotlin.math.max

/**
 * Combat: projectiles, status effects and regeneration, melee/ranged/spell attacks, damage, death, XP and loot.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.tickProjectiles(out: MutableList<WorldSimulationEvent>) {
    for ((mapId, projList) in mapProjectiles) {
        if (projList.isEmpty()) continue
        val map = maps[mapId] ?: continue
        val updatedList = mutableListOf<MapProjectile>()
        var changed = false

        for (proj in projList) {
            if (proj.free) {
                // A free-flight projectile moves every tick; the others step a tile at a time.
                changed = true
                stepFreeProjectile(proj, mapId, map, out)?.let { updatedList.add(it) }
                continue
            }
            if (currentTick < proj.nextStepTick) {
                updatedList.add(proj)
                continue
            }
            changed = true
            val nextCoord = TileCoord(proj.currentTileX, proj.currentTileY).step(proj.direction)
            if (!map.isInBounds(nextCoord)) continue
            val cell = map.getCell(nextCoord)
            if (cell?.attribute is TileAttribute.Blocked) continue

            // Check collision with NPCs (if fired by player) or Players (if fired by player in PvP or by NPC)
            var hitSomething = false
            var hitIds = proj.hitEntityIds
            if (proj.isFromPlayer) {
                val attacker = players[proj.sourceEntityId]
                val matchingNpcs = mapNpcs[mapId].orEmpty().filter {
                    it.isAlive && it.tileX == nextCoord.x && it.tileY == nextCoord.y && it.instanceId !in hitIds
                }
                val npcsToHit = if (proj.pierce) matchingNpcs else matchingNpcs.take(1)
                for (targetNpc in npcsToHit) {
                    if (attacker != null) {
                        applyDamageToNpc(attacker, targetNpc, proj.damage, isCrit = false, out = out)
                        if (proj.weaponType.isNotBlank()) {
                            registerWeaponHit(proj.sourceEntityId, proj.weaponType, out)
                        }
                    }
                    if (proj.pierce) hitIds = hitIds + targetNpc.instanceId
                    hitSomething = true
                }
                if (!hitSomething && attacker != null) {
                    worldObjectAt(mapId, nextCoord.x, nextCoord.y)?.let { obj ->
                        if (com.morgana.domain.systems.WorldStateSystem.strike(this, attacker, obj, proj.damage, out)) hitSomething = true
                    }
                }
                if ((!hitSomething || proj.pierce) && map.moral.pvpEnabled && attacker != null) {
                    val matchingPlayers = players.values.filter {
                        it.id != proj.sourceEntityId &&
                            it.id !in hitIds &&
                            it.isAlive(game.lifeVitalId) &&
                            it.position.mapId == mapId &&
                            it.position.tileX == nextCoord.x &&
                            it.position.tileY == nextCoord.y &&
                            canAttackPlayer(attacker, it)
                    }
                    val playersToHit = if (proj.pierce) matchingPlayers else matchingPlayers.take(1)
                    for (targetPlayer in playersToHit) {
                        applyPvpDamage(attacker, targetPlayer, proj.damage, isCrit = false, out = out)
                        if (proj.weaponType.isNotBlank()) {
                            registerWeaponHit(proj.sourceEntityId, proj.weaponType, out)
                        }
                        if (proj.pierce) hitIds = hitIds + targetPlayer.id
                        hitSomething = true
                    }
                }
            } else {
                // Projectile from NPC - check collision with players
                val matchingPlayers = players.values.filter {
                    it.id !in hitIds &&
                        it.isAlive(game.lifeVitalId) &&
                        it.position.mapId == mapId &&
                        it.position.tileX == nextCoord.x &&
                        it.position.tileY == nextCoord.y
                }
                val playersToHit = if (proj.pierce) matchingPlayers else matchingPlayers.take(1)
                for (targetPlayer in playersToHit) {
                    val lifeId = game.lifeVitalId
                    val curLife = targetPlayer.vitals[lifeId]?.cur ?: 0
                    val projDamage = actionDefence(targetPlayer, proj.currentTileX, proj.currentTileY, proj.damage, out)
                    val newLife = (curLife - projDamage).coerceAtLeast(0)
                    var updated = targetPlayer.copy(
                        vitals = targetPlayer.vitals + (lifeId to (targetPlayer.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife)))
                    )
                    out.add(
                        WorldSimulationEvent.FloatingTextEmitted(
                            mapId = mapId,
                            tileX = updated.position.tileX,
                            tileY = updated.position.tileY,
                            text = "-${proj.damage}",
                            category = "DAMAGE",
                            colorHex = "#EF4444"
                        )
                    )
                    updated = afterStruck(updated, out)
                    if (!updated.isAlive(lifeId)) {
                        updated = handlePlayerDeath(updated, map, out)
                    }
                    players[updated.id] = updated
                    out.add(WorldSimulationEvent.CharacterUpdated(updated))
                    if (proj.pierce) hitIds = hitIds + targetPlayer.id
                    hitSomething = true
                }
            }

            if ((!hitSomething || proj.pierce) && proj.remainingTiles > 1) {
                updatedList.add(
                    proj.copy(
                        currentTileX = nextCoord.x,
                        currentTileY = nextCoord.y,
                        remainingTiles = proj.remainingTiles - 1,
                        nextStepTick = currentTick + proj.ticksPerTile,
                        hitEntityIds = hitIds
                    )
                )
            }
        }

        if (changed) {
            projList.clear()
            projList.addAll(updatedList)
            out.add(WorldSimulationEvent.ProjectilesChanged(mapId, projList.toList()))
        }
    }
}

/** The squared distance from the point ([qx], [qy]) to the segment ([ax], [ay])-([bx], [by]). */
private fun segmentDistance2(ax: Float, ay: Float, bx: Float, by: Float, qx: Float, qy: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 < 1e-6f) 0f else (((qx - ax) * dx + (qy - ay) * dy) / len2).coerceIn(0f, 1f)
    val cx = ax + t * dx - qx
    val cy = ay + t * dy - qy
    return cx * cx + cy * cy
}

/**
 * Moves one free-flight projectile a tick: it stops at a wall or the map's edge, hits the hostile monsters (and, where PvP is on,
 * players) its body passes within reach of on the way (the whole step is swept, so a fast shot cannot skip a target), and ends when its
 * range runs out. Returns the projectile still in flight, or null when it has ended.
 */
private fun WorldSimulation.stepFreeProjectile(proj: MapProjectile, mapId: Int, map: MapDefinition, out: MutableList<WorldSimulationEvent>): MapProjectile? {
    val ts = map.tileSize
    val nx = proj.px + proj.vx
    val ny = proj.py + proj.vy
    if (nx < 0f || ny < 0f) return null
    val tile = TileCoord((nx / ts).toInt(), (ny / ts).toInt())
    if (!map.isInBounds(tile)) return null
    if (map.getCell(tile)?.attribute is TileAttribute.Blocked) return null

    val reach = (game.definition.gameplayTuning.actionTargetRadiusPixels + 6).toFloat()
    val reach2 = reach * reach
    var hitIds = proj.hitEntityIds
    var hitSomething = false
    val attacker = players[proj.sourceEntityId]
    if (attacker != null) {
        for (npc in mapNpcs[mapId].orEmpty().toList()) {
            if (!npc.isAlive || npc.instanceId in hitIds || !isHostileNpc(npc)) continue
            if (segmentDistance2(proj.px, proj.py, nx, ny, npc.bodyX(ts) + ts / 2f, npc.bodyY(ts) + ts / 2f) > reach2) continue
            applyDamageToNpc(attacker, npc, proj.damage, isCrit = proj.crit, out = out)
            hitSomething = true
            hitIds = hitIds + npc.instanceId
            if (!proj.pierce) break
        }
        if (!hitSomething) {
            worldObjectAt(mapId, tile.x, tile.y)?.let { obj ->
                if (com.morgana.domain.systems.WorldStateSystem.strike(this, attacker, obj, proj.damage, out)) hitSomething = true
            }
        }
        if ((!hitSomething || proj.pierce) && map.moral.pvpEnabled) {
            for (target in players.values.toList()) {
                if (target.id == proj.sourceEntityId || target.id in hitIds || !target.isAlive(game.lifeVitalId) || target.position.mapId != mapId || !canAttackPlayer(attacker, target)) continue
                if (segmentDistance2(proj.px, proj.py, nx, ny, target.position.pixelX + ts / 2f, target.position.pixelY + ts / 2f) > reach2) continue
                applyPvpDamage(attacker, target, proj.damage, isCrit = false, out = out)
                hitSomething = true
                hitIds = hitIds + target.id
                if (!proj.pierce) break
            }
        }
    }
    if (hitSomething && !proj.pierce) return null
    val left = proj.remainingPixels - kotlin.math.sqrt(proj.vx * proj.vx + proj.vy * proj.vy)
    if (left <= 0f) return null
    return proj.copy(
        px = nx, py = ny, currentTileX = tile.x, currentTileY = tile.y, remainingPixels = left,
        nextStepTick = currentTick + 1L, hitEntityIds = hitIds
    )
}

private const val LAST_SEEN_STAMP_TICKS = 200L

internal fun WorldSimulation.tickPlayers(out: MutableList<WorldSimulationEvent>) {
    val playerIds = players.keys.toList()
    for (charId in playerIds) {
        var player = players[charId] ?: continue
        var modified = false
        // Remembering when the player was last here fills their rested pool while they are away; saved with them, no packet.
        if (game.definition.rested?.enabled == true && currentTick % LAST_SEEN_STAMP_TICKS == 0L) {
            player = player.copy(lastSeenWorldTick = effectiveWorldTick())
            players[charId] = player
        }

        // Check spell cast completion
        if (player.isCasting && currentTick >= player.castFinishTick) {
            val spellId = player.castingSpellId ?: 0
            val spell = spells[spellId]
            val targetEntityId = player.castTargetEntityId
            val targetTileX = player.castTargetTileX
            val targetTileY = player.castTargetTileY

            player = player.copy(
                castingSpellId = null,
                castFinishTick = 0L,
                castTotalTicks = 0,
                castTargetEntityId = null,
                castTargetTileX = 0,
                castTargetTileY = 0,
                spellCooldowns = if (spell != null) {
                    player.spellCooldowns + (spellId to (currentTick + spell.cooldownTicks))
                } else player.spellCooldowns
            )
            players[charId] = player
            modified = true

            if (spell != null && player.level >= spell.levelReq && (spell.classReqIds.isEmpty() || player.classId in spell.classReqIds)) {
                out.add(WorldSimulationEvent.SpellCooldownStarted(charId, spellId, spell.cooldownTicks))
                resolveSpellEffect(player, spell, targetEntityId, targetTileX, targetTileY, out)
                player = players[charId] ?: player
            }
        }

        // Tick active status effects
        if (player.statusEffects.isNotEmpty()) {
            val remainingEffects = mutableListOf<ActiveStatusEffect>()
            for (effect in player.statusEffects) {
                val nextPulse = effect.ticksUntilNextPulse - 1
                val nextRem = effect.remainingTicks - 1
                if (nextPulse <= 0) {
                    when (effect.type) {
                        StatusEffectType.DOT_POISON, StatusEffectType.DOT_BURN, StatusEffectType.DOT_BLEED -> {
                            val dmg = max(1, effect.magnitude)
                            val lifeId = game.lifeVitalId
                            val curLife = player.vitals[lifeId]?.cur ?: 0
                            val newLife = (curLife - dmg).coerceAtLeast(0)
                            player = player.copy(vitals = player.vitals + (lifeId to (player.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife))))
                            modified = true
                            out.add(
                                WorldSimulationEvent.FloatingTextEmitted(
                                    mapId = player.position.mapId,
                                    tileX = player.position.tileX,
                                    tileY = player.position.tileY,
                                    text = "-$dmg",
                                    category = "STATUS",
                                    colorHex = "#C084FC"
                                )
                            )
                        }
                        StatusEffectType.HOT_REGEN -> {
                            val heal = max(1, effect.magnitude)
                            val lifeId = game.lifeVitalId
                            val curLife = player.vitals[lifeId]
                            if (curLife != null) {
                                player = player.copy(vitals = player.vitals + (lifeId to curLife.heal(heal)))
                                modified = true
                            }
                            out.add(
                                WorldSimulationEvent.FloatingTextEmitted(
                                    mapId = player.position.mapId,
                                    tileX = player.position.tileX,
                                    tileY = player.position.tileY,
                                    text = "+$heal",
                                    category = "HEAL",
                                    colorHex = "#22C55E"
                                )
                            )
                        }
                        else -> {}
                    }
                }
                if (nextRem > 0) {
                    remainingEffects.add(
                        effect.copy(
                            remainingTicks = nextRem,
                            ticksUntilNextPulse = if (nextPulse <= 0) effect.tickInterval else nextPulse
                        )
                    )
                } else {
                    modified = true
                }
            }
            if (remainingEffects != player.statusEffects) {
                player = player.copy(statusEffects = remainingEffects)
                modified = true
            }
        }

        // Natural regeneration driven by game.json
        if (player.isAlive(game.lifeVitalId)) {
            val eff = player.effectiveAttributes(game, items)
            val updatedVitals = player.vitals.toMutableMap()
            var healedAny = false
            for (vDef in game.definition.vitals) {
                val interval = game.regenEveryTicks(vDef.id) ?: continue
                if (currentTick % interval.toLong() == 0L) {
                    val amount = game.regenAmount(vDef.id, player.level, eff)?.toInt() ?: 0
                    if (amount > 0) {
                        val cur = updatedVitals[vDef.id]
                        if (cur != null && cur.cur < cur.max) {
                            updatedVitals[vDef.id] = cur.heal(amount)
                            healedAny = true
                        }
                    }
                }
            }
            if (healedAny) {
                player = player.copy(vitals = updatedVitals)
                modified = true
            }
        }

        // Check HealTrap attribute on current tile
        val map = maps[player.position.mapId]
        val cellAttr = map?.getCell(player.position.tileX, player.position.tileY)?.attribute
        if (cellAttr is TileAttribute.HealTrap && currentTick % max(1, cellAttr.intervalTicks).toLong() == 0L) {
            val targetVitalId = when (cellAttr.vitalType) {
                VitalType.HP -> game.lifeVitalId
                VitalType.MP -> "mp"
                VitalType.SP -> "sp"
            }
            val cur = player.vitals[targetVitalId]
            if (cur != null) {
                if (cellAttr.isTrapDamage) {
                    player = player.copy(vitals = player.vitals + (targetVitalId to cur.applyDamage(cellAttr.amount)))
                    out.add(
                        WorldSimulationEvent.FloatingTextEmitted(
                            mapId = player.position.mapId,
                            tileX = player.position.tileX,
                            tileY = player.position.tileY,
                            text = "-${cellAttr.amount}",
                            category = "DAMAGE",
                            colorHex = "#EF4444"
                        )
                    )
                } else {
                    player = player.copy(vitals = player.vitals + (targetVitalId to cur.heal(cellAttr.amount)))
                    out.add(
                        WorldSimulationEvent.FloatingTextEmitted(
                            mapId = player.position.mapId,
                            tileX = player.position.tileX,
                            tileY = player.position.tileY,
                            text = "+${cellAttr.amount}",
                            category = "HEAL",
                            colorHex = "#22C55E"
                        )
                    )
                }
                modified = true
            }
        }

        val pkRules = game.definition.combat.pk
        if (pkRules != null && pkRules.decayIntervalTicks > 0 && player.pkPoints > 0 && currentTick % pkRules.decayIntervalTicks.toLong() == 0L) {
            val nextPk = (player.pkPoints - 1).coerceAtLeast(0)
            player = player.copy(pkPoints = nextPk, isRoguePk = nextPk > 0 && player.isRoguePk)
            modified = true
        }

        if (player.isGhost) {
            val wait = game.definition.ghost?.autoReleaseTicks ?: 0
            if (game.definition.ghost?.enabled != true || (wait > 0 && currentTick - player.ghostSinceTick >= wait)) {
                if (modified) players[charId] = player
                out.addAll(releaseGhostImpl(charId))
                continue
            }
        } else if (!player.isAlive(game.lifeVitalId)) {
            player = handlePlayerDeath(player, map, out)
            modified = true
        }

        if (modified) {
            players[charId] = player
            out.add(WorldSimulationEvent.CharacterUpdated(player))
        }
    }
}

internal fun WorldSimulation.playerAttackImpl(characterId: String): List<WorldSimulationEvent> {
    var player = players[characterId] ?: return emptyList()
    if (!player.isAlive(game.lifeVitalId)) return emptyList()
    if (player.statusEffects.any { it.preventsActions } || actionBlocked(characterId, PlayerActivity.ATTACK)) return emptyList()

    val weaponStack = player.equipment.getActive(EquipmentSlot.WEAPON, game.definition.gearRules)
    val weaponDef = weaponStack?.let { items[it.itemId] }
    val attackSpeed = weaponDef?.attackSpeedTicks ?: game.definition.combat.defaultAttackSpeedTicks
    if (currentTick - player.lastAttackTick < attackSpeed) return emptyList()
    if (weaponDef?.weaponStyle == WeaponStyle.RANGED_BOW && !modules.isEnabled(OptionalModule.PROJECTILES)) {
        return listOf(moduleDisabledNotice(player, OptionalModule.PROJECTILES))
    }

    player = player.copy(lastAttackTick = currentTick)
    players[characterId] = player

    val out = mutableListOf<WorldSimulationEvent>()
    val mapId = player.position.mapId
    val map = maps[mapId] ?: return emptyList()

    // Ranged Bow / Projectile weapon
    if (weaponDef != null && weaponDef.weaponStyle == WeaponStyle.RANGED_BOW) {
        val requiredAmmoId = weaponDef.requiredAmmoItemId
        var ammoBonusDmg = 0
        if (requiredAmmoId > 0) {
            val equippedAmmo = player.equipment.getEquipped(EquipmentSlot.AMMO)
            if (equippedAmmo != null && equippedAmmo.itemId == requiredAmmoId && equippedAmmo.amount > 0) {
                ammoBonusDmg = items[requiredAmmoId]?.attackDamage ?: 0
                val nextEquip = if (equippedAmmo.amount > 1) {
                    player.equipment.equipped + (EquipmentSlot.AMMO to equippedAmmo.copy(amount = equippedAmmo.amount - 1))
                } else {
                    player.equipment.equipped - EquipmentSlot.AMMO
                }
                player = player.copy(equipment = player.equipment.copy(equipped = nextEquip))
                players[characterId] = player
                out.add(WorldSimulationEvent.CharacterUpdated(player))
            } else {
                val afterInv = player.inventory.removeByItemId(requiredAmmoId, 1)
                if (afterInv == null) {
                    out.add(
                        WorldSimulationEvent.FloatingTextEmitted(
                            mapId = mapId,
                            tileX = player.position.tileX,
                            tileY = player.position.tileY,
                            text = "Out of Ammo!",
                            category = "MISS_DODGE",
                            colorHex = "#94A3B8"
                        )
                    )
                    return out
                }
                ammoBonusDmg = items[requiredAmmoId]?.attackDamage ?: 0
                player = player.copy(inventory = afterInv)
                players[characterId] = player
                out.add(WorldSimulationEvent.CharacterUpdated(player))
            }
        }

        val attrs = player.effectiveAttributes(game, items)
        val critBp = (game.critChanceBp(attrs).toInt() + player.equipment.totalCritBonusBp(items, game.definition.gearRules) + com.morgana.domain.game.BuildBonuses.critChanceBpBonus(player, game.definition)).coerceIn(0, 10_000)
        val isCrit = rng.rollBasisPoints(critBp)
        val defaultDefender = game.definition.attributes.associate { it.id to it.base }
        val rawProjDamage = game.rangedDamage(
            attacker = attrs,
            defender = defaultDefender,
            weapon = (player.equipment.weaponBaseDamage(weaponStack, items, game.definition.gearRules) + player.equipment.rolledAttackDamage(items, game.definition.gearRules) + com.morgana.domain.game.BuildBonuses.attackDamageBonus(player, game.definition)).toLong(),
            ammo = ammoBonusDmg.toLong(),
            armor = 0L,
            crit = isCrit
        )
        val profMult = weaponProficiencyDamageMultiplierPercent(player, weaponDef.weaponType)
        val projDamage = (rawProjDamage * profMult / 100L).toInt().coerceAtLeast(1)
        val proj = MapProjectile(
            projectileId = nextId("proj"),
            sourceEntityId = player.id,
            isFromPlayer = true,
            mapId = mapId,
            currentTileX = player.position.tileX,
            currentTileY = player.position.tileY,
            direction = player.position.direction,
            remainingTiles = max(2, weaponDef.rangeTiles),
            spriteId = max(1, weaponDef.projectileSpriteId),
            damage = projDamage,
            weaponType = weaponDef.weaponType,
            ticksPerTile = 1,
            nextStepTick = currentTick + 1L
        )
        val list = mapProjectiles.getOrPut(mapId) { mutableListOf() }
        list.add(proj)
        out.add(WorldSimulationEvent.ProjectilesChanged(mapId, list.toList()))
        return out
    }

    // Melee / Staff attack on facing tile(s) up to weapon range
    val reach = max(1, weaponDef?.rangeTiles ?: 1)
    val attackerAttrs = player.effectiveAttributes(game, items)
    val weaponDamage = player.equipment.weaponBaseDamage(weaponStack, items, game.definition.gearRules) + player.equipment.rolledAttackDamage(items, game.definition.gearRules)

    for (step in 1..reach) {
        val targetTile = player.position.tileCoord.step(player.position.direction, step)
        val targetNpc = mapNpcs[mapId]?.firstOrNull {
            it.isAlive && it.tileX == targetTile.x && it.tileY == targetTile.y
        }
        if (targetNpc != null) {
            val npcDef = npcs[targetNpc.npcId]
            if (npcDef != null && isHostileNpc(targetNpc)) {
                val critBp = (game.critChanceBp(attackerAttrs).toInt() + player.equipment.totalCritBonusBp(items, game.definition.gearRules) + com.morgana.domain.game.BuildBonuses.critChanceBpBonus(player, game.definition)).coerceIn(0, 10_000)
                val isCrit = rng.rollBasisPoints(critBp)
                val npcAttrs = game.definition.attributes.associate { a ->
                    a.id to (npcDef.attributes[a.id] ?: a.base)
                }
                val rawDmg = game.meleeDamage(
                    attacker = attackerAttrs,
                    defender = npcAttrs,
                    weapon = (weaponDamage + com.morgana.domain.game.BuildBonuses.attackDamageBonus(player, game.definition)).toLong(),
                    armor = 0L,
                    crit = isCrit
                )
                val profMult = weaponProficiencyDamageMultiplierPercent(player, weaponDef?.weaponType.orEmpty())
                val dmg = (rawDmg * profMult / 100L).toInt().coerceAtLeast(1)
                applyDamageToNpc(player, targetNpc, dmg, isCrit, out)
                if (weaponDef != null) {
                    registerWeaponHit(characterId, weaponDef.weaponType, out)
                }
                rollOffHandStrike(characterId, attackerAttrs, npcAttrs, 0L)?.let { offDmg ->
                    mapNpcs[mapId]?.firstOrNull { it.instanceId == targetNpc.instanceId && it.isAlive }?.let { stillAlive ->
                        applyDamageToNpc(players[characterId] ?: player, stillAlive, offDmg, false, out)
                    }
                }
                if (weaponDef?.animationId != null && weaponDef.animationId > 0) {
                    out.add(WorldSimulationEvent.AnimationTriggered(mapId, targetTile.x, targetTile.y, weaponDef.animationId))
                }
                return out
            }
        }

        // Check PvP target
        val targetPlayer = players.values.firstOrNull {
            it.id != player.id &&
                it.isAlive(game.lifeVitalId) && !it.isFlying &&
                it.position.mapId == mapId &&
                it.position.tileX == targetTile.x &&
                it.position.tileY == targetTile.y
        }
        if (targetPlayer != null) {
            if (!map.moral.pvpEnabled) {
                out.add(
                    WorldSimulationEvent.FloatingTextEmitted(
                        mapId = mapId,
                        tileX = targetTile.x,
                        tileY = targetTile.y,
                        text = "Safe Zone",
                        category = "MISS_DODGE",
                        colorHex = "#94A3B8"
                    )
                )
                return out
            }
            if (!canAttackPlayer(player, targetPlayer)) {
                out.add(
                    WorldSimulationEvent.FloatingTextEmitted(
                        mapId = mapId,
                        tileX = targetTile.x,
                        tileY = targetTile.y,
                        text = "${player.pkMode.label} Mode",
                        category = "MISS_DODGE",
                        colorHex = "#94A3B8"
                    )
                )
                return out
            }
            val isCrit = rng.rollBasisPoints(game.critChanceBp(attackerAttrs).toInt())
            val defAttrs = targetPlayer.effectiveAttributes(game, items)
            val defArmor = armorOf(targetPlayer)
            val rawDmg = game.meleeDamage(
                attacker = attackerAttrs,
                defender = defAttrs,
                weapon = weaponDamage.toLong(),
                armor = defArmor.toLong(),
                crit = isCrit
            )
            val profMult = weaponProficiencyDamageMultiplierPercent(player, weaponDef?.weaponType.orEmpty())
            val dmg = (rawDmg * profMult / 100L).toInt().coerceAtLeast(1)
            applyPvpDamage(player, targetPlayer, dmg, isCrit, out)
            if (weaponDef != null) {
                registerWeaponHit(characterId, weaponDef.weaponType, out)
            }
            rollOffHandStrike(characterId, attackerAttrs, defAttrs, defArmor.toLong())?.let { offDmg ->
                players[targetPlayer.id]?.takeIf { it.isAlive(game.lifeVitalId) }?.let { stillAlive ->
                    applyPvpDamage(players[characterId] ?: player, stillAlive, offDmg, false, out)
                }
            }
            return out
        }

        // A structure in reach (a wall, a totem): the blow lands on it when its kind can be attacked
        worldObjectAt(mapId, targetTile.x, targetTile.y)?.let { obj ->
            if (com.morgana.domain.systems.WorldStateSystem.strike(this, player, obj, com.morgana.domain.systems.WorldStateSystem.plainHitDamage(this, player), out)) {
                if (weaponDef != null) registerWeaponHit(characterId, weaponDef.weaponType, out)
                return out
            }
        }
    }

    // If facing a resource node, allow melee swing with tool/weapon to harvest it as well!
    val facingTile = player.position.tileCoord.step(player.position.direction)
    val facingAttr = map.getCell(facingTile)?.attribute
    if (facingAttr is TileAttribute.ResourceNode) {
        harvestResourceAt(player, facingTile, facingAttr.resourceId, out)
    }

    return out
}

internal fun WorldSimulation.playerCastSpellImpl(
    characterId: String,
    spellId: Int,
    targetEntityId: String? = null,
    targetTileX: Int = 0,
    targetTileY: Int = 0
): List<WorldSimulationEvent> {
    var caster = players[characterId] ?: return emptyList()
    if (!caster.isAlive(game.lifeVitalId)) return emptyList()
    if (caster.statusEffects.any { it.preventsActions } || actionBlocked(characterId, PlayerActivity.CAST)) return emptyList()

    val spell = spells[spellId] ?: return emptyList()
    if (spell.targetType == SpellTargetType.DIRECTIONAL_PROJECTILE && !modules.isEnabled(OptionalModule.PROJECTILES)) {
        return listOf(moduleDisabledNotice(caster, OptionalModule.PROJECTILES))
    }
    if (caster.level < spell.levelReq) {
        return listOf(tellPlayer(caster, "${spell.name} requires Lv ${spell.levelReq}.", "WARN"))
    }
    if (spell.classReqIds.isNotEmpty() && caster.classId !in spell.classReqIds) {
        return listOf(tellPlayer(caster, classRestrictionMessage(spell.name, spell.classReqIds), "WARN"))
    }
    spellRefusal(caster, spell)?.let { return listOf(tellPlayer(caster, it, "WARN")) }
    val equippedWeaponDef = caster.equipment.getActive(EquipmentSlot.WEAPON, game.definition.gearRules)?.let { items[it.itemId] }
    val equippedWeaponType = equippedWeaponDef?.weaponType.orEmpty()
    if (spell.weaponTypeReqs.isNotEmpty() && equippedWeaponType !in spell.weaponTypeReqs) {
        val wpTypes = game.definition.weaponProficiency?.weaponTypes?.associateBy { it.id }.orEmpty()
        val neededNames = spell.weaponTypeReqs.sorted().joinToString(" or ") { id ->
            wpTypes[id]?.name ?: id.replaceFirstChar { it.uppercase() }
        }
        return listOf(tellPlayer(caster, "${spell.name} requires a $neededNames equipped.", "WARN"))
    }
    if (spell.weaponProficiencyLevelReq > 0 && game.definition.weaponProficiency?.enabled == true) {
        if (equippedWeaponType.isBlank() || caster.weaponProficiencyLevel(equippedWeaponType) < spell.weaponProficiencyLevelReq) {
            return listOf(tellPlayer(caster, "${spell.name} requires weapon proficiency Lv ${spell.weaponProficiencyLevelReq}.", "WARN"))
        }
    }

    val readyTick = caster.spellCooldowns[spellId] ?: 0L
    if (currentTick < readyTick) return emptyList()
    if (caster.isCasting) return emptyList()

    val newVitals = caster.vitals.toMutableMap()
    for ((vitalId, cost) in spell.costs) {
        val cur = newVitals[vitalId] ?: return emptyList()
        if (cur.cur < cost) {
            return listOf(tellPlayer(caster, "Not enough ${vitalId.uppercase()} for ${spell.name}.", "WARN"))
        }
        newVitals[vitalId] = cur.copy(cur = cur.cur - cost)
    }

    if (spell.castTimeTicks > 0) {
        caster = caster.copy(
            vitals = newVitals,
            castingSpellId = spellId,
            castFinishTick = currentTick + spell.castTimeTicks,
            castTotalTicks = spell.castTimeTicks,
            castTargetEntityId = targetEntityId,
            castTargetTileX = targetTileX,
            castTargetTileY = targetTileY
        )
        players[characterId] = caster
        val out = mutableListOf<WorldSimulationEvent>()
        out.add(WorldSimulationEvent.SpellCastStarted(characterId, spellId, caster.position.mapId, spell.castTimeTicks))
        out.add(WorldSimulationEvent.CharacterUpdated(caster))
        return out
    }

    caster = caster.copy(
        vitals = newVitals,
        spellCooldowns = caster.spellCooldowns + (spellId to (currentTick + spell.cooldownTicks))
    )
    players[characterId] = caster

    val out = mutableListOf<WorldSimulationEvent>()
    out.add(WorldSimulationEvent.SpellCooldownStarted(characterId, spellId, spell.cooldownTicks))
    resolveSpellEffect(caster, spell, targetEntityId, targetTileX, targetTileY, out)
    out.add(WorldSimulationEvent.CharacterUpdated(players[characterId] ?: caster))
    return out
}

internal fun WorldSimulation.resolveSpellEffect(
    caster: CharacterState,
    spell: SpellDefinition,
    targetEntityId: String?,
    targetTileX: Int,
    targetTileY: Int,
    out: MutableList<WorldSimulationEvent>
) {
    var activeCaster = caster
    val characterId = activeCaster.id
    val mapId = activeCaster.position.mapId
    val casterAttrs = activeCaster.effectiveAttributes(game, items)
    out.add(WorldSimulationEvent.SpellCast(characterId, spell.id, mapId))

    val defaultTarget = game.definition.attributes.associate { it.id to it.base }

    when (spell.targetType) {
        SpellTargetType.WARP -> {
            val warped = warpedPlayer(activeCaster, spell.warpMapId, spell.warpX, spell.warpY, Direction.DOWN, out)
            if (warped != null) {
                val oldMap = activeCaster.position.mapId
                activeCaster = warped
                players[characterId] = activeCaster
                out.add(WorldSimulationEvent.PlayerMoved(activeCaster, oldMap, activeCaster.position.mapId))
                fireMapEntryEvents(characterId, out)
            }
        }
        SpellTargetType.SELF -> if (spell.revivePercent > 0) {
            val reach = max(1, spell.rangeTiles)
            val ghosts = players.values.filter {
                it.isGhost && it.id != activeCaster.id && it.position.mapId == mapId &&
                    it.position.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) <= reach
            }
            val ghost = ghosts.firstOrNull { it.id == targetEntityId }
                ?: ghosts.minWithOrNull(compareBy<CharacterState> { it.position.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) }.thenBy { it.id })
            if (ghost == null) out.add(tellPlayer(activeCaster, "There is no fallen player in range to revive.", "WARN"))
            else {
                reviveGhost(ghost, spell.revivePercent, out)
                out.add(tellPlayer(ghost, "${activeCaster.name} revived you.", "INFO"))
            }
        } else {
            if (spell.groupTarget) {
                // A group spell lands on the caster and every living party or guild ally within reach, in a fixed order.
                val reach = max(1, spell.rangeTiles)
                val lifeId = game.lifeVitalId
                val group = players.values.filter {
                    it.position.mapId == mapId && it.isAlive(lifeId) && isSupportAlly(activeCaster, it) &&
                        it.position.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) <= reach
                }.sortedBy { it.id }
                for (member in group) applySpellEffectToPlayer(activeCaster, players[member.id] ?: member, spell, casterAttrs, out)
            } else {
                val recipient = if (spell.allyTargetable) pickAllySpellTarget(activeCaster, spell, targetEntityId) else activeCaster
                applySpellEffectToPlayer(activeCaster, recipient, spell, casterAttrs, out)
            }
            activeCaster = players[characterId] ?: activeCaster
        }
        SpellTargetType.DIRECTIONAL_PROJECTILE -> {
            // Aim toward explicit target tile (or target entity) when provided, otherwise fire along current facing.
            val aimCoord = when {
                targetTileX > 0 || targetTileY > 0 -> com.morgana.domain.spatial.TileCoord(targetTileX, targetTileY)
                targetEntityId != null -> {
                    players[targetEntityId]?.takeIf { it.position.mapId == mapId }?.position?.tileCoord
                        ?: mapNpcs[mapId].orEmpty().firstOrNull { it.instanceId == targetEntityId && it.isAlive }?.tileCoord
                }
                else -> null
            }
            val aimDirection = if (aimCoord != null && aimCoord != activeCaster.position.tileCoord) {
                val dx = aimCoord.x - activeCaster.position.tileX
                val dy = aimCoord.y - activeCaster.position.tileY
                if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                    if (dx >= 0) com.morgana.domain.spatial.Direction.RIGHT else com.morgana.domain.spatial.Direction.LEFT
                } else {
                    if (dy >= 0) com.morgana.domain.spatial.Direction.DOWN else com.morgana.domain.spatial.Direction.UP
                }
            } else {
                activeCaster.position.direction
            }
            val projPower = game.spellOutput(
                caster = casterAttrs,
                target = defaultTarget,
                basePower = spell.basePower.toLong(),
                scales = spell.scales,
                heals = false
            ).toInt().coerceAtLeast(1)
            val proj = MapProjectile(
                projectileId = nextId("spellproj"),
                sourceEntityId = activeCaster.id,
                isFromPlayer = true,
                mapId = mapId,
                currentTileX = activeCaster.position.tileX,
                currentTileY = activeCaster.position.tileY,
                direction = aimDirection,
                remainingTiles = max(2, spell.rangeTiles),
                spriteId = max(1, spell.projectileSpriteId),
                damage = projPower,
                spellId = spell.id,
                ticksPerTile = 1,
                nextStepTick = currentTick + 1L,
                pierce = spell.pierce
            )
            val list = mapProjectiles.getOrPut(mapId) { mutableListOf() }
            list.add(proj)
            out.add(WorldSimulationEvent.ProjectilesChanged(mapId, list.toList()))
        }
        SpellTargetType.SINGLE_TARGET -> {
            val reach = max(1, spell.rangeTiles)
            val findNearestEligible: () -> MapNpcInstance? = {
                val list = mapNpcs[mapId].orEmpty()
                list.firstOrNull {
                    it.isAlive && (spell.isHealing || isHostileNpc(it)) &&
                        (it.instanceId == targetEntityId || (targetTileX > 0 && targetTileY > 0 && it.tileX == targetTileX && it.tileY == targetTileY))
                } ?: list.firstOrNull {
                    val facing = activeCaster.position.tileCoord.step(activeCaster.position.direction)
                    it.isAlive && (spell.isHealing || isHostileNpc(it)) && it.tileX == facing.x && it.tileY == facing.y
                } ?: if (!spell.isHealing) {
                    list.filter { isHostileNpc(it) && it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) <= reach }
                        .minWithOrNull(compareBy<MapNpcInstance> { it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) }.thenBy { it.instanceId })
                } else null
            }

            var currentTarget = findNearestEligible()
            if (currentTarget != null) {
                val hits = spell.effectiveHitCount(activeCaster.spellLevel(spell.id))
                var landed = 0
                for (hitIdx in 0 until hits) {
                    val liveTarget = mapNpcs[mapId]?.firstOrNull { it.instanceId == currentTarget!!.instanceId && it.isAlive }
                        ?: if (!spell.isHealing) {
                            mapNpcs[mapId].orEmpty()
                                .filter { isHostileNpc(it) && it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) <= reach }
                                .minWithOrNull(compareBy<MapNpcInstance> { it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) }.thenBy { it.instanceId })
                        } else null
                    if (liveTarget == null) break
                    currentTarget = liveTarget
                    val outVal = computeSpellDamageToNpc(activeCaster, casterAttrs, liveTarget, spell)
                    applySpellToNpc(activeCaster, liveTarget, spell, outVal, out)
                    landed++
                }
                if (landed > 0) {
                    levelUpSpellOnUse(characterId, activeCaster, spell, out)
                    if (spell.baseDamagePercent > 0) {
                        val weaponType = activeCaster.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { items[it.itemId]?.weaponType }.orEmpty()
                        if (weaponType.isNotBlank()) {
                            registerWeaponHit(characterId, weaponType, out)
                        }
                    }
                }
            } else if (spell.isHealing) {
                applySpellEffectToPlayer(activeCaster, activeCaster, spell, casterAttrs, out)
                activeCaster = players[characterId] ?: activeCaster
            }
        }
        SpellTargetType.AOE_SELF_CENTERED, SpellTargetType.AOE_TARGET_CENTERED -> {
            val allHostile = mapNpcs[mapId]?.filter { isHostileNpc(it) }.orEmpty()
            val center = when {
                spell.targetType == SpellTargetType.AOE_SELF_CENTERED -> activeCaster.position.tileCoord
                targetEntityId != null -> allHostile.firstOrNull { it.instanceId == targetEntityId }?.tileCoord
                    ?: activeCaster.position.tileCoord
                targetTileX > 0 || targetTileY > 0 -> TileCoord(targetTileX, targetTileY)
                else -> {
                    val reach = max(1, spell.rangeTiles)
                    allHostile
                        .filter { it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) <= reach }
                        .minWithOrNull(compareBy<MapNpcInstance> { it.tileCoord.chebyshevDistanceTo(activeCaster.position.tileCoord) }.thenBy { it.instanceId })
                        ?.tileCoord ?: activeCaster.position.tileCoord
                }
            }

            val radius = max(1, spell.aoeRadiusTiles)
            val candidates = allHostile
                .filter { it.tileCoord.chebyshevDistanceTo(center) <= radius }
                .sortedWith(compareBy<MapNpcInstance> { it.tileCoord.chebyshevDistanceTo(center) }.thenBy { it.instanceId })
            val targets = if (spell.maxTargets > 0) candidates.take(spell.maxTargets) else candidates

            for (npcTarget in targets) {
                val outVal = computeSpellDamageToNpc(activeCaster, casterAttrs, npcTarget, spell)
                applySpellToNpc(activeCaster, npcTarget, spell, outVal, out)
            }
            if (targets.isEmpty() && spell.animationId > 0) {
                out.add(WorldSimulationEvent.AnimationTriggered(mapId, center.x, center.y, spell.animationId, casterEntityId = activeCaster.id, spellId = spell.id))
            }
            if (targets.isNotEmpty()) {
                levelUpSpellOnUse(characterId, activeCaster, spell, out)
                if (spell.baseDamagePercent > 0) {
                    val weaponType = activeCaster.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { items[it.itemId]?.weaponType }.orEmpty()
                    if (weaponType.isNotBlank()) {
                        registerWeaponHit(characterId, weaponType, out)
                    }
                }
            }
        }
        SpellTargetType.GROUND_AOE -> {
            val map = maps[mapId]
            val casterTile = activeCaster.position.tileCoord
            val reach = max(1, spell.rangeTiles)
            val rawCenter = if (targetTileX > 0 || targetTileY > 0) {
                TileCoord(targetTileX, targetTileY)
            } else {
                val stepDist = minOf(reach, 3)
                TileCoord(casterTile.x + activeCaster.position.direction.dx * stepDist, casterTile.y + activeCaster.position.direction.dy * stepDist)
            }
            val clampedX = rawCenter.x.coerceIn(casterTile.x - reach, casterTile.x + reach)
            val clampedY = rawCenter.y.coerceIn(casterTile.y - reach, casterTile.y + reach)
            val center = if (map != null) {
                TileCoord(clampedX.coerceIn(0, (map.width - 1).coerceAtLeast(0)), clampedY.coerceIn(0, (map.height - 1).coerceAtLeast(0)))
            } else {
                TileCoord(clampedX, clampedY)
            }
            val radius = max(1, spell.aoeRadiusTiles)
            if (spell.isHealing) {
                val lifeId = game.lifeVitalId
                val allies = players.values
                    .filter { it.position.mapId == mapId && it.isAlive(lifeId) && isSupportAlly(activeCaster, it) && it.position.tileCoord.chebyshevDistanceTo(center) <= radius }
                    .sortedBy { it.id }
                val recipients = if (spell.maxTargets > 0) allies.take(spell.maxTargets) else allies
                for (member in recipients) {
                    applySpellEffectToPlayer(activeCaster, players[member.id] ?: member, spell, casterAttrs, out)
                }
                if (recipients.isEmpty() && spell.animationId > 0) {
                    out.add(WorldSimulationEvent.AnimationTriggered(mapId, center.x, center.y, spell.animationId, casterEntityId = activeCaster.id, spellId = spell.id))
                }
                if (recipients.isNotEmpty()) {
                    levelUpSpellOnUse(characterId, activeCaster, spell, out)
                }
            } else {
                val allHostile = mapNpcs[mapId]?.filter { isHostileNpc(it) }.orEmpty()
                val candidates = allHostile
                    .filter { it.tileCoord.chebyshevDistanceTo(center) <= radius }
                    .sortedWith(compareBy<MapNpcInstance> { it.tileCoord.chebyshevDistanceTo(center) }.thenBy { it.instanceId })
                val targets = if (spell.maxTargets > 0) candidates.take(spell.maxTargets) else candidates
                for (npcTarget in targets) {
                    val outVal = computeSpellDamageToNpc(activeCaster, casterAttrs, npcTarget, spell)
                    applySpellToNpc(activeCaster, npcTarget, spell, outVal, out)
                }
                if (targets.isEmpty() && spell.animationId > 0) {
                    out.add(WorldSimulationEvent.AnimationTriggered(mapId, center.x, center.y, spell.animationId, casterEntityId = activeCaster.id, spellId = spell.id))
                }
                if (targets.isNotEmpty()) {
                    levelUpSpellOnUse(characterId, activeCaster, spell, out)
                    if (spell.baseDamagePercent > 0) {
                        val weaponType = activeCaster.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { items[it.itemId]?.weaponType }.orEmpty()
                        if (weaponType.isNotBlank()) {
                            registerWeaponHit(characterId, weaponType, out)
                        }
                    }
                }
            }
        }
    }
}

private fun WorldSimulation.computeSpellDamageToNpc(
    caster: CharacterState,
    casterAttrs: Map<String, Int>,
    npcTarget: MapNpcInstance,
    spell: SpellDefinition
): Int {
    val npcDef = npcs[npcTarget.npcId]
    val npcAttrs = game.definition.attributes.associate { a ->
        a.id to (npcDef?.attributes?.get(a.id) ?: a.base)
    }
    if (spell.baseDamagePercent > 0) {
        val spellLv = caster.spellLevel(spell.id)
        val casterWeapon = caster.equipment.getActive(EquipmentSlot.WEAPON, game.definition.gearRules)
        val weaponDef = casterWeapon?.let { items[it.itemId] }
        val weaponDamage = (caster.equipment.weaponBaseDamage(casterWeapon, items, game.definition.gearRules) + caster.equipment.rolledAttackDamage(items, game.definition.gearRules)).toLong()
        val ammoDmg = (weaponDef?.requiredAmmoItemId?.takeIf { it > 0 }?.let { items[it]?.attackDamage } ?: 0).toLong()
        val pct = (spell.baseDamagePercent + (spellLv - 1) * spell.baseDamagePercentPerLevel).coerceAtLeast(1)
        val baseDmg = if (weaponDef?.weaponStyle == WeaponStyle.RANGED_BOW) {
            game.rangedDamage(
                attacker = casterAttrs,
                defender = npcAttrs,
                weapon = weaponDamage,
                ammo = ammoDmg,
                armor = 0L,
                crit = false
            )
        } else {
            game.meleeDamage(
                attacker = casterAttrs,
                defender = npcAttrs,
                weapon = weaponDamage,
                armor = 0L,
                crit = false
            )
        }
        val profMult = weaponProficiencyDamageMultiplierPercent(caster, weaponDef?.weaponType.orEmpty())
        return (baseDmg * pct * profMult / 10_000L).toInt().coerceAtLeast(1)
    }
    val baseOut = game.spellOutput(
        caster = casterAttrs,
        target = npcAttrs,
        basePower = spell.basePower.toLong(),
        scales = spell.scales,
        heals = spell.isHealing
    )
    val spellLv = caster.spellLevel(spell.id)
    val lvMult = 100L + (spellLv - 1).coerceAtLeast(0) * spell.powerPercentPerLevel
    return (baseOut * lvMult / 100L).toInt().coerceAtLeast(1)
}

private fun WorldSimulation.levelUpSpellOnUse(
    characterId: String,
    fallbackCaster: CharacterState,
    spell: SpellDefinition,
    out: MutableList<WorldSimulationEvent>
) {
    val spellLv = fallbackCaster.spellLevel(spell.id)
    if ((spell.baseDamagePercentPerLevel <= 0 && spell.powerPercentPerLevel <= 0 && spell.maxHitCount <= spell.hitCount) || spell.castsPerLevel <= 0 || spellLv >= spell.maxSpellLevel) return
    val latestCaster = players[characterId] ?: fallbackCaster
    val nextUses = (latestCaster.spellUses[spell.id] ?: 0) + 1
    val needed = max(1, spell.castsPerLevel) * spellLv
    val updatedCaster = if (nextUses >= needed) {
        val newLv = spellLv + 1
        out.add(tellPlayer(latestCaster, "${spell.name} leveled up to Lv $newLv!", "GOOD"))
        latestCaster.copy(
            spellLevels = latestCaster.spellLevels + (spell.id to newLv),
            spellUses = latestCaster.spellUses + (spell.id to 0)
        )
    } else {
        latestCaster.copy(spellUses = latestCaster.spellUses + (spell.id to nextUses))
    }
    players[characterId] = updatedCaster
    out.add(WorldSimulationEvent.CharacterUpdated(updatedCaster))
}

internal fun WorldSimulation.weaponProficiencyDamageMultiplierPercent(
    player: CharacterState,
    weaponType: String
): Int {
    val cfg = game.definition.weaponProficiency ?: return 100
    if (!cfg.enabled || weaponType.isBlank() || cfg.damageBonusPercentPerLevel <= 0) return 100
    val lv = player.weaponProficiencyLevel(weaponType).coerceAtMost(cfg.maxLevel)
    return 100 + (lv - 1).coerceAtLeast(0) * cfg.damageBonusPercentPerLevel
}

/** One landed weapon hit: wears the weapon (when `durability` is configured), then advances weapon proficiency. */
internal fun WorldSimulation.registerWeaponHit(
    characterId: String,
    weaponType: String,
    out: MutableList<WorldSimulationEvent>
) {
    afterAttack(characterId, out)
    awardWeaponProficiencyHit(characterId, weaponType, out)
}

internal fun WorldSimulation.awardWeaponProficiencyHit(
    characterId: String,
    weaponType: String,
    out: MutableList<WorldSimulationEvent>
) {
    val cfg = game.definition.weaponProficiency ?: return
    if (!cfg.enabled || weaponType.isBlank()) return
    val player = players[characterId] ?: return
    val curLv = player.weaponProficiencyLevel(weaponType)
    if (curLv >= cfg.maxLevel) return
    val needed = max(1, cfg.hitsPerLevel) * curLv
    val nextHits = player.weaponProficiencyHitsFor(weaponType) + 1
    val typeName = cfg.weaponTypes.firstOrNull { it.id == weaponType }?.name
        ?: weaponType.replaceFirstChar { it.uppercase() }
    val updated = if (nextHits >= needed) {
        val newLv = curLv + 1
        out.add(tellPlayer(player, "$typeName Proficiency leveled up to Lv $newLv!", "GOOD"))
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = player.position.mapId,
                tileX = player.position.tileX,
                tileY = player.position.tileY,
                text = "$typeName Lv.$newLv!",
                category = "XP",
                colorHex = "#FBBF24"
            )
        )
        player.copy(
            weaponProficiencyLevels = player.weaponProficiencyLevels + (weaponType to newLv),
            weaponProficiencyHits = player.weaponProficiencyHits + (weaponType to 0)
        )
    } else {
        player.copy(
            weaponProficiencyHits = player.weaponProficiencyHits + (weaponType to nextHits)
        )
    }
    players[characterId] = updated
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
}

/** True when [other] shares the caster's party or guild (the caster counts as their own ally). */
internal fun WorldSimulation.isSupportAlly(caster: CharacterState, other: CharacterState): Boolean =
    other.id == caster.id ||
        (caster.partyId != null && caster.partyId == other.partyId) ||
        (caster.guildId != null && caster.guildId == other.guildId)

/**
 * Chooses who an [SpellDefinition.allyTargetable] support spell lands on: the explicit target when it
 * is a living ally in range, else (healing only) the most wounded living ally in range, else the caster.
 */
internal fun WorldSimulation.pickAllySpellTarget(
    caster: CharacterState,
    spell: SpellDefinition,
    targetEntityId: String?
): CharacterState {
    val lifeId = game.lifeVitalId
    val reach = max(1, spell.rangeTiles)
    val inRange = players.values.filter {
        it.position.mapId == caster.position.mapId && it.isAlive(lifeId) && isSupportAlly(caster, it) &&
            it.position.tileCoord.chebyshevDistanceTo(caster.position.tileCoord) <= reach
    }
    inRange.firstOrNull { it.id == targetEntityId }?.let { return it }
    if (spell.cleanses) {
        // A cleanse with no named target goes to the ally carrying the most harmful statuses.
        return inRange.filter { c -> c.statusEffects.any { it.type.isNegative } }
            .sortedWith(compareByDescending<CharacterState> { c -> c.statusEffects.count { it.type.isNegative } }.thenBy { it.id })
            .firstOrNull() ?: caster
    }
    if (!spell.isHealing) return caster
    fun missing(c: CharacterState): Double {
        val v = c.vitals[lifeId] ?: return 0.0
        return if (v.max <= 0) 0.0 else 1.0 - v.cur.toDouble() / v.max
    }
    return inRange.filter { missing(it) > 0.0 }
        .sortedWith(compareByDescending<CharacterState> { missing(it) }.thenBy { it.id })
        .firstOrNull() ?: caster
}

internal fun WorldSimulation.applySpellEffectToPlayer(
    caster: CharacterState,
    target: CharacterState,
    spell: SpellDefinition,
    casterAttrs: Map<String, Int>,
    out: MutableList<WorldSimulationEvent>
) {
    var updated = target
    val targetAttrs = target.effectiveAttributes(game, items)
    val power = game.spellOutput(
        caster = casterAttrs,
        target = targetAttrs,
        basePower = spell.basePower.toLong(),
        scales = spell.scales,
        heals = spell.isHealing
    ).toInt().coerceAtLeast(1).let { raw ->
        if (spell.isHealing) com.morgana.domain.game.ClassPassive.scale(raw, game.definition.passiveOf(caster.classId).healingPercent).coerceAtLeast(1) else raw
    }
    if (spell.isHealing && power > 0) {
        val lifeId = game.lifeVitalId
        val curLife = updated.vitals[lifeId]
        if (curLife != null) {
            val before = curLife.cur
            updated = updated.copy(vitals = updated.vitals + (lifeId to curLife.heal(power)))
            afterHealed(caster, target, (updated.vitals.getValue(lifeId).cur - before).coerceAtLeast(0))
        }
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = updated.position.mapId,
                tileX = updated.position.tileX,
                tileY = updated.position.tileY,
                text = "+$power",
                category = "HEAL",
                colorHex = "#22C55E"
            )
        )
    }
    if (spell.cleanses && updated.statusEffects.any { it.type.isNegative }) {
        updated = updated.copy(statusEffects = updated.statusEffects.filterNot { it.type.isNegative })
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = updated.position.mapId,
                tileX = updated.position.tileX,
                tileY = updated.position.tileY,
                text = "Cleansed",
                category = "HEAL",
                colorHex = "#38BDF8"
            )
        )
    }
    if (spell.statusEffectType != null && spell.statusDurationTicks > 0) {
        val mod = when (spell.statusEffectType) {
            StatusEffectType.STAT_BUFF -> game.definition.attributes.associate { it.id to spell.statusMagnitude }
            StatusEffectType.STAT_DEBUFF -> game.definition.attributes.associate { it.id to -spell.statusMagnitude }
            else -> emptyMap()
        }
        val effect = ActiveStatusEffect(
            id = "spell-${spell.id}",
            type = spell.statusEffectType,
            sourceEntityId = caster.id,
            magnitude = spell.statusMagnitude,
            attributeModifiers = mod,
            remainingTicks = spell.statusDurationTicks
        )
        val filtered = updated.statusEffects.filterNot { it.id == effect.id } + effect
        updated = updated.copy(statusEffects = filtered)
    }
    if (spell.animationId > 0) {
        out.add(
            WorldSimulationEvent.AnimationTriggered(
                mapId = updated.position.mapId,
                tileX = updated.position.tileX,
                tileY = updated.position.tileY,
                animationId = spell.animationId,
                casterEntityId = caster.id,
                spellId = spell.id
            )
        )
    }
    players[updated.id] = updated
}

internal fun WorldSimulation.applySpellToNpc(
    caster: CharacterState,
    npcTarget: MapNpcInstance,
    spell: SpellDefinition,
    amount: Int,
    out: MutableList<WorldSimulationEvent>
) {
    val list = mapNpcs[npcTarget.mapId] ?: return
    val idx = list.indexOfFirst { it.instanceId == npcTarget.instanceId }
    if (idx < 0) return
    var currentNpc = list[idx]

    if (spell.statusEffectType != null && spell.statusDurationTicks > 0) {
        val eff = ActiveStatusEffect(
            id = "spell-${spell.id}",
            type = spell.statusEffectType,
            sourceEntityId = caster.id,
            magnitude = spell.statusMagnitude,
            remainingTicks = spell.statusDurationTicks
        )
        currentNpc = currentNpc.copy(
            statusEffects = currentNpc.statusEffects.filterNot { it.id == eff.id } + eff
        )
        list[idx] = currentNpc
    }
    if (spell.animationId > 0) {
        out.add(WorldSimulationEvent.AnimationTriggered(currentNpc.mapId, currentNpc.tileX, currentNpc.tileY, spell.animationId, casterEntityId = caster.id, spellId = spell.id))
    }
    applyDamageToNpc(caster, currentNpc, amount, isCrit = false, out = out)
}

private val FRIENDLY_NPC_BEHAVIORS = setOf(
    NpcBehavior.SHOPKEEPER, NpcBehavior.BANKER, NpcBehavior.GUILD_BANKER, NpcBehavior.QUEST_GIVER, NpcBehavior.FRIENDLY_ROAM
)

internal fun WorldSimulation.isHostileNpc(npc: MapNpcInstance): Boolean {
    val def = npcs[npc.npcId] ?: return false
    return npc.isAlive && def.behavior !in FRIENDLY_NPC_BEHAVIORS
}

internal fun WorldSimulation.applyDamageToNpc(
    attacker: CharacterState,
    npc: MapNpcInstance,
    rawDamage: Int,
    isCrit: Boolean,
    out: MutableList<WorldSimulationEvent>
) {
    val damage = withClassDamage(attacker, rawDamage)
    val list = mapNpcs[npc.mapId] ?: return
    val idx = list.indexOfFirst { it.instanceId == npc.instanceId }
    if (idx < 0) return

    val npcDef = npcs[npc.npcId] ?: return
    if (hitOnNpcTakenOver(attacker, npc, npcDef, damage, out)) return

    // Life leech from rolled gear affixes and boons
    if (damage > 0) {
        val leechPct = attacker.equipment.totalLifeLeechPercent(items, game.definition.gearRules) + com.morgana.domain.game.BuildBonuses.lifeLeechPercentBonus(attacker, game.definition)
        if (leechPct > 0) {
            val liveAttacker = players[attacker.id] ?: attacker
            val lifeId = game.lifeVitalId
            val life = liveAttacker.vitals[lifeId]
            if (life != null && life.cur > 0 && life.cur < life.max) {
                val heal = (damage * leechPct / 100).coerceAtLeast(1)
                val healed = liveAttacker.copy(vitals = liveAttacker.vitals + (lifeId to life.heal(heal)))
                players[healed.id] = healed
                out.add(WorldSimulationEvent.CharacterUpdated(healed))
                out.add(WorldSimulationEvent.FloatingTextEmitted(healed.position.mapId, healed.position.tileX, healed.position.tileY, "+$heal", "HEAL", "#22C55E"))
            }
        }
    }
    val lifeId = game.lifeVitalId
    val curLife = npc.vitals[lifeId]?.cur ?: 0
    val newLife = (curLife - damage).coerceAtLeast(0)
    val updatedVitals = npc.vitals + (lifeId to (npc.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife)))
    // A system may record the hit (a threat table keeps the monster on its target until someone out-threatens them); otherwise it turns on the attacker.
    var updatedNpc = monsterHit(npc.copy(vitals = updatedVitals), attacker, damage)
        ?: npc.copy(vitals = updatedVitals, targetEntityId = attacker.id)

    out.add(
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = npc.mapId,
            tileX = npc.tileX,
            tileY = npc.tileY,
            text = if (isCrit) "-$damage!" else "-$damage",
            category = if (isCrit) "CRIT_DAMAGE" else "DAMAGE",
            colorHex = if (isCrit) "#F97316" else "#EF4444"
        )
    )

    if (!updatedNpc.isAlive(lifeId)) {
        updatedNpc = handleNpcKilled(updatedNpc, npcDef, attacker, out)
    }
    list[idx] = updatedNpc
    out.add(WorldSimulationEvent.NpcUpdated(updatedNpc))
}

internal fun WorldSimulation.handleNpcKilled(
    npc: MapNpcInstance,
    npcDef: NpcDefinition,
    killedBy: CharacterState?,
    out: MutableList<WorldSimulationEvent>
): MapNpcInstance {
    val map = maps[npc.mapId]
    // A boss with a lockout rewards only the players on the map who are not already saved to it.
    val lockout = monsterKillLockout(npcDef)
    val worldNow = effectiveWorldTick()
    val fighters = if (lockout > 0) players.values.filter { it.position.mapId == npc.mapId } else emptyList()
    val unsaved = fighters.filter { (it.encounterLockouts[npcDef.id] ?: 0L) <= worldNow }
    val killer = if (lockout > 0) (killedBy?.takeIf { k -> unsaved.any { it.id == k.id } } ?: unsaved.firstOrNull()) else killedBy
    val slotRespawn = map?.npcSpawns?.firstOrNull { it.slotIndex == npc.spawnSlotIndex }?.respawnTicks ?: game.definition.combat.defaultNpcRespawnTicks
    val lifeId = game.lifeVitalId
    val deadVitals = npc.vitals + (lifeId to (npc.vitals[lifeId]?.copy(cur = 0) ?: VitalState(0, 0)))
    val deadNpc = npc.copy(
        vitals = deadVitals,
        targetEntityId = null,
        threat = emptyMap(),
        statusEffects = emptyList(),
        // A spawn with respawnTicks 0 never comes back (generated dungeon floors), nor does a Guild War gate until the war starts or ends; otherwise at least one tick later.
        respawnAtTick = if (slotRespawn <= 0 || npc.spawnSlotIndex < 0 || npcDef.guildWarRole == "GATE") 0L else currentTick + max(1, slotRespawn)
    )

    val variant = npcVariant(npc)
    val rolled = rolledVariant(npc)
    val effectiveNpcLevel = (rolled?.effectiveLevel(npcDef.level) ?: npcDef.level) + npcLevelShift(npc)

    // Roll loot drops
    val groundList = mapGroundItems.getOrPut(npc.mapId) { mutableListOf() }
    var droppedAny = false
    val playedDropSounds = mutableSetOf<String>()
    val broadcastDropIds = mutableSetOf<Int>()
    val dropPasses = if (lockout > 0 && killer == null) 0 else 1 + (variant?.bonusDropRolls ?: 0).coerceAtLeast(0)
    val dropRateMult = (variant?.dropRatePercent ?: 100).coerceAtLeast(1)
    repeat(dropPasses) {
        for (drop in npcDef.drops) {
            // Whole basis points keep the original roll (so older replays are unchanged); only per-million drops use the finer one.
            val hit = if (drop.chancePerMillion > 0) rng.rollPerMillion(drop.chancePerMillion.toLong() * dropRateMult / 100L)
            else rng.rollBasisPoints(((drop.chanceBasisPoints.toLong() * dropRateMult) / 100L).toInt().coerceIn(0, 10_000))
            if (hit) {
                val qty = rng.nextIntInclusive(drop.minAmount, drop.maxAmount)
                if (qty > 0) {
                    fun lay(stack: ItemStack) = groundList.add(
                        MapGroundItem(
                            instanceId = nextId("item"),
                            mapId = npc.mapId,
                            tileX = npc.tileX,
                            tileY = npc.tileY,
                            stack = stack,
                            ownerCharacterId = killer?.id,
                            ownerProtectedUntilTick = currentTick + game.definition.combat.lootOwnerProtectionTicks,
                            despawnAtTick = currentTick + game.definition.combat.groundItemDespawnTicks
                        )
                    )
                    val dropDef = items[drop.itemId]
                    val loot = game.definition.loot
                    if (dropDef != null && loot != null && ItemRoller.canRoll(dropDef)) {
                        // Gear in a game with loot rules: each piece rolls its own rarity and affixes at the monster's level.
                        val maxSockets = game.definition.gearUpgrade?.takeIf { it.enabled }?.maxSockets ?: 0
                        repeat(qty) {
                            val rolled = ItemRoller.roll(dropDef, effectiveNpcLevel, loot, rng)
                            lay(ItemStack(drop.itemId, 1, rolls = rolled, sockets = ItemRoller.rollDropSockets(rolled, loot, maxSockets, rng)))
                        }
                    } else {
                        lay(ItemStack(drop.itemId, qty))
                    }
                    if (dropDef != null) {
                        if (dropDef.dropSoundId.isNotBlank() && playedDropSounds.add(dropDef.dropSoundId)) {
                            out.add(WorldSimulationEvent.SoundPlayed(killer?.id.orEmpty(), npc.mapId, dropDef.dropSoundId))
                        }
                        if (dropDef.broadcastOnDrop && broadcastDropIds.add(dropDef.id)) {
                            val finder = killer?.name ?: "An adventurer"
                            val mapLabel = map?.name ?: "Map #${npc.mapId}"
                            out.add(
                                WorldSimulationEvent.ChatEmitted(
                                    channel = "BROADCAST",
                                    mapId = 0,
                                    senderId = "SYSTEM",
                                    senderName = "SYSTEM",
                                    targetCharacterId = null,
                                    message = "$finder found a ${dropDef.name} in $mapLabel (${npc.tileX}, ${npc.tileY})!",
                                    showOverheadBubble = false,
                                    colorHex = "#FBBF24"
                                )
                            )
                        }
                    }
                    droppedAny = true
                }
            }
        }
    }
    if (droppedAny) {
        out.add(WorldSimulationEvent.GroundItemsChanged(npc.mapId, groundList.toList()))
    }

    // Award XP (shared across party if killer is in an active party on the same map)
    if (killer != null) {
        val xpReward = ((npcDef.xpReward * (variant?.xpPercent ?: 100)) / 100L).coerceAtLeast(1L)
        awardPartyOrSoloExperience(killer, xpReward, out, npcLevel = effectiveNpcLevel)
        notifyKillReward(killer, xpReward, out)
        chargeXpGauge(killer.id)
        advanceQuestProgress(killer.id, QuestObjectiveType.KILL_NPC, npcDef.id, 1, out)
        if (game.definition.bestiary?.enabled == true) {
            players[killer.id]?.let { live ->
                val counted = live.copy(bestiary = live.bestiary + (npcDef.id to (live.bestiary[npcDef.id] ?: 0) + 1))
                players[live.id] = counted
                out.add(WorldSimulationEvent.CharacterUpdated(counted))
            }
        }
    }

    if (lockout > 0) {
        for (p in unsaved) {
            val live = players[p.id] ?: continue
            val saved = live.copy(encounterLockouts = live.encounterLockouts + (npcDef.id to worldNow + lockout))
            players[p.id] = saved
            out.add(WorldSimulationEvent.CharacterUpdated(saved))
            out.add(tellPlayer(saved, "You are now saved to ${npcDef.name} and cannot be rewarded by it again until the lockout ends.", "INFO"))
        }
        for (p in fighters - unsaved.toSet()) out.add(tellPlayer(p, "You are saved to ${npcDef.name}: no rewards this time.", "WARNING"))
    }
    out.add(WorldSimulationEvent.NpcKilled(npc.instanceId, npc.npcId, npc.mapId, killer?.id))

    return deadNpc
}

internal fun WorldSimulation.awardPartyOrSoloExperience(
    earner: CharacterState,
    baseXp: Long,
    out: MutableList<WorldSimulationEvent>,
    npcLevel: Int? = null
) {
    if (baseXp <= 0L) return
    val scaleByLevel = game.definition.combat.levelOffsetXpScaling && npcLevel != null
    val party = if (modules.isEnabled(OptionalModule.PARTIES)) earner.partyId?.let { parties[it] } else null
    val sameMapMembers = party?.memberCharacterIds
        ?.mapNotNull { players[it] }
        ?.filter { it.isAlive(game.lifeVitalId) && it.position.mapId == earner.position.mapId }
        .orEmpty()

    if (party != null && party.shareXp && sameMapMembers.size > 1) {
        val bonusTotal = baseXp * (100 + game.definition.combat.partyXpBonusPercent) / 100
        val splitXp = max(1L, bonusTotal / sameMapMembers.size)
        for (member in sameMapMembers) {
            val xp = if (scaleByLevel) LevelOffsetRules.scaleXp(splitXp, member.level, npcLevel!!) else splitXp
            grantXpToCharacter(member.id, withRestedXp(member.id, withGemXpBonus(member, xp), out), out)
        }
    } else {
        val xp = if (scaleByLevel) LevelOffsetRules.scaleXp(baseXp, earner.level, npcLevel!!) else baseXp
        grantXpToCharacter(earner.id, withRestedXp(earner.id, withGemXpBonus(earner, xp), out), out)
    }
}

/** [xp] plus the percent bonus from the gems socketed into [player]'s worn gear. */
internal fun WorldSimulation.withGemXpBonus(player: CharacterState, xp: Long): Long {
    val pct = (players[player.id] ?: player).equipment.totalGemXpPercent(items, game.definition.gearRules)
    return if (pct <= 0) xp else xp * (100 + pct) / 100
}

/**
 * [xp] from a kill plus the rested and catch-up bonuses (see `RestedDefinition`); spends the rested pool by the bonus it paid.
 * Nothing changes without a `rested` section or at the level cap.
 */
internal fun WorldSimulation.withRestedXp(characterId: String, xp: Long, out: MutableList<WorldSimulationEvent>): Long {
    val cfg = game.definition.rested?.takeIf { it.enabled } ?: return xp
    val player = players[characterId] ?: return xp
    if (player.level >= game.definition.progression.maxLevel) return xp
    var bonus = 0L
    if (cfg.catchUpBelowLevel > 0 && cfg.catchUpPercent > 0 && player.level < cfg.catchUpBelowLevel) {
        bonus += xp * cfg.catchUpPercent / 100
    }
    var pool = player.restedXp
    if (pool > 0 && cfg.bonusPercent > 0) {
        val rested = minOf(pool, xp * cfg.bonusPercent / 100)
        bonus += rested
        pool -= rested
        if (pool != player.restedXp) {
            val updated = player.copy(restedXp = pool)
            players[characterId] = updated
            if (pool == 0L) out.add(tellPlayer(updated, "You are no longer rested.", "INFO"))
        }
    }
    return xp + bonus
}

/** Adds the rested XP earned while [character] was away (see `RestedDefinition`); the pool never exceeds its cap. */
internal fun WorldSimulation.withRestedPoolFromAbsence(character: CharacterState): CharacterState {
    val cfg = game.definition.rested?.takeIf { it.enabled } ?: return character
    val now = effectiveWorldTick()
    val seen = character.lastSeenWorldTick
    if (seen <= 0L || now <= seen) return character.copy(lastSeenWorldTick = now)
    val needed = game.xpToNext(character) ?: return character.copy(lastSeenWorldTick = now)
    val cap = needed * cfg.poolCapPercent / 100
    val earned = needed * cfg.poolPercentPerOfflineHour * (now - seen) / (100L * TICKS_PER_HOUR)
    val pool = (character.restedXp + earned).coerceAtMost(cap)
    return character.copy(restedXp = pool, lastSeenWorldTick = now)
}

private const val TICKS_PER_HOUR = 72_000L

internal fun WorldSimulation.grantXpToCharacter(characterId: String, amount: Long, out: MutableList<WorldSimulationEvent>) {
    val current = players[characterId] ?: return
    val classDef = classes[current.classId]
    val statPointsPerLv = classDef?.statPointsPerLevel ?: 3
    val autoGrowth = classDef?.autoStatGrowthPerLevel.orEmpty()
    val (leveled, levelsGained) = current.gainExperience(game, amount, statPointsPerLv, items, autoGrowth)
    players[characterId] = leveled
    out.add(
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = leveled.position.mapId,
            tileX = leveled.position.tileX,
            tileY = leveled.position.tileY,
            text = "+$amount XP",
            category = "XP_GAIN",
            colorHex = "#38BDF8",
            targetCharacterId = leveled.id
        )
    )
    if (levelsGained > 0) {
        out.add(
            WorldSimulationEvent.ChatEmitted(
                channel = "SYSTEM", mapId = leveled.position.mapId, senderId = "system", senderName = "System",
                targetCharacterId = leveled.id, message = "Level up! You are now level ${leveled.level}.",
                showOverheadBubble = false, notice = "LEVEL"
            )
        )
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = leveled.position.mapId,
                tileX = leveled.position.tileX,
                tileY = leveled.position.tileY,
                text = "LEVEL UP! (${leveled.level})",
                category = "LEVEL_UP",
                colorHex = "#FACC15"
            )
        )
    }
    out.add(WorldSimulationEvent.CharacterUpdated(leveled))
    if (levelsGained > 0) {
        fireCommonEvents(leveled.id, CommonEventTrigger.ON_LEVEL_UP, out)
    }
}

/** [damage] a character is about to deal, raised or lowered by their class passive (see `ClassPassive.damagePercent`). */
internal fun WorldSimulation.withClassDamage(attacker: CharacterState, damage: Int): Int =
    com.morgana.domain.game.ClassPassive.scale(damage, game.definition.passiveOf(attacker.classId).damagePercent).coerceAtLeast(if (damage > 0) 1 else 0)

/** The armour a character's gear gives, with their class passive applied (see `ClassPassive.armorPercent`). */
internal fun WorldSimulation.armorOf(player: CharacterState): Int =
    com.morgana.domain.game.ClassPassive.scale(player.equipment.totalArmorDefense(items, game.definition.gearRules), game.definition.passiveOf(player.classId).armorPercent)

internal fun WorldSimulation.applyPvpDamage(
    attacker: CharacterState,
    defender: CharacterState,
    rawDamage: Int,
    isCrit: Boolean,
    out: MutableList<WorldSimulationEvent>
) {
    val damage = withClassDamage(attacker, rawDamage)
    val map = maps[defender.position.mapId] ?: return
    if (!map.moral.pvpEnabled) return
    if (!canAttackPlayer(attacker, defender)) return
    lastPvpCombatTick[attacker.id] = currentTick
    lastPvpCombatTick[defender.id] = currentTick

    val lifeId = game.lifeVitalId
    val curLife = defender.vitals[lifeId]?.cur ?: 0
    val newLife = (curLife - damage).coerceAtLeast(0)
    var updatedAttacker = attacker
    var updatedDefender = defender.copy(vitals = defender.vitals + (lifeId to (defender.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife))))

    out.add(
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = defender.position.mapId,
            tileX = defender.position.tileX,
            tileY = defender.position.tileY,
            text = if (isCrit) "-$damage!" else "-$damage",
            category = if (isCrit) "CRIT_DAMAGE" else "DAMAGE",
            colorHex = if (isCrit) "#F97316" else "#EF4444"
        )
    )

    updatedDefender = afterStruck(updatedDefender, out)
    if (updatedDefender.isAlive(lifeId) && updatedDefender.reflectPercent > 0) {
        updatedAttacker = reflectOntoPlayer(updatedAttacker, updatedDefender, damage, out)
    }
    if (!updatedDefender.isAlive(lifeId)) {
        val pkRules = game.definition.combat.pk
        val defenderIsInnocent = !defender.isRoguePk && (pkRules == null || defender.pkPoints < pkRules.redNameThreshold)
        if (map.moral == MapMoral.OPEN_PVP && defenderIsInnocent) {
            updatedAttacker = updatedAttacker.copy(
                pkPoints = updatedAttacker.pkPoints + (pkRules?.pointsPerKill ?: 1),
                isRoguePk = true
            )
            players[updatedAttacker.id] = updatedAttacker
            out.add(WorldSimulationEvent.CharacterUpdated(updatedAttacker))
        }
        updatedDefender = handlePlayerDeath(updatedDefender, map, out)
    }

    players[updatedDefender.id] = updatedDefender
    out.add(WorldSimulationEvent.CharacterUpdated(updatedDefender))
}

internal fun WorldSimulation.handlePlayerDeath(
    player: CharacterState,
    map: MapDefinition?,
    out: MutableList<WorldSimulationEvent>
): CharacterState {
    // Falling in an arena match costs nothing: the player leaves the fight with full life (see ArenaSystem).
    arbitratedDeath(player, out)?.let { return it }
    val oldMapId = player.position.mapId
    interruptActivities(player.id, out)
    var currentInv = player.inventory
    var currentEquip = player.equipment
    val pkRules = game.definition.combat.pk
    val isBlackName = pkRules != null && player.pkPoints >= pkRules.blackNameThreshold
    val isRedName = pkRules != null && player.pkPoints >= pkRules.redNameThreshold

    // Drop non-bound inventory items if map moral is OPEN_PVP (Drop-on-Death)
    if (map != null && map.moral.dropItemsOnDeath) {
        val groundList = mapGroundItems.getOrPut(map.id) { mutableListOf() }
        var dropped = false
        val keptSlots = currentInv.slots.map { stack ->
            if (stack != null && items[stack.itemId]?.boundOnPickup != true) {
                groundList.add(
                    MapGroundItem(
                        instanceId = nextId("drop"),
                        mapId = map.id,
                        tileX = player.position.tileX,
                        tileY = player.position.tileY,
                        stack = stack,
                        despawnAtTick = currentTick + game.definition.combat.groundItemDespawnTicks
                    )
                )
                dropped = true
                null
            } else {
                stack
            }
        }
        if (dropped) {
            currentInv = currentInv.copy(slots = keptSlots)
            out.add(WorldSimulationEvent.GroundItemsChanged(map.id, groundList.toList()))
        }
    }

    // Red/Black names risk dropping equipped non-bound gear on death
    if (pkRules != null && (isRedName || isBlackName) && map != null) {
        val equipDropBp = if (isBlackName) pkRules.blackEquipDropChanceBp else pkRules.redEquipDropChanceBp
        if (equipDropBp > 0 && currentEquip.equipped.isNotEmpty()) {
            val groundList = mapGroundItems.getOrPut(map.id) { mutableListOf() }
            val keptEquip = currentEquip.equipped.toMutableMap()
            var droppedEquip = false
            for ((slot, stack) in currentEquip.equipped) {
                if (items[stack.itemId]?.boundOnPickup == true) continue
                if (rng.rollBasisPoints(equipDropBp)) {
                    groundList.add(
                        MapGroundItem(
                            instanceId = nextId("drop"),
                            mapId = map.id,
                            tileX = player.position.tileX,
                            tileY = player.position.tileY,
                            stack = stack,
                            despawnAtTick = currentTick + game.definition.combat.groundItemDespawnTicks
                        )
                    )
                    keptEquip.remove(slot)
                    droppedEquip = true
                }
            }
            if (droppedEquip) {
                currentEquip = com.morgana.domain.item.EquipmentLoadout(keptEquip)
                out.add(WorldSimulationEvent.GroundItemsChanged(map.id, groundList.toList()))
            }
        }
    }

    // The cost of dying in a game that sets one: worn gear loses durability. The death notice states it.
    val ghostCfg = game.definition.ghost?.takeIf { it.enabled }
    var wearNotice = ""
    val wearPoints = ghostCfg?.deathWearPoints ?: 0
    if (wearPoints > 0 && game.definition.durability?.enabled == true && currentEquip.equipped.isNotEmpty()) {
        currentEquip = currentEquip.copy(
            equipped = currentEquip.equipped.mapValues { (_, st) -> st.copy(durability = (st.durability - wearPoints).coerceAtLeast(0)) }
        )
        wearNotice = " Your worn gear lost $wearPoints durability."
    }

    val classDef = classes[player.classId]
    val sentToJail = pkRules != null && (isRedName || isBlackName) && pkRules.jailMapId > 0 && maps.containsKey(pkRules.jailMapId)
    val respawnMapId = when {
        sentToJail -> pkRules.jailMapId
        map?.moral?.safeRespawnInPlace == true -> oldMapId
        else -> classDef?.startMapId ?: 1
    }
    val destMap = maps[respawnMapId] ?: map
    val respawnX = when {
        sentToJail -> pkRules.jailX
        map?.moral?.safeRespawnInPlace == true -> player.position.tileX
        else -> classDef?.startX ?: 12
    }
    val respawnY = when {
        sentToJail -> pkRules.jailY
        map?.moral?.safeRespawnInPlace == true -> player.position.tileY
        else -> classDef?.startY ?: 9
    }
    if (sentToJail) {
        out.add(
            WorldSimulationEvent.ChatEmitted(
                channel = "SYSTEM",
                mapId = respawnMapId,
                senderId = "server",
                senderName = "System",
                message = "Your PK crimes landed you in PK Jail!",
                targetCharacterId = player.id
            )
        )
    }

    // With a `ghost` section the dead stay where they fell as a ghost; arenas and the PK jail still respawn at once.
    if (game.definition.ghost?.enabled == true && !sentToJail && map?.moral?.safeRespawnInPlace != true) {
        val lifeId = game.lifeVitalId
        val ghost = player.copy(
            inventory = currentInv,
            equipment = currentEquip,
            statusEffects = emptyList(),
            castingSpellId = null,
            castFinishTick = 0L,
            castTotalTicks = 0,
            castTargetEntityId = null,
            castTargetTileX = 0,
            castTargetTileY = 0,
            vitals = player.vitals + (lifeId to (player.vitals[lifeId]?.copy(cur = 0) ?: VitalState(0, 1))),
            isGhost = true,
            ghostSinceTick = currentTick
        )
        out.add(WorldSimulationEvent.PlayerDied(player.id, oldMapId))
        val selfRevive = if ((ghostCfg?.selfReviveAfterTicks ?: 0) > 0) {
            " Type /revive after ${ghostCfg!!.selfReviveAfterTicks / 20} seconds to get back up where you fell,"
        } else ""
        out.add(tellPlayer(ghost, "You have died.$wearNotice$selfRevive Find a healer to revive you, or type /release to return to town.", "WARN"))
        return ghost
    }

    val respawned = respawnedAt(
        player.copy(inventory = currentInv, equipment = currentEquip),
        respawnMapId, respawnX, respawnY, destMap
    )
    out.add(WorldSimulationEvent.PlayerDied(player.id, oldMapId))
    out.add(WorldSimulationEvent.PlayerMoved(respawned, oldMapId, respawned.position.mapId))
    return respawned
}

/** [player] standing at the given tile of [destMap] with full vitals and no spell, effects or ghost state (a respawn). */
private fun WorldSimulation.respawnedAt(player: CharacterState, mapId: Int, tileX: Int, tileY: Int, destMap: MapDefinition?): CharacterState =
    player.copy(
        statusEffects = emptyList(),
        castingSpellId = null,
        castFinishTick = 0L,
        castTotalTicks = 0,
        castTargetEntityId = null,
        castTargetTileX = 0,
        castTargetTileY = 0,
        isGhost = false,
        ghostSinceTick = 0L,
        position = WorldPosition.fromTile(
            mapId = mapId,
            tileX = tileX.coerceIn(0, (destMap?.width ?: 24) - 1),
            tileY = tileY.coerceIn(0, (destMap?.height ?: 18) - 1),
            direction = Direction.DOWN,
            tileSize = destMap?.tileSize ?: WorldPosition.DEFAULT_TILE_SIZE
        )
    ).recalculateVitals(game, items, refill = true)

/** `/revive`: a ghost who has waited long enough gets back up where they fell with part of their life (see `GhostDefinition`). */
internal fun WorldSimulation.selfReviveImpl(characterId: String): List<WorldSimulationEvent> {
    val ghost = players[characterId]?.takeIf { it.isGhost } ?: return emptyList()
    val cfg = game.definition.ghost?.takeIf { it.enabled && it.selfReviveAfterTicks > 0 }
        ?: return listOf(tellPlayer(ghost, "You need a healer, or /release to go home.", "WARN"))
    val left = cfg.selfReviveAfterTicks - (currentTick - ghost.ghostSinceTick)
    if (left > 0) return listOf(tellPlayer(ghost, "You can get up in ${(left + 19) / 20} seconds.", "INFO"))
    val out = mutableListOf<WorldSimulationEvent>()
    reviveGhost(ghost, cfg.selfRevivePercent, out)
    return out
}

/** Sends a ghost home: full life at their class's starting tile (`/release`, the auto-release timer and login as a ghost). */
internal fun WorldSimulation.releaseGhostImpl(characterId: String): List<WorldSimulationEvent> {
    val ghost = players[characterId]?.takeIf { it.isGhost } ?: return emptyList()
    val classDef = classes[ghost.classId]
    val mapId = classDef?.startMapId ?: 1
    val home = respawnedAt(ghost, mapId, classDef?.startX ?: 12, classDef?.startY ?: 9, maps[mapId])
    players[characterId] = home
    return listOf(
        WorldSimulationEvent.PlayerMoved(home, ghost.position.mapId, home.position.mapId),
        WorldSimulationEvent.CharacterUpdated(home)
    )
}

/** Brings the ghost [target] back on the spot with [percent] of their life (at least 1). */
internal fun WorldSimulation.reviveGhost(target: CharacterState, percent: Int, out: MutableList<WorldSimulationEvent>) {
    val lifeId = game.lifeVitalId
    val life = target.vitals[lifeId] ?: return
    val hp = (life.max.toLong() * percent.coerceIn(1, 100) / 100L).toInt().coerceIn(1, life.max.coerceAtLeast(1))
    val revived = target.copy(
        isGhost = false,
        ghostSinceTick = 0L,
        vitals = target.vitals + (lifeId to life.copy(cur = hp))
    )
    players[revived.id] = revived
    out.add(WorldSimulationEvent.FloatingTextEmitted(revived.position.mapId, revived.position.tileX, revived.position.tileY, "Revived", "HEAL", "#22C55E"))
    out.add(WorldSimulationEvent.CharacterUpdated(revived))
}

/** Floating text shown over a player whose action was refused because the server switched a module off. */
internal fun moduleDisabledNotice(player: CharacterState, module: OptionalModule): WorldSimulationEvent =
    WorldSimulationEvent.FloatingTextEmitted(
        mapId = player.position.mapId,
        tileX = player.position.tileX,
        tileY = player.position.tileY,
        text = "${module.displayName} disabled",
        category = "MISS_DODGE",
        colorHex = "#94A3B8"
    )

/**
 * Kill bonuses for [characterId]: adds `xpGauge.gainPerKill` to the gauge vital and extends the running status effect of
 * every spell with `killExtendTicks`.
 */
internal fun WorldSimulation.chargeXpGauge(characterId: String) {
    val current = players[characterId] ?: return
    var updated = current
    game.definition.xpGauge?.takeIf { it.enabled }?.let { gauge ->
        val vital = updated.vitals[gauge.vitalId]
        if (vital != null && gauge.gainPerKill > 0) {
            updated = updated.copy(vitals = updated.vitals + (gauge.vitalId to vital.copy(cur = (vital.cur + gauge.gainPerKill).coerceAtMost(vital.max))))
        }
    }
    if (updated.statusEffects.isNotEmpty()) {
        val extended = updated.statusEffects.map { effect ->
            val spellId = effect.id.removePrefix("spell-").takeIf { effect.id.startsWith("spell-") }?.toIntOrNull()
            val extra = spellId?.let { spells[it]?.killExtendTicks } ?: 0
            if (extra > 0) effect.copy(remainingTicks = effect.remainingTicks + extra) else effect
        }
        if (extended != updated.statusEffects) updated = updated.copy(statusEffects = extended)
    }
    if (updated != current) players[characterId] = updated
}

/**
 * Dual-wielding: after a landed melee hit, rolls the off-hand weapon's strike chance and returns the damage of that second
 * strike (a normal non-critical hit with the off-hand weapon scaled by `dualWield.damagePercent` and that weapon's
 * proficiency), or null when [characterId] has no off-hand weapon, may not dual-wield, or the roll fails. No RNG is drawn
 * unless a usable off-hand weapon is worn, so games without dual-wielding replay exactly as before.
 */
internal fun WorldSimulation.rollOffHandStrike(
    characterId: String,
    attackerAttrs: Map<String, Int>,
    defenderAttrs: Map<String, Int>,
    armor: Long
): Int? {
    val dw = game.definition.dualWield ?: return null
    val player = players[characterId] ?: return null
    if (!dw.allows(player.classId)) return null
    val offStack = player.equipment.getActiveOffHandWeapon(items, game.definition.gearRules) ?: return null
    val offDef = items[offStack.itemId] ?: return null
    if (!rng.rollBasisPoints(dw.strikeChancePercent * 100)) return null
    val raw = game.meleeDamage(
        attacker = attackerAttrs,
        defender = defenderAttrs,
        weapon = player.equipment.weaponDamageOf(offStack, items, game.definition.gearRules).toLong(),
        armor = armor,
        crit = false
    )
    val profMult = weaponProficiencyDamageMultiplierPercent(player, offDef.weaponType)
    return (raw * dw.damagePercent / 100L * profMult / 100L).toInt().coerceAtLeast(1)
}
