package com.example.GramStream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Settings.init(context)

            if (!Settings.autoStartOnBoot) {
                DebugLog.log("BootReceiver: auto-start disabled, skipping")
                return
            }

            // Start the service; TdLibManager.start() is already called
            // inside AddonForegroundService.onStartCommand() guarded by
            // hasApiCredentials, so no need to duplicate it here.
            val serviceIntent = Intent(context, AddonForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}