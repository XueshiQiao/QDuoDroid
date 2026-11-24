package dev.xueshi.qduodroid.engine

import dev.xueshi.qduodroid.model.QDuoAction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeActionEngineTest {

    @Test
    fun `generate returns tailored mock output for all sheet actions`() {
        val inputs = listOf(
            "PopClip style text selection toolbar for Android.",
            "这是一段用来测试中文翻译和润色效果的示例文本。",
        )

        for (input in inputs) {
            for (action in QDuoAction.sheetActions) {
                val output = FakeActionEngine.generate(action, input)
                assertTrue("Expected non-blank output for $action", output.isNotBlank())
                assertTrue("Expected mock marker for $action", output.contains("Demo Mock"))
            }
        }
    }

    @Test
    fun `generate handles blank input gracefully`() {
        val output = FakeActionEngine.generate(QDuoAction.TRANSLATE, "   ")
        assertTrue(output.contains("未检测到有效文本"))
    }

    @Test
    fun `stream progressively emits until full output`() = runTest {
        val input = "Testing streaming fake action output in QDuo Lab."
        val expected = FakeActionEngine.generate(QDuoAction.SUMMARIZE, input)
        val chunks = FakeActionEngine.stream(QDuoAction.SUMMARIZE, input, delayMs = 0L).toList()

        assertFalse(chunks.isEmpty())
        assertTrue(chunks.first().length <= expected.length)
        assertTrue(chunks.last() == expected)
    }
}
