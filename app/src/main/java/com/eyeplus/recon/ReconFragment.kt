package com.eyeplus.recon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.widget.ProgressBar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ReconFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: MaterialButton
    private lateinit var btnAuto: MaterialButton
    private lateinit var etIp: TextInputEditText
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var swCgi: MaterialSwitch
    private lateinit var swSniff: MaterialSwitch

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_recon, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvStatus = view.findViewById(R.id.tvReconStatus)
        tvLog = view.findViewById(R.id.tvReconLog)
        progress = view.findViewById(R.id.progressRecon)
        btnStart = view.findViewById(R.id.btnStartScan)
        btnAuto = view.findViewById(R.id.btnAutoDiscover)
        etIp = view.findViewById(R.id.etIp)
        etUser = view.findViewById(R.id.etUser)
        etPass = view.findViewById(R.id.etPass)
        swCgi = view.findViewById(R.id.swCgi)
        swSniff = view.findViewById(R.id.swSniff)

        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        etIp.setText(prefs.getString("ip", ""))
        etUser.setText(prefs.getString("user", "admin"))
        etPass.setText(prefs.getString("pass", "admin"))

        btnStart.setOnClickListener { startScan(false) }
        btnAuto.setOnClickListener { startScan(true) }
    }

    private fun startScan(autoDiscover: Boolean) {
        val ip = etIp.text?.toString()?.trim().orEmpty()
        val user = etUser.text?.toString()?.trim().orEmpty().ifEmpty { "admin" }
        val pass = etPass.text?.toString()?.trim().orEmpty().ifEmpty { "admin" }

        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        prefs.edit().putString("ip", ip).putString("user", user).putString("pass", pass).apply()

        if (!autoDiscover && ip.isEmpty()) {
            Toast.makeText(requireContext(), "Zadejte IP nebo pouzijte Auto-detect", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = getString(R.string.status_scanning)
        progress.visibility = View.VISIBLE
        tvLog.text = ""

        lifecycleScope.launch {
            val engine = ReconEngine(requireContext().applicationContext)
            engine.statusCallback = { s, msg ->
                requireActivity().runOnUiThread { tvStatus.text = "${s.name}: $msg" }
            }
            engine.progressCallback = { line ->
                requireActivity().runOnUiThread {
                    tvLog.append("> $line\n")
                    // auto-scroll inside NestedScrollView
                    (tvLog.parent as? View)?.post {
                        (tvLog.parent.parent as? View)?.let { p ->
                            // fragment_recon has NestedScrollView -> scroll to bottom
                            if (p is androidx.core.widget.NestedScrollView) {
                                p.post { p.fullScroll(View.FOCUS_DOWN) }
                            }
                        }
                    }
                }
            }
            try {
                val resolvedIp = if (autoDiscover) {
                    val found = engine.quickDiscovery()
                    if (found.isEmpty()) {
                        Toast.makeText(requireContext(), "Kamera nenalezena v siti", Toast.LENGTH_SHORT).show()
                        tvStatus.text = getString(R.string.status_not_found)
                        return@launch
                    }
                    val cam = found.first()
                    etIp.setText(cam.ip)
                    cam.ip
                } else ip

                val report = engine.runFullRecon(
                    targetIp = resolvedIp,
                    username = user,
                    password = pass,
                    includeCgi = swCgi.isChecked,
                    includeSniff = swSniff.isChecked
                )

                ReportStore.lastReport = report
                tvStatus.text = getString(R.string.status_complete)
                Toast.makeText(
                    requireContext(),
                    "Hotovo - ${report.ports.count { it.open }} portu, ${report.cgiResults.size} CGI, ${report.cloudEndpoints.size} cloud endpointu",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                tvStatus.text = "Chyba: ${e.message}"
                Toast.makeText(requireContext(), "Chyba: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}
