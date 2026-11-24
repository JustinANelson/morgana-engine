package com.morgana.domain.game

import com.morgana.domain.combat.CombatModule
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.LootRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a game declares about itself instead of the engine hard-wiring it: its attributes (stats), its vitals (HP-like
 * pools), how fast a character levels up, and the formulas for combat. Loaded from `game.json`; see `docs/GAME_DEFINITION.md`.
 *
 * Every formula is a plain string in the small integer-only expression language of [Formula]. Nothing here uses floating point.
 */
@Serializable
data class GameDefinition(
    val schemaVersion: Int = SCHEMA_VERSION,
    val engineApi: String = EngineApiVersion.CURRENT.toString(),
    val id: String,
    val name: String,
    /** Short summary of the realm/game, used by the New Realm Wizard and the realm directory. */
    val description: String = "",
    /** Discovery tags for the realm directory (e.g. `"Farming"`, `"Co-op"`). */
    val tags: List<String> = emptyList(),
    val capabilities: Set<String>? = null,
    val attributes: List<AttributeDefinition> = emptyList(),
    val vitals: List<VitalDefinition>,
    val progression: ProgressionDefinition,
    val combat: CombatDefinition,
    /** The game's calendar and optional day and night cycle; null means the game has no clock. */
    val clock: ClockDefinition? = null,
    /** Weather zones. Null keeps the older behaviour where each map carries its own fixed weather. */
    val weather: WeatherDefinition? = null,
    /** Which HUD controls the client shows; null means the default [UiProfile] (everything on). */
    val ui: UiProfile? = null,
    /** Opt-in character/NPC sprite sheet geometry & animation states; null keeps classic `32 x 32`, `3 x 4` sheets. */
    val sprites: SpriteProfile? = null,
    /** How many slots a character's bag has (8..200). Existing characters' bags grow to this at login, never shrink. */
    val inventorySlots: Int = Inventory.DEFAULT_MAX_SLOTS,
    /** Rarities and affixes that dropped gear rolls (Diablo-style loot); null means items never roll. See [LootRules]. */
    val loot: LootRules? = null,
    /** Optional tile-leap jump mechanic; null or `enabled = false` disables jumping. */
    val jump: JumpDefinition? = null,
    /** Optional sprint: a faster walking step that drains stamina; null or `enabled = false` disables it. */
    val sprint: SprintDefinition? = null,
    /** Optional towns that a town-gate item (`ItemDefinition.townGate`) can take a character to; null disables those items. */
    val townGate: TownGateDefinition? = null,
    /** Optional per-weapon-type proficiency progression; null or `enabled = false` disables proficiencies. */
    val weaponProficiency: WeaponProficiencyConfig? = null,
    /** Optional donation-ranked nobility; null disables it. See [NobilityDefinition]. */
    val nobility: NobilityDefinition? = null,
    /** Optional Battle Power summary number; null disables it. See [BattlePowerDefinition]. */
    val battlePower: BattlePowerDefinition? = null,
    /** Optional gear wear and repair; null means gear never loses durability. See [DurabilityDefinition]. */
    val durability: DurabilityDefinition? = null,
    /** Optional rebirth system; null disables it. See [RebirthDefinition]. */
    val rebirth: RebirthDefinition? = null,
    /** Optional paragon track: XP earned at the level cap keeps paying out bonus attribute points. See [ParagonDefinition]. */
    val paragon: ParagonDefinition? = null,
    /** Optional rested XP and catch-up bonus; null means kills always pay base XP. See [RestedDefinition]. */
    val rested: RestedDefinition? = null,
    /** Optional gear sockets, gem slotting and upgrades (the `upgrade_gear` event step); null disables them. */
    val gearUpgrade: GearUpgradeDefinition? = null,
    /** Optional kill-charged XP gauge for burst skills; null disables it. See [XpGaugeDefinition]. */
    val xpGauge: XpGaugeDefinition? = null,
    /** Optional class promotion tiers; null disables them. See [PromotionDefinition]. */
    val promotion: PromotionDefinition? = null,
    /** Optional equipment slots; null means the nine classic ones. See [EquipmentSlotDefinition]. */
    val equipmentSlots: List<EquipmentSlotDefinition>? = null,
    /** Optional dual-wielding (a second one-handed weapon in the off-hand slot); null disables it. See [DualWieldDefinition]. */
    val dualWield: DualWieldDefinition? = null,
    /** Optional ghost state on death (walk as a ghost until revived or released); null respawns at once. See [GhostDefinition]. */
    val ghost: GhostDefinition? = null,
    /** Optional new-player tips (shown once per account); null gives the built-in tips. See [com.morgana.domain.onboarding.OnboardingConfig]. */
    val onboarding: com.morgana.domain.onboarding.OnboardingConfig? = null,
    /** Optional shared guild vault, opened at a `GUILD_BANKER` NPC; null means guilds have no bank. See [GuildBankDefinition]. */
    val guildBank: GuildBankDefinition? = null,
    /** Optional Guild War (a timed contest for a guild-held area); null means no war. See [GuildWarDefinition]. */
    val guildWar: GuildWarDefinition? = null,
    /** Per-monster kill counts and the Bestiary window; null means no bestiary. See [BestiaryDefinition]. */
    val bestiary: BestiaryDefinition? = null,
    /** Arena matchmaking and instanced team fights; null means no arena queue. See [ArenaDefinition]. */
    val arena: ArenaDefinition? = null,
    /** Optional player stalls (`/stall`); null means none. See [StallDefinition]. */
    val stall: StallDefinition? = null,
    /** Optional dungeon run lifecycle rules (timers, custom seeds, extraction/ephemeral runs, permadeath). See [RunRulesDefinition]. */
    val runs: RunRulesDefinition? = null,
    /** Optional roguelike boons / relics offered during runs (`/boon`). See [BoonDefinition]. */
    val boons: List<BoonDefinition> = emptyList(),
    /** Optional class skill / talent tree (`/skills`). See [SkillTreeDefinition]. */
    val skillTree: SkillTreeDefinition? = null,
    /** Wall-clock events (a weekly reset, a daily bonus, a launch) run by the `scheduler` system. See [ScheduleDefinition]. */
    val schedules: List<ScheduleDefinition> = emptyList(),
    /** Gameplay numbers a game may change without engine code (harvest cooldown, ranges, `+` level values, party size...). See [GameplayTuning]. */
    val tuning: GameplayTuning? = null,
    /** A small standing bonus per class id (maximum vitals, damage, armour, healing, movement speed). See [ClassPassive]. */
    val classPassives: Map<Int, ClassPassive> = emptyMap(),
    /** The kinds of thing that can be placed in the world and stay there (walls, chests, totems). See [WorldObjectsDefinition]. */
    val worldObjects: WorldObjectsDefinition? = null,
    /** Claiming land: who may build where, claim spacing and size, safe zones. Needs `worldObjects`. See [TerritoryDefinition]. */
    val territory: TerritoryDefinition? = null
) {
    /** The passive of class [classId], or none. */
    fun passiveOf(classId: Int): ClassPassive = classPassives[classId] ?: ClassPassive.NONE

    /** The equipment slots this game has, in display order: [equipmentSlots] or the classic nine. */
    val slots: List<EquipmentSlotDefinition>
        get() = equipmentSlots ?: EquipmentSlotDefinition.CLASSIC

    /** The game's [tuning], or the defaults when it has none. */
    val gameplayTuning: GameplayTuning
        get() = tuning ?: GameplayTuning()

    /** How worn gear counts in this game: broken-gear percentage, unarmed damage and the value of `+` levels. See [com.morgana.domain.item.GearRules]. */
    val gearRules: com.morgana.domain.item.GearRules
        get() {
            val t = gameplayTuning
            return com.morgana.domain.item.GearRules(
                brokenPercent = brokenGearStatPercent, unarmedDamage = t.unarmedDamage, plusWeaponDamage = t.plusWeaponDamage,
                plusArmorDefense = t.plusArmorDefense, plusRingHp = t.plusRingHp, plusPercentPerLevel = t.plusPercentPerLevel
            )
        }

    /** The percent of its bonuses a broken piece of gear still gives in this game (0 when durability is off or not configured). */
    val brokenGearStatPercent: Int
        get() = durability?.takeIf { it.enabled }?.brokenStatPercent?.coerceIn(0, 100) ?: 0

    /** True when [slot] is one of this game's equipment slots. */
    fun hasSlot(slot: com.morgana.domain.item.EquipmentSlot): Boolean = slots.any { it.id == slot.id }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Largest effective attribute value when a definition sets no `cap`; keeps every formula in range. */
        const val ATTRIBUTE_HARD_CAP = 1_000_000

        /** Names that formulas already use, so no attribute, vital or `name =` step may take them. */
        val RESERVED_NAMES: Set<String> = setOf(
            "level", "bonus", "a", "d", "weapon", "armor", "ammo", "crit", "basePower", "scales", "heals",
            "min", "max", "clamp", "abs", "mulDiv", "pow", "isqrt"
        )

        val ID_PATTERN: Regex = Regex("[a-z][a-z0-9_]*")
        val COLOR_PATTERN: Regex = Regex("#[0-9a-fA-F]{6}")
    }
}

