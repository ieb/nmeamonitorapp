package uk.co.tfd.nmeamonitor.nmea

import kotlinx.coroutines.flow.MutableSharedFlow

interface NmeaSource {
    suspend fun start(sink: MutableSharedFlow<String>)
    fun stop()
}
