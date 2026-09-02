package com.openminis.app.assist

import android.app.Service
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
 * the next chat as an assist invocation, and then opens the existing
 * `minis://action/new_chat` route. Using the normal NewChat route is important:
 * AppNavigation already mounts it directly on cold start, whereas the older
 * OpenAssist startup path could leave the user on the session list.
 */
class AssistTriggerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.info(TAG, "onStartCommand invoked by system/hook")

        // Capture before MainActivity comes to foreground. The trigger intent
        // carries EXTRA_ATTACH_SCREEN=false for invocation types where a screen
        // attachment is undesirable (for example long-press Power).
        AssistCapture.requestIfEnabled(applicationContext, intent)

        // ChatScreen only waits for an in-flight assist screenshot when this
        // gate is present. The actual image is delivered asynchronously by
        // AssistCapture; text is null because the SystemUI hook has no
        // AssistStructure payload.
        DeepLinkCoordinator.setPendingAssist(null)

        try {
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
        } catch (t: Throwable) {
            // Never let a system-triggered one-shot service crash the app.
            AppLogger.warning(TAG, "failed to launch assist chat: ${t.message}")
        } finally {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AssistTriggerService"
    }
}
