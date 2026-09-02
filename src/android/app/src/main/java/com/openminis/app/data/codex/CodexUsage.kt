package com.openminis.app.data.codex

import android.content.Context
import com.openminis.app.auth.OpenAIOAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read-only view of the two rate-limit windows exposed by the Codex backend.
 * It deliberately uses the same encrypted OAuth credential managed by Miniss.
 */
data class CodexUsageSnapshot(
    val primary: CodexUsageWindow? = null,
    val secondary: CodexUsageWindow? = null,
)

data class CodexUsageWindow(
    val usedPercent: Double,
    val windowMinutes: Long? = null,
    val resetsAtEpochSeconds: Long? = null,
) {
    val remainingPercent: Double get() = (100.0 - usedPercent).coerceIn(0.0, 100.0)
}

data class CodexAccountStatus(
    val accountId: String,
    val planType: String?,
    val usage: CodexUsageSnapshot,
)

object CodexUsageRepository {
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun refresh(context: Context, providerInstanceId: String): CodexAccountStatus =
        withContext(Dispatchers.IO) {
            val manager = OpenAIOAuthManager(context.applicationContext, providerInstanceId)
            val token = manager.validAccessToken() ?: error("Codex is not signed in")
            val accountId = manager.accountId?.takeIf { it.isNotBlank() }
                ?: error("ChatGPT account ID was not returned by OAuth")
            val request = Request.Builder()
                .url(USAGE_URL)
                .header("Authorization", "Bearer $token")
                .header("ChatGPT-Account-Id", accountId)
                .header("originator", "codex_cli_rs")
                .header("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string().orEmpty() }
            if (!response.isSuccessful) error("Codex usage request failed: HTTP ${response.code}")
            CodexAccountStatus(
                accountId = accountId,
                planType = manager.planType,
                usage = parse(JSONObject(body)),
            )
        }

    internal fun parse(json: JSONObject): CodexUsageSnapshot {
        val rateLimit = json.optJSONObject("rate_limit")
        return CodexUsageSnapshot(
            primary = rateLimit?.optJSONObject("primary_window")?.toWindow(),
            secondary = rateLimit?.optJSONObject("secondary_window")?.toWindow(),
        )
    }

    private fun JSONObject.toWindow(): CodexUsageWindow? {
        if (!has("used_percent")) return null
        val used = optDouble("used_percent", Double.NaN)
        if (used.isNaN()) return null
        val seconds = optLong("limit_window_seconds", 0L).takeIf { it > 0 }
        val reset = optLong("reset_at", 0L).takeIf { it > 0 }?.let {
            if (it > 10_000_000_000L) it / 1000L else it
        }
        return CodexUsageWindow(
            usedPercent = used.coerceIn(0.0, 100.0),
            windowMinutes = seconds?.div(60L),
            resetsAtEpochSeconds = reset,
        )
    }
}
