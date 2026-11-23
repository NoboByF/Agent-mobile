package com.example.data

import org.json.JSONObject

data class HttpCustomTunnelProfile(
    val id: String,
    val name: String,
    val operatorName: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: String,
    val nameserver: String,
    val pubKey: String,
    val bannerNote: String,
    val rawJson: String,
    val dnsServer: String = "auto"
) {
    companion object {
        val DEFAULT_PROFILES = listOf(
            HttpCustomTunnelProfile(
                id = "hc_gp_robi_airtel_1",
                name = "Fahim - nsts3.me-link.uk (Pro)",
                operatorName = "Grameenphone, Robi & Airtel",
                host = "nsts3.me-link.uk",
                port = 22,
                username = "jssh_9qknqpo",
                password = "RgNax1QLRtx68x",
                protocol = "SlowDns + SSH",
                nameserver = "nsts3.me-link.uk",
                pubKey = "75148e30f06c1a58700756c3746640d7e27d73632aed5b4844bdded4ffe86220",
                bannerNote = "য়ে যাবে",
                dnsServer = "auto",
                rawJson = """
                    {
                      "config": {
                        "c": ["ssh"],
                        "g": {
                          "a": "unlocked",
                          "w": true,
                          "x": { "h": "য়ে যাবে", "s": "custom", "v": 1 }
                        },
                        "l": "Fahim",
                        "m": "kill_switch",
                        "s": 4,
                        "t": 1795366051224
                      },
                      "decoded": {
                        "config": {
                          "protection": {
                            "tunnelBanner": { "h": "ফ্রি ", "s": "custom", "v": 1 }
                          }
                        },
                        "profiles": {
                          "s0": {
                            "configJson": {
                              "dns": "8.8.8.8",
                              "methods": ["SlowDns"],
                              "ns": "nsts3.me-link.uk",
                              "pubKey": "75148e30f06c1a58700756c3746640d7e27d73632aed5b4844bdded4ffe86220",
                              "serverAddress": "nsts3.me-link.uk:22",
                              "slowDnsMode": "auto",
                              "timeout": 30
                            },
                            "host": "nsts3.me-link.uk",
                            "name": "Fahim - nsts3.me-link.uk (Pro)",
                            "password": "RgNax1QLRtx68x",
                            "port": 22,
                            "protocolType": "SSH",
                            "username": "jssh_9qknqpo"
                          }
                        }
                      },
                      "format": "http-custom"
                    }
                """.trimIndent()
            ),
            HttpCustomTunnelProfile(
                id = "hc_cloudflare_slowdns_2",
                name = "Fahim - nsts3.me-link.uk (Cloudflare DNS)",
                operatorName = "GP, Robi & Airtel (1.1.1.1)",
                host = "nsts3.me-link.uk",
                port = 22,
                username = "jssh_9qknqpo",
                password = "RgNax1QLRtx68x",
                protocol = "SlowDns + SSH",
                nameserver = "nsts3.me-link.uk",
                pubKey = "75148e30f06c1a58700756c3746640d7e27d73632aed5b4844bdded4ffe86220",
                bannerNote = "Cloudflare 1.1.1.1 + Carrier Fallback",
                dnsServer = "1.1.1.1",
                rawJson = """
                    {
                      "config": {
                        "c": ["ssh"],
                        "g": {
                          "a": "unlocked",
                          "w": true,
                          "x": { "h": "Cloudflare 1.1.1.1 + Carrier Fallback", "s": "custom", "v": 1 }
                        },
                        "l": "Fahim",
                        "m": "kill_switch",
                        "s": 4,
                        "t": 1795366051224
                      },
                      "decoded": {
                        "config": {
                          "protection": {
                            "tunnelBanner": { "h": "ফ্রি ", "s": "custom", "v": 1 }
                          }
                        },
                        "profiles": {
                          "s0": {
                            "configJson": {
                              "dns": "1.1.1.1",
                              "methods": ["SlowDns"],
                              "ns": "nsts3.me-link.uk",
                              "pubKey": "75148e30f06c1a58700756c3746640d7e27d73632aed5b4844bdded4ffe86220",
                              "serverAddress": "nsts3.me-link.uk:22",
                              "slowDnsMode": "auto",
                              "timeout": 30
                            },
                            "host": "nsts3.me-link.uk",
                            "name": "Fahim - nsts3.me-link.uk (Cloudflare DNS)",
                            "password": "RgNax1QLRtx68x",
                            "port": 22,
                            "protocolType": "SSH",
                            "username": "jssh_9qknqpo"
                          }
                        }
                      },
                      "format": "http-custom"
                    }
                """.trimIndent()
            )
        )

        fun parseFromJson(rawJson: String): Result<HttpCustomTunnelProfile> = runCatching {
            val root = JSONObject(rawJson)
            val decoded = root.optJSONObject("decoded")
            val profilesObj = decoded?.optJSONObject("profiles") ?: root.optJSONObject("profiles")
            val s0 = profilesObj?.optJSONObject("s0")
            val rawH = s0?.optString("h", "").orEmpty()
            val configJson = s0?.optJSONObject("configJson")
                ?: rawH.takeIf { it.trimStart().startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }

            val name = s0?.optString("name", "")?.takeIf { it.isNotBlank() } ?: "Custom Profile"
            val host = s0?.optString("host")?.takeIf { it.isNotBlank() }
                ?: s0?.optString("c")?.takeIf { it.isNotBlank() }
                ?: configJson?.optString("serverAddress")?.substringBefore(":")?.takeIf { it.isNotBlank() }
                ?: "nsts3.me-link.uk"
            val port = s0?.optInt("port", 0)?.takeIf { it in 1..65535 }
                ?: s0?.optInt("d", 0)?.takeIf { it in 1..65535 }
                ?: configJson?.optString("serverAddress")?.substringAfter(":", "")?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: 22
            val username = s0?.optString("username")?.takeIf { it.isNotBlank() }
                ?: s0?.optString("e")?.takeIf { it.isNotBlank() }
                ?: "jssh_9qknqpo"
            val password = s0?.optString("password")?.takeIf { it.isNotBlank() }
                ?: s0?.optString("f")?.takeIf { it.isNotBlank() }
                ?: "secret"
            val ns = configJson?.optString("ns")?.takeIf { it.isNotBlank() } ?: host
            val dns = configJson?.optString("dns")?.takeIf { it.isNotBlank() } ?: "auto"
            val pubKey = configJson?.optString("pubKey", "") ?: ""
            val banner = decoded?.optJSONObject("config")?.optJSONObject("protection")
                ?.optJSONObject("tunnelBanner")?.optString("h")?.takeIf { it.isNotBlank() }
                ?: root.optJSONObject("config")?.optJSONObject("g")?.optJSONObject("x")
                    ?.optString("h")?.takeIf { it.isNotBlank() }
                ?: "Custom Config"

            HttpCustomTunnelProfile(
                id = "hc_custom_${System.currentTimeMillis()}",
                name = name,
                operatorName = "Custom HTTP Profile",
                host = host,
                port = port,
                username = username,
                password = password,
                protocol = "SlowDns + SSH",
                nameserver = ns,
                pubKey = pubKey,
                bannerNote = banner,
                rawJson = rawJson,
                dnsServer = dns
            )
        }
    }
}

object HttpCustomProfileParser {
    fun parseFromJson(rawJson: String): Result<HttpCustomTunnelProfile> =
        HttpCustomTunnelProfile.parseFromJson(rawJson)
}
