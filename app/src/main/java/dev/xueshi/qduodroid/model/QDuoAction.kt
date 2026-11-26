package dev.xueshi.qduodroid.model

import java.net.URLEncoder

/**
 * 动作的类型。决定点了悬浮条上的按钮之后做什么。
 *
 * [id] 会写进保存动作列表的 JSON 里，不要改已有的值。
 */
enum class ActionKind(
    val id: String,
    val label: String,
    /** 这类动作的结果显示在底部结果卡片里（卡片顶部的切换按钮也只列这类动作）。 */
    val showsResult: Boolean,
    val hint: String,
) {
    AI(
        "ai",
        "AI 动作",
        true,
        "把下面的提示词和选中的文字一起发给「设置」里配置的 AI 模型，结果显示在底部卡片里。",
    ),
    TRANSLATE(
        "translate",
        "免费翻译",
        true,
        "用哔哩哔哩的免费翻译，不需要 API Key：中文译成英文，其他语言译成中文。",
    ),
    OPEN_URL(
        "open_url",
        "打开网址",
        false,
        "用浏览器（或能打开这个网址的 App）打开下面的网址。网址里的 {text} 会被换成选中的文字。",
    ),
    SPEAK(
        "speak",
        "朗读",
        false,
        "用系统语音把选中的文字读出来。",
    ),
    ;

    companion object {
        fun fromId(id: String?): ActionKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个用户可以编辑的动作：悬浮条上的一个按钮。
 *
 * 动作列表由 [dev.xueshi.qduodroid.util.ActionStore] 保存，可以添加、删除、编辑、排序。
 * [enabled] 为 false 的动作不出现在悬浮条和结果卡片上，但留在列表里。
 */
data class QDuoAction(
    val id: String,
    val title: String,
    val kind: ActionKind,
    /** [ActionKind.AI] 用的系统提示词。 */
    val prompt: String = "",
    /** [ActionKind.OPEN_URL] 用的网址，`{text}` 代表选中的文字。 */
    val url: String = "",
    val enabled: Boolean = true,
) {
    val showsResult: Boolean get() = kind.showsResult

    /** 动作列表里，标题下面那一行灰字。 */
    fun detailLine(): String = when (kind) {
        ActionKind.AI -> prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { "（还没有写提示词）" }
        ActionKind.TRANSLATE -> "哔哩哔哩免费翻译，不需要 API Key"
        ActionKind.OPEN_URL -> url.ifBlank { "（还没有填网址）" }
        ActionKind.SPEAK -> "系统语音朗读"
    }

    companion object {
        const val TEXT_PLACEHOLDER = "{text}"
        private const val MAX_URL_TEXT_CHARS = 200

        /** 把网址模板里的 `{text}` 换成编码后的选中文字（最多取前 200 个字）。 */
        fun fillUrl(template: String, text: String): String {
            val encoded = URLEncoder.encode(text.trim().take(MAX_URL_TEXT_CHARS), "UTF-8")
                .replace("+", "%20")
            return template.trim().replace(TEXT_PLACEHOLDER, encoded)
        }
    }
}
