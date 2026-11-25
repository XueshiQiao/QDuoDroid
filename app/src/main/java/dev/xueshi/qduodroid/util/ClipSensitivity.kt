package dev.xueshi.qduodroid.util

import android.os.PersistableBundle

/**
 * 判断剪贴板内容是否被来源应用标记为「敏感」。
 *
 * 密码管理器、银行类应用复制密码或验证码时，会在 ClipDescription 的 extras 里
 * 写入 `android.content.extra.IS_SENSITIVE = true`（Android 13 起成为官方常量
 * ClipDescription.EXTRA_IS_SENSITIVE；不少应用在更早的系统上也写同一个键）。
 * 被标记的内容不弹悬浮条、不暂存、不写进日志。
 */
object ClipSensitivity {
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun isSensitive(extras: PersistableBundle?): Boolean {
        if (extras == null) return false
        return runCatching { extras.getBoolean(EXTRA_IS_SENSITIVE, false) }.getOrDefault(false)
    }
}
