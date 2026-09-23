package com.example.GramStream

import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.GramStream.databinding.FragmentHomeBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.net.NetworkInterface
import java.util.Collections
import kotlin.collections.get

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.serverToggleSwitch.isChecked = AddonForegroundService.isRunning
        updateServerUI(AddonForegroundService.isRunning)

        binding.serverToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!AddonForegroundService.isNetworkAllowed(requireContext())) {
                    binding.serverToggleSwitch.isChecked = false
                    val msg = if (Settings.wifiOnly) "Wi-Fi is not connected!" else "No active network connection!"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    updateServerUI(false)
                    return@setOnCheckedChangeListener
                }
                AddonForegroundService.start(requireContext())
                Toast.makeText(context, "Status: Started", Toast.LENGTH_SHORT).show()
            } else {
                AddonForegroundService.stop(requireContext())
                Toast.makeText(context, "Status: Stopped", Toast.LENGTH_SHORT).show()
            }
            updateServerUI(isChecked)
        }

        updateBatteryButtonStatus()

        binding.batteryOptButton.setOnClickListener {
            if (isTvDevice()) {
                Toast.makeText(context, "Background execution is allowed by default on TV!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = requireContext().packageName

                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent().apply {
                        action = AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    }
                } else {
                    Toast.makeText(context, "Background execution is already allowed!", Toast.LENGTH_SHORT).show()
                }
            }
            updateBatteryButtonStatus()
        }

        binding.copyLinkButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GramStream Addon Link", binding.manifestLinkText.text))
            Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
        }

        binding.clearCacheButton.setOnClickListener {
            CatalogRepository.invalidateCache()
            TdLibManager.optimizeStorage()
            Toast.makeText(context, "Catalog cache cleared!", Toast.LENGTH_SHORT).show()
        }

        // Cloudflare Tunnel Controls
        updateTunnelUI()

        binding.tunnelButton.setOnClickListener {
            if (CloudflaredTunnel.isRunning()) {
                CloudflaredTunnel.stop()
                Settings.publicHost = ""
                updateTunnelUI()
                Toast.makeText(context, "Cloudflare Tunnel stopped", Toast.LENGTH_SHORT).show()
            } else {
                binding.tunnelStatusText.text = "Starting Cloudflare tunnel..."
                CloudflaredTunnel.onUrlFound = { url: String? ->
                    activity?.runOnUiThread {
                        if (url != null) {
                            val host = url.removePrefix("https://").removePrefix("http://")
                            Settings.publicHost = host
                            updateTunnelUI()
                        }
                    }
                }
                CloudflaredTunnel.start(requireContext(), Settings.port)
            }
        }

        loadUserInfo()
        refreshManifestLink()

        // Re-render the manifest link/QR when the default network changes
        // (Wi-Fi <-> cellular handover), since the LAN IP shown depends on
        // the active interface. drop(1) skips the flow's initial value so
        // this doesn't double-fire alongside the refreshManifestLink() call
        // above.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NetworkStateNotifier.changeCounter.drop(1).collect {
                    refreshManifestLink()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryButtonStatus()
        updateTunnelUI()
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = context?.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun loadUserInfo() {
        TdLibManager.rawClient()?.send(TdApi.GetMe()) { obj ->
            if (obj is TdApi.User) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    val name = when {
                        obj.usernames != null && obj.usernames!!.activeUsernames.isNotEmpty() -> "@${obj.usernames!!.activeUsernames[0]}"
                        obj.firstName.isNotBlank() -> "${obj.firstName} ${obj.lastName}".trim()
                        else -> "Telegram User"
                    }
                    binding.userNameText.text = name
                    binding.userIdText.text = "ID: ${obj.id}"
                }
            }
        }
    }

    private fun updateBatteryButtonStatus() {
        val strokePx = (1 * resources.displayMetrics.density).toInt()
        binding.batteryOptButton.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        binding.batteryOptButton.strokeWidth = strokePx

        if (isTvDevice()) {
            binding.batteryOptButton.text = "Background Execution Allowed (TV)"
            binding.batteryOptButton.strokeColor = ColorStateList.valueOf(Color.parseColor("#388E3C")) // GREEN
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(requireContext().packageName)

            if (isIgnoring) {
                binding.batteryOptButton.text = "Background Execution Allowed"
                binding.batteryOptButton.strokeColor = ColorStateList.valueOf(Color.parseColor("#388E3C")) // GREEN
            } else {
                binding.batteryOptButton.text = "Allow Running in Background"
                binding.batteryOptButton.strokeColor = ColorStateList.valueOf(Color.parseColor("#D32F2F")) // RED
            }
        }
    }

    private fun updateServerUI(isRunning: Boolean) {
        if (isRunning) {
            binding.serverStatusText.text = "Status:"
            binding.serverStatusIndicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")) // GREEN
            binding.serverStatusCard.visibility = View.VISIBLE
        } else {
            binding.serverStatusText.text = "Status:"
            binding.serverStatusIndicator.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F")) // RED
            binding.serverStatusCard.visibility = View.GONE
        }
    }

    private fun updateTunnelUI() {
        if (CloudflaredTunnel.isRunning() && Settings.publicHost.isNotBlank()) {
            binding.tunnelStatusText.text = "https://${Settings.publicHost}"
            binding.tunnelButton.text = "Stop Cloudflare Tunnel"
            binding.tunnelButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
        } else {
            binding.tunnelStatusText.text = "Tunnel not running"
            binding.tunnelButton.text = "Start Cloudflare Tunnel"
            binding.tunnelButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        }
        refreshManifestLink()
    }

    private fun refreshManifestLink() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val host = Settings.publicHost.ifBlank { null }
            val manifestUrl = if (host != null) {
                "https://$host/manifest.json"
            } else {
                val ip = getLanAddress()
                "http://$ip:${Settings.port}/manifest.json"
            }

            val qrBitmap = generateQrCodeBitmap(manifestUrl)

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    binding.manifestLinkText.text = manifestUrl
                    if (qrBitmap != null) {
                        binding.qrCodeImage.setImageBitmap(qrBitmap)
                    }
                }
            }
        }
    }

    private fun generateQrCodeBitmap(text: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 300, 300)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            DebugLog.log("QR Code error: ${e.message}")
            null
        }
    }

    private fun getLanAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress!!
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}