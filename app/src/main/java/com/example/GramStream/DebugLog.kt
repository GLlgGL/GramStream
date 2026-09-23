package com.example.GramStream

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {
    private const val MAX_LOG_ENTRIES = 500
    private val logs = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"

        logs.add(entry)

        // Trim oldest logs when queue exceeds MAX_LOG_ENTRIES
        while (logs.size > MAX_LOG_ENTRIES) {
            logs.poll()
        }
    }

    fun all(): List<String> = logs.toList()

    fun clear() {
        logs.clear()
    }
}