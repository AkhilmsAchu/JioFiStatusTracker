package com.example.jiofistatustracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

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
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(applicationContext, IntegerWidgetProvider::class.java)
        )

        // Update all widgets
        for (id in ids) {
            IntegerWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, id)
        }

        // Check battery and send notifications if needed
        checkBatteryAndNotify()

        return Result.success()
    }

    private fun isNotificationAllowed(): Boolean {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)

        // Convert current time to minutes since midnight
        val currentMinutes = hour * 60 + minute

        val nightStart = 22 * 60 + 30   // 10:30 PM = 1350
        val morningEnd = 7 * 60         // 7:00 AM = 420

        // Blocked if between 10:30 PM → midnight OR midnight → 7:00 AM
        return !(currentMinutes >= nightStart || currentMinutes < morningEnd)
    }

    private fun checkBatteryAndNotify() {
        try {

            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (!isNotificationAllowed())
            {
                wifiManager.isWifiEnabled = false
                return
            }

            wifiManager.isWifiEnabled = true

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

        } catch (e: Exception) {
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