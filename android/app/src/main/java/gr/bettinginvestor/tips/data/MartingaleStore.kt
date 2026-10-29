package gr.bettinginvestor.tips.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

data class MartingaleBet(
    val id: Int,
    val tipDate: String,
    val matchName: String,
    val market: String,
    val odds: Double,
    val stake: Double,
    val desiredStake: Double = stake,
    val resultWon: Boolean?,
    val profit: Double?,
    val poolAfter: Double?,
    val cycleStep: Int,
    val levelBefore: Double,
    val starOfDay: Boolean = false,
    val starMultiplierApplied: Double = 1.0,
    val lossMultiplier: Double = 10.0,
    val poolExceeded: Boolean = false,
    val time: String? = null,
)

data class MartingaleState(
    val pool: Double,
    val baseStake: Double,
    val starMultiplier: Double,
    val lossMultiplier: Double,
    val levelStake: Double,
    val cycleStep: Int,
    val nextStakeHint: Double?,
    val maxDrawdown: Double,
    val peakPool: Double,
    val startingPool: Double,
    val ruleSummary: String,
    val bets: List<MartingaleBet>,
)

/**
 * Rules v2:
 * - level = current base-level stake (starts at baseStake, default 25€)
 * - placed stake = level × starMult if Σημείο Ημέρας, else level
 * - after LOSS: next level = level_that_lost × lossMult (default ×10) — NOT × the doubled placed stake
 * - after WIN: level resets to baseStake
 * - if desired stake > pool: stake entire pool and flag poolExceeded
 */
object MartingaleStore {
    const val STARTING_POOL = 1000.0
    const val DEFAULT_BASE = 25.0
    const val DEFAULT_STAR_MULT = 2.0
    const val DEFAULT_LOSS_MULT = 10.0
    const val RULE_SUMMARY =
        "Base B. Σημείο Ημέρας = level×starMult (×2). Ήττα → next level = level×lossMult (×10), μετά ×star αν Σημείο. Νίκη → base. Stake>pool → όλο το pool."

    private val athens = ZoneId.of("Europe/Athens")
    private var baseStake: Double = DEFAULT_BASE
    private var starMultiplier: Double = DEFAULT_STAR_MULT
    private var lossMultiplier: Double = DEFAULT_LOSS_MULT
    private var levelStake: Double = DEFAULT_BASE
    private var pool: Double = STARTING_POOL
    private var cycleStep: Int = 1
    private var peakPool: Double = STARTING_POOL
    private var maxDrawdown: Double = 0.0
    private var nextId = 1
    private val bets = mutableListOf<MartingaleBet>()
    private var seeded = false
    private var ruleSummary: String = RULE_SUMMARY

