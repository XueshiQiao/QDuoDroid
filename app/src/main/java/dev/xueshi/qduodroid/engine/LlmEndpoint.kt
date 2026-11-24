package dev.xueshi.qduodroid.engine

import java.net.URI
import java.net.URLEncoder

data class LlmEndpoint(
    val format: ApiFormat,
    val base: String,
    val geminiVersion: String = "v1beta",
) {
    fun chatUrl(model: String, stream: Boolean): String {
        return when (format) {
            ApiFormat.OPENAI -> "$base/chat/completions"
            ApiFormat.ANTHROPIC -> "$base/v1/messages"
            ApiFormat.GEMINI -> {
                val id = geminiModelId(model.trim())
                val escaped = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
                val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
                "$base/$geminiVersion/models/$escaped:$method"
            }
        }
    }

    fun modelsUrl(): String {
        return when (format) {
            ApiFormat.OPENAI -> "$base/models"
            ApiFormat.ANTHROPIC -> "$base/v1/models?limit=1000"
            ApiFormat.GEMINI -> "$base/$geminiVersion/models?pageSize=1000"
        }
    }

    fun authHeaders(apiKey: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        if (format == ApiFormat.ANTHROPIC) {
            headers["anthropic-version"] = "2023-06-01"
        }
        val key = apiKey.trim()
        if (key.isEmpty()) return headers
        when (format) {
            ApiFormat.OPENAI -> headers["Authorization"] = "Bearer $key"
            ApiFormat.ANTHROPIC -> headers["x-api-key"] = key
            ApiFormat.GEMINI -> headers["x-goog-api-key"] = key
        }
        return headers
    }

    companion object {
        private val GEMINI_MODELS_TAIL = Regex("/models(/(?!v1(?:beta|alpha)?$)[^/]+)?$")

        fun geminiModelId(model: String): String =
            if (model.startsWith("models/")) model.removePrefix("models/") else model

        fun parse(format: ApiFormat, rawUrl: String): LlmEndpoint? {
            val (origin, rawPath) = split(rawUrl) ?: return null
            var path = rawPath
            var version = "v1beta"
            when (format) {
                ApiFormat.OPENAI -> {
                    path = stripSuffix(
                        path,
                        listOf("/chat/completions", "/completions", "/models", "/responses"),
                    )
                    if (path in setOf("/api", "/api/chat", "/api/generate", "/api/tags")) {
                        path = "/v1"
                    }
                }

                ApiFormat.ANTHROPIC -> {
                    path = stripSuffix(
                        path,
                        listOf("/v1/messages", "/messages", "/v1/models", "/models"),
                    )
                    path = stripSuffix(path, listOf("/v1"))
                }

                ApiFormat.GEMINI -> {
                    val match = GEMINI_MODELS_TAIL.find(path)
                    if (match != null) {
                        path = path.substring(0, match.range.first)
                    }
                    if (path.endsWith("/v1")) {
                        version = "v1"
                    }
                    path = stripSuffix(path, listOf("/v1beta", "/v1"))
                }
            }
            return LlmEndpoint(
                format = format,
                base = origin + path,
                geminiVersion = version,
            )
        }

        internal fun split(raw: String): Pair<String, String>? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (!text.contains("://")) {
                val probedHost = runCatching { URI("http://$text").host }.getOrNull()
                val scheme = if (isLocal(probedHost)) "http://" else "https://"
                text = scheme + text
            }
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            val origin = buildString {
                append(scheme).append("://").append(hostPart)
                if (uri.port > 0) append(":").append(uri.port)
            }
            var path = uri.rawPath.orEmpty()
            while (path.endsWith("/")) {
                path = path.dropLast(1)
            }
            return origin to path
        }

        internal fun isLocal(host: String?): Boolean {
            val clean = host?.lowercase() ?: return false
            if (clean in setOf("localhost", "0.0.0.0", "::1", "[::1]")) return true
            val parts = clean.split('.')
            if (parts.size != 4) return false
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            return nums[0] == 127 ||
                nums[0] == 10 ||
                (nums[0] == 192 && nums[1] == 168) ||
                (nums[0] == 172 && nums[1] in 16..31)
        }

        private fun stripSuffix(path: String, suffixes: List<String>): String {
            for (suffix in suffixes) {
                if (path.endsWith(suffix)) {
                    return path.dropLast(suffix.length)
                }
            }
            return path
        }
    }
}
