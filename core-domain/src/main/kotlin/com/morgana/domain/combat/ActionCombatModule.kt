package com.morgana.domain.combat

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.InputTokens
import com.morgana.domain.content.SkillArmorType
import com.morgana.domain.content.SkillDamageKind
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.MapProjectile
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.applyDamageToNpc
import com.morgana.domain.sim.isHostileNpc
import com.morgana.domain.spatial.Direction
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState

/**
 * Action combat: the player fights with timed skills chained into combos instead of one attack button.
 *
 * What a character can do comes from the game's `weapon_sets` (the combo graph) and `action_skills` (timelines with hitboxes,
 * armour windows, dashes and guards). A key press arrives as an input token (`LMB`, `SHIFT+RMB`, `BACK+SPACE`, see
 * [InputTokens]) through `c_action` `input`; the plain attack counts as `LMB`. Everything else (monsters, spells, projectiles in
 * flight, regeneration, status effects) is the realtime module's, which this one wraps: monsters still fight as they do in any
 * game, and the player's moves are the skills.
 *
 * The skill's aim is fixed when it starts: the facing, or the cursor if the client sends `aimX` and `aimY` (world pixels).
 * Rules per tick (all integer ticks, one `StrictMath` cosine, the simulation's seeded random only): a hit-stop freezes the timeline,
 * hitboxes hit each target once per hit group, an edge is taken inside its window (an early press is buffered), `requiresHit`
 * edges need a connect, a guard holds until released or timed out, a dash moves the character with collision, an
 * `INVULNERABLE` window dodges every hit and a `SUPER_ARMOR` window shrugs off stun and root.
 *
 * Replays: the state lives in `WorldSimulation.actionStates` and is part of the state hash.
 */
class ActionCombatModule(private val base: CombatModule = RealtimeCombatModule()) : CombatModule {
    override val id: String get() = ID

    override fun tickProjectiles(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) = base.tickProjectiles(sim, out)

    override fun tickPlayers(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        base.tickPlayers(sim, out)
        for (charId in sim.actionStates.keys.toList()) step(sim, charId, out)
    }

    override fun onPlayerAttack(sim: WorldSimulation, characterId: String): List<WorldSimulationEvent> = input(sim, characterId, "LMB", null)

    override fun onPlayerCastSpell(
        sim: WorldSimulation,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int
    ): List<WorldSimulationEvent> = base.onPlayerCastSpell(sim, characterId, spellId, targetEntityId, targetTileX, targetTileY)

    override fun handleAction(sim: WorldSimulation, characterId: String, actionId: String, params: Map<String, String>): CombatActionResult =
        when (actionId.lowercase()) {
            "input" -> {
                val token = params["token"]?.let { InputTokens.canonical(it) }
                val aimX = params["aimX"]?.toDoubleOrNull()
                val aimY = params["aimY"]?.toDoubleOrNull()
                CombatActionResult(true, if (token == null) emptyList() else input(sim, characterId, token, if (aimX != null && aimY != null) aimX to aimY else null))
            }
            "release" -> {
                release(sim, characterId)
                CombatActionResult(true)
            }
            "attack" -> CombatActionResult(true, onPlayerAttack(sim, characterId))
            else -> base.handleAction(sim, characterId, actionId, params)
        }

    // ---- input ----

    private fun input(sim: WorldSimulation, charId: String, token: String, aim: Pair<Double, Double>?): List<WorldSimulationEvent> {
        val player = sim.players[charId] ?: return emptyList()
        if (!player.isAlive(sim.game.lifeVitalId) || player.isGhost || player.statusEffects.any { it.preventsActions }) return emptyList()
        val out = mutableListOf<WorldSimulationEvent>()
        val state = sim.actionStates[charId] ?: ActionState()
        val next = handleInput(sim, player, state, token, aim, fromBuffer = false, out)
        if (next != null) sim.actionStates[charId] = next
        return out
    }

    private fun release(sim: WorldSimulation, charId: String) {
        val st = sim.actionStates[charId] ?: return
        if (st.holdUntil > sim.currentTick) sim.actionStates[charId] = st.copy(holdUntil = sim.currentTick)
    }

