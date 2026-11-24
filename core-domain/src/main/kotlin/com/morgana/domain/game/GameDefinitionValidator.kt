package com.morgana.domain.game

import com.morgana.domain.onboarding.OnboardingConfig
import com.morgana.domain.onboarding.OnboardingDefaults
import com.morgana.domain.onboarding.TipDefinition
import com.morgana.domain.onboarding.TipTrigger
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.LootRules
import com.morgana.domain.script.ScriptCapability

/** One problem in a game definition: [pointer] is a JSON pointer into `game.json` (for example `/vitals/0/max`). */
data class GameIssue(val pointer: String, val message: String) {
    override fun toString(): String = "$pointer: $message"
}

/**
 * Checks a [GameDefinition] the way `ContentValidator` checks content: pure, deterministic, and reporting every problem it can find
 * rather than stopping at the first. Rules are listed in `docs/GAME_DEFINITION.md`.
 *
 * Besides structure and names, every formula is parsed, checked against the variables its slot provides, and **trial-evaluated across
 * the extremes of its inputs**, so an overflow or a division by zero is found when the game loads, not in the middle of a fight.
 */
object GameDefinitionValidator {
    const val MAX_ATTRIBUTES = 32
    const val MAX_VITALS = 8
    const val MAX_LEVEL_LIMIT = 1000

    /** Ranges used when trial-evaluating formulas (inclusive). Attributes use their own floor and cap, `level` uses 1..maxLevel. */
    private const val MAX_ITEM_VALUE = 100_000L
    private const val MAX_BONUS = 1_000_000L

    /** Beyond this many input combinations a formula is sampled at the extremes only. */
    private const val MAX_COMBINATIONS = 20_000

