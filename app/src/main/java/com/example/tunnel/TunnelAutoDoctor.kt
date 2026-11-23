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
            val extraReason = if (captivePortal) {
                "আপনার $opLabel সিম সব ট্র্যাফিক ব্যালেন্স পোর্টালে (HTTP 302) রিডাইরেক্ট করছে।"
            } else {
                "আপনার $opLabel সিমে বর্তমানে WebSocket (80/443), Cloudflare Edge ও SlowDNS (UDP/DoH/DoT) সবগুলো পোর্ট ফায়ারওয়ালে ব্লক রয়েছে।"
            }
            return TunnelDiagnosis(
                category = FailureCategory.ALL_ROUTES_BLOCKED,
                titleBn = "$opLabel ফায়ারওয়াল সব পোর্ট ব্লক করেছে",
                explanationBn = "$extraReason অটো সমাধান চাপলে ১৬টি GP পোর্টাল ও সব DNS স্ক্যান করে কোনো খোলা রুট থাকলে সাথে সাথে কানেক্ট করবে।",
                autoFixActionLabelBn = "অটো সমাধান ও GP স্ক্যান করুন",
                canAutoFix = true,
                checks = checks,
                requiresNewConfig = true
            )
        }

        return TunnelDiagnosis(
            category = FailureCategory.WS_PORTS_BLOCKED,
            titleBn = "সার্ভারে কানেক্ট করা যায়নি",
            explanationBn = "বর্তমান রুট দিয়ে সার্ভার রেসপন্স দেয়নি। অটো সমাধান চাপলে সব বিকল্প পোর্ট ও GP Bug Host স্ক্যান করে চালু রুট সেট করে দেবে।",
            autoFixActionLabelBn = "অটো সমাধান ও রিকানেক্ট করুন",
            canAutoFix = true,
            checks = checks
        )
    }

    /**
     * Computes the best automated self-healing action from live Bug-Host scan & DNS benchmark data.
     */
    fun buildAutoFixPlan(
        currentProfile: HttpCustomTunnelProfile,
        allProfiles: List<HttpCustomTunnelProfile>,
        currentDiagnosis: TunnelDiagnosis?,
        bugHostResults: List<GpBugHostScanEntry>,
        dnsResults: List<DnsBenchmarkEntry>
    ): AutoFixPlan {
        // 1. Best case: Found a live HTTP 101 / 200 WebSocket host or Cloudflare Edge IP!
        val bestWs101 = bugHostResults.firstOrNull { it.wsUpgrade101 }
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
                summaryBn = "✓ চালু রুট পাওয়া গেছে: ${bestWs101.label} (${bestWs101.rttMs ?: 0}ms) — কানেক্ট হচ্ছে!"
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

        // 3. Found any reachable Cloudflare Edge / GP Portal host (even non-101, e.g. 400/426 where split payload can upgrade)
        val reachableEdge = bugHostResults.firstOrNull {
            it.reachable && (it.serverHeader?.contains("cloudflare", ignoreCase = true) == true || it.connectTarget.startsWith("104.") || it.connectTarget.startsWith("162."))
        }
        if (reachableEdge != null) {
            val wsHost = if (currentProfile.isWebSocketSsh) currentProfile.host else "nl1.wssht.to"
            val updated = currentProfile.copy(
                isWebSocketSsh = true,
                host = wsHost,
                port = reachableEdge.port,
                useTls = reachableEdge.useTls,
                bugHost = reachableEdge.connectTarget,
                sniHost = reachableEdge.sniHost.ifBlank { wsHost },
                customPayload = if (!reachableEdge.useTls) SshWebSocketTransport.PRESET_GP_SPLIT_DPI else "",
                enableSplitPayload = true,
                enableTlsFragment = true,
                autoFallbackAllModes = true
            )
            return AutoFixPlan(
                shouldReconnect = true,
                updatedProfile = updated,
                selectProfileId = updated.id,
                recommendedTransportMode = TunnelTransportMode.AUTO_SMART,
                summaryBn = "✓ Cloudflare Edge (${reachableEdge.connectTarget}:${reachableEdge.port}) সাড়া দিয়েছে — Split DPI দিয়ে কানেক্ট হচ্ছে!"
            )
        }

        // 4. If user was on a non-Super-Auto profile and DoH:443 or Carrier DNS responded, switch to GP Super Auto or fastest DNS
        val bestDoh = dnsResults.firstOrNull { it.reachable && it.transport == "DoH:443" }
        if (bestDoh != null && currentProfile.id != HttpCustomTunnelProfile.GP_SUPER_AUTO_ID) {
            return AutoFixPlan(
                shouldReconnect = true,
                updatedProfile = null,
                selectProfileId = HttpCustomTunnelProfile.GP_SUPER_AUTO_ID,
                recommendedTransportMode = TunnelTransportMode.DOH_HTTPS,
                summaryBn = "✓ Encrypted DoH:443 (${bestDoh.rttMs}ms) চালু — GP Super Auto দিয়ে কানেক্ট হচ্ছে!"
            )
        }

        val reachablePortalCount = bugHostResults.count { it.reachable }
        val reachableDnsList = dnsResults.filter { it.reachable }
        val openPortals = bugHostResults.filter { it.reachable }.take(2).joinToString(", ") { "${it.label} (${it.httpStatusCode ?: "OK"})" }
        val openDnsText = reachableDnsList.take(2).joinToString(", ") { "${it.serverIp} (${it.rttMs}ms)" }

        val honestChecks = listOf(
            DiagnosticCheckRow(
                label = "সিম DNS স্ক্যান (${dnsResults.size} টি)",
                passed = reachableDnsList.isNotEmpty(),
                detail = if (reachableDnsList.isNotEmpty()) "চালু: $openDnsText" else "সব DNS ব্লক/অফলাইন"
            ),
            DiagnosticCheckRow(
                label = "Cloudflare & WS পোর্ট (80/443)",
                passed = false,
                detail = "১৬টির মধ্যে কোনো হোস্ট HTTP 101 দেয়নি"
            ),
            DiagnosticCheckRow(
                label = "GP পোর্টাল রিচেবিলিটি",
                passed = reachablePortalCount > 0,
                detail = if (reachablePortalCount > 0) "খোলা: $openPortals" else "কোনো GP পোর্টাল রেসপন্স দেয়নি"
            )
        )

        val finalExplanation = if (reachableDnsList.isEmpty()) {
            "সিমের মোবাইল ডাটা সম্পূর্ণ বন্ধ বা 4G সিগন্যাল নেই। ফোনের Mobile Data অন করে (অথবা Airplane Mode অফ-অন করে) আবার চেষ্টা করুন।"
        } else if (reachablePortalCount > 0) {
            "আপনার GP সিমে শুধু $openPortals খোলা আছে, কিন্তু বর্তমান সার্ভার (${currentProfile.host}) ও SlowDNS (nsts3.me-link.uk) ব্লক। একটি নতুন চালু GP কনফিগ (.hc JSON বা নতুন SSH WS সার্ভার) ইমপোর্ট করুন।"
        } else {
            "আপনার GP সিম শুধুমাত্র ক্যারিয়ার DNS ($openDnsText) ছাড়া বাইরের সব TCP পোর্ট (80/443/8080/8443) এবং SlowDNS TXT ব্লক করে রেখেছে। একটি ছোট ডাটা/সোশ্যাল প্যাক চালু করুন অথবা নতুন GP কনফিগ ইমপোর্ট করুন।"
        }

        val updatedDiag = TunnelDiagnosis(
            category = if (reachableDnsList.isEmpty()) FailureCategory.NO_NETWORK else FailureCategory.ALL_ROUTES_BLOCKED,
            titleBn = if (reachableDnsList.isEmpty()) "মোবাইল ডাটা পাওয়া যাচ্ছে না" else "এই সিমে বর্তমান সার্ভারের কোনো পোর্ট খোলা নেই",
            explanationBn = finalExplanation,
            autoFixActionLabelBn = "আবার অটো স্ক্যান করুন",
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
