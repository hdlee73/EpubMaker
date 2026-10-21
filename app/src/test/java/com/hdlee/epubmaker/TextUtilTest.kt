package com.hdlee.epubmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {
    @Test
    fun joinLines_rejoinsHyphenatedWord() {
        assertEquals("hyphenated word", TextUtil.joinLines(listOf("hyphen-", "ated word")))
    }

    @Test
    fun joinLines_hangulGetsSpace_ideographicDoesNot() {
        assertEquals("한글 문장 이어짐", TextUtil.joinLines(listOf("한글 문장", "이어짐")))
        assertEquals("漢字文章", TextUtil.joinLines(listOf("漢字", "文章")))
    }

    @Test
    fun pageNumbers_areDetected() {
        assertTrue(TextUtil.isPageNumber("- 12 -"))
        assertTrue(TextUtil.isPageNumber("3 / 20"))
        assertTrue(TextUtil.isPageNumber("Page 7"))
        assertFalse(TextUtil.isPageNumber("Chapter 7 begins here"))
    }

    @Test
    fun normKey_replacesDigits() {
        assertEquals(TextUtil.normKey("Report 2024  - 5"), TextUtil.normKey("Report 2024  - 9"))
    }

    @Test
    fun xmlEscape_escapesAndDropsInvalid() {
        assertEquals("a &amp; b &lt;c&gt;", TextUtil.xmlEscape("a & b <c>"))
        assertEquals("ab", TextUtil.xmlEscape("a\u0000b"))
    }

    @Test
    fun listStart_and_sentenceEnd() {
        assertTrue(TextUtil.isListStart("1. 항목"))
        assertTrue(TextUtil.isListStart("• item"))
        assertFalse(TextUtil.isListStart("문장이 이어집니다"))
        assertTrue(TextUtil.endsSentence("끝났습니다."))
        assertFalse(TextUtil.endsSentence("계속되는 문장"))
    }
}