    /** Acts on [token]; returns the new state when a skill started or the press was buffered, null when nothing changed. */
    private fun handleInput(
        sim: WorldSimulation, player: CharacterState, st: ActionState, token: String, aim: Pair<Double, Double>?,
        fromBuffer: Boolean, out: MutableList<WorldSimulationEvent>
    ): ActionState? {
        val now = sim.currentTick
        var set: WeaponSetDefinition? = sim.weaponSets[st.setId]?.takeIf { it.slot == com.morgana.domain.content.WeaponSetSlot.MAIN && it.levelReq <= player.level && (it.classIds.isEmpty() || player.classId in it.classIds) }
        var state = st
        if (set == null) {
            set = ActionCombatRules.chooseSet(sim.weaponSets, player.classId, player.level) ?: return null
            state = state.copy(setId = set.id)
        }
        if (state.skillId != 0) {
            val skill = sim.actionSkills[state.skillId]
            if (skill != null) {
                val t = (now - state.start).toInt()
                val cancel = ActionCombatRules.cancelTick(skill)
                val edge = ActionCombatRules.findEdge(set, state.skillId, token, state.hit)
                if (edge != null && t >= cancel && t <= cancel + edge.windowTicks) return startSkill(sim, player, state, edge.toSkillId, aim, out)
                val any = ActionCombatRules.findEdge(set, state.skillId, token, null)
                if (any != null && t < cancel && cancel - t <= set.inputBufferTicks && !fromBuffer) {
                    return state.copy(bufferedToken = token, bufferedAt = now)
                }
                if (t < ActionCombatRules.skillLength(skill)) return if (state != st) state else null
            }
        }
        val entry = ActionCombatRules.findEntry(set, token)
        if (entry != null) return startSkill(sim, player, state, entry.skillId, aim, out)
        return if (state != st) state else null
    }

    private fun startSkill(
        sim: WorldSimulation, player: CharacterState, st: ActionState, skillId: Int, aim: Pair<Double, Double>?, out: MutableList<WorldSimulationEvent>
    ): ActionState? {
        val skill = sim.actionSkills[skillId] ?: return null
        val now = sim.currentTick
        if (skill.levelReq > player.level) return null
        if (now < (st.cooldowns[skillId] ?: 0L)) return null
        for ((vital, amount) in skill.costs) {
            if ((player.vitals[vital]?.cur ?: 0) < amount) {
                out.add(notice(player, "Not enough $vital"))
                return null
            }
        }
        var updated = player
        for (vital in skill.costs.keys.sorted()) {
            val v = updated.vitals[vital] ?: continue
            updated = updated.copy(vitals = updated.vitals + (vital to v.copy(cur = (v.cur - skill.costs.getValue(vital)).coerceAtLeast(0))))
        }
        val tileSize = sim.maps[player.position.mapId]?.tileSize ?: 32
        val cx = player.position.pixelX + tileSize / 2.0
        val cy = player.position.pixelY + tileSize / 2.0
        val vector = aim?.let { ActionCombatRules.aimVector(cx, cy, it.first, it.second) } ?: ActionCombatRules.facingVector(player.position.direction)
        val facing = ActionCombatRules.nearestDirection(vector.first, vector.second)
        if (facing != updated.position.direction || updated != player) {
            updated = updated.copy(position = updated.position.copy(direction = facing))
            sim.players[player.id] = updated
            out.add(WorldSimulationEvent.CharacterUpdated(updated))
        }
        if (skill.animationId > 0) out.add(WorldSimulationEvent.AnimationTriggered(updated.position.mapId, updated.position.tileX, updated.position.tileY, skill.animationId))
        val guard = skill.guard
        return st.copy(
            skillId = skillId, start = now, done = -1, hit = false, hits = emptySet(), groupCounts = emptyMap(), freeze = 0,
            fx = vector.first, fy = vector.second, bufferedToken = null, bufferedAt = 0L, movedTo = -1,
            holdUntil = if (guard != null && guard.maxHoldTicks > 0) now + skill.startupTicks + guard.maxHoldTicks else 0L,
            cooldowns = if (skill.cooldownTicks > 0) st.cooldowns + (skillId to now + skill.cooldownTicks) else st.cooldowns
        )
    }

    // ---- the tick ----

