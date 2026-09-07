package com.eyeplus.recon

import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

class TrafficCaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isCapturing = false
    private var packetCount = 0L
    private var bytesReceived = 0L
    private var bytesSent = 0L

    companion object {
        const val ACTION_START_CAPTURE = "ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "ACTION_STOP_CAPTURE"
        const val EXTRA_PORT = "extra_port"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TrafficCaptureService", "Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val port = intent.getIntExtra(EXTRA_PORT, 554)
                startCapture(port)
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
            }
        }
        return START_STICKY
    }

    private fun startCapture(port: Int) {
        isCapturing = true
        scope.launch {
            while (isCapturing) {
                try {
                    val rx = TrafficStats.getMobileRxBytes()
                    val tx = TrafficStats.getMobileTxBytes()
                    
                    if (rx > bytesReceived) {
                        packetCount++
                        bytesReceived = rx
                    }
                    if (tx > bytesSent) {
                        bytesSent = tx
                    }
                    
                    // Log every second
                    delay(1000L)
                } catch (e: Exception) {
                    Log.e("TrafficCapture", "Capture error", e)
                }
            }
        }
    }

    private fun stopCapture() {
        isCapturing = false
        Log.d("TrafficCaptureService", "Stopped capture")
    }

    fun getStats(): String {
        return buildString {
            appendLine("=== Traffic Statistics ===")
            appendLine("Packets: $packetCount")
            appendLine("Bytes RX: $bytesReceived")
            appendLine("Bytes TX: $bytesSent")
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
