package dev.xueshi.qduodroid.engine

import java.util.concurrent.ConcurrentHashMap

sealed interface ModelListState {
    data object Idle : ModelListState
    data object NeedsKey : ModelListState
    data object Loading : ModelListState
    data class Loaded(val models: List<ModelInfo>) : ModelListState
    data class Failed(val message: String) : ModelListState
}

object ModelListStore {

    private val cache = ConcurrentHashMap<String, List<ModelInfo>>()

    fun cacheKey(
        providerId: String,
        format: ApiFormat,
        baseUrl: String,
        apiKey: String,
    ): String {
        val base = LlmEndpoint.parse(format, baseUrl)?.base ?: baseUrl.trim()
        return "$providerId|${format.id}|$base|${apiKey.trim().hashCode()}"
    }

    fun getCached(key: String): List<ModelInfo>? = cache[key]

    fun putCached(key: String, models: List<ModelInfo>) {
        cache[key] = models
    }

    fun reasoningLevelsFor(
        providerId: String,
        format: ApiFormat,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): List<String>? {
        val key = cacheKey(providerId, format, baseUrl, apiKey)
        return getCached(key)?.firstOrNull { it.id == modelId }?.reasoningLevels
    }

    fun clearForProvider(providerId: String) {
        val prefix = "$providerId|"
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}
