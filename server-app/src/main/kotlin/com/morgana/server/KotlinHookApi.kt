package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import com.morgana.protocol.ChatChannel
import org.slf4j.LoggerFactory

/**
 * Strongly-typed Kotlin Hook API for server plugins and custom event commands.
 *
 * Callbacks are notifications fired after the simulation has applied the action; they cannot veto it.
 * Game rules and formulas are configured via GameDefinition; content via content files or event pages.
 * The [WorldSimulation] passed in is the live one: prefer its public methods, and note that changes a plugin
 * makes this way are not captured by replay recording.
 */
/** What a player asked to do, as shown to [MorganaServerHook.onBeforeAction] before the server acts on it. */
sealed interface PlayerAction {
    /** A non-command chat line (slash commands are not offered). */
    data class Chat(val channel: ChatChannel, val message: String) : PlayerAction
    /** Use, equip or consume the item in [slotIndex]; [itemId] is what is in the slot (null if empty). */
    data class UseItem(val slotIndex: Int, val itemId: Int?) : PlayerAction
    data class DropItem(val slotIndex: Int, val itemId: Int?, val amount: Int) : PlayerAction
    data class CastSpell(val spellId: Int) : PlayerAction
    data object Attack : PlayerAction
    data class ShopBuy(val shopId: Int, val entryIndex: Int, val quantity: Int) : PlayerAction
    data class ShopSell(val shopId: Int, val slotIndex: Int, val itemId: Int?, val quantity: Int) : PlayerAction
    data class Craft(val recipeId: Int) : PlayerAction
    data class Custom(val actionId: String, val params: Map<String, String>) : PlayerAction
}

/** The answer of [MorganaServerHook.onBeforeAction]. */
sealed interface HookDecision {
    data object Allow : HookDecision
    /** Refuse the action; [reason] is shown to the player as a server message (blank shows nothing). */
    data class Deny(val reason: String = "") : HookDecision
}

interface MorganaServerHook {
    val pluginId: String

    /**
     * Asked before the server acts on a player's request. Return [HookDecision.Deny] to refuse it: nothing happens and nothing is
     * recorded for replay (the server never sees the request). The first plugin to deny wins; a hook that throws is skipped (the
     * action is allowed). Keep it fast: it runs on the server's packet thread. Do not change the world here, only decide.
     */
    fun onBeforeAction(character: CharacterState, action: PlayerAction, sim: WorldSimulation): HookDecision = HookDecision.Allow

    fun onPlayerLogin(character: CharacterState, sim: WorldSimulation) {}
    fun onPlayerLogout(character: CharacterState, sim: WorldSimulation) {}
    fun onCustomEventHook(character: CharacterState, hookId: String, payload: Map<String, String>, sim: WorldSimulation) {}

    /** A player equipped an item, consumed it, or learned a spell from a scroll. */
    fun onItemUsed(character: CharacterState, itemId: Int, sim: WorldSimulation) {}

    /** A player cast a spell (the cast succeeded: resources spent, cooldown started). */
    fun onSpellCast(character: CharacterState, spellId: Int, sim: WorldSimulation) {}

    /** An NPC died. [killer] is null if no player landed the killing blow or they have since logged out. */
    fun onNpcKilled(npcInstanceId: String, npcId: Int, mapId: Int, killer: CharacterState?, sim: WorldSimulation) {}

    /** A player died (called before they are shown at the respawn point). */
    fun onPlayerDeath(character: CharacterState, sim: WorldSimulation) {}

    /** A client sent a plugin message. [channel] is `<pluginId>:<name>`; every hook sees it, so check the prefix. Treat the payload as untrusted. */
    fun onPluginMessage(character: CharacterState, channel: String, payload: Map<String, String>, sim: WorldSimulation) {}

    /**
     * The player pressed [buttonId] of a plugin window that is open for them (ids are `<pluginId>:<name>`). Checked by the server.
     * [values] is the text of the window's inputs (input id to text); the server has already dropped unknown inputs and cut each
     * text to its limit, but it is still player-typed text: validate it.
     */
    fun onPluginAction(character: CharacterState, windowId: String, buttonId: String, values: Map<String, String>, sim: WorldSimulation) {}

    /** The player closed a plugin window that was open for them. */
    fun onPluginWindowClosed(character: CharacterState, windowId: String, sim: WorldSimulation) {}

