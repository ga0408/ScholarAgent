package com.jjlee.mynote.ui.popup

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private class CitationBridge(
    private val onPageClickCallback: () -> ((Int) -> Unit)?
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPageClick(pageNumber: Int) {
        mainHandler.post {
            onPageClickCallback()?.invoke(pageNumber)
        }
    }
}

/**
 * Renders Markdown + LaTeX math ($$...$$, $...$, \begin{aligned}...\end{aligned}, tables,
 * citations [P. X, ...](cite:page_X)) inside a scrollable WebView.
 * Uses an offline-ready HTML/CSS math formatter immediately and upgrades with KaTeX when online.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun MarkdownMathPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    onCitationClick: ((Int) -> Unit)? = null
) {
    val latestOnCitationClick = rememberUpdatedState(onCitationClick)
    val htmlContent = remember(markdown) { buildMarkdownMathHtml(markdown) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = false
                    useWideViewPort = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                addJavascriptInterface(
                    CitationBridge { latestOnCitationClick.value },
                    "CitationBridge"
                )
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    false
                }
            }
        },
        update = { webView ->
            if (webView.tag != htmlContent) {
                webView.tag = htmlContent
                webView.loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net/",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

internal fun buildMarkdownMathHtml(markdown: String): String {
    val normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")

    val codeBlocks = mutableListOf<String>()
    val inlineCodes = mutableListOf<String>()
    val displayMaths = mutableListOf<String>()
    val inlineMaths = mutableListOf<String>()

    var working = normalized

    // 1. Extract fenced code blocks ```lang ... ```
    working = Regex("""```([a-zA-Z0-9_-]*)\n([\s\S]*?)```""").replace(working) { match ->
        val rawLang = match.groupValues[1].trim()
        val rawCode = match.groupValues[2].trimEnd('\n')
        val idx = codeBlocks.size
        if (rawLang.equals("mermaid", ignoreCase = true)) {
            val fallback = renderMermaidFallbackHtml(rawCode)
            codeBlocks += "<div class=\"mermaid-box\" data-mermaid=\"${escapeHtml(rawCode)}\">$fallback</div>"
        } else {
            val lang = escapeHtml(rawLang)
            val code = escapeHtml(rawCode)
            val langBadge = if (lang.isNotEmpty()) "<div class=\"code-lang\">$lang</div>" else ""
            codeBlocks += "<pre class=\"code-block\">$langBadge<code>$code</code></pre>"
        }
        "\n\nMNPHCODEBLK${idx}PH\n\n"
    }

    // 2. Extract inline code `...`
    working = Regex("""`([^`\n]+?)`""").replace(working) { match ->
        val code = escapeHtml(match.groupValues[1])
        val idx = inlineCodes.size
        inlineCodes += "<code class=\"inline-code\">$code</code>"
        "MNPHINLCODE${idx}PH"
    }

    // 3. Extract display math $$...$$ and \[...\]
    working = Regex("""\$\$([\s\S]*?)\$\$""").replace(working) { match ->
        val rawLatex = match.groupValues[1].trim()
        val idx = displayMaths.size
        displayMaths += buildMathElement(rawLatex, displayMode = true)
        "\n\nMNPHDISPMATH${idx}PH\n\n"
    }
    working = Regex("""\\\[([\s\S]*?)\\\]""").replace(working) { match ->
        val rawLatex = match.groupValues[1].trim()
        val idx = displayMaths.size
        displayMaths += buildMathElement(rawLatex, displayMode = true)
        "\n\nMNPHDISPMATH${idx}PH\n\n"
    }

    // 4. Extract inline math $...$ and \(...\)
    working = Regex("""(?<!\\)\$([^\$\n]+?)(?<!\\)\$""").replace(working) { match ->
        val inner = match.groupValues[1]
        // Guard against plain currency like "$10 and $20"
        if (inner.firstOrNull()?.isWhitespace() == true && inner.lastOrNull()?.isWhitespace() == true) {
            match.value
        } else {
            val rawLatex = inner.trim()
            val idx = inlineMaths.size
            inlineMaths += buildMathElement(rawLatex, displayMode = false)
            "MNPHINLMATH${idx}PH"
        }
    }
    working = Regex("""\\\(([^\n]*?)\\\)""").replace(working) { match ->
        val rawLatex = match.groupValues[1].trim()
        val idx = inlineMaths.size
        inlineMaths += buildMathElement(rawLatex, displayMode = false)
        "MNPHINLMATH${idx}PH"
    }

    // 5. Render block-level Markdown
    val bodyHtml = renderMarkdownBlocks(working)

    // 6. Restore placeholders
    var finalBody = bodyHtml
    codeBlocks.forEachIndexed { idx, html ->
        finalBody = finalBody.replace("<p>MNPHCODEBLK${idx}PH</p>", html)
            .replace("MNPHCODEBLK${idx}PH", html)
    }
    displayMaths.forEachIndexed { idx, html ->
        finalBody = finalBody.replace("<p>MNPHDISPMATH${idx}PH</p>", html)
            .replace("MNPHDISPMATH${idx}PH", html)
    }
    inlineMaths.forEachIndexed { idx, html ->
        finalBody = finalBody.replace("MNPHINLMATH${idx}PH", html)
    }
    inlineCodes.forEachIndexed { idx, html ->
        finalBody = finalBody.replace("MNPHINLCODE${idx}PH", html)
    }

    return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css" crossorigin="anonymous">
<style>
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    padding: 0;
    background: transparent;
    color: #1E293B;
    font-family: -apple-system, BlinkMacSystemFont, "Noto Sans KR", "Segoe UI", Roboto, sans-serif;
    font-size: 13.5px;
    line-height: 1.65;
    word-break: keep-all;
    overflow-wrap: break-word;
  }
  .md-root {
    background: rgba(255, 255, 255, 0.88);
    border-radius: 10px;
    padding: 12px 14px 28px 14px;
    border: 1px solid #E2E8F0;
  }
  h1 {
    font-size: 17px;
    font-weight: 700;
    color: #0F172A;
    margin: 4px 0 12px 0;
    padding-bottom: 6px;
    border-bottom: 2px solid #F59E0B;
  }
  h2 {
    font-size: 15px;
    font-weight: 700;
    color: #0F766E;
    margin: 16px 0 8px 0;
    padding-bottom: 4px;
    border-bottom: 1px solid #CCFBF1;
  }
  h3 {
    font-size: 14px;
    font-weight: 700;
    color: #1D4ED8;
    margin: 12px 0 6px 0;
  }
  h4 {
    font-size: 13px;
    font-weight: 700;
    color: #6D28D9;
    margin: 10px 0 4px 0;
  }
  p {
    margin: 6px 0;
  }
  hr {
    border: none;
    border-top: 1px solid #E2E8F0;
    margin: 12px 0;
  }
  ul, ol {
    margin: 6px 0 8px 0;
    padding-left: 20px;
  }
  li {
    margin: 3px 0;
  }
  blockquote {
    margin: 8px 0;
    padding: 6px 10px;
    border-left: 3.5px solid #3B82F6;
    background: #EFF6FF;
    color: #334155;
    border-radius: 0 6px 6px 0;
  }
  strong { color: #0F172A; font-weight: 700; }
  .inline-code {
    background: #F1F5F9;
    color: #B45309;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    padding: 1px 5px;
    border-radius: 4px;
    border: 1px solid #E2E8F0;
  }
  .code-block {
    background: #0F172A;
    color: #E2E8F0;
    padding: 10px 12px;
    border-radius: 8px;
    overflow-x: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 1.5;
    margin: 8px 0;
  }
  .code-lang {
    font-size: 10px;
    color: #94A3B8;
    margin-bottom: 4px;
    text-transform: uppercase;
  }
  table.md-table {
    width: 100%;
    border-collapse: collapse;
    margin: 10px 0;
    font-size: 12.5px;
    display: block;
    overflow-x: auto;
  }
  table.md-table th, table.md-table td {
    border: 1px solid #CBD5E1;
    padding: 6px 8px;
    text-align: left;
  }
  table.md-table th {
    background: #FEF3C7;
    color: #78350F;
    font-weight: 700;
  }
  table.md-table tr:nth-child(even) td {
    background: #F8FAFC;
  }
  .cite-badge {
    display: inline-block;
    margin: 0 2px;
    padding: 1px 6px;
    border-radius: 5px;
    font-size: 11px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    background: #EFF6FF;
    color: #1D4ED8;
    border: 1px solid #BFDBFE;
    text-decoration: none;
    vertical-align: baseline;
  }
  .math-display {
    margin: 10px 0;
    padding: 10px 12px;
    background: #F8FAFC;
    border: 1px solid #E2E8F0;
    border-left: 3px solid #6366F1;
    border-radius: 8px;
    overflow-x: auto;
    text-align: center;
    font-family: "Times New Roman", "STIX Two Math", Georgia, serif;
    font-size: 14.5px;
    color: #0F172A;
  }
  .math-inline {
    font-family: "Times New Roman", "STIX Two Math", Georgia, serif;
    background: #F1F5F9;
    padding: 0 3px;
    border-radius: 3px;
    color: #0F172A;
  }
  .math-aligned {
    margin: 0 auto;
    border-collapse: collapse;
  }
  .math-aligned td {
    padding: 2px 4px;
    vertical-align: middle;
  }
  .math-aligned td.col-left { text-align: right; }
  .math-aligned td.col-right { text-align: left; }
  .frac {
    display: inline-flex;
    flex-direction: column;
    vertical-align: middle;
    text-align: center;
    margin: 0 2px;
    font-size: 0.9em;
  }
  .frac > .num {
    border-bottom: 1px solid #334155;
    padding: 0 4px 1px 4px;
  }
  .frac > .den {
    padding: 1px 4px 0 4px;
  }
  .math-text {
    font-family: -apple-system, BlinkMacSystemFont, "Noto Sans KR", sans-serif;
    font-style: normal;
  }
  .math-bf {
    font-weight: 700;
  }
  .accent-wrap {
    position: relative;
    display: inline-block;
  }
  .katex-display {
    margin: 0 !important;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 2px 0;
  }
  .mermaid-box {
    margin: 12px 0;
    padding: 12px;
    background: #F8FAFC;
    border: 1px solid #CBD5E1;
    border-radius: 10px;
    overflow-x: auto;
  }
  .mermaid-box svg {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 0 auto;
  }
  .mmd-subgraph {
    margin: 8px 0;
    padding: 10px 12px;
    background: #FFFFFF;
    border: 1px solid #93C5FD;
    border-left: 4px solid #2563EB;
    border-radius: 8px;
  }
  .mmd-sg-title {
    font-size: 12.5px;
    font-weight: 700;
    color: #1D4ED8;
    margin-bottom: 8px;
  }
  .mmd-edge {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    margin: 5px 0;
    font-size: 12px;
  }
  .mmd-node {
    display: inline-block;
    padding: 4px 9px;
    background: #EFF6FF;
    border: 1px solid #BFDBFE;
    border-radius: 6px;
    color: #0F172A;
    font-weight: 500;
  }
  .mmd-arrow {
    color: #2563EB;
    font-weight: 700;
    padding: 0 2px;
  }
  .mmd-cond {
    display: inline-block;
    padding: 1px 6px;
    background: #FEF3C7;
    border: 1px solid #FDE68A;
    border-radius: 999px;
    color: #92400E;
    font-size: 11px;
  }
</style>
</head>
<body>
<div class="md-root">
$finalBody
</div>
<script>
function upgradeWithKatex() {
  if (!window.katex) return;
  document.querySelectorAll('[data-latex]').forEach(function(el) {
    if (el.getAttribute('data-katex-done') === '1') return;
    var latex = el.getAttribute('data-latex');
    var displayMode = el.classList.contains('math-display');
    try {
      window.katex.render(latex, el, { displayMode: displayMode, throwOnError: false });
      el.setAttribute('data-katex-done', '1');
    } catch (e) {}
  });
}
function upgradeWithMermaid() {
  if (!window.mermaid) return;
  try {
    window.mermaid.initialize({
      startOnLoad: false,
      theme: 'default',
      securityLevel: 'loose',
      flowchart: { htmlLabels: true, curve: 'basis' }
    });
  } catch (e) {}
  var boxes = document.querySelectorAll('.mermaid-box[data-mermaid]');
  boxes.forEach(function(el, idx) {
    if (el.getAttribute('data-mmd-done') === '1') return;
    var src = el.getAttribute('data-mermaid');
    if (!src) return;
    try {
      window.mermaid.render('mmd_svg_' + idx + '_' + Date.now(), src).then(function(res) {
        if (res && res.svg) {
          el.innerHTML = res.svg;
          el.setAttribute('data-mmd-done', '1');
        }
      }).catch(function() {});
    } catch (e) {}
  });
}
</script>
<script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js" crossorigin="anonymous" onload="upgradeWithKatex()"></script>
<script defer src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js" crossorigin="anonymous" onload="upgradeWithMermaid()"></script>
</body>
</html>
    """.trimIndent()
}

private fun renderMermaidFallbackHtml(rawMermaid: String): String {
    val lines = rawMermaid.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
    val nodeLabels = LinkedHashMap<String, String>()
    val nodeDefRe = Regex("""^([A-Za-z0-9_]+)\s*(?:\["([^"]+)"\]|\[([^\]]+)\]|\("([^"]+)"\)|\(([^)]+)\)|\{([^}]+)\})$""")

    fun parseNodeToken(token: String): String {
        val t = token.trim()
        val m = nodeDefRe.matchEntire(t)
        if (m != null) {
            val id = m.groupValues[1]
            val label = (1..5).map { m.groupValues[it + 1] }.firstOrNull { it.isNotEmpty() } ?: id
            nodeLabels[id] = label
            return label
        }
        return nodeLabels[t] ?: t
    }

    val sb = StringBuilder()
    var inSubgraph = false
    val edgeRe = Regex("""^(.+?)\s*(-+>|==+>|-\.->)\s*(?:\|"([^"]*)"\|\s*|\|([^|]*)\|\s*)?(.+)$""")
    val sgRe = Regex("""^subgraph\s+(?:[A-Za-z0-9_]+\s*\["?([^"\]]+)"?\]|"?([^"]+)"?)$""", RegexOption.IGNORE_CASE)

    for (line in lines) {
        if (line.startsWith("flowchart ", ignoreCase = true) || line.startsWith("graph ", ignoreCase = true)) {
            continue
        }
        val sgMatch = sgRe.matchEntire(line)
        if (sgMatch != null) {
            if (inSubgraph) sb.append("</div>")
            val title = sgMatch.groupValues[1].ifEmpty { sgMatch.groupValues[2] }.ifEmpty { "Stage" }
            sb.append("<div class=\"mmd-subgraph\"><div class=\"mmd-sg-title\">")
                .append(escapeHtml(title))
                .append("</div>")
            inSubgraph = true
            continue
        }
        if (line.equals("end", ignoreCase = true)) {
            if (inSubgraph) {
                sb.append("</div>")
                inSubgraph = false
            }
            continue
        }
        val edgeMatch = edgeRe.matchEntire(line)
        if (edgeMatch != null) {
            val fromLabel = parseNodeToken(edgeMatch.groupValues[1])
            val arrowType = edgeMatch.groupValues[2]
            val cond = edgeMatch.groupValues[3].ifEmpty { edgeMatch.groupValues[4] }.trim()
            val toLabel = parseNodeToken(edgeMatch.groupValues[5])
            val arrowSym = if (arrowType.contains(".")) "⇢" else "→"
            sb.append("<div class=\"mmd-edge\">")
                .append("<span class=\"mmd-node\">").append(escapeHtml(fromLabel)).append("</span>")
            if (cond.isNotEmpty()) {
                sb.append("<span class=\"mmd-arrow\">──</span>")
                    .append("<span class=\"mmd-cond\">").append(escapeHtml(cond)).append("</span>")
            }
            sb.append("<span class=\"mmd-arrow\">").append(arrowSym).append("</span>")
                .append("<span class=\"mmd-node\">").append(escapeHtml(toLabel)).append("</span>")
                .append("</div>")
        }
    }
    if (inSubgraph) sb.append("</div>")
    return if (sb.isNotEmpty()) sb.toString() else "<pre class=\"code-block\"><code>${escapeHtml(rawMermaid)}</code></pre>"
}

private fun renderMarkdownBlocks(text: String): String {
    val lines = text.split('\n')
    val out = StringBuilder()
    var i = 0

    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Code block or display math placeholder lines
        if (trimmed.matches(Regex("""^MNPH(CODEBLK|DISPMATH)\d+PH$"""))) {
            out.append(trimmed).append("\n")
            i++
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("""^([-*_])\1{2,}$"""))) {
            out.append("<hr />\n")
            i++
            continue
        }

        // Headings # .. ####
        val headingMatch = Regex("""^(#{1,4})\s+(.*)$""").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = formatInlineMarkdown(headingMatch.groupValues[2])
            out.append("<h$level>$content</h$level>\n")
            i++
            continue
        }

        // Blockquote > ...
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines += lines[i].trim().removePrefix(">").trimStart()
                i++
            }
            out.append("<blockquote>")
                .append(quoteLines.joinToString("<br/>") { formatInlineMarkdown(it) })
                .append("</blockquote>\n")
            continue
        }

        // Markdown Table | a | b |
        if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparatorLine(lines[i + 1])) {
            val headers = splitTableRow(trimmed)
            i += 2 // skip header + separator
            out.append("<table class=\"md-table\"><thead><tr>")
            headers.forEach { cell ->
                out.append("<th>").append(formatInlineMarkdown(cell)).append("</th>")
            }
            out.append("</tr></thead><tbody>\n")
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                val cells = splitTableRow(lines[i].trim())
                out.append("<tr>")
                for (c in headers.indices) {
                    val cellText = cells.getOrElse(c) { "" }
                    out.append("<td>").append(formatInlineMarkdown(cellText)).append("</td>")
                }
                out.append("</tr>\n")
                i++
            }
            out.append("</tbody></table>\n")
            continue
        }

        // Unordered list - ... or * ...
        if (isUnorderedListItem(rawLine)) {
            out.append("<ul>\n")
            while (i < lines.size && isUnorderedListItem(lines[i])) {
                val itemContent = lines[i].trimStart().drop(2).trimStart()
                out.append("<li>").append(formatInlineMarkdown(itemContent)).append("</li>\n")
                i++
            }
            out.append("</ul>\n")
            continue
        }

        // Ordered list 1. ...
        if (isOrderedListItem(rawLine)) {
            out.append("<ol>\n")
            while (i < lines.size && isOrderedListItem(lines[i])) {
                val itemContent = lines[i].trimStart().replaceFirst(Regex("""^\d+\.\s+"""), "")
                out.append("<li>").append(formatInlineMarkdown(itemContent)).append("</li>\n")
                i++
            }
            out.append("</ol>\n")
            continue
        }

        // Normal paragraph (group consecutive plain lines until blank or block starter)
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = lines[i]
            val curTrim = cur.trim()
            if (curTrim.isEmpty() ||
                curTrim.matches(Regex("""^MNPH(CODEBLK|DISPMATH)\d+PH$""")) ||
                curTrim.matches(Regex("""^([-*_])\1{2,}$""")) ||
                curTrim.matches(Regex("""^#{1,4}\s+.*$""")) ||
                curTrim.startsWith(">") ||
                isUnorderedListItem(cur) ||
                isOrderedListItem(cur) ||
                (curTrim.startsWith("|") && i + 1 < lines.size && isTableSeparatorLine(lines[i + 1]))
            ) {
                break
            }
            paraLines += curTrim
            i++
        }
        out.append("<p>")
            .append(paraLines.joinToString("<br/>") { formatInlineMarkdown(it) })
            .append("</p>\n")
    }

    return out.toString()
}

private fun isUnorderedListItem(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("- ") || trimmed.startsWith("* ")
}

private fun isOrderedListItem(line: String): Boolean {
    return line.trimStart().matches(Regex("""^\d+\.\s+.*"""))
}

private fun isTableSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("|") && trimmed.matches(Regex("""^\|[\s:\-\|]+\|$"""))
}

private fun splitTableRow(line: String): List<String> {
    return line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map { it.trim() }
}

internal fun formatInlineMarkdown(raw: String): String {
    var html = escapeHtml(raw)

    // Links & citations [label](href)
    html = Regex("""\[([^\]]+)\]\(([^)]+)\)""").replace(html) { match ->
        val label = match.groupValues[1]
        val href = match.groupValues[2].trim()
        if (href.startsWith("cite:") || href.startsWith("#page=")) {
            val pageNum = extractPageNumber(href, label)
            "<a class=\"cite-badge\" href=\"javascript:void(0)\" onclick=\"CitationBridge.onPageClick($pageNum)\">&#x1F4C4; $label</a>"
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            "<a href=\"$href\" style=\"color:#2563EB;text-decoration:underline;\">$label</a>"
        } else {
            label
        }
    }

    // Bold + Italic ***...***
    html = Regex("""\*\*\*([^\*\n]+?)\*\*\*""").replace(html, "<strong><em>$1</em></strong>")
    // Bold **...**
    html = Regex("""\*\*([^\*\n]+?)\*\*""").replace(html, "<strong>$1</strong>")
    // Italic *...*
    html = Regex("""\*([^\*\n]+?)\*""").replace(html, "<em>$1</em>")

    return html
}

private fun extractPageNumber(href: String, label: String): Int {
    val fromHref = Regex("""(?:page_?|p)(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
    if (fromHref != null) return fromHref
    val fromLabel = Regex("""P(?:age)?\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
    return fromLabel ?: 1
}

private fun buildMathElement(rawLatex: String, displayMode: Boolean): String {
    val escapedAttr = escapeHtml(rawLatex)
    val fallbackHtml = renderLatexToHtmlFallback(rawLatex, displayMode)
    return if (displayMode) {
        "<div class=\"math-display\" data-latex=\"$escapedAttr\">$fallbackHtml</div>"
    } else {
        "<span class=\"math-inline\" data-latex=\"$escapedAttr\">$fallbackHtml</span>"
    }
}

/**
 * Pure Kotlin offline LaTeX -> HTML/Unicode formatter so formulas remain legible even offline
 * or while KaTeX CDN script finishes loading.
 */
