package gr.bettinginvestor.tips.data

/**
 * Demo-mode auth helpers.
 * Admin is decided by email (trim + lowercase), not by a cached role string.
 */
object DemoAuth {
    const val ADMIN_EMAIL = "bettinginvestor@hotmail.com"

    fun normalizeEmail(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), "")

    fun isAdminEmail(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return normalizeEmail(raw) == ADMIN_EMAIL
    }

    fun roleForEmail(raw: String): String =
        if (isAdminEmail(raw)) "admin" else "user"
}
