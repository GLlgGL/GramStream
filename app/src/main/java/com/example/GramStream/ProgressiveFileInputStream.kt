package com.example.GramStream

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Streams bytes [startPosition, endPositionInclusive] of a Telegram file
 * while TDLib is still downloading it.
 *
 * How it stays correct while the underlying file is growing:
 *  - Before returning any bytes, it checks TDLib's current download state
 *    for this file (`TdApi.LocalFile.downloadOffset` /
 *    `downloadedPrefixSize`), which tells us exactly how many contiguous
 *    bytes are available for read starting from wherever TDLib was told
 *    to download from.
 *  - If the position we need isn't available yet, it polls briefly and
 *    retries - this is what makes "wait for it to arrive" work instead of
 *    reading a torn/incomplete chunk.
 *  - If the position we need is *behind* where TDLib is currently
 *    downloading from (e.g. the player seeks backward/forward outside the
 *    already-downloaded range), it calls [TdLibManager.startDownload]
 *    again with the new offset - this is what makes seeking work.
 */
class ProgressiveFileInputStream(
    private val fileId: Int,
    startPosition: Long,
    private val endPositionInclusive: Long
) : InputStream() {

    private var position = startPosition
    private var raf: RandomAccessFile? = null
    private var lastRequestedOffset = -1L

    private val pollIntervalMs = 200L
    private val maxWaitMs = 60_000L // give up after 60s of no progress at this position
    private val heartbeatEveryMs = 5_000L

    init {
        DebugLog.log("ProgressiveFileInputStream: opened fileId=$fileId range=[$startPosition,$endPositionInclusive]")
    }

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n <= 0) -1 else (buf[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position > endPositionInclusive) return -1

        val available = waitForBytesAvailable()
        if (available == null) {
            DebugLog.log("ProgressiveFileInputStream: TIMED OUT after ${maxWaitMs}ms waiting for fileId=$fileId at position=$position - giving up (EOF)")
            return -1
        }
        if (available <= position) return -1 // timed out with nothing new

        val file = ensureRandomAccessFile() ?: return -1

        val wantLen = minOf(len.toLong(), (endPositionInclusive - position + 1), (available - position))
            .toInt()
            .coerceAtLeast(0)
        if (wantLen == 0) return -1

        file.seek(position)
        val read = file.read(b, off, wantLen)
        if (read > 0) position += read
        return read
    }

    /**
     * Blocks (with polling) until bytes at [position] are available, or
     * redirects the download there if TDLib isn't currently heading that
     * way. Returns the absolute byte offset up to which data is
     * currently available, or null if we gave up waiting.
     *
     * FIX: this loop used to poll silently - now it logs the initial
     * download kick-off, a 5s heartbeat with TDLib's reported state, and
     * the final timeout, so a stalled download shows up in the log instead
     * of just being an empty gap.
     */
    private fun waitForBytesAvailable(): Long? {
        val start = System.currentTimeMillis()
        var lastHeartbeat = 0L

        while (true) {
            val file = TdLibManager.getCachedFile(fileId) ?: TdLibManager.getFileBlocking(fileId)
            val local = file?.local
            val elapsed = System.currentTimeMillis() - start

            if (local != null) {
                if (local.isDownloadingCompleted) {
                    // whole file is on disk - everything is "available"
                    return Long.MAX_VALUE
                }
                val downloadedUpTo = local.downloadOffset + local.downloadedPrefixSize
                val downloadCoversOurPosition =
                    local.downloadOffset <= position && position < downloadedUpTo

                if (downloadCoversOurPosition) {
                    return downloadedUpTo
                }

                // Not covered: either downloading a different region (seek
                // happened) or hasn't started there yet - (re)point it here.
                if (lastRequestedOffset != position) {
                    DebugLog.log("ProgressiveFileInputStream: requesting download fileId=$fileId from position=$position (was downloadOffset=${local.downloadOffset}, downloadedPrefixSize=${local.downloadedPrefixSize})")
                    TdLibManager.startDownload(fileId, position)
                    lastRequestedOffset = position
                }
            } else {
                if (lastRequestedOffset != position) {
                    DebugLog.log("ProgressiveFileInputStream: no TdApi.File yet for fileId=$fileId, requesting download from position=$position")
                    TdLibManager.startDownload(fileId, position)
                    lastRequestedOffset = position
                }
            }

            if (elapsed - lastHeartbeat >= heartbeatEveryMs) {
                lastHeartbeat = elapsed
                DebugLog.log(
                    "ProgressiveFileInputStream: still waiting for fileId=$fileId at position=$position " +
                            "after ${elapsed}ms (downloadOffset=${local?.downloadOffset}, downloadedPrefixSize=${local?.downloadedPrefixSize}, path=\"${local?.path}\")"
                )
            }

            if (elapsed > maxWaitMs) return null
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                return null
            }
        }
    }

    private fun ensureRandomAccessFile(): RandomAccessFile? {
        if (raf != null) return raf
        val path = TdLibManager.getCachedFile(fileId)?.local?.path ?: return null
        if (path.isBlank()) return null
        return try {
            RandomAccessFile(path, "r").also { raf = it }
        } catch (e: IOException) {
            DebugLog.log("ProgressiveFileInputStream: failed to open local file \"$path\": ${e.message}")
            null
        }
    }

    override fun close() {
        DebugLog.log("ProgressiveFileInputStream: closed fileId=$fileId at position=$position")
        raf?.close()
        raf = null
    }
}