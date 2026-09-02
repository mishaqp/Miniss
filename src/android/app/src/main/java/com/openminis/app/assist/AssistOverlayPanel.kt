package com.openminis.app.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File

internal enum class AssistOverlayPhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class AssistOverlayMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
)

internal data class AssistOverlayUiState(
    val phase: AssistOverlayPhase = AssistOverlayPhase.READY,
    val messages: List<AssistOverlayMessage> = emptyList(),
    val screenshot: File? = null,
    val screenshotSelected: Boolean = false,
    val modelName: String? = null,
    val status: String = "Чем могу помочь?",
)

/**
 * Bottom assistant surface inspired by eta-ru's EtaVoicePanel, adapted to the
 * native Minis Material theme and its provider runtime. The full-screen root is
 * transparent except for a light scrim, so the app the user was looking at
 * stays visible behind the assistant.
 */
@Composable
internal fun AssistOverlayPanel(
    state: AssistOverlayUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onToggleScreenshot: () -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenMinis: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entered = true
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f)),
    ) {
        // Tap outside the card to dismiss, like Gemini/Eta.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        AnimatedVisibility(
            visible = entered,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Miniss",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = state.modelName ?: state.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onOpenMinis) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Открыть Miniss")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }

                    val screenshot = state.screenshot
                    if (screenshot != null && state.messages.none { it.isUser }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClick = onToggleScreenshot)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AsyncImage(
                                model = screenshot,
                                contentDescription = "Снимок текущего экрана",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.screenshotSelected) "Экран прикреплён" else "Экран не прикреплён",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = if (state.screenshotSelected) {
                                        "Нажмите, чтобы не отправлять скриншот"
                                    } else {
                                        "Нажмите, чтобы добавить скриншот"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = if (state.screenshotSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    if (state.messages.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                AssistMessageBubble(message)
                            }
                        }
                    }

                    if (state.phase == AssistOverlayPhase.ERROR) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (state.phase == AssistOverlayPhase.PROCESSING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = state.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (input.isEmpty()) {
                                Text(
                                    text = "Спросить Miniss…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = onInputChange,
                                enabled = state.phase != AssistOverlayPhase.PROCESSING,
                                singleLine = false,
                                maxLines = 4,
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.primary,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (input.isNotBlank() && state.phase != AssistOverlayPhase.PROCESSING) {
                                            keyboard?.hide()
                                            onSubmit()
                                        }
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                            )
                        }

                        Spacer(Modifier.size(4.dp))

                        if (state.phase == AssistOverlayPhase.PROCESSING) {
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Остановить",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (input.isNotBlank()) {
                                        keyboard?.hide()
                                        onSubmit()
                                    }
                                },
                                enabled = input.isNotBlank(),
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape,
                                    ),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить",
                                    tint = if (input.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

@Composable
private fun AssistMessageBubble(message: AssistOverlayMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (message.isUser) 0.86f else 0.96f)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (message.isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                text = message.text.ifEmpty { "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isUser) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
