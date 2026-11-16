package com.example.GramStream

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object CloudflaredTunnel {

    private var process: Process? = null
    var publicUrl: String? = null
        private set
    var onUrlFound: ((String?) -> Unit)? = null

    private val urlRegex = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")

    // Process.isAlive() is API 26+, but minSdk is 24 (Android 7 TV boxes).
    // exitValue() throws while the process is still running.
    fun isRunning(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    fun start(context: Context, localPort: Int) {
        if (isRunning()) return

        val execPath = context.applicationInfo.nativeLibraryDir + "/libcloudflared.so"
        val execFile = File(execPath)

        if (!execFile.exists()) {
            DebugLog.log("Cloudflare tunnel: binary missing at $execPath")
            onUrlFound?.invoke(null)
            return
        }

        try {
            val pb = ProcessBuilder(
                execPath,
                "tunnel",
                "--edge-ip-version", "4",
                "--protocol", "http2",
                "--url", "http://127.0.0.1:$localPort"
            )
            pb.redirectErrorStream(true)
            pb.environment()["GODEBUG"] = "netdns=cgo"

            val proc = pb.start()
            process = proc
            DebugLog.log("Cloudflare tunnel process started successfully")

            Thread {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        DebugLog.log("cloudflared: $l")
                        val match = urlRegex.find(l)
                        if (match != null && publicUrl == null) {
                            val foundUrl = match.value
                            if (!foundUrl.contains("api.trycloudflare.com") &&
                                !foundUrl.contains("update.trycloudflare.com")
                            ) {
                                publicUrl = foundUrl
                                DebugLog.log("Cloudflare tunnel URL: $publicUrl")
                                onUrlFound?.invoke(publicUrl)
                            }
                        }
                    }
                } catch (e: IOException) {
                    DebugLog.log("cloudflared reader closed: ${e.message}")
                } finally {
                    if (publicUrl != null) {
                        publicUrl = null
                        onUrlFound?.invoke(null)
                    }
                }
            }.start()

        } catch (e: Exception) {
            DebugLog.log("Cloudflare tunnel failed to start: ${e.message}")
            onUrlFound?.invoke(null)
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        publicUrl = null
    }
}