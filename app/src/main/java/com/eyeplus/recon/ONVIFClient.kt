package com.eyeplus.recon

import android.util.Log
import org.simpleframework.xml.Element
import org.simpleframework.xml.Path
import org.simpleframework.xml.Root
import java.net.HttpURLConnection
import java.net.URL

class ONVIFClient {
    private val TAG = "ONVIFClient"

    @Root(name = "Profiles")
    data class OnvifProfile(
        @field:Element(name = "token", required = false)
        @param:Element(name = "token", required = false)
        var token: String = "",
        @field:Element(name = "Name", required = false)
        @param:Element(name = "Name", required = false)
        var name: String = "",
        @field:Element(name = "VideoEncoderConfiguration", required = false)
        @param:Element(name = "VideoEncoderConfiguration", required = false)
        var videoEncoder: String = "",
        var rtspUri: String = "",
        var encoding: String = "H264",
        var resolution: String = "1280x720"
    )

    fun discover(ip: String, username: String = "admin", password: String = "admin"): List<OnvifProfile> {
        return try {
            val profiles = getProfiles(ip, username, password)
            profiles.map { profile ->
                val streamUri = getStreamUri(ip, username, password, profile.token)
                profile.copy(rtspUri = streamUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONVIF discovery failed", e)
            emptyList()
        }
    }

    private fun getProfiles(ip: String, username: String, password: String): List<OnvifProfile> {
        val request = """<?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                              xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
                <soapenv:Header/>
                <soapenv:Body>
                    <trt:GetProfiles/>
                </soapenv:Body>
            </soapenv:Envelope>""".trimIndent()

        val response = sendSoapRequest(ip, username, password, request)
        return parseProfiles(response)
    }

    private fun getStreamUri(ip: String, username: String, password: String, profileToken: String): String {
        val request = """<?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                              xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
                <soapenv:Header/>
                <soapenv:Body>
                    <trt:GetStreamUri>
                        <trt:StreamSetup>
                            <trt:Stream>RTP-Unicast</trt:Stream>
                            <trt:Transport>
                                <trt:Protocol>RTSP</trt:Protocol>
                            </trt:Transport>
                        </trt:StreamSetup>
                        <trt:ProfileToken>$profileToken</trt:ProfileToken>
                    </trt:GetStreamUri>
                </soapenv:Body>
            </soapenv:Envelope>""".trimIndent()

        val response = sendSoapRequest(ip, username, password, request)
        return extractUri(response) ?: ""
    }

    private fun sendSoapRequest(ip: String, username: String, password: String, request: String): String {
        val urlString = "http://$ip/onvif/device_service"
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        connection.setRequestProperty("SOAPAction", "")
        connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP))
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        connection.outputStream.use { it.write(request.toByteArray()) }

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("HTTP error code: $responseCode")
        }
    }

    private fun extractUri(response: String): String? {
        val pattern = Regex("<[^>]*anyUri[^>]*>(.*?)</[^>]*>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(response)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseProfiles(response: String): List<OnvifProfile> {
        val profiles = mutableListOf<OnvifProfile>()
        val profilePattern = Regex("<[^:]*:?Profiles[^>]*token=\"([^\"]+)\"[^>]*name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
        
        profilePattern.findAll(response).forEachIndexed { index, matchResult ->
            val token = matchResult.groupValues.getOrNull(1) ?: "profile_$index"
            val name = matchResult.groupValues.getOrNull(2) ?: "Profile $index"
            val encoding = "H264"
            val resolution = if (name.contains("main", ignoreCase = true)) "1920x1080" else "640x480"
            profiles.add(OnvifProfile(token = token, name = name, encoding = encoding, resolution = resolution))
        }
        return profiles
    }
}
