package com.example.GramStream

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.GramStream.databinding.FragmentSettingsBinding
import org.drinkless.tdlib.TdApi

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(Settings.exportBackupJson().toByteArray())
                }
                Toast.makeText(context, "Backup exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to save backup: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Render Vibe Coded Footer with clickable HTML link
        val footerHtml = "Vibe coded by: <a href=\"https://github.com/GLlgGL\"><b><u>GLlgGL</u></b></a>"
        binding.vibeCodedText.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(footerHtml, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(footerHtml)
        }
        binding.vibeCodedText.movementMethod = LinkMovementMethod.getInstance()

        // Render logs directly inside terminal window
        refreshLogs()

        // Long click on logs window to open full Storage Diagnostics report
        binding.logsScrollView.setOnLongClickListener {
            inspectStorageUsage()
            true
        }

        binding.backupSettingsButton.setOnClickListener {
            exportBackupLauncher.launch("gramstream_backup.json")
        }

        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.logoutButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of your Telegram account?")
                .setPositiveButton("Log Out") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        val logEntries = DebugLog.all().joinToString("\n").ifBlank { "No log entries yet." }
        binding.logsTextView.text = logEntries

        // Auto-scroll log box to the bottom
        binding.logsScrollView.post {
            binding.logsScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyLogsToClipboard() {
        val logEntries = DebugLog.all().joinToString("\n").ifBlank { "No log entries yet." }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GramStream Logs", logEntries))
        Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun performLogout() {
        AddonForegroundService.stop(requireContext())
        TdLibManager.rawClient()?.send(TdApi.LogOut()) { }

        Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        activity?.finish()
    }

    private fun inspectStorageUsage() {
        val ctx = context ?: return
        val filesDir = ctx.filesDir
        val cacheDir = ctx.cacheDir

        val report = StringBuilder("=== STORAGE BREAKDOWN ===\n\n")

        var totalCacheSize = 0L
        cacheDir.walkTopDown().filter { it.isFile }.forEach { totalCacheSize += it.length() }
        report.append("Cache Dir Total: %.2f MB\n\n".format(totalCacheSize / (1024f * 1024f)))

        report.append("Files Over 1 MB in /files:\n")
        val largeFiles = filesDir.walkTopDown().filter { it.isFile && it.length() > 1024 * 1024 }.toList()
        if (largeFiles.isEmpty()) {
            report.append("None\n")
        } else {
            largeFiles.sortedByDescending { it.length() }.forEach { file ->
                val mb = file.length() / (1024f * 1024f)
                report.append("- ${file.name}: %.2f MB\n".format(mb))
            }
        }

        val scroll = ScrollView(requireContext())
        val text = TextView(requireContext()).apply {
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            textSize = 11f
            text = report.toString()
        }
        scroll.addView(text)

        AlertDialog.Builder(requireContext())
            .setTitle("Storage Diagnostics")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}