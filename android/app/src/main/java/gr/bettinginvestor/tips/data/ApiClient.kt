package gr.bettinginvestor.tips.data

import gr.bettinginvestor.tips.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class User(val id: Int, val email: String, val name: String, val role: String) {
    val isAdmin: Boolean get() = role == "admin"
}

data class Tip(
    val id: Int,
    val matchName: String,
    val market: String,
    val odds: Double?,
    val analysis: String?,
    val tipDate: String,
    val tipTime: String?,
    val sport: String?,
    val pickType: String?,
    val starOfDay: Boolean,
    val stake: Double?,
    val resultWon: Boolean?,
    val resultDetail: String?,
    val profit: Double?,
    val locked: Boolean,
)

data class DaySystem(
    val tipDate: String,
    val systemName: String,
    val stake: Double?,
    val profit: Double?,
    val progressive: Double?,
    val dayProfit: Double?,
    val locked: Boolean,
)

data class TipsResponse(
    val tipDate: String,
    val today: String,
    val isToday: Boolean,
    val isPast: Boolean,
    val dayUnlocked: Boolean,
    val lockedCount: Int,
    val tips: List<Tip>,
    val system: DaySystem?,
    val dailyPassEur: Double,
)

data class StatRow(
    val name: String,
    val wins: Int,
    val losses: Int,
    val total: Int,
    val winPct: Double,
    val profit: Double,
)

data class StatsResponse(
    val overall: StatRow,
    val bySport: List<StatRow>,
    val byPickType: List<StatRow>,
    val starOfDay: StatRow,
    val tipsProfit: Double,
    val systemsProfit: Double,
    val systemsProgressive: Double?,
    val days: List<DaySystem>,
    val winRate: Double = 0.0,
    val totalPicks: Int = 0,
    val roiPct: Double = 0.0,
    val progressive: List<Double> = emptyList(),
    val form: List<Boolean> = emptyList(), // true=win chronologically oldest→newest, take last N
)

class ApiException(message: String) : Exception(message)

object ApiClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')
    @Volatile var token: String? = null

    private fun authHeaders(builder: Request.Builder): Request.Builder {
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun parseError(body: String?, code: Int): String {
        if (body.isNullOrBlank()) return "Σφάλμα $code"
        return try {
            JSONObject(body).optString("error").ifBlank { "Σφάλμα $code" }
        } catch (_: Exception) {
            body.take(120)
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authed: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val reqBody = body?.toString()?.toRequestBody(jsonMedia)
        val builder = Request.Builder().url("$baseUrl$path")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(reqBody ?: "{}".toRequestBody(jsonMedia))
            "PUT" -> builder.put(reqBody ?: "{}".toRequestBody(jsonMedia))
            "DELETE" -> builder.delete()
            else -> error("bad method")
        }
        if (authed) authHeaders(builder) else token?.let { builder.header("Authorization", "Bearer $it") }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(parseError(text, resp.code))
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    suspend fun login(email: String, password: String): Pair<String, User> {
        val json = request("POST", "/api/auth/login", JSONObject().put("email", email).put("password", password), authed = false)
        val t = json.getString("token"); token = t
        return t to parseUser(json.getJSONObject("user"))
    }

    suspend fun register(name: String, email: String, password: String): Pair<String, User> {
        val json = request(
            "POST", "/api/auth/register",
            JSONObject().put("name", name).put("email", email).put("password", password),
            authed = false,
        )
        val t = json.getString("token"); token = t
        return t to parseUser(json.getJSONObject("user"))
    }

    suspend fun me(): User {
        val json = request("GET", "/api/me")
        return parseUser(json.getJSONObject("user"))
    }

    suspend fun stats(): StatsResponse {
        val json = request("GET", "/api/stats", authed = false)
        fun row(o: JSONObject, fallbackName: String = "") = StatRow(
            name = o.optString("name", fallbackName),
            wins = o.optInt("wins"),
            losses = o.optInt("losses"),
            total = o.optInt("total"),
            winPct = o.optDouble("winPct"),
            profit = o.optDouble("profit"),
        )
        fun rows(arr: JSONArray) = buildList {
            for (i in 0 until arr.length()) add(row(arr.getJSONObject(i)))
        }
        val prog = json.getJSONObject("progressive")
        val daysArr = prog.getJSONArray("days")
        val days = buildList {
            for (i in 0 until daysArr.length()) {
                val d = daysArr.getJSONObject(i)
                add(
                    DaySystem(
                        tipDate = d.getString("tipDate"),
                        systemName = d.optString("systemName"),
                        stake = if (d.isNull("stake")) null else d.getDouble("stake"),
                        profit = if (d.isNull("profit")) null else d.getDouble("profit"),
                        progressive = if (d.isNull("progressive")) null else d.getDouble("progressive"),
                        dayProfit = if (d.isNull("dayProfit")) null else d.getDouble("dayProfit"),
                        locked = false,
                    )
                )
            }
        }
        return StatsResponse(
            overall = row(json.getJSONObject("overall"), "Γενική"),
            bySport = rows(json.getJSONArray("bySport")),
            byPickType = rows(json.getJSONArray("byPickType")),
            starOfDay = row(json.getJSONObject("starOfDay"), "Σημεία Ημέρας"),
            tipsProfit = prog.optDouble("tipsProfit"),
            systemsProfit = prog.optDouble("systemsProfit"),
            systemsProgressive = if (prog.isNull("systemsProgressive")) null else prog.getDouble("systemsProgressive"),
            days = days,
        )
    }

    suspend fun tips(date: String): TipsResponse {
        val json = request("GET", "/api/tips?date=$date", authed = false)
        val arr = json.getJSONArray("tips")
        val list = buildList {
            for (i in 0 until arr.length()) add(parseTip(arr.getJSONObject(i)))
        }
        val sys = if (json.isNull("system")) null else {
            val s = json.getJSONObject("system")
            DaySystem(
                tipDate = s.getString("tipDate"),
                systemName = s.optString("systemName"),
                stake = if (s.isNull("stake")) null else s.getDouble("stake"),
                profit = if (s.isNull("profit")) null else s.getDouble("profit"),
                progressive = if (s.isNull("progressive")) null else s.getDouble("progressive"),
                dayProfit = if (s.isNull("dayProfit")) null else s.getDouble("dayProfit"),
                locked = s.optBoolean("locked", false),
            )
        }
        return TipsResponse(
            tipDate = json.getString("tipDate"),
            today = json.optString("today"),
            isToday = json.optBoolean("isToday"),
            isPast = json.optBoolean("isPast"),
            dayUnlocked = json.optBoolean("dayUnlocked"),
            lockedCount = json.optInt("lockedCount"),
            tips = list,
            system = sys,
            dailyPassEur = json.optDouble("dailyPassEur", 0.5),
        )
    }

    suspend fun createTip(
        matchName: String, market: String, odds: Double, analysis: String, tipDate: String,
        tipTime: String?, sport: String, pickType: String?, starOfDay: Boolean, stake: Double?,
    ): Tip {
        val body = JSONObject()
            .put("matchName", matchName).put("market", market).put("odds", odds)
            .put("analysis", analysis).put("tipDate", tipDate).put("sport", sport)
            .put("starOfDay", starOfDay)
        if (tipTime != null) body.put("tipTime", tipTime)
        if (pickType != null) body.put("pickType", pickType)
        if (stake != null) body.put("stake", stake)
        return parseTip(request("POST", "/api/tips", body))
    }

    suspend fun updateTip(
        id: Int, matchName: String, market: String, odds: Double, analysis: String, tipDate: String,
        tipTime: String?, sport: String, pickType: String?, starOfDay: Boolean, stake: Double?,
        resultWon: Boolean?, resultDetail: String?, profit: Double?,
    ): Tip {
        val body = JSONObject()
            .put("matchName", matchName).put("market", market).put("odds", odds)
            .put("analysis", analysis).put("tipDate", tipDate).put("sport", sport)
            .put("starOfDay", starOfDay)
        if (tipTime != null) body.put("tipTime", tipTime)
        if (pickType != null) body.put("pickType", pickType)
        if (stake != null) body.put("stake", stake)
        if (resultWon != null) body.put("resultWon", resultWon) else body.put("resultWon", JSONObject.NULL)
        if (resultDetail != null) body.put("resultDetail", resultDetail)
        if (profit != null) body.put("profit", profit)
        return parseTip(request("PUT", "/api/tips/$id", body))
    }

    suspend fun deleteTip(id: Int) { request("DELETE", "/api/tips/$id") }

    suspend fun unlockStub(tipDate: String): JSONObject {
        return request(
            "POST", "/api/unlock",
            JSONObject().put("tipDate", tipDate).put("stub", true).put("productId", "daily_pass"),
        )
    }

    private fun parseUser(o: JSONObject) = User(
        id = o.getInt("id"), email = o.getString("email"), name = o.getString("name"), role = o.getString("role"),
    )

    private fun parseTip(o: JSONObject) = Tip(
        id = o.getInt("id"),
        matchName = o.getString("matchName"),
        market = o.getString("market"),
        odds = if (o.isNull("odds")) null else o.getDouble("odds"),
        analysis = if (o.isNull("analysis")) null else o.optString("analysis"),
        tipDate = o.getString("tipDate"),
        tipTime = if (o.isNull("tipTime")) null else o.optString("tipTime"),
        sport = if (o.isNull("sport")) null else o.optString("sport"),
        pickType = if (o.isNull("pickType")) null else o.optString("pickType"),
        starOfDay = o.optBoolean("starOfDay", false),
        stake = if (o.isNull("stake")) null else o.getDouble("stake"),
        resultWon = if (o.isNull("resultWon")) null else o.getBoolean("resultWon"),
        resultDetail = if (o.isNull("resultDetail")) null else o.optString("resultDetail"),
        profit = if (o.isNull("profit")) null else o.getDouble("profit"),
        locked = o.optBoolean("locked", false),
    )
}
