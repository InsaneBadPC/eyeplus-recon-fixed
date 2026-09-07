package com.eyeplus.recon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var tabs: TabLayout
    private lateinit var pagerAdapter: MainPagerAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Neco chybi: $denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        tabs = findViewById(R.id.tabs)

        pagerAdapter = MainPagerAdapter(this)
        pager.adapter = pagerAdapter

        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = getString(MainPagerAdapter.titles[position])
        }.attach()

        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>().apply {
            // INTERNET / NETWORK_STATE / WIFI_STATE are normal permissions - no runtime request
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}
