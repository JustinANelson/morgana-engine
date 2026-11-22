package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.validation.ContentCatalog
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * An economy model for Trevana Realms, computed from the game's own data (monster drops, the upgrade rules, item prices, the
 * XP curve) under stated play assumptions, with design targets as assertions. It writes the table it computes to
 * `build/economy/trevana_realms_economy.md`; the same table is kept in `games/trevana-realms/RETHEME.md` (section 9).
 *
 * The question it answers: does a hero earn Marks, Starshards and Pearls at a pace that makes the upgrade ladder, repairs, potions
 * and the long-term sinks (skill books, nobility) worth doing at every level?
 */
class TrevanaEconomyModelTest {
    private val gameOrNull: Path? = listOf(Path.of("games/trevana-realms"), Path.of("../games/trevana-realms")).firstOrNull { it.exists() }
    private val game: Path
        get() {
            org.junit.jupiter.api.Assumptions.assumeTrue(gameOrNull != null, "Developer game games/trevana-realms is not present in this checkout")
            return gameOrNull!!
        }

    /** The play assumptions every number below rests on. */
    private object Play {
        /** Kills per hour of steady hunting, including walking and recovering (about one every ten seconds). */
        const val KILLS_PER_HOUR = 360.0
        /**
         * Kills per hour for the pace (hours to a level) only. The headless sim (`TrevanaKillRateSimTest`, 2026-11-22) measured
         * about 1,800 for a hunter with human reaction times; income and upkeep below still use the older, lower
         * [KILLS_PER_HOUR] until the economy is re-tuned for the new pace (MODERNIZATION.md, order of work step 5).
         */
        const val PACE_KILLS_PER_HOUR = 1800.0
        const val HITS_DEALT_PER_KILL = 5.0
        const val HITS_TAKEN_PER_KILL = 3.0
        const val POTIONS_PER_HOUR = 10.0
        const val MANA_DRAUGHTS_PER_HOUR = 4.0
        const val FRAGMENTS_PER_PEARL = 8
        const val BIND_COST_MARKS = 500
    }

    private class Kill(val marks: Double, val sale: Double, val starshards: Double, val pearls: Double) {
        val income get() = marks + sale
    }

    private class Row(
        val npc: NpcDefinition, val kill: Kill, val killsPerLevel: Double, val hoursPerLevel: Double,
        val incomePerHour: Double, val sinksPerHour: Double, val killsPerPearl: Double, val killsPerStarLevel: Double
    ) {
        val ratio get() = incomePerHour / sinksPerHour
    }

    private class Model(val def: GameDefinition, val cat: ContentCatalog, val xpToNext: (Int) -> Long) {
        val rarityMultiplier: Double = def.loot!!.rarities.let { r -> r.sumOf { it.weight * it.priceMultiplierPercent / 100.0 } / r.sumOf { it.weight } }
        /** What shops pay for loot (the lowest sell rate in the game): loot is sold, not hoarded. */
        val sellRate: Double = cat.shops.values.minOfOrNull { it.sellRatePercent }?.div(100.0) ?: 0.5
        val gearTypes = setOf(ItemType.WEAPON, ItemType.ARMOR, ItemType.HELMET, ItemType.BOOTS, ItemType.SHIELD, ItemType.RING)

        fun kill(npc: NpcDefinition): Kill {
            var marks = 0.0; var sale = 0.0; var star = 0.0; var frag = 0.0; var pearl = 0.0
            for (d in npc.drops) {
                val chance = d.chanceBasisPoints / 10_000.0
                val amount = (d.minAmount + d.maxAmount) / 2.0
                val item = cat.items[d.itemId] ?: continue
                when {
                    item.type == ItemType.CURRENCY -> marks += chance * amount
                    item.name == "Starshard" -> star += chance * amount
                    item.name == "Pearl Fragment" -> frag += chance * amount
                    item.name == "Wyrm Pearl" -> pearl += chance * amount
                    else -> sale += chance * amount * item.price * sellRate * (if (item.type in gearTypes) rarityMultiplier else 1.0)
                }
            }
            return Kill(marks, sale, star, pearl + frag / Play.FRAGMENTS_PER_PEARL)
        }

