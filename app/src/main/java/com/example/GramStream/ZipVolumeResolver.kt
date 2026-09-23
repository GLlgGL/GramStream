package com.example.GramStream

import org.drinkless.tdlib.TdApi
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles the ".zip.001 / .zip.002 / ..." case: several Telegram messages
 * that are really one Store-mode zip archive, split into volumes, holding
 * one video entry. Groups them and works out where the actual video bytes
 * start (skipping the zip local file header).
 */
object ZipVolumeResolver {

    data class VolumeInfo(val fileId: Int, val messageId: Long, val size: Long)
    data class ZipEntryPlan(
        val volumes: List<VolumeInfo>,
        val dataStartOffsetInFirstVolume: Long,
        /** Total video length if known from the header's size field, else the sum of all volume bytes minus the header (best-effort, may include a few trailing zip bytes). */
        val entryLength: Long
    )

    // FIX: resolvePlan() used to be re-run on every single HTTP request for
    // a zip-backed stream - including the several near-simultaneous
    // connections a player normally opens for one file. Each call redid the
    // full handshake (download volume 1 from offset 0, poll up to 20s for
    // the first 64 bytes, parse the zip header) completely independently,
    // so concurrent requests all fought over the same TDLib download and
    // the same 20s clock, with none of them able to finish in time. Now the
    // plan is resolved once per (chatId, base) and reused.
    private val planCache = ConcurrentHashMap<String, ZipEntryPlan>()

    fun invalidatePlanCache() {
        planCache.clear()
        DebugLog.log("ZipVolumeResolver: plan cache invalidated")
    }

    /** e.g. "Frozen II 2019 1080p.zip.002" -> ("frozen ii 2019 1080p.zip", 2) */
    private fun parseVolume(fileName: String): Pair<String, Int>? {
        val m = Regex("""^(.*)\.zip\.(\d{3})$""", RegexOption.IGNORE_CASE).find(fileName) ?: return null
        val base = m.groupValues[1].lowercase() + ".zip"
        val num = m.groupValues[2].toIntOrNull() ?: return null
        return base to num
    }

