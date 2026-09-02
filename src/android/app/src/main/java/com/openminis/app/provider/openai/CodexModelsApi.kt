package com.openminis.app.provider.openai

import android.content.Context
import android.os.Build
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fetches the model catalog authenticated as the logged-in ChatGPT/Codex account. */
object CodexModelsApi {
    private const val CLIENT_VERSION = "0.144.5"
    private const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(context: Context, providerInstanceId: String): List<LLMModel> =
        withContext(Dispatchers.IO) {
            val manager = OpenAIOAuthManager(context.applicationContext, providerInstanceId)
            val token = manager.validAccessToken() ?: return@withContext emptyList()
            val request = Request.Builder()
                .url("$MODELS_URL?client_version=$CLIENT_VERSION")
                .header("Authorization", "Bearer $token")
                .header("ChatGPT-Account-Id", manager.accountId.orEmpty())
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", "codex_cli_rs")
                .header(
                    "User-Agent",
                    "codex_cli_rs/$CLIENT_VERSION (Android ${Build.VERSION.RELEASE}; " +
                        "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})",
                )
                .header("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string().orEmpty() }
            if (!response.isSuccessful) return@withContext emptyList()
            val data = runCatching { JSONObject(body).optJSONArray("models") }.getOrNull()
                ?: return@withContext emptyList()
            data.toModels()
        }

    private fun JSONArray.toModels(): List<LLMModel> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (item.optString("visibility", "list") != "list") continue
            val id = item.optString("slug").ifBlank { item.optString("id") }
            if (id.isBlank()) continue
            val rawModalities = item.optJSONArray("input_modalities")
            val input = if (rawModalities == null) {
                listOf("text", "image")
            } else {
                buildList {
                    for (modalityIndex in 0 until rawModalities.length()) {
                        rawModalities.optString(modalityIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.normalizeModalities()?.ifEmpty { listOf("text") } ?: listOf("text")
            }
            add(
                LLMModel(
                    id = id,
                    displayName = item.optString("display_name", id),
                    provider = "OpenAI",
                    inputModalities = input,
                    supportsReasoning =
                        item.optJSONArray("supported_reasoning_levels")?.length()?.let { it > 0 }
                            ?: item.optBoolean("supports_reasoning_summaries", false),
                ),
            )
        }
    }
}
