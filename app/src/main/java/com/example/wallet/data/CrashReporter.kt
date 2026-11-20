package com.example.wallet.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the most recent crash stack traces in a private file so the next launch can show them (and they can be copied
 * and sent for diagnosis) without a computer or adb. A stack trace holds class names, line numbers and exception messages.
 * The wallet never puts keys or the seed in an exception message, but messages can include addresses or amounts, so the
 * user decides whether to share the text. It is never uploaded anywhere automatically.
 */
class CrashLog(private val file: File, private val nowMillis: () -> Long = { System.currentTimeMillis() }) {

    /** Appends an entry, keeping only the newest [MAX_TOTAL] characters. Never throws: a crash reporter must not crash. */
    fun record(kind: String, thread: String, e: Throwable) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMillis()))
            val entry = "=== $kind at $stamp UTC on $thread ===\n" + e.stackTraceToString().take(MAX_ENTRY) + "\n"
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((existing + entry).takeLast(MAX_TOTAL))
        } catch (ignored: Throwable) {
        }
    }

    /** The saved text, or null if there is none. */
    fun pending(): String? = try {
        if (file.exists()) file.readText().ifBlank { null } else null
    } catch (t: Throwable) {
        null
    }

    fun clear() {
        try { file.delete() } catch (ignored: Throwable) { }
    }

    companion object {
        const val MAX_ENTRY = 4_000
        const val MAX_TOTAL = 9_000
    }
}

object CrashReporter {
    @Volatile
    var log: CrashLog? = null
        private set

    /** Call once from Application.onCreate. The previous handler still runs afterwards, so the app dies exactly as before. */
    fun install(context: Context) {
        val l = CrashLog(File(context.filesDir, "last_crash.txt"))
        log = l
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            l.record("CRASH", thread.name, e)
            previous?.uncaughtException(thread, e)
        }
    }

    /** For errors the app caught and survived but that should be looked at. */
    fun recordHandled(e: Throwable) {
        log?.record("HANDLED ERROR", Thread.currentThread().name, e)
    }
}
