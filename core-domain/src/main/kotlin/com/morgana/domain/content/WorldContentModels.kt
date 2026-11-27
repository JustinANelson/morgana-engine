package com.morgana.domain.content

import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ToolType
import com.morgana.domain.stats.StatusEffectType
import kotlinx.serialization.Serializable

/** Version of the on-disk content document schema understood by this build. */
object ContentSchema {
    const val CURRENT_VERSION: Int = 2
}

/**
 * Whether a class frames its abilities as magical spells or martial/combat skills in the UI.
 */
@Serializable
enum class AbilityTerminology(
    val pluralLabel: String,
    val singularLabel: String,
    val windowTitle: String,
    val touchShortLabel: String,
    val levelPrefix: String
) {
    SPELLS("Spells", "Spell", "Spellbook", "Spl", "Spell Lv"),
    SKILLS("Skills", "Skill", "Skills", "Skl", "Skill Lv")
}

/**
 * Playable character class definition.
 */
@Serializable
data class ClassDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val spriteIds: List<Int> = listOf(1),
    val baseAttributes: Map<String, Int> = emptyMap(),
    val statPointsPerLevel: Int = 3,
    val autoStatGrowthPerLevel: Map<String, Int> = emptyMap(),
    val startMapId: Int = 1,
    val startX: Int = 12,
    val startY: Int = 9,
    val startingItems: List<ItemStack> = emptyList(),
    /** When true, equippable [startingItems] are automatically equipped on character creation. */
    val autoEquipStartingItems: Boolean = false,
    val startingSpellIds: List<Int> = emptyList(),
    val abilityTerminology: AbilityTerminology = AbilityTerminology.SPELLS,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Morgana ORPG NPC AI behaviors.
 */
@Serializable
enum class NpcBehavior {
    /** Wanders peacefully; only retaliates when attacked. */
    PASSIVE,
    /** Attacks players within aggro range on sight. */
    ATTACK_ON_SIGHT,
    /** Attacks PK/Rogue players and hostile monsters on sight. */
    GUARD,
    /** Stationary or roaming merchant that opens a shop on interaction. */
    SHOPKEEPER,
    /** Opens player bank storage on interaction. */
    BANKER,
    /** Opens the guild's shared vault on interaction (needs `guildBank` in the game definition); members only. */
    GUILD_BANKER,
    /** Triggers quest/dialogue event on interaction. */
    QUEST_GIVER,
    /** Friendly townsfolk that cannot be attacked. */
    FRIENDLY_ROAM
}

/**
 * Loot table entry on an [NpcDefinition].
 */
@Serializable
data class NpcDropEntry(
    val itemId: Int,
    val minAmount: Int = 1,
    val maxAmount: Int = 1,
    /** Drop chance in basis points (10000 = 100%, 2500 = 25%). Ignored when [chancePerMillion] is set. */
    val chanceBasisPoints: Int = 5000,
    /**
     * Drop chance in parts per million (1,000,000 = 100%, 400 = 1 in 2,500, 20 = 1 in 50,000) for drops rarer than a basis
     * point can express. 0 means "use [chanceBasisPoints]".
     */
    val chancePerMillion: Int = 0
) {
    /** The chance in parts per million, whichever field is in use. */
    val chancePpm: Long get() = if (chancePerMillion > 0) chancePerMillion.toLong() else chanceBasisPoints.toLong() * 100L
}

/**
 * Authoritative NPC template editable live via the F1 NPC Editor.
 */
