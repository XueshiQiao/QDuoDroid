package dev.xueshi.qduodroid.engine

import dev.xueshi.qduodroid.model.ActionKind
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.util.ActionListCodec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeActionEngineTest {

    private val defaults = ActionListCodec.defaultActions(llmTranslate = false)

    @Test
    fun `generate returns tailored mock output for all result actions`() {
        val inputs = listOf(
            "PopClip style text selection toolbar for Android.",
            "这是一段用来测试中文翻译和润色效果的示例文本。",
        )

        for (input in inputs) {
            for (action in defaults.filter { it.showsResult }) {
                val output = FakeActionEngine.generate(action, input)
                assertTrue("Expected non-blank output for ${action.title}", output.isNotBlank())
                assertTrue("Expected mock marker for ${action.title}", output.contains("Demo Mock"))
            }
        }
    }

    @Test
    fun `generate falls back to a generic mock for user-made AI actions`() {
        val custom = QDuoAction("a_custom", "改成文言文", ActionKind.AI, prompt = "Rewrite in Classical Chinese.")
        val output = FakeActionEngine.generate(custom, "今天天气很好")
        assertTrue(output.contains("Demo Mock · 改成文言文"))
        assertTrue(output.contains("Rewrite in Classical Chinese."))
    }

    @Test
    fun `generate handles blank input gracefully`() {
        val output = FakeActionEngine.generate(defaults.first(), "   ")
        assertTrue(output.contains("未检测到有效文本"))
    }

    @Test
    fun `stream progressively emits until full output`() = runTest {
        val input = "Testing streaming fake action output in QDuo Lab."
        val summarize = defaults.first { it.id == "summarize" }
        val expected = FakeActionEngine.generate(summarize, input)
        val chunks = FakeActionEngine.stream(summarize, input, delayMs = 0L).toList()

        assertFalse(chunks.isEmpty())
        assertTrue(chunks.first().length <= expected.length)
        assertTrue(chunks.last() == expected)
    }
}
