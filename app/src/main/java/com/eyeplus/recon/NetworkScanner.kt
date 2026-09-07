package com.eyeplus.recon

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class NetworkScanner(private val context: Context) {
    private val TAG = "NetworkScanner"

    private val vendorSignatures = mapOf(
        "24:72:60" to "Ginatex/EYEPLUS",
        "24:72:5e" to "Ginatex",
        "00:0d:cb" to "D-Link",
        "ac:cc:13" to "Hikvision",
        "00:12:2d" to "Axis",
        "5c:cf:7f" to "YooCam",
        "00:1e:6d" to "Foscam",
        "3c:af:54" to "Reolink",
        "50:9e:cd" to "YI Technology",
        "00:17:88" to "Ubiquiti",
        "00:24:10" to "Foscam",
        "00:e0:4d" to "Ginatex",
        "c8:3a:6b" to "Ginatex",
        "ac:cc:8e" to "Ginatex",
        "d4:7b:b0" to "Ginatex"
    )

    data class NetworkInfo(
        val gateway: String,
        val subnetMask: String,
        val networkInterface: String,
        val localIp: String,
        val prefixLength: Int
    )

    suspend fun getNetworkInfo(): NetworkInfo? = withContext(Dispatchers.IO) {
        try {
            val ni = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull {
                    it.isUp && !it.isLoopback &&
                    java.util.Collections.list(it.inetAddresses).any { addr ->
                        !addr.isLoopbackAddress && addr is Inet4Address
                    }
                } ?: return@withContext null

            val ipv4 = java.util.Collections.list(ni.inetAddresses)
                .filterIsInstance(Inet4Address::class.java)
                .firstOrNull { !it.isLoopbackAddress } ?: return@withContext null

            val gateway = getGateway() ?: "192.168.1.1"
            NetworkInfo(
                gateway = gateway,
                subnetMask = "255.255.255.0",
                networkInterface = ni.name,
                localIp = ipv4.hostAddress ?: "",
                prefixLength = countBits(gateway)
            )
        } catch (e: Exception) {
            Log.e(TAG, "getNetworkInfo failed", e)
            null
        }
    }

    private fun countBits(ip: String): Int {
        return try {
            val addr = InetAddress.getByName(ip)
            val raw = addr.address
            var prefix = 0
            for (b in raw) {
                var v = b.toInt() and 0xff
                while (v != 0) {
                    prefix += (v and 1)
                    v = v shr 1
                }
            }
            prefix
        } catch (e: Exception) { 24 }
    }

    private fun getGateway(): String? {
        return try {
            val dhcp = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            dhcp?.dhcpInfo?.gateway?.let { ipToString(it) }
        } catch (e: Exception) { null }
    }

    private fun ipToString(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    suspend fun scanSubnet(
        baseIp: String,
        range: Int = 254,
        timeoutMs: Int = 800,
        callback: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val parts = baseIp.split(".")
        if (parts.size != 4) return@withContext results
        val subnetBase = parts.take(3).joinToString(".")
        val count = min(254, range)

        for (i in 1..count) {
            val testIp = "$subnetBase.$i"
            try {
                val addr = InetAddress.getByName(testIp)
                if (addr.isReachable(timeoutMs)) {
                    results.add(testIp)
                    callback(testIp)
                }
            } catch (_: IOException) {
                // nedostupny
            }
        }
        results
    }

    suspend fun discoverByMac(): List<CameraInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CameraInfo>()
        try {
            val arpFile = java.io.File("/proc/net/arp")
            if (!arpFile.exists() || !arpFile.canRead()) return@withContext list

            arpFile.readLines().drop(1).forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3].uppercase(Locale.ROOT)
                    if (mac != "00:00:00:00:00:00" && mac != "(INCOMPLETE)") {
                        val prefix = mac.take(8)
                        val vendor = vendorSignatures[prefix] ?: ""
                        if (vendor.isNotEmpty()) {
                            list.add(CameraInfo(mac = mac, ip = ip, vendor = vendor))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "discoverByMac failed", e)
        }
        list
    }

    suspend fun portScan(
        ip: String,
        ports: List<Int> = listOf(80, 443, 554, 8001, 34567, 23, 22, 8080, 55443, 8899, 5000, 8888),
        timeoutMs: Int = 1500
    ): List<PortScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PortScanResult>()
        for (port in ports) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                val banner = tryGrabBanner(s, port)
                s.close()
                results.add(
                    PortScanResult(
                        port = port,
                        open = true,
                        service = getServiceName(port),
                        banner = banner
                    )
                )
            } catch (_: IOException) {
                results.add(PortScanResult(port = port, open = false, service = getServiceName(port)))
            }
        }
        results
    }

    private fun tryGrabBanner(s: Socket, port: Int): String {
        return try {
            s.soTimeout = 1500
            val ins = s.getInputStream()
            val buf = ByteArray(256)
            val n = ins.read(buf)
            if (n > 0) String(buf, 0, n).trim() else ""
        } catch (e: Exception) { "" }
    }

    private fun getServiceName(port: Int): String = when (port) {
        80 -> "HTTP"
        443 -> "HTTPS"
        554 -> "RTSP"
        8001 -> "vcom-tunnel"
        34567 -> "ONVIF"
        23 -> "Telnet"
        22 -> "SSH"
        8080 -> "HTTP-alt"
        55443 -> "RTSP-alt"
        8899 -> "ONVIF-alt"
        5000 -> "UPnP"
        8888 -> "HTTP-mgmt"
        else -> "?"
    }

    fun getTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    }
}
