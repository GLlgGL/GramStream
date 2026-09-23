package com.example.GramStream

import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.collections.iterator
import kotlin.concurrent.thread

object CatalogRepository {

    private const val DEFAULT_POSTER = "https://v3-cinemeta.strem.io/poster/m/tt0398286.jpg"

    private var cachedCatalogJson: JSONObject? = null
    private var lastCacheTime: Long = 0L
    private const val CACHE_EXPIRATION_MS = 15 * 60 * 1000L
    @Volatile private var isEnriching = false

    fun invalidateCache() {
        cachedCatalogJson = null
        lastCacheTime = 0L
        isEnriching = false
        ZipVolumeResolver.invalidatePlanCache()
        DebugLog.log("Catalog cache invalidated")
    }

    /**
     * Resolves active chat IDs strictly based on Global Search setting.
     * When disabled: returns ONLY manually added channels in Settings.chatIds.
     * When enabled: returns ALL joined Telegram channels.
     */
    fun getActiveChatIds(): Set<Long> {
        return if (Settings.globalSearchEnabled) {
            val joined = TdLibManager.getJoinedChatIdsBlocking()
            if (joined.isNotEmpty()) joined else Settings.chatIds
        } else {
            Settings.chatIds
        }
    }

    fun buildId(chatId: Long, messageId: Long) = "tg_${chatId}_${messageId}"
    fun buildZipId(chatId: Long, base: String) =
        "tgz:${chatId}:${URLEncoder.encode(base, "UTF-8")}"

    fun parseId(id: String): Pair<Long, Long>? {
        val body = id.removePrefix("tg_").removeSuffix(".json")
        val parts = body.split("_")
        if (parts.size != 2) return null
        val chatId = parts[0].toLongOrNull() ?: return null
        val messageId = parts[1].toLongOrNull() ?: return null
        return chatId to messageId
    }

    fun parseZipId(id: String): Pair<Long, String>? {
        val body = id.removeSuffix(".json")
        val parts = body.split(":")
        if (parts.size != 3 || parts[0] != "tgz") return null
        val chatId = parts[1].toLongOrNull() ?: return null
        val base = URLDecoder.decode(parts[2], "UTF-8")
        return chatId to base
    }

    fun isImdbId(id: String): Boolean =
        Regex("""^tt\d+""").containsMatchIn(id.substringBefore(".json").substringBefore(":"))

    fun parseCaption(caption: String): Pair<String, String?> {
        val yearMatch = Regex("(19|20)\\d{2}").find(caption)
        val year = yearMatch?.value
        var title = if (yearMatch != null) caption.substring(0, yearMatch.range.first) else caption
        title = title.replace(Regex("[._]"), " ").trim(' ', '-', '(', '[', ']').trim()
        return title to year
    }

    fun extractCaption(message: TdApi.Message): String? = when (val content = message.content) {
        is TdApi.MessageVideo -> content.caption?.text?.takeIf { it.isNotBlank() } ?: content.video.fileName
        is TdApi.MessageDocument -> content.caption?.text?.takeIf { it.isNotBlank() } ?: content.document.fileName
        else -> null
    }

    fun extractVideoFile(message: TdApi.Message): TdApi.File? = when (val content = message.content) {
        is TdApi.MessageVideo -> content.video.video
        is TdApi.MessageDocument -> content.document.document
        else -> null
    }

    fun buildCatalogJson(chatId: Long, searchQuery: String): JSONObject {
        return buildCatalogJson(getActiveChatIds(), searchQuery)
    }

