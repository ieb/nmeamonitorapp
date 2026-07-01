package uk.co.tfd.nmeamonitor.history.persist

import android.content.Context
import android.util.Log
import uk.co.tfd.nmeamonitor.nmea.BinaryProtocol
import uk.co.tfd.nmeamonitor.nmea.BmsProtocol
import uk.co.tfd.nmeamonitor.nmea.EngineProtocol
import java.io.File

/**
 * Owner of the engine- and battery-history FrameLog writers. (The full
 * app records nav too; this quick-glance viewer persists engine and BMS
 * frames for the Engine and Battery graphs.)
 *
 * Files live under `Context.getExternalFilesDir("history")`, which
 * resolves on-device to
 *   /sdcard/Android/data/uk.co.tfd.nmeamonitor/files/history/
 * and is accessible via the Files app and `adb pull` without any
 * manifest permissions. Falls back to the app-private `filesDir` if
 * external storage is unavailable.
 *
 * Engine frames arrive at ~1 Hz (1 s/slot); the BMS board is slower
 * (~5 s/slot) so its file isn't bloated with sentinels.
 */
class HistoryLogger(context: Context) {

    private val dir: File = resolveHistoryDir(context)

    val nav: FrameLog = FrameLog(
        dir = dir,
        streamName = "nav",
        streamType = STREAM_NAV,
        recordSize = BinaryProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = BinaryProtocol.SENTINEL_FRAME,
    )

    val engine: FrameLog = FrameLog(
        dir = dir,
        streamName = "engine",
        streamType = STREAM_ENGINE,
        recordSize = EngineProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = EngineProtocol.SENTINEL_FRAME,
    )

    val bms: FrameLog = FrameLog(
        dir = dir,
        streamName = "bms",
        streamType = STREAM_BMS,
        recordSize = BmsProtocol.HISTORY_SLOT_SIZE,
        secondsPerRecord = 5,
        sentinel = BmsProtocol.SENTINEL_SLOT,
    )

    /** For diagnostics and for the charts' HistoryDataSource. */
    val directory: File get() = dir

    fun close() {
        nav.close()
        engine.close()
        bms.close()
    }

    companion object {
        private const val TAG = "HistoryLogger"

        const val STREAM_NAV = 1
        const val STREAM_ENGINE = 2
        const val STREAM_BMS = 3

        private fun resolveHistoryDir(context: Context): File {
            val ext = context.getExternalFilesDir("history")
            val chosen = if (ext != null) {
                ext
            } else {
                Log.w(TAG, "external files dir unavailable, falling back to internal filesDir")
                File(context.filesDir, "history")
            }
            chosen.mkdirs()
            return chosen
        }
    }
}
