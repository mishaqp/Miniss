package com.openminis.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.logging.AppLogger

/**
 * One system-assistant session. Android may deliver current-screen assist data
 * (AssistStructure + AssistContent) and optionally a screenshot. The session
 * hands those into Minis through the existing deep-link + pending payload path.
 *
 * Some Android/OEM builds correctly select Minis as the active assistant and
 * show the VoiceInteractionSession, but never deliver onHandleAssist(). In that
 * case the previous implementation stayed completely invisible forever because
 * launchMinisChat() was only reached from onHandleAssist().
 *
 * To make invocation reliable, onShow() now arms a short fallback. If no assist
 * payload arrives in time, Minis still opens a fresh assist chat. Right before
 * opening it we ask Accessibility for a screenshot (when enabled) so the agent
 * can still receive screen context on frameworks that omit AssistStructure.
 */
class AssistSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    private val TAG = "AssistSession"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var frameworkShot: Bitmap? = null
    private var showWithScreenshot = false
    private var handedOff = false

    private val SCREENSHOT_WAIT_MS = 350L
    private val ASSIST_PAYLOAD_FALLBACK_MS = 700L

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showWithScreenshot =
            (showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT) != 0
        AppLogger.info(TAG, "session shown flags=$showFlags")

        // Android 17 / some OEM implementations may show the active voice
        // session but never send onHandleAssist(). Without this fallback the
        // session has no visible content and appears to the user as if nothing
        // happened at all.
        mainHandler.postDelayed({
            if (handedOff) return@postDelayed
            AppLogger.warning(
                TAG,
                "assist payload not delivered after ${ASSIST_PAYLOAD_FALLBACK_MS}ms; opening Minis fallback",
            )
            // Capture before MainActivity comes to foreground. ChatScreen will
            // consume the pending Accessibility screenshot, if one succeeds.
            AssistCapture.requestIfEnabled(context)
            handoff(ctx = "", allowEmpty = true)
        }, ASSIST_PAYLOAD_FALLBACK_MS)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            frameworkShot = screenshot
            AppLogger.info(TAG, "framework screenshot received")
        }
    }

    override fun onHandleAssist(
        state: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        super.onHandleAssist(state, structure, content)
        AppLogger.info(TAG, "onHandleAssist called")

        val ctx = AssistContext.flatten(structure, content)
        if (ctx.isBlank()) AppLogger.warning(TAG, "no usable assist context")

        // If Android promised a framework screenshot, wait briefly for it.
        val waitMs =
            if (showWithScreenshot && frameworkShot == null) SCREENSHOT_WAIT_MS else 0L
        mainHandler.postDelayed({ handoff(ctx) }, waitMs)
    }

    /**
     * Unified handoff: persist a framework screenshot when present, publish a
     * pending assist payload, open a new Minis assist chat, then close the voice
     * session. [allowEmpty] is used only by the reliability fallback so an
     * invocation still opens Minis even when Android supplied neither text nor
     * screenshot.
     */
    private fun handoff(ctx: String, allowEmpty: Boolean = false) {
        if (handedOff) return
        handedOff = true

        var shotPath: String? = null
        val shot = frameworkShot
        if (shot != null && AssistCapture.isEnabled(context)) {
            shotPath = AssistCapture.saveFrameworkShot(context, shot)?.absolutePath
        }
        shot?.recycle()
        frameworkShot = null

        if (ctx.isBlank() && shotPath == null && !allowEmpty) {
            AppLogger.warning(TAG, "no assist context and no screenshot; nothing to hand off")
            finish()
            return
        }

        DeepLinkCoordinator.setPendingAssist(
            text = ctx.takeIf { it.isNotBlank() },
            screenshotPath = shotPath,
        )
        launchMinisChat()
        finish()
    }

    private fun launchMinisChat() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("minis://assist"))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            context.startActivity(intent)
            AppLogger.info(TAG, "Minis assist chat launched")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "launch minis chat failed: ${t.message}")
        }
    }
}
