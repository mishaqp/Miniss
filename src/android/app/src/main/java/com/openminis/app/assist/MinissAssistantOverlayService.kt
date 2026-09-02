/*
 * Overlay window lifecycle adapted from Eta's assistant panel architecture.
 * Required Notice: Copyright © 2026 蛮吉 (Mangi-11).
 * Eta portions are used under the PolyForm Noncommercial License 1.0.0.
 * See THIRD_PARTY_LICENSES.md.
 */
package com.openminis.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.MinisApp
import com.openminis.app.MainActivity
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * The real system-assistant surface. It is a Service-owned
 * TYPE_APPLICATION_OVERLAY window, so a normal assist question never starts
 * MainActivity and never creates a second LLM runtime.
 */
internal class MinissAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null

    private var viewModel: ChatViewModel? = null
    private var draftSessionId: String? = null
    private var screenshotAwaitJob: Job? = null
    private var initialContext: String? = null
    private var firstPromptSent = false
    private var closing = false
    private var handoffRequested = false

    private var screenshotFile by mutableStateOf<File?>(null)
    private var screenshotSelected by mutableStateOf(false)
    private var screenshotConsumed by mutableStateOf(false)
    private var windowVisible by mutableStateOf(false)
    private var conversationReady by mutableStateOf(false)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        activeService = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            requestClose()
            return START_NOT_STICKY
        }
        if (closing || handoffRequested) return START_NOT_STICKY

        val app = application as? MinisApp
        if (app == null || !app.subsystemsReady()) {
            AppLogger.warning(TAG, "app repositories are not ready; assist overlay skipped")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            AppLogger.warning(TAG, "SYSTEM_ALERT_WINDOW is not granted; opening overlay settings")
            openOverlaySettings()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val suppliedContext = intent?.getStringExtra(EXTRA_CONTEXT_TEXT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (initialContext.isNullOrBlank() && suppliedContext != null) {
            initialContext = suppliedContext
        }

        val suppliedShot = intent?.getStringExtra(EXTRA_SCREENSHOT_PATH)
            ?.let { File(it).takeIf(File::exists) }
        if (screenshotFile == null && suppliedShot != null && !firstPromptSent) {
            screenshotFile = suppliedShot
            screenshotSelected = true
        }

        if (viewModel == null) {
            createAssistViewModel(app)
        }

        if (windowView == null) {
            showWindow()
        }

        // AssistTriggerService and the framework session start capture before
        // starting this service. Consume that one-shot result only after the
        // overlay exists; no second capture is started here, which preserves
        // attach_screen=false for power-button invocations.
        if (screenshotFile == null && screenshotAwaitJob == null) {
            awaitPendingScreenshot()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeService === this) activeService = null
        screenshotAwaitJob?.cancel()
        screenshotAwaitJob = null
        if (!handoffRequested) {
            viewModel?.cancelStream()
        }
        removeWindow()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun createAssistViewModel(app: MinisApp) {
        val draft = "__new__assist_${UUID.randomUUID()}"
        draftSessionId = draft
        val owner = ChatViewModelStore.ownerFor(draft)
        viewModel = ViewModelProvider(
            owner,
            ChatViewModel.factory(
                sessionId = draft,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )[ChatViewModel::class.java]
    }

    private fun showWindow() {
        val vm = viewModel ?: return
        if (windowView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            AppLogger.warning(TAG, "WindowManager unavailable; assist overlay skipped")
            stopSelf()
            return
        }

        val view = ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@MinissAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@MinissAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    requestClose()
                    true
                } else {
                    false
                }
            }
            setContent {
                val prefs = getAppearancePrefs(this@MinissAssistantOverlayService)
                val darkTheme = when (prefs.getInt(KEY_THEME_MODE, 0)) {
                    1 -> false
                    2 -> true
                    else -> isSystemInDarkTheme()
                }
                MinisTheme(
                    darkTheme = darkTheme,
                    fontScale = fontScaleForLevel(prefs.getInt(KEY_FONT_APP_BASE, 0)),
                ) {
                    MinissAssistPanel(
                        viewModel = vm,
                        screenshot = screenshotFile,
                        screenshotSelected = screenshotSelected,
                        screenshotConsumed = screenshotConsumed,
                        screenContext = initialContext,
                        canOpenConversation = conversationReady,
                        visible = windowVisible,
                        onScreenshotSelectedChange = { screenshotSelected = it },
                        onSend = ::sendPrompt,
                        onStop = { vm.cancelStream() },
                        onClose = ::requestClose,
                        onOpenConversation = ::openConversation,
                    )
                }
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "MinissAssistantOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val added = runCatching {
            wm.addView(view, params)
            true
        }.getOrElse { throwable ->
            AppLogger.warning(
                TAG,
                "overlay addView failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
            )
            false
        }
        if (!added) {
            draftSessionId?.let(ChatViewModelStore::release)
            stopSelf()
            return
        }

        windowManager = wm
        windowView = view
        windowVisible = true
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun awaitPendingScreenshot() {
        if (screenshotFile != null || screenshotAwaitJob?.isActive == true || firstPromptSent) return
        screenshotAwaitJob = serviceScope.launch(Dispatchers.IO) {
            val shot = AssistCapture.awaitPendingShot()
            withContext(Dispatchers.Main.immediate) {
                screenshotAwaitJob = null
                if (shot == null) {
                    AppLogger.info(TAG, "no pending assist screenshot")
                } else if (!closing && !firstPromptSent && shot.exists()) {
                    screenshotFile = shot
                    screenshotSelected = true
                    AppLogger.info(TAG, "assist screenshot is ready: ${shot.name}")
                } else {
                    shot.delete()
                }
            }
        }
    }

    private fun sendPrompt(text: String) {
        val vm = viewModel ?: return
        val clean = text.trim()
        if (clean.isEmpty() || vm.isStreaming.value) return

        serviceScope.launch {
            if (!firstPromptSent) {
                // A user can type faster than Accessibility can write the
                // image. Give the already-started capture a short chance to
                // finish so the first prompt remains the attachment boundary.
                screenshotAwaitJob?.let { job ->
                    withTimeoutOrNull(SCREENSHOT_SEND_WAIT_MS) { job.join() }
                }

                val shot = screenshotFile
                if (screenshotSelected && shot != null && shot.exists()) {
                    val attached = vm.addAttachmentFromStagedShare(shot) != null
                    screenshotConsumed = attached
                    AppLogger.info(TAG, "assist screenshot attached to first prompt=$attached")
                }
                firstPromptSent = true
                vm.sendAssistMessage(clean, initialContext)
                watchForDraftPromotion(vm)
            } else {
                vm.sendMessage(clean)
            }
        }
    }

    private fun watchForDraftPromotion(vm: ChatViewModel) {
        serviceScope.launch {
            repeat(DRAFT_PROMOTION_POLLS) {
                if (vm.currentSessionId != draftSessionId &&
                    !vm.currentSessionId.startsWith("__new__")
                ) {
                    conversationReady = true
                    return@launch
                }
                delay(DRAFT_PROMOTION_POLL_MS)
            }
        }
    }

    private fun requestClose() {
        if (closing) return
        closing = true
        windowVisible = false

        val vm = viewModel
        vm?.cancelStream()
        serviceScope.launch {
            delay(CLOSE_ANIMATION_MS)
            removeWindow()

            // The user did not explicitly promote this conversation. Wait for
            // the native ChatViewModel job to unwind before deleting its DB
            // rows; this prevents a late finalizer from recreating residue.
            vm?.awaitStreamCompletion()
            val sid = vm?.currentSessionId ?: draftSessionId
            val app = application as? MinisApp
            if (sid != null && !sid.startsWith("__new__") && app?.subsystemsReady() == true) {
                withContext(Dispatchers.IO) {
                    runCatching { app.chatRepository.deleteSession(sid) }
                        .onFailure { error ->
                            AppLogger.warning(TAG, "temporary assist delete failed: ${error.message}")
                        }
                }
            }
            sid?.let { ChatViewModelStore.release(it) }
            screenshotFile?.delete()
            viewModel = null
            stopSelf()
        }
    }

    private fun openConversation() {
        val vm = viewModel ?: return
        if (vm.isStreaming.value || vm.messages.value.isEmpty()) return
        val sid = vm.currentSessionId
        if (sid.startsWith("__new__")) return

        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("minis://session/$sid"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val started = runCatching {
            startActivity(intent)
            true
        }.getOrElse { error ->
            AppLogger.warning(TAG, "failed to open full conversation: ${error.message}")
            false
        }
        if (!started) return

        handoffRequested = true
        closing = true
        windowVisible = false
        serviceScope.launch {
            delay(CLOSE_ANIMATION_MS)
            removeWindow()
            screenshotFile?.delete()
            viewModel = null
            stopSelf()
        }
    }

    private fun openOverlaySettings() {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(packageIntent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { fallback ->
                AppLogger.warning(TAG, "unable to open overlay settings: ${fallback.message}")
            }
        }
    }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback(::requestClose)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun removeWindow() {
        unregisterSystemBackCallback()
        val view = windowView
        val wm = windowManager
        windowView = null
        windowManager = null
        if (view != null && wm != null) {
            runCatching {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                if (view.isAttachedToWindow) wm.removeView(view)
            }.onFailure { error ->
                AppLogger.warning(TAG, "overlay removeView failed: ${error.message}")
            }
        }
    }

    internal companion object {
        const val ACTION_SHOW = "com.openminis.app.assist.action.SHOW_OVERLAY"
        private const val ACTION_DISMISS = "com.openminis.app.assist.action.DISMISS_OVERLAY"
        private const val EXTRA_CONTEXT_TEXT = "com.openminis.app.assist.extra.CONTEXT_TEXT"
        private const val EXTRA_SCREENSHOT_PATH = "com.openminis.app.assist.extra.SCREENSHOT_PATH"
        private const val TAG = "MinissAssistOverlay"
        private const val CLOSE_ANIMATION_MS = 220L
        private const val SCREENSHOT_SEND_WAIT_MS = 3_000L
        private const val DRAFT_PROMOTION_POLL_MS = 50L
        private const val DRAFT_PROMOTION_POLLS = 80

        @Volatile
        private var activeService: MinissAssistantOverlayService? = null

        fun show(
            context: Context,
            trigger: Intent? = null,
            initialContext: String? = null,
            screenshotPath: String? = null,
        ) {
            val appContext = context.applicationContext
            val validShot = screenshotPath?.let { File(it).exists() } == true
            // A repeated gesture while the panel is already visible must not
            // start a second capture that can leak into a later invocation.
            if (activeService == null && !validShot && Settings.canDrawOverlays(appContext)) {
                AssistCapture.requestIfEnabled(appContext, trigger)
            }

            val intent = Intent(appContext, MinissAssistantOverlayService::class.java)
                .setAction(ACTION_SHOW)
            initialContext?.trim()?.takeIf { it.isNotEmpty() }?.let {
                intent.putExtra(EXTRA_CONTEXT_TEXT, it)
            }
            if (validShot) intent.putExtra(EXTRA_SCREENSHOT_PATH, screenshotPath)

            runCatching { appContext.startService(intent) }.onFailure { error ->
                AppLogger.warning(TAG, "failed to start overlay service: ${error.message}")
            }
        }

        fun dismiss(context: Context) {
            val intent = Intent(context.applicationContext, MinissAssistantOverlayService::class.java)
                .setAction(ACTION_DISMISS)
            runCatching { context.applicationContext.startService(intent) }
                .onFailure { error -> AppLogger.warning(TAG, "dismiss request failed: ${error.message}") }
        }
    }
}
