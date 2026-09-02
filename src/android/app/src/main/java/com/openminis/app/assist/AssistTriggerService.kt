package com.openminis.app.assist

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.openminis.app.MainActivity
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.logging.AppLogger

/**
 * Small exported bridge used by OEM or LSPosed/system hooks that can reliably
 * reach a Service but should not depend on VoiceInteractionSession delivery.
 *
 * The bridge captures the screen as early as possible (when enabled), marks
 * the next chat as an assist invocation, and prefers the debug Gemini-style
 * AssistOverlayActivity when that component is packaged. Release builds that
 * do not contain the prototype activity fall back to the existing fresh-chat
 * route without changing production behaviour.
 */
class AssistTriggerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.info(TAG, "onStartCommand invoked by system/hook")

        // Capture before any Miniss window comes to foreground. The trigger
        // intent carries EXTRA_ATTACH_SCREEN=false for invocation types where a
        // screen attachment is undesirable (for example long-press Power).
        AssistCapture.requestIfEnabled(applicationContext, intent)
        DeepLinkCoordinator.setPendingAssist(null)

        try {
            if (!launchOverlayIfPresent(intent)) {
                launchFreshChat()
            }
        } catch (t: Throwable) {
            // Never let a system-triggered one-shot service crash the app.
            AppLogger.warning(TAG, "failed to launch assist UI: ${t.message}")
        } finally {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * The overlay is currently a debug-source-set prototype. Refer to it by
     * class name so the main/release source set still compiles and gracefully
     * falls back when that Activity is not packaged.
     */
    private fun launchOverlayIfPresent(trigger: Intent?): Boolean {
        val overlay = Intent()
            .setClassName(packageName, "$packageName.assist.AssistOverlayActivity")
            .setAction(Intent.ACTION_ASSIST)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )

        trigger?.extras?.let { overlay.putExtras(it) }

        return try {
            startActivity(overlay)
            AppLogger.info(TAG, "launched assist overlay")
            true
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "assist overlay not packaged; falling back to full chat")
            false
        }
    }

    private fun launchFreshChat() {
        val launcher = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("minis://action/new_chat"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        startActivity(launcher)
        AppLogger.info(TAG, "launched fresh assist chat")
    }

    companion object {
        private const val TAG = "AssistTriggerService"
    }
}
