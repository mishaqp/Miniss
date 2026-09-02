/*
 * Overlay UX adapted from Eta's assistant panel architecture.
 * Required Notice: Copyright © 2026 蛮吉 (Mangi-11).
 * Eta portions are used under the PolyForm Noncommercial License 1.0.0.
 * See THIRD_PARTY_LICENSES.md.
 */
package com.openminis.app.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatViewModel
import java.io.File

/**
 * Compact assistant surface hosted by [AssistTriggerService]'s overlay window.
 *
 * Unlike the old transparent Activity prototype this never navigates into the
 * main app on send. It talks to a normal Minis [ChatViewModel], so provider
 * selection, agent tools, streaming, memory and model fallbacks stay on the
 * same code path as a regular chat. The main Activity is opened only when the
 * user explicitly taps the expand button.
 */
@Composable
internal fun MinissAssistPanel(
    viewModel: ChatViewModel,
    screenshot: File?,
    screenshotSelected: Boolean,
    screenshotConsumed: Boolean,
    screenContext: String?,
    canOpenConversation: Boolean,
    visible: Boolean,
    onScreenshotSelectedChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val messages by viewModel.uiMessages.collectAsState()
    val streamingById by viewModel.streamingById.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf("") }

    val rendered = remember(messages, streamingById) {
        messages.map { message ->
            val delta = streamingById[message.id]
            message.copy(
                content = delta?.content ?: message.content,
                toolBlocks = delta?.toolBlocks ?: message.toolBlocks,
                isAwaitingModelResponse = delta?.isAwaitingModelResponse
                    ?: message.isAwaitingModelResponse,
            )
        }
    }

    LaunchedEffect(rendered.size, streamingById) {
        if (rendered.isNotEmpty()) listState.animateScrollToItem(rendered.lastIndex)
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.26f else 0f,
        label = "assist_scrim",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .heightIn(min = 124.dp, max = 620.dp)
                    // Consume taps so they do not reach the dismissing scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(30.dp),
                tonalElevation = 8.dp,
                shadowElevation = 14.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Header(
                        modelName = modelName,
                        canOpenConversation = canOpenConversation && rendered.isNotEmpty() && !isStreaming,
                        onOpenConversation = onOpenConversation,
                        onClose = onClose,
                    )

                    if (screenshot != null) {
                        ScreenshotContext(
                            file = screenshot,
                            selected = screenshotSelected,
                            consumed = screenshotConsumed,
                            enabled = !isStreaming && !screenshotConsumed,
                            onSelectedChange = onScreenshotSelectedChange,
                        )
                    }

                    if (!screenContext.isNullOrBlank()) {
                        ScreenContextHint(screenContext)
                    }

                    if (rendered.isEmpty()) {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(
                                text = "Чем могу помочь?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (screenshot != null) {
                                    "Экран готов — спросите о том, что сейчас открыто."
                                } else {
                                    "Задайте вопрос, не покидая текущее приложение."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 330.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rendered, key = { it.id }) { message ->
                                AssistMessageBubble(message)
                            }
                            if (isStreaming && rendered.lastOrNull()?.role != "assistant") {
                                item(key = "assist-thinking") { ThinkingRow() }
                            }
                        }
                    }

                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            enabled = !isStreaming,
                            placeholder = { Text("Спросить Miniss") },
                            maxLines = 4,
                            shape = RoundedCornerShape(22.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val text = input.trim()
                                    if (text.isNotEmpty()) {
                                        input = ""
                                        keyboard?.hide()
                                        onSend(text)
                                    }
                                },
                            ),
                        )

                        if (isStreaming) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Rounded.Stop,
                                        contentDescription = "Остановить",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = if (input.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ) {
                                IconButton(
                                    enabled = input.isNotBlank(),
                                    onClick = {
                                        val text = input.trim()
                                        if (text.isNotEmpty()) {
                                            input = ""
                                            keyboard?.hide()
                                            onSend(text)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        contentDescription = "Отправить",
                                        tint = if (input.isNotBlank()) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    modelName: String,
    canOpenConversation: Boolean,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "✦",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Miniss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (modelName.isNotBlank()) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenConversation, enabled = canOpenConversation) {
            Icon(Icons.Rounded.ExpandLess, contentDescription = "Открыть полный чат")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
        }
    }
}

@Composable
private fun ScreenshotContext(
    file: File,
    selected: Boolean,
    consumed: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelectedChange(!selected) },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = file,
                contentDescription = "Текущий экран",
                modifier = Modifier
                    .size(width = 92.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        consumed -> "Текущий экран прикреплён к первому вопросу"
                        selected -> "Текущий экран прикреплён"
                        else -> "Текущий экран не выбран"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = when {
                        consumed -> "Можно продолжить разговор ниже"
                        selected -> "Нажмите, чтобы не отправлять"
                        else -> "Нажмите, чтобы прикрепить"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenContextHint(context: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = "Текстовый контекст экрана",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = context.trim().take(320),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistMessageBubble(message: ChatMessage) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.9f else 1f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp,
            ),
            color = bubbleColor,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (message.content.isNotBlank()) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                } else if (message.isAwaitingModelResponse || message.isStreaming) {
                    ThinkingRow()
                }

                val tools = message.toolBlocks.filter { it.kind == "tool_use" }
                tools.takeLast(2).forEach { block ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚙ ${block.toolTitle.ifBlank { block.toolName.ifBlank { "Инструмент" } }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!message.error.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            "Думаю…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