/** The guild bank: a vault of [com.morgana.domain.item.Inventory.DEFAULT_BANK_SLOTS] slots shared by a guild's members. */
@Serializable
data class GuildBankDefinition(
    /** Kill-switch: false disables the bank without removing the section. */
    val enabled: Boolean = true,
    /** The lowest guild rank that may withdraw until the founder changes it: `Founder`, `Officer` or `Member`. */
    val defaultWithdrawRank: String = "Officer",
    /** How many of the newest vault movements the guild keeps. */
    val logEntries: Int = 40,
    /** How close (in tiles) a member must stand to a `GUILD_BANKER` NPC to use the vault. */
    val reachTiles: Int = 4,
    /** Slots per vault tab. The vault is shown (and deposited into) one tab at a time; its size grows with [upgrades]. */
    val tabSlots: Int = 24,
    /** Items a rank may withdraw per day, by rank name (`Officer`, `Member`); a rank that is absent has no limit. */
    val withdrawLimits: Map<String, Int> = emptyMap(),
    /** Length of the withdrawal-limit day, in world ticks (1,728,000 is 24 hours at 20 ticks a second). */
    val limitDayTicks: Long = 1_728_000L,
    /** The item that counts as the guild's Marks: its vault total is the balance and pays for [upgrades]. */
    val currencyItemId: Int = 1,
    /** Vault upgrades in order: the founder pays [GuildBankUpgrade.cost] from the vault's own Marks to add slots. */
    val upgrades: List<GuildBankUpgrade> = emptyList()
)