    fun validate(definition: GameDefinition): List<GameIssue> {
        val issues = mutableListOf<GameIssue>()
        fun error(pointer: String, message: String) { issues += GameIssue(pointer, message) }

        if (definition.schemaVersion != GameDefinition.SCHEMA_VERSION) {
            error("/schemaVersion", "is ${definition.schemaVersion}; this build reads ${GameDefinition.SCHEMA_VERSION}")
        }
        if (!EngineApiVersion.isCompatible(definition.engineApi)) {
            error("/engineApi", "requires engine API '${definition.engineApi}', but this engine supports API 1..${EngineApiVersion.CURRENT}")
        }
        if (!GAME_ID.matches(definition.id)) error("/id", "must be lowercase letters, digits, '_' or '-' and start with a letter")
        if (definition.name.isBlank() || definition.name.length > 60) error("/name", "must be 1 to 60 characters")
        if (definition.description.length > 400) error("/description", "must be at most 400 characters")
        if (definition.tags.size > 16) error("/tags", "must list at most 16 tags")
        if (definition.inventorySlots !in 8..Inventory.MAX_SLOTS_LIMIT) error("/inventorySlots", "must be 8 to ${Inventory.MAX_SLOTS_LIMIT} (got ${definition.inventorySlots})")
        definition.tags.forEachIndexed { i, tag ->
            if (tag.isBlank() || tag.length > 24) error("/tags/$i", "must be 1 to 24 characters")
        }
        if (definition.combat.module.lowercase() !in com.morgana.domain.combat.CombatModule.VALID_IDS) {
            error("/combat/module", "unknown combat module '${definition.combat.module}'; valid: ${com.morgana.domain.combat.CombatModule.VALID_IDS.sorted().joinToString()}")
        }
        definition.combat.let { c ->
            if (c.defaultAttackSpeedTicks !in 1..1_200) error("/combat/defaultAttackSpeedTicks", "must be 1 to 1200 (got ${c.defaultAttackSpeedTicks})")
            if (c.defaultNpcRespawnTicks !in 0..1_000_000) error("/combat/defaultNpcRespawnTicks", "must be 0 to 1000000 (got ${c.defaultNpcRespawnTicks})")
            if (c.lootOwnerProtectionTicks !in 0..1_000_000) error("/combat/lootOwnerProtectionTicks", "must be 0 to 1000000 (got ${c.lootOwnerProtectionTicks})")
            if (c.groundItemDespawnTicks !in 1..1_000_000) error("/combat/groundItemDespawnTicks", "must be 1 to 1000000 (got ${c.groundItemDespawnTicks})")
            if (c.partyXpBonusPercent !in 0..1_000) error("/combat/partyXpBonusPercent", "must be 0 to 1000 (got ${c.partyXpBonusPercent})")
        }
        definition.capabilities?.let { caps ->
            caps.forEach { cap ->
                if (cap !in ScriptCapability.ALL) {
                    error("/capabilities", "unknown script capability '$cap'; valid capabilities are: ${ScriptCapability.ALL.sorted().joinToString()}")
                }
            }
        }

        checkClock(definition, ::error)
        checkSchedules(definition, ::error)
        checkTuning(definition, ::error)
        checkClassPassives(definition, ::error)
        checkWeather(definition, ::error)
        checkSprites(definition, ::error)
        checkOnboarding(definition, ::error)
        checkAttributes(definition, ::error)
        checkVitals(definition, ::error)
        val maxLevel = checkProgression(definition, ::error)
        checkFormulas(definition, maxLevel, ::error)
        checkLoot(definition, ::error)
        checkWeaponProficiency(definition, ::error)
        checkNobilityAndBattlePower(definition, ::error)
        definition.gearUpgrade?.let { g ->
            if (g.maxSockets !in 1..6) error("/gearUpgrade/maxSockets", "must be 1 to 6 (got ${g.maxSockets})")
            g.quality?.let { q ->
                val rarityIds = definition.loot?.rarities.orEmpty().map { it.id }.toSet()
                if (q.steps.isEmpty()) error("/gearUpgrade/quality/steps", "needs at least one step")
                q.steps.forEachIndexed { i, st ->
                    if (st.rarityId !in rarityIds) error("/gearUpgrade/quality/steps/$i/rarityId", "must be a rarity of loot.rarities (got \"${st.rarityId}\")")
                    if (st.chanceBasisPoints !in 1..10_000) error("/gearUpgrade/quality/steps/$i/chanceBasisPoints", "must be 1 to 10000 (got ${st.chanceBasisPoints})")
                    if (st.materialAmount !in 1..9_999) error("/gearUpgrade/quality/steps/$i/materialAmount", "must be 1 to 9999 (got ${st.materialAmount})")
                }
                if (q.steps.map { it.rarityId }.toSet().size != q.steps.size) error("/gearUpgrade/quality/steps", "each rarity may appear once")
                if (q.pityBonusBasisPointsPerStack !in 0..10_000) error("/gearUpgrade/quality/pityBonusBasisPointsPerStack", "must be 0 to 10000")
                if (q.maxPityStacks !in 0..1_000) error("/gearUpgrade/quality/maxPityStacks", "must be 0 to 1000")
                if (q.pityGainPerFailure !in 0..100) error("/gearUpgrade/quality/pityGainPerFailure", "must be 0 to 100")
                if (q.socketChanceBasisPoints !in 0..10_000) error("/gearUpgrade/quality/socketChanceBasisPoints", "must be 0 to 10000")
            }
            g.enhancement?.let { e ->
                val maxPlus = com.morgana.domain.item.ItemStack.MAX_PLUS
                if (e.tiers.isEmpty() || e.tiers.size > maxPlus) error("/gearUpgrade/enhancement/tiers", "must hold 1 to $maxPlus tiers (got ${e.tiers.size})")
                e.tiers.forEachIndexed { i, t ->
                    if (t.plus != i + 1) error("/gearUpgrade/enhancement/tiers/$i/plus", "must be ${i + 1}: tiers run +1, +2, +3 with no gaps (got ${t.plus})")
                    if (t.chanceBasisPoints !in 1..10_000) error("/gearUpgrade/enhancement/tiers/$i/chanceBasisPoints", "must be 1 to 10000 (got ${t.chanceBasisPoints})")
                    if (t.materialItemId < 1) error("/gearUpgrade/enhancement/tiers/$i/materialItemId", "must name an item")
                    if (t.materialAmount !in 1..9_999) error("/gearUpgrade/enhancement/tiers/$i/materialAmount", "must be 1 to 9999 (got ${t.materialAmount})")
                }
                if (e.pityBonusBasisPointsPerStack !in 0..10_000) error("/gearUpgrade/enhancement/pityBonusBasisPointsPerStack", "must be 0 to 10000")
                if (e.maxPityStacks !in 0..1_000) error("/gearUpgrade/enhancement/maxPityStacks", "must be 0 to 1000")
                if (e.pityGainPerFailure !in 0..100) error("/gearUpgrade/enhancement/pityGainPerFailure", "must be 0 to 100")
                if (e.failDurabilityLoss !in 0..100) error("/gearUpgrade/enhancement/failDurabilityLoss", "must be 0 to 100")
                if (e.dropPlusOnFailFrom !in 0..maxPlus) error("/gearUpgrade/enhancement/dropPlusOnFailFrom", "must be 0 (never) or a + level up to $maxPlus")
            }
        }
        definition.runs?.let { runs ->
            if (runs.timeLimitTicks !in 0..1_000_000) error("/runs/timeLimitTicks", "must be 0 to 1000000 (got ${runs.timeLimitTicks})")
            runs.metaCurrencyItemIds.forEach { itemId ->
                if (itemId < 1) error("/runs/metaCurrencyItemIds", "item ids must be >= 1 (got $itemId)")
            }
        }
        checkBoonsAndSkillTree(definition, ::error)
        definition.rebirth?.let { r ->
            if (!GameDefinition.ID_PATTERN.matches(r.countVariable)) error("/rebirth/countVariable", "must be lowercase letters, digits or '_' and start with a letter")
            val maxLevel = definition.progression.maxLevel
            if (r.minLevel !in 2..maxLevel) error("/rebirth/minLevel", "must be 2 to the maximum level $maxLevel (got ${r.minLevel})")
            if (r.resetLevel !in 1 until r.minLevel.coerceAtLeast(2)) error("/rebirth/resetLevel", "must be 1 to below minLevel (got ${r.resetLevel})")
            if (r.maxRebirths !in 1..20) error("/rebirth/maxRebirths", "must be 1 to 20 (got ${r.maxRebirths})")
            if (r.bonusStatPointsPerRebirth !in 0..10_000) error("/rebirth/bonusStatPointsPerRebirth", "must be 0 to 10000 (got ${r.bonusStatPointsPerRebirth})")
            r.grantSpellIds.keys.filter { it !in 1..r.maxRebirths }.forEach { error("/rebirth/grantSpellIds/$it", "rebirth number must be 1 to ${r.maxRebirths}") }
        }
        definition.townGate?.let { t ->
            if (t.towns.isEmpty()) error("/townGate/towns", "list at least one town")
            t.towns.forEachIndexed { i, town ->
                if (town.name.isBlank()) error("/townGate/towns/$i/name", "must not be blank")
                if (town.mapId < 1) error("/townGate/towns/$i/mapId", "must be a map id of 1 or more (got ${town.mapId})")
                if (town.x < 0 || town.y < 0) error("/townGate/towns/$i/x", "coordinates must be 0 or more")
            }
            if (t.towns.map { it.mapId }.distinct().size != t.towns.size) error("/townGate/towns", "two towns share a map")
        }
        definition.sprint?.let { sp ->
            if (sp.speedPercent !in 100..200) error("/sprint/speedPercent", "must be 100 to 200 (got ${sp.speedPercent})")
            if (sp.stepsPerStamina !in 1..100) error("/sprint/stepsPerStamina", "must be 1 to 100 (got ${sp.stepsPerStamina})")
            if (definition.vitals.none { it.id == sp.staminaVitalId }) error("/sprint/staminaVitalId", "no vital named '${sp.staminaVitalId}'")
        }
        definition.paragon?.let { g ->
            if (!GameDefinition.ID_PATTERN.matches(g.variable)) error("/paragon/variable", "must be lowercase letters, digits or '_' and start with a letter")
            if (g.xpBase !in 1L..1_000_000_000L) error("/paragon/xpBase", "must be 1 to 1000000000 (got ${g.xpBase})")
            if (g.xpStep !in 0L..1_000_000_000L) error("/paragon/xpStep", "must be 0 to 1000000000 (got ${g.xpStep})")
            if (g.statPointsPerLevel !in 0..1_000) error("/paragon/statPointsPerLevel", "must be 0 to 1000 (got ${g.statPointsPerLevel})")
            if (g.maxLevels !in 1..10_000) error("/paragon/maxLevels", "must be 1 to 10000 (got ${g.maxLevels})")
        }
        definition.durability?.let { d ->
            if (d.weaponWearChancePercent !in 0..100) error("/durability/weaponWearChancePercent", "must be 0 to 100 (got ${d.weaponWearChancePercent})")
            if (d.armorWearChancePercent !in 0..100) error("/durability/armorWearChancePercent", "must be 0 to 100 (got ${d.armorWearChancePercent})")
            if (d.wearPerHit !in 1..100) error("/durability/wearPerHit", "must be 1 to 100 (got ${d.wearPerHit})")
            if (d.repairPricePercent !in 0..10_000) error("/durability/repairPricePercent", "must be 0 to 10000 (got ${d.repairPricePercent})")
            if (d.brokenStatPercent !in 0..100) error("/durability/brokenStatPercent", "must be 0 to 100 (got ${d.brokenStatPercent})")
        }
        definition.xpGauge?.let { g ->
            if (definition.vitals.none { it.id == g.vitalId }) error("/xpGauge/vitalId", "no vital with id \"${g.vitalId}\"")
            if (g.gainPerKill !in 0..10_000) error("/xpGauge/gainPerKill", "must be 0 to 10000 (got ${g.gainPerKill})")
        }
        definition.equipmentSlots?.let { slots ->
            if (slots.isEmpty() || slots.size > 32) error("/equipmentSlots", "needs 1 to 32 slots (got ${slots.size})")
            val seen = mutableSetOf<String>()
            slots.forEachIndexed { i, sl ->
                if (!EquipmentSlot.ID_PATTERN.matches(sl.id)) error("/equipmentSlots/$i/id", "must be upper-case letters, digits and underscores, starting with a letter (got \"${sl.id}\")")
                if (!seen.add(sl.id)) error("/equipmentSlots/$i/id", "duplicate slot id \"${sl.id}\"")
                if (sl.name.isBlank()) error("/equipmentSlots/$i/name", "must not be blank")
            }
            if (EquipmentSlot.WEAPON.id !in seen) error("/equipmentSlots", "must include the WEAPON slot (combat reads it)")
        }
        definition.stall?.let { s ->
            if (s.maxListings !in 1..100) error("/stall/maxListings", "must be 1 to 100 (got ${s.maxListings})")
            if (s.rangeTiles !in 1..50) error("/stall/rangeTiles", "must be 1 to 50 (got ${s.rangeTiles})")
            if (s.currencyItemId < 1) error("/stall/currencyItemId", "must be an item id (got ${s.currencyItemId})")
        }
        definition.arena?.let { a ->
            if (a.teamSizes.isEmpty() || a.teamSizes.any { it !in 1..10 }) error("/arena/teamSizes", "needs at least one size, each 1 to 10 (got ${a.teamSizes})")
            val biggest = a.teamSizes.maxOrNull() ?: 1
            if (a.teamASpawns.size < biggest) error("/arena/teamASpawns", "needs a spot for each member of the biggest team ($biggest), has ${a.teamASpawns.size}")
            if (a.teamBSpawns.size < biggest) error("/arena/teamBSpawns", "needs a spot for each member of the biggest team ($biggest), has ${a.teamBSpawns.size}")
            if (a.countdownTicks !in 0..6000) error("/arena/countdownTicks", "must be 0 to 6000 (got ${a.countdownTicks})")
            if (a.matchTicks !in 100..100_000) error("/arena/matchTicks", "must be 100 to 100000 (got ${a.matchTicks})")
            if (a.ratingK !in 1..200) error("/arena/ratingK", "must be 1 to 200 (got ${a.ratingK})")
            if (a.maxRatingGap < 0 || a.gapGrowth < 0 || a.gapGrowthTicks < 1) error("/arena/maxRatingGap", "the rating gap settings must not be negative, and gapGrowthTicks must be at least 1")
            if ((a.winnerRewards + a.loserRewards).any { it.value < 1 }) error("/arena/winnerRewards", "every reward amount must be at least 1")
        }
        definition.combat.npcs.let { n ->
            if (n.threatSwitchPercent != 0 && n.threatSwitchPercent !in 100..1000) error("/combat/npcs/threatSwitchPercent", "must be 0 (off) or 100 to 1000 (got ${n.threatSwitchPercent})")
            if (n.tauntThreatPercent !in 100..1000) error("/combat/npcs/tauntThreatPercent", "must be 100 to 1000 (got ${n.tauntThreatPercent})")
            if (n.healThreatPercent !in 0..1000) error("/combat/npcs/healThreatPercent", "must be 0 to 1000 (got ${n.healThreatPercent})")
        }
        definition.bestiary?.let { b ->
            if (b.statsAfterKills !in 1..10_000) error("/bestiary/statsAfterKills", "must be 1 to 10000 (got ${b.statsAfterKills})")
            if (b.dropsAfterKills !in 1..10_000) error("/bestiary/dropsAfterKills", "must be 1 to 10000 (got ${b.dropsAfterKills})")
        }
        definition.guildWar?.let { w ->
            if (w.durationTicks !in 1..10_000_000) error("/guildWar/durationTicks", "must be 1 to 10000000 (got ${w.durationTicks})")
            if (w.intervalTicks != 0 && w.intervalTicks <= w.durationTicks) error("/guildWar/intervalTicks", "must be 0 (staff start only) or longer than durationTicks (got ${w.intervalTicks})")
            if (w.minDamage < 1) error("/guildWar/minDamage", "must be at least 1 (got ${w.minDamage})")
            if (w.rewardItems.any { it.value < 1 }) error("/guildWar/rewardItems", "every amount must be at least 1")
        }
        definition.guildBank?.let { b ->
            if (b.defaultWithdrawRank !in listOf("Founder", "Officer", "Member")) error("/guildBank/defaultWithdrawRank", "must be Founder, Officer or Member (got ${b.defaultWithdrawRank})")
            if (b.logEntries !in 1..500) error("/guildBank/logEntries", "must be 1 to 500 (got ${b.logEntries})")
            if (b.tabSlots !in 1..200) error("/guildBank/tabSlots", "must be 1 to 200 (got ${b.tabSlots})")
            if (b.limitDayTicks !in 1..100_000_000L) error("/guildBank/limitDayTicks", "must be 1 to 100000000 (got ${b.limitDayTicks})")
            for ((rank, limit) in b.withdrawLimits) {
                if (rank !in listOf("Officer", "Member")) error("/guildBank/withdrawLimits", "rank must be Officer or Member (got $rank); the founder has no limit")
                if (limit < 1) error("/guildBank/withdrawLimits/$rank", "must be at least 1 (got $limit)")
            }
            if (b.upgrades.any { it.cost < 0 || it.extraSlots < 1 }) error("/guildBank/upgrades", "every upgrade needs a cost of 0 or more and at least 1 extra slot")
            if (com.morgana.domain.item.Inventory.DEFAULT_BANK_SLOTS + b.upgrades.sumOf { it.extraSlots } > com.morgana.domain.item.Inventory.MAX_SLOTS_LIMIT) {
                error("/guildBank/upgrades", "the vault cannot grow past ${com.morgana.domain.item.Inventory.MAX_SLOTS_LIMIT} slots")
            }
            if (b.reachTiles !in 1..20) error("/guildBank/reachTiles", "must be 1 to 20 (got ${b.reachTiles})")
        }
        definition.ghost?.let { g ->
            if (g.autoReleaseTicks !in 0..1_000_000) error("/ghost/autoReleaseTicks", "must be 0 to 1000000 (got ${g.autoReleaseTicks})")
        }
        definition.ghost?.let { g ->
            if (g.deathWearPoints !in 0..100) error("/ghost/deathWearPoints", "must be 0 to 100 (got ${g.deathWearPoints})")
            if (g.selfReviveAfterTicks !in 0..1_000_000) error("/ghost/selfReviveAfterTicks", "must be 0 to 1000000 (got ${g.selfReviveAfterTicks})")
            if (g.selfRevivePercent !in 1..100) error("/ghost/selfRevivePercent", "must be 1 to 100 (got ${g.selfRevivePercent})")
        }
        definition.rested?.let { r ->
            if (r.poolPercentPerOfflineHour !in 0..1_000) error("/rested/poolPercentPerOfflineHour", "must be 0 to 1000 (got ${r.poolPercentPerOfflineHour})")
            if (r.poolCapPercent !in 0..10_000) error("/rested/poolCapPercent", "must be 0 to 10000 (got ${r.poolCapPercent})")
            if (r.bonusPercent !in 0..1_000) error("/rested/bonusPercent", "must be 0 to 1000 (got ${r.bonusPercent})")
            if (r.catchUpPercent !in 0..1_000) error("/rested/catchUpPercent", "must be 0 to 1000 (got ${r.catchUpPercent})")
            if (r.catchUpBelowLevel !in 0..definition.progression.maxLevel) error("/rested/catchUpBelowLevel", "must be 0 to the level cap (got ${r.catchUpBelowLevel})")
        }
        definition.dualWield?.let { d ->
            if (d.strikeChancePercent !in 0..100) error("/dualWield/strikeChancePercent", "must be 0 to 100 (got ${d.strikeChancePercent})")
            if (d.damagePercent !in 1..300) error("/dualWield/damagePercent", "must be 1 to 300 (got ${d.damagePercent})")
            if (d.enabled && definition.slots.none { it.id == EquipmentSlot.SHIELD.id }) error("/dualWield", "needs the SHIELD (off-hand) equipment slot")
        }
        definition.promotion?.let { p ->
            if (p.variable.isBlank()) error("/promotion/variable", "must not be blank")
            if (p.startName.isBlank()) error("/promotion/startName", "must not be blank")
            if (p.tiers.isEmpty() || p.tiers.size > 10) error("/promotion/tiers", "needs 1 to 10 tiers (got ${p.tiers.size})")
            var previous = 1
            p.tiers.forEachIndexed { i, t ->
                if (t.name.isBlank()) error("/promotion/tiers/$i/name", "must not be blank")
                if (t.minLevel !in 2..definition.progression.maxLevel) error("/promotion/tiers/$i/minLevel", "must be 2 to the maximum level ${definition.progression.maxLevel} (got ${t.minLevel})")
                if (t.minLevel < previous) error("/promotion/tiers/$i/minLevel", "must not be lower than the tier before it ($previous)")
                if (t.bonusStatPoints !in 0..10_000) error("/promotion/tiers/$i/bonusStatPoints", "must be 0 to 10000 (got ${t.bonusStatPoints})")
                previous = t.minLevel
            }
        }
        return issues
    }

