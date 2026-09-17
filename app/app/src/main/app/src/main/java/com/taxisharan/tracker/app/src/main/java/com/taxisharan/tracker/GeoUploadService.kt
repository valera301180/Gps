package com.taxisharan.tracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeoUploadService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null

    companion object {
        const val CHANNEL_ID = "GeoTrackerChannel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()
        startLocationUpdates()
        isRunning = true

        return START_STICKY
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            15000L // Каждые 15 секунд
        )
            .setMinUpdateDistanceMeters(50f) // Или каждые 50 метров
            .build()
    }

    private fun startLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    sendLocationToServer(location)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null
            )
        }
    }

    private fun sendLocationToServer(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            try {                val prefs = getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)
                val driverId = prefs.getString("driver_id", "") ?: ""
                val apiKey = prefs.getString("api_key", "") ?: ""

                if (driverId.isEmpty() || apiKey.isEmpty()) {
                    Log.e("GeoService", "Настройки не заполнены!")
                    return@launch
                }

                val jsonBody = JSONObject().apply {
                    put("key", apiKey)
                    put("driver_id", driverId)
                    put("lat", location.latitude)
                    put("lng", location.longitude)
                    put("speed", location.speed)
                    put("accuracy", location.accuracy)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }

                // ВАЖНО: Замени на свой URL!
                val url = URL("https://твой-сайт.ru/taxi/driver/geo_listener.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                OutputStreamWriter(conn.outputStream).use { 
                    it.write(jsonBody.toString()) 
                }

                val responseCode = conn.responseCode
                Log.i("GeoService", "Отправлено. Код: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.i("GeoService", "✅ Успешно отправлено: ${location.latitude}, ${location.longitude}")
                }

                conn.disconnect()

            } catch (e: Exception) {
                Log.e("GeoService", "❌ Ошибка: ${e.message}")
            }
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 GPS Трекер")
            .setContentText("Отправка координат активна")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 8)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Трекер",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновая отправка геолокации"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        isRunning = false
        Log.d("GeoService", "Служба остановлена")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