internal fun renderLatexToHtmlFallback(latex: String, displayMode: Boolean): String {
    val trimmed = latex.trim()
    if (trimmed.isEmpty()) return ""

    // Handle \begin{aligned}...\end{aligned}, \begin{align*}...\end{align*}, \begin{cases}...\end{cases}
    val envRegex = Regex("""\\begin\{(aligned|align\*?|gather\*?|cases)\}([\s\S]*?)\\end\{\1\}""")
    val envMatch = envRegex.find(trimmed)
    if (envMatch != null) {
        val before = trimmed.substring(0, envMatch.range.first).trim()
        val envBody = envMatch.groupValues[2].trim()
        val after = trimmed.substring(envMatch.range.last + 1).trim()
        val rows = envBody.split(Regex("""\\\\"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val tableHtml = buildString {
            append("<table class=\"math-aligned\">")
            rows.forEach { row ->
                val cols = row.split('&')
                append("<tr>")
                if (cols.size >= 2) {
                    append("<td class=\"col-left\">").append(parseLatexExpression(cols[0])).append("</td>")
                    append("<td class=\"col-right\">").append(parseLatexExpression(cols.drop(1).joinToString(" "))).append("</td>")
                } else {
                    append("<td colspan=\"2\">").append(parseLatexExpression(row)).append("</td>")
                }
                append("</tr>")
            }
            append("</table>")
        }

        return buildString {
            if (before.isNotEmpty()) append(parseLatexExpression(before)).append(" ")
            append(tableHtml)
            if (after.isNotEmpty()) append("<div style=\"margin-top:4px;font-size:12px;color:#475569;\">").append(parseLatexExpression(after)).append("</div>")
        }
    }

    val rendered = parseLatexExpression(trimmed)
    return if (displayMode) rendered else rendered
}

private fun parseLatexExpression(input: String): String {
    val sb = StringBuilder()
    var i = 0
    val n = input.length

    while (i < n) {
        val c = input[i]
        when {
            c == '\\' -> {
                if (i + 1 >= n) {
                    i++
                    continue
                }
                val next = input[i + 1]
                if (!next.isLetter()) {
                    // Escaped symbol or spacing command
                    when (next) {
                        '{', '}', '$', '%', '&', '#' -> sb.append(escapeHtml(next.toString()))
                        ',' -> sb.append("&#8201;")
                        ';', ':' -> sb.append("&#8197;")
                        ' ' -> sb.append("&nbsp;")
                        '!' -> Unit
                        '\\' -> sb.append("<br/>")
                        else -> sb.append(escapeHtml(next.toString()))
                    }
                    i += 2
                    continue
                }

                var j = i + 1
                while (j < n && input[j].isLetter()) j++
                val cmd = input.substring(i + 1, j)
                // Optional trailing * (e.g. \operatorname*)
                if (j < n && input[j] == '*') j++

                when (cmd) {
                    "frac", "dfrac", "tfrac" -> {
                        val (num, afterNum) = readBraceOrCharArg(input, j)
                        val (den, afterDen) = readBraceOrCharArg(input, afterNum)
                        sb.append("<span class=\"frac\"><span class=\"num\">")
                            .append(parseLatexExpression(num))
                            .append("</span><span class=\"den\">")
                            .append(parseLatexExpression(den))
                            .append("</span></span>")
                        i = afterDen
                    }
                    "sqrt" -> {
                        var idx = skipWhitespace(input, j)
                        var rootIndex = ""
                        if (idx < n && input[idx] == '[') {
                            val closeBracket = input.indexOf(']', idx + 1)
                            if (closeBracket != -1) {
                                rootIndex = input.substring(idx + 1, closeBracket)
                                idx = closeBracket + 1
                            }
                        }
                        val (body, afterBody) = readBraceOrCharArg(input, idx)
                        if (rootIndex.isNotEmpty()) {
                            sb.append("<sup>").append(parseLatexExpression(rootIndex)).append("</sup>")
                        }
                        sb.append("&radic;<span style=\"border-top:1px solid #334155;padding:0 2px;\">")
                            .append(parseLatexExpression(body))
                            .append("</span>")
                        i = afterBody
                    }
                    "text", "textrm", "mathrm", "operatorname" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append("<span class=\"math-text\">").append(escapeHtml(body)).append("</span>")
                        i = afterBody
                    }
                    "mathbf", "textbf", "boldsymbol", "bm" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append("<span class=\"math-bf\">").append(parseLatexExpression(body)).append("</span>")
                        i = afterBody
                    }
                    "mathbb" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        val mapped = when (body.trim()) {
                            "R" -> "&#8477;"
                            "N" -> "&#8469;"
                            "Z" -> "&#8484;"
                            "Q" -> "&#8474;"
                            "C" -> "&#8450;"
                            "E" -> "&#120124;"
                            else -> escapeHtml(body)
                        }
                        sb.append("<span class=\"math-bf\">").append(mapped).append("</span>")
                        i = afterBody
                    }
                    "mathcal" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append("<em>").append(escapeHtml(body)).append("</em>")
                        i = afterBody
                    }
                    "tilde", "widetilde" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append(applyCombiningAccent(body, "&#771;"))
                        i = afterBody
                    }
                    "hat", "widehat" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append(applyCombiningAccent(body, "&#770;"))
                        i = afterBody
                    }
                    "bar", "overline" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append("<span style=\"border-top:1px solid #1E293B;padding-top:1px;\">")
                            .append(parseLatexExpression(body))
                            .append("</span>")
                        i = afterBody
                    }
                    "vec" -> {
                        val (body, afterBody) = readBraceOrCharArg(input, j)
                        sb.append(applyCombiningAccent(body, "&#8407;"))
                        i = afterBody
                    }
                    "left", "right", "big", "Big", "bigg", "Bigg" -> {
                        i = j
                    }
                    else -> {
                        sb.append(latexCommandSymbol(cmd))
                        i = j
                    }
                }
            }
            c == '_' || c == '^' -> {
                val tag = if (c == '_') "sub" else "sup"
                val (arg, afterArg) = readBraceOrCharArg(input, i + 1)
                sb.append("<$tag style=\"font-size:0.76em;\">")
                    .append(parseLatexExpression(arg))
                    .append("</$tag>")
                i = afterArg
            }
            c == '{' -> {
                val (group, afterGroup) = readBraceGroup(input, i)
                sb.append(parseLatexExpression(group))
                i = afterGroup
            }
            c == '}' -> {
                i++
            }
            else -> {
                sb.append(escapeHtml(c.toString()))
                i++
            }
        }
    }

    return sb.toString()
}

private fun applyCombiningAccent(body: String, combiningEntity: String): String {
    val clean = body.trim()
    return if (clean.length == 1) {
        "${escapeHtml(clean)}$combiningEntity"
    } else {
        parseLatexExpression(clean) + combiningEntity
    }
}

private fun skipWhitespace(src: String, start: Int): Int {
    var i = start
    while (i < src.length && src[i].isWhitespace()) i++
    return i
}

private fun readBraceGroup(src: String, start: Int): Pair<String, Int> {
    if (start >= src.length || src[start] != '{') return "" to start
    var depth = 0
    var i = start
    while (i < src.length) {
        when (src[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return src.substring(start + 1, i) to (i + 1)
                }
            }
        }
        i++
    }
    return src.substring(start + 1) to src.length
}

private fun readBraceOrCharArg(src: String, start: Int): Pair<String, Int> {
    val i = skipWhitespace(src, start)
    if (i >= src.length) return "" to i
    if (src[i] == '{') return readBraceGroup(src, i)
    if (src[i] == '\\') {
        var j = i + 1
        while (j < src.length && src[j].isLetter()) j++
        if (j == i + 1 && j < src.length) j++
        return src.substring(i, j) to j
    }
    return src.substring(i, i + 1) to (i + 1)
}

private fun latexCommandSymbol(cmd: String): String = when (cmd) {
    // Delimiters & spacing
    "langle" -> "&lang;"
    "rangle" -> "&rang;"
    "lbrace" -> "{"
    "rbrace" -> "}"
    "lvert", "rvert", "mid" -> "|"
    "lVert", "rVert" -> "&Vert;"
    "quad" -> "&emsp;"
    "qquad" -> "&emsp;&emsp;"
    // Operators & Relations
    "sum" -> "&sum;"
    "prod" -> "&prod;"
    "int" -> "&int;"
    "times" -> "&times;"
    "cdot" -> "&middot;"
    "pm" -> "&plusmn;"
    "mp" -> "&#8723;"
    "div" -> "&divide;"
    "leq", "le" -> "&le;"
    "geq", "ge" -> "&ge;"
    "neq", "ne" -> "&ne;"
    "approx" -> "&approx;"
    "equiv" -> "&equiv;"
    "sim" -> "&sim;"
    "propto" -> "&prop;"
    "in" -> "&isin;"
    "notin" -> "&notin;"
    "subset" -> "&sub;"
    "subseteq" -> "&sube;"
    "cup" -> "&cup;"
    "cap" -> "&cap;"
    "forall" -> "&forall;"
    "exists" -> "&exist;"
    "nabla" -> "&nabla;"
    "partial" -> "&part;"
    "infty" -> "&infin;"
    "top" -> "&#8868;"
    "bot" -> "&#8869;"
    "ell" -> "&#8467;"
    "dots", "ldots", "cdots" -> "&hellip;"
    "vdots" -> "&#8942;"
    "ddots" -> "&#8945;"
    "to", "rightarrow" -> "&rarr;"
    "leftarrow" -> "&larr;"
    "leftrightarrow" -> "&harr;"
    "Rightarrow", "implies" -> "&rArr;"
    "Leftarrow" -> "&lArr;"
    "Leftrightarrow", "iff" -> "&hArr;"
    "mapsto" -> "&#8614;"
    // Greek letters
    "alpha" -> "&alpha;"
    "beta" -> "&beta;"
    "gamma" -> "&gamma;"
    "delta" -> "&delta;"
    "epsilon", "varepsilon" -> "&epsilon;"
    "zeta" -> "&zeta;"
    "eta" -> "&eta;"
    "theta", "vartheta" -> "&theta;"
    "iota" -> "&iota;"
    "kappa" -> "&kappa;"
    "lambda" -> "&lambda;"
    "mu" -> "&mu;"
    "nu" -> "&nu;"
    "xi" -> "&xi;"
    "pi" -> "&pi;"
    "rho" -> "&rho;"
    "sigma" -> "&sigma;"
    "tau" -> "&tau;"
    "upsilon" -> "&upsilon;"
    "phi", "varphi" -> "&phi;"
    "chi" -> "&chi;"
    "psi" -> "&psi;"
    "omega" -> "&omega;"
    "Gamma" -> "&Gamma;"
    "Delta" -> "&Delta;"
    "Theta" -> "&Theta;"
    "Lambda" -> "&Lambda;"
    "Xi" -> "&Xi;"
    "Pi" -> "&Pi;"
    "Sigma" -> "&Sigma;"
    "Phi" -> "&Phi;"
    "Psi" -> "&Psi;"
    "Omega" -> "&Omega;"
    // Common functions
    "exp", "log", "ln", "sin", "cos", "tan", "max", "min", "arg", "softmax", "dim", "det" -> cmd
    "argmax" -> "argmax"
    "argmin" -> "argmin"
    else -> cmd
}

private fun escapeHtml(text: String): String = buildString(text.length) {
    text.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