    private fun fileNameOf(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document.fileName
            is TdApi.MessageVideo -> content.video.fileName
            else -> null
        }
    }

    /**
     * Groups all video/document messages in [messages] that share the same
     * .zip base name, sorted by volume number.
     */
    fun groupVolumes(messages: List<TdApi.Message>): Map<String, List<TdApi.Message>> {
        val groups = mutableMapOf<String, MutableList<Pair<Int, TdApi.Message>>>()
        for (m in messages) {
            val name = fileNameOf(m) ?: continue
            val (base, num) = parseVolume(name) ?: continue
            groups.getOrPut(base) { mutableListOf() }.add(num to m)
        }
        return groups.mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
    }

    /**
     * Cached wrapper around [resolvePlan]. [chatId] + [base] identify the
     * logical movie; [orderedVolumeMessages] is only needed on a cache miss.
     * Returns null (and logs why) instead of resolving if the messages
     * don't actually contain any usable volumes.
     */
    fun resolvePlanCached(chatId: Long, base: String, orderedVolumeMessages: List<TdApi.Message>): ZipEntryPlan? {
        val key = "$chatId:$base"
        planCache[key]?.let {
            DebugLog.log("resolvePlanCached: cache HIT for $key (${it.volumes.size} volumes, entryLength=${it.entryLength})")
            return it
        }

        DebugLog.log("resolvePlanCached: cache MISS for $key, resolving (${orderedVolumeMessages.size} candidate messages)")
        val plan = resolvePlan(orderedVolumeMessages)
        if (plan == null) {
            DebugLog.log("resolvePlanCached: resolvePlan FAILED for $key")
            return null
        }
        planCache[key] = plan
        DebugLog.log("resolvePlanCached: resolved $key -> ${plan.volumes.size} volumes, entryLength=${plan.entryLength}")
        return plan
    }

    /**
     * Blocks briefly to download just the first ~64 bytes of volume 1 -
     * enough to read the zip local file header and find where the actual
     * video data starts, without waiting for the whole volume.
     */
    fun resolvePlan(orderedVolumeMessages: List<TdApi.Message>): ZipEntryPlan? {
        if (orderedVolumeMessages.isEmpty()) {
            DebugLog.log("resolvePlan: no candidate messages given")
            return null
        }

        val volumes = orderedVolumeMessages.mapNotNull { m ->
            val file = CatalogRepository.extractVideoFile(m) ?: return@mapNotNull null
            val size = file.size.takeIf { it > 0 } ?: file.expectedSize.takeIf { it > 0 } ?: return@mapNotNull null
            VolumeInfo(file.id, m.id, size)
        }
        if (volumes.isEmpty()) {
            DebugLog.log("resolvePlan: none of the ${orderedVolumeMessages.size} messages had a usable video file/size")
            return null
        }

        val firstVolume = volumes[0]
        DebugLog.log("resolvePlan: starting header download for fileId=${firstVolume.fileId} (${volumes.size} volumes total)")
        TdLibManager.startDownload(firstVolume.fileId, 0)

        val headerBytes = waitForHeaderBytes(firstVolume.fileId, 64, timeoutMs = 20_000)
        if (headerBytes == null) {
            DebugLog.log("resolvePlan: TIMED OUT waiting for header bytes on fileId=${firstVolume.fileId} - TDLib never delivered the first 64 bytes within 20s")
            return null
        }

        // ZIP local file header: PK\x03\x04 (4) + version(2) + flags(2) +
        // method(2) + time(2) + date(2) + crc32(4) + compSize(4) +
        // uncompSize(4) + nameLen(2) + extraLen(2) = 30 bytes fixed part
        if (headerBytes.size < 30) {
            DebugLog.log("resolvePlan: header too short (${headerBytes.size} bytes)")
            return null
        }
        if (!(headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
                    headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte())
        ) {
            DebugLog.log("resolvePlan: first 4 bytes are not a valid PK\\x03\\x04 local file header signature")
            return null // not a valid local file header - unexpected layout
        }

        fun u16(off: Int) = (headerBytes[off].toInt() and 0xFF) or ((headerBytes[off + 1].toInt() and 0xFF) shl 8)
        fun u32(off: Int): Long {
            var v = 0L
            for (i in 0..3) v = v or ((headerBytes[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        val compressedSizeField = u32(18)
        val nameLen = u16(26)
        val extraLen = u16(28)
        val dataStart = 30L + nameLen + extraLen

        val totalVolumeBytes = volumes.sumOf { it.size }
        val entryLength = if (compressedSizeField > 0) {
            compressedSizeField
        } else {
            // General-purpose bit 3 (data descriptor) was set - size wasn't
            // known when the header was written. Best effort: everything
            // after the header, across all volumes. May include a small
            // trailing data descriptor / central directory tail, which
            // most players tolerate after valid container data.
            totalVolumeBytes - dataStart
        }

        DebugLog.log("resolvePlan: OK dataStart=$dataStart entryLength=$entryLength totalVolumeBytes=$totalVolumeBytes")
        return ZipEntryPlan(volumes, dataStart, entryLength)
    }

    private fun waitForHeaderBytes(fileId: Int, count: Int, timeoutMs: Long): ByteArray? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val file = TdLibManager.getCachedFile(fileId) ?: TdLibManager.getFileBlocking(fileId)
            val local = file?.local
            if (local != null && local.downloadOffset == 0L && local.downloadedPrefixSize >= count && local.path.isNotBlank()) {
                return try {
                    RandomAccessFile(local.path, "r").use { raf ->
                        val buf = ByteArray(count)
                        raf.readFully(buf)
                        buf
                    }
                } catch (e: Exception) {
                    DebugLog.log("waitForHeaderBytes: read failed for fileId=$fileId: ${e.message}")
                    null
                }
            }
            try { Thread.sleep(150) } catch (e: InterruptedException) { return null }
        }
        return null
    }
}