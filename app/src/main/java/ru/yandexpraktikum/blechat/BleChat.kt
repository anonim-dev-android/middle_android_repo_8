package ru.yandexpraktikum.blechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.HiltAndroidApp
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelperImpl.Companion.CHANNEL_ID
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelperImpl.Companion.CHANNEL_NAME
import ru.yandexpraktikum.blechat.utils.checkForPostNotificationPermission

@HiltAndroidApp
class BleChat: Application() {

    override fun onCreate() {
        super.onCreate()
        setUpNotificationsChannel()
    }

    // создание канала
    private fun setUpNotificationsChannel() {
        // Задание 4
        this.checkForPostNotificationPermission {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }
}