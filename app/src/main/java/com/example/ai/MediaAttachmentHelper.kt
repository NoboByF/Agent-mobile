package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.example.data.AttachmentKind
import com.example.data.ChatAttachmentMeta
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Zero-permission attachment reader built on Android Photo Picker & SAF (OpenDocument).
 * Supports Photos, Videos, Audio, PDFs, Text/Code files, and Documents for Gemini multimodal Ask AI.
 */
data class PreparedAttachment(
    val meta: ChatAttachmentMeta,
    val inlineMimeType: String? = null,
    val inlineBase64: String? = null,
    val rawBytesForUpload: ByteArray? = null,
    val extractedText: String? = null
)

object MediaAttachmentHelper {

    private const val MAX_INLINE_BYTES = 10 * 1024 * 1024L // 10 MB inline limit
    private const val MAX_UPLOAD_BYTES = 45 * 1024 * 1024L // 45 MB Gemini Files API limit
    private const val MAX_TEXT_CHARS = 35_000

    fun inspectUri(context: Context, uri: Uri): ChatAttachmentMeta {
        val cr = context.contentResolver
        var displayName = "attachment"
        var sizeBytes = 0L

        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                        }
                    }
                }
        }

        val resolvedMime = cr.getType(uri)?.takeIf { it.isNotBlank() }
            ?: guessMimeFromFilename(displayName)
            ?: "application/octet-stream"

        val kind = classifyKind(resolvedMime, displayName)
        return ChatAttachmentMeta(
            id = "att_${UUID.randomUUID().toString().take(8)}",
            displayName = displayName,
            mimeType = resolvedMime,
            sizeBytes = sizeBytes,
            kind = kind,
            uriString = uri.toString()
        )
    }

    suspend fun prepareForGemini(
        context: Context,
        meta: ChatAttachmentMeta
    ): Result<PreparedAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(meta.uriString)
            val cr = context.contentResolver

            when (meta.kind) {
                AttachmentKind.IMAGE -> {
                    val jpegBytes = decodeAndCompressImage(context, uri)
                        ?: error("Could not read image '${meta.displayName}'.")
                    PreparedAttachment(
                        meta = meta.copy(sizeBytes = jpegBytes.size.toLong()),
                        inlineMimeType = "image/jpeg",
                        inlineBase64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    )
                }

                AttachmentKind.TEXT -> {
                    val bytes = readLimitedBytes(cr, uri, 2 * 1024 * 1024L)
                        ?: error("Could not read text file '${meta.displayName}'.")
                    val decoded = bytes.toString(Charsets.UTF_8).take(MAX_TEXT_CHARS)
                    PreparedAttachment(
                        meta = meta.copy(sizeBytes = bytes.size.toLong()),
                        extractedText = "[Attached File: ${meta.displayName} (${meta.mimeType})]\n$decoded"
                    )
                }

                AttachmentKind.VIDEO,
                AttachmentKind.AUDIO,
                AttachmentKind.DOCUMENT,
                AttachmentKind.OTHER -> {
                    if (meta.sizeBytes > MAX_UPLOAD_BYTES) {
                        error("File '${meta.displayName}' is larger than 45 MB limit (${formatFileSize(meta.sizeBytes)}).")
                    }
                    val bytes = readLimitedBytes(cr, uri, MAX_UPLOAD_BYTES)
                        ?: error("Could not read file '${meta.displayName}'.")

                    val effectiveMime = normalizeGeminiMime(meta.mimeType, meta.displayName)
                    if (bytes.size <= MAX_INLINE_BYTES && isInlineSupportedMime(effectiveMime)) {
                        PreparedAttachment(
                            meta = meta.copy(sizeBytes = bytes.size.toLong(), mimeType = effectiveMime),
                            inlineMimeType = effectiveMime,
                            inlineBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        )
                    } else {
                        // Larger videos/PDFs/audio use Gemini Files API resumable upload
                        PreparedAttachment(
                            meta = meta.copy(sizeBytes = bytes.size.toLong(), mimeType = effectiveMime),
                            inlineMimeType = effectiveMime,
                            rawBytesForUpload = bytes
                        )
                    }
                }
            }
        }.onFailure { err ->
            PilotStateHub.log(LogLevel.ERROR, "Attachments", "Prepare failed (${meta.displayName}): ${err.message}")
        }
    }

    private fun decodeAndCompressImage(context: Context, uri: Uri): ByteArray? {
        val cr = context.contentResolver
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) {
            // Fallback raw bytes if bitmap bounds unavailable (e.g. WebP/HEIC fallback)
            return readLimitedBytes(cr, uri, MAX_INLINE_BYTES)
        }

        var sampleSize = 1
        val maxDim = 1280
        while ((srcW / sampleSize) > maxDim * 2 || (srcH / sampleSize) > maxDim * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 84, baos)
        scaled.recycle()
        return baos.toByteArray()
    }

    private fun readLimitedBytes(
        cr: android.content.ContentResolver,
        uri: Uri,
        maxBytes: Long
    ): ByteArray? {
        cr.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    error("File exceeds ${maxBytes / (1024 * 1024)} MB maximum size.")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
        return null
    }

    private fun classifyKind(mime: String, filename: String): AttachmentKind {
        val lowerMime = mime.lowercase()
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when {
            lowerMime.startsWith("image/") -> AttachmentKind.IMAGE
            lowerMime.startsWith("video/") -> AttachmentKind.VIDEO
            lowerMime.startsWith("audio/") -> AttachmentKind.AUDIO
            lowerMime == "application/pdf" || ext == "pdf" -> AttachmentKind.DOCUMENT
            lowerMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> AttachmentKind.TEXT
            else -> AttachmentKind.OTHER
        }
    }

    private fun guessMimeFromFilename(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return null
        if (ext in TEXT_EXTENSIONS) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun normalizeGeminiMime(mime: String, filename: String): String {
        val lower = mime.lowercase()
        if (lower != "application/octet-stream") return lower
        return guessMimeFromFilename(filename) ?: "application/pdf"
    }

    private fun isInlineSupportedMime(mime: String): Boolean {
        val lower = mime.lowercase()
        return lower.startsWith("image/") ||
            lower.startsWith("video/") ||
            lower.startsWith("audio/") ||
            lower == "application/pdf" ||
            lower.startsWith("text/")
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ts",
        "kt", "java", "py", "c", "cpp", "h", "sh", "yaml", "yml", "ini", "log", "sql"
    )
}
