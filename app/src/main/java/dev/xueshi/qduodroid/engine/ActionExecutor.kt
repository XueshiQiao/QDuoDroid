package dev.xueshi.qduodroid.engine

import android.content.Context
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.util.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

sealed interface ActionEvent {
    val engineBadge: String

    data class Loading(
        override val engineBadge: String,
        val statusText: String,
    ) : ActionEvent

    data class Streaming(
        override val engineBadge: String,
        val text: String,
    ) : ActionEvent

    data class Success(
        override val engineBadge: String,
        val text: String,
    ) : ActionEvent

    data class NeedsConfig(
        override val engineBadge: String,
        val message: String,
    ) : ActionEvent

    data class Failure(
        override val engineBadge: String,
        val errorMessage: String,
    ) : ActionEvent
}

object ActionExecutor {

    fun execute(context: Context, action: QDuoAction, inputText: String): Flow<ActionEvent> {
        val text = inputText.trim()
        val prefs = AppPrefs(context)

        if (action == QDuoAction.TRANSLATE && !(prefs.useLlmForTranslate && prefs.hasLlmConfig())) {
            val (_, targetName) = BilibiliTranslator.detectTarget(text)
            val badge = "Bilibili 免费翻译 · 译成$targetName"
            return flow {
                emit(ActionEvent.Loading(badge, "正在调用 Bilibili Index-Translate 翻译…"))
                val res = BilibiliTranslator.translate(text)
                emit(
                    ActionEvent.Success(
                        engineBadge = "Bilibili 免费翻译 · ${res.directionLabel} · ${res.elapsedMs}ms",
                        text = res.translatedText,
                    ),
                )
            }.catch { err ->
                emit(
                    ActionEvent.Failure(
                        engineBadge = "Bilibili 免费翻译 · 失败",
                        errorMessage = "翻译请求失败：${err.message ?: err.javaClass.simpleName}",
                    ),
                )
            }
        }

        if (!prefs.hasLlmConfig()) {
            return flow {
                emit(
                    ActionEvent.NeedsConfig(
                        engineBadge = "未配置 AI 模型",
                        message = "「${action.title}」需要接入 AI 大模型（支持 DeepSeek / OpenAI / Anthropic / Gemini 等，填入 Key 后自动拉取模型列表）。\n\n" +
                            "请点击下方「去配置 AI 模型」完成配置；如果只是想先看界面交互，也可以点「演示效果」。",
                    ),
                )
            }
        }

        val provider = prefs.activeProvider
        val format = prefs.activeApiFormat
        val modelName = prefs.llmModel
        val baseUrl = prefs.llmBaseUrl
        val apiKey = prefs.llmApiKey
        val reasoningEffort = prefs.llmReasoningEffort
        val reasoningLevels = ModelListStore.reasoningLevelsFor(
            providerId = provider.id,
            format = format,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelName,
        )
        val badge = "$modelName · ${action.title}"
        val systemPrompt = QDuoPrompts.forAction(action)
        return flow {
            emit(ActionEvent.Loading(badge, "正在请求 $modelName…"))
            var latest = ""
            LlmClient.streamChat(
                provider = provider,
                format = format,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = modelName,
                reasoningEffort = reasoningEffort,
                reasoningLevels = reasoningLevels,
                systemPrompt = systemPrompt,
                userText = text,
            ).collect { accumulated ->
                latest = accumulated
                emit(ActionEvent.Streaming(badge, accumulated))
            }
            if (latest.isNotEmpty()) {
                emit(ActionEvent.Success(badge, latest))
            }
        }.catch { err ->
            emit(
                ActionEvent.Failure(
                    engineBadge = "$modelName · 失败",
                    errorMessage = "模型请求失败：${err.message ?: err.javaClass.simpleName}",
                ),
            )
        }
    }
}
