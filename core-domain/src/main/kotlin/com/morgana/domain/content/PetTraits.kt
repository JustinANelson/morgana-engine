package com.morgana.domain.content

import kotlinx.serialization.Serializable

/**
 * Makes an [NpcDefinition] a pet (`pet` on the NPC; its behavior should be `FRIENDLY_ROAM` so monsters and players leave it
 * alone). A player adopts the pet with an item whose `adoptPetNpcId` names it, then summons it with `/pet summon`. A
 * summoned pet follows its owner across maps, helps against monsters that are hunting the owner (its kills and damage
 * count as the owner's), and grows: it takes a share of the XP its owner earns and gains a level at a time, hitting harder
 * with each. Nothing about a pet can hurt or be hurt by anything but the monsters it fights.
 */
@Serializable
data class PetTraits(
    /** The highest level the pet reaches. */
    val maxLevel: Int = 20,
    /** XP to go from level L to L + 1 is `L * xpPerLevel`. */
    val xpPerLevel: Int = 200,
    /** Percent of the XP the owner earns from a kill that the pet earns too. */
    val xpSharePercent: Int = 50,
    /** The pet's `attackDamage` grows by this percent for each level above the first. */
    val damagePerLevelPercent: Int = 10,
    /** Tiles it lets the owner walk away before it follows. */
    val followDistance: Int = 2,
    /** Whether it fights at all; false makes a pure companion. */
    val fights: Boolean = true
)
