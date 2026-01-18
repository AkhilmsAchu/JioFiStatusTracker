package com.example.jiofistatustracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
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
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

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

        private fun fetchBatteryData(): BatteryData {
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
    }
}

data class BatteryData(
    val percentage: String,
    val status: String,
    val percentageValue: Int
)