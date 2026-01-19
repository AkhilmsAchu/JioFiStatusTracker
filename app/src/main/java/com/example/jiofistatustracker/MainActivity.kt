package com.example.jiofistatustracker

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var batteryPercentageText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var refreshButton: Button
    private lateinit var restartButton: Button

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 123
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        batteryPercentageText = findViewById(R.id.battery_percentage)
        batteryStatusText = findViewById(R.id.battery_status)
        lastUpdateText = findViewById(R.id.last_update)
        refreshButton = findViewById(R.id.refresh_button)
        restartButton = findViewById(R.id.restart_button)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }

        // Set click listeners
        refreshButton.setOnClickListener {
            fetchBatteryData()
        }

        restartButton.setOnClickListener {
            restartDevice()
        }

        // Fetch data on start
        fetchBatteryData()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchBatteryData() {
        refreshButton.isEnabled = false
        refreshButton.text = "Refreshing..."

        lifecycleScope.launch {
            try {
                val batteryData = withContext(Dispatchers.IO) {
                    IntegerWidgetProvider.fetchBatteryData()
                }

                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                batteryPercentageText.text = batteryData.percentage
                batteryStatusText.text = batteryData.status
                lastUpdateText.text = "Last updated: $timestamp"

                // Change color based on battery level
                val color = when {
                    batteryData.percentageValue >= 60 -> getColor(android.R.color.holo_green_dark)
                    batteryData.percentageValue >= 20 -> getColor(android.R.color.holo_orange_dark)
                    batteryData.percentageValue > 0 -> getColor(android.R.color.holo_red_dark)
                    else -> getColor(android.R.color.darker_gray)
                }
                batteryPercentageText.setTextColor(color)

            } catch (e: Exception) {
                batteryPercentageText.text = "N/A"
                batteryStatusText.text = "Error"
                lastUpdateText.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, "Failed to fetch data", Toast.LENGTH_SHORT).show()
            } finally {
                refreshButton.isEnabled = true
                refreshButton.text = "Refresh"
            }
        }
    }

    private fun restartDevice() {
        restartButton.isEnabled = false
        restartButton.text = "Restarting..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    performJioFiRestart()
                }

                Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Restart failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                restartButton.isEnabled = true
                restartButton.text = "Restart Device"
            }
        }
    }

    private fun performJioFiRestart(): String {
        val base = "http://jiofi.local.html"
        val password = "administrator" // Change if different
        val cookieManager = java.net.CookieManager()
        java.net.CookieHandler.setDefault(cookieManager)

        try {
            // Step 1: Initial load to get SessionID
            makeRequest("$base/", "GET")

            // Step 2: Load login.htm and extract csrf_token2
            val loginHtml = makeRequest("$base/login.htm", "GET", mapOf(
                "Referer" to "$base/"
            ))
            val csrfToken1 = extractCsrfToken(loginHtml)

            // Step 3: Get rand from mark_lang.w.xml
            val markLangXml = makeRequest("$base/mark_lang.w.xml?_=${System.currentTimeMillis()}", "GET", mapOf(
                "Referer" to "$base/"
            ))
            val rand = extractXmlValue(markLangXml, "rand")

            // Step 4: Login with hashed password
            val hashedPassword = md5Hash(rand + password.lowercase())
            makeRequest("$base/wxml/post_login.xml", "POST", mapOf(
                "Origin" to base,
                "Referer" to "$base/",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "__RequestVerificationToken" to csrfToken1
            ), "Name=administrator&password=$hashedPassword&rand=$rand")

            // Step 5: Get new csrf_token2 after login
            val setUserHtml = makeRequest("$base/set_user.html", "GET", mapOf(
                "Referer" to "$base/index.htm"
            ))
            val csrfToken2 = extractCsrfToken(setUserHtml)

            try {
                // Step 6: Send restore/restart request
                makeRequest("$base/wxml/set_restore.xml", "POST", mapOf(
                    "Referer" to "$base/set_user.html",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "__RequestVerificationToken" to csrfToken2
                ), "restore=1")
                return "Device restart failed"
            } catch (e: Exception) {

            }
        } catch (e: Exception) {
            throw Exception("Failed: ${e.message}")
        }
        return "Device restart initiated successfully"
    }

    private fun makeRequest(
        urlString: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): String {
        val url = java.net.URL(urlString)
        val connection = url.openConnection() as java.net.HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true

            // Common headers
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")

            // Add custom headers
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Send body if POST
            if (method == "POST" && body != null) {
                connection.doOutput = true
                connection.outputStream.write(body.toByteArray())
            }

            // Read response
            val responseCode = connection.responseCode
            val response = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            }

            return response

        } finally {
            connection.disconnect()
        }
    }

    private fun extractCsrfToken(html: String): String {
        val regex = """<input[^>]*id=["']csrf_token2["'][^>]*value=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(html)
        return match?.groupValues?.get(1) ?: throw Exception("csrf_token2 not found")
    }

    private fun extractXmlValue(xml: String, tag: String): String {
        val regex = "<$tag>([^<]+)</$tag>".toRegex()
        val match = regex.find(xml)
        return match?.groupValues?.get(1) ?: throw Exception("$tag not found in XML")
    }

    private fun md5Hash(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}