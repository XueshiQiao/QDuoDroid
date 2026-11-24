package dev.xueshi.qduodroid.engine

enum class ApiFormat(val id: String, val label: String) {
    OPENAI("openai", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Anthropic"),
    GEMINI("gemini", "Gemini"),
    ;

    companion object {
        fun fromId(id: String?): ApiFormat =
            entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

enum class ThinkingDialect {
    SWITCH_AND_EFFORT,
    QWEN_FLAG,
    NO_THINK_PREFIX,
    EFFORT,
    ANTHROPIC,
    GEMINI,
    GATEWAY,
}

data class ThinkingOption(
    val tag: String,
    val label: String,
)

data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val reasoningLevels: List<String>? = null,
) {
    companion object {
        fun create(
            id: String,
            displayName: String? = null,
            reasoningLevels: List<String>? = null,
        ): ModelInfo {
            val resolvedName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: id
            return ModelInfo(
                id = id,
                displayName = resolvedName,
                reasoningLevels = reasoningLevels,
            )
        }
    }
}

data class LlmProvider(
    val id: String,
    val displayName: String,
    val format: ApiFormat,
    val defaultUrl: String,
    val needsKey: Boolean,
    val dialect: ThinkingDialect? = null,
    val initialEffortValue: String = "none",
) {
    fun effectiveDialect(
        format: ApiFormat = this.format,
        levels: List<String>? = null,
    ): ThinkingDialect {
        if (id == LlmProviders.CUSTOM.id && levels != null) {
            return ThinkingDialect.GATEWAY
        }
        return dialect ?: when (format) {
            ApiFormat.OPENAI -> ThinkingDialect.EFFORT
            ApiFormat.ANTHROPIC -> ThinkingDialect.ANTHROPIC
            ApiFormat.GEMINI -> ThinkingDialect.GEMINI
        }
    }

    fun thinkingOptions(
        format: ApiFormat = this.format,
        levels: List<String>? = null,
    ): List<ThinkingOption> {
        return when (effectiveDialect(format, levels)) {
            ThinkingDialect.QWEN_FLAG -> listOf(
                ThinkingOption("none", "关闭（推荐）"),
                ThinkingOption("", "开启"),
            )

            ThinkingDialect.SWITCH_AND_EFFORT -> if (id == LlmProviders.DOUBAO.id) {
                listOf(
                    ThinkingOption("none", "关闭（推荐）"),
                    ThinkingOption("minimal", "极低"),
                    ThinkingOption("low", "低"),
                    ThinkingOption("medium", "中"),
                    ThinkingOption("high", "高"),
                )
            } else {
                listOf(
                    ThinkingOption("none", "关闭（推荐）"),
                    ThinkingOption("high", "高"),
                    ThinkingOption("max", "最高"),
                )
            }

            ThinkingDialect.NO_THINK_PREFIX -> listOf(
                ThinkingOption("none", "关闭（推荐）"),
                ThinkingOption("", "模型默认"),
                ThinkingOption("low", "低"),
                ThinkingOption("medium", "中"),
                ThinkingOption("high", "高"),
            )

            ThinkingDialect.EFFORT -> if (id == LlmProviders.OPENAI.id) {
                listOf(
                    ThinkingOption("none", "关闭（默认）"),
                    ThinkingOption("", "模型默认"),
                    ThinkingOption("low", "低"),
                    ThinkingOption("medium", "中"),
                    ThinkingOption("high", "高"),
                )
            } else {
                listOf(
                    ThinkingOption("", "模型默认"),
                    ThinkingOption("low", "低"),
                    ThinkingOption("medium", "中"),
                    ThinkingOption("high", "高"),
                )
            }

            ThinkingDialect.ANTHROPIC -> listOf(
                ThinkingOption("none", "关闭（推荐）"),
                ThinkingOption("", "模型默认"),
            )

            ThinkingDialect.GEMINI -> listOf(
                ThinkingOption("low", "低（最快）"),
                ThinkingOption("medium", "中"),
                ThinkingOption("high", "高"),
                ThinkingOption("", "模型默认"),
            )

            ThinkingDialect.GATEWAY -> {
                if (levels == null) {
                    listOf(
                        ThinkingOption("none", "关闭"),
                        ThinkingOption("", "模型默认"),
                        ThinkingOption("low", "低"),
                        ThinkingOption("medium", "中"),
                        ThinkingOption("high", "高"),
                    )
                } else {
                    buildList {
                        if ("none" in levels) {
                            add(ThinkingOption("none", "关闭"))
                        }
                        add(ThinkingOption("", "模型默认"))
                        levels
                            .filter { it.isNotEmpty() && it != "none" }
                            .forEach { add(ThinkingOption(it, levelLabel(it))) }
                    }
                }
            }
        }
    }

    fun clampThinking(
        effort: String,
        format: ApiFormat = this.format,
        levels: List<String>? = null,
    ): String {
        if (levels == null && effectiveDialect(format, null) == ThinkingDialect.GATEWAY) {
            return effort
        }
        val options = thinkingOptions(format, levels)
        val tags = options.map { it.tag }
        if (effort in tags) return effort
        if ("none" in tags) return "none"
        if (initialEffortValue in tags) return initialEffortValue
        return options.first().tag
    }

    fun initialEffort(
        format: ApiFormat = this.format,
        levels: List<String>? = null,
    ): String = clampThinking(initialEffortValue, format, levels)

    fun labelForEffort(
        effort: String,
        format: ApiFormat = this.format,
        levels: List<String>? = null,
    ): String {
        val clamped = clampThinking(effort, format, levels)
        val options = thinkingOptions(format, levels)
        return options.firstOrNull { it.tag == clamped }?.label ?: clamped
    }

    companion object {
        internal fun levelLabel(level: String): String = when (level) {
            "none" -> "关闭"
            "minimal" -> "极低"
            "low" -> "低"
            "medium" -> "中"
            "high" -> "高"
            "max" -> "最高"
            "xhigh" -> "极高"
            else -> level.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

object LlmProviders {
    val DEEPSEEK = LlmProvider(
        id = "deepseek",
        displayName = "DeepSeek",
        format = ApiFormat.OPENAI,
        defaultUrl = "https://api.deepseek.com",
        needsKey = true,
        dialect = ThinkingDialect.SWITCH_AND_EFFORT,
        initialEffortValue = "none",
    )
    val OPENAI = LlmProvider(
        id = "openai",
        displayName = "OpenAI",
        format = ApiFormat.OPENAI,
        defaultUrl = "https://api.openai.com/v1",
        needsKey = true,
        dialect = ThinkingDialect.EFFORT,
        initialEffortValue = "none",
    )
    val ANTHROPIC = LlmProvider(
        id = "anthropic",
        displayName = "Anthropic",
        format = ApiFormat.ANTHROPIC,
        defaultUrl = "https://api.anthropic.com",
        needsKey = true,
        dialect = ThinkingDialect.ANTHROPIC,
        initialEffortValue = "none",
    )
    val GEMINI = LlmProvider(
        id = "gemini",
        displayName = "Gemini",
        format = ApiFormat.GEMINI,
        defaultUrl = "https://generativelanguage.googleapis.com",
        needsKey = true,
        dialect = ThinkingDialect.GEMINI,
        initialEffortValue = "low",
    )
    val ALIBABA = LlmProvider(
        id = "alibaba",
        displayName = "Qwen (DashScope)",
        format = ApiFormat.OPENAI,
        defaultUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        needsKey = true,
        dialect = ThinkingDialect.QWEN_FLAG,
        initialEffortValue = "none",
    )
    val DOUBAO = LlmProvider(
        id = "doubao",
        displayName = "Doubao (Ark)",
        format = ApiFormat.OPENAI,
        defaultUrl = "https://ark.cn-beijing.volces.com/api/v3",
        needsKey = true,
        dialect = ThinkingDialect.SWITCH_AND_EFFORT,
        initialEffortValue = "none",
    )
    val CUSTOM = LlmProvider(
        id = "custom",
        displayName = "自定义 / 网关",
        format = ApiFormat.OPENAI,
        defaultUrl = "",
        needsKey = false,
        dialect = null,
        initialEffortValue = "none",
    )

    val ALL: List<LlmProvider> = listOf(
        DEEPSEEK,
        OPENAI,
        ANTHROPIC,
        GEMINI,
        ALIBABA,
        DOUBAO,
        CUSTOM,
    )

    fun byId(id: String?): LlmProvider = ALL.firstOrNull { it.id == id } ?: DEEPSEEK
}
