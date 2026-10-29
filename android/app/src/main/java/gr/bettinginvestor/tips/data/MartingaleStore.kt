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
    val resultWon: Boolean?, // null = pending
    val profit: Double?,
    val poolAfter: Double?,
    val cycleStep: Int,
    val cycleLossesBefore: Double,
)

data class MartingaleState(
    val pool: Double,
    val baseStake: Double,
    val cycleStep: Int,          // 1-based step within current recovery cycle
    val cycleLosses: Double,     // accumulated losses in current cycle
    val nextStakeHint: Double?,  // null until odds known for next pick
    val maxDrawdown: Double,
    val peakPool: Double,
    val startingPool: Double,
    val bets: List<MartingaleBet>,
)

/**
 * Martingale progression:
 * - win → profit = stake*(odds-1), pool += profit, reset cycle losses & step to 1, next base
 * - loss → profit = -stake, pool += profit, cycleLosses += stake, step++
 * - next stake = (cycleLosses + base) / (odds - 1)  [when posting next pick with known odds]
 * - after win, stake = base
 */
object MartingaleStore {
    const val STARTING_POOL = 1000.0
    const val DEFAULT_BASE = 25.0

    private val athens = ZoneId.of("Europe/Athens")
    private var baseStake: Double = DEFAULT_BASE
    private var pool: Double = STARTING_POOL
    private var cycleStep: Int = 1
    private var cycleLosses: Double = 0.0
    private var peakPool: Double = STARTING_POOL
    private var maxDrawdown: Double = 0.0
    private var nextId = 1
    private val bets = mutableListOf<MartingaleBet>()

    fun today(): String = LocalDate.now(athens).format(DateTimeFormatter.ISO_LOCAL_DATE)


    private var seeded = false

    fun loadSeed(context: Context) {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            try {
                val text = context.assets.open("martingale-seed.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                baseStake = root.optDouble("baseStake", DEFAULT_BASE)
                pool = root.optDouble("pool", STARTING_POOL)
                cycleStep = root.optInt("cycleStep", 1)
                cycleLosses = root.optDouble("cycleLosses", 0.0)
                peakPool = root.optDouble("peakPool", STARTING_POOL)
                maxDrawdown = root.optDouble("maxDrawdown", 0.0)
                nextId = root.optInt("nextId", 1)
                bets.clear()
                val arr = root.getJSONArray("bets")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    bets.add(
                        MartingaleBet(
                            id = o.getInt("id"),
                            tipDate = o.getString("tipDate"),
                            matchName = o.getString("matchName"),
                            market = o.getString("market"),
                            odds = o.getDouble("odds"),
                            stake = o.getDouble("stake"),
                            resultWon = if (o.isNull("resultWon")) null else o.getBoolean("resultWon"),
                            profit = if (o.has("profit") && !o.isNull("profit")) o.getDouble("profit") else null,
                            poolAfter = if (o.has("poolAfter") && !o.isNull("poolAfter")) o.getDouble("poolAfter") else null,
                            cycleStep = o.optInt("cycleStep", 1),
                            cycleLossesBefore = o.optDouble("cycleLossesBefore", 0.0),
                        )
                    )
                }
                seeded = true
            } catch (_: Exception) {
                // keep empty defaults
            }
        }
    }

    fun reset(base: Double = DEFAULT_BASE) {
        baseStake = base
        pool = STARTING_POOL
        cycleStep = 1
        cycleLosses = 0.0
        peakPool = STARTING_POOL
        maxDrawdown = 0.0
        nextId = 1
        bets.clear()
        seeded = true // prevent asset seed from overwriting admin reset
    }

    fun setBaseStake(value: Double) {
        if (value > 0) baseStake = value
    }

    fun baseStake(): Double = baseStake

    /** Stake required for given odds in current cycle state. */
    fun stakeForOdds(odds: Double): Double {
        if (odds <= 1.0) return baseStake
        return if (cycleLosses <= 0.0) {
            baseStake
        } else {
            (cycleLosses + baseStake) / (odds - 1.0)
        }
    }

    fun state(pendingOdds: Double? = null): MartingaleState {
        val hint = pendingOdds?.let { stakeForOdds(it) }
            ?: if (cycleLosses > 0) null else baseStake
        return MartingaleState(
            pool = round2(pool),
            baseStake = baseStake,
            cycleStep = cycleStep,
            cycleLosses = round2(cycleLosses),
            nextStakeHint = hint?.let { round2(it) },
            maxDrawdown = round2(maxDrawdown),
            peakPool = round2(peakPool),
            startingPool = STARTING_POOL,
            bets = bets.sortedByDescending { it.id },
        )
    }

    fun poolHistory(): List<Double> {
        val series = mutableListOf(STARTING_POOL)
        for (b in bets.sortedBy { it.id }) {
            b.poolAfter?.let { series.add(it) }
        }
        return series.map { round2(it) }
    }

    /** Admin posts a new pick — stake computed from current cycle + odds. Pending until settled. */
    fun addPick(matchName: String, market: String, odds: Double, tipDate: String = today()): MartingaleBet {
        val stake = round2(stakeForOdds(odds))
        val bet = MartingaleBet(
            id = nextId++,
            tipDate = tipDate,
            matchName = matchName,
            market = market,
            odds = odds,
            stake = stake,
            resultWon = null,
            profit = null,
            poolAfter = null,
            cycleStep = cycleStep,
            cycleLossesBefore = round2(cycleLosses),
        )
        bets.add(bet)
        return bet
    }

    fun settle(id: Int, won: Boolean): MartingaleBet? {
        val idx = bets.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val bet = bets[idx]
        if (bet.resultWon != null) return bet // already settled
        val profit = if (won) round2(bet.stake * (bet.odds - 1.0)) else round2(-bet.stake)
        pool = round2(pool + profit)
        peakPool = max(peakPool, pool)
        maxDrawdown = max(maxDrawdown, peakPool - pool)
        if (won) {
            cycleLosses = 0.0
            cycleStep = 1
        } else {
            cycleLosses = round2(cycleLosses + bet.stake)
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

    fun pendingToday(): MartingaleBet? =
        bets.filter { it.tipDate == today() && it.resultWon == null }.maxByOrNull { it.id }

    fun todayStakeDisplay(oddsForHint: Double? = null): Double {
        pendingToday()?.let { return it.stake }
        return oddsForHint?.let { stakeForOdds(it) } ?: if (cycleLosses <= 0) baseStake else stakeForOdds(2.0) // illustrative if no odds
    }

    fun nextStakeExceedsPool(odds: Double): Boolean = stakeForOdds(odds) > pool + 1e-6

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