@Serializable
data class NpcDefinition(
    val id: Int,
    val name: String,
    val title: String = "",
    val spriteId: Int = 1,
    val behavior: NpcBehavior = NpcBehavior.PASSIVE,
    val level: Int = 1,
    val attributes: Map<String, Int> = emptyMap(),
    val vitals: Map<String, Int> = emptyMap(),
    val attackDamage: Int = 6,
    val attackSpeedTicks: Int = 16,
    val moveSpeedTicks: Int = 10,
    val aggroRangeTiles: Int = 5,
    val leashRangeTiles: Int = 10,
    val xpReward: Long = 25L,
    val drops: List<NpcDropEntry> = emptyList(),
    val spellIds: List<Int> = emptyList(),
    val shopId: Int = 0,
    val eventId: Int = 0,
    val chatGreeting: String = "",
    val animationId: Int = 0,
    /** `POLE` or `GATE` makes this NPC part of the Guild War (see `GuildWarDefinition`); blank for every other NPC. */
    val guildWarRole: String = "",
    /** A scripted boss fight with phases, adds and telegraphed attacks; null for an ordinary monster. */
    val encounter: BossEncounter? = null,
    /** Makes this NPC a pet a player can adopt, summon and grow; null for everything else. See [PetTraits]. */
    val pet: PetTraits? = null,
    /**
     * A telegraphed strike: when [telegraphTicks] > 0 the monster does not hit at once. It stops, the client marks the danger zone on the
     * ground, and when the time is up whoever stands in the zone is hit, so a player can step out or dodge. [telegraphKind]: "slam" (a circle
     * round the monster), "smash" (a circle on where the target stood) or "lunge" (the monster dashes along a line of [telegraphLengthPixels]).
     * [telegraphRadiusPixels] is the circle's radius or the line's half-width; [telegraphRangeTiles] how far away it starts one;
     * [telegraphDamagePercent] scales the monster's normal damage.
     */
    val telegraphTicks: Int = 0,
    val telegraphKind: String = "slam",
    val telegraphRadiusPixels: Int = 48,
    val telegraphLengthPixels: Int = 0,
    val telegraphRangeTiles: Int = 1,
    val telegraphDamagePercent: Int = 100,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Targeting geometry for spells and skills.
 */
@Serializable
enum class SpellTargetType {
    SELF,
    SINGLE_TARGET,
    DIRECTIONAL_PROJECTILE,
    AOE_SELF_CENTERED,
    AOE_TARGET_CENTERED,
    GROUND_AOE,
    WARP
}

/**
 * Authoritative spell / ability definition editable live via the F1 Spell Editor.
 */
@Serializable
data class SpellDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val iconId: Int = 1,
    val targetType: SpellTargetType = SpellTargetType.SINGLE_TARGET,
    val levelReq: Int = 1,
    val classReqIds: Set<Int> = emptySet(),
    /** Optional required equipped weapon type id(s) (e.g. `setOf("blade", "sword")`). Empty = any weapon. */
    val weaponTypeReqs: Set<String> = emptySet(),
    /** Optional minimum weapon proficiency level required for the equipped weapon (0 = no requirement). */
    val weaponProficiencyLevelReq: Int = 0,
    val costs: Map<String, Int> = emptyMap(),
    val scales: Boolean = true,
    val castTimeTicks: Int = 0,
    val cooldownTicks: Int = 40,
    val rangeTiles: Int = 6,
    val aoeRadiusTiles: Int = 0,
    val basePower: Int = 15,
    val isHealing: Boolean = false,
    val projectileSpriteId: Int = 0,
    /** On a `DIRECTIONAL_PROJECTILE` spell, keeps flying through targets instead of stopping on the first hit. */
    val pierce: Boolean = false,
    val animationId: Int = 1,
    val statusEffectType: StatusEffectType? = null,
    val statusMagnitude: Int = 0,
    val statusDurationTicks: Int = 0,
    val warpMapId: Int = 0,
    val warpX: Int = 0,
    val warpY: Int = 0,
    /** When > 0 on an AoE spell, caps how many enemies are hit. */
    val maxTargets: Int = 0,
    /**
     * On a `SELF` support spell, lets the cast land on a party or guild ally within [rangeTiles]
     * instead of only the caster: the named `targetEntityId` when it is an ally, otherwise (for
     * healing) the most wounded ally in range, falling back to the caster.
     */
    val allyTargetable: Boolean = false,
    /**
     * On a `SELF` support spell, lands on the caster and every living party or guild ally within [rangeTiles] at once (a group
     * heal, a group buff), instead of on one recipient. Only valid on `SELF` spells.
     */
    val groupTarget: Boolean = false,
    /**
     * On a `SELF` support spell, strips every harmful status (poison, burn, bleed, slow, stun, root, debuff, taunt) from whoever it lands
     * on. With [allyTargetable] and no named target it picks the ally with the most harmful statuses. Only valid on `SELF` spells.
     */
    val cleanses: Boolean = false,
    /** Rebirths (see `RebirthDefinition`) a character needs before they can learn or cast this spell; 0 = none. */
    val rebirthReq: Int = 0,
    /** Promotion tier (see `PromotionDefinition`) a character needs before they can learn or cast this spell; 0 = none. */
    val promotionReq: Int = 0,
    /** Ticks added to this spell's running status effect on the caster each time they kill an NPC (XP skills); 0 = none. */
    val killExtendTicks: Int = 0,
    /** When above 0 the spell revives a ghost (see `GhostDefinition`) in range, restoring this percent of their life; it cannot target the living. */
    val revivePercent: Int = 0,
    /** Number of hits / shots fired per cast on a target (e.g., Rapid Fire). */
    val hitCount: Int = 1,
    /** Optional hit count at [maxSpellLevel] when > [hitCount]; 0 means fixed [hitCount]. */
    val maxHitCount: Int = 0,
    /** When > 0, deals this percentage of the caster's base attack damage instead of [basePower]. */
    val baseDamagePercent: Int = 0,
    /** Additional percentage of base attack damage gained per skill level above Lv 1. */
    val baseDamagePercentPerLevel: Int = 0,
    /** Additional percentage of [basePower] gained per skill level above Lv 1. */
    val powerPercentPerLevel: Int = 0,
    /** Maximum skill level for spells that level up with use. */
    val maxSpellLevel: Int = 9,
    /** Successful casts required per skill level (`castsPerLevel * currentLevel`). */
    val castsPerLevel: Int = 10,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
) {
    /** Number of hits / shots fired per cast at [spellLevel] (1..[maxSpellLevel]). */
    fun effectiveHitCount(spellLevel: Int): Int {
        val base = hitCount.coerceAtLeast(1)
        if (maxHitCount <= base || maxSpellLevel <= 1) return base
        val clampedLv = spellLevel.coerceIn(1, maxSpellLevel)
        return base + ((maxHitCount - base) * (clampedLv - 1)) / (maxSpellLevel - 1)
    }
}

