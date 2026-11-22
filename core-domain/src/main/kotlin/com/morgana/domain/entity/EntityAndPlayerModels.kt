package com.morgana.domain.entity

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.ItemStack
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.VitalRole
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.VitalState
import kotlinx.serialization.Serializable

/**
 * Hotbar slot binding (either an Item ID or a Spell ID).
 */
@Serializable
enum class HotbarBindingType {
    NONE,
    ITEM,
    SPELL
}

@Serializable
data class HotbarSlot(
    val index: Int,
    val type: HotbarBindingType = HotbarBindingType.NONE,
    val targetId: Int = 0
)

/**
 * Persistent Account document (JSON file now, 1:1 MongoDB document ready).
 */
@Serializable
data class AccountDocument(
    val id: String,
    val username: String,
    val passwordHash: String,
    val accessLevel: AccessLevel = AccessLevel.PLAYER,
    val extraPermissions: Set<PermissionFlag> = emptySet(),
    val banned: Boolean = false,
    val mutedUntilTick: Long = 0L,
    val characterIds: List<String> = emptyList(),
    /** Ids of the new-player tips this account has been shown (each is shown once per account, not per character). */
    val tipsSeen: Set<String> = emptySet(),
    /** The player turned tips off (`/tips off` or "Turn off tips"). */
    val tipsOff: Boolean = false,
    val schemaVersion: Int = 1
)

/**
 * Persistent & runtime Character state (JSON file now, 1:1 MongoDB document ready).
 */
@Serializable
enum class PkMode(val label: String) {
    PEACE("Peace"),
    TEAM("Team"),
    GUILD("Guild"),
    PK("PK")
}