    private fun checkNobilityAndBattlePower(definition: GameDefinition, error: (String, String) -> Unit) {
        definition.nobility?.let { n ->
            if (!GameDefinition.ID_PATTERN.matches(n.donationVariable)) {
                error("/nobility/donationVariable", "must be lowercase letters, digits or '_' and start with a letter")
            }
            if (n.ranks.size > 64) error("/nobility/ranks", "has ${n.ranks.size} ranks; the limit is 64")
            n.ranks.forEachIndexed { i, r ->
                val at = "/nobility/ranks/$i"
                if (!GameDefinition.ID_PATTERN.matches(r.id)) error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
                if (n.ranks.indexOfFirst { it.id == r.id } != i) error("$at/id", "rank '${r.id}' is defined twice")
                if (r.name.isBlank() || r.name.length > 32) error("$at/name", "must be 1 to 32 characters")
                if (r.minDonation < 1) error("$at/minDonation", "must be at least 1 (got ${r.minDonation})")
                if (n.ranks.indexOfFirst { it.minDonation == r.minDonation } != i) {
                    error("$at/minDonation", "two ranks need ${r.minDonation} donated")
                }
                if (r.battlePower !in 0..1_000_000) error("$at/battlePower", "must be 0 to 1000000 (got ${r.battlePower})")
            }
        }
        definition.battlePower?.let { bp ->
            for ((name, v) in listOf("perLevel" to bp.perLevel, "perItemLevel" to bp.perItemLevel, "perPlus" to bp.perPlus, "perGem" to bp.perGem)) {
                if (v !in 0..100_000) error("/battlePower/$name", "must be 0 to 100000 (got $v)")
            }
            for ((id, v) in bp.rarityPoints) {
                if (v !in 0..100_000) error("/battlePower/rarityPoints/$id", "must be 0 to 100000 (got $v)")
                val loot = definition.loot
                if (loot != null && loot.rarity(id) == null) error("/battlePower/rarityPoints/$id", "no rarity '$id' in /loot/rarities")
            }
        }
    }

