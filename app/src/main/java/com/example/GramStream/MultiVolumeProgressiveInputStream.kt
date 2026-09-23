package com.example.GramStream

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Streams a video that spans multiple Telegram-hosted zip volumes as one
 * continuous byte range, [startPosition, endPositionInclusive] in
 * "entry space" (0 = first byte of video data, after the zip header).
 *
 * This is what the earlier stall bug was almost certainly missing: instead
 * of only starting to download volume 2 once playback reaches the exact
 * boundary (a cold start with nothing buffered), this proactively calls
 * TdLibManager.startDownload() for the *next* volume once the reader gets
 * within [prefetchThresholdBytes] of the current volume's end - so by the
 * time playback actually crosses the seam, part of volume 2 is often
 * already sitting on disk.
 */
class MultiVolumeProgressiveInputStream(
    private val plan: ZipVolumeResolver.ZipEntryPlan,
    startPosition: Long,
    private val endPositionInclusive: Long
) : InputStream() {

    // Each segment covers [entryStart, entryEnd) in entry-space, backed by
    // one volume. Segment 0 starts after the zip header; later segments
    // start at volume-local offset 0.
    private data class Segment(
        val entryStart: Long,
        val entryEnd: Long, // exclusive
        val fileId: Int,
        val volumeBaseOffset: Long // volume-local offset corresponding to entryStart
    )

    private val segments: List<Segment> = buildSegments()
    private var position = startPosition
    private var currentSegmentIndex = -1
    private var raf: RandomAccessFile? = null

    private val prefetchThresholdBytes = 8L * 1024 * 1024 // start next volume 8MB before the seam
    private val pollIntervalMs = 200L
    private val maxWaitMs = 60_000L
    private val heartbeatEveryMs = 5_000L

    init {
        DebugLog.log(
            "MultiVolumeProgressiveInputStream: opened, ${segments.size} segment(s), " +
                    "range=[$startPosition,$endPositionInclusive]"
        )
    }

    private fun buildSegments(): List<Segment> {
        val list = mutableListOf<Segment>()
        var entryPos = 0L
        plan.volumes.forEachIndexed { idx, vol ->
            val volumeBase = if (idx == 0) plan.dataStartOffsetInFirstVolume else 0L
            val availableInVolume = vol.size - volumeBase
            val entryEnd = entryPos + availableInVolume
            list.add(Segment(entryPos, entryEnd, vol.fileId, volumeBase))
            entryPos = entryEnd
        }
        return list
    }

    private fun segmentFor(entryPos: Long): Segment? =
        segments.firstOrNull { entryPos >= it.entryStart && entryPos < it.entryEnd }

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n <= 0) -1 else (buf[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position > endPositionInclusive) return -1

        val segment = segmentFor(position)
        if (segment == null) {
            DebugLog.log("MultiVolumeProgressiveInputStream: no segment covers position=$position (out of ${segments.size} segments) - EOF")
            return -1
        }
        val segIndex = segments.indexOf(segment)

        if (segIndex != currentSegmentIndex) {
            raf?.close()
            raf = null
            currentSegmentIndex = segIndex
            DebugLog.log("MultiVolumeProgressiveInputStream: switched to segment $segIndex (fileId=${segment.fileId}) at position=$position")
        }

        // Proactively kick off the *next* volume once we're close to this
        // segment's end - the actual fix for the boundary stall.
        maybePrefetchNext(segment, segIndex)

        val volumeOffset = segment.volumeBaseOffset + (position - segment.entryStart)
        val available = waitForBytesAvailable(segment.fileId, volumeOffset)
        if (available == null) {
            DebugLog.log("MultiVolumeProgressiveInputStream: TIMED OUT after ${maxWaitMs}ms waiting for fileId=${segment.fileId} at volumeOffset=$volumeOffset - giving up (EOF)")
            return -1
        }
        if (available <= volumeOffset) return -1

        val path = TdLibManager.getCachedFile(segment.fileId)?.local?.path
        if (path.isNullOrBlank()) {
            DebugLog.log("MultiVolumeProgressiveInputStream: fileId=${segment.fileId} has no local path even though bytes are reported available - EOF")
            return -1
        }
        val file = raf ?: try {
            RandomAccessFile(path, "r").also { raf = it }
        } catch (e: IOException) {
            DebugLog.log("MultiVolumeProgressiveInputStream: failed to open local file \"$path\": ${e.message}")
            return -1
        }

        val maxFromVolume = available - volumeOffset
        val maxFromEntryEnd = segment.entryEnd - position
        val maxFromRequestedEnd = endPositionInclusive - position + 1
        val wantLen = minOf(len.toLong(), maxFromVolume, maxFromEntryEnd, maxFromRequestedEnd)
            .toInt().coerceAtLeast(0)
        if (wantLen == 0) return -1

        file.seek(volumeOffset)
        val readCount = file.read(b, off, wantLen)
        if (readCount > 0) position += readCount
        return readCount
    }

    private fun maybePrefetchNext(segment: Segment, segIndex: Int) {
        val nextIndex = segIndex + 1
        if (nextIndex >= segments.size) return
        val remainingInSegment = segment.entryEnd - position
        if (remainingInSegment <= prefetchThresholdBytes) {
            val nextSegment = segments[nextIndex]
            TdLibManager.startDownload(nextSegment.fileId, nextSegment.volumeBaseOffset)
        }
    }

    // FIX: this loop used to poll silently for up to 60s and return null
    // with no trace of what happened. Now it logs the very first download
    // kick-off, a heartbeat every 5s showing TDLib's reported download
    // state (or that there's no TdApi.File at all yet), and the final
    // outcome - so a stalled/failed download is visible instead of just
    // "stream doesn't play" with an empty log.
    private fun waitForBytesAvailable(fileId: Int, volumeOffset: Long): Long? {
        val start = System.currentTimeMillis()
        var lastRequested = -1L
        var lastHeartbeat = 0L

        while (true) {
            val file = TdLibManager.getCachedFile(fileId) ?: TdLibManager.getFileBlocking(fileId)
            val local = file?.local
            val elapsed = System.currentTimeMillis() - start

            if (local != null) {
                if (local.isDownloadingCompleted) return Long.MAX_VALUE
                val downloadedUpTo = local.downloadOffset + local.downloadedPrefixSize
                if (local.downloadOffset <= volumeOffset && volumeOffset < downloadedUpTo) {
                    return downloadedUpTo
                }
                if (lastRequested != volumeOffset) {
                    DebugLog.log("MultiVolumeProgressiveInputStream: requesting download fileId=$fileId from volumeOffset=$volumeOffset (was downloadOffset=${local.downloadOffset}, downloadedPrefixSize=${local.downloadedPrefixSize})")
                    TdLibManager.startDownload(fileId, volumeOffset)
                    lastRequested = volumeOffset
                }
            } else if (lastRequested != volumeOffset) {
                DebugLog.log("MultiVolumeProgressiveInputStream: no TdApi.File yet for fileId=$fileId, requesting download from volumeOffset=$volumeOffset")
                TdLibManager.startDownload(fileId, volumeOffset)
                lastRequested = volumeOffset
            }

            if (elapsed - lastHeartbeat >= heartbeatEveryMs) {
                lastHeartbeat = elapsed
                DebugLog.log(
                    "MultiVolumeProgressiveInputStream: still waiting for fileId=$fileId at volumeOffset=$volumeOffset " +
                            "after ${elapsed}ms (downloadOffset=${local?.downloadOffset}, downloadedPrefixSize=${local?.downloadedPrefixSize}, path=\"${local?.path}\")"
                )
            }

            if (elapsed > maxWaitMs) return null
            try { Thread.sleep(pollIntervalMs) } catch (e: InterruptedException) { return null }
        }
    }

    override fun close() {
        DebugLog.log("MultiVolumeProgressiveInputStream: closed at position=$position")
        raf?.close()
        raf = null
    }
}