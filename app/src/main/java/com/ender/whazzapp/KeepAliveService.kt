package com.ender.whazzapp

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Ön plan servisi: süreci ayakta tutar, böylece SessionManager'daki WebView'lar (WhatsApp Web
 * oturumları) uygulama kapalıyken de bağlı kalır ve bildirimler gelir. Süreç yine de öldürülürse
 * START_STICKY ile yeniden başlar ve tüm hesapları yeniden yükler.
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_STOP = "com.ender.whazzapp.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }

        /** Hesap sayısı değişince kalıcı bildirimi güncellemek için. */
        fun refresh(context: Context) = start(context)
    }

    private val accountsListener: () -> Unit = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        AccountStore.addListener(accountsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i("WhazzApp", "stop requested")
            AccountStore.backgroundEnabled = false
            SessionManager.destroyAll()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        SessionManager.ensureAll()
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        AccountStore.removeListener(accountsListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        androidx.core.app.NotificationManagerCompat.from(this).let {
            try {
                it.notify(Notifications.SERVICE_NOTIFICATION_ID, buildNotification())
            } catch (_: SecurityException) {
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_whazzapp)
            .setColor(getColor(R.color.wa_green))
            .setContentTitle(getString(R.string.service_title))
            .setContentText(AccountStore.all().size.let { resources.getQuantityString(R.plurals.service_text, it, it) })
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.service_stop), stop)
            .build()
    }
}
