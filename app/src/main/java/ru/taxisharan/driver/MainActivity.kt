package ru.taxisharan.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var etPhone: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvRegisterStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDriverId: TextView
    private lateinit var tvPoints: TextView

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutRegistration = findViewById(R.id.layoutRegistration)
        layoutTracking = findViewById(R.id.layoutTracking)
        etPhone = findViewById(R.id.etPhone)
        btnRegister = findViewById(R.id.btnRegister)
        tvRegisterStatus = findViewById(R.id.tvRegisterStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvDriverId = findViewById(R.id.tvDriverId)
        tvPoints = findViewById(R.id.tvPoints)

        val prefs = getSharedPreferences("TaxiPrefs", MODE_PRIVATE)
        val driverId = prefs.getString("driver_id", null)
        if (driverId.isNullOrBlank()) {
            showRegistration()
        } else {
            showTracking(driverId)
        }

        btnRegister.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.length < 10) {
                tvRegisterStatus.text = "Введите корректный номер телефона"
                return@setOnClickListener
            }
            registerDriver(phone)
        }
    }

    private fun showRegistration() {
        layoutRegistration.visibility = View.VISIBLE
        layoutTracking.visibility = View.GONE
    }

    private fun showTracking(driverId: String) {
        layoutRegistration.visibility = View.GONE
        layoutTracking.visibility = View.VISIBLE
        tvDriverId.text = driverId
        updatePointsCounter()
        
        if (!GpsTrackingService.isRunning) {
            if (checkPermissions()) {
                startTrackingService(driverId)
            } else {
                requestPermissions()
            }
        }
    }

    private fun registerDriver(phone: String) {
        tvRegisterStatus.text = "Регистрация..."
        tvRegisterStatus.setTextColor(getColor(android.R.color.holo_blue_dark))
        btnRegister.isEnabled = false

        val json = JSONObject().apply { put("phone", phone) }.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url("https://такси-люкс.рф/register_driver.php")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    tvRegisterStatus.text = "Ошибка сети. Проверьте интернет."
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
                                
                                if (checkPermissions()) {
                                    startTrackingService(newDriverId)
                                } else {
                                    requestPermissions()
                                }
                                showTracking(newDriverId)
                            } else {
                                tvRegisterStatus.text = jsonResp.optString("message", "Ошибка регистрации")
                                tvRegisterStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                                btnRegister.isEnabled = true
                            }
                        } catch (e: Exception) {
                            tvRegisterStatus.text = "Ошибка обработки ответа"
                            btnRegister.isEnabled = true
                        }
                    } else {
                        tvRegisterStatus.text = "Ошибка сервера"
                        btnRegister.isEnabled = true
                    }
                }
            }
        })
    }

    private fun startTrackingService(driverId: String) {
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra("driver_id", driverId)        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updatePointsCounter() {
        val points = getSharedPreferences("TaxiPrefs", MODE_PRIVATE).getInt("points_sent", 0)
        tvPoints.text = "Отправлено точек: $points"
    }

    private fun checkPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return fineLocation && bgLocation && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onResume() {
        super.onResume()
        updatePointsCounter()
        val driverId = getSharedPreferences("TaxiPrefs", MODE_PRIVATE).getString("driver_id", null)
        if (!driverId.isNullOrBlank() && !GpsTrackingService.isRunning && checkPermissions()) {
            startTrackingService(driverId)
        }
    }
}
