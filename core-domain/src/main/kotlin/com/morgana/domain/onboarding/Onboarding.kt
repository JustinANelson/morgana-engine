package com.morgana.domain.onboarding

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import kotlinx.serialization.Serializable

/**
 * What makes a tip appear. Most are read from the character's state, so they fire however the state came about (a hit,
 * a level, an item found); [DEATH] comes from the death event, and [WELCOME] from the first login of an account.
 */
@Serializable
enum class TipTrigger(
    /** An urgent tip is shown at once; the others wait their turn so a player is never buried in advice. */
    val urgent: Boolean = false
) {
    /** The first time an account enters the world. */
    WELCOME,

    /** The character carries gear it could wear, in a slot that is empty. */
    EQUIPMENT_FOUND,

    /** The character has unspent attribute points. */
    STAT_POINTS,

    /** The character's level lets it learn a skill or spell it does not know yet. */
    SKILL_AVAILABLE,

    /** Worn gear has started to wear down (durability under 100). */
    GEAR_WORN,

    /** A worn piece of gear has broken (durability 0). */
    GEAR_BROKEN(urgent = true),

    /** The character is alive with less than 30% of its life. */
    LOW_HEALTH(urgent = true),

    /** The character has died. */
    DEATH(urgent = true),

    /** The character reached [TipDefinition.level] (a game's own tip: a promotion, a feature that opens). */
    LEVEL
}

/**
 * One piece of advice. [text] may use `{key:ACTION}` (the player's key for a game action, for example `{key:INVENTORY}`)
 * and `{abilities}` (the game's word for skills or spells); the client fills them in, so rebinding keys never leaves a
 * tip lying.
 */
@Serializable
data class TipDefinition(
    /** Stable id, remembered per account so the tip is shown only once: `A-Z a-z 0-9 _`, at most 40 characters. */
    val id: String,
    val trigger: TipTrigger,
    val title: String = "",
    val text: String = "",
    /** The level for a [TipTrigger.LEVEL] tip. */
    val level: Int = 0
) {
    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_]{1,40}")
        const val MAX_TITLE = 60
        const val MAX_TEXT = 500
    }
}

/**
 * New-player tips (`onboarding` in `game.json`). Without the section a game gets the built-in tips; with it a game turns
 * them off, rewrites one (give a tip the id of a built-in), removes one (the same id with blank text) or adds its own.
 * Each tip is shown once per account, never again for a second character.
 */
@Serializable
data class OnboardingConfig(
    /** Kill-switch: false shows no tips at all. */
    val enabled: Boolean = true,
    /** False drops the built-in tips, leaving only [tips]. */
    val defaults: Boolean = true,
    val tips: List<TipDefinition> = emptyList()
) {
    companion object {
        const val MAX_TIPS = 40
    }
}

/** What the engine tells a new player when the game says nothing: written for any genre, built from what the game enables. */
object OnboardingDefaults {
    fun tips(game: GameDefinition): List<TipDefinition> = buildList {
        add(
            TipDefinition(
                "welcome", TipTrigger.WELCOME, "Welcome!",
                "Move with the arrow keys or WASD. Press {key:INTERACT} to talk to people and use what is in front of you, " +
                    "{key:ATTACK} to attack, and {key:PICK_UP} to pick things up. {key:CONTROLS} lists every key."
            )
        )
        add(
            TipDefinition(
                "gear", TipTrigger.EQUIPMENT_FOUND, "You can wear that",
                "You are carrying gear you are not wearing. Open your inventory ({key:INVENTORY}), pick the item and choose Equip."
            )
        )
        if (game.attributes.any { it.allocatable }) {
            add(
                TipDefinition(
                    "stat_points", TipTrigger.STAT_POINTS, "Points to spend",
                    "You have unspent points. Open your character ({key:CHARACTER}), go to the Stats tab and press +1 beside the " +
                        "attribute you want to grow."
                )
            )
        }
        if (OnboardingRules.spellsShown(game)) {
            add(
                TipDefinition(
                    "skills", TipTrigger.SKILL_AVAILABLE, "New {abilities}",
                    "You are ready to learn {abilities}. Open the {abilities} window ({key:SPELLBOOK}) to see them, and put the ones " +
                        "you want on your quickbar to use them with the number keys."
                )
            )
        }
        if (game.durability?.enabled == true) {
            add(
                TipDefinition(
                    "gear_worn", TipTrigger.GEAR_WORN, "Gear wears down",
                    "Fighting wears your gear down. Its condition shows in your inventory. Have it repaired by a smith or armourer " +
                        "for a fee before it breaks, because broken gear stops working."
                )
            )
            add(
                TipDefinition(
                    "gear_broken", TipTrigger.GEAR_BROKEN, "Something broke",
                    "A piece of your gear broke and no longer counts. Visit a smith or armourer to repair it."
                )
            )
        }
        add(
            TipDefinition(
                "low_health", TipTrigger.LOW_HEALTH, "Low health",
                "Your health is low. Use a healing item from your quickbar, step away from the fight, or rest before going on."
            )
        )
        val ghost = game.ghost?.takeIf { it.enabled }
        add(
            if (ghost != null) {
                val auto = if (ghost.autoReleaseTicks > 0) " You are sent home on your own after about ${ghost.autoReleaseTicks / 20} seconds." else ""
                TipDefinition(
                    "death", TipTrigger.DEATH, "You died",
                    "You are a ghost now. You can walk, but you cannot fight, cast, use items or trade. A healer who can revive " +
                        "will bring you back where you stand, or type /release to return home with full health.$auto" +
                        if (ghost.selfReviveAfterTicks > 0) " After ${ghost.selfReviveAfterTicks / 20} seconds you can also type /revive to get up where you fell." else ""
                )
            } else {
                TipDefinition(
                    "death", TipTrigger.DEATH, "You were defeated",
                    "You were sent back to where you return after defeat. Be careful, since dying can cost you something. Heal up and try again."
                )
            }
        )
    }
}

