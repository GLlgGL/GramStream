package com.example.GramStream

import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.example.GramStream.R
import com.example.GramStream.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOrientationForDevice()

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#0A0A0C")
        window.navigationBarColor = Color.parseColor("#0A0A0C")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Settings.init(applicationContext)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> loadFragment(HomeFragment())
                R.id.nav_channels -> loadFragment(ChannelsFragment())
                R.id.nav_config -> loadFragment(ConfigFragment())
                R.id.nav_settings -> loadFragment(SettingsFragment())
            }
            true
        }
    }

    private fun setupOrientationForDevice() {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}