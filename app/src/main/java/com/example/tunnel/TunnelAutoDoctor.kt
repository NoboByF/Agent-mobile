package com.example.tunnel

import com.example.data.HttpCustomTunnelProfile

enum class FailureCategory {
    NONE,
    NO_NETWORK,
    VPN_PERMISSION_DENIED,
    GP_DNS_TXT_BLOCKED,
    WS_PORTS_BLOCKED,
    DPI_SNI_FILTERED,
    SSH_AUTH_FAILED,
    NOISE_KEY_MISMATCH,
    PROXY_NO_INTERNET,
    ALL_ROUTES_BLOCKED
}

data class DiagnosticCheckRow(
    val label: String,
    val passed: Boolean,
    val detail: String
)

data class TunnelDiagnosis(
    val category: FailureCategory,
    val titleBn: String,
    val explanationBn: String,
    val autoFixActionLabelBn: String,
    val canAutoFix: Boolean,
    val checks: List<DiagnosticCheckRow>,
    val suggestedProfileId: String? = null,
    val requiresNewConfig: Boolean = false
)

data class AutoFixPlan(
    val shouldReconnect: Boolean,
    val updatedProfile: HttpCustomTunnelProfile?,
    val selectProfileId: String?,
    val recommendedTransportMode: TunnelTransportMode?,
    val summaryBn: String,
    val updatedDiagnosis: TunnelDiagnosis? = null
)

/**
 * Smart Root-Cause Diagnostic & Self-Healing Engine for GP / Robi / Airtel Tunnels.
 * Translates raw socket, TLS, HTTP, DNS RCODE, and SSH failures into exact plain-Bengali
 * explanations and computes an automated 1-tap fix plan.
 */
object TunnelAutoDoctor {

