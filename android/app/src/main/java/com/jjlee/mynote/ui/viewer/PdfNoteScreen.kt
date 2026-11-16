package com.jjlee.mynote.ui.viewer

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Create
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jjlee.mynote.data.FavoritePenPreset
import com.jjlee.mynote.data.FolderSettings
import com.jjlee.mynote.data.SettingsDataStore
import com.jjlee.mynote.data.db.DocumentEntity
import com.jjlee.mynote.data.db.DocumentSourceType
import com.jjlee.mynote.data.db.NoteDao
import com.jjlee.mynote.data.db.PopupNoteEntity
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.data.db.StrokePoint
import com.jjlee.mynote.drive.DriveApiService
import com.jjlee.mynote.drive.DriveAuthManager
import com.jjlee.mynote.ink.BrushPresets
import com.jjlee.mynote.ink.BrushState
import com.jjlee.mynote.ink.BrushType
import com.jjlee.mynote.ink.GestureAction
import com.jjlee.mynote.ink.GestureTrigger
import com.jjlee.mynote.ink.StylusCanvasView
import java.util.UUID
import com.jjlee.mynote.pdf.PdfDocumentManager
import com.jjlee.mynote.pdf.PdfExporter
import com.jjlee.mynote.ui.popup.FloatingNotePopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfNoteScreen(
    document: DocumentEntity,
    localOriginalFile: File?,
    noteDao: NoteDao,
    folderSettings: FolderSettings,
    settingsDataStore: SettingsDataStore,
    driveApiService: DriveApiService,
    initialShowStrokes: Boolean = true,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val isNoteMode = document.sourceType == DocumentSourceType.NOTE.name

    val pdfManager = remember { PdfDocumentManager() }
    var pageCount by remember { mutableIntStateOf(if (isNoteMode) 1 else 0) }
    var currentPage by remember { mutableIntStateOf(document.lastPage.coerceAtLeast(0)) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderWidthPx by remember { mutableIntStateOf(2190) } // Lenovo 13" portrait native width

    var brushState by remember { mutableStateOf(BrushState()) }
    var strokesVisible by remember(document.id, initialShowStrokes) {
        mutableStateOf(initialShowStrokes)
    }
    var straightLineMode by remember { mutableStateOf(false) }
    var gestureOnlyMode by remember { mutableStateOf(false) }
    var paperStyle by remember { mutableStateOf(PaperStyle.LINED) }
    var showPenSlotDialog by remember { mutableStateOf(false) }
    var editingPenSlot by remember { mutableStateOf<FavoritePenPreset?>(null) }
    var showGestureConfigDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    var showPaperAgentPanel by remember { mutableStateOf(false) }

    // Floating toolbar offset & expand/collapse state
    var toolbarOffsetX by remember { mutableFloatStateOf(16f) }
    var toolbarOffsetY by remember { mutableFloatStateOf(12f) }
    var toolbarExpanded by remember { mutableStateOf(true) }

    // Zoom & pan state
    var zoomScale by remember(currentPage) { mutableFloatStateOf(1f) }
    var panX by remember(currentPage) { mutableFloatStateOf(0f) }
    var panY by remember(currentPage) { mutableFloatStateOf(0f) }

    val pageStrokes by noteDao
        .observePageStrokes(document.id, currentPage)
        .collectAsState(initial = emptyList())

    val popupNotes by noteDao
        .observePopupNotes(document.id)
        .collectAsState(initial = emptyList())
    val activePopup = popupNotes.firstOrNull()
    val popupStrokes by (activePopup?.let { noteDao.observePopupStrokes(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    val redoStack = remember(currentPage) { mutableStateListOf<StrokeEntity>() }

    // In-memory stroke buffer: flushed to Room DB on idle (1.5s), periodic (5s), page change, or exit
    val unsavedPageStrokes = remember(currentPage) { mutableStateListOf<StrokeEntity>() }
    var lastStrokeTimeMs by remember { mutableLongStateOf(0L) }
    var tempStrokeSeq by remember { mutableLongStateOf(-1L) }

    suspend fun flushUnsavedStrokes() {
        if (unsavedPageStrokes.isEmpty()) return
        val batch = unsavedPageStrokes.map { it.copy(id = 0) }
        unsavedPageStrokes.clear()
        noteDao.insertStrokes(batch)
    }

    // Idle flush: save 1.5s after last stroke
    LaunchedEffect(lastStrokeTimeMs) {
        if (lastStrokeTimeMs > 0L && unsavedPageStrokes.isNotEmpty()) {
            delay(1500L)
            flushUnsavedStrokes()
        }
    }

    // Periodic flush: save every 5s during continuous writing
    LaunchedEffect(document.id, currentPage) {
        while (true) {
            delay(5000L)
            flushUnsavedStrokes()
        }
    }

    // Flush remaining strokes when switching pages or leaving screen
    DisposableEffect(document.id, currentPage) {
        onDispose {
            if (unsavedPageStrokes.isNotEmpty()) {
                val batch = unsavedPageStrokes.map { it.copy(id = 0) }
                unsavedPageStrokes.clear()
                CoroutineScope(Dispatchers.IO).launch {
                    noteDao.insertStrokes(batch)
                }
            }
        }
    }

    val combinedPageStrokes = remember(pageStrokes, unsavedPageStrokes.toList()) {
        pageStrokes + unsavedPageStrokes
    }

    DisposableEffect(localOriginalFile) {
        onDispose { pdfManager.close() }
    }

    LaunchedEffect(localOriginalFile, isNoteMode) {
        if (isNoteMode) {
            val all = noteDao.getAllDocumentPageStrokes(document.id)
            val maxPageIdx = all.maxOfOrNull { it.pageIndex } ?: 0
            pageCount = maxOf(1, maxPageIdx + 1, document.lastPage + 1)
        } else if (localOriginalFile != null) {
            val count = pdfManager.open(localOriginalFile)
            pageCount = count
            if (currentPage >= count && count > 0) {
                currentPage = count - 1
            }
        }
    }

    LaunchedEffect(currentPage, pageCount, isNoteMode, renderWidthPx) {
        if (pageCount > 0) {
            noteDao.updateLastPage(document.id, currentPage)
            if (!isNoteMode) {
                pageBitmap = pdfManager.renderPage(currentPage, targetWidthPx = renderWidthPx)
            }
        }
    }

    fun performUndo() {
        val unsavedLast = unsavedPageStrokes.removeLastOrNull()
        if (unsavedLast != null) {
            redoStack.add(unsavedLast)
            return
        }
        val last = pageStrokes.lastOrNull() ?: return
        scope.launch {
            redoStack.add(last)
            noteDao.deleteStrokeById(last.id)
        }
    }

    fun performRedo() {
        val restored = redoStack.removeLastOrNull() ?: return
        val tempId = tempStrokeSeq--
        unsavedPageStrokes.add(restored.copy(id = tempId))
        lastStrokeTimeMs = System.currentTimeMillis()
    }

    fun openMemoPopup(toggleIfVisible: Boolean = false) {
        if (activePopup == null) {
            scope.launch {
                noteDao.insertPopupNote(
                    PopupNoteEntity(documentId = document.id, title = "메모장")
                )
                popupVisible = true
            }
        } else {
            if (activePopup.isMinimized) {
                scope.launch { noteDao.updatePopupNote(activePopup.copy(isMinimized = false)) }
                popupVisible = true
            } else {
                popupVisible = if (toggleIfVisible) !popupVisible else true
            }
        }
    }

    fun executeGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.UNDO -> performUndo()
            GestureAction.REDO -> performRedo()
            GestureAction.OPEN_MEMO -> openMemoPopup(toggleIfVisible = false)
            GestureAction.TOGGLE_GESTURE_MODE -> gestureOnlyMode = !gestureOnlyMode
            GestureAction.TOGGLE_HIGHLIGHTER -> {
                brushState = if (brushState.type == BrushType.HIGHLIGHTER) {
                    brushState.copy(type = BrushType.PEN, alpha = 1f)
                } else {
                    brushState.copy(
                        type = BrushType.HIGHLIGHTER,
                        alpha = 0.35f,
                        colorArgb = android.graphics.Color.parseColor("#FACC15")
                    )
                }
                gestureOnlyMode = false
            }
            GestureAction.TOGGLE_STRAIGHT_LINE -> {
                straightLineMode = !straightLineMode
                gestureOnlyMode = false
            }
            GestureAction.ERASE, GestureAction.NONE -> Unit
        }
    }

    fun uploadLocalTextFileToDrive(file: File) {
        scope.launch {
            val token = DriveAuthManager.getActiveAccessToken(context, folderSettings)
            if (token == null) {
                Toast.makeText(
                    context,
                    "로컬에 저장되었습니다 (Drive 업로드를 원하시면 홈 화면 설정에서 Google Drive를 연결하세요)",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val targetFolderId = folderSettings.exportFolderId ?: folderSettings.sourceFolderId
            runCatching {
                driveApiService.uploadMarkdownOrLogFile(
                    accessToken = token,
                    folderId = targetFolderId,
                    fileName = file.name,
                    localFile = file
                )
            }.onSuccess { uploaded ->
                Toast.makeText(context, "Drive 업로드 완료: ${uploaded.name}", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "로컬 저장됨 (Drive 업로드 실패: ${e.message})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportAnnotated(uploadToDrive: Boolean) {
        if (isExporting) return
        isExporting = true
        scope.launch {
            runCatching {
                flushUnsavedStrokes()
                val allStrokes = noteDao.getAllDocumentPageStrokes(document.id)
                val grouped = allStrokes.groupBy { it.pageIndex }
                val exportedFile = if (isNoteMode || localOriginalFile == null) {
                    PdfExporter.exportNoteAsPdf(
                        context = context,
                        noteTitle = document.fileName,
                        pageCount = pageCount,
                        paperStyle = paperStyle,
                        strokesByPage = grouped,
                        density = density
                    )
                } else {
                    PdfExporter.exportAnnotatedPdf(
                        context = context,
                        originalPdfFile = localOriginalFile,
                        originalFileName = document.fileName,
                        strokesByPage = grouped,
                        density = density
                    )
                }

                var driveId: String? = document.exportedDriveFileId
                if (uploadToDrive) {
                    val token = DriveAuthManager.getActiveAccessToken(context, folderSettings)
                        ?: error("Google Drive에 먼저 연결(로그인 또는 토큰 입력)해 주세요.")
                    val targetFolderId = folderSettings.exportFolderId
                        ?: error("수정본 저장용 Google Drive 폴더를 먼저 지정해 주세요.")

                    val uploaded = driveApiService.uploadOrUpdateAnnotatedPdf(
                        accessToken = token,
                        exportFolderId = targetFolderId,
                        fileName = exportedFile.name,
                        localPdfFile = exportedFile,
                        existingFileId = document.exportedDriveFileId
                    )
                    driveId = uploaded.id
                }

                noteDao.updateExportMetadata(
                    documentId = document.id,
                    driveFileId = driveId,
                    localPath = exportedFile.absolutePath
                )
                exportedFile
            }.onSuccess { outFile ->
                val msg = if (uploadToDrive) {
                    "수정본 저장 및 Drive 업로드 완료: ${outFile.name}"
                } else {
                    "수정본 분리 저장 완료: ${outFile.absolutePath}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(context, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isExporting = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE2E8F0))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Main Full-Width Paper / PDF Canvas Area
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val containerWidthPx = constraints.maxWidth.coerceAtLeast(2190)
            LaunchedEffect(containerWidthPx) {
                renderWidthPx = containerWidthPx
            }

            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                zoomScale = (zoomScale * zoomChange).coerceIn(1f, 4f)
                if (zoomScale > 1.01f) {
                    panX += panChange.x
                    panY += panChange.y
                } else {
                    panX = 0f
                    panY = 0f
                }
            }

            // Finger horizontal swipe for prev/next page & left-edge swipe back
            var dragStartX by remember(currentPage) { mutableFloatStateOf(0f) }
            var pageSwipeDrag by remember(currentPage) { mutableFloatStateOf(0f) }
            val leftEdgeZonePx = 80f * density
            val pageSwipeThresholdPx = 55f * density

            val bmp = pageBitmap
            if (isNoteMode || bmp != null) {
                val aspect = if (isNoteMode) {
                    595f / 842f
                } else {
                    bmp!!.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(folderSettings.gestureMappings) {
                            // Observe multi-finger touch gestures without blocking scroll/zoom
                            val swipeUpThresholdPx = 75f * density
                            val pinchTolerancePx = 65f * density
                            awaitEachGesture {
                                var maxTouchPointers = 0
                                var downTimeMs = 0L
                                var twoFingerStartY = 0f
                                var twoFingerStartSpan = 0f
                                var twoFingerSwipeFired = false

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val touchChanges = event.changes.filter {
                                        it.type == PointerType.Touch
                                    }
                                    if (touchChanges.isEmpty()) break

                                    val pressed = touchChanges.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        val duration = System.currentTimeMillis() - downTimeMs
                                        if (!twoFingerSwipeFired && duration < 280L) {
                                            when (maxTouchPointers) {
                                                2 -> folderSettings.gestureMappings[GestureTrigger.TWO_FINGER_TAP]
                                                    ?.let(::executeGestureAction)
                                                3 -> folderSettings.gestureMappings[GestureTrigger.THREE_FINGER_TAP]
                                                    ?.let(::executeGestureAction)
                                            }
                                        }
                                        break
                                    }

                                    if (downTimeMs == 0L) {
                                        downTimeMs = System.currentTimeMillis()
                                    }
                                    if (pressed.size > maxTouchPointers) {
                                        maxTouchPointers = pressed.size
                                        if (pressed.size == 2) {
                                            twoFingerStartY = (pressed[0].position.y + pressed[1].position.y) / 2f
                                            twoFingerStartSpan = kotlin.math.hypot(
                                                pressed[1].position.x - pressed[0].position.x,
                                                pressed[1].position.y - pressed[0].position.y
                                            )
                                        }
                                    }

                                    if (pressed.size == 2 && !twoFingerSwipeFired) {
                                        val avgY = (pressed[0].position.y + pressed[1].position.y) / 2f
                                        val span = kotlin.math.hypot(
                                            pressed[1].position.x - pressed[0].position.x,
                                            pressed[1].position.y - pressed[0].position.y
                                        )
                                        if (avgY - twoFingerStartY < -swipeUpThresholdPx &&
                                            kotlin.math.abs(span - twoFingerStartSpan) < pinchTolerancePx
                                        ) {
                                            twoFingerSwipeFired = true
                                            folderSettings.gestureMappings[GestureTrigger.TWO_FINGER_SWIPE_UP]
                                                ?.let(::executeGestureAction)
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(currentPage, pageCount, zoomScale) {
                            if (zoomScale <= 1.05f) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        dragStartX = offset.x
                                        pageSwipeDrag = 0f
                                    },
                                    onDragEnd = {
                                        if (dragStartX <= leftEdgeZonePx && pageSwipeDrag > pageSwipeThresholdPx) {
                                            onBack()
                                        } else if (pageSwipeDrag < -pageSwipeThresholdPx && currentPage < pageCount - 1) {
                                            currentPage++
                                        } else if (pageSwipeDrag > pageSwipeThresholdPx && currentPage > 0) {
                                            currentPage--
                                        }
                                        pageSwipeDrag = 0f
                                    },
                                    onDragCancel = {
                                        pageSwipeDrag = 0f
                                    }
                                ) { _, dragAmount ->
                                    pageSwipeDrag += dragAmount
                                }
                            }
                        }
                        .transformable(
                            state = transformState,
                            canPan = { zoomScale > 1.01f }
                        )
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = panX
                            translationY = panY
                        }
                        .verticalScroll(rememberScrollState(), enabled = zoomScale <= 1.05f),
                    contentAlignment = Alignment.Center
                ) {
                    // Exact full-width paper fit (ideal for Lenovo 13" 2190px portrait width)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .background(Color.White)
                    ) {
                        if (isNoteMode) {
                            PaperBackground(style = paperStyle, modifier = Modifier.fillMaxSize())
                        } else if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "PDF Page ${currentPage + 1}",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        AndroidView(
                            factory = { ctx ->
                                StylusCanvasView(ctx).apply {
                                    this.brushState = brushState
                                    this.strokesVisible = strokesVisible
                                    this.straightLineMode = straightLineMode
                                    this.gestureOnlyMode = gestureOnlyMode
                                    this.gestureMappings = folderSettings.gestureMappings
                                    this.onStrokeCompleted = { usedBrush, pts ->
                                        redoStack.clear()
                                        val tempId = tempStrokeSeq--
                                        unsavedPageStrokes.add(
                                            StrokeEntity(
                                                id = tempId,
                                                documentId = document.id,
                                                pageIndex = currentPage,
                                                popupNoteId = null,
                                                brushType = usedBrush.type.name,
                                                colorArgb = usedBrush.colorArgb,
                                                baseWidthDp = usedBrush.widthDp,
                                                alpha = usedBrush.effectiveAlpha(),
                                                pointsJson = StrokePoint.encodeList(pts)
                                            )
                                        )
                                        lastStrokeTimeMs = System.currentTimeMillis()
                                    }
                                    this.onEraseIdsRequested = { ids ->
                                        val unsavedIds = ids.filter { it < 0 }.toSet()
                                        if (unsavedIds.isNotEmpty()) {
                                            unsavedPageStrokes.removeAll { it.id in unsavedIds }
                                        }
                                        val dbIds = ids.filter { it > 0 }
                                        if (dbIds.isNotEmpty()) {
                                            scope.launch {
                                                noteDao.deleteStrokesByIds(dbIds)
                                            }
                                        }
                                    }
                                    this.onGestureTriggered = { trigger ->
                                        val action = folderSettings.gestureMappings[trigger]
                                        if (action != null) {
                                            executeGestureAction(action)
                                        }
                                    }
                                }
                            },
                            update = { view ->
                                view.brushState = brushState
                                view.strokesVisible = strokesVisible
                                view.straightLineMode = straightLineMode
                                view.gestureOnlyMode = gestureOnlyMode
                                view.gestureMappings = folderSettings.gestureMappings
                                view.onGestureTriggered = { trigger ->
                                    val action = folderSettings.gestureMappings[trigger]
                                    if (action != null) {
                                        executeGestureAction(action)
                                    }
                                }
                                view.setStrokes(combinedPageStrokes)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                CircularProgressIndicator()
            }
        }

        // 2. Unified Single-Row Floating Pill Toolbar (sits cleanly below status bar)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(toolbarOffsetX.roundToInt(), toolbarOffsetY.roundToInt()) }
                .widthIn(max = 1040.dp)
                .padding(end = 16.dp)
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 10.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Drag handle for moving floating toolbar
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = "툴바 이동",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier
                            .size(26.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    toolbarOffsetX = (toolbarOffsetX + dragAmount.x).coerceAtLeast(0f)
                                    toolbarOffsetY = (toolbarOffsetY + dragAmount.y).coerceAtLeast(0f)
                                }
                            }
                    )

                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", modifier = Modifier.size(20.dp))
                    }

                    if (!toolbarExpanded) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(brushState.colorArgb))
                                .border(1.dp, Color(0xFFCBD5E1), CircleShape)
                        )
                    }

                    AnimatedVisibility(visible = toolbarExpanded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VerticalDivider(modifier = Modifier.height(22.dp), color = Color(0xFFE2E8F0))

                            // Flexcil-style Favorite Pen Slots + '+' Add Button (all in one row)
                    folderSettings.favoritePens.forEach { preset ->
                        val isSelected = !gestureOnlyMode &&
                            brushState.type == preset.brushType &&
                            brushState.colorArgb == preset.colorArgb &&
                            kotlin.math.abs(brushState.widthDp - preset.widthDp) < 0.2f
                        val icon = when (preset.brushType) {
                            BrushType.HIGHLIGHTER, BrushType.TRANSLUCENT_PEN -> Icons.Default.BorderColor
                            BrushType.FOUNTAIN_PEN -> Icons.Default.Create
                            else -> Icons.Default.Edit
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) Color(preset.colorArgb).copy(alpha = 0.16f) else Color(0xFFF8FAFC),
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(preset.colorArgb) else Color(0xFFE2E8F0),
                                    shape = CircleShape
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (isSelected) {
                                            editingPenSlot = preset
                                            showPenSlotDialog = true
                                        } else {
                                            brushState = preset.toBrushState()
                                            gestureOnlyMode = false
                                        }
                                    },
                                    onLongClick = {
                                        editingPenSlot = preset
                                        showPenSlotDialog = true
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = preset.label,
                                    tint = Color(preset.colorArgb),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${preset.widthDp.toInt()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF334155)
                                )
                            }
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFEFF6FF),
                        modifier = Modifier
                            .size(30.dp)
                            .border(1.dp, Color(0xFFBFDBFE), CircleShape)
                            .clickable {
                                editingPenSlot = null
                                showPenSlotDialog = true
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "펜 슬롯 추가",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    VerticalDivider(modifier = Modifier.height(22.dp), color = Color(0xFFE2E8F0))

                    // Tools: Eraser, Straight Line, Gesture Mode
                    FilterChip(
                        selected = !gestureOnlyMode && brushState.type == BrushType.ERASER,
                        onClick = {
                            brushState = brushState.copy(type = BrushType.ERASER)
                            gestureOnlyMode = false
                        },
                        label = { Text("지우개") },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp)) }
                    )

                    FilterChip(
                        selected = straightLineMode,
                        onClick = {
                            straightLineMode = !straightLineMode
                            gestureOnlyMode = false
                        },
                        label = { Text("직선") },
                        leadingIcon = { Icon(Icons.Default.HorizontalRule, null, Modifier.size(16.dp)) }
                    )

                    FilterChip(
                        selected = gestureOnlyMode,
                        onClick = { gestureOnlyMode = !gestureOnlyMode },
                        label = { Text(if (gestureOnlyMode) "제스처" else "필기") },
                        leadingIcon = { Icon(Icons.Default.PanTool, null, Modifier.size(16.dp)) }
                    )

                    if (isNoteMode) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val entries = PaperStyle.entries
                                paperStyle = entries[(entries.indexOf(paperStyle) + 1) % entries.size]
                            },
                            label = { Text(paperStyle.label) }
                        )
                    }

                    VerticalDivider(modifier = Modifier.height(22.dp), color = Color(0xFFE2E8F0))

                    // Actions: Undo, Redo, Original View, Memo Popup, Save, Drive Upload
                    IconButton(onClick = { performUndo() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "실행 취소", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { performRedo() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { strokesVisible = !strokesVisible },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            if (strokesVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (strokesVisible) "원본 보기" else "필기 보기",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    FilterChip(
                        selected = popupVisible,
                        onClick = {
                            openMemoPopup(toggleIfVisible = true)
                        },
                        label = { Text("노트") },
                        leadingIcon = { Icon(Icons.Default.EditNote, null, Modifier.size(16.dp)) }
                    )

                    FilterChip(
                        selected = showPaperAgentPanel,
                        onClick = { showPaperAgentPanel = true },
                        label = { Text("AI") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                    )

                    IconButton(
                        onClick = { exportAnnotated(uploadToDrive = false) },
                        enabled = !isExporting,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "수정본 로컬 저장", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { exportAnnotated(uploadToDrive = true) },
                        enabled = !isExporting,
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = "수정본 Drive 업로드", modifier = Modifier.size(20.dp))
                        }
                    }
                        }
                    }

                    IconButton(
                        onClick = { toolbarExpanded = !toolbarExpanded },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (toolbarExpanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = if (toolbarExpanded) "필기도구 줄이기" else "필기도구 펼치기",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Expandable Palette: Gesture Palette when in Gesture Mode
                if (toolbarExpanded && gestureOnlyMode) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "제스처 옵션",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FilterChip(
                            selected = false,
                            onClick = { showGestureConfigDialog = true },
                            label = { Text("제스처 매핑 설정") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            },
                            label = { Text("배율 100%") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { if (currentPage > 0) currentPage-- },
                            enabled = currentPage > 0,
                            label = { Text("이전 페이지") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { if (currentPage < pageCount - 1) currentPage++ },
                            enabled = currentPage < pageCount - 1,
                            label = { Text("다음 페이지") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = onBack,
                            label = { Text("문서 목록") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { gestureOnlyMode = false },
                            label = { Text("필기 복귀") }
                        )
                    }
                }
            }
        }

        // 4. Floating Note Popup Overlay
        if (showPaperAgentPanel) {
            val docTitle = document.fileName.substringBeforeLast('.').ifBlank { document.fileName }
            PaperAgentPanelDialog(
                documentId = document.id,
                documentTitle = docTitle,
                localOriginalFile = localOriginalFile,
                onJumpToPage = { targetPageIdx ->
                    if (targetPageIdx in 0 until pageCount) {
                        currentPage = targetPageIdx
                    }
                },
                onDismiss = { showPaperAgentPanel = false }
            )
        }

        if (popupVisible && activePopup != null) {
            val docTitle = document.fileName.substringBeforeLast('.').ifBlank { document.fileName }
            FloatingNotePopup(
                note = activePopup,
                documentTitle = docTitle,
                strokes = popupStrokes,
                brushState = brushState,
                onUploadMarkdownToDrive = { f -> uploadLocalTextFileToDrive(f) },
                onNavigateToPage = { targetPageIdx ->
                    if (targetPageIdx in 0 until pageCount) {
                        currentPage = targetPageIdx
                    }
                },
                onUpdateNote = { updated ->
                    scope.launch { noteDao.updatePopupNote(updated) }
                },
                onClosePopup = { popupVisible = false },
                onStrokeCompleted = { usedBrush, pts ->
                    scope.launch {
                        noteDao.insertStroke(
                            StrokeEntity(
                                documentId = document.id,
                                pageIndex = -1,
                                popupNoteId = activePopup.id,
                                brushType = usedBrush.type.name,
                                colorArgb = usedBrush.colorArgb,
                                baseWidthDp = usedBrush.widthDp,
                                alpha = usedBrush.effectiveAlpha(),
                                pointsJson = StrokePoint.encodeList(pts)
                            )
                        )
                    }
                },
                onEraseIds = { ids ->
                    scope.launch { noteDao.deleteStrokesByIds(ids) }
                },
                onUndo = {
                    popupStrokes.lastOrNull()?.let { last ->
                        scope.launch { noteDao.deleteStrokeById(last.id) }
                    }
                },
                onRedo = {},
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // 5. Bottom Compact Page Navigation Pill
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "이전 페이지")
                }
                Text(
                    text = if (pageCount > 0) "${currentPage + 1} / $pageCount" else "0 / 0",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                IconButton(
                    onClick = { if (currentPage < pageCount - 1) currentPage++ },
                    enabled = currentPage < pageCount - 1,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "다음 페이지")
                }
                if (isNoteMode) {
                    IconButton(
                        onClick = {
                            pageCount++
                            currentPage = pageCount - 1
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "페이지 추가")
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        gestureOnlyMode -> "제스처"
                        strokesVisible -> "필기 중"
                        else -> "원본"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 6. Add / Edit Pen Slot Picker Dialog
        if (showPenSlotDialog) {
            PenSlotDialog(
                editingPreset = editingPenSlot,
                initialBrush = brushState,
                canDelete = folderSettings.favoritePens.size > 1,
                onSaveSlot = { preset ->
                    scope.launch {
                        val updated = if (editingPenSlot != null) {
                            folderSettings.favoritePens.map { if (it.id == preset.id) preset else it }
                        } else {
                            folderSettings.favoritePens + preset
                        }
                        settingsDataStore.saveFavoritePens(updated)
                        brushState = preset.toBrushState()
                        gestureOnlyMode = false
                        showPenSlotDialog = false
                        editingPenSlot = null
                    }
                },
                onDeleteSlot = { target ->
                    scope.launch {
                        val remaining = folderSettings.favoritePens.filterNot { it.id == target.id }
                        if (remaining.isNotEmpty()) {
                            settingsDataStore.saveFavoritePens(remaining)
                            brushState = remaining.first().toBrushState()
                            Toast.makeText(context, "펜 슬롯이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                        showPenSlotDialog = false
                        editingPenSlot = null
                    }
                },
                onDismiss = {
                    showPenSlotDialog = false
                    editingPenSlot = null
                }
            )
        }

        // 7. Gesture -> Action Mapping Configuration Dialog
        if (showGestureConfigDialog) {
            GestureMappingConfigDialog(
                mappings = folderSettings.gestureMappings,
                onChangeMapping = { trigger, action ->
                    scope.launch {
                        settingsDataStore.setGestureMapping(trigger, action)
                    }
                },
                onDismiss = { showGestureConfigDialog = false }
            )
        }
    }
}

@Composable
private fun GestureMappingConfigDialog(
    mappings: Map<GestureTrigger, GestureAction>,
    onChangeMapping: (GestureTrigger, GestureAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("레노버 펜 & 터치 제스처 설정") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "행동 버튼을 눌러 원하는 동작으로 변경하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
                GestureTrigger.entries.forEach { trigger ->
                    val currentAction = mappings[trigger] ?: GestureAction.NONE
                    var menuExpanded by remember(trigger) { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(trigger.label, style = MaterialTheme.typography.labelLarge)
                            Text(
                                trigger.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        Box {
                            FilterChip(
                                selected = true,
                                onClick = { menuExpanded = true },
                                label = { Text(currentAction.label) }
                            )
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                GestureAction.entries.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        onClick = {
                                            onChangeMapping(trigger, action)
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("완료")
            }
        }
    )
}

@Composable
private fun PenSlotDialog(
    editingPreset: FavoritePenPreset?,
    initialBrush: BrushState,
    canDelete: Boolean,
    onSaveSlot: (FavoritePenPreset) -> Unit,
    onDeleteSlot: (FavoritePenPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember(editingPreset?.id) {
        mutableStateOf(
            editingPreset?.brushType
                ?: if (initialBrush.type == BrushType.ERASER) BrushType.PEN else initialBrush.type
        )
    }
    var selectedColor by remember(editingPreset?.id) {
        mutableIntStateOf(editingPreset?.colorArgb ?: initialBrush.colorArgb)
    }
    var selectedWidth by remember(editingPreset?.id) {
        mutableFloatStateOf(editingPreset?.widthDp ?: initialBrush.widthDp)
    }
    var selectedAlpha by remember(editingPreset?.id) {
        mutableFloatStateOf(
            editingPreset?.alpha ?: if (selectedType == BrushType.HIGHLIGHTER) 0.35f else 1.0f
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingPreset != null) "펜 슬롯 편집" else "새 펜 슬롯 추가") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("펜 종류", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == BrushType.PEN,
                        onClick = {
                            selectedType = BrushType.PEN
                            selectedAlpha = 1.0f
                        },
                        label = { Text("펜") },
                        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = selectedType == BrushType.HIGHLIGHTER,
                        onClick = {
                            selectedType = BrushType.HIGHLIGHTER
                            if (selectedAlpha >= 0.9f) selectedAlpha = 0.35f
                        },
                        label = { Text("형광펜") },
                        leadingIcon = { Icon(Icons.Default.BorderColor, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = selectedType == BrushType.FOUNTAIN_PEN,
                        onClick = {
                            selectedType = BrushType.FOUNTAIN_PEN
                            selectedAlpha = 1.0f
                        },
                        label = { Text("만년필") },
                        leadingIcon = { Icon(Icons.Default.Create, null, Modifier.size(16.dp)) }
                    )
                }

                Text("색상", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BrushPresets.defaultColors.forEach { argb ->
                        val isSelected = selectedColor == argb
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) Color.Black else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = argb }
                        )
                    }
                }

                Text("굵기: ${selectedWidth.toInt()}pt", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = selectedWidth,
                    onValueChange = { selectedWidth = it },
                    valueRange = 1.5f..12f
                )

                if (selectedType == BrushType.HIGHLIGHTER) {
                    Text(
                        "투명도: ${(selectedAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = selectedAlpha,
                        onValueChange = { selectedAlpha = it },
                        valueRange = 0.15f..0.85f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val label = when (selectedType) {
                        BrushType.HIGHLIGHTER -> "형광펜"
                        BrushType.FOUNTAIN_PEN -> "만년필"
                        else -> "펜"
                    }
                    onSaveSlot(
                        FavoritePenPreset(
                            id = editingPreset?.id ?: "pen_${UUID.randomUUID()}",
                            label = label,
                            brushType = selectedType,
                            colorArgb = selectedColor,
                            widthDp = selectedWidth,
                            alpha = if (selectedType == BrushType.HIGHLIGHTER) selectedAlpha else 1.0f
                        )
                    )
                }
            ) {
                Text(if (editingPreset != null) "저장" else "추가")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editingPreset != null && canDelete) {
                    TextButton(onClick = { onDeleteSlot(editingPreset) }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("취소")
                }
            }
        }
    )
}
