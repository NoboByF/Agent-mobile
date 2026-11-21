package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.data.SafetyGuardrails
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
    fun `live setup payload strictly follows v1beta handshake schema`() {
        val modelPath = GeminiLiveClient.normalizeModelPath("models/gemini-3.8-live")
        assertEquals("models/gemini-3.8-live", modelPath)

        val payload = GeminiLiveClient.buildSetupPayload(
            modelPath = modelPath,
            voiceName = "Kore",
            resumptionHandle = "handle_abc123"
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

        val declarations = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(12, declarations.length())
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
