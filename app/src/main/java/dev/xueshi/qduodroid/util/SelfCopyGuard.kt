package dev.xueshi.qduodroid.util

class SelfCopyGuard(
    private val ttlMs: Long = 3500L,
) {
    @Volatile
    private var lastText: String? = null

    @Volatile
    private var lastMarkedAtMs: Long = 0L

    @Synchronized
    fun markSelfCopied(text: String, nowMs: Long = System.currentTimeMillis()) {
        lastText = text
        lastMarkedAtMs = nowMs
    }

    @Synchronized
    fun shouldIgnore(text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val saved = lastText ?: return false
        val elapsed = nowMs - lastMarkedAtMs
        if (elapsed > ttlMs || elapsed < 0L) {
            lastText = null
            return false
        }
        if (saved == text) {
            return true
        }
        return false
    }

    @Synchronized
    fun clear() {
        lastText = null
        lastMarkedAtMs = 0L
    }

    companion object {
        val shared = SelfCopyGuard()
    }
}
