package dev.xueshi.qduodroid.engine

import org.json.JSONArray
import org.json.JSONObject

object ModelListParser {

    private val NON_CHAT_MARKERS = listOf(
        "embedding",
        "embed-",
        "whisper",
        "tts",
        "dall-e",
        "gpt-image",
        "moderation",
        "transcribe",
        "-realtime",
        "davinci-002",
        "babbage-002",
        "sora",
        "rerank",
    )

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>")

    fun parse(rawJson: String, format: ApiFormat): List<ModelInfo> {
        val json = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: throw IllegalStateException("模型列表响应不是有效的 JSON")

        val parsed: List<ModelInfo> = when (format) {
            ApiFormat.OPENAI -> {
                val items = json.optJSONArray("data") ?: json.optJSONArray("models")
                    ?: throw IllegalStateException("模型列表缺少 \"data\" 字段")
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id").ifBlank { item.optString("name") }.trim()
                        if (id.isEmpty() || !isChatModel(id)) continue
                        val displayName = item.optString("display_name").takeIf { it.isNotBlank() }
                        val levels = extractReasoningLevels(item)
                        add(
                            ModelInfo.create(
                                id = id,
                                displayName = displayName,
                                reasoningLevels = levels,
                            ),
                        )
                    }
                }
            }

            ApiFormat.ANTHROPIC -> {
                val items = json.optJSONArray("data")
                    ?: throw IllegalStateException("模型列表缺少 \"data\" 字段")
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isEmpty()) continue
                        val displayName = item.optString("display_name").takeIf { it.isNotBlank() }
                        add(ModelInfo.create(id = id, displayName = displayName))
                    }
                }
            }

            ApiFormat.GEMINI -> {
                val items = json.optJSONArray("models")
                    ?: throw IllegalStateException("模型列表缺少 \"models\" 字段")
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val name = item.optString("name").trim()
                        if (name.isEmpty()) continue
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        if (methods != null && !containsString(methods, "generateContent")) {
                            continue
                        }
                        val id = LlmEndpoint.geminiModelId(name)
                        val displayName = item.optString("displayName").takeIf { it.isNotBlank() }
                        add(ModelInfo.create(id = id, displayName = displayName))
                    }
                }
            }
        }

        val seen = HashSet<String>()
        return parsed.filter { seen.add(it.id) }
    }

    fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_CHAT_MARKERS.none { lower.contains(it) }
    }

    fun extractErrorMessage(raw: String): String? {
        return runCatching {
            val obj = JSONObject(raw)
            val errObj = obj.optJSONObject("error")
            if (errObj != null) {
                errObj.optString("message").takeIf { it.isNotBlank() }
            } else {
                obj.optString("error").takeIf { it.isNotBlank() && it != "null" }
            } ?: obj.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun stripThinkTags(text: String): String {
        if (!text.contains("<think>")) return text
        return THINK_BLOCK_REGEX.replace(text, "")
    }

    fun visibleSoFar(raw: String): String {
        val stripped = stripThinkTags(raw)
        val openIdx = stripped.indexOf("<think>")
        return if (openIdx >= 0) stripped.substring(0, openIdx) else stripped
    }

    fun dropTrailingPartial(marker: String, text: String): String {
        var prefix = marker.dropLast(1)
        while (prefix.isNotEmpty()) {
            if (text.endsWith(prefix)) {
                return text.dropLast(prefix.length)
            }
            prefix = prefix.dropLast(1)
        }
        return text
    }

    private fun extractReasoningLevels(item: JSONObject): List<String>? {
        item.optJSONArray("reasoning_levels")?.let { arr ->
            return buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }
        item.optJSONArray("supported_reasoning_levels")?.let { arr ->
            val levels = buildList {
                for (i in 0 until arr.length()) {
                    val effort = arr.optJSONObject(i)?.optString("effort")?.trim().orEmpty()
                    if (effort.isNotEmpty()) add(effort)
                }
            }
            if (levels.isNotEmpty() || arr.length() == 0) return levels
        }
        if (item.has("reasoning") && !item.optBoolean("reasoning", true)) {
            return emptyList()
        }
        return null
    }

    private fun containsString(array: JSONArray, target: String): Boolean {
        for (i in 0 until array.length()) {
            if (array.optString(i) == target) return true
        }
        return false
    }
}
