package com.example

import com.example.automation.PilotAccessibilityService
import com.example.messaging.ChatModerationEngine
import com.example.messaging.IncomingMessageListener
import com.example.messaging.ModerationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoReplyUnitTest {

    @Test
    fun `smart closing stops thank-you, ok, welcome, farewell loops without flagging them as spam`() {
        ChatModerationEngine.clearAllForTest()
        val convKey = "com.whatsapp|rahim"
        val history = listOf(
            "Rahim: ভাই মিটিং কয়টায়?",
            "Me: বিকেল ৪টায় মিটিং আছে।"
        )

        val closingMessages = listOf(
            "ধন্যবাদ",
            "অনেক ধন্যবাদ ভাই",
            "আপনাকেও ধন্যবাদ",
            "ওয়েলকাম",
            "স্বাগতম",
            "আচ্ছা ঠিক আছে",
            "ঠিক আছে ভাই, ভালো থাকবেন",
            "আল্লাহ হাফেজ",
            "ভালো থেকো",
            "পরে কথা হবে",
            "ok",
            "okay thanks",
            "thank you so much",
            "you're welcome",
            "it's ok",
            "don't mention it",
            "no problem",
            "hmm",
            "👍",
            "👍🏻",
            "🙏",
            "❤️",
            "bye"
        )
        for (msg in closingMessages) {
            val decision = ChatModerationEngine.evaluateIncomingLocally(
                convKey = convKey,
                incomingText = msg,
                conversationHistory = history,
                smartClosingEnabled = true
            )
            assertEquals("Expected SILENT_CONVERSATION_ENDED for \"$msg\"", ModerationAction.SILENT_CONVERSATION_ENDED, decision.action)
            assertEquals("", decision.replyText)
        }

        // Anti-ping-pong after AI already sent a closing message
        val closingHistory = listOf(
            "Rahim: অনেক ধন্যবাদ",
            "Me: আপনাকেও ধন্যবাদ, ভালো থাকবেন।"
        )
        val pingPong = ChatModerationEngine.evaluateIncomingLocally(
            convKey = convKey,
            incomingText = "জি ভালো থাকবেন আপনিও",
            conversationHistory = closingHistory,
            smartClosingEnabled = true
        )
        assertEquals(ModerationAction.SILENT_CONVERSATION_ENDED, pingPong.action)

        // Messages with thanks/ok PLUS a real question or request MUST still proceed to AI!
        val realFollowUps = listOf(
            "অনেক ধন্যবাদ ভাই, কালকে কয়টায় আসবেন?",
            "আচ্ছা, ফাইলটা একটু পাঠিয়ে দাও তো",
            "ok can you send me the schedule?",
            "ধন্যবাদ! আরেকটা কবিতা লিখে দাও",
            "আচ্ছা তুমি কেমন আছো?"
        )
        realFollowUps.forEachIndexed { idx, msg ->
            val decision = ChatModerationEngine.evaluateIncomingLocally(
                convKey = convKey,
                incomingText = msg,
                nowMs = 1_000_000L + idx * 60_000L,
                conversationHistory = history,
                smartClosingEnabled = true
            )
            assertEquals("Expected PROCEED_TO_AI for \"$msg\"", ModerationAction.PROCEED_TO_AI, decision.action)
        }

        // Genuine spam floods like "asdfghjkl" and "😂😂😂😂" still work as spam moderation
        ChatModerationEngine.clearAllForTest()
        val spamKey = "com.whatsapp|spammer"
        assertEquals(ModerationAction.AVOID_SPAM_ONCE, ChatModerationEngine.evaluateIncomingLocally(spamKey, "asdfghjkl", nowMs = 1_000L).action)
        assertEquals(ModerationAction.SILENT_IGNORE_SPAM, ChatModerationEngine.evaluateIncomingLocally(spamKey, "😂😂😂😂", nowMs = 40_000L).action)
    }

    @Test
    fun `owner active detection and manual takeover pause AI auto-reply immediately`() {
        IncomingMessageListener.clearEchoCacheForTest()
        PilotAccessibilityService.clearOwnerActivityForTest()

        val pkg = "com.whatsapp"
        val sender = "Karim"
        val convKey = IncomingMessageListener.conversationKey(pkg, sender)
        val t0 = 10_000_000L

        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0))

        // Owner enters or types inside WhatsApp -> AI pauses immediately and for 75s after leaving
        PilotAccessibilityService.recordOwnerInteractionForTest(pkg, t0)
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 5_000L))

        PilotAccessibilityService.lastForegroundPackage = "com.android.chrome"
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 30_000L))
        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 80_000L))

        // Owner takes over conversation manually -> pauses for 3 minutes
        IncomingMessageListener.markOwnerTookOverConversation(pkg, sender, nowMs = t0 + 100_000L)
        assertNotNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 200_000L))
        assertNull(IncomingMessageListener.getOwnerActivePauseReason(pkg, convKey, "WhatsApp", nowMs = t0 + 300_000L))

        // Verify conversation key normalization & self-echo still clean
        IncomingMessageListener.registerSentReply(pkg, sender, "বিকেল ৪টায় দেখা হবে", nowMs = t0)
        assertTrue(IncomingMessageListener.isRecentSelfEcho(pkg, sender, "বিকেল ৪টায় দেখা হবে", nowMs = t0 + 2_000L))
        assertFalse(IncomingMessageListener.isRecentSelfEcho(pkg, sender, "ঠিক আছে ভাই", nowMs = t0 + 2_000L))

        PilotAccessibilityService.clearOwnerActivityForTest()
        IncomingMessageListener.clearEchoCacheForTest()
    }
}
