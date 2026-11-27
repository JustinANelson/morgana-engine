package com.morgana.domain.sim

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.TrainingFocusKind
import com.morgana.domain.item.EquipmentSlot
import kotlin.math.max
import kotlin.math.min

private const val MILLIS_PER_DAY = 86_400_000L

/** Flat damage multiplier percent for an action skill's mastery level (`100` = base). */
fun WorldSimulation.skillMasteryDamageMultiplierPercent(player: CharacterState, skillId: Int): Long {
    val cfg = game.definition.skillMastery ?: return 100L
    if (!cfg.enabled || skillId <= 0) return 100L
    val lv = player.skillMasteryLevel(skillId)
    return 100L + (lv - 1).toLong() * cfg.damagePercentPerLevel.toLong()
}

/**
 * Shared post-hit mastery hook for action combat: advances equipped weapon proficiency (+1)
 * and the used action skill's mastery (+1).
 */
internal fun WorldSimulation.onActionCombatHitLanded(
    characterId: String,
    actionSkillId: Int,
    out: MutableList<WorldSimulationEvent>
) {
    val player = players[characterId] ?: return
    val weaponDef = player.equipment.getActive(EquipmentSlot.WEAPON, game.definition.gearRules)?.let { items[it.itemId] }
    if (weaponDef != null && weaponDef.weaponType.isNotBlank()) {
        awardWeaponMasteryHits(characterId, weaponDef.weaponType, 1, out, announce = true)
    }
    if (actionSkillId > 0) {
        awardSkillMasteryHits(characterId, actionSkillId, 1, out, announce = true)
    }
}

/**
 * Advances weapon proficiency for [weaponType] by [hits] (supports single combat hits and bulk offline/AFK grants).
 * Returns `(startLevel, endLevel)`.
 */
internal fun WorldSimulation.awardWeaponMasteryHits(
    characterId: String,
    weaponType: String,
    hits: Int,
    out: MutableList<WorldSimulationEvent>,
    announce: Boolean = true
): Pair<Int, Int> {
    val player = players[characterId] ?: return 1 to 1
    val (updated, startLv, endLv) = applyWeaponMasteryHits(player, weaponType, hits, out, announce)
    if (updated !== player) {
        players[characterId] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
    return startLv to endLv
}

internal fun WorldSimulation.applyWeaponMasteryHits(
    player: CharacterState,
    weaponType: String,
    hits: Int,
    out: MutableList<WorldSimulationEvent>,
    announce: Boolean = true
): Triple<CharacterState, Int, Int> {
    val cfg = game.definition.weaponProficiency ?: return Triple(player, 1, 1)
    if (!cfg.enabled || weaponType.isBlank() || hits <= 0) return Triple(player, player.weaponProficiencyLevel(weaponType), player.weaponProficiencyLevel(weaponType))
    val startLv = player.weaponProficiencyLevel(weaponType)
    var curLv = startLv
    var curHits = player.weaponProficiencyHitsFor(weaponType)
    if (curLv >= cfg.maxLevel) return Triple(player, curLv, curLv)

    var remaining = hits
    while (remaining > 0 && curLv < cfg.maxLevel) {
        val needed = max(1, cfg.hitsPerLevel) * curLv
        val toNext = (needed - curHits).coerceAtLeast(1)
        if (remaining >= toNext) {
            remaining -= toNext
            curLv += 1
            curHits = 0
        } else {
            curHits += remaining
            remaining = 0
        }
    }
    if (curLv >= cfg.maxLevel) curHits = 0

    if (announce && curLv > startLv) {
        val typeName = cfg.weaponTypes.firstOrNull { it.id == weaponType }?.name
            ?: weaponType.replaceFirstChar { it.uppercase() }
        out.add(tellPlayer(player, "$typeName Mastery leveled up to Lv $curLv!", "GOOD"))
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = player.position.mapId,
                tileX = player.position.tileX,
                tileY = player.position.tileY,
                text = "$typeName Lv.$curLv",
                category = "LEVEL_UP",
                colorHex = "#38BDF8"
            )
        )
    }

    val updated = player.copy(
        weaponProficiencyLevels = player.weaponProficiencyLevels + (weaponType to curLv),
        weaponProficiencyHits = player.weaponProficiencyHits + (weaponType to curHits)
    )
    return Triple(updated, startLv, curLv)
}

