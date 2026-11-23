package com.example

import com.example.data.HttpCustomProfileParser
import com.example.data.HttpCustomTunnelProfile
import com.example.tunnel.DnsPacketCodec
import com.example.tunnel.DnsttCodec
import com.example.tunnel.KcpWire
import com.example.tunnel.NoisePrimitives
import com.example.tunnel.SmuxWire
import com.example.tunnel.SshProxyBridge
import com.example.tunnel.SshWebSocketTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure-JVM unit tests (no Robolectric SDK download required) covering:
 * - JSch crypto provider reflection availability (fixes ClassNotFoundException: com.jcraft.jsch.jce.Random)
 * - sshOcean SSH WebSocket profiles, HTTP 101 upgrade, RFC 6455 binary framing, and fallback ordering
 * - SlowDNS (dnstt) Base32/TXT codec, KCP, Noise_NK X25519+ChaChaPoly+BLAKE2s, and SMUX v1
 */
class TunnelProtocolUnitTest {

    @Test
    fun `jsch dynamic JCE Random and cipher providers load cleanly`() {
        val randomCls = Class.forName("com.jcraft.jsch.jce.Random")
        assertNotNull(randomCls.getDeclaredConstructor().newInstance())
        assertNotNull(Class.forName("com.jcraft.jsch.UserAuthPassword"))
        assertNotNull(Class.forName("com.jcraft.jsch.jce.AES128CTR"))
    }

