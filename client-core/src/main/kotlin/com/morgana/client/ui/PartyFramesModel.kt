package com.morgana.client.ui

import com.morgana.domain.entity.PartyState
import com.morgana.protocol.PublicPlayerSnapshot

/**
 * Pure presentation model for the always-visible party frames: one compact row per party member other than the
 * local player, with a life bar when that member is on the same map. Members on other maps are listed as away.
 */
object PartyFramesModel {

    data class Frame(
        val characterId: String,
        val name: String,
        val level: Int,
        val curHp: Int,
        val maxHp: Int,
        val isLeader: Boolean,
        /** True when the member is on this map, so [curHp] and [maxHp] are live. */
        val nearby: Boolean,
        val ghost: Boolean,
        val spriteId: Int = 1,
        val curMp: Int = 0,
        val maxMp: Int = 0
    ) {
        val fraction: Float
            get() = if (!nearby || maxHp <= 0) 0f else (curHp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)

        val mpFraction: Float
            get() = if (!nearby || maxMp <= 0) 0f else (curMp.toFloat() / maxMp.toFloat()).coerceIn(0f, 1f)
    }

    /** Frames never exceed this many rows, so an oversized party cannot flood the screen. */
    const val MAX_FRAMES = 8

    fun frames(
        party: PartyState?,
        localCharacterId: String?,
        playersOnMap: Map<String, PublicPlayerSnapshot>,
        lifeVitalId: String = "hp",
        namesById: Map<String, String> = emptyMap(),
        manaVitalId: String = "mp"
    ): List<Frame> {
        if (party == null) return emptyList()
        return party.memberCharacterIds
            .filter { it != localCharacterId }
            .take(MAX_FRAMES)
            .map { id ->
                val snap = playersOnMap[id]
                val life = snap?.vitals?.get(lifeVitalId)
                val mana = snap?.vitals?.get(manaVitalId)
                Frame(
                    characterId = id,
                    name = snap?.name ?: namesById[id] ?: id,
                    level = snap?.level ?: 0,
                    curHp = life?.cur ?: 0,
                    maxHp = life?.max ?: 0,
                    isLeader = id == party.leaderCharacterId,
                    nearby = snap != null,
                    ghost = snap?.isGhost == true,
                    spriteId = snap?.spriteId ?: 1,
                    curMp = mana?.cur ?: 0,
                    maxMp = mana?.max ?: 0
                )
            }
    }
}
