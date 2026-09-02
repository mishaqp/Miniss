package com.openminis.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.openminis.app.logging.AppLogger

/**
 * One system-assistant session. Android may deliver current-screen assist data
 * (AssistStructure + AssistContent) and optionally a screenshot. The session
 * hands those into the shared Miniss assistant overlay service.
 *
 * Some Android/OEM builds correctly select Minis as the active assistant and
 * show the VoiceInteractionSession, but never deliver onHandleAssist(). In that
 * case the overlay must still appear even without a framework payload.
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
    private var fallbackRunnable: Runnable? = null

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
        val fallback = Runnable {
            if (!handedOff) {
                AppLogger.warning(
                    TAG,
                    "assist payload not delivered after ${ASSIST_PAYLOAD_FALLBACK_MS}ms; opening Minis fallback",
                )
                // If the framework did not deliver a screenshot, the overlay
                // service bridge starts Accessibility capture before its window.
                handoff(ctx = "")
            }
        }
        fallbackRunnable = fallback
        mainHandler.postDelayed(fallback, ASSIST_PAYLOAD_FALLBACK_MS)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            if (handedOff) {
                screenshot.recycle()
            } else {
                frameworkShot = screenshot
                AppLogger.info(TAG, "framework screenshot received")
            }
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
     * Unified handoff: persist a framework screenshot when present, pass the
     * screen text and image to the shared overlay, then close the voice session.
     * An empty payload is valid: the user should still get the assistant panel.
     */
    private fun handoff(ctx: String) {
        if (handedOff) return
        handedOff = true
        fallbackRunnable?.let(mainHandler::removeCallbacks)
        fallbackRunnable = null

        var shotPath: String? = null
        val shot = frameworkShot
        if (shot != null && AssistCapture.isEnabled(context)) {
            shotPath = AssistCapture.saveFrameworkShot(context, shot)?.absolutePath
        }
        shot?.recycle()
        frameworkShot = null

        MinissAssistantOverlayService.show(
            context = context,
            initialContext = ctx.takeIf { it.isNotBlank() },
            screenshotPath = shotPath,
        )
        finish()
    }
}
