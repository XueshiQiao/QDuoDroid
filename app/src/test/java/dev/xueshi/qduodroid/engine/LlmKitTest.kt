package dev.xueshi.qduodroid.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmKitTest {

    @Test
    fun `endpoint derives openai models and chat urls cleanly`() {
        val bare = LlmEndpoint.parse(ApiFormat.OPENAI, "https://api.deepseek.com")
        assertNotNull(bare)
        assertEquals("https://api.deepseek.com", bare!!.base)
        assertEquals("https://api.deepseek.com/models", bare.modelsUrl())
        assertEquals("https://api.deepseek.com/chat/completions", bare.chatUrl("deepseek-chat", stream = true))

        val full = LlmEndpoint.parse(ApiFormat.OPENAI, "https://api.openai.com/v1/chat/completions/")
        assertNotNull(full)
        assertEquals("https://api.openai.com/v1", full!!.base)
        assertEquals("https://api.openai.com/v1/models", full.modelsUrl())
        assertEquals("https://api.openai.com/v1/chat/completions", full.chatUrl("gpt-4o-mini", stream = false))

        val ollama = LlmEndpoint.parse(ApiFormat.OPENAI, "localhost:11434/api/chat")
        assertNotNull(ollama)
        assertEquals("http://localhost:11434/v1", ollama!!.base)
        assertEquals("http://localhost:11434/v1/models", ollama.modelsUrl())
    }

    @Test
    fun `endpoint derives anthropic and gemini urls and headers`() {
        val anthropic = LlmEndpoint.parse(ApiFormat.ANTHROPIC, "https://api.anthropic.com/v1/messages")
        assertNotNull(anthropic)
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", anthropic!!.modelsUrl())
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.chatUrl("claude-haiku-4-5", stream = true))
        val aHeaders = anthropic.authHeaders("sk-ant-123")
        assertEquals("2023-06-01", aHeaders["anthropic-version"])
        assertEquals("sk-ant-123", aHeaders["x-api-key"])

        val geminiBeta = LlmEndpoint.parse(ApiFormat.GEMINI, "https://generativelanguage.googleapis.com")
        assertNotNull(geminiBeta)
        assertEquals("v1beta", geminiBeta!!.geminiVersion)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            geminiBeta.modelsUrl(),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            geminiBeta.chatUrl("models/gemini-2.5-flash", stream = true),
        )

        val geminiV1 = LlmEndpoint.parse(ApiFormat.GEMINI, "https://generativelanguage.googleapis.com/v1")
        assertNotNull(geminiV1)
        assertEquals("v1", geminiV1!!.geminiVersion)
        assertEquals("https://generativelanguage.googleapis.com/v1/models?pageSize=1000", geminiV1.modelsUrl())
    }

    @Test
    fun `model list parser handles openai, anthropic, gemini and deduplicates`() {
        val openaiJson = """
            {
              "object": "list",
              "data": [
                {"id": "deepseek-chat"},
                {"id": "deepseek-reasoner"},
                {"id": "text-embedding-3-small"},
                {"id": "whisper-1"},
                {"id": "deepseek-chat"}
              ]
            }
        """.trimIndent()
        val openaiModels = ModelListParser.parse(openaiJson, ApiFormat.OPENAI)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), openaiModels.map { it.id })

        val anthropicJson = """
            {
              "data": [
                {"id": "claude-sonnet-4-5", "display_name": "Claude Sonnet 4.5"}
              ]
            }
        """.trimIndent()
        val anthropicModels = ModelListParser.parse(anthropicJson, ApiFormat.ANTHROPIC)
        assertEquals(1, anthropicModels.size)
        assertEquals("claude-sonnet-4-5", anthropicModels[0].id)
        assertEquals("Claude Sonnet 4.5", anthropicModels[0].displayName)

        val geminiJson = """
            {
              "models": [
                {"name": "models/embedding-001", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash", "supportedGenerationMethods": ["generateContent"]}
              ]
            }
        """.trimIndent()
        val geminiModels = ModelListParser.parse(geminiJson, ApiFormat.GEMINI)
        assertEquals(1, geminiModels.size)
        assertEquals("gemini-2.5-flash", geminiModels[0].id)
        assertEquals("Gemini 2.5 Flash", geminiModels[0].displayName)
    }

    @Test
    fun `stream helper hides complete and partial think tags`() {
        assertEquals("最终答案", ModelListParser.visibleSoFar("<think>思考过程</think>最终答案"))
        assertEquals("前缀", ModelListParser.visibleSoFar("前缀<think>未闭合"))
        assertEquals(
            "你好",
            ModelListParser.dropTrailingPartial("<think>", "你好<thi"),
        )
        assertTrue(ModelListParser.isChatModel("deepseek-chat"))
    }

    @Test
    fun `initial thinking defaults off where supported and low for gemini`() {
        assertEquals("none", LlmProviders.DEEPSEEK.initialEffort())
        assertEquals("none", LlmProviders.OPENAI.initialEffort())
        assertEquals("none", LlmProviders.ANTHROPIC.initialEffort())
        assertEquals("none", LlmProviders.ALIBABA.initialEffort())
        assertEquals("none", LlmProviders.DOUBAO.initialEffort())
        assertEquals("low", LlmProviders.GEMINI.initialEffort())
    }

    @Test
    fun `chat payload injects provider-specific thinking disable or effort fields`() {
        val deepseekOff = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.DEEPSEEK,
                format = ApiFormat.OPENAI,
                model = "deepseek-chat",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "none",
            ),
        )
        assertEquals("disabled", deepseekOff.getJSONObject("thinking").getString("type"))
        assertFalse(deepseekOff.has("reasoning_effort"))

        val deepseekHigh = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.DEEPSEEK,
                format = ApiFormat.OPENAI,
                model = "deepseek-reasoner",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "high",
            ),
        )
        assertEquals("high", deepseekHigh.getString("reasoning_effort"))
        assertFalse(deepseekHigh.has("thinking"))

        val qwenOff = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.ALIBABA,
                format = ApiFormat.OPENAI,
                model = "qwen-plus",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "none",
            ),
        )
        assertFalse(qwenOff.getBoolean("enable_thinking"))

        val openaiOff = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.OPENAI,
                format = ApiFormat.OPENAI,
                model = "gpt-4o-mini",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "none",
            ),
        )
        assertFalse(openaiOff.has("reasoning_effort"))

        val openaiLow = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.OPENAI,
                format = ApiFormat.OPENAI,
                model = "gpt-4o-mini",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "low",
            ),
        )
        assertEquals("low", openaiLow.getString("reasoning_effort"))

        val anthropicOff = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.ANTHROPIC,
                format = ApiFormat.ANTHROPIC,
                model = "claude-haiku-4-5",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "none",
            ),
        )
        assertEquals("disabled", anthropicOff.getJSONObject("thinking").getString("type"))

        val geminiLow = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.GEMINI,
                format = ApiFormat.GEMINI,
                model = "gemini-2.5-flash",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "low",
            ),
        )
        assertEquals(
            "low",
            geminiLow.getJSONObject("generationConfig")
                .getJSONObject("thinkingConfig")
                .getString("thinkingLevel"),
        )

        val gatewayOff = JSONObject(
            LlmClient.buildChatPayload(
                provider = LlmProviders.CUSTOM,
                format = ApiFormat.OPENAI,
                model = "deepseek/deepseek-chat",
                systemPrompt = "sys",
                userText = "hi",
                stream = true,
                temperature = 0.3,
                reasoningEffort = "none",
                reasoningLevels = listOf("none", "low", "max"),
            ),
        )
        assertEquals("none", gatewayOff.getString("reasoning_effort"))
    }

    @Test
    fun `model list parser extracts gateway reasoning levels`() {
        val json = """
            {
              "data": [
                {"id": "m1", "reasoning_levels": ["none", "low", "max"]},
                {"id": "m2", "supported_reasoning_levels": [{"effort": "low"}, {"effort": "high"}]}
              ]
            }
        """.trimIndent()
        val parsed = ModelListParser.parse(json, ApiFormat.OPENAI)
        assertEquals(listOf("none", "low", "max"), parsed[0].reasoningLevels)
        assertEquals(listOf("low", "high"), parsed[1].reasoningLevels)
    }
}
