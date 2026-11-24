package com.morgana.domain.sim

import com.morgana.domain.combat.actionDefence
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.game.VitalRole
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import kotlin.math.abs
import kotlin.math.max

/**
 * NPC AI tick: behavior, pathing, aggro, respawn.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.tickNpcs(out: MutableList<WorldSimulationEvent>) {
    val npcRules = game.definition.combat.npcs
    // How many live monsters already hunt each player (for combat.npcs.maxAttackersPerTarget).
    val hunters = HashMap<String, Int>()
    if (npcRules.maxAttackersPerTarget > 0) {
        for (list in mapNpcs.values) for (n in list) {
            val t = n.targetEntityId
            if (t != null && n.isAlive) hunters[t] = (hunters[t] ?: 0) + 1
        }
    }
    for ((mapId, npcList) in mapNpcs) {
        val map = maps[mapId] ?: continue
        beforeMonstersOf(mapId, npcList, out)
        for (idx in npcList.indices) {
            var npc = npcList[idx]
            val npcDef = npcs[npc.npcId] ?: continue
            // A pet is moved by PetSystem, not by the monster AI.
            if (npc.ownerId != null) continue

            // Respawn check
            if (!npc.isAlive) {
                if (npc.respawnAtTick > 0L && currentTick >= npc.respawnAtTick) {
                    val newVariant = rollVariantForNpc(npcDef)
                    val vMap = spawnVitalsForNpc(npcDef, newVariant, npc.scale)
                    npc = npc.copy(
                        tileX = npc.homeX,
                        tileY = npc.homeY,
                        direction = Direction.DOWN,
                        vitals = vMap,
                        targetEntityId = null,
                        statusEffects = emptyList(),
                        castingSpellId = null,
                        castFinishTick = 0L,
                        strikeReadyTick = 0L,
                        respawnAtTick = 0L,
                        variantId = newVariant?.id,
                        phaseIndex = 0,
                        zone = null,
                        abilityReadyTicks = emptyMap()
                    )
                    npcList[idx] = npc
                    out.add(WorldSimulationEvent.NpcUpdated(npc))
                }
                continue
            }

            // Some monsters do nothing at all (Guild War poles and gates stand still and never fight back).
            if (monsterHoldsStill(npc, npcDef)) continue

            // A scripted step before the monster thinks (a boss fight's danger zones, phases and adds).
            val stepped = stepMonster(npc, npcDef, map, out)
            if (stepped != npc) {
                npc = stepped
                npcList[idx] = npc
            }
            // A step can end the monster's life (a plugin's damage): a dead monster does no more thinking this tick.
            if (!npc.isAlive(game.lifeVitalId)) continue

            // Status effects on NPC
            if (npc.statusEffects.isNotEmpty()) {
                val nextEffects = mutableListOf<ActiveStatusEffect>()
                for (eff in npc.statusEffects) {
                    val pulse = eff.ticksUntilNextPulse - 1
                    val rem = eff.remainingTicks - 1
                    if (pulse <= 0 && eff.type in setOf(StatusEffectType.DOT_POISON, StatusEffectType.DOT_BURN, StatusEffectType.DOT_BLEED)) {
                        val dmg = max(1, eff.magnitude)
                        val lifeId = game.lifeVitalId
                        val curLife = npc.vitals[lifeId]?.cur ?: 0
                        val newLife = (curLife - dmg).coerceAtLeast(0)
                        val updatedVitals = npc.vitals + (lifeId to (npc.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife)))
                        npc = npc.copy(vitals = updatedVitals)
                        out.add(
                            WorldSimulationEvent.FloatingTextEmitted(
                                mapId = mapId,
                                tileX = npc.tileX,
                                tileY = npc.tileY,
                                text = "-$dmg",
                                category = "STATUS",
                                colorHex = "#C084FC"
                            )
                        )
                    }
                    if (rem > 0) {
                        nextEffects.add(eff.copy(remainingTicks = rem, ticksUntilNextPulse = if (pulse <= 0) eff.tickInterval else pulse))
                    }
                }
                npc = npc.copy(statusEffects = nextEffects)
                if (!npc.isAlive(game.lifeVitalId)) {
                    val killer = npc.targetEntityId?.let { players[it] }
                    npc = handleNpcKilled(npc, npcDef, killer, out)
                    npcList[idx] = npc
                    out.add(WorldSimulationEvent.NpcUpdated(npc))
                    continue
                }
            }

            if (npc.statusEffects.any { it.preventsActions }) {
                // Stunned or frozen: a spell being wound up is interrupted.
                if (npc.castingSpellId != null) npc = npc.copy(castingSpellId = null, castFinishTick = 0L)
                npcList[idx] = npc
                continue
            }

            val variant = adjustedVariant(npc, npcVariant(npc))
            val effectiveBehavior = if (variant?.forceAggro == true) NpcBehavior.ATTACK_ON_SIGHT else npcDef.behavior
            // A SLOW status stretches both walking and striking by its magnitude in percent (the strongest one counts).
            val slowPercent = npc.statusEffects.filter { it.type == StatusEffectType.SLOW }.maxOfOrNull { it.magnitude.coerceIn(0, 300) } ?: 0
            val effectiveMoveSpeedTicks = ((npcDef.moveSpeedTicks * (variant?.moveSpeedPercent ?: 100) / 100) * (100 + slowPercent) / 100).coerceAtLeast(4)
            val effectiveAttackSpeedTicks = npcDef.attackSpeedTicks * (100 + slowPercent) / 100
            val effectiveAttackDamage = ((npcDef.attackDamage * (variant?.attackPercent ?: 100)) / 100).coerceAtLeast(1)

            // Acquire target if ATTACK_ON_SIGHT or GUARD
            var targetPlayer = npc.targetEntityId?.let { players[it] }?.takeIf {
                it.isAlive(game.lifeVitalId) && it.position.mapId == mapId
            }
            if (targetPlayer == null && (effectiveBehavior == NpcBehavior.ATTACK_ON_SIGHT || effectiveBehavior == NpcBehavior.GUARD)) {
                val huntCap = npcRules.maxAttackersPerTarget
                targetPlayer = players.values.firstOrNull { p ->
                    p.isAlive(game.lifeVitalId) &&
                        (huntCap <= 0 || (hunters[p.id] ?: 0) < huntCap) &&
                        p.position.mapId == mapId &&
                        (
                            effectiveBehavior == NpcBehavior.ATTACK_ON_SIGHT ||
                                p.isRoguePk ||
                                p.pkPoints >= (game.definition.combat.pk?.redNameThreshold ?: Int.MAX_VALUE)
                            ) &&
                        p.position.tileCoord.manhattanDistanceTo(npc.tileCoord) <= npcDef.aggroRangeTiles
                }
                if (targetPlayer != null) {
                    npc = npc.copy(
                        targetEntityId = targetPlayer.id,
                        strikeReadyTick = if (npcRules.firstStrikeDelayTicks > 0) currentTick + npcRules.firstStrikeDelayTicks else npc.strikeReadyTick
                    )
                    hunters[targetPlayer.id] = (hunters[targetPlayer.id] ?: 0) + 1
                    npcList[idx] = npc // keep the target even if the monster neither moves nor strikes this tick
                }
            }
            // Systems may reconsider who the monster fights (a threat table follows the highest threat).
            val previousTarget = npc.targetEntityId
            val retargeted = retargetMonster(npc)
            if (retargeted != npc) {
                npc = retargeted
                npcList[idx] = npc
                if (npc.targetEntityId != previousTarget) targetPlayer = npc.targetEntityId?.let { players[it] }
            }
            // A TAUNT status forces the fight onto whoever applied it, whatever the monster's own target rules say.
            npc.statusEffects.firstOrNull { it.type == StatusEffectType.TAUNT }?.let { taunt ->
                val taunter = players[taunt.sourceEntityId]?.takeIf { it.isAlive(game.lifeVitalId) && it.position.mapId == mapId }
                if (taunter != null) {
                    val lifted = tauntedMonster(npc, taunter)
                    if (lifted != npc) {
                        npc = lifted
                        npcList[idx] = npc
                    }
                    targetPlayer = taunter
                    if (npc.targetEntityId != taunter.id) {
                        npc = npc.copy(targetEntityId = taunter.id)
                        npcList[idx] = npc
                    }
                }
            }
            if (targetPlayer == null && npc.castingSpellId != null) {
                npc = npc.copy(castingSpellId = null, castFinishTick = 0L)
                npcList[idx] = npc
            }

            // Leash check
            if (npc.tileCoord.manhattanDistanceTo(TileCoord(npc.homeX, npc.homeY)) > npcDef.leashRangeTiles) {
                npc = npc.copy(
                    tileX = npc.homeX,
                    tileY = npc.homeY,
                    targetEntityId = null,
                    threat = emptyMap(),
                    vitals = npc.vitals.mapValues { (_, v) -> v.copy(cur = v.max) },
                    phaseIndex = 0,
                    zone = null,
                    abilityReadyTicks = emptyMap()
                )
                npcList[idx] = npc
                out.add(WorldSimulationEvent.NpcUpdated(npc))
                continue
            }

            // Attack or Move toward target
            if (targetPlayer != null) {
                val dist = npc.tileCoord.manhattanDistanceTo(targetPlayer.position.tileCoord)

                // 1. NPC Spell Casting Check
                var castSpellThisTick = false
                val npcAttackSpell = npcDef.spellIds.mapNotNull { spells[it] }
                    .firstOrNull { !it.isHealing && it.targetType != SpellTargetType.SELF }
                // A spell being wound up: stand still until it lands (or fizzles if the target got out of range).
                var finishingSpell: com.morgana.domain.content.SpellDefinition? = null
                val windingUp = npc.castingSpellId?.let { spells[it] }
                if (npc.castingSpellId != null && windingUp == null) {
                    npc = npc.copy(castingSpellId = null, castFinishTick = 0L)
                    npcList[idx] = npc
                } else if (windingUp != null) {
                    if (currentTick < npc.castFinishTick) {
                        castSpellThisTick = true
                    } else if (dist <= windingUp.rangeTiles + 1) {
                        finishingSpell = windingUp
                    } else {
                        npc = npc.copy(castingSpellId = null, castFinishTick = 0L)
                        npcList[idx] = npc
                        out.add(
                            WorldSimulationEvent.FloatingTextEmitted(
                                mapId = mapId, tileX = npc.tileX, tileY = npc.tileY,
                                text = "${windingUp.name} fizzles", category = "CAST", colorHex = "#9CA3AF"
                            )
                        )
                        out.add(WorldSimulationEvent.NpcUpdated(npc))
                        castSpellThisTick = true
                    }
                }
                if (!castSpellThisTick && npcDef.spellIds.isNotEmpty() && currentTick >= npc.strikeReadyTick) {
                    val availableSpells = npcDef.spellIds.mapNotNull { spells[it] }
                    val lifeId = game.lifeVitalId
                    val curNpcLife = npc.vitals[lifeId]?.cur ?: 0
                    val maxNpcLife = npc.vitals[lifeId]?.max ?: (npcDef.vitals[lifeId] ?: 50)

                    val healSpell = availableSpells.firstOrNull { it.isHealing || it.targetType == SpellTargetType.SELF }
                    val attackSpell = availableSpells.firstOrNull { !it.isHealing && it.targetType != SpellTargetType.SELF }

                    val spellToCast = when {
                        finishingSpell != null -> finishingSpell
                        healSpell != null && curNpcLife < (maxNpcLife * 0.6).toInt() -> healSpell
                        attackSpell != null && dist <= attackSpell.rangeTiles && dist > 0 -> attackSpell
                        else -> null
                    }

                    if (spellToCast != null && (finishingSpell != null || npc.lastSpellCastTick == 0L || currentTick - npc.lastSpellCastTick >= spellToCast.cooldownTicks.coerceAtLeast(20))) {
                        val npcAttrs = game.definition.attributes.associate { a ->
                            a.id to (npcDef.attributes[a.id] ?: a.base)
                        }

                        if (spellToCast.isHealing || spellToCast.targetType == SpellTargetType.SELF) {
                            val healAmount = game.spellOutput(
                                caster = npcAttrs,
                                target = npcAttrs,
                                basePower = spellToCast.basePower.toLong(),
                                scales = spellToCast.scales,
                                heals = true
                            ).toInt().coerceAtLeast(1)
                            val newHp = (curNpcLife + healAmount).coerceAtMost(maxNpcLife)
                            npc = npc.copy(
                                vitals = npc.vitals + (lifeId to (npc.vitals[lifeId]?.copy(cur = newHp) ?: VitalState(newHp, maxNpcLife))),
                                lastSpellCastTick = currentTick
                            )
                            npcList[idx] = npc
                            out.add(
                                WorldSimulationEvent.FloatingTextEmitted(
                                    mapId = mapId,
                                    tileX = npc.tileX,
                                    tileY = npc.tileY,
                                    text = "+$healAmount",
                                    category = "HEAL",
                                    colorHex = "#22C55E"
                                )
                            )
                            if (spellToCast.animationId > 0) {
                                out.add(WorldSimulationEvent.AnimationTriggered(mapId, npc.tileX, npc.tileY, spellToCast.animationId, casterEntityId = npc.instanceId, spellId = spellToCast.id))
                            }
                            out.add(WorldSimulationEvent.SpellCast(npc.instanceId, spellToCast.id, mapId))
                            out.add(WorldSimulationEvent.NpcUpdated(npc))
                            castSpellThisTick = true
                        } else if (finishingSpell == null && (if (spellToCast.castTimeTicks > 0) spellToCast.castTimeTicks else npcRules.spellWindUpTicks) > 0) {
                            // Telegraph: the monster stops and winds the spell up; it lands later if the player stays in range.
                            val windUp = if (spellToCast.castTimeTicks > 0) spellToCast.castTimeTicks else npcRules.spellWindUpTicks
                            val windDir = Direction.fromDelta(
                                targetPlayer.position.tileX - npc.tileX,
                                targetPlayer.position.tileY - npc.tileY
                            ) ?: npc.direction
                            npc = npc.copy(
                                direction = windDir,
                                lastSpellCastTick = currentTick,
                                castingSpellId = spellToCast.id,
                                castFinishTick = currentTick + windUp
                            )
                            npcList[idx] = npc
                            out.add(
                                WorldSimulationEvent.FloatingTextEmitted(
                                    mapId = mapId, tileX = npc.tileX, tileY = npc.tileY,
                                    text = "Casting ${spellToCast.name}...", category = "CAST", colorHex = "#FBBF24"
                                )
                            )
                            out.add(WorldSimulationEvent.NpcUpdated(npc))
                            castSpellThisTick = true
                        } else {
                            val dir = Direction.fromDelta(
                                targetPlayer.position.tileX - npc.tileX,
                                targetPlayer.position.tileY - npc.tileY
                            ) ?: npc.direction
                            npc = npc.copy(direction = dir, lastSpellCastTick = currentTick, castingSpellId = null, castFinishTick = 0L)
                            npcList[idx] = npc

                            val playerAttrs = targetPlayer.effectiveAttributes(game, items)
                            val spellDmg = actionDefence(targetPlayer, npc.tileX, npc.tileY, game.spellOutput(
                                caster = npcAttrs,
                                target = playerAttrs,
                                basePower = spellToCast.basePower.toLong(),
                                scales = spellToCast.scales,
                                heals = false
                            ).toInt().coerceAtLeast(1), out)

                            if (spellToCast.targetType == SpellTargetType.DIRECTIONAL_PROJECTILE && modules.isEnabled(OptionalModule.PROJECTILES)) {
                                val proj = MapProjectile(
                                    projectileId = nextId("npcproj"),
                                    sourceEntityId = npc.instanceId,
                                    isFromPlayer = false,
                                    mapId = mapId,
                                    currentTileX = npc.tileX,
                                    currentTileY = npc.tileY,
                                    direction = dir,
                                    remainingTiles = spellToCast.rangeTiles,
                                    spriteId = spellToCast.projectileSpriteId,
                                    damage = spellDmg,
                                    ticksPerTile = 2,
                                    nextStepTick = currentTick + 2
                                )
                                val projList = mapProjectiles.getOrPut(mapId) { mutableListOf() }
                                projList.add(proj)
                                out.add(WorldSimulationEvent.ProjectilesChanged(mapId, projList.toList()))
                            } else {
                                val curPlayerLife = targetPlayer.vitals[lifeId]?.cur ?: 0
                                val newPlayerLife = (curPlayerLife - spellDmg).coerceAtLeast(0)
                                var updatedTarget = targetPlayer.copy(
                                    vitals = targetPlayer.vitals + (lifeId to (targetPlayer.vitals[lifeId]?.copy(cur = newPlayerLife) ?: VitalState(newPlayerLife, newPlayerLife)))
                                )
                                out.add(
                                    WorldSimulationEvent.FloatingTextEmitted(
                                        mapId = mapId,
                                        tileX = updatedTarget.position.tileX,
                                        tileY = updatedTarget.position.tileY,
                                        text = "-$spellDmg",
                                        category = "DAMAGE",
                                        colorHex = "#EF4444"
                                    )
                                )
                                if (spellToCast.statusEffectType != null && spellToCast.statusDurationTicks > 0) {
                                    val eff = ActiveStatusEffect(
                                        id = nextId("status"),
                                        type = spellToCast.statusEffectType,
                                        sourceEntityId = npc.instanceId,
                                        magnitude = spellToCast.statusMagnitude,
                                        remainingTicks = spellToCast.statusDurationTicks,
                                        tickInterval = 20
                                    )
                                    updatedTarget = updatedTarget.copy(statusEffects = updatedTarget.statusEffects + eff)
                                }
                                if (spellToCast.animationId > 0) {
                                    out.add(WorldSimulationEvent.AnimationTriggered(mapId, updatedTarget.position.tileX, updatedTarget.position.tileY, spellToCast.animationId, casterEntityId = npc.instanceId, spellId = spellToCast.id))
                                }
                                updatedTarget = afterStruck(updatedTarget, out)
                                if (!updatedTarget.isAlive(lifeId)) {
                                    updatedTarget = handlePlayerDeath(updatedTarget, map, out)
                                    npc = npc.copy(targetEntityId = null)
                                    npcList[idx] = npc
                                }
                                players[updatedTarget.id] = updatedTarget
                                out.add(WorldSimulationEvent.CharacterUpdated(updatedTarget))
                            }
                            out.add(WorldSimulationEvent.SpellCast(npc.instanceId, spellToCast.id, mapId))
                            out.add(WorldSimulationEvent.NpcUpdated(npc))
                            castSpellThisTick = true
                        }
                    }
                }

                if (!castSpellThisTick) {
                    if (dist == 1) {
                        if (currentTick - npc.lastAttackTick >= effectiveAttackSpeedTicks && currentTick >= npc.strikeReadyTick && !targetPlayer.isFlying) {
                            val dir = Direction.fromDelta(
                                targetPlayer.position.tileX - npc.tileX,
                                targetPlayer.position.tileY - npc.tileY
                            ) ?: npc.direction
                            npc = npc.copy(direction = dir, lastAttackTick = currentTick)
                            npcList[idx] = npc

                            val playerAttrs = targetPlayer.effectiveAttributes(game, items)
                            val playerArmor = armorOf(targetPlayer)
                            val npcAttrs = game.definition.attributes.associate { a ->
                                a.id to (npcDef.attributes[a.id] ?: a.base)
                            }
                            val dmg = actionDefence(targetPlayer, npc.tileX, npc.tileY, game.meleeDamage(
                                attacker = npcAttrs,
                                defender = playerAttrs,
                                weapon = effectiveAttackDamage.toLong(),
                                armor = playerArmor.toLong(),
                                crit = false
                            ).toInt().coerceAtLeast(1), out)
                            val lifeId = game.lifeVitalId
                            val curLife = targetPlayer.vitals[lifeId]?.cur ?: 0
                            val newLife = (curLife - dmg).coerceAtLeast(0)
                            var updatedTarget = targetPlayer.copy(
                                vitals = targetPlayer.vitals + (lifeId to (targetPlayer.vitals[lifeId]?.copy(cur = newLife) ?: VitalState(newLife, newLife)))
                            )
                            out.add(
                                WorldSimulationEvent.FloatingTextEmitted(
                                    mapId = mapId,
                                    tileX = updatedTarget.position.tileX,
                                    tileY = updatedTarget.position.tileY,
                                    text = "-$dmg",
                                    category = "DAMAGE",
                                    colorHex = "#EF4444"
                                )
                            )
                            updatedTarget = afterStruck(updatedTarget, out)
                            if (!updatedTarget.isAlive(lifeId)) {
                                updatedTarget = handlePlayerDeath(updatedTarget, map, out)
                                npc = npc.copy(targetEntityId = null)
                                npcList[idx] = npc
                            } else if (updatedTarget.reflectPercent > 0) {
                                npc = reflectOntoNpc(npc, npcDef, updatedTarget, dmg, out)
                                npcList[idx] = npc
                            }
                            players[updatedTarget.id] = updatedTarget
                            out.add(WorldSimulationEvent.CharacterUpdated(updatedTarget))
                        }
                    } else if (npcRules.casterKeepsDistance && npcAttackSpell != null && dist <= npcAttackSpell.rangeTiles) {
                        // A caster in spell range holds its ground while the spell recharges instead of closing to melee.
                    } else if (currentTick - npc.lastMoveTick >= effectiveMoveSpeedTicks && !npc.statusEffects.any { it.preventsMovement }) {
                        val dx = (targetPlayer.position.tileX - npc.tileX).coerceIn(-1, 1)
                        val dy = (targetPlayer.position.tileY - npc.tileY).coerceIn(-1, 1)
                        val stepDir = if (abs(targetPlayer.position.tileX - npc.tileX) >= abs(targetPlayer.position.tileY - npc.tileY)) {
                            Direction.fromDelta(dx, 0)
                        } else {
                            Direction.fromDelta(0, dy)
                        } ?: Direction.DOWN
                        val nextTile = npc.tileCoord.step(stepDir)
                        if (canNpcStepTo(map, npc.tileCoord, nextTile, stepDir)) {
                            npc = npc.copy(
                                tileX = nextTile.x,
                                tileY = nextTile.y,
                                direction = stepDir,
                                lastMoveTick = currentTick
                            )
                            npcList[idx] = npc
                            out.add(WorldSimulationEvent.NpcUpdated(npc))
                        }
                    }
                }
            } else if (
                effectiveBehavior in setOf(NpcBehavior.PASSIVE, NpcBehavior.ATTACK_ON_SIGHT, NpcBehavior.FRIENDLY_ROAM) &&
                currentTick - npc.lastMoveTick >= effectiveMoveSpeedTicks * 3L &&
                rng.rollBasisPoints(2000)
            ) {
                val dir = Direction.CARDINALS[rng.nextInt(Direction.CARDINALS.size)]
                val nextTile = npc.tileCoord.step(dir)
                if (
                    nextTile.manhattanDistanceTo(TileCoord(npc.homeX, npc.homeY)) <= 4 &&
                    canNpcStepTo(map, npc.tileCoord, nextTile, dir)
                ) {
                    npc = npc.copy(
                        tileX = nextTile.x,
                        tileY = nextTile.y,
                        direction = dir,
                        lastMoveTick = currentTick
                    )
                    npcList[idx] = npc
                    out.add(WorldSimulationEvent.NpcUpdated(npc))
                }
            }
        }
    }
}

internal fun WorldSimulation.canNpcStepTo(map: MapDefinition, from: TileCoord, to: TileCoord, dir: Direction): Boolean {
    val unlocked = unlockedDoors[map.id]?.keys ?: emptySet()
    val depleted = mapResources[map.id]?.filterValues { it.depleted }?.keys ?: emptySet()
    if (!map.canTraverse(from, to, dir, isNpc = true, unlockedDoors = unlocked, depletedResources = depleted)) {
        return false
    }
    val occupiedByPlayer = players.values.any {
        it.isAlive(game.lifeVitalId) && it.position.mapId == map.id && it.position.tileX == to.x && it.position.tileY == to.y
    }
    if (occupiedByPlayer) return false
    val occupiedByNpc = mapNpcs[map.id]?.any {
        it.isAlive && it.tileX == to.x && it.tileY == to.y
    } ?: false
    return !occupiedByNpc
}
