package com.example.data

object SafetyGuardrails {

    /**
     * Hardcoded list of protected financial, mobile-banking, authenticator, and
     * password-manager applications where screen inspection, screenshots, clicks,
     * drags, and automated text input are strictly prohibited.
     */
    val BLOCKED_PACKAGES: Set<String> = setOf(
        // Bangladesh Mobile Financial Services (MFS) & Banking
        "com.bKash.customerapp",
        "com.bkash.customerapp",
        "com.konasl.nagad",
        "com.dbbl.nexus.pay",
        "com.ucb.upay",
        "com.rocket.mobilebanking",
        "com.dbbl.mbs.apps.main",
        "com.citybank.citytouch",
        "com.bracbank.astha",
        "com.ebl.skybanking",
        "com.islamibankbd.cellfin",
        "com.sslwireless.tap",
        "com.surecash.wallet",
        // Global Banking & Payment Wallets
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.paypal.android.p2pmobile",
        "com.chase.sig.android",
        "com.infonow.bofa",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.revolut.revolut",
        "com.transferwise.android",
        "com.phonepe.app",
        "net.one97.paytm",
        // Authenticators & 2FA
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.duosecurity.duomobile",
        "com.okta.android.auth",
        // Password Managers
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.onepassword.android",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.callpod.android_apps.keeper",
        "keepass2android.keepass2android"
    )

    private val BLOCKED_KEYWORDS: List<String> = listOf(
        "bkash",
        "nagad",
        "nexus.pay",
        "upay",
        "cellfin",
        "citytouch",
        "skybanking",
        "authenticator",
        "passwordmanager",
        "1password",
        "bitwarden",
        "lastpass",
        "dashlane",
        "keepass"
    )

    val PROTECTED_DISPLAY_LABELS: List<String> = listOf(
        "bKash",
        "Nagad",
        "NexusPay",
        "Upay",
        "Banking Apps",
        "Authenticators (2FA)",
        "Password Managers"
    )

    fun isPackageBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (BLOCKED_PACKAGES.any { it.equals(packageName, ignoreCase = true) }) {
            return true
        }
        val lower = packageName.lowercase()
        return BLOCKED_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    fun blockedWarningMessage(packageName: String?): String {
        val pkg = packageName ?: "this protected app"
        return "SAFETY_BLOCKED: Automated UI inspection, screenshots, and gestures are strictly disabled inside protected financial or security app ($pkg). Inform the user by voice immediately that protected banking, payment, authenticator, and password manager apps are blocked for their safety."
    }

    private val DESTRUCTIVE_KEYWORDS: List<String> = listOf(
        "factory reset",
        "erase all",
        "wipe data",
        "format sd",
        "format storage",
        "clear all data",
        "permanently delete",
        "delete account",
        "remove account",
        "deactivate account",
        "uninstall",
        "confirm payment",
        "pay now",
        "place order",
        "send money",
        "transfer money",
        "purchase now",
        "ডিলিট অ্যাকাউন্ট",
        "অ্যাকাউন্ট ডিলিট",
        "ফ্যাক্টরি রিসেট",
        "সব মুছে ফেল",
        "আনইনস্টল",
        "ফরম্যাট",
        "টাকা পাঠান",
        "পেমেন্ট কনফার্ম"
    )

    private val SOFT_DELETE_KEYWORDS: List<String> = listOf(
        "delete", "remove", "reset", "clear data", "মুছে", "ডিলিট", "রিসেট"
    )

    /**
     * Protects the user's phone from irreversible/destructive actions during autonomous self-healing.
     * Hard destructive operations (factory reset, uninstall, delete account, payment) are ALWAYS blocked
     * for user confirmation. Soft deletes are blocked unless the user's original goal explicitly asked for it.
     */
    fun isDestructiveAction(
        actionText: String,
        statusLabel: String,
        userGoal: String
    ): Boolean {
        val combined = "${actionText.trim()} ${statusLabel.trim()}".lowercase()
        if (combined.isBlank()) return false

        if (DESTRUCTIVE_KEYWORDS.any { combined.contains(it) }) {
            return true
        }

        val goalLower = userGoal.lowercase()
        val userExplicitlyAskedDelete = SOFT_DELETE_KEYWORDS.any { goalLower.contains(it) }
        if (!userExplicitlyAskedDelete && SOFT_DELETE_KEYWORDS.any { kw ->
                Regex("(^|\\b)${Regex.escape(kw)}(\\b|$)").containsMatchIn(combined)
            }
        ) {
            return true
        }

        return false
    }

    fun destructiveBlockedMessage(target: String): String {
        return "NON_DESTRUCTIVE_GUARD: Stopped before irreversible action ('$target') so nothing on your phone gets deleted, uninstalled, reset, or charged by mistake."
    }
}
