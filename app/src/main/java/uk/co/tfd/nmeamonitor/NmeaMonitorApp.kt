package uk.co.tfd.nmeamonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NmeaMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NMEA Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while the app is connected to the boat over Bluetooth"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "nmea_monitor_channel"
        const val NOTIFICATION_ID = 1
    }
}
