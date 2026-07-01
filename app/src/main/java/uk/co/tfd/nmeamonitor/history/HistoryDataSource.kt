package uk.co.tfd.nmeamonitor.history

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.tfd.nmeamonitor.history.persist.FrameLogReader
import uk.co.tfd.nmeamonitor.history.persist.HistoryLogger
import uk.co.tfd.nmeamonitor.nmea.BinaryProtocol
import uk.co.tfd.nmeamonitor.nmea.BmsProtocol
import uk.co.tfd.nmeamonitor.nmea.EngineProtocol
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Loads a multi-day window of one history stream's files into an
 * in-memory [FrameRing]. The chart reads from the resulting
 * [RingSnapshot] using the same per-field accessors that decode live
 * frames; the file origin is invisible to it.
 *
 * Configured per-stream via [forEngine] / [forBms]. Memory bound: the
 * stream is capped at [MAX_DISPLAY_SAMPLES] slots regardless of window;
 * wider windows apply a uniform stride so the ring still fits.
 *
 * The instance keeps the last result cached — a subsequent call with
 * identical (tStartMs, tEndMs) returns it without touching disk.
 */
class HistoryDataSource private constructor(
    private val historyDir: File,
    private val streamName: String,
    private val streamType: Int,
    private val recordSize: Int,
    private val secondsPerRecord: Int,
    private val sentinel: ByteArray,
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var cachedStart = 0L
    private var cachedEnd = 0L
    private var cached: RingSnapshot? = null

    /**
     * Read every history file whose UTC date falls in
     * [floorDate(tStartMs), floorDate(tEndMs)] and build a snapshot
     * covering all slots whose timestamp is in [tStartMs, tEndMs].
     * Sentinel slots are skipped silently. Runs on Dispatchers.IO.
     */
    suspend fun loadWindow(tStartMs: Long, tEndMs: Long): RingSnapshot {
        require(tEndMs >= tStartMs) { "tEndMs ($tEndMs) < tStartMs ($tStartMs)" }
        cached?.let { c ->
            if (cachedStart == tStartMs && cachedEnd == tEndMs) return c
        }
        val result = withContext(Dispatchers.IO) {
            loadStream(tStartMs, tEndMs)
        }
        cached = result
        cachedStart = tStartMs
        cachedEnd = tEndMs
        return result
    }

    /** Drop the cached snapshot so the next load re-reads disk (live tail). */
    fun invalidate() {
        cached = null
    }

    private fun loadStream(tStartMs: Long, tEndMs: Long): RingSnapshot {
        val windowSec = ((tEndMs - tStartMs) / 1000L).coerceAtLeast(0L)
        val rawSlots = (windowSec / secondsPerRecord).toInt() + 1
        val stride = if (rawSlots <= MAX_DISPLAY_SAMPLES) 1
                     else ((rawSlots + MAX_DISPLAY_SAMPLES - 1) / MAX_DISPLAY_SAMPLES)
        val ringCapacity = ((rawSlots + stride - 1) / stride).coerceAtLeast(1)
        val ring = FrameRing(frameSize = recordSize, capacity = ringCapacity)

        var dayMs = floorMidnightUtcMs(tStartMs)
        val lastDayMs = floorMidnightUtcMs(tEndMs)
        while (dayMs <= lastDayMs) {
            val date = dateFormat.format(Date(dayMs))
            val file = FrameLogReader.fileFor(historyDir, streamName, date)
            val reader = FrameLogReader.open(
                file,
                expectedStreamType = streamType,
                expectedRecordSize = recordSize,
                expectedSecondsPerRecord = secondsPerRecord,
            )
            if (reader != null) {
                try {
                    for (slot in reader.streamSlots(stride = stride, skipSentinel = sentinel)) {
                        if (slot.timestampMs < tStartMs) continue
                        if (slot.timestampMs > tEndMs) break
                        ring.append(slot.timestampMs, slot.bytes)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "read $streamName for $date: ${e.message}")
                } finally {
                    reader.close()
                }
            }
            dayMs += MS_PER_DAY
        }
        return ring.snapshot()
    }

    private fun floorMidnightUtcMs(ms: Long): Long =
        (ms / MS_PER_DAY) * MS_PER_DAY

    companion object {
        private const val TAG = "HistoryDataSource"

        /**
         * Upper bound on samples after striding. At one sample per 1–2 px
         * the polyline already looks smooth; larger values just multiply
         * the per-window scan+draw cost.
         */
        const val MAX_DISPLAY_SAMPLES = 1024

        private const val MS_PER_DAY = 86_400_000L

        fun forNav(historyDir: File): HistoryDataSource = HistoryDataSource(
            historyDir = historyDir,
            streamName = "nav",
            streamType = HistoryLogger.STREAM_NAV,
            recordSize = BinaryProtocol.FRAME_SIZE,
            secondsPerRecord = 1,
            sentinel = BinaryProtocol.SENTINEL_FRAME,
        )

        fun forEngine(historyDir: File): HistoryDataSource = HistoryDataSource(
            historyDir = historyDir,
            streamName = "engine",
            streamType = HistoryLogger.STREAM_ENGINE,
            recordSize = EngineProtocol.FRAME_SIZE,
            secondsPerRecord = 1,
            sentinel = EngineProtocol.SENTINEL_FRAME,
        )

        fun forBms(historyDir: File): HistoryDataSource = HistoryDataSource(
            historyDir = historyDir,
            streamName = "bms",
            streamType = HistoryLogger.STREAM_BMS,
            recordSize = BmsProtocol.HISTORY_SLOT_SIZE,
            secondsPerRecord = 5,
            sentinel = BmsProtocol.SENTINEL_SLOT,
        )
    }
}