        /** Expected materials to climb from Common to the top quality, with pity (resets on each success). */
        fun qualityPathPearls(): Double {
            val q = def.gearUpgrade!!.quality!!
            return q.steps.sumOf { step ->
                val maxS = q.maxPityStacks
                fun p(s: Int) = minOf(1.0, (step.chanceBasisPoints + s * q.pityBonusBasisPointsPerStack) / 10_000.0)
                val e = DoubleArray(maxS + 1)
                e[maxS] = step.materialAmount / p(maxS)
                for (s in maxS - 1 downTo 0) e[s] = step.materialAmount + (1 - p(s)) * e[minOf(maxS, s + q.pityGainPerFailure)]
                e[0]
            }
        }

        /** The priciest shop-grade worn piece for a slot; mounts are left out because they never take a hit, so they never wear. */
        private fun best(level: Int, slot: (ItemDefinition) -> Boolean): ItemDefinition? =
            cat.items.values.filter { it.type in gearTypes && it.mountSpeedPercent == 0 && slot(it) && it.levelReq <= level && it.price > 0 }.maxByOrNull { it.price }

        /** Marks per hour spent on potions and on repairs, for a hero hunting at [level]. */
        fun sinksPerHour(level: Int): Double {
            val potion = cat.items.values.filter { it.restore.containsKey("hp") && it.name.contains("Salve") && it.levelReq <= level }.maxByOrNull { it.price }?.price ?: 15
            val mana = cat.items.values.filter { it.restore.containsKey("mp") && it.restore.size == 1 }.minOfOrNull { it.price } ?: 35
            val dur = def.durability
            val perPoint = (dur?.repairPricePercent ?: 0) / 10_000.0
            val wear = (dur?.weaponWearChancePercent ?: 0) / 100.0
            // From level 30 on a hero wears dropped gear, which is worth its rarity multiplier more than the shop's best.
            val worn = if (level >= 30) rarityMultiplier else 1.0
            val weapon = (best(level) { it.type == ItemType.WEAPON }?.price ?: 0) * worn
            val armor = listOf(ItemType.HELMET, ItemType.ARMOR, ItemType.BOOTS, ItemType.SHIELD).sumOf { t -> best(level) { it.type == t }?.price ?: 0 } * worn
            val repair = weapon * Play.KILLS_PER_HOUR * Play.HITS_DEALT_PER_KILL * wear * perPoint +
                armor * Play.KILLS_PER_HOUR * Play.HITS_TAKEN_PER_KILL * wear * perPoint / 4.0
            return potion * Play.POTIONS_PER_HOUR + mana * Play.MANA_DRAUGHTS_PER_HOUR + repair
        }

        fun rows(): List<Row> = cat.npcs.values.filter { n ->
            (n.behavior.name == "PASSIVE" || n.behavior.name == "ATTACK_ON_SIGHT") && n.xpReward > 0 && n.title.isBlank() && n.name != "Training Dummy"
        }.sortedBy { it.level }.map { n ->
            val k = kill(n)
            val kpl = xpToNext(n.level).toDouble() / n.xpReward
            Row(
                n, k, kpl, kpl / Play.KILLS_PER_HOUR, k.income * Play.KILLS_PER_HOUR, sinksPerHour(n.level),
                if (k.pearls > 0) 1 / k.pearls else Double.MAX_VALUE,
                if (k.starshards > 0) 3 / k.starshards else Double.MAX_VALUE
            )
        }

        /** Hours to reach the top level, hunting the best creature at or just above the hero's level the whole way. */
        fun hoursToMaxLevel(rows: List<Row>): Double {
            var hours = 0.0
            for (level in 1 until def.progression.maxLevel) {
                val r = rows.lastOrNull { it.npc.level <= level + 4 } ?: rows.first()
                hours += xpToNext(level).toDouble() / (r.npc.xpReward * Play.PACE_KILLS_PER_HOUR)
            }
            return hours
        }
    }

    private fun model(): Pair<Model, List<Row>> {
        val loaded = GameDefinitionLoader.load(game).game
        val cat = ContentLoader.load(game.resolve("data")).catalog
        val m = Model(loaded.definition, cat) { level -> loaded.xpToNext(level) ?: 0L }
        return m to m.rows()
    }

