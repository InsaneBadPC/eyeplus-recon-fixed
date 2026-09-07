package com.eyeplus.recon

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class EyePlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stack = sw.toString()
                val msg = "Thread: ${thread.name}\n${Log.getStackTraceString(throwable)}\n---\n$stack"
                // Write to app files dir
                try {
                    File(filesDir, "crash.log").writeText(msg)
                } catch (_: Exception) {}
                // Write to external files dir (no permission needed)
                try {
                    val ext = getExternalFilesDir(null)
                    if (ext != null) {
                        File(ext, "crash.log").writeText(msg)
                    }
                } catch (_: Exception) {}
                // Try Download via MediaStore fallback - direct file (works on many devices with legacy)
                try {
                    val dl = File(Environment.getExternalStorageDirectory(), "Download/eyeplus_crash.txt")
                    dl.parentFile?.mkdirs()
                    dl.writeText(msg)
                } catch (_: Exception) {}
                Log.e("EyePlusCrash", msg)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i("EyePlusApp", "Crash handler installed")
    }
}
