package dev.xueshi.qduodroid.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCopyGuardTest {

    @Test
    fun `ignores matching text within ttl and allows different text`() {
        val guard = SelfCopyGuard(ttlMs = 3000L)
        guard.markSelfCopied("hello world", nowMs = 1000L)

        assertTrue(guard.shouldIgnore("hello world", nowMs = 2500L))
        assertFalse(guard.shouldIgnore("different text", nowMs = 2500L))
    }

    @Test
    fun `expires after ttl`() {
        val guard = SelfCopyGuard(ttlMs = 3000L)
        guard.markSelfCopied("hello world", nowMs = 1000L)

        assertFalse(guard.shouldIgnore("hello world", nowMs = 4500L))
    }
}
