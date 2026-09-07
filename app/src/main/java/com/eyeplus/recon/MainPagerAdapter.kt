package com.eyeplus.recon

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    companion object {
        val titles = listOf(
            R.string.title_recon,
            R.string.title_ptz,
            R.string.title_stream,
            R.string.title_hidden,
            R.string.title_traffic,
            R.string.title_results
        )
    }

    override fun getItemCount(): Int = titles.size

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ReconFragment()
            1 -> PTZFragment()
            2 -> StreamFragment()
            3 -> HiddenSettingsFragment()
            4 -> TrafficFragment()
            5 -> ResultsFragment()
            else -> ReconFragment()
        }
    }
}
