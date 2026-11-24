package dev.xueshi.qduodroid.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BilibiliTranslator {

    private const val CHAT_URL = "https://index-translate.bilibili.com/v1/chat/completions"
    private const val MODEL = "Index-Translate-35B-A3B"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 25_000

    data class TranslationResult(
        val translatedText: String,
        val directionLabel: String,
        val elapsedMs: Long,
    )

    fun detectTarget(text: String): Pair<String, String> {
        var han = 0
        var letters = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF) {
                han++
                letters++
            } else if (ch.isLetter()) {
                letters++
            }
        }
        return if (han > 0 && han * 3 >= letters) {
            "en" to "英语"
        } else {
            "zh" to "简体中文"
        }
    }

    fun buildPrompt(text: String, targetName: String): String {
        val sourceJson = JSONObject().put("text", text).toString()
        return """
        请将以下文本翻译成$targetName，并且严格遵循所有约束要求。

        【源文】
        $sourceJson

        【约束要求】
        1. 【硬性要求】保留源文中的 JSON 格式标记不变

        请以相同的 JSON 格式输出翻译结果，key 保持不变，value 为对应译文。只输出 JSON，不要有任何额外说明。
        """.trimIndent()
    }

    fun buildRequestJson(prompt: String): String {
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", prompt),
        )
        return JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", 4096)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .toString()
    }

    fun extractTranslation(reply: String): String? {
        var s = reply.trim()
        if (s.startsWith("<think>")) {
            val closeIdx = s.lastIndexOf("</think>")
            if (closeIdx >= 0) {
                s = s.substring(closeIdx + "</think>".length).trim()
            }
        }
        if (s.startsWith("```")) {
            val nlIdx = s.indexOf('\n')
            if (nlIdx >= 0) {
                s = s.substring(nlIdx + 1).trim()
            }
            if (s.endsWith("```")) {
                s = s.dropLast(3).trim()
            }
        }
        runCatching {
            val obj = JSONObject(s)
            if (obj.has("text")) {
                return obj.optString("text").trim().takeIf { it.isNotEmpty() }
            }
        }
        return s.takeIf { it.isNotEmpty() }
    }

    suspend fun translate(text: String): TranslationResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val (targetCode, targetName) = detectTarget(text)
        val directionLabel = if (targetCode == "en") "中 → 英" else "外 → 中"
        val prompt = buildPrompt(text, targetName)
        val bodyJson = buildRequestJson(prompt)

        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                error("HTTP $status: ${rawResponse.take(180)}")
            }

            val root = JSONObject(rawResponse)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            val translated = extractTranslation(content)
                ?: error("译文解析失败：${content.take(120)}")

            TranslationResult(
                translatedText = translated,
                directionLabel = directionLabel,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } finally {
            conn.disconnect()
        }
    }
}
