package com.ender.whazzapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference

/** WebView'un Activity gerektiren istekleri (dosya seçici, izinler) ve şerit güncellemesi için. */
interface SessionHost {
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun requestMediaPermission(request: PermissionRequest)
    fun onChatModeChanged(accountId: String, open: Boolean)
    fun onSessionsStopped()
}

/**
 * Her hesap için bir WebView. WebView'lar Activity'den bağımsız, uygulama ömrü boyunca yaşar
 * (KeepAliveService süreci ayakta tutar). Activity görünürken seçili hesabın WebView'u onun
 * container'ına takılır; Activity kapanınca çıkarılır ama yok edilmez, böylece WhatsApp Web
 * bağlı kalır ve bildirimler gelmeye devam eder.
 */
@SuppressLint("StaticFieldLeak") // Yalnızca applicationContext tutulur; Activity bağlamı detach'te geri alınır.
object SessionManager {
    private const val TAG = "WhazzApp"
    private const val HOME_URL = "https://web.whatsapp.com/"
    private val ALLOWED_HOSTS = listOf("web.whatsapp.com", "whatsapp.com", "whatsapp.net")

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, WebView>()
    private val chatOpen = HashMap<String, Boolean>()
    private val documentStartInjected = HashSet<String>()

    private var host: WeakReference<SessionHost>? = null
    private var container: FrameLayout? = null
    var attachedId: String? = null
        private set

    val multiProfileSupported: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun webView(accountId: String?): WebView? = sessions[accountId]

    fun isChatOpen(accountId: String) = chatOpen[accountId] == true

    fun sessionCount() = sessions.size

