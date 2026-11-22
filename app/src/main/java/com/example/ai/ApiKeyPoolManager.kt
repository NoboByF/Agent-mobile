package com.example.ai

import com.example.data.GeminiApiKeyEntry
import com.example.data.LogLevel
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import java.util.UUID

enum class ApiFailureKind {
    RATE_LIMIT_SHORT,
    QUOTA_EXHAUSTED,
    INVALID_KEY,
    TRANSIENT
}

data class ApiFailureClassification(
    val kind: ApiFailureKind,
    val cooldownMs: Long,
    val summary: String
)

data class KeySelectionOutcome(
    val entry: GeminiApiKeyEntry?,
    val nextAvailableInSec: Long = 0L,
    val totalEnabledKeys: Int = 0
)

/**
 * Thread-safe Multi-Gemini API Key Pool & Failover Engine.
 * Handles smart quota/rate-limit/invalid-key classification, cooldown timers,
 * automatic circular rotation across REST and Live WebSocket sessions, and
 * safe masked logging (never logs raw API keys).
 */
object ApiKeyPoolManager {

    private const val COOLDOWN_SHORT_RATE_LIMIT_MS = 90_000L       // 90 seconds (RPM/TPM limit)
    private const val COOLDOWN_QUOTA_STANDARD_MS = 15 * 60_000L    // 15 minutes
    private const val COOLDOWN_QUOTA_DAILY_MS = 60 * 60_000L       // 60 minutes (RPD / daily quota)

    private val poolLock = Any()

    /**
     * Classifies HTTP/WebSocket errors accurately so transient network or 404 model errors
     * never falsely penalize healthy API keys, while quota/rate-limit/auth errors trigger failover.
     */
    fun classifyFailure(
        httpCode: Int? = null,
        wsCloseCode: Int? = null,
        message: String? = null
    ): ApiFailureClassification {
        val raw = message.orEmpty()
        val lower = raw.lowercase()

        // Never penalize key for wrong model ID (404) or client prompt formatting issues
        if (httpCode == 404 || (lower.contains("not found") && lower.contains("model"))) {
            return ApiFailureClassification(
                kind = ApiFailureKind.TRANSIENT,
                cooldownMs = 0L,
                summary = raw.take(100)
            )
        }

        // Invalid / expired / revoked / unauthorized key detection
        val isInvalidKeyMessage = lower.contains("api_key_invalid") ||
            lower.contains("api key not valid") ||
            lower.contains("invalid api key") ||
            lower.contains("api key expired") ||
            lower.contains("key expired") ||
            lower.contains("unregistered callers") ||
            lower.contains("permission_denied") ||
            lower.contains("permission denied") ||
            lower.contains("invalid argument: api key")

        if (isInvalidKeyMessage || httpCode == 401 || (httpCode == 403 && !lower.contains("quota"))) {
            return ApiFailureClassification(
                kind = ApiFailureKind.INVALID_KEY,
                cooldownMs = 0L,
                summary = "Invalid or unauthorized API key"
            )
        }

        // Quota / Rate Limit detection
        val isQuotaOrRate = httpCode == 429 ||
            lower.contains("resource_exhausted") ||
            lower.contains("quota") ||
            lower.contains("rate limit") ||
            lower.contains("rate-limit") ||
            lower.contains("too many requests") ||
            lower.contains("exceeded") ||
            lower.contains("limit: 0") ||
            lower.contains("billing") ||
            lower.contains("exhausted")

        if (isQuotaOrRate) {
            val isDailyOrHardQuota = lower.contains("per day") ||
                lower.contains("daily") ||
                lower.contains("rpd") ||
                lower.contains("limit: 0") ||
                lower.contains("billing") ||
                lower.contains("free tier")

            val isMinuteRateLimit = !isDailyOrHardQuota && (
                lower.contains("per minute") ||
                    lower.contains("rpm") ||
                    lower.contains("tpm") ||
                    lower.contains("rate limit") ||
                    lower.contains("rate-limit") ||
                    lower.contains("too many requests")
                )

            return when {
                isDailyOrHardQuota -> ApiFailureClassification(
                    kind = ApiFailureKind.QUOTA_EXHAUSTED,
                    cooldownMs = COOLDOWN_QUOTA_DAILY_MS,
                    summary = "Daily quota exhausted (60m cooldown)"
                )
                isMinuteRateLimit -> ApiFailureClassification(
                    kind = ApiFailureKind.RATE_LIMIT_SHORT,
                    cooldownMs = COOLDOWN_SHORT_RATE_LIMIT_MS,
                    summary = "Rate limit hit (90s cooldown)"
                )
                else -> ApiFailureClassification(
                    kind = ApiFailureKind.QUOTA_EXHAUSTED,
                    cooldownMs = COOLDOWN_QUOTA_STANDARD_MS,
                    summary = "Quota exhausted (15m cooldown)"
                )
            }
        }

        // WebSocket policy/server close codes when paired with key/quota hints
        if (wsCloseCode == 1008) {
            return if (lower.contains("key") || lower.contains("auth") || lower.contains("unauth")) {
                ApiFailureClassification(
                    kind = ApiFailureKind.INVALID_KEY,
                    cooldownMs = 0L,
                    summary = "WS 1008: API key rejected"
                )
            } else if (lower.contains("model") || lower.contains("not found") || lower.contains("not supported")) {
                ApiFailureClassification(
                    kind = ApiFailureKind.TRANSIENT,
                    cooldownMs = 0L,
                    summary = raw.take(100)
                )
            } else {
                ApiFailureClassification(
                    kind = ApiFailureKind.QUOTA_EXHAUSTED,
                    cooldownMs = COOLDOWN_QUOTA_STANDARD_MS,
                    summary = "WS 1008: Policy/Quota limit"
                )
            }
        }

        if (wsCloseCode == 1011 && (lower.contains("quota") || lower.contains("limit") || lower.contains("resource"))) {
            return ApiFailureClassification(
                kind = ApiFailureKind.QUOTA_EXHAUSTED,
                cooldownMs = COOLDOWN_QUOTA_STANDARD_MS,
                summary = "WS 1011: Server quota limit"
            )
        }

        return ApiFailureClassification(
            kind = ApiFailureKind.TRANSIENT,
            cooldownMs = 0L,
            summary = raw.take(100)
        )
    }

