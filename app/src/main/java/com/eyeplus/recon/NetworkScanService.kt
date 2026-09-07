package com.eyeplus.recon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class NetworkScanService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d("NetworkScanService", "Service created")
    }
}
