package com.example.GramStream

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.GramStream.databinding.ActivityLoginBinding
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var currentStep = Step.PHONE

    enum class Step { PHONE, CODE, PASSWORD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOrientationForDevice()

        // Force pitch-black top status bar and bottom navigation bar
        window.statusBarColor = Color.parseColor("#0A0A0C")
        window.navigationBarColor = Color.parseColor("#0A0A0C")

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateUiState(Step.PHONE)

        // Display errors returned by TDLib (API_ID_INVALID, PHONE_NUMBER_INVALID, etc.)
        TdLibManager.onError = { errorMessage ->
            runOnUiThread {
                Toast.makeText(this, "Telegram Error: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }

        // Handle TDLib state changes during login
        TdLibManager.onAuthStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    is TdApi.AuthorizationStateWaitCode -> {
                        updateUiState(Step.CODE)
                    }
                    is TdApi.AuthorizationStateWaitPassword -> {
                        updateUiState(Step.PASSWORD)
                    }
                    is TdApi.AuthorizationStateReady -> {
                        onAuthSuccess()
                    }
                    else -> { }
                }
            }
        }

        binding.actionButton.setOnClickListener {
            when (currentStep) {
                Step.PHONE -> {
                    val rawPhone = binding.phoneInput.text.toString().trim()
                    if (rawPhone.isNotBlank()) {
                        // Ensure international format starting with '+'
                        val formattedPhone = if (rawPhone.startsWith("+")) rawPhone else "+$rawPhone"
                        TdLibManager.setPhoneNumber(formattedPhone)
                    } else {
                        Toast.makeText(this, "Enter phone number with country code", Toast.LENGTH_SHORT).show()
                    }
                }
                Step.CODE -> {
                    val code = binding.codeInput.text.toString().trim()
                    if (code.isNotBlank()) {
                        TdLibManager.checkCode(code)
                    } else {
                        Toast.makeText(this, "Enter verification code", Toast.LENGTH_SHORT).show()
                    }
                }
                Step.PASSWORD -> {
                    val pass = binding.passwordInput.text.toString().trim()
                    if (pass.isNotBlank()) {
                        TdLibManager.checkPassword(pass)
                    } else {
                        Toast.makeText(this, "Enter 2FA password", Toast.LENGTH_SHORT).show()
                    }
                }
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

    private fun onAuthSuccess() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun updateUiState(step: Step) {
        currentStep = step
        when (step) {
            Step.PHONE -> {
                binding.titleText.text = "Enter Phone Number"
                binding.phoneInputLayout.visibility = View.VISIBLE
                binding.codeInputLayout.visibility = View.GONE
                binding.passwordInputLayout.visibility = View.GONE
                binding.actionButton.text = "Send Code"
            }
            Step.CODE -> {
                binding.titleText.text = "Enter Code"
                binding.phoneInputLayout.visibility = View.GONE
                binding.codeInputLayout.visibility = View.VISIBLE
                binding.passwordInputLayout.visibility = View.GONE
                binding.actionButton.text = "Verify Code"
            }
            Step.PASSWORD -> {
                binding.titleText.text = "Enter 2FA Password"
                binding.phoneInputLayout.visibility = View.GONE
                binding.codeInputLayout.visibility = View.GONE
                binding.passwordInputLayout.visibility = View.VISIBLE
                binding.actionButton.text = "Submit Password"
            }
        }
    }
}