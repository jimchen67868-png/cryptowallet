package com.example.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCrashGuardTest {
    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "File.kt", 1)

    private fun npe(vararg frames: StackTraceElement): NullPointerException = NullPointerException().also { it.stackTrace = arrayOf(*frames) }

    private val legacy = "androidx.compose.foundation.text.input.internal.LegacyCursorAnchorInfoController"
    private val older = "androidx.compose.ui.text.input.CursorAnchorInfoController"

    @Test fun recognisesTheKnownComposeBug() {
        val g = CursorBugGuard()
        assertTrue(g.isKnownComposeBug(npe(frame(legacy, "updateCursorAnchorInfo"), frame("a.B", "c"))))
        assertTrue("older Compose versions use another package", g.isKnownComposeBug(npe(frame(older, "updateCursorAnchorInfo"))))
        assertTrue("it may sit a few frames down", g.isKnownComposeBug(npe(frame("x.A", "a"), frame("x.B", "b"), frame(legacy, "updateCursorAnchorInfo"))))
    }

    @Test fun ignoresEverythingElse() {
        val g = CursorBugGuard()
        assertFalse("another null pointer", g.isKnownComposeBug(npe(frame("com.example.wallet.ui.WalletViewModel", "confirmBridge"))))
        assertFalse("same place, other method", g.isKnownComposeBug(npe(frame(legacy, "updateTextLayoutResult"))))
        assertFalse("wrong exception type", g.isKnownComposeBug(IllegalStateException().also { it.stackTrace = arrayOf(frame(legacy, "updateCursorAnchorInfo")) }))
        assertFalse("deep in the stack is somebody else's problem", g.isKnownComposeBug(npe(*Array(8) { frame("x.A", "m$it") }, frame(legacy, "updateCursorAnchorInfo"))))
        assertFalse("no stack at all", g.isKnownComposeBug(npe()))
        assertFalse(g.isKnownComposeBug(OutOfMemoryError()))
    }

    @Test fun onlyTheKnownBugIsSwallowed() {
        val g = CursorBugGuard()
        assertTrue(g.shouldSwallow(npe(frame(legacy, "updateCursorAnchorInfo"))))
        assertFalse(g.shouldSwallow(RuntimeException("boom")))
        assertFalse(g.shouldSwallow(npe(frame("x.A", "a"))))
    }

    @Test fun aFloodOfTheSameErrorIsNotSwallowedForever() {
        var now = 0L
        val g = CursorBugGuard(maxPerWindow = 3, windowMillis = 1_000L) { now }
        val bug = npe(frame(legacy, "updateCursorAnchorInfo"))
        repeat(3) { assertTrue(g.shouldSwallow(bug)); now += 10 }
        assertFalse("the fourth within the window means swallowing is not working", g.shouldSwallow(bug))
        assertFalse(g.shouldSwallow(bug))
    }

    @Test fun theWindowSlidesSoOccasionalErrorsAreAlwaysFine() {
        var now = 0L
        val g = CursorBugGuard(maxPerWindow = 3, windowMillis = 1_000L) { now }
        val bug = npe(frame(legacy, "updateCursorAnchorInfo"))
        repeat(50) { assertTrue("occurrence $it", g.shouldSwallow(bug)); now += 600 }
    }

    @Test fun defaultsAreSane() {
        var now = 0L
        val g = CursorBugGuard(nowMillis = { now })
        val bug = npe(frame(legacy, "updateCursorAnchorInfo"))
        var swallowed = 0
        repeat(40) { if (g.shouldSwallow(bug)) swallowed++ }
        assertEquals(20, swallowed)
    }
}
