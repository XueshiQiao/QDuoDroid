package dev.xueshi.qduodroid.state

import dev.xueshi.qduodroid.service.SelectionAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MonitorSnapshot(
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val userServiceConnected: Boolean = false,
    val watchServiceRunning: Boolean = false,
    val autoStartMonitoring: Boolean = true,
    val autoApplyWhitelist: Boolean = true,
    val enableFreezeWakeup: Boolean = true,
    val whitelistExempted: Boolean = false,
    val lastWhitelistResult: String = "",
    val directDeliveryCount: Int = 0,
    val freezeWakeCount: Int = 0,
    val whitelistFeedback: String = "",
    val whitelistFeedbackSeq: Long = 0L,
    val wakeTestFeedback: String = "",
    val wakeTestFeedbackSeq: Long = 0L,
    val accessibilityEnabled: Boolean = false,
    val accessibilityServiceRunning: Boolean = false,
    val enableAccessibilitySelection: Boolean = true,
    val lastTriggerSummary: String = "尚未触发",
    val axSelectionDirectCount: Int = 0,
    val axSelectionNoTextCount: Int = 0,
    val axToolbarFocusDirectCount: Int = 0,
    val axToolbarAutoCopyShownCount: Int = 0,
    val axAutoCopySuccessCount: Int = 0,
    val axAutoCopyFailCount: Int = 0,
    val axProbeMissCount: Int = 0,
    val clipTriggerCount: Int = 0,
    val processTextMenuCount: Int = 0,
    val shareIntentCount: Int = 0,
    val axTotalEvents: Int = 0,
    val axIgnoredEvents: Int = 0,
    val axProbeCount: Int = 0,
    val axWindowNoiseDropped: Int = 0,
    val axEnableFeedback: String = "",
    val axEnableFeedbackSeq: Long = 0L,
    val activeMode: String = "未启动",
    val diagnostics: String = "",
    val lastCapturedText: String = "",
    val logs: List<String> = emptyList(),
)

object MonitorState {
    private const val MAX_LOGS = 150
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _snapshot = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    fun update(transform: (MonitorSnapshot) -> MonitorSnapshot) {
        _snapshot.update(transform)
    }

    fun addLog(message: String) {
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = "[$timestamp] $message"
        runCatching { android.util.Log.w("QDuo", entry) }
        _snapshot.update { current ->
            current.copy(logs = (listOf(entry) + current.logs).take(MAX_LOGS))
        }
    }

    fun clearLogs() {
        _snapshot.update { it.copy(logs = emptyList()) }
    }

    fun resetStats() {
        SelectionAccessibilityService.resetCounters()
        _snapshot.update {
            it.copy(
                lastTriggerSummary = "尚未触发",
                axSelectionDirectCount = 0,
                axSelectionNoTextCount = 0,
                axToolbarFocusDirectCount = 0,
                axToolbarAutoCopyShownCount = 0,
                axAutoCopySuccessCount = 0,
                axAutoCopyFailCount = 0,
                axProbeMissCount = 0,
                clipTriggerCount = 0,
                processTextMenuCount = 0,
                shareIntentCount = 0,
                axTotalEvents = 0,
                axIgnoredEvents = 0,
                axProbeCount = 0,
                axWindowNoiseDropped = 0,
                directDeliveryCount = 0,
                freezeWakeCount = 0,
            )
        }
        addLog("已重置触发路径统计")
    }
}
