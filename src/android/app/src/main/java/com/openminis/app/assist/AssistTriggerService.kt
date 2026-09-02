package com.openminis.app.assist

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.openminis.app.logging.AppLogger

/**
 * Small exported bridge used by OEM or LSPosed/system hooks that can reliably
 * reach a Service but should not depend on VoiceInteractionSession delivery.
 *
 * The bridge captures the screen as early as possible (when enabled), then
 * hands the invocation to the one-window assistant overlay. MainActivity is
 * deliberately not opened for a normal assistant question.
 */
class AssistTriggerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.info(TAG, "onStartCommand invoked by system/hook")

        // Capture before MainActivity comes to foreground. The trigger intent
        // carries EXTRA_ATTACH_SCREEN=false for invocation types where a screen
        // attachment is undesirable (for example long-press Power).
        AssistCapture.requestIfEnabled(applicationContext, intent)

        MinissAssistantOverlayService.show(this, trigger = intent)
        AppLogger.info(TAG, "requested Miniss assistant overlay")
        stopSelf()

        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AssistTriggerService"
    }
}
