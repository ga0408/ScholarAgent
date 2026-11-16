package com.jjlee.mynote.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.jjlee.mynote.data.LocalFileStorage
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.ink.StrokeRenderer
import com.jjlee.mynote.ui.viewer.PaperStyle
import com.jjlee.mynote.ui.viewer.drawAndroidPaperBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Exports a flattened annotated PDF without modifying the original PDF.
 * Output is written to the separated Annotated directory.
 */
object PdfExporter {

    suspend fun exportAnnotatedPdf(
        context: Context,
        originalPdfFile: File,
        originalFileName: String,
        strokesByPage: Map<Int, List<StrokeEntity>>,
        density: Float,
        optionalTreeOutputUri: Uri? = null
    ): File = withContext(Dispatchers.IO) {
        val outFile = LocalFileStorage.annotatedFileFor(context, originalFileName)
        val pfd = ParcelFileDescriptor.open(originalPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val outDoc = PdfDocument()

        try {
            val scaleFactor = 2f // 144 DPI crisp rendering
            for (pageIndex in 0 until renderer.pageCount) {
                renderer.openPage(pageIndex).use { srcPage ->
                    val pageWidth = srcPage.width
                    val pageHeight = srcPage.height
                    val renderW = (pageWidth * scaleFactor).toInt().coerceAtLeast(1)
                    val renderH = (pageHeight * scaleFactor).toInt().coerceAtLeast(1)

                    val pageBitmap = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    pageBitmap.eraseColor(Color.WHITE)
                    srcPage.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                    val outPage = outDoc.startPage(pageInfo)
                    val canvas = outPage.canvas

                    // Draw original PDF page scaled to page points
                    canvas.save()
                    canvas.scale(1f / scaleFactor, 1f / scaleFactor)
                    canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                    canvas.restore()
                    pageBitmap.recycle()

                    // Draw strokes for this page using normalized coordinates
                    val pageStrokes = strokesByPage[pageIndex].orEmpty()
                    if (pageStrokes.isNotEmpty()) {
                        val saveCount = canvas.saveLayer(
                            0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), null
                        )
                        StrokeRenderer.drawStrokes(
                            canvas = canvas,
                            strokes = pageStrokes,
                            width = pageWidth.toFloat(),
                            height = pageHeight.toFloat(),
                            density = density / 2.2f,
                            forOverlay = false
                        )
                        canvas.restoreToCount(saveCount)
                    }

                    outDoc.finishPage(outPage)
                }
            }

            FileOutputStream(outFile).use { fos ->
                outDoc.writeTo(fos)
            }

            if (optionalTreeOutputUri != null) {
                copyToSafUri(context, outFile, optionalTreeOutputUri)
            }
        } finally {
            outDoc.close()
            renderer.close()
            pfd.close()
        }

        outFile
    }

    suspend fun exportNoteAsPdf(
        context: Context,
        noteTitle: String,
        pageCount: Int,
        paperStyle: PaperStyle,
        strokesByPage: Map<Int, List<StrokeEntity>>,
        density: Float
    ): File = withContext(Dispatchers.IO) {
        val outFile = LocalFileStorage.annotatedFileFor(context, noteTitle)
        val outDoc = PdfDocument()
        val pageWidth = 595 // A4 points
        val pageHeight = 842

        try {
            val totalPages = pageCount.coerceAtLeast(1)
            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val outPage = outDoc.startPage(pageInfo)
                val canvas = outPage.canvas

                drawAndroidPaperBackground(
                    canvas = canvas,
                    width = pageWidth.toFloat(),
                    height = pageHeight.toFloat(),
                    style = paperStyle
                )

                val pageStrokes = strokesByPage[pageIndex].orEmpty()
                if (pageStrokes.isNotEmpty()) {
                    val saveCount = canvas.saveLayer(
                        0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), null
                    )
                    StrokeRenderer.drawStrokes(
                        canvas = canvas,
                        strokes = pageStrokes,
                        width = pageWidth.toFloat(),
                        height = pageHeight.toFloat(),
                        density = density / 2.2f,
                        forOverlay = false
                    )
                    canvas.restoreToCount(saveCount)
                }
                outDoc.finishPage(outPage)
            }

            FileOutputStream(outFile).use { fos ->
                outDoc.writeTo(fos)
            }
        } finally {
            outDoc.close()
        }

        outFile
    }

    private fun copyToSafUri(context: Context, sourceFile: File, targetUri: Uri) {
        runCatching {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
    }
}