    /**
     * Selects the best currently available API key from the user's pool.
     * Prefers the sticky active key while healthy; otherwise rotates circularly
     * to the next enabled, non-invalid, non-cooling-down key.
     */
    fun selectBestKey(
        settings: PilotSettings = PilotStateHub.settings.value,
        excludeIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): KeySelectionOutcome = synchronized(poolLock) {
        val pool = effectivePool(settings)
        val enabledKeys = pool.filter { it.enabled && it.key.isNotBlank() }
        if (enabledKeys.isEmpty()) {
            return KeySelectionOutcome(entry = null, totalEnabledKeys = 0)
        }

        val activeIndex = enabledKeys.indexOfFirst { it.id == settings.activeApiKeyId }
            .takeIf { it >= 0 } ?: 0

        if (!settings.autoRotateApiKeys) {
            val activeCandidate = enabledKeys[activeIndex]
            return if (activeCandidate.id !in excludeIds && activeCandidate.isAvailable(nowMs)) {
                KeySelectionOutcome(entry = activeCandidate, totalEnabledKeys = enabledKeys.size)
            } else {
                val soonestSec = if (!activeCandidate.isInvalid && activeCandidate.isCoolingDown(nowMs)) {
                    activeCandidate.remainingCooldownSec(nowMs)
                } else {
                    0L
                }
                KeySelectionOutcome(
                    entry = null,
                    nextAvailableInSec = soonestSec,
                    totalEnabledKeys = enabledKeys.size
                )
            }
        }

        // Check in circular order starting from activeIndex
        for (offset in enabledKeys.indices) {
            val candidate = enabledKeys[(activeIndex + offset) % enabledKeys.size]
            if (candidate.id in excludeIds) continue
            if (candidate.isAvailable(nowMs)) {
                return KeySelectionOutcome(
                    entry = candidate,
                    totalEnabledKeys = enabledKeys.size
                )
            }
        }

        // If auto-rotation is off or all keys are cooling down, compute earliest cooldown expiry
        val coolingKeys = enabledKeys.filter { !it.isInvalid && it.isCoolingDown(nowMs) }
        val soonestSec = coolingKeys.minOfOrNull { it.remainingCooldownSec(nowMs) } ?: 0L
        return KeySelectionOutcome(
            entry = null,
            nextAvailableInSec = soonestSec,
            totalEnabledKeys = enabledKeys.size
        )
    }

