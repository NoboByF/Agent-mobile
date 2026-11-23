package com.example.messaging

import com.example.data.PersonalDataItem
import com.example.data.PilotSettings
import com.example.firebase.FirebaseRuntimeConfig
import java.util.concurrent.ConcurrentHashMap

enum class ModerationAction {
    PROCEED_TO_AI,
    WARN_ABUSE,
    SILENT_IGNORE_ABUSE,
    AVOID_SPAM_ONCE,
    SILENT_IGNORE_SPAM,
    SILENT_CONVERSATION_ENDED
}

data class ModerationDecision(
    val action: ModerationAction,
    val replyText: String = "",
    val reason: String = "",
    val strikeCount: Int = 0
)

/**
 * Intelligent Per-Sender Chat Moderation & Anti-Nag Engine:
 *
 * 1. Abusive / Profane Messages ("বকাবাজি / গালাগালি"):
 *    - Strikes 1..3 -> Firm polite warning ("ভালোভাবে কথা বলুন, না হলে আমি আপনার সাথে আর কথা বলবো না।")
 *    - Strike 4+    -> Silently ignores abusive messages (no reply, zero owner disturbance).
 *    - Good/polite message from the same sender -> Always replied to normally!
 *
 * 2. Spam / Nonsense / "আবুল তাবুল" Avoidance:
 *    - Detects gibberish, keyboard mash, meaningless symbols/emojis, and rapid repeated flooding.
 *    - Gently avoids / ignores nonsense; after 4+ spam strikes (3-5 threshold), completely silences
 *      spam replies until a real, meaningful message arrives.
 *
 * 3. Anti-Nag Owner Escalation & Named Away Replies:
 *    - Prevents repeated nagging when the owner is away from the phone.
 *    - Formats polite waiting reply using the owner's actual account display name.
 */
object ChatModerationEngine {

    const val MAX_ABUSE_WARNINGS = 3
    const val MAX_SPAM_STRIKES_BEFORE_MUTE = 4
    private const val SPAM_WINDOW_MS = 30 * 60_000L
    private const val FLOOD_WINDOW_MS = 25_000L
    private const val ESCALATION_COOLDOWN_MS = 10 * 60_000L

    private data class SenderState(
        var abuseCount: Int = 0,
        var spamCount: Int = 0,
        var lastSpamAtMs: Long = 0L,
        var lastEscalatedAtMs: Long = 0L,
        var lastAwayNoticeAtMs: Long = 0L,
        val recentTimestamps: MutableList<Long> = mutableListOf(),
        val recentNormalizedTexts: MutableList<String> = mutableListOf()
    )

    private val senderStates = ConcurrentHashMap<String, SenderState>()

    // Strict Bengali, Banglish, and English profanity / verbal abuse patterns (avoids benign words)
    private val ABUSE_REGEX = Regex(
        "(?iu)(" +
            // Bengali slurs & insults
            "মাগি|খানকি|চুদা|চুদ|চোদা|চোদে|মাদারচোদ|বাইনচোদ|বালছাল|বালের|ভোদাই|ভুদা|হারামি|হারামজাদা|কুত্তার\\s*বাচ্চা|কুত্তা|শুয়োর|শুয়োর|শূয়োর|শালা|শালার|গাধা|ছাগল|বেয়াদব|বেয়াদব|বদমাইশ|ফালতু\\s*লোক|মুখ\\s*সামলে|চুপ\\s*কর\\s*কুত্তা|তোর\\s*মা|তোর\\s*বাপ|নষ্ট\\s*মেয়ে|নষ্ট\\s*ছেলে|জানোয়ার|জানোয়ার|পশু|" +
            // Banglish profanity
            "\\b(magi|khanki|chuda|choda|chod|madarchod|bainchod|bhodai|harami|haramjada|kuttar\\s*baccha|kutta|shuor|shuyore|shala|shalar|gadha|beyadob|bodmaish|baler|balchal|mc|bc)\\b|" +
            // English profanity & direct insults
            "\\b(fuck|fucking|fucked|fucker|motherfucker|bitch|bastard|asshole|dickhead|slut|whore|cunt|bullshit|dumbass|moron|idiot|stupid\\s+idiot|shut\\s+the\\s+fuck\\s+up|stfu|go\\s+to\\s+hell)\\b" +
            ")"
    )