@Serializable
data class CharacterState(
    val id: String,
    val accountId: String,
    val name: String,
    val classId: Int = 1,
    val spriteId: Int = 1,
    /** Opt-in Paperdolling v2 character base layers (e.g., tinted skin body and hair). */
    val paperdollBaseLayers: List<PaperdollLayer> = emptyList(),
    val accessLevel: AccessLevel = AccessLevel.PLAYER,
    val level: Int = 1,
    val xp: Long = 0L,
    val unspentStatPoints: Int = 0,
    val attributes: Map<String, Int> = emptyMap(),
    val vitals: Map<String, VitalState> = emptyMap(),
    val position: WorldPosition = WorldPosition.fromTile(mapId = 1, tileX = 12, tileY = 9, direction = Direction.DOWN),
    val movementMode: MovementMode = MovementMode.GRID_LOCKED,
    val inventory: Inventory = Inventory(),
    val bank: Inventory = Inventory(maxSlots = Inventory.DEFAULT_BANK_SLOTS),
    val equipment: EquipmentLoadout = EquipmentLoadout(),
    val knownSpellIds: List<Int> = emptyList(),
    /** Current skill level (1-based) per spell id for spells that level up with use. */
    val spellLevels: Map<Int, Int> = emptyMap(),
    /** Cast progress toward the next skill level per spell id. */
    val spellUses: Map<Int, Int> = emptyMap(),
    /** Current weapon proficiency level (1-based) per weapon type id. */
    val weaponProficiencyLevels: Map<String, Int> = emptyMap(),
    /** Hit progress toward the next proficiency level per weapon type id. */
    val weaponProficiencyHits: Map<String, Int> = emptyMap(),
    val hotbar: List<HotbarSlot> = List(10) { HotbarSlot(it) },
    val statusEffects: List<ActiveStatusEffect> = emptyList(),
    val spellCooldowns: Map<Int, Long> = emptyMap(),
    val castingSpellId: Int? = null,
    val castFinishTick: Long = 0L,
    val castTotalTicks: Int = 0,
    val castTargetEntityId: String? = null,
    val castTargetTileX: Int = 0,
    val castTargetTileY: Int = 0,
    val quests: Map<Int, PlayerQuestState> = emptyMap(),
    val switches: Map<String, Boolean> = emptyMap(),
    val variables: Map<String, Int> = emptyMap(),
    val stringVariables: Map<String, String> = emptyMap(),
    val guildId: String? = null,
    val guildRank: String = "",
    val partyId: String? = null,
    /**
     * Where the character stood before entering an instanced map (see `MapInstances`), so they can be put back there
     * if they log in after the instance closed. Null outside instances.
     */
    val instanceReturn: WorldPosition? = null,
    /** The pets this character has adopted, by pet npc id. */
    val pets: Map<Int, PetState> = emptyMap(),
    /** The pet that follows this character right now (npc id), or 0 for none. */
    val activePetNpcId: Int = 0,
    /** Kills per monster kind (npc id), kept when the game has a `bestiary` section. */
    val bestiary: Map<Int, Int> = emptyMap(),
    /** Arena rating (see `ArenaDefinition`); 0 until the first match, then the rating after each one. */
    val arenaRating: Int = 0,
    val arenaWins: Int = 0,
    val arenaLosses: Int = 0,
    /** Bosses this character is saved to, by npc id, until the given world tick (see `BossEncounter.lockoutTicks`). */
    val encounterLockouts: Map<Int, Long> = emptyMap(),
    val friendNames: Set<String> = emptySet(),
    /** Characters whose chat, whispers, mail and invitations this player does not receive (see `IgnoreRules`). */
    val ignoredNames: Set<String> = emptySet(),
    val pkPoints: Int = 0,
    val isRoguePk: Boolean = false,
    val pkMode: PkMode = PkMode.PEACE,
    val lastMoveTick: Long = 0L,
    val lastAttackTick: Long = 0L,
    /** True while the character is a ghost after dying (see `GhostDefinition`); they keep 0 life until revived or released. */
    val isGhost: Boolean = false,
    val ghostSinceTick: Long = 0L,
    /** True when the character died permanently under `PermadeathMode.LOCK` (kept read-only in the Hall of the Fallen). */
    val fallen: Boolean = false,
    /** Rested XP (see `RestedDefinition`): bonus XP still to be paid out on kills. */
    val restedXp: Long = 0L,
    /** World tick (`effectiveWorldTick`) at which this character was last in the simulation; fills the rested pool while away. */
    val lastSeenWorldTick: Long = 0L,
    /** Upgrade pity (see `QualityUpgradeDefinition` and `EnhancementDefinition`): failed chancy attempts since the last chancy success. */
    val upgradePityStacks: Int = 0,
    val schemaVersion: Int = 2
) {
    val isAlive: Boolean
        get() = (vitals["hp"]?.cur ?: (vitals.values.firstOrNull()?.cur ?: 0)) > 0

    fun isAlive(lifeVitalId: String): Boolean =
        (vitals[lifeVitalId]?.cur ?: 0) > 0

    val isCasting: Boolean get() = castingSpellId != null

    fun spellLevel(spellId: Int): Int = (spellLevels[spellId] ?: 1).coerceAtLeast(1)

    fun weaponProficiencyLevel(weaponType: String): Int =
        (weaponProficiencyLevels[weaponType] ?: 1).coerceAtLeast(1)

    fun weaponProficiencyHitsFor(weaponType: String): Int =
        (weaponProficiencyHits[weaponType] ?: 0).coerceAtLeast(0)

    /**
     * Computes effective attributes (base + equipment + active status buffs/debuffs) clamped to floor/cap.
     */
    fun effectiveAttributes(game: CompiledGame, itemCatalog: Map<Int, ItemDefinition>): Map<String, Int> {
        val equipBonus = equipment.totalAttributeBonuses(itemCatalog)
        val buildBonus = com.morgana.domain.game.BuildBonuses.attributeBonuses(this, game.definition)
        val statusBonus = mutableMapOf<String, Int>()
        for (effect in statusEffects) {
            for ((k, v) in effect.attributeModifiers) {
                statusBonus[k] = (statusBonus[k] ?: 0) + v
            }
        }
        val out = mutableMapOf<String, Int>()
        for (attr in game.definition.attributes) {
            val base = attributes[attr.id] ?: attr.base
            val raw = base + (equipBonus[attr.id] ?: 0) + (buildBonus[attr.id] ?: 0) + (statusBonus[attr.id] ?: 0)
            out[attr.id] = game.clampAttribute(attr.id, raw.toLong())
        }
        return out
    }

    /**
     * Recalculates max vital pools from current level, effective attributes, and equipment bonuses.
     */
    fun recalculateVitals(game: CompiledGame, itemCatalog: Map<Int, ItemDefinition>, refill: Boolean = false): CharacterState {
        val eff = effectiveAttributes(game, itemCatalog)
        val newVitals = mutableMapOf<String, VitalState>()
        for (vDef in game.definition.vitals) {
            val bonus = equipment.totalVitalMaxBonuses(vDef.id, itemCatalog)
            val maxVal = game.vitalMax(vDef.id, level, eff, bonus).toInt().coerceAtLeast(if (vDef.role == VitalRole.LIFE) 1 else 0)
            val existingCur = vitals[vDef.id]?.cur
            val curVal = if (refill || existingCur == null) maxVal else existingCur.coerceIn(0, maxVal)
            newVitals[vDef.id] = VitalState(cur = curVal, max = maxVal)
        }
        return copy(vitals = newVitals)
    }

    /**
     * At the level cap, XP fills the paragon track (see `ParagonDefinition`): each full cost adds a paragon level in
     * the character's variables and its unspent attribute points. Without a track the XP is dropped, as before.
     */
    private fun gainParagonExperience(game: CompiledGame, amount: Long): CharacterState {
        val paragon = game.definition.paragon?.takeIf { it.enabled } ?: return this
        var tier = paragon.level(this)
        if (tier >= paragon.maxLevels) return this
        var curXp = xp + amount
        var points = unspentStatPoints
        while (tier < paragon.maxLevels) {
            val cost = paragon.costToNext(tier)
            if (curXp < cost) break
            curXp -= cost
            tier += 1
            points += paragon.statPointsPerLevel
        }
        if (tier >= paragon.maxLevels) curXp = 0L
        return copy(xp = curXp, unspentStatPoints = points, variables = variables + (paragon.variable to tier))
    }

    /**
     * Awards XP and processes level-ups deterministically.
     */
    fun gainExperience(
        game: CompiledGame,
        amount: Long,
        statPointsPerLevel: Int = 3,
        itemCatalog: Map<Int, ItemDefinition> = emptyMap(),
        autoStatGrowthPerLevel: Map<String, Int> = emptyMap()
    ): Pair<CharacterState, Int> {
        val maxLevel = game.definition.progression.maxLevel
        if (amount <= 0L) return this to 0
        if (level >= maxLevel) return gainParagonExperience(game, amount) to 0
        var curLevel = level
        var curXp = xp + amount
        var curStatPoints = unspentStatPoints
        var levelsGained = 0

        while (curLevel < maxLevel) {
            val needed = game.xpToNext(curLevel) ?: break
            if (curXp >= needed) {
                curXp -= needed
                curLevel += 1
                if (autoStatGrowthPerLevel.isEmpty()) {
                    curStatPoints += statPointsPerLevel
                }
                levelsGained += 1
            } else {
                break
            }
        }

        val updatedAttrs = if (levelsGained > 0 && autoStatGrowthPerLevel.isNotEmpty()) {
            val next = attributes.toMutableMap()
            for ((attrId, perLv) in autoStatGrowthPerLevel) {
                if (perLv != 0) {
                    next[attrId] = (next[attrId] ?: 0) + perLv * levelsGained
                }
            }
            next
        } else {
            attributes
        }

        val updated = copy(
            level = curLevel,
            xp = curXp,
            attributes = updatedAttrs,
            unspentStatPoints = curStatPoints
        ).let { state ->
            if (levelsGained > 0) state.recalculateVitals(game, itemCatalog, refill = true) else state
        }
        return updated to levelsGained
    }
}

