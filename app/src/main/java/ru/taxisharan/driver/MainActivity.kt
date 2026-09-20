package ru.taxisharan.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etDriverId: EditText
    private lateinit var cbConsent: CheckBox
    private lateinit var btnAction: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPoints: TextView

    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDriverId = findViewById(R.id.etDriverId)
        cbConsent = findViewById(R.id.cbConsent)
        btnAction = findViewById(R.id.btnAction)
        tvStatus = findViewById(R.id.tvStatus)
        tvPoints = findViewById(R.id.tvPoints)

        val prefs = getSharedPreferences("TaxiPrefs", MODE_PRIVATE)
        val savedId = prefs.getString("driver_id", "")
        if (!savedId.isNullOrBlank()) {
            etDriverId.setText(savedId)
        }

        isTracking = GpsTrackingService.isRunning
        updateUI()

        findViewById<TextView>(R.id.tvRegisterLink).setOnClickListener {
            Toast.makeText(this, "Откройте приложение MAX, найдите бота 'Диспетчер' и напишите 'Хочу работать'", Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.tvConsentLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://такси-люкс.рф/driver_agreement.html")))
        }

        btnAction.setOnClickListener {
            if (!isTracking) {
                startTracking()
            } else {
                pauseTracking()
            }
        }
    }

    private fun startTracking() {
        val driverId = etDriverId.text.toString().trim()
        if (driverId.isBlank()) {
            Toast.makeText(this, "Введите ID водителя", Toast.LENGTH_SHORT).show()
            return
        }
        if (!cbConsent.isChecked) {
            Toast.makeText(this, "Необходимо согласие на обработку данных", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("TaxiPrefs", MODE_PRIVATE).edit()
            .putString("driver_id", driverId)
            .putBoolean("is_active", true)
            .apply()

        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra("driver_id", driverId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isTracking = true
        updateUI()
    }

    private fun pauseTracking() {
        val intent = Intent(this, GpsTrackingService::class.java).apply {            action = GpsTrackingService.ACTION_PAUSE
        }
        startService(intent)
        
        isTracking = false
        updateUI()
    }

    private fun updateUI() {
        if (isTracking) {
            btnAction.text = "⏸ ПАУЗА"
            tvStatus.text = "🔵 Активно"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        } else {
            btnAction.text = "▶ ПРОДОЛЖИТЬ"
            tvStatus.text = "🟡 Пассивно (60 сек)"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        }
        
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
        updateUI()
    }
}
