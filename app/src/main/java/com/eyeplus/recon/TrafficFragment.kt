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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class TrafficFragment : Fragment() {

    private lateinit var rvConnections: RecyclerView
    private lateinit var tvInfo: TextView
    private lateinit var tvTx: TextView
    private lateinit var tvRx: TextView
    private lateinit var tvConnCount: TextView
    private lateinit var tvCloud: TextView
    private lateinit var connAdapter: ConnectionAdapter
    private val capture = PacketCapture()
    private var monitoring = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_traffic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvConnections = view.findViewById(R.id.rvConnections)
        tvInfo = view.findViewById(R.id.tvCloudEndpoints)
        tvTx = view.findViewById(R.id.tvTxBytes)
        tvRx = view.findViewById(R.id.tvRxBytes)
        tvConnCount = view.findViewById(R.id.tvConnectionCount)
        tvCloud = view.findViewById(R.id.tvCloudEndpoints)

        connAdapter = ConnectionAdapter()
        rvConnections.layoutManager = LinearLayoutManager(requireContext())
        rvConnections.adapter = connAdapter

        view.findViewById<Button>(R.id.btnStartTraffic)?.setOnClickListener { startMonitor() }
        view.findViewById<Button>(R.id.btnStopTraffic)?.setOnClickListener { stopMonitor() }

        // Traffic stats
        val cd = CloudDetector(requireContext().applicationContext, OkHttpClient())
        val stats = cd.getTrafficStats()
        tvTx.text = formatBytes(stats["total_tx_B"] ?: 0)
        tvRx.text = formatBytes(stats["total_rx_B"] ?: 0)

        // Cloud endpoints
        val r = ReportStore.lastReport
        if (r != null) {
            tvCloud.text = r.cloudEndpoints.joinToString("\n") {
                "  ${it.domain} ${it.ip}:${it.port} [${it.protocol}]"
            }.ifEmpty { "Zadne cloud endpointy" }
            tvConnCount.text = r.traffic.size.toString()
        }
    }

    private fun startMonitor() {
        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        val ip = prefs.getString("ip", "") ?: ""
        if (ip.isEmpty()) {
            tvInfo.text = "Chybi IP"
            return
        }
        monitoring = true
        tvInfo.text = "Monitoruji port 8001 (vcom-tunnel)..."
        lifecycleScope.launch {
            val recs = capture.monitorPort(ip, 8001, 30)
            connAdapter.submitList(recs)
            tvConnCount.text = recs.size.toString()
            tvInfo.text = "Hotovo - ${recs.size} spojeni"
        }
    }

    private fun stopMonitor() {
        monitoring = false
        capture.stop()
        tvInfo.text = "Zastaveno"
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "${b / (1024 * 1024)} MB"
    }
}

class ConnectionAdapter : androidx.recyclerview.widget.ListAdapter<ConnectionRecord, ConnectionAdapter.VH>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<ConnectionRecord>() {
        override fun areItemsTheSame(oldItem: ConnectionRecord, newItem: ConnectionRecord) =
            oldItem.timestamp == newItem.timestamp
        override fun areContentsTheSame(oldItem: ConnectionRecord, newItem: ConnectionRecord) = oldItem == newItem
    }
) {
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(android.R.id.text1)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply { setPadding(16, 6, 16, 6); textSize = 12f }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.tv.text = "${r.protocol}: ${r.srcIp}:${r.srcPort} -> ${r.dstIp}:${r.dstPort} [${r.state}]"
    }
}
