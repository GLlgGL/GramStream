package com.example.GramStream

import android.content.Context
import org.json.JSONObject

data class RemoteConfig(
    val movieQueryTemplates: List<String>,
    val titleThreshold: Double,
    val titleThresholdFuzzy: Double,
    val maxStreams: Int
) {
    companion object {
        private var cached: RemoteConfig? = null

        fun load(context: Context): RemoteConfig {
            cached?.let { return it }
            val json = context.assets.open("remote_config.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val templates = mutableListOf<String>()
            val arr = obj.getJSONArray("movie_query_templates")
            for (i in 0 until arr.length()) templates.add(arr.getString(i))

            val config = RemoteConfig(
                movieQueryTemplates = templates,
                titleThreshold = obj.optDouble("title_threshold", 0.7),
                titleThresholdFuzzy = obj.optDouble("title_threshold_fuzzy", 0.6),
                maxStreams = obj.optInt("max_streams", 25)
            )
            cached = config
            return config
        }

        /** Already-loaded config, or a safe default if load() hasn't run yet. */
        fun current(): RemoteConfig = cached ?: RemoteConfig(
            movieQueryTemplates = listOf("{title} {year}", "{title}"),
            titleThreshold = 0.7,
            titleThresholdFuzzy = 0.6,
            maxStreams = 25
        )
    }

    /** Fills a template like "{title} {year}" - blank {year} just leaves a trailing space, trimmed by caller. */
    fun buildQuery(template: String, title: String, year: String?): String {
        return template
            .replace("{title}", title)
            .replace("{year}", year ?: "")
            .trim()
    }
}
