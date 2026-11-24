package dev.xueshi.qduodroid.engine

import dev.xueshi.qduodroid.model.QDuoAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object FakeActionEngine {

    fun generate(action: QDuoAction, rawInput: String): String {
        val text = rawInput.trim()
        if (text.isEmpty()) return "（未检测到有效文本）"

        val cjkCount = text.count { it in '\u4E00'..'\u9FFF' }
        val isMostlyChinese = cjkCount * 3 >= text.length
        val preview = if (text.length > 72) text.take(72) + "…" else text

        return when (action) {
            QDuoAction.TRANSLATE -> buildTranslateMock(text, preview, isMostlyChinese)
            QDuoAction.POLISH -> buildPolishMock(text, preview, isMostlyChinese)
            QDuoAction.SUMMARIZE -> buildSummarizeMock(text, preview, isMostlyChinese)
            QDuoAction.EXPLAIN -> buildExplainMock(text, preview, isMostlyChinese)
            QDuoAction.SEARCH -> "将使用浏览器搜索：$preview"
            QDuoAction.SPEAK -> "正在通过系统 TTS 朗读：$preview"
        }
    }

    fun stream(action: QDuoAction, rawInput: String, delayMs: Long = 22L): Flow<String> = flow {
        val full = generate(action, rawInput)
        val chunkSize = (full.length / 26).coerceIn(3, 14)
        var end = 0
        while (end < full.length) {
            end = (end + chunkSize).coerceAtMost(full.length)
            emit(full.substring(0, end))
            if (end < full.length && delayMs > 0) {
                delay(delayMs)
            }
        }
    }

    private fun buildTranslateMock(text: String, preview: String, isMostlyChinese: Boolean): String {
        return if (isMostlyChinese) {
            """
            [Demo Mock · 自动识别为中文 → English]

            Translated Preview:
            "Here is a natural English rendering of your selected Chinese snippet (${text.length} chars): $preview"

            关键术语对照：
            • 核心表达 → core expression / key point
            • 语义保留 → faithful to the original context

            注：当前为离线假数据演示，后续可直接替换为 AIModelKit / OpenAI / DeepSeek 接口。
            """.trimIndent()
        } else {
            """
            [Demo Mock · 自动识别为英文/外文 → 简体中文]

            参考译文：
            「这是针对所选内容（共 ${text.length} 个字符）的模拟中文译文：$preview —— 保持原意流畅自然，适合阅读与快速理解。」

            对照说明：
            • 语态处理：将原文长句拆分为更符合中文阅读习惯的短句
            • 词汇保留：专有名词与技术标识保持原样

            注：当前为离线假数据演示，后续可直接接入你的翻译或大模型后端。
            """.trimIndent()
        }
    }

    private fun buildPolishMock(text: String, preview: String, isMostlyChinese: Boolean): String {
        return if (isMostlyChinese) {
            """
            [Demo Mock · 文本润色]

            版本 A（专业精炼）：
            「$preview」—— 逻辑更紧凑，删去冗余修饰，适合工作沟通与文档沉淀。

            版本 B（温和自然）：
            「其实核心意思是：$preview。整体表达更口语、更容易让对方接受。」

            改动点：共 ${text.length} 字 · 优化句式衔接 · 提升信息密度。
            """.trimIndent()
        } else {
            """
            [Demo Mock · Writing Polish]

            Option A (Concise & Professional):
            "Refined version: $preview — clearer structure, stronger verbs, and zero filler words."

            Option B (Friendly & Natural):
            "Conversational version: $preview — smoother flow for everyday messages."

            Stats: ${text.length} chars · improved clarity and tone.
            """.trimIndent()
        }
    }

    private fun buildSummarizeMock(text: String, preview: String, isMostlyChinese: Boolean): String {
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val lineCount = text.lines().size
        return if (isMostlyChinese) {
            """
            [Demo Mock · 一句话总结与要点]

            一句话结论：
            这段内容围绕「$preview」展开，重点强调了核心结论与可执行方向。

            核心要点（3 条）：
            1. 背景与对象：涉及主题的上下文信息已提取。
            2. 关键信息：共包含 ${text.length} 字（$lineCount 行），聚焦主要诉求。
            3. 建议动作：可直接复制结果或切换「翻译 / 解释」进一步查看。
            """.trimIndent()
        } else {
            """
            [Demo Mock · Key Takeaways]

            TL;DR:
            The selected snippet highlights "$preview" with emphasis on its primary takeaway.

            Bullet Summary:
            1. Scope: $wordCount words across $lineCount line(s) (${text.length} chars).
            2. Main Signal: Core intent and key terms extracted cleanly.
            3. Next Step: Copy this summary or switch tabs to Translate / Explain.
            """.trimIndent()
        }
    }

    private fun buildExplainMock(text: String, preview: String, isMostlyChinese: Boolean): String {
        val langTag = if (isMostlyChinese) "中文语境" else "英文 / 技术语境"
        return """
        [Demo Mock · 深度解释（$langTag）]

        1. 字面含义
        原文「$preview」表达了一个相对独立的语义单元，长度为 ${text.length} 个字符。

        2. 隐含上下文与用法
        • 常见场景：技术文档、信息流摘录或跨语言沟通中的高频片段。
        • 理解重点：注意其中的关键限定词与动作主体，避免断章取义。

        3. 延伸联想
        如果你需要进一步查证背景，可以直接点击左下角「网页搜索」。
        """.trimIndent()
    }
}
