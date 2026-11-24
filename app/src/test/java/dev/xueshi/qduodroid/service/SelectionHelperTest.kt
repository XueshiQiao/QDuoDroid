package dev.xueshi.qduodroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHelperTest {

    @Test
    fun `isCopyButtonLabel matches Chinese and English copy buttons cleanly`() {
        assertTrue(SelectionAccessibilityService.isCopyButtonLabel("复制"))
        assertTrue(SelectionAccessibilityService.isCopyButtonLabel(" 复制 "))
        assertTrue(SelectionAccessibilityService.isCopyButtonLabel("Copy"))
        assertTrue(SelectionAccessibilityService.isCopyButtonLabel("COPY"))
        assertTrue(SelectionAccessibilityService.isCopyButtonLabel("复制文本"))

        assertFalse(SelectionAccessibilityService.isCopyButtonLabel("全部复制"))
        assertFalse(SelectionAccessibilityService.isCopyButtonLabel("粘贴"))
        assertFalse(SelectionAccessibilityService.isCopyButtonLabel(""))
        assertFalse(SelectionAccessibilityService.isCopyButtonLabel(null))
    }

    @Test
    fun `extractSubstringSafe handles normal, clamped, empty, and reversed bounds`() {
        assertEquals(
            "PopClip",
            SelectionAccessibilityService.extractSubstringSafe("QDuo brings PopClip actions", 12, 19),
        )
        assertEquals(
            "actions",
            SelectionAccessibilityService.extractSubstringSafe("QDuo actions", 5, 99),
        )
        assertNull(SelectionAccessibilityService.extractSubstringSafe("hello", 3, 3))
        assertNull(SelectionAccessibilityService.extractSubstringSafe("hello", -1, 2))
        assertNull(SelectionAccessibilityService.extractSubstringSafe("hello", 4, 2))
        assertNull(SelectionAccessibilityService.extractSubstringSafe("hello   ", 5, 8))
        assertNull(SelectionAccessibilityService.extractSubstringSafe(null, 0, 3))
    }
}