/**
 * Advances skill mastery for [actionSkillId] by [hits] (supports single combat hits and bulk offline/AFK grants).
 * Returns `(startLevel, endLevel)`.
 */
internal fun WorldSimulation.awardSkillMasteryHits(
    characterId: String,
    actionSkillId: Int,
    hits: Int,
    out: MutableList<WorldSimulationEvent>,
    announce: Boolean = true
): Pair<Int, Int> {
    val player = players[characterId] ?: return 1 to 1
    val (updated, startLv, endLv) = applySkillMasteryHits(player, actionSkillId, hits, out, announce)
    if (updated !== player) {
        players[characterId] = updated
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
    return startLv to endLv
}

internal fun WorldSimulation.applySkillMasteryHits(
    player: CharacterState,
    actionSkillId: Int,
    hits: Int,
    out: MutableList<WorldSimulationEvent>,
    announce: Boolean = true
): Triple<CharacterState, Int, Int> {
    val cfg = game.definition.skillMastery ?: return Triple(player, 1, 1)
    if (!cfg.enabled || actionSkillId <= 0 || hits <= 0) return Triple(player, player.skillMasteryLevel(actionSkillId), player.skillMasteryLevel(actionSkillId))
    val startLv = player.skillMasteryLevel(actionSkillId)
    var curLv = startLv
    var curProgress = player.skillMasteryProgressFor(actionSkillId)
    if (curLv >= cfg.maxLevel) return Triple(player, curLv, curLv)

    var remaining = hits
    while (remaining > 0 && curLv < cfg.maxLevel) {
        val needed = max(1, cfg.usesPerLevel) * curLv
        val toNext = (needed - curProgress).coerceAtLeast(1)
        if (remaining >= toNext) {
            remaining -= toNext
            curLv += 1
            curProgress = 0
        } else {
            curProgress += remaining
            remaining = 0
        }
    }
    if (curLv >= cfg.maxLevel) curProgress = 0

    if (announce && curLv > startLv) {
        val skillName = actionSkills[actionSkillId]?.name ?: "Skill #$actionSkillId"
        out.add(tellPlayer(player, "$skillName Mastery leveled up to Lv $curLv!", "GOOD"))
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = player.position.mapId,
                tileX = player.position.tileX,
                tileY = player.position.tileY,
                text = "$skillName Lv.$curLv",
                category = "LEVEL_UP",
                colorHex = "#FBBF24"
            )
        )
    }

    val updated = player.copy(
        skillMasteryLevels = player.skillMasteryLevels + (actionSkillId to curLv),
        skillMasteryProgress = player.skillMasteryProgress + (actionSkillId to curProgress)
    )
    return Triple(updated, startLv, curLv)
}

/** Human-readable name of a training focus target (`WEAPON` or `SKILL`). */
internal fun WorldSimulation.trainingFocusDisplayName(focusKind: String, focusId: String): String = when (focusKind) {
    TrainingFocusKind.WEAPON -> {
        game.definition.weaponProficiency?.weaponTypes?.firstOrNull { it.id == focusId }?.name
            ?: focusId.replaceFirstChar { it.uppercase() }
    }
    TrainingFocusKind.SKILL -> {
        val sid = focusId.toIntOrNull() ?: 0
        actionSkills[sid]?.name ?: "Skill #$focusId"
    }
    else -> focusId
}

/** Sets the character's active training focus (`WEAPON` + weaponTypeId or `SKILL` + actionSkillId). */
fun WorldSimulation.setTrainingFocus(
    characterId: String,
    focusKind: String,
    focusId: String
): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val cfg = game.definition.training?.takeIf { it.enabled } ?: return out
    val player = players[characterId] ?: return out
    val kind = focusKind.trim().uppercase()
    val cleanId = focusId.trim()
    when (kind) {
        TrainingFocusKind.WEAPON -> {
            if (cleanId.isBlank()) return out
        }
        TrainingFocusKind.SKILL -> {
            val sid = cleanId.toIntOrNull()
            if (sid == null || sid !in actionSkills) {
                out.add(tellPlayer(player, "Unknown skill selected for training.", "WARNING"))
                return out
            }
        }
        else -> {
            out.add(tellPlayer(player, "Invalid training focus.", "WARNING"))
            return out
        }
    }
    val updated = player.copy(
        trainingState = player.trainingState.copy(
            focusKind = kind,
            focusId = cleanId
        )
    )
    players[characterId] = updated
    val label = trainingFocusDisplayName(kind, cleanId)
    out.add(tellPlayer(updated, "Training focus set to $label.", "GOOD"))
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
    return out
}

