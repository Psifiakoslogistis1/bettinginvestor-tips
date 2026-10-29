package gr.bettinginvestor.tips.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Offline demo. Star section uses flat 1€ stake: win=(odds-1), loss=-1.
 * Page 2 locked until daily pass — admin always unlocked via isAdmin.
 */
object LocalDemoStore {
    private val athens = ZoneId.of("Europe/Athens")
    private var tips: MutableList<Tip> = mutableListOf()
    private var systems: MutableList<DaySystem> = mutableListOf()
    private var paidUnlocked: Boolean = false
    private var loaded = false
    private var nextId = 1000

    const val STAR_STAKE = 1.0

    fun today(): String = LocalDate.now(athens).format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** Flat 1€ profit for a star pick. */
    fun starProfit(tip: Tip): Double? {
        val won = tip.resultWon ?: return null
        val odds = tip.odds ?: return null
        return if (won) odds - 1.0 else -1.0
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = context.assets.open("seed-data.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("tips")
            tips.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tips.add(
                    Tip(
                        id = i + 1,
                        matchName = o.getString("matchName"),
                        market = o.getString("market"),
                        odds = o.getDouble("odds"),
                        analysis = if (o.has("analysis") && !o.isNull("analysis")) o.getString("analysis") else null,
                        tipDate = o.getString("tipDate"),
                        tipTime = if (o.has("time") && !o.isNull("time")) o.getString("time") else null,
                        sport = if (o.has("sport") && !o.isNull("sport")) o.getString("sport") else null,
                        pickType = if (o.has("pickType") && !o.isNull("pickType")) o.getString("pickType") else null,
                        starOfDay = o.optBoolean("starOfDay", false),
                        stake = if (o.has("stake") && !o.isNull("stake")) o.getDouble("stake") else null,
                        resultWon = when {
                            !o.has("resultWon") || o.isNull("resultWon") -> null
                            else -> o.getBoolean("resultWon")
                        },
                        resultDetail = if (o.has("resultDetail") && !o.isNull("resultDetail")) o.getString("resultDetail") else null,
                        profit = if (o.has("profit") && !o.isNull("profit")) o.getDouble("profit") else null,
                        locked = false,
                    )
                )
            }
            nextId = tips.size + 1
            val sys = root.getJSONArray("systems")
            systems.clear()
            for (i in 0 until sys.length()) {
                val o = sys.getJSONObject(i)
                systems.add(
                    DaySystem(
                        tipDate = o.getString("tipDate"),
                        systemName = o.getString("systemName"),
                        stake = if (o.has("stake") && !o.isNull("stake")) o.getDouble("stake") else null,
                        profit = if (o.has("profit") && !o.isNull("profit")) o.getDouble("profit") else null,
                        progressive = if (o.has("progressive") && !o.isNull("progressive")) o.getDouble("progressive") else null,
                        dayProfit = if (o.has("dayProfit") && !o.isNull("dayProfit")) o.getDouble("dayProfit") else null,
                        locked = false,
                    )
                )
            }
            loaded = true
        }
    }

    fun isPaidUnlocked(isAdmin: Boolean): Boolean = isAdmin || paidUnlocked
    fun unlockPaid() { paidUnlocked = true }

    fun starTips(dateFilter: String? = null): List<Tip> {
        return tips.filter { it.starOfDay && (dateFilter == null || it.tipDate == dateFilter) }
            .let { TipSort.tipsNewestFirst(it) }
            .map { it.copy(locked = false) }
    }

    fun paidTips(isAdmin: Boolean, dateFilter: String? = null): Pair<Boolean, List<Tip>> {
        if (!isPaidUnlocked(isAdmin)) return false to emptyList()
        val list = tips.filter { !it.starOfDay && (dateFilter == null || it.tipDate == dateFilter) }
            .let { TipSort.tipsNewestFirst(it) }
            .map { it.copy(locked = false) }
        return true to list
    }

    fun systemsForPaid(isAdmin: Boolean): List<DaySystem> {
        if (!isPaidUnlocked(isAdmin)) return emptyList()
        return systems.sortedByDescending { it.tipDate }.map { it.copy(locked = false) }
    }

    /** Star stats with flat 1€ stakes. */
    fun starStats(): StatsResponse {
        val stars = tips.filter { it.starOfDay }.sortedWith(compareBy<Tip> { it.tipDate }.thenBy { it.id })
        val settled = stars.filter { it.resultWon != null && it.odds != null }
        val profits = settled.map { starProfit(it)!! }
        val progressive = profits.runningFold(0.0) { acc, p -> acc + p }.drop(1)
        val wins = settled.count { it.resultWon == true }
        val losses = settled.size - wins
        val totalProfit = profits.sum()
        val stakeTotal = settled.size * STAR_STAKE
        val roi = if (stakeTotal > 0) (totalProfit / stakeTotal) * 100.0 else 0.0
        val winRate = if (settled.isEmpty()) 0.0 else (wins * 1000.0 / settled.size).toInt() / 10.0

        fun bucket(key: (Tip) -> String?): List<StatRow> {
            val map = linkedMapOf<String, Triple<Int, Int, Double>>()
            for (tip in settled) {
                val k = key(tip) ?: "Άλλο"
                val cur = map[k] ?: Triple(0, 0, 0.0)
                val p = starProfit(tip) ?: 0.0
                map[k] = Triple(
                    cur.first + if (tip.resultWon == true) 1 else 0,
                    cur.second + if (tip.resultWon == false) 1 else 0,
                    cur.third + p,
                )
            }
            return map.map { (name, v) ->
                val total = v.first + v.second
                StatRow(name, v.first, v.second, total,
                    if (total == 0) 0.0 else ((v.first * 1000.0 / total).toInt() / 10.0),
                    (v.third * 1000).toInt() / 1000.0)
            }.sortedByDescending { it.total }
        }

        val form = settled.map { it.resultWon == true }
        return StatsResponse(
            overall = StatRow("Γενική", wins, losses, settled.size, winRate, (totalProfit * 1000).toInt() / 1000.0),
            bySport = bucket { it.sport },
            byPickType = bucket { it.pickType },
            starOfDay = StatRow("Σημεία Ημέρας", wins, losses, settled.size, winRate, (totalProfit * 1000).toInt() / 1000.0),
            tipsProfit = (totalProfit * 1000).toInt() / 1000.0,
            systemsProfit = 0.0,
            systemsProgressive = progressive.lastOrNull()?.let { (it * 1000).toInt() / 1000.0 },
            days = emptyList(),
            winRate = winRate,
            totalPicks = settled.size,
            roiPct = (roi * 10).toInt() / 10.0,
            progressive = progressive.map { (it * 1000).toInt() / 1000.0 },
            form = form,
        )
    }

    fun paidStats(isAdmin: Boolean): StatsResponse? {
        if (!isPaidUnlocked(isAdmin)) return null
        val settled = tips.filter { !it.starOfDay && it.resultWon != null }
            .sortedWith(compareBy<Tip> { it.tipDate }.thenBy { it.id })
        val profits = settled.map { it.profit ?: 0.0 }
        val progressive = profits.runningFold(0.0) { acc, p -> acc + p }.drop(1)
        val wins = settled.count { it.resultWon == true }
        val losses = settled.size - wins
        val totalProfit = profits.sum()
        val stakeTotal = settled.sumOf { it.stake ?: 0.0 }
        val roi = if (stakeTotal > 0) (totalProfit / stakeTotal) * 100.0 else 0.0
        val winRate = if (settled.isEmpty()) 0.0 else (wins * 1000.0 / settled.size).toInt() / 10.0

        fun bucket(key: (Tip) -> String?): List<StatRow> {
            val map = linkedMapOf<String, Triple<Int, Int, Double>>()
            for (tip in settled) {
                val k = key(tip) ?: "Άλλο"
                val cur = map[k] ?: Triple(0, 0, 0.0)
                map[k] = Triple(
                    cur.first + if (tip.resultWon == true) 1 else 0,
                    cur.second + if (tip.resultWon == false) 1 else 0,
                    cur.third + (tip.profit ?: 0.0),
                )
            }
            return map.map { (name, v) ->
                val total = v.first + v.second
                StatRow(name, v.first, v.second, total,
                    if (total == 0) 0.0 else ((v.first * 1000.0 / total).toInt() / 10.0),
                    (v.third * 1000).toInt() / 1000.0)
            }.sortedByDescending { it.total }
        }

        return StatsResponse(
            overall = StatRow("Γενική", wins, losses, settled.size, winRate, (totalProfit * 1000).toInt() / 1000.0),
            bySport = bucket { it.sport },
            byPickType = bucket { it.pickType },
            starOfDay = StatRow("—", 0, 0, 0, 0.0, 0.0),
            tipsProfit = (totalProfit * 1000).toInt() / 1000.0,
            systemsProfit = systems.sumOf { it.profit ?: 0.0 }.let { (it * 1000).toInt() / 1000.0 },
            systemsProgressive = systems.maxByOrNull { it.tipDate }?.progressive,
            days = systems.sortedBy { it.tipDate },
            winRate = winRate,
            totalPicks = settled.size,
            roiPct = (roi * 10).toInt() / 10.0,
            progressive = progressive.map { (it * 1000).toInt() / 1000.0 },
            form = settled.map { it.resultWon == true },
        )
    }

    fun createTip(
        matchName: String, market: String, odds: Double, analysis: String, tipDate: String,
        tipTime: String?, sport: String, pickType: String?, starOfDay: Boolean, stake: Double?,
    ): Tip {
        val tip = Tip(
            id = nextId++, matchName = matchName, market = market, odds = odds, analysis = analysis,
            tipDate = tipDate, tipTime = tipTime, sport = sport, pickType = pickType,
            starOfDay = starOfDay, stake = if (starOfDay) STAR_STAKE else stake,
            resultWon = null, resultDetail = null, profit = null, locked = false,
        )
        tips.add(tip)
        return tip
    }

    fun updateTip(tip: Tip): Tip {
        val idx = tips.indexOfFirst { it.id == tip.id }
        if (idx >= 0) tips[idx] = tip
        return tip
    }

    fun deleteTip(id: Int) { tips.removeAll { it.id == id } }
}