/** One guild vault upgrade: [cost] of the vault's Marks buys [extraSlots] more slots. */
@Serializable
data class GuildBankUpgrade(val cost: Int, val extraSlots: Int)

/** One town a Town Portal item can land in. */
@Serializable
data class TownGateTown(val name: String, val mapId: Int, val x: Int, val y: Int)

/**
 * Town Portal: an item with `townGate = true` takes the user to the nearest of [towns], measured in map hops over
 * seamless borders and warp tiles from the map they stand on (see `TownGates`). A character on a map in
 * [disabledMapIds] (a guild-war arena, a jail) cannot use it. When no town is reachable the first one is used.
 */
@Serializable
data class TownGateDefinition(
    val enabled: Boolean = true,
    val towns: List<TownGateTown> = emptyList(),
    val disabledMapIds: Set<Int> = emptySet(),
    /** When true, using a town portal inside an instanced dungeon leaves a two-way return portal (use `/return` in town). */
    val leaveReturnPortal: Boolean = false
)

/**
 * Sprint (free pixel movement only): holding the sprint key lengthens each step to [speedPercent] of a normal step
 * while stamina lasts, draining one stamina point every [stepsPerStamina] ticks of sprinting. The server enforces
 * it: a longer step than a normal one is only accepted while the character has stamina, and is otherwise cut back
 * to a normal step. A game without this section keeps the old, looser step limit.
 */
@Serializable
data class SprintDefinition(
    val enabled: Boolean = true,
    /** Sprint step length as a percentage of a normal step (100 = no gain). */
    val speedPercent: Int = 150,
    /** Vital ID used as stamina. */
    val staminaVitalId: String = "stamina",
    /** One stamina point is spent every this many ticks of sprinting (1 = every step). */
    val stepsPerStamina: Int = 2
)

