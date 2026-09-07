package com.eyeplus.recon

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.eyeplus.recon.ONVIFClient.OnvifProfile

class ReconEngine(private val context: Context) {
    private val TAG = "ReconEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val networkScanner = NetworkScanner(context)
    private val onvifClient = ONVIFClient()
    private val rtspClient = RTSPClient()
    private val ptzController = PTZController(httpClient)
    private val settingsFinder = SettingsFinder(httpClient)
    private val cloudDetector = CloudDetector(context, httpClient)
    private val packetCapture = PacketCapture()

    var statusCallback: ((ReconStatus, String) -> Unit)? = null
    var progressCallback: ((String) -> Unit)? = null

    suspend fun runFullRecon(
        targetIp: String,
        username: String = "admin",
        password: String = "admin",
        includeSniff: Boolean = false,
        includeCgi: Boolean = true
    ): ReconReport = withContext(Dispatchers.IO) {
        updateStatus(ReconStatus.SCANNING, "Spoustim rekognoskaci...")

        // 1. Zakladni info
        val cameraInfo = CameraInfo(
            ip = targetIp,
            vendor = "Ginatex/EYEPLUS"
        )

        // 2. Port scan
        updateStatus(ReconStatus.SCANNING, "Prohledavam porty...")
        val ports = networkScanner.portScan(targetIp).also { result ->
            result.forEach {
                progressCallback?.invoke(
                    "Port ${it.port}: ${if (it.open) "OTEVRENY" else "zavreny"} (${it.service})" +
                    if (it.banner.isNotEmpty()) " [${it.banner.take(40)}]" else ""
                )
            }
        }

        // 3. ONVIF
        updateStatus(ReconStatus.ONVIF, "ONVIF dotazy...")
        val profiles = try { onvifClient.discover(targetIp, username, password) } catch (e: Exception) { emptyList() }
        progressCallback?.invoke("ONVIF: ${profiles.size} profilu")

        // 4. RTSP probe
        updateStatus(ReconStatus.RTSP, "RTSP probe...")
        val mainSdp = rtspClient.describe("rtsp://$targetIp:554/0/av0", username, password)
        val subSdp = rtspClient.describe("rtsp://$targetIp:554/0/av1", username, password)
        progressCallback?.invoke(
            "RTSP main: ${if (mainSdp != null) "OK" else "FAIL"}, " +
            "sub: ${if (subSdp != null) "OK" else "FAIL"}"
        )

        // 5. CGI endpoint probing
        val cgiResults = if (includeCgi) {
            updateStatus(ReconStatus.CGI, "Prohledavam skryte CGI endpointy...")
            settingsFinder.probeEndpoints(targetIp, username, password)
        } else emptyList()

        // 6. CGI command dump
        val commandResults = if (includeCgi) {
            updateStatus(ReconStatus.CGI, "Dump specifickych parametru...")
            settingsFinder.probeCommands(targetIp, username, password)
        } else emptyList()

        progressCallback?.invoke("CGI: ${cgiResults.size} endpointu, ${commandResults.size} prikazu")

        // 7. Cloud detekce
        updateStatus(ReconStatus.CLOUD, "Cloud detekce...")
        val cloudDomains = cloudDetector.detectCloudDomains()
        val cloudPorts = cloudDetector.scanCameraCloudPorts(targetIp)
        progressCallback?.invoke("Cloud: ${cloudDomains.size} domen, ${cloudPorts.size} portu")

        // 8. Traffic capture (pasivne)
        val connections = if (includeSniff) {
            updateStatus(ReconStatus.TRAFFIC, "Ctu aktivni konexe...")
            val tcp = packetCapture.readProcNetTcp()
            val udp = packetCapture.readProcNetUdp()
            tcp + udp
        } else emptyList()

        // 9. Cloud monitoring (kratke, paralelne)
        updateStatus(ReconStatus.CLOUD, "Sleduju odchozi spojeni kamery (10s)...")
        val cloudMonitor = cloudDetector.monitorOutgoing(targetIp, 10)

        updateStatus(ReconStatus.COMPLETE, "Rekognoskace hotova!")

        // Finalizujeme
        val sensitive = settingsFinder.getSensitiveParams(cgiResults + commandResults)

        val cloudEndpoints = mutableListOf<CloudEndpoint>()
        cloudPorts.forEach { (port, service) ->
            cloudEndpoints.add(CloudEndpoint(domain = service, ip = targetIp, port = port))
        }
        cloudDomains.forEach { domain ->
            cloudEndpoints.add(CloudEndpoint(domain = domain))
        }
        cloudMonitor.forEach { ce -> cloudEndpoints.add(ce) }

        val finalCgi = cgiResults + commandResults.map {
            it.copy(endpoint = "[CMD] " + it.endpoint)
        }

        ReconReport(
            camera = cameraInfo,
            ports = ports,
            onvifProfiles = profiles.map { it -> com.eyeplus.recon.OnvifProfile(it.token, it.name, it.encoding, it.resolution, it.rtspUri) },
            cgiResults = finalCgi,
            cloudEndpoints = cloudEndpoints,
            traffic = connections.map {
                TrafficCapture(
                    timestamp = System.currentTimeMillis(),
                    srcIp = it.srcIp,
                    dstIp = it.dstIp,
                    dstPort = it.dstPort,
                    protocol = it.protocol,
                    dnsQuery = null,
                    payload = "${it.state}"
                )
            },
            sensitiveParams = sensitive,
            generatedAt = System.currentTimeMillis()
        )
    }

    private fun updateStatus(status: ReconStatus, message: String) {
        statusCallback?.invoke(status, message)
        Log.d(TAG, "Status: $status - $message")
    }

    fun cancel() {
        scope.cancel()
    }

    suspend fun quickDiscovery(): List<CameraInfo> = networkScanner.discoverByMac()

    fun ptzUp(ip: String, user: String = "admin", pass: String = "admin") = ptzController.moveUp(ip, user, pass)
    fun ptzDown(ip: String, user: String = "admin", pass: String = "admin") = ptzController.moveDown(ip, user, pass)
    fun ptzLeft(ip: String, user: String = "admin", pass: String = "admin") = ptzController.moveLeft(ip, user, pass)
    fun ptzRight(ip: String, user: String = "admin", pass: String = "admin") = ptzController.moveRight(ip, user, pass)
    fun ptzStop(ip: String, user: String = "admin", pass: String = "admin") = ptzController.stop(ip, user, pass)
    fun ptzZoomIn(ip: String, user: String = "admin", pass: String = "admin") = ptzController.zoomIn(ip, user, pass)
    fun ptzZoomOut(ip: String, user: String = "admin", pass: String = "admin") = ptzController.zoomOut(ip, user, pass)
}
