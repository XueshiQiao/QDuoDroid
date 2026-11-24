package dev.xueshi.qduodroid.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.xueshi.qduodroid.overlay.OverlayController
import dev.xueshi.qduodroid.state.MonitorState
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class SelectionAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSelectionRunnable: Runnable? = null
    private var pendingToolbarProbeRunnable: Runnable? = null

    private var lastPathAHitAtMs: Long = 0L
    private var lastPathAText: String = ""
    private var lastToolbarWindowId: Int = -1
    private var lastToolbarHitAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        MonitorState.update {
            it.copy(
                accessibilityEnabled = true,
                accessibilityServiceRunning = true,
            )
        }
        MonitorState.addLog("无障碍选词服务已连接 ✓ (仅监听选区变化与选词条弹出)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearStateAndDisconnect("无障碍选词服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearStateAndDisconnect("无障碍选词服务已停止")
        super.onDestroy()
    }

    private fun clearStateAndDisconnect(logMsg: String) {
        mainHandler.removeCallbacksAndMessages(null)
        pendingSelectionRunnable = null
        pendingToolbarProbeRunnable = null
        if (activeInstance === this) {
            activeInstance = null
        }
        MonitorState.update {
            it.copy(accessibilityServiceRunning = false)
        }
        MonitorState.addLog(logMsg)
    }

    override fun onInterrupt() {
        MonitorState.addLog("无障碍服务被系统中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        totalEvents.incrementAndGet()

        val snap = MonitorState.snapshot.value
        if (!snap.enableAccessibilitySelection || !isScreenInteractive()) {
            ignoredEvents.incrementAndGet()
            return
        }

        val eventPkg = event.packageName?.toString().orEmpty()
        if (eventPkg == packageName && OverlayController.isShowing()) {
            ignoredEvents.incrementAndGet()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleSelectionChangedEvent(event, eventPkg)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val changes = event.windowChanges
                    if ((changes and AccessibilityEvent.WINDOWS_CHANGE_ADDED) == 0) {
                        ignoredEvents.incrementAndGet()
                        windowNoiseDropped.incrementAndGet()
                        return
                    }
                }
                if (OverlayController.isShowing()) {
                    ignoredEvents.incrementAndGet()
                    return
                }
                scheduleToolbarProbe(eventPkg, triggerEventName = "WINDOWS_ADDED")
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (eventPkg == SYSTEM_UI_PACKAGE || eventPkg == packageName || OverlayController.isShowing()) {
                    ignoredEvents.incrementAndGet()
                    return
                }
                scheduleToolbarProbe(eventPkg, triggerEventName = "WINDOW_STATE")
            }

            else -> ignoredEvents.incrementAndGet()
        }
    }

    private fun handleSelectionChangedEvent(event: AccessibilityEvent, pkg: String) {
        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to <= from) {
            pendingSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingSelectionRunnable = null
            ignoredEvents.incrementAndGet()
            return
        }

        val extracted = extractSelectionText(event, from, to)
        val displayPkg = pkg.ifBlank { "unknown" }

        pendingSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!extracted.isNullOrBlank()) {
                val now = SystemClock.uptimeMillis()
                if (extracted == lastPathAText && (now - lastPathAHitAtMs) < SAME_SELECTION_COOLDOWN_MS) {
                    ignoredEvents.incrementAndGet()
                    return@Runnable
                }
                lastPathAText = extracted
                lastPathAHitAtMs = now
                val preview = extracted.replace('\n', ' ').take(36)
                MonitorState.update {
                    it.copy(
                        axSelectionDirectCount = it.axSelectionDirectCount + 1,
                        lastTriggerSummary = "[AX·选区事件] $displayPkg · ${extracted.length} 字",
                    )
                }
                syncCountersToState()
                MonitorState.addLog("[AX·选区事件] 命中 ($displayPkg, $from..$to, ${extracted.length}字): $preview")
                OverlayController.showActionBar(
                    context = applicationContext,
                    text = extracted,
                    triggerSource = "AX·选区事件",
                )
            } else {
                MonitorState.update {
                    it.copy(axSelectionNoTextCount = it.axSelectionNoTextCount + 1)
                }
                syncCountersToState()
                MonitorState.addLog("[AX·选区事件] 检测到选区 ($displayPkg, $from..$to) 但节点未暴露文本，转由选词条探测")
                scheduleToolbarProbe(displayPkg, triggerEventName = "SELECTION_FALLBACK")
            }
        }
        pendingSelectionRunnable = r
        mainHandler.postDelayed(r, SELECTION_DEBOUNCE_MS)
    }

    private fun scheduleToolbarProbe(hintPkg: String, triggerEventName: String) {
        pendingToolbarProbeRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { probeFloatingToolbar(hintPkg, triggerEventName) }
        pendingToolbarProbeRunnable = r
        mainHandler.postDelayed(r, TOOLBAR_PROBE_DEBOUNCE_MS)
    }

    private fun probeFloatingToolbar(hintPkg: String, triggerEventName: String) {
        if (OverlayController.isShowing()) {
            ignoredEvents.incrementAndGet()
            return
        }

        probeCount.incrementAndGet()
        val match = findCopyToolbarMatch()
        if (match == null) {
            probeMissCount.incrementAndGet()
            ignoredEvents.incrementAndGet()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastPathAHitAtMs < SAME_SELECTION_COOLDOWN_MS) {
            MonitorState.addLog("[AX·选词条] 已由选区事件直读命中，跳过重复弹窗 (${match.packageName})")
            syncCountersToState()
            return
        }
        if (match.windowId == lastToolbarWindowId && (now - lastToolbarHitAtMs) < TOOLBAR_COOLDOWN_MS) {
            ignoredEvents.incrementAndGet()
            return
        }

        lastToolbarWindowId = match.windowId
        lastToolbarHitAtMs = now
        val effectivePkg = match.packageName.ifBlank { hintPkg.ifBlank { "unknown" } }
        val directText = tryReadFocusedSelectionText()

        if (!directText.isNullOrBlank()) {
            val preview = directText.replace('\n', ' ').take(36)
            MonitorState.update {
                it.copy(
                    axToolbarFocusDirectCount = it.axToolbarFocusDirectCount + 1,
                    lastTriggerSummary = "[AX·选词条直读] $effectivePkg · ${directText.length} 字",
                )
            }
            syncCountersToState()
            MonitorState.addLog(
                "[AX·选词条] 命中 ($effectivePkg, 按钮「${match.buttonLabel}」, via=$triggerEventName) 且焦点直读到 (${directText.length}字): $preview",
            )
            OverlayController.showActionBar(
                context = applicationContext,
                text = directText,
                triggerSource = "AX·选词条直读",
            )
        } else {
            MonitorState.update {
                it.copy(
                    axToolbarAutoCopyShownCount = it.axToolbarAutoCopyShownCount + 1,
                    lastTriggerSummary = "[AX·选词条+代复制] $effectivePkg · 「${match.buttonLabel}」",
                )
            }
            syncCountersToState()
            MonitorState.addLog(
                "[AX·选词条] 检测到选词条 ($effectivePkg, 按钮「${match.buttonLabel}」, via=$triggerEventName, win=${match.windowId})，已弹出悬浮条（点击动作将自动代复制）",
            )
            OverlayController.showActionBar(
                context = applicationContext,
                text = "",
                triggerSource = "AX·选词条+代复制",
                onAcquireText = { deliver -> performAutoCopy(deliver) },
            )
        }
    }

    private fun performAutoCopy(onResult: (String?) -> Unit) {
        val startedAt = SystemClock.uptimeMillis()
        var completed = false

        fun finishOnce(text: String?, via: String) {
            if (completed) return
            completed = true
            if (!text.isNullOrBlank()) {
                val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(1L)
                MonitorState.update {
                    it.copy(
                        axAutoCopySuccessCount = it.axAutoCopySuccessCount + 1,
                        lastTriggerSummary = "[AX·代复制] $via · ${text.length} 字",
                    )
                }
                syncCountersToState()
                MonitorState.addLog("[AX·代复制] 拿到文本 ($via, ${text.length}字, ${elapsed}ms)")
                onResult(text)
            } else {
                MonitorState.update {
                    it.copy(
                        axAutoCopyFailCount = it.axAutoCopyFailCount + 1,
                        lastTriggerSummary = "[AX·代复制失败] $via",
                    )
                }
                syncCountersToState()
                MonitorState.addLog("[AX·代复制] 未能通过「$via」读取到文本")
                onResult(null)
            }
        }

        ClipboardWatchService.awaitAutoCopyClip(AUTO_COPY_TIMEOUT_MS) { clip ->
            if (clip != null) {
                finishOnce(clip, "Shizuku 剪贴板回传")
            } else {
                val fallback = readClipboardFallback()
                finishOnce(fallback, if (fallback != null) "系统剪贴板兜底" else "超时无文本")
            }
        }

        val clickedLabel = clickLiveCopyButtonNode()
        if (clickedLabel != null) {
            MonitorState.addLog("[AX·代复制] 已自动点击选词条「$clickedLabel」")
        } else {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val copiedViaFocus = if (focused != null) {
                try {
                    focused.performAction(AccessibilityNodeInfo.ACTION_COPY)
                } finally {
                    recycleSafe(focused)
                }
            } else {
                false
            }
            if (copiedViaFocus) {
                MonitorState.addLog("[AX·代复制] 未找到悬浮复制按钮，已对焦点控件执行 ACTION_COPY")
            } else {
                MonitorState.addLog("[AX·代复制] 当前无可用的「复制」按钮或焦点选区")
            }
        }

        if (!MonitorState.snapshot.value.watchServiceRunning) {
            mainHandler.postDelayed({
                if (!completed) {
                    val clip = readClipboardFallback()
                    if (!clip.isNullOrBlank()) {
                        finishOnce(clip, "系统剪贴板直接读取")
                    }
                }
            }, 300L)
        }
    }

    private fun readClipboardFallback(): String? {
        return runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun clickLiveCopyButtonNode(): String? {
        val windowList = runCatching { windows }.getOrNull().orEmpty()
        val dm = resources.displayMetrics
        val maxH = (dm.heightPixels * 0.35f).toInt()
        val maxW = dm.widthPixels

        for (w in windowList) {
            if (!isCandidatePopupWindow(w, maxW, maxH)) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            val rootPkg = root.packageName?.toString().orEmpty()
            if (rootPkg == packageName) {
                recycleSafe(root)
                continue
            }
            val found = findCopyButtonNode(root)
            if (found != null) {
                val label = found.second
                val node = found.first
                val clickTarget = findClickableNodeOrSelf(node)
                val clicked = runCatching {
                    clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }.getOrDefault(false)
                if (clickTarget !== node) recycleSafe(clickTarget)
                recycleSafe(node)
                if (clicked) return label
            }
        }
        return null
    }

    private fun findCopyToolbarMatch(): ToolbarMatch? {
        val windowList = runCatching { windows }.getOrNull().orEmpty()
        if (windowList.isEmpty()) return null

        val dm = resources.displayMetrics
        val maxH = (dm.heightPixels * 0.35f).toInt()
        val maxW = dm.widthPixels

        for (w in windowList) {
            if (!isCandidatePopupWindow(w, maxW, maxH)) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            val rootPkg = root.packageName?.toString().orEmpty()
            if (rootPkg == packageName) {
                recycleSafe(root)
                continue
            }
            val bounds = Rect()
            w.getBoundsInScreen(bounds)
            val found = findCopyButtonNode(root)
            if (found != null) {
                recycleSafe(found.first)
                return ToolbarMatch(
                    windowId = w.id,
                    packageName = rootPkg,
                    buttonLabel = found.second,
                    bounds = bounds,
                )
            }
        }
        return null
    }

    private fun isCandidatePopupWindow(w: AccessibilityWindowInfo, maxW: Int, maxH: Int): Boolean {
        val type = w.type
        if (type != AccessibilityWindowInfo.TYPE_APPLICATION && type != AccessibilityWindowInfo.TYPE_SYSTEM) {
            return false
        }
        val rect = Rect()
        w.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0 && rect.height() < maxH && rect.width() <= maxW
    }

    private fun findCopyButtonNode(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_BFS_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            if (isCopyButtonLabel(label)) {
                while (queue.isNotEmpty()) {
                    recycleSafe(queue.removeFirst())
                }
                return node to (label ?: "复制")
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (queue.size + visited < MAX_BFS_NODES * 2) {
                    queue.add(child)
                } else {
                    recycleSafe(child)
                }
            }
            recycleSafe(node)
        }
        while (queue.isNotEmpty()) {
            recycleSafe(queue.removeFirst())
        }
        return null
    }

    private fun findClickableNodeOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current = node.parent
        var depth = 0
        while (current != null && depth < 3) {
            if (current.isClickable) return current
            val next = current.parent
            recycleSafe(current)
            current = next
            depth++
        }
        if (current != null) recycleSafe(current)
        return node
    }

    private fun extractSelectionText(event: AccessibilityEvent, from: Int, to: Int): String? {
        val source = runCatching { event.source }.getOrNull()
        val sourceText = if (source != null) {
            try {
                source.text?.toString()
            } finally {
                recycleSafe(source)
            }
        } else {
            null
        }
        extractSubstringSafe(sourceText, from, to)?.let { return it }

        val eventJoined = event.text.joinToString("") { it?.toString().orEmpty() }
        return extractSubstringSafe(eventJoined, from, to)
    }

    private fun tryReadFocusedSelectionText(): String? {
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return null
        return try {
            val start = focused.textSelectionStart
            val end = focused.textSelectionEnd
            val full = focused.text?.toString()
            extractSubstringSafe(full, start, end)
        } finally {
            recycleSafe(focused)
        }
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm?.isInteractive ?: true
    }

    @Suppress("DEPRECATION")
    private fun recycleSafe(node: AccessibilityNodeInfo?) {
        if (node != null) {
            runCatching { node.recycle() }
        }
    }

    private data class ToolbarMatch(
        val windowId: Int,
        val packageName: String,
        val buttonLabel: String,
        val bounds: Rect,
    )

    companion object {
        private const val SELECTION_DEBOUNCE_MS = 320L
        private const val TOOLBAR_PROBE_DEBOUNCE_MS = 240L
        private const val SAME_SELECTION_COOLDOWN_MS = 1200L
        private const val TOOLBAR_COOLDOWN_MS = 1500L
        private const val AUTO_COPY_TIMEOUT_MS = 900L
        private const val MAX_BFS_NODES = 40
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private val COPY_LABELS = setOf("复制", "copy", "复制文本")

        private val totalEvents = AtomicInteger(0)
        private val ignoredEvents = AtomicInteger(0)
        private val probeCount = AtomicInteger(0)
        private val probeMissCount = AtomicInteger(0)
        private val windowNoiseDropped = AtomicInteger(0)

        @Volatile
        private var activeInstance: SelectionAccessibilityService? = null

        fun isRunning(): Boolean = activeInstance != null

        fun syncCountersToState() {
            MonitorState.update {
                it.copy(
                    axTotalEvents = totalEvents.get(),
                    axIgnoredEvents = ignoredEvents.get(),
                    axProbeCount = probeCount.get(),
                    axProbeMissCount = probeMissCount.get(),
                    axWindowNoiseDropped = windowNoiseDropped.get(),
                )
            }
        }

        fun resetCounters() {
            totalEvents.set(0)
            ignoredEvents.set(0)
            probeCount.set(0)
            probeMissCount.set(0)
            windowNoiseDropped.set(0)
        }

        fun componentId(packageName: String): String =
            "$packageName/${SelectionAccessibilityService::class.java.name}"

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            }.getOrDefault(false)
            if (!enabled) return false

            val rawServices = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
            if (rawServices.isBlank()) return false

            val full = ComponentName(context, SelectionAccessibilityService::class.java).flattenToString()
            val short = ComponentName(context, SelectionAccessibilityService::class.java).flattenToShortString()
            return rawServices.split(':').any { entry ->
                val trimmed = entry.trim()
                trimmed.equals(full, ignoreCase = true) || trimmed.equals(short, ignoreCase = true)
            }
        }

        internal fun isCopyButtonLabel(label: String?): Boolean {
            val clean = label?.trim()?.lowercase() ?: return false
            return clean in COPY_LABELS
        }

        internal fun extractSubstringSafe(fullText: String?, from: Int, to: Int): String? {
            if (fullText.isNullOrEmpty() || from < 0 || to <= from || from >= fullText.length) {
                return null
            }
            val safeEnd = to.coerceAtMost(fullText.length)
            if (safeEnd <= from) return null
            return fullText.substring(from, safeEnd).trim().takeIf { it.isNotEmpty() }
        }
    }
}