    fun buildCatalogJson(chatIds: Set<Long>, searchQuery: String): JSONObject {
        val targetChatIds = if (chatIds.isEmpty()) getActiveChatIds() else chatIds
        if (targetChatIds.isEmpty()) return JSONObject().put("metas", JSONArray())

        val currentTime = System.currentTimeMillis()

        if (searchQuery.isBlank() && cachedCatalogJson != null && (currentTime - lastCacheTime < CACHE_EXPIRATION_MS)) {
            DebugLog.log("buildCatalogJson -> Returning CACHED catalog (${cachedCatalogJson?.optJSONArray("metas")?.length()} items)")
            return cachedCatalogJson!!
        }

        val metas = JSONArray()

        for (chatId in targetChatIds) {
            val messages = if (searchQuery.isBlank()) {
                val allMessages = mutableListOf<TdApi.Message>()
                var lastMsgId = 0L

                while (true) {
                    val batch = TdLibManager.getChatHistoryBlocking(chatId, limit = 100, fromMessageId = lastMsgId)
                    if (batch.isEmpty()) break

                    allMessages.addAll(batch)
                    val lowestMsgId = batch.last().id

                    if (lowestMsgId == lastMsgId) break
                    lastMsgId = lowestMsgId
                }
                allMessages.distinctBy { it.id }
            } else {
                TdLibManager.searchVideoMessagesBlocking(chatId, searchQuery)
            }

            DebugLog.log("buildCatalogJson chatId=$chatId query=\"$searchQuery\" -> ${messages.size} messages retrieved")

            val zipGroups = ZipVolumeResolver.groupVolumes(messages)
            val groupedMessageIds = zipGroups.values.flatten().map { it.id }.toSet()

            for ((base, group) in zipGroups) {
                val caption = extractCaption(group.first()) ?: continue
                val (title, year) = parseCaption(caption)
                if (title.isBlank()) continue

                metas.put(JSONObject().apply {
                    put("id", buildZipId(chatId, base))
                    put("type", "movie")
                    put("name", title)
                    put("poster", DEFAULT_POSTER)
                    if (year != null) put("releaseInfo", year)
                })
            }

            for (m in messages) {
                if (m.id in groupedMessageIds) continue
                if (extractVideoFile(m) == null) continue

                val caption = extractCaption(m) ?: continue
                val (title, year) = parseCaption(caption)
                if (title.isBlank()) continue

                metas.put(JSONObject().apply {
                    put("id", buildId(chatId, m.id))
                    put("type", "movie")
                    put("name", title)
                    put("poster", DEFAULT_POSTER)
                    if (year != null) put("releaseInfo", year)
                })
            }
        }

        val resultJson = JSONObject().put("metas", metas)

        if (searchQuery.isBlank() && metas.length() > 0) {
            cachedCatalogJson = resultJson
            lastCacheTime = currentTime
            DebugLog.log("buildCatalogJson -> Updated catalog CACHE with ${metas.length()} items")
            enrichCatalogInBackground(metas)
        }

        return resultJson
    }

    private fun enrichCatalogInBackground(metas: JSONArray) {
        val apiKey = Settings.tmdbApiKey
        if (apiKey.isBlank() || isEnriching) return

        thread {
            isEnriching = true
            DebugLog.log("Starting background TMDB catalog enrichment for ${metas.length()} items...")

            val enrichedMetas = JSONArray()
            for (i in 0 until metas.length()) {
                val item = metas.getJSONObject(i)
                val rawTitle = item.optString("name")
                val year = item.optString("releaseInfo").takeIf { it.isNotBlank() }

                val tmdbData = TmdbClient.searchMovie(rawTitle, year, apiKey)

                val enrichedItem = JSONObject().apply {
                    put("id", item.optString("id"))
                    put("type", "movie")
                    put("name", tmdbData?.title ?: rawTitle)
                    put("poster", tmdbData?.posterUrl ?: DEFAULT_POSTER)
                    if (tmdbData?.backgroundUrl != null) put("background", tmdbData.backgroundUrl)
                    if (tmdbData?.overview != null) put("description", tmdbData.overview)
                    if (tmdbData?.rating != null) put("imdbRating", String.format("%.1f", tmdbData.rating))
                    put("releaseInfo", tmdbData?.year ?: year ?: "")
                }
                enrichedMetas.put(enrichedItem)
            }

            cachedCatalogJson = JSONObject().put("metas", enrichedMetas)
            lastCacheTime = System.currentTimeMillis()
            isEnriching = false
            DebugLog.log("Background TMDB enrichment COMPLETE! Cache updated with full metadata.")
        }
    }

    fun buildMetaJson(idPart: String): JSONObject {
        val parsedZip = parseZipId(idPart)
        val parsedDirect = parseId(idPart)
        val chatId = parsedZip?.first ?: parsedDirect?.first ?: Settings.chatId
        return buildMetaJson(chatId, idPart)
    }

    fun buildMetaJson(chatId: Long, idPart: String): JSONObject {
        val apiKey = Settings.tmdbApiKey
        var rawCaption: String? = null

        parseZipId(idPart)?.let { (cid, base) ->
            val searchKey = base.removeSuffix(".zip")
            val messages = TdLibManager.searchVideoMessagesBlocking(cid, searchKey).ifEmpty {
                TdLibManager.getChatHistoryBlocking(cid, 100)
            }
            val groups = ZipVolumeResolver.groupVolumes(messages)
            val firstMsg = groups[base]?.firstOrNull() ?: groups.values.firstOrNull()?.firstOrNull()
            if (firstMsg != null) rawCaption = extractCaption(firstMsg)
        } ?: parseId(idPart)?.let { (cid, messageId) ->
            val message = TdLibManager.getMessageBlocking(cid, messageId)
            if (message != null) rawCaption = extractCaption(message)
        }

        val (title, year) = if (rawCaption != null) parseCaption(rawCaption!!) else Pair("Telegram Movie", null)
        val tmdbData = if (apiKey.isNotBlank() && title.isNotBlank()) TmdbClient.searchMovie(title, year, apiKey) else null

        val meta = JSONObject().apply {
            put("id", idPart)
            put("type", "movie")
            put("name", tmdbData?.title ?: title)
            put("poster", tmdbData?.posterUrl ?: DEFAULT_POSTER)
            if (tmdbData?.backgroundUrl != null) put("background", tmdbData.backgroundUrl)
            if (tmdbData?.overview != null) put("description", tmdbData.overview)
            if (tmdbData?.rating != null) put("imdbRating", String.format("%.1f", tmdbData.rating))
            put("releaseInfo", tmdbData?.year ?: year ?: "")
        }

        return JSONObject().put("meta", meta)
    }

