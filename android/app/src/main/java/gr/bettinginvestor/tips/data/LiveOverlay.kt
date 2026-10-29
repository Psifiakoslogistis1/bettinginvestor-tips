package gr.bettinginvestor.tips.data

/**
 * Merge ESPN/live-scores onto tips/bets by tipId, with matchName fallback
 * when demo seed IDs diverge from server IDs.
 */
object LiveOverlay {
    fun shouldShow(live: LiveScore?): Boolean {
        if (live == null) return false
        return live.live || live.final || !live.score.isNullOrBlank() || live.status.isNotBlank()
    }

    fun label(live: LiveScore): String =
        listOfNotNull(
            live.score?.takeIf { it.isNotBlank() },
            live.status.takeIf { it.isNotBlank() && it != live.score },
        ).joinToString(" · ")

    fun forTip(tip: Tip, byId: Map<Int, LiveScore>, byName: Map<String, LiveScore> = emptyMap()): Tip {
        val live = tip.live ?: byId[tip.id] ?: byName[normName(tip.matchName)]
        return if (live != null) tip.copy(live = live) else tip
    }

    fun forBet(bet: MartingaleBet, byId: Map<Int, LiveScore>, byName: Map<String, LiveScore> = emptyMap()): MartingaleBet {
        val live = bet.live
            ?: bet.tipId?.let { byId[it] }
            ?: byId[bet.id]
            ?: byName[normName(bet.matchName)]
        return if (live != null) bet.copy(live = live) else bet
    }

    fun nameIndexFromTips(tips: List<Tip>, byId: Map<Int, LiveScore>): Map<String, LiveScore> {
        val out = mutableMapOf<String, LiveScore>()
        for (t in tips) {
            val live = t.live ?: byId[t.id] ?: continue
            out[normName(t.matchName)] = live
        }
        return out
    }

    fun normName(name: String): String =
        name.lowercase()
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