    private fun step(sim: WorldSimulation, charId: String, out: MutableList<WorldSimulationEvent>) {
        val now = sim.currentTick
        val player = sim.players[charId]
        val stored = sim.actionStates[charId]
        if (player == null || stored == null) { sim.actionStates.remove(charId); return }
        var st: ActionState = stored
        if (!player.isAlive(sim.game.lifeVitalId) || player.isGhost) { sim.actionStates.remove(charId); return }
        val skill = sim.actionSkills[st.skillId]
        if (st.skillId != 0 && skill == null) st = st.copy(skillId = 0)
        if (skill == null || st.skillId == 0) { finish(sim, charId, st); return }

        var current = player
        val t0 = (now - st.start).toInt()
        // A stun or root ends the skill, unless a super-armour window is shrugging it off.
        if (current.statusEffects.any { it.type == StatusEffectType.STUN || it.type == StatusEffectType.ROOT }) {
            if (ActionCombatRules.hasArmor(skill, t0, SkillArmorType.SUPER_ARMOR) || ActionCombatRules.hasArmor(skill, t0, SkillArmorType.INVULNERABLE)) {
                current = current.copy(statusEffects = current.statusEffects.filterNot { it.type == StatusEffectType.STUN || it.type == StatusEffectType.ROOT })
                sim.players[charId] = current
                out.add(WorldSimulationEvent.CharacterUpdated(current))
            } else {
                sim.actionStates[charId] = st.copy(skillId = 0, bufferedToken = null, holdUntil = 0L)
                return
            }
        }

        if (st.freeze > 0) {
            sim.actionStates[charId] = st.copy(freeze = st.freeze - 1, start = st.start + 1)
            return
        }
        // A held guard keeps its timeline at the end of the start-up while the key is down (until released or timed out); the guard is up the whole time.
        val holding = skill.guard != null && st.holdUntil > now && t0 >= skill.startupTicks
        if (holding) {
            st = st.copy(start = now - skill.startupTicks) // the timeline waits at the end of the start-up
        }
        val length = ActionCombatRules.skillLength(skill)
        val t = (now - st.start).toInt()
        val last = minOf(t, length)
        for (u in st.done + 1..last) st = evalHitboxes(sim, current, st, skill, u, out)
        st = st.copy(done = maxOf(st.done, last))
        applyMoves(sim, charId, st, skill, last, out)
        st = st.copy(movedTo = maxOf(st.movedTo, last))

        val buffered = st.bufferedToken
        if (buffered != null) {
            val set = sim.weaponSets[st.setId]
            if (set == null || now - st.bufferedAt > set.inputBufferTicks) {
                st = st.copy(bufferedToken = null, bufferedAt = 0L)
            } else if (t >= ActionCombatRules.cancelTick(skill)) {
                st = st.copy(bufferedToken = null, bufferedAt = 0L)
                val live = sim.players[charId] ?: current
                st = handleInput(sim, live, st, buffered, null, fromBuffer = true, out) ?: st
            }
        }
        if (st.skillId == skill.id && t > length + ActionCombatRules.COMBO_GRACE) st = st.copy(skillId = 0, hit = false)
        finish(sim, charId, st)
    }

    /** Stores [st], or forgets the character once nothing is left to remember. */
    private fun finish(sim: WorldSimulation, charId: String, st: ActionState) {
        val now = sim.currentTick
        val cooldowns = st.cooldowns.filterValues { it > now }
        if (st.skillId == 0 && cooldowns.isEmpty()) sim.actionStates.remove(charId)
        else sim.actionStates[charId] = st.copy(cooldowns = cooldowns)
    }

    // ---- hitboxes ----