/** Which tips a game has, and which one a character should be shown next. Pure: state in, tip out. */
object OnboardingRules {
    /** Fraction of life (in percent) under which [TipTrigger.LOW_HEALTH] fires. */
    const val LOW_HEALTH_PERCENT = 30

    fun enabled(game: GameDefinition): Boolean = game.onboarding?.enabled != false

    /** Whether the game shows a skills or spells window at all (a peaceful game hides it). */
    internal fun spellsShown(game: GameDefinition): Boolean = (game.ui?.spells ?: true) && (game.ui?.combat ?: true)

    /** The game's tips in the order they are offered: its built-in ones, rewritten, removed or extended by `onboarding`. */
    fun tips(game: GameDefinition): List<TipDefinition> {
        val config = game.onboarding ?: return OnboardingDefaults.tips(game)
        if (!config.enabled) return emptyList()
        val merged = LinkedHashMap<String, TipDefinition>()
        if (config.defaults) OnboardingDefaults.tips(game).forEach { merged[it.id] = it }
        for (tip in config.tips) {
            if (tip.text.isBlank()) merged.remove(tip.id) else merged[tip.id] = tip
        }
        return merged.values.toList()
    }

    /**
     * The state-based triggers that hold for [character] right now. [TipTrigger.WELCOME] and [TipTrigger.DEATH] are not
     * here: the server raises them from the login and the death event.
     */
    fun triggersFor(
        character: CharacterState,
        game: GameDefinition,
        items: Map<Int, ItemDefinition>,
        spells: Collection<SpellDefinition>
    ): Set<TipTrigger> = buildSet {
        if (character.isGhost) return@buildSet
        if (character.unspentStatPoints > 0) add(TipTrigger.STAT_POINTS)
        if (spellsShown(game) && spells.any { it.levelReq <= character.level && classAllows(it.classReqIds, character.classId) && it.id !in character.knownSpellIds }) {
            add(TipTrigger.SKILL_AVAILABLE)
        }
        if (game.durability?.enabled == true) {
            val worn = character.equipment.equipped.values
            if (worn.any { it.durability < 100 }) add(TipTrigger.GEAR_WORN)
            if (worn.any { it.durability <= 0 }) add(TipTrigger.GEAR_BROKEN)
        }
        val lifeId = game.vitals.firstOrNull { it.role == VitalRole.LIFE }?.id
        val life = lifeId?.let { character.vitals[it] }
        if (life != null && life.cur > 0 && life.max > 0 && life.cur * 100 < life.max * LOW_HEALTH_PERCENT) add(TipTrigger.LOW_HEALTH)
        if (wearableGearUnworn(character, items)) add(TipTrigger.EQUIPMENT_FOUND)
    }

    /** Whether the bag holds a piece of gear the character may wear (level, class) whose slot is empty. */
    private fun wearableGearUnworn(character: CharacterState, items: Map<Int, ItemDefinition>): Boolean {
        val gearTypes = setOf(ItemType.WEAPON, ItemType.SHIELD, ItemType.HELMET, ItemType.ARMOR, ItemType.BOOTS)
        for (stack in character.inventory.slots) {
            val def = stack?.let { items[it.itemId] } ?: continue
            if (def.type !in gearTypes || def.equipSlot.isNotBlank()) continue
            val slot: EquipmentSlot = def.equipmentSlotOrNull() ?: continue
            if (def.levelReq > character.level || !classAllows(def.classReqIds, character.classId)) continue
            if (character.equipment.getEquipped(slot) == null) return true
        }
        return false
    }

    private fun classAllows(required: Set<Int>, classId: Int): Boolean = required.isEmpty() || classId in required

    /**
     * The tip to show now, or null: the first tip (urgent ones first, then in the game's order) whose trigger holds and
     * which this account has not [seen]. [raised] are the one-off triggers (login, death) plus [triggersFor].
     */
    fun next(character: CharacterState, tips: List<TipDefinition>, raised: Set<TipTrigger>, seen: Set<String>): TipDefinition? {
        val due = tips.filter { tip ->
            tip.id !in seen && when (tip.trigger) {
                TipTrigger.LEVEL -> tip.level > 0 && character.level >= tip.level
                else -> tip.trigger in raised
            }
        }
        return due.firstOrNull { it.trigger.urgent } ?: due.firstOrNull()
    }
}
