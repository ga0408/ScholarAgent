package com.jjlee.mynote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jjlee.mynote.ink.BrushState
import com.jjlee.mynote.ink.BrushType
import com.jjlee.mynote.ink.DefaultGestureMappings
import com.jjlee.mynote.ink.GestureAction
import com.jjlee.mynote.ink.GestureTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mynote_settings")

data class FavoritePenPreset(
    val id: String,
    val label: String,
    val brushType: BrushType,
    val colorArgb: Int,
    val widthDp: Float,
    val alpha: Float
) {
    fun toBrushState(): BrushState = BrushState(
        type = brushType,
        colorArgb = colorArgb,
        widthDp = widthDp,
        alpha = alpha
    )
}

object DefaultFavoritePens {
    val presets: List<FavoritePenPreset> = listOf(
        FavoritePenPreset("pen_black", "검정 펜", BrushType.PEN, 0xFF1E293B.toInt(), 3.0f, 1.0f),
        FavoritePenPreset("pen_blue", "파랑 펜", BrushType.PEN, 0xFF2563EB.toInt(), 3.0f, 1.0f),
        FavoritePenPreset("pen_red", "빨강 펜", BrushType.PEN, 0xFFDC2626.toInt(), 3.0f, 1.0f),
        FavoritePenPreset("hl_yellow", "노랑 형광", BrushType.HIGHLIGHTER, 0xFFFACC15.toInt(), 4.5f, 0.35f),
        FavoritePenPreset("ft_ink", "만년필", BrushType.FOUNTAIN_PEN, 0xFF0F172A.toInt(), 3.5f, 1.0f)
    )
}

data class FolderSettings(
    val sourceFolderId: String? = null,
    val sourceFolderName: String? = null,
    val exportFolderId: String? = null,
    val exportFolderName: String? = null,
    val localExportTreeUri: String? = null,
    val manualDriveToken: String? = null,
    val favoritePens: List<FavoritePenPreset> = DefaultFavoritePens.presets,
    val gestureMappings: Map<GestureTrigger, GestureAction> = DefaultGestureMappings.defaults,
    val aiHighlightColorArgb: Int? = null
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val SOURCE_FOLDER_ID = stringPreferencesKey("source_folder_id")
        val SOURCE_FOLDER_NAME = stringPreferencesKey("source_folder_name")
        val EXPORT_FOLDER_ID = stringPreferencesKey("export_folder_id")
        val EXPORT_FOLDER_NAME = stringPreferencesKey("export_folder_name")
        val LOCAL_EXPORT_TREE_URI = stringPreferencesKey("local_export_tree_uri")
        val MANUAL_DRIVE_TOKEN = stringPreferencesKey("manual_drive_token")
        val FAVORITE_PENS_JSON = stringPreferencesKey("favorite_pens_json")
        val GESTURE_MAPPINGS_JSON = stringPreferencesKey("gesture_mappings_json")
        val AI_HIGHLIGHT_COLOR_ARGB = intPreferencesKey("ai_highlight_color_argb")
    }

    val folderSettings: Flow<FolderSettings> = context.dataStore.data.map { prefs ->
        FolderSettings(
            sourceFolderId = prefs[Keys.SOURCE_FOLDER_ID],
            sourceFolderName = prefs[Keys.SOURCE_FOLDER_NAME],
            exportFolderId = prefs[Keys.EXPORT_FOLDER_ID],
            exportFolderName = prefs[Keys.EXPORT_FOLDER_NAME],
            localExportTreeUri = prefs[Keys.LOCAL_EXPORT_TREE_URI],
            manualDriveToken = prefs[Keys.MANUAL_DRIVE_TOKEN],
            favoritePens = decodeFavoritePens(prefs[Keys.FAVORITE_PENS_JSON]),
            gestureMappings = decodeGestureMappings(prefs[Keys.GESTURE_MAPPINGS_JSON]),
            aiHighlightColorArgb = prefs[Keys.AI_HIGHLIGHT_COLOR_ARGB]
        )
    }

    suspend fun setSourceFolder(id: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE_FOLDER_ID] = id
            prefs[Keys.SOURCE_FOLDER_NAME] = name
        }
    }

    suspend fun setExportFolder(id: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EXPORT_FOLDER_ID] = id
            prefs[Keys.EXPORT_FOLDER_NAME] = name
        }
    }

    suspend fun setLocalExportTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(Keys.LOCAL_EXPORT_TREE_URI)
            } else {
                prefs[Keys.LOCAL_EXPORT_TREE_URI] = uri
            }
        }
    }

    suspend fun setManualDriveToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(Keys.MANUAL_DRIVE_TOKEN)
            } else {
                prefs[Keys.MANUAL_DRIVE_TOKEN] = token.trim()
            }
        }
    }

    suspend fun setAiHighlightColor(argb: Int?) {
        context.dataStore.edit { prefs ->
            if (argb == null) {
                prefs.remove(Keys.AI_HIGHLIGHT_COLOR_ARGB)
            } else {
                prefs[Keys.AI_HIGHLIGHT_COLOR_ARGB] = argb
            }
        }
    }

    suspend fun saveFavoritePens(pens: List<FavoritePenPreset>) {
        val arr = JSONArray()
        pens.forEach { pen ->
            arr.put(
                JSONObject().apply {
                    put("id", pen.id)
                    put("label", pen.label)
                    put("type", pen.brushType.name)
                    put("color", pen.colorArgb)
                    put("width", pen.widthDp.toDouble())
                    put("alpha", pen.alpha.toDouble())
                }
            )
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.FAVORITE_PENS_JSON] = arr.toString()
        }
    }

    suspend fun setGestureMapping(trigger: GestureTrigger, action: GestureAction) {
        context.dataStore.edit { prefs ->
            val current = decodeGestureMappings(prefs[Keys.GESTURE_MAPPINGS_JSON]).toMutableMap()
            current[trigger] = action
            val obj = JSONObject()
            current.forEach { (k, v) -> obj.put(k.name, v.name) }
            prefs[Keys.GESTURE_MAPPINGS_JSON] = obj.toString()
        }
    }

    private fun decodeFavoritePens(json: String?): List<FavoritePenPreset> {
        if (json.isNullOrBlank()) return DefaultFavoritePens.presets
        return runCatching {
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        FavoritePenPreset(
                            id = o.getString("id"),
                            label = o.optString("label", "펜"),
                            brushType = BrushType.fromName(o.getString("type")),
                            colorArgb = o.getInt("color"),
                            widthDp = o.optDouble("width", 3.5).toFloat(),
                            alpha = o.optDouble("alpha", 1.0).toFloat()
                        )
                    )
                }
            }
        }.getOrDefault(DefaultFavoritePens.presets)
    }

    private fun decodeGestureMappings(json: String?): Map<GestureTrigger, GestureAction> {
        val base = DefaultGestureMappings.defaults.toMutableMap()
        if (json.isNullOrBlank()) return base
        runCatching {
            val obj = JSONObject(json)
            for (trigger in GestureTrigger.entries) {
                val actionName = obj.optString(trigger.name, "")
                val parsed = GestureAction.entries.firstOrNull { it.name == actionName }
                if (parsed != null) {
                    base[trigger] = parsed
                }
            }
        }
        return base
    }
}
