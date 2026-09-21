package ru.taxisharan.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpsTrackingService : Service() {
    companion object {
        const val ACTION_START = "START"
        var isRunning = false
        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "TaxiTrackerChannel"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var driverId: String = ""
    private val client = OkHttpClient()
    private val pendingUpdates = mutableListOf<String>()
    
    // Динамические интервалы (по умолчанию офлайн)
    private var commandIntervalMs = 60000L // 60 сек
    private var gpsIntervalMs = 120000L    // 120 сек
    private var currentMode = "offline"
    private var connectionErrorCount = 0
    // Планировщики
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commandRunnable: Runnable
    private lateinit var gpsRunnable: Runnable

    // Звуки
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        isRunning = true
        initMediaPlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            driverId = intent.getStringExtra("driver_id") ?: "UNKNOWN"
            startForeground(NOTIF_ID, createNotification("🟡 Офлайн (ожидание команды)"))
            
            // Запускаем циклы
            scheduleCommandPoll()
            scheduleGpsUpdate()
            processPendingUpdates()
        }
        return START_STICKY
    }

    private fun scheduleCommandPoll() {
        commandRunnable = object : Runnable {
            override fun run() {
                fetchCommands()
                // Перепланируем с текущим (возможно, обновленным) интервалом
                handler.postDelayed(this, commandIntervalMs)
            }
        }
        handler.post(commandRunnable)
    }

    private fun scheduleGpsUpdate() {
        gpsRunnable = object : Runnable {
            override fun run() {
                requestSingleLocationUpdate()
                handler.postDelayed(this, gpsIntervalMs)
            }
        }
        handler.post(gpsRunnable)
    }
    private fun fetchCommands() {
        val json = JSONObject().apply { put("driver_id", driverId) }.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url("https://такси-люкс.рф/get_commands.php")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                connectionErrorCount++
                if (connectionErrorCount >= 3) {
                    updateNotification("⚠️ Нет связи с сервером")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    connectionErrorCount = 0
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        try {
                            val jsonResp = JSONObject(responseBody)
                            if (jsonResp.optString("status") == "ok") {
                                val newMode = jsonResp.optString("mode", "offline")
                                val sounds = jsonResp.optJSONArray("sounds")
                                
                                // Обновляем интервалы
                                commandIntervalMs = jsonResp.optInt("command_interval", 60) * 1000L
                                gpsIntervalMs = jsonResp.optInt("gps_interval", 120) * 1000L

                                if (newMode != currentMode) {
                                    currentMode = newMode
                                    val statusText = if (currentMode == "online") "🔵 Онлайн" else "🟡 Офлайн"
                                    updateNotification(statusText)
                                }

                                // Воспроизведение звуков
                                if (sounds != null && sounds.length() > 0) {
                                    for (i in 0 until sounds.length()) {
                                        val soundId = sounds.optInt(i)
                                        playSound(soundId)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Игнорируем ошибки парсинга, продолжаем работу                        }
                    }
                }
            }
        })
    }

    private fun requestSingleLocationUpdate() {
        val priority = if (currentMode == "online") Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val locationRequest = LocationRequest.Builder(priority, 10000L) // 10 сек таймаут на получение
            .setMinUpdateIntervalMillis(5000L)
            .setMaxUpdates(1) // Получаем только одну точку за вызов
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
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
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date())
        
        var batteryPct = 0
        try {
            val batteryIntent = this.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100 / scale)
            }
        } catch (e: Exception) {
            batteryPct = 0
        }

        val json = JSONObject().apply {
            put("driver_id", driverId)
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy.toDouble())            put("battery", batteryPct)
            put("mode", currentMode)
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
            val jsonStr = iterator.next()
            if (isNetworkAvailable()) {
                sendToServer(jsonStr)
                iterator.remove()
            } else {
                break            }
        }
        savePendingToPrefs()
    }

    private fun playSound(soundNumber: Int) {
        try {
            val resId = if (soundNumber == 1) R.raw.sound1 else R.raw.sound2
            
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            
            val uri = android.net.Uri.parse("android.resource://$packageName/$resId")
            mediaPlayer?.setDataSource(this, uri)
            mediaPlayer?.prepare()
            mediaPlayer?.start()
        } catch (e: Exception) {
            // Игнорируем любые ошибки воспроизведения, чтобы сервис продолжал работать
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setOnCompletionListener { reset() }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Такси Трекер", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Минимальное уведомление для работы GPS"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Такси Шаран")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, createNotification(text))
    }

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
        handler.removeCallbacks(commandRunnable)
        handler.removeCallbacks(gpsRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mediaPlayer?.release()
        mediaPlayer = null
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
