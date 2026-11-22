package com.example

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ApiFailureKind
import com.example.ai.ApiKeyPoolManager
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.GeminiToolSchemas
import com.example.automation.AutonomousMode
import com.example.automation.AutonomousTaskEngine
import com.example.automation.DrawingGestureHelper
import com.example.automation.SmartSearchAndFinder
import com.example.data.AppThemeMode
import com.example.data.AskAiMessage
import com.example.data.DiscoveredModel
import com.example.data.GeminiApiKeyEntry
import com.example.data.PilotPreferences
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import com.example.data.RoutineAccent
import com.example.data.SafetyGuardrails
import com.example.data.VoiceLanguage
import com.example.messaging.IncomingMessageListener
import com.example.messaging.SensitiveMessageGuard
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
        assertEquals(29, declarations.length())
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
    fun `single key never enters 60m lockout and generic billing boilerplate stays short cooldown`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PilotPreferences(context)
        val t0 = 2_000_000L

        val googleBoilerplate429 = ApiKeyPoolManager.classifyFailure(
            httpCode = 429,
            message = "You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: free_tier_requests."
        )
        assertEquals(ApiFailureKind.RATE_LIMIT_SHORT, googleBoilerplate429.kind)

        val singleKey = GeminiApiKeyEntry(
            id = "solo_key",
            label = "Gemini",
            key = "AIzaSySoloTestKey00001",
            enabled = true
        )
        PilotStateHub.updateSettings {
            PilotSettings(
                apiKey = singleKey.key,
                apiKeys = listOf(singleKey),
                activeApiKeyId = singleKey.id,
                autoRotateApiKeys = true
            )
        }

        val dailyErr = ApiKeyPoolManager.classifyFailure(
            httpCode = 429,
            message = "Daily quota exhausted"
        )
        val promoted = ApiKeyPoolManager.reportFailureAndRotate("solo_key", dailyErr, nowMs = t0)
        assertNull(promoted)

        // Single key must remain immediately selectable (never 0/1 READY / WAIT 59m)
        val afterFailure = PilotStateHub.settings.value
        assertEquals(0L, afterFailure.apiKeys.first().cooldownUntilMs)
        assertEquals(1, afterFailure.healthyKeyCount(nowMs = t0 + 1_000L))
        assertEquals("solo_key", ApiKeyPoolManager.selectBestKey(nowMs = t0 + 1_000L).entry?.id)

        // Legacy persisted cooldown on a single key heals automatically on loadSettings()
        prefs.saveSettings(
            afterFailure.copy(
                apiKeys = listOf(singleKey.copy(cooldownUntilMs = t0 + 3_600_000L, lastError = "Old cooldown"))
            )
        )
        val healed = prefs.loadSettings()
        assertEquals(0L, healed.apiKeys.first().cooldownUntilMs)
        assertNull(healed.apiKeys.first().lastError)
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

    @Test
    fun `voice language lock persists and builds strict Bengali system instruction header`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PilotPreferences(context)

        prefs.saveSettings(PilotSettings(voiceLanguage = VoiceLanguage.BENGALI_BD))
        val loaded = prefs.loadSettings()
        assertEquals(VoiceLanguage.BENGALI_BD, loaded.voiceLanguage)

        val bnPrompt = GeminiToolSchemas.buildSystemInstruction("", VoiceLanguage.BENGALI_BD)
        assertTrue(bnPrompt.contains("bn-BD"))
        assertTrue(bnPrompt.contains("NEVER interpret the user's audio as Hindi"))

        val enPrompt = GeminiToolSchemas.buildSystemInstruction("", VoiceLanguage.ENGLISH_US)
        assertTrue(enPrompt.contains("en-US"))
    }

    @Test
    fun `smart search expands Bengali and English UI synonyms and performs fuzzy matching`() {
        val searchTerms = SmartSearchAndFinder.expandSynonyms("সার্চ")
        assertTrue(searchTerms.contains("search"))
        assertTrue(searchTerms.contains("খুঁজুন"))

        val settingsTerms = SmartSearchAndFinder.expandSynonyms("সেটিংস")
        assertTrue(settingsTerms.contains("settings"))

        assertTrue(SmartSearchAndFinder.isFuzzyClose("youtube", "youtbe"))
        assertTrue(SmartSearchAndFinder.isFuzzyClose("settings", "setings"))
        assertFalse(SmartSearchAndFinder.isFuzzyClose("search", "camera"))
    }

    @Test
    fun `dual brain migrates legacy placeholder models and resolves non-live vision fallbacks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawPrefs = context.getSharedPreferences("livepilot_ai_secure_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .clear()
            .putString("live_model_id", "gemini-3.8-live")
            .putString("text_model_id", "gemini-4-hydrogen")
            .commit()

        val prefs = PilotPreferences(context)
        val migrated = prefs.loadSettings()
        assertEquals(PilotSettings.DEFAULT_LIVE_MODEL, migrated.liveModel)
        assertEquals(PilotSettings.DEFAULT_AGENT_MODEL, migrated.agentModel)
        assertEquals(PilotSettings.DEFAULT_TEXT_MODEL, migrated.textModel)
        assertTrue(migrated.verifyTaskCompletion)

        prefs.saveSettings(migrated.copy(agentModel = "gemini-2.5-pro", verifyTaskCompletion = false))
        val reloaded = prefs.loadSettings()
        assertEquals("gemini-2.5-pro", reloaded.agentModel)
        assertFalse(reloaded.verifyTaskCompletion)

        val fallbacks = GeminiRestClient.resolveAgentFallbackModels(
            primaryModel = "gemini-2.5-pro",
            discovered = listOf(
                DiscoveredModel("gemini-2.5-flash-native-audio-preview-12-2025", "Live", true, true),
                DiscoveredModel("gemini-2.5-flash", "Flash", false, true),
                DiscoveredModel("gemini-3.0-pro-exp", "Pro 3", false, true)
            )
        )
        assertEquals("gemini-2.5-pro", fallbacks.first())
        assertTrue(fallbacks.contains("gemini-2.5-flash"))
        assertTrue(fallbacks.contains("gemini-3.0-pro-exp"))
        assertFalse(fallbacks.any { it.contains("native-audio") })
        assertTrue(GeminiToolSchemas.LIVE_SYSTEM_INSTRUCTION.contains("DUAL-BRAIN ARCHITECTURE"))
        assertTrue(GeminiToolSchemas.LIVE_SYSTEM_INSTRUCTION.contains("VERIFIED COMPLETED"))
    }

    @Test
    fun `high demand 503 classification and canvas grid timing behave reliably`() {
        val transient = com.example.ai.ApiKeyPoolManager.classifyFailure(
            httpCode = 503,
            message = "HTTP 503: This model is currently experiencing high demand."
        )
        assertEquals(com.example.ai.ApiFailureKind.TRANSIENT, transient.kind)

        val dur = DrawingGestureHelper.computeRecommendedDurationMs(
            screenPoints = listOf(100f to 100f, 300f to 300f),
            requestedMs = 0L
        )
        assertTrue(dur >= 260L)

        val src = android.graphics.Bitmap.createBitmap(256, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val gridded = DrawingGestureHelper.drawCoordinateGridOverlay(src)
        assertEquals(256, gridded.width)
        assertEquals(512, gridded.height)
        src.recycle()
        gridded.recycle()
    }

    @Test
    fun `messaging style extractor filters own RemoteInput replies and prevents WhatsApp self-reply loops`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val me = Person.Builder().setName("You").build()
        val sumaiya = Person.Builder().setName("সুমাইয়া").build()

        // 1. Incoming real message from সুমাইয়া -> must be extracted as incoming
        val incomingNotif = NotificationCompat.Builder(context, "test_ch")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("সুমাইয়া")
            .setContentText("হাই")
            .setStyle(
                NotificationCompat.MessagingStyle(me)
                    .addMessage("হাই", System.currentTimeMillis(), sumaiya)
            )
            .build()

        val extractedIncoming = SensitiveMessageGuard.extractIncomingMessage(incomingNotif)
        assertFalse(extractedIncoming.isSelfOrSystem)
        assertEquals("সুমাইয়া", extractedIncoming.senderName)
        assertEquals("হাই", extractedIncoming.messageText)

        // 2. Re-posted WhatsApp notification after our own RemoteInput reply (person == null or self) -> MUST be skipped!
        val selfEchoNotif = NotificationCompat.Builder(context, "test_ch")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("সুমাইয়া")
            .setContentText("হ্যালো! কেমন আছেন?")
            .setStyle(
                NotificationCompat.MessagingStyle(me)
                    .addMessage("হাই", System.currentTimeMillis() - 1000L, sumaiya)
                    .addMessage("হ্যালো! কেমন আছেন?", System.currentTimeMillis(), null as Person?)
            )
            .build()

        val extractedSelf = SensitiveMessageGuard.extractIncomingMessage(selfEchoNotif)
        assertTrue(extractedSelf.isSelfOrSystem)

        // 3. Sent-reply echo guard also blocks exact sent reply even if MessagingStyle is stripped
        IncomingMessageListener.registerSentReply("com.whatsapp", "সুমাইয়া", "হ্যালো! কেমন আছেন?")
        assertTrue(IncomingMessageListener.isRecentSelfEcho("com.whatsapp", "সুমাইয়া", "হ্যালো! কেমন আছেন?"))
        assertFalse(IncomingMessageListener.isRecentSelfEcho("com.whatsapp", "সুমাইয়া", "ভালো আছি, তুমি কেমন আছো?"))
    }

    @Test
    fun `sensitive question guard detects financial, OTP, and private questions and defers politely`() {
        val bkash = SensitiveMessageGuard.assessSensitivityLocally("ভাই আমাকে ৫০০০ টাকা বিকাশ করে দাও জরুরি")
        assertTrue(bkash.isSensitive)
        assertEquals("আমি জেনে বলছি।", bkash.deferralReply)

        val otp = SensitiveMessageGuard.assessSensitivityLocally("Can you send me the OTP verification code?")
        assertTrue(otp.isSensitive)
        assertEquals("Let me check and get back to you.", otp.deferralReply)

        val address = SensitiveMessageGuard.assessSensitivityLocally("তোমার বাসার ঠিকানা দাও এখন")
        assertTrue(address.isSensitive)
        assertEquals("আমি জেনে বলছি।", address.deferralReply)

        val casual = SensitiveMessageGuard.assessSensitivityLocally("হাই কেমন আছো?")
        assertFalse(casual.isSensitive)
    }

    @Test
    fun `non-destructive safety guardrails block irreversible actions while permitting safe self-healing controls`() {
        assertTrue(SafetyGuardrails.isDestructiveAction("Factory Reset", "Reset phone", "Open Settings"))
        assertTrue(SafetyGuardrails.isDestructiveAction("Uninstall", "Tap Uninstall", "Check app info"))
        assertTrue(SafetyGuardrails.isDestructiveAction("Pay Now", "Confirm checkout", "Search shoes on Daraz"))
        assertTrue(SafetyGuardrails.isDestructiveAction("Delete", "Delete item", "Open gallery"))

        // Benign navigation & popup self-healing buttons are permitted
        assertFalse(SafetyGuardrails.isDestructiveAction("Skip Ad", "Dismiss popup", "Play song on YouTube"))
        assertFalse(SafetyGuardrails.isDestructiveAction("Not now", "Close dialog", "Open Chrome"))
        assertFalse(SafetyGuardrails.isDestructiveAction("Search", "Tap search", "Search Dhaka weather"))
    }

    @Test
    fun `device identity is valid for Firestore path and personal data plus deleted models persist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = com.example.firebase.DeviceIdentityHelper.resolve(context)
        assertTrue(profile.deviceId.matches(Regex("^[a-zA-Z0-9_\\-]+$")))
        assertTrue(profile.deviceId.length in 4..64)
        assertEquals(profile.deviceId, com.example.firebase.DeviceIdentityHelper.resolve(context).deviceId)

        val prefs = PilotPreferences(context)
        val savedSettings = PilotSettings(
            deletedModels = listOf("gemini-2.0-flash", "gemini-1.5-pro"),
            handsFreeVoiceEscalationEnabled = true
        )
        prefs.saveSettings(savedSettings)
        val reloadedSettings = prefs.loadSettings()
        assertTrue(reloadedSettings.deletedModels.contains("gemini-2.0-flash"))
        assertTrue(reloadedSettings.handsFreeVoiceEscalationEnabled)

        val personalFact = com.example.data.PersonalDataItem(
            id = "pd_test_1",
            title = "আমার পেশা",
            content = "আমি একজন Android ডেভেলপার।",
            category = "কাজ / পেশা",
            shareInAutoReply = true
        )
        prefs.savePersonalData(listOf(personalFact))
        val reloadedFacts = prefs.loadPersonalData()
        assertEquals(1, reloadedFacts.size)
        assertEquals("আমার পেশা", reloadedFacts.first().title)

        PilotStateHub.setPersonalData(reloadedFacts)
        val prompt = GeminiToolSchemas.buildSystemInstruction("", VoiceLanguage.BENGALI_BD)
        assertTrue(prompt.contains("OWNER'S SAVED PERSONAL DATA"))
        assertTrue(prompt.contains("আমি একজন Android ডেভেলপার।"))
        PilotStateHub.setPersonalData(emptyList())

        // Verify zero-login JSON backup export and restore round-trip
        val backupJson = com.example.data.DeviceBackupManager.exportToJson(
            deviceProfile = profile,
            settings = reloadedSettings.copy(
                apiKeys = listOf(GeminiApiKeyEntry(id = "k1", label = "Main", key = "AIzaSyBackupKey001"))
            ),
            personalData = reloadedFacts,
            allRoutines = listOf(
                PilotRoutine("r_custom", "Morning", "Open news", "Open news app", RoutineAccent.COBALT, isBuiltIn = false)
            ) + PilotSettings.DEFAULT_ROUTINES,
            askAiMessages = listOf(AskAiMessage(id = 10L, isUser = true, text = "Hello", timestamp = "10:00"))
        )
        val restored = com.example.data.DeviceBackupManager.parseFromJson(backupJson).getOrThrow()
        assertEquals(profile.deviceId, restored.sourceDeviceId)
        assertEquals(1, restored.settings.apiKeys.size)
        assertEquals("AIzaSyBackupKey001", restored.settings.apiKeys.first().key)
        assertTrue(restored.settings.deletedModels.contains("gemini-2.0-flash"))
        assertEquals(1, restored.personalData.size)
        assertEquals("আমার পেশা", restored.personalData.first().title)
        assertEquals(1, restored.customRoutines.size)
        assertEquals(1, restored.askAiMessages.size)
    }
}