    fun today(): String = LocalDate.now(athens).format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun loadSeed(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            try {
                val text = context.assets.open("martingale-seed.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                baseStake = root.optDouble("baseStake", DEFAULT_BASE)
                starMultiplier = root.optDouble("starMultiplier", DEFAULT_STAR_MULT)
                lossMultiplier = root.optDouble("lossMultiplier", DEFAULT_LOSS_MULT)
                levelStake = root.optDouble("levelStake", root.optDouble("cycleLosses", baseStake))
                pool = root.optDouble("pool", STARTING_POOL)
                cycleStep = root.optInt("cycleStep", 1)
                peakPool = root.optDouble("peakPool", STARTING_POOL)
                maxDrawdown = root.optDouble("maxDrawdown", 0.0)
                nextId = root.optInt("nextId", 1)
                ruleSummary = root.optString("ruleSummary", RULE_SUMMARY)
                bets.clear()
                val arr = root.getJSONArray("bets")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    bets.add(parseBet(o))
                }
                seeded = true
            } catch (_: Exception) { }
        }
    }

    private fun parseBet(o: JSONObject): MartingaleBet {
        val level = o.optDouble("levelBefore", o.optDouble("cycleLossesBefore", baseStake))
        return MartingaleBet(
            id = o.getInt("id"),
            tipDate = o.getString("tipDate"),
            matchName = o.getString("matchName"),
            market = o.getString("market"),
            odds = o.getDouble("odds"),
            stake = o.getDouble("stake"),
            desiredStake = o.optDouble("desiredStake", o.getDouble("stake")),
            resultWon = if (o.isNull("resultWon")) null else o.getBoolean("resultWon"),
            profit = if (o.has("profit") && !o.isNull("profit")) o.getDouble("profit") else null,
            poolAfter = if (o.has("poolAfter") && !o.isNull("poolAfter")) o.getDouble("poolAfter") else null,
            cycleStep = o.optInt("cycleStep", 1),
            levelBefore = level,
            starOfDay = o.optBoolean("starOfDay", false),
            starMultiplierApplied = o.optDouble("starMultiplierApplied", if (o.optBoolean("starOfDay", false)) starMultiplier else 1.0),
            lossMultiplier = o.optDouble("lossMultiplier", lossMultiplier),
            poolExceeded = o.optBoolean("poolExceeded", false),
            time = if (o.has("time") && !o.isNull("time")) o.getString("time") else null,
        )
    }

    fun reset(base: Double = DEFAULT_BASE, star: Double = DEFAULT_STAR_MULT, loss: Double = DEFAULT_LOSS_MULT) {
        baseStake = base
        starMultiplier = star
        lossMultiplier = loss
        levelStake = base
        pool = STARTING_POOL
        cycleStep = 1
        peakPool = STARTING_POOL
        maxDrawdown = 0.0
        nextId = 1
        bets.clear()
        seeded = true
    }

    fun setBaseStake(value: Double) { if (value > 0) baseStake = value }
    fun setStarMultiplier(value: Double) { if (value > 0) starMultiplier = value }
    fun setLossMultiplier(value: Double) { if (value > 0) lossMultiplier = value }
    fun baseStake(): Double = baseStake
    fun starMultiplier(): Double = starMultiplier
    fun lossMultiplier(): Double = lossMultiplier
    fun levelStake(): Double = levelStake

    fun desiredStake(starOfDay: Boolean): Double =
        round2(levelStake * (if (starOfDay) starMultiplier else 1.0))

    fun computeStake(starOfDay: Boolean): Triple<Double, Double, Boolean> {
        val desired = desiredStake(starOfDay)
        val exceeded = desired > pool + 1e-9
        val stake = if (exceeded) round2(pool) else desired
        return Triple(desired, stake, exceeded)
    }

    /** @deprecated odds no longer affect stake; kept for UI preview helpers */
    fun stakeForOdds(odds: Double, starOfDay: Boolean = false): Double = computeStake(starOfDay).second

    fun state(pendingStar: Boolean = false): MartingaleState {
        val (_, hint, _) = computeStake(pendingStar)
        return MartingaleState(
            pool = round2(pool),
            baseStake = baseStake,
            starMultiplier = starMultiplier,
            lossMultiplier = lossMultiplier,
            levelStake = round2(levelStake),
            cycleStep = cycleStep,
            nextStakeHint = round2(hint),
            maxDrawdown = round2(maxDrawdown),
            peakPool = round2(peakPool),
            startingPool = STARTING_POOL,
            ruleSummary = ruleSummary,
            bets = TipSort.martingaleNewestFirst(bets),
        )
    }

    fun poolHistory(): List<Double> {
        val series = mutableListOf(STARTING_POOL)
        for (b in bets.sortedBy { it.id }) b.poolAfter?.let { series.add(it) }
        return series.map { round2(it) }
    }

    fun addPick(
        matchName: String, market: String, odds: Double,
        tipDate: String = today(), starOfDay: Boolean = false, time: String? = null,
    ): MartingaleBet {
        val (desired, stake, exceeded) = computeStake(starOfDay)
        val bet = MartingaleBet(
            id = nextId++, tipDate = tipDate, matchName = matchName, market = market, odds = odds,
            stake = stake, desiredStake = desired, resultWon = null, profit = null, poolAfter = null,
            cycleStep = cycleStep, levelBefore = round2(levelStake), starOfDay = starOfDay,
            starMultiplierApplied = if (starOfDay) starMultiplier else 1.0,
            lossMultiplier = lossMultiplier, poolExceeded = exceeded, time = time,
        )
        bets.add(bet)
        return bet
    }

    fun settle(id: Int, won: Boolean): MartingaleBet? {
        val idx = bets.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val bet = bets[idx]
        if (bet.resultWon != null) return bet
        val profit = if (won) round2(bet.stake * (bet.odds - 1.0)) else round2(-bet.stake)
        pool = round2(pool + profit)
        peakPool = max(peakPool, pool)
        maxDrawdown = max(maxDrawdown, peakPool - pool)
        if (won) {
            levelStake = baseStake
            cycleStep = 1
        } else {
            levelStake = round2(bet.levelBefore * lossMultiplier)
            cycleStep += 1
        }
        val updated = bet.copy(resultWon = won, profit = profit, poolAfter = pool)
        bets[idx] = updated
        return updated
    }

    fun deletePending(id: Int) {
        val b = bets.find { it.id == id } ?: return
        if (b.resultWon == null) bets.removeAll { it.id == id }
    }

    fun nextStakeExceedsPool(starOfDay: Boolean = false): Boolean = desiredStake(starOfDay) > pool + 1e-6

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