/**
 * Loads food from [slotIndex] into the character's offline training ration bar.
 * Enforces the single-tier food lock: only food matching the currently loaded tier may be added
 * until rations are cleared or exhausted. Food is consumed ONLY while offline.
 */
fun WorldSimulation.fuelOfflineTraining(
    characterId: String,
    slotIndex: Int,
    amount: Int = 1
): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    if (game.definition.training?.enabled != true) return out
    val player = players[characterId] ?: return out
    val requested = amount.coerceAtLeast(1)
    val stack = player.inventory.slots.getOrNull(slotIndex) ?: return out
    val item = items[stack.itemId] ?: return out
    if (item.trainingTier <= 0 || item.trainingMinutes <= 0 || item.trainingCapMinutes <= 0) {
        out.add(tellPlayer(player, "${item.name} cannot be used for offline training.", "WARNING"))
        return out
    }
    val st = player.trainingState
    if (st.foodTier != 0 && st.foodTier != item.trainingTier) {
        out.add(
            tellPlayer(
                player,
                "Rations are locked to Tier ${st.foodTier} food. Clear rations first to switch to Tier ${item.trainingTier}.",
                "WARNING"
            )
        )
        return out
    }
    val capSeconds = if (st.foodTier == 0) item.trainingCapMinutes * 60 else st.capSeconds
    val spaceSeconds = (capSeconds - st.fueledSeconds).coerceAtLeast(0)
    if (spaceSeconds <= 0) {
        out.add(tellPlayer(player, "Offline training rations are already at the Tier ${item.trainingTier} cap (${capSeconds / 60}m).", "INFO"))
        return out
    }
    val perItemSeconds = item.trainingMinutes * 60
    val maxUsefulUnits = ((spaceSeconds + perItemSeconds - 1) / perItemSeconds).coerceAtLeast(1)
    val unitsToUse = min(min(requested, stack.amount), maxUsefulUnits)
    if (unitsToUse <= 0) return out

    val nextSlots = player.inventory.slots.toMutableList()
    val remainingStack = stack.amount - unitsToUse
    nextSlots[slotIndex] = if (remainingStack > 0) stack.copy(amount = remainingStack) else null

    val addedSeconds = min(spaceSeconds, unitsToUse * perItemSeconds)
    val nextFueled = st.fueledSeconds + addedSeconds
    val updated = player.copy(
        inventory = player.inventory.copy(slots = nextSlots),
        trainingState = st.copy(
            foodTier = item.trainingTier,
            fueledSeconds = nextFueled,
            capSeconds = capSeconds
        )
    )
    players[characterId] = updated
    out.add(
        tellPlayer(
            updated,
            "Loaded ${unitsToUse}x ${item.name} (+${addedSeconds / 60}m). Offline rations: ${nextFueled / 60}m / ${capSeconds / 60}m (Tier ${item.trainingTier}).",
            "GOOD"
        )
    )
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
    return out
}

/** Clears all loaded offline training rations and unlocks the food tier so another tier can be loaded. */
fun WorldSimulation.clearOfflineTrainingFuel(characterId: String): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    if (game.definition.training?.enabled != true) return out
    val player = players[characterId] ?: return out
    val st = player.trainingState
    if (st.foodTier == 0 && st.fueledSeconds == 0) return out
    val updated = player.copy(
        trainingState = st.copy(foodTier = 0, fueledSeconds = 0, capSeconds = 0)
    )
    players[characterId] = updated
    out.add(tellPlayer(updated, "Offline training rations cleared. You may now load any food tier.", "INFO"))
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
    return out
}

