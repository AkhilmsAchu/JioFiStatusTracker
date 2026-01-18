package com.example.jiofistatustracker

import android.app.job.JobParameters
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName

class WidgetUpdateService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(this, IntegerWidgetProvider::class.java)
        )

        for (id in ids) {
            IntegerWidgetProvider.updateAppWidget(this, appWidgetManager, id)
        }

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}