/** One adopted pet: how far it has grown and what its owner calls it (blank for the kind's own name). */
@Serializable
data class PetState(val level: Int = 1, val xp: Int = 0, val name: String = "")

/** A boss's telegraphed danger zone: players standing within [radius] tiles of ([centerX], [centerY]) at [resolveTick] are hit by [spellId]. */
@Serializable
data class BossZone(val centerX: Int, val centerY: Int, val radius: Int, val resolveTick: Long, val spellId: Int)

/**
 * What an instanced map's [com.morgana.domain.map.InstanceScaling] did to one monster: its life, attack damage and XP
 * reward in percent of normal, and how many levels above (or below) its own level it now counts for XP.
 */
@Serializable
data class NpcScale(
    val hpPercent: Int = 100,
    val attackPercent: Int = 100,
    val xpPercent: Int = 100,
    val levelBonus: Int = 0,
    /** Drop chances in percent of normal (a harder difficulty tier pays more). */
    val dropRatePercent: Int = 100
)

/**
 * Live NPC instance spawned on a map.
 */
@Serializable
data class MapNpcInstance(
    val instanceId: String,
    val spawnSlotIndex: Int,
    val npcId: Int,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val homeX: Int,
    val homeY: Int,
    val direction: Direction = Direction.DOWN,
    val vitals: Map<String, VitalState> = emptyMap(),
    val targetEntityId: String? = null,
    val statusEffects: List<ActiveStatusEffect> = emptyList(),
    val lastMoveTick: Long = 0L,
    val lastAttackTick: Long = 0L,
    val lastSpellCastTick: Long = 0L,
    val respawnAtTick: Long = 0L,
    /** Spell this monster is winding up (see `combat.npcs.spellWindUpTicks`); null when not casting. */
    val castingSpellId: Int? = null,
    /** Tick at which the wound-up spell lands. */
    val castFinishTick: Long = 0L,
    /** No melee blow or spell may land before this tick (the first-strike delay, `combat.npcs.firstStrikeDelayTicks`). */
    val strikeReadyTick: Long = 0L,
    /** Optional [EnemyVariantDefinition.id] rolled at spawn time (e.g. "feral", "elite"). */
    val variantId: String? = null,
    /** The character a pet follows and fights for; null for every other NPC. */
    val ownerId: String? = null,
    /** A name shown instead of the definition's (a pet's own name); blank for the definition's. */
    val displayName: String = "",
    /** How an instanced map's `scaling` has toughened this monster for the party inside; null when the map does not scale. */
    val scale: NpcScale? = null,
    /** Threat by character id (see `combat.npcs.threatSwitchPercent`); empty when threat is off or nobody has fought it. */
    val threat: Map<String, Int> = emptyMap(),
    /** Index into the boss's `encounter.phases` it has reached this fight (0 at the start and after a reset). */
    val phaseIndex: Int = 0,
    /** The telegraphed ground attack this boss has under way, or null. */
    val zone: BossZone? = null,
    /** When each boss ability (by spell id) may next be used. */
    val abilityReadyTicks: Map<Int, Long> = emptyMap()
) {
    val isAlive: Boolean
        get() = (vitals["hp"]?.cur ?: (vitals.values.firstOrNull()?.cur ?: 0)) > 0 && respawnAtTick == 0L

    fun isAlive(lifeVitalId: String): Boolean =
        (vitals[lifeVitalId]?.cur ?: 0) > 0

    val tileCoord: TileCoord
        get() = TileCoord(tileX, tileY)
}

