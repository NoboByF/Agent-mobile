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
import com.example.ai.GeneratedImageHelper
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
import com.example.messaging.ChatModerationEngine
import com.example.messaging.IncomingMessageListener
import com.example.messaging.ModerationAction
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
        assertEquals(31, declarations.length())
        val toolNames = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet()
        assertTrue(toolNames.contains("end_live_call"))
        assertTrue(toolNames.contains("generate_ai_image"))
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
    fun `sensitive question guard detects financial, OTP, and private questions and defers politely with owner name`() {
        val bkash = SensitiveMessageGuard.assessSensitivityLocally(
            messageText = "ভাই আমাকে ৫০০০ টাকা বিকাশ করে দাও জরুরি",
            ownerName = "ফাহিম"
        )
        assertTrue(bkash.isSensitive)
        assertEquals("উত্তরের জন্য অপেক্ষা করুন, ফাহিম আসলেই উত্তর দেবেন।", bkash.deferralReply)

        val otp = SensitiveMessageGuard.assessSensitivityLocally(
            messageText = "Can you send me the OTP verification code?",
            ownerName = "Fahim"
        )
        assertTrue(otp.isSensitive)
        assertEquals("Please wait for a reply, Fahim will respond as soon as they are back.", otp.deferralReply)

        val address = SensitiveMessageGuard.assessSensitivityLocally(
            messageText = "তোমার বাসার ঠিকানা দাও এখন",
            ownerName = "ফাহিম"
        )
        assertTrue(address.isSensitive)
        assertEquals("উত্তরের জন্য অপেক্ষা করুন, ফাহিম আসলেই উত্তর দেবেন।", address.deferralReply)

        // Casual, poem, and image creation requests MUST NOT be escalated as sensitive!
        assertFalse(SensitiveMessageGuard.assessSensitivityLocally("হাই কেমন আছো?").isSensitive)
        assertFalse(SensitiveMessageGuard.assessSensitivityLocally("আমাকে একটা সুন্দর কবিতা লিখে পাঠাও").isSensitive)
        assertFalse(SensitiveMessageGuard.assessSensitivityLocally("একটা সূর্যাস্তের ছবি তৈরি করে দাও").isSensitive)
        assertFalse(SensitiveMessageGuard.assessSensitivityLocally("Write a short poem about rain").isSensitive)
    }

    @Test
    fun `chat moderation engine warns 3 times for abuse, ignores 4th+, and still replies to polite messages`() {
        ChatModerationEngine.clearAllForTest()
        val convKey = "com.whatsapp|abusive_user"

        val strike1 = ChatModerationEngine.evaluateIncomingLocally(convKey, "তুই একটা গাধা বেয়াদব")
        assertEquals(ModerationAction.WARN_ABUSE, strike1.action)
        assertEquals(1, strike1.strikeCount)
        assertTrue(strike1.replyText.contains("ভালোভাবে কথা বলুন"))

        val strike2 = ChatModerationEngine.evaluateIncomingLocally(convKey, "শালা হারামি")
        assertEquals(ModerationAction.WARN_ABUSE, strike2.action)
        assertEquals(2, strike2.strikeCount)

        val strike3 = ChatModerationEngine.evaluateIncomingLocally(convKey, "কুত্তার বাচ্চা")
        assertEquals(ModerationAction.WARN_ABUSE, strike3.action)
        assertEquals(3, strike3.strikeCount)

        // 4th+ abusive message -> silently ignored (empty reply)
        val strike4 = ChatModerationEngine.evaluateIncomingLocally(convKey, "তুই একটা শুয়োর")
        assertEquals(ModerationAction.SILENT_IGNORE_ABUSE, strike4.action)
        assertEquals("", strike4.replyText)
        assertEquals(4, strike4.strikeCount)

        // Good/polite message from the SAME sender -> proceeds to AI normally!
        val polite = ChatModerationEngine.evaluateIncomingLocally(convKey, "দুঃখিত ভাই, আজকে আবহাওয়া কেমন?")
        assertEquals(ModerationAction.PROCEED_TO_AI, polite.action)

        // Subsequent abuse is still silently ignored
        val strike5 = ChatModerationEngine.evaluateIncomingLocally(convKey, "হারামজাদা")
        assertEquals(ModerationAction.SILENT_IGNORE_ABUSE, strike5.action)
    }

    @Test
    fun `chat moderation avoids abul-tabul spam and mutes after threshold while allowing real messages`() {
        ChatModerationEngine.clearAllForTest()
        val convKey = "com.whatsapp|spam_user"
        val t0 = 5_000_000L

        val s1 = ChatModerationEngine.evaluateIncomingLocally(convKey, "asdfghjkl", nowMs = t0)
        assertEquals(ModerationAction.AVOID_SPAM_ONCE, s1.action)

        val s2 = ChatModerationEngine.evaluateIncomingLocally(convKey, "qwertyuiop", nowMs = t0 + 30_000L)
        assertEquals(ModerationAction.SILENT_IGNORE_SPAM, s2.action)

        val s3 = ChatModerationEngine.evaluateIncomingLocally(convKey, "😂😂😂😂", nowMs = t0 + 60_000L)
        assertEquals(ModerationAction.SILENT_IGNORE_SPAM, s3.action)

        val s4 = ChatModerationEngine.evaluateIncomingLocally(convKey, "আবুল তাবুল", nowMs = t0 + 90_000L)
        assertEquals(ModerationAction.SILENT_IGNORE_SPAM, s4.action)
        assertTrue(s4.strikeCount >= ChatModerationEngine.MAX_SPAM_STRIKES_BEFORE_MUTE)

        // Meaningful real message after flood window still gets answered
        val realMsg = ChatModerationEngine.evaluateIncomingLocally(
            convKey,
            "ভাই একটা সুন্দর কবিতা লিখে দাও তো",
            nowMs = t0 + 150_000L
        )
        assertEquals(ModerationAction.PROCEED_TO_AI, realMsg.action)
    }

    @Test
    fun `anti-nag escalation cooldown prevents repeated disturbance and resets when owner responds`() {
        ChatModerationEngine.clearAllForTest()
        val convKey = "com.whatsapp|important_sender"
        val t0 = 9_000_000L

        assertTrue(ChatModerationEngine.canEscalateToOwner(convKey, nowMs = t0))
        // Second escalation 1 minute later must be suppressed (no nagging!)
        assertFalse(ChatModerationEngine.canEscalateToOwner(convKey, nowMs = t0 + 60_000L))

        assertTrue(ChatModerationEngine.shouldSendAwayNotice(convKey, nowMs = t0))
        assertFalse(ChatModerationEngine.shouldSendAwayNotice(convKey, nowMs = t0 + 60_000L))

        // Once owner responds, cooldown clears immediately
        ChatModerationEngine.markOwnerRespondedForSender(convKey)
        assertTrue(ChatModerationEngine.canEscalateToOwner(convKey, nowMs = t0 + 90_000L))

        val bnAway = ChatModerationEngine.buildOwnerAwayMessage("রাকিব", "ভাই জরুরি টাকা লাগবে")
        assertEquals("উত্তরের জন্য অপেক্ষা করুন, রাকিব আসলেই উত্তর দেবেন।", bnAway)
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
    fun `device identity is valid for Firestore path and personal data, owner name, deleted models, and JSON backup persist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = com.example.firebase.DeviceIdentityHelper.resolve(context)
        assertTrue(profile.deviceId.matches(Regex("^[a-zA-Z0-9_\\-]+$")))
        assertTrue(profile.deviceId.length in 4..64)
        assertEquals(profile.deviceId, com.example.firebase.DeviceIdentityHelper.resolve(context).deviceId)

        val prefs = PilotPreferences(context)
        val savedSettings = PilotSettings(
            ownerDisplayName = "ফাহিম",
            apiKeys = listOf(GeminiApiKeyEntry(id = "k1", label = "Main", key = "AIzaSyBackupKey001")),
            activeApiKeyId = "k1",
            deletedModels = listOf("gemini-2.0-flash", "gemini-1.5-pro"),
            handsFreeVoiceEscalationEnabled = true
        )
        prefs.saveSettings(savedSettings)
        val reloadedSettings = prefs.loadSettings()
        assertEquals("ফাহিম", reloadedSettings.ownerDisplayName)
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

        // Verify JSON backup export and import round-trip via PilotPreferences
        prefs.saveCustomRoutines(
            listOf(
                PilotRoutine("r_custom", "Morning", "Open news", "Open news app", RoutineAccent.COBALT, isBuiltIn = false)
            ) + PilotSettings.DEFAULT_ROUTINES
        )
        prefs.saveAskAiHistory(listOf(AskAiMessage(id = 10L, isUser = true, text = "Hello", timestamp = "10:00")))

        val backupJson = prefs.exportDeviceBackupJson(
            deviceId = profile.deviceId,
            deviceLabel = profile.displayLabel
        )
        // Wipe prefs then restore from backup JSON
        prefs.saveSettings(PilotSettings())
        prefs.savePersonalData(emptyList())
        prefs.saveCustomRoutines(PilotSettings.DEFAULT_ROUTINES)
        prefs.saveAskAiHistory(emptyList())

        val importResult = prefs.importDeviceBackupJson(backupJson)
        assertTrue(importResult.isSuccess)

        val restoredSettings = prefs.loadSettings()
        assertEquals("ফাহিম", restoredSettings.ownerDisplayName)
        assertEquals(1, restoredSettings.apiKeys.size)
        assertEquals("AIzaSyBackupKey001", restoredSettings.apiKeys.first().key)
        assertTrue(restoredSettings.deletedModels.contains("gemini-2.0-flash"))
        assertEquals(1, prefs.loadPersonalData().size)
        assertEquals("আমার পেশা", prefs.loadPersonalData().first().title)
        assertTrue(prefs.loadRoutines().any { it.id == "r_custom" })
        assertEquals(1, prefs.loadAskAiHistory().size)
    }

    @Test
    fun `email and password auth validator checks gmail format, password length, and confirmation match`() {
        assertNull(com.example.ui.screens.AuthInputValidator.validateLogin("fahim@gmail.com", "secret123"))
        assertNotNull(com.example.ui.screens.AuthInputValidator.validateLogin("not-an-email", "secret123"))
        assertNotNull(com.example.ui.screens.AuthInputValidator.validateLogin("fahim@gmail.com", "12345"))

        assertNull(
            com.example.ui.screens.AuthInputValidator.validateSignUp(
                accountName = "ফাহিম",
                email = "fahim@gmail.com",
                password = "secret123",
                confirmPassword = "secret123"
            )
        )
        assertNotNull(
            com.example.ui.screens.AuthInputValidator.validateSignUp(
                accountName = "ফাহিম",
                email = "fahim@gmail.com",
                password = "secret123",
                confirmPassword = "different"
            )
        )
        assertNotNull(
            com.example.ui.screens.AuthInputValidator.validateSignUp(
                accountName = "",
                email = "fahim@gmail.com",
                password = "secret123",
                confirmPassword = "secret123"
            )
        )
        assertNull(com.example.ui.screens.AuthInputValidator.validateResetEmail("fahim@gmail.com"))
        assertNotNull(com.example.ui.screens.AuthInputValidator.validateResetEmail("bad"))

        val apiKeyErr = com.example.ui.screens.AuthInputValidator.mapFirebaseAuthError(
            RuntimeException("An internal error has occurred. [ API key not valid. Please pass a valid API key. ]")
        )
        assertTrue(apiKeyErr.contains("google-services.json"))
    }

    @Test
    fun `firebase runtime config parses real google-services json and rejects CI placeholders`() {
        val sampleValid = """
            {
              "project_info": {
                "project_id": "jorashop-12345",
                "storage_bucket": "jorashop-12345.firebasestorage.app"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:1234567890:android:abcdef123456",
                    "android_client_info": { "package_name": "com.aistudio.livepilotai.k7m2x9" }
                  },
                  "api_key": [{ "current_key": "AIzaSyTestRuntimeFirebaseKey1234567890" }]
                }
              ]
            }
        """.trimIndent()

        val parsed = com.example.firebase.FirebaseRuntimeConfig.parseGoogleServicesJson(
            rawJson = sampleValid,
            targetPackage = "com.aistudio.livepilotai.k7m2x9",
            defaultAiStudioDbId = "ai-studio-db"
        ).getOrThrow()
        assertEquals("jorashop-12345", parsed.projectId)
        assertEquals("1:1234567890:android:abcdef123456", parsed.applicationId)
        assertEquals("(default)", parsed.databaseId)

        val placeholderJson = sampleValid
            .replace("jorashop-12345", "livepilot-ci-placeholder")
            .replace("AIzaSyTestRuntimeFirebaseKey1234567890", "CI_PLACEHOLDER_ADD_GOOGLE_SERVICES_JSON_SECRET")
        val failed = com.example.firebase.FirebaseRuntimeConfig.parseGoogleServicesJson(
            rawJson = placeholderJson,
            targetPackage = "com.aistudio.livepilotai.k7m2x9"
        )
        assertTrue(failed.isFailure)
    }

    @Test
    fun `image generation prompt detector and Gemini plus Imagen response parsers work accurately`() {
        assertTrue(GeneratedImageHelper.isImageGenerationRequest("একটি পাখির ইমেজ তৈরি করে দাও"))
        assertTrue(GeneratedImageHelper.isImageGenerationRequest("সূর্যাস্তের সুন্দর ছবি বানাও"))
        assertTrue(GeneratedImageHelper.isImageGenerationRequest("generate an image of a colorful bird"))
        assertTrue(GeneratedImageHelper.isImageGenerationRequest("/image futuristic Dhaka skyline"))

        assertFalse(GeneratedImageHelper.isImageGenerationRequest("এই ছবিটি বিশ্লেষণ করো"))
        assertFalse(GeneratedImageHelper.isImageGenerationRequest("describe this image for me"))
        assertFalse(GeneratedImageHelper.isImageGenerationRequest("কেমন আছো ভাই?"))

        val fakePngB64 = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3, 4), android.util.Base64.NO_WRAP)
        val geminiJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "text": "এটি আপনার রঙিন পাখির ছবি।" },
                    { "inlineData": { "mimeType": "image/png", "data": "$fakePngB64" } }
                  ]
                }
              }]
            }
        """.trimIndent()
        val parsedGemini = GeminiRestClient.parseGeneratedImageFromGenerateContent(
            bodyStr = geminiJson,
            modelUsed = "gemini-2.5-flash-image",
            keyLabel = "Key 1"
        )
        assertNotNull(parsedGemini)
        assertEquals("image/png", parsedGemini!!.mimeType)
        assertEquals(4, parsedGemini.imageBytes.size)
        assertEquals("এটি আপনার রঙিন পাখির ছবি।", parsedGemini.captionText)

        val imagenJson = """
            {
              "predictions": [{
                "bytesBase64Encoded": "$fakePngB64",
                "mimeType": "image/jpeg"
              }]
            }
        """.trimIndent()
        val parsedImagen = GeminiRestClient.parseGeneratedImageFromImagen(
            bodyStr = imagenJson,
            modelUsed = "imagen-3.0-generate-002"
        )
        assertNotNull(parsedImagen)
        assertEquals("image/jpeg", parsedImagen!!.mimeType)
        assertEquals(4, parsedImagen.imageBytes.size)
    }

    @Test
    fun `owner voice consultation parser distinguishes DISCUSS, SEND_REPLY, and CANCEL actions`() {
        val discuss = GeminiRestClient.parseOwnerConsultationJson(
            rawText = """{"action":"DISCUSS","spoken_to_owner":"ও আগে বলেছিল জরুরি কথা আছে। আপনি কি বলবো?","reply_to_sender":""}""",
            fallbackUtterance = "আগে কী মেসেজ করেছিল?"
        )
        assertEquals("DISCUSS", discuss.action)
        assertEquals("", discuss.replyToSender)
        assertTrue(discuss.spokenToOwner.contains("আগে বলেছিল"))

        val send = GeminiRestClient.parseOwnerConsultationJson(
            rawText = """{"action":"SEND_REPLY","spoken_to_owner":"ঠিক আছে, পাঠিয়ে দিচ্ছি।","reply_to_sender":"আমি ১০ মিনিট পর কল দিচ্ছি ভাই।"}""",
            fallbackUtterance = "ওকে বলো আমি ১০ মিনিট পর কল দিচ্ছি"
        )
        assertEquals("SEND_REPLY", send.action)
        assertEquals("আমি ১০ মিনিট পর কল দিচ্ছি ভাই।", send.replyToSender)

        val cancel = GeminiRestClient.parseOwnerConsultationJson(
            rawText = """{"action":"CANCEL","spoken_to_owner":"ঠিক আছে, বাদ দিলাম।","reply_to_sender":""}""",
            fallbackUtterance = "বাদ দাও"
        )
        assertEquals("CANCEL", cancel.action)
        assertEquals("", cancel.replyToSender)

        // Guard: SEND_REPLY with empty reply_to_sender must safely fall back to DISCUSS
        val guardEmpty = GeminiRestClient.parseOwnerConsultationJson(
            rawText = """{"action":"SEND_REPLY","spoken_to_owner":"পাঠাবো?","reply_to_sender":"   "}""",
            fallbackUtterance = "পাঠাও"
        )
        assertEquals("DISCUSS", guardEmpty.action)
        assertEquals("", guardEmpty.replyToSender)
    }

    @Test
    fun `gemini decision 1 request builder, response parser, and preferences persistence work accurately`() {
        val req = com.example.ai.GeminiDecisionClient.buildDecisionsRequestJson(
            modelId = "models/gemini-decision-1",
            input = "Sender: Karim | Message: ভাই ৫০০০ টাকা বিকাশ করো জরুরি",
            questions = linkedMapOf(
                "needs_owner" to com.example.ai.DecisionQuestion.Noul("Requires human owner approval?"),
                "category" to com.example.ai.DecisionQuestion.Choice(
                    instructions = "Pick best category",
                    criteria = linkedMapOf(
                        "NORMAL" to "Casual chat",
                        "SENSITIVE_OWNER_ONLY" to "Financial or private request"
                    )
                ),
                "urgency" to com.example.ai.DecisionQuestion.Score(
                    instructions = "Rate urgency",
                    criteria = listOf("0: Low", "1: Medium", "2: Urgent")
                )
            )
        )
        assertEquals("gemini-decision-1", req.getString("model"))
        val qObj = req.getJSONObject("questions")
        assertEquals("noul", qObj.getJSONObject("needs_owner").getString("type"))
        assertEquals("choice", qObj.getJSONObject("category").getString("type"))
        assertEquals("score", qObj.getJSONObject("urgency").getString("type"))
        assertEquals(3, qObj.getJSONObject("urgency").getJSONArray("criteria").length())

        val sampleResponse = """
            {
              "answers": {
                "needs_owner": { "type": "noul", "noul": 0.97, "confidence": 0.99 },
                "category": {
                  "type": "choice",
                  "choice": "SENSITIVE_OWNER_ONLY",
                  "probabilities": { "NORMAL": 0.02, "SENSITIVE_OWNER_ONLY": 0.98 },
                  "confidence": 0.96
                },
                "urgency": {
                  "type": "score",
                  "score": 1.88,
                  "probabilities": { "0": 0.02, "1": 0.08, "2": 0.90 },
                  "confidence": 0.93
                }
              }
            }
        """.trimIndent()

        val parsed = com.example.ai.GeminiDecisionClient.parseDecisionsResponseJson(
            bodyStr = sampleResponse,
            modelUsed = "gemini-decision-1",
            latencyMs = 215L,
            keyLabelUsed = "Key 1"
        )
        assertTrue(parsed.noulAnswers["needs_owner"]!!.isYes)
        assertEquals(0.97, parsed.noulAnswers["needs_owner"]!!.probability, 0.001)
        assertEquals("SENSITIVE_OWNER_ONLY", parsed.choiceAnswers["category"]!!.choice)
        assertEquals(0.96, parsed.choiceAnswers["category"]!!.confidence, 0.001)
        assertEquals(1.88, parsed.scoreAnswers["urgency"]!!.score, 0.001)
        assertEquals(215L, parsed.latencyMs)

        // Verify PilotPreferences persistence & JSON backup round-trip for decisionModel + useDecisionModel
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PilotPreferences(context)
        prefs.saveSettings(
            PilotSettings(
                decisionModel = "gemini-decision-1",
                useDecisionModel = false
            )
        )
        val loaded = prefs.loadSettings()
        assertEquals("gemini-decision-1", loaded.decisionModel)
        assertFalse(loaded.useDecisionModel)

        val backup = prefs.exportDeviceBackupJson("dev_test", "Pixel")
        prefs.saveSettings(PilotSettings(decisionModel = "other-model", useDecisionModel = true))
        assertTrue(prefs.importDeviceBackupJson(backup).isSuccess)

        val restored = prefs.loadSettings()
        assertEquals("gemini-decision-1", restored.decisionModel)
        assertFalse(restored.useDecisionModel)
    }

    @Test
    fun `smart conversation closing stops thank-you, ok, welcome, farewell loops while answering real follow-up questions`() {
        ChatModerationEngine.clearAllForTest()
        val convKey = "com.whatsapp|rahim"
        val history = listOf(
            "Rahim: ভাই মিটিং কয়টায়?",
            "Me: বিকেল ৪টায় মিটিং আছে।"
        )

        // Pure closings, thanks, welcome, ok, farewells, and approval reactions -> SILENT_CONVERSATION_ENDED
        val closingMessages = listOf(
            "ধন্যবাদ",
            "অনেক ধন্যবাদ ভাই",
            "ওয়েলকাম",
            "আচ্ছা ঠিক আছে",
            "ঠিক আছে ভাই, ভালো থাকবেন",
            "আল্লাহ হাফেজ",
            "ok",
            "okay thanks",
            "thank you so much",
            "you're welcome",
            "hmm",
            "👍",
            "🙏",
            "bye"
        )
        for (msg in closingMessages) {
            val decision = ChatModerationEngine.evaluateIncomingLocally(
                convKey = convKey,
                incomingText = msg,
                conversationHistory = history,
                smartClosingEnabled = true
            )
            assertEquals("Expected NO_REPLY closing for \"$msg\"", ModerationAction.SILENT_CONVERSATION_ENDED, decision.action)
            assertEquals("", decision.replyText)
        }

        // Messages that include thanks/ok PLUS a real question or request MUST still proceed to AI!
        val realFollowUps = listOf(
            "অনেক ধন্যবাদ ভাই, কালকে কয়টায় আসবেন?",
            "আচ্ছা, ফাইলটা একটু পাঠিয়ে দাও তো",
            "ok can you send me the schedule?",
            "ধন্যবাদ! আরেকটা কবিতা লিখে দাও"
        )
        for (msg in realFollowUps) {
            val decision = ChatModerationEngine.evaluateIncomingLocally(
                convKey = convKey,
                incomingText = msg,
                conversationHistory = history,
                smartClosingEnabled = true
            )
            assertEquals("Expected PROCEED_TO_AI for \"$msg\"", ModerationAction.PROCEED_TO_AI, decision.action)
        }
    }

    @Test
    fun `owner active detection and manual takeover pause AI auto-reply immediately`() {
        IncomingMessageListener.clearEchoCacheForTest()
        com.example.automation.PilotAccessibilityService.clearOwnerActivityForTest()

        val pkg = "com.whatsapp"
        val sender = "Karim"
        val convKey = IncomingMessageListener.conversationKey(pkg, sender)
        val t0 = 10_000_000L

        // Initially owner is NOT active -> AI is allowed to reply
        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0))

        // Case 1: Owner opens or types inside WhatsApp -> AI pauses immediately and for 75s after leaving
        com.example.automation.PilotAccessibilityService.recordOwnerInteractionForTest(pkg, t0)
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 5_000L))

        // Switch away to another app: still paused within 75s recent activity window, then resumes after 80s
        com.example.automation.PilotAccessibilityService.lastForegroundPackage = "com.android.chrome"
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 30_000L))
        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 80_000L))

        // Case 2: Owner manually takes over / replies / opens chat notification -> pauses for 3 minutes
        IncomingMessageListener.markOwnerTookOverConversation(pkg, sender, nowMs = t0 + 100_000L)
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 200_000L))
        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 300_000L))

        com.example.automation.PilotAccessibilityService.clearOwnerActivityForTest()
        IncomingMessageListener.clearEchoCacheForTest()
    }

    @Test
    fun `smart auto-reply JSON parser handles NO_REPLY, NORMAL, and persists smart human-first settings`() {
        val noReply = GeminiRestClient.parseSmartAutoReplyJson(
            rawText = """{"message_type":"NO_REPLY","should_reply":false,"decision_reason":"ধন্যবাদ জানিয়ে কথা শেষ করেছে","is_sensitive":false,"reply":""}""",
            senderName = "Rahim",
            incomingText = "অনেক ধন্যবাদ ভাই",
            defaultDeferral = "অপেক্ষা করুন"
        )
        assertEquals("NO_REPLY", noReply.messageType)
        assertFalse(noReply.shouldReply)
        assertEquals("", noReply.replyText)
        assertTrue(noReply.decisionReason.contains("ধন্যবাদ"))

        val normalReply = GeminiRestClient.parseSmartAutoReplyJson(
            rawText = """{"message_type":"NORMAL","should_reply":true,"decision_reason":"সময় জানতে চেয়েছে","is_sensitive":false,"reply":"বিকেল ৪টায় দেখা হবে।"}""",
            senderName = "Rahim",
            incomingText = "কয়টায় দেখা হবে?",
            defaultDeferral = "অপেক্ষা করুন"
        )
        assertEquals("NORMAL", normalReply.messageType)
        assertTrue(normalReply.shouldReply)
        assertEquals("বিকেল ৪টায় দেখা হবে।", normalReply.replyText)

        // Verify persistence of new human-first settings
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PilotPreferences(context)
        prefs.saveSettings(
            PilotSettings(
                autoReplyDelaySec = 30,
                pauseAutoReplyWhenOwnerActive = true,
                smartConversationClosingEnabled = true
            )
        )
        val loaded = prefs.loadSettings()
        assertEquals(30, loaded.autoReplyDelaySec)
        assertTrue(loaded.pauseAutoReplyWhenOwnerActive)
        assertTrue(loaded.smartConversationClosingEnabled)
    }
}

