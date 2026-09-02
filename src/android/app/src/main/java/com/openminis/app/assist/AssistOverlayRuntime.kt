package com.openminis.app.assist

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Lightweight, non-persistent conversation runtime for the system-assistant
 * overlay. It deliberately does not create a ChatSessionEntity: invoking the
 * assistant must not litter the normal Minis history with throwaway "New Chat"
 * rows.
 *
 * The runtime uses the same provider config / ProviderFactory as the main chat
 * screen, so Codex OAuth, API-key providers and custom OpenAI-compatible
 * endpoints keep using the user's existing credentials.
 */
internal class AssistOverlayRuntime(
    private val context: Context,
) {
    private val history = mutableListOf<LLMMessage>()

    suspend fun send(
        prompt: String,
        screenshot: File?,
        onModelResolved: (String) -> Unit = {},
        onThinking: () -> Unit = {},
        onText: (String) -> Unit,
    ): String {
        val app = context.applicationContext as? MinisApp
            ?: error("Minis application is unavailable")
        val providerRepository = app.providerRepositoryOrNull
            ?: error("Minis is still starting; try the assistant again in a moment")

        providerRepository.awaitConfigLoaded()
        val entry = resolveEntry(providerRepository)
            ?: error("No enabled model is available for the assistant")
        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: error("The selected provider no longer exists")
        if (!instance.isEnabled || !instance.providerType.isUsable) {
            error("The selected provider is disabled or unsupported")
        }

        val key = providerRepository.usableApiKey(instance)
        if (key == null && instance.credentialType != ProviderCredential.oauth) {
            error("The selected provider is not authenticated")
        }

        val model = entry.model
        val provider = ProviderFactory.create(
            instance = instance,
            apiKey = key.orEmpty(),
            model = model,
            context = context,
        )
        onModelResolved(model.displayName.ifBlank { model.id })

        val imageParts = screenshot
            ?.takeIf { it.isFile }
            ?.let { file ->
                runCatching {
                    listOf(
                        LLMMessage.ImagePart(
                            data = file.readBytes(),
                            mimeType = "image/jpeg",
                        )
                    )
                }.getOrDefault(emptyList())
            }
            .orEmpty()

        val userMessage = LLMMessage(
            role = LLMMessage.Role.USER,
            content = prompt,
            imageParts = imageParts,
        )
        val requestHistory = history + userMessage
        val answer = StringBuilder()

        try {
            provider.streamMessage(
                messages = requestHistory,
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = provider.effectiveMaxOutputTokens(model)
                    .coerceAtMost(MAX_OUTPUT_TOKENS)
                    .coerceAtLeast(MIN_OUTPUT_TOKENS),
            ).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.Text -> {
                        answer.append(chunk.text)
                        onText(answer.toString())
                    }
                    is LLMStreamChunk.ThinkingDelta -> onThinking()
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

        val finalText = answer.toString().trim()
        history += userMessage
        if (finalText.isNotEmpty()) {
            history += LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = finalText,
            )
        }
        return finalText
    }

    fun clear() {
        history.clear()
    }

    private fun resolveEntry(repository: ProviderRepository): ModelEntry? {
        val config = repository.config.value

        // Match the main app's new-chat intent: a configured primary group wins.
        val defaultGroupEntry = config.defaultPrimaryGroupId
            ?.let { groupId -> config.modelGroups.firstOrNull { it.id == groupId } }
            ?.memberEntryIds
            ?.asSequence()
            ?.mapNotNull { memberId -> config.modelEntries.firstOrNull { it.id == memberId } }
            ?.firstOrNull { entry -> isEntryUsable(repository, entry) }
        if (defaultGroupEntry != null) return defaultGroupEntry

        // Otherwise prefer what the user was just using in Minis.
        repository.lastUsedVisibleEntry()
            ?.takeIf { isEntryUsable(repository, it) }
            ?.let { return it }

        // Last-resort fallback for a fresh install/config where no last-used id
        // exists yet. Keep this deterministic and never pick a hidden/disabled
        // provider entry.
        return config.modelEntries.firstOrNull { entry ->
            !entry.isHidden && isEntryUsable(repository, entry)
        }
    }

    private fun isEntryUsable(repository: ProviderRepository, entry: ModelEntry): Boolean {
        val instance = repository.instance(entry.providerInstanceId) ?: return false
        return instance.isEnabled && instance.providerType.isUsable
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 4_096
        const val MIN_OUTPUT_TOKENS = 512
        const val SYSTEM_PROMPT =
            "You are Miniss, a concise Android system assistant. Reply in the user's language. " +
                "When a screenshot is attached, use it as screen context and do not invent details " +
                "that are not visible in the screenshot. Keep answers useful and direct."
    }
}
