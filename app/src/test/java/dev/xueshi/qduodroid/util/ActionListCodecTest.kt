package dev.xueshi.qduodroid.util

import dev.xueshi.qduodroid.engine.QDuoPrompts
import dev.xueshi.qduodroid.model.ActionKind
import dev.xueshi.qduodroid.model.QDuoAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionListCodecTest {

    @Test
    fun `default list keeps the six original actions in order`() {
        val defaults = ActionListCodec.defaultActions(llmTranslate = false)
        assertEquals(listOf("翻译", "润色", "总结", "解释", "搜索", "朗读"), defaults.map { it.title })
        assertEquals(ActionKind.TRANSLATE, defaults[0].kind)
        assertEquals(ActionKind.OPEN_URL, defaults[4].kind)
        assertEquals(ActionKind.SPEAK, defaults[5].kind)
    }

    @Test
    fun `users who had LLM translate on get an AI translate action`() {
        val translate = ActionListCodec.defaultActions(llmTranslate = true).first()
        assertEquals(ActionKind.AI, translate.kind)
        assertEquals(QDuoPrompts.TRANSLATE_PROMPT, translate.prompt)
    }

    @Test
    fun `encode then decode returns the same list`() {
        val list = ActionListCodec.defaultActions(llmTranslate = false) + listOf(
            QDuoAction("a_1", "豆瓣", ActionKind.OPEN_URL, url = "https://www.douban.com/search?q={text}", enabled = false),
            QDuoAction("a_2", "改写", ActionKind.AI, prompt = "第一行\n第二行 \"引号\""),
        )
        assertEquals(list, ActionListCodec.decode(ActionListCodec.encode(list)))
    }

    @Test
    fun `decode skips unknown kinds and duplicate ids`() {
        val raw = """[
            {"id":"x","title":"未来的类型","kind":"teleport"},
            {"id":"a","title":"朗读","kind":"speak"},
            {"id":"a","title":"重复","kind":"speak"},
            {"id":"b","kind":"translate"}
        ]"""
        val decoded = ActionListCodec.decode(raw)!!
        assertEquals(listOf("a", "b"), decoded.map { it.id })
        assertEquals("免费翻译", decoded[1].title)
        assertEquals(true, decoded[1].enabled)
    }

    @Test
    fun `decode returns null for broken json`() {
        assertNull(ActionListCodec.decode("{not json"))
    }

    @Test
    fun `fillUrl encodes the selected text into the placeholder`() {
        assertEquals(
            "https://www.google.com/search?q=hello%20world%20%26%20%E4%BD%A0%E5%A5%BD",
            QDuoAction.fillUrl("https://www.google.com/search?q={text}", "  hello world & 你好 "),
        )
    }
}