@Serializable
data class JumpDefinition(
    /** Easy kill-switch: set false in `game.json` to disable jumping server- and client-side. */
    val enabled: Boolean = true,
    /** Maximum Chebyshev tile distance per jump. */
    val maxRangeTiles: Int = 8,
    /** Stamina consumed per jump; 0 makes jumping free (no stamina needed). */
    val staminaCost: Int = 15,
    /** Vital ID used as stamina. */
    val staminaVitalId: String = "stamina",
    /** Minimum ticks between jumps. */
    val cooldownTicks: Int = 12,
    /** Maps where jumping is refused (a walled arena, a jail), so a leap cannot cross a wall that must hold. */
    val disabledMapIds: Set<Int> = emptySet()
)

/**
 * Optional per-weapon-type proficiency progression (e.g. Blade, Sword, Club, Bow and Katana).
 * When null or `enabled = false`, weapon proficiencies are inactive.
 */
@Serializable
data class WeaponProficiencyConfig(
    /** Easy kill-switch: set false in `game.json` to disable proficiency tracking and bonuses. */
    val enabled: Boolean = true,
    /** Maximum proficiency level. */
    val maxLevel: Int = 20,
    /** Hits required to advance from level `L` to `L + 1` (`hitsPerLevel * L`). */
    val hitsPerLevel: Int = 25,
    /** Bonus percentage applied to attack damage per proficiency level above 1 (e.g. `5` = +5% per level). */
    val damageBonusPercentPerLevel: Int = 5,
    /** Declared weapon types (e.g. `blade`, `sword`, `club`, `bow`, `katana`). */
    val weaponTypes: List<WeaponTypeDefinition> = emptyList()
)

@Serializable
data class WeaponTypeDefinition(
    val id: String,
    val name: String
)

/** One stat such as strength. Characters hold a base value; the effective value adds equipment and status modifiers. */
@Serializable
data class AttributeDefinition(
    val id: String,
    val name: String,
    /** Short label for the HUD; blank means the upper-cased id. */
    val short: String = "",
    /** Default base value for a new character whose class does not set one. */
    val base: Int = 0,
    /** Minimum effective value (after equipment and status modifiers). */
    val floor: Int = 0,
    /** Maximum effective value; null means [GameDefinition.ATTRIBUTE_HARD_CAP]. */
    val cap: Int? = null,
    /** Whether the player may spend stat points on it. */
    val allocatable: Boolean = false
) {
    val effectiveCap: Int get() = cap ?: GameDefinition.ATTRIBUTE_HARD_CAP
}

/** The one thing a vital can be for the engine: the pool whose reaching zero kills. Exactly one vital has it. */
@Serializable
enum class VitalRole {
    @SerialName("life")
    LIFE
}

/** A pool with a current and a maximum value (health, mana, stamina, ...). */
@Serializable
data class VitalDefinition(
    val id: String,
    val name: String,
    val short: String = "",
    /** HUD bar color as `#rrggbb`. */
    val color: String = "#ffffff",
    val role: VitalRole? = null,
    /** Formula for the maximum. Variables: `level`, every attribute id, and `bonus` (equipment max-bonuses for this vital). */
    val max: String,
    val regen: RegenDefinition? = null
)

@Serializable
data class RegenDefinition(
    /** Game ticks between regeneration steps (20 ticks per second). */
    val everyTicks: Int,
    /** Formula for how much to restore each step. Variables: `level` and every attribute id. */
    val amount: String
)

/** Levelling. Give exactly one of [xpTable] and [xpExpr]. */
@Serializable
data class ProgressionDefinition(
    val maxLevel: Int = 100,
    /** Entry `n` (counting from 0) is the experience needed to go from level `n + 1` to level `n + 2`; `maxLevel - 1` entries. */
    val xpTable: List<Long>? = null,
    /** Formula for the experience needed to leave `level`. Variable: `level`. */
    val xpExpr: String? = null
)

