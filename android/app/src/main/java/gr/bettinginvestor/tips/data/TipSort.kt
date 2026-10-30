package gr.bettinginvestor.tips.data

/**
 * Sort by Greece tipDate + kickoff.
 * Times 00:00–07:59 on a slate = after-midnight (next calendar morning) → end of slate.
 * Newest day on top; within day latest real kickoff on top (after-midnight last chronologically = top when newest-first).
 */
object TipSort {
    fun kickoffMinutes(time: String?): Int {
        if (time.isNullOrBlank()) return -1
        var s = time.trim()
        if (s.startsWith("~")) s = s.substring(1).trim()
        s = s.split('–', '-', '—').first().trim()
        val m = Regex("""(\d{1,2}):(\d{2})""").find(s) ?: return -1
        val h = m.groupValues[1].toIntOrNull() ?: return -1
        val min = m.groupValues[2].toIntOrNull() ?: return -1
        if (h !in 0..47 || min !in 0..59) return -1
        return h * 60 + min
    }

    /** After-midnight window folded to end of slate (+24h). */
    fun slateSortMinutes(time: String?): Int {
        val m = kickoffMinutes(time)
        if (m < 0) return -1
        return if (m < 8 * 60) m + 24 * 60 else m
    }

    fun tipsNewestFirst(list: List<Tip>): List<Tip> =
        list.sortedWith(
            compareByDescending<Tip> { it.tipDate }
                .thenByDescending { slateSortMinutes(it.tipTime) }
                .thenByDescending { it.id },
        )

    fun martingaleNewestFirst(list: List<MartingaleBet>): List<MartingaleBet> =
        list.sortedWith(
            compareByDescending<MartingaleBet> { it.tipDate }
                .thenByDescending { slateSortMinutes(it.time) }
                .thenByDescending { it.id },
        )
}