    fun buildStreamJsonForDirectId(chatId: Long, messageId: Long, hostBase: String): JSONObject {
        val message = TdLibManager.getMessageBlocking(chatId, messageId)
        val streams = JSONArray()
        if (message != null && extractVideoFile(message) != null) {
            val channelTitle = TdLibManager.getChatTitleBlocking(chatId)
            streams.put(JSONObject().apply {
                put("title", channelTitle)
                put("url", "$hostBase/file/${chatId}_${messageId}")
            })
        }
        return JSONObject().put("streams", streams)
    }

    fun buildStreamJsonForZip(chatId: Long, base: String, hostBase: String): JSONObject {
        val searchKey = base.removeSuffix(".zip")
        val messages = TdLibManager.searchVideoMessagesBlocking(chatId, searchKey).ifEmpty {
            TdLibManager.getChatHistoryBlocking(chatId, 100)
        }

        val groups = ZipVolumeResolver.groupVolumes(messages)
        val group = groups[base] ?: groups.values.firstOrNull() ?: return JSONObject().put("streams", JSONArray())
        val streams = JSONArray()
        val channelTitle = TdLibManager.getChatTitleBlocking(chatId)

        streams.put(JSONObject().apply {
            put("title", "$channelTitle")
            put("url", "$hostBase/zipfile/${chatId}/${URLEncoder.encode(base, "UTF-8")}")
        })

        return JSONObject().put("streams", streams)
    }

    fun buildStreamJsonForImdb(chatId: Long, imdbId: String, hostBase: String, config: RemoteConfig): JSONObject {
        return buildStreamJsonForImdb(getActiveChatIds(), imdbId, hostBase, config)
    }

    fun buildStreamJsonForImdb(chatIds: Set<Long>, imdbId: String, hostBase: String, config: RemoteConfig): JSONObject {
        val targetChatIds = if (chatIds.isEmpty()) getActiveChatIds() else chatIds
        if (targetChatIds.isEmpty()) return JSONObject().put("streams", JSONArray())

        val apiKey = Settings.tmdbApiKey
        DebugLog.log("buildStreamJsonForImdb imdbId=$imdbId across ${targetChatIds.size} channels")

        val resolved = TmdbClient.findByImdbId(imdbId, apiKey) ?: return JSONObject().put("streams", JSONArray())
        val streams = JSONArray()

        for (chatId in targetChatIds) {
            val channelTitle = TdLibManager.getChatTitleBlocking(chatId)

            var matches: List<TdApi.Message> = emptyList()
            for (template in config.movieQueryTemplates) {
                val query = config.buildQuery(template, resolved.title, resolved.year)
                val found = TdLibManager.searchVideoMessagesBlocking(chatId, query)
                val filtered = found.filter { m ->
                    val caption = extractCaption(m) ?: return@filter false
                    val (title, _) = parseCaption(caption)
                    val score = Fuzzy.similarity(title, resolved.title)
                    score >= config.titleThresholdFuzzy
                }
                if (filtered.isNotEmpty()) { matches = filtered; break }
            }

            if (matches.isEmpty()) continue

            val zipGroups = ZipVolumeResolver.groupVolumes(matches)
            val groupedIds = zipGroups.values.flatten().map { it.id }.toSet()

            for ((base, _) in zipGroups) {
                streams.put(JSONObject().apply {
                    put("title", "$channelTitle")
                    put("url", "$hostBase/zipfile/${chatId}/${URLEncoder.encode(base, "UTF-8")}")
                })
            }

            for (m in matches) {
                if (m.id in groupedIds) continue
                if (extractVideoFile(m) == null) continue

                streams.put(JSONObject().apply {
                    put("title", "$channelTitle — ${resolved.title}")
                    put("url", "$hostBase/file/${chatId}_${m.id}")
                })
            }
        }

        return JSONObject().put("streams", streams)
    }
}