/**
 * Ground item stack dropped or spawned on a map tile.
 */
@Serializable
data class MapGroundItem(
    val instanceId: String,
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val stack: ItemStack,
    val ownerCharacterId: String? = null,
    val ownerProtectedUntilTick: Long = 0L,
    val despawnAtTick: Long = 0L
)

/**
 * Live ranged projectile or directional spell missile in flight on a map.
 */
@Serializable
data class MapProjectile(
    val projectileId: String,
    val sourceEntityId: String,
    val isFromPlayer: Boolean,
    val mapId: Int,
    val currentTileX: Int,
    val currentTileY: Int,
    val direction: Direction,
    val remainingTiles: Int,
    val spriteId: Int,
    val damage: Int,
    val spellId: Int = 0,
    /** Weapon proficiency type id carried by this projectile for hit progression. */
    val weaponType: String = "",
    val ticksPerTile: Int = 1,
    val nextStepTick: Long = 0L,
    /** When true, this projectile passes through entities instead of stopping on its first hit. */
    val pierce: Boolean = false,
    /** Entities already struck by a piercing projectile so it hits each target at most once. */
    val hitEntityIds: Set<String> = emptySet()
)

/**
 * Resource node runtime state on a map tile.
 */
@Serializable
data class MapResourceState(
    val mapId: Int,
    val tileX: Int,
    val tileY: Int,
    val resourceId: Int,
    val currentHealth: Int,
    val depleted: Boolean = false,
    val respawnAtTick: Long = 0L
)

