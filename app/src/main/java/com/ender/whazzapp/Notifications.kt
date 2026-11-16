package com.ender.whazzapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bildirim kanalları ve mesaj bildirimleri.
 * Her hesabın kendi kanalı var (msg_<id>): ses/titreşim hesap başına sistem ayarlarından seçilebilir.
 */
object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val SERVICE_NOTIFICATION_ID = 1

    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_NOTIF_ID = "notif_id"

    private lateinit var app: Context
    private val nextId = AtomicInteger(1000)
    private val requestCodes = AtomicInteger(1)

    /** JS bildirim id'si → Android (tag, id); WhatsApp notification.close() çağırınca kaldırmak için. */
    private val shown = HashMap<String, Pair<String, Int>>()

    fun init(context: Context) {
        app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("messages") // 0.1.0'daki tek kanal
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, app.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        AccountStore.all().forEach { ensureChannel(it) }
    }

    private fun channelId(accountId: String) = "msg_$accountId"

    fun ensureChannel(account: Account) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId(account.id),
                app.getString(R.string.channel_messages, account.name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    fun deleteChannel(accountId: String) {
        app.getSystemService(NotificationManager::class.java).deleteNotificationChannel(channelId(accountId))
    }

    /** WhatsApp sayfasındaki `new Notification(...)` çağrısının Android karşılığı. */
    fun showMessage(accountId: String, jsId: Int, title: String, body: String, jsTag: String) {
        val nmc = NotificationManagerCompat.from(app)
        if (!nmc.areNotificationsEnabled()) return
        val account = AccountStore.get(accountId) ?: return
        ensureChannel(account)

        // Tarayıcıdaki gibi: aynı tag (aynı sohbet) yeni bildirimle yer değiştirir.
        val tag = if (jsTag.isNotBlank()) "$accountId:$jsTag" else accountId
        val id = if (jsTag.isNotBlank()) 0 else nextId.incrementAndGet()

        val open = PendingIntent.getActivity(
            app, requestCodes.incrementAndGet(),
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ACCOUNT, accountId)
                .putExtra(EXTRA_NOTIF_ID, jsId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val multi = AccountStore.all().size > 1
        val n = NotificationCompat.Builder(app, channelId(accountId))
            .setSmallIcon(R.drawable.ic_stat_whazzapp)
            .setColor(app.getColor(R.color.wa_green))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(if (multi) account.name else null)
            .setGroup(accountId)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nmc.notify(tag, id, n)
            synchronized(shown) { shown["$accountId/$jsId"] = tag to id }
        } catch (_: SecurityException) {
        }
    }

    fun closeMessage(accountId: String, jsId: Int) {
        val entry = synchronized(shown) { shown.remove("$accountId/$jsId") } ?: return
        NotificationManagerCompat.from(app).cancel(entry.first, entry.second)
    }

    fun cancelAll(accountId: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.tag == accountId || it.tag?.startsWith("$accountId:") == true }
            .forEach { nm.cancel(it.tag, it.id) }
        synchronized(shown) { shown.keys.removeAll { it.startsWith("$accountId/") } }
    }
}
