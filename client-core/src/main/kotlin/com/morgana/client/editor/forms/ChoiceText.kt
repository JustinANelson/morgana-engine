package com.morgana.client.editor.forms

/**
 * Plain-language names and one-line explanations for the raw values of the editor's choice fields (QUEST_GIVER, HERB_PATCH...).
 * The data files keep the raw value; only the editor shows these. A value with no entry still gets a readable name made from
 * the raw one, so a choice added to the engine later is never shown as shouting capitals.
 */
object ChoiceText {

    /** "QUEST_GIVER" becomes "Quest giver" unless [NAMES] has a nicer name. */
    fun label(raw: String): String = NAMES[raw] ?: raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** What the choice does, or an empty text when there is nothing useful to add. [field] is the form field key, for values that mean different things in different fields. */
    fun explain(raw: String, field: String = ""): String = EXPLANATIONS["$field:$raw"] ?: EXPLANATIONS[raw].orEmpty()

    /** The raw value for a label shown by [label], or null if none of [raws] has that label. */
    fun rawFor(label: String, raws: Collection<String>): String? = raws.firstOrNull { label(it) == label }

    private val NAMES = mapOf(
        "ATTACK_ON_SIGHT" to "Aggressive",
        "FRIENDLY_ROAM" to "Friendly wanderer",
        "RANGED_BOW" to "Bow (ranged)",
        "MAGIC_STAFF" to "Magic staff",
        "DOT_POISON" to "Poison (damage over time)",
        "DOT_BURN" to "Burn (damage over time)",
        "DOT_BLEED" to "Bleed (damage over time)",
        "HOT_REGEN" to "Regeneration (heal over time)",
        "STAT_BUFF" to "Stat boost",
        "STAT_DEBUFF" to "Stat penalty",
        "AOE_SELF_CENTERED" to "Area around you",
        "AOE_TARGET_CENTERED" to "Area around the target",
        "GROUND_AOE" to "Ground area (cursor)",
        "DIRECTIONAL_PROJECTILE" to "Projectile in a direction",
        "SINGLE_TARGET" to "One target",
        "SELF" to "Yourself",
        "WARP" to "Teleport",
        "SPELL_SCROLL" to "Spell scroll",
        "KILL_NPC" to "Defeat creatures",
        "COLLECT_ITEM" to "Collect items",
        "TALK_TO_NPC" to "Talk to someone",
        "HARVEST_RESOURCE" to "Gather resources",
        "CRAFT_RECIPE" to "Craft something",
        "VISIT_MAP" to "Visit a place",
        "ORE_VEIN" to "Ore vein",
        "HERB_PATCH" to "Herb patch",
        "FISHING_SPOT" to "Fishing spot",
        "FISHING_ROD" to "Fishing rod",
        "ACTION_INTERACT" to "Player interacts with it",
        "PLAYER_TOUCH" to "Player steps on it",
        "AUTORUN_ON_ENTER" to "Player enters the map",
        "PLAYER" to "This player only",
        "WORLD" to "Whole world (shared)",
        "MANUAL" to "Only when called",
        "ON_TIMER" to "On a timer",
        "ON_LOGIN" to "When player logs in",
        "ON_LEVEL_UP" to "When player levels up",
        "ALL" to "All of these match",
        "ANY" to "Any one matches",
        "NONE" to "None",
        "PERSISTENT" to "Persistent (keep everything)",
        "EXTRACTION" to "Extraction (keep only if you escape)",
        "EPHEMERAL" to "Ephemeral (reset after run, keep meta-currency)",
        "OFF" to "Off (normal death)",
        "LOCK" to "Soft-lock (Hall of the Fallen)",
        "DELETE" to "Hard delete (erase character)",
        "ROOMS_AND_CORRIDORS" to "Rooms and corridors",
        "CELLULAR_CAVES" to "Cellular caves",
        "BSP_DUNGEON" to "BSP catacombs",
        "OPEN_ARENA" to "Open courtyard / arena"
    )