/**
 * Active player Party state.
 */
@Serializable
data class PartyState(
    val partyId: String,
    val leaderCharacterId: String,
    val memberCharacterIds: List<String>,
    val shareXp: Boolean = true
)

/**
 * Persistent Guild document.
 */
@Serializable
data class GuildDocument(
    val id: String,
    val name: String,
    val tag: String,
    val founderCharacterId: String,
    val memberRanks: Map<String, String> = emptyMap(),
    val motd: String = "Welcome to the guild!",
    /** True for the guild that won the last Guild War (see `GuildWarDefinition`); at most one guild holds the area. */
    val holdsGuildArea: Boolean = false,
    /** The shared vault (see `GuildBankDefinition`); members deposit, and the rank in [bankWithdrawRank] and above may withdraw. */
    val bank: Inventory = Inventory(maxSlots = Inventory.DEFAULT_BANK_SLOTS),
    /** The lowest rank that may withdraw; blank means the game's `guildBank.defaultWithdrawRank`. Set by the founder. */
    val bankWithdrawRank: String = "",
    /** The newest vault movements, oldest first, as plain lines (capped by `guildBank.logEntries`). */
    val bankLog: List<String> = emptyList(),
    /** How many of the game's `guildBank.upgrades` the guild has bought. */
    val bankLevel: Int = 0,
    /** What each member has taken out today, by character id (see `guildBank.withdrawLimits`). */
    val bankWithdrawn: Map<String, BankWithdrawal> = emptyMap(),
    /** How many Guild Wars the guild has won or successfully defended (shown as `{guildwins}` in event text). */
    val warWins: Int = 0,
    val schemaVersion: Int = 1
)

/** Items a member has withdrawn from the guild vault during limit day [day] (world tick divided by `limitDayTicks`). */
@Serializable
data class BankWithdrawal(val day: Long, val count: Int)

/**
 * Two-stage player-to-player trade session state.
 */
@Serializable
data class TradeSessionState(
    val sessionId: String,
    val initiatorId: String,
    val partnerId: String,
    val initiatorOffer: List<ItemStack> = emptyList(),
    val partnerOffer: List<ItemStack> = emptyList(),
    val initiatorLocked: Boolean = false,
    val partnerLocked: Boolean = false,
    val initiatorConfirmed: Boolean = false,
    val partnerConfirmed: Boolean = false
)

/**
 * What one character has explored, per map id (see `Exploration`). Stored apart from [CharacterState] so that walking does
 * not change the character document or its sync packets; the document id is the character id.
 */
@Serializable
data class ExplorationDocument(
    val id: String,
    val maps: Map<Int, com.morgana.domain.explore.ExploredMap> = emptyMap(),
    val schemaVersion: Int = 1
)

/**
 * Asynchronous player Mail message (with optional item attachment).
 */
@Serializable
data class MailMessageDocument(
    val id: String,
    val senderName: String,
    val recipientCharacterId: String,
    val subject: String,
    val body: String,
    val attachedItem: ItemStack? = null,
    val read: Boolean = false,
    val claimed: Boolean = false,
    val sentAtTick: Long = 0L,
    val schemaVersion: Int = 1
)

/**
 * Offline Trade / Auction House market listing.
 */
@Serializable
data class MarketListingDocument(
    val id: String,
    val sellerCharacterId: String,
    val sellerName: String,
    val item: ItemStack,
    val priceCurrencyAmount: Int,
    val createdAtTick: Long = 0L,
    val schemaVersion: Int = 1
)

