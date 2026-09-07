package com.eyeplus.recon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PTZFragment : Fragment() {

    private lateinit var tvIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStop: MaterialButton
    private lateinit var btnPreset1: MaterialButton
    private lateinit var btnPreset2: MaterialButton
    private lateinit var btnPreset3: MaterialButton

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ptz, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvIp = view.findViewById(R.id.tvPtzIp)
        tvStatus = view.findViewById(R.id.tvPtzStatus)
        btnStop = view.findViewById(R.id.btnStop)
        btnPreset1 = view.findViewById(R.id.btnPreset1)
        btnPreset2 = view.findViewById(R.id.btnPreset2)
        btnPreset3 = view.findViewById(R.id.btnPreset3)

        val prefs = requireContext().getSharedPreferences("eyeplus_recon", 0)
        val ip = prefs.getString("ip", "--") ?: "--"
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "admin") ?: "admin"

        tvIp.text = "IP: $ip   |   $user:$pass"

        view.findViewById<FloatingActionButton>(R.id.btnUp).setOnClickListener { cmd("up") }
        view.findViewById<FloatingActionButton>(R.id.btnDown).setOnClickListener { cmd("down") }
        view.findViewById<FloatingActionButton>(R.id.btnLeft).setOnClickListener { cmd("left") }
        view.findViewById<FloatingActionButton>(R.id.btnRight).setOnClickListener { cmd("right") }
        view.findViewById<FloatingActionButton>(R.id.btnZoomIn).setOnClickListener { cmd("zoomin") }
        view.findViewById<FloatingActionButton>(R.id.btnZoomOut).setOnClickListener { cmd("zoomout") }

        btnStop.setOnClickListener { cmd("stop") }
        btnPreset1.setOnClickListener { cmd("preset1") }
        btnPreset2.setOnClickListener { cmd("preset2") }
        btnPreset3.setOnClickListener { cmd("preset3") }
    }

    private fun cmd(name: String) {
        val ip = requireContext().getSharedPreferences("eyeplus_recon", 0).getString("ip", "") ?: ""
        val user = requireContext().getSharedPreferences("eyeplus_recon", 0).getString("user", "admin") ?: "admin"
        val pass = requireContext().getSharedPreferences("eyeplus_recon", 0).getString("pass", "admin") ?: "admin"

        if (ip.isEmpty()) {
            Toast.makeText(requireContext(), "Neni nastavena IP kamery", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Posilam: $name"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val engine = ReconEngine(requireContext().applicationContext)
                when (name) {
                    "up" -> engine.ptzUp(ip, user, pass)
                    "down" -> engine.ptzDown(ip, user, pass)
                    "left" -> engine.ptzLeft(ip, user, pass)
                    "right" -> engine.ptzRight(ip, user, pass)
                    "stop" -> engine.ptzStop(ip, user, pass)
                    "zoomin" -> engine.ptzZoomIn(ip, user, pass)
                    "zoomout" -> engine.ptzZoomOut(ip, user, pass)
                    "preset1" -> { sendCommand(ip, user, pass, 31); true }
                    "preset2" -> { sendCommand(ip, user, pass, 33); true }
                    "preset3" -> { sendCommand(ip, user, pass, 35); true }
                    else -> false
                }
            }
            tvStatus.text = if (ok) "$name: OK" else "$name: SELHALO"
        }
    }

    private fun sendCommand(ip: String, user: String, pass: String, code: Int) {
        try {
            val ptz = PTZController()
            ptz.controlHttp(ip, user, pass, code)
        } catch (_: Exception) {}
    }
}
