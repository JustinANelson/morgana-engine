package com.morgana.domain.systems

import com.morgana.domain.sim.tileDistanceAcross
import com.morgana.domain.sim.PlayerActivity
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.actionBlocked
import com.morgana.domain.sim.tellPlayer

import com.morgana.domain.entity.TradeSessionState

/**
 * Two-stage player-to-player trading.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

internal fun WorldSimulation.initiateTradeImpl(initiatorId: String, partnerId: String): List<WorldSimulationEvent> {
    val p1 = players[initiatorId] ?: return emptyList()
    val p2 = players[partnerId] ?: return emptyList()
    // Across a map border in a seamless world they only have to be near each other.
    if (p1.position.mapId != p2.position.mapId && tileDistanceAcross(p1, p2) == null) return emptyList()
    if (p1.isGhost || p2.isGhost || actionBlocked(initiatorId, PlayerActivity.TRADE) || actionBlocked(partnerId, PlayerActivity.TRADE)) return emptyList()

    val session = TradeSessionState(
        sessionId = nextId("trade"),
        initiatorId = initiatorId,
        partnerId = partnerId
    )
    tradeSessions[session.sessionId] = session
    return listOf(WorldSimulationEvent.TradeUpdated(listOf(initiatorId, partnerId), session))
}

internal fun WorldSimulation.addTradeOfferItemImpl(characterId: String, inventorySlotIndex: Int, amount: Int): List<WorldSimulationEvent> {
    val session = tradeSessions.values.firstOrNull {
        it.initiatorId == characterId || it.partnerId == characterId
    } ?: return emptyList()
    if (session.initiatorLocked && session.partnerLocked) return emptyList()

    val player = players[characterId] ?: return emptyList()
    val stack = player.inventory.getSlot(inventorySlotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    if (itemDef.boundOnPickup) return emptyList()

    val actualQty = amount.coerceIn(1, stack.amount)
    val (afterInv, offeredStack) = player.inventory.removeFromSlot(inventorySlotIndex, actualQty) ?: return emptyList()
    val updatedPlayer = player.copy(inventory = afterInv)
    players[characterId] = updatedPlayer

    val updatedSession = if (session.initiatorId == characterId) {
        session.copy(
            initiatorOffer = session.initiatorOffer + offeredStack,
            initiatorLocked = false,
            partnerLocked = false,
            initiatorConfirmed = false,
            partnerConfirmed = false
        )
    } else {
        session.copy(
            partnerOffer = session.partnerOffer + offeredStack,
            initiatorLocked = false,
            partnerLocked = false,
            initiatorConfirmed = false,
            partnerConfirmed = false
        )
    }
    tradeSessions[session.sessionId] = updatedSession
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updatedPlayer),
        WorldSimulationEvent.TradeUpdated(listOf(session.initiatorId, session.partnerId), updatedSession)
    )
}

internal fun WorldSimulation.lockAndConfirmTradeImpl(characterId: String, confirmStage: Boolean): List<WorldSimulationEvent> {
    val session = tradeSessions.values.firstOrNull {
        it.initiatorId == characterId || it.partnerId == characterId
    } ?: return emptyList()

    var updated = if (session.initiatorId == characterId) {
        session.copy(
            initiatorLocked = true,
            initiatorConfirmed = if (session.initiatorLocked && session.partnerLocked) confirmStage else false
        )
    } else {
        session.copy(
            partnerLocked = true,
            partnerConfirmed = if (session.initiatorLocked && session.partnerLocked) confirmStage else false
        )
    }

    val out = mutableListOf<WorldSimulationEvent>()
    if (updated.initiatorConfirmed && updated.partnerConfirmed) {
        val p1 = players[updated.initiatorId]
        val p2 = players[updated.partnerId]
        if (p1 != null && p2 != null) {
            var p1Inv = p1.inventory
            var p2Inv = p2.inventory
            for (stack in updated.partnerOffer) {
                val def = items[stack.itemId] ?: continue
                p1Inv = p1Inv.addStack(def, stack).updatedInventory
            }
            for (stack in updated.initiatorOffer) {
                val def = items[stack.itemId] ?: continue
                p2Inv = p2Inv.addStack(def, stack).updatedInventory
            }
            val nextP1 = p1.copy(inventory = p1Inv)
            val nextP2 = p2.copy(inventory = p2Inv)
            players[nextP1.id] = nextP1
            players[nextP2.id] = nextP2
            out.add(WorldSimulationEvent.CharacterUpdated(nextP1))
            out.add(WorldSimulationEvent.CharacterUpdated(nextP2))
        }
        tradeSessions.remove(updated.sessionId)
        out.add(WorldSimulationEvent.TradeUpdated(listOf(updated.initiatorId, updated.partnerId), null))
    } else {
        tradeSessions[updated.sessionId] = updated
        out.add(WorldSimulationEvent.TradeUpdated(listOf(updated.initiatorId, updated.partnerId), updated))
    }
    return out
}
