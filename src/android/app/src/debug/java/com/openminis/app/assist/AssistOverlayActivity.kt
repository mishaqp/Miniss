package com.openminis.app.assist

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug prototype of a Gemini-style system-assistant surface.
 *
 * This is a translucent Activity, not TYPE_APPLICATION_OVERLAY: the app the
 * user was looking at remains visible behind the assistant card, while normal
 * Android activity lifecycle/back/IME behaviour stays intact. The LSPosed
 * SystemUI hook still only owns invocation routing; all UI lives in Miniss.
 *
 * The current prototype deliberately reuses the existing ChatScreen pipeline:
 * a prompt (and the pre-overlay screenshot, when available) is handed to a
 * fresh Miniss chat. Once this interaction shell is proven on-device we can
 * keep the response inside this surface instead of transitioning to full chat.
 */
class AssistOverlayActivity : ComponentActivity() {

    private var pendingShot: File? = null
    private var handedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.16f }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Direct activity route (the already-installed v1 AOSP hook) reaches
        // us without AssistTriggerService. Fire capture here as well; the
        // capture coordinator de-duplicates a service+activity double request.
        AssistCapture.requestIfEnabled(applicationContext, intent)
        DeepLinkCoordinator.setPendingAssist(null)

        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = scheme) {
                AssistOverlayContent(
                    onShotReady = { file -> pendingShot = file },
                    onDismiss = { finish() },
                    onExpand = { handoffToChat(prompt = null, voice = false) },
                    onVoice = { handoffToChat(prompt = null, voice = true) },
                    onSend = { prompt -> handoffToChat(prompt = prompt, voice = false) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (!handedOff && isFinishing) {
            pendingShot?.delete()
        }
        super.onDestroy()
    }

    private fun handoffToChat(prompt: String?, voice: Boolean) {
        val normalized = prompt?.trim()?.takeIf { it.isNotEmpty() }
        DeepLinkCoordinator.setPendingAssist(
            text = normalized,
            screenshotPath = pendingShot?.absolutePath,
        )
        handedOff = true

        val route = if (voice) "minis://action/voice_chat" else "minis://action/new_chat"
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(route))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            AppLogger.info(
                TAG,
                "handoff to full chat voice=$voice text=${normalized != null} shot=${pendingShot != null}",
            )
        } catch (t: Throwable) {
            handedOff = false
            AppLogger.warning(TAG, "handoff failed: ${t.message}")
            return
        }
        finish()
    }

    companion object {
        private const val TAG = "AssistOverlay"
    }
}

@Composable
private fun AssistOverlayContent(
    onShotReady: (File?) -> Unit,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onVoice: () -> Unit,
    onSend: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var captureResolved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val shot = AssistCapture.awaitPendingShot()
        onShotReady(shot)
        preview = if (shot != null) {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(shot.absolutePath)?.asImageBitmap()
            }
        } else {
            null
        }
        captureResolved = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Transparent click-catcher. Window dim provides the visual separation
        // while leaving the underlying app recognisable, like Gemini's panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .heightIn(min = 230.dp, max = 560.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "Miniss",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onExpand) {
                        Icon(
                            imageVector = Icons.Filled.OpenInFull,
                            contentDescription = stringResource(R.string.assist_overlay_expand),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.assist_overlay_close),
                        )
                    }
                }

                when {
                    preview != null -> {
                        Image(
                            bitmap = requireNotNull(preview),
                            contentDescription = stringResource(R.string.assist_overlay_screen_attached),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(118.dp)
                                .clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    !captureResolved -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.assist_overlay_capturing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.assist_overlay_no_screen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.assist_overlay_prompt)) },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (prompt.isNotBlank()) onSend(prompt)
                        },
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onVoice) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.assist_overlay_voice),
                                )
                            }
                            IconButton(
                                onClick = { if (prompt.isNotBlank()) onSend(prompt) },
                                enabled = prompt.isNotBlank(),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.assist_overlay_send),
                                )
                            }
                        }
                    },
                )

                if (preview != null) {
                    Text(
                        text = stringResource(R.string.assist_overlay_screen_attached),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
