package com.jjlee.mynote.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingRecognizerTest {

    @Test
    fun `formats recognized lines into markdown with title header`() {
        val md = HandwritingRecognizer.formatAsMarkdown(
            title = "논문 요약",
            rawText = "핵심 아이디어\n- 성능 2배 향상\n결론 정리"
        )
        assertTrue(md.startsWith("# 논문 요약\n\n"))
        assertTrue(md.contains("핵심 아이디어\n- 성능 2배 향상\n결론 정리"))
    }

    @Test
    fun `handles blank recognized text cleanly`() {
        val md = HandwritingRecognizer.formatAsMarkdown("빈 메모", "   ")
        assertEquals("# 빈 메모\n\n", md)
    }

    @Test
    fun `appendRecognizedMarkdown adds title once and avoids duplicate headers on repeated runs`() {
        val first = HandwritingRecognizer.appendRecognizedMarkdown(
            existingMemo = "",
            title = "Attention Is All You Need",
            rawText = "1차 필기 내용"
        )
        assertEquals("# Attention Is All You Need\n\n1차 필기 내용\n", first)

        val second = HandwritingRecognizer.appendRecognizedMarkdown(
            existingMemo = first,
            title = "Attention Is All You Need",
            rawText = "2차 필기 내용"
        )
        assertEquals("# Attention Is All You Need\n\n1차 필기 내용\n\n2차 필기 내용\n", second)
    }

    @Test
    fun `appendRecognizedMarkdown prepends title once if existing memo has no heading`() {
        val merged = HandwritingRecognizer.appendRecognizedMarkdown(
            existingMemo = "미리 적어둔 메모",
            title = "문서제목",
            rawText = "추가 필기"
        )
        assertEquals("# 문서제목\n\n미리 적어둔 메모\n\n추가 필기\n", merged)
    }
}