    fun diagnose(
        profile: HttpCustomTunnelProfile,
        carrierName: String,
        isGrameenphone: Boolean,
        dnsReachable: Boolean,
        workingDnsServer: String?,
        workingDnsTransport: String?,
        dnsRttMs: Long?,
        wsAttempted: Boolean,
        wsSucceeded: Boolean,
        wsFailures: List<String>,
        slowDnsAttempted: Boolean,
        slowDnsSucceeded: Boolean,
        slowDnsDiagnostics: List<String>,
        sshError: String?,
        internetCheck: InternetCheckResult? = null
    ): TunnelDiagnosis {
        val opLabel = if (isGrameenphone) "Grameenphone (GP)" else carrierName
        val checks = mutableListOf<DiagnosticCheckRow>()

        // 1. SIM / Carrier DNS Check
        checks.add(
            DiagnosticCheckRow(
                label = if (isGrameenphone) "GP সিম নেটওয়ার্ক / DNS" else "সিম নেটওয়ার্ক / DNS",
                passed = dnsReachable,
                detail = if (dnsReachable) {
                    "${workingDnsServer ?: "OK"} (${workingDnsTransport ?: "UDP:53"}, ${dnsRttMs ?: 0}ms)"
                } else {
                    "কোনো রেসপন্স নেই (ডাটা/সিগন্যাল বন্ধ)"
                }
            )
        )

        // 2. WebSocket / Cloudflare Edge Check
        if (wsAttempted) {
            val wsSummary = when {
                wsSucceeded -> "HTTP 101 কানেক্ট হয়েছে"
                wsFailures.any { it.contains("HTTP 302", ignoreCase = true) || it.contains("HTTP 301", ignoreCase = true) } ->
                    "সিম পোর্টালে রিডাইরেক্ট (HTTP 302)"
                wsFailures.any { it.contains("HTTP 403", ignoreCase = true) || it.contains("HTTP 400", ignoreCase = true) } ->
                    "হোস্ট/SNI রিজেক্ট (HTTP 403/400)"
                wsFailures.any { it.contains("reset", ignoreCase = true) || it.contains("SSL", ignoreCase = true) || it.contains("Handshake", ignoreCase = true) } ->
                    "DPI / TLS রিসেট হয়েছে"
                wsFailures.any { it.contains("timed out", ignoreCase = true) || it.contains("Timeout", ignoreCase = true) } ->
                    "সব পোর্ট (80/443/8080/8443) টাইমআউট"
                else -> wsFailures.lastOrNull()?.take(48) ?: "সংযোগ ব্যর্থ"
            }
            checks.add(
                DiagnosticCheckRow(
                    label = "SSH WebSocket / CF Edge",
                    passed = wsSucceeded,
                    detail = wsSummary
                )
            )
        }

        // 3. SlowDNS Check
        if (slowDnsAttempted) {
            val slowSummary = when {
                slowDnsSucceeded -> "SlowDNS TXT চালু"
                slowDnsDiagnostics.any { it.contains("SERVFAIL") || it.contains("REFUSED") } ->
                    "ক্যারিয়ার DNS ব্লক করছে (SERVFAIL/REFUSED)"
                slowDnsDiagnostics.any { it.contains("NOERROR AN=0") } ->
                    "সার্ভার থেকে TXT উত্তর নেই (AN=0)"
                else -> "UDP/DoH/DoT সব SlowDNS টাইমআউট"
            }
            checks.add(
                DiagnosticCheckRow(
                    label = "SlowDNS (UDP / DoH / DoT)",
                    passed = slowDnsSucceeded,
                    detail = slowSummary
                )
            )
        }

        // 4. SSH Auth Check
        if (!sshError.isNullOrBlank()) {
            val isAuth = sshError.contains("Auth fail", ignoreCase = true) ||
                sshError.contains("Auth cancel", ignoreCase = true)
            checks.add(
                DiagnosticCheckRow(
                    label = "SSH অ্যাকাউন্ট লগইন",
                    passed = false,
                    detail = if (isAuth) "ইউজারনেম/পাসওয়ার্ড ভুল বা মেয়াদ শেষ" else sshError.take(48)
                )
            )
        }

        // Classification Priority Order
        if (!dnsReachable && !wsSucceeded) {
            return TunnelDiagnosis(
                category = FailureCategory.NO_NETWORK,
                titleBn = "$opLabel সিমে ডাটা/নেটওয়ার্ক পাওয়া যাচ্ছে না",
                explanationBn = "মোবাইল ডাটা বন্ধ আছে অথবা 4G সিগন্যাল রেসপন্স করছে না। ডাটা অন করে আবার অটো সমাধান চাপুন।",
                autoFixActionLabelBn = "নেটওয়ার্ক রিফ্রেশ ও অটো সমাধান",
                canAutoFix = true,
                checks = checks
            )
        }

        if (!sshError.isNullOrBlank() &&
            (sshError.contains("Auth fail", ignoreCase = true) || sshError.contains("Auth cancel", ignoreCase = true))
        ) {
            val altId = if (profile.isWebSocketSsh) "hc_gp_robi_airtel_1" else HttpCustomTunnelProfile.GP_SUPER_AUTO_ID
            return TunnelDiagnosis(
                category = FailureCategory.SSH_AUTH_FAILED,
                titleBn = "SSH অ্যাকাউন্টের মেয়াদ শেষ বা পাসওয়ার্ড ভুল",
                explanationBn = "টানেল সার্ভার চালু আছে, কিন্তু '${profile.username}' অ্যাকাউন্টে লগইন করতে দিচ্ছে না। অন্য অ্যাকাউন্টে সুইচ করুন অথবা নতুন SSH অ্যাকাউন্ট দিন।",
                autoFixActionLabelBn = "অন্য অ্যাকাউন্টে সুইচ ও কানেক্ট",
                canAutoFix = true,
                checks = checks,
                suggestedProfileId = altId,
                requiresNewConfig = true
            )
        }

        if (internetCheck != null && internetCheck.status == InternetCheckStatus.OFFLINE) {
            return TunnelDiagnosis(
                category = FailureCategory.PROXY_NO_INTERNET,
                titleBn = "টানেল যুক্ত কিন্তু সার্ভার থেকে ইন্টারনেট আসছে না",
                explanationBn = "SSH কানেক্ট হয়েছে কিন্তু HTTP 204 ইন্টারনেট রেসপন্স দেয়নি। নতুন Cloudflare Edge রুট দিয়ে অটো রিকানেক্ট করুন।",
                autoFixActionLabelBn = "নতুন রুট খুঁজে অটো রিকানেক্ট",
                canAutoFix = true,
                checks = checks
            )
        }

        val hasDpiReset = wsFailures.any {
            it.contains("reset", ignoreCase = true) ||
                it.contains("SSLHandshake", ignoreCase = true) ||
                it.contains("ECONNRESET", ignoreCase = true) ||
                it.contains("HTTP 403", ignoreCase = true)
        }
        if (wsAttempted && !wsSucceeded && hasDpiReset && !slowDnsAttempted) {
            return TunnelDiagnosis(
                category = FailureCategory.DPI_SNI_FILTERED,
                titleBn = "$opLabel DPI পোর্ট 443/80 রিসেট করছে",
                explanationBn = "আপনার সিমের DPI ফায়ারওয়াল সাধারণ SNI আটকে দিয়েছে। অটো সমাধান চাপলে চালু GP Bug Host ও Split-DPI রুট খুঁজে কানেক্ট করবে।",
                autoFixActionLabelBn = "DPI Bypass ও Bug Host অটো সমাধান",
                canAutoFix = true,
                checks = checks,
                suggestedProfileId = HttpCustomTunnelProfile.GP_SUPER_AUTO_ID
            )
        }

        if (slowDnsAttempted && !slowDnsSucceeded && !wsAttempted) {
            return TunnelDiagnosis(
                category = FailureCategory.GP_DNS_TXT_BLOCKED,
                titleBn = "$opLabel DNS-এ SlowDNS TXT ব্লক করা আছে",
                explanationBn = "${workingDnsServer ?: "ক্যারিয়ার DNS"} দিয়ে SlowDNS TXT উত্তর আসছে না। অটো সমাধান চাপলে GP Super Auto (Cloudflare Edge + DoH/DoT) চালু হবে।",
                autoFixActionLabelBn = "GP Super Auto দিয়ে অটো সমাধান",
                canAutoFix = true,
                checks = checks,
                suggestedProfileId = HttpCustomTunnelProfile.GP_SUPER_AUTO_ID
            )
        }

        if (wsAttempted && !wsSucceeded && slowDnsAttempted && !slowDnsSucceeded) {
            val captivePortal = wsFailures.any { it.contains("HTTP 302", ignoreCase = true) || it.contains("HTTP 301", ignoreCase = true) }
            if (captivePortal) {
                return TunnelDiagnosis(
                    category = FailureCategory.ALL_ROUTES_BLOCKED,
                    titleBn = "সিমে সক্রিয় ইন্টারনেট ডাটা নেই (HTTP 302)",
                    explanationBn = "আপনার $opLabel সিমের সব ট্র্যাফিক অপারেটরের ব্যালেন্স পোর্টালে (HTTP 302) রিডাইরেক্ট হচ্ছে। যেকোনো VPN বা SSH টানেল সঠিকভাবে কানেক্ট হতে হলে প্রথমে সিমে সক্রিয় ইন্টারনেট ডাটা প্যাক অথবা Wi-Fi চালু থাকতে হবে।",
                    autoFixActionLabelBn = "ডাটা/Wi-Fi চালু করে পুনরায় যাচাই করুন",
                    canAutoFix = true,
                    checks = checks,
                    requiresNewConfig = false
                )
            }
            return TunnelDiagnosis(
                category = FailureCategory.ALL_ROUTES_BLOCKED,
                titleBn = "$opLabel নেটওয়ার্কে টানেল সার্ভার পৌঁছায়নি",
                explanationBn = "বর্তমান সার্ভারে WebSocket (80/443) ও SlowDNS কোনোটিই সাড়া দেয়নি। সক্রিয় ইন্টারনেট ডাটা বা Wi-Fi আছে কিনা দেখুন, অথবা অটো স্ক্যান চাপুন।",
                autoFixActionLabelBn = "সার্ভার ও পোর্ট স্ক্যান করুন",
                canAutoFix = true,
                checks = checks,
                requiresNewConfig = true
            )
        }

        return TunnelDiagnosis(
            category = FailureCategory.WS_PORTS_BLOCKED,
            titleBn = "সার্ভারে কানেক্ট করা যায়নি",
            explanationBn = "বর্তমান রুট দিয়ে সার্ভার রেসপন্স দেয়নি। অটো স্ক্যান চাপলে সব বিকল্প পোর্ট পরীক্ষা করে চালু রুট সেট করে দেবে।",
            autoFixActionLabelBn = "সার্ভার ও পোর্ট স্ক্যান করুন",
            canAutoFix = true,
            checks = checks
        )
    }

