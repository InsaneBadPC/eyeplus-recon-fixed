package com.eyeplus.recon

import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request

data class CgiCommand(
    val path: String,
    val cmd: String,
    val description: String
)

class SettingsFinder(private val httpClient: OkHttpClient = OkHttpClient()) {
    private val TAG = "SettingsFinder"

    private val knownEndpoints = listOf(
        // Ginatex / HiSilicon - hlavní
        "/cgi-bin/hi3510/param.cgi",
        "/cgi-bin/hi3510/config.cgi",
        "/cgi-bin/hi3510/systemInfo.cgi",
        "/cgi-bin/hi3510/NetworkCfg.cgi",
        "/cgi-bin/hi3510/videoParam.cgi",
        "/cgi-bin/hi3510/ptzParam.cgi",
        "/cgi-bin/hi3510/motionDetect.cgi",
        "/cgi-bin/hi3510/getMotionInfo.cgi",
        "/cgi-bin/hi3510/getAlarm.cgi",
        "/cgi-bin/hi3510/userInfo.cgi",
        "/cgi-bin/hi3510/getuserinfo.cgi",
        "/cgi-bin/hi3510/GetVideoFormat.cgi",
        "/cgi-bin/hi3510/GetAudioFormat.cgi",
        "/cgi-bin/hi3510/GetVideoAttr.cgi",
        "/cgi-bin/hi3510/GetVideoAttrEx.cgi",
        "/cgi-bin/hi3510/GetStreamURI.cgi",
        "/cgi-bin/hi3510/GetSnapshot.cgi",
        "/cgi-bin/hi3510/GetDVRDateTime.cgi",
        "/cgi-bin/hi3510/SetDVRDateTime.cgi",
        "/cgi-bin/hi3510/GetDevInfo.cgi",
        "/cgi-bin/hi3510/GetSerialNumber.cgi",
        // Vcom - iCam365 backend
        "/cgi-bin/vcom/param.cgi",
        "/cgi-bin/vcom/getParam.cgi",
        "/cgi-bin/vcom/setParam.cgi",
        "/cgi-bin/vcom/devInfo.cgi",
        "/cgi-bin/vcom/getDevInfo.cgi",
        "/cgi-bin/vcom/netInfo.cgi",
        "/cgi-bin/vcom/getNetInfo.cgi",
        "/cgi-bin/vcom/cloudCfg.cgi",
        "/cgi-bin/vcom/getCloudCfg.cgi",
        // Generic
        "/decoder_control.cgi",
        "/param.cgi",
        "/status.xml",
        "/get_params.cgi",
        "/getuserinfo",
        "/get_net_info",
        "/get_wireless",
        "/get_ddns",
        "/get_ptz",
        "/get_video",
        "/get_alarm",
        "/get_record",
        "/get_system",
        "/get_config",
        // Další skryté
        "/admin/getParam.cgi",
        "/admin/setParam.cgi",
        "/admin/config.cgi",
        "/admin/system.cgi",
        "/admin/video.cgi",
        "/admin/ptz.cgi",
        "/admin/network.cgi",
        "/admin/wireless.cgi",
        "/admin/ddns.cgi",
        "/admin/record.cgi",
        "/admin/alarm.cgi",
        "/admin/systemInfo.cgi",
        "/admin/getSystemInfo.cgi",
        "/admin/getVideoFormat.cgi",
        "/admin/getVideoAttr.cgi",
        "/admin/getStreamUri.cgi",
        "/admin/getSnapshot.cgi",
        "/admin/getDevInfo.cgi",
        "/admin/getSerialNumber.cgi",
        "/admin/getRecordInfo.cgi",
        "/admin/getRecordList.cgi",
        // YAML/XML konfiguraky
        "/system.conf",
        "/config.xml",
        "/camera.conf",
        "/config.json",
        "/backup.cgi",
        "/firmware.bin",
        "/upgrade.cgi",
        // Diagnostika
        "/diag.cgi",
        "/diag/dump.cgi",
        "/diag/log.cgi",
        "/log.txt",
        "/sysinfo.xml"
    )

