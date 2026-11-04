package io.github.weavespeak.client

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebChromeClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Capacitor's chrome client with fullscreen that actually happens, and chosen files the page
 * can actually read.
 *
 * Fullscreen: the stock client answers every onShowCustomView with an immediate
 * onCustomViewHidden, which the page sees as requestFullscreen() succeeding and fullscreen
 * ending in the same breath. Here the custom view is laid over the window and the system
 * bars step aside (immersive: a swipe brings them back briefly).
 *
 * Files: a picture chosen from a gallery reaches the page as a content:// link back into the
 * gallery app, which the WebView re-checks every time the file is read. On newer Android
 * (seen on a Galaxy S20 FE, Android 13) the provider answers differently the second time,
 * and the read is refused: the page saw "Failed to fetch" on upload, then "could not be
 * read" once it read the picture up front. So each chosen file is copied into this app's own
 * cache first, and the page is handed that local copy, which nothing else can change.
 *
 * Everything else — microphone and camera permission prompts, camera capture — is inherited.
 */
class WeaveChromeClient(private val bridge: Bridge) : BridgeWebChromeClient(bridge) {

    private var customView: View? = null
    private var callback: CustomViewCallback? = null

    private var pickCallback: ValueCallback<Array<Uri>>? = null
    private val copier = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Registered here, in the activity's onCreate, because a launcher registered after the
    // activity has started throws.
    private val picker = bridge.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pickCallback ?: return@registerForActivityResult
        pickCallback = null
        val data = result.data
        val picked: List<Uri> = if (result.resultCode != Activity.RESULT_OK || data == null) {
            emptyList()
        } else {
            data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
        }
        if (picked.isEmpty()) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        // Off the main thread: a photo can be several megabytes.
        copier.execute {
            val local = picked.map { copyToCache(it) ?: it }
            main.post { cb.onReceiveValue(local.toTypedArray()) }
        }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        // Taking a photo with the camera is Capacitor's path, and already hands over a file
        // of this app's own.
        if (fileChooserParams.isCaptureEnabled) {
            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
        }
        pickCallback?.onReceiveValue(null)
        pickCallback = filePathCallback
        val intent = fileChooserParams.createIntent()
        if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val types = fileChooserParams.acceptTypes.filter { it.contains('/') }
        if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        try {
            picker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pickCallback = null
            filePathCallback.onReceiveValue(null)
        }
        return true
    }

    /** A copy of the chosen file in this app's cache, or null to hand over the original. */
    private fun copyToCache(uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        val context = bridge.context
        return try {
            val dir = File(context.cacheDir, "picked").apply { mkdirs() }
            // Nothing here is needed once its upload is done; an hour is generous.
            val hourAgo = System.currentTimeMillis() - 3_600_000
            dir.listFiles()?.forEach { if (it.lastModified() < hourAgo) it.deleteRecursively() }

            val resolver = context.contentResolver
            var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "picture"
            name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
            // The page reads the type off the extension, so a name without one gets it back.
            if (!name.contains('.')) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))?.let { name += ".$it" }
            }
            // A folder per pick, so two pictures with the same name cannot overwrite each other.
            val out = File(File(dir, System.nanoTime().toString()).apply { mkdirs() }, name)
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source -> out.outputStream().use { source.copyTo(it) } }
            Uri.fromFile(out)
        } catch (e: Exception) {
            AppLog.write(context, "warn", "could not copy a chosen file: ${e.javaClass.simpleName}")
            null
        }
    }

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
