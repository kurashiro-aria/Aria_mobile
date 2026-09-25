package com.kura.aria

import android.app.Application
import android.content.Intent
import android.os.Build

/** Starts Background ARIA V1 once the app process is created. */
class AriaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val service = Intent(this, AriaForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
        else startService(service)
    }
}
