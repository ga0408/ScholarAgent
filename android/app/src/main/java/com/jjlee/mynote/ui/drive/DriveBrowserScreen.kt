package com.jjlee.mynote.ui.drive

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.jjlee.mynote.data.FolderSettings
import com.jjlee.mynote.data.LocalFileStorage
import com.jjlee.mynote.data.SettingsDataStore
import com.jjlee.mynote.data.db.DocumentEntity
import com.jjlee.mynote.data.db.DocumentSourceType
import com.jjlee.mynote.data.db.NoteDao
import com.jjlee.mynote.drive.DriveApiService
import com.jjlee.mynote.drive.DriveAuthManager
import com.jjlee.mynote.drive.DriveItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private enum class FolderPickTarget {
    SOURCE,
    EXPORT
}

private data class DriveBrowserEntry(
    val item: DriveItem,
    val isPaperBundle: Boolean = false,
    val highlightedPdf: DriveItem? = null,
    val originalPdf: DriveItem? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowserScreen(
    noteDao: NoteDao,
    settingsDataStore: SettingsDataStore,
    driveApiService: DriveApiService,
    canGoForward: Boolean = false,
    onForwardToLastDocument: () -> Unit = {},
    onOpenDocument: (DocumentEntity, File?, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folderSettings by settingsDataStore.folderSettings.collectAsState(initial = FolderSettings())
    val recentDocs by noteDao.observeDocuments().collectAsState(initial = emptyList())

    var signedInEmail by remember {
        mutableStateOf(DriveAuthManager.getLastSignedInAccount(context)?.email)
    }
    val hasManualToken = !folderSettings.manualDriveToken.isNullOrBlank()
    val isDriveConnected = signedInEmail != null || hasManualToken

    var isLoading by remember { mutableStateOf(false) }

    var sourceDriveEntries by remember { mutableStateOf<List<DriveBrowserEntry>>(emptyList()) }
    var driveFolderStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var folderPickTarget by remember { mutableStateOf<FolderPickTarget?>(null) }
    var signInErrorDetail by remember { mutableStateOf<String?>(null) }
    var showTokenInputDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedHomeTab by remember { mutableIntStateOf(0) } // 0 = PDF, 1 = 노트
    var noteToDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    suspend fun refreshDriveLists() {
        val token = DriveAuthManager.getActiveAccessToken(context, folderSettings) ?: return
        val targetFolderId = driveFolderStack.lastOrNull()?.first ?: folderSettings.sourceFolderId
        isLoading = true
        runCatching {
            val rawChildren = targetFolderId?.let {
                driveApiService.listChildrenInFolder(token, it)
            } ?: emptyList()
            val hasHighlighted = rawChildren.any { it.name.equals("highlighted.pdf", ignoreCase = true) }
            val filtered = if (hasHighlighted) {
                rawChildren.filterNot { it.name.equals("original.pdf", ignoreCase = true) }
            } else {
                rawChildren
            }
            sourceDriveEntries = coroutineScope {
                filtered.map { child ->
                    async {
                        if (child.mimeType == "application/vnd.google-apps.folder") {
                            val pdfs = runCatching {
                                driveApiService.listPdfsInFolder(token, child.id)
                            }.getOrDefault(emptyList())
                            val hl = pdfs.firstOrNull { it.name.equals("highlighted.pdf", ignoreCase = true) }
                            val orig = pdfs.firstOrNull { it.name.equals("original.pdf", ignoreCase = true) }
                            if (hl != null || orig != null) {
                                DriveBrowserEntry(
                                    item = child,
                                    isPaperBundle = true,
                                    highlightedPdf = hl,
                                    originalPdf = orig
                                )
                            } else {
                                DriveBrowserEntry(item = child)
                            }
                        } else {
                            DriveBrowserEntry(item = child)
                        }
                    }
                }.awaitAll()
            }
        }.onFailure { e ->
            Toast.makeText(context, "Drive 조회 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        isLoading = false
    }

    LaunchedEffect(folderSettings.sourceFolderId) {
        driveFolderStack = emptyList()
    }

    LaunchedEffect(
        isDriveConnected,
        folderSettings.manualDriveToken,
        folderSettings.sourceFolderId,
        folderSettings.exportFolderId
    ) {
        if (isDriveConnected) {
            refreshDriveLists()
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching {
            val account = task.getResult(ApiException::class.java)
            signedInEmail = account.email
        }.onFailure { e ->
            val code = (e as? ApiException)?.statusCode
            val reason = when (code) {
                10 -> "DEVELOPER_ERROR (10): Google Cloud Console에 패키지명(com.jjlee.mynote)과 디버그 SHA-1(${DriveAuthManager.DEBUG_SHA1})이 등록되지 않았습니다."
                12500 -> "SIGN_IN_FAILED (12500): 기기의 Google Play 서비스 또는 계정 설정을 확인하세요."
                12501 -> "로그인이 취소되었습니다 (12501)."
                else -> "오류 코드: ${code ?: "알 수 없음"} (${e.message})"
            }
            signInErrorDetail = reason
        }
    }

    val localPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            val fileName = resolveFileName(context, uri) ?: "document.pdf"
            val docId = "local_${UUID.nameUUIDFromBytes(uri.toString().toByteArray())}"
            val localCache = LocalFileStorage.originalFileFor(context, docId, fileName)
            LocalFileStorage.copyUriToOriginalCache(context, uri, localCache)
            val entity = noteDao.getDocument(docId) ?: DocumentEntity(
                id = docId,
                sourceType = DocumentSourceType.LOCAL.name,
                sourceIdOrUri = uri.toString(),
                fileName = fileName
            )
            noteDao.upsertDocument(entity)
            onOpenDocument(entity, localCache, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MyNote") },
                actions = {
                    if (canGoForward) {
                        IconButton(onClick = onForwardToLastDocument) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "열던 문서로 이동")
                        }
                    }
                    if (isDriveConnected) {
                        IconButton(onClick = { scope.launch { refreshDriveLists() } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "동기화 새로고침")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
            )
        }
    ) { innerPadding ->
        val density = LocalDensity.current
        val pdfDocs = remember(recentDocs) {
            recentDocs.filter { it.sourceType != DocumentSourceType.NOTE.name }
        }
        val noteDocs = remember(recentDocs) {
            recentDocs.filter { it.sourceType == DocumentSourceType.NOTE.name }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedHomeTab) {
                    Tab(
                        selected = selectedHomeTab == 0,
                        onClick = { selectedHomeTab = 0 },
                        text = { Text("PDF") },
                        icon = { Icon(Icons.Default.Description, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedHomeTab == 1,
                        onClick = { selectedHomeTab = 1 },
                        text = { Text("노트") },
                        icon = { Icon(Icons.Default.EditNote, contentDescription = null) }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (selectedHomeTab == 0) {
                        // PDF Tab
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { localPdfLauncher.launch(arrayOf("application/pdf")) }
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("기기 PDF 열기")
                            }
                            Text(
                                text = if (isDriveConnected) {
                                    "Drive 동기화: ${signedInEmail ?: "OAuth 토큰"}"
                                } else {
                                    "우측 상단 설정에서 Google Drive 동기화 가능"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (isDriveConnected) {
                            Spacer(Modifier.height(12.dp))
                            FolderCard(
                                title = "Google Drive 폴더",
                                folderName = folderSettings.sourceFolderName ?: "미지정",
                                onSelect = { folderPickTarget = FolderPickTarget.SOURCE },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        if (isLoading) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (sourceDriveEntries.isNotEmpty() || driveFolderStack.isNotEmpty()) {
                                item {
                                    val pathLabel = if (driveFolderStack.isEmpty()) {
                                        "Google Drive 논문 / 문서"
                                    } else {
                                        "Google Drive / ${driveFolderStack.joinToString(" / ") { it.second }}"
                                    }
                                    Text(
                                        pathLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                if (driveFolderStack.isNotEmpty()) {
                                    item(key = "drive_parent_up") {
                                        ListItem(
                                            headlineContent = { Text(".. (상위 폴더)") },
                                            leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                                            modifier = Modifier.clickable {
                                                driveFolderStack = driveFolderStack.dropLast(1)
                                                scope.launch { refreshDriveLists() }
                                            }
                                        )
                                    }
                                }
                                items(sourceDriveEntries, key = { it.item.id }) { entry ->
                                    val item = entry.item
                                    if (entry.isPaperBundle) {
                                        val cleanBasePdf = entry.originalPdf ?: entry.highlightedPdf!!
                                        val originalTarget = entry.originalPdf ?: cleanBasePdf
                                        ListItem(
                                            headlineContent = { Text(item.name) },
                                            supportingContent = {
                                                Text(
                                                    if (entry.highlightedPdf != null) {
                                                        "탭하여 하이라이트+필기 열기 • ${cleanBasePdf.sizeBytes / 1024} KB"
                                                    } else {
                                                        "탭하여 열기 • ${cleanBasePdf.sizeBytes / 1024} KB"
                                                    }
                                                )
                                            },
                                            leadingContent = { Icon(Icons.Default.Description, null) },
                                            trailingContent = {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch {
                                                            openDrivePdf(
                                                                context,
                                                                originalTarget.copy(name = "${item.name} (원본).pdf"),
                                                                folderSettings,
                                                                noteDao,
                                                                driveApiService,
                                                                showStrokes = false,
                                                                onOpenDocument = onOpenDocument
                                                            )
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Visibility,
                                                        contentDescription = "원본 보기",
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("원본 보기")
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                scope.launch {
                                                    openDrivePdf(
                                                        context,
                                                        cleanBasePdf.copy(name = "${item.name}.pdf"),
                                                        folderSettings,
                                                        noteDao,
                                                        driveApiService,
                                                        showStrokes = true,
                                                        onOpenDocument = onOpenDocument
                                                    )
                                                }
                                            }
                                        )
                                    } else if (item.mimeType == "application/vnd.google-apps.folder") {
                                        ListItem(
                                            headlineContent = { Text(item.name) },
                                            supportingContent = { Text("폴더 열기") },
                                            leadingContent = { Icon(Icons.Default.Folder, null) },
                                            modifier = Modifier.clickable {
                                                driveFolderStack = driveFolderStack + (item.id to item.name)
                                                scope.launch { refreshDriveLists() }
                                            }
                                        )
                                    } else {
                                        ListItem(
                                            headlineContent = { Text(item.name) },
                                            supportingContent = { Text("탭하여 수정본 열기 • ${item.sizeBytes / 1024} KB") },
                                            leadingContent = { Icon(Icons.Default.Description, null) },
                                            trailingContent = {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch {
                                                            openDrivePdf(
                                                                context, item, folderSettings,
                                                                noteDao, driveApiService,
                                                                showStrokes = false,
                                                                onOpenDocument = onOpenDocument
                                                            )
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Visibility,
                                                        contentDescription = "원본 보기",
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("원본 보기")
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                scope.launch {
                                                    openDrivePdf(
                                                        context, item, folderSettings,
                                                        noteDao, driveApiService,
                                                        showStrokes = true,
                                                        onOpenDocument = onOpenDocument
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            if (pdfDocs.isNotEmpty()) {
                                item {
                                    Text(
                                        "최근 열람 PDF",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                items(pdfDocs, key = { it.id }) { doc ->
                                    ListItem(
                                        headlineContent = { Text(doc.fileName) },
                                        supportingContent = {
                                            Text("${doc.sourceType} • 마지막 페이지 ${doc.lastPage + 1}")
                                        },
                                        leadingContent = { Icon(Icons.Default.Description, null) },
                                        trailingContent = {
                                            OutlinedButton(
                                                onClick = {
                                                    val cached = LocalFileStorage.originalFileFor(context, doc.id, doc.fileName)
                                                    if (cached.exists()) {
                                                        onOpenDocument(doc, cached, false)
                                                    } else {
                                                        Toast.makeText(context, "로컬 캐시 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Visibility,
                                                    contentDescription = "원본 보기",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("원본 보기")
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            val cached = LocalFileStorage.originalFileFor(context, doc.id, doc.fileName)
                                            if (cached.exists()) {
                                                onOpenDocument(doc, cached, true)
                                            } else {
                                                Toast.makeText(context, "로컬 캐시 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            } else if (sourceDriveEntries.isEmpty() && !isLoading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "열어본 PDF가 없습니다. '기기 PDF 열기' 또는 설정에서 Drive 동기화를 이용해 주세요.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Note Tab
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "독립 필기 노트 (${noteDocs.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val noteId = "note_${UUID.randomUUID()}"
                                        val timestamp = java.text.SimpleDateFormat(
                                            "MMdd_HHmm",
                                            java.util.Locale.getDefault()
                                        ).format(java.util.Date())
                                        val noteEntity = DocumentEntity(
                                            id = noteId,
                                            sourceType = DocumentSourceType.NOTE.name,
                                            sourceIdOrUri = noteId,
                                            fileName = "필기노트_$timestamp"
                                        )
                                        noteDao.upsertDocument(noteEntity)
                                        onOpenDocument(noteEntity, null, true)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("새 필기 노트")
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (noteDocs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "생성된 필기 노트가 없습니다. '새 필기 노트'를 눌러 시작하세요.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(noteDocs, key = { it.id }) { doc ->
                                    ListItem(
                                        headlineContent = { Text(doc.fileName) },
                                        supportingContent = {
                                            Text("총 ${doc.lastPage + 1}페이지 사용")
                                        },
                                        leadingContent = { Icon(Icons.Default.EditNote, null) },
                                        trailingContent = {
                                            IconButton(onClick = { noteToDelete = doc }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "필기 노트 삭제",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            onOpenDocument(doc, null, true)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (canGoForward) {
                var rightEdgeDrag by remember { mutableStateOf(0f) }
                val triggerPx = with(density) { 36.dp.toPx() }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(56.dp)
                        .fillMaxSize()
                        .pointerInput(canGoForward) {
                            detectHorizontalDragGestures(
                                onDragStart = { rightEdgeDrag = 0f },
                                onDragEnd = {
                                    if (rightEdgeDrag < -triggerPx) {
                                        onForwardToLastDocument()
                                    }
                                    rightEdgeDrag = 0f
                                }
                            ) { _, dragAmount ->
                                rightEdgeDrag += dragAmount
                            }
                        }
                )
            }
        }
    }

    folderPickTarget?.let { target ->
        DriveFolderPickerDialog(
            title = "Google Drive 폴더 선택",
            folderSettings = folderSettings,
            driveApiService = driveApiService,
            onDismiss = { folderPickTarget = null },
            onSelectFolder = { item ->
                scope.launch {
                    settingsDataStore.setSourceFolder(item.id, item.name)
                    settingsDataStore.setExportFolder(item.id, item.name)
                    folderPickTarget = null
                    refreshDriveLists()
                }
            }
        )
    }

    signInErrorDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { signInErrorDetail = null },
            title = { Text("Google 로그인 실패") },
            text = {
                Text(
                    "$detail\n\nGoogle Cloud Console 등록 전이라면 아래 '토큰으로 직접 연결'을 이용해 Drive를 바로 연결할 수 있습니다."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signInErrorDetail = null
                        showTokenInputDialog = true
                    }
                ) {
                    Text("토큰으로 직접 연결")
                }
            },
            dismissButton = {
                TextButton(onClick = { signInErrorDetail = null }) {
                    Text("닫기")
                }
            }
        )
    }

    if (showTokenInputDialog) {
        var tokenInput by remember { mutableStateOf(folderSettings.manualDriveToken.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showTokenInputDialog = false },
            title = { Text("Google Drive 토큰 직접 연결") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "발급 사이트:\nhttps://developers.google.com/oauthplayground/",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "발급 순서:\n" +
                            "1. 위 Google OAuth 2.0 Playground 사이트 접속\n" +
                            "2. Step 1에서 'Drive API v3' → 'https://www.googleapis.com/auth/drive' 선택 후 [Authorize APIs] 클릭해 Google 로그인\n" +
                            "3. Step 2에서 [Exchange authorization code for tokens] 클릭\n" +
                            "4. 생성된 Access token (ya29....)을 복사해 아래에 붙여넣기",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("ya29.... Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsDataStore.setManualDriveToken(tokenInput)
                            showTokenInputDialog = false
                            refreshDriveLists()
                        }
                    }
                ) {
                    Text("연결")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenInputDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Google Drive 동기화", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (isDriveConnected) {
                            "연결됨: ${signedInEmail ?: "OAuth 토큰 직접 연결"}"
                        } else {
                            "연결 상태: 미연결"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showSettingsDialog = false
                            signInLauncher.launch(DriveAuthManager.buildSignInIntent(context))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive 동기화")
                    }
                    OutlinedButton(
                        onClick = {
                            showSettingsDialog = false
                            showTokenInputDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("토큰 직접 연결")
                    }
                    if (isDriveConnected) {
                        OutlinedButton(
                            onClick = {
                                showSettingsDialog = false
                                folderPickTarget = FolderPickTarget.SOURCE
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Google Drive 폴더: ${folderSettings.sourceFolderName ?: "미지정"}")
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    settingsDataStore.setManualDriveToken(null)
                                    DriveAuthManager.signOut(context) { signedInEmail = null }
                                    showSettingsDialog = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("동기화 해제 (로그아웃)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }

    noteToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("필기 노트 삭제") },
            text = { Text("'${doc.fileName}' 노트를 삭제할까요?\n노트 내 필기 내용이 모두 삭제됩니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            noteDao.deleteDocument(doc.id)
                            noteToDelete = null
                            Toast.makeText(context, "필기 노트를 삭제했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun FolderCard(
    title: String,
    folderName: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(folderName, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun DriveFolderPickerDialog(
    title: String,
    folderSettings: FolderSettings,
    driveApiService: DriveApiService,
    onDismiss: () -> Unit,
    onSelectFolder: (DriveItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentParentId by remember { mutableStateOf("root") }
    var currentParentName by remember { mutableStateOf("내 드라이브") }
    var folders by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var newFolderName by remember { mutableStateOf("") }
    var showCreateBox by remember { mutableStateOf(false) }

    suspend fun loadFolders(parentId: String) {
        val token = DriveAuthManager.getActiveAccessToken(context, folderSettings)
        if (token == null) {
            loading = false
            return
        }
        loading = true
        runCatching {
            folders = driveApiService.listFolders(token, parentId)
        }.onFailure { e ->
            Toast.makeText(context, "폴더 조회 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        loading = false
    }

    LaunchedEffect(currentParentId) {
        loadFolders(currentParentId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("현재 위치: $currentParentName", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { showCreateBox = !showCreateBox }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "새 폴더")
                    }
                }

                if (showCreateBox) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("새 폴더 이름") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (newFolderName.isBlank()) return@IconButton
                                scope.launch {
                                    val token = DriveAuthManager.getActiveAccessToken(
                                        context, folderSettings
                                    ) ?: return@launch
                                    runCatching {
                                        driveApiService.createFolder(token, newFolderName.trim(), currentParentId)
                                    }.onSuccess {
                                        newFolderName = ""
                                        showCreateBox = false
                                        loadFolders(currentParentId)
                                    }.onFailure { e ->
                                        Toast.makeText(context, "폴더 생성 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "생성")
                        }
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(folders, key = { it.id }) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                trailingContent = {
                                    TextButton(onClick = { onSelectFolder(folder) }) {
                                        Text("선택")
                                    }
                                },
                                modifier = Modifier.clickable {
                                    currentParentId = folder.id
                                    currentParentName = folder.name
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentParentId != "root") {
                TextButton(
                    onClick = {
                        onSelectFolder(
                            DriveItem(
                                id = currentParentId,
                                name = currentParentName,
                                mimeType = "application/vnd.google-apps.folder"
                            )
                        )
                    }
                ) {
                    Text("현재 폴더 선택")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

private suspend fun openDrivePdf(
    context: android.content.Context,
    item: DriveItem,
    folderSettings: FolderSettings,
    noteDao: NoteDao,
    driveApiService: DriveApiService,
    showStrokes: Boolean,
    onOpenDocument: (DocumentEntity, File?, Boolean) -> Unit
) {
    val token = DriveAuthManager.getActiveAccessToken(context, folderSettings) ?: return
    val docId = "drive_${item.id}"
    val destFile = LocalFileStorage.originalFileFor(context, docId, item.name)
    runCatching {
        if (!destFile.exists() || destFile.length() == 0L) {
            driveApiService.downloadPdf(token, item.id, destFile)
        }
        val entity = noteDao.getDocument(docId) ?: DocumentEntity(
            id = docId,
            sourceType = DocumentSourceType.DRIVE.name,
            sourceIdOrUri = item.id,
            fileName = item.name
        )
        noteDao.upsertDocument(entity)
        runCatching {
            prefetchScholarAgentAssets(context, token, driveApiService, item.id, docId)
        }
        onOpenDocument(entity, destFile, showStrokes)
    }.onFailure { e ->
        Toast.makeText(context, "PDF 다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

internal suspend fun prefetchScholarAgentAssets(
    context: android.content.Context,
    token: String,
    driveApiService: DriveApiService,
    pdfFileId: String,
    docId: String
) {
    val safeId = docId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val cacheDir = File(context.filesDir, "scholar_agent/$safeId").apply { mkdirs() }
    val parentId = driveApiService.getFileParents(token, pdfFileId).firstOrNull() ?: return

    // 1. Check same folder for highlights.json / summary.ko.md
    val hlFile = File(cacheDir, "highlights.json")
    if (!hlFile.exists()) {
        driveApiService.findChildByName(token, parentId, "highlights.json")?.let {
            hlFile.writeText(driveApiService.downloadText(token, it.id))
        }
    }

    val summaryFile = File(cacheDir, "summary.ko.md")
    if (!summaryFile.exists()) {
        val direct = driveApiService.findChildByName(token, parentId, "summary.ko.md")
        if (direct != null) {
            summaryFile.writeText(driveApiService.downloadText(token, direct.id))
        } else {
            // Check sibling summary/ folder (e.g. parent is highlights/ or paper folder)
            val paperFolderId = driveApiService.getFileParents(token, parentId).firstOrNull() ?: parentId
            val summaryFolder = driveApiService.findChildByName(token, paperFolderId, "summary")
                ?: driveApiService.findChildByName(token, parentId, "summary")
            if (summaryFolder != null) {
                val koItem = driveApiService.findChildByName(token, summaryFolder.id, "summary.ko.md")
                    ?: driveApiService.findChildByName(token, summaryFolder.id, "summary.en.md")
                if (koItem != null) {
                    summaryFile.writeText(driveApiService.downloadText(token, koItem.id))
                }
            }
        }
    }
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment
}
