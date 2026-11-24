package dev.xueshi.qduodroid.shizuku

import android.content.ClipData
import android.content.IOnPrimaryClipChangedListener
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import dev.xueshi.qduodroid.IClipboardCallback
import dev.xueshi.qduodroid.IClipboardMonitorService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class ClipboardUserService() : IClipboardMonitorService.Stub() {

    private var clipboardService: Any? = null
    private var activeCallback: IClipboardCallback? = null
    private var linkedBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var clipListener: IOnPrimaryClipChangedListener.Stub? = null
    private val wakeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QDuoWakeExec").apply { isDaemon = true }
    }

    @Volatile
    private var lastText: String? = null

    @Volatile
    private var lastEventAtMs: Long = 0L

    @Volatile
    private var modeStatus: String = "未启动"

    @Volatile
    private var lastDiagnostics: String = ""

    @Volatile
    private var pendingClipText: String? = null

    @Volatile
    private var enableFreezeWakeup: Boolean = true

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
        initClipboardProxy()
    }

    private fun initClipboardProxy() {
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "clipboard") as? IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.content.IClipboard\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                clipboardService = asInterface.invoke(null, binder)
            }
        }.onFailure { err ->
            lastDiagnostics = "IClipboard init failed: ${err.javaClass.simpleName}: ${err.message}"
        }
    }

    override fun destroy() {
        stopWatching()
        wakeExecutor.shutdownNow()
        exitProcess(0)
    }

    override fun startWatching(
        callback: IClipboardCallback?,
        enableFreezeWakeup: Boolean,
    ) {
        stopWatching()
        this.enableFreezeWakeup = enableFreezeWakeup
        activeCallback = callback
        if (callback != null) {
            val binder = callback.asBinder()
            val recipient = IBinder.DeathRecipient {
                destroy()
            }
            runCatching {
                binder.linkToDeath(recipient, 0)
                linkedBinder = binder
                deathRecipient = recipient
            }
        }
        if (clipboardService == null) {
            initClipboardProxy()
        }

        lastText = runCatching { getCurrentClipText() }.getOrNull()
        lastEventAtMs = System.currentTimeMillis()
        lastDiagnostics = buildDiagnosticsText()

        if (clipboardService == null) {
            notifyStatus("IClipboard 不可用")
            return
        }

        val listenerResult = runCatching {
            val listener = object : IOnPrimaryClipChangedListener.Stub() {
                override fun dispatchPrimaryClipChanged() {
                    handleClipEvent()
                }
            }
            invokeClipboardMethod("addPrimaryClipChangedListener", listener)
            clipListener = listener
        }
        if (listenerResult.isSuccess) {
            notifyStatus("IClipboard 事件监听")
        } else {
            val reason = listenerResult.exceptionOrNull()?.message ?: "unknown"
            lastDiagnostics += "\nListener registration failed: $reason"
            notifyStatus("事件监听注册失败: $reason")
        }
    }

    override fun stopWatching() {
        val listener = clipListener
        clipListener = null
        if (listener != null && clipboardService != null) {
            runCatching {
                invokeClipboardMethod("removePrimaryClipChangedListener", listener)
            }
        }
        val recipient = deathRecipient
        val binder = linkedBinder
        deathRecipient = null
        linkedBinder = null
        if (recipient != null && binder != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        activeCallback = null
        modeStatus = "已停止"
    }

    override fun getCurrentClipText(): String? {
        val clip = invokeClipboardMethod("getPrimaryClip") as? ClipData ?: return null
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
            val htmlFallback = item.htmlText
                ?.replace(Regex("<[^>]*>"), "")
                ?.trim()
            if (!htmlFallback.isNullOrBlank()) {
                return htmlFallback
            }
        }
        return null
    }

    override fun getDiagnostics(): String {
        if (lastDiagnostics.isBlank()) {
            lastDiagnostics = buildDiagnosticsText()
        }
        return "$lastDiagnostics\nActiveMode=$modeStatus"
    }

    @Synchronized
    override fun consumePendingClipText(): String? {
        val text = pendingClipText
        pendingClipText = null
        return text
    }

    override fun applyKeepAliveExemptions(packageName: String?): String {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return "empty package"
        val results = mutableListOf<String>()
        val cmds = listOf(
            "whitelist" to arrayOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            "run_bg" to arrayOf("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            "run_any_bg" to arrayOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
        )
        for ((label, cmd) in cmds) {
            val (code, detail) = runShellCommand(cmd, timeoutSec = 3L)
            results.add(if (code == 0) "$label=0" else "$label=$detail")
        }
        return results.joinToString(", ")
    }

    override fun grantAccessibilityWritePermissions(packageName: String?): String {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return "empty package"

        val (grantCode, grantOut) = runShellCommand(
            arrayOf("pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"),
            timeoutSec = 4L,
        )
        val (appOpsCode, appOpsOut) = runShellCommand(
            arrayOf("cmd", "appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow"),
            timeoutSec = 3L,
        )
        return if (grantCode == 0) {
            "ok (appops=$appOpsCode)"
        } else {
            "err(grant=$grantCode:$grantOut, appops=$appOpsCode:$appOpsOut)"
        }
    }

    override fun triggerTestWake() {
        Log.w("QDuoUserSvc", "triggerTestWake requested")
        wakeWatchServiceViaAm("dev.xueshi.qduodroid.action.TEST_WAKE")
    }

    private fun handleClipEvent() {
        var text = runCatching { getCurrentClipText() }.getOrNull()
        if (text.isNullOrBlank()) {
            runCatching { Thread.sleep(120L) }
            text = runCatching { getCurrentClipText() }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            runCatching {
                activeCallback?.onDebugLog("收到剪贴板事件，但读取内容为空（可能非纯文本或系统拦截）")
            }
            return
        }

        val now = System.currentTimeMillis()
        val changed = text != lastText
        val shouldEmit = changed || (now - lastEventAtMs >= DUPLICATE_EVENT_DEBOUNCE_MS)

        if (!shouldEmit) {
            runCatching {
                activeCallback?.onDebugLog("收到重复剪贴板事件，已防抖跳过")
            }
            return
        }

        lastText = text
        lastEventAtMs = now
        synchronized(this) {
            pendingClipText = text
        }

        val callback = activeCallback
        if (callback == null) {
            if (enableFreezeWakeup) {
                wakeWatchServiceViaAm("dev.xueshi.qduodroid.action.WAKE_AND_PULL")
            }
            return
        }

        val delivered = runCatching {
            callback.onClipChanged(text, now)
        }
        if (delivered.isSuccess) {
            synchronized(this) {
                if (pendingClipText == text) {
                    pendingClipText = null
                }
            }
            Log.w("QDuoUserSvc", "direct callback ok (${text.length} chars)")
        } else {
            val err = delivered.exceptionOrNull()
            if (enableFreezeWakeup) {
                Log.w("QDuoUserSvc", "callback failed (${err?.javaClass?.simpleName}), waking service via am")
                wakeWatchServiceViaAm("dev.xueshi.qduodroid.action.WAKE_AND_PULL")
            } else {
                Log.w("QDuoUserSvc", "callback failed (${err?.javaClass?.simpleName}), freeze wakeup disabled")
            }
        }
    }

    private fun wakeWatchServiceViaAm(action: String) {
        wakeExecutor.execute {
            val (code, detail) = runShellCommand(
                arrayOf(
                    "am",
                    "start-foreground-service",
                    "-n",
                    "dev.xueshi.qduodroid/.service.ClipboardWatchService",
                    "-a",
                    action,
                ),
                timeoutSec = 4L,
            )
            if (code != 0) {
                Log.w("QDuoUserSvc", "am wake exit=$code ($detail)")
            }
        }
    }

    private fun runShellCommand(cmd: Array<String>, timeoutSec: Long = 3L): Pair<Int, String> {
        var proc: java.lang.Process? = null
        return try {
            proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            runCatching { proc.outputStream.close() }
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                -1 to "timeout"
            } else {
                proc.exitValue() to output
            }
        } catch (err: Exception) {
            -1 to err.javaClass.simpleName
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    private fun notifyStatus(status: String) {
        modeStatus = status
        runCatching {
            activeCallback?.onStatusChanged(status)
        }
    }

    private fun invokeClipboardMethod(
        methodName: String,
        listener: IOnPrimaryClipChangedListener? = null,
    ): Any? {
        val service = clipboardService ?: error("IClipboard proxy is null")
        val candidates = service.javaClass.methods
            .filter { it.name == methodName }
            .sortedByDescending { it.parameterTypes.size }

        if (candidates.isEmpty()) {
            error("No method named $methodName on ${service.javaClass.name}")
        }

        val errors = mutableListOf<String>()
        for (method in candidates) {
            val args = buildArgsForMethod(method, listener) ?: continue
            val result = runCatching {
                method.isAccessible = true
                method.invoke(service, *args)
            }
            if (result.isSuccess) {
                return result.getOrNull()
            } else {
                val ex = result.exceptionOrNull()
                val cause = ex?.cause ?: ex
                errors.add("${signatureOf(method)} -> ${cause?.javaClass?.simpleName}: ${cause?.message}")
            }
        }
        error("All candidates failed for $methodName: ${errors.joinToString(" | ")}")
    }

    private fun buildArgsForMethod(
        method: Method,
        listener: IOnPrimaryClipChangedListener?,
    ): Array<Any?>? {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        var stringCount = 0
        for (i in types.indices) {
            val type = types[i]
            when {
                IOnPrimaryClipChangedListener::class.java.isAssignableFrom(type) -> {
                    if (listener == null) return null
                    args[i] = listener
                }
                type == String::class.java -> {
                    args[i] = if (stringCount == 0) CALLING_PACKAGE else null
                    stringCount++
                }
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> {
                    args[i] = 0
                }
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> {
                    args[i] = false
                }
                else -> return null
            }
        }
        return args
    }

    private fun buildDiagnosticsText(): String {
        val service = clipboardService
        val uid = Process.myUid()
        val methodsDesc = if (service == null) {
            "IClipboard=null"
        } else {
            val interesting = setOf(
                "getPrimaryClip",
                "addPrimaryClipChangedListener",
                "removePrimaryClipChangedListener",
            )
            service.javaClass.methods
                .filter { it.name in interesting }
                .joinToString("\n") { "  - ${signatureOf(it)}" }
        }
        return "UID=$uid (shell=2000), SDK=${Build.VERSION.SDK_INT}, Device=${Build.MANUFACTURER} ${Build.MODEL}\n$methodsDesc"
    }

    private fun signatureOf(method: Method): String {
        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
        return "${method.name}($params)"
    }

    companion object {
        private const val CALLING_PACKAGE = "com.android.shell"
        private const val DUPLICATE_EVENT_DEBOUNCE_MS = 350L
    }
}
