package com.eyeplus.recon

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Shizuku integrace pro privileged operace:
 * - ctemu /proc/net/tcp, /proc/net/udp (RAW connections - bez root/Shizuku nemožné)
 * - spoustim tcpdump pres su
 * - lze nastavit iptables REDIRECT pro packet capture
 * - pristup k sitovym interfacum pres ip link
 */
class ShizukuService : Service() {
    private val binder = ShizukuBinder()

    inner class ShizukuBinder : Binder() {
        fun getService(): ShizukuService = this@ShizukuService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ShizukuService created")
    }

    /**
     * Spusti prikaz pres su
     * @return stdout nebo chybova hlaska
     */
    suspend fun execAsRoot(command: String): String = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            reader.close()
            errorReader.close()
            process.waitFor()
            if (error.isNotEmpty() && output.isEmpty()) error else output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Precte libovolny soubor jako root
     */
    suspend fun catFile(path: String): String = execAsRoot("cat $path")

    /**
     * Seznam sitovych rozhrani pres ip
     */
    suspend fun getNetworkInterfaces(): String = execAsRoot("ip link show")

    /**
     * Routing tabulka
     */
    suspend fun getRouteTable(): String = execAsRoot("ip route show")

    /**
     * DNS resolver
     */
    suspend fun getDnsServers(): String = execAsRoot("getprop net.dns1") + "\n" + execAsRoot("getprop net.dns2")

    /**
     * Aktivni TCP/UDP konexe
     */
    suspend fun getActiveConnections(): String {
        val tcp = catFile("/proc/net/tcp")
        val udp = catFile("/proc/net/udp")
        return "=== TCP ===\n$tcp\n=== UDP ===\n$udp"
    }

    /**
     * Spusti tcpdump a vrati cestu k souboru (pouzij FileProvider pro sdileni)
     * Muzete pouzit Vysherovo VPN API misto tcpdump
     */
    suspend fun startTcpdump(
        iface: String = "any",
        outputFile: String = "/sdcard/Download/eyeplus_capture.pcap",
        filter: String = "host $outputFile"
    ): String = execAsRoot("tcpdump -i $iface $filter -w $outputFile 2>&1")

    /**
     * Zastavi tcpdump
     */
    suspend fun stopTcpdump(): String = execAsRoot("pkill -9 tcpdump")

    /**
     * Nastavi iptables REDIRECT pro zachytavani trafficu na portu
     */
    suspend fun enableRedirect(sourcePort: Int, destPort: Int): Boolean {
        val out = execAsRoot("iptables -t nat -A OUTPUT -p tcp --dport $sourcePort -j REDIRECT --to-port $destPort")
        return !out.startsWith("ERROR")
    }

    suspend fun disableRedirect(destPort: Int): Boolean {
        val out = execAsRoot("iptables -t nat -D OUTPUT -p tcp -j REDIRECT --to-port $destPort")
        return !out.startsWith("ERROR")
    }

    /**
     * Prohleda /proc za ucelem identifikace jestli dana IP komunikuje
     */
    suspend fun checkCameraConnections(cameraIp: String): List<String> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<String>()
        try {
            val tcp = File("/proc/net/tcp")
            if (tcp.exists() && tcp.canRead()) {
                tcp.readLines().drop(1).forEach { line ->
                    if (line.contains(cameraIp, ignoreCase = true)) hits.add("TCP: $line")
                }
            }
            val udp = File("/proc/net/udp")
            if (udp.exists() && udp.canRead()) {
                udp.readLines().drop(1).forEach { line ->
                    if (line.contains(cameraIp, ignoreCase = true)) hits.add("UDP: $line")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkCameraConnections failed", e)
        }
        hits
    }

    companion object {
        private const val TAG = "ShizukuService"
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
