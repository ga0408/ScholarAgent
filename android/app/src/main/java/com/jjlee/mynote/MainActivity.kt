package com.jjlee.mynote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jjlee.mynote.data.FolderSettings
import com.jjlee.mynote.data.db.DocumentEntity
import com.jjlee.mynote.ui.drive.DriveBrowserScreen
import com.jjlee.mynote.ui.theme.MyNoteTheme
import com.jjlee.mynote.ui.viewer.PdfNoteScreen
import java.io.File

private data class OpenedDocument(
    val document: DocumentEntity,
    val file: File?,
    val showStrokes: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MyNoteApp
        val noteDao = app.database.noteDao()

        setContent {
            MyNoteTheme {
                val folderSettings by app.settingsDataStore.folderSettings
                    .collectAsState(initial = FolderSettings())
                var openedDoc by remember { mutableStateOf<OpenedDocument?>(null) }
                var lastOpenedDoc by remember { mutableStateOf<OpenedDocument?>(null) }

                val current = openedDoc
                if (current == null) {
                    DriveBrowserScreen(
                        noteDao = noteDao,
                        settingsDataStore = app.settingsDataStore,
                        driveApiService = app.driveApiService,
                        canGoForward = lastOpenedDoc != null,
                        onForwardToLastDocument = {
                            lastOpenedDoc?.let { openedDoc = it }
                        },
                        onOpenDocument = { doc, file, showStrokes ->
                            val item = OpenedDocument(doc, file, showStrokes)
                            lastOpenedDoc = item
                            openedDoc = item
                        }
                    )
                } else {
                    PdfNoteScreen(
                        document = current.document,
                        localOriginalFile = current.file,
                        noteDao = noteDao,
                        folderSettings = folderSettings,
                        settingsDataStore = app.settingsDataStore,
                        driveApiService = app.driveApiService,
                        initialShowStrokes = current.showStrokes,
                        onBack = { openedDoc = null }
                    )
                }
            }
        }
    }
}