/** Formulas and module selection for combat. See `docs/GAME_DEFINITION.md` for the variables each formula receives. */
@Serializable
data class CombatDefinition(
    val melee: String = "1",
    val ranged: String = "1",
    val spell: String = "1",
    val critChanceBp: String = "0",
    val dodgeChanceBp: String = "0",
    /** Which combat module runs (`realtime`, `noop` / `none`, or `lua_combat` / `lua`). */
    val module: String = CombatModule.DEFAULT_ID,
    /** Ticks between attacks when the equipped weapon (or none) does not set its own `attackSpeedTicks`. */
    val defaultAttackSpeedTicks: Int = 12,
    /** Ticks until a killed monster respawns when its spawn slot does not say (a slot's `respawnTicks` wins; 0 never respawns). */
    val defaultNpcRespawnTicks: Int = 100,
    /** Ticks a monster's drop stays reserved for the player who killed it. */
    val lootOwnerProtectionTicks: Int = 600,
    /** Ticks an item lying on the ground (a drop, a thrown or death-dropped item) lasts before it disappears. */
    val groundItemDespawnTicks: Int = 2400,
    /** Experience bonus, in percent, when a party that shares XP kills together on one map (the total is then split evenly). */
    val partyXpBonusPercent: Int = 20,
    /** When true, monster XP and nameplate colors scale by level difference (Green 20%, White 100% / same-level 200%, Red 150%, Black 200%). */
    val levelOffsetXpScaling: Boolean = false,
    /** Optional spawn-time monster variants (e.g. Feral/aggro, Elite, Swift, Golden) rolled for non-boss monsters. */
    val enemyVariants: List<EnemyVariantDefinition> = emptyList(),
    /** Optional PK points, Red/Black name thresholds, equipped-gear drop risk, decay, and PK Jail rules. */
    val pk: PkRulesDefinition? = null,
    /** How monsters pick fights: a cap on pile-ons, a wind-up before the first blow, and telegraphed spells. */
    val npcs: NpcCombatDefinition = NpcCombatDefinition()
)

/**
 * Pacing rules for monsters. The defaults keep the original behaviour (everything in range attacks at once, spells land
 * instantly), so a game opts in by setting values in `combat.npcs`.
 */
@Serializable
data class NpcCombatDefinition(
    /**
     * Most monsters that may hunt one player at the same time; others in aggro range wait (and wander) until one
     * dies or loses the target. 0 = no limit. Monsters the player has attacked always keep fighting.
     */
    val maxAttackersPerTarget: Int = 0,
    /** Ticks after a monster picks a target before its first melee blow or spell may land (0 = as soon as it arrives). */
    val firstStrikeDelayTicks: Int = 0,
    /**
     * Ticks a monster stands still "casting" before an attack spell lands, when the spell has no `castTimeTicks` of its
     * own. The player sees "Casting <spell>..." and can step out of range to dodge it. 0 = spells land instantly.
     */
    val spellWindUpTicks: Int = 0,
    /** When true, a monster with an attack spell stays at spell range instead of walking into melee while the spell recharges. */
    val casterKeepsDistance: Boolean = false,
    /**
     * Threat table. 0 (the default) keeps the old rule: a monster turns on whoever hit it last. A value of 100 or more
     * turns threat on: every hit adds its damage to the attacker's threat on that monster, and the monster changes
     * target only when someone's threat exceeds its current target's by this percent (110 is the usual "10% more").
     */
    val threatSwitchPercent: Int = 0,
    /** With threat on, a taunt lifts the taunter's threat to this percent of the highest threat on the monster. */
    val tauntThreatPercent: Int = 120,
    /** With threat on, healing an ally a monster is fighting adds this percent of the healing to the healer's threat on that monster. */
    val healThreatPercent: Int = 50
)

/**
 * Optional PK points, Red/Black name, equipped-gear drop risk, and PK Jail rules.
 */
@Serializable
data class PkRulesDefinition(
    /** PK points added when killing an innocent player in OPEN_PVP. */
    val pointsPerKill: Int = 10,
    /** PK points threshold for Red Name status. */
    val redNameThreshold: Int = 30,
    /** PK points threshold for Black Name status. */
    val blackNameThreshold: Int = 100,
    /** Ticks between 1 PK point decaying while online (0 = no decay). */
    val decayIntervalTicks: Int = 600,
    /** Basis points chance (0..10000) per equipped non-bound slot to drop on death when Red Name. */
    val redEquipDropChanceBp: Int = 1500,
    /** Basis points chance (0..10000) per equipped non-bound slot to drop on death when Black Name. */
    val blackEquipDropChanceBp: Int = 5000,
    /** Optional map ID to send Red/Black names to on death (0 = normal respawn). */
    val jailMapId: Int = 0,
    val jailX: Int = 18,
    val jailY: Int = 15
)

