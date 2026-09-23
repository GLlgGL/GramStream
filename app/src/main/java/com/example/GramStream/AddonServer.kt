package com.example.GramStream

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder

class AddonServer(port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", null)
            addCorsHeaders(response)
            return response
        }

        val uri = session.uri
        DebugLog.log("AddonServer: ${session.method} $uri")

        val response = try {
            when {
                uri.endsWith("/manifest.json") -> jsonResponse(manifest())
                uri.contains("/catalog/") -> handleCatalog(session)
                uri.contains("/stream/") -> handleStream(session)
                uri.contains("/meta/") -> handleMeta(session)
                uri.contains("/zipfile/") -> handleZipFile(session)
                uri.contains("/file/") -> handleFile(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            DebugLog.log("AddonServer error serving $uri: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}"
            )
        }

        addCorsHeaders(response)
        return response
    }

    private fun handleMeta(session: IHTTPSession): Response {
        val activeChatIds = CatalogRepository.getActiveChatIds()
        if (activeChatIds.isEmpty()) return jsonResponse(JSONObject().put("meta", JSONObject()))

        val segments = session.uri.trim('/').split("/")
        val idPart = segments.lastOrNull()?.removeSuffix(".json") ?: return notFound()

        return jsonResponse(CatalogRepository.buildMetaJson(idPart))
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
    }

    private fun manifest(baseUrl: String = ""): JSONObject = JSONObject().apply {
        put("id", "com.example.GramStream")
        put("version", "0.2.0")
        put("name", "GramStream")
        put("description", "Streams your Telegram channels directly from your device\n\nVibe coded by: GLlgGL")

        // Dynamic logo URL served locally or hosted on GitHub
        val logoUrl = if (baseUrl.isNotBlank()) {
            "$baseUrl/logo.png"
        } else {
            "https://emojiapi.dev/api/v1/diya_lamp/256.png"
        }
        put("logo", logoUrl)

        put("resources", JSONArray(listOf("catalog", "stream", "meta")))
        put("types", JSONArray(listOf("movie")))
        put("catalogs", JSONArray().put(JSONObject().apply {
            put("type", "movie")
            put("id", "latest")
            put("name", "Telegram")
            put("extra", JSONArray(listOf(
                JSONObject().apply {
                    put("name", "search")
                    put("isRequired", false)
                },
                JSONObject().apply {
                    put("name", "skip")
                    put("isRequired", false)
                }
            )))
        }))
        put("idPrefixes", JSONArray(listOf("tg_", "tgz:", "tt")))
    }

    private fun handleCatalog(session: IHTTPSession): Response {
        val activeChatIds = CatalogRepository.getActiveChatIds()
        if (activeChatIds.isEmpty()) return jsonResponse(JSONObject().put("metas", JSONArray()))

        val segments = session.uri.trim('/').split("/")
        var searchQuery = ""
        val lastSegment = segments.lastOrNull() ?: ""

        if (lastSegment.contains("=")) {
            val decoded = URLDecoder.decode(lastSegment.removeSuffix(".json"), "UTF-8")
            if (decoded.startsWith("search=")) {
                searchQuery = decoded.substringAfter("search=", "")
            }
        }
        if (searchQuery.isBlank()) {
            searchQuery = session.parameters["search"]?.firstOrNull() ?: ""
        }

        return jsonResponse(CatalogRepository.buildCatalogJson(activeChatIds, searchQuery))
    }

    private fun handleStream(session: IHTTPSession): Response {
        val activeChatIds = CatalogRepository.getActiveChatIds()
        if (activeChatIds.isEmpty()) return jsonResponse(JSONObject().put("streams", JSONArray()))

        val segments = session.uri.trim('/').split("/")
        val idPart = segments.lastOrNull() ?: return emptyStreams()
        val host = session.headers["host"] ?: "127.0.0.1:11470"

        val scheme = if (session.headers["x-forwarded-proto"] == "https") "https" else "http"
        val hostBase = "$scheme://$host"

        CatalogRepository.parseZipId(idPart)?.let { (cid, base) ->
            return jsonResponse(CatalogRepository.buildStreamJsonForZip(cid, base, hostBase))
        }
        CatalogRepository.parseId(idPart)?.let { (cid, messageId) ->
            return jsonResponse(CatalogRepository.buildStreamJsonForDirectId(cid, messageId, hostBase))
        }
        if (CatalogRepository.isImdbId(idPart)) {
            val imdbId = idPart.substringBefore(".json")
            return jsonResponse(
                CatalogRepository.buildStreamJsonForImdb(activeChatIds, imdbId, hostBase, RemoteConfig.current())
            )
        }
        return emptyStreams()
    }

    private fun handleFile(session: IHTTPSession): Response {
        val segments = session.uri.trim('/').split("/")
        val idPart = segments.lastOrNull() ?: return notFound()
        val parts = idPart.split("_")
        if (parts.size != 2) return notFound()
        val chatId = parts[0].toLongOrNull() ?: return notFound()
        val messageId = parts[1].toLongOrNull() ?: return notFound()

        val message = TdLibManager.getMessageBlocking(chatId, messageId) ?: return notFound()
        val videoFile = CatalogRepository.extractVideoFile(message) ?: return notFound()

        val totalSize = videoFile.size.takeIf { it > 0 }
            ?: videoFile.expectedSize.takeIf { it > 0 }
            ?: return notFound()

        val (start, end, status) = parseRange(session, totalSize)
        val length = end - start + 1

        TdLibManager.startDownload(videoFile.id, start)
        val stream = ProgressiveFileInputStream(videoFile.id, start, end)
        return rangeResponse(stream, length, status, start, end, totalSize)
    }

    private fun handleZipFile(session: IHTTPSession): Response {
        val segments = session.uri.trim('/').split("/")
        val idx = segments.indexOf("zipfile")
        if (idx == -1 || segments.size < idx + 3) {
            DebugLog.log("handleZipFile: malformed uri ${session.uri}")
            return notFound()
        }
        val chatId = segments[idx + 1].toLongOrNull() ?: return notFound()
        val base = URLDecoder.decode(segments[idx + 2], "UTF-8")
        DebugLog.log("handleZipFile: chatId=$chatId base=\"$base\" range=${session.headers["range"]}")

        val searchKey = base.removeSuffix(".zip")
        val messages = TdLibManager.searchVideoMessagesBlocking(chatId, searchKey).ifEmpty {
            TdLibManager.getChatHistoryBlocking(chatId, 100)
        }

        val groups = ZipVolumeResolver.groupVolumes(messages)
        val group = groups[base] ?: return notFound()

        val plan = ZipVolumeResolver.resolvePlanCached(chatId, base, group) ?: return notFound()
        val totalSize = plan.entryLength

        val (start, end, status) = parseRange(session, totalSize)
        val length = end - start + 1

        val firstSegmentVolumeOffset = if (start == 0L) plan.dataStartOffsetInFirstVolume else null
        if (firstSegmentVolumeOffset != null) {
            TdLibManager.startDownload(plan.volumes[0].fileId, firstSegmentVolumeOffset)
        }

        val stream = MultiVolumeProgressiveInputStream(plan, start, end)
        return rangeResponse(stream, length, status, start, end, totalSize)
    }

    private fun parseRange(session: IHTTPSession, totalSize: Long): Triple<Long, Long, Response.Status> {
        val rangeHeader = session.headers["range"]
        return if (rangeHeader == null) {
            Triple(0L, totalSize - 1, Response.Status.OK)
        } else {
            val range = rangeHeader.removePrefix("bytes=").split("-")
            val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = range.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: (totalSize - 1)
            Triple(start, end, Response.Status.PARTIAL_CONTENT)
        }
    }

    private fun rangeResponse(
        stream: InputStream, length: Long, status: Response.Status,
        start: Long, end: Long, totalSize: Long
    ): Response {
        val resp = newFixedLengthResponse(status, "video/x-matroska", stream, length)
        resp.addHeader("Accept-Ranges", "bytes")
        if (status == Response.Status.PARTIAL_CONTENT) {
            resp.addHeader("Content-Range", "bytes $start-$end/$totalSize")
        }
        addCorsHeaders(resp)
        return resp
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    private fun emptyStreams() = jsonResponse(JSONObject().put("streams", JSONArray()))

    private fun jsonResponse(json: JSONObject): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        resp.addHeader("Pragma", "no-cache")
        resp.addHeader("Expires", "0")
        return resp
    }
}