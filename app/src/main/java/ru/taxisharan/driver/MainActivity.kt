package ru.taxisharan.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var layoutRegistration: LinearLayout
    private lateinit var layoutTracking: LinearLayout
    private lateinit var layoutSetup: LinearLayout
    private lateinit var etPhone: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvRegisterStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDriverId: TextView
    private lateinit var tvPoints: TextView
    private lateinit var tvLastGps: TextView
    private lateinit var tvLog: TextView

    // Кнопки для настроек
    private lateinit var btnLocation: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button
    private lateinit var btnAllSettings: Button

    private val client = OkHttpClient()
    private val BASE_URL = "http://такси-люкс.рф/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация элементов
        layoutRegistration = findViewById(R.id.layoutRegistration)
        layoutTracking = findViewById(R.id.layoutTracking)
        layoutSetup = findViewById(R.id.layoutSetup)
        etPhone = findViewById(R.id.etPhone)
        btnRegister = findViewById(R.id.btnRegister)
        tvRegisterStatus = findViewById(R.id.tvRegisterStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvDriverId = findViewById(R.id.tvDriverId)
        tvPoints = findViewById(R.id.tvPoints)
        tvLastGps = findViewById(R.id.tvLastGps)
        tvLog = findViewById(R.id.tvLog)
        btnLocation = findViewById(R.id.btnLocation)
        btnNotifications = findViewById(R.id.btnNotifications)
        btnBattery = findViewById(R.id.btnBattery)
        btnAllSettings = findViewById(R.id.btnAllSettings)

        // Обработчики кнопок настроек
        btnLocation.setOnClickListener { openLocationSettings() }
        btnNotifications.setOnClickListener { openNotificationSettings() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnAllSettings.setOnClickListener { openAppSettings() }

        val prefs = getSharedPreferences("TaxiPrefs", MODE_PRIVATE)
        val driverId = prefs.getString("driver_id", null)
        
        if (driverId.isNullOrBlank()) {
            showRegistration()
        } else {
            showMainScreen(driverId)
        }

        btnRegister.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.length < 10) {
                tvRegisterStatus.text = "❌ Введите корректный номер телефона"
                return@setOnClickListener
            }
            registerDriver(phone)
        }
    }

    override fun onResume() {
        super.onResume()
        val driverId = getSharedPreferences("TaxiPrefs", MODE_PRIVATE).getString("driver_id", null)
        if (!driverId.isNullOrBlank()) {
            showMainScreen(driverId)
        }
    }

    private fun showRegistration() {
        layoutRegistration.visibility = View.VISIBLE
        layoutTracking.visibility = View.GONE
        layoutSetup.visibility = View.GONE
    }

    private fun showMainScreen(driverId: String) {
        layoutRegistration.visibility = View.GONE
        layoutTracking.visibility = View.VISIBLE
        layoutSetup.visibility = View.VISIBLE
        
        tvDriverId.text = "ID: $driverId"
        updateUI()
        
        // Запускаем сервис если все разрешения есть
        if (allPermissionsGranted()) {
            if (!GpsTrackingService.isRunning) {
                startTrackingService(driverId)
                addLog("✅ Сервис запущен")
            }
        } else {
            addLog("⚠️ Не все разрешения выданы")
        }
    }

    private fun updateUI() {
        val prefs = getSharedPreferences("TaxiPrefs", MODE_PRIVATE)
        val points = prefs.getInt("points_sent", 0)
        val lastLat = prefs.getFloat("last_lat", 0f)
        val lastLng = prefs.getFloat("last_lng", 0f)
        
        tvPoints.text = "📡 Отправлено точек: $points"
        
        if (lastLat != 0f && lastLng != 0f) {
            tvLastGps.text = "📍 Последние координаты: ${"%.5f".format(lastLat)}, ${"%.5f".format(lastLng)}"
        } else {
            tvLastGps.text = "📍 Координаты: ожидаем..."
        }

        // Обновляем статус сервиса
        if (GpsTrackingService.isRunning) {
            tvStatus.text = "🟢 Сервис работает"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvStatus.text = "🔴 Сервис остановлен"
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }

        // Обновляем кнопки настроек
        updateSetupButtons()
    }

    private fun updateSetupButtons() {
        val locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgLocationOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || 
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = isBatteryOptimizationDisabled()

        btnLocation.text = if (locationOk && bgLocationOk) "✅ Геолокация (всегда)" else "❌ Включить геолокацию"
        btnNotifications.text = if (notifOk) "✅ Уведомления" else "❌ Включить уведомления"
        btnBattery.text = if (batteryOk) "✅ Батарея (не ограничена)" else "❌ Отключить экономию батареи"
        
        val allOk = locationOk && bgLocationOk && notifOk && batteryOk
        btnAllSettings.text = if (allOk) "✅ Все настройки включены" else "⚙️ Открыть все настройки"
    }

    private fun allPermissionsGranted(): Boolean {
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || 
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return location && bgLocation && notif
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openLocationSettings() {
        // Сначала запрашиваем разрешение
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        } else {
            // На старых Android уведомления включаются автоматически
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Если не получилось, открываем общие настройки батареи
                val intent2 = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent2)
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun registerDriver(phone: String) {
        tvRegisterStatus.text = "⏳ Регистрация..."
        tvRegisterStatus.setTextColor(getColor(android.R.color.holo_blue_dark))
        btnRegister.isEnabled = false

        val json = JSONObject().apply { put("phone", phone) }.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url("${BASE_URL}register_driver.php")
            .post(body)
            .build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    tvRegisterStatus.text = "❌ Ошибка сети: ${e.message}"
                    tvRegisterStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    btnRegister.isEnabled = true
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val jsonResp = JSONObject(responseBody)
                            if (jsonResp.optString("status") == "ok") {
                                val newDriverId = jsonResp.optString("driver_id")
                                getSharedPreferences("TaxiPrefs", MODE_PRIVATE).edit()
                                    .putString("driver_id", newDriverId)
                                    .putBoolean("is_active", true)
                                    .apply()
                                
                                addLog("✅ Регистрация успешна: $newDriverId")
                                showMainScreen(newDriverId)
                            } else {
                                tvRegisterStatus.text = "❌ " + jsonResp.optString("message", "Ошибка")
                                tvRegisterStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                                btnRegister.isEnabled = true
                            }
                        } catch (e: Exception) {
                            tvRegisterStatus.text = "❌ Ошибка обработки: ${e.message}"
                            btnRegister.isEnabled = true
                        }
                    } else {
                        tvRegisterStatus.text = "❌ Ошибка сервера (код ${response.code})"
                        btnRegister.isEnabled = true
                    }
                }
            }
        })
    }

    private fun startTrackingService(driverId: String) {
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra("driver_id", driverId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val currentLog = tvLog.text.toString()
            val newLine = "[$time] $message\n"
            tvLog.text = newLine + currentLog.take(500) // Ограничиваем длину лога
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUI()
        
        // Если все разрешения получены, запускаем сервис
        if (allPermissionsGranted()) {
            val driverId = getSharedPreferences("TaxiPrefs", MODE_PRIVATE).getString("driver_id", null)
            if (!driverId.isNullOrBlank() && !GpsTrackingService.isRunning) {
                startTrackingService(driverId)
                addLog("✅ Все разрешения получены, сервис запущен")
            }
        }
    }
}
