package com.example.wallet.data

import android.os.Handler
import android.os.Looper

/**
 * Compose's text field has a long-standing bug (Google issue tracker 313010266): while the screen is being laid out, the
 * keyboard's cursor-position update (`LegacyCursorAnchorInfoController.updateCursorAnchorInfo`) can hit a null and throw
 * a NullPointerException, which closes the whole app. It depends on the keyboard app and on timing, so it shows up
 * "sometimes", and it is reported against many Compose versions, so upgrading is not a sure fix.
 *
 * This guard swallows ONLY that exact error and lets the app carry on (the worst case is the keyboard missing one cursor
 * update). Any other exception is rethrown and crashes as before, and an error that keeps repeating is rethrown too.
 */
class CursorBugGuard(
    private val maxPerWindow: Int = 20,
    private val windowMillis: Long = 2_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val recent = ArrayDeque<Long>()

    /** True for the known Compose cursor-anchor NullPointerException (top of the stack only), and nothing else. */
    fun isKnownComposeBug(e: Throwable): Boolean =
        e is NullPointerException && e.stackTrace.take(6).any {
            it.className.endsWith("CursorAnchorInfoController") && it.methodName == "updateCursorAnchorInfo"
        }

    /** Whether to swallow [e]. A flood of the same error means swallowing is not helping, so it stops and the app crashes. */
    fun shouldSwallow(e: Throwable): Boolean {
        if (!isKnownComposeBug(e)) return false
        val now = nowMillis()
        while (recent.isNotEmpty() && now - recent.first() > windowMillis) recent.removeFirst()
        if (recent.size >= maxPerWindow) return false
        recent.addLast(now)
        return true
    }
}

object UiCrashGuard {
    /**
     * Call once from Application.onCreate. Runs the main thread's message loop inside a try/catch so the one known error
     * above does not end the process. Everything else propagates to the uncaught-exception handler as usual.
     */
    fun install() {
        val guard = CursorBugGuard()
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    if (!guard.shouldSwallow(e)) throw e
                }
            }
        }
    }
}
