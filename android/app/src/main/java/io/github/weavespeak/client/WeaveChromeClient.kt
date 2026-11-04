package io.github.weavespeak.client

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebChromeClient

/**
 * Capacitor's chrome client with fullscreen that actually happens.
 *
 * The stock one answers every onShowCustomView with an immediate onCustomViewHidden, which the
 * page sees as requestFullscreen() succeeding and fullscreen ending in the same breath — so a
 * stream tile could never fill the screen. Here the custom view is laid over the window and
 * the system bars step aside (immersive: a swipe brings them back briefly).
 *
 * Everything else — microphone and camera permission prompts in particular — is inherited.
 */
class WeaveChromeClient(private val bridge: Bridge) : BridgeWebChromeClient(bridge) {

    private var customView: View? = null
    private var callback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, cb: CustomViewCallback) {
        if (customView != null) {
            // One at a time; a second request is refused cleanly rather than stacked.
            cb.onCustomViewHidden()
            return
        }
        val activity = bridge.activity
        customView = view
        callback = cb
        val decor = activity.window.decorView as ViewGroup
        decor.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowCompat.getInsetsController(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        val cb = callback
        // Cleared first: telling the page re-enters onHideCustomView, which must find nothing.
        customView = null
        callback = null
        val activity = bridge.activity
        val decor = activity.window.decorView as ViewGroup
        decor.removeView(view)
        WindowCompat.getInsetsController(activity.window, decor).show(WindowInsetsCompat.Type.systemBars())
        cb?.onCustomViewHidden()
    }
}
