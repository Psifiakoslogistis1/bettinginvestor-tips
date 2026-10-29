package gr.bettinginvestor.tips.billing

import android.app.Activity
import android.content.Context
import gr.bettinginvestor.tips.BuildConfig

/**
 * Placeholder for Google Play Billing (consumable product id: daily_pass).
 * Debug builds use STUB_BILLING / LocalDemoStore.unlock — no Play Console needed.
 */
class BillingHelper(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun isStub(): Boolean = BuildConfig.STUB_BILLING

    fun launchPurchase(
        @Suppress("UNUSED_PARAMETER") activity: Activity,
        onToken: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (BuildConfig.STUB_BILLING) {
            onToken("stub-token-${System.currentTimeMillis()}")
        } else {
            onError("Ρύθμισε το Play Console product daily_pass και αντικατέστησε το BillingHelper.")
        }
    }
}