    /** Tüm hesapların WebView'larını oluşturup yükler (servis çağırır). */
    fun ensureAll() {
        AccountStore.all().forEach { getOrCreate(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(account: Account): WebView {
        sessions[account.id]?.let { return it }

        val wv = WebView(MutableContextWrapper(app))
        // Profil, WebView'a başka hiçbir şey yapılmadan önce atanmalı.
        if (multiProfileSupported) {
            ProfileStore.getInstance().getOrCreateProfile(account.profile)
            WebViewCompat.setProfile(wv, account.profile)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookies = if (multiProfileSupported) WebViewCompat.getProfile(wv).cookieManager else CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(wv, true)

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            useWideViewPort = false      // Sayfa telefon genişliğinde (device-width) düzenlensin
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            setSupportMultipleWindows(false) // target=_blank linkler shouldOverrideUrlLoading'e düşer
            userAgentString = desktopUserAgent()
        }
        applyDesktopClientHints(wv)
        // Görünmezken de renderer'ın önceliği düşürülmesin (arka plan hesapları için).
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        wv.addJavascriptInterface(JsBridge(app, account.id), "WhazzAndroid")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, buildInjectionScript(), setOf("https://web.whatsapp.com"))
            documentStartInjected += account.id
        }

        wv.webViewClient = Client(account.id)
        wv.webChromeClient = ChromeClient()
        sessions[account.id] = wv
        // Ekrana hiç takılmamış WebView kendini "visible" bildirir; WhatsApp görünür sayfada bildirim
        // göstermeyebilir. attach() çağrılana kadar gizli (paused) başlat.
        wv.onPause()
        wv.loadUrl(HOME_URL)
        Log.i(TAG, "session created: ${account.id} (${account.profile})")
        return wv
    }

    /** Seçili hesabın WebView'unu Activity'nin container'ına takar. */
    fun attach(activity: SessionHost, activityContext: Context, target: FrameLayout, accountId: String): WebView? {
        val account = AccountStore.get(accountId) ?: return null
        host = WeakReference(activity)
        container = target
        val wv = getOrCreate(account)
        if (attachedId == accountId && wv.parent === target) return wv

        for (i in target.childCount - 1 downTo 0) {
            val child = target.getChildAt(i)
            if (child is WebView) parkInBackground(child)
        }
        target.removeAllViews()
        (wv.parent as? ViewGroup)?.removeView(wv)
        setBaseContext(wv, activityContext)
        target.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        wv.onResume()
        attachedId = accountId
        wv.requestFocus()
        return wv
    }

    /** Activity yok edilirken: WebView'u çıkar, Activity bağlamını bırak, ama oturumu yaşat. */
    fun detach(activity: SessionHost) {
        if (host?.get() !== activity) return
        container?.let { c ->
            for (i in c.childCount - 1 downTo 0) {
                val child = c.getChildAt(i)
                if (child is WebView) parkInBackground(child)
            }
            c.removeAllViews()
        }
        container = null
        host = null
        attachedId = null
    }

    /** Hesap kaldırılırken: WebView'u yok et ve profil verisini sil. */
    fun destroy(accountId: String, deleteProfile: Boolean) {
        val wv = sessions.remove(accountId)
        chatOpen.remove(accountId)
        documentStartInjected.remove(accountId)
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        if (attachedId == accountId) attachedId = null
        Notifications.cancelAll(accountId)
        if (deleteProfile && multiProfileSupported) {
            val profile = Account(accountId, "").profile
            // Profil yalnızca hiçbir WebView kullanmıyorken silinebilir; destroy sonrası bir tur bekle.
            main.post {
                try {
                    ProfileStore.getInstance().deleteProfile(profile)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteProfile($profile) failed", e)
                }
            }
        }
    }

    /** "Durdur": tüm oturumları kapat. */
    fun destroyAll() {
        sessions.keys.toList().forEach { destroy(it, deleteProfile = false) }
        host?.get()?.onSessionsStopped()
    }

    fun onChatMode(accountId: String, open: Boolean) {
        chatOpen[accountId] = open
        host?.get()?.onChatModeChanged(accountId, open)
    }

    /** Ekrandan çıkan WebView: Activity bağlamını bırak, sayfayı "hidden" yap (JS ve bağlantı sürer). */
    private fun parkInBackground(wv: WebView) {
        setBaseContext(wv, app)
        wv.onPause()
    }

    private fun setBaseContext(wv: WebView, context: Context) {
        (wv.context as? MutableContextWrapper)?.baseContext = context
    }

    // ---- User agent ------------------------------------------------------------------------

    /** WhatsApp Web mobil tarayıcıları reddeder; masaüstü Chrome gibi görünürüz.
     *  Chrome sürümünü cihazdaki WebView'dan alırız ki UA güncel kalsın. */
    private fun desktopUserAgent(): String {
        val default = WebSettings.getDefaultUserAgent(app)
        val major = Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    /** navigator.userAgentData / Sec-CH-UA başlıkları da "mobil değil" desin. */
    private fun applyDesktopClientHints(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val major = Regex("Chrome/(\\d+)").find(wv.settings.userAgentString)?.groupValues?.get(1) ?: "130"
        fun brand(name: String, ver: String) = UserAgentMetadata.BrandVersion.Builder()
            .setBrand(name).setMajorVersion(ver).setFullVersion("$ver.0.0.0").build()
        val metadata = UserAgentMetadata.Builder()
            .setMobile(false)
            .setPlatform("Linux")
            .setPlatformVersion("")
            .setModel("")
            .setBrandVersionList(
                listOf(
                    brand("Chromium", major),
                    brand("Google Chrome", major),
                    brand("Not?A_Brand", "99")
                )
            )
            .build()
        WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
    }

    // ---- Script injection ------------------------------------------------------------------

    private val injectionScript: String by lazy {
        fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }
        "window.__WHAZZ_CSS = ${JSONObject.quote(asset("mobile.css"))};\n" + asset("mobile.js")
    }

    private fun buildInjectionScript() = injectionScript

    // ---- Clients ---------------------------------------------------------------------------

    private class Client(private val accountId: String) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "blob" || uri.scheme == "data") return false
            val h = uri.host ?: return false
            if (ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }) return false
            try {
                view.context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            // addDocumentStartJavaScript desteklenmiyorsa (çok eski WebView) yedek yol
            if (accountId !in documentStartInjected) view.evaluateJavascript(buildInjectionScript(), null)
        }

        /** Renderer çöktü/öldürüldü: true dönmezsek uygulama da çöker. WebView'u yeniden kur. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone for $accountId (crash=${detail.didCrash()})")
            main.post { recreate(accountId, view) }
            return true
        }
    }

    private fun recreate(accountId: String, dead: WebView) {
        if (sessions[accountId] !== dead) return
        val wasAttached = attachedId == accountId
        val target = container
        sessions.remove(accountId)
        chatOpen.remove(accountId)
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.destroy()
        val account = AccountStore.get(accountId) ?: return
        getOrCreate(account)
        val h = host?.get()
        if (wasAttached && target != null && h != null) {
            attachedId = null
            attach(h, target.context, target, accountId)
        }
        h?.onChatModeChanged(accountId, false)
    }

    private class ChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            val h = host?.get() ?: return false
            return h.showFileChooser(callback, params)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post {
                val h = host?.get()
                if (h == null) request.deny() else h.requestMediaPermission(request)
            }
        }
    }
}
