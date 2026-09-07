package com.eyeplus.recon

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Socket
import java.nio.ByteBuffer
import java.util.regex.Pattern

class StreamPlayer {
    private val TAG = "StreamPlayer"
    private var isPlaying = false
    private var playerThread: Thread? = null
    private var codec: MediaCodec? = null
    
    fun playStream(rtspUrl: String, surface: Surface?): Boolean {
        if (isPlaying) return false
        isPlaying = true
        
        playerThread = Thread {
            try {
                val uri = Uri.parse(rtspUrl)
                val host = uri.host
                val port = uri.port.takeIf { it != -1 } ?: 554
                val path = uri.path ?: "/0/av0"
                
                Log.d(TAG, "Connecting to $host:$port$path")
                
                val socket = Socket(host, port)
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                
                // RTSP DESCRIBE
                val describeRequest = """
                    DESCRIBE $rtspUrl RTSP/1.0
                    CSeq: 1
                    User-Agent: EyePlusRecon/1.0
                    Accept: application/sdp
                    
                """.trimIndent()
                
                output.write(describeRequest.toByteArray())
                output.flush()
                
                val response = readHttpResponse(input)
                Log.d(TAG, "DESCRIBE response: ${response.take(500)}")
                
                if (response.startsWith("RTSP/1.0 200") || response.startsWith("RTSP/1.0 401")) {
                    // Try with auth
                    val authString = "Basic " + android.util.Base64.encodeToString("admin:admin".toByteArray(), android.util.Base64.NO_WRAP)
                    
                    val authRequest = """
                        DESCRIBE $rtspUrl RTSP/1.0
                        CSeq: 1
                        User-Agent: EyePlusRecon/1.0
                        Accept: application/sdp
                        Authorization: $authString
                        
                    """.trimIndent()
                    
                    output.write(authRequest.toByteArray())
                    output.flush()
                    
                    val authResponse = readHttpResponse(input)
                    Log.d(TAG, "Auth DESCRIBE response: ${authResponse.take(500)}")
                    
                    if (authResponse.startsWith("RTSP/1.0 200")) {
                        // SETUP
                        val setupRequest = """
                            SETUP $rtspUrl RTSP/1.0
                            CSeq: 2
                            Authorization: $authString
                            Transport: RTP/AVP;unicast;client_port=8000-8001
                            
                        """.trimIndent()
                        
                        output.write(setupRequest.toByteArray())
                        output.flush()
                        
                        val setupResponse = readHttpResponse(input)
                        Log.d(TAG, "SETUP response: $setupResponse")
                        
                        if (setupResponse.startsWith("RTSP/1.0 200")) {
                            // PLAY
                            val playRequest = """
                                PLAY $rtspUrl RTSP/1.0
                                CSeq: 3
                                Authorization: $authString
                                Session: 123456
                                
                            """.trimIndent()
                            
                            output.write(playRequest.toByteArray())
                            output.flush()
                            
                            val playResponse = readHttpResponse(input)
                            Log.d(TAG, "PLAY response: $playResponse")
                            
                            if (playResponse.startsWith("RTSP/1.0 200")) {
                                // Start receiving RTP packets
                                receiveRtpPackets(input, surface)
                            }
                        }
                    }
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Stream playback error", e)
            } finally {
                isPlaying = false
            }
        }
        playerThread?.start()
        return true
    }
    
    private fun readHttpResponse(input: InputStream): String {
        val response = StringBuilder()
        val reader = BufferedReader(InputStreamReader(input))
        var line: String?
        var contentLength = 0
        var headerComplete = false
        
        while (reader.readLine().also { line = it } != null) {
            if (line!!.isEmpty()) {
                headerComplete = true
                break
            }
            response.append(line).append("\n")
            
            if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line!!.substringAfter(":").trim().toInt()
            }
        }
        
        if (headerComplete && contentLength > 0) {
            val buffer = CharArray(contentLength)
            reader.read(buffer)
            response.append(String(buffer))
        }
        
        return response.toString()
    }
    
    private fun receiveRtpPackets(input: InputStream, surface: Surface?) {
        if (surface == null) return
        
        val buffer = ByteArray(8192)
        try {
            // Configure H264 decoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
            
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            
            // Read RTP packets (basic implementation)
            while (isPlaying) {
                val bytesRead = input.read(buffer)
                if (bytesRead > 0) {
                    // Process RTP packet for H264 NALU
                    if (bytesRead > 12) {
                        val rtpHeader = ByteArray(12)
                        System.arraycopy(buffer, 0, rtpHeader, 0, 12)
                        val payload = ByteArray(bytesRead - 12)
                        System.arraycopy(buffer, 12, payload, 0, bytesRead - 12)
                        
                        // Feed to decoder
                        val inputBufferIndex = codec?.dequeueInputBuffer(10000) ?: -1
                        if (inputBufferIndex >= 0) {
                            val inputBuffer: ByteBuffer? = codec?.getInputBuffer(inputBufferIndex)
                            inputBuffer?.put(payload)
                            codec?.queueInputBuffer(inputBufferIndex, 0, payload.size, System.nanoTime() / 1000, 0)
                        }
                        
                        // Render output
                        val bufferInfo = MediaCodec.BufferInfo()
                        val outputBufferIndex = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                        if (outputBufferIndex >= 0) {
                            codec?.releaseOutputBuffer(outputBufferIndex, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTP packet receiving error", e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
        }
    }
    
    fun stop() {
        isPlaying = false
        try {
            playerThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread join interrupted", e)
        }
    }
}
