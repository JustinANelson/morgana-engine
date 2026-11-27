package com.morgana.server

import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.clearOfflineTrainingFuel
import com.morgana.domain.sim.fuelOfflineTraining
import com.morgana.domain.sim.setTrainingFocus
import com.morgana.domain.sim.toggleAfkTraining
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket

/**
 * Handles mastery & offline/online-AFK training packets outside [MorganaWorldCoordinator]
 * to keep the coordinator lean.
 */
internal object TrainingPacketHandlers {
    fun handle(
        simulation: WorldSimulation,
        charId: String,
        packet: ClientPacket
    ): List<WorldSimulationEvent>? = when (packet) {
        is ClientPacket.SetTrainingFocus -> simulation.setTrainingFocus(charId, packet.focusKind, packet.focusId)
        is ClientPacket.FuelOfflineTraining -> simulation.fuelOfflineTraining(charId, packet.slotIndex, packet.amount)
        is ClientPacket.ClearOfflineTrainingFuel -> simulation.clearOfflineTrainingFuel(charId)
        is ClientPacket.ToggleAfkTraining -> simulation.toggleAfkTraining(charId, packet.active)
        else -> null
    }

    fun toPacket(ev: WorldSimulationEvent.OfflineTrainingCompleted): ServerPacket.OfflineTrainingReport =
        ServerPacket.OfflineTrainingReport(
            minutesTrained = ev.minutesTrained,
            focusKind = ev.focusKind,
            focusId = ev.focusId,
            focusName = ev.focusName,
            hitsAwarded = ev.hitsAwarded,
            startLevel = ev.startLevel,
            endLevel = ev.endLevel,
            remainingFuelSeconds = ev.remainingFuelSeconds
        )
}
