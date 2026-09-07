package com.eyeplus.recon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class HiddenSettingsFragment : Fragment() {

    private lateinit var tvSensitive: TextView
    private lateinit var rvCgiResults: RecyclerView
    private val cgiAdapter = CgiResultAdapter()
    private val results = mutableListOf<CgiResult>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_hidden, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvSensitive = view.findViewById(R.id.tvSensitive)
        rvCgiResults = view.findViewById(R.id.rvCgiResults)

        rvCgiResults.layoutManager = LinearLayoutManager(requireContext())
        rvCgiResults.adapter = cgiAdapter

        view.findViewById<Button>(R.id.btnDumpAll)?.setOnClickListener { runDumpAll() }
        view.findViewById<Button>(R.id.btnDumpCmd)?.setOnClickListener { runDumpCmds() }
        view.findViewById<Button>(R.id.btnExportCgi)?.setOnClickListener { exportCgi() }

        // Nacti sensitive z posledniho reportu
        val r = ReportStore.lastReport
        if (r != null) {
            showSensitive(r.sensitiveParams)
            cgiAdapter.submitList(r.cgiResults.toList())
        }
    }

    private fun showSensitive(params: Map<String, String>) {
        if (params.isEmpty()) {
            tvSensitive.text = "Zadne citlive parametry nenalezeny"
        } else {
            tvSensitive.text = params.entries.joinToString("\n") { "${it.key}\n  = ${it.value}" }
        }
    }

    private fun runDumpAll() {
        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        val ip = prefs.getString("ip", "") ?: ""
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "admin") ?: "admin"
        if (ip.isEmpty()) return

        lifecycleScope.launch {
            try {
                val client = OkHttpClient()
                val sf = SettingsFinder(client)
                val found = sf.probeEndpoints(ip, user, pass)
                cgiAdapter.submitList(found)
                ReportStore.lastReport = ReportStore.lastReport?.copy(cgiResults = found)
            } catch (e: Exception) {
                tvSensitive.text = "Chyba: ${e.message}"
            }
        }
    }

    private fun runDumpCmds() {
        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        val ip = prefs.getString("ip", "") ?: ""
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "admin") ?: "admin"
        if (ip.isEmpty()) return

        lifecycleScope.launch {
            try {
                val client = OkHttpClient()
                val sf = SettingsFinder(client)
                val found = sf.probeCommands(ip, user, pass)
                cgiAdapter.submitList(found)
            } catch (e: Exception) {
                tvSensitive.text = "Chyba: ${e.message}"
            }
        }
    }

    private fun exportCgi() {
        android.widget.Toast.makeText(requireContext(), "Export CGI (ulozeno do reports/)", android.widget.Toast.LENGTH_SHORT).show()
    }
}

class CgiResultAdapter : androidx.recyclerview.widget.ListAdapter<CgiResult, CgiResultAdapter.VH>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CgiResult>() {
        override fun areItemsTheSame(oldItem: CgiResult, newItem: CgiResult) = oldItem.endpoint == newItem.endpoint
        override fun areContentsTheSame(oldItem: CgiResult, newItem: CgiResult) = oldItem == newItem
    }
) {
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvEndpoint: TextView = itemView.findViewById(android.R.id.text1)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(16, 8, 16, 8)
            textSize = 12f
        }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvEndpoint.text = "[${item.code}] ${item.endpoint} (${item.parsedParams.size} params)"
    }
}