/**
 * Entry sold in a [ShopDefinition].
 */
@Serializable
data class ShopEntry(
    val itemId: Int,
    val amount: Int = 1,
    val currencyItemId: Int = 1,
    val priceOverride: Int = 0
)

/**
 * Authoritative shop definition editable live via the F1 Shop Editor.
 */
@Serializable
data class ShopDefinition(
    val id: Int,
    val name: String,
    /** Percentage of item base price when player buys (default 100%). */
    val buyRatePercent: Int = 100,
    /** Percentage of item base price paid when player sells to shop (default 50%). */
    val sellRatePercent: Int = 50,
    val currencyItemId: Int = 1,
    val entries: List<ShopEntry> = emptyList(),
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Resource node category for Morgana ORPG.
 */
@Serializable
enum class ResourceCategory {
    TREE,
    ORE_VEIN,
    FISHING_SPOT,
    HERB_PATCH
}

/**
 * Authoritative gathering resource node definition editable live via the F1 Resource Editor.
 */
@Serializable
data class ResourceDefinition(
    val id: Int,
    val name: String,
    val category: ResourceCategory = ResourceCategory.TREE,
    val intactSpriteId: Int = 1,
    val depletedSpriteId: Int = 2,
    val requiredTool: ToolType = ToolType.HATCHET,
    val rewardItemId: Int = 1,
    val minYield: Int = 1,
    val maxYield: Int = 2,
    val maxHealth: Int = 3,
    val respawnTicks: Int = 160,
    val xpReward: Long = 15L,
    val emptyMessage: String = "There is nothing left to harvest right now.",
    val successMessage: String = "You harvest resources!",
    /** Floating text for each swing that does not finish the node (for example `Mine!` for ore). */
    val swingText: String = "Chop!",
    /** Extra loot rolled when the node is used up, on top of [rewardItemId] (a rare gem from an ore vein); empty for none. */
    val bonusDrops: List<NpcDropEntry> = emptyList(),
    val animationId: Int = 0,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Ingredient requirement in a crafting [RecipeDefinition].
 */
@Serializable
data class RecipeIngredient(
    val itemId: Int,
    val amount: Int = 1
)

/**
 * Crafting station types.
 */
@Serializable
enum class CraftingStationType {
    ANYWHERE,
    ANVIL,
    ALCHEMY_TABLE,
    COOKING_FIRE,
    WORKBENCH
}

/**
 * Crafting recipe definition editable live via the F1 Recipe Editor.
 */
@Serializable
data class RecipeDefinition(
    val id: Int,
    val name: String,
    val stationType: CraftingStationType = CraftingStationType.ANYWHERE,
    val levelReq: Int = 1,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val outputItemId: Int,
    val outputAmount: Int = 1,
    val xpReward: Long = 20L,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

/**
 * Sprite animation definition editable live via the F1 Animation Editor.
 */
@Serializable
data class AnimationDefinition(
    val id: Int,
    val name: String,
    val spriteSheetId: Int = 1,
    val frameCount: Int = 5,
    val frameDurationMs: Int = 80,
    val soundId: String = "",
    val renderAboveEntities: Boolean = true,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
)

