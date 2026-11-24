package dev.xueshi.qduodroid.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import dev.xueshi.qduodroid.BuildConfig
import dev.xueshi.qduodroid.R
import dev.xueshi.qduodroid.engine.ApiFormat
import dev.xueshi.qduodroid.engine.LlmClient
import dev.xueshi.qduodroid.engine.LlmProviders
import dev.xueshi.qduodroid.engine.ModelInfo
import dev.xueshi.qduodroid.engine.ModelListState
import dev.xueshi.qduodroid.engine.ModelListStore
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.overlay.OverlayController
import dev.xueshi.qduodroid.service.ClipboardWatchService
import dev.xueshi.qduodroid.service.SelectionAccessibilityService
import dev.xueshi.qduodroid.state.MonitorSnapshot
import dev.xueshi.qduodroid.state.MonitorState
import dev.xueshi.qduodroid.util.AppPrefs
import dev.xueshi.qduodroid.util.SelfCopyGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var tvAppVersion: TextView
    private lateinit var layoutPermissionsHeader: LinearLayout
    private lateinit var tvPermissionsSummary: TextView
    private lateinit var tvPermissionsToggle: TextView
    private lateinit var layoutPermissionsBody: LinearLayout
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnShizukuAction: MaterialButton
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnOverlayAction: MaterialButton
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotificationAction: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibilityAction: MaterialButton
    private lateinit var btnAppSettings: MaterialButton
    private lateinit var tvWatchMode: TextView
    private lateinit var btnToggleWatch: MaterialButton
    private lateinit var switchAutoMonitor: MaterialSwitch
    private lateinit var switchAccessibilitySelection: MaterialSwitch
    private lateinit var layoutTriggerStatsBox: LinearLayout
    private lateinit var tvResetStats: TextView
    private lateinit var tvLastTriggerSummary: TextView
    private lateinit var tvTriggerStatsDetail: TextView
    private lateinit var tvWhitelistStatus: TextView
    private lateinit var switchAutoWhitelist: MaterialSwitch
    private lateinit var btnApplyWhitelist: MaterialButton
    private lateinit var tvWhitelistActionFeedback: TextView
    private lateinit var tvFreezeWakeStats: TextView
    private lateinit var switchFreezeWakeup: MaterialSwitch
    private lateinit var btnTestWakePath: MaterialButton
    private lateinit var tvWakeTestFeedback: TextView

    private lateinit var cardAiConfig: MaterialCardView
    private lateinit var layoutAiConfigHeader: LinearLayout
    private lateinit var tvAiConfigSummary: TextView
    private lateinit var tvAiConfigToggle: TextView
    private lateinit var layoutAiConfigBody: LinearLayout
    private lateinit var layoutProviderSelector: LinearLayout
    private lateinit var tvProviderValue: TextView
    private lateinit var layoutCustomFormatSection: LinearLayout
    private lateinit var layoutFormatSelector: LinearLayout
    private lateinit var tvFormatValue: TextView
    private lateinit var etLlmBaseUrl: EditText
    private lateinit var etLlmApiKey: EditText
    private lateinit var tvToggleApiKeyVisibility: TextView
    private lateinit var tvModelModeToggle: TextView
    private lateinit var layoutModelPickerRow: LinearLayout
    private lateinit var layoutModelPickerBox: LinearLayout
    private lateinit var progressModelLoading: ProgressBar
    private lateinit var tvModelPrimary: TextView
    private lateinit var tvModelSecondary: TextView
    private lateinit var tvModelChevron: TextView
    private lateinit var btnRefreshModels: MaterialButton
    private lateinit var etLlmModelManual: EditText
    private lateinit var tvModelListStatus: TextView
    private lateinit var layoutThinkingSelector: LinearLayout
    private lateinit var tvThinkingValue: TextView
    private lateinit var switchUseLlmForTranslate: MaterialSwitch
    private lateinit var btnSaveLlmConfig: MaterialButton
    private lateinit var btnTestLlmConfig: MaterialButton
    private lateinit var tvLlmTestStatus: TextView

    private lateinit var etTestInput: EditText
    private lateinit var btnPreviewBar: MaterialButton
    private lateinit var btnCopyTrigger: MaterialButton
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var btnShowDiagnostics: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var tvLogs: TextView

    private val appPrefs by lazy { AppPrefs(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var userToggledPermissionsCard = false
    private var permissionsExpanded = true
    private var aiConfigExpanded = false
    private var userManuallyPaused = false
    private var watchStartInFlight = false
    private var lastRenderedLogs: List<String>? = null
    private var lastHandledWhitelistSeq = 0L
    private var lastHandledWakeTestSeq = 0L
    private var lastHandledAxEnableSeq = 0L
    private var hideWhitelistRunnable: Runnable? = null
    private var hideWakeTestRunnable: Runnable? = null
    private var hideLlmStatusRunnable: Runnable? = null

    private var apiKeyVisible = false
    private var manualModelTyping = false
    private var isPopulatingAiInputs = false
    private var modelListState: ModelListState = ModelListState.Idle
    private var debounceFetchJob: Job? = null
    private var activeFetchJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        MonitorState.addLog(if (granted) "已授予通知权限" else "用户拒绝了通知权限")
        refreshStatuses()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        MonitorState.addLog("检测到 Shizuku 服务已就绪")
        refreshStatuses()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        MonitorState.addLog("Shizuku 服务已断开")
        refreshStatuses()
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU_PERMISSION) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                MonitorState.addLog(
                    if (granted) "Shizuku 授权成功！" else "Shizuku 授权被拒绝",
                )
                refreshStatuses()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applySafeAreaInsets()
        setupHeaderVersion()
        styleAiInputBoxes()
        loadAiConfigIntoUi()
        lastHandledWhitelistSeq = MonitorState.snapshot.value.whitelistFeedbackSeq
        lastHandledWakeTestSeq = MonitorState.snapshot.value.wakeTestFeedbackSeq
        lastHandledAxEnableSeq = MonitorState.snapshot.value.axEnableFeedbackSeq
        setupListeners()
        handleIntentExtras(intent)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        lifecycleScope.launch {
            MonitorState.snapshot.collect { snap ->
                renderSnapshot(snap)
            }
        }

        refreshStatuses()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroy() {
        hideWhitelistRunnable?.let { mainHandler.removeCallbacks(it) }
        hideWakeTestRunnable?.let { mainHandler.removeCallbacks(it) }
        hideLlmStatusRunnable?.let { mainHandler.removeCallbacks(it) }
        watchStartInFlight = false
        debounceFetchJob?.cancel()
        activeFetchJob?.cancel()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun bindViews() {
        rootScroll = findViewById(R.id.rootScroll)
        contentContainer = findViewById(R.id.contentContainer)
        tvAppVersion = findViewById(R.id.tvAppVersion)
        layoutPermissionsHeader = findViewById(R.id.layoutPermissionsHeader)
        tvPermissionsSummary = findViewById(R.id.tvPermissionsSummary)
        tvPermissionsToggle = findViewById(R.id.tvPermissionsToggle)
        layoutPermissionsBody = findViewById(R.id.layoutPermissionsBody)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnShizukuAction = findViewById(R.id.btnShizukuAction)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnOverlayAction = findViewById(R.id.btnOverlayAction)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        btnNotificationAction = findViewById(R.id.btnNotificationAction)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibilityAction = findViewById(R.id.btnAccessibilityAction)
        btnAppSettings = findViewById(R.id.btnAppSettings)
        tvWatchMode = findViewById(R.id.tvWatchMode)
        btnToggleWatch = findViewById(R.id.btnToggleWatch)
        switchAutoMonitor = findViewById(R.id.switchAutoMonitor)
        switchAccessibilitySelection = findViewById(R.id.switchAccessibilitySelection)
        layoutTriggerStatsBox = findViewById(R.id.layoutTriggerStatsBox)
        tvResetStats = findViewById(R.id.tvResetStats)
        tvLastTriggerSummary = findViewById(R.id.tvLastTriggerSummary)
        tvTriggerStatsDetail = findViewById(R.id.tvTriggerStatsDetail)
        tvWhitelistStatus = findViewById(R.id.tvWhitelistStatus)
        switchAutoWhitelist = findViewById(R.id.switchAutoWhitelist)
        btnApplyWhitelist = findViewById(R.id.btnApplyWhitelist)
        tvWhitelistActionFeedback = findViewById(R.id.tvWhitelistActionFeedback)
        tvFreezeWakeStats = findViewById(R.id.tvFreezeWakeStats)
        switchFreezeWakeup = findViewById(R.id.switchFreezeWakeup)
        btnTestWakePath = findViewById(R.id.btnTestWakePath)
        tvWakeTestFeedback = findViewById(R.id.tvWakeTestFeedback)

        cardAiConfig = findViewById(R.id.cardAiConfig)
        layoutAiConfigHeader = findViewById(R.id.layoutAiConfigHeader)
        tvAiConfigSummary = findViewById(R.id.tvAiConfigSummary)
        tvAiConfigToggle = findViewById(R.id.tvAiConfigToggle)
        layoutAiConfigBody = findViewById(R.id.layoutAiConfigBody)
        layoutProviderSelector = findViewById(R.id.layoutProviderSelector)
        tvProviderValue = findViewById(R.id.tvProviderValue)
        layoutCustomFormatSection = findViewById(R.id.layoutCustomFormatSection)
        layoutFormatSelector = findViewById(R.id.layoutFormatSelector)
        tvFormatValue = findViewById(R.id.tvFormatValue)
        etLlmBaseUrl = findViewById(R.id.etLlmBaseUrl)
        etLlmApiKey = findViewById(R.id.etLlmApiKey)
        tvToggleApiKeyVisibility = findViewById(R.id.tvToggleApiKeyVisibility)
        tvModelModeToggle = findViewById(R.id.tvModelModeToggle)
        layoutModelPickerRow = findViewById(R.id.layoutModelPickerRow)
        layoutModelPickerBox = findViewById(R.id.layoutModelPickerBox)
        progressModelLoading = findViewById(R.id.progressModelLoading)
        tvModelPrimary = findViewById(R.id.tvModelPrimary)
        tvModelSecondary = findViewById(R.id.tvModelSecondary)
        tvModelChevron = findViewById(R.id.tvModelChevron)
        btnRefreshModels = findViewById(R.id.btnRefreshModels)
        etLlmModelManual = findViewById(R.id.etLlmModelManual)
        tvModelListStatus = findViewById(R.id.tvModelListStatus)
        layoutThinkingSelector = findViewById(R.id.layoutThinkingSelector)
        tvThinkingValue = findViewById(R.id.tvThinkingValue)
        switchUseLlmForTranslate = findViewById(R.id.switchUseLlmForTranslate)
        btnSaveLlmConfig = findViewById(R.id.btnSaveLlmConfig)
        btnTestLlmConfig = findViewById(R.id.btnTestLlmConfig)
        tvLlmTestStatus = findViewById(R.id.tvLlmTestStatus)

        etTestInput = findViewById(R.id.etTestInput)
        btnPreviewBar = findViewById(R.id.btnPreviewBar)
        btnCopyTrigger = findViewById(R.id.btnCopyTrigger)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnShowDiagnostics = findViewById(R.id.btnShowDiagnostics)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)
    }

    private fun applySafeAreaInsets() {
        val baseLeft = contentContainer.paddingLeft
        val baseTop = contentContainer.paddingTop
        val baseRight = contentContainer.paddingRight
        val baseBottom = contentContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            rootScroll.setPadding(bars.left, bars.top, bars.right, 0)
            contentContainer.setPadding(baseLeft, baseTop, baseRight, baseBottom + bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(rootScroll)
    }

    private fun setupHeaderVersion() {
        tvAppVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val radiusPx = dpF(10f)
        tvAppVersion.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor("#E8F1FF"))
        }
    }

    private fun styleAiInputBoxes() {
        val boxRadius = dpF(10f)
        val strokePx = dp(1)
        val strokeColor = Color.parseColor("#E5E7EB")
        val fillColor = Color.parseColor("#F3F4F6")
        listOf(
            layoutProviderSelector,
            layoutFormatSelector,
            etLlmBaseUrl,
            etLlmApiKey,
            layoutModelPickerBox,
            etLlmModelManual,
            layoutThinkingSelector,
        ).forEach { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = boxRadius
                setColor(fillColor)
                setStroke(strokePx, strokeColor)
            }
        }
        layoutTriggerStatsBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(12f)
            setColor(Color.parseColor("#F3F5F9"))
            setStroke(strokePx, strokeColor)
        }
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_EXPAND_AI_CONFIG, false) == true) {
            aiConfigExpanded = true
            applyAiConfigExpandedUi()
            fetchModels(force = false)
            rootScroll.post {
                rootScroll.smoothScrollTo(0, (cardAiConfig.top - dp(12)).coerceAtLeast(0))
            }
            intent.removeExtra(EXTRA_EXPAND_AI_CONFIG)
        }
    }

    private fun setupListeners() {
        layoutPermissionsHeader.setOnClickListener {
            userToggledPermissionsCard = true
            permissionsExpanded = !permissionsExpanded
            applyPermissionsExpandedUi()
        }
        btnShizukuAction.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                openShizukuApp()
                return@setOnClickListener
            }
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "当前 Shizuku 版本过旧，请升级 Shizuku", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
            }
        }

        btnOverlayAction.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            runCatching { startActivity(intent) }.onFailure {
                openAppDetailsSettings()
            }
        }

        btnNotificationAction.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnAccessibilityAction.setOnClickListener {
            val snap = MonitorState.snapshot.value
            if (snap.watchServiceRunning && snap.userServiceConnected) {
                ClipboardWatchService.enableAccessibilityViaShizuku(this)
                Toast.makeText(this, "正在通过 Shizuku 自动开启无障碍…", Toast.LENGTH_SHORT).show()
            } else {
                openAccessibilitySettings()
            }
        }

        btnAppSettings.setOnClickListener {
            openAppDetailsSettings()
        }

        btnToggleWatch.setOnClickListener {
            val running = MonitorState.snapshot.value.watchServiceRunning
            if (running) {
                userManuallyPaused = true
                watchStartInFlight = false
                ClipboardWatchService.stop(this)
            } else {
                userManuallyPaused = false
                startWatchingWithChecks()
            }
        }

        switchAutoMonitor.setOnCheckedChangeListener { _, isChecked ->
            val prev = MonitorState.snapshot.value.autoStartMonitoring
            if (prev != isChecked) {
                appPrefs.autoStartMonitoring = isChecked
                MonitorState.update { it.copy(autoStartMonitoring = isChecked) }
                MonitorState.addLog(
                    if (isChecked) "已开启条件满足时自动监听" else "已关闭自动监听",
                )
                if (isChecked) {
                    userManuallyPaused = false
                    maybeAutoStartMonitoring()
                }
            }
        }

        switchAccessibilitySelection.setOnCheckedChangeListener { _, isChecked ->
            val prev = MonitorState.snapshot.value.enableAccessibilitySelection
            if (prev != isChecked) {
                appPrefs.enableAccessibilitySelection = isChecked
                MonitorState.update { it.copy(enableAccessibilitySelection = isChecked) }
                MonitorState.addLog(
                    if (isChecked) "已开启选词自动弹出悬浮条（无障碍）" else "已关闭无障碍选词自动弹出",
                )
                if (isChecked && !SelectionAccessibilityService.isEnabledInSettings(this)) {
                    val snap = MonitorState.snapshot.value
                    if (snap.watchServiceRunning && snap.userServiceConnected) {
                        ClipboardWatchService.enableAccessibilityViaShizuku(this)
                    } else {
                        Toast.makeText(this, "请在系统设置中开启 QDuo 无障碍服务", Toast.LENGTH_SHORT).show()
                        openAccessibilitySettings()
                    }
                }
            }
        }

        tvResetStats.setOnClickListener {
            MonitorState.resetStats()
            Toast.makeText(this, "已重置统计数据", Toast.LENGTH_SHORT).show()
        }

        switchAutoWhitelist.setOnCheckedChangeListener { _, isChecked ->
            val prev = MonitorState.snapshot.value.autoApplyWhitelist
            if (prev != isChecked) {
                appPrefs.autoApplyWhitelist = isChecked
                MonitorState.update { it.copy(autoApplyWhitelist = isChecked) }
                MonitorState.addLog(
                    if (isChecked) "已开启连接 Shizuku 时自动加入后台白名单" else "已关闭自动加入后台白名单",
                )
            }
        }

        btnApplyWhitelist.setOnClickListener {
            val snap = MonitorState.snapshot.value
            if (snap.watchServiceRunning && snap.userServiceConnected) {
                ClipboardWatchService.applyExemptionsNow(this)
                Toast.makeText(this, "正在通过 Shizuku 写入后台白名单…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先开启上方监听（也可在系统电池设置中手动放行）", Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        switchFreezeWakeup.setOnCheckedChangeListener { _, isChecked ->
            val prev = MonitorState.snapshot.value.enableFreezeWakeup
            if (prev != isChecked) {
                appPrefs.enableFreezeWakeup = isChecked
                MonitorState.update { it.copy(enableFreezeWakeup = isChecked) }
                MonitorState.addLog(
                    if (isChecked) "已启用冻结自动唤醒兜底" else "已关闭冻结自动唤醒兜底",
                )
                ClipboardWatchService.notifyModeChanged(this)
            }
        }

        btnTestWakePath.setOnClickListener {
            val snap = MonitorState.snapshot.value
            if (snap.watchServiceRunning && snap.userServiceConnected) {
                ClipboardWatchService.requestTestWake(this)
            } else {
                Toast.makeText(this, "请先开启上方监听连接 Shizuku", Toast.LENGTH_SHORT).show()
            }
        }

        layoutAiConfigHeader.setOnClickListener {
            aiConfigExpanded = !aiConfigExpanded
            applyAiConfigExpandedUi()
            if (aiConfigExpanded) {
                fetchModels(force = false)
            }
        }

        layoutProviderSelector.setOnClickListener {
            showProviderPickerDialog()
        }

        layoutFormatSelector.setOnClickListener {
            showFormatPickerDialog()
        }

        tvToggleApiKeyVisibility.setOnClickListener {
            apiKeyVisible = !apiKeyVisible
            val sel = etLlmApiKey.selectionEnd.coerceAtLeast(0)
            etLlmApiKey.inputType = if (apiKeyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etLlmApiKey.setSelection(sel.coerceAtMost(etLlmApiKey.text?.length ?: 0))
            tvToggleApiKeyVisibility.text = if (apiKeyVisible) "隐藏" else "显示"
        }

        val credentialWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isPopulatingAiInputs) return
                saveAiConfigFromInputs()
                refreshAiConfigSummary()
                scheduleDebouncedModelFetch()
            }
        }
        etLlmBaseUrl.addTextChangedListener(credentialWatcher)
        etLlmApiKey.addTextChangedListener(credentialWatcher)

        etLlmModelManual.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (isPopulatingAiInputs) return
                    appPrefs.llmModel = s?.toString().orEmpty().trim()
                    renderThinkingPickerUi()
                    refreshAiConfigSummary()
                }
            },
        )

        layoutThinkingSelector.setOnClickListener {
            showThinkingPickerDialog()
        }

        tvModelModeToggle.setOnClickListener {
            manualModelTyping = !manualModelTyping
            if (manualModelTyping) {
                isPopulatingAiInputs = true
                etLlmModelManual.setText(appPrefs.llmModel)
                isPopulatingAiInputs = false
                etLlmModelManual.requestFocus()
            } else {
                appPrefs.llmModel = etLlmModelManual.text.toString().trim()
                fetchModels(force = false)
            }
            renderModelPickerUi()
        }

        layoutModelPickerBox.setOnClickListener {
            when (val state = modelListState) {
                is ModelListState.Loaded -> {
                    if (state.models.isNotEmpty()) {
                        showModelPickerDialog(state.models)
                    } else {
                        manualModelTyping = true
                        renderModelPickerUi()
                        etLlmModelManual.requestFocus()
                    }
                }

                is ModelListState.NeedsKey -> {
                    Toast.makeText(this, "请先填入 API Key，系统将自动拉取模型列表", Toast.LENGTH_SHORT).show()
                    etLlmApiKey.requestFocus()
                }

                is ModelListState.Loading -> Unit
                is ModelListState.Failed, ModelListState.Idle -> {
                    fetchModels(force = true)
                }
            }
        }

        btnRefreshModels.setOnClickListener {
            manualModelTyping = false
            saveAiConfigFromInputs()
            fetchModels(force = true)
        }

        switchUseLlmForTranslate.setOnCheckedChangeListener { _, isChecked ->
            if (!isPopulatingAiInputs) {
                appPrefs.useLlmForTranslate = isChecked
            }
        }

        btnSaveLlmConfig.setOnClickListener {
            saveAiConfigFromInputs()
            refreshAiConfigSummary()
            showTransientFeedback(
                view = tvLlmTestStatus,
                text = "配置已保存 ✓",
                isError = false,
                showToast = true,
                previousRunnable = hideLlmStatusRunnable,
            ) { hideLlmStatusRunnable = it }
        }

        btnTestLlmConfig.setOnClickListener {
            saveAiConfigFromInputs()
            refreshAiConfigSummary()
            val provider = appPrefs.activeProvider
            val format = appPrefs.activeApiFormat
            val url = appPrefs.llmBaseUrl
            val key = appPrefs.llmApiKey
            val model = appPrefs.llmModel
            val effort = appPrefs.llmReasoningEffort
            val levels = currentReasoningLevels()
            tvLlmTestStatus.setTextColor(Color.parseColor("#6B7280"))
            tvLlmTestStatus.text = "正在拉取模型并测试连接…"
            tvLlmTestStatus.visibility = View.VISIBLE
            lifecycleScope.launch {
                val res = LlmClient.testConnection(
                    provider = provider,
                    format = format,
                    baseUrl = url,
                    apiKey = key,
                    model = model,
                    reasoningEffort = effort,
                    reasoningLevels = levels,
                )
                if (res.isSuccess) {
                    val summary = res.getOrThrow()
                    val listed = summary.listedModels
                    if (listed != null && listed.isNotEmpty()) {
                        val cacheKey = ModelListStore.cacheKey(provider.id, format, url, key)
                        ModelListStore.putCached(cacheKey, listed)
                        modelListState = ModelListState.Loaded(listed)
                        if (appPrefs.llmModel.isBlank()) {
                            appPrefs.llmModel = listed.first().id
                            isPopulatingAiInputs = true
                            etLlmModelManual.setText(appPrefs.llmModel)
                            isPopulatingAiInputs = false
                        }
                        renderModelPickerUi()
                        renderThinkingPickerUi()
                        refreshAiConfigSummary()
                    }
                    val countPart = listed?.let { " · ${it.size} 个模型" }.orEmpty()
                    val warnPart = if (summary.modelListed == false) "（所选模型未在列表中）" else ""
                    val msg = "连接成功 ✓ (${summary.elapsedMs}ms$countPart)$warnPart"
                    MonitorState.addLog("AI 模型连接测试: $msg (${appPrefs.llmModel})")
                    showTransientFeedback(
                        view = tvLlmTestStatus,
                        text = msg,
                        isError = false,
                        showToast = true,
                        previousRunnable = hideLlmStatusRunnable,
                    ) { hideLlmStatusRunnable = it }
                } else {
                    val errMsg = "连接失败：${res.exceptionOrNull()?.message ?: "未知错误"}"
                    MonitorState.addLog("AI 模型连接测试失败: $errMsg")
                    showTransientFeedback(
                        view = tvLlmTestStatus,
                        text = errMsg,
                        isError = true,
                        showToast = true,
                        previousRunnable = hideLlmStatusRunnable,
                    ) { hideLlmStatusRunnable = it }
                }
            }
        }

        btnPreviewBar.setOnClickListener {
            val sample = etTestInput.text.toString().trim()
                .ifEmpty { getString(R.string.sample_test_text) }
            if (Settings.canDrawOverlays(this)) {
                OverlayController.showActionBar(applicationContext, sample)
            } else {
                Toast.makeText(this, "未开启悬浮窗权限，先在应用内预览底部卡片", Toast.LENGTH_SHORT).show()
                showInAppFallbackSheet(sample)
            }
        }

        btnCopyTrigger.setOnClickListener {
            val sample = etTestInput.text.toString().trim()
                .ifEmpty { getString(R.string.sample_test_text) }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QDuo Test", sample))
            MonitorState.addLog("已主动写入剪贴板 (${sample.length} 字)")
            if (!MonitorState.snapshot.value.watchServiceRunning) {
                Toast.makeText(this, "已复制，请先开启上方「剪贴板悬浮条监听」", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyLogs.setOnClickListener {
            val allLogs = MonitorState.snapshot.value.logs.joinToString("\n")
            if (allLogs.isBlank()) {
                Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SelfCopyGuard.shared.markSelfCopied(allLogs)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QDuo Logs", allLogs))
            Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show()
        }

        btnShowDiagnostics.setOnClickListener {
            val diag = MonitorState.snapshot.value.diagnostics
                .ifBlank { "尚未连接 Shizuku UserService，开启监听后会自动采集 IClipboard 诊断信息。" }
            AlertDialog.Builder(this)
                .setTitle("Shizuku & IClipboard 诊断")
                .setMessage(diag)
                .setPositiveButton("关闭", null)
                .show()
        }

        btnClearLogs.setOnClickListener {
            MonitorState.clearLogs()
        }
    }

    private fun scheduleDebouncedModelFetch() {
        debounceFetchJob?.cancel()
        debounceFetchJob = lifecycleScope.launch {
            delay(500L)
            fetchModels(force = false)
        }
    }

    private fun fetchModels(force: Boolean) {
        val provider = appPrefs.activeProvider
        val format = appPrefs.activeApiFormat
        val url = etLlmBaseUrl.text.toString().trim()
        val key = etLlmApiKey.text.toString().trim()

        if (url.isBlank()) {
            activeFetchJob?.cancel()
            modelListState = ModelListState.Idle
            renderModelPickerUi()
            return
        }

        if (provider.needsKey && key.isBlank()) {
            activeFetchJob?.cancel()
            modelListState = ModelListState.NeedsKey
            renderModelPickerUi()
            return
        }

        val cacheKey = ModelListStore.cacheKey(provider.id, format, url, key)
        if (!force) {
            val cached = ModelListStore.getCached(cacheKey)
            if (cached != null) {
                modelListState = ModelListState.Loaded(cached)
                autoSelectFirstIfUnset(cached)
                renderModelPickerUi()
                renderThinkingPickerUi()
                return
            }
        }

        activeFetchJob?.cancel()
        modelListState = ModelListState.Loading
        renderModelPickerUi()

        activeFetchJob = lifecycleScope.launch {
            val result = LlmClient.listModels(format, url, key)
            if (result.isSuccess) {
                val models = result.getOrDefault(emptyList())
                ModelListStore.putCached(cacheKey, models)
                modelListState = ModelListState.Loaded(models)
                autoSelectFirstIfUnset(models)
                MonitorState.addLog("已自动拉取 ${models.size} 个模型 (${provider.displayName})")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "拉取失败"
                modelListState = ModelListState.Failed(msg)
                MonitorState.addLog("模型列表拉取失败 (${provider.displayName}): $msg")
            }
            renderModelPickerUi()
            renderThinkingPickerUi()
        }
    }

    private fun autoSelectFirstIfUnset(models: List<ModelInfo>) {
        if (models.isEmpty()) return
        if (appPrefs.llmModel.isBlank()) {
            appPrefs.llmModel = models.first().id
            isPopulatingAiInputs = true
            etLlmModelManual.setText(appPrefs.llmModel)
            isPopulatingAiInputs = false
            refreshAiConfigSummary()
        }
    }

    private fun showProviderPickerDialog() {
        val providers = LlmProviders.ALL
        val labels = providers.map { it.displayName }.toTypedArray()
        val currentIdx = providers.indexOfFirst { it.id == appPrefs.llmProviderId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择 AI 服务商")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                dialog.dismiss()
                val chosen = providers[which]
                if (chosen.id != appPrefs.llmProviderId) {
                    saveAiConfigFromInputs()
                    appPrefs.llmProviderId = chosen.id
                    manualModelTyping = false
                    debounceFetchJob?.cancel()
                    activeFetchJob?.cancel()
                    loadAiConfigIntoUi()
                    fetchModels(force = false)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFormatPickerDialog() {
        val formats = ApiFormat.entries
        val labels = formats.map { it.label }.toTypedArray()
        val currentIdx = formats.indexOf(appPrefs.llmCustomFormat).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择 API 协议格式")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                dialog.dismiss()
                val chosen = formats[which]
                if (chosen != appPrefs.llmCustomFormat) {
                    saveAiConfigFromInputs()
                    appPrefs.llmCustomFormat = chosen
                    manualModelTyping = false
                    tvFormatValue.text = chosen.label
                    renderThinkingPickerUi()
                    fetchModels(force = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModelPickerDialog(models: List<ModelInfo>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        var filtered = models
        val currentModel = appPrefs.llmModel

        lateinit var dialog: AlertDialog

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): ModelInfo = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                            addView(
                                TextView(this@MainActivity).apply {
                                    tag = "primary"
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                                    setTextColor(Color.parseColor("#111827"))
                                },
                            )
                            addView(
                                TextView(this@MainActivity).apply {
                                    tag = "secondary"
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                                    setTextColor(Color.parseColor("#6B7280"))
                                },
                            )
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            tag = "check"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#1F6FEB"))
                            setPadding(dp(8), 0, 0, 0)
                        },
                    )
                }
                val item = filtered[position]
                val primary = row.findViewWithTag<TextView>("primary")
                val secondary = row.findViewWithTag<TextView>("secondary")
                val check = row.findViewWithTag<TextView>("check")
                primary.text = item.displayName
                if (item.displayName != item.id) {
                    secondary.text = item.id
                    secondary.visibility = View.VISIBLE
                } else {
                    secondary.visibility = View.GONE
                }
                val isSelected = item.id == currentModel
                primary.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                check.text = if (isSelected) "✓" else ""
                return row
            }
        }

        if (models.size > 6) {
            val searchBox = EditText(this).apply {
                hint = "搜索模型名称或 ID…"
                setSingleLine(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(Color.parseColor("#111827"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(8f)
                    setColor(Color.parseColor("#F3F4F6"))
                    setStroke(dp(1), Color.parseColor("#E5E7EB"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val q = s?.toString().orEmpty().trim().lowercase()
                            filtered = if (q.isEmpty()) {
                                models
                            } else {
                                models.filter {
                                    it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q)
                                }
                            }
                            adapter.notifyDataSetChanged()
                        }
                    },
                )
            }
            container.addView(searchBox)
        }

        val listView = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360).coerceAtMost((models.size * dp(56)).coerceAtLeast(dp(140))),
            )
            setOnItemClickListener { _, _, position, _ ->
                val picked = filtered[position]
                appPrefs.llmModel = picked.id
                manualModelTyping = false
                isPopulatingAiInputs = true
                etLlmModelManual.setText(picked.id)
                isPopulatingAiInputs = false
                renderModelPickerUi()
                renderThinkingPickerUi()
                refreshAiConfigSummary()
                dialog.dismiss()
            }
        }
        container.addView(listView)

        dialog = AlertDialog.Builder(this)
            .setTitle("选择模型 (${models.size})")
            .setView(container)
            .setNeutralButton("手动输入…") { _, _ ->
                manualModelTyping = true
                isPopulatingAiInputs = true
                etLlmModelManual.setText(appPrefs.llmModel)
                isPopulatingAiInputs = false
                renderModelPickerUi()
                etLlmModelManual.requestFocus()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun renderModelPickerUi() {
        val state = modelListState
        val selectedModel = appPrefs.llmModel
        val canReturnToList = state is ModelListState.Loaded && state.models.isNotEmpty()

        if (manualModelTyping) {
            layoutModelPickerRow.visibility = View.GONE
            etLlmModelManual.visibility = View.VISIBLE
            tvModelModeToggle.visibility = View.VISIBLE
            tvModelModeToggle.text = if (canReturnToList) "返回列表" else "自动拉取"
            tvModelListStatus.setTextColor(Color.parseColor("#6B7280"))
            tvModelListStatus.text = "当前为手动输入模型 ID 模式"
            return
        }

        layoutModelPickerRow.visibility = View.VISIBLE
        etLlmModelManual.visibility = View.GONE
        tvModelModeToggle.visibility = View.VISIBLE
        tvModelModeToggle.text = "手动输入"

        when (state) {
            ModelListState.Idle -> {
                progressModelLoading.visibility = View.GONE
                tvModelChevron.visibility = View.VISIBLE
                btnRefreshModels.isEnabled = true
                if (selectedModel.isNotBlank()) {
                    tvModelPrimary.text = selectedModel
                    tvModelPrimary.setTextColor(Color.parseColor("#111827"))
                } else {
                    tvModelPrimary.text = "点击刷新以拉取模型列表"
                    tvModelPrimary.setTextColor(Color.parseColor("#6B7280"))
                }
                tvModelSecondary.visibility = View.GONE
                tvModelListStatus.setTextColor(Color.parseColor("#6B7280"))
                tvModelListStatus.text = "展开后会自动向服务商请求可用模型"
            }

            ModelListState.NeedsKey -> {
                progressModelLoading.visibility = View.GONE
                tvModelChevron.visibility = View.VISIBLE
                btnRefreshModels.isEnabled = false
                if (selectedModel.isNotBlank()) {
                    tvModelPrimary.text = selectedModel
                    tvModelPrimary.setTextColor(Color.parseColor("#111827"))
                } else {
                    tvModelPrimary.text = "请先填入 API Key 以自动拉取模型"
                    tvModelPrimary.setTextColor(Color.parseColor("#6B7280"))
                }
                tvModelSecondary.visibility = View.GONE
                tvModelListStatus.setTextColor(Color.parseColor("#6B7280"))
                tvModelListStatus.text = "填入上方 API Key 后将自动拉取可用模型"
            }

            ModelListState.Loading -> {
                progressModelLoading.visibility = View.VISIBLE
                tvModelChevron.visibility = View.GONE
                btnRefreshModels.isEnabled = false
                tvModelPrimary.text = if (selectedModel.isNotBlank()) {
                    "$selectedModel（正在刷新列表…）"
                } else {
                    "正在拉取模型列表…"
                }
                tvModelPrimary.setTextColor(Color.parseColor("#6B7280"))
                tvModelSecondary.visibility = View.GONE
                tvModelListStatus.setTextColor(Color.parseColor("#6B7280"))
                tvModelListStatus.text = "正在请求服务端模型列表…"
            }

            is ModelListState.Loaded -> {
                progressModelLoading.visibility = View.GONE
                tvModelChevron.visibility = View.VISIBLE
                btnRefreshModels.isEnabled = true
                val chosenInfo = state.models.firstOrNull { it.id == selectedModel }
                when {
                    selectedModel.isBlank() && state.models.isEmpty() -> {
                        tvModelPrimary.text = "服务端未返回模型，可点右侧手动输入"
                        tvModelPrimary.setTextColor(Color.parseColor("#B45309"))
                        tvModelSecondary.visibility = View.GONE
                    }

                    selectedModel.isBlank() -> {
                        tvModelPrimary.text = "点击选择模型…"
                        tvModelPrimary.setTextColor(Color.parseColor("#1F6FEB"))
                        tvModelSecondary.visibility = View.GONE
                    }

                    chosenInfo != null -> {
                        tvModelPrimary.text = chosenInfo.displayName
                        tvModelPrimary.setTextColor(Color.parseColor("#111827"))
                        if (chosenInfo.id != chosenInfo.displayName) {
                            tvModelSecondary.text = chosenInfo.id
                            tvModelSecondary.visibility = View.VISIBLE
                        } else {
                            tvModelSecondary.visibility = View.GONE
                        }
                    }

                    else -> {
                        tvModelPrimary.text = "$selectedModel (不在列表中)"
                        tvModelPrimary.setTextColor(Color.parseColor("#111827"))
                        tvModelSecondary.visibility = View.GONE
                    }
                }

                if (state.models.isEmpty()) {
                    tvModelListStatus.setTextColor(Color.parseColor("#B45309"))
                    tvModelListStatus.text = "接口返回了 0 个对话模型，请点右上角「手动输入」"
                } else {
                    tvModelListStatus.setTextColor(Color.parseColor("#047857"))
                    tvModelListStatus.text = "已自动拉取 ${state.models.size} 个模型 · 点击上方框选"
                }
            }

            is ModelListState.Failed -> {
                progressModelLoading.visibility = View.GONE
                tvModelChevron.visibility = View.VISIBLE
                btnRefreshModels.isEnabled = true
                if (selectedModel.isNotBlank()) {
                    tvModelPrimary.text = selectedModel
                    tvModelPrimary.setTextColor(Color.parseColor("#111827"))
                } else {
                    tvModelPrimary.text = "模型拉取失败，点此重试或手动输入"
                    tvModelPrimary.setTextColor(Color.parseColor("#B45309"))
                }
                tvModelSecondary.visibility = View.GONE
                tvModelListStatus.setTextColor(Color.parseColor("#B45309"))
                tvModelListStatus.text = "拉取失败：${state.message}（可点「刷新」重试或切换「手动输入」）"
            }
        }
    }

    private fun startWatchingWithChecks() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "请先打开 Shizuku 并启动无线调试服务", Toast.LENGTH_SHORT).show()
            openShizukuApp()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启「悬浮窗权限」以弹出 PopClip 悬浮条", Toast.LENGTH_LONG).show()
            btnOverlayAction.performClick()
            return
        }
        ClipboardWatchService.start(this)
    }

    private fun refreshStatuses() {
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuRunning &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

        val pm = getSystemService(PowerManager::class.java)
        val whitelistExempted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val axEnabled = SelectionAccessibilityService.isEnabledInSettings(this)
        val axRunning = SelectionAccessibilityService.isRunning()
        SelectionAccessibilityService.syncCountersToState()

        MonitorState.update {
            it.copy(
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
                whitelistExempted = whitelistExempted,
                accessibilityEnabled = axEnabled,
                accessibilityServiceRunning = axRunning,
            )
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (overlayGranted) "已开启 ✓" else "未开启（悬浮条必需）"
        tvOverlayStatus.setTextColor(
            if (overlayGranted) Color.parseColor("#047857") else Color.parseColor("#B45309"),
        )
        btnOverlayAction.isEnabled = !overlayGranted
        btnOverlayAction.text = if (overlayGranted) "已开启" else "前往开启"

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        tvNotificationStatus.text = if (notifGranted) "已开启 ✓" else "未开启（建议允许）"
        tvNotificationStatus.setTextColor(
            if (notifGranted) Color.parseColor("#047857") else Color.parseColor("#6B7280"),
        )
        btnNotificationAction.isEnabled = !notifGranted
        btnNotificationAction.text = if (notifGranted) "已开启" else "申请权限"

        val allPermissionsReady = shizukuRunning && shizukuGranted && overlayGranted && notifGranted &&
            (!appPrefs.enableAccessibilitySelection || axEnabled)
        updatePermissionsCardState(allPermissionsReady)
        maybeAutoStartMonitoring()
    }

    private fun updatePermissionsCardState(allReady: Boolean) {
        tvPermissionsSummary.text = if (allReady) "全部已就绪 ✓" else "待完成"
        tvPermissionsSummary.setTextColor(
            if (allReady) Color.parseColor("#047857") else Color.parseColor("#B45309"),
        )
        if (!userToggledPermissionsCard) {
            permissionsExpanded = !allReady
        }
        applyPermissionsExpandedUi()
    }

    private fun applyPermissionsExpandedUi() {
        layoutPermissionsBody.visibility = if (permissionsExpanded) View.VISIBLE else View.GONE
        tvPermissionsToggle.text = if (permissionsExpanded) "收起 ▴" else "展开 ▾"
    }

    private fun maybeAutoStartMonitoring() {
        val snap = MonitorState.snapshot.value
        if (!snap.autoStartMonitoring || userManuallyPaused || snap.watchServiceRunning || watchStartInFlight) {
            return
        }
        if (snap.shizukuRunning && snap.shizukuGranted && Settings.canDrawOverlays(this)) {
            watchStartInFlight = true
            MonitorState.addLog("条件已就绪，自动开启剪贴板监听")
            ClipboardWatchService.start(this)
        }
    }

    private fun renderSnapshot(snap: MonitorSnapshot) {
        if (snap.watchServiceRunning) {
            watchStartInFlight = false
        }

        when {
            !snap.shizukuRunning -> {
                tvShizukuStatus.text = "Shizuku 未运行（请先在 Shizuku 中启动服务）"
                tvShizukuStatus.setTextColor(Color.parseColor("#B91C1C"))
                btnShizukuAction.isEnabled = true
                btnShizukuAction.text = "打开 Shizuku"
            }
            !snap.shizukuGranted -> {
                tvShizukuStatus.text = "Shizuku 已运行 · 待授权"
                tvShizukuStatus.setTextColor(Color.parseColor("#B45309"))
                btnShizukuAction.isEnabled = true
                btnShizukuAction.text = "一键申请授权"
            }
            else -> {
                val svcText = if (snap.userServiceConnected) "UserService 已连接" else "已授权"
                tvShizukuStatus.text = "Shizuku $svcText ✓"
                tvShizukuStatus.setTextColor(Color.parseColor("#047857"))
                btnShizukuAction.isEnabled = false
                btnShizukuAction.text = "已授权"
            }
        }

        when {
            snap.accessibilityEnabled && snap.accessibilityServiceRunning -> {
                tvAccessibilityStatus.text = "已开启并运行中 ✓（支持选词即弹）"
                tvAccessibilityStatus.setTextColor(Color.parseColor("#047857"))
                btnAccessibilityAction.isEnabled = false
                btnAccessibilityAction.text = "已开启"
            }
            snap.accessibilityEnabled -> {
                tvAccessibilityStatus.text = "已通过 Shizuku 开启 · 正在连接服务…"
                tvAccessibilityStatus.setTextColor(Color.parseColor("#047857"))
                btnAccessibilityAction.isEnabled = false
                btnAccessibilityAction.text = "已开启"
            }
            else -> {
                tvAccessibilityStatus.text = "未开启（开启后无需复制即可弹窗）"
                tvAccessibilityStatus.setTextColor(Color.parseColor("#B45309"))
                btnAccessibilityAction.isEnabled = true
                btnAccessibilityAction.text = if (snap.userServiceConnected) "一键开启" else "前往开启"
            }
        }

        tvWatchMode.text = "当前状态：${snap.activeMode}"
        btnToggleWatch.text = if (snap.watchServiceRunning) {
            "停止剪贴板悬浮条监听"
        } else {
            "开启剪贴板悬浮条监听"
        }
        if (switchAutoMonitor.isChecked != snap.autoStartMonitoring) {
            switchAutoMonitor.isChecked = snap.autoStartMonitoring
        }
        if (switchAccessibilitySelection.isChecked != snap.enableAccessibilitySelection) {
            switchAccessibilitySelection.isChecked = snap.enableAccessibilitySelection
        }

        tvLastTriggerSummary.text = "最近触发：${snap.lastTriggerSummary}"
        tvTriggerStatsDetail.text =
            "【长按选词 · 无障碍】\n" +
                "• 选区事件直读：${snap.axSelectionDirectCount} 次（无文本回落 ${snap.axSelectionNoTextCount}）\n" +
                "• 弹窗触发·焦点直读：${snap.axToolbarFocusDirectCount} 次\n" +
                "• 弹窗触发·代码代复制：弹栏 ${snap.axToolbarAutoCopyShownCount} · 成功 ${snap.axAutoCopySuccessCount} · 失败 ${snap.axAutoCopyFailCount}\n" +
                "【主动动作 · 确定触发】\n" +
                "• 主动复制（剪贴板）：${snap.clipTriggerCount} 次\n" +
                "• 选词菜单「QDuo」：${snap.processTextMenuCount} 次 · 系统分享：${snap.shareIntentCount} 次\n" +
                "【底层扫描诊断】\n" +
                "• 弹窗扫描：${snap.axProbeCount} 次（未命中 ${snap.axProbeMissCount} · 过滤噪音 ${snap.axWindowNoiseDropped}）"

        val whitelistBase = if (snap.whitelistExempted) {
            "电池优化白名单：已豁免 ✓"
        } else {
            "电池优化白名单：未豁免（建议开启）"
        }
        tvWhitelistStatus.text = if (snap.lastWhitelistResult.isNotBlank()) {
            "$whitelistBase · ${snap.lastWhitelistResult}"
        } else {
            whitelistBase
        }
        tvWhitelistStatus.setTextColor(
            if (snap.whitelistExempted) Color.parseColor("#047857") else Color.parseColor("#B45309"),
        )
        if (switchAutoWhitelist.isChecked != snap.autoApplyWhitelist) {
            switchAutoWhitelist.isChecked = snap.autoApplyWhitelist
        }

        val statsBase = "精准统计：直连送达 ${snap.directDeliveryCount} 次 · 解冻唤醒 ${snap.freezeWakeCount} 次"
        tvFreezeWakeStats.text = statsBase
        if (switchFreezeWakeup.isChecked != snap.enableFreezeWakeup) {
            switchFreezeWakeup.isChecked = snap.enableFreezeWakeup
        }

        if (snap.axEnableFeedbackSeq > lastHandledAxEnableSeq) {
            lastHandledAxEnableSeq = snap.axEnableFeedbackSeq
            if (snap.axEnableFeedback.isNotBlank()) {
                val ok = snap.axEnableFeedback.contains("✓")
                Toast.makeText(this, snap.axEnableFeedback, Toast.LENGTH_SHORT).show()
                refreshStatuses()
                mainHandler.postDelayed({ refreshStatuses() }, 500L)
                if (!ok) {
                    openAccessibilitySettings()
                }
            }
        }

        if (snap.whitelistFeedbackSeq > lastHandledWhitelistSeq) {
            lastHandledWhitelistSeq = snap.whitelistFeedbackSeq
            if (snap.whitelistFeedback.isNotBlank()) {
                showTransientFeedback(
                    view = tvWhitelistActionFeedback,
                    text = snap.whitelistFeedback,
                    isError = false,
                    showToast = true,
                    previousRunnable = hideWhitelistRunnable,
                ) { hideWhitelistRunnable = it }
            }
        }

        if (snap.wakeTestFeedbackSeq > lastHandledWakeTestSeq) {
            lastHandledWakeTestSeq = snap.wakeTestFeedbackSeq
            if (snap.wakeTestFeedback.isNotBlank()) {
                showTransientFeedback(
                    view = tvWakeTestFeedback,
                    text = snap.wakeTestFeedback,
                    isError = false,
                    showToast = true,
                    previousRunnable = hideWakeTestRunnable,
                ) { hideWakeTestRunnable = it }
            }
        }

        if (snap.logs !== lastRenderedLogs) {
            lastRenderedLogs = snap.logs
            tvLogs.text = if (snap.logs.isEmpty()) {
                "暂无日志"
            } else {
                snap.logs.joinToString("\n")
            }
        }
    }

    private fun loadAiConfigIntoUi() {
        val provider = appPrefs.activeProvider
        val format = appPrefs.activeApiFormat
        val url = appPrefs.llmBaseUrl
        val key = appPrefs.llmApiKey
        val model = appPrefs.llmModel

        isPopulatingAiInputs = true
        tvProviderValue.text = provider.displayName
        layoutCustomFormatSection.visibility =
            if (provider.id == LlmProviders.CUSTOM.id) View.VISIBLE else View.GONE
        tvFormatValue.text = format.label
        etLlmBaseUrl.hint = provider.defaultUrl.ifEmpty { "http://localhost:1234/v1" }
        etLlmBaseUrl.setText(url)
        etLlmApiKey.setText(key)
        etLlmModelManual.setText(model)
        switchUseLlmForTranslate.isChecked = appPrefs.useLlmForTranslate
        isPopulatingAiInputs = false

        val cached = ModelListStore.getCached(
            ModelListStore.cacheKey(provider.id, format, url, key),
        )
        modelListState = when {
            cached != null -> ModelListState.Loaded(cached)
            provider.needsKey && key.isBlank() -> ModelListState.NeedsKey
            else -> ModelListState.Idle
        }

        renderModelPickerUi()
        renderThinkingPickerUi()
        refreshAiConfigSummary()
        applyAiConfigExpandedUi()
    }

    private fun currentReasoningLevels(): List<String>? {
        val state = modelListState
        val selectedModel = appPrefs.llmModel
        if (state is ModelListState.Loaded && selectedModel.isNotBlank()) {
            return state.models.firstOrNull { it.id == selectedModel }?.reasoningLevels
        }
        return ModelListStore.reasoningLevelsFor(
            providerId = appPrefs.activeProvider.id,
            format = appPrefs.activeApiFormat,
            baseUrl = appPrefs.llmBaseUrl,
            apiKey = appPrefs.llmApiKey,
            modelId = selectedModel,
        )
    }

    private fun renderThinkingPickerUi() {
        val provider = appPrefs.activeProvider
        val format = appPrefs.activeApiFormat
        val levels = currentReasoningLevels()
        val stored = appPrefs.llmReasoningEffort
        val clamped = provider.clampThinking(stored, format, levels)
        if (clamped != stored) {
            appPrefs.llmReasoningEffort = clamped
        }
        tvThinkingValue.text = provider.labelForEffort(clamped, format, levels)
    }

    private fun showThinkingPickerDialog() {
        val provider = appPrefs.activeProvider
        val format = appPrefs.activeApiFormat
        val levels = currentReasoningLevels()
        val options = provider.thinkingOptions(format, levels)
        val current = provider.clampThinking(appPrefs.llmReasoningEffort, format, levels)
        val currentIdx = options.indexOfFirst { it.tag == current }.coerceAtLeast(0)
        val labels = options.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择思考程度")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                dialog.dismiss()
                appPrefs.llmReasoningEffort = options[which].tag
                renderThinkingPickerUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAiConfigFromInputs() {
        appPrefs.llmBaseUrl = etLlmBaseUrl.text.toString().trim()
        appPrefs.llmApiKey = etLlmApiKey.text.toString().trim()
        if (manualModelTyping) {
            appPrefs.llmModel = etLlmModelManual.text.toString().trim()
        }
        appPrefs.useLlmForTranslate = switchUseLlmForTranslate.isChecked
    }

    private fun refreshAiConfigSummary() {
        if (appPrefs.hasLlmConfig()) {
            tvAiConfigSummary.text = "已配置 · ${appPrefs.llmModel}"
            tvAiConfigSummary.setTextColor(Color.parseColor("#047857"))
        } else {
            tvAiConfigSummary.text = "仅免费翻译"
            tvAiConfigSummary.setTextColor(Color.parseColor("#6B7280"))
        }
    }

    private fun applyAiConfigExpandedUi() {
        layoutAiConfigBody.visibility = if (aiConfigExpanded) View.VISIBLE else View.GONE
        tvAiConfigToggle.text = if (aiConfigExpanded) "收起 ▴" else "展开 ▾"
    }

    private inline fun showTransientFeedback(
        view: TextView,
        text: String,
        isError: Boolean,
        showToast: Boolean,
        previousRunnable: Runnable?,
        assignRunnable: (Runnable) -> Unit,
    ) {
        previousRunnable?.let { mainHandler.removeCallbacks(it) }
        view.setTextColor(
            Color.parseColor(if (isError) "#B91C1C" else "#047857"),
        )
        view.text = text
        view.visibility = View.VISIBLE
        if (showToast) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        val r = Runnable { view.visibility = View.GONE }
        assignRunnable(r)
        mainHandler.postDelayed(r, 4000L)
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "未检测到 Shizuku 应用，请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            openAppDetailsSettings()
        }
    }

    private fun showInAppFallbackSheet(text: String) {
        val dialog = BottomSheetDialog(this, R.style.Theme_QDuoDroid_BottomSheetDialog)
        val container = FrameLayout(this)
        val content = OverlayController.buildResultSheetView(
            context = this,
            inputText = text,
            initialAction = QDuoAction.TRANSLATE,
            onDismiss = { dialog.dismiss() },
        )
        container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setContentView(container)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )

    companion object {
        const val EXTRA_EXPAND_AI_CONFIG = "expand_ai_config"
        private const val REQ_SHIZUKU_PERMISSION = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
