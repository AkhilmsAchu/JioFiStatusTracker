package com.example.jiofistatustracker


import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(applicationContext, IntegerWidgetProvider::class.java)
        )

        for (id in ids) {
            IntegerWidgetProvider.updateAppWidget(applicationContext, appWidgetManager, id)
        }

        return Result.success()
    }
}