package uk.co.tfd.nmeamonitor.history.persist

import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Read-only counterpart to [FrameLog]. Opens an existing per-day binary
 * history file, validates the 14-byte header, and offers indexed slot
 * access plus a striding sequence iterator.
 *
 * The header constants here match those in [FrameLog]; if the on-disk
 * format ever gains a new version, both classes must move in lockstep.
 */
class FrameLogReader private constructor(
    val file: File,
    val streamType: Int,
    val recordSize: Int,
    val secondsPerRecord: Int,
    val startTimeSec: Long,
    /** The whole file body (after the 14-byte header), read once at open. */
    private val body: ByteArray,
) : Closeable {

    /** Number of slots in the file (header excluded, partial trailing slots ignored). */
    val slotCount: Int = body.size / recordSize

    /** Wall-clock UTC ms for the slot at `slot` (slot 0 = startTimeSec). */
    fun timestampMsOf(slot: Int): Long =
        (startTimeSec + slot.toLong() * secondsPerRecord) * 1000L

    /**
     * Read slot `slot` into `dst`. `dst.size` must equal [recordSize] —
     * caller-provided so a caller iterating many slots can reuse one
     * buffer.
     */
    fun readSlot(slot: Int, dst: ByteArray) {
        require(dst.size == recordSize) { "dst size ${dst.size} != recordSize $recordSize" }
        require(slot in 0 until slotCount) { "slot $slot out of [0, $slotCount)" }
        System.arraycopy(body, slot * recordSize, dst, 0, recordSize)
    }

    /**
     * The wall-clock UTC ms of the **last** slot in the file. Returns
     * `null` when the file holds no records.
     */
    val lastSlotMs: Long?
        get() = if (slotCount == 0) null else timestampMsOf(slotCount - 1)

    /**
     * The wall-clock UTC ms of the **first** slot in the file. Returns
     * `null` when the file holds no records.
     */
    val firstSlotMs: Long?
        get() = if (slotCount == 0) null else timestampMsOf(0)

    /**
     * Random-access lookup: return the slot whose timestamp is closest
     * to `tMs` without exceeding it. Returns `null` when the file is
     * empty or `tMs` is before the file's first slot. When `tMs` is past
     * the end, returns the last slot.
     */
    fun slotAt(tMs: Long): Slot? {
        if (slotCount == 0) return null
        val firstMs = timestampMsOf(0)
        if (tMs < firstMs) return null
        val idx = ((tMs - firstMs) / 1000L / secondsPerRecord)
            .toInt()
            .coerceIn(0, slotCount - 1)
        return slotByIndex(idx)
    }

    /**
     * Return the slot at logical index `slot`. Bounds-checked.
     */
    fun slotByIndex(slot: Int): Slot? {
        if (slot !in 0 until slotCount) return null
        val frameCopy = ByteArray(recordSize)
        System.arraycopy(body, slot * recordSize, frameCopy, 0, recordSize)
        return Slot(timestampMsOf(slot), frameCopy)
    }

    /**
     * Yield (timestampMs, frameBytes) for every `stride`th slot in the
     * file. `frameBytes` is a fresh copy on each emission. If
     * `skipSentinel` is non-null, slots whose contents exactly match the
     * byte array are silently dropped from the sequence.
     */
    fun streamSlots(stride: Int = 1, skipSentinel: ByteArray? = null): Sequence<Slot> = sequence {
        require(stride >= 1) { "stride must be >= 1, got $stride" }
        if (skipSentinel != null) {
            require(skipSentinel.size == recordSize) {
                "skipSentinel size ${skipSentinel.size} != recordSize $recordSize"
            }
        }
        val n = slotCount
        var i = 0
        while (i < n) {
            val off = i * recordSize
            if (skipSentinel == null || !regionEquals(body, off, skipSentinel)) {
                val frameCopy = ByteArray(recordSize)
                System.arraycopy(body, off, frameCopy, 0, recordSize)
                yield(Slot(timestampMsOf(i), frameCopy))
            }
            i += stride
        }
    }

    private fun regionEquals(a: ByteArray, aOff: Int, b: ByteArray): Boolean {
        for (k in b.indices) if (a[aOff + k] != b[k]) return false
        return true
    }

    /**
     * One element of [streamSlots]. `bytes.size == recordSize`.
     */
    data class Slot(val timestampMs: Long, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Slot && timestampMs == other.timestampMs && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * timestampMs.hashCode() + bytes.contentHashCode()
    }

    /**
     * No-op: the file is read in full at [open] time and closed
     * immediately. Kept for source compatibility with `use { … }`
     * blocks at call sites.
     */
    override fun close() {}

    companion object {
        private const val TAG = "FrameLogReader"

        // Header layout matches FrameLog.MAGIC / HEADER_SIZE.
        private val MAGIC = "navdata".toByteArray(Charsets.US_ASCII)
        const val HEADER_SIZE = 14

        // Sanity cap on the body ByteArray we allocate. A full day of the
        // widest stream (48 B BMS at 1 s/slot) is ~4 MB; 16 MB gives head-
        // room for future streams while refusing pathological files.
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024

        /**
         * Open a history file for reading. Returns null if the file is
         * missing, too short to contain a header, has the wrong magic,
         * or carries impossible header values. Mismatched
         * `expectedStreamType` / `expectedRecordSize` /
         * `expectedSecondsPerRecord` (when any are non-null) also yield
         * null.
         */
        fun open(
            file: File,
            expectedStreamType: Int? = null,
            expectedRecordSize: Int? = null,
            expectedSecondsPerRecord: Int? = null,
        ): FrameLogReader? {
            if (!file.exists() || file.length() < HEADER_SIZE) return null
            val raf = try {
                RandomAccessFile(file, "r")
            } catch (e: IOException) {
                Log.w(TAG, "open ${file.name}: ${e.message}")
                return null
            }
            try {
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                for (i in MAGIC.indices) {
                    if (header[i] != MAGIC[i]) {
                        Log.w(TAG, "${file.name}: bad magic")
                        return null
                    }
                }
                val startSec = (header[7].toLong() and 0xFFL) or
                        ((header[8].toLong() and 0xFFL) shl 8) or
                        ((header[9].toLong() and 0xFFL) shl 16) or
                        ((header[10].toLong() and 0xFFL) shl 24)
                val streamType = header[11].toInt() and 0xFF
                val recordSize = header[12].toInt() and 0xFF
                val spr = header[13].toInt() and 0xFF
                if (recordSize <= 0 || spr <= 0) {
                    Log.w(TAG, "${file.name}: impossible header (rec=$recordSize spr=$spr)")
                    return null
                }
                if (expectedStreamType != null && streamType != expectedStreamType) return null
                if (expectedRecordSize != null && recordSize != expectedRecordSize) return null
                if (expectedSecondsPerRecord != null && spr != expectedSecondsPerRecord) return null

                val bodyLen = (file.length() - HEADER_SIZE).coerceAtLeast(0L)
                // A per-day file for any stream is under ~3 MB. Anything
                // above the cap is corrupt or hostile; refuse rather than
                // let ByteArray(bodyLen.toInt()) OOM or wrap to a negative
                // size on files > 2 GiB.
                if (bodyLen > MAX_BODY_BYTES) {
                    Log.w(TAG, "${file.name}: body $bodyLen exceeds cap $MAX_BODY_BYTES")
                    return null
                }
                val truncated = bodyLen - (bodyLen % recordSize)
                val body = ByteArray(truncated.toInt())
                raf.readFully(body)
                return FrameLogReader(file, streamType, recordSize, spr, startSec, body)
            } catch (e: IOException) {
                Log.w(TAG, "open ${file.name}: ${e.message}")
                return null
            } finally {
                try { raf.close() } catch (_: IOException) {}
            }
        }

        /** Build the canonical filename for `streamName` on the given UTC date "YYYY-MM-DD". */
        fun fileFor(dir: File, streamName: String, date: String): File =
            File(dir, "$streamName-$date.bin")
    }
}
