package com.example.firebase

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

data class DeviceProfileIdentity(
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val displayLabel: String
)

object DeviceIdentityHelper {

    private val NON_ALNUM = Regex("[^a-zA-Z0-9]+")

    @SuppressLint("HardwareIds")
    fun resolve(context: Context): DeviceProfileIdentity {
        val rawManufacturer = Build.MANUFACTURER.orEmpty().trim().ifBlank { "Android" }
        val rawModel = Build.MODEL.orEmpty().trim().ifBlank { "Device" }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().ifBlank { "emulator_fallback_${rawManufacturer}_${rawModel}" }

        val shortHash = sha256Hex("$rawManufacturer|$rawModel|$androidId").take(10)
        val cleanMfg = sanitizeSegment(rawManufacturer).take(16).ifBlank { "dev" }
        val cleanModel = sanitizeSegment(rawModel).take(24).ifBlank { "phone" }
        val deviceId = "${cleanMfg}_${cleanModel}_${shortHash}".take(64)

        val niceMfg = rawManufacturer.replaceFirstChar { it.uppercaseChar() }
        val displayLabel = if (rawModel.startsWith(rawManufacturer, ignoreCase = true)) {
            rawModel
        } else {
            "$niceMfg $rawModel"
        }

        return DeviceProfileIdentity(
            deviceId = deviceId,
            manufacturer = niceMfg.take(80),
            model = rawModel.take(100),
            displayLabel = displayLabel.take(100)
        )
    }

    private fun sanitizeSegment(raw: String): String =
        raw.lowercase()
            .replace(NON_ALNUM, "_")
            .trim('_')

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
