package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiToolSchemas
import com.example.data.AppThemeMode
import com.example.data.PilotPreferences
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.RoutineAccent
import com.example.data.SafetyGuardrails
import com.example.ui.components.formatAiRichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(22, declarations.length())
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
}
