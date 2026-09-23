package com.example.GramStream

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TmdbClient {

    data class TmdbSearchResult(
        val title: String,
        val year: String?,
        val posterUrl: String?,
        val backgroundUrl: String?,
        val overview: String?,
        val rating: Double?,
        val genres: List<String>,
        val cast: List<String>
    )

    private const val POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w1280"

    private fun getJson(urlStr: String): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    fun findByImdbId(imdbId: String, apiKey: String): TmdbSearchResult? {
        if (apiKey.isBlank()) return null
        val url = "https://api.themoviedb.org/3/find/$imdbId" +
                "?external_source=imdb_id&api_key=$apiKey"
        val json = getJson(url) ?: return null

        val movieResults = json.optJSONArray("movie_results")
        if (movieResults != null && movieResults.length() > 0) {
            return parseMovieObject(movieResults.getJSONObject(0))
        }

        val tvResults = json.optJSONArray("tv_results")
        if (tvResults != null && tvResults.length() > 0) {
            return parseTvObject(tvResults.getJSONObject(0))
        }

        return null
    }

    fun searchMovie(title: String, year: String?, apiKey: String): TmdbSearchResult? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val yearParam = if (!year.isNullOrBlank()) "&year=$year" else ""
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$encodedTitle$yearParam"

            val json = getJson(url) ?: return null
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val movie = results.getJSONObject(0)
            parseMovieObject(movie)
        } catch (e: Exception) {
            DebugLog.log("TmdbClient.searchMovie error: ${e.message}")
            null
        }
    }

    private fun parseMovieObject(movie: JSONObject): TmdbSearchResult {
        val title = movie.optString("title").ifBlank { movie.optString("original_title") }
        val releaseDate = movie.optString("release_date")
        val parsedYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null

        val posterPath = movie.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val backdropPath = movie.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
        val overview = movie.optString("overview").takeIf { it.isNotBlank() && it != "null" }

        val voteAverage = movie.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

        return TmdbSearchResult(
            title = title,
            year = parsedYear,
            posterUrl = posterPath?.let { "$POSTER_BASE_URL$it" },
            backgroundUrl = backdropPath?.let { "$BACKDROP_BASE_URL$it" },
            overview = overview,
            rating = voteAverage,
            genres = emptyList(), // Can be enriched via TMDB details endpoint if needed
            cast = emptyList()
        )
    }

    private fun parseTvObject(tv: JSONObject): TmdbSearchResult {
        val title = tv.optString("name").ifBlank { tv.optString("original_name") }
        val airDate = tv.optString("first_air_date")
        val parsedYear = if (airDate.length >= 4) airDate.substring(0, 4) else null

        val posterPath = tv.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val backdropPath = tv.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
        val overview = tv.optString("overview").takeIf { it.isNotBlank() && it != "null" }
        val voteAverage = tv.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

        return TmdbSearchResult(
            title = title,
            year = parsedYear,
            posterUrl = posterPath?.let { "$POSTER_BASE_URL$it" },
            backgroundUrl = backdropPath?.let { "$BACKDROP_BASE_URL$it" },
            overview = overview,
            rating = voteAverage,
            genres = emptyList(),
            cast = emptyList()
        )
    }
}