/**
 * Spawn-time variant modifier rolled for non-boss hostile/passive monsters.
 */
@Serializable
data class EnemyVariantDefinition(
    val id: String,
    val label: String,
    /** Spawn chance in basis points out of 10000. */
    val spawnBasisPoints: Int = 0,
    /** When true, overrides PASSIVE behavior to ATTACK_ON_SIGHT. */
    val forceAggro: Boolean = false,
    /** HP multiplier in percent (100 = 1.0x). */
    val hpPercent: Int = 100,
    /** Attack damage multiplier in percent (100 = 1.0x). */
    val attackPercent: Int = 100,
    /** Move speed tick multiplier in percent (100 = normal, < 100 = faster). */
    val moveSpeedPercent: Int = 100,
    /** XP reward multiplier in percent (100 = 1.0x). */
    val xpPercent: Int = 100,
    /** Drop chance multiplier in percent (100 = 1.0x). */
    val dropRatePercent: Int = 100,
    /** Extra drop roll passes on death (0 = normal 1 pass). */
    val bonusDropRolls: Int = 0,
    /** Bonus levels added to the base monster's level (minimum +1 for a variant). */
    val levelBonus: Int = 1,
    /** Nameplate color hex used when level-offset colors are not active. */
    val colorHex: String = "FBBF24"
) {
    /** Effective level of a monster spawned with this variant (`baseLevel + levelBonus`, always at least `baseLevel + 1`). */
    fun effectiveLevel(baseLevel: Int): Int = baseLevel + levelBonus.coerceAtLeast(1)
}

/**
 * Which parts of the client HUD a game wants shown (`ui` section in `game.json`).
 * Every switch defaults to `true`, so a game that omits `ui` gets the full Classic HUD unchanged.
 */
@Serializable
data class UiProfile(
    /** Attack button, Spells button/window, and empty hotbar slots. */
    val combat: Boolean = true,
    /** Spell button, Spells window (`K`), and `Spl` touch hotkey. Also treated as false when [combat] is false or the game has no spells. */
    val spells: Boolean = true,
    /** Bottom quickbar (`1..0`) and numbered touch hotkeys (`1..5`). */
    val hotbar: Boolean = true,
    /** Top-right minimap radar (`M`). */
    val minimap: Boolean = true,
    /** Bottom-left chat window. */
    val chat: Boolean = true,
    /** Party button and window (`P`). */
    val party: Boolean = true,
    /** Floating essence/spirit orbs fly from slain enemies to the player and visually accrue XP on arrival. */
    val xpOrbs: Boolean = false
)

/** Logical animation states supported by multi-state character and NPC sprite sheets. */
@Serializable
enum class SpriteState {
    @SerialName("idle")
    IDLE,

    @SerialName("walk")
    WALK,

    @SerialName("attack")
    ATTACK,

    @SerialName("cast")
    CAST,

    @SerialName("harvest")
    HARVEST,

    /** A jump: plays once across the leap (the hop arc). Columns follow [HARVEST]; 0 frames falls back to walk. */
    @SerialName("jump")
    JUMP,

    /** A short flinch when the character takes damage. 0 frames falls back to idle. */
    @SerialName("hurt")
    HURT
}

/** Where a sprite cell attaches to the map tile an entity occupies. */
@Serializable
enum class SpriteAnchor {
    @SerialName("bottom_center")
    BOTTOM_CENTER
}

/**
 * Opt-in character/NPC sprite cell dimensions and animation layout (`sprites` section in `game.json`).
 *
 * When omitted (`GameDefinition.sprites == null`), every sprite uses classic `32 x 32` cells with `3 x 4` sheets.
 * When present, sheets are sliced into `totalFrames` columns x `4` direction rows (`DOWN, LEFT, RIGHT, UP`) of size
 * `[cellWidth] x [cellHeight]` and drawn anchored bottom-center at the entity's feet so taller characters extend upward.
 */
