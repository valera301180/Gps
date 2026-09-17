package com.taxisharan.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "🔄 Телефон включился, проверяем настройки...")

            // Проверяем, сохранены ли настройки
            val prefs = context.getSharedPreferences("tracker_prefs", Context.MODE_PRIVATE)
            val driverId = prefs.getString("driver_id", "")
            val apiKey = prefs.getString("api_key", "")

            if (!driverId.isNullOrEmpty() && !apiKey.isNullOrEmpty()) {
                Log.d("BootReceiver", "✅ Настройки найдены, запускаем службу...")

                // Запускаем службу геолокации
                val serviceIntent = Intent(context, GeoUploadService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d("BootReceiver", "🚀 Служба запущена!")
            } else {
                Log.d("BootReceiver", "❌ Настройки не найдены, служба не запущена")
            }
        }
    }
}
