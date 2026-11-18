package io.github.weavespeak.client

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import com.getcapacitor.BridgeActivity
import com.getcapacitor.WebViewListener

/**
 * The one activity: Capacitor's, plus the things the stock one does not do.
 *
 *  1. Real fullscreen. Capacitor's chrome client cancels fullscreen the instant it is asked
 *     for, so a stream tile could never fill the screen. WeaveChromeClient replaces it.
 *  2. Edge to edge, so the layout owns the whole screen and pads itself from the safe-area
 *     values (see the --safe-* tokens). Android 15 imposes this anyway; doing it everywhere
 *     means one layout rather than two.
 *  3. The renderer keeps IMPORTANT priority when the window is not visible: the page carries
 *     the call, and Android must not treat it as expendable when the screen goes off.
 *  4. A renderer crash does not take the app down. Capacitor returns false from
 *     onRenderProcessGone when no listener claims it, which kills the process; recreating
 *     the activity builds a fresh WebView and the app signs back in from its saved session.
 *  5. Picture-in-picture: leaving the app during a video call shrinks it to a small window
 *     that keeps playing (and keeps your camera going, since the window is still visible),
 *     rather than hiding it. Only while the page says there is a picture worth keeping
 *     (VoiceBus.pipAllowed); otherwise leaving is leaving.
 *
 * The back button is not handled here. The page decides what Back means (src/ui/back.js),
 * through @capacitor/app, and it never exits — leaving would end the call.
 */
class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(WeaveNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val webView = bridge.webView
        // After super.onCreate, never before: the Bridge installs its own chrome client while
        // it loads the page, and would overwrite anything set earlier.
        webView.webChromeClient = WeaveChromeClient(bridge)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        bridge.addWebViewListener(object : WebViewListener() {
            override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.write(this@MainActivity, "warn",
                    "renderer gone (crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}); recreating")
                recreate()
                return true
            }
        })
    }

    /** Re-stated whenever the page changes its mind, so Android 12+ can enter it by itself. */
    fun applyPipParams() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            setPictureInPictureParams(pipParams())
        } catch (e: IllegalStateException) {
            // Not resumed yet; the next change of mind tries again.
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val muted = VoiceService.muted
        val actions = listOf(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_stat_weave),
                if (muted) "Unmute" else "Mute", if (muted) "Turn your microphone on" else "Turn your microphone off",
                VoiceService.actionIntent(this, !muted),
            ),
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_stat_weave),
                "Hang up", "Leave voice",
                VoiceService.actionIntent(this, null),
            ),
        )
        return PictureInPictureParams.Builder()
            // Portrait, phone-shaped: a face fills it, and a landscape stream letterboxes.
            .setAspectRatio(Rational(9, 16))
            .setActions(actions)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(VoiceBus.pipAllowed) }
            .build()
    }

    /** Android 10 and 11 have no auto-enter: this is the moment somebody is leaving. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && VoiceBus.pipAllowed && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: IllegalStateException) {
                AppLog.write(this, "warn", "picture-in-picture refused: $e")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        VoiceBus.window(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        // The WebView carrying the call goes with this activity. A configuration change
        // recreates it, which is not leaving; anything else is, and the notification must not
        // outlive the call it describes.
        if (isFinishing) VoiceService.end()
        super.onDestroy()
    }
}
