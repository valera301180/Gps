package ru.taxisharan.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class GpsTrackingService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_PAUSE = "PAUSE"
        var isRunning = false
        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "TaxiTrackerChannel"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isPassiveMode = false
    private var driverId: String = ""
    private val client = OkHttpClient()

    // Очередь для кэширования при обрыве связи
    private val pendingUpdates = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {            ACTION_START -> {
                driverId = intent.getStringExtra("driver_id") ?: "UNKNOWN"
                isPassiveMode = false
                startForeground(NOTIF_ID, createNotification("🔵 Трекинг активен"))
                startLocationUpdates(3000L) // 3 секунды
            }
            ACTION_PAUSE -> {
                isPassiveMode = true
                startForeground(NOTIF_ID, createNotification("🟡 Пассивный режим"))
                startLocationUpdates(60000L) // 60 секунд
            }
        }
        processPendingUpdates() // Пытаемся отправить накопленное
        return START_STICKY
    }

    private fun startLocationUpdates(interval: Long) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    sendLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun sendLocation(location: Location) {
        val mode = if (isPassiveMode) "passive" else "active"
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date())
        
        // Получаем заряд батареи
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (batteryLevel >= 0 && batteryScale > 0) (batteryLevel * 100 / batteryScale) else 0

        // Используем встроенный JSONObject вместо Gson
        val json = JSONObject().apply {
            put("driver_id", driverId)
            put("lat", location.latitude)            put("lon", location.longitude)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("battery", batteryPct)
            put("mode", mode)
            put("timestamp", timestamp)
        }

        val jsonString = json.toString()

        if (isNetworkAvailable()) {
            sendToServer(jsonString)
        } else {
            if (pendingUpdates.size < 1000) {
                pendingUpdates.add(jsonString)
                savePendingToPrefs()
            }
        }
    }

    private fun sendToServer(jsonString: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonString.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://такси-люкс.рф/update_gps.php")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                synchronized(pendingUpdates) {
                    if (pendingUpdates.size < 1000) pendingUpdates.add(jsonString)
                    savePendingToPrefs()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val prefs = getSharedPreferences("TaxiPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putInt("points_sent", prefs.getInt("points_sent", 0) + 1).apply()
                }
            }
        })
    }

    private fun processPendingUpdates() {
        loadPendingFromPrefs()
        val iterator = pendingUpdates.iterator()
        while (iterator.hasNext()) {
            val json = iterator.next()            if (isNetworkAvailable()) {
                sendToServer(json)
                iterator.remove()
            } else {
                break
            }
        }
        savePendingToPrefs()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Такси Трекер", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Минимальное уведомление для работы GPS"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Такси Шаран")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    private fun savePendingToPrefs() {
        getSharedPreferences("TaxiPrefs", Context.MODE_PRIVATE).edit()
            .putStringSet("pending_gps", pendingUpdates.toSet())
            .apply()
    }

    private fun loadPendingFromPrefs() {
        val prefs = getSharedPreferences("TaxiPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("pending_gps", emptySet()) ?: emptySet()
        pendingUpdates.clear()
        pendingUpdates.addAll(saved)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isRunning = false    }

    override fun onBind(intent: Intent?): IBinder? = null
}
