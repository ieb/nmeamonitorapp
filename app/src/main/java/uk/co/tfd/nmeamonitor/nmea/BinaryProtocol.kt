package uk.co.tfd.nmeamonitor.nmea

import uk.co.tfd.nmeamonitor.history.RingSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the 29-byte binary BLE navigation protocol (magic 0xCC).
 * See doc/ble-transport.md in the nmeabridgeapp project for the full spec.
 */
object BinaryProtocol {

    internal const val MAGIC: Byte = 0xCC.toByte()
    internal const val FRAME_SIZE = 29

    // Field offsets from magic byte at 0.
    internal const val OFF_LAT = 1       // S32, 1e-7 deg
    internal const val OFF_LON = 5       // S32, 1e-7 deg
    internal const val OFF_COG = 9       // U16, 0.0001 rad
    internal const val OFF_SOG = 11      // U16, 0.01 m/s
    internal const val OFF_VARIATION = 13 // S16, 0.0001 rad
    internal const val OFF_HEADING = 15  // U16, 0.0001 rad
    internal const val OFF_DEPTH = 17    // U16, 0.01 m
    internal const val OFF_AWA = 19      // U16, 0.0001 rad
    internal const val OFF_AWS = 21      // U16, 0.01 m/s
    internal const val OFF_STW = 23      // U16, 0.01 m/s
    internal const val OFF_LOG = 25      // U32, 1 m

    internal const val MS_TO_KNOTS = 1.0 / 0.514444
    internal const val M_TO_NM = 1.0 / 1852.0
    internal const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Decode a 29-byte binary frame into NavigationState.
     * Returns null if the frame is invalid (wrong magic or size).
     */
    fun decode(data: ByteArray): NavigationState? {
        if (data.size != FRAME_SIZE) return null
        if (data[0] != MAGIC) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val latRaw = buf.int
        val lonRaw = buf.int
        val cogRaw = buf.short.toInt() and 0xFFFF
        val sogRaw = buf.short.toInt() and 0xFFFF
        val varRaw = buf.short
        val hdgRaw = buf.short.toInt() and 0xFFFF
        val depthRaw = buf.short.toInt() and 0xFFFF
        val awaRaw = buf.short.toInt() and 0xFFFF
        val awsRaw = buf.short.toInt() and 0xFFFF
        val stwRaw = buf.short.toInt() and 0xFFFF
        val logRaw = buf.int

        return NavigationState(
            latitude = s32OrNull(latRaw)?.let { it / 1e7 },
            longitude = s32OrNull(lonRaw)?.let { it / 1e7 },
            cog = u16OrNull(cogRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            sog = u16OrNull(sogRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            variation = s16OrNull(varRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            heading = u16OrNull(hdgRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            depth = u16OrNull(depthRaw)?.let { it * 0.01 },
            awa = u16OrNull(awaRaw)?.let {
                val deg = it * 0.0001 * RAD_TO_DEG
                // Convert 0-360 to ±180 (port negative, starboard positive)
                if (deg > 180.0) deg - 360.0 else deg
            },
            aws = u16OrNull(awsRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            stw = u16OrNull(stwRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            logNm = u32OrNull(logRaw)?.let { it * M_TO_NM },
        )
    }

    // --- Per-field accessors over a history RingSnapshot -----------------
    //
    // The nav graph reads STW / depth per sample, and awa/aws/stw to
    // recompute Polar % at each historical point.

    fun stwAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_STW))?.let { it * 0.01 * MS_TO_KNOTS }

    fun depthAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_DEPTH))?.let { it * 0.01 }

    fun awsAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_AWS))?.let { it * 0.01 * MS_TO_KNOTS }

    fun awaAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_AWA))?.let {
            val deg = it * 0.0001 * RAD_TO_DEG
            if (deg > 180.0) deg - 360.0 else deg
        }

    /**
     * 29 B sentinel frame written by FrameLog for silent seconds. Each
     * field holds its "not available" value so accessors return null and
     * the chart draws a gap. Signed fields use 0x7FFF…; a blanket 0xFF
     * fill would leave them holding -1 (valid data).
     */
    internal val SENTINEL_FRAME: ByteArray = run {
        val buf = ByteBuffer.allocate(FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(0x7FFFFFFF)              // lat s32 N/A
        buf.putInt(0x7FFFFFFF)              // lon s32 N/A
        buf.putShort(0xFFFF.toShort())      // cog u16 N/A
        buf.putShort(0xFFFF.toShort())      // sog u16 N/A
        buf.putShort(0x7FFF)                // variation s16 N/A
        buf.putShort(0xFFFF.toShort())      // heading u16 N/A
        buf.putShort(0xFFFF.toShort())      // depth u16 N/A
        buf.putShort(0xFFFF.toShort())      // awa u16 N/A
        buf.putShort(0xFFFF.toShort())      // aws u16 N/A
        buf.putShort(0xFFFF.toShort())      // stw u16 N/A
        buf.putInt(-1)                      // log u32 N/A (0xFFFFFFFF)
        buf.array()
    }
}
