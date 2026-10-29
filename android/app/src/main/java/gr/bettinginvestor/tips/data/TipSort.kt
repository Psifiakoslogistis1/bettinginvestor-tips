package gr.bettinginvestor.tips.data

/**
 * Sort by Greece betting day + local kickoff (Europe/Athens times as stored on tips).
 * Newest day on top; within a day latest HH:mm on top, earliest morning at bottom.
 * Handles "~13:00", ranges "21:00–22:00", missing → bottom of day.
 */
object TipSort {
    /** Minutes from midnight; strips ~ and ranges; unknown → -1 (sorts last when descending). */
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

    fun tipsNewestFirst(list: List<Tip>): List<Tip> =
        list.sortedWith(
            compareByDescending<Tip> { it.tipDate }
                .thenByDescending { kickoffMinutes(it.tipTime) }
                .thenByDescending { it.id },
        )

    fun martingaleNewestFirst(list: List<MartingaleBet>): List<MartingaleBet> =
        list.sortedWith(
            compareByDescending<MartingaleBet> { it.tipDate }
                .thenByDescending { kickoffMinutes(it.time) }
                .thenByDescending { it.id },
        )
}
