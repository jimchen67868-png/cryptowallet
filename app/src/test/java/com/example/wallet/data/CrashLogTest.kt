package com.example.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashLogTest {
    private fun tempFile(): File = File.createTempFile("crash", ".txt").also { it.delete(); it.deleteOnExit() }

    @Test fun nothingPendingAtFirst() = assertNull(CrashLog(tempFile()).pending())

    @Test fun recordsKindThreadTimeClassAndMessage() {
        val log = CrashLog(tempFile()) { 0L }
        log.record("CRASH", "main", IllegalStateException("bridge exploded"))
        val text = log.pending()!!
        assertTrue(text.contains("=== CRASH at 1970-01-01 00:00:00 UTC on main ==="))
        assertTrue(text.contains("java.lang.IllegalStateException: bridge exploded"))
        assertTrue("the stack trace names this test", text.contains("CrashLogTest"))
    }

    @Test fun clearForgetsEverything() {
        val log = CrashLog(tempFile())
        log.record("CRASH", "main", RuntimeException("x"))
        log.clear()
        assertNull(log.pending())
    }

    @Test fun entriesAreAppendedInOrder() {
        val log = CrashLog(tempFile())
        log.record("CRASH", "main", RuntimeException("first"))
        log.record("HANDLED ERROR", "worker", RuntimeException("second"))
        val text = log.pending()!!
        assertTrue(text.indexOf("first") < text.indexOf("second"))
        assertTrue(text.contains("HANDLED ERROR") && text.contains("on worker"))
    }

    @Test fun oneHugeMessageIsTrimmed() {
        val log = CrashLog(tempFile())
        log.record("CRASH", "main", RuntimeException("m".repeat(50_000)))
        assertTrue(log.pending()!!.length < CrashLog.MAX_ENTRY + 200)
    }

    @Test fun totalSizeIsCappedAndKeepsTheNewest() {
        val log = CrashLog(tempFile())
        repeat(20) { log.record("CRASH", "main", RuntimeException("entry-$it " + "x".repeat(2_000))) }
        val text = log.pending()!!
        assertTrue(text.length <= CrashLog.MAX_TOTAL)
        assertTrue("the newest entry survives", text.contains("entry-19"))
        assertFalse("the oldest is dropped", text.contains("entry-0 "))
    }

    @Test fun neverThrowsEvenIfTheFileCannotBeWritten() {
        val log = CrashLog(File("/nonexistent-dir-for-test/crash.txt"))
        log.record("CRASH", "main", RuntimeException("x"))
        assertNull(log.pending())
        log.clear()
    }

    @Test fun blankFileCountsAsNothingPending() {
        val f = tempFile(); f.writeText("  \n")
        assertNull(CrashLog(f).pending())
    }
}