    // Keyboard mash / repetitive gibberish patterns
    private val KEYBOARD_MASH_REGEX = Regex(
        "(?i)^(123456+|00000+|11111+|[a-z]{1,2}|[\\p{P}\\p{S}\\s]+)$"
    )
    private val KEYBOARD_ROW_MASH_REGEX = Regex(
        "(?i)(asdf|sdfg|dfgh|fghj|ghjk|hjkl|qwer|qwerty|uiop|zxcv|xcvb|cvbn|vbnm)"
    )
    private val REPEATED_SINGLE_CHAR_REGEX = Regex("(.)\\1{5,}")
    private val ABUL_TABUL_PHRASES = Regex(
        "(?iu)^(হাহা+|হিহি+|হুহু+|আবুল\\s*তাবুল|ব্লা\\s*ব্লা+|blah+|lmao+|lol+|yo+|test+|testing+|\\.\\.\\.+|\\?+|!+)$"
    )

    // Question or explicit action/request words -> never swallow as a closing acknowledgement!
    private val QUESTION_OR_REQUEST_REGEX = Regex(
        "(?iu)([?؟]|\\b(how|what|when|where|why|who|which|can\\s+you|could\\s+you|would\\s+you|please\\s+(send|tell|give|help|write|make|draw|check)|tell\\s+me|send\\s+me|help\\s+me|need)\\b|কখন|কোথায়|কোথায়|কত|কেন|কিভাবে|কীভাবে|কেমন|পারবে|পারবেন|দাও|দেন|বলো|বলেন|করো|করেন|পাঠাও|পাঠান|লাগবে|দরকার|কবিতা|গল্প|ছবি|ইমেজ|টাকা|বিকাশ|নগদ|ঠিকানা|কী\\s*কর|কি\\s*কর)"
    )

    // Pure polite acknowledgement / closing reaction emojis (1..3 glyphs, distinct from spam floods like 😂😂😂😂)
    private val PURE_ACK_REACTION_REGEX = Regex(
        "^(?:\\s|👍[🏻-🏿]?|👌[🏻-🏿]?|🙏[🏻-🏿]?|❤️|❤|♥|♥️|🤝|✅|✔|✔️|😊|🙂|🫡|💐|🌹|🤲[🏻-🏿]?|✨|💯|👏[🏻-🏿]?)+$"
    )

