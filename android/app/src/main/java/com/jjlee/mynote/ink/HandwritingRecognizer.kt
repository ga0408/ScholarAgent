package com.jjlee.mynote.ink

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.data.db.StrokePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Converts handwritten strokes into text & Markdown using on-device ML Kit Digital Ink Recognition.
 */
object HandwritingRecognizer {

    suspend fun recognizeToMarkdown(
        title: String,
        strokes: List<StrokeEntity>,
        languageTag: String = "ko-KR",
        canvasWidth: Float = 1000f,
        canvasHeight: Float = 1200f,
        includeTitle: Boolean = true
    ): String {
        if (strokes.isEmpty()) {
            return if (includeTitle) "# $title\n\n(변환할 필기가 없습니다.)" else "(변환할 필기가 없습니다.)"
        }
        val recognizedText = recognizeText(
            strokes = strokes,
            languageTag = languageTag,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
        return formatAsMarkdown(title = title, rawText = recognizedText, includeTitle = includeTitle)
    }

    suspend fun recognizeText(
        strokes: List<StrokeEntity>,
        languageTag: String = "ko-KR",
        canvasWidth: Float = 1000f,
        canvasHeight: Float = 1200f
    ): String = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext ""

        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
            ?: error("Digital Ink 언어 모델을 찾을 수 없습니다: $languageTag")

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        ensureModelDownloaded(model)

        val inkBuilder = Ink.builder()
        var syntheticTime = 0L
        for (strokeEntity in strokes) {
            val pts = StrokePoint.decodeList(strokeEntity.pointsJson)
            if (pts.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (pt in pts) {
                syntheticTime += 12L
                strokeBuilder.addPoint(
                    Ink.Point.create(
                        pt.x * canvasWidth,
                        pt.y * canvasHeight,
                        syntheticTime
                    )
                )
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.recognize(inkBuilder.build())
                    .addOnSuccessListener { result ->
                        val best = result.candidates.firstOrNull()?.text.orEmpty()
                        cont.resume(best)
                    }
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                    }
            }
        } finally {
            recognizer.close()
        }
    }

    fun formatRecognizedBody(rawText: String): String {
        val cleaned = rawText.trim()
        if (cleaned.isBlank()) return ""
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    fun formatAsMarkdown(title: String, rawText: String, includeTitle: Boolean = true): String {
        val body = formatRecognizedBody(rawText)
        if (body.isBlank()) return if (includeTitle) "# $title\n\n" else ""
        return if (includeTitle) "# $title\n\n$body\n" else "$body\n"
    }

    /**
     * Appends newly recognized handwriting text to an existing Markdown memo without duplicating
     * the top-level `# title` header across repeated conversions.
     */
    fun appendRecognizedMarkdown(
        existingMemo: String,
        title: String,
        rawText: String
    ): String {
        val body = formatRecognizedBody(rawText)
        if (body.isBlank()) {
            return if (existingMemo.isBlank()) "# $title\n\n" else existingMemo
        }
        if (existingMemo.isBlank()) {
            return "# $title\n\n$body\n"
        }
        val hasTitleHeading = existingMemo.lineSequence().any { it.trimStart().startsWith("# ") }
        val trimmedExisting = existingMemo.trimEnd()
        return if (hasTitleHeading) {
            "$trimmedExisting\n\n$body\n"
        } else {
            "# $title\n\n$trimmedExisting\n\n$body\n"
        }
    }

    private suspend fun ensureModelDownloaded(model: DigitalInkRecognitionModel) {
        val manager = RemoteModelManager.getInstance()
        val isDownloaded = suspendCancellableCoroutine { cont ->
            manager.isModelDownloaded(model)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        if (!isDownloaded) {
            val conditions = DownloadConditions.Builder().build()
            suspendCancellableCoroutine { cont ->
                manager.download(model, conditions)
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
    }
}
