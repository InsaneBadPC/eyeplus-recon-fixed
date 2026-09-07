package com.eyeplus.recon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

data class ConnectionRecord(
    val timestamp: Long,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val protocol: String,
    val state: String = "ESTABLISHED"
)

class PacketCapture {
    private val TAG = "PacketCapture"
    private val isCapturing = AtomicBoolean(false)

    suspend fun monitorPort(targetIp: String, port: Int, durationSeconds: Int = 60): List<ConnectionRecord> =
        withContext(Dispatchers.IO) {
            val records = mutableListOf<ConnectionRecord>()
            val endTime = System.currentTimeMillis() + (durationSeconds * 1000L)
            isCapturing.set(true)

            try {
                while (System.currentTimeMillis() < endTime && isCapturing.get()) {
                    try {
                        val socket = Socket(targetIp, port)
                        records.add(
                            ConnectionRecord(
                                timestamp = System.currentTimeMillis(),
                                srcIp = socket.localAddress.hostAddress ?: "",
                                dstIp = targetIp,
                                srcPort = socket.localPort,
                                dstPort = port,
                                protocol = "TCP",
                                state = "OPEN"
                            )
                        )
                        socket.close()
                    } catch (_: Exception) {
                        // nedostupny
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        return@withContext records
                    }
                }
            } finally {
                isCapturing.set(false)
            }
            records
        }

    suspend fun readProcNetTcp(): List<ConnectionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ConnectionRecord>()
        try {
            val f = File("/proc/net/tcp")
            if (!f.exists() || !f.canRead()) return@withContext list
            f.readLines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val local = parseIpPort(parts[1])
                    val remote = parseIpPort(parts[2])
                    val state = parts[3]
                    if (local != null && remote != null && state == "01") {
                        list.add(
                            ConnectionRecord(
                                timestamp = System.currentTimeMillis(),
                                srcIp = local.first,
                                dstIp = remote.first,
                                srcPort = local.second,
                                dstPort = remote.second,
                                protocol = "TCP",
                                state = "ESTABLISHED"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readProcNetTcp", e)
        }
        list
    }

    suspend fun readProcNetUdp(): List<ConnectionRecord> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ConnectionRecord>()
        try {
            val f = File("/proc/net/udp")
            if (!f.exists() || !f.canRead()) return@withContext list
            f.readLines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val local = parseIpPort(parts[1])
                    val remote = parseIpPort(parts[2])
                    if (local != null && remote != null && remote.first != "0.0.0.0") {
                        list.add(
                            ConnectionRecord(
                                timestamp = System.currentTimeMillis(),
                                srcIp = local.first,
                                dstIp = remote.first,
                                srcPort = local.second,
                                dstPort = remote.second,
                                protocol = "UDP",
                                state = "OPEN"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readProcNetUdp", e)
        }
        list
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

    fun stop() { isCapturing.set(false) }
}