    private fun checkWeaponProficiency(definition: GameDefinition, error: (String, String) -> Unit) {
        val wp = definition.weaponProficiency ?: return
        if (wp.maxLevel !in 1..100) error("/weaponProficiency/maxLevel", "must be 1 to 100 (got ${wp.maxLevel})")
        if (wp.hitsPerLevel !in 1..100_000) error("/weaponProficiency/hitsPerLevel", "must be 1 to 100000 (got ${wp.hitsPerLevel})")
        if (wp.damageBonusPercentPerLevel !in 0..100) {
            error("/weaponProficiency/damageBonusPercentPerLevel", "must be 0 to 100 (got ${wp.damageBonusPercentPerLevel})")
        }
        if (wp.weaponTypes.size > 32) error("/weaponProficiency/weaponTypes", "has ${wp.weaponTypes.size} types; the limit is 32")
        wp.weaponTypes.forEachIndexed { i, wt ->
            val at = "/weaponProficiency/weaponTypes/$i"
            if (!GameDefinition.ID_PATTERN.matches(wt.id)) {
                error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
            }
            if (wp.weaponTypes.indexOfFirst { it.id == wt.id } != i) {
                error("$at/id", "weapon type '${wt.id}' is defined twice")
            }
            if (wt.name.isBlank() || wt.name.length > 32) {
                error("$at/name", "must be 1 to 32 characters")
            }
        }
    }

