package com.jjlee.mynote

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    private var stylusButtonEventSeq by mutableIntStateOf(0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val code = event.keyCode
            val devName = event.device?.name?.lowercase().orEmpty()
            val isStylusSource = (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS ||
                devName.contains("pen") || devName.contains("stylus")
            val isStylusKeyCode = code == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
                code == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
                code == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
                code == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
            val isSystemKey = code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_VOLUME_UP ||
                code == KeyEvent.KEYCODE_VOLUME_DOWN ||
                code == KeyEvent.KEYCODE_POWER
            if (isStylusKeyCode || (isStylusSource && !isSystemKey)) {
                stylusButtonEventSeq++
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

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
                        stylusButtonEventSeq = stylusButtonEventSeq,
                        onBack = { openedDoc = null }
                    )
                }
            }
        }
    }
}
