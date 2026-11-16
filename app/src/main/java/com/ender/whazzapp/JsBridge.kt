package com.ender.whazzapp

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * Sayfadaki JS'in çağırdığı köprü (mobile.js içindeki window.WhazzAndroid).
 * Her hesabın WebView'una kendi örneği eklenir; accountId böylece bilinir.
 * @JavascriptInterface metotları WebView'un arka plan iş parçacığında çalışır.
 */
class JsBridge(private val app: Context, private val accountId: String) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun notify(id: Int, title: String, body: String, tag: String) {
        Notifications.showMessage(accountId, id, title, body, tag)
    }

    @JavascriptInterface
    fun closeNotification(id: Int) {
        Notifications.closeMessage(accountId, id)
    }

    /** Sohbet açıldı/kapandı: hesap şeridini gizlemek/göstermek için. */
    @JavascriptInterface
    fun onChatMode(open: Boolean) {
        main.post { SessionManager.onChatMode(accountId, open) }
    }

    /** blob: URL'lerden gelen indirmeleri Downloads klasörüne yazar. */
    @JavascriptInterface
    fun saveFile(fileName: String, mimeType: String, base64: String) {
        val name = fileName.ifBlank { "whatsapp_${System.currentTimeMillis()}" }
        val ok = try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
        main.post {
            val msg = if (ok) app.getString(R.string.download_done, name) else app.getString(R.string.download_failed)
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
