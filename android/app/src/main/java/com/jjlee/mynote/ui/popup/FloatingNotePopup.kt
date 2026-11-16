package com.jjlee.mynote.ui.popup

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jjlee.mynote.data.LocalFileStorage
import com.jjlee.mynote.data.db.PopupNoteEntity
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.data.db.StrokePoint
import com.jjlee.mynote.ink.BrushState
import com.jjlee.mynote.ink.HandwritingRecognizer
import com.jjlee.mynote.ink.StylusCanvasView
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Draggable, resizable, minimizable floating note popup with:
 * - Tab 0: 144Hz Stylus handwriting canvas + Convert-to-Markdown (ML Kit Digital Ink)
 * - Tab 1: Basic text & Markdown notepad + preview + .md export
 */
@Composable
fun FloatingNotePopup(
    note: PopupNoteEntity,
    documentTitle: String = note.title,
    strokes: List<StrokeEntity>,
    brushState: BrushState,
    onUploadMarkdownToDrive: ((File) -> Unit)? = null,
    onNavigateToPage: ((Int) -> Unit)? = null,
    onUpdateNote: (PopupNoteEntity) -> Unit,
    onClosePopup: () -> Unit,
    onStrokeCompleted: (BrushState, List<StrokePoint>) -> Unit,
    onEraseIds: (List<Long>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var offsetX by remember(note.id) { mutableFloatStateOf(note.offsetX) }
    var offsetY by remember(note.id) { mutableFloatStateOf(note.offsetY) }
    var widthDp by remember(note.id) { mutableFloatStateOf(note.widthDp.coerceAtLeast(380f)) }
    var heightDp by remember(note.id) { mutableFloatStateOf(note.heightDp.coerceAtLeast(440f)) }
    var selectedTab by remember(note.id) { mutableIntStateOf(0) } // 0 = 필기 노트, 1 = MD 메모장
    var memoText by remember(note.id, note.memoText) { mutableStateOf(note.memoText) }
    var isPreviewingMemo by remember(note.id) { mutableStateOf(false) }
    var isConverting by remember { mutableStateOf(false) }

    if (note.isMinimized) {
        Box(
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
        ) {
            FloatingActionButton(
                onClick = { onUpdateNote(note.copy(isMinimized = false)) },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = "노트 열기")
                    Spacer(Modifier.width(6.dp))
                    Text(note.title)
                }
            }
        }
        return
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(widthDp.dp)
            .height(heightDp.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFFBEB)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Draggable header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFEF3C7))
                        .pointerInput(note.id) {
                            detectDragGestures(
                                onDragEnd = {
                                    onUpdateNote(note.copy(offsetX = offsetX, offsetY = offsetY))
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceAtLeast(0f)
                                offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "이동",
                            tint = Color(0xFF92400E),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = note.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF78350F)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                widthDp = (widthDp - 80f).coerceIn(280f, 1050f)
                                heightDp = (heightDp - 80f).coerceIn(260f, 900f)
                                onUpdateNote(note.copy(widthDp = widthDp, heightDp = heightDp))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "노트 창 축소",
                                tint = Color(0xFF78350F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                widthDp = (widthDp + 80f).coerceIn(280f, 1050f)
                                heightDp = (heightDp + 80f).coerceIn(260f, 900f)
                                onUpdateNote(note.copy(widthDp = widthDp, heightDp = heightDp))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "노트 창 확대",
                                tint = Color(0xFF78350F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onUpdateNote(
                                    note.copy(
                                        offsetX = offsetX,
                                        offsetY = offsetY,
                                        widthDp = widthDp,
                                        heightDp = heightDp,
                                        memoText = memoText,
                                        isMinimized = true
                                    )
                                )
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Minimize,
                                contentDescription = "최소화",
                                tint = Color(0xFF78350F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onUpdateNote(
                                    note.copy(
                                        offsetX = offsetX,
                                        offsetY = offsetY,
                                        widthDp = widthDp,
                                        heightDp = heightDp,
                                        memoText = memoText
                                    )
                                )
                                onClosePopup()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "닫기",
                                tint = Color(0xFF78350F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFFFEF9C3)) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("필기 노트") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("MD 메모장") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("AI 요약") }
                    )
                }

                if (selectedTab == 0) {
                    // Handwriting canvas + Convert-to-Markdown action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFFBEB))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = !isConverting,
                            onClick = {
                                if (strokes.isEmpty()) {
                                    Toast.makeText(context, "변환할 필기가 없습니다.", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                isConverting = true
                                scope.launch {
                                    runCatching {
                                        HandwritingRecognizer.recognizeText(strokes = strokes)
                                    }.onSuccess { recognizedText ->
                                        if (recognizedText.isBlank()) {
                                            Toast.makeText(context, "인식된 텍스트가 없습니다.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            memoText = HandwritingRecognizer.appendRecognizedMarkdown(
                                                existingMemo = memoText,
                                                title = documentTitle,
                                                rawText = recognizedText
                                            )
                                            onUpdateNote(note.copy(memoText = memoText))
                                            selectedTab = 1
                                            Toast.makeText(context, "필기를 마크다운으로 변환했습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { e ->
                                        Toast.makeText(context, "필기 인식 실패: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isConverting = false
                                }
                            }
                        ) {
                            if (isConverting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("인식 중...")
                            } else {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("필기 → 마크다운 변환")
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                StylusCanvasView(ctx).apply {
                                    this.brushState = brushState
                                    this.onStrokeCompleted = onStrokeCompleted
                                    this.onEraseIdsRequested = onEraseIds
                                    this.onTwoFingerTapUndo = onUndo
                                    this.onThreeFingerTapRedo = onRedo
                                }
                            },
                            update = { view ->
                                view.brushState = brushState
                                view.setStrokes(strokes)
                                view.onStrokeCompleted = onStrokeCompleted
                                view.onEraseIdsRequested = onEraseIds
                                view.onTwoFingerTapUndo = onUndo
                                view.onThreeFingerTapRedo = onRedo
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (selectedTab == 1) {
                    // Text / Markdown Notepad
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        if (isPreviewingMemo) {
                            MarkdownMathPreview(
                                markdown = memoText.ifBlank { "*작성된 메모가 없습니다.*" },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                onCitationClick = { pageNum ->
                                    onNavigateToPage?.invoke((pageNum - 1).coerceAtLeast(0))
                                }
                            )
                        } else {
                            OutlinedTextField(
                                value = memoText,
                                onValueChange = { memoText = it },
                                placeholder = { Text("# 제목\n여기에 메모를 입력하거나 필기를 변환하세요...") },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { isPreviewingMemo = !isPreviewingMemo }
                            ) {
                                Text(if (isPreviewingMemo) "편집" else "미리보기")
                            }
                            TextButton(
                                onClick = {
                                    onUpdateNote(note.copy(memoText = memoText))
                                    Toast.makeText(context, "메모 저장됨", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("메모 저장")
                            }
                            TextButton(
                                onClick = {
                                    onUpdateNote(note.copy(memoText = memoText))
                                    val mdFile = LocalFileStorage.saveMarkdownFile(
                                        context = context,
                                        title = documentTitle,
                                        content = memoText
                                    )
                                    Toast.makeText(
                                        context,
                                        "마크다운 저장: ${mdFile.name}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    onUploadMarkdownToDrive?.invoke(mdFile)
                                }
                            ) {
                                Text(".md 파일 저장")
                            }
                        }
                    }
                } else {
                    // Tab 2: ScholarAgent AI Summary (summary.ko.md)
                    val safeDocId = remember(note.documentId) {
                        note.documentId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    }
                    val cachedSummary = remember(note.documentId, note.aiSummaryText) {
                        val dir = File(context.filesDir, "scholar_agent/$safeDocId")
                        val koFile = File(dir, "summary.ko.md")
                        val enFile = File(dir, "summary.en.md")
                        when {
                            koFile.exists() -> koFile.readText()
                            enFile.exists() -> enFile.readText()
                            note.aiSummaryText.isNotBlank() -> note.aiSummaryText
                            else -> ""
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (cachedSummary.isBlank()) {
                            Text(
                                "로드된 AI 요약이 없습니다.\nGoogle Drive에서 ScholarAgent 논문을 열거나 상단 AI 버튼에서 summary.ko.md를 불러와 주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF78350F)
                            )
                        } else {
                            MarkdownMathPreview(
                                markdown = cachedSummary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Bottom-right resize handle (40dp target for easy finger & stylus dragging)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .background(
                        color = Color(0xFFFEF3C7).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 16.dp)
                    )
                    .pointerInput(note.id) {
                        detectDragGestures(
                            onDragEnd = {
                                onUpdateNote(note.copy(widthDp = widthDp, heightDp = heightDp))
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val dxDp = with(density) { dragAmount.x.toDp().value }
                            val dyDp = with(density) { dragAmount.y.toDp().value }
                            widthDp = (widthDp + dxDp).coerceIn(280f, 1050f)
                            heightDp = (heightDp + dyDp).coerceIn(260f, 900f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "드래그하여 크기 조절",
                    tint = Color(0xFF92400E),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