    private fun evalHitboxes(sim: WorldSimulation, player: CharacterState, state: ActionState, skill: ActionSkillDefinition, u: Int, out: MutableList<WorldSimulationEvent>): ActionState {
        var st = state
        val map = sim.maps[player.position.mapId] ?: return st
        val ts = map.tileSize
        val cx = player.position.pixelX + ts / 2.0
        val cy = player.position.pixelY + ts / 2.0
        var bestStop = 0
        skill.hitboxes.forEachIndexed { index, hb ->
            if (u < hb.fromTick || u > hb.toTick) return@forEachIndexed
            val projectile = hb.projectile
            if (projectile != null) {
                val key = "p$index"
                if (key !in st.hits && u == hb.fromTick) {
                    spawnProjectile(sim, player, st, hb, out)
                    st = st.copy(hits = st.hits + key)
                }
                return@forEachIndexed
            }
            val reach = ActionCombatRules.reachTiles(hb, ts)
            val candidates = (sim.mapNpcs[player.position.mapId] ?: emptyList<MapNpcInstance>()).filter {
                it.isAlive && sim.isHostileNpc(it) && StrictMath.abs(it.tileX - player.position.tileX) <= reach && StrictMath.abs(it.tileY - player.position.tileY) <= reach
            }
            for (npc in candidates) {
                val key = "${hb.hitGroup}:${npc.instanceId}"
                val count = st.groupCounts[hb.hitGroup] ?: 0
                if (key in st.hits || (hb.maxTargets > 0 && count >= hb.maxTargets)) continue
                if (!ActionCombatRules.inShape(hb, cx, cy, st.fx, st.fy, npc.tileX * ts + ts / 2.0, npc.tileY * ts + ts / 2.0)) continue
                st = st.copy(hits = st.hits + key, groupCounts = st.groupCounts + (hb.hitGroup to count + 1), hit = true)
                hitNpc(sim, sim.players[player.id] ?: player, npc, hb.hit, st, out)
                bestStop = maxOf(bestStop, hb.hit.hitStopTicks)
            }
        }
        return if (bestStop > 0) st.copy(freeze = st.freeze + bestStop) else st
    }

    private fun weaponDamage(sim: WorldSimulation, player: CharacterState): Long {
        val weapon = player.equipment.getActive(EquipmentSlot.WEAPON)?.let { sim.items[it.itemId] }
        return ((weapon?.attackDamage ?: 2) + player.equipment.rolledAttackDamage(sim.items) + com.morgana.domain.game.BuildBonuses.attackDamageBonus(player, sim.game.definition)).toLong()
    }

    private fun damageOf(sim: WorldSimulation, player: CharacterState, npc: MapNpcInstance?, hit: SkillHit): Pair<Int, Boolean> {
        if (hit.damagePercent <= 0) return 0 to false
        val attrs = player.effectiveAttributes(sim.game, sim.items)
        val def = npc?.let { sim.npcs[it.npcId] }
        val defender = sim.game.definition.attributes.associate { a -> a.id to (def?.attributes?.get(a.id) ?: a.base) }
        val critBp = (sim.game.critChanceBp(attrs).toInt() + player.equipment.totalCritBonusBp(sim.items) + com.morgana.domain.game.BuildBonuses.critChanceBpBonus(player, sim.game.definition)).coerceIn(0, 10_000)
        val crit = sim.rng.rollBasisPoints(critBp)
        val weapon = weaponDamage(sim, player)
        val raw = when (hit.damageKind) {
            SkillDamageKind.MELEE -> sim.game.meleeDamage(attrs, defender, weapon, 0L, crit)
            SkillDamageKind.RANGED -> sim.game.rangedDamage(attrs, defender, weapon, 0L, 0L, crit)
            SkillDamageKind.SPELL -> sim.game.spellOutput(attrs, defender, weapon, true, false)
        }
        return (raw * hit.damagePercent / 100L).toInt().coerceAtLeast(1) to crit
    }

    private fun hitNpc(sim: WorldSimulation, player: CharacterState, npc: MapNpcInstance, hit: SkillHit, st: ActionState, out: MutableList<WorldSimulationEvent>) {
        val (damage, crit) = damageOf(sim, player, npc, hit)
        if (damage > 0) sim.applyDamageToNpc(player, npc, damage, crit, out)
        val list = sim.mapNpcs[npc.mapId] ?: return
        val idx = list.indexOfFirst { it.instanceId == npc.instanceId }
        if (idx < 0) return
        var live = list[idx]
        if (!live.isAlive) return
        val effects = ArrayList<ActiveStatusEffect>()
        if (hit.reaction != HitReaction.NONE && hit.reactionTicks > 0) {
            effects += ActiveStatusEffect("action-react", StatusEffectType.STUN, player.id, 0, emptyMap(), hit.reactionTicks)
        }
        val status = hit.statusEffectType
        if (status != null && hit.statusDurationTicks > 0) {
            effects += ActiveStatusEffect("action-status-${status.name}", status, player.id, hit.statusMagnitude, emptyMap(), hit.statusDurationTicks)
        }
        if (effects.isNotEmpty()) live = live.copy(statusEffects = live.statusEffects.filterNot { e -> effects.any { it.id == e.id } } + effects)
        if (hit.knockbackPixels > 0) live = knockedBack(sim, live, st, hit.knockbackPixels)
        if (live != list[idx]) {
            list[idx] = live
            out.add(WorldSimulationEvent.NpcUpdated(live))
        }
    }

