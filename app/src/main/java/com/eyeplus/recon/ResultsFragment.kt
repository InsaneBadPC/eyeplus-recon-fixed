package com.eyeplus.recon

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultsFragment : Fragment() {

    private lateinit var tvSummary: TextView
    private var reportText = StringBuilder()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_results, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvSummary = view.findViewById(R.id.tvResultsSummary)

        view.findViewById<Button>(R.id.btnExportJson)?.setOnClickListener { saveReport("json") }
        view.findViewById<Button>(R.id.btnExportMd)?.setOnClickListener { saveReport("md") }
        view.findViewById<Button>(R.id.btnCopyAll)?.setOnClickListener { copyAll() }

        renderReport()
    }

    override fun onResume() {
        super.onResume()
        renderReport()
    }

    private fun renderReport() {
        val r = ReportStore.lastReport
        if (r == null) {
            reportText.clear()
            reportText.append("Zadny report.\nSpust rekognoskaci v zalozce RECON.")
            tvSummary?.text = reportText.toString()
            return
        }
        reportText.clear()
        reportText.append("=== EYEPLUS RECON REPORT ===\n\n")
        reportText.append("IP: ${r.camera.ip}\n")
        reportText.append("MAC: ${r.camera.mac}\n")
        reportText.append("Vendor: ${r.camera.vendor}\n")
        reportText.append("Firmware: ${r.camera.firmware}\n\n")
        reportText.append("-- OTEVENE PORTS (${r.ports.count { it.open }}/${r.ports.size}) --\n")
        r.ports.filter { it.open }.forEach { reportText.append("  ${it.port} ${it.service}\n") }
        reportText.append("\n-- ONVIF PROFILY (${r.onvifProfiles.size}) --\n")
        r.onvifProfiles.forEach { reportText.append("  ${it.name} | ${it.encoding} | ${it.resolution}\n  RTSP: ${it.rtspUri}\n") }
        reportText.append("\n-- CGI ENDPOINTY (${r.cgiResults.size}) --\n")
        r.cgiResults.filter { it.code in 200..299 }.take(30).forEach { reportText.append("  [${it.code}] ${it.endpoint}\n") }
        reportText.append("\n-- CLOUD ENDPOINTS (${r.cloudEndpoints.size}) --\n")
        r.cloudEndpoints.forEach { reportText.append("  ${it.domain} ${it.ip}:${it.port} [${it.protocol}]\n") }
        if (r.sensitiveParams.isNotEmpty()) {
            reportText.append("\n-- CITLIVE PARAMETRY --\n")
            r.sensitiveParams.forEach { (k, v) -> reportText.append("  $k = $v\n") }
        }
        tvSummary?.text = reportText.toString()
    }

    private fun saveReport(type: String) {
        val r = ReportStore.lastReport ?: return
        lifecycleScope.launch {
            try {
                val dir = File(requireContext().filesDir, "reports")
                dir.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val content = if (type == "json") {
                    GsonBuilder().setPrettyPrinting().create().toJson(r)
                } else {
                    buildMarkdown(r)
                }
                val ext = if (type == "json") "json" else "md"
                val f = File(dir, "report_${r.camera.ip}_$ts.$ext")
                f.writeText(content)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Ulozeno: ${f.absolutePath}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Chyba: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyAll() {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("EyePlus Report", reportText.toString())
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(requireContext(), "Zkopirovano", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildMarkdown(r: ReconReport): String = buildString {
        appendLine("# EyePlus Recon Report")
        appendLine("IP: ${r.camera.ip}  MAC: ${r.camera.mac}")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(r.generatedAt))}")
        appendLine()
        appendLine("## Open Ports")
        r.ports.filter { it.open }.forEach { appendLine("- ${it.port} ${it.service}") }
        appendLine()
        appendLine("## ONVIF")
        r.onvifProfiles.forEach { appendLine("- ${it.name} (${it.encoding} ${it.resolution}) -> ${it.rtspUri}") }
        appendLine()
        appendLine("## CGI (2xx)")
        r.cgiResults.filter { it.code in 200..299 }.forEach { appendLine("- [${it.code}] ${it.endpoint}") }
        appendLine()
        appendLine("## Cloud")
        r.cloudEndpoints.forEach { appendLine("- ${it.domain} ${it.ip}:${it.port}") }
        appendLine()
        appendLine("## Sensitive")
        r.sensitiveParams.forEach { appendLine("- ${it.key} = ${it.value}") }
    }
}
