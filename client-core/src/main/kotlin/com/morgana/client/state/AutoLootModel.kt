package com.morgana.client.state

import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.sim.GROUND_PICKUP_RANGE_TILES
import kotlin.math.abs

/**
 * Decides when the client asks the server to pick up loot on its own (the Auto-loot setting). The server still checks range,
 * ownership and room, so this only saves the key press: it sends the ordinary pickup request when something the player may take
 * lies within reach and fits in the bag. Pure (no graphics, no network), so the rules are unit-tested.
 */
class AutoLootModel {
    private var lastSignature: String = ""
    private var lastSentMs: Long = Long.MIN_VALUE

    /**
     * True when a pickup request should be sent now. Items owned by someone else are left alone (even once their protection
     * runs out; press the pickup key for those), and so are items that would not fit. After a request, the same loot is not
     * asked for again for [RETRY_MS] unless the loot on the ground changed, so a refusal cannot turn into a stream of requests.
     */
    fun shouldPickUp(
        enabled: Boolean,
        alive: Boolean,
        characterId: String?,
        mapId: Int,
        tileX: Int,
        tileY: Int,
        ground: List<MapGroundItem>,
        inventory: Inventory?,
        items: Map<Int, ItemDefinition>,
        nowMs: Long,
        pickupRange: Int = GROUND_PICKUP_RANGE_TILES
    ): Boolean {
        if (!enabled || !alive || characterId == null || inventory == null) {
            lastSignature = ""
            return false
        }
        val takeable = ground.filter { g ->
            g.mapId == mapId &&
                maxOf(abs(g.tileX - tileX), abs(g.tileY - tileY)) <= pickupRange &&
                (g.ownerCharacterId == null || g.ownerCharacterId == characterId) &&
                fits(g, inventory, items)
        }
        if (takeable.isEmpty()) {
            lastSignature = ""
            return false
        }
        val signature = takeable.sortedBy { it.instanceId }.joinToString(",") { "${it.instanceId}:${it.stack.amount}" }
        if (signature == lastSignature && nowMs - lastSentMs < RETRY_MS) return false
        lastSignature = signature
        lastSentMs = nowMs
        return true
    }

    private fun fits(g: MapGroundItem, inventory: Inventory, items: Map<Int, ItemDefinition>): Boolean {
        val def = items[g.stack.itemId] ?: return false
        return inventory.addStack(def, g.stack).addedAmount > 0
    }

    companion object {
        /**
         * True when the player could take something from the ground right now: loot on this map within pickup range that is
         * theirs or unowned. Ignores bag room (the pose plays for a try; the server decides). Used to play the gather pose.
         */
        fun lootInReach(ground: List<MapGroundItem>, characterId: String?, mapId: Int, tileX: Int, tileY: Int, pickupRange: Int = GROUND_PICKUP_RANGE_TILES): Boolean =
            ground.any { g ->
                g.mapId == mapId &&
                    maxOf(abs(g.tileX - tileX), abs(g.tileY - tileY)) <= pickupRange &&
                    (g.ownerCharacterId == null || g.ownerCharacterId == characterId)
            }

        /** How long the same loot waits before it is asked for again. */
        const val RETRY_MS = 3_000L
    }
}