    // Exact closing / thank-you / welcome / farewell / acknowledgement tokens (Bengali, Banglish, English)
    private val CLOSING_PHRASES: Set<String> = setOf(
        // Thank-you & welcome
        "ধন্যবাদ", "ধন্নবাদ", "অনেক ধন্যবাদ", "অসংখ্য ধন্যবাদ", "আপনাকেও ধন্যবাদ", "তোমাকেও ধন্যবাদ",
        "শুকরিয়া", "শুকরিয়া", "জাযাকাল্লাহ", "জাযাকাল্লাহু খাইরান", "জাজাকাল্লাহ",
        "স্বাগতম", "ওয়েলকাম", "ওয়েলকাম", "কোনো ব্যাপার না", "কোন ব্যাপার না",
        "কোনো সমস্যা নেই", "কোন সমস্যা নেই", "সমস্যা নেই",
        "thanks", "thank", "thank you", "thank u", "thanks a lot", "thank you so much",
        "thx", "ty", "tysm", "tnx", "tnq", "shukriya", "jazakallah", "jazakallah khair",
        "welcome", "youre welcome", "you're welcome", "your welcome", "most welcome", "my pleasure",
        "no problem", "np", "no worries", "mention not", "dont mention it", "don't mention it",
        "its ok", "its okay", "it's ok", "it's okay", "dhonnobad", "dhonnyobad",
        "kono somossa nei", "somossa nei", "kono bepar na",
        // Acknowledgements
        "আচ্ছা", "আচ্ছা ঠিক আছে", "ঠিক আছে", "ঠিকআছে", "ওকে", "ওকে ঠিক আছে",
        "বুঝেছি", "বুঝলাম", "পেয়েছি", "পেয়েছি", "হুম", "হুঁ", "হু", "জ্বী", "জ্বি", "জি",
        "হ্যাঁ ঠিক আছে", "হ্যা ঠিক আছে", "হইছে", "হয়েছে", "হয়েছে", "বেশ", "হবে", "চলে", "ভালো",
        "ok", "okay", "okey", "okk", "okh", "k", "kk", "hmm", "hmmm", "hm",
        "accha", "acha", "achha", "thik ache", "thik ase", "thk ache", "thikase",
        "bujhechi", "bujhlam", "bujlam", "peyechi", "peyeci", "ji", "jee",
        "done", "noted", "got it", "alright", "all right", "fine", "cool", "nice",
        "great", "perfect", "sure", "right", "hoise", "hoyeche", "hobe", "besh", "good",
        // Farewells & wrap-ups
        "আল্লাহ হাফেজ", "খোদা হাফেজ", "ভালো থাকবেন", "ভালো থেকো", "ভালো থাকো",
        "আপনিও ভালো থাকবেন", "তুমিও ভালো থেকো", "সুস্থ থাকবেন", "শুভ রাত্রি",
        "টাটা", "বাই", "রাখলাম", "আসি", "পরে কথা হবে", "পরে কথা বলবো", "পরে নক দেবো", "পরে জানাবো",
        "allah hafez", "allah hafiz", "khoda hafez",
        "valo thakben", "valo theko", "valo thako", "bhalo thakben", "bhalo theko", "bhalo thako",
        "take care", "tc", "bye", "byee", "byeee", "goodbye", "good bye", "tata",
        "good night", "goodnight", "gn", "see you", "see ya", "cya",
        "talk later", "ttyl", "pore kotha hobe", "pore kotha bolbo", "rakhlam"
    )

    private val POLITE_ADDRESS_FILLERS: Set<String> = setOf(
        "ভাই", "ভাইয়া", "ভাইয়া", "আপু", "বন্ধু", "দোস্ত", "বস", "স্যার", "ম্যাডাম",
        "আপনাকে", "আপনাকেও", "তোমাকে", "তোমাকেও", "আপনিও", "তুমিও", "অনেক", "অসংখ্য", "খুব", "এখন",
        "bro", "bhai", "vai", "vaia", "bhaiyya", "apu", "sir", "boss", "dear", "friend",
        "very", "so", "much", "a", "lot", "most", "you", "u", "too", "to", "now", "then"
    )

    private val CLOSING_REPLY_HINT_REGEX = Regex(
        "(?iu)(ধন্যবাদ|স্বাগতম|ওয়েলকাম|ওয়েলকাম|আল্লাহ\\s*হাফেজ|খোদা\\s*হাফেজ|ভালো\\s*থাকবেন|ভালো\\s*থেকো|শুভ\\s*রাত্রি|পরে\\s*কথা\\s*হবে|আর\\s*কিছু\\s*লাগলে|thank\\s*you|thanks|welcome|allah\\s*hafez|take\\s*care|good\\s*night|goodbye|\\bbye\\b)"
    )

    fun isAbusiveText(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        return ABUSE_REGEX.containsMatchIn(clean)
    }

