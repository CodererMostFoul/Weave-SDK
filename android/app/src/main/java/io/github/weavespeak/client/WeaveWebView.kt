package io.github.weavespeak.client

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.getcapacitor.CapacitorWebView

/**
 * Capacitor's WebView, which during a call keeps telling Chromium that it is on screen.
 *
 * Chromium freezes a hidden page, and a call does not exempt it. Measured on the S9
 * (Android 10, WebView 157) with the voice service running, the microphone open and the
 * phone in call mode: the page froze exactly 60 seconds after the screen went off. A frozen
 * page's AudioContext stops rendering while its state still reads 'running', so every voice
 * in the room stops playing and nothing in the page can notice; its timers stop too, so the
 * heartbeat goes quiet and the server lets the peer go about two minutes later. The
 * foreground service (VoiceService) keeps the PROCESS alive; it does nothing for the page
 * inside it.
 *
 * Chromium learns that the page is hidden from the window's visibility, which Android reports
 * as GONE when the activity stops (the screen going off, or another app in front). While
 * held, that report is replaced with VISIBLE. Nothing else changes: the activity is still
 * stopped and the window still draws nothing. What the call costs is a page that keeps its
 * foreground pace — timers at their normal rate — for as long as the call lasts, which is
 * what a call needs. The room stops its own animations in the background (applyMotionPrefs
 * in src/room/index.js), because document.hidden no longer does it for them.
 *
 * Let go, it reports the window as it really is, so a page that leaves voice in the
 * background becomes hidden, and freezable, as usual.
 *
 * Installed through res/layout/capacitor_bridge_layout_main.xml, which takes the place of
 * Capacitor's own layout of the same name.
 */
class WeaveWebView(context: Context, attrs: AttributeSet?) : CapacitorWebView(context, attrs) {

    private var holding = false

    /** In voice (true) or not. Safe to repeat. */
    fun holdVisible(on: Boolean) {
        if (on == holding) return
        holding = on
        super.onWindowVisibilityChanged(if (on) View.VISIBLE else windowVisibility)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (holding) View.VISIBLE else visibility)
    }
}