    private val hi3510Commands = listOf(
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getuserinfo", "Seznam uzivatelu a roli"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getnetinfo", "Sit (IP, maska, DNS)"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getwireless", "WiFi (SSID, heslo)"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getddns", "DDNS konfigurace"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getmotion", "Detekce pohybu"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getptz", "PTZ nastaveni"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getvideoattr", "Video (rozliseni, kodek, fps)"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getaudioattr", "Audio parametry"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getrecord", "Nahravani"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getalarm", "Alarmy"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getcloud", "Cloud nastaveni (iCam365)"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getcloudcfg", "Cloud konfigurace"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "gettimezone", "Casove pasmo"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getntp", "NTP server"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getsmb", "SMB/NFS"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getftp", "FTP server"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getsmtp", "SMTP email"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getrtsp", "RTSP konfigurace"),
        CgiCommand("/cgi-bin/hi3510/param.cgi", "getonvif", "ONVIF konfigurace")
    )

    suspend fun probeEndpoints(ip: String, username: String, password: String): List<CgiResult> {
        val results = mutableListOf<CgiResult>()
        val auth = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(),
            Base64.NO_WRAP
        )

        for (endpoint in knownEndpoints) {
            try {
                val fullUrl = "http://$ip$endpoint"
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("Authorization", auth)
                    .header("User-Agent", "EyePlusRecon/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val parsedParams = parseParams(body)

                    results.add(
                        CgiResult(
                            endpoint = endpoint,
                            code = response.code,
                            body = body.take(1000),
                            parsedParams = parsedParams
                        )
                    )
                }
                delay(80)
            } catch (e: Exception) {
                results.add(
                    CgiResult(
                        endpoint = endpoint,
                        code = -1,
                        body = e.message ?: "Error",
                        parsedParams = emptyMap()
                    )
                )
            }
        }
        return results
    }

    suspend fun probeCommands(
        ip: String, username: String, password: String,
        cmds: List<CgiCommand> = hi3510Commands
    ): List<CgiResult> {
        val results = mutableListOf<CgiResult>()
        val auth = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(),
            Base64.NO_WRAP
        )

        for (c in cmds) {
            try {
                val fullUrl = "http://$ip${c.path}?cmd=${c.cmd}"
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("Authorization", auth)
                    .header("User-Agent", "EyePlusRecon/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    results.add(
                        CgiResult(
                            endpoint = "${c.path}?cmd=${c.cmd}",
                            code = response.code,
                            body = body,
                            parsedParams = parseParams(body)
                        )
                    )
                }
                delay(80)
            } catch (e: Exception) {
                results.add(
                    CgiResult(
                        endpoint = "${c.path}?cmd=${c.cmd}",
                        code = -1,
                        body = e.message ?: "Error",
                        parsedParams = emptyMap()
                    )
                )
            }
        }
        return results
    }

    private fun parseParams(body: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        // CGI query style: key=value&key2=value2
        val queryPattern = Regex("([a-zA-Z_][a-zA-Z0-9_.-]*)=([^&\\r\\n]+)")
        queryPattern.findAll(body).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (key.isNotEmpty() && value.isNotEmpty()) {
                params[key] = value
            }
        }
        return params
    }

    fun getSensitiveParams(results: List<CgiResult>): Map<String, String> {
        val sensitiveKeys = listOf(
            "username", "password", "pwd", "user", "admin",
            "wifi_ssid", "wifi_key", "wifi_pass", "wpa_psk",
            "ddns_domain", "ddns_user", "ddns_pwd",
            "ptz_preset", "motion_zone", "record_path",
            "video_resolution", "stream_type",
            "smb_user", "smb_pwd", "ftp_user", "ftp_pwd",
            "smtp_user", "smtp_pwd", "onvif_user", "onvif_pwd",
            "cloud_uid", "cloud_key", "p2p_uid", "p2p_key",
            "rtsp_user", "rtsp_pwd"
        )
        val found = mutableMapOf<String, String>()
        for (result in results) {
            for ((key, value) in result.parsedParams) {
                val lower = key.lowercase()
                if (sensitiveKeys.any { lower.contains(it) }) {
                    found["${result.endpoint}::$key"] = value
                }
            }
        }
        return found
    }
}
