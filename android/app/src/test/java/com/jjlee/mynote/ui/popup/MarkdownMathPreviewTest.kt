package com.jjlee.mynote.ui.popup

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathPreviewTest {

    @Test
    fun `renders markdown headings, aligned LaTeX blocks, inline math, and citation links`() {
        val sample = """
            ## 2. 핵심 방법론 (Methodology)
            KBLaM은 각 트리플 ${'$'}\langle \text{name, property, value} \rangle${'$'}를 변환합니다 [P. 2, §1](cite:page_2).

            ${'$'}${'$'}\begin{aligned}
            k_m &= f(\langle \text{name} \rangle_m) \in \mathbb{R}^P \\
            \tilde{y}_l^n &= \frac{\sum_{m=1}^M \exp(\tilde{w}_{l,n,m})}{\sum_{i=1}^N \exp(w_{l,n,i})}
            \end{aligned}${'$'}${'$'}
        """.trimIndent()

        val html = buildMarkdownMathHtml(sample)
        assertTrue(html.contains("<h2>2. 핵심 방법론 (Methodology)</h2>"))
        assertTrue(html.contains("class=\"math-inline\""))
        assertTrue(html.contains("class=\"math-display\""))
        assertTrue(html.contains("class=\"math-aligned\""))
        assertTrue(html.contains("class=\"frac\""))
        assertTrue(html.contains("CitationBridge.onPageClick(2)"))
        assertTrue(html.contains("katex.min.js"))
    }
}
