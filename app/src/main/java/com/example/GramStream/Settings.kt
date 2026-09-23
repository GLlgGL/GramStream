package com.example.GramStream

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Settings {
    private const val PREFS_NAME = "GramStream_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Global Search Setting ---
    var globalSearchEnabled: Boolean
        get() = prefs.getBoolean("global_search_enabled", false)
        set(value) = prefs.edit().putBoolean("global_search_enabled", value).apply()

    // --- Wi-Fi Only Setting ---
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit().putBoolean("wifi_only", value).apply()

    // --- Multi-Channel Storage ---
    var chatIds: Set<Long>
        get() {
            val raw = prefs.getStringSet("chat_ids", null)
            if (raw == null) {
                val single = prefs.getLong("chat_id", 0L)
                return if (single != 0L) setOf(single) else emptySet()
            }
            return raw.mapNotNull { it.toLongOrNull() }.toSet()
        }
        set(value) {
            val setStr = value.map { it.toString() }.toSet()
            prefs.edit().putStringSet("chat_ids", setStr).apply()
        }

    fun addChatId(id: Long) {
        if (id == 0L) return
        chatIds = chatIds + id
    }

    fun removeChatId(id: Long) {
        chatIds = chatIds - id
    }

    var chatId: Long
        get() = chatIds.firstOrNull() ?: prefs.getLong("chat_id", 0L)
        set(value) {
            prefs.edit().putLong("chat_id", value).apply()
            if (value != 0L) addChatId(value)
        }

    var tmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "") ?: ""
        set(value) = prefs.edit().putString("tmdb_api_key", value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_on_boot", false)
        set(value) = prefs.edit().putBoolean("auto_start_on_boot", value).apply()

    var publicHost: String
        get() = prefs.getString("public_host", "") ?: ""
        set(value) = prefs.edit().putString("public_host", value).apply()

    var maxCacheMb: Long
        get() = prefs.getLong("max_cache_mb", 2048L)
        set(value) = prefs.edit().putLong("max_cache_mb", value).apply()

    var bufferMb: Int
        get() = prefs.getInt("buffer_mb", 20)
        set(value) = prefs.edit().putInt("buffer_mb", value).apply()

    var port: Int
        get() = prefs.getInt("addon_port", 11470)
        set(value) = prefs.edit().putInt("addon_port", value).apply()

    var apiId: Int
        get() = prefs.getInt("telegram_api_id", 0)
        set(value) = prefs.edit().putInt("telegram_api_id", value).apply()

    var apiHash: String
        get() = prefs.getString("telegram_api_hash", "") ?: ""
        set(value) = prefs.edit().putString("telegram_api_hash", value).apply()

    val hasApiCredentials: Boolean
        get() = apiId != 0 && apiHash.isNotBlank()

    // --- Backup & Restore ---
    fun exportBackupJson(): String {
        val json = JSONObject().apply {
            put("apiId", apiId)
            put("apiHash", apiHash)
            put("tmdbApiKey", tmdbApiKey)
            put("port", port)
            put("maxCacheMb", maxCacheMb)
            put("bufferMb", bufferMb)
            put("wifiOnly", wifiOnly)
            put("globalSearchEnabled", globalSearchEnabled)
            put("autoStartOnBoot", autoStartOnBoot)

            // Export Channel IDs list
            val channelArray = JSONArray()
            chatIds.forEach { channelArray.put(it) }
            put("chatIds", channelArray)
        }
        return json.toString(4)
    }

    fun importBackupJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            if (json.has("apiId")) apiId = json.getInt("apiId")
            if (json.has("apiHash")) apiHash = json.getString("apiHash")
            if (json.has("tmdbApiKey")) tmdbApiKey = json.getString("tmdbApiKey")
            if (json.has("port")) port = json.getInt("port")
            if (json.has("maxCacheMb")) maxCacheMb = json.getLong("maxCacheMb")
            if (json.has("bufferMb")) bufferMb = json.getInt("bufferMb")
            if (json.has("wifiOnly")) wifiOnly = json.getBoolean("wifiOnly")
            if (json.has("globalSearchEnabled")) globalSearchEnabled = json.getBoolean("globalSearchEnabled")
            if (json.has("autoStartOnBoot")) autoStartOnBoot = json.getBoolean("autoStartOnBoot")

            // Restore Channel IDs list
            if (json.has("chatIds")) {
                val array = json.getJSONArray("chatIds")
                val restoredSet = mutableSetOf<Long>()
                for (i in 0 until array.length()) {
                    restoredSet.add(array.getLong(i))
                }
                chatIds = restoredSet
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}