package com.jjlee.mynote

import android.app.Application
import com.jjlee.mynote.data.SettingsDataStore
import com.jjlee.mynote.data.db.NoteDatabase
import com.jjlee.mynote.drive.DriveApiService

class MyNoteApp : Application() {
    val database by lazy { NoteDatabase.getInstance(this) }
    val settingsDataStore by lazy { SettingsDataStore(this) }
    val driveApiService by lazy { DriveApiService() }
}
