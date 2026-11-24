package dev.xueshi.qduodroid

import android.app.Application
import android.os.Build
import dev.xueshi.qduodroid.state.MonitorState
import dev.xueshi.qduodroid.util.AppPrefs
import org.lsposed.hiddenapibypass.HiddenApiBypass

class QDuoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
        val prefs = AppPrefs(this)
        MonitorState.update {
            it.copy(
                autoApplyWhitelist = prefs.autoApplyWhitelist,
                enableFreezeWakeup = prefs.enableFreezeWakeup,
                autoStartMonitoring = prefs.autoStartMonitoring,
                enableAccessibilitySelection = prefs.enableAccessibilitySelection,
            )
        }
    }
}
