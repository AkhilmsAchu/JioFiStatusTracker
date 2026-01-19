package com.example.jiofistatustracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class IntegerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Schedule periodic updates using WorkManager
        scheduleWidgetUpdates(context)

        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Widget added for the first time, schedule updates
        scheduleWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget removed, cancel updates
        WorkManager.getInstance(context).cancelUniqueWork("WidgetUpdate")
    }

    private fun scheduleWidgetUpdates(context: Context) {
        val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            10, TimeUnit.MINUTES // Update every 10 minutes
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "WidgetUpdate",
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Set up click listener to refresh
            val intent = Intent(context, IntegerWidgetProvider::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, IntegerWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Set click listener on the entire widget layout
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // Fetch and update the values
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val batteryData = fetchBatteryData()
                    val timestamp = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

                    checkBatteryAndNotify(context, batteryData)

                    withContext(Dispatchers.Main) {
                        views.setTextViewText(
                            R.id.widget_battery_percentage,
                            batteryData.percentage
                        )
                        views.setTextViewText(
                            R.id.widget_battery_status,
                            batteryData.status
                        )
                        views.setTextViewText(
                            R.id.widget_timestamp,
                            "Updated: $timestamp"
                        )

                        // Change color based on battery level
                        val color = when {
                            batteryData.percentageValue >= 60 -> 0xFF4CAF50.toInt() // Green
                            batteryData.percentageValue >= 30 -> 0xFFFFC107.toInt() // Yellow
                            batteryData.percentageValue > 10 -> 0xFFF44336.toInt() // Red
                            else -> 0xFFCCCCCC.toInt() // Gray for N/A
                        }
                        views.setTextColor(R.id.widget_battery_percentage, color)

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        views.setTextViewText(R.id.widget_battery_percentage, "N/A")
                        views.setTextViewText(R.id.widget_battery_status, "Error")
                        views.setTextViewText(
                            R.id.widget_timestamp,
                            e.message ?: "Connection failed"
                        )
                        views.setTextColor(R.id.widget_battery_percentage, 0xFFCCCCCC.toInt())
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            }
        }

        public fun fetchBatteryData(): BatteryData {
            val url = java.net.URL("http://jiofi.local.html/st_dev.w.xml")
            val connection = url.openConnection() as java.net.HttpURLConnection

            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val xmlResponse = connection.inputStream.bufferedReader().readText()
                return parseXmlResponse(xmlResponse)
            } finally {
                connection.disconnect()
            }
        }

        private fun parseXmlResponse(xml: String): BatteryData {
            try {
                // Extract batt_per
                val battPerRegex = "<batt_per>(\\d+)</batt_per>".toRegex()
                val battPerMatch = battPerRegex.find(xml)
                val battPer = battPerMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                // Extract batt_st
                val battStRegex = "<batt_st>(\\d+)</batt_st>".toRegex()
                val battStMatch = battStRegex.find(xml)
                val battSt = battStMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                if (battPer > 0) {
                    val percentage = "$battPer%"

                    // Calculate battery status using bit shift
                    val hi = battSt ushr 8  // unsigned right shift by 8 bits

                    val status = when (hi) {
                        in 0..3 -> "Discharging"
                        4 -> "Charging"
                        5 -> "Full Charged"
                        else -> "Unknown"
                    }

                    return BatteryData(percentage, status, battPer)
                } else {
                    return BatteryData("N/A", "N/A", 0)
                }
            } catch (e: Exception) {
                return BatteryData("N/A", "N/A", 0)
            }
        }

        private fun checkBatteryAndNotify(context: Context, batteryData: BatteryData) {
            val percentage = batteryData.percentageValue
            val status = batteryData.status

            val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
            val lastNotifType = prefs.getString("last_notification_type", "")

            // Low battery notification (below 30% and discharging)
            if (percentage in 1..29 && status == "Discharging") {
                if (lastNotifType != "low") {
                    sendNotification(
                        context,
                        "Battery Low - $percentage%",
                        "⚠️ Time to switch on charging!",
                        1
                    )
                    prefs.edit().putString("last_notification_type", "low").apply()
                }
            }
            // High battery notification (above 90% and charging/full)
            else if (percentage > 90 && (status == "Charging" || status == "Full Charged")) {
                if (lastNotifType != "high") {
                    sendNotification(
                        context,
                        "Battery Full - $percentage%",
                        "✅ Time to switch off charging!",
                        2
                    )
                    prefs.edit().putString("last_notification_type", "high").apply()
                }
            }
            // Reset notification state when battery is in normal range
            else {
                if (lastNotifType != "") {
                    prefs.edit().putString("last_notification_type", "").apply()
                }
            }
        }

        private fun sendNotification(context: Context, title: String, message: String, notificationId: Int) {
            val channelId = "battery_alerts"

            // Create notification channel for Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Battery Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for battery charging alerts"
                }

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.createNotificationChannel(channel)
            }

            // Create an empty PendingIntent that does nothing
            val intent = Intent()
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(notificationId, notification)
        }
    }
}

data class BatteryData(
    val percentage: String,
    val status: String,
    val percentageValue: Int
)