package io.github.weavespeak.client

import android.content.Context
import com.getcapacitor.JSObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * The app log: logs/app.log in the app's private files, rolled at about a megabyte.
 *
 * The desktop app writes the same kind of file, and for the same reason — a failure that only
 * reaches a console is one no bug report will ever contain. The page's lines arrive already
 * redacted and in batches (src/platform/mobile.js); the native side adds its own few.
 */
object AppLog {

    private const val MAX_BYTES = 1_000_000L
    private val lock = Any()

    private fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }

    fun file(context: Context) = File(dir(context), "app.log")

    fun append(context: Context, lines: List<String>) = synchronized(lock) {
        if (lines.isEmpty()) return@synchronized
        val file = file(context)
        if (file.length() > MAX_BYTES) {
            val previous = File(dir(context), "app.log.1")
            previous.delete()
            file.renameTo(previous)
        }
        file.appendText(lines.joinToString(separator = "") { "$it\n" })
    }

    fun write(context: Context, level: String, message: String) =
        append(context, listOf("${Instant.now()} [$level] [native] $message"))

    /** The newest part of the log, for a bug report. A partial first line is dropped. */
    fun tail(context: Context, maxBytes: Int): JSObject = synchronized(lock) {
        val file = file(context)
        val result = JSObject()
        result.put("path", file.absolutePath)
        if (!file.exists()) {
            result.put("text", "")
            result.put("truncated", false)
            return@synchronized result
        }
        val cap = maxBytes.coerceIn(4_096, 256 * 1_024).toLong()
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val length = minOf(size, cap)
            val bytes = ByteArray(length.toInt())
            raf.seek(size - length)
            raf.readFully(bytes)
            var text = String(bytes, Charsets.UTF_8)
            if (size > length) text = text.substringAfter('\n', text)
            result.put("text", text)
            result.put("truncated", size > length)
        }
        result
    }
}
