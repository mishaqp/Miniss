package com.openminis.app.assist

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.MainActivity
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * System-assistant entry + overlay host.
 *
 * The LSPosed/SystemUI hook already targets this exported service, so keeping
 * the actual overlay lifecycle here gives us two important properties:
 *  1. no intermediate Activity flashes on screen;
 *  2. no second background-service launch is needed on Android 14+.
 *
 * The implementation follows eta-ru's successful architecture: capture the
 * screen before the assistant surface appears, then add a focusable
 * TYPE_APPLICATION_OVERLAY window and keep the temporary conversation entirely
 * inside that window. Normal Minis history is untouched unless the user
 * explicitly opens the main app.
 */
class AssistTriggerService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime by lazy { AssistOverlayRuntime(this) }

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private var backCallback: OnBackInvokedCallback? = null
    private var entryJob: Job? = null
    private var runJob: Job? = null

    private var inputText by mutableStateOf("")
    private var uiState by mutableStateOf(AssistOverlayUiState())
    private var messageSequence = 0L

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AppLogger.info(TAG, "overlay service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.info(TAG, "assistant invocation received")
        beginEntry(intent)
        return START_NOT_STICKY
    }

    private fun beginEntry(trigger: Intent?) {
        entryJob?.cancel()
        cancelRun(resetUi = false)
        removeWindow()
        runtime.clear()
        inputText = ""
        messageSequence = 0L
        uiState = AssistOverlayUiState(
            phase = AssistOverlayPhase.READY,
            status = "Подготавливаю экран…",
        )

        if (!Settings.canDrawOverlays(this)) {
            AppLogger.warning(TAG, "SYSTEM_ALERT_WINDOW is not granted; using full-app fallback")
            launchFullAppFallback()
            return
        }

        entryJob = scope.launch {
            // If an old overlay was on screen, give WindowManager one frame to
            // detach it before AccessibilityService.takeScreenshot snapshots the
            // display. This is the same ordering eta-ru protects explicitly.
            delay(80)
            val captureStarted = AssistCapture.requestIfEnabled(applicationContext, trigger)
            val screenshot = if (captureStarted) {
                AssistCapture.awaitPendingShot(CAPTURE_WAIT_MS)
            } else {
                null
            }

            uiState = AssistOverlayUiState(
                phase = AssistOverlayPhase.READY,
                screenshot = screenshot,
                screenshotSelected = screenshot != null,
                status = if (screenshot != null) "Экран готов" else "Чем могу помочь?",
            )
            showWindow()
        }
    }

    private fun showWindow() {
        if (windowView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            AppLogger.warning(TAG, "WindowManager unavailable")
            stopSelf()
            return
        }

        val view = ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@AssistTriggerService)
            setViewTreeSavedStateRegistryOwner(this@AssistTriggerService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MinisTheme {
                    AssistOverlayPanel(
                        state = uiState,
                        input = inputText,
                        onInputChange = { inputText = it },
                        onToggleScreenshot = {
                            if (uiState.phase != AssistOverlayPhase.PROCESSING && uiState.screenshot != null) {
                                uiState = uiState.copy(
                                    screenshotSelected = !uiState.screenshotSelected,
                                )
                            }
                        },
                        onSubmit = ::submit,
                        onStop = { cancelRun(resetUi = true) },
                        onClose = ::dismissAndStop,
                        onOpenMinis = ::openMinis,
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            title = "MinissAssistantOverlay"
        }

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "overlay addView failed: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf()
            return
        }

        windowManager = wm
        windowView = view
        registerBackCallback(view)
        view.requestFocus()
        AppLogger.info(TAG, "assistant overlay shown")
    }

    private fun submit() {
        val prompt = inputText.trim()
        if (prompt.isEmpty() || runJob != null) return

        val screenshotForTurn = uiState.screenshot.takeIf { uiState.screenshotSelected }
        val userId = nextMessageId()
        val assistantId = nextMessageId()
        inputText = ""
        uiState = uiState.copy(
            phase = AssistOverlayPhase.PROCESSING,
            status = "Думаю…",
            screenshotSelected = false,
            messages = uiState.messages +
                AssistOverlayMessage(userId, isUser = true, text = prompt) +
                AssistOverlayMessage(assistantId, isUser = false, text = ""),
        )

        runJob = scope.launch {
            try {
                val finalText = runtime.send(
                    prompt = prompt,
                    screenshot = screenshotForTurn,
                    onModelResolved = { name ->
                        uiState = uiState.copy(modelName = name, status = "Думаю…")
                    },
                    onThinking = {
                        if (uiState.phase == AssistOverlayPhase.PROCESSING) {
                            uiState = uiState.copy(status = "Думаю…")
                        }
                    },
                    onText = { text ->
                        replaceMessage(assistantId, text)
                        uiState = uiState.copy(status = "Отвечаю…")
                    },
                )

                if (finalText.isBlank()) {
                    replaceMessage(assistantId, "Ответ пуст. Попробуйте сформулировать вопрос иначе.")
                }
                uiState = uiState.copy(
                    phase = AssistOverlayPhase.READY,
                    status = "Готово",
                )
            } catch (cancelled: CancellationException) {
                removeEmptyAssistantTail(assistantId)
                uiState = uiState.copy(
                    phase = AssistOverlayPhase.READY,
                    status = "Остановлено",
                )
            } catch (t: Throwable) {
                removeEmptyAssistantTail(assistantId)
                val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                AppLogger.warning(TAG, "overlay request failed: ${t.javaClass.simpleName}: $detail")
                uiState = uiState.copy(
                    phase = AssistOverlayPhase.ERROR,
                    status = detail,
                )
            } finally {
                runJob = null
            }
        }
    }

    private fun replaceMessage(id: Long, text: String) {
        uiState = uiState.copy(
            messages = uiState.messages.map { message ->
                if (message.id == id) message.copy(text = text) else message
            },
        )
    }

    private fun removeEmptyAssistantTail(id: Long) {
        uiState = uiState.copy(
            messages = uiState.messages.filterNot { it.id == id && it.text.isBlank() },
        )
    }

    private fun cancelRun(resetUi: Boolean) {
        val job = runJob ?: return
        runJob = null
        job.cancel()
        if (resetUi) {
            uiState = uiState.copy(
                phase = AssistOverlayPhase.READY,
                status = "Остановлено",
            )
        }
    }

    private fun openMinis() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "failed to open main app: ${t.message}")
        }
        dismissAndStop()
    }

    private fun launchFullAppFallback() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "full-app fallback failed: ${t.message}")
        } finally {
            stopSelf()
        }
    }

    private fun registerBackCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backDispatcher = dispatcher
        backCallback = callback
    }

    private fun unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = backDispatcher
        val callback = backCallback
        backDispatcher = null
        backCallback = null
        if (dispatcher != null && callback != null) {
            runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }

    private fun removeWindow() {
        unregisterBackCallback()
        val view = windowView
        val wm = windowManager
        windowView = null
        windowManager = null
        if (view != null && wm != null) {
            runCatching {
                if (view.isAttachedToWindow) wm.removeView(view)
            }.onFailure { throwable ->
                AppLogger.warning(TAG, "overlay removeView failed: ${throwable.message}")
            }
        }
    }

    private fun dismissAndStop() {
        entryJob?.cancel()
        entryJob = null
        cancelRun(resetUi = false)
        runtime.clear()
        removeWindow()
        stopSelf()
    }

    override fun onDestroy() {
        entryJob?.cancel()
        runJob?.cancel()
        entryJob = null
        runJob = null
        removeWindow()
        runtime.clear()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        AppLogger.info(TAG, "overlay service destroyed")
        super.onDestroy()
    }

    private fun nextMessageId(): Long = ++messageSequence

    companion object {
        private const val TAG = "AssistTriggerService"
        private const val CAPTURE_WAIT_MS = 1_800L
    }
}
