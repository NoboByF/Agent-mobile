package com.example.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object GeneratedImageHelper {

    private const val DIR_NAME = "generated_images"

    private val IMAGE_NOUN_REGEX = Regex(
        "(ছবি|ইমেজ|পিকচার|পিক|ফটো|ড্রয়িং|ড্রয়িং|আর্ট|ওয়ালপেপার|ওয়ালপেপার|লোগো|পোস্টার|সিনারি|ইলাস্ট্রেশন|" +
            "\\bimage\\b|\\bpicture\\b|\\bphoto\\b|\\bpic\\b|\\bwallpaper\\b|\\blogo\\b|\\bposter\\b|\\billustration\\b|\\bartwork\\b|\\bportrait\\b|\\bpainting\\b|\\bsketch\\b|\\bchobi\\b|\\bchhobi\\b|\\bpic\\b)",
        RegexOption.IGNORE_CASE
    )

    private val CREATE_VERB_REGEX = Regex(
        "(তৈরি|তৈরী|বানা|বানিয়ে|বানিয়ে|বানাও|বানান|জেনারেট|আঁক|আক|আঁকো|আঁকুন|অঙ্কন|ডিজাইন|" +
            "\\bgenerate\\b|\\bcreate\\b|\\bmake\\b|\\bdraw\\b|\\bpaint\\b|\\bdesign\\b|\\brender\\b|\\bproduce\\b|\\bimagine\\b|\\bbanao\\b|\\btoiri\\b|\\bbaniye\\b)",
        RegexOption.IGNORE_CASE
    )

    private val ANALYSIS_ONLY_REGEX = Regex(
        "(এই\\s*(ছবি|ইমেজ|ফটো)(টি|টা)?\\s*(কী|কি|বিশ্লেষণ|দেখো|পড়ো|পড়ো|ব্যাখ্যা)|" +
            "what\\s+is\\s+in\\s+this\\s+(image|photo|picture)|describe\\s+this\\s+(image|photo|picture)|analyze\\s+this\\s+(image|photo|picture))",
        RegexOption.IGNORE_CASE
    )

    /**
     * Detects whether a Bengali, Banglish, or English user prompt is asking the AI
     * to generate/create a new image (e.g. "একটি পাখির ইমেজ তৈরি করে দাও", "generate an image of a bird").
     */
    fun isImageGenerationRequest(prompt: String): Boolean {
        val clean = prompt.trim()
        if (clean.length < 4) return false
        if (ANALYSIS_ONLY_REGEX.containsMatchIn(clean)) return false

        val lower = clean.lowercase()
        if (lower.startsWith("/image ") || lower.startsWith("/img ") || lower.startsWith("imagine ")) {
            return true
        }
        val hasNoun = IMAGE_NOUN_REGEX.containsMatchIn(clean)
        val hasCreateVerb = CREATE_VERB_REGEX.containsMatchIn(clean)
        if (hasNoun && hasCreateVerb) return true

        // Catch concise Bengali requests like "পাখির একটা ছবি দাও" / "সূর্যাস্তের ইমেজ দাও"
        if (hasNoun && Regex("(করে\\s*(দাও|দিন)|দাও|দিন|চাই|লাগবে|পাঠাও|পাঠান)$").containsMatchIn(clean)) {
            return true
        }
        return false
    }

    fun saveGeneratedBytesToAppFile(
        context: Context,
        bytes: ByteArray,
        mimeType: String = "image/png"
    ): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        // Keep storage tidy: retain latest 40 generated images
        val existing = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (existing.size >= 40) {
            existing.drop(39).forEach { runCatching { it.delete() } }
        }

        val ext = when {
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "png"
        }
        val file = File(dir, "livepilot_img_${System.currentTimeMillis()}.$ext")
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
        }
        return file
    }

    /**
     * Saves a generated image file into Android's public Gallery (Pictures/LivePilot AI)
     * via MediaStore — requires ZERO storage permissions on Android 11+ (minSdk 30).
     */
    fun saveImageFileToGallery(
        context: Context,
        filePath: String,
        displayLabel: String = "LivePilot_AI"
    ): Result<Uri> = runCatching {
        val src = File(filePath)
        require(src.exists() && src.length() > 0L) { "Generated image file not found." }

        val mimeType = when (src.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val safePrefix = displayLabel
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(24)
            .ifBlank { "LivePilot_AI" }
        val fileName = "${safePrefix}_${System.currentTimeMillis()}.${src.extension.ifBlank { "png" }}"

        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LivePilot AI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create Gallery entry.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } ?: error("Could not open Gallery output stream.")

            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /**
     * Shares a generated image via FileProvider to WhatsApp, Messenger, imo, Telegram, or Android share sheet.
     */
    fun shareImageFile(
        context: Context,
        filePath: String,
        caption: String = "",
        targetPackage: String? = null
    ): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false
        val appCtx = context.applicationContext
        val uri = runCatching {
            FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", file)
        }.getOrNull() ?: return false

        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (caption.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, caption.take(500))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!targetPackage.isNullOrBlank()) {
            val targeted = Intent(baseIntent).setPackage(targetPackage)
            val launched = runCatching {
                appCtx.startActivity(targeted)
                true
            }.getOrDefault(false)
            if (launched) return true
        }

        val chooser = Intent.createChooser(baseIntent, "AI ছবি শেয়ার করুন").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appCtx.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    fun decodeSampledBitmap(filePath: String, maxDimensionPx: Int = 1024): Bitmap? {
        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@runCatching null

            var sampleSize = 1
            while ((width / sampleSize) > maxDimensionPx || (height / sampleSize) > maxDimensionPx) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
            BitmapFactory.decodeFile(filePath, decodeOpts)
        }.getOrNull()
    }
}