    /** A player sent a non-command chat message. */
    fun onPlayerChat(character: CharacterState, channel: ChatChannel, message: String, sim: WorldSimulation) {}
}

class EventHookRegistry {
    private val logger = LoggerFactory.getLogger(EventHookRegistry::class.java)
    // Copy-on-write: plugins can be enabled or disabled while the game tick is dispatching.
    private val hooks: MutableList<MorganaServerHook> = java.util.concurrent.CopyOnWriteArrayList()

    fun register(hook: MorganaServerHook) {
        hooks.removeAll { it.pluginId == hook.pluginId }
        hooks.add(hook)
    }

    /** Remove a hook (by identity); used when a plugin is disabled. Returns whether it was registered. */
    fun unregister(hook: MorganaServerHook): Boolean = hooks.removeAll { it === hook }

    /** The plugin ids of the registered hooks, in call order. */
    fun pluginIds(): List<String> = hooks.map { it.pluginId }

    /** The first [HookDecision.Deny] from any hook, else [HookDecision.Allow]. */
    fun dispatchBeforeAction(character: CharacterState, action: PlayerAction, sim: WorldSimulation): HookDecision {
        for (hook in hooks.toList()) {
            try {
                val decision = hook.onBeforeAction(character, action, sim)
                if (decision is HookDecision.Deny) return decision
            } catch (e: Exception) {
                logger.error("Hook '{}' failed in onBeforeAction", hook.pluginId, e)
            }
        }
        return HookDecision.Allow
    }

    fun dispatchLogin(character: CharacterState, sim: WorldSimulation) {
        forEachHook("onPlayerLogin") { it.onPlayerLogin(character, sim) }
    }

    fun dispatchLogout(character: CharacterState, sim: WorldSimulation) {
        forEachHook("onPlayerLogout") { it.onPlayerLogout(character, sim) }
    }

    fun dispatchCustomHook(character: CharacterState, hookId: String, payload: Map<String, String>, sim: WorldSimulation) {
        forEachHook("onCustomEventHook") { it.onCustomEventHook(character, hookId, payload, sim) }
    }

    fun dispatchItemUsed(character: CharacterState, itemId: Int, sim: WorldSimulation) {
        forEachHook("onItemUsed") { it.onItemUsed(character, itemId, sim) }
    }

    fun dispatchSpellCast(character: CharacterState, spellId: Int, sim: WorldSimulation) {
        forEachHook("onSpellCast") { it.onSpellCast(character, spellId, sim) }
    }

    fun dispatchNpcKilled(npcInstanceId: String, npcId: Int, mapId: Int, killer: CharacterState?, sim: WorldSimulation) {
        forEachHook("onNpcKilled") { it.onNpcKilled(npcInstanceId, npcId, mapId, killer, sim) }
    }

    fun dispatchPlayerDeath(character: CharacterState, sim: WorldSimulation) {
        forEachHook("onPlayerDeath") { it.onPlayerDeath(character, sim) }
    }

    fun dispatchPluginMessage(character: CharacterState, channel: String, payload: Map<String, String>, sim: WorldSimulation) {
        forEachHook("onPluginMessage") { it.onPluginMessage(character, channel, payload, sim) }
    }

    fun dispatchPluginAction(character: CharacterState, windowId: String, buttonId: String, values: Map<String, String>, sim: WorldSimulation) {
        forEachHook("onPluginAction") { it.onPluginAction(character, windowId, buttonId, values, sim) }
    }

    fun dispatchPluginWindowClosed(character: CharacterState, windowId: String, sim: WorldSimulation) {
        forEachHook("onPluginWindowClosed") { it.onPluginWindowClosed(character, windowId, sim) }
    }

    fun dispatchPlayerChat(character: CharacterState, channel: ChatChannel, message: String, sim: WorldSimulation) {
        forEachHook("onPlayerChat") { it.onPlayerChat(character, channel, message, sim) }
    }

    /** A failing plugin is logged and skipped so it can never break the game tick or other plugins. */
    private fun forEachHook(callback: String, action: (MorganaServerHook) -> Unit) {
        for (hook in hooks.toList()) {
            try {
                action(hook)
            } catch (e: Exception) {
                logger.error("Hook '{}' failed in {}", hook.pluginId, callback, e)
            }
        }
    }
}
