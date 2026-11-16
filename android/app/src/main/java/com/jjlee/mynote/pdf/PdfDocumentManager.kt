package com.jjlee.mynote.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentManager {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()

    private val pageCache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    suspend fun open(file: File): Int = withContext(Dispatchers.IO) {
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(fd)
        fileDescriptor = fd
        renderer = pdfRenderer
        pdfRenderer.pageCount
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val currentRenderer = renderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= currentRenderer.pageCount) return@withContext null

        val clampedWidth = targetWidthPx.coerceIn(200, 3600)
        val cacheKey = "${pageIndex}_$clampedWidth"
        pageCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            pageCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }
            currentRenderer.openPage(pageIndex).use { page ->
                val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                val targetHeight = (clampedWidth * aspect).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(clampedWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageCache.put(cacheKey, bitmap)
                bitmap
            }
        }
    }

    fun close() {
        pageCache.evictAll()
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }
}