    @Test
    fun `sshOcean WebSocket profiles, HTTP 101 upgrade, and RFC 6455 framing round-trip cleanly`() {
        val wsTlsProfile = HttpCustomTunnelProfile.DEFAULT_PROFILES.firstOrNull { it.id == "hc_sshocean_nl1_ws_tls" }
        assertNotNull(wsTlsProfile)
        assertEquals("nl1.wssht.to", wsTlsProfile!!.host)
        assertEquals(443, wsTlsProfile.port)
        assertEquals("sshocean-fahimsshh", wsTlsProfile.username)
        assertEquals("fahimsshh", wsTlsProfile.password)
        assertTrue(wsTlsProfile.isWebSocketSsh)
        assertTrue(wsTlsProfile.useTls)

        val gpSniProfile = HttpCustomTunnelProfile.DEFAULT_PROFILES.firstOrNull { it.id == "hc_sshocean_nl1_gp_sni" }
        assertNotNull(gpSniProfile)
        assertEquals("www.grameenphone.com", gpSniProfile!!.sniHost)

        val reqBytes = SshWebSocketTransport.buildUpgradeRequest(
            wsPath = "/",
            hostHeader = "nl1.wssht.to",
            wsKey = "dGhlIHNhbXBsZSBub25jZQ=="
        )
        val reqText = String(reqBytes, Charsets.US_ASCII)
        assertTrue(reqText.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(reqText.contains("Host: nl1.wssht.to\r\n"))
        assertTrue(reqText.contains("Upgrade: websocket\r\n"))

        val fakeResp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
        val parsedResp = SshWebSocketTransport.readHttpUpgradeResponse(
            ByteArrayInputStream(fakeResp.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(101, parsedResp.statusCode)
        assertEquals("websocket", parsedResp.headers["upgrade"])

        // RFC 6455 masked binary frame round-trip (>125 bytes exercises 16-bit extended length)
        val payload = "SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.6\r\n".repeat(5).toByteArray(Charsets.US_ASCII)
        val encoded = SshWebSocketTransport.encodeClientBinaryFrame(payload)
        val decoded = SshWebSocketTransport.readWebSocketFrame(ByteArrayInputStream(encoded))
        assertNotNull(decoded)
        assertEquals(0x02, decoded!!.first)
        assertTrue(payload.contentEquals(decoded.second))

        // Candidate strategy ordering includes primary TLS, wildcard SNI, CF Edge IP fronting, DPI Frag, and HTTP 80
        val candidates = SshWebSocketTransport.buildCandidateConfigs(
            host = "nl1.wssht.to",
            port = 443,
            sniHost = "www.grameenphone.com",
            bugHost = "",
            wsPath = "/",
            useTls = true,
            resolvedHostIp = null,
            enableSplitPayload = true,
            enableTlsFragment = true
        )
        assertTrue(candidates.any { it.port == 443 && it.useTls && it.sniHost == "www.grameenphone.com" })
        assertTrue(candidates.any { it.wsHostHeader == "www.grameenphone.com.nl1.wssht.to" })
        assertTrue(candidates.any { it.connectHost == "104.16.132.229" && it.port == 80 && it.splitPayload })
        assertTrue(candidates.any { it.connectHost == "104.18.2.2" && it.port == 443 && it.fragmentTlsClientHello })
        assertTrue(candidates.any { it.port == 80 && !it.useTls })

        // Verify HTTP Custom split payload rendering ([crlf], [split], [host], [sni])
        val splitChunks = SshWebSocketTransport.renderPayloadChunks(
            template = SshWebSocketTransport.PRESET_GP_SPLIT_DPI,
            hostHeader = "nl1.wssht.to",
            port = 80,
            sniHost = "www.grameenphone.com",
            wsPath = "/",
            wsKey = "dGhlIHNhbXBsZSBub25jZQ=="
        )
        assertEquals(2, splitChunks.size)
        val chunk1 = String(splitChunks[0], Charsets.US_ASCII)
        val chunk2 = String(splitChunks[1], Charsets.US_ASCII)
        assertTrue(chunk1.contains("www.grameenphone.com"))
        assertTrue(chunk2.contains("Host: nl1.wssht.to"))
        assertTrue(chunk2.contains("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="))

        // Verify GP Super Auto profile & 16-target GP Bug-Host Scanner list
        val gpAuto = HttpCustomTunnelProfile.DEFAULT_PROFILES.firstOrNull { it.id == HttpCustomTunnelProfile.GP_SUPER_AUTO_ID }
        assertNotNull(gpAuto)
        assertTrue(gpAuto!!.autoFallbackAllModes)
        assertTrue(gpAuto.enableTlsFragment)
        assertTrue(gpAuto.enableSplitPayload)
        val scanTargets = com.example.tunnel.GpBugHostScanner.buildGpScanTargets("nl1.wssht.to")
        assertEquals(16, scanTargets.size)
        assertTrue(scanTargets.any { it.connectTarget == "104.16.132.229" && it.port == 80 })
        assertTrue(scanTargets.any { it.connectTarget == "www.grameenphone.com" && it.port == 443 })

        val generatedKey = SshWebSocketTransport.generateWebSocketKey()
        assertEquals(24, generatedKey.length)
        assertTrue(wsTlsProfile.rawJson.contains("\"nl1.wssht.to\""))
        assertTrue(wsTlsProfile.rawJson.contains("\"sshocean-fahimsshh\""))
    }

    @Test
    fun `dnstt SlowDNS, KCP, Noise_NK, SMUX, and HTTP CONNECT proxy parsers work accurately`() {
        val mtu = DnsttCodec.computeMaxKcpMtu("nsts3.me-link.uk")
        assertTrue(mtu in 100..140)

        val clientId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val qname = DnsttCodec.encodeQueryDomain(clientId, "hello".toByteArray(), "nsts3.me-link.uk")
        assertTrue(qname.endsWith(".nsts3.me-link.uk"))
        assertTrue(qname.split('.').all { it.length in 1..63 })

        val query = DnsPacketCodec.buildDnsQueryPacket("nsts3.me-link.uk", DnsPacketCodec.TYPE_TXT, 0x1234, true, 1232)
        assertTrue(query.size > 27)

        val kcpSeg = KcpWire.Segment(1, KcpWire.CMD_PUSH, 0, 256, 100, 1, 0, "ok".toByteArray())
        val decodedKcp = KcpWire.decodeSegments(KcpWire.encodeSegment(kcpSeg))
        assertEquals(1, decodedKcp.size)
        assertEquals("ok", String(decodedKcp[0].data))

        val (aPriv, aPub) = NoisePrimitives.generateX25519Keypair()
        val (bPriv, bPub) = NoisePrimitives.generateX25519Keypair()
        assertTrue(NoisePrimitives.x25519SharedSecret(aPriv, bPub).contentEquals(NoisePrimitives.x25519SharedSecret(bPriv, aPub)))

        val smuxFrame = SmuxWire.Frame(1, SmuxWire.CMD_PSH, 3, "SSH-2.0".toByteArray())
        val parsedSmux = SmuxWire.readFrame(ByteArrayInputStream(SmuxWire.encodeFrame(smuxFrame)))
        assertEquals("SSH-2.0", String(parsedSmux!!.data))

        assertEquals("nl1.wssht.to" to 443, SshProxyBridge.parseHostPort("nl1.wssht.to:443", 443))
    }

    @Test
    fun `TunnelAutoDoctor accurately diagnoses GP failures and builds self-healing Auto-Fix plan`() {
        val gpAuto = HttpCustomTunnelProfile.DEFAULT_PROFILES.first { it.id == HttpCustomTunnelProfile.GP_SUPER_AUTO_ID }

        // 1. All routes blocked on GP SIM
        val allBlocked = com.example.tunnel.TunnelAutoDoctor.diagnose(
            profile = gpAuto,
            carrierName = "Grameenphone (GP)",
            isGrameenphone = true,
            dnsReachable = true,
            workingDnsServer = "192.0.0.1",
            workingDnsTransport = "UDP:53",
            dnsRttMs = 140L,
            wsAttempted = true,
            wsSucceeded = false,
            wsFailures = listOf("TLS:443 -> Connection timed out"),
            slowDnsAttempted = true,
            slowDnsSucceeded = false,
            slowDnsDiagnostics = listOf("Carrier(192.0.0.1,EDNS512) -> Timeout(3200ms)"),
            sshError = null
        )
        assertEquals(com.example.tunnel.FailureCategory.ALL_ROUTES_BLOCKED, allBlocked.category)
        assertTrue(allBlocked.canAutoFix)
        assertEquals(3, allBlocked.checks.size)
        assertTrue(allBlocked.checks.first().passed)

        // 2. SSH Auth failed
        val authDiag = com.example.tunnel.TunnelAutoDoctor.diagnose(
            profile = gpAuto,
            carrierName = "Grameenphone (GP)",
            isGrameenphone = true,
            dnsReachable = true,
            workingDnsServer = "192.0.0.1",
            workingDnsTransport = "UDP:53",
            dnsRttMs = 95L,
            wsAttempted = true,
            wsSucceeded = true,
            wsFailures = emptyList(),
            slowDnsAttempted = false,
            slowDnsSucceeded = false,
            slowDnsDiagnostics = emptyList(),
            sshError = "Auth fail"
        )
        assertEquals(com.example.tunnel.FailureCategory.SSH_AUTH_FAILED, authDiag.category)
        assertTrue(authDiag.requiresNewConfig)

        // 3. Auto-Fix plan picks live HTTP 101 Cloudflare Edge IP automatically
        val winnerEdge = com.example.tunnel.GpBugHostScanEntry(
            label = "CF Edge 104.16 (HTTP:80)",
            connectTarget = "104.16.132.229",
            port = 80,
            useTls = false,
            sniHost = "",
            wsHostHeader = "nl1.wssht.to",
            reachable = true,
            wsUpgrade101 = true,
            httpStatusCode = 101,
            rttMs = 68L,
            serverHeader = "cloudflare",
            detail = "HTTP 101 • WS READY [cloudflare]"
        )
        val plan = com.example.tunnel.TunnelAutoDoctor.buildAutoFixPlan(
            currentProfile = gpAuto,
            allProfiles = HttpCustomTunnelProfile.DEFAULT_PROFILES,
            currentDiagnosis = allBlocked,
            bugHostResults = listOf(winnerEdge),
            dnsResults = emptyList()
        )
        assertTrue(plan.shouldReconnect)
        assertNotNull(plan.updatedProfile)
        assertEquals("104.16.132.229", plan.updatedProfile!!.bugHost)
        assertEquals(80, plan.updatedProfile!!.port)
        assertTrue(plan.updatedProfile!!.enableSplitPayload)
    }
}

