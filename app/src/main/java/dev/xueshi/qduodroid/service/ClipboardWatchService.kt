package dev.xueshi.qduodroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.xueshi.qduodroid.BuildConfig
import dev.xueshi.qduodroid.IClipboardCallback
import dev.xueshi.qduodroid.IClipboardMonitorService
import dev.xueshi.qduodroid.R
import dev.xueshi.qduodroid.overlay.OverlayController
import dev.xueshi.qduodroid.shizuku.ClipboardUserService
import dev.xueshi.qduodroid.state.MonitorState
import dev.xueshi.qduodroid.ui.MainActivity
import dev.xueshi.qduodroid.util.AppPrefs
import dev.xueshi.qduodroid.util.SelfCopyGuard
import rikka.shizuku.Shizuku

class ClipboardWatchService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitorService: IClipboardMonitorService? = null
    private var isBound = false
    private var isDestroyed = false
    private var rebindRunnable: Runnable? = null
    private var wakeTestRequestedAtMs: Long = 0L

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ClipboardUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("clipboard")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val clipboardCallback = object : IClipboardCallback.Stub() {
        override fun onClipChanged(text: String?, timestamp: Long) {
            val raw = text ?: return
            MonitorState.update { it.copy(directDeliveryCount = it.directDeliveryCount + 1) }
            handleIncomingClip(raw)
        }

        override fun onStatusChanged(status: String?) {
            val statusText = status ?: return
            MonitorState.update { it.copy(activeMode = statusText) }
            MonitorState.addLog("监听模式切换: $statusText")
            updateNotification("监听中：$statusText")
        }

        override fun onDebugLog(message: String?) {
            if (!message.isNullOrBlank()) {
                MonitorState.addLog("[Shizuku] $message")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                MonitorState.addLog("UserService binder 无效")
                return
            }
            val service = IClipboardMonitorService.Stub.asInterface(binder)
            monitorService = service
            MonitorState.update { it.copy(userServiceConnected = true) }
            MonitorState.addLog("Shizuku UserService 已连接")

            val snap = MonitorState.snapshot.value
            runCatching {
                if (snap.autoApplyWhitelist) {
                    runKeepAliveExemptions(service)
                } else {
                    refreshWhitelistState()
                }
                service.startWatching(clipboardCallback, snap.enableFreezeWakeup)
                val diag = service.getDiagnostics()
                MonitorState.update { it.copy(diagnostics = diag) }
                if (snap.enableAccessibilitySelection && !SelectionAccessibilityService.isEnabledInSettings(this@ClipboardWatchService)) {
                    enableAccessibilityNow(service, userTriggered = false)
                }
                val pending = service.consumePendingClipText()
                if (!pending.isNullOrBlank()) {
                    MonitorState.update { it.copy(freezeWakeCount = it.freezeWakeCount + 1) }
                    handleIncomingClip(pending, fromFreezeWake = true)
                }
            }.onFailure { err ->
                MonitorState.addLog("启动剪贴板监听失败: ${err.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService = null
            isBound = false
            MonitorState.update {
                it.copy(
                    userServiceConnected = false,
                    activeMode = "UserService 已断开，正在重连…",
                )
            }
            MonitorState.addLog("Shizuku UserService 已断开，准备自动重连")
            updateNotification("Shizuku UserService 重连中…")
            if (!isDestroyed) {
                scheduleRebind(1500L)
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!isDestroyed) {
            scheduleRebind(300L)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        monitorService = null
        isBound = false
        MonitorState.update {
            it.copy(
                shizukuRunning = false,
                userServiceConnected = false,
                activeMode = "Shizuku 已断开",
            )
        }
        MonitorState.addLog("Shizuku 服务已断开连接")
        updateNotification("Shizuku 已断开")
    }

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        ensureNotificationChannel()
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MonitorState.addLog("用户从通知栏停止监听")
            AppPrefs(this).userPausedMonitoring = true
            stopSelf()
            return START_NOT_STICKY
        }

        val statusSummary = if (monitorService != null) {
            "监听中：${MonitorState.snapshot.value.activeMode}"
        } else {
            "正在连接 Shizuku…"
        }
        startForegroundCompat(buildNotification(statusSummary))
        MonitorState.update { it.copy(watchServiceRunning = true) }

        when (intent?.action) {
            ACTION_WAKE_AND_PULL -> {
                val nextCount = MonitorState.snapshot.value.freezeWakeCount + 1
                MonitorState.update { it.copy(freezeWakeCount = nextCount) }
                MonitorState.addLog("已从系统冻结状态唤醒 (第 $nextCount 次)，拉取剪贴板")
                val svc = monitorService
                if (svc != null) {
                    val pending = runCatching { svc.consumePendingClipText() }.getOrNull()
                    if (!pending.isNullOrBlank()) {
                        handleIncomingClip(pending, fromFreezeWake = true)
                    }
                } else {
                    bindShizukuUserService()
                }
                return START_STICKY
            }

            ACTION_TEST_WAKE -> {
                val elapsed = if (wakeTestRequestedAtMs > 0L) {
                    (System.currentTimeMillis() - wakeTestRequestedAtMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                wakeTestRequestedAtMs = 0L
                val feedback = if (elapsed > 0L) {
                    "唤醒通路正常 ✓ (${elapsed}ms)"
                } else {
                    "唤醒通路正常 ✓"
                }
                MonitorState.update {
                    it.copy(
                        wakeTestFeedback = feedback,
                        wakeTestFeedbackSeq = it.wakeTestFeedbackSeq + 1L,
                    )
                }
                MonitorState.addLog("唤醒通路测试成功 ✓ (Shell → AMS → ClipboardWatchService)")
                return START_STICKY
            }

            ACTION_APPLY_EXEMPTIONS -> {
                val svc = monitorService
                if (svc != null) {
                    runCatching { runKeepAliveExemptions(svc, notifyFeedback = true) }
                        .onFailure { err -> MonitorState.addLog("应用白名单失败: ${err.message}") }
                } else {
                    MonitorState.addLog("请先开启监听连接 Shizuku")
                }
                return START_STICKY
            }

            ACTION_REQUEST_TEST_WAKE -> {
                val svc = monitorService
                if (svc != null) {
                    wakeTestRequestedAtMs = System.currentTimeMillis()
                    runCatching { svc.triggerTestWake() }
                        .onFailure { err -> MonitorState.addLog("触发唤醒测试失败: ${err.message}") }
                } else {
                    MonitorState.addLog("请先开启监听连接 Shizuku")
                }
                return START_STICKY
            }

            ACTION_ENABLE_ACCESSIBILITY -> {
                val svc = monitorService
                if (svc != null) {
                    enableAccessibilityNow(svc, userTriggered = true)
                } else {
                    MonitorState.update {
                        it.copy(
                            axEnableFeedback = "Shizuku 尚未连接，请前往系统无障碍设置",
                            axEnableFeedbackSeq = it.axEnableFeedbackSeq + 1L,
                        )
                    }
                    MonitorState.addLog("请先开启监听连接 Shizuku 后再自动开启无障碍")
                }
                return START_STICKY
            }

            ACTION_RESTART_WATCH -> {
                val svc = monitorService
                if (svc != null) {
                    val snap = MonitorState.snapshot.value
                    runCatching {
                        svc.startWatching(clipboardCallback, snap.enableFreezeWakeup)
                    }.onFailure { err ->
                        MonitorState.addLog("切换监听模式失败: ${err.message}")
                    }
                    return START_STICKY
                }
            }
        }

        bindShizukuUserService()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroyed = true
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = null
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        runCatching { monitorService?.stopWatching() }
        if (isBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
            isBound = false
        }
        monitorService = null
        OverlayController.dismissAll()
        MonitorState.update {
            it.copy(
                watchServiceRunning = false,
                userServiceConnected = false,
                activeMode = "未启动",
            )
        }
        MonitorState.addLog("剪贴板监听服务已停止")
    }

    private fun runKeepAliveExemptions(
        service: IClipboardMonitorService,
        notifyFeedback: Boolean = false,
    ) {
        val exemptRes = service.applyKeepAliveExemptions(packageName)
        MonitorState.update {
            if (notifyFeedback) {
                it.copy(
                    lastWhitelistResult = exemptRes,
                    whitelistFeedback = "白名单已生效 ✓ ($exemptRes)",
                    whitelistFeedbackSeq = it.whitelistFeedbackSeq + 1L,
                )
            } else {
                it.copy(lastWhitelistResult = exemptRes)
            }
        }
        MonitorState.addLog("后台白名单配置: $exemptRes")
        refreshWhitelistState()
    }

    private fun refreshWhitelistState() {
        val pm = getSystemService(PowerManager::class.java)
        val exempted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        MonitorState.update { it.copy(whitelistExempted = exempted) }
    }

    private fun enableAccessibilityNow(
        service: IClipboardMonitorService,
        userTriggered: Boolean,
    ) {
        val grantRes = runCatching { service.grantAccessibilityWritePermissions(packageName) }
            .getOrElse { "err: ${it.message}" }
        val targetComponent = SelectionAccessibilityService.componentId(packageName)

        val writeErr = runCatching {
            val existing = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                .toMutableList()

            val full = targetComponent
            val short = "$packageName/.service.SelectionAccessibilityService"
            if (existing.none { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }) {
                existing.add(targetComponent)
            }
            val merged = existing.joinToString(":")
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged,
            )
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.exceptionOrNull()

        val verified = SelectionAccessibilityService.isEnabledInSettings(this)
        val detail = writeErr?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: grantRes
        val feedback = if (verified) {
            "已通过 Shizuku 自动开启无障碍 ✓"
        } else {
            "自动开启未生效，请手动开启 ($detail)"
        }

        MonitorState.update {
            it.copy(
                accessibilityEnabled = verified,
                axEnableFeedback = if (userTriggered || verified) feedback else it.axEnableFeedback,
                axEnableFeedbackSeq = if (userTriggered || verified) it.axEnableFeedbackSeq + 1L else it.axEnableFeedbackSeq,
            )
        }
        MonitorState.addLog("Shizuku 自动开启无障碍: verified=$verified, $detail")
    }

    private fun handleIncomingClip(raw: String, fromFreezeWake: Boolean = false) {
        if (raw.isBlank()) return
        val normalized = if (raw.length > MAX_TEXT_LENGTH) {
            raw.take(MAX_TEXT_LENGTH)
        } else {
            raw
        }
        if (SelfCopyGuard.shared.shouldIgnore(normalized)) {
            MonitorState.addLog("忽略应用内自复制文本 (${normalized.length} 字)")
            return
        }

        if (deliverToAutoCopyWaiterIfActive(normalized)) {
            return
        }

        val sourceLabel = if (fromFreezeWake) "剪贴板·解冻唤醒" else "剪贴板·直连"
        MonitorState.update {
            it.copy(
                lastCapturedText = normalized,
                clipTriggerCount = it.clipTriggerCount + 1,
                lastTriggerSummary = "[$sourceLabel] ${normalized.length} 字",
            )
        }
        MonitorState.addLog("[$sourceLabel] 捕获 (${normalized.length} 字)")

        mainHandler.post {
            OverlayController.showActionBar(
                context = applicationContext,
                text = normalized,
                triggerSource = sourceLabel,
            )
        }
    }

    private fun scheduleRebind(delayMs: Long) {
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!isDestroyed && !isBound && Shizuku.pingBinder()) {
                bindShizukuUserService()
            }
        }
        rebindRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun bindShizukuUserService() {
        if (!Shizuku.pingBinder()) {
            MonitorState.update {
                it.copy(
                    shizukuRunning = false,
                    activeMode = "Shizuku 未运行",
                )
            }
            MonitorState.addLog("Shizuku 未运行，无法绑定 UserService")
            updateNotification("等待 Shizuku 启动…")
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            MonitorState.update {
                it.copy(
                    shizukuRunning = true,
                    shizukuGranted = false,
                    activeMode = "未获得 Shizuku 授权",
                )
            }
            MonitorState.addLog("未获得 Shizuku 授权，请在首页点击申请授权")
            updateNotification("请先授权 Shizuku")
            return
        }

        if (!isBound) {
            runCatching {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                isBound = true
                MonitorState.addLog("正在绑定 Shizuku UserService…")
            }.onFailure { err ->
                MonitorState.addLog("绑定 UserService 异常: ${err.message}")
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QDuo 剪贴板监听",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "用于保持 Shizuku 剪贴板悬浮条监听服务在后台稳定运行"
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClipboardWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qduo_notif)
            .setContentTitle("QDuo 剪贴板监听")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qduo_clipboard_watch"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_TEXT_LENGTH = 8000

        const val ACTION_START = "dev.xueshi.qduodroid.action.START_WATCH"
        const val ACTION_STOP = "dev.xueshi.qduodroid.action.STOP_WATCH"
        const val ACTION_RESTART_WATCH = "dev.xueshi.qduodroid.action.RESTART_WATCH"
        const val ACTION_WAKE_AND_PULL = "dev.xueshi.qduodroid.action.WAKE_AND_PULL"
        const val ACTION_APPLY_EXEMPTIONS = "dev.xueshi.qduodroid.action.APPLY_EXEMPTIONS"
        const val ACTION_REQUEST_TEST_WAKE = "dev.xueshi.qduodroid.action.REQUEST_TEST_WAKE"
        const val ACTION_TEST_WAKE = "dev.xueshi.qduodroid.action.TEST_WAKE"
        const val ACTION_ENABLE_ACCESSIBILITY = "dev.xueshi.qduodroid.action.ENABLE_ACCESSIBILITY"

        private val waiterLock = Any()
        private var pendingAutoCopyCallback: ((String?) -> Unit)? = null
        private var pendingAutoCopyTimeout: Runnable? = null
        private val companionHandler = Handler(Looper.getMainLooper())

        fun awaitAutoCopyClip(timeoutMs: Long = 900L, onResult: (String?) -> Unit) {
            val callbackToCancelPrevious: ((String?) -> Unit)?
            synchronized(waiterLock) {
                pendingAutoCopyTimeout?.let { companionHandler.removeCallbacks(it) }
                callbackToCancelPrevious = pendingAutoCopyCallback
                pendingAutoCopyCallback = onResult
                val timeoutRunnable = Runnable {
                    val timedOutCb: ((String?) -> Unit)?
                    synchronized(waiterLock) {
                        timedOutCb = pendingAutoCopyCallback
                        pendingAutoCopyCallback = null
                        pendingAutoCopyTimeout = null
                    }
                    timedOutCb?.invoke(null)
                }
                pendingAutoCopyTimeout = timeoutRunnable
                companionHandler.postDelayed(timeoutRunnable, timeoutMs)
            }
            callbackToCancelPrevious?.invoke(null)
        }

        private fun deliverToAutoCopyWaiterIfActive(text: String): Boolean {
            val cb: (String?) -> Unit
            synchronized(waiterLock) {
                val active = pendingAutoCopyCallback ?: return false
                cb = active
                pendingAutoCopyCallback = null
                pendingAutoCopyTimeout?.let { companionHandler.removeCallbacks(it) }
                pendingAutoCopyTimeout = null
            }
            companionHandler.post { cb.invoke(text) }
            return true
        }

        fun start(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardWatchService::class.java))
        }

        fun notifyModeChanged(context: Context) {
            if (!MonitorState.snapshot.value.watchServiceRunning) return
            val intent = Intent(context, ClipboardWatchService::class.java).setAction(ACTION_RESTART_WATCH)
            context.startForegroundService(intent)
        }

        fun applyExemptionsNow(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java).setAction(ACTION_APPLY_EXEMPTIONS)
            context.startForegroundService(intent)
        }

        fun requestTestWake(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java).setAction(ACTION_REQUEST_TEST_WAKE)
            context.startForegroundService(intent)
        }

        fun enableAccessibilityViaShizuku(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java).setAction(ACTION_ENABLE_ACCESSIBILITY)
            context.startForegroundService(intent)
        }
    }
}
