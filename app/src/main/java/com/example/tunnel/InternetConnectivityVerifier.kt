package com.example.tunnel

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class InternetCheckStatus {
    IDLE,
    CHECKING,
    VERIFIED_ONLINE,
    DNS_ONLY_NO_INTERNET,
    OFFLINE
}

data class InternetCheckResult(
    val status: InternetCheckStatus = InternetCheckStatus.IDLE,
    val httpLatencyMs: Long? = null,
    val exitPublicIp: String? = null,
    val checkedEndpoint: String? = null,
    val summaryBn: String = "কানেক্ট হলে অটো ইন্টারনেট চেক হবে",
    val detailText: String? = null,
    val checkedAtMs: Long? = null
)

enum class PipelineStageState {
    PENDING,
    RUNNING,
    PASSED,
    FAILED
}

data class TunnelPipelineStages(
    val carrierDns: PipelineStageState = PipelineStageState.PENDING,
    val slowDnsServer: PipelineStageState = PipelineStageState.PENDING,
    val noiseSmux: PipelineStageState = PipelineStageState.PENDING,
    val sshAuth: PipelineStageState = PipelineStageState.PENDING,
    val internetCheck: PipelineStageState = PipelineStageState.PENDING,
    val failureHintBn: String? = null
)

/**
 * Verifies real end-to-end internet connectivity by executing actual HTTP 204 & Exit IP requests
 * through the active local SlowDNS+SSH proxy (127.0.0.1:<port>) — or honestly reporting when only
 * DNS is open and TCP internet is still blocked by the carrier.
 */
object InternetConnectivityVerifier {

    private val CONNECTIVITY_URLS = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204"
    )

    private const val EXIT_IP_URL = "https://api.ipify.org?format=text"

    suspend fun verifyInternet(
        localProxyPort: Int?,
        underlyingNetwork: Network? = null,
        protectTcp: ((Socket) -> Boolean)? = null,
        timeoutSec: Long = 10L
    ): InternetCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val usingProxy = localProxyPort != null && localProxyPort in 1..65535

        val client = if (usingProxy) {
            OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyPort!!)))
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        } else {
            GpNetworkInspector.createProtectedDohClient(
                underlyingNetwork = underlyingNetwork,
                protectTcp = protectTcp,
                timeoutMs = (timeoutSec * 1000L).coerceAtMost(6000L)
            )
        }

        var workingLatency: Long? = null
        var workingUrl: String? = null
        var lastErr: String? = null

        for (url in CONNECTIVITY_URLS) {
            val outcome = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "close")
                    .get()
                    .build()
                val t0 = System.currentTimeMillis()
                client.newCall(req).execute().use { resp ->
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (resp.code == 204 || resp.code in 200..299) {
                        rtt to url
                    } else {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                }
            }
            if (outcome.isSuccess) {
                val (rtt, hitUrl) = outcome.getOrThrow()
                workingLatency = rtt
                workingUrl = hitUrl
                break
            } else {
                lastErr = outcome.exceptionOrNull()?.localizedMessage
                    ?: outcome.exceptionOrNull()?.javaClass?.simpleName
            }
        }

        if (workingLatency != null) {
            // Also resolve real Exit Public IP through the same route (short timeout)
            val exitIp = runCatching {
                val ipReq = Request.Builder()
                    .url(EXIT_IP_URL)
                    .header("Connection", "close")
                    .get()
                    .build()
                client.newCall(ipReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.string()?.trim()?.takeIf { it.length in 7..45 && !it.contains('<') }
                    } else null
                }
            }.getOrNull()

            val modeDesc = if (usingProxy) "SlowDNS+SSH প্রক্সি চালু" else "ডাইরেক্ট ইন্টারনেট চালু"
            return@withContext InternetCheckResult(
                status = InternetCheckStatus.VERIFIED_ONLINE,
                httpLatencyMs = workingLatency,
                exitPublicIp = exitIp,
                checkedEndpoint = workingUrl,
                summaryBn = "✅ ইন্টারনেট চালু আছে ($modeDesc)",
                detailText = buildString {
                    append("HTTP 204 OK • ${workingLatency}ms")
                    if (!exitIp.isNullOrBlank()) append(" • Exit IP: $exitIp")
                },
                checkedAtMs = now
            )
        }

        return@withContext if (usingProxy) {
            InternetCheckResult(
                status = InternetCheckStatus.OFFLINE,
                httpLatencyMs = null,
                exitPublicIp = null,
                checkedEndpoint = CONNECTIVITY_URLS.first(),
                summaryBn = "⚠️ টানেল যুক্ত কিন্তু ইন্টারনেট রেসপন্স নেই",
                detailText = "SSH প্রক্সি থেকে HTTP 204 ব্যর্থ (${lastErr ?: "Timeout"}) — সার্ভার ফরোয়ার্ডিং চেক করুন",
                checkedAtMs = now
            )
        } else {
            InternetCheckResult(
                status = InternetCheckStatus.DNS_ONLY_NO_INTERNET,
                httpLatencyMs = null,
                exitPublicIp = null,
                checkedEndpoint = CONNECTIVITY_URLS.first(),
                summaryBn = "⚠️ শুধু GP DNS চালু — ইন্টারনেট কাজ করছে না",
                detailText = "SlowDNS/SSH হ্যান্ডশেক সম্পন্ন না হলে ব্রাউজার/অ্যাপে ইন্টারনেট চলবে না (${lastErr ?: "Blocked"})",
                checkedAtMs = now
            )
        }
    }
}
