package gr.bettinginvestor.tips.data


/**
 * Newest betting day first; within a day, latest real kickoff first.
 * tipDate is the betting day (including after-midnight NBA listed under that day).
 * Sort key within day uses parsed kickoff minutes (02:30 > 23:00 for ordering? NO —
 * "latest kickoff on top": 23:00 above 16:00 above 02:30). After-midnight (e.g. 02:30)
 * is still on that tipDate but sorts by real clock time, so morning/early-AM is below evening.
 */
object TipSort {
    /** Minutes from midnight; ranges/tilde stripped; unknown → -1 */
    fun kickoffMinutes(time: String?): Int {
        if (time.isNullOrBlank()) return -1
        var s = time.trim().trimStart('~').trim()
        // take start of range 21:00–22:00 or 21:00-22:00
        s = s.split('–', '-', '—').first().trim()
        val m = Regex("""(\d{1,2}):(\d{2})""").find(s) ?: return -1
        val h = m.groupValues[1].toIntOrNull() ?: return -1
        val min = m.groupValues[2].toIntOrNull() ?: return -1
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