    private fun checkLoot(definition: GameDefinition, error: (String, String) -> Unit) {
        val loot = definition.loot ?: return
        val maxLevel = LootRules.MAX_ITEM_LEVEL
        if (loot.rarities.size > LootRules.MAX_RARITIES) error("/loot/rarities", "has ${loot.rarities.size} rarities; the limit is ${LootRules.MAX_RARITIES}")
        if (loot.affixes.size > LootRules.MAX_AFFIXES) error("/loot/affixes", "has ${loot.affixes.size} affixes; the limit is ${LootRules.MAX_AFFIXES}")
        loot.rarities.forEachIndexed { i, r ->
            val at = "/loot/rarities/$i"
            if (!GameDefinition.ID_PATTERN.matches(r.id)) error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
            if (loot.rarities.indexOfFirst { it.id == r.id } != i) error("$at/id", "rarity '${r.id}' is defined twice")
            if (r.name.isBlank() || r.name.length > 24) error("$at/name", "must be 1 to 24 characters")
            if (!GameDefinition.COLOR_PATTERN.matches(r.color)) error("$at/color", "must be a #rrggbb colour")
            if (r.weight !in 0..LootRules.MAX_WEIGHT) error("$at/weight", "must be 0 to ${LootRules.MAX_WEIGHT} (0 never drops)")
            if (r.minItemLevel !in 1..maxLevel) error("$at/minItemLevel", "must be 1 to $maxLevel")
            if (r.affixesMin !in 0..LootRules.MAX_AFFIXES_PER_ITEM) error("$at/affixesMin", "must be 0 to ${LootRules.MAX_AFFIXES_PER_ITEM}")
            if (r.affixesMax !in 0..LootRules.MAX_AFFIXES_PER_ITEM) error("$at/affixesMax", "must be 0 to ${LootRules.MAX_AFFIXES_PER_ITEM}")
            if (r.affixesMin > r.affixesMax) error("$at/affixesMin", "must not be more than affixesMax")
            if (r.priceMultiplierPercent !in 0..LootRules.MAX_PRICE_MULTIPLIER_PERCENT) {
                error("$at/priceMultiplierPercent", "must be 0 to ${LootRules.MAX_PRICE_MULTIPLIER_PERCENT}")
            }
            if (r.socketChanceBasisPoints !in 0..10_000) error("$at/socketChanceBasisPoints", "must be 0 to 10000")
            if (r.affixesMax > 0 && loot.affixes.isEmpty()) error("$at/affixesMax", "rolls affixes, but the game defines none in /loot/affixes")
        }
        if (loot.rarities.isNotEmpty() && loot.rarities.none { it.minItemLevel <= 1 }) {
            error("/loot/rarities", "no rarity can roll at item level 1; give one of them minItemLevel 1")
        }
        val attributeIds = definition.attributes.map { it.id }.toSet()
        val vitalIds = definition.vitals.map { it.id }.toSet()
        loot.affixes.forEachIndexed { i, a ->
            val at = "/loot/affixes/$i"
            if (!GameDefinition.ID_PATTERN.matches(a.id)) error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
            if (loot.affixes.indexOfFirst { it.id == a.id } != i) error("$at/id", "affix '${a.id}' is defined twice")
            if (a.name.isBlank() || a.name.length > 32) error("$at/name", "must be 1 to 32 characters")
            when (a.stat) {
                AffixStat.ATTRIBUTE -> if (a.statId !in attributeIds) error("$at/statId", "must name an attribute (one of ${attributeIds.sorted().joinToString()})")
                AffixStat.VITAL_MAX -> if (a.statId !in vitalIds) error("$at/statId", "must name a vital (one of ${vitalIds.sorted().joinToString()})")
                AffixStat.ATTACK_DAMAGE,
                AffixStat.ARMOR_DEFENSE,
                AffixStat.CRIT_CHANCE_BP,
                AffixStat.DODGE_CHANCE_BP,
                AffixStat.LIFE_LEECH_PERCENT,
                AffixStat.MAGIC_FIND_PERCENT -> if (a.statId.isNotEmpty()) error("$at/statId", "must be empty for ${a.stat}")
            }
            val range = -LootRules.MAX_AFFIX_VALUE..LootRules.MAX_AFFIX_VALUE
            if (a.min !in range || a.max !in range) error("$at/min", "min and max must be within ±${LootRules.MAX_AFFIX_VALUE}")
            if (a.min > a.max) error("$at/min", "must not be more than max")
            if (a.weight !in 1..LootRules.MAX_WEIGHT) error("$at/weight", "must be 1 to ${LootRules.MAX_WEIGHT}")
            if (a.minItemLevel !in 1..maxLevel || a.maxItemLevel !in 1..maxLevel) error("$at/minItemLevel", "item levels must be 1 to $maxLevel")
            if (a.minItemLevel > a.maxItemLevel) error("$at/minItemLevel", "must not be more than maxItemLevel")
            if (EquipmentSlot.AMMO in a.slots) error("$at/slots", "ammunition never rolls affixes")
        }
    }

    private fun checkBoonsAndSkillTree(definition: GameDefinition, error: (String, String) -> Unit) {
        val attrIds = definition.attributes.map { it.id }.toSet()
        definition.boons.forEachIndexed { i, b ->
            val at = "/boons/$i"
            if (!GameDefinition.ID_PATTERN.matches(b.id)) error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
            if (definition.boons.indexOfFirst { it.id == b.id } != i) error("$at/id", "boon '${b.id}' is defined twice")
            if (b.name.isBlank() || b.name.length > 40) error("$at/name", "must be 1 to 40 characters")
            if (b.maxStacks !in 1..20) error("$at/maxStacks", "must be 1 to 20 (got ${b.maxStacks})")
            if (b.weight !in 1..LootRules.MAX_WEIGHT) error("$at/weight", "must be 1 to ${LootRules.MAX_WEIGHT}")
            b.attributeBonuses.keys.forEach { k -> if (k !in attrIds) error("$at/attributeBonuses/$k", "unknown attribute '$k'") }
        }
        definition.skillTree?.let { tree ->
            if (tree.pointsPerLevel !in 0..20) error("/skillTree/pointsPerLevel", "must be 0 to 20 (got ${tree.pointsPerLevel})")
            val nodeIds = tree.nodes.map { it.id }.toSet()
            tree.nodes.forEachIndexed { i, n ->
                val at = "/skillTree/nodes/$i"
                if (!GameDefinition.ID_PATTERN.matches(n.id)) error("$at/id", "must be lowercase letters, digits or '_' and start with a letter")
                if (tree.nodes.indexOfFirst { it.id == n.id } != i) error("$at/id", "skill node '${n.id}' is defined twice")
                if (n.name.isBlank() || n.name.length > 40) error("$at/name", "must be 1 to 40 characters")
                if (n.maxRank !in 1..20) error("$at/maxRank", "must be 1 to 20 (got ${n.maxRank})")
                if (n.minLevel < 1) error("$at/minLevel", "must be >= 1")
                n.requires.forEach { req ->
                    if (req == n.id) error("$at/requires", "node '${n.id}' cannot require itself")
                    else if (req !in nodeIds) error("$at/requires", "unknown prerequisite node '$req'")
                }
                n.attributeBonuses.keys.forEach { k -> if (k !in attrIds) error("$at/attributeBonuses/$k", "unknown attribute '$k'") }
            }
        }
    }

    // ---- structure ---------------------------------------------------------------------------------------------

