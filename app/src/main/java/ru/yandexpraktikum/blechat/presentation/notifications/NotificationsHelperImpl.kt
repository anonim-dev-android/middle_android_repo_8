package ru.yandexpraktikum.blechat.presentation.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.utils.checkForPostNotificationPermission
import javax.inject.Inject

class NotificationsHelperImpl
@Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: NotificationManagerCompat
) : NotificationsHelper {

    companion object {
        const val CHANNEL_ID = "channel_id"
        const val CHANNEL_NAME = "Входящие сообщения"
        const val NOTIFICATION_ID = 1234
    }

    override fun notifyOnMessageReceived(title: String, message: String) {
        // Задание 4

        context.checkForPostNotificationPermission {
            // создание оповещения
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.bluetooth_ic)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

    }
}