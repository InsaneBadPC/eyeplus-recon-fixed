package com.eyeplus.recon

import android.util.Log
import java.io.*
import java.net.Socket
import java.util.regex.Pattern

class RTSPClient {
    private val TAG = "RTSPClient"
    
    fun describe(url: String, username: String, password: String): String? {
        return try {
            // Parse rtsp:// URL
            val pattern = Pattern.compile("rtsp://([^:/@]+)(?::(\\d+))?(@)?(/[^:]*)?(?::(\\d+))?")
            val matcher = pattern.matcher(url)
            if (!matcher.find()) return null
            
            val host = matcher.group(1)
            val port = matcher.group(2)?.toIntOrNull() ?: 554
            val path = matcher.group(4) ?: "/0/av0"
            
            val socket = Socket(host, port)
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)
            
            // Send DESCRIBE request
            val authBase64 = android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
            val describeRequest = """
                DESCRIBE $url RTSP/1.0
                CSeq: 1
                User-Agent: EyePlusRecon/1.0
                Accept: application/sdp
                Authorization: Basic $authBase64
                
            """.trimIndent()
            
            writer.println(describeRequest)
            
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
                if (response.contains("401")) {
                    // Try again with auth already provided
                    break
                }
            }
            
            socket.close()
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "RTSP DESCRIBE failed", e)
            null
        }
    }
    
    fun playStream(url: String, username: String, password: String): Boolean {
        return try {
            val host = url.substringAfter("rtsp://").substringBefore(":")
            val socket = Socket(host, 554)
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)
            
            // Setup RTSP session
            val authBase64 = android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
            
            // DESCRIBE
            writer.println("DESCRIBE $url RTSP/1.0\r\nCSeq: 1\r\nAuthorization: Basic $authBase64\r\n\r\n")
            reader.readLine()
            
            // SETUP
            writer.println("SETUP $url RTSP/1.0\r\nCSeq: 2\r\nAuthorization: Basic $authBase64\r\nTransport: RTP/RTSP;unicast;client_port=8000-8001\r\n\r\n")
            reader.readLine()
            
            // PLAY
            writer.println("PLAY $url RTSP/1.0\r\nCSeq: 3\r\nAuthorization: Basic $authBase64\r\nSession: 123456\r\n\r\n")
            val playResponse = reader.readLine()
            
            socket.close()
            playResponse?.contains("200") ?: false
        } catch (e: Exception) {
            Log.e(TAG, "RTSP PLAY failed", e)
            false
        }
    }
    
    data class StreamInfo(val codec: String, val resolution: String, val framerate: Int)
    
    fun parseSDP(sdp: String): StreamInfo? {
        val codec = if (sdp.contains("H264")) "H264" else "H265"
        val resolutionPattern = Regex("a=resolution:([^\\r\\n]+)")
        val resMatch = resolutionPattern.find(sdp)
        val resolution = resMatch?.groupValues?.getOrNull(1) ?: "1920x1080"
        
        val frameratePattern = Regex("a=framerate:(\\d+\\.?\\d*)")
        val fpsMatch = frameratePattern.find(sdp)
        val framerate = (fpsMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 30.0).toInt()
        
        return StreamInfo(codec, resolution, framerate)
    }
}
