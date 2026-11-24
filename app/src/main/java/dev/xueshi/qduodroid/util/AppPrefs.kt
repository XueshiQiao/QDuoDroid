package dev.xueshi.qduodroid.util

import android.content.Context
import dev.xueshi.qduodroid.engine.ApiFormat
import dev.xueshi.qduodroid.engine.LlmProvider
import dev.xueshi.qduodroid.engine.LlmProviders

class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoApplyWhitelist: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WHITELIST, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_WHITELIST, value).apply()

    var enableFreezeWakeup: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FREEZE_WAKEUP, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FREEZE_WAKEUP, value).apply()

    var autoStartMonitoring: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_MONITORING, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_MONITORING, value).apply()

    var enableAccessibilitySelection: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AX_SELECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AX_SELECTION, value).apply()

    var llmProviderId: String
        get() = prefs.getString(KEY_LLM_PROVIDER, LlmProviders.DEEPSEEK.id)
            .orEmpty()
            .ifBlank { LlmProviders.DEEPSEEK.id }
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, LlmProviders.byId(value).id).apply()

    var llmCustomFormat: ApiFormat
        get() = ApiFormat.fromId(prefs.getString(KEY_LLM_CUSTOM_FORMAT, ApiFormat.OPENAI.id))
        set(value) = prefs.edit().putString(KEY_LLM_CUSTOM_FORMAT, value.id).apply()

    val activeProvider: LlmProvider
        get() = LlmProviders.byId(llmProviderId)

    val activeApiFormat: ApiFormat
        get() = if (activeProvider.id == LlmProviders.CUSTOM.id) llmCustomFormat else activeProvider.format

    fun formatFor(provider: LlmProvider): ApiFormat =
        if (provider.id == LlmProviders.CUSTOM.id) llmCustomFormat else provider.format

    fun urlFor(providerId: String): String {
        val provider = LlmProviders.byId(providerId)
        val scoped = prefs.getString(keyUrl(provider.id), null)
        if (!scoped.isNullOrBlank()) return scoped
        if (provider.id == LlmProviders.DEEPSEEK.id) {
            val legacy = prefs.getString(KEY_LEGACY_BASE_URL, null)
            if (!legacy.isNullOrBlank()) {
                return if (legacy == "https://api.deepseek.com/v1") provider.defaultUrl else legacy
            }
        }
        return provider.defaultUrl
    }

    fun setUrlFor(providerId: String, url: String) {
        val provider = LlmProviders.byId(providerId)
        prefs.edit().putString(keyUrl(provider.id), url.trim()).apply()
    }

    fun apiKeyFor(providerId: String): String {
        val provider = LlmProviders.byId(providerId)
        val scoped = prefs.getString(keyApiKey(provider.id), null)
        if (!scoped.isNullOrBlank()) return scoped
        if (provider.id == LlmProviders.DEEPSEEK.id) {
            return prefs.getString(KEY_LEGACY_API_KEY, "").orEmpty().trim()
        }
        return ""
    }

    fun setApiKeyFor(providerId: String, apiKey: String) {
        val provider = LlmProviders.byId(providerId)
        prefs.edit().putString(keyApiKey(provider.id), apiKey.trim()).apply()
    }

    fun modelFor(providerId: String): String {
        val provider = LlmProviders.byId(providerId)
        val scoped = prefs.getString(keyModel(provider.id), null)
        if (scoped != null) return scoped.trim()
        if (provider.id == LlmProviders.DEEPSEEK.id) {
            val legacyKey = prefs.getString(KEY_LEGACY_API_KEY, "").orEmpty().trim()
            val legacyModel = prefs.getString(KEY_LEGACY_MODEL, "").orEmpty().trim()
            if (legacyKey.isNotEmpty() && legacyModel.isNotEmpty() && legacyModel != LEGACY_DEFAULT_MODEL) {
                return legacyModel
            }
        }
        return ""
    }

    fun setModelFor(providerId: String, model: String) {
        val provider = LlmProviders.byId(providerId)
        prefs.edit().putString(keyModel(provider.id), model.trim()).apply()
    }

    fun reasoningEffortFor(providerId: String): String {
        val provider = LlmProviders.byId(providerId)
        val stored = prefs.getString(keyEffort(provider.id), null)
        return stored?.trim() ?: provider.initialEffort(formatFor(provider))
    }

    fun setReasoningEffortFor(providerId: String, effort: String) {
        val provider = LlmProviders.byId(providerId)
        prefs.edit().putString(keyEffort(provider.id), effort.trim()).apply()
    }

    var llmBaseUrl: String
        get() = urlFor(llmProviderId)
        set(value) = setUrlFor(llmProviderId, value)

    var llmApiKey: String
        get() = apiKeyFor(llmProviderId)
        set(value) = setApiKeyFor(llmProviderId, value)

    var llmModel: String
        get() = modelFor(llmProviderId)
        set(value) = setModelFor(llmProviderId, value)

    var llmReasoningEffort: String
        get() = reasoningEffortFor(llmProviderId)
        set(value) = setReasoningEffortFor(llmProviderId, value)

    var useLlmForTranslate: Boolean
        get() = prefs.getBoolean(KEY_USE_LLM_FOR_TRANSLATE, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_LLM_FOR_TRANSLATE, value).apply()

    fun hasLlmConfig(): Boolean {
        val provider = activeProvider
        val url = urlFor(provider.id)
        val model = modelFor(provider.id)
        val key = apiKeyFor(provider.id)
        return url.isNotBlank() && model.isNotBlank() && (!provider.needsKey || key.isNotBlank())
    }

    companion object {
        private const val PREFS_NAME = "qduo_prefs"
        private const val KEY_AUTO_WHITELIST = "auto_apply_whitelist"
        private const val KEY_ENABLE_FREEZE_WAKEUP = "enable_freeze_wakeup"
        private const val KEY_AUTO_START_MONITORING = "auto_start_monitoring"
        private const val KEY_ENABLE_AX_SELECTION = "enable_ax_selection"
        private const val KEY_LLM_PROVIDER = "llm_provider_id"
        private const val KEY_LLM_CUSTOM_FORMAT = "llm_custom_format"
        private const val KEY_USE_LLM_FOR_TRANSLATE = "use_llm_for_translate"

        private const val KEY_LEGACY_BASE_URL = "llm_base_url"
        private const val KEY_LEGACY_API_KEY = "llm_api_key"
        private const val KEY_LEGACY_MODEL = "llm_model"
        private const val LEGACY_DEFAULT_MODEL = "deepseek-chat"

        private fun keyUrl(providerId: String) = "llm_url_$providerId"
        private fun keyApiKey(providerId: String) = "llm_key_$providerId"
        private fun keyModel(providerId: String) = "llm_model_$providerId"
        private fun keyEffort(providerId: String) = "llm_effort_$providerId"
    }
}