@Serializable
data class SpriteProfile(
    val cellWidth: Int = CLASSIC_CELL_SIZE,
    val cellHeight: Int = CLASSIC_CELL_SIZE,
    /** Frames per direction row for each state, laid out left-to-right in [SpriteState.entries] order. */
    val stateFrames: Map<SpriteState, Int> = DEFAULT_STATE_FRAMES,
    val anchor: SpriteAnchor = SpriteAnchor.BOTTOM_CENTER,
    /** Enables slot-based directional, tinted, and composited paperdoll layers when true. */
    val paperdollingV2: Boolean = false,
    /**
     * Draw scale per sprite id (default 1). A character whose art is drawn small in its cell can be shown larger
     * without redrawing the sheet: `{"3": 2}` draws sprite 3 at twice the cell size, feet still on the tile, with
     * name plates and bars following its height. Use whole numbers to keep pixels even.
     */
    val scales: Map<Int, Float> = emptyMap(),
    /**
     * Visible height in cell pixels per sprite id, for sprites whose art is shorter than the cell (a 32 px enemy in a
     * 48 px cell). Name plates, bars, chat bubbles and damage text rise to this height instead of the whole cell, so
     * short sprites do not float their labels above empty space. Default: the cell height.
     */
    val uiHeights: Map<Int, Int> = emptyMap()
) {
    /** Draw scale of [spriteId]: its entry in [scales], or 1. */
    fun scaleOf(spriteId: Int): Float = scales[spriteId] ?: 1f

    /** Pixels from the feet to where world labels for [spriteId] start, as drawn (visible height times scale). */
    fun uiHeightOf(spriteId: Int): Float = (uiHeights[spriteId] ?: cellHeight) * scaleOf(spriteId)

    /** Total columns in each of the 4 direction rows. */
    val totalFrames: Int
        get() = SpriteState.entries.sumOf { (stateFrames[it] ?: 0).coerceAtLeast(0) }.coerceAtLeast(1)

    /** Expected PNG width in pixels: `cellWidth * totalFrames`. */
    val sheetWidth: Int get() = cellWidth * totalFrames

    /** Expected PNG height in pixels: `cellHeight * DIRECTIONS`. */
    val sheetHeight: Int get() = cellHeight * DIRECTIONS

    /**
     * Sheet column range `[start..endInclusive]` for [state].
     * When [state] has 0 configured frames, falls back to [SpriteState.WALK] (frame 0 for idle, full walk for unconfigured states);
     * [SpriteState.HURT] falls back to the idle frames instead, so a character without a flinch simply stands.
     */
    fun columnRange(state: SpriteState): IntRange {
        var cursor = 0
        var walkRange: IntRange? = null
        for (entry in SpriteState.entries) {
            val count = (stateFrames[entry] ?: 0).coerceAtLeast(0)
            val range = if (count > 0) cursor until (cursor + count) else null
            if (entry == SpriteState.WALK) walkRange = range
            if (entry == state && range != null) return range
            cursor += count
        }
        val fallbackWalk = walkRange ?: 0..0
        if (state == SpriteState.HURT) {
            val idleCount = (stateFrames[SpriteState.IDLE] ?: 0).coerceAtLeast(0)
            if (idleCount > 0) return 0 until idleCount
        }
        return if (state == SpriteState.IDLE || state == SpriteState.HURT) fallbackWalk.first..fallbackWalk.first else fallbackWalk
    }

    companion object {
        const val CLASSIC_CELL_SIZE = 32
        const val CLASSIC_WALK_FRAMES = 3
        const val DIRECTIONS = 4
        const val MIN_CELL = 8
        const val MAX_CELL = 256
        const val MAX_FRAMES_PER_STATE = 16
        const val MAX_SHEET_EDGE = 4096
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 8f

        val DEFAULT_STATE_FRAMES: Map<SpriteState, Int> = mapOf(SpriteState.WALK to CLASSIC_WALK_FRAMES)
        val CLASSIC = SpriteProfile()
        val MODERN_TALL = SpriteProfile(
            cellWidth = 32,
            cellHeight = 64,
            stateFrames = mapOf(
                SpriteState.IDLE to 1,
                SpriteState.WALK to 3,
                SpriteState.ATTACK to 3,
                SpriteState.CAST to 3,
                SpriteState.HARVEST to 3
            ),
            anchor = SpriteAnchor.BOTTOM_CENTER
        )
    }
}
