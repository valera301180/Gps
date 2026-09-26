package ru.taxisharan.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
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
        private const val UPDATE_CHANNEL_ID = "update_channel"
        private const val UPDATE_NOTIF_ID = 102
    }

    // ✅ ИСПРАВЛЕНО: Четкое объявление переменных на уровне класса
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var driverId: String = ""
    private val client = OkHttpClient()
    
    // Динамические интервалы (по умолчанию, как в ТЗ)
    private var commandIntervalMs = 60000L  // 60 сек
    private var gpsIntervalMs = 120000L     // 120 сек
    
    private var currentMode = "offline"
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateChecked = false

    override fun onCreate() {
        super.onCreate()
        // ✅ Инициализация клиента локаций
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        createUpdateChannel()
        isRunning = true
        mediaPlayer = MediaPlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            driverId = intent.getStringExtra("driver_id") ?: "UNKNOWN"
            startForeground(NOTIF_ID, createNotification("🟡 Офлайн"))
            startLoops()
        }
        return START_STICKY
    }

    private fun startLoops() {
        // Цикл 1: Команды и проверка обновлений
        handler.post(object : Runnable {
            override fun run() {
                try {
                    fetchCommands()
                    checkForUpdate()
                } catch (e: Exception) {
                    // Игнорируем ошибки, чтобы цикл не прервался
                }
                handler.postDelayed(this, commandIntervalMs)
            }
        })

        // Цикл 2: GPS (независимо от команд)
        handler.post(object : Runnable {
            override fun run() {
                try {
                    requestLocation()
                } catch (e: Exception) {
                    // Игнорируем ошибки
                }
                handler.postDelayed(this, gpsIntervalMs)
            }
        })
    }

    private fun fetchCommands() {
        val json = JSONObject().put("driver_id", driverId).toString()
        val request = Request.Builder()
            .url("https://xn----7sbyhcf3beb0k.xn--p1ai/get_commands.php")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // Ошибка сети — просто пропускаем
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    try {
                        val resp = JSONObject(body)
                        if (resp.optString("status") == "ok") {
                            // 1. Обновляем режим
                            val newMode = resp.optString("mode", "offline")
                            if (newMode != currentMode) {
                                currentMode = newMode
                                val text = if (currentMode == "online") "🔵 Онлайн" else "🟡 Офлайн"
                                getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, createNotification(text))
                            }

                            // 2. Обновляем интервалы (из ТЗ v12.0)
                            commandIntervalMs = resp.optInt("command_interval", 60) * 1000L
                            gpsIntervalMs = resp.optInt("gps_interval", 120) * 1000L

                            // 3. Проигрываем звуки, если сервер прислал команду
                            val sounds = resp.optJSONArray("sounds")
                            if (sounds != null) {
                                for (i in 0 until sounds.length()) {
                                    playSound(sounds.optInt(i))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ошибка парсинга JSON
                    }
                }
            }
        })
    }

    private fun checkForUpdate() {
        if (updateChecked) return

        try {
            val request = Request.Builder()
                .url("https://xn----7sbyhcf3beb0k.xn--p1ai/version.json")
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string())
                            val serverVersion = json.optInt("version_code", 0)
                            val currentVersion = applicationContext.packageManager
                                .getPackageInfo(applicationContext.packageName, 0).versionCode

                            if (serverVersion > currentVersion) {
                                updateChecked = true
                                showUpdateNotification()
                            }
                        } catch (e: Exception) {}
                    }
                }
            })
        } catch (e: Exception) {}
    }

    private fun showUpdateNotification() {
        val intent = Intent(this, UpdateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("🔄 Вышло обновление!")
            .setContentText("Нажмите, чтобы скачать новую версию")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(UPDATE_NOTIF_ID, notification)
    }

    private fun playSound(id: Int) {
        try {
            val resId = if (id == 1) R.raw.sound1 else R.raw.sound2
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(this, Uri.parse("android.resource://$packageName/$resId"))
            mediaPlayer?.prepare()
            mediaPlayer?.start()
        } catch (e: Exception) {
            // Ошибка воспроизведения
        }
    }

    private fun requestLocation() {
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY, null
            ).addOnSuccessListener { loc ->
                if (loc != null) sendLocation(loc)
            }
        } catch (e: Exception) {
            // Ошибка запроса локации
        }
    }

    private fun sendLocation(loc: Location) {
        val json = JSONObject().apply {
            put("driver_id", driverId)
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("accuracy", loc.accuracy.toDouble())
            put("mode", currentMode)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date()))
        }

        val request = Request.Builder()
            .url("https://xn----7sbyhcf3beb0k.xn--p1ai/update_gps.php")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    try {
                        val prefs = getSharedPreferences("TaxiPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putInt("points_sent", prefs.getInt("points_sent", 0) + 1).apply()
                    } catch (e: Exception) {}
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Такси Трекер", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createUpdateChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Такси Шаран")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
