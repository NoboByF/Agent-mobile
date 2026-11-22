package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ApiFailureKind
import com.example.ai.ApiKeyPoolManager
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.GeminiToolSchemas
import com.example.automation.AutonomousMode
import com.example.automation.AutonomousTaskEngine
import com.example.automation.DrawingGestureHelper
import com.example.data.AppThemeMode
import com.example.data.AskAiMessage
import com.example.data.GeminiApiKeyEntry
import com.example.data.PilotPreferences
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import com.example.data.RoutineAccent
import com.example.data.SafetyGuardrails
import com.example.ui.components.formatAiRichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `app name matches LivePilot AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LivePilot AI", context.getString(R.string.app_name))
    }

    @Test
    fun `live setup payload strictly follows v1beta handshake schema and all 22 tools`() {
        val modelPath = GeminiLiveClient.normalizeModelPath("models/gemini-3.8-live")
        assertEquals("models/gemini-3.8-live", modelPath)

        val payload = GeminiLiveClient.buildSetupPayload(
            modelPath = modelPath,
            voiceName = "Kore",
            resumptionHandle = "handle_abc123",
            customInstructions = "Speak short Bengali"
        )
        val setup = payload.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))

        val genConfig = setup.getJSONObject("generationConfig")
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
        assertFalse(genConfig.has("responseMimeType"))

        assertTrue(setup.has("outputAudioTranscription"))
        assertTrue(setup.has("inputAudioTranscription"))
        assertEquals("handle_abc123", setup.getJSONObject("sessionResumption").getString("handle"))

        val systemText = setup
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(systemText.contains("Speak short Bengali"))

        val declarations = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(26, declarations.length())
    }

    @Test
    fun `custom system instruction appends user preferences cleanly`() {
        val defaultInstruction = GeminiToolSchemas.buildSystemInstruction("")
        assertFalse(defaultInstruction.contains("USER PERSONAL PREFERENCES"))

        val customized = GeminiToolSchemas.buildSystemInstruction("Speak fast")
        assertTrue(customized.contains("USER PERSONAL PREFERENCES"))
        assertTrue(customized.endsWith("Speak fast"))
    }

    @Test
    fun `preferences persist theme mode, HUD flags, and custom routines`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PilotPreferences(context)

        val updated = PilotSettings(
            themeMode = AppThemeMode.DARK,
            showTouchVisualizer = false,
            showFloatingCaptions = true
        )
        prefs.saveSettings(updated)
        val loaded = prefs.loadSettings()
        assertEquals(AppThemeMode.DARK, loaded.themeMode)
        assertFalse(loaded.showTouchVisualizer)
        assertTrue(loaded.showFloatingCaptions)

        val custom = PilotRoutine(
            id = "custom_test_1",
            title = "Night Quiet",
            subtitle = "Mute & 7AM alarm",
            prompt = "Mute phone and set alarm",
            accent = RoutineAccent.VIOLET,
            isBuiltIn = false
        )
        prefs.saveCustomRoutines(listOf(custom) + PilotSettings.DEFAULT_ROUTINES)
        val loadedRoutines = prefs.loadRoutines()
        assertTrue(loadedRoutines.any { it.id == "custom_test_1" && it.title == "Night Quiet" })
        assertTrue(loadedRoutines.size >= PilotSettings.DEFAULT_ROUTINES.size + 1)
    }

    @Test
    fun `formatAiRichText cleans markdown bold and bullets`() {
        val formatted = formatAiRichText("**Step 1:**\n- Open YouTube")
        assertEquals("Step 1:\n• Open YouTube", formatted.text)
        assertTrue(formatted.spanStyles.isNotEmpty())
    }

    @Test
    fun `safety guardrails block financial and security apps while permitting normal apps`() {
        assertTrue(SafetyGuardrails.isPackageBlocked("com.bKash.customerapp"))
        assertTrue(SafetyGuardrails.isPackageBlocked("com.konasl.nagad"))
        assertTrue(SafetyGuardrails.isPackageBlocked("com.dbbl.nexus.pay"))
        assertTrue(SafetyGuardrails.isPackageBlocked("com.ucb.upay"))
        assertTrue(SafetyGuardrails.isPackageBlocked("com.google.android.apps.authenticator2"))
        assertTrue(SafetyGuardrails.isPackageBlocked("com.x8bit.bitwarden"))

        assertFalse(SafetyGuardrails.isPackageBlocked("com.google.android.youtube"))
        assertFalse(SafetyGuardrails.isPackageBlocked("com.imo.android.imoim"))
        assertFalse(SafetyGuardrails.isPackageBlocked("com.whatsapp"))
        assertFalse(SafetyGuardrails.isPackageBlocked("com.lemon.lvoverseas"))
    }

    @Test
    fun `api failure classification distinguishes rate limit, daily quota, invalid key, and transient 404`() {
        val rpm = ApiKeyPoolManager.classifyFailure(httpCode = 429, message = "Requests per minute limit exceeded")
        assertEquals(ApiFailureKind.RATE_LIMIT_SHORT, rpm.kind)
        assertEquals(90_000L, rpm.cooldownMs)

        val daily = ApiKeyPoolManager.classifyFailure(httpCode = 429, message = "RESOURCE_EXHAUSTED: Quota exceeded for free tier per day")
        assertEquals(ApiFailureKind.QUOTA_EXHAUSTED, daily.kind)
        assertEquals(60 * 60_000L, daily.cooldownMs)

        val invalid = ApiKeyPoolManager.classifyFailure(httpCode = 400, message = "API_KEY_INVALID: API key not valid.")
        assertEquals(ApiFailureKind.INVALID_KEY, invalid.kind)

        val modelNotFound = ApiKeyPoolManager.classifyFailure(httpCode = 404, message = "models/foo is not found")
        assertEquals(ApiFailureKind.TRANSIENT, modelNotFound.kind)

        val wsModelErr = ApiKeyPoolManager.classifyFailure(wsCloseCode = 1008, message = "model not supported for bidiGenerateContent")
        assertEquals(ApiFailureKind.TRANSIENT, wsModelErr.kind)

        val wsQuota = ApiKeyPoolManager.classifyFailure(wsCloseCode = 1008, message = "Quota exceeded for project")
        assertEquals(ApiFailureKind.QUOTA_EXHAUSTED, wsQuota.kind)
    }

    @Test
    fun `api key pool rotates circularly across 3 keys and recovers after cooldown expires`() {
        val t0 = 1_000_000L
        val k1 = GeminiApiKeyEntry(id = "k1", label = "Key 1", key = "AIzaSyTestKeyOne00001")
        val k2 = GeminiApiKeyEntry(id = "k2", label = "Key 2", key = "AIzaSyTestKeyTwo00002")
        val k3 = GeminiApiKeyEntry(id = "k3", label = "Key 3", key = "AIzaSyTestKeyThr00003")

        PilotStateHub.updateSettings {
            PilotSettings(
                apiKey = k1.key,
                apiKeys = listOf(k1, k2, k3),
                activeApiKeyId = "k1",
                autoRotateApiKeys = true
            )
        }

        // Initial selection picks Key 1
        assertEquals("k1", ApiKeyPoolManager.selectBestKey(nowMs = t0).entry?.id)

        // Key 1 hits RPM limit -> rotates to Key 2
        val rpmErr = ApiKeyPoolManager.classifyFailure(httpCode = 429, message = "Rate limit per minute")
        val promoted1 = ApiKeyPoolManager.reportFailureAndRotate("k1", rpmErr, nowMs = t0)
        assertEquals("k2", promoted1?.id)
        assertEquals("k2", ApiKeyPoolManager.selectBestKey(nowMs = t0 + 1_000L).entry?.id)

        // Key 2 is invalid -> rotates to Key 3
        val invalidErr = ApiKeyPoolManager.classifyFailure(httpCode = 401, message = "API_KEY_INVALID")
        val promoted2 = ApiKeyPoolManager.reportFailureAndRotate("k2", invalidErr, nowMs = t0 + 2_000L)
        assertEquals("k3", promoted2?.id)

        // Key 3 hits daily quota -> all keys temporarily unavailable
        val dailyErr = ApiKeyPoolManager.classifyFailure(httpCode = 429, message = "Daily quota exhausted")
        val promoted3 = ApiKeyPoolManager.reportFailureAndRotate("k3", dailyErr, nowMs = t0 + 3_000L)
        assertNull(promoted3)

        val exhausted = ApiKeyPoolManager.selectBestKey(nowMs = t0 + 4_000L)
        assertNull(exhausted.entry)
        assertTrue(exhausted.nextAvailableInSec in 80L..90L)

        // After 95s, Key 1's 90s cooldown expires and it becomes available again automatically
        val recovered = ApiKeyPoolManager.selectBestKey(nowMs = t0 + 95_000L)
        assertNotNull(recovered.entry)
        assertEquals("k1", recovered.entry?.id)

        // When autoRotateApiKeys is false and active key is cooling down, it must not jump to another key
        PilotStateHub.updateSettings { it.copy(autoRotateApiKeys = false, activeApiKeyId = "k3") }
        assertNull(ApiKeyPoolManager.selectBestKey(nowMs = t0 + 95_000L).entry)

        // Resetting health restores all keys
        ApiKeyPoolManager.resetAllKeyHealth()
        assertEquals("k3", ApiKeyPoolManager.selectBestKey(nowMs = t0 + 95_000L).entry?.id)
    }

    @Test
    fun `addKeysFromInput parses multi-line keys, deduplicates, and legacy single key migrates cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawPrefs = context.getSharedPreferences("livepilot_ai_secure_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().putString("gemini_api_key", "AIzaSyLegacySingleKey123").commit()

        val prefs = PilotPreferences(context)
        val migrated = prefs.loadSettings()
        assertEquals(1, migrated.apiKeys.size)
        assertEquals("AIzaSyLegacySingleKey123", migrated.apiKeys.first().key)
        assertEquals("Key 1", migrated.apiKeys.first().label)

        PilotStateHub.updateSettings { migrated }
        val (added, dupes) = ApiKeyPoolManager.addKeysFromInput(
            "AIzaSyLegacySingleKey123\nAIzaSyBackupKeyTwo456, AIzaSyBackupKeyThree789"
        )
        assertEquals(2, added)
        assertEquals(1, dupes)
        assertEquals(3, PilotStateHub.settings.value.apiKeys.size)

        // Persist and reload full multi-key pool
        prefs.saveSettings(PilotStateHub.settings.value)
        val reloaded = prefs.loadSettings()
        assertEquals(3, reloaded.apiKeys.size)
        assertTrue(reloaded.autoRotateApiKeys)
    }

    @Test
    fun `buildSanitizedContents drops leading model turn and merges consecutive user turns`() {
        val dirtyHistory = listOf(
            AskAiMessage(id = 1, isUser = false, text = "Old assistant greeting", timestamp = "12:00"),
            AskAiMessage(id = 2, isUser = true, text = "First question (failed previously)", timestamp = "12:01"),
            AskAiMessage(id = 3, isUser = true, text = "Second question", timestamp = "12:02"),
            AskAiMessage(id = 4, isUser = false, text = "Answer", timestamp = "12:03"),
            AskAiMessage(id = 5, isUser = true, text = "Follow up", timestamp = "12:04")
        )
        val contents = GeminiRestClient.buildSanitizedContents(dirtyHistory)
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals(
            "First question (failed previously)\nSecond question",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        )
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    @Test
    fun `autonomous mode resolves Bengali and English drawing vs workflow goals accurately`() {
        assertEquals(AutonomousMode.DRAWING, AutonomousTaskEngine.resolveMode("এখানে একটি পাখির ছবি আকেন", "AUTO"))
        assertEquals(AutonomousMode.DRAWING, AutonomousTaskEngine.resolveMode("Draw a bird on this canvas", "AUTO"))
        assertEquals(AutonomousMode.UI_WORKFLOW, AutonomousTaskEngine.resolveMode("Open Chrome and fill out the registration form", "AUTO"))
        assertEquals(AutonomousMode.DRAWING, AutonomousTaskEngine.resolveMode("Anything", "DRAWING"))
    }

    @Test
    fun `drawing helper parses curved stroke JSON and maps canvas coordinates safely`() {
        val json = """
            [
              {"label": "Head", "points": [[400, 300], [500, 220], [600, 300]]},
              {"label": "Eye", "points": [[520, 260]]}
            ]
        """.trimIndent()
        val strokes = DrawingGestureHelper.parseStrokesFromJson(json)
        assertEquals(2, strokes.size)
        assertEquals("Head", strokes[0].label)
        assertEquals(3, strokes[0].points.size)
        assertEquals(1, strokes[1].points.size)

        // Canvas box [350, 500, 730, 600] on 1080x2400 screen -> center (500,500) maps inside box
        val (px, py) = DrawingGestureHelper.mapPointToScreen(
            x = 500f,
            y = 500f,
            screenWidth = 1080,
            screenHeight = 2400,
            normalizedSpace = true,
            canvasBoundsNorm = intArrayOf(350, 500, 730, 600)
        )
        assertTrue(px in 570f..600f)
        assertTrue(py in 1300f..1340f)

        val cleanedJson = GeminiRestClient.extractCleanJsonObject("```json\n{\"done\": true, \"action\": \"CLICK\"}\n```")
        assertTrue(cleanedJson.getBoolean("done"))
        assertEquals("CLICK", cleanedJson.getString("action"))
    }
}
