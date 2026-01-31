package com.example.jiofistatustracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "battery_alerts"
        private const val NOTIF_LOW_BATTERY = 1
        private const val NOTIF_HIGH_BATTERY = 2
    }

    override suspend fun doWork(): Result {
        val quietHourStatus = Utils.isQuietHours()

        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(applicationContext, IntegerWidgetProvider::class.java)
        )

        // Update all widgets
        for (id in ids) {
            IntegerWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, id)
        }

        // Check battery and send notifications if needed
        if (!quietHourStatus) checkBatteryAndNotify()

        return Result.success()
    }

    private fun checkBatteryAndNotify() {
        try {

            val batteryData = IntegerWidgetProvider.fetchBatteryData()
            val percentage = batteryData.percentageValue
            val status = batteryData.status

            // Low battery notification (below 30% and discharging)
            if (percentage in 1..29 && status == "Discharging") {
                sendNotification(
                    "Battery Low - $percentage%",
                    "⚠️ Time to switch on charging!",
                    NOTIF_LOW_BATTERY
                )
            }
            // High battery notification (above 90% and charging)
            else if (percentage > 90 && (status == "Charging" || status == "Full Charged")) {
                sendNotification(
                    "Battery Full - $percentage%",
                    "✅ Time to switch off charging!",
                    NOTIF_HIGH_BATTERY
                )
            }

        } catch (_: Exception) {
            // Failed to fetch battery data, skip notification
        }
    }

    private fun sendNotification(title: String, message: String, notificationId: Int) {
        createNotificationChannel()

        val intent = Intent()
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for battery charging alerts"
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}