package dev.xueshi.qduodroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliTranslatorTest {

    @Test
    fun `detectTarget picks en for Chinese and zh for English`() {
        assertEquals("en" to "英语", BilibiliTranslator.detectTarget("今天天气真不错"))
        assertEquals("zh" to "简体中文", BilibiliTranslator.detectTarget("Android clipboard monitoring via Shizuku"))
    }

    @Test
    fun `buildPrompt wraps input inside JSON text field`() {
        val prompt = BilibiliTranslator.buildPrompt("Hello \"world\"", "简体中文")
        assertTrue(prompt.startsWith("请将以下文本翻译成简体中文"))
        assertTrue(prompt.contains("{\"text\":\"Hello \\\"world\\\"\"}"))
    }

    @Test
    fun `extractTranslation unwraps JSON, fenced JSON, and think blocks`() {
        assertEquals("你好世界", BilibiliTranslator.extractTranslation("""{"text":"你好世界"}"""))
        assertEquals(
            "你好世界",
            BilibiliTranslator.extractTranslation("```json\n{\"text\":\"你好世界\"}\n```"),
        )
        assertEquals(
            "你好世界",
            BilibiliTranslator.extractTranslation("<think>reasoning</think>\n{\"text\":\"你好世界\"}"),
        )
    }
}