    private fun checkClock(definition: GameDefinition, error: (String, String) -> Unit) {
        val clock = definition.clock ?: return
        if (clock.dayLengthTicks !in ClockDefinition.MIN_DAY_LENGTH_TICKS..ClockDefinition.MAX_DAY_LENGTH_TICKS) {
            error("/clock/dayLengthTicks", "must be between ${ClockDefinition.MIN_DAY_LENGTH_TICKS} and ${ClockDefinition.MAX_DAY_LENGTH_TICKS}")
        }
        if (clock.daysPerSeason !in 1..ClockDefinition.MAX_DAYS_PER_SEASON) {
            error("/clock/daysPerSeason", "must be between 1 and ${ClockDefinition.MAX_DAYS_PER_SEASON}")
        }
        if (clock.seasons.isEmpty() || clock.seasons.size > ClockDefinition.MAX_SEASONS) {
            error("/clock/seasons", "must list 1 to ${ClockDefinition.MAX_SEASONS} season names")
        }
        clock.seasons.forEachIndexed { i, name ->
            if (name.isBlank() || name.length > 24) error("/clock/seasons/$i", "must be 1 to 24 characters")
        }
        if (clock.seasons.toSet().size != clock.seasons.size) error("/clock/seasons", "season names must be unique")
        if (clock.startTimePermille !in 0..999) error("/clock/startTimePermille", "must be between 0 and 999")
        val bounds = listOf(
            "dawnStartPermille" to clock.dawnStartPermille, "dayStartPermille" to clock.dayStartPermille,
            "duskStartPermille" to clock.duskStartPermille, "nightStartPermille" to clock.nightStartPermille
        )
        bounds.forEach { (name, value) -> if (value !in 0..1000) error("/clock/$name", "must be between 0 and 1000") }
        if (bounds.zipWithNext().any { (a, b) -> a.second > b.second }) {
            error("/clock", "dawnStartPermille <= dayStartPermille <= duskStartPermille <= nightStartPermille must hold (the day runs midnight, dawn, day, dusk, night)")
        }
        if (clock.nightLight !in 0..1000) error("/clock/nightLight", "must be between 0 and 1000")
    }

    private fun checkClassPassives(definition: GameDefinition, error: (String, String) -> Unit) {
        val vitalIds = definition.vitals.map { it.id }.toSet()
        for ((classId, p) in definition.classPassives) {
            val at = "/classPassives/$classId"
            if (classId <= 0) error(at, "class ids are 1 or more")
            fun percent(field: String, value: Int) {
                if (value !in ClassPassive.MIN_PERCENT..ClassPassive.MAX_PERCENT) error("$at/$field", "must be ${ClassPassive.MIN_PERCENT} to ${ClassPassive.MAX_PERCENT} (got $value)")
            }
            percent("damagePercent", p.damagePercent)
            percent("armorPercent", p.armorPercent)
            percent("healingPercent", p.healingPercent)
            percent("moveSpeedPercent", p.moveSpeedPercent)
            for ((vital, value) in p.vitalMaxPercent) {
                if (vital !in vitalIds) error("$at/vitalMaxPercent/$vital", "is not a vital of this game")
                percent("vitalMaxPercent/$vital", value)
            }
        }
    }

    private fun checkTuning(definition: GameDefinition, error: (String, String) -> Unit) {
        val t = definition.tuning ?: return
        fun range(field: String, value: Int, min: Int, max: Int) {
            if (value !in min..max) error("/tuning/$field", "must be $min to $max (got $value)")
        }
        range("harvestCooldownTicks", t.harvestCooldownTicks, 0, GameplayTuning.MAX_HARVEST_COOLDOWN_TICKS)
        range("shopRangeTiles", t.shopRangeTiles, 1, GameplayTuning.MAX_RANGE_TILES)
        range("pickupRangeTiles", t.pickupRangeTiles, 1, GameplayTuning.MAX_RANGE_TILES)
        range("unarmedDamage", t.unarmedDamage, 0, GameplayTuning.MAX_DAMAGE)
        range("plusWeaponDamage", t.plusWeaponDamage, 0, GameplayTuning.MAX_DAMAGE)
        range("plusArmorDefense", t.plusArmorDefense, 0, GameplayTuning.MAX_DAMAGE)
        range("plusRingHp", t.plusRingHp, 0, GameplayTuning.MAX_DAMAGE)
        range("plusPercentPerLevel", t.plusPercentPerLevel, 0, GameplayTuning.MAX_PLUS_PERCENT)
        range("maxGuildMembers", t.maxGuildMembers, 1, GameplayTuning.MAX_GUILD_MEMBERS)
        range("maxPartySize", t.maxPartySize, 0, GameplayTuning.MAX_PARTY_SIZE)
        range("actionTargetRadiusPixels", t.actionTargetRadiusPixels, 0, GameplayTuning.MAX_TARGET_RADIUS_PIXELS)
    }

    private fun checkSchedules(definition: GameDefinition, error: (String, String) -> Unit) {
        val schedules = definition.schedules
        if (schedules.size > ScheduleDefinition.MAX_SCHEDULES) error("/schedules", "has ${schedules.size} schedules; the limit is ${ScheduleDefinition.MAX_SCHEDULES}")
        schedules.forEachIndexed { i, def ->
            if (schedules.indexOfFirst { it.id == def.id } != i) error("/schedules/$i/id", "schedule id '${def.id}' is used twice")
            for ((field, message) in ScheduleRules.problems(def)) error("/schedules/$i/$field", message)
        }
    }

    private fun checkWeather(definition: GameDefinition, error: (String, String) -> Unit) {
        val weather = definition.weather ?: return
        if (weather.zones.size > WeatherDefinition.MAX_ZONES) error("/weather/zones", "has ${weather.zones.size} zones; the limit is ${WeatherDefinition.MAX_ZONES}")
        weather.zones.forEachIndexed { i, z ->
            val at = "/weather/zones/$i"
            if (z.id < 0) error("$at/id", "must be 0 or more")
            if (weather.zones.indexOfFirst { it.id == z.id } != i) error("$at/id", "zone id ${z.id} is used twice")
            if (z.name.isBlank() || z.name.length > 40) error("$at/name", "must be 1 to 40 characters")
            if (z.periodTicks !in WeatherZone.MIN_PERIOD_TICKS..WeatherZone.MAX_PERIOD_TICKS) {
                error("$at/periodTicks", "must be between ${WeatherZone.MIN_PERIOD_TICKS} and ${WeatherZone.MAX_PERIOD_TICKS}")
            }
            if (z.states.isEmpty() || z.states.size > WeatherZone.MAX_STATES) error("$at/states", "must list 1 to ${WeatherZone.MAX_STATES} weather states")
            z.states.forEachIndexed { j, s ->
                if (s.weight !in 1..WeatherZone.MAX_WEIGHT) error("$at/states/$j/weight", "must be between 1 and ${WeatherZone.MAX_WEIGHT}")
                if (s.intensity !in 0f..1f) error("$at/states/$j/intensity", "must be between 0 and 1")
            }
        }
    }

