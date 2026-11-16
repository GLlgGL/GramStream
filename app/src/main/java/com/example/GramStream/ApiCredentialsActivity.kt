package com.example.GramStream

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.GramStream.databinding.ActivityApiCredentialsBinding
import org.drinkless.tdlib.TdApi

class ApiCredentialsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiCredentialsBinding

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val jsonString = contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                    reader.readText()
                }
                if (jsonString != null && Settings.importBackupJson(jsonString)) {
                    if (Settings.apiId != 0) binding.apiIdInput.setText(Settings.apiId.toString())
                    binding.apiHashInput.setText(Settings.apiHash)
                    Toast.makeText(this, "Backup restored!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup file!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Restore error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOrientationForDevice()

        window.statusBarColor = Color.parseColor("#0A0A0C")
        window.navigationBarColor = Color.parseColor("#0A0A0C")

        Settings.init(applicationContext)

        if (Settings.hasApiCredentials) {
            TdLibManager.start(applicationContext)

            TdLibManager.onAuthStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        is TdApi.AuthorizationStateReady -> {
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        else -> { }
                    }
                }
            }
            return
        }

        binding = ActivityApiCredentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Settings.apiId != 0) binding.apiIdInput.setText(Settings.apiId.toString())
        binding.apiHashInput.setText(Settings.apiHash)

        // Setup underlined orange "Restore" text link
        val restoreHtml = "<u>Restore</u>"
        binding.restoreBackupText.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(restoreHtml, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(restoreHtml)
        }

        binding.restoreBackupText.setOnClickListener {
            try {
                importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            } catch (e: ActivityNotFoundException) {
                // Many Android TV boxes ship without a system file picker
                Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_LONG).show()
            }
        }

        binding.continueButton.setOnClickListener {
            val apiId = binding.apiIdInput.text.toString().toIntOrNull() ?: 0
            val apiHash = binding.apiHashInput.text.toString().trim()

            if (apiId != 0 && apiHash.isNotBlank()) {
                Settings.apiId = apiId
                Settings.apiHash = apiHash

                TdLibManager.start(applicationContext)

                TdLibManager.onAuthStateChanged = { state ->
                    runOnUiThread {
                        when (state) {
                            is TdApi.AuthorizationStateReady -> {
                                startActivity(Intent(this, MainActivity::class.java))
                                finish()
                            }
                            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                                startActivity(Intent(this, LoginActivity::class.java))
                                finish()
                            }
                            else -> { }
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Please enter valid API ID and API Hash", Toast.LENGTH_SHORT).show()
            }
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
}