    private val EXPLANATIONS = mapOf(
        // NPC behaviour
        "PASSIVE" to "Wanders peacefully and only fights back when attacked.",
        "ATTACK_ON_SIGHT" to "Attacks any player who comes close.",
        "GUARD" to "Protects the town: attacks troublemakers and hostile monsters.",
        "SHOPKEEPER" to "Opens a shop when a player talks to them.",
        "BANKER" to "Opens the player's bank when talked to.",
        "GUILD_BANKER" to "Opens the guild's shared vault when a member talks to them (needs the guildBank section).",
        "QUEST_GIVER" to "Starts a quest or dialogue when talked to.",
        "FRIENDLY_ROAM" to "Friendly townsfolk who stroll around and cannot be attacked.",
        // item types
        "WEAPON" to "Held in the hand to attack.",
        "SHIELD" to "Held in the off hand to block damage.",
        "HELMET" to "Worn on the head.",
        "ARMOR" to "Worn on the body.",
        "BOOTS" to "Worn on the feet.",
        "RING" to "Worn as jewellery; often gives small bonuses.",
        "AMULET" to "Worn around the neck; often gives bonuses.",
        "TOOL" to "Used to gather resources, like an axe or a pickaxe.",
        "AMMO" to "Fired by ranged weapons and used up.",
        "CONSUMABLE" to "Used once: food, potions and the like.",
        "SPELL_SCROLL" to "Used once to learn a spell.",
        "KEY" to "Opens locked doors.",
        "CURRENCY" to "Money.",
        "MATERIAL" to "An ingredient or trade good with no use of its own.",
        "QUEST" to "A quest item; usually cannot be sold or dropped.",
        // weapon style
        "MELEE" to "Hits enemies standing next to the player.",
        "RANGED_BOW" to "Shoots enemies from a distance and needs ammunition.",
        "MAGIC_STAFF" to "Casts bolts from a distance using magic.",
        // gathering tool
        "HATCHET" to "Cuts trees.",
        "PICKAXE" to "Mines ore.",
        "FISHING_ROD" to "Catches fish.",
        // status effects
        "DOT_POISON" to "Hurts a little every moment until it wears off.",
        "DOT_BURN" to "Burns for a little damage every moment until it wears off.",
        "DOT_BLEED" to "Bleeds for a little damage every moment until it wears off.",
        "HOT_REGEN" to "Heals a little every moment until it wears off.",
        "STUN" to "Cannot move or act.",
        "ROOT" to "Cannot move but can still act.",
        "SLOW" to "Moves more slowly.",
        "HASTE" to "Moves faster.",
        "status:SHIELD" to "Absorbs some damage.",
        "STAT_BUFF" to "Raises attributes for a while.",
        "STAT_DEBUFF" to "Lowers attributes for a while.",
        // spell targets
        "SELF" to "Affects only the caster.",
        "SINGLE_TARGET" to "Affects the one enemy or ally the caster has selected.",
        "DIRECTIONAL_PROJECTILE" to "Flies in the direction the caster faces and hits the first thing in its way.",
        "AOE_SELF_CENTERED" to "Affects everyone around the caster.",
        "AOE_TARGET_CENTERED" to "Affects everyone around the selected target.",
        "GROUND_AOE" to "Affects everyone in an area centered on the tile you aim at.",
        "WARP" to "Moves the caster to another place.",
        // resources
        "TREE" to "A tree; cut with a hatchet.",
        "ORE_VEIN" to "A rock with ore; mined with a pickaxe.",
        "FISHING_SPOT" to "Water to fish in; needs a fishing rod.",
        "HERB_PATCH" to "Plants picked by hand.",
        // crafting stations
        "ANYWHERE" to "Can be crafted anywhere, with no station.",
        "ANVIL" to "Needs an anvil nearby.",
        "ALCHEMY_TABLE" to "Needs an alchemy table nearby.",
        "COOKING_FIRE" to "Needs a cooking fire nearby.",
        "WORKBENCH" to "Needs a workbench nearby.",
        // quest objectives
        "KILL_NPC" to "The target is an NPC id; the player must defeat that many.",
        "COLLECT_ITEM" to "The target is an item id; the player must hold that many.",
        "TALK_TO_NPC" to "The target is an NPC id; the player must talk to them.",
        "HARVEST_RESOURCE" to "The target is a resource id; the player must gather from it that many times.",
        "CRAFT_RECIPE" to "The target is a recipe id; the player must craft it that many times.",
        "VISIT_MAP" to "The target is a map id; the player must go there.",
        // variable scopes
        "PLAYER" to "Remembered only for the character who triggered the event.",
        "WORLD" to "Shared by everyone in the realm and saved across restarts.",
        // conditional match modes
        "ALL" to "Every condition listed below must be true (AND).",
        "ANY" to "At least one condition listed below must be true (OR).",
        // common event triggers
        "MANUAL" to "Runs only when invoked by a Run common event step.",
        "ON_TIMER" to "Runs automatically for every online player each time its tick interval repeats.",
        "ON_LOGIN" to "Runs automatically once when a player enters the world.",
        "ON_LEVEL_UP" to "Runs automatically whenever a player gains one or more levels."
    )
}
