package com.eyeplus.recon

import java.net.InetAddress

data class CameraInfo(
    var mac: String = "",
    var ip: String = "",
    var hostname: String = "",
    var vendor: String = "",
    var firmware: String = "",
    var serial: String = "",
    var model: String = ""
)

data class PortScanResult(
    val port: Int,
    val open: Boolean,
    val service: String = "",
    val banner: String = ""
)

data class OnvifProfile(
    val token: String,
    val name: String,
    val encoding: String,
    val resolution: String,
    val rtspUri: String = ""
)

data class CgiResult(
    val endpoint: String,
    val code: Int,
    val body: String,
    val parsedParams: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

data class TrafficCapture(
    val timestamp: Long,
    val srcIp: String,
    val dstIp: String,
    val dstPort: Int,
    val protocol: String,
    val dnsQuery: String? = null,
    val payload: String = ""
)

data class CloudEndpoint(
    val domain: String,
    val ip: String = "",
    val port: Int = 0,
    val protocol: String = "TCP",
    val observedAt: Long = 0L
)

data class ReconReport(
    val camera: CameraInfo,
    val ports: List<PortScanResult>,
    val onvifProfiles: List<OnvifProfile>,
    val cgiResults: List<CgiResult>,
    val cloudEndpoints: List<CloudEndpoint>,
    val traffic: List<TrafficCapture>,
    val sensitiveParams: Map<String, String> = emptyMap(),
    val generatedAt: Long
)

enum class ReconStatus {
    IDLE, SCANNING, ONVIF, CGI, RTSP, CLOUD, TRAFFIC, COMPLETE, ERROR
}

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class AppSettings(
    var cameraIp: String = "",
    var username: String = "admin",
    var password: String = "admin",
    var autoScan: Boolean = true,
    var includeSniff: Boolean = false,
    var includeCgi: Boolean = true,
    var rtspTimeout: Int = 10,
    var themeMode: ThemeMode = ThemeMode.SYSTEM
)
