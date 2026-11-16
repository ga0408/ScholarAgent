package com.jjlee.mynote.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String = "",
    val sizeBytes: Long = 0L
)

class DriveApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    suspend fun listFolders(accessToken: String, parentId: String = "root"): List<DriveItem> =
        withContext(Dispatchers.IO) {
            val query = "'$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            queryFiles(accessToken, query, orderBy = "name")
        }

    suspend fun listPdfsInFolder(accessToken: String, folderId: String): List<DriveItem> =
        withContext(Dispatchers.IO) {
            val query = "'$folderId' in parents and mimeType = 'application/pdf' and trashed = false"
            queryFiles(accessToken, query, orderBy = "modifiedTime desc")
        }

    suspend fun listChildrenInFolder(accessToken: String, folderId: String): List<DriveItem> =
        withContext(Dispatchers.IO) {
            val query =
                "'$folderId' in parents and (mimeType = 'application/vnd.google-apps.folder' or mimeType = 'application/pdf') and trashed = false"
            queryFiles(accessToken, query, orderBy = "folder,name")
        }

    suspend fun findChildByName(accessToken: String, parentId: String, name: String): DriveItem? =
        withContext(Dispatchers.IO) {
            val safeName = name.replace("'", "\\'")
            val query = "'$parentId' in parents and name = '$safeName' and trashed = false"
            queryFiles(accessToken, query, orderBy = "name").firstOrNull()
        }

    suspend fun getFileParents(accessToken: String, fileId: String): List<String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?fields=parents")
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val parents = JSONObject(resp.body?.string().orEmpty()).optJSONArray("parents")
                    ?: return@withContext emptyList()
                buildList(parents.length()) {
                    for (i in 0 until parents.length()) add(parents.getString(i))
                }
            }
        }

    suspend fun downloadText(accessToken: String, fileId: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Drive text download failed (${resp.code})")
                resp.body?.string().orEmpty()
            }
        }

    suspend fun createFolder(
        accessToken: String,
        folderName: String,
        parentId: String = "root"
    ): DriveItem = withContext(Dispatchers.IO) {
        val metadata = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put(parentId))
        }
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id,name,mimeType")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive createFolder failed (${resp.code}): $body")
            val obj = JSONObject(body)
            DriveItem(
                id = obj.getString("id"),
                name = obj.getString("name"),
                mimeType = obj.optString("mimeType", "application/vnd.google-apps.folder")
            )
        }
    }

    suspend fun downloadPdf(accessToken: String, fileId: String, destFile: File): File =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Drive download failed (${resp.code})")
                val body = resp.body ?: error("Empty Drive download body")
                FileOutputStream(destFile).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            destFile
        }

    suspend fun uploadOrUpdateAnnotatedPdf(
        accessToken: String,
        exportFolderId: String,
        fileName: String,
        localPdfFile: File,
        existingFileId: String? = null
    ): DriveItem = withContext(Dispatchers.IO) {
        val metadataJson = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "application/pdf")
            if (existingFileId == null) {
                put("parents", org.json.JSONArray().put(exportFolderId))
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                metadataJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .addPart(localPdfFile.asRequestBody("application/pdf".toMediaType()))
            .build()

        val url = if (existingFileId != null) {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id,name,mimeType,modifiedTime,size"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,modifiedTime,size"
        }

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")

        val req = if (existingFileId != null) {
            builder.patch(multipartBody).build()
        } else {
            builder.post(multipartBody).build()
        }

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive upload failed (${resp.code}): $body")
            val obj = JSONObject(body)
            DriveItem(
                id = obj.getString("id"),
                name = obj.getString("name"),
                mimeType = obj.optString("mimeType", "application/pdf"),
                modifiedTime = obj.optString("modifiedTime", ""),
                sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L
            )
        }
    }

    suspend fun uploadMarkdownOrLogFile(
        accessToken: String,
        folderId: String?,
        fileName: String,
        localFile: File
    ): DriveItem = withContext(Dispatchers.IO) {
        val targetFolderId = folderId?.takeIf { it.isNotBlank() } ?: run {
            val folderQuery =
                "'root' in parents and name = 'MyNote' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val existingFolder = queryFiles(accessToken, folderQuery, orderBy = "name").firstOrNull()
            existingFolder?.id ?: createFolder(accessToken, "MyNote", "root").id
        }

        val escapedName = fileName.replace("'", "\\'")
        val existingFile = queryFiles(
            accessToken,
            "'$targetFolderId' in parents and name = '$escapedName' and trashed = false",
            orderBy = "modifiedTime desc"
        ).firstOrNull()

        val metadataJson = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "text/markdown")
            if (existingFile == null) {
                put("parents", org.json.JSONArray().put(targetFolderId))
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                metadataJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .addPart(localFile.asRequestBody("text/markdown; charset=utf-8".toMediaType()))
            .build()

        val url = if (existingFile != null) {
            "https://www.googleapis.com/upload/drive/v3/files/${existingFile.id}?uploadType=multipart&fields=id,name,mimeType,modifiedTime,size"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,modifiedTime,size"
        }

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")

        val req = if (existingFile != null) {
            builder.patch(multipartBody).build()
        } else {
            builder.post(multipartBody).build()
        }

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive markdown/log upload failed (${resp.code}): $body")
            val obj = JSONObject(body)
            DriveItem(
                id = obj.getString("id"),
                name = obj.getString("name"),
                mimeType = obj.optString("mimeType", "text/markdown"),
                modifiedTime = obj.optString("modifiedTime", ""),
                sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L
            )
        }
    }

    private fun queryFiles(accessToken: String, query: String, orderBy: String): List<DriveItem> {
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val encodedOrder = URLEncoder.encode(orderBy, "UTF-8")
        val url =
            "https://www.googleapis.com/drive/v3/files?q=$encodedQ&orderBy=$encodedOrder&pageSize=100&fields=files(id,name,mimeType,modifiedTime,size)"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive query failed (${resp.code}): $body")
            val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
            return buildList(files.length()) {
                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    add(
                        DriveItem(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            mimeType = obj.optString("mimeType", ""),
                            modifiedTime = obj.optString("modifiedTime", ""),
                            sizeBytes = obj.optString("size", "0").toLongOrNull() ?: 0L
                        )
                    )
                }
            }
        }
    }
}
