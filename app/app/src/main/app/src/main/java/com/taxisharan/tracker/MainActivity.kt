package com.taxisharan.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etDriverId: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: Button
    private lateinit var btnStartService: Button

    companion object {
        const val LOCATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDriverId = findViewById(R.id.et_driver_id)
        etApiKey = findViewById(R.id.et_api_key)
        btnSave = findViewById(R.id.btn_save)
        btnStartService = findViewById(R.id.btn_start_service)

        // Загружаем сохранённые данные
        val prefs = getSharedPreferences("tracker_prefs", MODE_PRIVATE)
        etDriverId.setText(prefs.getString("driver_id", ""))
        etApiKey.setText(prefs.getString("api_key", ""))

        // Кнопка сохранения
        btnSave.setOnClickListener {
            val driverId = etDriverId.text.toString().trim()
            val apiKey = etApiKey.text.toString().trim()

            if (driverId.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(this, "Заполните оба поля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Сохраняем            prefs.edit().putString("driver_id", driverId).apply()
            prefs.edit().putString("api_key", apiKey).apply()

            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()

            // Запрашиваем разрешения
            requestPermissions()
        }

        // Кнопка запуска службы
        btnStartService.setOnClickListener {
            val driverId = prefs.getString("driver_id", "")
            val apiKey = prefs.getString("api_key", "")

            if (driverId.isNullOrEmpty() || apiKey.isNullOrEmpty()) {
                Toast.makeText(this, "Сначала сохраните настройки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Запускаем службу
            val serviceIntent = Intent(this, GeoUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "Служба запущена! GPS отправляется.", Toast.LENGTH_LONG).show()
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.plus(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(                this,
                permissionsToRequest.toTypedArray(),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Все разрешения получены!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Нужны разрешения для работы GPS", Toast.LENGTH_LONG).show()
            }
        }
    }
}
