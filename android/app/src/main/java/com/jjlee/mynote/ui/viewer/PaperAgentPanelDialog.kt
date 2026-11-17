package com.jjlee.mynote.ui.viewer

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jjlee.mynote.ui.popup.MarkdownMathPreview
import org.json.JSONArray
import java.io.File

data class PaperHighlightItem(
    val blockId: String,
    val page: Int,
    val category: String,
    val importance: Int,
    val quote: String,
    val reason: String,
    val matched: Boolean,
    val rects: List<List<Float>> = emptyList()
)

internal fun highlightOverlayColor(category: String): Color = when (category.lowercase()) {
    "background" -> Color(0xFF93C5FD)
    "contribution" -> Color(0xFFFDE047)
    "method" -> Color(0xFF86EFAC)
    "experiment" -> Color(0xFFFDBA74)
    "limitation" -> Color(0xFFF9A8D4)
    else -> Color(0xFFFDE047)
}

private fun categoryBadgeColor(category: String): Color = when (category.lowercase()) {
    "background" -> Color(0xFFDBEAFE)
    "contribution" -> Color(0xFFFEF08A)
    "method" -> Color(0xFFDCFCE7)
    "experiment" -> Color(0xFFFED7AA)
    "limitation" -> Color(0xFFFCE7F3)
    else -> Color(0xFFE2E8F0)
}

internal fun parseHighlightsJson(raw: String): List<PaperHighlightItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rectsArr = obj.optJSONArray("rects")
                val rectsList = if (rectsArr != null) {
                    buildList(rectsArr.length()) {
                        for (j in 0 until rectsArr.length()) {
                            val r = rectsArr.optJSONArray(j) ?: continue
                            if (r.length() >= 4) {
                                add(
                                    listOf(
                                        r.optDouble(0).toFloat(),
                                        r.optDouble(1).toFloat(),
                                        r.optDouble(2).toFloat(),
                                        r.optDouble(3).toFloat()
                                    )
                                )
                            }
                        }
                    }
                } else {
                    emptyList()
                }
                add(
                    PaperHighlightItem(
                        blockId = obj.optString("block_id", ""),
                        page = obj.optInt("page", 1),
                        category = obj.optString("category", "method"),
                        importance = obj.optInt("importance", 3),
                        quote = obj.optString("quote", ""),
                        reason = obj.optString("reason", ""),
                        matched = obj.optBoolean("matched", true),
                        rects = rectsList
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun docCacheDir(context: Context, documentId: String): File {
    val safeId = documentId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(context.filesDir, "scholar_agent/$safeId").apply { mkdirs() }
}

private fun loadCachedOrSiblingFile(
    context: Context,
    documentId: String,
    localPdfFile: File?,
    fileName: String,
    siblingSubdir: String
): String {
    val cached = File(docCacheDir(context, documentId), fileName)
    if (cached.exists()) return cached.readText()

    if (localPdfFile != null) {
        val parent = localPdfFile.parentFile
        val sameDir = parent?.let { File(it, fileName) }
        if (sameDir != null && sameDir.exists()) return sameDir.readText()

        val sibling = parent?.parentFile?.let { File(File(it, siblingSubdir), fileName) }
        if (sibling != null && sibling.exists()) return sibling.readText()
    }
    return ""
}

private fun readUriText(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

@Composable
fun ScholarAgentPanelDialog(
    documentId: String,
    documentTitle: String,
    localOriginalFile: File?,
    onJumpToPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var summaryMarkdown by remember { mutableStateOf("") }
    var highlights by remember { mutableStateOf<List<PaperHighlightItem>>(emptyList()) }

    LaunchedEffect(documentId, localOriginalFile) {
        summaryMarkdown = loadCachedOrSiblingFile(
            context, documentId, localOriginalFile, "summary.ko.md", "summary"
        ).ifBlank {
            loadCachedOrSiblingFile(context, documentId, localOriginalFile, "summary.en.md", "summary")
        }
        val hlRaw = loadCachedOrSiblingFile(
            context, documentId, localOriginalFile, "highlights.json", "highlights"
        )
        highlights = parseHighlightsJson(hlRaw)
    }

    val pickSummaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readUriText(context, uri)
            if (text.isNotBlank()) {
                File(docCacheDir(context, documentId), "summary.ko.md").writeText(text)
                summaryMarkdown = text
                Toast.makeText(context, "AI 요약(.md)을 불러왔습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pickHighlightsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readUriText(context, uri)
            val parsed = parseHighlightsJson(text)
            if (parsed.isNotEmpty()) {
                File(docCacheDir(context, documentId), "highlights.json").writeText(text)
                highlights = parsed
                Toast.makeText(context, "형광펜 ${parsed.size}개를 불러왔습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "유효한 highlights.json 파일이 아닙니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ScholarAgent AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(documentTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("AI 요약 (.md)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("형광펜 (${highlights.size})") }
                    )
                }

                if (selectedTab == 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = { pickSummaryLauncher.launch(arrayOf("text/*", "*/*")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("summary.ko.md 불러오기")
                        }
                    }
                    HorizontalDivider()
                    if (summaryMarkdown.isBlank()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "ScholarAgent에서 생성한 summary.ko.md를 불러와 보세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            MarkdownMathPreview(
                                markdown = summaryMarkdown,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = { pickHighlightsLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("highlights.json 불러오기")
                        }
                    }
                    HorizontalDivider()
                    if (highlights.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "ScholarAgent에서 생성한 highlights.json을 불러오면\n항목을 탭해 해당 페이지로 바로 이동할 수 있습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(highlights) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onJumpToPage((item.page - 1).coerceAtLeast(0))
                                            onDismiss()
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        categoryBadgeColor(item.category),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    item.category.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF1E293B),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Text(
                                                "p. ${item.page}  •  ★${item.importance}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "\"${item.quote}\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (item.reason.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                item.reason,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
