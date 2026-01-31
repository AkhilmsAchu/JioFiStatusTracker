package com.example.jiofistatustracker

import java.util.Calendar

object Utils {
    fun isQuietHours(): Boolean {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val nightStart = 22 * 60 + 30   // 10:30 PM
        val morningEnd = 6 * 60 + 30    // 6:30 AM
        return currentMinutes >= nightStart || currentMinutes < morningEnd
    }
}