/** Toggles online AFK training mode (uses daily budget without consuming offline food). */
fun WorldSimulation.toggleAfkTraining(characterId: String, active: Boolean): List<WorldSimulationEvent> {
    val out = mutableListOf<WorldSimulationEvent>()
    val cfg = game.definition.training?.takeIf { it.enabled } ?: return out
    val player = players[characterId] ?: return out
    val dayIndex = if (wallClockMillis > 0L) wallClockMillis / MILLIS_PER_DAY else player.trainingState.afkDayIndex
    var st = player.trainingState
    if (dayIndex > 0L && st.afkDayIndex != dayIndex) {
        st = st.copy(afkDayIndex = dayIndex, afkUsedSecondsToday = 0)
    }
    if (active) {
        if (st.focusKind.isBlank() || st.focusId.isBlank()) {
            out.add(tellPlayer(player, "Choose a weapon or skill to train before starting AFK mode.", "WARNING"))
            return out
        }
        val remaining = st.remainingAfkSeconds(cfg.onlineAfkDailyLimitMinutes, dayIndex)
        if (remaining <= 0) {
            out.add(tellPlayer(player, "Daily online AFK training limit (${cfg.onlineAfkDailyLimitMinutes}m) reached. Resets at 00:00 UTC.", "WARNING"))
            return out
        }
        val updated = player.copy(trainingState = st.copy(afkActive = true))
        players[characterId] = updated
        val focusName = trainingFocusDisplayName(st.focusKind, st.focusId)
        out.add(tellPlayer(updated, "Online AFK training started for $focusName (${remaining / 60}m ${remaining % 60}s left today).", "GOOD"))
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    } else {
        if (!st.afkActive) return out
        val updated = player.copy(trainingState = st.copy(afkActive = false))
        players[characterId] = updated
        out.add(tellPlayer(updated, "Online AFK training paused.", "INFO"))
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
    return out
}

/**
 * Resolves offline food-fueled training when [character] logs in.
 * Food is consumed ONLY for elapsed offline time; awards weapon or skill mastery hits and emits
 * [WorldSimulationEvent.OfflineTrainingCompleted] when progress was earned.
 */
internal fun WorldSimulation.resolveOfflineTrainingOnLogin(
    character: CharacterState,
    out: MutableList<WorldSimulationEvent>
): CharacterState {
    val cfg = game.definition.training?.takeIf { it.enabled } ?: return character
    val nowMs = wallClockMillis
    var st = character.trainingState.copy(afkActive = false)
    val dayIndex = if (nowMs > 0L) nowMs / MILLIS_PER_DAY else st.afkDayIndex
    if (dayIndex > 0L && st.afkDayIndex != dayIndex) {
        st = st.copy(afkDayIndex = dayIndex, afkUsedSecondsToday = 0)
    }
    if (nowMs <= 0L) return character.copy(trainingState = st)

    val lastMs = st.lastOnlineEpochMs
    if (lastMs <= 0L || nowMs <= lastMs || st.fueledSeconds <= 0 || st.focusKind.isBlank() || st.focusId.isBlank()) {
        return character.copy(trainingState = st.copy(lastOnlineEpochMs = nowMs))
    }

    val elapsedSeconds = ((nowMs - lastMs) / 1000L).coerceAtLeast(0L)
    val consumedSeconds = min(elapsedSeconds, st.fueledSeconds.toLong()).toInt()
    val trainedMinutes = consumedSeconds / 60
    if (trainedMinutes <= 0) {
        return character.copy(trainingState = st.copy(lastOnlineEpochMs = nowMs))
    }

    val actualConsumedSeconds = trainedMinutes * 60
    val remainingFuel = (st.fueledSeconds - actualConsumedSeconds).coerceAtLeast(0)
    val nextState = if (remainingFuel <= 0) {
        st.copy(foodTier = 0, fueledSeconds = 0, capSeconds = 0, lastOnlineEpochMs = nowMs)
    } else {
        st.copy(fueledSeconds = remainingFuel, lastOnlineEpochMs = nowMs)
    }

    val hits = trainedMinutes * cfg.offlineHitsPerMinute.coerceAtLeast(1)
    var updated = character.copy(trainingState = nextState)
    val (afterHits, startLv, endLv) = when (st.focusKind) {
        TrainingFocusKind.WEAPON -> applyWeaponMasteryHits(updated, st.focusId, hits, out, announce = false)
        TrainingFocusKind.SKILL -> applySkillMasteryHits(updated, st.focusId.toIntOrNull() ?: 0, hits, out, announce = false)
        else -> Triple(updated, 1, 1)
    }
    updated = afterHits

    val focusName = trainingFocusDisplayName(st.focusKind, st.focusId)
    out.add(
        WorldSimulationEvent.OfflineTrainingCompleted(
            characterId = updated.id,
            minutesTrained = trainedMinutes,
            focusKind = st.focusKind,
            focusId = st.focusId,
            focusName = focusName,
            hitsAwarded = hits,
            startLevel = startLv,
            endLevel = endLv,
            remainingFuelSeconds = remainingFuel
        )
    )
    return updated
}

/** Stamps `lastOnlineEpochMs` and clears `afkActive` when a character logs out. */
internal fun WorldSimulation.stampTrainingLogout(character: CharacterState): CharacterState {
    if (game.definition.training?.enabled != true) return character
    val nowMs = wallClockMillis
    val st = character.trainingState
    return character.copy(
        trainingState = st.copy(
            afkActive = false,
            lastOnlineEpochMs = if (nowMs > 0L) nowMs else st.lastOnlineEpochMs
        )
    )
}

/** Runs on every simulation tick: grants online AFK training progress without consuming offline food. */
internal fun WorldSimulation.tickOnlineAfkTraining(out: MutableList<WorldSimulationEvent>) {
    val cfg = game.definition.training?.takeIf { it.enabled } ?: return
    val interval = cfg.onlineAfkIntervalTicks.coerceAtLeast(20)
    if (currentTick == 0L || currentTick % interval.toLong() != 0L) return

    val intervalSeconds = max(1, interval / 20)
    val dayIndex = if (wallClockMillis > 0L) wallClockMillis / MILLIS_PER_DAY else 0L
    val hitsPerGrant = max(1, (cfg.onlineAfkHitsPerMinute * intervalSeconds) / 60)

    for (charId in players.keys.sorted()) {
        val player = players[charId] ?: continue
        var st = player.trainingState
        if (wallClockMillis > 0L && currentTick % 200L == 0L) {
            st = st.copy(lastOnlineEpochMs = wallClockMillis)
        }
        if (!st.afkActive || !player.isAlive) {
            if (st != player.trainingState) {
                players[charId] = player.copy(trainingState = st)
            }
            continue
        }
        if (dayIndex > 0L && st.afkDayIndex != dayIndex) {
            st = st.copy(afkDayIndex = dayIndex, afkUsedSecondsToday = 0)
        }
        val remaining = st.remainingAfkSeconds(cfg.onlineAfkDailyLimitMinutes, dayIndex)
        if (remaining <= 0 || st.focusKind.isBlank() || st.focusId.isBlank()) {
            val stopped = player.copy(trainingState = st.copy(afkActive = false))
            players[charId] = stopped
            out.add(tellPlayer(stopped, "Daily online AFK training limit (${cfg.onlineAfkDailyLimitMinutes}m) reached.", "INFO"))
            out.add(WorldSimulationEvent.CharacterUpdated(stopped))
            continue
        }
        val spent = min(intervalSeconds, remaining)
        val nextUsed = st.afkUsedSecondsToday + spent
        val exhausted = nextUsed >= cfg.onlineAfkDailyLimitMinutes * 60
        var updated = player.copy(
            trainingState = st.copy(
                afkUsedSecondsToday = nextUsed,
                afkActive = !exhausted,
                lastOnlineEpochMs = if (wallClockMillis > 0L) wallClockMillis else st.lastOnlineEpochMs
            )
        )
        val (afterHits, _, _) = when (st.focusKind) {
            TrainingFocusKind.WEAPON -> applyWeaponMasteryHits(updated, st.focusId, hitsPerGrant, out, announce = true)
            TrainingFocusKind.SKILL -> applySkillMasteryHits(updated, st.focusId.toIntOrNull() ?: 0, hitsPerGrant, out, announce = true)
            else -> Triple(updated, 1, 1)
        }
        updated = afterHits
        players[charId] = updated
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = updated.position.mapId,
                tileX = updated.position.tileX,
                tileY = updated.position.tileY,
                text = "+Mastery",
                category = "XP",
                colorHex = "#38BDF8"
            )
        )
        if (exhausted) {
            out.add(tellPlayer(updated, "Daily online AFK training limit (${cfg.onlineAfkDailyLimitMinutes}m) reached.", "INFO"))
        }
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
}
