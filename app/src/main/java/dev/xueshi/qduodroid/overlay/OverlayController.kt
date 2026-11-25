package dev.xueshi.qduodroid.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.xueshi.qduodroid.R
import dev.xueshi.qduodroid.engine.ActionEvent
import dev.xueshi.qduodroid.engine.ActionExecutor
import dev.xueshi.qduodroid.engine.FakeActionEngine
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.state.MonitorState
import dev.xueshi.qduodroid.ui.MainActivity
import dev.xueshi.qduodroid.util.AppPrefs
import dev.xueshi.qduodroid.util.SelfCopyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

object OverlayController {

    private const val OUTSIDE_TOUCH_GRACE_MS = 400L
    private const val TTS_IDLE_SHUTDOWN_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var barView: View? = null
    private var sheetView: View? = null
    private var dismissRunnable: Runnable? = null
    private var ttsShutdownRunnable: Runnable? = null
    private var preferredBarYOffsetPx: Int = -1
    private var barShownAtUptimeMs: Long = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingSpeakText: String? = null

    fun isShowing(): Boolean = barView != null || sheetView != null

    fun showActionBar(
        context: Context,
        text: String,
        triggerSource: String = "剪贴板",
        onAcquireText: (((String?) -> Unit) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showActionBar(appContext, text, triggerSource, onAcquireText) }
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            MonitorState.addLog("缺少悬浮窗权限，无法弹出 PopClip 工具条")
            return
        }

        dismissActionBar()

        val themed = ContextThemeWrapper(appContext, R.style.Theme_QDuoDroid)
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (preferredBarYOffsetPx < 0) {
            preferredBarYOffsetPx = dp(themed, 140)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = preferredBarYOffsetPx
        }