    private fun checkOnboarding(definition: GameDefinition, error: (String, String) -> Unit) {
        val config = definition.onboarding ?: return
        if (config.tips.size > OnboardingConfig.MAX_TIPS) error("/onboarding/tips", "has ${config.tips.size} tips; the limit is ${OnboardingConfig.MAX_TIPS}")
        val builtIn = OnboardingDefaults.tips(definition).map { it.id }.toSet()
        val seen = HashSet<String>()
        config.tips.forEachIndexed { i, tip ->
            val at = "/onboarding/tips/$i"
            if (!TipDefinition.ID_PATTERN.matches(tip.id)) error("$at/id", "must be 1 to 40 letters, digits or underscores")
            else if (!seen.add(tip.id)) error("$at/id", "'${tip.id}' is used twice")
            if (tip.title.length > TipDefinition.MAX_TITLE) error("$at/title", "must be at most ${TipDefinition.MAX_TITLE} characters")
            if (tip.text.length > TipDefinition.MAX_TEXT) error("$at/text", "must be at most ${TipDefinition.MAX_TEXT} characters")
            // Blank text removes a built-in tip; for anything else it would be a tip that says nothing.
            if (tip.text.isBlank() && tip.id !in builtIn) error("$at/text", "is blank; blank text only removes a built-in tip, and '${tip.id}' is not one")
            if (tip.trigger == TipTrigger.LEVEL && tip.level !in 1..100000) error("$at/level", "a LEVEL tip needs a level of 1 or more")
        }
    }

    private fun checkSprites(definition: GameDefinition, error: (String, String) -> Unit) {
        val sprites = definition.sprites ?: return
        if (sprites.cellWidth !in SpriteProfile.MIN_CELL..SpriteProfile.MAX_CELL) {
            error("/sprites/cellWidth", "must be between ${SpriteProfile.MIN_CELL} and ${SpriteProfile.MAX_CELL}")
        }
        if (sprites.cellHeight !in SpriteProfile.MIN_CELL..SpriteProfile.MAX_CELL) {
            error("/sprites/cellHeight", "must be between ${SpriteProfile.MIN_CELL} and ${SpriteProfile.MAX_CELL}")
        }
        val walkFrames = sprites.stateFrames[SpriteState.WALK] ?: 0
        if (walkFrames < 1) {
            error("/sprites/stateFrames/walk", "walk state must declare at least 1 frame")
        }
        for ((state, frames) in sprites.stateFrames) {
            if (frames < 0 || frames > SpriteProfile.MAX_FRAMES_PER_STATE) {
                error(
                    "/sprites/stateFrames/${state.name.lowercase()}",
                    "must be between 0 and ${SpriteProfile.MAX_FRAMES_PER_STATE}"
                )
            }
        }
        if (sprites.cellWidth.toLong() * sprites.totalFrames > SpriteProfile.MAX_SHEET_EDGE) {
            error(
                "/sprites/stateFrames",
                "total sheet width (${sprites.sheetWidth}px) exceeds ${SpriteProfile.MAX_SHEET_EDGE}px limit"
            )
        }
        for ((id, scale) in sprites.scales) {
            if (id <= 0) error("/sprites/scales/$id", "sprite ids start at 1")
            if (scale.isNaN() || scale < SpriteProfile.MIN_SCALE || scale > SpriteProfile.MAX_SCALE) {
                error("/sprites/scales/$id", "must be between ${SpriteProfile.MIN_SCALE} and ${SpriteProfile.MAX_SCALE}")
            }
        }
        for ((id, height) in sprites.uiHeights) {
            if (id <= 0) error("/sprites/uiHeights/$id", "sprite ids start at 1")
            if (height < 1 || height > sprites.cellHeight) error("/sprites/uiHeights/$id", "must be between 1 and the cell height (${sprites.cellHeight})")
        }
        if (sprites.cellHeight.toLong() * SpriteProfile.DIRECTIONS > SpriteProfile.MAX_SHEET_EDGE) {
            error(
                "/sprites/cellHeight",
                "total sheet height (${sprites.sheetHeight}px) exceeds ${SpriteProfile.MAX_SHEET_EDGE}px limit"
            )
        }
    }

    private fun checkAttributes(definition: GameDefinition, error: (String, String) -> Unit) {
        if (definition.attributes.size > MAX_ATTRIBUTES) error("/attributes", "has ${definition.attributes.size} entries; the limit is $MAX_ATTRIBUTES")
        definition.attributes.forEachIndexed { i, a ->
            val at = "/attributes/$i"
            checkId("$at/id", a.id, definition, error, firstUse = definition.attributes.indexOfFirst { it.id == a.id } == i)
            checkText("$at/name", a.name, 40, error)
            if (a.short.length > 8) error("$at/short", "must be at most 8 characters")
            if (a.floor < 0) error("$at/floor", "must be 0 or more")
            val cap = a.effectiveCap
            if (cap > GameDefinition.ATTRIBUTE_HARD_CAP) error("$at/cap", "must be at most ${GameDefinition.ATTRIBUTE_HARD_CAP}")
            if (cap < a.floor) error("$at/cap", "must not be below the floor (${a.floor})")
            if (a.base < 0 || a.base > cap) error("$at/base", "must be between 0 and the cap ($cap)")
        }
    }

    private fun checkVitals(definition: GameDefinition, error: (String, String) -> Unit) {
        if (definition.vitals.isEmpty()) error("/vitals", "must declare at least one vital (one of them is the life vital)")
        if (definition.vitals.size > MAX_VITALS) error("/vitals", "has ${definition.vitals.size} entries; the limit is $MAX_VITALS")
        definition.vitals.forEachIndexed { i, v ->
            val at = "/vitals/$i"
            checkId("$at/id", v.id, definition, error, firstUse = definition.vitals.indexOfFirst { it.id == v.id } == i)
            checkText("$at/name", v.name, 40, error)
            if (v.short.length > 8) error("$at/short", "must be at most 8 characters")
            if (!GameDefinition.COLOR_PATTERN.matches(v.color)) error("$at/color", "must look like #rrggbb")
            v.regen?.let { r ->
                if (r.everyTicks !in 1..100_000) error("$at/regen/everyTicks", "must be between 1 and 100000")
            }
        }
        val lifeCount = definition.vitals.count { it.role == VitalRole.LIFE }
        if (lifeCount != 1) error("/vitals", "exactly one vital must have role \"life\" (found $lifeCount)")
    }

    /** Ids share one namespace because formulas use them as variable names. */
    private fun checkId(pointer: String, id: String, definition: GameDefinition, error: (String, String) -> Unit, firstUse: Boolean) {
        if (!GameDefinition.ID_PATTERN.matches(id)) {
            error(pointer, "'$id' must be lowercase letters, digits and '_' and start with a letter")
            return
        }
        if (id in GameDefinition.RESERVED_NAMES) error(pointer, "'$id' is a reserved name")
        val owners = definition.attributes.count { it.id == id } + definition.vitals.count { it.id == id }
        if (owners > 1 && firstUse) error(pointer, "'$id' is used more than once (attribute and vital ids share one namespace)")
        if (owners > 1 && !firstUse) error(pointer, "'$id' is a duplicate")
    }

    private fun checkText(pointer: String, text: String, max: Int, error: (String, String) -> Unit) {
        if (text.isBlank() || text.length > max) error(pointer, "must be 1 to $max characters")
    }