    /** Pushes a monster away along the skill's aim, one tile per 32 px (at least one), stopping at a wall or another body. */
    private fun knockedBack(sim: WorldSimulation, npc: MapNpcInstance, st: ActionState, pixels: Int): MapNpcInstance {
        val map = sim.maps[npc.mapId] ?: return npc
        val dir = ActionCombatRules.nearestDirection(st.fx, st.fy)
        var moved = npc
        repeat(maxOf(1, pixels / map.tileSize)) {
            val to = com.morgana.domain.spatial.TileCoord(moved.tileX + dir.dx, moved.tileY + dir.dy)
            val occupied = (sim.mapNpcs[npc.mapId] ?: emptyList<MapNpcInstance>()).any { it.isAlive && it.instanceId != moved.instanceId && it.tileX == to.x && it.tileY == to.y } ||
                sim.players.values.any { it.position.mapId == npc.mapId && it.position.tileX == to.x && it.position.tileY == to.y }
            if (!occupied && map.isInBounds(to) && map.canTraverse(com.morgana.domain.spatial.TileCoord(moved.tileX, moved.tileY), to, dir, isNpc = true)) {
                moved = moved.copy(tileX = to.x, tileY = to.y)
            } else return moved
        }
        return moved
    }

    private fun spawnProjectile(sim: WorldSimulation, player: CharacterState, st: ActionState, hb: SkillHitbox, out: MutableList<WorldSimulationEvent>) {
        if (!sim.modules.isEnabled(OptionalModule.PROJECTILES)) return
        val p = hb.projectile ?: return
        val dir = ActionCombatRules.nearestDirection(st.fx, st.fy)
        val (damage, _) = damageOf(sim, player, null, hb.hit)
        val tiles = maxOf(2, p.rangePixels / 32)
        val proj = MapProjectile(
            projectileId = sim.nextId("aproj"), sourceEntityId = player.id, isFromPlayer = true, mapId = player.position.mapId,
            currentTileX = player.position.tileX, currentTileY = player.position.tileY, direction = dir, remainingTiles = tiles,
            spriteId = maxOf(1, p.spriteId), damage = maxOf(1, damage), ticksPerTile = maxOf(1, 32 / maxOf(1, p.speedPixelsPerTick)),
            nextStepTick = sim.currentTick + 1L, pierce = p.pierce
        )
        val list = sim.mapProjectiles.getOrPut(player.position.mapId) { mutableListOf() }
        list.add(proj)
        out.add(WorldSimulationEvent.ProjectilesChanged(player.position.mapId, list.toList()))
    }

    // ---- movement ----

    /** Spreads each move window's displacement evenly over its ticks and applies the ticks that passed since the last call. */
    private fun applyMoves(sim: WorldSimulation, charId: String, st: ActionState, skill: ActionSkillDefinition, last: Int, out: MutableList<WorldSimulationEvent>) {
        if (skill.moves.isEmpty()) return
        var dx = 0.0
        var dy = 0.0
        for (m in skill.moves) {
            val from = maxOf(m.fromTick, st.movedTo + 1)
            val to = minOf(m.toTick, last)
            if (to < from) continue
            val span = (m.toTick - m.fromTick + 1).toDouble()
            val ticks = (to - from + 1).toDouble()
            val forward = m.forwardPixels * ticks / span
            val side = m.sidePixels * ticks / span
            dx += st.fx * forward + (-st.fy) * side
            dy += st.fy * forward + st.fx * side
        }
        if (dx == 0.0 && dy == 0.0) return
        val player = sim.players[charId] ?: return
        val map = sim.maps[player.position.mapId] ?: return
        val half = map.tileSize / 2.0
        val newX = player.position.pixelX + dx.toFloat()
        val newY = player.position.pixelY + dy.toFloat()
        val toTile = com.morgana.domain.spatial.TileCoord(((newX + half) / map.tileSize).toInt().coerceAtLeast(0), ((newY + half) / map.tileSize).toInt().coerceAtLeast(0))
        val from = player.position.tileCoord
        if (toTile != from) {
            val dir = ActionCombatRules.nearestDirection(dx, dy)
            val blockedByBody = (sim.mapNpcs[player.position.mapId] ?: emptyList<MapNpcInstance>()).any { it.isAlive && it.tileX == toTile.x && it.tileY == toTile.y }
            if (blockedByBody || !map.isInBounds(toTile) || !map.canTraverse(from, toTile, dir, isNpc = false)) return
        }
        val moved = player.copy(position = player.position.withPixels(newX, newY, map.tileSize))
        sim.players[charId] = moved
        out.add(WorldSimulationEvent.PlayerMoved(moved, map.id, map.id))
        out.add(WorldSimulationEvent.CharacterUpdated(moved))
    }

