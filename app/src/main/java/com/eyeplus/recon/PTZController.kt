package com.eyeplus.recon

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PTZController(private val httpClient: OkHttpClient = OkHttpClient()) {
    private val TAG = "PTZController"
    
    // HTTP fallback commands
    companion object {
        const val CMD_UP = 0
        const val CMD_DOWN = 2
        const val CMD_LEFT = 4
        const val CMD_RIGHT = 6
        const val CMD_STOP = 1
        const val CMD_ZOOM_IN = 8
        const val CMD_ZOOM_OUT = 9
        const val CMD_PRESET_1 = 31
        const val CMD_PRESET_2 = 33
        const val CMD_PRESET_3 = 35
    }

    // Control via HTTP fallback
    fun controlHttp(ip: String, username: String, password: String, command: Int): Boolean {
        return try {
            val url = "http://$ip/decoder_control.cgi?command=$command"
            val auth = "Basic " + android.util.Base64.encodeToString(
                "$username:$password".toByteArray(), android.util.Base64.NO_WRAP
            )
            
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .build()
            
            val response = httpClient.newCall(request).execute()
            val success = response.code == 200
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "HTTP PTZ control failed", e)
            false
        }
    }

    // Control via ONVIF RelativeMove
    fun controlOnvif(ip: String, username: String, password: String, pan: Float, tilt: Float): Boolean {
        return try {
            val soapEnvelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:ptz="http://www.onvif.org/ver20/ptz/wsdl">
                    <soapenv:Header/>
                    <soapenv:Body>
                        <ptz:RelativeMove>
                            <ptz:ProfileToken>Profile_1</ptz:ProfileToken>
                            <ptz:Translation>
                                <ptz:PanTilt x="$pan" y="$tilt"/>
                            </ptz:Translation>
                        </ptz:RelativeMove>
                    </soapenv:Body>
                </soapenv:Envelope>
            """.trimIndent()

            val auth = "Basic " + android.util.Base64.encodeToString(
                "$username:$password".toByteArray(), android.util.Base64.NO_WRAP
            )

            val request = Request.Builder()
                .url("http://$ip/onvif/ptz_service")
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .header("Authorization", auth)
                .post(soapEnvelope.toRequestBody("application/soap+xml".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val success = response.code == 200
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "ONVIF PTZ control failed", e)
            false
        }
    }

    // Convenience methods for common actions
    fun moveUp(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_UP)
    fun moveDown(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_DOWN)
    fun moveLeft(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_LEFT)
    fun moveRight(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_RIGHT)
    fun stop(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_STOP)
    fun zoomIn(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_ZOOM_IN)
    fun zoomOut(ip: String, username: String, password: String): Boolean = controlHttp(ip, username, password, CMD_ZOOM_OUT)
    fun goPreset(ip: String, username: String, password: String, preset: Int): Boolean {
        return when (preset) {
            1 -> controlHttp(ip, username, password, CMD_PRESET_1)
            2 -> controlHttp(ip, username, password, CMD_PRESET_2)
            3 -> controlHttp(ip, username, password, CMD_PRESET_3)
            else -> false
        }
    }
}
