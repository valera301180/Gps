package ru.taxisharan.driver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("TaxiPrefs", Context.MODE_PRIVATE)
            val wasActive = prefs.getBoolean("is_active", false)
            val driverId = prefs.getString("driver_id", "")
            
            if (wasActive && !driverId.isNullOrBlank()) {
                val serviceIntent = Intent(context, GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_START
                    putExtra("driver_id", driverId)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
