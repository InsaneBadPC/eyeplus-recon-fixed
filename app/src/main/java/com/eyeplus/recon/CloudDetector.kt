package com.eyeplus.recon

import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class CloudDetector(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val TAG = "CloudDetector"

    data class CloudService(
        val name: String,
        val domains: List<String>,
        val description: String
    )

    private val cloudServices = listOf(
        CloudService(
            "iCam365",
            listOf("icam365.com", "sdk.icloud365.com", "p2p.icloud365.com", "api.icloud365.com"),
            "Hlavni cloudova sluzba - vzdaleny pristup, notifikace"
        ),
        CloudService(
            "iCam365 P2P",
            listOf("p2piam.365.com", "p2p.365cam.com", "p2p.icam365.com"),
            "P2P relay - pripojeni pres proxy kdyz nelze NAT"
        ),
        CloudService(
            "Ginatex/Goke",
            listOf("ginatex.com", "gokemcu.com", "sdk.ginatex.com"),
            "Vyrobce cipu Goke - OTA updates, diagnostika"
        ),
        CloudService(
            "HiSilicon",
            listOf("hisilicon.com", "hi3519.com"),
            "Cipova sada - potentialni cloud backdoor"
        ),
        CloudService(
            "Tuya",
            listOf("tuyaus.com", "pxl.tuyaus.com", "a1.tuyaus.com"),
            "Alternativni smart home cloud"
        ),
        CloudService(
            "vcom-tunnel",
            listOf("vcom.com", "vcamtunnel.com"),
            "Proprietarni tunnel protokol pro kameru"
        )
    )

    /**
     * Monitoruje aktivni odchozi spojeni z kamery behem N sekund
     */
    suspend fun monitorOutgoing(
        cameraIp: String,
        durationSeconds: Int = 30
    ): List<CloudEndpoint> = withContext(Dispatchers.IO) {
        val endpoints = mutableListOf<CloudEndpoint>()
        val endTime = System.currentTimeMillis() + (durationSeconds * 1000L)
        val checkedPairs = mutableSetOf<String>()

        // Cteme /proc/net/tcp a /proc/net/udp
        while (System.currentTimeMillis() < endTime) {
            try {
                val tcpFile = File("/proc/net/tcp")
                val udpFile = File("/proc/net/udp")

                listOf(tcpFile to "TCP", udpFile to "UDP").forEach { (file, proto) ->
                    if (file.exists() && file.canRead()) {
                        file.readLines().drop(1).forEach { line ->
                            val parts = line.trim().split("\\s+".toRegex())
                            if (parts.size >= 4) {
                                val remote = parts[2]
                                val state = parts[3]
                                // TCP: state 01=ESTABLISHED, UDP: bez stavu
                                if (proto == "TCP" && state != "01") return@forEach

                                val ipPort = parseIpPort(remote)
                                if (ipPort != null) {
                                    val (ip, port) = ipPort
                                    val key = "$ip:$port"
                                    if (!checkedPairs.contains(key) && ip != "0.0.0.0") {
                                        checkedPairs.add(key)
                                        val service = detectService(ip, port)
                                        endpoints.add(
                                            CloudEndpoint(
                                                domain = service,
                                                ip = ip,
                                                port = port,
                                                protocol = proto,
                                                observedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "monitorOutgoing", e)
            }
            delay(2000)
        }
        endpoints.distinctBy { "${it.ip}:${it.port}" }
    }

    /**
     * Zkusi se pripojit na cloud porty kamery (8001 = vcom tunnel)
     */
    suspend fun scanCameraCloudPorts(
        cameraIp: String,
        cloudPorts: List<Int> = listOf(8001, 34567, 55443, 8899)
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, String>()
        for (port in cloudPorts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(cameraIp, port), 2000)
                    result[port] = when (port) {
                        8001 -> "vcom-tunnel (iCam365 cloud tunnel)"
                        34567 -> "ONVIF-ALT"
                        55443 -> "RTSP-ALT"
                        8899 -> "HiSilicon media"
                        else -> "OPEN"
                    }
                }
            } catch (_: Exception) {
                // port closed
            }
        }
        result
    }

    /**
     * Zkusime resolvovat cloud domeny - pokud kamery pouzivaji konkretni IP,
     * zjistimereverse lookup
     */
    suspend fun detectCloudDomains(): List<String> = withContext(Dispatchers.IO) {
        val found = mutableListOf<String>()
        for (svc in cloudServices) {
            for (domain in svc.domains) {
                try {
                    val addrs = InetAddress.getAllByName(domain)
                    if (addrs.isNotEmpty()) {
                        found.add("${svc.name}: $domain -> ${addrs.first().hostAddress}")
                    }
                } catch (_: Exception) {
                    // neresoelvutelna domena
                }
            }
        }
        found
    }

    /**
     * Identifikuje sluzbu podle IP/portu
     */
    private fun detectService(ip: String, port: Int): String {
        if (ip.contains(".") && ip.split(".").all { it.toIntOrNull() != null }) {
            // muzeme zkusit reverse DNS
        }
        return when (port) {
            8001 -> "iCam365-vcom-tunnel"
            443, 8443 -> "HTTPS-cloud"
            80, 8080 -> "HTTP-cloud"
            554 -> "RTSP"
            34567 -> "ONVIF"
            else -> "cloud:$port"
        }
    }

    /**
     * Zkontroluje nainstalovane aplikace pro praci s kamerou
     */
    fun getInstalledCameraApps(): List<String> {
        val found = mutableListOf<String>()
        val knownPackages = listOf(
            "com.icam365.smarten",
            "com.icam365.filmcamera",
            "com.ginatex.camera",
            "com.yoosee",
            "com.xm.get",
            "com.tuya.android.common",
            "com.eyeplus.recon",
            "com.eyeplus.icam",
            "com.ginatex.app",
            "com.vcam365.app",
            "com.gokemcu.app"
        )
        val pm = context.packageManager
        for (pkg in knownPackages) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                found.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // neni nainstalovana
            }
        }
        return found
    }

    /**
     * Traffic stats - kolik dat proslo pres mobil/wifi
     */
    fun getTrafficStats(): Map<String, Long> = try {
        mapOf(
            "mobile_rx_B" to TrafficStats.getMobileRxBytes(),
            "mobile_tx_B" to TrafficStats.getMobileTxBytes(),
            "total_rx_B" to TrafficStats.getTotalRxBytes(),
            "total_tx_B" to TrafficStats.getTotalTxBytes()
        )
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parseIpPort(address: String): Pair<String, Int>? {
        val parts = address.split(":")
        if (parts.size != 2) return null
        return try {
            val port = parts[1].toInt(16)
            val ipLong = parts[0].toLong(16)
            val ip = String.format(
                "%d.%d.%d.%d",
                ipLong and 0xFF,
                (ipLong shr 8) and 0xFF,
                (ipLong shr 16) and 0xFF,
                (ipLong shr 24) and 0xFF
            )
            ip to port
        } catch (e: Exception) { null }
    }
}
