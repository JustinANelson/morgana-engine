package com.morgana.server

import com.morgana.domain.onboarding.OnboardingRules
import com.morgana.domain.onboarding.TipTrigger
import com.morgana.domain.sim.WorldSimulation
import com.morgana.persistence.AccountRepository
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.TipsAction

/**
 * New-player tips: decides when a character is shown one, remembers per account which were shown, and sends them.
 *
 * Tips are state-driven ([OnboardingRules.triggersFor]) plus two one-off triggers (the first login, a death), so they
 * fire however the state came about. Each tip is shown once per account, so a second character never repeats them. They
 * are throttled: an ordinary tip waits [TIP_GAP_TICKS] after the last one, an urgent one (death, broken gear, low health)
 * only [URGENT_GAP_TICKS], and a character is looked at no more than once per [CHECK_EVERY_TICKS]. Nothing here changes the
 * simulation, so replays and determinism are untouched.
 *
 * @property send delivers a packet to a character, only if it advertised the tips capability.
 */
class TipService(
    private val accounts: AccountRepository,
    private val simulation: WorldSimulation,
    private val send: (characterId: String, packet: ServerPacket) -> Boolean
) {
    private class AccountTips(var seen: Set<String>, var off: Boolean)

    private val byAccount = HashMap<String, AccountTips>()
    private val lastTipTick = HashMap<String, Long>()
    private val lastCheckTick = HashMap<String, Long>()

    /**
     * Accounts that never get tips this server run: those that signed in with a dev ticket (an LLM or a script driving the
     * client, or a developer testing), for whom a tip window is only in the way. Not saved, so the account's own tip
     * settings and history are untouched and a normal login shows tips as usual.
     */
    private val quiet = HashSet<String>()

    fun quietAccount(accountId: String) {
        quiet += accountId
    }

    private fun stateOf(accountId: String): AccountTips? = byAccount[accountId] ?: accounts.findById(accountId)?.let {
        AccountTips(it.tipsSeen, it.tipsOff).also { s -> byAccount[accountId] = s }
    }

    private fun persist(accountId: String, state: AccountTips) {
        val account = accounts.findById(accountId) ?: return
        accounts.save(account.copy(tipsSeen = state.seen, tipsOff = state.off))
    }

    /** The character entered the world: the welcome tip (if this account never saw it) and anything already due. */
    fun onLogin(characterId: String) {
        lastTipTick.remove(characterId)
        lastCheckTick.remove(characterId)
        offer(characterId, setOf(TipTrigger.WELCOME), force = true)
    }

    /** The character's state changed: show a tip if one is due and it is time. */
    fun onCharacterUpdated(characterId: String) {
        val now = simulation.currentTick
        val last = lastCheckTick[characterId]
        if (last != null && now - last < CHECK_EVERY_TICKS) return
        lastCheckTick[characterId] = now
        offer(characterId, emptySet(), force = false)
    }

    /** The character died. */
    fun onDeath(characterId: String) = offer(characterId, setOf(TipTrigger.DEATH), force = true)

    /** The character left the world. */
    fun forget(characterId: String) {
        lastTipTick.remove(characterId)
        lastCheckTick.remove(characterId)
    }

    /** `/tips` or a [TipsAction] from the client. Returns the sentence to tell the player. */
    fun control(characterId: String, action: TipsAction): String {
        val player = simulation.players[characterId] ?: return ""
        val state = stateOf(player.accountId) ?: return ""
        when (action) {
            TipsAction.OFF -> state.off = true
            TipsAction.ON -> state.off = false
            TipsAction.RESET -> { state.off = false; state.seen = emptySet() }
        }
        persist(player.accountId, state)
        lastTipTick.remove(characterId)
        return when (action) {
            TipsAction.OFF -> "Tips are off. Type /tips on to turn them back on."
            TipsAction.ON -> "Tips are on."
            TipsAction.RESET -> "Tips are on and every tip will be shown again."
        }
    }

    private fun offer(characterId: String, oneOff: Set<TipTrigger>, force: Boolean) {
        val game = simulation.game.definition
        if (!OnboardingRules.enabled(game)) return
        val player = simulation.players[characterId] ?: return
        if (player.accountId in quiet) return
        val state = stateOf(player.accountId) ?: return
        if (state.off) return
        val tips = OnboardingRules.tips(game)
        if (tips.isEmpty() || tips.all { it.id in state.seen }) return
        val raised = oneOff + OnboardingRules.triggersFor(player, game, simulation.items, simulation.spells.values)
        val tip = OnboardingRules.next(player, tips, raised, state.seen) ?: return
        val now = simulation.currentTick
        val last = lastTipTick[characterId]
        if (!force && last != null) {
            val gap = if (tip.trigger.urgent) URGENT_GAP_TICKS else TIP_GAP_TICKS
            if (now - last < gap) return
        }
        if (!send(characterId, ServerPacket.Tip(tip.id, tip.title, tip.text))) return
        state.seen = state.seen + tip.id
        persist(player.accountId, state)
        lastTipTick[characterId] = now
    }

    /** The tip ids this character's account has been shown (for tests and the admin tools). */
    fun seen(characterId: String): Set<String> =
        simulation.players[characterId]?.let { stateOf(it.accountId)?.seen } ?: emptySet()

    companion object {
        /** An ordinary tip waits this long after the previous one: 30 seconds. */
        const val TIP_GAP_TICKS = 600L

        /** An urgent tip (death, broken gear, low health) waits this long: 5 seconds. */
        const val URGENT_GAP_TICKS = 100L

        /** A character's state is looked at no more than once a second. */
        const val CHECK_EVERY_TICKS = 20L
    }
}