    /**
     * Checks whether a message is meaningless nonsense, gibberish, pure punctuation/emoji noise,
     * or rapid repetitive spam flooding.
     */
    fun isSpamOrNonsenseText(convKey: String, text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return true

        val state = senderStates.computeIfAbsent(convKey) { SenderState() }
        synchronized(state) {
            state.recentTimestamps.removeAll { nowMs - it > FLOOD_WINDOW_MS }
            state.recentTimestamps.add(nowMs)

            val norm = clean.lowercase().replace(Regex("\\s+"), " ")
            val repeatedSameRecently = state.recentNormalizedTexts.count { it == norm } >= 2
            state.recentNormalizedTexts.add(norm)
            while (state.recentNormalizedTexts.size > 6) {
                state.recentNormalizedTexts.removeAt(0)
            }

            // Rapid flood (4+ messages in 25 seconds) or exact same message spammed 3+ times
            if (state.recentTimestamps.size >= 4 || repeatedSameRecently) {
                return true
            }
        }

        // Pure symbols / emojis / numbers with no Bengali or Latin letters
        val hasLetters = clean.any { it in '\u0980'..'\u09FF' || it in 'a'..'z' || it in 'A'..'Z' }
        if (!hasLetters && clean.length <= 24) {
            return true
        }

        if (KEYBOARD_MASH_REGEX.matches(clean)) return true
        if (clean.length <= 30 && KEYBOARD_ROW_MASH_REGEX.containsMatchIn(clean)) return true
        if (REPEATED_SINGLE_CHAR_REGEX.containsMatchIn(clean) && clean.length <= 35) return true
        if (ABUL_TABUL_PHRASES.matches(clean)) return true

        // Consonant-only random mash in English (e.g. "xkcdjklmnpq")
        if (clean.length >= 6 && clean.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            val vowels = clean.count { it.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u', 'y') }
            if (vowels == 0) return true
        }

        return false
    }

    /**
     * Detects pure conversation closings, thank-you/welcome wrap-ups, polite acknowledgements,
     * farewells, or single-tap reaction emojis so the AI stops cleanly instead of creating an
     * endless "ধন্যবাদ -> ওয়েলকাম -> আচ্ছা -> ভালো থাকবেন" ping-pong loop!
     */
    fun evaluateConversationClosingLocally(
        incomingText: String,
        conversationHistory: List<String> = emptyList()
    ): Pair<Boolean, String> {
        val clean = incomingText.trim()
        if (clean.isBlank()) return false to ""

        // Never treat messages with a question or explicit request as a conversation closing!
        if (QUESTION_OR_REQUEST_REGEX.containsMatchIn(clean)) {
            return false to ""
        }

        // 1. Pure acknowledgement reaction emoji (e.g., 👍, 🙏, ❤️, 👌) up to 3 code points
        val noSpaces = clean.replace(Regex("\\s+"), "")
        val codePoints = noSpaces.codePointCount(0, noSpaces.length)
        if (codePoints in 1..4 && PURE_ACK_REACTION_REGEX.matches(clean)) {
            return true to "রিঅ্যাকশন ইমোজি — বাড়তি রিপ্লাই প্রয়োজন নেই"
        }

        // Only apply local closing shortcut to concise messages (long nuanced messages go to Gemini AI)
        if (clean.length > 68) return false to ""

        // Strip apostrophes cleanly (so "you're welcome" -> "youre welcome") and replace other punctuation/emojis with spaces
        val stripped = clean
            .lowercase()
            .replace(Regex("['’`]+"), "")
            .replace(Regex("(?:[.,!।;:\\-–—~\"()\\[\\]{}]|👍[🏻-🏿]?|👌[🏻-🏿]?|🙏[🏻-🏿]?|❤️|❤|♥|♥️|🤝|✅|✔|✔️|😊|🙂|🫡|💐|🌹|🤲[🏻-🏿]?|✨|💯|👏[🏻-🏿]?)+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isBlank()) return false to ""

        // 2. Exact match against known closing / thanks / welcome / farewell / acknowledgement phrases
        if (stripped in CLOSING_PHRASES) {
            return true to buildClosingReason(stripped)
        }

        // 3. Multi-phrase closing combinations (e.g. "আচ্ছা ধন্যবাদ ভাই", "ok thanks bye", "ঠিক আছে ভালো থাকবেন")
        if (isCombinationOfClosingPhrases(stripped)) {
            return true to buildClosingReason(stripped)
        }

        // 4. Anti-ping-pong check: if our previous AI reply was ALREADY a closing/thanks/goodbye,
        // and the sender replies with a short non-question wrap-up (<= 40 chars) containing any closing keyword
        val lastAiReply = conversationHistory.lastOrNull { it.startsWith("Me:") }?.removePrefix("Me:")?.trim().orEmpty()
        if (lastAiReply.isNotBlank() &&
            CLOSING_REPLY_HINT_REGEX.containsMatchIn(lastAiReply) &&
            stripped.length <= 42 &&
            CLOSING_REPLY_HINT_REGEX.containsMatchIn(stripped)
        ) {
            return true to "কথা আগেই শেষ হয়েছে — লুপ এড়াতে AI চুপ ছিল"
        }

        return false to ""
    }

    private fun isCombinationOfClosingPhrases(normalized: String): Boolean {
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 8) return false

        var idx = 0
        var matchedClosingCount = 0
        while (idx < tokens.size) {
            var matchedLen = 0
            // Try longest 3-word, 2-word, 1-word phrase match from CLOSING_PHRASES
            for (len in minOf(3, tokens.size - idx) downTo 1) {
                val slice = tokens.subList(idx, idx + len).joinToString(" ")
                if (slice in CLOSING_PHRASES) {
                    matchedLen = len
                    matchedClosingCount++
                    break
                }
            }
            if (matchedLen > 0) {
                idx += matchedLen
            } else if (tokens[idx] in POLITE_ADDRESS_FILLERS) {
                idx += 1
            } else {
                return false
            }
        }
        return matchedClosingCount >= 1
    }

    private fun buildClosingReason(stripped: String): String = when {
        stripped.contains("ধন্যবাদ") || stripped.contains("thank") || stripped.contains("thx") ||
            stripped.contains("shukriya") || stripped.contains("jazak") || stripped.contains("ধন্নবাদ") ||
            stripped.contains("dhonnobad") ->
            "ধন্যবাদ জানিয়ে কথা শেষ করেছে — বাড়তি রিপ্লাই প্রয়োজন নেই"
        stripped.contains("welcome") || stripped.contains("স্বাগতম") || stripped.contains("ওয়েলকাম") ||
            stripped.contains("ওয়েলকাম") || stripped.contains("সমস্যা নেই") || stripped.contains("no problem") ->
            "সমাপনী শুভেচ্ছা — লুপ এড়াতে AI চুপ ছিল"
        stripped.contains("হাফেজ") || stripped.contains("hafez") || stripped.contains("hafiz") ||
            stripped.contains("bye") || stripped.contains("ভালো থাক") || stripped.contains("ভালো থেক") ||
            stripped.contains("good night") || stripped.contains("শুভ রাত্রি") || stripped.contains("পরে কথা") ->
            "বিদায়/সমাপ্তি মেসেজ — কথা শেষ হয়েছে"
        else ->
            "সম্মতি/কথা শেষ (\"${stripped.take(24)}\") — রিপ্লাই দরকার নেই"
    }

    /**
     * Fast pre-AI moderation check (Layer 1).
     * Note: Polite/meaningful messages ALWAYS return PROCEED_TO_AI even if the sender previously abused or spammed!
     * Polite closings ("ধন্যবাদ", "আচ্ছা", "ঠিক আছে", "ok", "welcome", "আল্লাহ হাফেজ", "👍") return
     * SILENT_CONVERSATION_ENDED instead of ever being misclassified as spam!
     */
    fun evaluateIncomingLocally(
        convKey: String,
        incomingText: String,
        nowMs: Long = System.currentTimeMillis(),
        conversationHistory: List<String> = emptyList(),
        smartClosingEnabled: Boolean = true
    ): ModerationDecision {
        if (isAbusiveText(incomingText)) {
            return recordAbuseAndDecide(convKey, incomingText)
        }
        if (smartClosingEnabled) {
            val (isClosing, reason) = evaluateConversationClosingLocally(incomingText, conversationHistory)
            if (isClosing) {
                return ModerationDecision(
                    action = ModerationAction.SILENT_CONVERSATION_ENDED,
                    replyText = "",
                    reason = reason
                )
            }
        }
        if (isSpamOrNonsenseText(convKey, incomingText, nowMs)) {
            return recordSpamAndDecide(convKey, incomingText, nowMs)
        }
        return ModerationDecision(ModerationAction.PROCEED_TO_AI)
    }

    /**
     * Records an abusive message (detected locally OR by Gemini) and returns:
     * - Strikes 1..3: Firm warning asking them to speak respectfully
     * - Strike 4+:    Silent ignore (no reply sent)
     */
    fun recordAbuseAndDecide(convKey: String, incomingText: String): ModerationDecision {
        val state = senderStates.computeIfAbsent(convKey) { SenderState() }
        val count = synchronized(state) {
            state.abuseCount += 1
            state.abuseCount
        }
        if (count > MAX_ABUSE_WARNINGS) {
            return ModerationDecision(
                action = ModerationAction.SILENT_IGNORE_ABUSE,
                replyText = "",
                reason = "বকাবাজি ৩ বারের বেশি ($count বার) — রিপ্লাই এড়িয়ে যাওয়া হয়েছে",
                strikeCount = count
            )
        }

        val isBn = SensitiveMessageGuard.isLikelyBengaliOrBanglish(incomingText)
        val warning = if (isBn) {
            when (count) {
                1 -> "দয়া করে ভালোভাবে কথা বলুন, না হলে আমি আপনার সাথে আর কথা বলবো না।"
                2 -> "আবারও বলছি, ভালোভাবে কথা বলুন—না হলে আমি আপনার সাথে আর কথা বলবো না।"
                else -> "এটি শেষ সতর্কতা (৩/৩)। ভালোভাবে কথা বলুন, না হলে এরপর থেকে আপনার বকাবাজির আর কোনো রিপ্লাই দেবো না।"
            }
        } else {
            when (count) {
                1 -> "Please speak politely, otherwise I will stop talking to you."
                2 -> "Again, please speak respectfully, or I will stop replying to you."
                else -> "Final warning (3/3). Please speak politely, otherwise I will ignore any further abusive messages."
            }
        }
        return ModerationDecision(
            action = ModerationAction.WARN_ABUSE,
            replyText = warning,
            reason = "বকাবাজি সতর্কতা ($count/$MAX_ABUSE_WARNINGS)",
            strikeCount = count
        )
    }

    /**
     * Records a spam / abul-tabul message (detected locally OR by Gemini):
     * - Strikes 1..3: Gently avoids pure noise or gives at most 1 brief redirect
     * - Strike 4+:    Completely silences spam replies because sender is intentionally spamming
     */
    fun recordSpamAndDecide(
        convKey: String,
        incomingText: String,
        nowMs: Long = System.currentTimeMillis()
    ): ModerationDecision {
        val state = senderStates.computeIfAbsent(convKey) { SenderState() }
        val count = synchronized(state) {
            if (state.lastSpamAtMs > 0L && (nowMs - state.lastSpamAtMs) > SPAM_WINDOW_MS) {
                state.spamCount = 0
            }
            state.spamCount += 1
            state.lastSpamAtMs = nowMs
            state.spamCount
        }

        if (count >= MAX_SPAM_STRIKES_BEFORE_MUTE) {
            return ModerationDecision(
                action = ModerationAction.SILENT_IGNORE_SPAM,
                replyText = "",
                reason = "ইচ্ছাকৃত স্প্যাম / আবুল-তাবুল ($count বার) — রিপ্লাই বন্ধ রাখা হয়েছে",
                strikeCount = count
            )
        }

        // Pure symbols/emojis/single chars are silently ignored right away; only verbal nonsense gets 1 gentle redirect on strike 1
        val hasLetters = incomingText.any { it in '\u0980'..'\u09FF' || it in 'a'..'z' || it in 'A'..'Z' }
        if (!hasLetters || count > 1) {
            return ModerationDecision(
                action = ModerationAction.SILENT_IGNORE_SPAM,
                replyText = "",
                reason = "আবুল-তাবুল / অপ্রয়োজনীয় মেসেজ এড়িয়ে যাওয়া হয়েছে ($count/$MAX_SPAM_STRIKES_BEFORE_MUTE)",
                strikeCount = count
            )
        }

        val isBn = SensitiveMessageGuard.isLikelyBengaliOrBanglish(incomingText)
        val gentleReply = if (isBn) {
            "দয়া করে স্পষ্ট করে বলুন, আপনার কোনো দরকারি কথা থাকলে জানান।"
        } else {
            "Please write clearly if you have something important to ask."
        }
        return ModerationDecision(
            action = ModerationAction.AVOID_SPAM_ONCE,
            replyText = gentleReply,
            reason = "অপ্রাসঙ্গিক / আবুল-তাবুল মেসেজ ($count/$MAX_SPAM_STRIKES_BEFORE_MUTE)",
            strikeCount = count
        )
    }

    /**
     * Prevents repeated voice/notification nagging for the same sender when the owner is away.
     */
    fun canEscalateToOwner(convKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val state = senderStates.computeIfAbsent(convKey) { SenderState() }
        return synchronized(state) {
            val last = state.lastEscalatedAtMs
            if (last > 0L && (nowMs - last) < ESCALATION_COOLDOWN_MS) {
                false
            } else {
                state.lastEscalatedAtMs = nowMs
                true
            }
        }
    }

    fun shouldSendAwayNotice(convKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val state = senderStates.computeIfAbsent(convKey) { SenderState() }
        return synchronized(state) {
            val last = state.lastAwayNoticeAtMs
            if (last > 0L && (nowMs - last) < ESCALATION_COOLDOWN_MS) {
                false
            } else {
                state.lastAwayNoticeAtMs = nowMs
                true
            }
        }
    }

    fun markOwnerRespondedForSender(convKey: String) {
        val state = senderStates[convKey] ?: return
        synchronized(state) {
            state.lastEscalatedAtMs = 0L
            state.lastAwayNoticeAtMs = 0L
        }
    }

    fun resolveOwnerDisplayName(
        settings: PilotSettings,
        personalData: List<PersonalDataItem>
    ): String {
        val explicit = settings.ownerDisplayName.trim()
        if (explicit.isNotBlank()) return explicit

        val firebaseUser = FirebaseRuntimeConfig.currentUserOrNull()
        val fbName = firebaseUser?.displayName?.trim().orEmpty()
        if (fbName.isNotBlank()) return fbName

        // Check if owner saved their name inside "আমার ডাটা"
        val fromPersonalData = personalData.firstOrNull { item ->
            val t = item.title.lowercase()
            t.contains("নাম") || t.contains("name") || t.contains("পরিচয়") || t.contains("পরিচয়")
        }?.content?.trim()?.take(40)
        if (!fromPersonalData.isNullOrBlank() && fromPersonalData.length <= 32) {
            return fromPersonalData
        }

        val emailPrefix = firebaseUser?.email?.substringBefore('@')?.trim().orEmpty()
        if (emailPrefix.isNotBlank()) return emailPrefix

        return "অ্যাকাউন্টের মালিক"
    }

    fun buildOwnerAwayMessage(ownerName: String, incomingText: String): String {
        val cleanName = ownerName.trim().ifBlank { "অ্যাকাউন্টের মালিক" }
        return if (SensitiveMessageGuard.isLikelyBengaliOrBanglish(incomingText)) {
            "উত্তরের জন্য অপেক্ষা করুন, $cleanName আসলেই উত্তর দেবেন।"
        } else {
            val enName = if (cleanName == "অ্যাকাউন্টের মালিক") "the owner" else cleanName
            "Please wait for a reply, $enName will respond as soon as they are back."
        }
    }

    fun getAbuseCount(convKey: String): Int = senderStates[convKey]?.abuseCount ?: 0

    fun getSpamCount(convKey: String): Int = senderStates[convKey]?.spamCount ?: 0

    internal fun clearAllForTest() {
        senderStates.clear()
    }
}
