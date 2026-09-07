package com.eyeplus.recon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamFragment : Fragment() {

    private lateinit var etStream0: TextInputEditText
    private lateinit var etStream1: TextInputEditText
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var btnPlay0: MaterialButton
    private lateinit var btnPlay1: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnTest: MaterialButton

    private var player: StreamPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_stream, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etStream0 = view.findViewById(R.id.etStream0)
        etStream1 = view.findViewById(R.id.etStream1)
        surfaceView = view.findViewById(R.id.surfaceView)
        tvStatus = view.findViewById(R.id.tvStreamStatus)
        btnPlay0 = view.findViewById(R.id.btnPlay0)
        btnPlay1 = view.findViewById(R.id.btnPlay1)
        btnStop = view.findViewById(R.id.btnStopStream)
        btnTest = view.findViewById(R.id.btnTestRtsp)

        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        val ip = prefs.getString("ip", "IP") ?: "IP"
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "admin") ?: "admin"

        etStream0.setText("rtsp://$user:$pass@$ip:554/0/av0")
        etStream1.setText("rtsp://$user:$pass@$ip:554/0/av1")

        btnPlay0.setOnClickListener { playStream(etStream0.text?.toString() ?: "") }
        btnPlay1.setOnClickListener { playStream(etStream1.text?.toString() ?: "") }
        btnStop.setOnClickListener { stopStream() }
        btnTest.setOnClickListener { testRtsp() }
    }

    private fun playStream(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "URL je prazdne", Toast.LENGTH_SHORT).show()
            return
        }
        stopStream()
        tvStatus.text = "Pripojuji se..."
        scope.launch {
            try {
                val holder = surfaceView.holder
                val surf = holder.surface
                withContext(Dispatchers.IO) {
                    val p = StreamPlayer()
                    player = p
                    p.playStream(url, surf)
                }
                tvStatus.text = "Prehravam: $url"
            } catch (e: Exception) {
                tvStatus.text = "Chyba: ${e.message}"
                Toast.makeText(requireContext(), "Stream selhal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopStream() {
        try { player?.stop() } catch (_: Exception) {}
        player = null
        tvStatus.text = "Stream neaktivni"
    }

    private fun testRtsp() {
        val url = etStream0.text?.toString() ?: ""
        tvStatus.text = "Testuji RTSP..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val user = requireContext().getSharedPreferences("eyeplus_recon", 0).getString("user", "admin") ?: "admin"
                    val pass = requireContext().getSharedPreferences("eyeplus_recon", 0).getString("pass", "admin") ?: "admin"
                    val client = RTSPClient()
                    client.describe(url, user, pass)
                } catch (e: Exception) { null }
            }
            tvStatus.text = if (result != null) "RTSP: OK\n${result.take(150)}" else "RTSP: selhalo"
        }
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }
}