    // ---- defence (called by the engine when a monster or a boss zone damages a player) ----

    /**
     * What a hit of [damage] from ([sourceX], [sourceY]) in tile coordinates does to [player]: nothing while an `INVULNERABLE`
     * window is open, less while a guard faces it (the guard pays its block cost per hit and breaks when it cannot), else all of it.
     */
    fun incomingDamage(sim: WorldSimulation, player: CharacterState, sourceTileX: Int, sourceTileY: Int, damage: Int, out: MutableList<WorldSimulationEvent>): Int {
        val st = sim.actionStates[player.id] ?: return damage
        val skill = sim.actionSkills[st.skillId] ?: return damage
        val t = (sim.currentTick - st.start).toInt()
        if (ActionCombatRules.hasArmor(skill, t, SkillArmorType.INVULNERABLE)) {
            out.add(WorldSimulationEvent.FloatingTextEmitted(player.position.mapId, player.position.tileX, player.position.tileY, "Dodge!", "MISS_DODGE", "#38BDF8"))
            return 0
        }
        val guard = skill.guard ?: return damage
        val active = t >= skill.startupTicks && (t < skill.startupTicks + skill.activeTicks || st.holdUntil > sim.currentTick)
        if (!active) return damage
        val ts = sim.maps[player.position.mapId]?.tileSize ?: 32
        val inArc = ActionCombatRules.inGuardArc(
            guard.arcDegrees, player.position.pixelX + ts / 2.0, player.position.pixelY + ts / 2.0, st.fx, st.fy, sourceTileX * ts + ts / 2.0, sourceTileY * ts + ts / 2.0
        )
        if (!inArc) return damage
        var paid = player
        for ((vital, amount) in guard.blockCosts) {
            val v = paid.vitals[vital]
            if (v == null || v.cur < amount) {
                sim.actionStates[player.id] = st.copy(skillId = 0, holdUntil = 0L)
                out.add(WorldSimulationEvent.FloatingTextEmitted(player.position.mapId, player.position.tileX, player.position.tileY, "Guard broken!", "MISS_DODGE", "#F97316"))
                return damage
            }
            paid = paid.copy(vitals = paid.vitals + (vital to VitalState(v.cur - amount, v.max)))
        }
        if (paid !== player) {
            sim.players[player.id] = paid
            out.add(WorldSimulationEvent.CharacterUpdated(paid))
        }
        out.add(WorldSimulationEvent.FloatingTextEmitted(player.position.mapId, player.position.tileX, player.position.tileY, "Blocked", "MISS_DODGE", "#94A3B8"))
        return (damage.toLong() * (100 - guard.damageReductionPercent) / 100).toInt()
    }

    private fun notice(player: CharacterState, text: String) =
        WorldSimulationEvent.FloatingTextEmitted(player.position.mapId, player.position.tileX, player.position.tileY, text, "MISS_DODGE", "#94A3B8")

    companion object {
        const val ID = "action"
    }
}

/**
 * The damage a player takes from a monster, a boss zone or a monster's projectile after action-combat defence: an `INVULNERABLE`
 * window takes none, a guard takes less. Other combat modules leave [damage] alone.
 */
internal fun WorldSimulation.actionDefence(player: CharacterState, sourceTileX: Int, sourceTileY: Int, damage: Int, out: MutableList<WorldSimulationEvent>): Int =
    (combat as? ActionCombatModule)?.incomingDamage(this, player, sourceTileX, sourceTileY, damage, out) ?: damage
