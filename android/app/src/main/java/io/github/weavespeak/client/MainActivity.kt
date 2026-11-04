package io.github.weavespeak.client

import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import com.getcapacitor.BridgeActivity
import com.getcapacitor.WebViewListener

/**
 * The one activity: Capacitor's, plus the four things the stock one does not do.
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
}