    /**
     * Computes the best automated self-healing action from live endpoint scan & DNS benchmark data.
     * Never loops on HTTP 301/302 captive portal redirects or plain HTTP 200 webpages.
     */
    fun buildAutoFixPlan(
        currentProfile: HttpCustomTunnelProfile,
        allProfiles: List<HttpCustomTunnelProfile>,
        currentDiagnosis: TunnelDiagnosis?,
        bugHostResults: List<GpBugHostScanEntry>,
        dnsResults: List<DnsBenchmarkEntry>
    ): AutoFixPlan {
        // 1. Best case: Found a genuine HTTP 101 Switching Protocols WebSocket SSH endpoint!
        val bestWs101 = bugHostResults.firstOrNull { it.wsUpgrade101 && it.httpStatusCode == 101 }
        if (bestWs101 != null) {
            val wsHost = if (currentProfile.isWebSocketSsh) currentProfile.host else "nl1.wssht.to"
            val isDirect = bestWs101.connectTarget.equals(wsHost, ignoreCase = true)
            val updated = currentProfile.copy(
                isWebSocketSsh = true,
                host = wsHost,
                port = bestWs101.port,
                useTls = bestWs101.useTls,
                bugHost = if (isDirect) "" else bestWs101.connectTarget,
                sniHost = bestWs101.sniHost.ifBlank { wsHost },
                protocol = if (bestWs101.useTls) "SSH WebSocket (SSL/TLS)" else "SSH WebSocket (HTTP)",
                enableSplitPayload = !bestWs101.useTls,
                enableTlsFragment = bestWs101.useTls,
                autoFallbackAllModes = true
            )
            return AutoFixPlan(
                shouldReconnect = true,
                updatedProfile = updated,
                selectProfileId = updated.id,
                recommendedTransportMode = TunnelTransportMode.AUTO_SMART,
                summaryBn = "✓ চালু HTTP 101 সার্ভার পাওয়া গেছে: ${bestWs101.label} (${bestWs101.rttMs ?: 0}ms) — কানেক্ট হচ্ছে!"
            )
        }

        // 2. If SSH Auth failed, automatically switch to an alternate built-in account profile
        if (currentDiagnosis?.category == FailureCategory.SSH_AUTH_FAILED) {
            val alternate = allProfiles.firstOrNull {
                it.id != currentProfile.id && it.username.isNotBlank() && it.username != currentProfile.username
            }
            if (alternate != null) {
                return AutoFixPlan(
                    shouldReconnect = true,
                    updatedProfile = null,
                    selectProfileId = alternate.id,
                    recommendedTransportMode = TunnelTransportMode.AUTO_SMART,
                    summaryBn = "✓ অন্য অ্যাকাউন্ট (${alternate.name}) নির্বাচিত — কানেক্ট হচ্ছে!"
                )
            }
        }

        // 3. Detect Captive Portal (HTTP 301/302 redirect) — DO NOT loop! Report clearly and honestly.
        val captiveRedirectCount = bugHostResults.count { it.httpStatusCode in 301..302 }
        if (captiveRedirectCount > 0) {
            val captiveDiag = TunnelDiagnosis(
                category = FailureCategory.ALL_ROUTES_BLOCKED,
                titleBn = "সিমে সক্রিয় ডাটা প্যাক নেই (HTTP 302 রিডাইরেক্ট)",
                explanationBn = "স্ক্যানে দেখা গেছে আপনার সিম সব কানেকশন অপারেটরের ব্যালেন্স পোর্টালে (HTTP 302) পাঠিয়ে দিচ্ছে এবং কোনো সার্ভারই HTTP 101 দেয়নি। সিমে সক্রিয় ইন্টারনেট ডাটা অথবা Wi-Fi চালু করে তারপর কানেক্ট করুন।",
                autoFixActionLabelBn = "ডাটা/Wi-Fi চালু করে পুনরায় যাচাই করুন",
                canAutoFix = true,
                checks = listOf(
                    DiagnosticCheckRow(
                        label = "ইন্টারনেট ডাটা স্ট্যাটাস",
                        passed = false,
                        detail = "$captiveRedirectCount টি পোর্টে HTTP 302 (পোর্টাল রিডাইরেক্ট)"
                    ),
                    DiagnosticCheckRow(
                        label = "SSH WebSocket (HTTP 101)",
                        passed = false,
                        detail = "সক্রিয় ইন্টারনেট ছাড়া সার্ভারে পৌঁছানো সম্ভব নয়"
                    )
                ),
                requiresNewConfig = false
            )
            return AutoFixPlan(
                shouldReconnect = false,
                updatedProfile = null,
                selectProfileId = null,
                recommendedTransportMode = null,
                summaryBn = captiveDiag.titleBn,
                updatedDiagnosis = captiveDiag
            )
        }

        // 4. If user was on a non-Super-Auto profile and DoH:443 responded, switch to GP Super Auto
        val bestDoh = dnsResults.firstOrNull { it.reachable && it.transport == "DoH:443" }
        if (bestDoh != null && currentProfile.id != HttpCustomTunnelProfile.GP_SUPER_AUTO_ID) {
            return AutoFixPlan(
                shouldReconnect = true,
                updatedProfile = null,
                selectProfileId = HttpCustomTunnelProfile.GP_SUPER_AUTO_ID,
                recommendedTransportMode = TunnelTransportMode.DOH_HTTPS,
                summaryBn = "✓ Encrypted DoH:443 (${bestDoh.rttMs}ms) চালু — কানেক্ট হচ্ছে!"
            )
        }

        val reachableDnsList = dnsResults.filter { it.reachable }
        val openDnsText = reachableDnsList.take(2).joinToString(", ") { "${it.serverIp} (${it.rttMs}ms)" }

        val honestChecks = listOf(
            DiagnosticCheckRow(
                label = "সিম DNS স্ক্যান (${dnsResults.size} টি)",
                passed = reachableDnsList.isNotEmpty(),
                detail = if (reachableDnsList.isNotEmpty()) "চালু: $openDnsText" else "সব DNS অফলাইন"
            ),
            DiagnosticCheckRow(
                label = "SSH WebSocket পোর্ট (80/443/8443)",
                passed = false,
                detail = "কোনো পোর্টেই HTTP 101 রেসপন্স আসেনি"
            )
        )

        val finalExplanation = if (reachableDnsList.isEmpty()) {
            "সিমের মোবাইল ডাটা সম্পূর্ণ বন্ধ বা 4G সিগন্যাল নেই। ফোনের Mobile Data বা Wi-Fi চালু করে আবার চেষ্টা করুন।"
        } else {
            "সিম থেকে শুধু ক্যারিয়ার DNS ($openDnsText) চালু আছে, কিন্তু বাইরের SSH সার্ভার (${currentProfile.host}) বা SlowDNS পোর্টে ইন্টারনেট সংযোগ পৌঁছাচ্ছে না। সক্রিয় ইন্টারনেট ডাটা প্যাক বা Wi-Fi চালু করুন অথবা নতুন চালু SSH সার্ভার কনফিগ ইমপোর্ট করুন।"
        }

        val updatedDiag = TunnelDiagnosis(
            category = if (reachableDnsList.isEmpty()) FailureCategory.NO_NETWORK else FailureCategory.ALL_ROUTES_BLOCKED,
            titleBn = if (reachableDnsList.isEmpty()) "মোবাইল ডাটা পাওয়া যাচ্ছে না" else "সার্ভারে ইন্টারনেট সংযোগ পৌঁছায়নি",
            explanationBn = finalExplanation,
            autoFixActionLabelBn = "পুনরায় স্ক্যান করুন",
            canAutoFix = true,
            checks = honestChecks,
            requiresNewConfig = reachableDnsList.isNotEmpty()
        )

        return AutoFixPlan(
            shouldReconnect = false,
            updatedProfile = null,
            selectProfileId = null,
            recommendedTransportMode = null,
            summaryBn = updatedDiag.titleBn,
            updatedDiagnosis = updatedDiag
        )
    }
}
