package com.example.GramStream

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AddonForegroundService : Service() {

    private var server: AddonServer? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Fallback storage enforcement: fires on a timer regardless of playback
    // state, so the cache still gets trimmed even if the user backs out of
    // the player before its own read-based cleanup threshold is hit.
    private val handler = Handler(Looper.getMainLooper())
    private val enforceStorageTick = object : Runnable {
        override fun run() {
            Thread { TdLibManager.applyStrictStorageLimits() }.start()
            handler.postDelayed(this, STORAGE_CHECK_INTERVAL_MS)
        }
    }

    companion object {
        @Volatile var isRunning = false
            private set

        private const val STORAGE_CHECK_INTERVAL_MS = 60_000L
        private const val NETWORK_LOSS_DEBOUNCE_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, AddonForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AddonForegroundService::class.java)
            context.stopService(intent)
        }

        fun isNetworkAllowed(context: Context): Boolean {
            Settings.init(context)
            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
                return if (Settings.wifiOnly) {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                } else {
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                return if (Settings.wifiOnly) {
                    networkInfo.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
                } else {
                    networkInfo.isConnected
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "addon_service_channel")
            .setContentTitle("GramStream Service")
            .setContentText("GramStream Addon Server is running")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        registerNetworkListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Settings.init(applicationContext)

        // Initial check: if network is not allowed based on wifiOnly setting, stop immediately
        if (!isNetworkAllowed(this)) {
            val msg = if (Settings.wifiOnly) "Wi-Fi is disconnected. Stopping server." else "No active network connection. Stopping server."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (server == null) {
            // FIX: this service can be the very first thing to run in a fresh
            // process - e.g. BootReceiver launching it after a reboot with
            // "auto-start on boot" enabled, or Android restarting a
            // START_STICKY service with a null intent after killing the app
            // in the background. In both cases no Activity/LoginActivity ever
            // ran in this process, so TdLibManager.client was still null.
            //
            // Every TdLib call in TdLibManager guards on `client?.send(...)`
            // and silently returns empty/null when client is null - so the
            // addon looked "alive" (server responded) but every catalog and
            // stream request came back empty, with no visible error.
            //
            // TdLibManager.start() itself already no-ops if a client exists,
            // so it's safe to call from every entry point that can bring the
            // service up cold.
            if (Settings.hasApiCredentials) {
                TdLibManager.start(applicationContext)
            } else {
                DebugLog.log("AddonForegroundService: no Telegram API credentials set, TdLib not started")
            }

            val port = Settings.port
            server = AddonServer(port)
            server?.start()
            isRunning = true
            DebugLog.log("AddonServer started on port $port")

            handler.post(enforceStorageTick)
        }
        return START_STICKY
    }

    private fun registerNetworkListener() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Wifi<->cellular handover: local IP changes with the
                // interface. Debounce so we notify after the switch settles.
                handler.postDelayed({
                    NetworkStateNotifier.notifyChanged()
                }, NETWORK_LOSS_DEBOUNCE_MS)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // Debounce: on a Wi-Fi <-> cellular handover, the system briefly
                // has no default network while it switches over. Re-check after
                // a short delay instead of acting on the instantaneous state.
                handler.postDelayed({
                    if (!isNetworkAllowed(applicationContext)) {
                        val msg = if (Settings.wifiOnly) "Wi-Fi lost. Addon server disabled." else "Network lost. Addon server disabled."
                        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                        stopSelf()
                    } else {
                        NetworkStateNotifier.notifyChanged()
                    }
                }, NETWORK_LOSS_DEBOUNCE_MS)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Tracks the system's actual default route, not every network
                // that individually satisfies NET_CAPABILITY_INTERNET. This is
                // what fixes the Wi-Fi->4G false disconnect: onLost now only
                // fires when there's truly no usable default network, not just
                // when the *previous* transport specifically drops.
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (e: Exception) {
            DebugLog.log("Failed to register Network listener: ${e.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(enforceStorageTick)
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Already unregistered
            }
        }
        server?.stop()
        server = null
        isRunning = false
        DebugLog.log("AddonServer stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "addon_service_channel",
                "Addon Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}