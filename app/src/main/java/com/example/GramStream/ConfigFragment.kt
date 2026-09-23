package com.example.GramStream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.GramStream.databinding.FragmentConfigBinding

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Global Search Switch
        binding.globalSearchSwitch.isChecked = Settings.globalSearchEnabled
        binding.globalSearchSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            Settings.globalSearchEnabled = isChecked
            CatalogRepository.invalidateCache()
            val msg = if (isChecked) {
                "Global Search enabled (all joined channels)"
            } else {
                "Global Search disabled (only added channels)"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // Wi-Fi Only Mode Switch
        binding.wifiOnlySwitch.isChecked = Settings.wifiOnly
        binding.wifiOnlySwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            Settings.wifiOnly = isChecked
            val msg = if (isChecked) {
                "Wi-Fi Only Mode enabled"
            } else {
                "Wi-Fi Only Mode disabled (Mobile Data allowed)"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // Auto Start on Boot Switch
        binding.autoStartSwitch.isChecked = Settings.autoStartOnBoot
        binding.autoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            Settings.autoStartOnBoot = isChecked
            Toast.makeText(context, "Auto-start on boot ${if (isChecked) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.cacheInput.setText(Settings.maxCacheMb.toString())
        binding.bufferInput.setText(Settings.bufferMb.toString())
        binding.portInput.setText(Settings.port.toString())
        binding.tmdbKeyInput.setText(Settings.tmdbApiKey)

        binding.saveStreamingSettingsButton.setOnClickListener {
            val cacheMb = binding.cacheInput.text.toString().toLongOrNull() ?: 2048L
            val bufferMb = binding.bufferInput.text.toString().toIntOrNull() ?: 20
            val port = binding.portInput.text.toString().toIntOrNull() ?: 11470
            val tmdbKey = binding.tmdbKeyInput.text.toString().trim()

            Settings.maxCacheMb = cacheMb
            Settings.bufferMb = bufferMb
            Settings.port = port
            Settings.tmdbApiKey = tmdbKey

            TdLibManager.applyStrictStorageLimits()
            CatalogRepository.invalidateCache()
            Toast.makeText(context, "Config saved & storage trimmed to ${cacheMb} MB!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}