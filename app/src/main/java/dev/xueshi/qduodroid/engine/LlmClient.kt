package dev.xueshi.qduodroid.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ConnectionTestSummary(
    val elapsedMs: Long,
    val listedModels: List<ModelInfo>?,
    val modelListed: Boolean?,
    val listWarning: String?,
)

object LlmClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val LIST_READ_TIMEOUT_MS = 15_000
    private const val STREAM_READ_TIMEOUT_MS = 45_000
    private const val PING_READ_TIMEOUT_MS = 20_000
    private const val ANTHROPIC_MAX_TOKENS = 8192

    suspend fun listModels(
        format: ApiFormat,
        baseUrl: String,
        apiKey: String,
    ): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = LlmEndpoint.parse(format, baseUrl)
                ?: error("无效的 API 地址：$baseUrl")
            val url = endpoint.modelsUrl()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = LIST_READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                for ((field, value) in endpoint.authHeaders(apiKey)) {
                    setRequestProperty(field, value)
                }
            }
            try {
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val err = ModelListParser.extractErrorMessage(raw) ?: raw.take(180)
                    error("HTTP $status: $err")
                }
                ModelListParser.parse(raw, format)
            } finally {
                conn.disconnect()
            }
        }
    }

    fun streamChat(
        provider: LlmProvider,
        format: ApiFormat,
        baseUrl: String,
        apiKey: String,
        model: String,
        reasoningEffort: String,
        reasoningLevels: List<String>? = null,
        systemPrompt: String,
        userText: String,
    ): Flow<String> = flow {
        val endpoint = LlmEndpoint.parse(format, baseUrl)
            ?: error("API 地址格式不正确")
        val cleanModel = model.trim()
        require(cleanModel.isNotEmpty()) { "模型名称不能为空" }

        val url = endpoint.chatUrl(cleanModel, stream = true)
        val payload = buildChatPayload(
            provider = provider,
            format = format,
            model = cleanModel,
            systemPrompt = systemPrompt,
            userText = userText,
            stream = true,
            temperature = 0.3,
            reasoningEffort = reasoningEffort,
            reasoningLevels = reasoningLevels,
        )

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = STREAM_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            for ((field, value) in endpoint.authHeaders(apiKey)) {
                setRequestProperty(field, value)
            }
        }

        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val msg = ModelListParser.extractErrorMessage(errBody) ?: errBody.take(180)
                error("HTTP $status: $msg")
            }

            val rawAcc = StringBuilder()
            var lastShown = ""
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    val event = parseStreamEvent(line, format)
                    when (event) {
                        is StreamChunk.Text -> {
                            rawAcc.append(event.chunk)
                            val shown = ModelListParser.dropTrailingPartial(
                                "<think>",
                                ModelListParser.visibleSoFar(rawAcc.toString()),
                            )
                            if (shown != lastShown) {
                                lastShown = shown
                                emit(shown)
                            }
                        }

                        StreamChunk.Done -> break
                        is StreamChunk.Error -> error(event.message)
                        StreamChunk.Other -> continue
                    }
                }
            }
            val finalVisible = ModelListParser.visibleSoFar(rawAcc.toString()).trim()
            if (finalVisible.isEmpty()) {
                error("模型返回为空")
            }
            if (finalVisible != lastShown) {
                emit(finalVisible)
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testConnection(
        provider: LlmProvider,
        format: ApiFormat,
        baseUrl: String,
        apiKey: String,
        model: String,
        reasoningEffort: String,
        reasoningLevels: List<String>? = null,
    ): Result<ConnectionTestSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = LlmEndpoint.parse(format, baseUrl)
                ?: error("无效的 API 地址：$baseUrl")

            var listedModels: List<ModelInfo>? = null
            var listWarning: String? = null

            val listResult = listModels(format, baseUrl, apiKey)
            if (listResult.isSuccess) {
                listedModels = listResult.getOrNull()
            } else {
                val msg = listResult.exceptionOrNull()?.message.orEmpty()
                if (msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403")) {
                    error("鉴权失败：$msg")
                }
                listWarning = msg
            }

            val effectiveModel = model.trim().ifBlank { listedModels?.firstOrNull()?.id.orEmpty() }
            require(effectiveModel.isNotEmpty()) {
                listWarning?.let { "未选定模型，且无法自动拉取列表：$it" } ?: "请先选择或填写模型名称"
            }

            val resolvedLevels = listedModels?.firstOrNull { it.id == effectiveModel }?.reasoningLevels
                ?: reasoningLevels

            val started = System.currentTimeMillis()
            val url = endpoint.chatUrl(effectiveModel, stream = false)
            val payload = buildChatPayload(
                provider = provider,
                format = format,
                model = effectiveModel,
                systemPrompt = "Reply with the single word OK.",
                userText = "ping",
                stream = false,
                temperature = null,
                reasoningEffort = reasoningEffort,
                reasoningLevels = resolvedLevels,
            )

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = PING_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                for ((field, value) in endpoint.authHeaders(apiKey)) {
                    setRequestProperty(field, value)
                }
            }

            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val err = ModelListParser.extractErrorMessage(raw) ?: raw.take(180)
                    error("HTTP $status: $err")
                }
                val elapsed = System.currentTimeMillis() - started
                val modelListed = listedModels?.any { it.id == effectiveModel }
                ConnectionTestSummary(
                    elapsedMs = elapsed,
                    listedModels = listedModels,
                    modelListed = modelListed,
                    listWarning = listWarning,
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    internal fun buildChatPayload(
        provider: LlmProvider,
        format: ApiFormat,
        model: String,
        systemPrompt: String,
        userText: String,
        stream: Boolean,
        temperature: Double?,
        reasoningEffort: String,
        reasoningLevels: List<String>? = null,
    ): String {
        val dialect = provider.effectiveDialect(format, reasoningLevels)
        val effort = provider.clampThinking(reasoningEffort, format, reasoningLevels)
        val root = JSONObject()

        when (format) {
            ApiFormat.OPENAI -> {
                val effectiveSystem = if (dialect == ThinkingDialect.NO_THINK_PREFIX && effort == "none") {
                    if (systemPrompt.isBlank()) "/no_think" else "/no_think\n$systemPrompt"
                } else {
                    systemPrompt
                }
                root.put("model", model)
                root.put("stream", stream)
                if (temperature != null) root.put("temperature", temperature)
                if (!stream) root.put("max_tokens", 16)
                root.put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", effectiveSystem))
                        .put(JSONObject().put("role", "user").put("content", userText)),
                )

                when (dialect) {
                    ThinkingDialect.QWEN_FLAG -> {
                        if (!stream || effort == "none") {
                            root.put("enable_thinking", false)
                        }
                    }

                    ThinkingDialect.SWITCH_AND_EFFORT -> {
                        when (effort) {
                            "" -> Unit
                            "none" -> root.put("thinking", JSONObject().put("type", "disabled"))
                            else -> root.put("reasoning_effort", effort)
                        }
                    }

                    ThinkingDialect.EFFORT, ThinkingDialect.NO_THINK_PREFIX -> {
                        if (effort.isNotEmpty() && effort != "none") {
                            root.put("reasoning_effort", effort)
                        }
                    }

                    ThinkingDialect.GATEWAY -> {
                        if (effort.isNotEmpty()) {
                            root.put("reasoning_effort", effort)
                        }
                    }

                    ThinkingDialect.ANTHROPIC, ThinkingDialect.GEMINI -> Unit
                }
            }

            ApiFormat.ANTHROPIC -> {
                root.put("model", model)
                root.put("max_tokens", if (stream) ANTHROPIC_MAX_TOKENS else 32)
                root.put("stream", stream)
                if (systemPrompt.isNotBlank()) root.put("system", systemPrompt)
                root.put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", userText)),
                )
                if (effort == "none") {
                    root.put("thinking", JSONObject().put("type", "disabled"))
                }
            }

            ApiFormat.GEMINI -> {
                root.put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userText))),
                    ),
                )
                if (systemPrompt.isNotBlank()) {
                    root.put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", systemPrompt)),
                        ),
                    )
                }
                if (effort.isNotEmpty() && effort != "none") {
                    root.put(
                        "generationConfig",
                        JSONObject().put(
                            "thinkingConfig",
                            JSONObject().put("thinkingLevel", effort),
                        ),
                    )
                }
            }
        }
        return root.toString()
    }

    private sealed interface StreamChunk {
        data class Text(val chunk: String) : StreamChunk
        data object Done : StreamChunk
        data class Error(val message: String) : StreamChunk
        data object Other : StreamChunk
    }

    private fun parseStreamEvent(line: String, format: ApiFormat): StreamChunk {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return StreamChunk.Other
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamChunk.Done
        if (data.isEmpty()) return StreamChunk.Other

        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return StreamChunk.Other
        val errObj = obj.optJSONObject("error")
        if (errObj != null) {
            val msg = errObj.optString("message").ifBlank {
                obj.optString("message").ifBlank { "模型服务返回错误" }
            }
            return StreamChunk.Error(msg)
        }

        return when (format) {
            ApiFormat.OPENAI -> {
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return StreamChunk.Other
                val delta = choice.optJSONObject("delta")?.optString("content").orEmpty()
                if (delta.isEmpty()) StreamChunk.Other else StreamChunk.Text(delta)
            }

            ApiFormat.ANTHROPIC -> {
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta") ?: return StreamChunk.Other
                        if (delta.optString("type") != "text_delta") return StreamChunk.Other
                        val text = delta.optString("text")
                        if (text.isEmpty()) StreamChunk.Other else StreamChunk.Text(text)
                    }

                    "message_stop" -> StreamChunk.Done
                    "error" -> StreamChunk.Error(obj.optString("message").ifBlank { "Anthropic 返回错误" })
                    else -> StreamChunk.Other
                }
            }

            ApiFormat.GEMINI -> {
                val candidates = obj.optJSONArray("candidates") ?: return StreamChunk.Other
                val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return StreamChunk.Other
                val parts = content.optJSONArray("parts") ?: return StreamChunk.Other
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    sb.append(part.optString("text"))
                }
                val chunk = sb.toString()
                if (chunk.isEmpty()) StreamChunk.Other else StreamChunk.Text(chunk)
            }
        }
    }
}