    fun reportSuccess(
        keyId: String,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(poolLock) {
        PilotStateHub.updateSettings { current ->
            val pool = effectivePool(current)
            if (pool.none { it.id == keyId }) return@updateSettings current
            val updatedPool = pool.map { entry ->
                if (entry.id == keyId) {
                    entry.copy(
                        cooldownUntilMs = 0L,
                        isInvalid = false,
                        lastError = null,
                        totalRequests = entry.totalRequests + 1,
                        lastUsedAtMs = nowMs
                    )
                } else {
                    entry
                }
            }
            val activeEntry = updatedPool.firstOrNull { it.id == keyId }
            current.copy(
                apiKeys = updatedPool,
                activeApiKeyId = keyId,
                apiKey = activeEntry?.key ?: current.apiKey
            )
        }
    }

    /**
     * Marks the failed key according to failure classification and rotates activeApiKeyId
     * to the next healthy key if auto-rotation is enabled.
     * Returns the newly promoted healthy key, or null if none remain.
     */
    fun reportFailureAndRotate(
        failedKeyId: String,
        classification: ApiFailureClassification,
        nowMs: Long = System.currentTimeMillis()
    ): GeminiApiKeyEntry? = synchronized(poolLock) {
        if (classification.kind == ApiFailureKind.TRANSIENT) {
            return null
        }

        var promotedKey: GeminiApiKeyEntry? = null
        var failedLabel = failedKeyId

        PilotStateHub.updateSettings { current ->
            val pool = effectivePool(current)
            val failedIdx = pool.indexOfFirst { it.id == failedKeyId }
            if (failedIdx < 0) return@updateSettings current

            failedLabel = pool[failedIdx].label
            val updatedPool = pool.map { entry ->
                if (entry.id == failedKeyId) {
                    when (classification.kind) {
                        ApiFailureKind.INVALID_KEY -> entry.copy(
                            isInvalid = true,
                            cooldownUntilMs = 0L,
                            lastError = classification.summary,
                            lastUsedAtMs = nowMs
                        )
                        ApiFailureKind.RATE_LIMIT_SHORT,
                        ApiFailureKind.QUOTA_EXHAUSTED -> entry.copy(
                            cooldownUntilMs = nowMs + classification.cooldownMs,
                            lastError = classification.summary,
                            lastUsedAtMs = nowMs
                        )
                        ApiFailureKind.TRANSIENT -> entry
                    }
                } else {
                    entry
                }
            }

            if (current.autoRotateApiKeys) {
                for (offset in 1 until updatedPool.size) {
                    val candidate = updatedPool[(failedIdx + offset) % updatedPool.size]
                    if (candidate.isAvailable(nowMs)) {
                        promotedKey = candidate
                        break
                    }
                }
            }

            val nextActiveId = promotedKey?.id ?: current.activeApiKeyId
            val nextMirrorKey = updatedPool.firstOrNull { it.id == nextActiveId }?.key ?: current.apiKey
            current.copy(
                apiKeys = updatedPool,
                activeApiKeyId = nextActiveId,
                apiKey = nextMirrorKey
            )
        }

        val next = promotedKey
        if (next != null) {
            PilotStateHub.log(
                LogLevel.INFO,
                "KeyPool",
                "$failedLabel (${classification.summary}) -> auto-switched to ${next.label} (${next.maskedKey()})"
            )
        } else {
            PilotStateHub.log(
                LogLevel.ERROR,
                "KeyPool",
                "$failedLabel (${classification.summary}) -> no other healthy backup API keys available."
            )
        }
        return promotedKey
    }

    /**
     * Parses one or multiple keys (newline, comma, or whitespace separated),
     * deduplicates against existing keys, and adds them to the pool.
     * Returns pair of (addedCount, skippedDuplicateCount).
     */
    fun addKeysFromInput(rawKeysInput: String, customLabel: String = ""): Pair<Int, Int> =
        synchronized(poolLock) {
            val tokens = rawKeysInput
                .split('\n', ',', ';', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (tokens.isEmpty()) return 0 to 0

            var added = 0
            var duplicates = 0

            PilotStateHub.updateSettings { current ->
                val pool = effectivePool(current).toMutableList()
                val existingKeyStrings = pool.map { it.key.trim() }.toMutableSet()

                tokens.forEachIndexed { idx, token ->
                    if (token in existingKeyStrings) {
                        duplicates++
                    } else {
                        existingKeyStrings.add(token)
                        val keyNumber = pool.size + 1
                        val label = when {
                            customLabel.isNotBlank() && tokens.size == 1 -> customLabel.trim()
                            customLabel.isNotBlank() -> "${customLabel.trim()} ${idx + 1}"
                            else -> "Key $keyNumber"
                        }
                        pool.add(
                            GeminiApiKeyEntry(
                                id = "key_${UUID.randomUUID().toString().take(8)}",
                                label = label,
                                key = token,
                                enabled = true
                            )
                        )
                        added++
                    }
                }

                val activeId = current.activeApiKeyId
                    ?.takeIf { id -> pool.any { it.id == id && it.isAvailable() } }
                    ?: pool.firstOrNull { it.isAvailable() }?.id
                    ?: pool.firstOrNull()?.id
                val mirrorKey = pool.firstOrNull { it.id == activeId }?.key ?: current.apiKey

                current.copy(
                    apiKeys = pool,
                    activeApiKeyId = activeId,
                    apiKey = mirrorKey
                )
            }

            if (added > 0) {
                PilotStateHub.log(LogLevel.INFO, "KeyPool", "Added $added Gemini API key(s) to rotation pool.")
            }
            return added to duplicates
        }

    fun removeKey(keyId: String) = synchronized(poolLock) {
        PilotStateHub.updateSettings { current ->
            val remaining = effectivePool(current).filterNot { it.id == keyId }
            val nextActive = if (current.activeApiKeyId == keyId) {
                remaining.firstOrNull { it.isAvailable() }?.id
                    ?: remaining.firstOrNull { it.enabled }?.id
                    ?: remaining.firstOrNull()?.id
            } else {
                current.activeApiKeyId
            }
            val mirrorKey = remaining.firstOrNull { it.id == nextActive }?.key.orEmpty()
            current.copy(
                apiKeys = remaining,
                activeApiKeyId = nextActive,
                apiKey = mirrorKey
            )
        }
    }

    fun setActiveKey(keyId: String) = synchronized(poolLock) {
        PilotStateHub.updateSettings { current ->
            val pool = effectivePool(current)
            val target = pool.firstOrNull { it.id == keyId } ?: return@updateSettings current
            // Also clear cooldown/invalid state if user manually selects this key
            val updated = pool.map { entry ->
                if (entry.id == keyId) {
                    entry.copy(enabled = true, cooldownUntilMs = 0L, isInvalid = false, lastError = null)
                } else {
                    entry
                }
            }
            current.copy(
                apiKeys = updated,
                activeApiKeyId = target.id,
                apiKey = target.key
            )
        }
    }

    fun toggleKeyEnabled(keyId: String) = synchronized(poolLock) {
        PilotStateHub.updateSettings { current ->
            val pool = effectivePool(current)
            val updated = pool.map { entry ->
                if (entry.id == keyId) {
                    val nextEnabled = !entry.enabled
                    entry.copy(
                        enabled = nextEnabled,
                        cooldownUntilMs = if (nextEnabled) 0L else entry.cooldownUntilMs,
                        isInvalid = if (nextEnabled) false else entry.isInvalid,
                        lastError = if (nextEnabled) null else entry.lastError
                    )
                } else {
                    entry
                }
            }
            val currentActiveStillEnabled = updated.any { it.id == current.activeApiKeyId && it.enabled }
            val nextActiveId = if (currentActiveStillEnabled) {
                current.activeApiKeyId
            } else {
                updated.firstOrNull { it.isAvailable() }?.id
                    ?: updated.firstOrNull { it.enabled }?.id
            }
            val mirrorKey = updated.firstOrNull { it.id == nextActiveId }?.key.orEmpty()
            current.copy(
                apiKeys = updated,
                activeApiKeyId = nextActiveId,
                apiKey = mirrorKey
            )
        }
    }

    fun resetAllKeyHealth() = synchronized(poolLock) {
        PilotStateHub.updateSettings { current ->
            val updated = effectivePool(current).map { entry ->
                entry.copy(cooldownUntilMs = 0L, isInvalid = false, lastError = null)
            }
            val nextActive = current.activeApiKeyId
                ?.takeIf { id -> updated.any { it.id == id && it.enabled } }
                ?: updated.firstOrNull { it.enabled }?.id
            val mirrorKey = updated.firstOrNull { it.id == nextActive }?.key ?: current.apiKey
            current.copy(
                apiKeys = updated,
                activeApiKeyId = nextActive,
                apiKey = mirrorKey
            )
        }
        PilotStateHub.log(LogLevel.INFO, "KeyPool", "Reset cooldown & error status for all saved API keys.")
    }

    fun buildNoKeyAvailableMessage(outcome: KeySelectionOutcome): String {
        return when {
            outcome.totalEnabledKeys == 0 ->
                "Add at least one Gemini API Key in Settings first."
            outcome.nextAvailableInSec > 0 -> {
                val mins = outcome.nextAvailableInSec / 60L
                val secs = outcome.nextAvailableInSec % 60L
                val waitStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                "All ${outcome.totalEnabledKeys} API keys are in quota cooldown. Retry in ~$waitStr or add another key in Settings."
            }
            else ->
                "All ${outcome.totalEnabledKeys} enabled API keys are invalid or exhausted. Add a fresh key or tap Reset Status in Settings."
        }
    }

    fun effectivePool(settings: PilotSettings): List<GeminiApiKeyEntry> {
        if (settings.apiKeys.isNotEmpty()) return settings.apiKeys
        val single = settings.apiKey.trim()
        return if (single.isNotBlank()) {
            listOf(
                GeminiApiKeyEntry(
                    id = "key_primary",
                    label = "Key 1",
                    key = single,
                    enabled = true
                )
            )
        } else {
            emptyList()
        }
    }
}
