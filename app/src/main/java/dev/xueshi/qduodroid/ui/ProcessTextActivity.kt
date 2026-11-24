package dev.xueshi.qduodroid.ui

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xueshi.qduodroid.R
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.overlay.OverlayController
import dev.xueshi.qduodroid.state.MonitorState

class ProcessTextActivity : AppCompatActivity() {

    private var activeDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        disableOpenTransition()
        super.onCreate(savedInstanceState)
        handleProcessIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeDialog?.dismiss()
        handleProcessIntent(intent)
    }

    override fun finish() {
        super.finish()
        disableCloseTransition()
    }

    private fun handleProcessIntent(intent: Intent?) {
        val selectedText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()

        if (selectedText.isNullOrBlank()) {
            finish()
            return
        }

        val cleaned = selectedText.trim()
        val isShare = intent?.action == Intent.ACTION_SEND
        val sourceLabel = if (isShare) {
            "系统分享"
        } else {
            "选词菜单"
        }
        MonitorState.update {
            it.copy(
                shareIntentCount = if (isShare) it.shareIntentCount + 1 else it.shareIntentCount,
                processTextMenuCount = if (!isShare) it.processTextMenuCount + 1 else it.processTextMenuCount,
                lastTriggerSummary = "[$sourceLabel] ${cleaned.length} 字",
            )
        }
        MonitorState.addLog("[$sourceLabel] 触发 (${cleaned.length} 字)")

        if (Settings.canDrawOverlays(this)) {
            OverlayController.showResultSheet(applicationContext, cleaned, QDuoAction.TRANSLATE)
            finish()
            return
        }

        val dialog = BottomSheetDialog(this, R.style.Theme_QDuoDroid_BottomSheetDialog)
        dialog.window?.navigationBarColor = Color.TRANSPARENT
        val container = FrameLayout(this)
        val sheetContent = OverlayController.buildResultSheetView(
            context = this,
            inputText = cleaned,
            initialAction = QDuoAction.TRANSLATE,
            onDismiss = { dialog.dismiss() },
        )
        container.addView(
            sheetContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setContentView(container)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener {
            if (!isFinishing) {
                finish()
            }
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun disableOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun disableCloseTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        activeDialog?.setOnDismissListener(null)
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }
}
