package com.eyeplus.recon

import java.net.HttpURLConnection
import java.net.URL

object NetworkApi {
    suspend fun getRaw(url: String, user: String? = null, pass: String? = null): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (user != null && pass != null) {
                val cred = "$user:$pass".toByteArray().let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                conn.setRequestProperty("Authorization", "Basic $cred")
            }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) { null }
    }
}