    /** Returns the max level to use for the trial evaluations (1 when it is invalid, so later checks still run). */
    private fun checkProgression(definition: GameDefinition, error: (String, String) -> Unit): Int {
        val p = definition.progression
        if (p.maxLevel !in 1..MAX_LEVEL_LIMIT) {
            error("/progression/maxLevel", "must be between 1 and $MAX_LEVEL_LIMIT")
            return 1
        }
        val table = p.xpTable
        when {
            table != null && p.xpExpr != null -> error("/progression", "give either xpTable or xpExpr, not both")
            table == null && p.xpExpr == null -> error("/progression", "give either xpTable or xpExpr")
            table != null -> {
                if (table.size != p.maxLevel - 1) error("/progression/xpTable", "needs maxLevel - 1 = ${p.maxLevel - 1} entries, found ${table.size}")
                table.forEachIndexed { i, xp ->
                    if (xp < 1) error("/progression/xpTable/$i", "must be at least 1")
                    if (i > 0 && xp < table[i - 1]) error("/progression/xpTable/$i", "must not be lower than the previous entry")
                }
            }
        }
        return p.maxLevel
    }

    // ---- formulas ----------------------------------------------------------------------------------------------

    private fun checkFormulas(definition: GameDefinition, maxLevel: Int, error: (String, String) -> Unit) {
        val attributes = definition.attributes.filter { GameDefinition.ID_PATTERN.matches(it.id) }
        // An attribute with a nonsensical floor or cap is reported on its own; here it just needs a usable, non-empty range.
        fun rangeOf(a: AttributeDefinition): LongRange {
            val low = a.floor.coerceIn(0, GameDefinition.ATTRIBUTE_HARD_CAP)
            val high = a.effectiveCap.coerceIn(low, GameDefinition.ATTRIBUTE_HARD_CAP)
            return low.toLong()..high.toLong()
        }
        fun attributeRanges(prefix: String): Map<String, LongRange> = attributes.associate { prefix + it.id to rangeOf(it) }

        val self = attributeRanges("") + mapOf("level" to 1L..maxLevel.toLong())
        val attacker = attributeRanges("a.")
        val defender = attributeRanges("d.")
        val boolean = 0L..1L
        val itemValue = 0L..MAX_ITEM_VALUE

        definition.vitals.forEachIndexed { i, v ->
            val at = "/vitals/$i"
            formula("$at/max", v.max, self + ("bonus" to 0L..MAX_BONUS), error)
            v.regen?.let { formula("$at/regen/amount", it.amount, self, error) }
        }
        definition.progression.xpExpr?.let { source ->
            val parsed = formula("/progression/xpExpr", source, mapOf("level" to 1L..maxLevel.toLong()), error)
            if (parsed != null) {
                for (level in 1 until maxLevel) {
                    val xp = try { parsed.evaluate { name -> if (name == "level") level.toLong() else null } } catch (e: FormulaEvaluationException) { break }
                    if (xp < 1) { error("/progression/xpExpr", "gives $xp at level $level; it must be at least 1"); break }
                }
            }
        }

        val c = definition.combat
        val melee = attacker + defender + mapOf("weapon" to itemValue, "armor" to itemValue, "crit" to boolean)
        formula("/combat/melee", c.melee, melee, error)
        formula("/combat/ranged", c.ranged, melee + ("ammo" to itemValue), error)
        formula("/combat/spell", c.spell, attacker + defender + mapOf("basePower" to itemValue, "scales" to boolean, "heals" to boolean), error)
        formula("/combat/critChanceBp", c.critChanceBp, attacker, error)
        formula("/combat/dodgeChanceBp", c.dodgeChanceBp, attacker + defender, error)
    }

    /**
     * Parses [source], checks every name it reads against [ranges], and trial-evaluates it. Returns the formula when it parsed (even if
     * the trial found problems), or null when it did not parse.
     */
    private fun formula(
        pointer: String,
        source: String,
        ranges: Map<String, LongRange>,
        error: (String, String) -> Unit
    ): Formula? {
        val parsed = try {
            Formula.parse(source)
        } catch (e: FormulaException) {
            error(pointer, if (e.position >= 0) "${e.message} (at character ${e.position})" else e.message.orEmpty())
            return null
        }
        var usable = true
        for (name in parsed.variables) {
            if (name !in ranges) {
                error(pointer, "uses '$name', which is not available here (available: ${ranges.keys.sorted().joinToString()})")
                usable = false
            }
        }
        for (step in parsed.stepNames) {
            if (step in ranges || step in GameDefinition.RESERVED_NAMES) {
                error(pointer, "the step name '$step' is already a variable or a reserved name")
                usable = false
            }
        }
        if (usable) trialEvaluate(pointer, parsed, ranges, error)
        return parsed
    }

    private fun trialEvaluate(pointer: String, formula: Formula, ranges: Map<String, LongRange>, error: (String, String) -> Unit) {
        val names = formula.variables
        if (names.isEmpty()) {
            try { formula.evaluate { null } } catch (e: FormulaEvaluationException) { error(pointer, "fails: ${e.message}") }
            return
        }
        for (combination in combinations(names.map { ranges.getValue(it) })) {
            val values = HashMap<String, Long>()
            names.forEachIndexed { i, name -> values[name] = combination[i] }
            try {
                formula.evaluate { values[it] }
            } catch (e: FormulaEvaluationException) {
                error(pointer, "fails when ${values.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}: ${e.message}")
                return
            }
        }
    }

    /** Sample points per input (extremes plus a few inside), falling back to extremes only when there would be too many combinations. */
    private fun combinations(ranges: List<LongRange>): Sequence<LongArray> {
        fun samples(r: LongRange, detailed: Boolean): List<Long> =
            if (r.first == r.last) listOf(r.first)
            else if (detailed) listOf(r.first, r.first + 1, (r.first + r.last) / 2, r.last - 1, r.last).distinct().filter { it in r }
            else listOf(r.first, r.last)

        val detailed = ranges.map { samples(it, true) }
        if (detailed.fold(1L) { acc, s -> acc * s.size } <= MAX_COMBINATIONS) return product(detailed)
        val extremes = ranges.map { samples(it, false) }
        if (extremes.fold(1L) { acc, s -> acc * s.size } <= MAX_COMBINATIONS) return product(extremes)
        // Too many inputs for a full grid: all lowest, all highest, and each input alone at the opposite extreme.
        val lows = LongArray(ranges.size) { ranges[it].first }
        val highs = LongArray(ranges.size) { ranges[it].last }
        val list = mutableListOf(lows, highs)
        for (i in ranges.indices) {
            list += lows.copyOf().also { it[i] = highs[i] }
            list += highs.copyOf().also { it[i] = lows[i] }
        }
        return list.asSequence()
    }

    /** Every combination of one value from each list, as an odometer (first list varies slowest). */
    private fun product(lists: List<List<Long>>): Sequence<LongArray> = sequence {
        val position = IntArray(lists.size)
        while (true) {
            yield(LongArray(lists.size) { lists[it][position[it]] })
            var i = lists.size - 1
            while (i >= 0 && ++position[i] == lists[i].size) { position[i] = 0; i-- }
            if (i < 0) break
        }
    }

    private val GAME_ID = Regex("[a-z][a-z0-9_-]*")
}