    private fun report(m: Model, rows: List<Row>): String {
        val sb = StringBuilder()
        sb.appendLine("| Lv | Creature | Kills / level | Hours / level | Marks / hour | Sinks / hour | Income / sinks | Kills / Pearl | Kills / Starshard level |")
        sb.appendLine("|----|----------|---------------|---------------|--------------|--------------|----------------|---------------|-------------------------|")
        for (r in rows) {
            sb.appendLine("| ${r.npc.level} | ${r.npc.name} | ${r.killsPerLevel.toInt()} | ${"%.2f".format(r.hoursPerLevel)} | ${r.incomePerHour.toInt()} | ${r.sinksPerHour.toInt()} | ${"%.1f".format(r.ratio)} | ${r.killsPerPearl.toInt()} | ${r.killsPerStarLevel.toInt()} |")
        }
        sb.appendLine()
        sb.appendLine("Common to Mythic costs about ${"%.1f".format(m.qualityPathPearls())} Wyrm Pearls on average (with pity), plus ${Play.BIND_COST_MARKS} Marks to bind each pearl made from fragments.")
        sb.appendLine("Hours to the top level (${m.def.progression.maxLevel}) at ${Play.PACE_KILLS_PER_HOUR.toInt()} kills an hour (plain XP, no level-offset or catch-up bonus): about ${m.hoursToMaxLevel(rows).toInt()}.")
        return sb.toString()
    }

    @Test
    fun `the model runs and writes its table`() {
        val (m, rows) = model()
        val text = report(m, rows)
        val out = Path.of("build/economy").also { Files.createDirectories(it) }.resolve("trevana_realms_economy.md")
        Files.writeString(out, text)
        println(text)
        assertTrue(rows.size >= 20, "the model covers the regular creatures (${rows.size})")
    }

    @Test
    fun `climbing from Common to Mythic costs about two dozen pearls`() {
        val (m, _) = model()
        val pearls = m.qualityPathPearls()
        assertTrue(pearls in 20.0..30.0, "expected pearls for the full quality ladder: $pearls")
    }

    @Test
    fun `pearls and Starshards arrive at a pace that makes the upgrades worth chasing`() {
        val (_, rows) = model()
        for (r in rows) {
            assertTrue(r.killsPerPearl in 40.0..600.0, "${r.npc.name}: ${r.killsPerPearl.toInt()} kills per pearl")
            assertTrue(r.killsPerStarLevel in 30.0..500.0, "${r.npc.name}: ${r.killsPerStarLevel.toInt()} kills per Starshard level")
        }
    }

    @Test
    fun `marks stay ahead of potions and repairs at every level, without running away`() {
        val (_, rows) = model()
        for (r in rows) {
            assertTrue(r.ratio in 2.0..25.0, "${r.npc.name} (Lv ${r.npc.level}): income is ${"%.1f".format(r.ratio)} times the upkeep")
        }
    }

    @Test
    fun `the top nobility rank is a long, real goal at endgame income`() {
        val (m, rows) = model()
        val top = m.def.nobility!!.ranks.maxOf { it.minDonation }
        val hours = top / rows.last().incomePerHour
        assertTrue(hours in 100.0..600.0, "hours of top-level hunting to donate the top rank ($top Marks): $hours")
    }

    @Test
    fun `every skill book is within a few hours of income at the level it unlocks`() {
        val (m, rows) = model()
        for (book in m.cat.items.values.filter { it.type == ItemType.SPELL_SCROLL }) {
            val r = rows.lastOrNull { it.npc.level <= maxOf(1, book.levelReq) + 4 } ?: rows.first()
            val hours = book.price / (r.incomePerHour - r.sinksPerHour)
            assertTrue(hours <= 6.0, "${book.name} (Lv ${book.levelReq}, ${book.price} Marks) is ${"%.1f".format(hours)} hours of surplus at Lv ${r.npc.level}")
        }
    }

    @Test
    fun `reaching the top level takes a long but sane time`() {
        val (m, rows) = model()
        val hours = m.hoursToMaxLevel(rows)
        // 2026-11-22: the XP table (game.json) targets about 40 hours to level 60 (10 hours each for 1-30, 30-45, 45-55, 55-60, checked
        // by TrevanaKillRateSimTest); levels 60 and up are a placeholder. Under 8 hours would be too fast.
        assertTrue(hours in 8.0..400.0, "hours to the top level: $hours")
    }
}