        val rootContainer = FrameLayout(themed).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (SystemClock.uptimeMillis() - barShownAtUptimeMs < OUTSIDE_TOUCH_GRACE_MS) {
                        MonitorState.addLog("忽略刚弹出时的外部触摸事件")
                    } else {
                        dismissActionBar(reason = "外部触摸")
                    }
                    true
                } else {
                    false
                }
            }
        }

        val pillContainer = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(themed, 6), dp(themed, 4), dp(themed, 6), dp(themed, 4))
            elevation = dp(themed, 12).toFloat()
            clipToOutline = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#3B7BE8"),
                    Color.parseColor("#1D96DA"),
                    Color.parseColor("#00A7C7"),
                ),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(themed, 24).toFloat()
                setStroke(dp(themed, 1), Color.parseColor("#4DFFFFFF"))
            }
        }

        val dragHandle = createDragHandle(themed, wm, rootContainer, params)
        pillContainer.addView(dragHandle)

        val actionsRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (action in QDuoAction.entries) {
            val btn = createBarButton(themed, action.title) {
                dismissActionBar()
                if (text.isNotBlank()) {
                    handleAction(appContext, text, action)
                } else if (onAcquireText != null) {
                    MonitorState.addLog("动作「${action.title}」触发代复制读取文本…")
                    onAcquireText { acquired ->
                        mainHandler.post {
                            val cleaned = acquired?.trim()
                            if (cleaned.isNullOrEmpty()) {
                                Toast.makeText(
                                    appContext,
                                    "未能自动读取选中文本，请点系统「复制」",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                handleAction(appContext, cleaned, action)
                            }
                        }
                    }
                }
            }
            actionsRow.addView(btn)
        }

        val maxActionsWidth = (themed.resources.displayMetrics.widthPixels - dp(themed, 90))
            .coerceAtLeast(dp(themed, 180))
        val scroll = object : HorizontalScrollView(themed) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedSpec = MeasureSpec.makeMeasureSpec(maxActionsWidth, MeasureSpec.AT_MOST)
                super.onMeasure(cappedSpec, heightMeasureSpec)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(actionsRow)
        }
        pillContainer.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val divider = View(themed).apply {
            setBackgroundColor(Color.parseColor("#45FFFFFF"))
        }
        pillContainer.addView(
            divider,
            LinearLayout.LayoutParams(dp(themed, 1), dp(themed, 16)).apply {
                marginStart = dp(themed, 2)
                marginEnd = dp(themed, 2)
            },
        )

        val closeBtn = createBarButton(themed, "✕", subtle = true) {
            dismissActionBar(reason = "手动关闭")
        }
        pillContainer.addView(closeBtn)

        rootContainer.addView(
            pillContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        pillContainer.translationY = dp(themed, 14).toFloat()

        runCatching {
            wm.addView(rootContainer, params)
            barView = rootContainer
            barShownAtUptimeMs = SystemClock.uptimeMillis()
            MonitorState.addLog("悬浮条已显示 [$triggerSource]")
            pillContainer.animate()
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            scheduleAutoDismiss(appContext)
        }.onFailure { err ->
            MonitorState.addLog("悬浮条显示失败: ${err.javaClass.simpleName}: ${err.message}")
        }
    }

    fun dismissActionBar(reason: String? = null) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val view = barView ?: return
        barView = null
        if (reason != null) {
            MonitorState.addLog("悬浮条已收起 ($reason)")
        }
        val wm = view.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(view) }
    }

    fun showResultSheet(context: Context, text: String, initialAction: QDuoAction) {
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showResultSheet(appContext, text, initialAction) }
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            MonitorState.addLog("缺少悬浮窗权限，无法打开底部结果卡片")
            return
        }

        dismissActionBar()
        dismissResultSheet()

        val themed = ContextThemeWrapper(appContext, R.style.Theme_QDuoDroid)
        val palette = sheetPalette(themed)
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayRoot = object : FrameLayout(themed) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissResultSheet()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#73000000"))
            isFocusableInTouchMode = true
            setOnClickListener { dismissResultSheet() }
        }

        val cardWrapper = FrameLayout(themed).apply {
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = dp(themed, 24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(palette.cardBg)
                setStroke(dp(themed, 1), palette.cardBorder)
            }
            elevation = dp(themed, 16).toFloat()
        }

        val contentView = buildResultSheetView(
            context = themed,
            inputText = text,
            initialAction = initialAction,
            onDismiss = { dismissResultSheet() },
        )
        cardWrapper.addView(
            contentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val maxSheetHeight = (themed.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val cardLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        overlayRoot.addView(cardWrapper, cardLp)

        val baseBottomPad = contentView.paddingBottom
        val fallbackNavBarHeight = fallbackNavigationBarHeight(themed)
        contentView.setPadding(
            contentView.paddingLeft,
            contentView.paddingTop,
            contentView.paddingRight,
            baseBottomPad + fallbackNavBarHeight,
        )
        ViewCompat.setOnApplyWindowInsetsListener(overlayRoot) { _, windowInsets ->
            val navBottom = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val extraBottom = if (navBottom > 0) navBottom else fallbackNavBarHeight
            contentView.setPadding(
                contentView.paddingLeft,
                contentView.paddingTop,
                contentView.paddingRight,
                baseBottomPad + extraBottom,
            )
            windowInsets
        }

        cardWrapper.post {
            if (cardWrapper.height > maxSheetHeight) {
                val lp = cardWrapper.layoutParams
                lp.height = maxSheetHeight
                cardWrapper.layoutParams = lp
            }
        }

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cardWrapper.translationY = dp(themed, 180).toFloat()
        overlayRoot.alpha = 0f

        runCatching {
            wm.addView(overlayRoot, params)
            sheetView = overlayRoot
            overlayRoot.requestFocus()
            ViewCompat.requestApplyInsets(overlayRoot)
            overlayRoot.animate().alpha(1f).setDuration(160L).start()
            cardWrapper.animate()
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }.onFailure { err ->
            MonitorState.addLog("底部结果弹窗显示失败: ${err.message}")
        }
    }

    fun dismissResultSheet() {
        val view = sheetView ?: return
        sheetView = null
        val wm = view.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(view) }
    }

    fun dismissAll() {
        dismissActionBar()
        dismissResultSheet()
        shutdownTts()
    }

    fun handleAction(context: Context, text: String, action: QDuoAction) {
        val appContext = context.applicationContext
        MonitorState.addLog("执行动作「${action.title}」(${text.length} 字)")
        when {
            action.isSheetAction -> showResultSheet(appContext, text, action)
            action == QDuoAction.SEARCH -> openBrowserSearch(appContext, text)
            action == QDuoAction.SPEAK -> speakText(appContext, text)
        }
    }

    fun buildResultSheetView(
        context: Context,
        inputText: String,
        initialAction: QDuoAction,
        onDismiss: () -> Unit,
    ): View {
        val palette = sheetPalette(context)
        val startAction = if (initialAction.isSheetAction) initialAction else QDuoAction.TRANSLATE
        var activeAction = startAction
        var currentOutput = ""
        var quoteExpanded = false

        val root = object : LinearLayout(context) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            override fun onDetachedFromWindow() {
                super.onDetachedFromWindow()
                scope.cancel()
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 16))
        }

        // Top drag handle
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 4).toFloat()
                setColor(palette.handle)
            }
        }
        root.addView(
            handle,
            LinearLayout.LayoutParams(dp(context, 38), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 12)
            },
        )

        // Header row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = "QDuo"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
        }
        val engineBadge = TextView(context).apply {
            text = "准备中"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.badgeText)
            setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 10).toFloat()
                setColor(palette.badgeBg)
            }
        }
        val spacer = View(context)
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.textSecondary)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 6), dp(context, 4))
            setOnClickListener { onDismiss() }
        }

        headerRow.addView(titleView)
        headerRow.addView(
            engineBadge,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 8)
                marginEnd = dp(context, 8)
            },
        )
        headerRow.addView(spacer, LinearLayout.LayoutParams(0, 1))
        headerRow.addView(closeButton)
        root.addView(headerRow)

        // Action chips
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 12), 0, dp(context, 10))
        }
        val chipViews = mutableMapOf<QDuoAction, TextView>()

        // Original text box (tap to expand/collapse)
        val originalBox = TextView(context).apply {
            text = "原文：${inputText.trim()}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(palette.textSecondary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                setColor(palette.quoteBg)
            }
            setOnClickListener {
                quoteExpanded = !quoteExpanded
                maxLines = if (quoteExpanded) 12 else 2
            }
        }

        // Result text + action helper row
        val resultView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(palette.textPrimary)
            setLineSpacing(dp(context, 3).toFloat(), 1.2f)
            setTextIsSelectable(true)
            setPadding(dp(context, 2), dp(context, 8), dp(context, 2), dp(context, 6))
        }

        val inlineActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(context, 4), 0, dp(context, 6))
        }

        val resultContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                resultView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                inlineActionRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val resultScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            minimumHeight = dp(context, 130)
            addView(
                resultContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        var actionJob: Job? = null

        fun updateChipStyles(selected: QDuoAction) {
            for ((action, chip) in chipViews) {
                val isSelected = action == selected
                chip.setTextColor(if (isSelected) Color.WHITE else palette.chipInactiveText)
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 16).toFloat()
                    setColor(if (isSelected) palette.accent else palette.chipInactiveBg)
                }
            }
        }

        fun runDemoPreview(action: QDuoAction) {
            inlineActionRow.visibility = View.GONE
            engineBadge.text = "Demo 演示 · ${action.title}"
            resultView.setTextColor(palette.textPrimary)
            actionJob?.cancel()
            actionJob = root.scope.launch {
                FakeActionEngine.stream(action, inputText).collect { chunk ->
                    currentOutput = chunk
                    resultView.text = chunk
                }
            }
        }

        fun runAction(action: QDuoAction) {
            activeAction = action
            currentOutput = ""
            updateChipStyles(action)
            inlineActionRow.removeAllViews()
            inlineActionRow.visibility = View.GONE
            resultView.setTextColor(palette.textPrimary)
            actionJob?.cancel()
            actionJob = root.scope.launch {
                ActionExecutor.execute(context, action, inputText).collect { event ->
                    engineBadge.text = event.engineBadge
                    when (event) {
                        is ActionEvent.Loading -> {
                            resultView.setTextColor(palette.textSecondary)
                            resultView.text = event.statusText
                        }
                        is ActionEvent.Streaming -> {
                            resultView.setTextColor(palette.textPrimary)
                            currentOutput = event.text
                            resultView.text = event.text
                        }
                        is ActionEvent.Success -> {
                            resultView.setTextColor(palette.textPrimary)
                            currentOutput = event.text
                            resultView.text = event.text
                        }
                        is ActionEvent.NeedsConfig -> {
                            resultView.setTextColor(palette.textSecondary)
                            resultView.text = event.message
                            inlineActionRow.removeAllViews()
                            inlineActionRow.visibility = View.VISIBLE
                            inlineActionRow.addView(
                                createFooterButton(context, "去配置 AI 模型", primary = true, palette = palette) {
                                    onDismiss()
                                    val i = Intent(context.applicationContext, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        putExtra(MainActivity.EXTRA_EXPAND_AI_CONFIG, true)
                                    }
                                    runCatching { context.applicationContext.startActivity(i) }
                                }.apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply { marginEnd = dp(context, 8) }
                                },
                            )
                            inlineActionRow.addView(
                                createFooterButton(context, "演示效果", primary = false, palette = palette) {
                                    runDemoPreview(action)
                                },
                            )
                        }
                        is ActionEvent.Failure -> {
                            resultView.setTextColor(palette.errorText)
                            resultView.text = event.errorMessage
                            inlineActionRow.removeAllViews()
                            inlineActionRow.visibility = View.VISIBLE
                            inlineActionRow.addView(
                                createFooterButton(context, "重试", primary = true, palette = palette) {
                                    runAction(action)
                                },
                            )
                        }
                    }
                }
            }
        }

        for (action in QDuoAction.sheetActions) {
            val chip = TextView(context).apply {
                text = action.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7))
                setOnClickListener { runAction(action) }
            }
            chipViews[action] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(context, 8)
                },
            )
        }

        root.addView(chipRow)
        root.addView(
            originalBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(context, 8)
            },
        )
        root.addView(
            resultScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // Footer row
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, 0)
        }

        val copyBtn = createFooterButton(context, "复制结果", primary = true, palette = palette) {
            val textToCopy = currentOutput.trim()
            if (textToCopy.isEmpty()) {
                Toast.makeText(context, "暂无可复制的结果", Toast.LENGTH_SHORT).show()
                return@createFooterButton
            }
            SelfCopyGuard.shared.markSelfCopied(textToCopy)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QDuo Result", textToCopy))
            MonitorState.addLog("已复制「${activeAction.title}」结果（已启用防回环保护）")
            Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
        }
        val speakBtn = createFooterButton(context, "朗读", primary = false, palette = palette) {
            val toSpeak = currentOutput.ifBlank { inputText }
            speakText(context.applicationContext, toSpeak)
        }
        val retryBtn = createFooterButton(context, "重试", primary = false, palette = palette) {
            runAction(activeAction)
        }
        val closeFooterBtn = createFooterButton(context, "关闭", primary = false, palette = palette) {
            onDismiss()
        }

        footerRow.addView(
            copyBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 6)
            },
        )
        footerRow.addView(
            speakBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(context, 6)
            },
        )
        footerRow.addView(
            retryBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(context, 6)
            },
        )
        footerRow.addView(closeFooterBtn)
        root.addView(footerRow)

        runAction(startAction)
        return root
    }

    private fun openBrowserSearch(appContext: Context, queryText: String) {
        val q = queryText.trim().take(200)
        if (q.isEmpty()) return
        val searchUri = Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")
        val viewIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            appContext.startActivity(viewIntent)
        }.isSuccess
        if (!launched) {
            val webIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", q)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(webIntent)
            }.onFailure {
                Toast.makeText(appContext, "未能拉起浏览器（可能需开启后台弹出界面权限）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun speakText(appContext: Context, text: String) {
        val sample = text.trim().take(400)
        if (sample.isEmpty()) return

        val engine = tts
        if (engine != null && ttsReady) {
            val res = engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "qduo_tts")
            if (res == TextToSpeech.SUCCESS) {
                scheduleTtsIdleShutdown()
                Toast.makeText(appContext, "正在朗读…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(appContext, "系统 TTS 暂不可用", Toast.LENGTH_SHORT).show()
            }
            return
        }

        pendingSpeakText = sample
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.language = Locale.getDefault()
                    val pending = pendingSpeakText
                    pendingSpeakText = null
                    if (!pending.isNullOrBlank()) {
                        mainHandler.post {
                            tts?.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "qduo_tts")
                            scheduleTtsIdleShutdown()
                            Toast.makeText(appContext, "正在朗读…", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        mainHandler.post { scheduleTtsIdleShutdown() }
                    }
                } else {
                    ttsReady = false
                    mainHandler.post {
                        shutdownTts()
                        Toast.makeText(appContext, "系统语音引擎初始化失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun scheduleTtsIdleShutdown() {
        ttsShutdownRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { shutdownTts() }
        ttsShutdownRunnable = r
        mainHandler.postDelayed(r, TTS_IDLE_SHUTDOWN_MS)
    }

    private fun shutdownTts() {
        ttsShutdownRunnable?.let { mainHandler.removeCallbacks(it) }
        ttsShutdownRunnable = null
        pendingSpeakText = null
        ttsReady = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun scheduleAutoDismiss(context: Context) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val timeoutMs = AppPrefs(context).actionBarAutoDismissMs
        if (timeoutMs <= 0L) return
        val r = Runnable { dismissActionBar(reason = "超时 (${timeoutMs / 1000}秒)") }
        dismissRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragHandle(
        context: Context,
        wm: WindowManager,
        rootView: View,
        params: WindowManager.LayoutParams,
    ): View {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_qduo_bar_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "QDuo"
            layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
            setPadding(dp(context, 5), dp(context, 5), dp(context, 3), dp(context, 5))

            var downRawY = 0f
            var initialY = 0
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
                        downRawY = event.rawY
                        initialY = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = (downRawY - event.rawY).toInt()
                        if (abs(deltaY) > 2) {
                            val maxY = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
                            params.y = (initialY + deltaY).coerceIn(dp(context, 36), maxY)
                            preferredBarYOffsetPx = params.y
                            runCatching { wm.updateViewLayout(rootView, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scheduleAutoDismiss(context.applicationContext)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createBarButton(
        context: Context,
        label: String,
        subtle: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = if (subtle) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setTextColor(if (subtle) Color.parseColor("#D9FFFFFF") else Color.WHITE)
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private data class SheetPalette(
        val cardBg: Int,
        val cardBorder: Int,
        val quoteBg: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val chipInactiveBg: Int,
        val chipInactiveText: Int,
        val badgeBg: Int,
        val badgeText: Int,
        val handle: Int,
        val errorText: Int,
    )

    private fun sheetPalette(context: Context): SheetPalette {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDark) {
            SheetPalette(
                cardBg = Color.parseColor("#1A1D24"),
                cardBorder = Color.parseColor("#2D3240"),
                quoteBg = Color.parseColor("#242834"),
                textPrimary = Color.parseColor("#F3F4F6"),
                textSecondary = Color.parseColor("#9CA3AF"),
                accent = Color.parseColor("#3B82F6"),
                chipInactiveBg = Color.parseColor("#272B36"),
                chipInactiveText = Color.parseColor("#D1D5DB"),
                badgeBg = Color.parseColor("#1E3A5F"),
                badgeText = Color.parseColor("#93C5FD"),
                handle = Color.parseColor("#4B5563"),
                errorText = Color.parseColor("#FCA5A5"),
            )
        } else {
            SheetPalette(
                cardBg = Color.parseColor("#FFFFFF"),
                cardBorder = Color.parseColor("#E5E7EB"),
                quoteBg = Color.parseColor("#F3F5F9"),
                textPrimary = Color.parseColor("#111827"),
                textSecondary = Color.parseColor("#6B7280"),
                accent = Color.parseColor("#1F6FEB"),
                chipInactiveBg = Color.parseColor("#EEF1F6"),
                chipInactiveText = Color.parseColor("#374151"),
                badgeBg = Color.parseColor("#E8F1FF"),
                badgeText = Color.parseColor("#0F5ED7"),
                handle = Color.parseColor("#D5D8E0"),
                errorText = Color.parseColor("#B91C1C"),
            )
        }
    }

    private fun fallbackNavigationBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 20)
    }

    private fun createFooterButton(
        context: Context,
        label: String,
        primary: Boolean,
        palette: SheetPalette,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else palette.chipInactiveText)
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                setColor(if (primary) palette.accent else palette.chipInactiveBg)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }
}
