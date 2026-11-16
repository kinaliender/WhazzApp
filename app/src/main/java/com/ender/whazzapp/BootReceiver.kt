package com.ender.whazzapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Telefon açılınca ve uygulama güncellenince arka plan servisini başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!AccountStore.backgroundEnabled) return
                try {
                    KeepAliveService.start(context)
                } catch (e: Exception) {
                    Log.w("WhazzApp", "could not start service from ${intent.action}", e)
                }
            }
        }
    }
}
